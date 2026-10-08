package forge.gamemodes.net.coop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * Stash / restore helper for the guest's local overworld entities (enemies or
 * nodes) while a co-op session mirrors the host world.
 *
 * @param <T> entity record type (caller owns sprite lifetime)
 */
public final class CoopLocalEntityStash<T> {
    private final List<T> stashed = new ArrayList<>();

    /**
     * Move matching live entities into the stash and clear {@code live}.
     * @param keep when true, entity is stashed (typically non-mirrored locals)
     */
    public void stashAndClear(final Collection<T> live, final Predicate<T> keep) {
        stashed.clear();
        if (live == null) {
            return;
        }
        for (final T item : new ArrayList<>(live)) {
            if (item != null && (keep == null || keep.test(item))) {
                stashed.add(item);
            }
        }
        live.clear();
    }

    /** Restore stashed entities into {@code live} and clear the stash. */
    public void restoreInto(final Collection<T> live) {
        if (live != null) {
            live.addAll(stashed);
        }
        stashed.clear();
    }

    public List<T> snapshot() {
        return new ArrayList<>(stashed);
    }

    public int size() {
        return stashed.size();
    }

    public boolean isEmpty() {
        return stashed.isEmpty();
    }

    public void clear() {
        stashed.clear();
    }
}
