package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.PlayerSprite;
import forge.adventure.character.ResourceNodeSprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.MaterialData;
import forge.adventure.data.MaterialListData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.GameScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.gamemodes.net.coop.CoopInviteUiState;
import forge.gamemodes.net.coop.CoopLocationPolicy;
import forge.gamemodes.net.coop.CoopPartyState;
import forge.gamemodes.net.coop.CoopPausedEventQueue;
import forge.gamemodes.net.coop.CoopPendingGatherQueue;
import forge.gamemodes.net.coop.CoopPositionSync;
import forge.gamemodes.net.coop.CoopRateLimiter;
import forge.gamemodes.net.coop.CoopWireLimits;
import forge.gamemodes.net.coop.CoopWorldAuthority;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopEnemyEncounterRequestEvent;
import forge.gamemodes.net.event.coop.CoopEnemyStateEvent;
import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;
import forge.gamemodes.net.event.coop.CoopHostPresenceEvent;
import forge.gamemodes.net.event.coop.CoopLocationExitEvent;
import forge.gamemodes.net.event.coop.CoopLocationInviteEvent;
import forge.gamemodes.net.event.coop.CoopLocationResponseEvent;
import forge.gamemodes.net.event.coop.CoopNodeStateEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;
import forge.gamemodes.net.event.coop.CoopPoiChangeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CO2 shared-overworld runtime. Guest is a pure mirror of host enemies/nodes.
 * All GL / stage / texture work is posted to the GL thread.
 */
public final class CoopOverworldRuntime implements CoopHooks.OverworldListener {
    private static final CoopOverworldRuntime INSTANCE = new CoopOverworldRuntime();

    private volatile boolean attached;
    private volatile CoopPositionSync positionSync;
    private volatile CoopWorldAuthority authority;
    private final CoopPartyState party = new CoopPartyState();
    private final CoopLocationPolicy locationPolicy = new CoopLocationPolicy();
    private volatile CoopPartnerSprite partnerSprite;
    private volatile float partnerX = Float.NaN;
    private volatile float partnerY = Float.NaN;
    /** Local player position cached on the GL thread for {@link #partnersNearby()}. */
    private volatile float localPlayerX = Float.NaN;
    private volatile float localPlayerY = Float.NaN;
    private volatile long lastEnemyBroadcastMs;
    private final AtomicLong locationInviteSeq = new AtomicLong(1L);
    /** Guest-generated positive request ids (host echoes them). */
    private final AtomicLong gatherRequestSeq = new AtomicLong(1L);
    private final AtomicLong encounterRequestSeq = new AtomicLong(1L);
    private final Map<Long, ResourceNodeSprite> localNodesById = new ConcurrentHashMap<>();
    private final Map<Long, EnemySprite> localEnemiesById = new ConcurrentHashMap<>();
    private final Map<EnemySprite, Long> enemyIds = new ConcurrentHashMap<>();
    private final Map<ResourceNodeSprite, Long> nodeIds = new ConcurrentHashMap<>();
    /** Mirrored (guest) sprites — never written by {@link WorldStage#save()}. */
    private final Set<Object> mirroredActors = ConcurrentHashMap.newKeySet();
    /** Guest: multiple in-flight gather requests matched by request id. */
    private final CoopPendingGatherQueue pendingGathers = new CoopPendingGatherQueue();
    private volatile boolean hostWorldPaused;
    private volatile String hostPresenceLabel = "";
    private volatile CoopHostPresenceEvent.Presence localPresence = CoopHostPresenceEvent.Presence.OVERWORLD;
    /** Enemy ids the guest already requested this contact (cleared when separated). */
    private final Set<Long> encounterContactedIds = ConcurrentHashMap.newKeySet();
    /**
     * Host SPAWN + DESPAWN/CLAIMED events queued while world sim is paused;
     * flushed FIFO on resume so despawns never race ahead of their spawn.
     */
    private final CoopPausedEventQueue pausedWorldEvents = new CoopPausedEventQueue();
    /**
     * Inbound teleport samples from the peer (waypoint / portal / reset) that
     * were not pre-armed by a location-exit. Caps abuse of the teleport flag.
     */
    private final CoopRateLimiter teleportAcceptLimiter = new CoopRateLimiter(2, 10_000L);
    /** Headless + HUD invite prompt state (party / location Accept-Decline). */
    private final CoopInviteUiState inviteUi = new CoopInviteUiState();

    private CoopOverworldRuntime() {
    }

    public static CoopOverworldRuntime get() {
        return INSTANCE;
    }

    public CoopPartyState getParty() {
        return party;
    }

    public CoopLocationPolicy getLocationPolicy() {
        return locationPolicy;
    }

    public CoopWorldAuthority getAuthority() {
        return authority;
    }

    public boolean isHostWorldPaused() {
        return hostWorldPaused;
    }

    public String getHostPresenceLabel() {
        return hostPresenceLabel;
    }

    public CoopInviteUiState getInviteUi() {
        return inviteUi;
    }

    public CoopPendingGatherQueue getPendingGathers() {
        return pendingGathers;
    }

    public CoopPausedEventQueue getPausedWorldEvents() {
        return pausedWorldEvents;
    }

    public boolean isMirroredActor(final Object actor) {
        return actor != null && mirroredActors.contains(actor);
    }

    public long inviteTimeoutMs() {
        final int sec = Config.instance().getConfigData().coopLocationInviteTimeoutSeconds;
        return Math.max(1, sec) * 1000L;
    }

