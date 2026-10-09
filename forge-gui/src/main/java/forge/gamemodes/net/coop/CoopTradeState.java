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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Headless TR1 trade state machine (locked for Netty + GL thread safety).
 *
 * <h2>Two-phase commit</h2>
 * <ol>
 *   <li>Both sides confirm matching <b>both</b> offer versions → host emits
 *       {@link CoopTradeExecuteEvent} with a unique trade id and enters
 *       {@link Status#WAITING_GUEST_ACK} <b>without</b> mutating its bag.</li>
 *   <li>Guest applies locally (idempotent by trade id), keeps a rollback
 *       snapshot (including Overflow), sends {@link CoopTradeAckEvent}, enters
 *       {@link Status#GUEST_APPLIED}.</li>
 *   <li>Host applies only after a successful guest ack (idempotent), then sends
 *       a host ack (complete). Guest discards its snapshot.</li>
 *   <li>Host commit ({@link CoopTradeLog.Phase#HOST_COMMITTED}) is the commit
 *       point. Before that, cancel / disconnect / timeout may roll the guest
 *       back. Afterward the guest must <b>never</b> roll back — reconnect
 *       reconcile finishes forward.</li>
 * </ol>
 *
 * <p>Peers are identified by {@link CoopTradeRole}. Confirms carry mine+theirs
 * offer versions. Cancel vs host-apply uses an atomic {@link Status#HOST_APPLYING}
 * claim so {@code getPendingExecute} / {@code markHostCompleted} cannot race.
 */
public final class CoopTradeState {
    public enum Status {
        IDLE,
        INVITE_SENT,
        INVITE_RECEIVED,
        OPEN,
        /** Host sent Execute; waiting for guest Ack. Host bag unchanged. */
        WAITING_GUEST_ACK,
        /** Guest applied locally; waiting for host complete Ack. */
        GUEST_APPLIED,
        /** Host claimed the pending execute; apply in progress (cancel blocked). */
        HOST_APPLYING,
        /**
         * Guest applied; ack timeout fired before host commit was known.
         * Keep the snapshot and reconcile on reconnect — do not roll back yet.
         */
        NEEDS_RECONCILE,
        COMPLETED,
        CANCELLED
    }

    /** Outcome of {@link #expireGuestAckIfNeeded}. */
    public enum TimeoutOutcome {
        NONE,
        /** Host not committed — safe to roll back. */
        ROLLBACK,
        /** Ambiguous / host may have committed — keep snap, reconcile. */
        RECONCILE,
        /** Log already shows host commit — keep apply, mark complete. */
        ALREADY_COMPLETE
    }

    private final Object lock = new Object();
    private final AtomicLong seq = new AtomicLong(1L);
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
    private CoopTradeBag.Snapshot guestRollbackSnap;

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

    public boolean isIdle() {
        synchronized (lock) {
            return status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED;
        }
    }

    public boolean isInFlight() {
        synchronized (lock) {
            return status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLIED
                    || status == Status.HOST_APPLYING || status == Status.NEEDS_RECONCILE
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
                    || status == Status.WAITING_GUEST_ACK || status == Status.GUEST_APPLIED
                    || status == Status.HOST_APPLYING || status == Status.NEEDS_RECONCILE) {
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
            // Unique trade id (also used as invite id) — never reused; apply is idempotent by id.
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
                    || status == Status.GUEST_APPLIED || status == Status.HOST_APPLYING
                    || status == Status.NEEDS_RECONCILE) {
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
            // Guest provisional trade id; replaced by Execute's authoritative trade id.
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
     * Confirm carrying both offer versions. Mismatched versions are ignored.
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
            final int myCurrent = role == CoopTradeRole.HOST ? hostOfferVersion : guestOfferVersion;
            final int theirCurrent = role == CoopTradeRole.HOST ? guestOfferVersion : hostOfferVersion;
            if (event.getMyOfferVersion() != myCurrent
                    || event.getTheirOfferVersion() != theirCurrent) {
                // Stale confirm — versions don't match current state.
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
            // Gold overflow against the RECEIVER, not the giver.
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
            tradeLog.record(tradeId, CoopTradeLog.Phase.EXECUTED, nowMs);
            return exec;
        }
    }

    public Object acceptConfirm(final CoopTradeConfirmEvent event, final boolean weAreHost) {
        return acceptConfirm(event, weAreHost, System.currentTimeMillis());
    }

    /** Guest (or host mirror) receives Execute — do not apply bags here. */
    public boolean receiveExecute(final CoopTradeExecuteEvent event, final boolean weAreHost,
                                  final long nowMs) {
        synchronized (lock) {
            if (event == null) {
                return false;
            }
            // Idempotent: already completed/applied this trade id.
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
            // Authoritative trade id comes from Execute.
            tradeId = event.getTradeId();
            hostOffer = event.getHostOffer();
            guestOffer = event.getGuestOffer();
            hostOfferVersion = event.getHostOfferVersion();
            guestOfferVersion = event.getGuestOfferVersion();
            pendingExecute = event;
            status = Status.WAITING_GUEST_ACK;
            tradeLog.record(tradeId, CoopTradeLog.Phase.EXECUTED, nowMs);
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
     * Guest finished local apply (or failed). On success keeps {@code snap} for
     * rollback until host commit is known. Duplicate success for the same trade
     * id is a no-op (idempotent).
     */
    public CoopTradeAckEvent markGuestApplied(final boolean success, final String detail,
                                              final CoopTradeBag.Snapshot snap, final long nowMs) {
        synchronized (lock) {
            if (pendingExecute == null) {
                return null;
            }
            final long id = pendingExecute.getTradeId();
            if (success && tradeLog.hasLocalApply(id, CoopTradeRole.GUEST)) {
                // Idempotent re-apply — already mutated; keep/refresh snap for safety.
                if (snap != null && guestRollbackSnap == null) {
                    guestRollbackSnap = snap;
                }
                status = Status.GUEST_APPLIED;
                guestAppliedSinceMs = nowMs;
                return new CoopTradeAckEvent(id, CoopTradeRole.GUEST, true, "idempotent");
            }
            if (status != Status.WAITING_GUEST_ACK && status != Status.OPEN
                    && status != Status.NEEDS_RECONCILE) {
                return null;
            }
            if (success) {
                guestRollbackSnap = snap;
                guestAppliedSinceMs = nowMs;
                status = Status.GUEST_APPLIED;
                tradeLog.record(id, CoopTradeLog.Phase.GUEST_APPLIED, nowMs);
            } else {
                guestRollbackSnap = null;
                status = Status.CANCELLED;
                cancelReason = detail == null ? "guest apply failed" : detail;
                tradeLog.record(id, CoopTradeLog.Phase.ABORTED, nowMs);
            }
            return new CoopTradeAckEvent(id, CoopTradeRole.GUEST, success,
                    detail == null ? "" : detail);
        }
    }

    /**
     * Host processes guest ack. On success the caller must
     * {@link #beginHostApply()} then apply, then {@link #markHostCompleted()}.
     * On failure returns a cancel (host never applies).
     */
    public Object receiveGuestAck(final CoopTradeAckEvent ack) {
        synchronized (lock) {
            if (ack == null || ack.getFromRole() != CoopTradeRole.GUEST) {
                return null;
            }
            if (status != Status.WAITING_GUEST_ACK || ack.getTradeId() != tradeId) {
                // Idempotent: already past this point.
                if (ack.getTradeId() == tradeId && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)
                        && ack.isSuccess()) {
                    return new CoopTradeAckEvent(tradeId, CoopTradeRole.HOST, true, "complete");
                }
                return null;
            }
            if (!ack.isSuccess()) {
                return cancelUnlocked("guest apply failed: " + ack.getDetail());
            }
            return ack;
        }
    }

    /**
     * Atomically claim the pending execute for host apply. Transitions
     * WAITING_GUEST_ACK → HOST_APPLYING. Cancel cannot interleave: while
     * HOST_APPLYING, {@link #cancel} is rejected.
     * @return the execute to apply, or null if cancelled / already done / missing
     */
    public CoopTradeExecuteEvent beginHostApply() {
        synchronized (lock) {
            if (pendingExecute == null) {
                return null;
            }
            final long id = pendingExecute.getTradeId();
            if (tradeLog.hasLocalApply(id, CoopTradeRole.HOST)) {
                // Idempotent — already applied; jump to completed.
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

    /** Host finished applying after guest ack — emit complete ack for the guest. */
    public CoopTradeAckEvent markHostCompleted() {
        synchronized (lock) {
            if (status != Status.HOST_APPLYING && status != Status.WAITING_GUEST_ACK) {
                return null;
            }
            if (pendingExecute == null) {
                return null;
            }
            final long id = pendingExecute.getTradeId();
            final long now = System.currentTimeMillis();
            tradeLog.record(id, CoopTradeLog.Phase.HOST_COMMITTED, now);
            tradeLog.record(id, CoopTradeLog.Phase.COMPLETED, now);
            status = Status.COMPLETED;
            hostConfirmed = false;
            guestConfirmed = false;
            guestRollbackSnap = null;
            return new CoopTradeAckEvent(id, CoopTradeRole.HOST, true, "complete");
        }
    }

    /**
     * Host apply failed after {@link #beginHostApply}. Releases HOST_APPLYING
     * and cancels so the guest can roll back (commit point not reached).
     */
    public CoopTradeCancelEvent abortHostApply(final String reason) {
        synchronized (lock) {
            if (status != Status.HOST_APPLYING) {
                return cancelUnlocked(reason);
            }
            // Commit point not reached — abort is allowed.
            return cancelUnlocked(reason != null ? reason : "host apply failed");
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
            if (ack.getTradeId() != tradeId && tradeId > 0L) {
                // Still accept if log knows this trade.
                if (!tradeLog.isAtLeast(ack.getTradeId(), CoopTradeLog.Phase.GUEST_APPLIED)
                        && status != Status.GUEST_APPLIED && status != Status.WAITING_GUEST_ACK
                        && status != Status.NEEDS_RECONCILE) {
                    return false;
                }
            }
            if (status != Status.GUEST_APPLIED && status != Status.WAITING_GUEST_ACK
                    && status != Status.NEEDS_RECONCILE && status != Status.COMPLETED) {
                return false;
            }
            final long id = ack.getTradeId() > 0L ? ack.getTradeId() : tradeId;
            tradeId = id;
            guestRollbackSnap = null;
            status = Status.COMPLETED;
            hostConfirmed = false;
            guestConfirmed = false;
            final long now = System.currentTimeMillis();
            tradeLog.record(id, CoopTradeLog.Phase.HOST_COMMITTED, now);
            tradeLog.record(id, CoopTradeLog.Phase.COMPLETED, now);
            return true;
        }
    }

    /**
     * Roll back guest apply using the stored snapshot. Must not be called after
     * host commit ({@link CoopTradeLog.Phase#HOST_COMMITTED}).
     * @return true if a snapshot was restored
     */
    public boolean rollbackGuestApply(final CoopTradeBag bag) {
        synchronized (lock) {
            if (tradeId > 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                // Never roll back after the host commit point.
                guestRollbackSnap = null;
                return false;
            }
            if (guestRollbackSnap == null || bag == null) {
                guestRollbackSnap = null;
                return false;
            }
            bag.restore(guestRollbackSnap);
            guestRollbackSnap = null;
            if (tradeId > 0L) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
            }
            return true;
        }
    }

    public CoopTradeCancelEvent cancel(final String reason) {
        synchronized (lock) {
            return cancelUnlocked(reason);
        }
    }

    private CoopTradeCancelEvent cancelUnlocked(final String reason) {
        // Atomic with host apply: reject cancel while HOST_APPLYING so
        // beginHostApply → apply → markHostCompleted cannot race with cancel.
        if (status == Status.HOST_APPLYING) {
            return null;
        }
        final long id = tradeId > 0L ? tradeId : inviteId;
        cancelReason = CoopTradeWireLimits.clampText(reason);
        status = Status.CANCELLED;
        hostConfirmed = false;
        guestConfirmed = false;
        pendingExecute = null;
        if (id > 0L && !tradeLog.isAtLeast(id, CoopTradeLog.Phase.HOST_COMMITTED)) {
            tradeLog.record(id, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
        }
        // Snapshot kept until caller invokes rollbackGuestApply (only if pre-commit).
        return new CoopTradeCancelEvent(id, cancelReason);
    }

    public boolean receiveCancel(final CoopTradeCancelEvent event) {
        synchronized (lock) {
            if (event == null) {
                return false;
            }
            if (status == Status.HOST_APPLYING) {
                return false;
            }
            if (tradeId > 0L && event.getTradeId() != tradeId && event.getTradeId() != inviteId) {
                return false;
            }
            // After host commit, ignore cancel — reconcile forward instead.
            if (tradeId > 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                return false;
            }
            cancelReason = CoopTradeWireLimits.clampText(event.getReason());
            status = Status.CANCELLED;
            hostConfirmed = false;
            guestConfirmed = false;
            pendingExecute = null;
            if (tradeId > 0L) {
                tradeLog.record(tradeId, CoopTradeLog.Phase.ABORTED, System.currentTimeMillis());
            }
            return true;
        }
    }

    /**
     * Disconnect mid-trade. If the guest had applied and host has not committed,
     * caller must {@link #rollbackGuestApply}. Host never applies without guest ack.
     */
    public CoopTradeCancelEvent onDisconnect() {
        synchronized (lock) {
            if (status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED) {
                return null;
            }
            if (status == Status.HOST_APPLYING) {
                // Let in-flight host apply finish; do not cancel under the lock race.
                return null;
            }
            if (tradeId > 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                status = Status.COMPLETED;
                guestRollbackSnap = null;
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
     * Guest ack timeout. Rollback is allowed only before the host commit point.
     * If the log already shows host commit, complete without rollback. If the
     * host commit is unknown, enter {@link Status#NEEDS_RECONCILE} (keep snap).
     */
    public TimeoutOutcome expireGuestAckIfNeeded(final long nowMs, final long timeoutMs) {
        synchronized (lock) {
            if (status != Status.GUEST_APPLIED && status != Status.NEEDS_RECONCILE) {
                return TimeoutOutcome.NONE;
            }
            if (status == Status.GUEST_APPLIED
                    && (timeoutMs <= 0L || nowMs - guestAppliedSinceMs < timeoutMs)) {
                return TimeoutOutcome.NONE;
            }
            if (tradeId > 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                guestRollbackSnap = null;
                status = Status.COMPLETED;
                tradeLog.record(tradeId, CoopTradeLog.Phase.COMPLETED, nowMs);
                return TimeoutOutcome.ALREADY_COMPLETE;
            }
            // Ambiguous: do not roll back — reconcile on reconnect.
            status = Status.NEEDS_RECONCILE;
            return TimeoutOutcome.RECONCILE;
        }
    }

    /**
     * Apply a peer reconcile advertisement. Returns the action the local side
     * should take (resend ack, roll back, complete, apply host, …).
     */
    public CoopTradeLog.ReconcileAction applyReconcile(final CoopTradeReconcileEvent event,
                                                       final long nowMs) {
        synchronized (lock) {
            if (event == null || event.getTradeId() <= 0L) {
                return CoopTradeLog.ReconcileAction.NONE;
            }
            final long id = event.getTradeId();
            final CoopTradeLog.Entry local = tradeLog.get(id);
            final CoopTradeLog.Entry peer = new CoopTradeLog.Entry(id, event.getPhaseEnum(), nowMs);
            final CoopTradeLog.ReconcileAction action = CoopTradeLog.reconcile(local, peer);
            if (action == CoopTradeLog.ReconcileAction.COMPLETE_GUEST) {
                tradeId = id;
                guestRollbackSnap = null;
                status = Status.COMPLETED;
                tradeLog.record(id, CoopTradeLog.Phase.HOST_COMMITTED, nowMs);
                tradeLog.record(id, CoopTradeLog.Phase.COMPLETED, nowMs);
            } else if (action == CoopTradeLog.ReconcileAction.ROLLBACK_GUEST) {
                tradeId = id;
                status = Status.CANCELLED;
                cancelReason = "reconcile abort";
                tradeLog.record(id, CoopTradeLog.Phase.ABORTED, nowMs);
            } else if (action == CoopTradeLog.ReconcileAction.APPLY_HOST) {
                tradeId = id;
                if (pendingExecute == null && localRole == CoopTradeRole.HOST) {
                    // Caller must still have offers; mark waiting so beginHostApply works.
                    status = Status.WAITING_GUEST_ACK;
                }
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

    /** Build reconcile advertisements for every in-flight log entry. */
    public List<CoopTradeReconcileEvent> buildReconcileRequests(final boolean asRequests) {
        synchronized (lock) {
            final List<CoopTradeReconcileEvent> out = new ArrayList<>();
            for (final CoopTradeLog.Entry e : tradeLog.snapshotInFlight()) {
                out.add(new CoopTradeReconcileEvent(e.tradeId, localRole, e.phase, asRequests));
            }
            if (status == Status.NEEDS_RECONCILE || status == Status.GUEST_APPLIED
                    || status == Status.WAITING_GUEST_ACK || status == Status.HOST_APPLYING) {
                if (tradeId > 0L) {
                    final CoopTradeLog.Phase phase;
                    if (status == Status.GUEST_APPLIED || status == Status.NEEDS_RECONCILE) {
                        phase = CoopTradeLog.Phase.GUEST_APPLIED;
                    } else if (status == Status.HOST_APPLYING) {
                        phase = CoopTradeLog.Phase.EXECUTED;
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
            }
            return out;
        }
    }

    /**
     * Force a pre-commit timeout rollback (tests / explicit abort after reconcile
     * decided the host never committed).
     */
    public boolean forceTimeoutRollback(final String reason) {
        synchronized (lock) {
            if (status != Status.GUEST_APPLIED && status != Status.NEEDS_RECONCILE) {
                return false;
            }
            if (tradeId > 0L && tradeLog.isAtLeast(tradeId, CoopTradeLog.Phase.HOST_COMMITTED)) {
                return false;
            }
            cancelUnlocked(reason != null ? reason : "guest ack timeout — rolled back");
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
            // Trade log is durable across resets for idempotency / reconnect.
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
