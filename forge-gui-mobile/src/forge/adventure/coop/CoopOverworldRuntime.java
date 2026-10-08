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
import forge.gamemodes.net.coop.CoopLocationPolicy;
import forge.gamemodes.net.coop.CoopPartyState;
import forge.gamemodes.net.coop.CoopPositionSync;
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
    private volatile long lastEnemyBroadcastMs;
    private final AtomicLong locationInviteSeq = new AtomicLong(1L);
    private final AtomicLong gatherRequestSeq = new AtomicLong(1L);
    private final AtomicLong encounterRequestSeq = new AtomicLong(1L);
    private final Map<Long, ResourceNodeSprite> localNodesById = new ConcurrentHashMap<>();
    private final Map<Long, EnemySprite> localEnemiesById = new ConcurrentHashMap<>();
    private final Map<EnemySprite, Long> enemyIds = new ConcurrentHashMap<>();
    private final Map<ResourceNodeSprite, Long> nodeIds = new ConcurrentHashMap<>();
    /** Mirrored (guest) sprites — never written by {@link WorldStage#save()}. */
    private final Set<Object> mirroredActors = ConcurrentHashMap.newKeySet();
    private volatile boolean pendingGatherAwaitingHost;
    private volatile long pendingGatherRequestId = -1L;
    private volatile long pendingGatherNodeId = -1L;
    private volatile boolean hostWorldPaused;
    private volatile String hostPresenceLabel = "";
    private volatile CoopHostPresenceEvent.Presence localPresence = CoopHostPresenceEvent.Presence.OVERWORLD;

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
        pendingGatherAwaitingHost = false;
        pendingGatherRequestId = -1L;
        pendingGatherNodeId = -1L;
        hostWorldPaused = false;
        hostPresenceLabel = "";
        localPresence = CoopHostPresenceEvent.Presence.OVERWORLD;
        postGl(this::ensurePartnerSprite);
        if (CoopHooks.isWorldAuthority()) {
            postGl(this::sendReadySnapshot);
        }
        notifyHud("Co-op overworld ready");
    }

    public void onSessionEnded(final String reason) {
        party.clearParty();
        locationPolicy.reset();
        pendingGatherAwaitingHost = false;
        pendingGatherRequestId = -1L;
        pendingGatherNodeId = -1L;
        hostWorldPaused = false;
        hostPresenceLabel = "";
        if (authority != null) {
            authority.clear();
        }
        if (positionSync != null) {
            positionSync.clear();
        }
        final Map<Long, ResourceNodeSprite> nodesCopy = new ConcurrentHashMap<>(localNodesById);
        final Map<Long, EnemySprite> enemiesCopy = new ConcurrentHashMap<>(localEnemiesById);
        localNodesById.clear();
        localEnemiesById.clear();
        enemyIds.clear();
        nodeIds.clear();
        mirroredActors.clear();
        partnerX = Float.NaN;
        partnerY = Float.NaN;
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
            clearHostBanner();
        });
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
        if (party.expireIfNeeded(now, timeout)) {
            notifyHud("Party invite expired");
        }
        if (locationPolicy.expireIfNeeded(now, timeout)) {
            notifyHud("Location invite expired");
        }
        final ConfigData cfg = Config.instance().getConfigData();
        sendLocalPosition();
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
        final AdventurePlayer ap = Current.player();
        final String name = ap != null ? ap.getName() : "";
        String avatar = ap != null ? ap.spriteName() : player.getAtlasPath();
        if (avatar == null || !CoopWireLimits.isAllowedAvatarId(avatar)) {
            avatar = "sprites/heroes/Human_m.atlas";
        }
        final float facing = player.getDirection() != null ? player.getDirection().ordinal() : 0f;
        final String safeName = CoopWireLimits.acceptPlayerName(name);
        CoopSession.get().send(new CoopPlayerMoveEvent(
                player.getX(), player.getY(), facing, now,
                safeName == null ? "" : safeName, avatar));
        sync.markSent(now);
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
        // Receiving-side distance check.
        if (!partnersNearby()) {
            notifyHud("Party invite ignored — too far away");
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
            notifyHud("Party formed (mutual invite) with " + event.getFromPlayer());
            return;
        }
        notifyHud(event.getFromPlayer() + " invited you to a party (Accept / Decline)");
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
            notifyHud("Party formed with " + peer);
        } else if (event.getAction() == CoopPartyResponseEvent.Action.DECLINE) {
            notifyHud(peer + " declined the party invite");
        } else if (event.getAction() == CoopPartyResponseEvent.Action.LEAVE) {
            notifyHud("Party disbanded");
        }
    }

    @Override
    public void onGatherRequest(final CoopGatherRequestEvent event) {
        if (!CoopHooks.isOverworldReady() || !CoopHooks.isWorldAuthority() || authority == null) {
            return;
        }
        final String guest = CoopSession.get().getPeerName();
        final CoopPlayerMoveEvent last = positionSync != null ? positionSync.getLastAccepted() : null;
        final float ax = last != null ? last.getX() : Float.NaN;
        final float ay = last != null ? last.getY() : Float.NaN;
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
        final String guest = CoopSession.get().getPeerName();
        // CO3 hook — if a handler is registered it owns the fight start.
        if (CoopHooks.notifyGuestEnemyEncounter(event.getEnemyId(), event.getEnemyDataId(), guest)) {
            return;
        }
        // CO2: no co-op duel yet — acknowledge only (guest stays on overworld).
        notifyHud(guest + " bumped enemy " + event.getEnemyDataId() + " (solo fight on host later / CO3)");
    }

    @Override
    public void onPoiChange(final CoopPoiChangeEvent event) {
        if (!CoopHooks.isOverworldReady() || event == null) {
            return;
        }
        // Partner actually entered this POI (accepter path marks inside only on enter).
        if (event.getChangeType() == CoopPoiChangeEvent.ChangeType.VISITED) {
            locationPolicy.markPartnerEntered(event.getPoiId());
        }
        if (!CoopHooks.isWorldAuthority()) {
            notifyHud("World update: " + event.getChangeType() + " @ " + event.getPoiId());
        }
    }

    @Override
    public void onLocationInvite(final CoopLocationInviteEvent event) {
        if (!CoopHooks.isOverworldReady() || !party.inParty()) {
            return;
        }
        if (!partnersNearby()) {
            notifyHud("Location invite ignored — too far away");
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
            // Do NOT mark partner inside until they actually enter.
            locationPolicy.markPartnerAcceptedInvite();
            notifyHud("Partner accepted — they will enter when ready");
        } else {
            notifyHud("Partner will wait outside");
        }
        locationPolicy.clearPending();
    }

    @Override
    public void onLocationExit(final CoopLocationExitEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        locationPolicy.markPartnerExited();
        notifyHud((event.getFromPlayer() == null || event.getFromPlayer().isEmpty()
                ? "Partner" : event.getFromPlayer()) + " returned to the overworld");
    }

    @Override
    public void onHostPresence(final CoopHostPresenceEvent event) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority() || event == null) {
            return;
        }
        hostWorldPaused = event.isWorldPaused();
        hostPresenceLabel = event.getPlaceLabel() == null ? "" : event.getPlaceLabel();
        postGl(() -> {
            if (hostWorldPaused) {
                final String label = hostPresenceLabel.isEmpty() ? "somewhere" : hostPresenceLabel;
                showHostBanner("Host is in " + label);
            } else {
                clearHostBanner();
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
            notifyHud("Partner is too far away to invite");
            return;
        }
        final AdventurePlayer ap = Current.player();
        final CoopPartyInviteEvent invite = party.createInvite(
                ap != null ? ap.getName() : "Player", System.currentTimeMillis());
        if (invite == null) {
            notifyHud("Cannot invite right now");
            return;
        }
        CoopSession.get().send(invite);
        notifyHud("Party invite sent");
    }

    public void acceptParty() {
        final CoopPartyResponseEvent resp = party.respond(
                CoopPartyResponseEvent.Action.ACCEPT, System.currentTimeMillis(), inviteTimeoutMs());
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud("Joined party");
        }
    }

    public void declineParty() {
        final CoopPartyResponseEvent resp = party.respond(
                CoopPartyResponseEvent.Action.DECLINE, System.currentTimeMillis(), inviteTimeoutMs());
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud("Declined party invite");
        }
    }

    public void leaveParty() {
        final CoopPartyResponseEvent resp = party.respond(CoopPartyResponseEvent.Action.LEAVE);
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud("Left party");
        }
    }

    public boolean partnersNearby() {
        if (!CoopHooks.isOverworldReady()) {
            return false;
        }
        if (Float.isNaN(partnerX) || Float.isNaN(partnerY)) {
            return false;
        }
        final PlayerSprite player = WorldStage.getInstance().getPlayerSprite();
        if (player == null) {
            return false;
        }
        final float tile = CoopHooks.activeWorld() != null ? CoopHooks.activeWorld().getTileSize() : 16f;
        final float radius = Config.instance().getConfigData().coopPartyRadiusTiles;
        return CoopPartyState.withinRadius(player.getX(), player.getY(), partnerX, partnerY, tile, radius);
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
        if (!hostWorldPaused) {
            CoopSession.get().send(new CoopNodeStateEvent(id, CoopNodeStateEvent.Action.SPAWN,
                    materialId, node.getX(), node.getY(), ""));
        }
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
        if (!hostWorldPaused) {
            final float facing = enemy.getDirection() != null ? enemy.getDirection().ordinal() : 0f;
            CoopSession.get().send(new CoopEnemyStateEvent(id, CoopEnemyStateEvent.Action.SPAWN,
                    dataId, enemy.getX(), enemy.getY(), facing));
        }
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
            CoopSession.get().send(new CoopEnemyStateEvent(id, CoopEnemyStateEvent.Action.DESPAWN,
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
            CoopSession.get().send(new CoopNodeStateEvent(id, CoopNodeStateEvent.Action.DESPAWN,
                    "", 0f, 0f, ""));
        }
    }

    public boolean onGatherComplete(final ResourceNodeSprite node, final int amount) {
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
            final long reqId = gatherRequestSeq.getAndIncrement();
            final CoopGatherResultEvent result = authority.claimLocal(reqId, nodeId, who, amount, px, py);
            if (result.isAccepted()) {
                CoopSession.get().send(result);
                CoopSession.get().send(new CoopNodeStateEvent(result.getNodeId(),
                        CoopNodeStateEvent.Action.CLAIMED, result.getMaterialId(), 0f, 0f, who));
                removeLocalNode(result.getNodeId());
                return true;
            }
            notifyHud("Could not claim node: " + result.getReason());
            return false;
        }
        if (id < 0L) {
            notifyHud("That node is not in the shared world");
            return false;
        }
        final long reqId = gatherRequestSeq.getAndIncrement();
        pendingGatherAwaitingHost = true;
        pendingGatherRequestId = reqId;
        pendingGatherNodeId = id;
        CoopSession.get().send(new CoopGatherRequestEvent(reqId, id, px, py, System.currentTimeMillis()));
        return false;
    }

    /**
     * Guest collided with a mirrored host enemy — send encounter request; never
     * start a local duel on the guest.
     */
    public void onGuestEnemyCollision(final EnemySprite mob) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority() || mob == null) {
            return;
        }
        final long id = getEnemyId(mob);
        if (id < 0L) {
            return;
        }
        final PlayerSprite player = WorldStage.getInstance().getPlayerSprite();
        final String dataId = mob.getData() != null ? mob.getData().getName() : "";
        CoopSession.get().send(new CoopEnemyEncounterRequestEvent(
                encounterRequestSeq.getAndIncrement(), id, dataId,
                player != null ? player.getX() : 0f,
                player != null ? player.getY() : 0f));
        notifyHud("Waiting for host…");
    }

    public boolean beforeEnterPoi(final PointOfInterest poi) {
        if (!CoopHooks.isOverworldReady() || poi == null) {
            return true;
        }
        final String poiId = poi.getID() != null ? poi.getID() : poi.getData() != null ? poi.getData().name : "";
        if (!locationPolicy.canEnter(poiId)) {
            notifyHud("Partner is inside another location — wait outside (v1 rule)");
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
            notifyHud("Asked partner to come along to " + display);
        }
        locationPolicy.markLocalEntered(poiId);
        setLocalPresence(CoopHostPresenceEvent.Presence.INTERIOR,
                poi.getData() != null ? poi.getData().name : poiId);
        // Tell the peer we actually entered (marks accepter inside on their side).
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
        localPresence = presence;
        if (!CoopHooks.isWorldAuthority()) {
            return;
        }
        hostWorldPaused = presence == CoopHostPresenceEvent.Presence.INTERIOR
                || presence == CoopHostPresenceEvent.Presence.DUEL;
        hostPresenceLabel = label == null ? "" : label;
        CoopSession.get().send(new CoopHostPresenceEvent(presence, hostPresenceLabel));
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
        // Accepter is marked inside only when they actually enter (beforeEnterPoi).
        locationPolicy.clearPending();
        notifyHud("Accepted — enter the same location when ready");
    }

    public void declineLocationInvite() {
        final long id = locationPolicy.getPendingInviteId();
        if (id <= 0L) {
            return;
        }
        CoopSession.get().send(new CoopLocationResponseEvent(id, CoopLocationResponseEvent.Action.DECLINE));
        locationPolicy.clearPending();
        notifyHud("Waiting outside");
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
        final boolean matchRequest = pendingGatherAwaitingHost
                && event.getRequestId() == pendingGatherRequestId;
        final AdventurePlayer ap = Current.player();
        final String me = ap != null ? ap.getName() : "";
        final boolean matchClaimedBy = event.isAccepted() && me.equals(event.getClaimedBy());
        if (matchRequest || (pendingGatherAwaitingHost && matchClaimedBy
                && event.getNodeId() == pendingGatherNodeId)) {
            pendingGatherAwaitingHost = false;
            pendingGatherRequestId = -1L;
            pendingGatherNodeId = -1L;
            if (event.isAccepted()) {
                applyLocalLoot(event.getMaterialId(), event.getAmount());
                notifyHud("Gathered " + event.getAmount() + " " + event.getMaterialId());
            } else {
                notifyHud("Gather denied: " + event.getReason());
            }
        } else if (event.isAccepted()) {
            notifyHud(event.getClaimedBy() + " claimed a node");
        }
        removeLocalNode(event.getNodeId());
    }

    private void applyLocalLoot(final String materialId, final int amount) {
        if (materialId == null || materialId.isEmpty() || amount <= 0) {
            return;
        }
        final AdventurePlayer ap = Current.player();
        if (ap != null) {
            ap.addMaterial(materialId, amount);
        }
    }

    private void applyNodeState(final CoopNodeStateEvent event) {
        if (event == null || authority == null) {
            return;
        }
        switch (event.getAction()) {
            case SPAWN: {
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
        try {
            GameHUD.getInstance().addNotification(event.getFromPlayer()
                    + " invited you to party — use Party Accept / Decline");
        } catch (final Exception ignored) {
        }
    }

    private void promptLocationInvite(final CoopLocationInviteEvent event) {
        try {
            GameHUD.getInstance().addNotification(event.getFromPlayer()
                    + " is entering " + event.getDisplayName()
                    + " — Come along? (Location Accept / Decline)");
        } catch (final Exception ignored) {
        }
    }

    private void showHostBanner(final String msg) {
        try {
            GameHUD.getInstance().addNotification(msg);
        } catch (final Exception e) {
            System.out.println("[co-op] " + msg);
        }
    }

    private void clearHostBanner() {
        // Notifications are fire-and-forget; next OVERWORLD presence clears the conceptual banner.
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
