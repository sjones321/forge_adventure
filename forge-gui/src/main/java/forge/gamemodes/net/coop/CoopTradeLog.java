package forge.gamemodes.net.coop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * In-character TR1 trade log keyed by trade id. Forward-only escrow phases:
 * {@link Phase#ESCROWED} → {@link Phase#DELIVERED} (or {@link Phase#REFUNDED}
 * when reconcile shows the peer never escrowed). Persisted inside the character
 * save blob — never a sidecar file.
 */
public final class CoopTradeLog {

    public enum Phase {
        NONE,
        ESCROWED,
        DELIVERED,
        REFUNDED,
        COMPLETED
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
            if (phase == Phase.REFUNDED) {
                return other == Phase.REFUNDED || other == Phase.NONE;
            }
            if (other == Phase.REFUNDED) {
                return phase == Phase.REFUNDED;
            }
            return rank(phase) >= rank(other);
        }
    }

    public enum ReconcileAction {
        NONE,
        /** Peer escrowed (or delivered) — grant peer offer if we escrowed. */
        DELIVER,
        /** Peer never escrowed — refund our own escrow only. */
        REFUND,
        /** Peer needs our escrowed(id) again. */
        RESEND_ESCROWED,
        /** Peer needs our delivered(id) again. */
        RESEND_DELIVERED,
        /** Mark local trade complete. */
        COMPLETE,
        /** Hostile / unknown id / unreached step — ignore. */
        IGNORE_HOSTILE
    }

    /** Notified after a phase advance (triggers atomic character save). */
    public interface Listener {
        void onPhaseRecorded(Entry entry);
    }

    private static final int MAX_ENTRIES = 64;

    private final Object lock = new Object();
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();
    private Listener listener;

    public CoopTradeLog() {
    }

    public void setListener(final Listener listener) {
        synchronized (lock) {
            this.listener = listener;
        }
    }

    public boolean contains(final long tradeId) {
        synchronized (lock) {
            return tradeId != 0L && entries.containsKey(tradeId);
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

    public boolean hasEscrowed(final long tradeId) {
        return isAtLeast(tradeId, Phase.ESCROWED)
                && !isAtLeast(tradeId, Phase.REFUNDED);
    }

    public boolean hasDelivered(final long tradeId) {
        return isAtLeast(tradeId, Phase.DELIVERED);
    }

    public boolean record(final long tradeId, final Phase phase, final long nowMs) {
        return record(tradeId, phase, nowMs, null, null);
    }

    /**
     * Record phase; when offers are null, retain any previously stored offers
     * for this trade id.
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
                if (cur.phase == Phase.COMPLETED || cur.phase == Phase.REFUNDED) {
                    return false;
                }
                if (phase == Phase.REFUNDED) {
                    // Refund only from ESCROWED (never after DELIVERED).
                    if (cur.phase != Phase.ESCROWED) {
                        return false;
                    }
                } else if (rank(phase) <= rank(cur.phase)) {
                    return false;
                }
            } else if (phase == Phase.REFUNDED) {
                return false;
            }
            final CoopTradeOffer h = hostOffer != null ? hostOffer
                    : (cur != null ? cur.hostOffer : CoopTradeOffer.empty());
            final CoopTradeOffer g = guestOffer != null ? guestOffer
                    : (cur != null ? cur.guestOffer : CoopTradeOffer.empty());
            recorded = new Entry(tradeId, phase, nowMs, h, g);
            entries.put(tradeId, recorded);
            trimUnlocked();
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
                if (e.phase == Phase.ESCROWED) {
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
        }
    }

    /** Replace all entries (used when loading from character save). */
    public void replaceAll(final List<Entry> loaded) {
        synchronized (lock) {
            entries.clear();
            if (loaded != null) {
                for (final Entry e : loaded) {
                    if (e != null && e.tradeId != 0L && e.phase != Phase.NONE) {
                        entries.put(e.tradeId, e);
                    }
                }
            }
            trimUnlocked();
        }
    }

    /**
     * Encode the full log as a single string for {@code AdventurePlayer} save.
     * Format: {@code tradeId|PHASE|ms|hostEnc|guestEnc} lines joined by {@code \n}.
     */
    public String encode() {
        synchronized (lock) {
            final StringBuilder sb = new StringBuilder();
            for (final Entry e : entries.values()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(e.tradeId).append('|').append(e.phase.name()).append('|')
                        .append(e.updatedMs).append('|')
                        .append(encodeOffer(e.hostOffer)).append('|')
                        .append(encodeOffer(e.guestOffer));
            }
            return sb.toString();
        }
    }

    /** Load from a character-save blob previously produced by {@link #encode()}. */
    public void decode(final String blob) {
        final List<Entry> loaded = new ArrayList<>();
        if (blob != null && !blob.isEmpty()) {
            for (final String line : blob.split("\n", -1)) {
                final Entry e = parseLine(line);
                if (e != null) {
                    loaded.add(e);
                }
            }
        }
        replaceAll(loaded);
    }

    public static ReconcileAction reconcile(final Entry local, final Entry peer) {
        final Phase lp = local != null ? local.phase : Phase.NONE;
        final Phase pp = peer != null ? peer.phase : Phase.NONE;

        // Hostile: peer claims DELIVERED/COMPLETED without us ever escrowing,
        // or for a trade we refunded — ignore (do not grant).
        if ((pp == Phase.DELIVERED || pp == Phase.COMPLETED)
                && (lp == Phase.NONE || lp == Phase.REFUNDED)) {
            return ReconcileAction.IGNORE_HOSTILE;
        }
        if (pp == Phase.ESCROWED && lp == Phase.NONE) {
            // Peer escrowed but we have no record — we never confirmed/escrowed.
            return ReconcileAction.IGNORE_HOSTILE;
        }

        if (lp == Phase.COMPLETED || lp == Phase.DELIVERED) {
            if (pp == Phase.ESCROWED || pp == Phase.NONE) {
                return ReconcileAction.RESEND_DELIVERED;
            }
            return ReconcileAction.COMPLETE;
        }

        if (lp == Phase.REFUNDED) {
            return ReconcileAction.NONE;
        }

        if (lp == Phase.ESCROWED) {
            if (pp == Phase.ESCROWED || pp == Phase.DELIVERED || pp == Phase.COMPLETED) {
                return ReconcileAction.DELIVER;
            }
            if (pp == Phase.NONE || pp == Phase.REFUNDED) {
                // Peer never escrowed (or already refunded) — refund our escrow.
                return ReconcileAction.REFUND;
            }
        }

        if (lp == Phase.NONE) {
            if (pp == Phase.NONE || pp == Phase.REFUNDED) {
                return ReconcileAction.NONE;
            }
            return ReconcileAction.IGNORE_HOSTILE;
        }

        return ReconcileAction.NONE;
    }

    /** Compact offer encoding for the trade log (no new deps). */
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
            if (e != null && (e.phase == Phase.COMPLETED || e.phase == Phase.REFUNDED
                    || e.phase == Phase.DELIVERED)) {
                entries.remove(oldest);
            } else {
                break;
            }
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

    private static int rank(final Phase phase) {
        if (phase == null) {
            return 0;
        }
        switch (phase) {
            case NONE: return 0;
            case ESCROWED: return 1;
            case DELIVERED: return 2;
            case COMPLETED: return 3;
            case REFUNDED: return -1;
            default: return 0;
        }
    }
}
