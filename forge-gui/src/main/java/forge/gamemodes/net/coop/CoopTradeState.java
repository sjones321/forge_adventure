package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
import forge.gamemodes.net.event.coop.CoopTradeRequestEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Headless TR1 trade state machine — forward-only escrow.
 *
 * <p>All mutations are intended to run on the GL thread. Netty handlers only
 * {@code postRunnable}; there is no Netty-vs-GL shared mutable apply path.
 *
 * <ol>
 *   <li>Guest requests; host mints a {@link CoopTradeIds} id (reject if known).</li>
 *   <li>Both confirm matching wire offer versions → each side escrows <b>only
 *       its own</b> offer, writes {@code ESCROWED}, saves, sends escrowed(id).</li>
 *   <li>On peer escrowed(id): grant peer offer, write {@code DELIVERED}, save,
 *       send delivered(id). Never reverse received goods.</li>
 *   <li>Reconcile on reconnect for the matching id only. Refund own escrow
 *       solely when the peer never escrowed. No abandon after escrow.</li>
 * </ol>
 */
public final class CoopTradeState {
    public enum Status {
        IDLE,
        REQUEST_SENT,
        INVITE_SENT,
        INVITE_RECEIVED,
        OPEN,
        /** Local goods removed; waiting for peer escrowed (or deliver ready). */
        ESCROWED,
        /** Local has granted peer offer. */
        DELIVERED,
        /** Disconnected mid-flight; pending reconcile — no abandon. */
        NEEDS_RECONCILE,
        COMPLETED,
        REFUNDED,
        CANCELLED
    }

    /** Result of {@link #acceptConfirm}: relay confirm, begin escrow, or cancel. */
    public enum ConfirmResult {
        IGNORED,
        RELAY,
        BEGIN_ESCROW,
        CANCELLED
    }

    private final Object lock = new Object();
    private final CoopRateLimiter rateLimiter;
    private final CoopTradeLog tradeLog;

    private Status status = Status.IDLE;
    private long tradeId;
    private long inviteId;
    private CoopTradeRole localRole = CoopTradeRole.GUEST;
    private CoopTradeOffer hostOffer = CoopTradeOffer.empty();
    private CoopTradeOffer guestOffer = CoopTradeOffer.empty();
    private int hostOfferVersion;
    private int guestOfferVersion;
    private boolean hostConfirmed;
    private boolean guestConfirmed;
    private boolean peerEscrowed;
    private boolean peerDelivered;
    private String cancelReason = "";
    private long inviteSinceMs;
    private CoopTradeCancelEvent lastCancel;

    private Function<CoopTradeRole, CoopTradeBag> bagLookup = role -> null;

