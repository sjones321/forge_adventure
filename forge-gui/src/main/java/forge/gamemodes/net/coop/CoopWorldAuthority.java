package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-side authoritative bookkeeping for shared overworld entities (CO2):
 * resource nodes (first claim wins via atomic {@code nodes.remove}), enemy
 * existence checks, and gather validation. Pure Java — no LibGDX.
 */
public final class CoopWorldAuthority {
    public static final class NodeRecord {
        public final long id;
        public final String materialId;
        public final float x;
        public final float y;

        public NodeRecord(final long id, final String materialId, final float x, final float y) {
            this.id = id;
            this.materialId = materialId == null ? "" : materialId;
            this.x = x;
            this.y = y;
        }
    }

    public static final class EnemyRecord {
        public final long id;
        public final String enemyDataId;
        public volatile float x;
        public volatile float y;
        public volatile boolean alive = true;

        public EnemyRecord(final long id, final String enemyDataId, final float x, final float y) {
            this.id = id;
            this.enemyDataId = enemyDataId == null ? "" : enemyDataId;
            this.x = x;
            this.y = y;
        }
    }

    /**
     * Guest gather / encounter request ids are positive and guest-generated;
     * the host echoes them on results. Host-local claims use a separate
     * negative id space and never produce a wire result for the guest.
     */
    public static final long HOST_LOCAL_REQUEST_ID_START = -1L;

    /**
     * On disconnect: only the guest removes mirrored sprites from the stage.
     * The host merely clears id maps — its {@code localNodesById} /
     * {@code localEnemiesById} entries are real world entities.
     */
    public static boolean shouldRemoveEntitiesOnDisconnect(final boolean isWorldAuthority) {
        return !isWorldAuthority;
    }

    private final AtomicLong nextNodeId = new AtomicLong(1L);
    private final AtomicLong nextEnemyId = new AtomicLong(1L);
    private final AtomicLong nextHostLocalRequestId = new AtomicLong(HOST_LOCAL_REQUEST_ID_START);
    private final Map<Long, NodeRecord> nodes = new ConcurrentHashMap<>();
    private final Map<Long, EnemyRecord> enemies = new ConcurrentHashMap<>();
    private final CoopRateLimiter gatherRequestLimiter;
    private final CoopRateLimiter encounterRequestLimiter;
    private final float interactRangePx;

    public CoopWorldAuthority() {
        this(CoopWireLimits.DEFAULT_INTERACT_RANGE_PX, 8, 1000L);
    }

    public CoopWorldAuthority(final float interactRangePx, final int maxGatherRequestsPerWindow,
                              final long gatherWindowMs) {
        this(interactRangePx, maxGatherRequestsPerWindow, gatherWindowMs,
                CoopWireLimits.DEFAULT_ENCOUNTER_MAX_PER_WINDOW,
                CoopWireLimits.DEFAULT_ENCOUNTER_WINDOW_MS);
    }

    public CoopWorldAuthority(final float interactRangePx, final int maxGatherRequestsPerWindow,
                              final long gatherWindowMs, final int maxEncounterRequestsPerWindow,
                              final long encounterWindowMs) {
        this.interactRangePx = interactRangePx > 0f ? interactRangePx : CoopWireLimits.DEFAULT_INTERACT_RANGE_PX;
        this.gatherRequestLimiter = new CoopRateLimiter(Math.max(1, maxGatherRequestsPerWindow), gatherWindowMs);
        this.encounterRequestLimiter = new CoopRateLimiter(
                Math.max(1, maxEncounterRequestsPerWindow), Math.max(1L, encounterWindowMs));
    }

    public CoopRateLimiter getGatherRequestLimiter() {
        return gatherRequestLimiter;
    }

    public CoopRateLimiter getEncounterRequestLimiter() {
        return encounterRequestLimiter;
    }

