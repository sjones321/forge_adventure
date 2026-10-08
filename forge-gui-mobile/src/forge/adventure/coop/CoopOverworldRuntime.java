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
import forge.adventure.scene.GameScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.gamemodes.net.coop.CoopLocationPolicy;
import forge.gamemodes.net.coop.CoopPartyState;
import forge.gamemodes.net.coop.CoopPositionSync;
import forge.gamemodes.net.coop.CoopWireLimits;
import forge.gamemodes.net.coop.CoopWorldAuthority;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopEnemyStateEvent;
import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;
import forge.gamemodes.net.event.coop.CoopLocationInviteEvent;
import forge.gamemodes.net.event.coop.CoopLocationResponseEvent;
import forge.gamemodes.net.event.coop.CoopNodeStateEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;
import forge.gamemodes.net.event.coop.CoopPoiChangeEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CO2 shared-overworld runtime. Wires position sync, partner sprite, party,
 * host-authoritative nodes/enemies, and location invites onto the CO1 session.
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
    private final Map<Long, ResourceNodeSprite> localNodesById = new ConcurrentHashMap<>();
    private final Map<Long, EnemySprite> localEnemiesById = new ConcurrentHashMap<>();
    private final Map<EnemySprite, Long> enemyIds = new ConcurrentHashMap<>();
    private final Map<ResourceNodeSprite, Long> nodeIds = new ConcurrentHashMap<>();
    private volatile boolean pendingGatherAwaitingHost;
    private volatile long pendingGatherNodeId = -1L;

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

    public boolean isPendingGather() {
        return pendingGatherAwaitingHost;
    }

    /** Called when the session reaches READY. */
    public void onSessionReady() {
        if (!Config.ascendant()) {
            return;
        }
        ensureAttached();
        final ConfigData cfg = Config.instance().getConfigData();
        positionSync = new CoopPositionSync(
                Math.max(1, cfg.coopPositionMaxPerSecond),
                cfg.coopPositionSendHz > 0f ? cfg.coopPositionSendHz : 15f);
        authority = new CoopWorldAuthority(
                cfg.coopInteractRangePx > 0f ? cfg.coopInteractRangePx : CoopWireLimits.DEFAULT_INTERACT_RANGE_PX,
                Math.max(1, cfg.coopRequestMaxPerSecond),
                1000L);
        party.clearParty();
        locationPolicy.reset();
        pendingGatherAwaitingHost = false;
        pendingGatherNodeId = -1L;
        postGl(this::ensurePartnerSprite);
        notifyHud("Co-op overworld ready");
    }

    public void onSessionEnded(final String reason) {
        party.clearParty();
        locationPolicy.reset();
        pendingGatherAwaitingHost = false;
        pendingGatherNodeId = -1L;
        if (authority != null) {
            authority.clear();
        }
        if (positionSync != null) {
            positionSync.clear();
        }
        localNodesById.clear();
        localEnemiesById.clear();
        enemyIds.clear();
        nodeIds.clear();
        partnerX = Float.NaN;
        partnerY = Float.NaN;
        postGl(this::removePartnerSprite);
    }

    private void ensureAttached() {
        if (attached) {
            return;
        }
        CoopSession.get().addOverworldListener(this);
        attached = true;
    }

    /** Per-frame tick from WorldStage / Adventure background keep-alive. */
    public void tick(final float delta) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        final ConfigData cfg = Config.instance().getConfigData();
        sendLocalPosition();
        final CoopPartnerSprite sprite = partnerSprite;
        if (sprite != null) {
            sprite.interpolate(delta, cfg.coopPartnerInterpRate > 0f ? cfg.coopPartnerInterpRate : 12f);
        }
        if (CoopHooks.isWorldAuthority()) {
            maybeBroadcastEnemies(cfg);
        }
    }

    /**
     * When menus (inventory, deck editor) are open, keep the co-op world
     * simulating so pausing UI does not pause the shared overworld.
     */
    public void backgroundTick(final float delta) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        if (Forge.getCurrentScene() instanceof GameScene) {
            return; // WorldStage.onActing already ticks
        }
        // Menus don't pause the world in co-op: advance enemies/nodes/partner.
        try {
            WorldStage.getInstance().coopBackgroundTick(delta);
        } catch (final Exception ignored) {
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
        final String avatar = ap != null ? ap.spriteName() : player.getAtlasPath();
        final float facing = player.getDirection() != null ? player.getDirection().ordinal() : 0f;
        final CoopPlayerMoveEvent ev = new CoopPlayerMoveEvent(
                player.getX(), player.getY(), facing, now,
                CoopWireLimits.clampString(name, CoopWireLimits.MAX_PLAYER_NAME_LEN),
                CoopWireLimits.clampString(avatar, CoopWireLimits.MAX_AVATAR_ID_LEN));
        CoopSession.get().send(ev);
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
            if (sprite == null || id == null) {
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

    // ---- CoopHooks.OverworldListener (may run on Netty; post GL work) ----

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
        if (!party.receiveInvite(event)) {
            return;
        }
        notifyHud(event.getFromPlayer() + " invited you to a party (Accept / Decline)");
        // Auto-present a simple accept/decline via notifications; HUD buttons optional.
        // Player can accept via console or we auto-show dialog on GL thread.
        postGl(() -> promptPartyInvite(event));
    }

    @Override
    public void onPartyResponse(final CoopPartyResponseEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        final String peer = CoopSession.get().getPeerName();
        if (!party.applyPeerResponse(event, peer)) {
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
        // Loot amount is rolled on the host for the guest's character later; wire carries a placeholder.
        final CoopGatherResultEvent result = authority.handleGatherRequest(event, guest, 1);
        CoopSession.get().send(result);
        if (result.isAccepted()) {
            postGl(() -> removeLocalNode(result.getNodeId()));
            CoopSession.get().send(new CoopNodeStateEvent(result.getNodeId(),
                    CoopNodeStateEvent.Action.CLAIMED, result.getMaterialId(), 0f, 0f, result.getClaimedBy()));
        }
    }

    @Override
    public void onGatherResult(final CoopGatherResultEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        postGl(() -> applyGatherResult(event));
    }

    @Override
    public void onNodeState(final CoopNodeStateEvent event) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority()) {
            return; // host already has the node
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
    public void onPoiChange(final CoopPoiChangeEvent event) {
        if (!CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority()) {
            return;
        }
        // Guest mirrors POI flags into the session world only (never guest save slots).
        notifyHud("World update: " + event.getChangeType() + " @ " + event.getPoiId());
    }

    @Override
    public void onLocationInvite(final CoopLocationInviteEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        if (!party.inParty()) {
            return;
        }
        locationPolicy.setPendingInvite(event.getInviteId(), event.getPoiId());
        postGl(() -> promptLocationInvite(event));
    }

    @Override
    public void onLocationResponse(final CoopLocationResponseEvent event) {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        if (event.getAction() == CoopLocationResponseEvent.Action.ACCEPT) {
            notifyHud("Partner is joining the location");
            locationPolicy.markPartnerEntered(locationPolicy.getPendingPoiId());
        } else {
            notifyHud("Partner will wait outside");
        }
        locationPolicy.clearPending();
    }

    @Override
    public void onOverworldMessage(final NetEvent event) {
        // typed handlers above
    }

    // ---- Public API used by WorldStage / HUD ----

    public void inviteParty() {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        if (!partnersNearby()) {
            notifyHud("Partner is too far away to invite");
            return;
        }
        final AdventurePlayer ap = Current.player();
        final CoopPartyInviteEvent invite = party.createInvite(ap != null ? ap.getName() : "Player");
        if (invite == null) {
            notifyHud("Cannot invite right now");
            return;
        }
        CoopSession.get().send(invite);
        notifyHud("Party invite sent");
    }

    public void acceptParty() {
        final CoopPartyResponseEvent resp = party.respond(CoopPartyResponseEvent.Action.ACCEPT);
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud("Joined party");
        }
    }

    public void declineParty() {
        final CoopPartyResponseEvent resp = party.respond(CoopPartyResponseEvent.Action.DECLINE);
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

    /**
     * Host registers a newly spawned node and broadcasts SPAWN.
     * @return coop node id, or -1
     */
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
        CoopSession.get().send(new CoopNodeStateEvent(id, CoopNodeStateEvent.Action.SPAWN,
                materialId, node.getX(), node.getY(), ""));
        return id;
    }

    public long getNodeId(final ResourceNodeSprite node) {
        final Long id = nodeIds.get(node);
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
        final float facing = enemy.getDirection() != null ? enemy.getDirection().ordinal() : 0f;
        CoopSession.get().send(new CoopEnemyStateEvent(id, CoopEnemyStateEvent.Action.SPAWN,
                dataId, enemy.getX(), enemy.getY(), facing));
        return id;
    }

    public void onHostEnemyRemoved(final EnemySprite enemy) {
        final Long id = enemyIds.remove(enemy);
        if (id == null || authority == null) {
            return;
        }
        localEnemiesById.remove(id);
        authority.removeEnemy(id);
        if (CoopHooks.isOverworldReady() && CoopHooks.isWorldAuthority()) {
            CoopSession.get().send(new CoopEnemyStateEvent(id, CoopEnemyStateEvent.Action.DESPAWN,
                    "", 0f, 0f, 0f));
        }
    }

    /**
     * Complete a gather under host authority. Guest sends a request instead.
     * @return true if the caller should apply local loot now (host path or solo)
     */
    public boolean onGatherComplete(final ResourceNodeSprite node, final int amount) {
        if (!CoopHooks.isOverworldReady()) {
            return true; // solo / non-coop
        }
        final long id = getNodeId(node);
        final AdventurePlayer ap = Current.player();
        final String who = ap != null ? ap.getName() : "Player";
        if (CoopHooks.isWorldAuthority()) {
            if (authority == null) {
                return true;
            }
            // Ensure node is registered (host may have spawned before READY).
            long nodeId = id;
            if (nodeId < 0L && node != null) {
                nodeId = authority.registerNode(node.getMaterialId(), node.getX(), node.getY());
                if (nodeId >= 0L) {
                    nodeIds.put(node, nodeId);
                    localNodesById.put(nodeId, node);
                }
            }
            final CoopGatherResultEvent result = authority.claimLocal(nodeId, who, amount);
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
        // Guest: request claim; loot applied when host confirms.
        if (id < 0L) {
            notifyHud("That node is not in the shared world");
            return false;
        }
        final PlayerSprite player = WorldStage.getInstance().getPlayerSprite();
        pendingGatherAwaitingHost = true;
        pendingGatherNodeId = id;
        CoopSession.get().send(new CoopGatherRequestEvent(id,
                player != null ? player.getX() : 0f,
                player != null ? player.getY() : 0f,
                System.currentTimeMillis()));
        return false;
    }

    /**
     * Before loading a POI: invite nearby party partner. Returns true if enter
     * may proceed for the local player (always, unless blocked by interior rule).
     */
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
            locationPolicy.setPendingInvite(inviteId, poiId);
            CoopSession.get().send(new CoopLocationInviteEvent(inviteId, name, poiId, display,
                    timeout > 0 ? timeout : 12));
            notifyHud("Asked partner to come along to " + display);
        }
        locationPolicy.markLocalEntered(poiId);
        // Host broadcasts POI visit for session-world guests (not written into guest saves).
        if (CoopHooks.isWorldAuthority()) {
            CoopSession.get().send(new CoopPoiChangeEvent(poiId, CoopPoiChangeEvent.ChangeType.VISITED, "", 0L));
        }
        return true;
    }

    public void onExitPoi() {
        if (!CoopHooks.isOverworldReady()) {
            return;
        }
        locationPolicy.markLocalExited();
    }

    /** Guest must not spawn local enemies/nodes while co-op READY. */
    public boolean guestShouldSkipLocalSpawns() {
        return CoopHooks.isOverworldReady() && !CoopHooks.isWorldAuthority();
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
        if (pendingGatherAwaitingHost && event.getNodeId() == pendingGatherNodeId) {
            pendingGatherAwaitingHost = false;
            pendingGatherNodeId = -1L;
            if (event.isAccepted()) {
                applyLocalLoot(event.getMaterialId(), event.getAmount());
                notifyHud("Gathered " + event.getAmount() + " " + event.getMaterialId());
            } else {
                notifyHud("Gather denied: " + event.getReason());
            }
        } else if (event.isAccepted()) {
            // Peer claimed it — remove locally.
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
                WorldStage.getInstance().coopAddRemoteNode(node);
                break;
            }
            case DESPAWN:
            case CLAIMED:
                removeLocalNode(event.getNodeId());
                if (authority != null) {
                    authority.removeNode(event.getNodeId());
                }
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
                EnemyData data = WorldData.getEnemy(event.getEnemyDataId());
                if (data == null) {
                    return;
                }
                final EnemySprite sprite = new EnemySprite(data);
                sprite.setPosition(event.getX(), event.getY());
                localEnemiesById.put(event.getEnemyId(), sprite);
                enemyIds.put(sprite, event.getEnemyId());
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
        // v1: declining is the safe default on timeout; player can accept via HUD/commands.
    }

    public void acceptLocationInvite() {
        final long id = locationPolicy.getPendingInviteId();
        if (id <= 0L) {
            return;
        }
        final String poiId = locationPolicy.getPendingPoiId();
        CoopSession.get().send(new CoopLocationResponseEvent(id, CoopLocationResponseEvent.Action.ACCEPT));
        locationPolicy.markLocalEntered(poiId);
        locationPolicy.clearPending();
        // Entering the same POI is handled by the player walking in / loadPOI when they choose.
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