    public void onSessionReady() {
        if (!Config.ascendant()) {
            return;
        }
        ensureAttached();
        final ConfigData cfg = Config.instance().getConfigData();
        positionSync = new CoopPositionSync(
                Math.max(1, cfg.coopPositionMaxPerSecond),
                cfg.coopPositionSendHz > 0f ? cfg.coopPositionSendHz : 15f,
                cfg.coopMaxMoveSpeedPx > 0f ? cfg.coopMaxMoveSpeedPx : CoopWireLimits.DEFAULT_MAX_MOVE_SPEED_PX);
        authority = new CoopWorldAuthority(
                cfg.coopInteractRangePx > 0f ? cfg.coopInteractRangePx : CoopWireLimits.DEFAULT_INTERACT_RANGE_PX,
                Math.max(1, cfg.coopRequestMaxPerSecond),
                1000L);
        party.clearParty();
        locationPolicy.reset();
        pendingGathers.clear();
        inviteUi.hide();
        hostWorldPaused = false;
        hostPresenceLabel = "";
        localPresence = CoopHostPresenceEvent.Presence.OVERWORLD;
        encounterContactedIds.clear();
        teleportAcceptLimiter.reset();
        pausedWorldEvents.clear();
        postGl(() -> {
            ensurePartnerSprite();
            if (!CoopHooks.isWorldAuthority()) {
                WorldStage.getInstance().coopStashAndClearLocalEnemies();
                WorldStage.getInstance().coopStashAndClearLocalNodes();
            }
            if (CoopHooks.isWorldAuthority()) {
                sendReadySnapshot();
            }
            GameHUD.getInstance().onCoopSessionReady();
        });
        notifyHud(capHud("Co-op overworld ready"));
    }

    public void onSessionEnded(final String reason) {
        final boolean removeEntities = CoopWorldAuthority.shouldRemoveEntitiesOnDisconnect(
                CoopHooks.isWorldAuthority());
        party.clearParty();
        locationPolicy.reset();
        pendingGathers.clear();
        inviteUi.hide();
        hostWorldPaused = false;
        hostPresenceLabel = "";
        encounterContactedIds.clear();
        teleportAcceptLimiter.reset();
        pausedWorldEvents.clear();
        if (authority != null) {
            authority.clear();
        }
        if (positionSync != null) {
            positionSync.clear();
        }

        final Map<Long, ResourceNodeSprite> nodesCopy;
        final Map<Long, EnemySprite> enemiesCopy;
        if (removeEntities) {
            // Guest: remove mirrored sprites from the stage.
            nodesCopy = new ConcurrentHashMap<>(localNodesById);
            enemiesCopy = new ConcurrentHashMap<>(localEnemiesById);
        } else {
            // Host: real entities stay in the world — only clear id maps.
            nodesCopy = Map.of();
            enemiesCopy = Map.of();
        }
        localNodesById.clear();
        localEnemiesById.clear();
        enemyIds.clear();
        nodeIds.clear();
        mirroredActors.clear();
        partnerX = Float.NaN;
        partnerY = Float.NaN;
        localPlayerX = Float.NaN;
        localPlayerY = Float.NaN;
        final boolean restoreGuestLocals = removeEntities;
        postGl(() -> {
            removePartnerSprite();
            for (final ResourceNodeSprite n : nodesCopy.values()) {
                if (n != null) {
                    WorldStage.getInstance().coopRemoveRemoteNode(n);
                }
            }
            for (final EnemySprite e : enemiesCopy.values()) {
                if (e != null) {
                    WorldStage.getInstance().coopRemoveRemoteEnemy(e);
                }
            }
            if (restoreGuestLocals) {
                WorldStage.getInstance().coopRestoreStashedEnemies();
                WorldStage.getInstance().coopRestoreStashedNodes();
            }
            clearHostBanner();
            GameHUD.getInstance().onCoopSessionEnded();
        });
    }

    /** Called from {@link WorldStage#clearCache()} so id maps cannot go stale. */
    public void clearEntityIdMaps() {
        localNodesById.clear();
        localEnemiesById.clear();
        enemyIds.clear();
        nodeIds.clear();
        mirroredActors.clear();
        encounterContactedIds.clear();
        pendingGathers.clear();
        pausedWorldEvents.clear();
    }

    private void ensureAttached() {
        if (attached) {
            return;
        }
        CoopSession.get().addOverworldListener(this);
        attached = true;
    }