    /** Next id for a host-local claim (negative; never sent as a gather result). */
    public long nextHostLocalRequestId() {
        return nextHostLocalRequestId.getAndDecrement();
    }

    public static boolean isGuestRequestId(final long requestId) {
        return requestId > 0L;
    }

    public static boolean isHostLocalRequestId(final long requestId) {
        return requestId < 0L;
    }

    /**
     * Host rate-limit for guest encounter requests. Does not mutate enemy state.
     * @return true if the request is allowed under the current budget
     */
    public boolean tryAcceptEncounterRequest(final long nowMs) {
        return encounterRequestLimiter.tryAcquire(nowMs);
    }

    public float getInteractRangePx() {
        return interactRangePx;
    }

    public long registerNode(final String materialId, final float x, final float y) {
        if (!CoopWireLimits.coordsInBounds(x, y)) {
            return -1L;
        }
        final String mid = CoopWireLimits.clampString(materialId, CoopWireLimits.MAX_MATERIAL_ID_LEN);
        if (mid.isEmpty() || (materialId != null && materialId.length() > CoopWireLimits.MAX_MATERIAL_ID_LEN)) {
            return -1L;
        }
        final long id = nextNodeId.getAndIncrement();
        nodes.put(id, new NodeRecord(id, mid, x, y));
        return id;
    }

    public boolean putNode(final long id, final String materialId, final float x, final float y) {
        if (id <= 0L || !CoopWireLimits.coordsInBounds(x, y)) {
            return false;
        }
        final String mid = CoopWireLimits.clampString(materialId, CoopWireLimits.MAX_MATERIAL_ID_LEN);
        if (mid.isEmpty()) {
            return false;
        }
        nodes.put(id, new NodeRecord(id, mid, x, y));
        return true;
    }

    public NodeRecord getNode(final long id) {
        return nodes.get(id);
    }

    public Collection<NodeRecord> snapshotNodes() {
        return new ArrayList<>(nodes.values());
    }

    public void removeNode(final long id) {
        nodes.remove(id);
    }

    public long registerEnemy(final String enemyDataId, final float x, final float y) {
        if (!CoopWireLimits.coordsInBounds(x, y)) {
            return -1L;
        }
        final String eid = CoopWireLimits.clampString(enemyDataId, CoopWireLimits.MAX_ENEMY_DATA_ID_LEN);
        if (eid.isEmpty()) {
            return -1L;
        }
        final long id = nextEnemyId.getAndIncrement();
        enemies.put(id, new EnemyRecord(id, eid, x, y));
        return id;
    }

    public boolean putEnemy(final long id, final String enemyDataId, final float x, final float y) {
        if (id <= 0L || !CoopWireLimits.coordsInBounds(x, y)) {
            return false;
        }
        final String eid = CoopWireLimits.clampString(enemyDataId, CoopWireLimits.MAX_ENEMY_DATA_ID_LEN);
        if (eid.isEmpty()) {
            return false;
        }
        enemies.put(id, new EnemyRecord(id, eid, x, y));
        return true;
    }

    public EnemyRecord getEnemy(final long id) {
        return enemies.get(id);
    }

    public Collection<EnemyRecord> snapshotEnemies() {
        final List<EnemyRecord> out = new ArrayList<>();
        for (final EnemyRecord e : enemies.values()) {
            if (e != null && e.alive) {
                out.add(e);
            }
        }
        return out;
    }

    public void removeEnemy(final long id) {
        enemies.remove(id);
    }

    public void updateEnemyPosition(final long id, final float x, final float y) {
        final EnemyRecord e = enemies.get(id);
        if (e == null || !e.alive) {
            return;
        }
        if (!CoopWireLimits.coordsInBounds(x, y)) {
            return;
        }
        e.x = x;
        e.y = y;
    }

