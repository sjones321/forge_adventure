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
        /** Escrowed and peer escrowed, but deliver failed (e.g. gold overflow). Retry after freeing space. */
        DELIVER_BLOCKED,
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
    private String peerCharacterId = "";
    private String deliverBlockedReason = "";
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

    public String getPeerCharacterId() {
        synchronized (lock) {
            return peerCharacterId;
        }
    }

    public String getDeliverBlockedReason() {
        synchronized (lock) {
            return deliverBlockedReason;
        }
    }

    public boolean isDeliverBlocked() {
        synchronized (lock) {
            return status == Status.DELIVER_BLOCKED;
        }
    }

    public boolean isOpen() {
        synchronized (lock) {
            return status == Status.OPEN;
        }
    }

    /** True when any ESCROWED/DELIVERED log entry is bound to this peer. */
    public boolean hasPendingWithPeer(final String peerId) {
        return tradeLog.hasPendingWithPeer(peerId);
    }

    public boolean hasPendingEscrows() {
        synchronized (lock) {
            if (status == Status.NEEDS_RECONCILE || status == Status.ESCROWED
                    || status == Status.DELIVERED || status == Status.DELIVER_BLOCKED) {
                return true;
            }
            return !tradeLog.snapshotEscrowed().isEmpty()
                    || !tradeLog.snapshotInFlight().isEmpty();
        }
    }

    /**
     * Cancel / abandon is allowed only before both sides confirm (pre-escrow).
     * After escrow there is no abandon button — reconnect reconcile only.
     */
    public boolean isCancelAllowed() {
        synchronized (lock) {
            // Both confirmed → escrow imminent; cancel refused (matches cancelUnlocked).
            if (status == Status.OPEN && hostConfirmed && guestConfirmed) {
                return false;
            }
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
            peerCharacterId = "";
            deliverBlockedReason = "";
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
        return beginInvite(fromPlayer, timeoutSeconds, nowMs, "");
    }

    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds,
                                            final long nowMs, final String peerCharacterId) {
        synchronized (lock) {
            if (status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.DELIVER_BLOCKED
                    || status == Status.NEEDS_RECONCILE) {
                return null;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return null;
            }
            final String peer = CoopTradeWireLimits.clampName(peerCharacterId);
            if (!peer.isEmpty() && tradeLog.hasPendingWithPeer(peer)) {
                return null;
            }
            final String from = CoopTradeWireLimits.clampName(fromPlayer);
            if (from.isEmpty()) {
                return null;
            }
            return beginInviteUnlocked(from, timeoutSeconds, nowMs, peer);
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
                    || status == Status.DELIVER_BLOCKED
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
            final String peer = CoopTradeWireLimits.clampName(from);
            if (tradeLog.hasPendingWithPeer(peer)) {
                return null;
            }
            return beginInviteUnlocked(hostName, timeoutSeconds, nowMs, peer);
        }
    }

    private CoopTradeInviteEvent beginInviteUnlocked(final String fromPlayer,
                                                     final int timeoutSeconds, final long nowMs,
                                                     final String peerId) {
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
        deliverBlockedReason = "";
        peerCharacterId = peerId != null ? peerId : "";
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
            // Host never accepts a guest-chosen invite id — host mints only.
            if (weAreHost) {
                return false;
            }
            // Reject any id already present in the local log.
            if (tradeLog.contains(invite.getInviteId())) {
                return false;
            }
            if (status == Status.OPEN || status == Status.ESCROWED
                    || status == Status.DELIVERED || status == Status.DELIVER_BLOCKED
                    || status == Status.NEEDS_RECONCILE) {
                return false;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return false;
            }
            final String from = invite.getFromPlayer();
            if (from == null || from.isEmpty() || from.length() > CoopTradeWireLimits.MAX_NAME_LEN) {
                return false;
            }
            final String peer = CoopTradeWireLimits.clampName(from);
            if (tradeLog.hasPendingWithPeer(peer)) {
                return false;
            }
            resetOffersUnlocked();
            status = Status.INVITE_RECEIVED;
            inviteId = invite.getInviteId();
            tradeId = inviteId;
            localRole = CoopTradeRole.GUEST;
            inviteSinceMs = nowMs;
            cancelReason = "";
            deliverBlockedReason = "";
            peerCharacterId = peer;
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
            // Guest: response id must not already be in the local log (stale/replay).
            if (!weAreHost && status == Status.REQUEST_SENT
                    && tradeLog.contains(response.getInviteId())) {
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

    /** Bind the peer character id once known (host invite path / session peer). */
    public void setPeerCharacterId(final String peerId) {
        synchronized (lock) {
            if (peerId != null && !peerId.isEmpty()) {
                peerCharacterId = CoopTradeWireLimits.clampName(peerId);
            }
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
            // Confirm is never rate-limited (M3): dropping a confirm races escrow.
            if (event == null || event.getTradeId() != tradeId) {
                return ConfirmResult.IGNORED;
            }
            // After escrow started, ignore unconfirm / late confirms (race-proof).
            if (status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.DELIVER_BLOCKED || status == Status.NEEDS_RECONCILE
                    || status == Status.COMPLETED || tradeLog.hasEscrowed(tradeId)) {
                return ConfirmResult.IGNORED;
            }
            if (status != Status.OPEN) {
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
                // Stale versions: unconfirm that side so a crossing unconfirm/confirm cannot lock.
                if (!event.isConfirmed()) {
                    if (role == CoopTradeRole.HOST) {
                        hostConfirmed = false;
                    } else {
                        guestConfirmed = false;
                    }
                }
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
     * After bag remove succeeds: write ESCROWED with both offers + local role +
     * peer id. Returns the wire event to send, or null if record/save failed
     * (caller must restore the bag and must not send).
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
            if (!tradeLog.record(tradeId, CoopTradeLog.Phase.ESCROWED, nowMs,
                    hostOffer, guestOffer, localRole, peerCharacterId)) {
                return null;
            }
            status = Status.ESCROWED;
            deliverBlockedReason = "";
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
                    && status != Status.DELIVERED && status != Status.DELIVER_BLOCKED
                    && status != Status.NEEDS_RECONCILE) {
                return false;
            }
            peerEscrowed = true;
            return tradeLog.hasEscrowed(tradeId) && !tradeLog.hasDelivered(tradeId);
        }
    }

    /**
     * After bag grant succeeds: write DELIVERED. Returns wire event, or null if
     * record/save failed (caller restores bag, does not send).
     */
    public forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent markDelivered(final long nowMs) {
        synchronized (lock) {
            if (tradeId == 0L) {
                return null;
            }
            if (tradeLog.hasDelivered(tradeId)) {
                status = Status.DELIVERED;
                deliverBlockedReason = "";
                if (peerDelivered) {
                    tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, nowMs,
                            null, null, localRole, peerCharacterId);
                    status = Status.COMPLETED;
                }
                return new forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent(tradeId, localRole);
            }
            if (!tradeLog.hasEscrowed(tradeId)) {
                return null;
            }
            if (!tradeLog.record(tradeId, CoopTradeLog.Phase.DELIVERED, nowMs,
                    hostOffer, guestOffer, localRole, peerCharacterId)) {
                return null;
            }
            status = Status.DELIVERED;
            deliverBlockedReason = "";
            if (peerDelivered) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, nowMs,
                        null, null, localRole, peerCharacterId);
                status = Status.COMPLETED;
            }
            return new forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent(tradeId, localRole);
        }
    }

    /** Deliver failed (gold overflow, etc.) — keep escrow, allow retry. */
    public void markDeliverBlocked(final String reason) {
        synchronized (lock) {
            if (status == Status.ESCROWED || status == Status.NEEDS_RECONCILE
                    || status == Status.DELIVER_BLOCKED) {
                status = Status.DELIVER_BLOCKED;
                deliverBlockedReason = reason != null ? CoopTradeWireLimits.clampText(reason) : "";
            }
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
                    && status != Status.DELIVERED && status != Status.DELIVER_BLOCKED
                    && status != Status.COMPLETED) {
                return false;
            }
            peerDelivered = true;
            if (tradeLog.hasDelivered(tradeId)) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, System.currentTimeMillis(),
                        null, null, localRole, peerCharacterId);
                status = Status.COMPLETED;
            }
            return true;
        }
    }

    /** Ready to deliver: local escrowed and peer escrowed, not yet delivered. */
    public boolean shouldDeliver() {
        synchronized (lock) {
            return peerEscrowed && tradeLog.hasEscrowed(tradeId) && !tradeLog.hasDelivered(tradeId)
                    && (status == Status.ESCROWED || status == Status.NEEDS_RECONCILE
                    || status == Status.DELIVER_BLOCKED);
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
                || status == Status.DELIVER_BLOCKED
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
            // After escrow: ignore cancel (no abandon / no reverse). Race-proof:
            // peer cancel crossing our escrow must not wipe ESCROWED.
            if (status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.DELIVER_BLOCKED
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
            if (status == Status.DELIVERED || status == Status.DELIVER_BLOCKED) {
                // Waiting for peer delivered / retry — reconcile, never abandon.
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
        return applyReconcile(event, nowMs, null);
    }

    /**
     * @param connectedPeerCharacterId when non-empty, reject reconcile for entries
     *        bound to a different peer (H1).
     */
    public CoopTradeLog.ReconcileAction applyReconcile(final CoopTradeReconcileEvent event,
                                                       final long nowMs,
                                                       final String connectedPeerCharacterId) {
        synchronized (lock) {
            if (event == null || event.getTradeId() == 0L) {
                return CoopTradeLog.ReconcileAction.NONE;
            }
            // Only for the matching in-flight id.
            if (tradeId != 0L && event.getTradeId() != tradeId
                    && (status == Status.ESCROWED || status == Status.DELIVERED
                    || status == Status.DELIVER_BLOCKED
                    || status == Status.NEEDS_RECONCILE)) {
                return CoopTradeLog.ReconcileAction.NONE;
            }
            final long id = event.getTradeId();
            // Hostile: unknown id not in our log and not our active trade.
            if (!tradeLog.contains(id) && id != tradeId) {
                return CoopTradeLog.ReconcileAction.IGNORE_HOSTILE;
            }
            final CoopTradeLog.Entry local = tradeLog.get(id);
            // H1: never reconcile an entry bound to a different peer.
            if (local != null && connectedPeerCharacterId != null
                    && !connectedPeerCharacterId.isEmpty()
                    && !local.peerCharacterId.isEmpty()
                    && !local.matchesPeer(connectedPeerCharacterId)) {
                return CoopTradeLog.ReconcileAction.IGNORE_HOSTILE;
            }
            final CoopTradeLog.Entry peer = new CoopTradeLog.Entry(id, event.getPhaseEnum(), nowMs);
            final CoopTradeLog.ReconcileAction action = CoopTradeLog.reconcile(local, peer);
            if (action == CoopTradeLog.ReconcileAction.DELIVER
                    || action == CoopTradeLog.ReconcileAction.REFUND
                    || action == CoopTradeLog.ReconcileAction.RESEND_ESCROWED
                    || action == CoopTradeLog.ReconcileAction.RESEND_DELIVERED
                    || action == CoopTradeLog.ReconcileAction.COMPLETE) {
                // C1: restore role + peer from the durable entry, never from reset().
                if (local != null) {
                    localRole = local.localRole;
                    if (!local.peerCharacterId.isEmpty()) {
                        peerCharacterId = local.peerCharacterId;
                    }
                    hostOffer = local.hostOffer;
                    guestOffer = local.guestOffer;
                }
                tradeId = id;
                inviteId = id;
            }
            if (action == CoopTradeLog.ReconcileAction.DELIVER) {
                peerEscrowed = true;
                if (status != Status.DELIVERED && status != Status.COMPLETED) {
                    status = Status.ESCROWED;
                }
            } else if (action == CoopTradeLog.ReconcileAction.REFUND) {
                status = Status.NEEDS_RECONCILE;
            } else if (action == CoopTradeLog.ReconcileAction.COMPLETE) {
                status = Status.COMPLETED;
                tradeLog.record(id, CoopTradeLog.Phase.COMPLETED, nowMs,
                        null, null, localRole, peerCharacterId);
            } else if (action == CoopTradeLog.ReconcileAction.RESEND_ESCROWED) {
                if (status != Status.ESCROWED && status != Status.DELIVERED
                        && status != Status.DELIVER_BLOCKED) {
                    status = Status.ESCROWED;
                }
            } else if (action == CoopTradeLog.ReconcileAction.RESEND_DELIVERED) {
                status = Status.DELIVERED;
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
            final boolean ok = tradeLog.record(tradeId, CoopTradeLog.Phase.REFUNDED, nowMs,
                    null, null, localRole, peerCharacterId);
            if (ok) {
                status = Status.REFUNDED;
                peerEscrowed = false;
                peerDelivered = false;
            }
            return ok;
        }
    }

    public List<CoopTradeReconcileEvent> buildReconcileRequests(final boolean asRequests) {
        return buildReconcileRequests(asRequests, peerCharacterId);
    }

    /**
     * Only emit reconcile for entries bound to {@code connectedPeerCharacterId}.
     * A different guest connecting gets nothing (H1).
     */
    public List<CoopTradeReconcileEvent> buildReconcileRequests(final boolean asRequests,
                                                                final String connectedPeerCharacterId) {
        synchronized (lock) {
            final List<CoopTradeReconcileEvent> out = new ArrayList<>();
            final String peer = connectedPeerCharacterId != null ? connectedPeerCharacterId : "";
            for (final CoopTradeLog.Entry e : tradeLog.snapshotInFlight()) {
                if (peer.isEmpty() || !e.matchesPeer(peer)) {
                    continue;
                }
                final CoopTradeRole role = e.localRole != null ? e.localRole : localRole;
                out.add(new CoopTradeReconcileEvent(e.tradeId, role, e.phase, asRequests));
            }
            if ((status == Status.NEEDS_RECONCILE || status == Status.ESCROWED
                    || status == Status.DELIVERED || status == Status.DELIVER_BLOCKED)
                    && tradeId != 0L
                    && (peer.isEmpty() || peer.equals(peerCharacterId))) {
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
                    || status == Status.DELIVER_BLOCKED
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
     * Restore in-flight status from the log after a crash reload.
     * Role and peer id come from the entry (C1) — never default to GUEST.
     */
    public void restoreFromLog(final long id, final CoopTradeRole roleFallback) {
        synchronized (lock) {
            final CoopTradeLog.Entry e = tradeLog.get(id);
            if (e == null) {
                return;
            }
            tradeId = id;
            inviteId = id;
            localRole = e.localRole != null ? e.localRole
                    : (roleFallback != null ? roleFallback : localRole);
            peerCharacterId = e.peerCharacterId != null ? e.peerCharacterId : "";
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

    /** Restore every in-flight log entry into NEEDS_RECONCILE (no explicit restoreFromLog call needed). */
    public void recoverPendingFromLog() {
        synchronized (lock) {
            for (final CoopTradeLog.Entry e : tradeLog.snapshotInFlight()) {
                if (e.phase == CoopTradeLog.Phase.ESCROWED
                        || e.phase == CoopTradeLog.Phase.DELIVERED) {
                    restoreFromLogUnlocked(e);
                    return; // active pending trade
                }
            }
        }
    }

    private void restoreFromLogUnlocked(final CoopTradeLog.Entry e) {
        tradeId = e.tradeId;
        inviteId = e.tradeId;
        localRole = e.localRole != null ? e.localRole : localRole;
        peerCharacterId = e.peerCharacterId != null ? e.peerCharacterId : "";
        hostOffer = e.hostOffer;
        guestOffer = e.guestOffer;
        if (e.phase == CoopTradeLog.Phase.DELIVERED) {
            status = Status.DELIVERED;
        } else {
            status = Status.NEEDS_RECONCILE;
        }
    }

    /**
     * Clear the active open trade only. Preserves NEEDS_RECONCILE / pending
     * escrow state and never wipes role for in-flight log entries.
     */
    public void resetActiveIfIdle() {
        synchronized (lock) {
            if (status == Status.NEEDS_RECONCILE || status == Status.ESCROWED
                    || status == Status.DELIVERED || status == Status.DELIVER_BLOCKED
                    || tradeLog.hasEscrowed(tradeId)) {
                return;
            }
            resetUnlocked();
        }
    }

    public void reset() {
        synchronized (lock) {
            resetUnlocked();
        }
    }

    private void resetUnlocked() {
        status = Status.IDLE;
        tradeId = 0L;
        inviteId = 0L;
        // Do not force GUEST when pending log entries exist — recoverPendingFromLog
        // will restore role from the entry. Only default when truly idle.
        if (tradeLog.snapshotInFlight().isEmpty()) {
            localRole = CoopTradeRole.GUEST;
            peerCharacterId = "";
        }
        resetOffersUnlocked();
        cancelReason = "";
        deliverBlockedReason = "";
        inviteSinceMs = 0L;
        peerEscrowed = false;
        peerDelivered = false;
        lastCancel = null;
        rateLimiter.reset();
    }

    private boolean isIdleUnlocked() {
        // Pending escrows with other peers stay in the log; only the active
        // status blocks a new trade. Per-peer blocking uses hasPendingWithPeer.
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