    public CoopTradeState() {
        this(new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                CoopTradeWireLimits.DEFAULT_WINDOW_MS), new CoopTradeLog());
    }

    public CoopTradeState(final CoopRateLimiter rateLimiter) {
        this(rateLimiter, new CoopTradeLog());
    }

    public CoopTradeState(final CoopRateLimiter rateLimiter, final CoopTradeLog tradeLog) {
        this.rateLimiter = rateLimiter != null
                ? rateLimiter
                : new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                        CoopTradeWireLimits.DEFAULT_WINDOW_MS);
        this.tradeLog = tradeLog != null ? tradeLog : new CoopTradeLog();
    }

    public CoopTradeLog getTradeLog() {
        return tradeLog;
    }

    public void setBagLookup(final Function<CoopTradeRole, CoopTradeBag> lookup) {
        synchronized (lock) {
            bagLookup = lookup != null ? lookup : role -> null;
        }
    }

    public Status getStatus() {
        synchronized (lock) {
            return status;
        }
    }

    public long getTradeId() {
        synchronized (lock) {
            return tradeId;
        }
    }

    public long getInviteId() {
        synchronized (lock) {
            return inviteId;
        }
    }

    public CoopTradeRole getLocalRole() {
        synchronized (lock) {
            return localRole;
        }
    }

    public boolean isOpen() {
        synchronized (lock) {
            return status == Status.OPEN;
        }
    }

    /**
     * Cancel / abandon is allowed only before both sides confirm (pre-escrow).
     * After escrow there is no abandon button — reconnect reconcile only.
     */
    public boolean isCancelAllowed() {
        synchronized (lock) {
            return status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.INVITE_RECEIVED || status == Status.REQUEST_SENT;
        }
    }

    public boolean isIdle() {
        synchronized (lock) {
            return status == Status.IDLE || status == Status.COMPLETED
                    || status == Status.CANCELLED || status == Status.REFUNDED;
        }
    }

    public boolean isInFlight() {
        synchronized (lock) {
            return status == Status.OPEN || status == Status.ESCROWED
                    || status == Status.DELIVERED || status == Status.NEEDS_RECONCILE
                    || status == Status.INVITE_SENT || status == Status.INVITE_RECEIVED
                    || status == Status.REQUEST_SENT;
        }
    }

    public boolean isPendingReconcile() {
        synchronized (lock) {
            return status == Status.NEEDS_RECONCILE
                    || (status == Status.ESCROWED && !peerDelivered);
        }
    }

    public boolean isPeerEscrowed() {
        synchronized (lock) {
            return peerEscrowed;
        }
    }

    public boolean isPeerDelivered() {
        synchronized (lock) {
            return peerDelivered;
        }
    }

    public CoopTradeOffer getHostOffer() {
        synchronized (lock) {
            return hostOffer;
        }
    }

    public CoopTradeOffer getGuestOffer() {
        synchronized (lock) {
            return guestOffer;
        }
    }

    public CoopTradeOffer getLocalOffer() {
        synchronized (lock) {
            return localRole == CoopTradeRole.HOST ? hostOffer : guestOffer;
        }
    }

    public CoopTradeOffer getPeerOffer() {
        synchronized (lock) {
            return localRole == CoopTradeRole.HOST ? guestOffer : hostOffer;
        }
    }

    public int getHostOfferVersion() {
        synchronized (lock) {
            return hostOfferVersion;
        }
    }

    public int getGuestOfferVersion() {
        synchronized (lock) {
            return guestOfferVersion;
        }
    }

    public int getLocalOfferVersion() {
        synchronized (lock) {
            return localRole == CoopTradeRole.HOST ? hostOfferVersion : guestOfferVersion;
        }
    }

    public int getPeerOfferVersion() {
        synchronized (lock) {
            return localRole == CoopTradeRole.HOST ? guestOfferVersion : hostOfferVersion;
        }
    }

    public boolean isLocalConfirmed() {
        synchronized (lock) {
            return localRole == CoopTradeRole.HOST ? hostConfirmed : guestConfirmed;
        }
    }

    public boolean isPeerConfirmed() {
        synchronized (lock) {
            return localRole == CoopTradeRole.HOST ? guestConfirmed : hostConfirmed;
        }
    }

    public String getCancelReason() {
        synchronized (lock) {
            return cancelReason;
        }
    }

    public CoopTradeCancelEvent getLastCancel() {
        synchronized (lock) {
            return lastCancel;
        }
    }

    /** Guest requests a trade; host will mint the id. */
    public CoopTradeRequestEvent beginRequest(final String fromPlayer, final long nowMs) {
        synchronized (lock) {
            if (!isIdleUnlocked()) {
                return null;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return null;
            }
            final String from = CoopTradeWireLimits.clampName(fromPlayer);
            if (from.isEmpty()) {
                return null;
            }
            resetOffersUnlocked();
            status = Status.REQUEST_SENT;
            localRole = CoopTradeRole.GUEST;
            tradeId = 0L;
            inviteId = 0L;
            cancelReason = "";
            peerEscrowed = false;
            peerDelivered = false;
            lastCancel = null;
            inviteSinceMs = nowMs;
            return new CoopTradeRequestEvent(from);
        }
    }

    public CoopTradeRequestEvent beginRequest(final String fromPlayer) {
        return beginRequest(fromPlayer, System.currentTimeMillis());
    }

    /**
     * Host mints a unique SecureRandom trade id and sends an invite.
     * Rejects ids already present in the local log.
     */
    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds,
                                            final long nowMs) {
        synchronized (lock) {
            if (status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.NEEDS_RECONCILE) {
                return null;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return null;
            }
            final String from = CoopTradeWireLimits.clampName(fromPlayer);
            if (from.isEmpty()) {
                return null;
            }
            long id = 0L;
            for (int i = 0; i < 8; i++) {
                id = CoopTradeIds.next();
                if (!tradeLog.contains(id)) {
                    break;
                }
                id = 0L;
            }
            if (id == 0L) {
                return null;
            }
            resetOffersUnlocked();
            status = Status.INVITE_SENT;
            inviteId = id;
            tradeId = id;
            localRole = CoopTradeRole.HOST;
            inviteSinceMs = nowMs;
            cancelReason = "";
            peerEscrowed = false;
            peerDelivered = false;
            lastCancel = null;
            final int timeout = Math.max(1, Math.min(timeoutSeconds, 120));
            return new CoopTradeInviteEvent(inviteId, from, timeout);
        }
    }

    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds) {
        return beginInvite(fromPlayer, timeoutSeconds, System.currentTimeMillis());
    }

    /** Host answers a guest request by minting an invite. */
    public CoopTradeInviteEvent acceptRequest(final CoopTradeRequestEvent request,
                                              final String hostName, final int timeoutSeconds,
                                              final long nowMs) {
        synchronized (lock) {
            if (request == null) {
                return null;
            }
            if (status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.NEEDS_RECONCILE) {
                return null;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return null;
            }
            final String from = request.getFromPlayer();
            if (from == null || from.isEmpty() || from.length() > CoopTradeWireLimits.MAX_NAME_LEN) {
                return null;
            }
            return beginInviteUnlocked(hostName, timeoutSeconds, nowMs);
        }
    }

    private CoopTradeInviteEvent beginInviteUnlocked(final String fromPlayer,
                                                     final int timeoutSeconds, final long nowMs) {
        final String from = CoopTradeWireLimits.clampName(fromPlayer);
        if (from.isEmpty()) {
            return null;
        }
        long id = 0L;
        for (int i = 0; i < 8; i++) {
            id = CoopTradeIds.next();
            if (!tradeLog.contains(id)) {
                break;
            }
            id = 0L;
        }
        if (id == 0L) {
            return null;
        }
        resetOffersUnlocked();
        status = Status.INVITE_SENT;
        inviteId = id;
        tradeId = id;
        localRole = CoopTradeRole.HOST;
        inviteSinceMs = nowMs;
        cancelReason = "";
        peerEscrowed = false;
        peerDelivered = false;
        lastCancel = null;
        final int timeout = Math.max(1, Math.min(timeoutSeconds, 120));
        return new CoopTradeInviteEvent(inviteId, from, timeout);
    }

    public boolean receiveInvite(final CoopTradeInviteEvent invite, final boolean weAreHost,
                                 final long nowMs) {
        synchronized (lock) {
            if (invite == null || invite.getInviteId() == 0L) {
                return false;
            }
            // Reject any id already present in the local log.
            if (tradeLog.contains(invite.getInviteId())) {
                return false;
            }
            if (status == Status.OPEN || status == Status.ESCROWED
                    || status == Status.DELIVERED || status == Status.NEEDS_RECONCILE) {
                return false;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return false;
            }
            final String from = invite.getFromPlayer();
            if (from == null || from.isEmpty() || from.length() > CoopTradeWireLimits.MAX_NAME_LEN) {
                return false;
            }
            resetOffersUnlocked();
            status = Status.INVITE_RECEIVED;
            inviteId = invite.getInviteId();
            tradeId = inviteId;
            localRole = weAreHost ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
            inviteSinceMs = nowMs;
            cancelReason = "";
            peerEscrowed = false;
            peerDelivered = false;
            lastCancel = null;
            return true;
        }
    }

    public boolean receiveInvite(final CoopTradeInviteEvent invite, final boolean weAreHost) {
        return receiveInvite(invite, weAreHost, System.currentTimeMillis());
    }

    public CoopTradeResponseEvent respondInvite(final boolean accepted) {
        synchronized (lock) {
            if (status != Status.INVITE_RECEIVED) {
                return null;
            }
            final long id = inviteId;
            if (!accepted) {
                status = Status.CANCELLED;
                cancelReason = "declined";
                return new CoopTradeResponseEvent(id, false);
            }
            status = Status.OPEN;
            hostConfirmed = false;
            guestConfirmed = false;
            return new CoopTradeResponseEvent(id, true);
        }
    }

    public boolean applyPeerResponse(final CoopTradeResponseEvent response, final boolean weAreHost) {
        synchronized (lock) {
            if (response == null) {
                return false;
            }
            if (status != Status.INVITE_SENT && status != Status.INVITE_RECEIVED
                    && status != Status.REQUEST_SENT) {
                return false;
            }
            if (response.getInviteId() != inviteId && inviteId != 0L) {
                return false;
            }
            if (!response.isAccepted()) {
                status = Status.CANCELLED;
                cancelReason = "declined";
                return true;
            }
            if (inviteId == 0L) {
                inviteId = response.getInviteId();
                tradeId = inviteId;
            }
            localRole = weAreHost ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
            status = Status.OPEN;
            hostConfirmed = false;
            guestConfirmed = false;
            return true;
        }
    }

    /**
     * Accept an offer. Version is taken from the <b>wire</b>; it must be
     * strictly greater than the current version for that role.
     */
    public CoopTradeOfferEvent acceptOffer(final CoopTradeOfferEvent event, final long nowMs) {
        synchronized (lock) {
            if (event == null || status != Status.OPEN || event.getTradeId() != tradeId) {
                return null;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return null;
            }
            final CoopTradeRole role = event.getFromRole();
            if (role == null) {
                return null;
            }
            final int wireVer = event.getOfferVersion();
            final int current = role == CoopTradeRole.HOST ? hostOfferVersion : guestOfferVersion;
            if (wireVer <= current) {
                return null;
            }
            final CoopTradeBag bag = bagLookup.apply(role);
            final CoopTradeValidator.Result check = CoopTradeValidator.validate(event.getOffer(), bag);
            if (!check.ok()) {
                return null;
            }
            if (role == CoopTradeRole.HOST) {
                hostOffer = event.getOffer();
                hostOfferVersion = wireVer;
            } else {
                guestOffer = event.getOffer();
                guestOfferVersion = wireVer;
            }
            hostConfirmed = false;
            guestConfirmed = false;
            return new CoopTradeOfferEvent(tradeId, role, event.getOffer(), wireVer);
        }
    }

    public CoopTradeOfferEvent acceptOffer(final CoopTradeOfferEvent event) {
        return acceptOffer(event, System.currentTimeMillis());
    }

    public ConfirmResult acceptConfirm(final CoopTradeConfirmEvent event, final long nowMs) {
        synchronized (lock) {
            lastCancel = null;
            if (event == null || status != Status.OPEN || event.getTradeId() != tradeId) {
                return ConfirmResult.IGNORED;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return ConfirmResult.IGNORED;
            }
            final CoopTradeRole role = event.getFromRole();
            if (role == null) {
                return ConfirmResult.IGNORED;
            }
            final int myCurrent = role == CoopTradeRole.HOST ? hostOfferVersion : guestOfferVersion;
            final int theirCurrent = role == CoopTradeRole.HOST ? guestOfferVersion : hostOfferVersion;
            if (event.getMyOfferVersion() != myCurrent
                    || event.getTheirOfferVersion() != theirCurrent) {
                return ConfirmResult.IGNORED;
            }
            if (role == CoopTradeRole.HOST) {
                hostConfirmed = event.isConfirmed();
            } else {
                guestConfirmed = event.isConfirmed();
            }
            if (!hostConfirmed || !guestConfirmed) {
                return ConfirmResult.RELAY;
            }
            final CoopTradeBag hostBag = bagLookup.apply(CoopTradeRole.HOST);
            final CoopTradeBag guestBag = bagLookup.apply(CoopTradeRole.GUEST);
            if (!CoopTradeValidator.validate(hostOffer, hostBag).ok()
                    || !CoopTradeValidator.validate(guestOffer, guestBag).ok()) {
                lastCancel = cancelUnlocked("invalid offer at confirm");
                return ConfirmResult.CANCELLED;
            }
            if (guestBag != null
                    && !CoopTradeValidator.validateReceiverGold(hostOffer, guestBag).ok()) {
                lastCancel = cancelUnlocked("receiver gold overflow");
                return ConfirmResult.CANCELLED;
            }
            if (hostBag != null
                    && !CoopTradeValidator.validateReceiverGold(guestOffer, hostBag).ok()) {
                lastCancel = cancelUnlocked("receiver gold overflow");
                return ConfirmResult.CANCELLED;
            }
            return ConfirmResult.BEGIN_ESCROW;
        }
    }

    public ConfirmResult acceptConfirm(final CoopTradeConfirmEvent event) {
        return acceptConfirm(event, System.currentTimeMillis());
    }

    /**
     * After bag remove succeeds: write ESCROWED with both offers. Returns the
     * wire event to send, or null if already escrowed / wrong state.
     */
    public forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent markEscrowed(final long nowMs) {
        synchronized (lock) {
            if (tradeId == 0L) {
                return null;
            }
            if (tradeLog.hasEscrowed(tradeId) || tradeLog.hasDelivered(tradeId)) {
                status = tradeLog.hasDelivered(tradeId) ? Status.DELIVERED : Status.ESCROWED;
                return new forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent(tradeId, localRole);
            }
            if (status != Status.OPEN && status != Status.NEEDS_RECONCILE) {
                return null;
            }
            tradeLog.record(tradeId, CoopTradeLog.Phase.ESCROWED, nowMs, hostOffer, guestOffer);
            status = Status.ESCROWED;
            return new forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent(tradeId, localRole);
        }
    }

    /**
     * Peer announced escrowed(id). Reject unknown ids and ids we never opened.
     * @return true when local should now deliver (we escrowed and peer escrowed)
     */
    public boolean receivePeerEscrowed(final long peerTradeId, final CoopTradeRole fromRole) {
        synchronized (lock) {
            if (peerTradeId == 0L || peerTradeId != tradeId) {
                // Unknown / mismatched id — hostile or stale.
                return false;
            }
            if (fromRole == localRole) {
                return false;
            }
            if (status == Status.COMPLETED || status == Status.REFUNDED || status == Status.CANCELLED) {
                return false;
            }
            // Peer claiming escrowed for a trade we never reached escrow on, and
            // we have no OPEN/ESCROWED state — ignore hostile.
            if (status != Status.OPEN && status != Status.ESCROWED
                    && status != Status.DELIVERED && status != Status.NEEDS_RECONCILE) {
                return false;
            }
            peerEscrowed = true;
            return tradeLog.hasEscrowed(tradeId) && !tradeLog.hasDelivered(tradeId);
        }
    }

    /**
     * After bag grant succeeds: write DELIVERED. Returns wire event.
     */
    public forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent markDelivered(final long nowMs) {
        synchronized (lock) {
            if (tradeId == 0L) {
                return null;
            }
            if (tradeLog.hasDelivered(tradeId)) {
                status = Status.DELIVERED;
                if (peerDelivered) {
                    tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, nowMs);
                    status = Status.COMPLETED;
                }
                return new forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent(tradeId, localRole);
            }
            if (!tradeLog.hasEscrowed(tradeId)) {
                return null;
            }
            tradeLog.record(tradeId, CoopTradeLog.Phase.DELIVERED, nowMs, hostOffer, guestOffer);
            status = Status.DELIVERED;
            if (peerDelivered) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, nowMs);
                status = Status.COMPLETED;
            }
            return new forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent(tradeId, localRole);
        }
    }

    /**
     * Peer announced delivered(id). Hostile COMPLETED-style claims for unknown
     * ids are rejected.
     */
    public boolean receivePeerDelivered(final long peerTradeId, final CoopTradeRole fromRole) {
        synchronized (lock) {
            if (peerTradeId == 0L || peerTradeId != tradeId) {
                return false;
            }
            if (fromRole == localRole) {
                return false;
            }
            // Must have at least escrowed ourselves — never accept deliver for
            // a step we didn't reach.
            if (!tradeLog.hasEscrowed(tradeId) && status != Status.ESCROWED
                    && status != Status.DELIVERED && status != Status.COMPLETED) {
                return false;
            }
            peerDelivered = true;
            if (tradeLog.hasDelivered(tradeId)) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, System.currentTimeMillis());
                status = Status.COMPLETED;
            }
            return true;
        }
    }

    /** Ready to deliver: local escrowed and peer escrowed, not yet delivered. */
    public boolean shouldDeliver() {
        synchronized (lock) {
            return peerEscrowed && tradeLog.hasEscrowed(tradeId) && !tradeLog.hasDelivered(tradeId)
                    && (status == Status.ESCROWED || status == Status.NEEDS_RECONCILE);
        }
    }

    /**
     * Cancel refused after both confirm / escrow. Pre-escrow cancel only.
     */
    public CoopTradeCancelEvent cancel(final String reason) {
        synchronized (lock) {
            return cancelUnlocked(reason);
        }
    }

    /**
     * Escrow remove failed before {@code ESCROWED} was written — goods unchanged.
     * Allowed even though both confirmed, because nothing left the bag.
     */
    public CoopTradeCancelEvent abortPreEscrow(final String reason) {
        synchronized (lock) {
            if (status != Status.OPEN || tradeLog.hasEscrowed(tradeId)) {
                return null;
            }
            final long id = tradeId != 0L ? tradeId : inviteId;
            cancelReason = CoopTradeWireLimits.clampText(reason);
            status = Status.CANCELLED;
            hostConfirmed = false;
            guestConfirmed = false;
            peerEscrowed = false;
            peerDelivered = false;
            lastCancel = new CoopTradeCancelEvent(id, cancelReason);
            return lastCancel;
        }
    }

    private CoopTradeCancelEvent cancelUnlocked(final String reason) {
        if (status == Status.ESCROWED || status == Status.DELIVERED
                || status == Status.NEEDS_RECONCILE || status == Status.COMPLETED
                || status == Status.REFUNDED) {
            return null;
        }
        // Also refuse once both have confirmed (BEGIN_ESCROW imminent).
        if (status == Status.OPEN && hostConfirmed && guestConfirmed) {
            return null;
        }
        final long id = tradeId != 0L ? tradeId : inviteId;
        cancelReason = CoopTradeWireLimits.clampText(reason);
        status = Status.CANCELLED;
        hostConfirmed = false;
        guestConfirmed = false;
        peerEscrowed = false;
        peerDelivered = false;
        lastCancel = new CoopTradeCancelEvent(id, cancelReason);
        return lastCancel;
    }

    public boolean receiveCancel(final CoopTradeCancelEvent event) {
        synchronized (lock) {
            if (event == null) {
                return false;
            }
            if (event.getTradeId() != tradeId && event.getTradeId() != inviteId) {
                return false;
            }
            // After escrow: ignore cancel (no abandon / no reverse).
            if (status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.NEEDS_RECONCILE || status == Status.COMPLETED
                    || tradeLog.hasEscrowed(tradeId)) {
                return false;
            }
            cancelReason = CoopTradeWireLimits.clampText(event.getReason());
            status = Status.CANCELLED;
            hostConfirmed = false;
            guestConfirmed = false;
            peerEscrowed = false;
            peerDelivered = false;
            return true;
        }
    }

    /**
     * Disconnect: if escrowed, enter NEEDS_RECONCILE (pending, no abandon).
     * Pre-escrow: cancel cleanly.
     */
    public CoopTradeCancelEvent onDisconnect() {
        synchronized (lock) {
            if (status == Status.IDLE || status == Status.COMPLETED
                    || status == Status.CANCELLED || status == Status.REFUNDED) {
                return null;
            }
            if (status == Status.DELIVERED) {
                // Waiting for peer delivered — reconcile, never abandon.
                status = Status.NEEDS_RECONCILE;
                return null;
            }
            if (status == Status.ESCROWED || tradeLog.hasEscrowed(tradeId)) {
                status = Status.NEEDS_RECONCILE;
                return null;
            }
            return cancelUnlocked("disconnect");
        }
    }

    public boolean expireInviteIfNeeded(final long nowMs, final long timeoutMs) {
        synchronized (lock) {
            if (status != Status.INVITE_SENT && status != Status.INVITE_RECEIVED
                    && status != Status.REQUEST_SENT) {
                return false;
            }
            if (timeoutMs <= 0L || nowMs - inviteSinceMs < timeoutMs) {
                return false;
            }
            cancelUnlocked("invite expired");
            return true;
        }
    }

    public CoopTradeLog.ReconcileAction applyReconcile(final CoopTradeReconcileEvent event,
                                                       final long nowMs) {
        synchronized (lock) {
            if (event == null || event.getTradeId() == 0L) {
                return CoopTradeLog.ReconcileAction.NONE;
            }
            // Only for the matching in-flight id.
            if (tradeId != 0L && event.getTradeId() != tradeId
                    && (status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.NEEDS_RECONCILE)) {
                return CoopTradeLog.ReconcileAction.NONE;
            }
            final long id = event.getTradeId();
            // Hostile: unknown id not in our log and not our active trade.
            if (!tradeLog.contains(id) && id != tradeId) {
                return CoopTradeLog.ReconcileAction.IGNORE_HOSTILE;
            }
            final CoopTradeLog.Entry local = tradeLog.get(id);
            final CoopTradeLog.Entry peer = new CoopTradeLog.Entry(id, event.getPhaseEnum(), nowMs);
            final CoopTradeLog.ReconcileAction action = CoopTradeLog.reconcile(local, peer);
            if (action == CoopTradeLog.ReconcileAction.DELIVER) {
                tradeId = id;
                peerEscrowed = true;
                if (local != null) {
                    hostOffer = local.hostOffer;
                    guestOffer = local.guestOffer;
                }
                if (status != Status.DELIVERED && status != Status.COMPLETED) {
                    status = Status.ESCROWED;
                }
            } else if (action == CoopTradeLog.ReconcileAction.REFUND) {
                tradeId = id;
                if (local != null) {
                    hostOffer = local.hostOffer;
                    guestOffer = local.guestOffer;
                }
                status = Status.NEEDS_RECONCILE;
            } else if (action == CoopTradeLog.ReconcileAction.COMPLETE) {
                tradeId = id;
                status = Status.COMPLETED;
                tradeLog.record(id, CoopTradeLog.Phase.COMPLETED, nowMs);
            } else if (action == CoopTradeLog.ReconcileAction.RESEND_ESCROWED) {
                tradeId = id;
                if (status != Status.ESCROWED && status != Status.DELIVERED) {
                    status = Status.ESCROWED;
                }
            } else if (action == CoopTradeLog.ReconcileAction.RESEND_DELIVERED) {
                tradeId = id;
                status = Status.DELIVERED;
            } else if (action == CoopTradeLog.ReconcileAction.IGNORE_HOSTILE) {
                // leave state alone
            }
            return action;
        }
    }

    /** After successful bag refund: write REFUNDED. */
    public boolean markRefunded(final long nowMs) {
        synchronized (lock) {
            if (tradeId == 0L || !tradeLog.hasEscrowed(tradeId) || tradeLog.hasDelivered(tradeId)) {
                return false;
            }
            final boolean ok = tradeLog.record(tradeId, CoopTradeLog.Phase.REFUNDED, nowMs);
            if (ok) {
                status = Status.REFUNDED;
                peerEscrowed = false;
                peerDelivered = false;
            }
            return ok;
        }
    }

    public List<CoopTradeReconcileEvent> buildReconcileRequests(final boolean asRequests) {
        synchronized (lock) {
            final List<CoopTradeReconcileEvent> out = new ArrayList<>();
            for (final CoopTradeLog.Entry e : tradeLog.snapshotInFlight()) {
                out.add(new CoopTradeReconcileEvent(e.tradeId, localRole, e.phase, asRequests));
            }
            if ((status == Status.NEEDS_RECONCILE || status == Status.ESCROWED
                    || status == Status.DELIVERED) && tradeId != 0L) {
                final CoopTradeLog.Phase phase;
                if (tradeLog.hasDelivered(tradeId)) {
                    phase = CoopTradeLog.Phase.DELIVERED;
                } else if (tradeLog.hasEscrowed(tradeId)) {
                    phase = CoopTradeLog.Phase.ESCROWED;
                } else {
                    phase = CoopTradeLog.Phase.NONE;
                }
                boolean dup = false;
                for (final CoopTradeReconcileEvent ev : out) {
                    if (ev.getTradeId() == tradeId) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.add(new CoopTradeReconcileEvent(tradeId, localRole, phase, asRequests));
                }
            }
            return out;
        }
    }

    public void onTeardown() {
        synchronized (lock) {
            if (status == Status.ESCROWED || status == Status.DELIVERED
                    || (tradeId != 0L && tradeLog.hasEscrowed(tradeId)
                    && !tradeLog.hasDelivered(tradeId))) {
                status = Status.NEEDS_RECONCILE;
                return;
            }
            if (status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.INVITE_RECEIVED || status == Status.REQUEST_SENT) {
                status = Status.CANCELLED;
                cancelReason = "teardown";
            }
        }
    }

    /**
     * Restore in-flight ESCROWED status from the log after a crash reload.
     */
    public void restoreFromLog(final long id, final CoopTradeRole role) {
        synchronized (lock) {
            final CoopTradeLog.Entry e = tradeLog.get(id);
            if (e == null) {
                return;
            }
            tradeId = id;
            inviteId = id;
            localRole = role != null ? role : localRole;
            hostOffer = e.hostOffer;
            guestOffer = e.guestOffer;
            if (e.phase == CoopTradeLog.Phase.DELIVERED || e.phase == CoopTradeLog.Phase.COMPLETED) {
                status = e.phase == CoopTradeLog.Phase.COMPLETED ? Status.COMPLETED : Status.DELIVERED;
            } else if (e.phase == CoopTradeLog.Phase.ESCROWED) {
                status = Status.NEEDS_RECONCILE;
            } else if (e.phase == CoopTradeLog.Phase.REFUNDED) {
                status = Status.REFUNDED;
            }
        }
    }

    public void reset() {
        synchronized (lock) {
            status = Status.IDLE;
            tradeId = 0L;
            inviteId = 0L;
            localRole = CoopTradeRole.GUEST;
            resetOffersUnlocked();
            cancelReason = "";
            inviteSinceMs = 0L;
            peerEscrowed = false;
            peerDelivered = false;
            lastCancel = null;
            rateLimiter.reset();
        }
    }

    private boolean isIdleUnlocked() {
        return status == Status.IDLE || status == Status.COMPLETED
                || status == Status.CANCELLED || status == Status.REFUNDED;
    }

    private void resetOffersUnlocked() {
        hostOffer = CoopTradeOffer.empty();
        guestOffer = CoopTradeOffer.empty();
        hostOfferVersion = 0;
        guestOfferVersion = 0;
        hostConfirmed = false;
        guestConfirmed = false;
    }
}
