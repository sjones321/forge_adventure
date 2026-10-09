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
 * Persisted TR1 trade log keyed by trade id. Powers idempotent apply, reconnect
 * reconcile, and forward replay. Phases advance toward {@link Phase#COMPLETED}
 * or {@link Phase#ABORTED}. Full offers are stored so a mid-commit trade can be
 * replayed forward.
 *
 * <p>File format (UTF-8, no BOM):
 * <pre>
 * #slot=&lt;slotKey&gt;
 * tradeId|PHASE|updatedMs|hostOfferEnc|guestOfferEnc
 * </pre>
 * Loading a log whose {@code #slot=} does not match the bound slot key refuses
 * the file (empty log) so one character cannot replay another's commits.
 */
public final class CoopTradeLog {

    public enum Phase {
        NONE,
        EXECUTED,
        GUEST_APPLIED,
        HOST_COMMITTED,
        COMPLETED,
        ABORTED
    }

    public static final class Entry {
        public final long tradeId;
        public final Phase phase;
        public final long updatedMs;
        public final CoopTradeOffer hostOffer;
        public final CoopTradeOffer guestOffer;

        public Entry(final long tradeId, final Phase phase, final long updatedMs,
                     final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer) {
            this.tradeId = tradeId;
            this.phase = phase != null ? phase : Phase.NONE;
            this.updatedMs = updatedMs;
            this.hostOffer = hostOffer != null ? hostOffer : CoopTradeOffer.empty();
            this.guestOffer = guestOffer != null ? guestOffer : CoopTradeOffer.empty();
        }

        public Entry(final long tradeId, final Phase phase, final long updatedMs) {
            this(tradeId, phase, updatedMs, null, null);
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

    public enum ReconcileAction {
        NONE,
        RESEND_GUEST_ACK,
        RESEND_HOST_COMPLETE,
        ROLLBACK_GUEST,
        COMPLETE_GUEST,
        APPLY_HOST,
        ABORT
    }

    /** Notified after a phase advance is flushed (for character save). */
    public interface Listener {
        void onPhaseRecorded(Entry entry);
    }

    private static final int MAX_ENTRIES = 64;

    private final Object lock = new Object();
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();
    private Path persistPath;
    private String slotKey = "";
    private Listener listener;

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

    /** Bind this log to a save/character slot. Load refuses a mismatched file. */
    public void bindSlot(final String key) {
        synchronized (lock) {
            slotKey = sanitizeSlot(key);
        }
    }

    public String getSlotKey() {
        synchronized (lock) {
            return slotKey;
        }
    }

    public void setListener(final Listener listener) {
        synchronized (lock) {
            this.listener = listener;
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

    public boolean record(final long tradeId, final Phase phase, final long nowMs) {
        return record(tradeId, phase, nowMs, null, null);
    }

    /**
     * Record phase; when offers are null, retain any previously stored offers
     * for this trade id (so later phases keep replay data).
     */
    public boolean record(final long tradeId, final Phase phase, final long nowMs,
                          final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer) {
        if (tradeId == 0L || phase == null || phase == Phase.NONE) {
            return false;
        }
        final Entry recorded;
        synchronized (lock) {
            final Entry cur = entries.get(tradeId);
            if (cur != null) {
                if (cur.phase == Phase.COMPLETED || cur.phase == Phase.ABORTED) {
                    return false;
                }
                if (phase == Phase.ABORTED) {
                    if (commitRank(cur.phase) >= commitRank(Phase.HOST_COMMITTED)) {
                        return false;
                    }
                } else if (commitRank(phase) <= commitRank(cur.phase)) {
                    return false;
                }
            }
            final CoopTradeOffer h = hostOffer != null ? hostOffer
                    : (cur != null ? cur.hostOffer : CoopTradeOffer.empty());
            final CoopTradeOffer g = guestOffer != null ? guestOffer
                    : (cur != null ? cur.guestOffer : CoopTradeOffer.empty());
            recorded = new Entry(tradeId, phase, nowMs, h, g);
            entries.put(tradeId, recorded);
            trimUnlocked();
            flushUnlocked();
        }
        final Listener l = listener;
        if (l != null) {
            try {
                l.onPhaseRecorded(recorded);
            } catch (final RuntimeException ignored) {
            }
        }
        return true;
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
                String line = reader.readLine();
                if (line == null) {
                    return;
                }
                String fileSlot = "";
                if (line.startsWith("#slot=")) {
                    fileSlot = sanitizeSlot(line.substring(6));
                    if (!slotKey.isEmpty() && !slotKey.equals(fileSlot)) {
                        // Wrong character / save slot — refuse replay.
                        entries.clear();
                        return;
                    }
                    line = reader.readLine();
                } else if (!slotKey.isEmpty()) {
                    // Legacy file without slot header while we have a binding — refuse.
                    entries.clear();
                    return;
                }
                while (line != null) {
                    final Entry e = parseLine(line);
                    if (e != null) {
                        entries.put(e.tradeId, e);
                    }
                    line = reader.readLine();
                }
                trimUnlocked();
            } catch (final IOException ignored) {
                entries.clear();
            }
        }
    }

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

    /** Compact offer encoding for the tradelog (no new deps). */
    public static String encodeOffer(final CoopTradeOffer offer) {
        final CoopTradeOffer o = offer != null ? offer : CoopTradeOffer.empty();
        final StringBuilder sb = new StringBuilder();
        sb.append(o.getGold()).append(';');
        boolean first = true;
        for (final CoopTradeOffer.Line line : o.getMaterials()) {
            if (line == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(escape(line.getId())).append('=').append(line.getCount());
        }
        sb.append(';');
        first = true;
        for (final CoopTradeOffer.Line line : o.getItems()) {
            if (line == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(escape(line.getId())).append('=').append(line.getCount());
        }
        sb.append(';');
        first = true;
        for (final CoopTradeOffer.CardLine line : o.getCards()) {
            if (line == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(escape(line.getName())).append('~')
                    .append(escape(line.getSetCode())).append('~')
                    .append(line.getArtIndex()).append('=').append(line.getCount());
        }
        return sb.toString();
    }

    public static CoopTradeOffer decodeOffer(final String enc) {
        if (enc == null || enc.isEmpty()) {
            return CoopTradeOffer.empty();
        }
        final String[] parts = enc.split(";", -1);
        int gold = 0;
        try {
            gold = Integer.parseInt(parts[0]);
        } catch (final NumberFormatException ignored) {
            gold = 0;
        }
        final List<CoopTradeOffer.Line> mats = decodeLines(parts.length > 1 ? parts[1] : "");
        final List<CoopTradeOffer.Line> items = decodeLines(parts.length > 2 ? parts[2] : "");
        final List<CoopTradeOffer.CardLine> cards = decodeCards(parts.length > 3 ? parts[3] : "");
        return new CoopTradeOffer(gold, mats, items, cards);
    }

    private static List<CoopTradeOffer.Line> decodeLines(final String s) {
        final List<CoopTradeOffer.Line> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (final String piece : s.split(",", -1)) {
            if (piece.isEmpty()) {
                continue;
            }
            final int eq = piece.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                out.add(new CoopTradeOffer.Line(unescape(piece.substring(0, eq)),
                        Integer.parseInt(piece.substring(eq + 1))));
            } catch (final NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static List<CoopTradeOffer.CardLine> decodeCards(final String s) {
        final List<CoopTradeOffer.CardLine> out = new ArrayList<>();
        if (s == null || s.isEmpty()) {
            return out;
        }
        for (final String piece : s.split(",", -1)) {
            if (piece.isEmpty()) {
                continue;
            }
            final int eq = piece.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String left = piece.substring(0, eq);
            final String[] id = left.split("~", -1);
            if (id.length < 3) {
                continue;
            }
            try {
                out.add(new CoopTradeOffer.CardLine(unescape(id[0]), unescape(id[1]),
                        Integer.parseInt(id[2]), Integer.parseInt(piece.substring(eq + 1))));
            } catch (final NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static String escape(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("%", "%25").replace(";", "%3B").replace(",", "%2C")
                .replace("=", "%3D").replace("~", "%7E").replace("|", "%7C")
                .replace("\n", "%0A");
    }

    private static String unescape(final String s) {
        if (s == null) {
            return "";
        }
        return s.replace("%0A", "\n").replace("%7C", "|").replace("%7E", "~")
                .replace("%3D", "=").replace("%2C", ",").replace("%3B", ";")
                .replace("%25", "%");
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
                writer.write("#slot=" + slotKey);
                writer.newLine();
                for (final Entry e : entries.values()) {
                    writer.write(e.tradeId + "|" + e.phase.name() + "|" + e.updatedMs
                            + "|" + encodeOffer(e.hostOffer) + "|" + encodeOffer(e.guestOffer));
                    writer.newLine();
                }
            }
        } catch (final IOException ignored) {
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
            if (id == 0L || phase == Phase.NONE) {
                return null;
            }
            final CoopTradeOffer host = parts.length > 3 ? decodeOffer(parts[3]) : CoopTradeOffer.empty();
            final CoopTradeOffer guest = parts.length > 4 ? decodeOffer(parts[4]) : CoopTradeOffer.empty();
            return new Entry(id, phase, ms, host, guest);
        } catch (final RuntimeException ex) {
            return null;
        }
    }

    private static String sanitizeSlot(final String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return key.replaceAll("[^a-zA-Z0-9._@-]", "_");
    }

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
