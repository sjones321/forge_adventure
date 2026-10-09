package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopTradeAckEvent;
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeExecuteEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Headless TR1 trade state machine (locked for Netty + GL thread safety).
 *
 * <h2>Two-phase commit (review r4)</h2>
 * <ol>
 *   <li>Host assigns a globally unique {@link CoopTradeIds} trade id at invite.</li>
 *   <li>Offer versions come from the <b>wire</b>, not local counters.</li>
 *   <li>Both sides confirm matching mine+theirs versions → host emits Execute
 *       and enters {@link Status#WAITING_GUEST_ACK} without mutating its bag.</li>
 *   <li>Guest <b>claims</b> under the lock ({@link Status#GUEST_APPLYING}) before
 *       queuing the GL apply. Cancel is refused after Execute.</li>
 *   <li>Guest applies (idempotent), acks, enters {@link Status#GUEST_APPLIED}.
 *       After ack the guest must <b>never</b> roll back on disconnect/cancel/
 *       teardown — only an explicit host abort or reconcile {@code ABORTED}.</li>
 *   <li>Host {@link #beginHostApply(long)} claims {@link Status#HOST_APPLYING};
 *       {@link #abortHostApply} releases that claim. Commit → COMPLETED.</li>
 * </ol>
 *
 * <p>Rollback reverses only the trade's own lines ({@link CoopTradeApply#reverseLocal}).
 */
public final class CoopTradeState {
    public enum Status {
        IDLE,
        INVITE_SENT,
        INVITE_RECEIVED,
        OPEN,
        WAITING_GUEST_ACK,
        /** Guest claimed under lock; GL apply not finished. */
        GUEST_APPLYING,
        GUEST_APPLIED,
        HOST_APPLYING,
        NEEDS_RECONCILE,
        COMPLETED,
        CANCELLED
    }

    public enum TimeoutOutcome {
        NONE,
        RECONCILE,
        ALREADY_COMPLETE
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
    private String cancelReason = "";
    private CoopTradeExecuteEvent pendingExecute;
    private long inviteSinceMs;
    private long guestAppliedSinceMs;
    /** Give/receive for line-only rollback after guest apply. */
    private CoopTradeOffer guestGive;
    private CoopTradeOffer guestReceive;
    /** Set only by host abort or reconcile ABORTED. */
    private boolean guestRollbackPermitted;

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

    /** True while cancel is allowed (before Execute). */
    public boolean isCancelAllowed() {
        synchronized (lock) {
            return status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.INVITE_RECEIVED;
        }
    }

    public boolean isIdle() {
        synchronized (lock) {
            return status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED;
        }
    }

    public boolean isInFlight() {
        synchronized (lock) {
            return status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLYING
                    || status == Status.GUEST_APPLIED || status == Status.HOST_APPLYING
                    || status == Status.NEEDS_RECONCILE || status == Status.OPEN
                    || status == Status.INVITE_SENT || status == Status.INVITE_RECEIVED;
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

    public CoopTradeExecuteEvent getPendingExecute() {
        synchronized (lock) {
            return pendingExecute;
        }
    }

    public boolean isGuestRollbackPermitted() {
        synchronized (lock) {
            return guestRollbackPermitted;
        }
    }

    public boolean hasGuestRollbackLines() {
        synchronized (lock) {
            return guestGive != null && guestReceive != null;
        }
    }

    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds,
                                            final boolean weAreHost, final long nowMs) {
        synchronized (lock) {
            if (status == Status.OPEN || status == Status.INVITE_SENT
                    || status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLYING
                    || status == Status.GUEST_APPLIED || status == Status.HOST_APPLYING
                    || status == Status.NEEDS_RECONCILE) {
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
            // Host-assigned globally unique id (SecureRandom) — never a per-process counter.
            inviteId = CoopTradeIds.next();
            tradeId = inviteId;
            localRole = weAreHost ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
            inviteSinceMs = nowMs;
            cancelReason = "";
            pendingExecute = null;
            clearGuestRollbackUnlocked();
            final int timeout = Math.max(1, Math.min(timeoutSeconds, 120));
            return new CoopTradeInviteEvent(inviteId, from, timeout);
        }
    }

    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds,
                                            final boolean weAreHost) {
        return beginInvite(fromPlayer, timeoutSeconds, weAreHost, System.currentTimeMillis());
    }

    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds) {
        return beginInvite(fromPlayer, timeoutSeconds, true);
    }

    public boolean receiveInvite(final CoopTradeInviteEvent invite, final boolean weAreHost,
                                 final long nowMs) {
        synchronized (lock) {
            if (invite == null || status == Status.OPEN || status == Status.WAITING_GUEST_ACK
                    || status == Status.GUEST_APPLYING || status == Status.GUEST_APPLIED
                    || status == Status.HOST_APPLYING || status == Status.NEEDS_RECONCILE) {
                return false;
            }
            if (!rateLimiter.tryAcquire(nowMs)) {
                return false;
            }
            if (invite.getInviteId() == 0L) {
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
            clearGuestRollbackUnlocked();
            return true;
        }
    }

    public boolean receiveInvite(final CoopTradeInviteEvent invite, final boolean weAreHost) {
        return receiveInvite(invite, weAreHost, System.currentTimeMillis());
    }

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
     * Accept an offer. Version is taken from the <b>wire</b>
     * ({@link CoopTradeOfferEvent#getOfferVersion()}); it must be strictly greater
     * than the current version for that role.
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
            final int myCurrent = role == CoopTradeRole.HOST ? hostOfferVersion : guestOfferVersion;
            final int theirCurrent = role == CoopTradeRole.HOST ? guestOfferVersion : hostOfferVersion;
            if (event.getMyOfferVersion() != myCurrent
                    || event.getTheirOfferVersion() != theirCurrent) {
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
            if (guestBag != null
                    && !CoopTradeValidator.validateReceiverGold(hostOffer, guestBag).ok()) {
                return cancelUnlocked("receiver gold overflow");
            }
            if (hostBag != null
                    && !CoopTradeValidator.validateReceiverGold(guestOffer, hostBag).ok()) {
                return cancelUnlocked("receiver gold overflow");
            }
            if (!weAreHost) {
                return event;
            }
            final CoopTradeExecuteEvent exec = new CoopTradeExecuteEvent(
                    tradeId, hostOffer, guestOffer, hostOfferVersion, guestOfferVersion);
            pendingExecute = exec;
            status = Status.WAITING_GUEST_ACK;
            tradeLog.record(tradeId, CoopTradeLog.Phase.EXECUTED, nowMs, hostOffer, guestOffer);
            return exec;
        }
    }

    public Object acceptConfirm(final CoopTradeConfirmEvent event, final boolean weAreHost) {
        return acceptConfirm(event, weAreHost, System.currentTimeMillis());
    }

    public boolean receiveExecute(final CoopTradeExecuteEvent event, final boolean weAreHost,
                                  final long nowMs) {
        synchronized (lock) {
            if (event == null || event.getTradeId() == 0L) {
                return false;
            }
            if (tradeLog.isAtLeast(event.getTradeId(), CoopTradeLog.Phase.COMPLETED)
                    || (weAreHost && tradeLog.hasLocalApply(event.getTradeId(), CoopTradeRole.HOST))
                    || (!weAreHost && tradeLog.hasLocalApply(event.getTradeId(), CoopTradeRole.GUEST))) {
                tradeId = event.getTradeId();
                pendingExecute = event;
                status = Status.COMPLETED;
                return true;
            }
            if (status != Status.OPEN && status != Status.WAITING_GUEST_ACK) {
                return false;
            }
            tradeId = event.getTradeId();
            hostOffer = event.getHostOffer();
            guestOffer = event.getGuestOffer();
            hostOfferVersion = event.getHostOfferVersion();
            guestOfferVersion = event.getGuestOfferVersion();
            pendingExecute = event;
            status = Status.WAITING_GUEST_ACK;
            tradeLog.record(tradeId, CoopTradeLog.Phase.EXECUTED, nowMs, hostOffer, guestOffer);
            return true;
        }
    }

    public boolean receiveExecute(final CoopTradeExecuteEvent event, final boolean weAreHost) {
        return receiveExecute(event, weAreHost, System.currentTimeMillis());
    }

    public boolean receiveExecute(final CoopTradeExecuteEvent event) {
        return receiveExecute(event, localRole == CoopTradeRole.HOST);
    }

    /**
     * Claim guest apply under the lock <b>before</b> queuing the GL runnable.
     * Mismatched trade id → null. Cancel/disconnect that lands before this claim
     * cannot see a later bag mutation from a stale queued apply.
     */
    public CoopTradeExecuteEvent claimGuestApply(final long claimTradeId) {
        synchronized (lock) {
            if (claimTradeId == 0L || claimTradeId != tradeId) {
                return null;
            }
            if (pendingExecute == null || pendingExecute.getTradeId() != claimTradeId) {
                return null;
            }
            if (tradeLog.hasLocalApply(claimTradeId, CoopTradeRole.GUEST)) {
                status = Status.GUEST_APPLIED;
                return null;
            }
            if (status != Status.WAITING_GUEST_ACK) {
                return null;
            }
            status = Status.GUEST_APPLYING;
            return pendingExecute;
        }
    }

    /** True while the GL guest apply claim is still held. */
    public boolean isGuestApplyClaimed(final long claimTradeId) {
        synchronized (lock) {
            return status == Status.GUEST_APPLYING && tradeId == claimTradeId
                    && pendingExecute != null && pendingExecute.getTradeId() == claimTradeId;
        }
    }

    public CoopTradeAckEvent markGuestApplied(final boolean success, final String detail,
                                              final long nowMs) {
        synchronized (lock) {
            if (pendingExecute == null) {
                return null;
            }
            final long id = pendingExecute.getTradeId();
            if (success && tradeLog.hasLocalApply(id, CoopTradeRole.GUEST)) {
                status = Status.GUEST_APPLIED;
                guestAppliedSinceMs = nowMs;
                guestGive = pendingExecute.getGuestOffer();
                guestReceive = pendingExecute.getHostOffer();
                guestRollbackPermitted = false;
                return new CoopTradeAckEvent(id, CoopTradeRole.GUEST, true, "idempotent");
            }
            if (status != Status.GUEST_APPLYING && status != Status.WAITING_GUEST_ACK
                    && status != Status.NEEDS_RECONCILE) {
                return null;
            }
            if (success) {
                guestGive = pendingExecute.getGuestOffer();
                guestReceive = pendingExecute.getHostOffer();
                guestRollbackPermitted = false;
                guestAppliedSinceMs = nowMs;
                status = Status.GUEST_APPLIED;
                tradeLog.record(id, CoopTradeLog.Phase.GUEST_APPLIED, nowMs,
                        pendingExecute.getHostOffer(), pendingExecute.getGuestOffer());
            } else {
                clearGuestRollbackUnlocked();
                status = Status.CANCELLED;
                cancelReason = detail == null ? "guest apply failed" : detail;
                pendingExecute = null;
                tradeLog.record(id, CoopTradeLog.Phase.ABORTED, nowMs);
            }
            return new CoopTradeAckEvent(id, CoopTradeRole.GUEST, success,
                    detail == null ? "" : detail);
        }
    }

    public Object receiveGuestAck(final CoopTradeAckEvent ack) {
        synchronized (lock) {
            if (ack == null || ack.getFromRole() != CoopTradeRole.GUEST) {
                return null;
            }
            if (ack.getTradeId() != tradeId) {
                return null;
            }
            if (status != Status.WAITING_GUEST_ACK) {
                if (tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED) && ack.isSuccess()) {
                    return new CoopTradeAckEvent(tradeId, CoopTradeRole.HOST, true, "complete");
                }
                return null;
            }
            if (!ack.isSuccess()) {
                // Guest failed before apply — abort is allowed (no guest bag change).
                return abortAfterExecuteUnlocked("guest apply failed: " + ack.getDetail());
            }
            return ack;
        }
    }

    /**
     * Claim host apply for {@code claimTradeId}. Mismatch → null.
     */
    public CoopTradeExecuteEvent beginHostApply(final long claimTradeId) {
        synchronized (lock) {
            if (claimTradeId == 0L || claimTradeId != tradeId) {
                return null;
            }
            if (pendingExecute == null || pendingExecute.getTradeId() != claimTradeId) {
                return null;
            }
            if (tradeLog.hasLocalApply(claimTradeId, CoopTradeRole.HOST)) {
                status = Status.COMPLETED;
                return null;
            }
            if (status != Status.WAITING_GUEST_ACK) {
                return null;
            }
            status = Status.HOST_APPLYING;
            return pendingExecute;
        }
    }

    /** @deprecated use {@link #beginHostApply(long)} */
    public CoopTradeExecuteEvent beginHostApply() {
        synchronized (lock) {
            return beginHostApply(tradeId);
        }
    }

    public CoopTradeAckEvent markHostCompleted() {
        synchronized (lock) {
            if (status != Status.HOST_APPLYING) {
                return null;
            }
            if (pendingExecute == null) {
                return null;
            }
            final long id = pendingExecute.getTradeId();
            final long now = System.currentTimeMillis();
            tradeLog.record(id, CoopTradeLog.Phase.HOST_COMMITTED, now,
                    pendingExecute.getHostOffer(), pendingExecute.getGuestOffer());
            tradeLog.record(id, CoopTradeLog.Phase.COMPLETED, now);
            status = Status.COMPLETED;
            hostConfirmed = false;
            guestConfirmed = false;
            clearGuestRollbackUnlocked();
            return new CoopTradeAckEvent(id, CoopTradeRole.HOST, true, "complete");
        }
    }

    /**
     * Host apply failed — <b>releases</b> {@link Status#HOST_APPLYING} and
     * emits an abort so the guest may roll back (explicit host abort).
     */
    public CoopTradeCancelEvent abortHostApply(final String reason) {
        synchronized (lock) {
            if (status != Status.HOST_APPLYING) {
                return null;
            }
            return abortAfterExecuteUnlocked(reason != null ? reason : "host apply failed");
        }
    }

    public boolean receiveHostComplete(final CoopTradeAckEvent ack) {
        synchronized (lock) {
            if (ack == null || ack.getFromRole() != CoopTradeRole.HOST || !ack.isSuccess()) {
                return false;
            }
            // Strict trade-id match — mismatches ignored.
            if (ack.getTradeId() != tradeId) {
                return false;
            }
            if (status != Status.GUEST_APPLIED && status != Status.WAITING_GUEST_ACK
                    && status != Status.GUEST_APPLYING && status != Status.NEEDS_RECONCILE
                    && status != Status.COMPLETED) {
                return false;
            }
            clearGuestRollbackUnlocked();
            status = Status.COMPLETED;
            hostConfirmed = false;
            guestConfirmed = false;
            final long now = System.currentTimeMillis();
            final CoopTradeOffer h = pendingExecute != null ? pendingExecute.getHostOffer() : hostOffer;
            final CoopTradeOffer g = pendingExecute != null ? pendingExecute.getGuestOffer() : guestOffer;
            tradeLog.record(tradeId, CoopTradeLog.Phase.HOST_COMMITTED, now, h, g);
            tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, now);
            return true;
        }
    }

    /**
     * Line-only guest rollback. Allowed only when {@link #guestRollbackPermitted}
     * (host abort / reconcile ABORTED) and never after host commit.
     */
    public boolean rollbackGuestApply(final CoopTradeBag bag) {
        synchronized (lock) {
            if (!guestRollbackPermitted) {
                return false;
            }
            if (tradeId != 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                clearGuestRollbackUnlocked();
                return false;
            }
            if (bag == null || guestGive == null || guestReceive == null) {
                clearGuestRollbackUnlocked();
                return false;
            }
            final CoopTradeApply.Result r = CoopTradeApply.reverseLocal(bag, guestGive, guestReceive);
            clearGuestRollbackUnlocked();
            if (tradeId != 0L) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
            }
            return r.applied;
        }
    }

    /**
     * Cancel is refused after Execute (WAITING_GUEST_ACK and beyond), except via
     * {@link #abortHostApply}.
     */
    public CoopTradeCancelEvent cancel(final String reason) {
        synchronized (lock) {
            return cancelUnlocked(reason);
        }
    }

    private CoopTradeCancelEvent cancelUnlocked(final String reason) {
        if (status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLYING
                || status == Status.GUEST_APPLIED || status == Status.HOST_APPLYING
                || status == Status.NEEDS_RECONCILE) {
            // Cancel disabled after Execute.
            return null;
        }
        final long id = tradeId != 0L ? tradeId : inviteId;
        cancelReason = CoopTradeWireLimits.clampText(reason);
        status = Status.CANCELLED;
        hostConfirmed = false;
        guestConfirmed = false;
        pendingExecute = null;
        clearGuestRollbackUnlocked();
        if (id != 0L) {
            tradeLog.record(id, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
        }
        return new CoopTradeCancelEvent(id, cancelReason);
    }

    /** Explicit abort after Execute (host apply failure / guest apply failure). */
    private CoopTradeCancelEvent abortAfterExecuteUnlocked(final String reason) {
        final long id = tradeId != 0L ? tradeId : inviteId;
        cancelReason = CoopTradeWireLimits.clampText(reason);
        // Permit guest line-rollback only if guest had applied.
        if (status == Status.GUEST_APPLIED || status == Status.NEEDS_RECONCILE
                || tradeLog.hasLocalApply(id, CoopTradeRole.GUEST)) {
            guestRollbackPermitted = true;
        } else {
            clearGuestRollbackUnlocked();
        }
        status = Status.CANCELLED;
        hostConfirmed = false;
        guestConfirmed = false;
        pendingExecute = null;
        if (id != 0L && !tradeLog.isAtLeast(id, CoopTradeLog.Phase.HOST_COMMITTED)) {
            tradeLog.record(id, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
        }
        return new CoopTradeCancelEvent(id, cancelReason);
    }

    public boolean receiveCancel(final CoopTradeCancelEvent event) {
        synchronized (lock) {
            if (event == null) {
                return false;
            }
            if (event.getTradeId() != tradeId && event.getTradeId() != inviteId) {
                return false;
            }
            if (tradeId != 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                return false;
            }
            // After Execute: only honor as an explicit host abort (permits rollback).
            if (status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLYING
                    || status == Status.GUEST_APPLIED || status == Status.HOST_APPLYING
                    || status == Status.NEEDS_RECONCILE) {
                abortAfterExecuteUnlocked(event.getReason());
                return true;
            }
            cancelReason = CoopTradeWireLimits.clampText(event.getReason());
            status = Status.CANCELLED;
            hostConfirmed = false;
            guestConfirmed = false;
            pendingExecute = null;
            clearGuestRollbackUnlocked();
            if (tradeId != 0L) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
            }
            return true;
        }
    }

    /**
     * Disconnect. After guest ack: never roll back — enter NEEDS_RECONCILE.
     * Before claim: abandon without bag mutation. During GUEST_APPLYING before
     * log apply: release claim without mutation.
     */
    public CoopTradeCancelEvent onDisconnect() {
        synchronized (lock) {
            if (status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED) {
                return null;
            }
            if (status == Status.HOST_APPLYING) {
                return null;
            }
            if (tradeId != 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                status = Status.COMPLETED;
                clearGuestRollbackUnlocked();
                return null;
            }
            if (status == Status.GUEST_APPLIED || status == Status.NEEDS_RECONCILE) {
                // After ack: never roll back on disconnect.
                guestRollbackPermitted = false;
                status = Status.NEEDS_RECONCILE;
                return null;
            }
            if (status == Status.GUEST_APPLYING) {
                // Claim held but not yet applied to log — release without mutation.
                if (!tradeLog.hasLocalApply(tradeId, CoopTradeRole.GUEST)) {
                    status = Status.CANCELLED;
                    cancelReason = "disconnect";
                    pendingExecute = null;
                    clearGuestRollbackUnlocked();
                    tradeLog.record(tradeId, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
                } else {
                    guestRollbackPermitted = false;
                    status = Status.NEEDS_RECONCILE;
                }
                return null;
            }
            if (status == Status.WAITING_GUEST_ACK) {
                // Neither side applied — abort cleanly; no cancel event for rollback.
                status = Status.CANCELLED;
                cancelReason = "disconnect";
                pendingExecute = null;
                clearGuestRollbackUnlocked();
                tradeLog.record(tradeId, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
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

    public TimeoutOutcome expireGuestAckIfNeeded(final long nowMs, final long timeoutMs) {
        synchronized (lock) {
            if (status != Status.GUEST_APPLIED && status != Status.NEEDS_RECONCILE) {
                return TimeoutOutcome.NONE;
            }
            if (status == Status.GUEST_APPLIED
                    && (timeoutMs <= 0L || nowMs - guestAppliedSinceMs < timeoutMs)) {
                return TimeoutOutcome.NONE;
            }
            if (tradeId != 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                clearGuestRollbackUnlocked();
                status = Status.COMPLETED;
                tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, nowMs);
                return TimeoutOutcome.ALREADY_COMPLETE;
            }
            guestRollbackPermitted = false;
            status = Status.NEEDS_RECONCILE;
            return TimeoutOutcome.RECONCILE;
        }
    }

    public CoopTradeLog.ReconcileAction applyReconcile(final CoopTradeReconcileEvent event,
                                                       final long nowMs) {
        synchronized (lock) {
            if (event == null || event.getTradeId() == 0L) {
                return CoopTradeLog.ReconcileAction.NONE;
            }
            // Ignore mismatches against the active in-flight trade id.
            if (tradeId != 0L && event.getTradeId() != tradeId
                    && (status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLYING
                    || status == Status.GUEST_APPLIED || status == Status.HOST_APPLYING
                    || status == Status.NEEDS_RECONCILE)) {
                return CoopTradeLog.ReconcileAction.NONE;
            }
            final long id = event.getTradeId();
            final CoopTradeLog.Entry local = tradeLog.get(id);
            final CoopTradeLog.Entry peer = new CoopTradeLog.Entry(id, event.getPhaseEnum(), nowMs);
            final CoopTradeLog.ReconcileAction action = CoopTradeLog.reconcile(local, peer);
            if (action == CoopTradeLog.ReconcileAction.COMPLETE_GUEST) {
                tradeId = id;
                clearGuestRollbackUnlocked();
                status = Status.COMPLETED;
                tradeLog.record(id, CoopTradeLog.Phase.HOST_COMMITTED, nowMs);
                tradeLog.record(id, CoopTradeLog.Phase.COMPLETED, nowMs);
            } else if (action == CoopTradeLog.ReconcileAction.ROLLBACK_GUEST) {
                tradeId = id;
                status = Status.CANCELLED;
                cancelReason = "reconcile abort";
                guestRollbackPermitted = true;
                if (local != null) {
                    guestGive = local.guestOffer;
                    guestReceive = local.hostOffer;
                }
                tradeLog.record(id, CoopTradeLog.Phase.ABORTED, nowMs);
            } else if (action == CoopTradeLog.ReconcileAction.APPLY_HOST) {
                tradeId = id;
                if (local != null && pendingExecute == null) {
                    pendingExecute = new CoopTradeExecuteEvent(id, local.hostOffer, local.guestOffer, 0, 0);
                    hostOffer = local.hostOffer;
                    guestOffer = local.guestOffer;
                }
                status = Status.WAITING_GUEST_ACK;
            } else if (action == CoopTradeLog.ReconcileAction.RESEND_HOST_COMPLETE) {
                tradeId = id;
                status = Status.COMPLETED;
            } else if (action == CoopTradeLog.ReconcileAction.RESEND_GUEST_ACK) {
                tradeId = id;
                if (status != Status.GUEST_APPLIED && status != Status.NEEDS_RECONCILE) {
                    status = Status.GUEST_APPLIED;
                }
            }
            return action;
        }
    }

    public List<CoopTradeReconcileEvent> buildReconcileRequests(final boolean asRequests) {
        synchronized (lock) {
            final List<CoopTradeReconcileEvent> out = new ArrayList<>();
            for (final CoopTradeLog.Entry e : tradeLog.snapshotInFlight()) {
                out.add(new CoopTradeReconcileEvent(e.tradeId, localRole, e.phase, asRequests));
            }
            if ((status == Status.NEEDS_RECONCILE || status == Status.GUEST_APPLIED
                    || status == Status.WAITING_GUEST_ACK || status == Status.HOST_APPLYING
                    || status == Status.GUEST_APPLYING) && tradeId != 0L) {
                final CoopTradeLog.Phase phase;
                if (status == Status.GUEST_APPLIED || status == Status.NEEDS_RECONCILE) {
                    phase = CoopTradeLog.Phase.GUEST_APPLIED;
                } else {
                    phase = CoopTradeLog.Phase.EXECUTED;
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

    /**
     * Teardown: never roll back after guest ack. Clears rollback permission.
     * @return true if a pre-ack claim was released without mutation
     */
    public boolean onTeardown() {
        synchronized (lock) {
            if (status == Status.GUEST_APPLIED || status == Status.NEEDS_RECONCILE
                    || (status == Status.GUEST_APPLYING
                    && tradeLog.hasLocalApply(tradeId, CoopTradeRole.GUEST))) {
                guestRollbackPermitted = false;
                status = Status.NEEDS_RECONCILE;
                return false;
            }
            if (status == Status.GUEST_APPLYING) {
                status = Status.CANCELLED;
                cancelReason = "teardown";
                pendingExecute = null;
                clearGuestRollbackUnlocked();
                if (tradeId != 0L) {
                    tradeLog.record(tradeId, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
                }
                return true;
            }
            guestRollbackPermitted = false;
            return false;
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
            clearGuestRollbackUnlocked();
            rateLimiter.reset();
        }
    }

    private void clearGuestRollbackUnlocked() {
        guestGive = null;
        guestReceive = null;
        guestRollbackPermitted = false;
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
