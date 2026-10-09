package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeExecuteEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Headless TR1 trade state machine. Host-authoritative: validates offers,
 * clears both confirmations when either offer changes, and emits an execute
 * event only when both sides confirm. Disconnect / cancel leaves bags unchanged.
 *
 * <p>Process off the Netty loop; apply world/character changes on the GL thread
 * via the caller's {@code postRunnable}.
 */
public final class CoopTradeState {
    public enum Status {
        IDLE,
        INVITE_SENT,
        INVITE_RECEIVED,
        OPEN,
        /** Host has broadcast execute; waiting for local apply (optional). */
        EXECUTING,
        COMPLETED,
        CANCELLED
    }

    public enum Side { HOST, GUEST, LOCAL, PEER }

    private final AtomicLong seq = new AtomicLong(1L);
    private final CoopRateLimiter rateLimiter;

    private volatile Status status = Status.IDLE;
    private volatile long tradeId;
    private volatile long inviteId;
    private volatile String localName = "";
    private volatile String peerName = "";
    private volatile boolean localIsHost;
    private volatile CoopTradeOffer localOffer = CoopTradeOffer.empty();
    private volatile CoopTradeOffer peerOffer = CoopTradeOffer.empty();
    private volatile boolean localConfirmed;
    private volatile boolean peerConfirmed;
    private volatile String cancelReason = "";
    private volatile CoopTradeExecuteEvent pendingExecute;
    private volatile long inviteSinceMs;

    /** Optional live bags keyed by player name (host validation). */
    private volatile Function<String, CoopTradeBag> bagLookup = name -> null;

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

    public void setBagLookup(final Function<String, CoopTradeBag> lookup) {
        bagLookup = lookup != null ? lookup : name -> null;
    }

    public Status getStatus() {
        return status;
    }

    public long getTradeId() {
        return tradeId;
    }

    public long getInviteId() {
        return inviteId;
    }

    public String getLocalName() {
        return localName;
    }

    public String getPeerName() {
        return peerName;
    }

    public boolean isOpen() {
        return status == Status.OPEN;
    }

    public boolean isIdle() {
        return status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED;
    }

    public CoopTradeOffer getLocalOffer() {
        return localOffer;
    }

    public CoopTradeOffer getPeerOffer() {
        return peerOffer;
    }

    public boolean isLocalConfirmed() {
        return localConfirmed;
    }

