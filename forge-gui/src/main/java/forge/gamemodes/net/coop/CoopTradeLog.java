package forge.gamemodes.net.coop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * In-character TR1 trade log keyed by trade id. Forward-only escrow phases:
 * {@link Phase#ESCROWED} → {@link Phase#DELIVERED} (or {@link Phase#REFUNDED}
 * when reconcile shows the peer never escrowed). Persisted inside the character
 * / world save blob — never a sidecar file.
 *
 * <p>Each entry stores the local role and peer character id so a restarted host
 * restores the correct role on reconcile, and so reconcile only runs with the
 * matching peer (not a different guest).
 *
 * <p><b>Inherent protocol risk:</b> a hostile peer that claims {@link Phase#NONE}
 * while we are {@link Phase#ESCROWED} can obtain a refund of our escrow. That is
 * inherent to a two-party escrow without a trusted third party.
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
        /** Role of the local player when this entry was written. */
        public final CoopTradeRole localRole;
        /** Peer's character id (name) this trade is bound to. */
        public final String peerCharacterId;

        public Entry(final long tradeId, final Phase phase, final long updatedMs,
                     final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer,
                     final CoopTradeRole localRole, final String peerCharacterId) {
            this.tradeId = tradeId;
            this.phase = phase != null ? phase : Phase.NONE;
            this.updatedMs = updatedMs;
            this.hostOffer = hostOffer != null ? hostOffer : CoopTradeOffer.empty();
            this.guestOffer = guestOffer != null ? guestOffer : CoopTradeOffer.empty();
            this.localRole = localRole != null ? localRole : CoopTradeRole.GUEST;
            this.peerCharacterId = peerCharacterId != null ? peerCharacterId : "";
        }

        public Entry(final long tradeId, final Phase phase, final long updatedMs,
                     final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer) {
            this(tradeId, phase, updatedMs, hostOffer, guestOffer, null, null);
        }

        public Entry(final long tradeId, final Phase phase, final long updatedMs) {
            this(tradeId, phase, updatedMs, null, null, null, null);
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

        public boolean matchesPeer(final String peerId) {
            if (peerId == null || peerId.isEmpty() || peerCharacterId.isEmpty()) {
                return false;
            }
            return peerCharacterId.equals(peerId);
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

    /**
     * Notified after a phase advance (triggers atomic save). If the listener
     * throws, {@link #record} returns false and the caller must not send wire
     * events (failed saves abort the send).
     */
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
        return record(tradeId, phase, nowMs, null, null, null, null);
    }

    public boolean record(final long tradeId, final Phase phase, final long nowMs,
                          final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer) {
        return record(tradeId, phase, nowMs, hostOffer, guestOffer, null, null);
    }

    /**
     * Record phase; when offers/role/peer are null, retain any previously stored
     * values for this trade id. Returns false if the phase was not advanced or
     * if the save listener failed — callers must not send wire events.
     */
    public boolean record(final long tradeId, final Phase phase, final long nowMs,
                          final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer,
                          final CoopTradeRole localRole, final String peerCharacterId) {
        if (tradeId == 0L || phase == null || phase == Phase.NONE) {
            return false;
        }
        final Entry recorded;
        final Entry previous;
        synchronized (lock) {
            previous = entries.get(tradeId);
            if (previous != null) {
                if (previous.phase == Phase.COMPLETED || previous.phase == Phase.REFUNDED) {
                    return false;
                }
                if (phase == Phase.REFUNDED) {
                    if (previous.phase != Phase.ESCROWED) {
                        return false;
                    }
                } else if (rank(phase) <= rank(previous.phase)) {
                    return false;
                }
            } else if (phase == Phase.REFUNDED) {
                return false;
            }
            final CoopTradeOffer h = hostOffer != null ? hostOffer
                    : (previous != null ? previous.hostOffer : CoopTradeOffer.empty());
            final CoopTradeOffer g = guestOffer != null ? guestOffer
                    : (previous != null ? previous.guestOffer : CoopTradeOffer.empty());
            final CoopTradeRole role = localRole != null ? localRole
                    : (previous != null ? previous.localRole : CoopTradeRole.GUEST);
            final String peer = peerCharacterId != null ? peerCharacterId
                    : (previous != null ? previous.peerCharacterId : "");
            recorded = new Entry(tradeId, phase, nowMs, h, g, role, peer);
            entries.put(tradeId, recorded);
            trimUnlocked();
        }
        final Listener l = listener;
        if (l != null) {
            try {
                l.onPhaseRecorded(recorded);
            } catch (final RuntimeException ex) {
                // Roll back so wire send is aborted with no durable commit.
                synchronized (lock) {
                    if (previous != null) {
                        entries.put(tradeId, previous);
                    } else {
                        entries.remove(tradeId);
                    }
                }
                return false;
            }
        }
        return true;
    }

    public List<Entry> snapshotInFlight() {
        synchronized (lock) {
            final List<Entry> out = new ArrayList<>();
            for (final Entry e : entries.values()) {
                if (e.phase == Phase.ESCROWED || e.phase == Phase.DELIVERED) {
                    out.add(e);
                }
            }
            return Collections.unmodifiableList(out);
        }
    }

    /** ESCROWED-only pending (awaiting deliver or refund). */
    public List<Entry> snapshotEscrowed() {
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

    public boolean hasPendingWithPeer(final String peerCharacterId) {
        if (peerCharacterId == null || peerCharacterId.isEmpty()) {
            return false;
        }
        synchronized (lock) {
            for (final Entry e : entries.values()) {
                if ((e.phase == Phase.ESCROWED || e.phase == Phase.DELIVERED)
                        && e.matchesPeer(peerCharacterId)) {
                    return true;
                }
            }
            return false;
        }
    }

    public void clear() {
        synchronized (lock) {
            entries.clear();
        }
    }

    /** Replace all entries (used when loading from character / world save). */
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
     * Encode: {@code tradeId|PHASE|ms|hostEnc|guestEnc|ROLE|peerId} lines.
     * Older 5-field lines still decode (role=GUEST, peer empty).
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
                        .append(encodeOffer(e.guestOffer)).append('|')
                        .append(e.localRole.name()).append('|')
                        .append(escape(e.peerCharacterId));
            }
            return sb.toString();
        }
    }

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

        if ((pp == Phase.DELIVERED || pp == Phase.COMPLETED)
                && (lp == Phase.NONE || lp == Phase.REFUNDED)) {
            return ReconcileAction.IGNORE_HOSTILE;
        }
        if (pp == Phase.ESCROWED && lp == Phase.NONE) {
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
                // Inherent two-party risk: hostile NONE → refund.
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

    /**
     * Trim only COMPLETED / REFUNDED. Never trim DELIVERED — a peer may still
     * need to reconcile that id.
     */
    private void trimUnlocked() {
        while (entries.size() > MAX_ENTRIES) {
            final Long oldest = entries.keySet().iterator().next();
            final Entry e = entries.get(oldest);
            if (e != null && (e.phase == Phase.COMPLETED || e.phase == Phase.REFUNDED)) {
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
            CoopTradeRole role = CoopTradeRole.GUEST;
            if (parts.length > 5 && !parts[5].isEmpty()) {
                role = CoopTradeRole.valueOf(parts[5].trim());
            }
            final String peer = parts.length > 6 ? unescape(parts[6]) : "";
            return new Entry(id, phase, ms, host, guest, role, peer);
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
