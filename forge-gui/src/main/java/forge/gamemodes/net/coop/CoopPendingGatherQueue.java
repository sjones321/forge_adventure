package forge.gamemodes.net.coop;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guest-side queue of in-flight gather requests (CO2 follow-up). Allows more
 * than one pending claim at a time; results are matched by request id.
 */
public final class CoopPendingGatherQueue {
    public static final class Entry {
        public final long requestId;
        public final long nodeId;
        public final String materialId;

        public Entry(final long requestId, final long nodeId, final String materialId) {
            this.requestId = requestId;
            this.nodeId = nodeId;
            this.materialId = materialId == null ? "" : materialId;
        }
    }

    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();

    public void add(final long requestId, final long nodeId, final String materialId) {
        if (requestId <= 0L || nodeId <= 0L) {
            return;
        }
        pending.put(requestId, new Entry(requestId, nodeId, materialId));
    }

    /** @return removed entry, or {@code null} if unknown */
    public Entry take(final long requestId) {
        return pending.remove(requestId);
    }

    public Entry peek(final long requestId) {
        return pending.get(requestId);
    }

    public boolean contains(final long requestId) {
        return pending.containsKey(requestId);
    }

    public int size() {
        return pending.size();
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public Collection<Entry> snapshot() {
        return pending.values();
    }

    public void clear() {
        pending.clear();
    }
}
