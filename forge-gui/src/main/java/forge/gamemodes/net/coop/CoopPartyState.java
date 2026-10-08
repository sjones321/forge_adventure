package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless party invite / accept / decline / leave state machine (CO2).
 * Players are independent by default; nobody is moved without ACCEPT.
 */
public final class CoopPartyState {
    public enum Status { SOLO, INVITE_SENT, INVITE_RECEIVED, PARTY }

    private final AtomicLong inviteSeq = new AtomicLong(1L);
    private volatile Status status = Status.SOLO;
    private volatile long pendingInviteId;
    private volatile String pendingFrom = "";
    private volatile String partnerName = "";

    public Status getStatus() {
        return status;
    }

    public boolean inParty() {
        return status == Status.PARTY;
    }

    public String getPartnerName() {
        return partnerName;
    }

    public long getPendingInviteId() {
        return pendingInviteId;
    }

    public String getPendingFrom() {
        return pendingFrom;
    }

    /** Local player starts an invite. */
    public CoopPartyInviteEvent createInvite(final String fromPlayer) {
        if (status == Status.PARTY || status == Status.INVITE_SENT) {
            return null;
        }
        final String from = CoopWireLimits.clampString(fromPlayer, CoopWireLimits.MAX_PLAYER_NAME_LEN);
        if (from.isEmpty()) {
            return null;
        }
        final long id = inviteSeq.getAndIncrement();
        pendingInviteId = id;
        pendingFrom = from;
        status = Status.INVITE_SENT;
        return new CoopPartyInviteEvent(from, id);
    }

    /** Inbound invite from the peer. */
    public boolean receiveInvite(final CoopPartyInviteEvent invite) {
        if (invite == null) {
            return false;
        }
        if (status == Status.PARTY || status == Status.INVITE_SENT || status == Status.INVITE_RECEIVED) {
            return false;
        }
        final String from = CoopWireLimits.clampString(invite.getFromPlayer(), CoopWireLimits.MAX_PLAYER_NAME_LEN);
        if (from.isEmpty() || invite.getFromPlayer() != null
                && invite.getFromPlayer().length() > CoopWireLimits.MAX_PLAYER_NAME_LEN) {
            return false;
        }
        if (invite.getInviteId() <= 0L) {
            return false;
        }
        pendingInviteId = invite.getInviteId();
        pendingFrom = from;
        status = Status.INVITE_RECEIVED;
        return true;
    }

    /** Local accept / decline / leave. Returns the wire event to send, or null. */
    public CoopPartyResponseEvent respond(final CoopPartyResponseEvent.Action action) {
        if (action == null) {
            return null;
        }
        if (action == CoopPartyResponseEvent.Action.LEAVE) {
            if (status != Status.PARTY) {
                return null;
            }
            final long id = pendingInviteId;
            clearParty();
            return new CoopPartyResponseEvent(id, CoopPartyResponseEvent.Action.LEAVE);
        }
        if (status != Status.INVITE_RECEIVED && status != Status.INVITE_SENT) {
            // INVITE_SENT may also cancel by declining own? Only INVITE_RECEIVED accepts.
            if (status != Status.INVITE_RECEIVED) {
                return null;
            }
        }
        if (status != Status.INVITE_RECEIVED) {
            return null;
        }
        final long id = pendingInviteId;
        if (action == CoopPartyResponseEvent.Action.ACCEPT) {
            partnerName = pendingFrom;
            status = Status.PARTY;
            return new CoopPartyResponseEvent(id, CoopPartyResponseEvent.Action.ACCEPT);
        }
        clearPending();
        status = Status.SOLO;
        return new CoopPartyResponseEvent(id, CoopPartyResponseEvent.Action.DECLINE);
    }

    /** Apply a peer response to a local invite or shared party. */
    public boolean applyPeerResponse(final CoopPartyResponseEvent response, final String peerName) {
        if (response == null || response.getAction() == null) {
            return false;
        }
        final CoopPartyResponseEvent.Action action = response.getAction();
        if (action == CoopPartyResponseEvent.Action.LEAVE) {
            if (status == Status.PARTY) {
                clearParty();
                return true;
            }
            return false;
        }
        if (status == Status.INVITE_SENT && response.getInviteId() == pendingInviteId) {
            if (action == CoopPartyResponseEvent.Action.ACCEPT) {
                partnerName = CoopWireLimits.clampString(peerName, CoopWireLimits.MAX_PLAYER_NAME_LEN);
                status = Status.PARTY;
                return true;
            }
            clearPending();
            status = Status.SOLO;
            return true;
        }
        return false;
    }

    /** Drop party / pending invite (disconnect cleanup). */
    public void clearParty() {
        status = Status.SOLO;
        pendingInviteId = 0L;
        pendingFrom = "";
        partnerName = "";
    }

    private void clearPending() {
        pendingInviteId = 0L;
        pendingFrom = "";
    }

    /**
     * Whether two players are within invite / location radius (tile distance).
     */
    public static boolean withinRadius(final float ax, final float ay, final float bx, final float by,
                                       final float tileSize, final float radiusTiles) {
        if (tileSize <= 0f || radiusTiles < 0f) {
            return false;
        }
        if (!CoopWireLimits.coordsInBounds(ax, ay) || !CoopWireLimits.coordsInBounds(bx, by)) {
            return false;
        }
        final float dx = (ax - bx) / tileSize;
        final float dy = (ay - by) / tileSize;
        return dx * dx + dy * dy <= radiusTiles * radiusTiles;
    }
}
