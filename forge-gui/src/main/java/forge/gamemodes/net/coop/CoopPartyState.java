package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless party invite / accept / decline / leave state machine (CO2).
 * Simultaneous invites are treated as mutual accept. Invites expire after a
 * configurable timeout.
 */
public final class CoopPartyState {
    public enum Status { SOLO, INVITE_SENT, INVITE_RECEIVED, PARTY }

    /** Result of {@link #receiveInvite(CoopPartyInviteEvent, long, long)}. */
    public enum InviteOutcome {
        REJECTED,
        PENDING,
        /** Both sides invited each other — party formed immediately. */
        MUTUAL_ACCEPT
    }

    private final AtomicLong inviteSeq = new AtomicLong(1L);
    private volatile Status status = Status.SOLO;
    private volatile long pendingInviteId;
    private volatile String pendingFrom = "";
    private volatile String partnerName = "";
    private volatile long pendingSinceMs;

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

    public long getPendingSinceMs() {
        return pendingSinceMs;
    }

    /** Local player starts an invite. */
    public CoopPartyInviteEvent createInvite(final String fromPlayer, final long nowMs) {
        expireIfNeeded(nowMs, Long.MAX_VALUE);
        if (status == Status.PARTY || status == Status.INVITE_SENT) {
            return null;
        }
        final String from = CoopWireLimits.acceptPlayerName(fromPlayer);
        if (from == null || from.isEmpty()) {
            return null;
        }
        final long id = inviteSeq.getAndIncrement();
        pendingInviteId = id;
        pendingFrom = from;
        pendingSinceMs = nowMs;
        status = Status.INVITE_SENT;
        return new CoopPartyInviteEvent(from, id);
    }

    public CoopPartyInviteEvent createInvite(final String fromPlayer) {
        return createInvite(fromPlayer, System.currentTimeMillis());
    }

    /**
     * Inbound invite. If we already sent one, treat as mutual accept.
     * @param timeoutMs invite lifetime; {@link Long#MAX_VALUE} disables expiry here
     */
    public InviteOutcome receiveInvite(final CoopPartyInviteEvent invite, final long nowMs, final long timeoutMs) {
        if (invite == null) {
            return InviteOutcome.REJECTED;
        }
        expireIfNeeded(nowMs, timeoutMs);
        final String from = CoopWireLimits.acceptPlayerName(invite.getFromPlayer());
        if (from == null || from.isEmpty() || invite.getInviteId() <= 0L) {
            return InviteOutcome.REJECTED;
        }
        if (status == Status.PARTY) {
            return InviteOutcome.REJECTED;
        }
        if (status == Status.INVITE_SENT) {
            // Simultaneous invites → mutual accept.
            partnerName = from;
            pendingInviteId = invite.getInviteId();
            pendingFrom = from;
            pendingSinceMs = 0L;
            status = Status.PARTY;
            return InviteOutcome.MUTUAL_ACCEPT;
        }
        if (status == Status.INVITE_RECEIVED) {
            return InviteOutcome.REJECTED;
        }
        pendingInviteId = invite.getInviteId();
        pendingFrom = from;
        pendingSinceMs = nowMs;
        status = Status.INVITE_RECEIVED;
        return InviteOutcome.PENDING;
    }

    public boolean receiveInvite(final CoopPartyInviteEvent invite) {
        return receiveInvite(invite, System.currentTimeMillis(), Long.MAX_VALUE) != InviteOutcome.REJECTED;
    }

    public CoopPartyResponseEvent respond(final CoopPartyResponseEvent.Action action) {
        return respond(action, System.currentTimeMillis(), Long.MAX_VALUE);
    }

    public CoopPartyResponseEvent respond(final CoopPartyResponseEvent.Action action,
                                          final long nowMs, final long timeoutMs) {
        if (action == null) {
            return null;
        }
        expireIfNeeded(nowMs, timeoutMs);
        if (action == CoopPartyResponseEvent.Action.LEAVE) {
            if (status != Status.PARTY) {
                return null;
            }
            final long id = pendingInviteId;
            clearParty();
            return new CoopPartyResponseEvent(id, CoopPartyResponseEvent.Action.LEAVE);
        }
        if (status != Status.INVITE_RECEIVED) {
            return null;
        }
        final long id = pendingInviteId;
        if (action == CoopPartyResponseEvent.Action.ACCEPT) {
            partnerName = pendingFrom;
            status = Status.PARTY;
            pendingSinceMs = 0L;
            return new CoopPartyResponseEvent(id, CoopPartyResponseEvent.Action.ACCEPT);
        }
        clearPending();
        status = Status.SOLO;
        return new CoopPartyResponseEvent(id, CoopPartyResponseEvent.Action.DECLINE);
    }

    public boolean applyPeerResponse(final CoopPartyResponseEvent response, final String peerName) {
        return applyPeerResponse(response, peerName, System.currentTimeMillis(), Long.MAX_VALUE);
    }

    public boolean applyPeerResponse(final CoopPartyResponseEvent response, final String peerName,
                                     final long nowMs, final long timeoutMs) {
        if (response == null || response.getAction() == null) {
            return false;
        }
        expireIfNeeded(nowMs, timeoutMs);
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
                final String name = CoopWireLimits.acceptPlayerName(peerName);
                partnerName = name == null ? "" : name;
                status = Status.PARTY;
                pendingSinceMs = 0L;
                return true;
            }
            clearPending();
            status = Status.SOLO;
            return true;
        }
        return false;
    }

    /** Expire a pending invite if past timeout. */
    public boolean expireIfNeeded(final long nowMs, final long timeoutMs) {
        if (timeoutMs == Long.MAX_VALUE || timeoutMs <= 0L) {
            return false;
        }
        if (status != Status.INVITE_SENT && status != Status.INVITE_RECEIVED) {
            return false;
        }
        if (pendingSinceMs <= 0L) {
            return false;
        }
        if (nowMs - pendingSinceMs < timeoutMs) {
            return false;
        }
        clearPending();
        status = Status.SOLO;
        return true;
    }

    public void clearParty() {
        status = Status.SOLO;
        pendingInviteId = 0L;
        pendingFrom = "";
        partnerName = "";
        pendingSinceMs = 0L;
    }

    private void clearPending() {
        pendingInviteId = 0L;
        pendingFrom = "";
        pendingSinceMs = 0L;
    }

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
