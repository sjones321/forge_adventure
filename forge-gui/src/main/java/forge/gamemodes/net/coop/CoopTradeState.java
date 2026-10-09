package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopTradeAckEvent;
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeExecuteEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Headless TR1 trade state machine (locked for Netty + GL thread safety).
 *
 * <h2>Two-phase commit</h2>
 * <ol>
 *   <li>Both sides confirm matching offer versions → host emits
 *       {@link CoopTradeExecuteEvent} and enters {@link Status#WAITING_GUEST_ACK}
 *       <b>without</b> mutating its bag.</li>
 *   <li>Guest applies locally, keeps a rollback snapshot, sends
 *       {@link CoopTradeAckEvent}, enters {@link Status#GUEST_APPLIED}.</li>
 *   <li>Host applies only after a successful guest ack, then sends a host ack
 *       (complete). Guest discards its snapshot.</li>
 *   <li>Any failure, cancel, or disconnect before the host ack means
 *       <b>neither</b> side keeps changes — the guest restores its snapshot on
 *       cancel / disconnect / {@link #expireGuestAckIfNeeded}.</li>
 * </ol>
 *
 * <p>Guest-side timeout: after applying, if the host complete ack does not
 * arrive within {@code coopTradeAckTimeoutSeconds}, the guest rolls back.
 * Confirms carry an offer version; a confirm for a stale version is ignored.
 * Peers are identified by {@link CoopTradeRole}, not character name.
 */
public final class CoopTradeState {
    public enum Status {
        IDLE,
        INVITE_SENT,
        INVITE_RECEIVED,
        OPEN,
        /** Host sent Execute; waiting for guest Ack. Host bag unchanged. */
        WAITING_GUEST_ACK,
        /** Guest applied locally; waiting for host complete Ack or timeout rollback. */
        GUEST_APPLIED,
        COMPLETED,
        CANCELLED
    }

    private final Object lock = new Object();
    private final AtomicLong seq = new AtomicLong(1L);
    private final CoopRateLimiter rateLimiter;

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
    private String cancelReason = "";
    private CoopTradeExecuteEvent pendingExecute;
    private long inviteSinceMs;
    private long guestAppliedSinceMs;
    private CoopTradeBag.Snapshot guestRollbackSnap;

    private Function<CoopTradeRole, CoopTradeBag> bagLookup = role -> null;

    public CoopTradeState() {
        this(new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                CoopTradeWireLimits.DEFAULT_WINDOW_MS));
    }

    public CoopTradeState(final CoopRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter != null
                ? rateLimiter
                : new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                        CoopTradeWireLimits.DEFAULT_WINDOW_MS);
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

    public boolean isIdle() {
        synchronized (lock) {
            return status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED;
        }
    }

    public boolean isInFlight() {
        synchronized (lock) {
            return status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLIED
                    || status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.INVITE_RECEIVED;
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

    public CoopTradeExecuteEvent getPendingExecute() {
        synchronized (lock) {
            return pendingExecute;
        }
    }

    public boolean hasGuestRollbackSnap() {
        synchronized (lock) {
            return guestRollbackSnap != null;
        }
    }

    /** Local player invites the peer. {@code weAreHost} sets the local role. */
    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds,
                                            final boolean weAreHost, final long nowMs) {
        synchronized (lock) {
            if (status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLIED) {
                return null;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return null;
            }
            if (fromPlayer == null || fromPlayer.isEmpty()
                    || fromPlayer.length() > CoopTradeWireLimits.MAX_NAME_LEN) {
                return null;
            }
            final String from = CoopTradeWireLimits.clampName(fromPlayer);
            if (from.isEmpty()) {
                return null;
            }
            resetOffersUnlocked();
            status = Status.INVITE_SENT;
            inviteId = seq.getAndIncrement();
            tradeId = inviteId;
            localRole = weAreHost ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
            inviteSinceMs = nowMs;
            cancelReason = "";
            pendingExecute = null;
            guestRollbackSnap = null;
            final int timeout = Math.max(1, Math.min(timeoutSeconds, 120));
            return new CoopTradeInviteEvent(inviteId, from, timeout);
        }
    }

    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds,
                                            final boolean weAreHost) {
        return beginInvite(fromPlayer, timeoutSeconds, weAreHost, System.currentTimeMillis());
    }

    /** @deprecated use {@link #beginInvite(String, int, boolean)} */
    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds) {
        return beginInvite(fromPlayer, timeoutSeconds, true);
    }

    public boolean receiveInvite(final CoopTradeInviteEvent invite, final boolean weAreHost,
                                 final long nowMs) {
        synchronized (lock) {
            if (invite == null || status == Status.OPEN || status == Status.WAITING_GUEST_ACK
                    || status == Status.GUEST_APPLIED) {
                return false;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return false;
            }
            if (invite.getInviteId() <= 0L) {
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
            pendingExecute = null;
            guestRollbackSnap = null;
            return true;
        }
    }

    public boolean receiveInvite(final CoopTradeInviteEvent invite, final boolean weAreHost) {
        return receiveInvite(invite, weAreHost, System.currentTimeMillis());
    }

    /** @deprecated use {@link #receiveInvite(CoopTradeInviteEvent, boolean)} */
    public boolean receiveInvite(final CoopTradeInviteEvent invite) {
        return receiveInvite(invite, false);
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
            if (status != Status.INVITE_SENT && status != Status.INVITE_RECEIVED) {
                return false;
            }
            if (response.getInviteId() != inviteId) {
                return false;
            }
            if (!response.isAccepted()) {
                status = Status.CANCELLED;
                cancelReason = "declined";
                return true;
            }
            localRole = weAreHost ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
            status = Status.OPEN;
            hostConfirmed = false;
            guestConfirmed = false;
            return true;
        }
    }

    /**
     * Accept an offer update from {@code fromRole}. Returns a wire event with the
     * assigned offer version, or null when rejected.
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
            final CoopTradeBag bag = bagLookup.apply(role);
            final CoopTradeValidator.Result check = CoopTradeValidator.validate(event.getOffer(), bag);
            if (!check.ok()) {
                return null;
            }
            if (role == CoopTradeRole.HOST) {
                hostOffer = event.getOffer();
                hostOfferVersion++;
            } else {
                guestOffer = event.getOffer();
                guestOfferVersion++;
            }
            hostConfirmed = false;
            guestConfirmed = false;
            final int ver = role == CoopTradeRole.HOST ? hostOfferVersion : guestOfferVersion;
            return new CoopTradeOfferEvent(tradeId, role, event.getOffer(), ver);
        }
    }

    public CoopTradeOfferEvent acceptOffer(final CoopTradeOfferEvent event) {
        return acceptOffer(event, System.currentTimeMillis());
    }

    /**
     * Confirm for a specific offer version. Stale versions are ignored (returns null).
     * When host and both sides confirmed, returns {@link CoopTradeExecuteEvent}.
     */
    public Object acceptConfirm(final CoopTradeConfirmEvent event, final boolean weAreHost,
                                final long nowMs) {
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
            final int currentVer = role == CoopTradeRole.HOST ? hostOfferVersion : guestOfferVersion;
            if (event.getOfferVersion() != currentVer) {
                // Stale confirm after an offer change — ignore.
                return null;
            }
            if (role == CoopTradeRole.HOST) {
                hostConfirmed = event.isConfirmed();
            } else {
                guestConfirmed = event.isConfirmed();
            }
            if (!hostConfirmed || !guestConfirmed) {
                return event;
            }
            final CoopTradeBag hostBag = bagLookup.apply(CoopTradeRole.HOST);
            final CoopTradeBag guestBag = bagLookup.apply(CoopTradeRole.GUEST);
            if (!CoopTradeValidator.validate(hostOffer, hostBag).ok()
                    || !CoopTradeValidator.validate(guestOffer, guestBag).ok()) {
                return cancelUnlocked("invalid offer at confirm");
            }
            if (!weAreHost) {
                return event;
            }
            final CoopTradeExecuteEvent exec = new CoopTradeExecuteEvent(
                    tradeId, hostOffer, guestOffer, hostOfferVersion, guestOfferVersion);
            pendingExecute = exec;
            status = Status.WAITING_GUEST_ACK;
            return exec;
        }
    }

    public Object acceptConfirm(final CoopTradeConfirmEvent event, final boolean weAreHost) {
        return acceptConfirm(event, weAreHost, System.currentTimeMillis());
    }

    /** Guest (or host mirror) receives Execute — do not apply bags here. */
    public boolean receiveExecute(final CoopTradeExecuteEvent event, final boolean weAreHost) {
        synchronized (lock) {
            if (event == null || (status != Status.OPEN && status != Status.WAITING_GUEST_ACK)) {
                return false;
            }
            if (event.getTradeId() != tradeId) {
                return false;
            }
            pendingExecute = event;
            status = weAreHost ? Status.WAITING_GUEST_ACK : Status.WAITING_GUEST_ACK;
            // Guest will move to GUEST_APPLIED after local apply via markGuestApplied.
            return true;
        }
    }

    public boolean receiveExecute(final CoopTradeExecuteEvent event) {
        return receiveExecute(event, localRole == CoopTradeRole.HOST);
    }

    /**
     * Guest finished local apply (or failed). On success keeps {@code snap} for
     * timeout/disconnect rollback until {@link #receiveHostComplete}.
     */
    public CoopTradeAckEvent markGuestApplied(final boolean success, final String detail,
                                              final CoopTradeBag.Snapshot snap, final long nowMs) {
        synchronized (lock) {
            if (status != Status.WAITING_GUEST_ACK && status != Status.OPEN) {
                return null;
            }
            if (pendingExecute == null) {
                return null;
            }
            if (success) {
                guestRollbackSnap = snap;
                guestAppliedSinceMs = nowMs;
                status = Status.GUEST_APPLIED;
            } else {
                guestRollbackSnap = null;
                if (snap != null) {
                    // Caller already restored; ensure cancel state.
                }
                status = Status.CANCELLED;
                cancelReason = detail == null ? "guest apply failed" : detail;
            }
            return new CoopTradeAckEvent(tradeId, CoopTradeRole.GUEST, success,
                    detail == null ? "" : detail);
        }
    }

    /**
     * Host processes guest ack. On success the caller must apply the host bag,
     * then call {@link #markHostCompleted()} and send the returned host ack.
     * On failure returns a cancel (host never applies).
     */
    public Object receiveGuestAck(final CoopTradeAckEvent ack) {
        synchronized (lock) {
            if (ack == null || ack.getFromRole() != CoopTradeRole.GUEST) {
                return null;
            }
            if (status != Status.WAITING_GUEST_ACK || ack.getTradeId() != tradeId) {
                return null;
            }
            if (!ack.isSuccess()) {
                return cancelUnlocked("guest apply failed: " + ack.getDetail());
            }
            // Stay WAITING_GUEST_ACK until markHostCompleted after local apply.
            return ack;
        }
    }

    /** Host finished applying after guest ack — emit complete ack for the guest. */
    public CoopTradeAckEvent markHostCompleted() {
        synchronized (lock) {
            if (status != Status.WAITING_GUEST_ACK || pendingExecute == null) {
                return null;
            }
            status = Status.COMPLETED;
            hostConfirmed = false;
            guestConfirmed = false;
            guestRollbackSnap = null;
            return new CoopTradeAckEvent(tradeId, CoopTradeRole.HOST, true, "complete");
        }
    }

    /**
     * Guest receives host complete ack — discard rollback snapshot.
     * @return true if this completed the trade
     */
    public boolean receiveHostComplete(final CoopTradeAckEvent ack) {
        synchronized (lock) {
            if (ack == null || ack.getFromRole() != CoopTradeRole.HOST || !ack.isSuccess()) {
                return false;
            }
            if (ack.getTradeId() != tradeId) {
                return false;
            }
            if (status != Status.GUEST_APPLIED && status != Status.WAITING_GUEST_ACK) {
                return false;
            }
            guestRollbackSnap = null;
            status = Status.COMPLETED;
            hostConfirmed = false;
            guestConfirmed = false;
            return true;
        }
    }

    /**
     * Roll back guest apply using the stored snapshot. Used on cancel,
     * disconnect, and ack timeout.
     * @return true if a snapshot was restored
     */
    public boolean rollbackGuestApply(final CoopTradeBag bag) {
        synchronized (lock) {
            if (guestRollbackSnap == null || bag == null) {
                guestRollbackSnap = null;
                return false;
            }
            bag.restore(guestRollbackSnap);
            guestRollbackSnap = null;
            return true;
        }
    }

    public CoopTradeCancelEvent cancel(final String reason) {
        synchronized (lock) {
            return cancelUnlocked(reason);
        }
    }

    private CoopTradeCancelEvent cancelUnlocked(final String reason) {
        final long id = tradeId > 0L ? tradeId : inviteId;
        cancelReason = CoopTradeWireLimits.clampText(reason);
        status = Status.CANCELLED;
        hostConfirmed = false;
        guestConfirmed = false;
        pendingExecute = null;
        // Snapshot kept until caller invokes rollbackGuestApply.
        return new CoopTradeCancelEvent(id, cancelReason);
    }

    public boolean receiveCancel(final CoopTradeCancelEvent event) {
        synchronized (lock) {
            if (event == null) {
                return false;
            }
            if (tradeId > 0L && event.getTradeId() != tradeId && event.getTradeId() != inviteId) {
                return false;
            }
            cancelReason = CoopTradeWireLimits.clampText(event.getReason());
            status = Status.CANCELLED;
            hostConfirmed = false;
            guestConfirmed = false;
            pendingExecute = null;
            return true;
        }
    }

    /**
     * Disconnect mid-trade. If the guest had applied, caller must
     * {@link #rollbackGuestApply}. Host never applies without guest ack.
     */
    public CoopTradeCancelEvent onDisconnect() {
        synchronized (lock) {
            if (status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED) {
                return null;
            }
            return cancelUnlocked("disconnect");
        }
    }

    public boolean expireInviteIfNeeded(final long nowMs, final long timeoutMs) {
        synchronized (lock) {
            if (status != Status.INVITE_SENT && status != Status.INVITE_RECEIVED) {
                return false;
            }
            if (timeoutMs <= 0L || nowMs - inviteSinceMs < timeoutMs) {
                return false;
            }
            cancelUnlocked("invite expired");
            return true;
        }
    }

    /**
     * Guest ack timeout: if still {@link Status#GUEST_APPLIED} past
     * {@code timeoutMs}, cancel so the caller rolls back the snapshot.
     * Documented guest-side timeout for lost host complete acks.
     */
    public boolean expireGuestAckIfNeeded(final long nowMs, final long timeoutMs) {
        synchronized (lock) {
            if (status != Status.GUEST_APPLIED) {
                return false;
            }
            if (timeoutMs <= 0L || nowMs - guestAppliedSinceMs < timeoutMs) {
                return false;
            }
            cancelUnlocked("guest ack timeout — rolled back");
            return true;
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
            pendingExecute = null;
            inviteSinceMs = 0L;
            guestAppliedSinceMs = 0L;
            guestRollbackSnap = null;
            rateLimiter.reset();
        }
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