    public void tick(final float delta) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        final long now = System.currentTimeMillis();
        final long timeout = inviteTimeoutMs();
        final CoopInviteUiState.ExpiryResult expiry =
                inviteUi.tickExpiry(party, locationPolicy, now, timeout);
        if (expiry.dialogClosed) {
            postGl(() -> GameHUD.getInstance().hideCoopInviteDialog());
        }
        if (expiry.partyExpired) {
            notifyHud(capHud("Party invite expired"));
        }
        if (expiry.locationExpired) {
            notifyHud(capHud("Location invite expired"));
        }
        final ConfigData cfg = Config.instance().getConfigData();
        cacheLocalPlayerPosition();
        sendLocalPosition();
        updateEncounterSeparation();
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
        final CoopPartnerSprite sprite = partnerSprite;
        if (sprite != null) {
            sprite.interpolate(delta, cfg.coopPartnerInterpRate > 0f ? cfg.coopPartnerInterpRate : 12f);
        }
        if (CoopHooks.isWorldAuthority() && !hostWorldPaused) {
            maybeBroadcastEnemies(cfg);
        }
    }

    public void backgroundTick(final float delta) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        // Never advance world sim during a duel — would despawn the fought mob.
        if (Forge.getCurrentScene() instanceof DuelScene) {
            tick(delta); // position/party only
            return;
        }
        if (Forge.getCurrentScene() instanceof GameScene) {
            return;
        }
        if (!hostWorldPaused) {
            try {
                WorldStage.getInstance().coopBackgroundTick(delta);
            } catch (final Exception ignored) {
            }
        }
        tick(delta);
    }

    private void cacheLocalPlayerPosition() {
        try {
            final PlayerSprite player = WorldStage.getInstance().getPlayerSprite();
            if (player != null) {
                localPlayerX = player.getX();
                localPlayerY = player.getY();
                final CoopPositionSync sync = positionSync;
                if (sync != null) {
                    sync.setMaxSpeedPxPerSec(player.getMaxSpeedPxPerSec());
                }
            }
        } catch (final Exception ignored) {
        }
    }

    private void sendLocalPosition() {
        final CoopPositionSync sync = positionSync;
        if (sync == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (!sync.shouldSend(now)) {
            return;
        }
        final PlayerSprite player;
        try {
            player = WorldStage.getInstance().getPlayerSprite();
        } catch (final Exception e) {
            return;
        }
        if (player == null) {
            return;
        }
        localPlayerX = player.getX();
        localPlayerY = player.getY();
        final AdventurePlayer ap = Current.player();
        final String name = ap != null ? ap.getName() : "";
        String avatar = ap != null ? ap.spriteName() : player.getAtlasPath();
        if (avatar == null || !CoopWireLimits.isAllowedAvatarId(avatar)) {
            avatar = "sprites/heroes/Human_m.atlas";
        }
        final float facing = player.getDirection() != null ? player.getDirection().ordinal() : 0f;
        final String safeName = CoopWireLimits.acceptPlayerName(name);
        final float maxSpeed = player.getMaxSpeedPxPerSec();
        sync.setMaxSpeedPxPerSec(maxSpeed);
        CoopSession.get().send(new CoopPlayerMoveEvent(
                player.getX(), player.getY(), facing, now,
                safeName == null ? "" : safeName, avatar, maxSpeed, false));
        sync.markSent(now);
    }

    /**
     * Waypoint / portal / resetPlayerLocation / POI exit: send an explicit
     * teleport sample and reset local last-accepted. Peer must have armed
     * teleport (location exit arms the partner; local allowing actions arm
     * before send for the peer via {@link #armPeerTeleport()}).
     */
    public void notifyLocalTeleport() {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        final CoopPositionSync sync = positionSync;
        if (sync == null) {
            return;
        }
        PlayerSprite player = null;
        try {
            player = WorldStage.getInstance().getPlayerSprite();
        } catch (final Exception ignored) {
        }
        if (player == null) {
            return;
        }
        localPlayerX = player.getX();
        localPlayerY = player.getY();
        final AdventurePlayer ap = Current.player();
        final String name = ap != null ? ap.getName() : "";
        String avatar = ap != null ? ap.spriteName() : player.getAtlasPath();
        if (avatar == null || !CoopWireLimits.isAllowedAvatarId(avatar)) {
            avatar = "sprites/heroes/Human_m.atlas";
        }
        final float facing = player.getDirection() != null ? player.getDirection().ordinal() : 0f;
        final String safeName = CoopWireLimits.acceptPlayerName(name);
        final float maxSpeed = player.getMaxSpeedPxPerSec();
        final long now = System.currentTimeMillis();
        sync.setMaxSpeedPxPerSec(maxSpeed);
        sync.resetLastAccepted(player.getX(), player.getY(), facing, now,
                safeName == null ? "" : safeName, avatar);
        // Peer will accept this only if they have armed teleport.
        armPeerTeleport();
        CoopSession.get().send(new CoopPlayerMoveEvent(
                player.getX(), player.getY(), facing, now,
                safeName == null ? "" : safeName, avatar, maxSpeed, true));
        sync.markSent(now);
    }

    /**
     * Tell the peer (via a local arm before they validate our next teleport)
     * is handled on receive of location-exit / by allowing the next inbound
     * teleport after we perform an allowing action that the peer mirrors.
     * For inbound: arm when partner exits a POI or when we exit (they may move).
     */
    private void armPeerTeleport() {
        // No-op on wire: peer arms on CoopLocationExitEvent / local allowing paths.
        // Local sync is already reset via resetLastAccepted.
    }

    private void maybeBroadcastEnemies(final ConfigData cfg) {
        final float hz = cfg.coopEnemyBroadcastHz > 0f ? cfg.coopEnemyBroadcastHz : 5f;
        final long now = System.currentTimeMillis();
        if (now - lastEnemyBroadcastMs < (long) (1000f / hz)) {
            return;
        }
        lastEnemyBroadcastMs = now;
        for (final Map.Entry<EnemySprite, Long> e : enemyIds.entrySet()) {
            final EnemySprite sprite = e.getKey();
            final Long id = e.getValue();
            if (sprite == null || id == null || isMirroredActor(sprite)) {
                continue;
            }
            final String dataId = sprite.getData() != null ? sprite.getData().getName() : "";
            final float facing = sprite.getDirection() != null ? sprite.getDirection().ordinal() : 0f;
            if (authority != null) {
                authority.updateEnemyPosition(id, sprite.getX(), sprite.getY());
            }
            CoopSession.get().send(new CoopEnemyStateEvent(id, CoopEnemyStateEvent.Action.MOVE,
                    dataId, sprite.getX(), sprite.getY(), facing));
        }
    }

    /** Host: push full enemy+node snapshot once the session is READY. */
    private void sendReadySnapshot() {
        if (!CoopHooks.isWorldAuthority() || authority == null) {
            return;
        }
        WorldStage.getInstance().coopRegisterExistingForSnapshot();
        for (final CoopWorldAuthority.NodeRecord n : authority.snapshotNodes()) {
            CoopSession.get().send(new CoopNodeStateEvent(n.id, CoopNodeStateEvent.Action.SPAWN,
                    n.materialId, n.x, n.y, ""));
        }
        for (final CoopWorldAuthority.EnemyRecord e : authority.snapshotEnemies()) {
            CoopSession.get().send(new CoopEnemyStateEvent(e.id, CoopEnemyStateEvent.Action.SPAWN,
                    e.enemyDataId, e.x, e.y, 0f));
        }
    }

    // ---- listeners ----

    @Override
    public void onPlayerMove(final CoopPlayerMoveEvent event) {
        final CoopPositionSync sync = positionSync;
        if (sync == null || !CoopHooks.isOverworldReady()) {
            return;
        }
        // Teleport samples are accepted only after an allowing action:
        // location-exit arms the peer, or a rate-limited grant for waypoint /
        // portal / reset (sender only emits teleport after those actions).
        if (event != null && event.isTeleport() && !sync.isTeleportArmed()) {
            if (!teleportAcceptLimiter.tryAcquire(System.currentTimeMillis())) {
                return;
            }
            sync.allowTeleport();
        }
        final CoopPlayerMoveEvent accepted = sync.acceptInbound(event);
        if (accepted == null) {
            return;
        }
        partnerX = accepted.getX();
        partnerY = accepted.getY();
        postGl(() -> {
            ensurePartnerSprite();
            final CoopPartnerSprite sprite = partnerSprite;
            if (sprite != null) {
                sprite.applySample(accepted);
            }
        });
    }

    @Override
    public void onPartyInvite(final CoopPartyInviteEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        if (!partnersNearby()) {
            notifyHud(capHud("Party invite ignored — too far away"));
            return;
        }
        final CoopPartyState.InviteOutcome outcome =
                party.receiveInvite(event, System.currentTimeMillis(), inviteTimeoutMs());
        if (outcome == CoopPartyState.InviteOutcome.REJECTED) {
            return;
        }
        if (outcome == CoopPartyState.InviteOutcome.MUTUAL_ACCEPT) {
            CoopSession.get().send(new CoopPartyResponseEvent(event.getInviteId(),
                    CoopPartyResponseEvent.Action.ACCEPT));
            notifyHud(capHud("Party formed (mutual invite) with " + capName(event.getFromPlayer())));
            return;
        }
        notifyHud(capHud(capName(event.getFromPlayer()) + " invited you to a party (Accept / Decline)"));
        postGl(() -> promptPartyInvite(event));
    }

    @Override
    public void onPartyResponse(final CoopPartyResponseEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        final String peer = CoopSession.get().getPeerName();
        if (!party.applyPeerResponse(event, peer, System.currentTimeMillis(), inviteTimeoutMs())) {
            return;
        }
        if (event.getAction() == CoopPartyResponseEvent.Action.ACCEPT) {
            notifyHud(capHud("Party formed with " + capName(peer)));
        } else if (event.getAction() == CoopPartyResponseEvent.Action.DECLINE) {
            notifyHud(capHud(capName(peer) + " declined the party invite"));
        } else if (event.getAction() == CoopPartyResponseEvent.Action.LEAVE) {
            notifyHud(capHud("Party disbanded"));
        }
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
    }

    @Override
    public void onGatherRequest(final CoopGatherRequestEvent event) {
        if (!CoopHooks.isOverworldReady() || !CoopHooks.isWorldAuthority() || authority == null) {
            return;
        }
        if (event == null || !CoopWorldAuthority.isGuestRequestId(event.getRequestId())) {
            return; // host-local id space must never arrive on the wire
        }
        final String guest = CoopSession.get().getPeerName();
        final CoopPlayerMoveEvent last = positionSync != null ? positionSync.getLastAccepted() : null;
        final float ax = last != null ? last.getX() : Float.NaN;
        final float ay = last != null ? last.getY() : Float.NaN;
        // Amount is echoed for acknowledgement only; guest applies solo rewards locally.
        final CoopGatherResultEvent result = authority.handleGatherRequest(
                event, guest, 1, ax, ay, System.currentTimeMillis());
        CoopSession.get().send(result);
        if (result.isAccepted()) {
            postGl(() -> removeLocalNode(result.getNodeId()));
            CoopSession.get().send(new CoopNodeStateEvent(result.getNodeId(),
                    CoopNodeStateEvent.Action.CLAIMED, result.getMaterialId(), 0f, 0f, result.getClaimedBy()));
        }
    }

    @Override
    public void onGatherResult(final CoopGatherResultEvent event) {
        // Host never applies gather results from the wire.
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority()) {
            return;
        }
        postGl(() -> applyGatherResult(event));
    }

    @Override
    public void onNodeState(final CoopNodeStateEvent event) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority()) {
            return;
        }
        postGl(() -> applyNodeState(event));
    }

    @Override
    public void onEnemyState(final CoopEnemyStateEvent event) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority()) {
            return;
        }
        postGl(() -> applyEnemyState(event));
    }

    @Override
    public void onEnemyEncounterRequest(final CoopEnemyEncounterRequestEvent event) {
        if (!CoopHooks.isOverworldReady() || !CoopHooks.isWorldAuthority() || event == null) {
            return;
        }
        if (authority == null || !authority.enemyExists(event.getEnemyId())) {
            return;
        }
        // Host rate-limit; no HUD message per request.
        if (!authority.tryAcceptEncounterRequest(System.currentTimeMillis())) {
            return;
        }
        final String guest = CoopSession.get().getPeerName();
        // CO3 hook — if a handler is registered it owns the fight start.
        if (CoopHooks.notifyGuestEnemyEncounter(event.getEnemyId(), event.getEnemyDataId(), guest)) {
            return;
        }
        // CO2: no co-op duel yet — acknowledge silently (guest stays on overworld).
    }

    @Override
    public void onPoiChange(final CoopPoiChangeEvent event) {
        if (!CoopHooks.isOverworldReady() || event == null) {
            return;
        }
        if (event.getChangeType() == CoopPoiChangeEvent.ChangeType.VISITED) {
            locationPolicy.markPartnerEntered(event.getPoiId());
        }
        if (!CoopHooks.isWorldAuthority()) {
            notifyHud(capHud("World update: " + event.getChangeType() + " @ "
                    + CoopWireLimits.clampString(event.getPoiId(), CoopWireLimits.MAX_POI_ID_LEN)));
        }
    }

    @Override
    public void onLocationInvite(final CoopLocationInviteEvent event) {
        if (!CoopHooks.isOverworldReady() || !party.inParty()) {
            return;
        }
        if (!partnersNearby()) {
            notifyHud(capHud("Location invite ignored — too far away"));
            return;
        }
        locationPolicy.setPendingInvite(event.getInviteId(), event.getPoiId(), System.currentTimeMillis());
        postGl(() -> promptLocationInvite(event));
    }

    @Override
    public void onLocationResponse(final CoopLocationResponseEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        if (event.getAction() == CoopLocationResponseEvent.Action.ACCEPT) {
            locationPolicy.markPartnerAcceptedInvite();
            notifyHud(capHud("Partner accepted — they will enter when ready"));
        } else {
            notifyHud(capHud("Partner will wait outside"));
        }
        locationPolicy.clearPending();
    }

    @Override
    public void onLocationExit(final CoopLocationExitEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        locationPolicy.markPartnerExited();
        // Partner left an interior — arm teleport acceptance for their exit reposition.
        if (positionSync != null) {
            positionSync.allowTeleport();
        }
        final String who = event.getFromPlayer() == null || event.getFromPlayer().isEmpty()
                ? "Partner" : capName(event.getFromPlayer());
        notifyHud(capHud(who + " returned to the overworld"));
    }

    @Override
    public void onHostPresence(final CoopHostPresenceEvent event) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority() || event == null) {
            return;
        }
        final boolean wasPaused = hostWorldPaused;
        hostWorldPaused = event.isWorldPaused();
        hostPresenceLabel = event.getPlaceLabel() == null ? ""
                : CoopWireLimits.clampString(event.getPlaceLabel(), CoopWireLimits.MAX_DISPLAY_NAME_LEN);
        postGl(() -> {
            if (hostWorldPaused) {
                final String label = hostPresenceLabel.isEmpty() ? "somewhere" : hostPresenceLabel;
                showHostBanner(capHud("Host is in " + label));
            } else {
                clearHostBanner();
                if (wasPaused) {
                    flushPausedWorldEvents();
                }
            }
        });
    }

    @Override
    public void onOverworldMessage(final NetEvent event) {
    }

    // ---- public API ----

    public void inviteParty() {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        if (!partnersNearby()) {
            notifyHud(capHud("Partner is too far away to invite"));
            return;
        }
        final AdventurePlayer ap = Current.player();
        final CoopPartyInviteEvent invite = party.createInvite(
                ap != null ? ap.getName() : "Player", System.currentTimeMillis());
        if (invite == null) {
            notifyHud(capHud("Cannot invite right now"));
            return;
        }
        CoopSession.get().send(invite);
        notifyHud(capHud("Party invite sent"));
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
    }

    public void acceptParty() {
        // Do not clear the invite queue here — GameHUD advances it on dialog close.
        final CoopPartyResponseEvent resp = party.respond(
                CoopPartyResponseEvent.Action.ACCEPT, System.currentTimeMillis(), inviteTimeoutMs());
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud(capHud("Joined party"));
        }
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
    }

    public void declineParty() {
        final CoopPartyResponseEvent resp = party.respond(
                CoopPartyResponseEvent.Action.DECLINE, System.currentTimeMillis(), inviteTimeoutMs());
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud(capHud("Declined party invite"));
        }
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
    }

    public void leaveParty() {
        final CoopPartyResponseEvent resp = party.respond(CoopPartyResponseEvent.Action.LEAVE);
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud(capHud("Left party"));
        }
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
    }

    /**
     * Distance check using positions cached on the GL thread (never reads
     * {@link PlayerSprite} off-thread).
     */
    public boolean partnersNearby() {
        if (!CoopHooks.isOverworldReady()) {
            return false;
        }
        if (Float.isNaN(partnerX) || Float.isNaN(partnerY)
                || Float.isNaN(localPlayerX) || Float.isNaN(localPlayerY)) {
            return false;
        }
        final float tile = CoopHooks.activeWorld() != null ? CoopHooks.activeWorld().getTileSize() : 16f;
        final float radius = Config.instance().getConfigData().coopPartyRadiusTiles;
        return CoopPartyState.withinRadius(localPlayerX, localPlayerY, partnerX, partnerY, tile, radius);
    }

    public long onHostNodeSpawned(final ResourceNodeSprite node, final String materialId) {
        if (!CoopHooks.isOverworldReady() || !CoopHooks.isWorldAuthority() || authority == null || node == null) {
            return -1L;
        }
        final long id = authority.registerNode(materialId, node.getX(), node.getY());
        if (id < 0L) {
            return -1L;
        }
        nodeIds.put(node, id);
        localNodesById.put(id, node);
        final CoopNodeStateEvent spawn = new CoopNodeStateEvent(id, CoopNodeStateEvent.Action.SPAWN,
                materialId, node.getX(), node.getY(), "");
        enqueueOrSendWorldEvent(spawn);
        return id;
    }

    public long getNodeId(final ResourceNodeSprite node) {
        final Long id = nodeIds.get(node);
        return id == null ? -1L : id;
    }

    public long getEnemyId(final EnemySprite enemy) {
        final Long id = enemyIds.get(enemy);
        return id == null ? -1L : id;
    }

    /** Host/guest lookup of a live enemy sprite by CO2 registry id (0 / unknown → null). */
    public EnemySprite getEnemyById(final long enemyId) {
        return enemyId == 0L ? null : localEnemiesById.get(enemyId);
    }

    public long onHostEnemySpawned(final EnemySprite enemy) {
        if (!CoopHooks.isOverworldReady() || !CoopHooks.isWorldAuthority() || authority == null || enemy == null) {
            return -1L;
        }
        final String dataId = enemy.getData() != null ? enemy.getData().getName() : "enemy";
        final long id = authority.registerEnemy(dataId, enemy.getX(), enemy.getY());
        if (id < 0L) {
            return -1L;
        }
        enemyIds.put(enemy, id);
        localEnemiesById.put(id, enemy);
        final float facing = enemy.getDirection() != null ? enemy.getDirection().ordinal() : 0f;
        enqueueOrSendWorldEvent(new CoopEnemyStateEvent(id, CoopEnemyStateEvent.Action.SPAWN,
                dataId, enemy.getX(), enemy.getY(), facing));
        return id;
    }

    public void onHostEnemyRemoved(final EnemySprite enemy) {
        final Long id = enemyIds.remove(enemy);
        if (id == null) {
            return;
        }
        localEnemiesById.remove(id);
        if (authority != null) {
            authority.removeEnemy(id);
        }
        if (CoopHooks.isOverworldReady() && CoopHooks.isWorldAuthority()) {
            enqueueOrSendWorldEvent(new CoopEnemyStateEvent(id, CoopEnemyStateEvent.Action.DESPAWN,
                    "", 0f, 0f, 0f));
        }
    }

    public void onHostNodeRemoved(final ResourceNodeSprite node) {
        final Long id = nodeIds.remove(node);
        if (id == null) {
            return;
        }
        localNodesById.remove(id);
        if (authority != null) {
            authority.removeNode(id);
        }
        if (CoopHooks.isOverworldReady() && CoopHooks.isWorldAuthority()) {
            enqueueOrSendWorldEvent(new CoopNodeStateEvent(id, CoopNodeStateEvent.Action.DESPAWN,
                    "", 0f, 0f, ""));
        }
    }

    public boolean onGatherComplete(final ResourceNodeSprite node, final int amount) {
        return onGatherComplete(node, amount, false);
    }

    /**
     * @param blastExtra when true (host multi-node gather), skip interact-range
     *                   check and suppress the per-node failure HUD message
     */
    public boolean onGatherComplete(final ResourceNodeSprite node, final int amount,
                                    final boolean blastExtra) {
        if (!CoopHooks.isOverworldReady()) {
            return true;
        }
        final long id = getNodeId(node);
        final AdventurePlayer ap = Current.player();
        final String who = ap != null ? ap.getName() : "Player";
        final PlayerSprite player = WorldStage.getInstance().getPlayerSprite();
        final float px = player != null ? player.getX() : 0f;
        final float py = player != null ? player.getY() : 0f;
        if (CoopHooks.isWorldAuthority()) {
            if (authority == null) {
                return true;
            }
            long nodeId = id;
            if (nodeId < 0L && node != null) {
                nodeId = authority.registerNode(node.getMaterialId(), node.getX(), node.getY());
                if (nodeId >= 0L) {
                    nodeIds.put(node, nodeId);
                    localNodesById.put(nodeId, node);
                }
            }
            // Host-local id space — never produce a gather-result event for the guest.
            final long reqId = authority.nextHostLocalRequestId();
            final CoopGatherResultEvent result = blastExtra
                    ? authority.claimLocalSkipRange(reqId, nodeId, who, amount)
                    : authority.claimLocal(reqId, nodeId, who, amount, px, py);
            if (result.isAccepted()) {
                final CoopNodeStateEvent claimed = new CoopNodeStateEvent(result.getNodeId(),
                        CoopNodeStateEvent.Action.CLAIMED, result.getMaterialId(), 0f, 0f, who);
                enqueueOrSendWorldEvent(claimed);
                removeLocalNode(result.getNodeId());
                return true;
            }
            if (!blastExtra) {
                notifyHud(capHud("Could not claim node: " + result.getReason()));
            }
            return false;
        }
        if (id < 0L) {
            if (!blastExtra) {
                notifyHud(capHud("That node is not in the shared world"));
            }
            return false;
        }
        final long reqId = gatherRequestSeq.getAndIncrement();
        final String materialId = node != null && node.getMaterialId() != null ? node.getMaterialId() : "";
        pendingGathers.add(reqId, id, materialId);
        CoopSession.get().send(new CoopGatherRequestEvent(reqId, id, px, py, System.currentTimeMillis()));
        return false;
    }

    /**
     * Guest collided with a mirrored host enemy — one request per mob per
     * contact; cooldown until they separate. Never starts a local duel.
     */
    public void onGuestEnemyCollision(final EnemySprite mob) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority() || mob == null) {
            return;
        }
        final long id = getEnemyId(mob);
        if (id < 0L) {
            return;
        }
        if (!encounterContactedIds.add(id)) {
            return; // already requested this contact
        }
        final PlayerSprite player = WorldStage.getInstance().getPlayerSprite();
        final String dataId = mob.getData() != null ? mob.getData().getName() : "";
        CoopSession.get().send(new CoopEnemyEncounterRequestEvent(
                encounterRequestSeq.getAndIncrement(), id, dataId,
                player != null ? player.getX() : 0f,
                player != null ? player.getY() : 0f));
        // No per-request HUD spam on guest either.
    }

    private void updateEncounterSeparation() {
        if (encounterContactedIds.isEmpty() || Float.isNaN(localPlayerX)) {
            return;
        }
        final float range = authority != null ? authority.getInteractRangePx()
                : CoopWireLimits.DEFAULT_INTERACT_RANGE_PX;
        final float rangeSq = range * range;
        for (final Long id : new ArrayList<>(encounterContactedIds)) {
            final EnemySprite sprite = localEnemiesById.get(id);
            if (sprite == null) {
                encounterContactedIds.remove(id);
                continue;
            }
            final float dx = localPlayerX - sprite.getX();
            final float dy = localPlayerY - sprite.getY();
            if (dx * dx + dy * dy > rangeSq) {
                encounterContactedIds.remove(id);
            }
        }
    }

    public boolean beforeEnterPoi(final PointOfInterest poi) {
        if (!CoopHooks.isOverworldReady() || poi == null) {
            return true;
        }
        final String poiId = poi.getID() != null ? poi.getID() : poi.getData() != null ? poi.getData().name : "";
        if (!locationPolicy.canEnter(poiId)) {
            notifyHud(capHud("Partner is inside another location — wait outside (v1 rule)"));
            return false;
        }
        if (party.inParty() && partnersNearby()) {
            final int timeout = Config.instance().getConfigData().coopLocationInviteTimeoutSeconds;
            final long inviteId = locationInviteSeq.getAndIncrement();
            final String name = Current.player() != null ? Current.player().getName() : "Player";
            final String display = poi.getData() != null ? poi.getData().name : poiId;
            locationPolicy.setPendingInvite(inviteId, poiId, System.currentTimeMillis());
            CoopSession.get().send(new CoopLocationInviteEvent(inviteId, name, poiId, display,
                    timeout > 0 ? timeout : 12));
            notifyHud(capHud("Asked partner to come along to "
                    + CoopWireLimits.clampString(display, CoopWireLimits.MAX_DISPLAY_NAME_LEN)));
        }
        locationPolicy.markLocalEntered(poiId);
        setLocalPresence(CoopHostPresenceEvent.Presence.INTERIOR,
                poi.getData() != null ? poi.getData().name : poiId);
        CoopSession.get().send(new CoopPoiChangeEvent(poiId, CoopPoiChangeEvent.ChangeType.VISITED, "", 0L));
        return true;
    }

    public void onExitPoi() {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        final String poiId = locationPolicy.getActivePoiId();
        locationPolicy.markLocalExited();
        final String name = Current.player() != null ? Current.player().getName() : "Player";
        CoopSession.get().send(new CoopLocationExitEvent(poiId, name));
        setLocalPresence(CoopHostPresenceEvent.Presence.OVERWORLD, "");
        // Arm our own inbound for partner follow-up, and send teleport for exit reposition.
        if (positionSync != null) {
            positionSync.allowTeleport();
        }
        notifyLocalTeleport();
    }

    /** Host enters a duel — pause shared world sim for both. */
    public void onHostDuelStarted(final String encounterLabel) {
        if (!CoopHooks.isOverworldReady() || !CoopHooks.isWorldAuthority()) {
            return;
        }
        setLocalPresence(CoopHostPresenceEvent.Presence.DUEL,
                encounterLabel == null || encounterLabel.isEmpty() ? "a duel" : encounterLabel);
    }

    public void onHostDuelEnded() {
        if (!CoopHooks.isOverworldReady() || !CoopHooks.isWorldAuthority()) {
            return;
        }
        if (MapStage.getInstance().isInMap()) {
            setLocalPresence(CoopHostPresenceEvent.Presence.INTERIOR, hostPresenceLabel);
        } else {
            setLocalPresence(CoopHostPresenceEvent.Presence.OVERWORLD, "");
        }
    }

    private void setLocalPresence(final CoopHostPresenceEvent.Presence presence, final String label) {
        final boolean wasPaused = hostWorldPaused;
        localPresence = presence;
        if (!CoopHooks.isWorldAuthority()) {
            return;
        }
        hostWorldPaused = presence == CoopHostPresenceEvent.Presence.INTERIOR
                || presence == CoopHostPresenceEvent.Presence.DUEL;
        hostPresenceLabel = label == null ? ""
                : CoopWireLimits.clampString(label, CoopWireLimits.MAX_DISPLAY_NAME_LEN);
        CoopSession.get().send(new CoopHostPresenceEvent(presence, hostPresenceLabel));
        if (wasPaused && !hostWorldPaused) {
            flushPausedWorldEvents();
        }
    }

    private void enqueueOrSendWorldEvent(final NetEvent event) {
        if (event == null) {
            return;
        }
        if (hostWorldPaused) {
            pausedWorldEvents.enqueue(event);
        } else {
            CoopSession.get().send(event);
        }
    }

    private void flushPausedWorldEvents() {
        final List<NetEvent> copy = pausedWorldEvents.drain();
        for (final NetEvent e : copy) {
            CoopSession.get().send(e);
        }
    }

    public boolean guestShouldSkipLocalSpawns() {
        return CoopHooks.isOverworldReady() && !CoopHooks.isWorldAuthority();
    }

    /** Guest: no AI / lifetime / local fights against mirrored enemies. */
    public boolean guestIsPureMirror() {
        return guestShouldSkipLocalSpawns();
    }

    /** True when shared enemy AI / spawns / lifetimes should freeze. */
    public boolean shouldPauseWorldSim() {
        return CoopHooks.isOverworldReady() && hostWorldPaused;
    }

    public void acceptLocationInvite() {
        final long id = locationPolicy.getPendingInviteId();
        if (id <= 0L) {
            return;
        }
        CoopSession.get().send(new CoopLocationResponseEvent(id, CoopLocationResponseEvent.Action.ACCEPT));
        locationPolicy.clearPending();
        notifyHud(capHud("Accepted — enter the same location when ready"));
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
    }

    public void declineLocationInvite() {
        final long id = locationPolicy.getPendingInviteId();
        if (id <= 0L) {
            return;
        }
        CoopSession.get().send(new CoopLocationResponseEvent(id, CoopLocationResponseEvent.Action.DECLINE));
        locationPolicy.clearPending();
        notifyHud(capHud("Waiting outside"));
        postGl(() -> GameHUD.getInstance().refreshCoopPartyHud());
    }

    // ---- GL helpers ----

    private void ensurePartnerSprite() {
        if (partnerSprite != null) {
            return;
        }
        partnerSprite = new CoopPartnerSprite("sprites/heroes/Human_m.atlas");
        try {
            WorldStage.getInstance().getSpriteGroup().addActor(partnerSprite);
        } catch (final Exception ignored) {
        }
    }

    private void removePartnerSprite() {
        final CoopPartnerSprite sprite = partnerSprite;
        partnerSprite = null;
        if (sprite != null) {
            sprite.remove();
        }
    }

    private void applyGatherResult(final CoopGatherResultEvent event) {
        if (event == null) {
            return;
        }
        // Match by guest-generated request id only — no player-name fallback.
        final CoopPendingGatherQueue.Entry pending =
                CoopWorldAuthority.isGuestRequestId(event.getRequestId())
                        ? pendingGathers.take(event.getRequestId()) : null;
        if (pending != null) {
            final String materialId = pending.materialId.isEmpty()
                    ? event.getMaterialId() : pending.materialId;
            if (event.isAccepted()) {
                // Guest applies the normal solo reward path on confirmed claim.
                WorldStage.getInstance().coopApplyGuestGatherRewards(materialId);
            } else {
                notifyHud(capHud("Gather denied: " + event.getReason()));
            }
        } else if (event.isAccepted() && CoopWorldAuthority.isGuestRequestId(event.getRequestId())) {
            notifyHud(capHud(capName(event.getClaimedBy()) + " claimed a node"));
        }
        removeLocalNode(event.getNodeId());
    }

    private void applyNodeState(final CoopNodeStateEvent event) {
        if (event == null || authority == null) {
            return;
        }
        switch (event.getAction()) {
            case SPAWN: {
                // Replace any existing sprite for this id (ghost fix).
                removeLocalNode(event.getNodeId());
                if (!authority.putNode(event.getNodeId(), event.getMaterialId(), event.getX(), event.getY())) {
                    return;
                }
                final MaterialData mat = MaterialListData.get(event.getMaterialId());
                if (mat == null) {
                    return;
                }
                final ResourceNodeSprite node = new ResourceNodeSprite(mat);
                node.setPosition(event.getX(), event.getY());
                localNodesById.put(event.getNodeId(), node);
                nodeIds.put(node, event.getNodeId());
                mirroredActors.add(node);
                WorldStage.getInstance().coopAddRemoteNode(node);
                break;
            }
            case DESPAWN:
            case CLAIMED:
                removeLocalNode(event.getNodeId());
                break;
            default:
                break;
        }
    }

    private void applyEnemyState(final CoopEnemyStateEvent event) {
        if (event == null || authority == null) {
            return;
        }
        switch (event.getAction()) {
            case SPAWN: {
                // Replace any existing sprite for this id (ghost fix).
                final EnemySprite old = localEnemiesById.remove(event.getEnemyId());
                if (old != null) {
                    enemyIds.remove(old);
                    mirroredActors.remove(old);
                    WorldStage.getInstance().coopRemoveRemoteEnemy(old);
                }
                if (!authority.putEnemy(event.getEnemyId(), event.getEnemyDataId(), event.getX(), event.getY())) {
                    return;
                }
                final EnemyData data = WorldData.getEnemy(event.getEnemyDataId());
                if (data == null) {
                    return;
                }
                final EnemySprite sprite = new EnemySprite(data);
                sprite.setPosition(event.getX(), event.getY());
                localEnemiesById.put(event.getEnemyId(), sprite);
                enemyIds.put(sprite, event.getEnemyId());
                mirroredActors.add(sprite);
                WorldStage.getInstance().coopAddRemoteEnemy(sprite);
                break;
            }
            case MOVE: {
                final EnemySprite sprite = localEnemiesById.get(event.getEnemyId());
                if (sprite != null) {
                    sprite.setPosition(event.getX(), event.getY());
                    authority.updateEnemyPosition(event.getEnemyId(), event.getX(), event.getY());
                }
                break;
            }
            case DESPAWN: {
                final EnemySprite sprite = localEnemiesById.remove(event.getEnemyId());
                if (sprite != null) {
                    enemyIds.remove(sprite);
                    mirroredActors.remove(sprite);
                    WorldStage.getInstance().coopRemoveRemoteEnemy(sprite);
                }
                authority.removeEnemy(event.getEnemyId());
                encounterContactedIds.remove(event.getEnemyId());
                break;
            }
            default:
                break;
        }
    }

    private void removeLocalNode(final long nodeId) {
        final ResourceNodeSprite node = localNodesById.remove(nodeId);
        if (node != null) {
            nodeIds.remove(node);
            mirroredActors.remove(node);
            WorldStage.getInstance().coopRemoveRemoteNode(node);
        }
        if (authority != null) {
            authority.removeNode(nodeId);
        }
    }

    private void promptPartyInvite(final CoopPartyInviteEvent event) {
        final String from = capName(event.getFromPlayer());
        // Queue — never replace an open dialog (exit-dungeon / other invite).
        final forge.gamemodes.net.coop.CoopInviteUiState.Prompt activated =
                inviteUi.enqueue(forge.gamemodes.net.coop.CoopInviteUiState.PromptKind.PARTY,
                        event.getInviteId(), from, "");
        if (activated == null) {
            return; // queued behind an open dialog
        }
        try {
            GameHUD.getInstance().showCoopPartyInviteDialog(from);
        } catch (final Exception ignored) {
        }
    }

    private void promptLocationInvite(final CoopLocationInviteEvent event) {
        final String display = CoopWireLimits.clampString(event.getDisplayName(),
                CoopWireLimits.MAX_DISPLAY_NAME_LEN);
        final String from = capName(event.getFromPlayer());
        final forge.gamemodes.net.coop.CoopInviteUiState.Prompt activated =
                inviteUi.enqueue(forge.gamemodes.net.coop.CoopInviteUiState.PromptKind.LOCATION,
                        event.getInviteId(), from, display);
        if (activated == null) {
            return;
        }
        try {
            GameHUD.getInstance().showCoopLocationInviteDialog(from, display);
        } catch (final Exception ignored) {
        }
    }

    private void showHostBanner(final String msg) {
        try {
            GameHUD.getInstance().setHostPresenceBanner(msg);
        } catch (final Exception e) {
            System.out.println("[co-op] " + msg);
        }
    }

    private void clearHostBanner() {
        try {
            GameHUD.getInstance().clearHostPresenceBanner();
        } catch (final Exception ignored) {
        }
    }

    private void notifyHud(final String msg) {
        postGl(() -> {
            try {
                GameHUD.getInstance().addNotification(msg);
            } catch (final Exception e) {
                System.out.println("[co-op] " + msg);
            }
        });
    }

    private static String capHud(final String msg) {
        return CoopWireLimits.clampString(msg, CoopWireLimits.MAX_TEXT_LEN);
    }

    private static String capName(final String name) {
        return CoopWireLimits.clampString(name, CoopWireLimits.MAX_PLAYER_NAME_LEN);
    }

    private static void postGl(final Runnable r) {
        if (r == null) {
            return;
        }
        if (Gdx.app != null) {
            Gdx.app.postRunnable(() -> {
                try {
                    r.run();
                } catch (final Exception ignored) {
                }
            });
        } else {
            r.run();
        }
    }
}