    /**
     * Shared atomic claim used by host-local and guest-request paths.
     * {@code nodes.remove(id)} is the claim — whoever removes wins.
     *
     * @param posX/posY last accepted move sample (or host player pos); used for range
     * @param checkRange when true, deny (without claiming) if out of interact range
     */
    public CoopGatherResultEvent tryClaim(final long requestId, final long nodeId,
                                          final String claimant, final int lootAmount,
                                          final float posX, final float posY,
                                          final boolean checkRange) {
        final String who = CoopWireLimits.clampString(claimant, CoopWireLimits.MAX_PLAYER_NAME_LEN);
        if (nodeId <= 0L) {
            return deny(requestId, nodeId, who, "node does not exist");
        }
        final NodeRecord peek = nodes.get(nodeId);
        if (peek == null) {
            return deny(requestId, nodeId, who, "node does not exist");
        }
        if (checkRange) {
            if (!CoopWireLimits.coordsInBounds(posX, posY)) {
                return deny(requestId, nodeId, who, "invalid coordinates");
            }
            final float dx = posX - peek.x;
            final float dy = posY - peek.y;
            if (dx * dx + dy * dy > interactRangePx * interactRangePx) {
                return deny(requestId, nodeId, who, "out of range");
            }
        }
        // Atomic claim: remove wins the race between host and guest.
        final NodeRecord claimed = nodes.remove(nodeId);
        if (claimed == null) {
            return deny(requestId, nodeId, who, "already claimed");
        }
        final int amount = Math.max(0, Math.min(CoopWireLimits.MAX_GATHER_AMOUNT, lootAmount));
        return new CoopGatherResultEvent(requestId, claimed.id, true, who, claimed.materialId, amount, "");
    }

    /**
     * Host validates a guest gather request using the guest's last accepted
     * move sample for range (not the request's self-reported coords).
     */
    public CoopGatherResultEvent handleGatherRequest(final CoopGatherRequestEvent request,
                                                     final String requesterName,
                                                     final int lootAmount,
                                                     final float lastAcceptedX,
                                                     final float lastAcceptedY,
                                                     final long nowMs) {
        final String who = CoopWireLimits.clampString(requesterName, CoopWireLimits.MAX_PLAYER_NAME_LEN);
        if (request == null) {
            return deny(-1L, -1L, who, "invalid request");
        }
        if (!gatherRequestLimiter.tryAcquire(nowMs)) {
            return deny(request.getRequestId(), request.getNodeId(), who, "rate limited");
        }
        return tryClaim(request.getRequestId(), request.getNodeId(), who, lootAmount,
                lastAcceptedX, lastAcceptedY, true);
    }

    /** Host claims a node for the local player — same atomic remove. */
    public CoopGatherResultEvent claimLocal(final long requestId, final long nodeId,
                                            final String claimant, final int lootAmount,
                                            final float posX, final float posY) {
        return tryClaim(requestId, nodeId, claimant, lootAmount, posX, posY, true);
    }

    /**
     * Host claim for blast-radius extras: skips the per-node interact-range
     * check (primary node already validated the player is in range).
     */
    public CoopGatherResultEvent claimLocalSkipRange(final long requestId, final long nodeId,
                                                     final String claimant, final int lootAmount) {
        return tryClaim(requestId, nodeId, claimant, lootAmount, 0f, 0f, false);
    }

    public boolean enemyExists(final long enemyId) {
        final EnemyRecord e = enemies.get(enemyId);
        return e != null && e.alive;
    }

    public boolean denyEnemyRequest(final long enemyId) {
        return !enemyExists(enemyId);
    }

    public void clear() {
        nodes.clear();
        enemies.clear();
        gatherRequestLimiter.reset();
        encounterRequestLimiter.reset();
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int enemyCount() {
        return enemies.size();
    }

    private static CoopGatherResultEvent deny(final long requestId, final long nodeId,
                                              final String who, final String reason) {
        return new CoopGatherResultEvent(requestId, nodeId, false, who, "", 0,
                CoopWireLimits.clampString(reason, CoopWireLimits.MAX_REASON_LEN));
    }
}
