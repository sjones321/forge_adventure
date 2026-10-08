package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopDuelInviteEvent;
import forge.gamemodes.net.event.coop.CoopDuelResponseEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless "Join the fight?" state machine (CO3). Opt-in only: not in a party
 * never prompts; timeout / decline / no answer → solo fight.
 *
 * <p>Does not block. Callers schedule their own timer and invoke
 * {@link #timeout(long)} when it fires — never {@code sendAndWait}.
 */
public final class CoopDuelInviteState {
    public enum Status {
        IDLE,
        /** Local peer started a fight and is waiting on the partner. */
        WAITING_RESPONSE,
        /** Local peer received an invite and has not answered. */
        PROMPT_OPEN,
        /** Partner accepted; co-op match should be built. */
        JOINED,
        /** Timed out, declined, or not eligible → proceed solo. */
        SOLO
    }

    private final AtomicLong inviteSeq = new AtomicLong(1L);
    private volatile Status status = Status.IDLE;
    private volatile long pendingInviteId;
    private volatile String encounterId = "";
    private volatile String hostPlayer = "";
    private volatile String acceptedDecklist = "";

    public Status getStatus() {
        return status;
    }

    public long getPendingInviteId() {
        return pendingInviteId;
    }

    public String getEncounterId() {
        return encounterId;
    }

    public String getHostPlayer() {
        return hostPlayer;
    }

    public String getAcceptedDecklist() {
        return acceptedDecklist;
    }

    public boolean isWaiting() {
        return status == Status.WAITING_RESPONSE || status == Status.PROMPT_OPEN;
    }

    public boolean isJoined() {
        return status == Status.JOINED;
    }

    /**
     * Whether a fight start should offer a join prompt.
     * Not in a party / partner not nearby → false (solo, never prompted).
     */
    public static boolean shouldOfferJoin(final CoopPartyProximity proximity) {
        final CoopPartyProximity p = proximity != null ? proximity : CoopPartyProximity.NEVER;
        return p.partnerInPartyAndNearby();
    }

    /**
     * Local fighter starts an invite for a nearby party partner.
     * @return wire event to send, or null if not eligible / already busy
     */
    public CoopDuelInviteEvent beginInvite(final String hostPlayerName, final String encounter,
                                          final int timeoutSeconds, final CoopPartyProximity proximity) {
        if (status != Status.IDLE) {
            return null;
        }
        if (!shouldOfferJoin(proximity)) {
            status = Status.SOLO;
            return null;
        }
        final String host = CoopDuelWireLimits.clampString(hostPlayerName, CoopDuelWireLimits.MAX_NAME_LEN);
        final String enc = CoopDuelWireLimits.clampString(encounter, CoopDuelWireLimits.MAX_NAME_LEN);
        if (host.isEmpty() || enc.isEmpty()) {
            status = Status.SOLO;
            return null;
        }
        final int timeout = Math.max(1, Math.min(timeoutSeconds, 120));
        final long id = inviteSeq.getAndIncrement();
        pendingInviteId = id;
        hostPlayer = host;
        encounterId = enc;
        acceptedDecklist = "";
        status = Status.WAITING_RESPONSE;
        return new CoopDuelInviteEvent(id, host, enc, timeout);
    }

    /** Inbound invite from the peer — open the local prompt. */
    public boolean receiveInvite(final CoopDuelInviteEvent invite) {
        if (invite == null || status != Status.IDLE) {
            return false;
        }
        if (invite.getInviteId() <= 0L) {
            return false;
        }
        final String host = CoopDuelWireLimits.clampString(invite.getHostPlayer(), CoopDuelWireLimits.MAX_NAME_LEN);
        final String enc = CoopDuelWireLimits.clampString(invite.getEncounterId(), CoopDuelWireLimits.MAX_NAME_LEN);
        if (host.isEmpty() || enc.isEmpty()) {
            return false;
        }
        if (invite.getHostPlayer() != null && invite.getHostPlayer().length() > CoopDuelWireLimits.MAX_NAME_LEN) {
            return false;
        }
        if (invite.getEncounterId() != null && invite.getEncounterId().length() > CoopDuelWireLimits.MAX_NAME_LEN) {
            return false;
        }
        pendingInviteId = invite.getInviteId();
        hostPlayer = host;
        encounterId = enc;
        acceptedDecklist = "";
        status = Status.PROMPT_OPEN;
        return true;
    }

    /**
     * Local accept / decline while a prompt is open.
     * @return wire response to send, or null if not applicable
     */
    public CoopDuelResponseEvent respond(final boolean accepted, final String decklistText) {
        if (status != Status.PROMPT_OPEN) {
            return null;
        }
        final long id = pendingInviteId;
        if (accepted) {
            final String deck = decklistText != null ? decklistText : "";
            if (!CoopDuelWireLimits.decklistSizeOk(deck)) {
                status = Status.SOLO;
                return new CoopDuelResponseEvent(id, false, "");
            }
            acceptedDecklist = deck;
            status = Status.JOINED;
            return new CoopDuelResponseEvent(id, true, deck);
        }
        status = Status.SOLO;
        acceptedDecklist = "";
        return new CoopDuelResponseEvent(id, false, "");
    }

    /** Apply a peer response to a local WAITING_RESPONSE invite. */
    public boolean applyPeerResponse(final CoopDuelResponseEvent response) {
        if (response == null || status != Status.WAITING_RESPONSE) {
            return false;
        }
        if (response.getInviteId() != pendingInviteId) {
            return false;
        }
        if (response.isAccepted()) {
            final String deck = response.getDecklistText() != null ? response.getDecklistText() : "";
            if (!CoopDuelWireLimits.decklistSizeOk(deck)) {
                status = Status.SOLO;
                acceptedDecklist = "";
                return true;
            }
            acceptedDecklist = deck;
            status = Status.JOINED;
            return true;
        }
        status = Status.SOLO;
        acceptedDecklist = "";
        return true;
    }

    /** Timer fired with no answer → solo. */
    public boolean timeout(final long inviteId) {
        if (inviteId != pendingInviteId) {
            return false;
        }
        if (status != Status.WAITING_RESPONSE && status != Status.PROMPT_OPEN) {
            return false;
        }
        status = Status.SOLO;
        acceptedDecklist = "";
        return true;
    }

    /** Reset after the fight starts or is cancelled. */
    public void clear() {
        status = Status.IDLE;
        pendingInviteId = 0L;
        encounterId = "";
        hostPlayer = "";
        acceptedDecklist = "";
    }

    /** Force solo without an invite (not in party path). */
    public void markSolo() {
        status = Status.SOLO;
        acceptedDecklist = "";
    }
}
