package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.NetEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * FIFO queue for host world events deferred while the shared sim is paused
 * (interior / duel). SPAWN and DESPAWN/CLAIMED share one queue so a despawn
 * never races ahead of its earlier queued spawn.
 */
public final class CoopPausedEventQueue {
    private final List<NetEvent> events = new ArrayList<>();

    public synchronized void enqueue(final NetEvent event) {
        if (event != null) {
            events.add(event);
        }
    }

    /** Drain all queued events in enqueue order. */
    public synchronized List<NetEvent> drain() {
        if (events.isEmpty()) {
            return List.of();
        }
        final List<NetEvent> copy = new ArrayList<>(events);
        events.clear();
        return copy;
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized boolean isEmpty() {
        return events.isEmpty();
    }

    public synchronized void clear() {
        events.clear();
    }
}
