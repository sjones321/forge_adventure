package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Host-side authoritative bookkeeping for shared overworld entities (CO2):
 * resource nodes (first claim wins), enemy existence checks, and gather
 * validation. Pure Java — no LibGDX / textures / saves.
 */
public final class CoopWorldAuthority {
    public static final class NodeRecord {
        public final long id;
        public final String materialId;
        public final float x;
        public final float y;
        public volatile boolean claimed;
        public volatile String claimedBy = "";

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

    private final AtomicLong nextNodeId = new AtomicLong(1L);
    private final AtomicLong nextEnemyId = new AtomicLong(1L);
    private final Map<Long, NodeRecord> nodes = new ConcurrentHashMap<>();
    private final Map<Long, EnemyRecord> enemies = new ConcurrentHashMap<>();
    private final CoopRateLimiter gatherRequestLimiter;
    private final float interactRangePx;

    public CoopWorldAuthority() {
        this(CoopWireLimits.DEFAULT_INTERACT_RANGE_PX, 8, 1000L);
    }

    public CoopWorldAuthority(final float interactRangePx, final int maxGatherRequestsPerWindow,
                              final long gatherWindowMs) {
        this.interactRangePx = interactRangePx > 0f ? interactRangePx : CoopWireLimits.DEFAULT_INTERACT_RANGE_PX;
        this.gatherRequestLimiter = new CoopRateLimiter(Math.max(1, maxGatherRequestsPerWindow), gatherWindowMs);
    }

    public CoopRateLimiter getGatherRequestLimiter() {
        return gatherRequestLimiter;
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

    /** Register a node with a pre-assigned id (guest applying a host SPAWN). */
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
     * Host validates a guest gather request. First claim wins; out-of-range,
     * unknown node, already claimed, or rate-limited → denied.
     */
    public CoopGatherResultEvent handleGatherRequest(final CoopGatherRequestEvent request,
                                                     final String requesterName,
                                                     final int lootAmount) {
        return handleGatherRequest(request, requesterName, lootAmount, System.currentTimeMillis());
    }

    public CoopGatherResultEvent handleGatherRequest(final CoopGatherRequestEvent request,
                                                     final String requesterName,
                                                     final int lootAmount,
                                                     final long nowMs) {
        final String who = CoopWireLimits.clampString(requesterName, CoopWireLimits.MAX_PLAYER_NAME_LEN);
        if (request == null) {
            return deny(-1L, who, "invalid request");
        }
        if (!gatherRequestLimiter.tryAcquire(nowMs)) {
            return deny(request.getNodeId(), who, "rate limited");
        }
        if (!CoopWireLimits.coordsInBounds(request.getRequesterX(), request.getRequesterY())) {
            return deny(request.getNodeId(), who, "invalid coordinates");
        }
        final NodeRecord node = nodes.get(request.getNodeId());
        if (node == null) {
            return deny(request.getNodeId(), who, "node does not exist");
        }
        if (node.claimed) {
            return deny(request.getNodeId(), who, "already claimed");
        }
        final float dx = request.getRequesterX() - node.x;
        final float dy = request.getRequesterY() - node.y;
        if (dx * dx + dy * dy > interactRangePx * interactRangePx) {
            return deny(request.getNodeId(), who, "out of range");
        }
        final int amount = Math.max(0, Math.min(CoopWireLimits.MAX_GATHER_AMOUNT, lootAmount));
        node.claimed = true;
        node.claimedBy = who;
        nodes.remove(node.id);
        return new CoopGatherResultEvent(node.id, true, who, node.materialId, amount, "");
    }

    /**
     * Host claims a node for the local (host) player. Same first-wins semantics.
     */
    public CoopGatherResultEvent claimLocal(final long nodeId, final String claimant, final int lootAmount) {
        final String who = CoopWireLimits.clampString(claimant, CoopWireLimits.MAX_PLAYER_NAME_LEN);
        final NodeRecord node = nodes.get(nodeId);
        if (node == null) {
            return deny(nodeId, who, "node does not exist");
        }
        if (node.claimed) {
            return deny(nodeId, who, "already claimed");
        }
        final int amount = Math.max(0, Math.min(CoopWireLimits.MAX_GATHER_AMOUNT, lootAmount));
        node.claimed = true;
        node.claimedBy = who;
        nodes.remove(node.id);
        return new CoopGatherResultEvent(node.id, true, who, node.materialId, amount, "");
    }

    /** Guest request for a non-existent / dead enemy — always deny (CO2 validation). */
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
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int enemyCount() {
        return enemies.size();
    }

    private static CoopGatherResultEvent deny(final long nodeId, final String who, final String reason) {
        return new CoopGatherResultEvent(nodeId, false, who, "", 0,
                CoopWireLimits.clampString(reason, CoopWireLimits.MAX_REASON_LEN));
    }
}