    public boolean isPeerConfirmed() {
        return peerConfirmed;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public CoopTradeExecuteEvent getPendingExecute() {
        return pendingExecute;
    }

    /** Local player invites the peer. */
    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds,
                                            final long nowMs) {
        if (status == Status.OPEN || status == Status.INVITE_SENT || status == Status.EXECUTING) {
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
        resetOffers();
        status = Status.INVITE_SENT;
        inviteId = seq.getAndIncrement();
        tradeId = inviteId;
        localName = from;
        inviteSinceMs = nowMs;
        cancelReason = "";
        pendingExecute = null;
        final int timeout = Math.max(1, Math.min(timeoutSeconds, 120));
        return new CoopTradeInviteEvent(inviteId, from, timeout);
    }

    public CoopTradeInviteEvent beginInvite(final String fromPlayer, final int timeoutSeconds) {
        return beginInvite(fromPlayer, timeoutSeconds, System.currentTimeMillis());
    }

    /** Inbound invite. */
    public boolean receiveInvite(final CoopTradeInviteEvent invite, final long nowMs) {
        if (invite == null || status == Status.OPEN || status == Status.EXECUTING) {
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
        resetOffers();
        status = Status.INVITE_RECEIVED;
        inviteId = invite.getInviteId();
        tradeId = inviteId;
        peerName = from;
        inviteSinceMs = nowMs;
        cancelReason = "";
        pendingExecute = null;
        return true;
    }

    public boolean receiveInvite(final CoopTradeInviteEvent invite) {
        return receiveInvite(invite, System.currentTimeMillis());
    }

    /** Local accept/decline of an invite. */
    public CoopTradeResponseEvent respondInvite(final boolean accepted) {
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
        localConfirmed = false;
        peerConfirmed = false;
        return new CoopTradeResponseEvent(id, true);
    }

    /** Host/peer applied an accept response — open the window. */
    public boolean applyPeerResponse(final CoopTradeResponseEvent response, final String peer,
                                     final boolean weAreHost) {
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
        final String name = CoopTradeWireLimits.clampName(peer);
        if (status == Status.INVITE_SENT) {
            peerName = name;
        } else if (localName.isEmpty()) {
            localName = name;
        }
        localIsHost = weAreHost;
        status = Status.OPEN;
        localConfirmed = false;
        peerConfirmed = false;
        return true;
    }

    /**
     * Host path: accept a local or peer offer update. Returns null when rejected
     * (caller should not forward; inventory unchanged).
     */
    public CoopTradeOfferEvent acceptOffer(final CoopTradeOfferEvent event, final boolean fromLocal,
                                           final long nowMs) {
        if (event == null || status != Status.OPEN || event.getTradeId() != tradeId) {
            return null;
        }
        if (!rateLimiter.tryAcquire(nowMs)) {
            return null;
        }
        final String from = event.getFromPlayer();
        if (from == null || from.length() > CoopTradeWireLimits.MAX_NAME_LEN) {
            return null;
        }
        final CoopTradeBag bag = bagLookup.apply(from);
        final CoopTradeValidator.Result check = CoopTradeValidator.validate(event.getOffer(), bag);
        if (!check.ok()) {
            return null;
        }
        if (fromLocal) {
            localOffer = event.getOffer();
            localName = CoopTradeWireLimits.clampName(from);
        } else {
            peerOffer = event.getOffer();
            peerName = CoopTradeWireLimits.clampName(from);
        }
        // Changing an offer resets both confirmations.
        localConfirmed = false;
        peerConfirmed = false;
        return event;
    }

    public CoopTradeOfferEvent acceptOffer(final CoopTradeOfferEvent event, final boolean fromLocal) {
        return acceptOffer(event, fromLocal, System.currentTimeMillis());
    }

    /**
     * Apply a confirm. When this side is the host and both are confirmed, returns
     * a {@link CoopTradeExecuteEvent} to broadcast; otherwise null.
     */
    public Object acceptConfirm(final CoopTradeConfirmEvent event, final boolean fromLocal,
                                final boolean weAreHost, final long nowMs) {
        if (event == null || status != Status.OPEN || event.getTradeId() != tradeId) {
            return null;
        }
        if (!rateLimiter.tryAcquire(nowMs)) {
            return null;
        }
        final String from = event.getFromPlayer();
        if (from == null || from.length() > CoopTradeWireLimits.MAX_NAME_LEN) {
            return null;
        }
        if (fromLocal) {
            localConfirmed = event.isConfirmed();
        } else {
            peerConfirmed = event.isConfirmed();
        }
        if (!localConfirmed || !peerConfirmed) {
            return event;
        }
        // Re-validate both offers before commit.
        final CoopTradeBag localBag = bagLookup.apply(localName);
        final CoopTradeBag peerBag = bagLookup.apply(peerName);
        if (!CoopTradeValidator.validate(localOffer, localBag).ok()
                || !CoopTradeValidator.validate(peerOffer, peerBag).ok()) {
            return cancel("invalid offer at confirm");
        }
        if (!weAreHost) {
            return event;
        }
        final String hostPlayer = localIsHost ? localName : peerName;
        final String guestPlayer = localIsHost ? peerName : localName;
        final CoopTradeOffer hostOffer = localIsHost ? localOffer : peerOffer;
        final CoopTradeOffer guestOffer = localIsHost ? peerOffer : localOffer;
        final CoopTradeExecuteEvent exec = new CoopTradeExecuteEvent(
                tradeId, hostPlayer, guestPlayer, hostOffer, guestOffer);
        pendingExecute = exec;
        status = Status.EXECUTING;
        return exec;
    }

    public Object acceptConfirm(final CoopTradeConfirmEvent event, final boolean fromLocal,
                                final boolean weAreHost) {
        return acceptConfirm(event, fromLocal, weAreHost, System.currentTimeMillis());
    }

    /** Peer mirrored our confirm / we received execute. */
    public boolean receiveExecute(final CoopTradeExecuteEvent event) {
        if (event == null || (status != Status.OPEN && status != Status.EXECUTING)) {
            return false;
        }
        if (event.getTradeId() != tradeId) {
            return false;
        }
        pendingExecute = event;
        status = Status.EXECUTING;
        return true;
    }

    public void markCompleted() {
        status = Status.COMPLETED;
        localConfirmed = false;
        peerConfirmed = false;
    }

    public CoopTradeCancelEvent cancel(final String reason) {
        final long id = tradeId > 0L ? tradeId : inviteId;
        cancelReason = CoopTradeWireLimits.clampText(reason);
        status = Status.CANCELLED;
        localConfirmed = false;
        peerConfirmed = false;
        pendingExecute = null;
        return new CoopTradeCancelEvent(id, cancelReason);
    }

    public boolean receiveCancel(final CoopTradeCancelEvent event) {
        if (event == null) {
            return false;
        }
        if (tradeId > 0L && event.getTradeId() != tradeId && event.getTradeId() != inviteId) {
            return false;
        }
        cancelReason = CoopTradeWireLimits.clampText(event.getReason());
        status = Status.CANCELLED;
        localConfirmed = false;
        peerConfirmed = false;
        pendingExecute = null;
        return true;
    }

    /** Disconnect mid-trade — cancel cleanly with no partial swap. */
    public CoopTradeCancelEvent onDisconnect() {
        if (status == Status.IDLE || status == Status.COMPLETED || status == Status.CANCELLED) {
            return null;
        }
        return cancel("disconnect");
    }

    public boolean expireInviteIfNeeded(final long nowMs, final long timeoutMs) {
        if (status != Status.INVITE_SENT && status != Status.INVITE_RECEIVED) {
            return false;
        }
        if (timeoutMs <= 0L || nowMs - inviteSinceMs < timeoutMs) {
            return false;
        }
        cancel("invite expired");
        return true;
    }

    public void reset() {
        status = Status.IDLE;
        tradeId = 0L;
        inviteId = 0L;
        localName = "";
        peerName = "";
        localIsHost = false;
        resetOffers();
        cancelReason = "";
        pendingExecute = null;
        inviteSinceMs = 0L;
        rateLimiter.reset();
    }

    private void resetOffers() {
        localOffer = CoopTradeOffer.empty();
        peerOffer = CoopTradeOffer.empty();
        localConfirmed = false;
        peerConfirmed = false;
    }

    /**
     * Apply a pending execute against two bags atomically. Does not mutate if
     * either side would fail. Marks COMPLETED on success, CANCELLED on failure.
     */
    public CoopTradeApply.Result applyPending(final CoopTradeBag localBag, final CoopTradeBag peerBag,
                                              final boolean weAreHost) {
        final CoopTradeExecuteEvent exec = pendingExecute;
        if (exec == null || status != Status.EXECUTING) {
            return CoopTradeApply.Result.fail("no pending execute");
        }
        final CoopTradeOffer give = weAreHost ? exec.getHostOffer() : exec.getGuestOffer();
        final CoopTradeOffer recv = weAreHost ? exec.getGuestOffer() : exec.getHostOffer();
        // Atomic across both bags when both are available (host/tests).
        if (peerBag != null) {
            final CoopTradeApply.Result r = CoopTradeApply.applyAtomic(localBag, give, peerBag, recv);
            if (r.applied) {
                markCompleted();
            } else {
                cancel("apply failed: " + r.detail);
            }
            return r;
        }
        // Single local bag: remove what we give, grant what we receive (still atomic via snapshot).
        final CoopTradeBag.Snapshot snap = localBag.snapshot();
        final CoopTradeValidator.Result check = CoopTradeValidator.validate(give, localBag);
        if (!check.ok()) {
            cancel("local offer invalid");
            return CoopTradeApply.Result.fail(check.reason + ":" + check.detail);
        }
        try {
            if (give.getGold() > 0 && !localBag.takeGold(give.getGold())) {
                localBag.restore(snap);
                cancel("take gold");
                return CoopTradeApply.Result.fail("take gold");
            }
            for (final CoopTradeOffer.Line line : give.getMaterials()) {
                if (!localBag.takeMaterial(line.getId(), line.getCount())) {
                    localBag.restore(snap);
                    cancel("take material");
                    return CoopTradeApply.Result.fail("take material");
                }
            }
            for (final CoopTradeOffer.Line line : give.getItems()) {
                if (!localBag.takeItem(line.getId(), line.getCount())) {
                    localBag.restore(snap);
                    cancel("take item");
                    return CoopTradeApply.Result.fail("take item");
                }
            }
            for (final CoopTradeOffer.CardLine line : give.getCards()) {
                if (!localBag.takeCard(line.key(), line.getCount())) {
                    localBag.restore(snap);
                    cancel("take card");
                    return CoopTradeApply.Result.fail("take card");
                }
            }
            if (recv.getGold() > 0) {
                localBag.addGold(recv.getGold());
            }
            for (final CoopTradeOffer.Line line : recv.getMaterials()) {
                localBag.addMaterial(line.getId(), line.getCount());
            }
            for (final CoopTradeOffer.Line line : recv.getItems()) {
                localBag.addItem(line.getId(), line.getCount());
            }
            for (final CoopTradeOffer.CardLine line : recv.getCards()) {
                localBag.addCard(line.key(), line.getCount());
            }
            markCompleted();
            return CoopTradeApply.Result.ok();
        } catch (final RuntimeException ex) {
            localBag.restore(snap);
            cancel("exception");
            return CoopTradeApply.Result.fail(ex.getMessage());
        }
    }
}
