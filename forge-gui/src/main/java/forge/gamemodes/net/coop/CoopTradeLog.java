package forge.gamemodes.net.coop;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Persisted TR1 trade log keyed by trade id. Powers idempotent apply and
 * reconnect reconcile. Phases advance toward {@link Phase#COMPLETED} or
 * {@link Phase#ABORTED}; re-recording the same or an earlier phase is a no-op.
 *
 * <p>File format (UTF-8, no BOM): one {@code tradeId|PHASE|updatedMs} line per
 * entry. Missing file = empty log.
 */
public final class CoopTradeLog {

    public enum Phase {
        NONE,
        /** Execute emitted / received; bags not yet mutated on this side. */
        EXECUTED,
        /** Guest bag mutated; waiting for host commit. */
        GUEST_APPLIED,
        /** Host bag mutated — the commit point. Guest must not roll back after this. */
        HOST_COMMITTED,
        /** Both sides finished; apply is durable and idempotent. */
        COMPLETED,
        /** Terminal abort — not ordered above commit; see {@link #commitRank}. */
        ABORTED
    }

    public static final class Entry {
        public final long tradeId;
        public final Phase phase;
        public final long updatedMs;

        public Entry(final long tradeId, final Phase phase, final long updatedMs) {
            this.tradeId = tradeId;
            this.phase = phase != null ? phase : Phase.NONE;
            this.updatedMs = updatedMs;
        }

        public boolean isAtLeast(final Phase other) {
            if (phase == Phase.ABORTED) {
                return other == Phase.ABORTED || other == Phase.NONE;
            }
            if (other == Phase.ABORTED) {
                return phase == Phase.ABORTED;
            }
            return commitRank(phase) >= commitRank(other);
        }
    }

    /** What reconnect reconcile should do for one trade id. */
    public enum ReconcileAction {
        NONE,
        /** Guest should re-send its success ack. */
        RESEND_GUEST_ACK,
        /** Host should re-send complete ack. */
        RESEND_HOST_COMPLETE,
        /** Guest must roll back (host never committed). */
        ROLLBACK_GUEST,
        /** Guest applied and host committed — keep apply, mark complete. */
        COMPLETE_GUEST,
        /** Host should apply now (guest applied; host never did). */
        APPLY_HOST,
        /** Both sides aborted / idle. */
        ABORT
    }

    private static final int MAX_ENTRIES = 64;

    private final Object lock = new Object();
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();
    private Path persistPath;

    public CoopTradeLog() {
    }

    public void setPersistPath(final Path path) {
        synchronized (lock) {
            persistPath = path;
        }
    }

    public Path getPersistPath() {
        synchronized (lock) {
            return persistPath;
        }
    }

    public Entry get(final long tradeId) {
        synchronized (lock) {
            return entries.get(tradeId);
        }
    }

    public boolean isAtLeast(final long tradeId, final Phase phase) {
        synchronized (lock) {
            final Entry e = entries.get(tradeId);
            return e != null && e.isAtLeast(phase);
        }
    }

    /**
     * True when this side has already mutated its bag for {@code tradeId}
     * (idempotent apply must become a no-op).
     */
    public boolean hasLocalApply(final long tradeId, final CoopTradeRole role) {
        synchronized (lock) {
            final Entry e = entries.get(tradeId);
            if (e == null || e.phase == Phase.ABORTED) {
                return false;
            }
            if (e.phase == Phase.COMPLETED) {
                return true;
            }
            if (role == CoopTradeRole.GUEST) {
                return commitRank(e.phase) >= commitRank(Phase.GUEST_APPLIED);
            }
            return commitRank(e.phase) >= commitRank(Phase.HOST_COMMITTED);
        }
    }

    /**
     * Record {@code phase} for {@code tradeId}. Same or earlier phase is a no-op.
     * @return true when the log advanced
     */
    public boolean record(final long tradeId, final Phase phase, final long nowMs) {
        if (tradeId <= 0L || phase == null || phase == Phase.NONE) {
            return false;
        }
        synchronized (lock) {
            final Entry cur = entries.get(tradeId);
            if (cur != null) {
                if (cur.phase == Phase.COMPLETED || cur.phase == Phase.ABORTED) {
                    return false;
                }
                // ABORTED only wins before HOST_COMMITTED / COMPLETED.
                if (phase == Phase.ABORTED) {
                    if (commitRank(cur.phase) >= commitRank(Phase.HOST_COMMITTED)) {
                        return false;
                    }
                } else if (commitRank(phase) <= commitRank(cur.phase)) {
                    return false;
                }
            }
            entries.put(tradeId, new Entry(tradeId, phase, nowMs));
            trimUnlocked();
            flushUnlocked();
            return true;
        }
    }

    public List<Entry> snapshotInFlight() {
        synchronized (lock) {
            final List<Entry> out = new ArrayList<>();
            for (final Entry e : entries.values()) {
                if (e.phase != Phase.COMPLETED && e.phase != Phase.ABORTED && e.phase != Phase.NONE) {
                    out.add(e);
                }
            }
            return Collections.unmodifiableList(out);
        }
    }

    public List<Entry> snapshotAll() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<>(entries.values()));
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.clear();
            flushUnlocked();
        }
    }

    public void load() {
        synchronized (lock) {
            entries.clear();
            if (persistPath == null || !Files.isRegularFile(persistPath)) {
                return;
            }
            try (BufferedReader reader = Files.newBufferedReader(persistPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final Entry e = parseLine(line);
                    if (e != null) {
                        entries.put(e.tradeId, e);
                    }
                }
                trimUnlocked();
            } catch (final IOException ignored) {
                entries.clear();
            }
        }
    }

    /**
     * Decide reconnect action from local and peer log phases for one trade id.
     */
    public static ReconcileAction reconcile(final Entry local, final Entry peer) {
        final Phase lp = local != null ? local.phase : Phase.NONE;
        final Phase pp = peer != null ? peer.phase : Phase.NONE;
        if (lp == Phase.COMPLETED || pp == Phase.COMPLETED) {
            if (lp == Phase.GUEST_APPLIED || lp == Phase.EXECUTED || lp == Phase.NONE) {
                return ReconcileAction.COMPLETE_GUEST;
            }
            if (lp == Phase.HOST_COMMITTED) {
                return ReconcileAction.RESEND_HOST_COMPLETE;
            }
            return ReconcileAction.NONE;
        }
        if (pp == Phase.HOST_COMMITTED || pp == Phase.COMPLETED) {
            if (lp == Phase.GUEST_APPLIED || lp == Phase.EXECUTED) {
                return ReconcileAction.COMPLETE_GUEST;
            }
            if (lp == Phase.NONE || lp == Phase.ABORTED) {
                // Peer committed but we have no local apply — host side apply missing.
                return ReconcileAction.APPLY_HOST;
            }
            if (lp == Phase.HOST_COMMITTED) {
                return ReconcileAction.RESEND_HOST_COMPLETE;
            }
        }
        if (lp == Phase.HOST_COMMITTED) {
            return ReconcileAction.RESEND_HOST_COMPLETE;
        }
        if (lp == Phase.GUEST_APPLIED && (pp == Phase.NONE || pp == Phase.EXECUTED || pp == Phase.ABORTED)) {
            if (pp == Phase.ABORTED) {
                return ReconcileAction.ROLLBACK_GUEST;
            }
            return ReconcileAction.RESEND_GUEST_ACK;
        }
        if (lp == Phase.GUEST_APPLIED && pp == Phase.GUEST_APPLIED) {
            return ReconcileAction.RESEND_GUEST_ACK;
        }
        if ((lp == Phase.EXECUTED || lp == Phase.NONE) && pp == Phase.GUEST_APPLIED) {
            return ReconcileAction.APPLY_HOST;
        }
        if (lp == Phase.ABORTED || pp == Phase.ABORTED) {
            if (lp == Phase.GUEST_APPLIED) {
                return ReconcileAction.ROLLBACK_GUEST;
            }
            return ReconcileAction.ABORT;
        }
        return ReconcileAction.NONE;
    }

    private void trimUnlocked() {
        while (entries.size() > MAX_ENTRIES) {
            final Long oldest = entries.keySet().iterator().next();
            final Entry e = entries.get(oldest);
            if (e != null && (e.phase == Phase.COMPLETED || e.phase == Phase.ABORTED)) {
                entries.remove(oldest);
            } else {
                break;
            }
        }
    }

    private void flushUnlocked() {
        if (persistPath == null) {
            return;
        }
        try {
            final Path parent = persistPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter writer = Files.newBufferedWriter(persistPath, StandardCharsets.UTF_8)) {
                for (final Entry e : entries.values()) {
                    writer.write(e.tradeId + "|" + e.phase.name() + "|" + e.updatedMs);
                    writer.newLine();
                }
            }
        } catch (final IOException ignored) {
            // Best-effort persistence; in-memory log remains authoritative for the session.
        }
    }

    private static Entry parseLine(final String line) {
        if (line == null || line.isEmpty() || line.charAt(0) == '#') {
            return null;
        }
        final String[] parts = line.split("\\|", -1);
        if (parts.length < 2) {
            return null;
        }
        try {
            final long id = Long.parseLong(parts[0].trim());
            final Phase phase = Phase.valueOf(parts[1].trim());
            final long ms = parts.length > 2 ? Long.parseLong(parts[2].trim()) : 0L;
            if (id <= 0L || phase == Phase.NONE) {
                return null;
            }
            return new Entry(id, phase, ms);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    /** Commit-path ranking; {@link Phase#ABORTED} is not on this path. */
    private static int commitRank(final Phase phase) {
        if (phase == null) {
            return 0;
        }
        switch (phase) {
            case NONE: return 0;
            case EXECUTED: return 1;
            case GUEST_APPLIED: return 2;
            case HOST_COMMITTED: return 3;
            case COMPLETED: return 4;
            case ABORTED: return -1;
            default: return 0;
        }
    }
}
