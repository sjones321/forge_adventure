package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.TradeScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.gamemodes.net.coop.CoopInviteUiState;
import forge.gamemodes.net.coop.CoopRateLimiter;
import forge.gamemodes.net.coop.CoopTradeApply;
import forge.gamemodes.net.coop.CoopTradeBag;
import forge.gamemodes.net.coop.CoopTradeLog;
import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.coop.CoopTradeState;
import forge.gamemodes.net.coop.CoopTradeWireLimits;
import forge.gamemodes.net.event.coop.CoopTradeAckEvent;
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeExecuteEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;
import forge.localinstance.properties.ForgeConstants;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ascendant TR1 player trading with two-phase commit, idempotent trade ids, and
 * reconnect reconcile via a persisted {@link CoopTradeLog}.
 *
 * <p>Guest applies first and acks; host applies only after a successful ack
 * (atomic {@link CoopTradeState#beginHostApply}). Failure or disconnect before
 * the host commit point leaves both bags unchanged. After host commit the guest
 * never rolls back on timeout — it reconciles. Overflow is included in rollback
 * snapshots. Confirms carry both offer versions. Peers are identified by
 * {@link CoopTradeRole}.
 */
public final class CoopTradeRuntime implements CoopHooks.OverworldListener {
    private static final CoopTradeRuntime INSTANCE = new CoopTradeRuntime();

    private final CoopTradeLog tradeLog = new CoopTradeLog();
    private final CoopTradeState state = new CoopTradeState(
            new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                    CoopTradeWireLimits.DEFAULT_WINDOW_MS), tradeLog);
    private volatile boolean attached;
    private volatile ScheduledExecutorService timers;
    private volatile ScheduledFuture<?> ackTimeoutFuture;

    private CoopTradeRuntime() {
    }

    public static CoopTradeRuntime get() {
        return INSTANCE;
    }

    public CoopTradeState getState() {
        return state;
    }

    public CoopTradeLog getTradeLog() {
        return tradeLog;
    }

    public synchronized void attach() {
        if (attached || !Config.ascendant()) {
            return;
        }
        bindTradeLogPath();
        tradeLog.load();
        CoopSession.get().addOverworldListener(this);
        state.setBagLookup(this::bagForRole);
        if (timers == null || timers.isShutdown()) {
            timers = Executors.newSingleThreadScheduledExecutor(r -> {
                final Thread t = new Thread(r, "CoopTrade-AckTimeout");
                t.setDaemon(true);
                return t;
            });
        }
        attached = true;
    }

    public synchronized void detach() {
        if (!attached) {
            return;
        }
        cancelAckTimeout();
        try {
            CoopSession.get().removeOverworldListener(this);
        } catch (final Exception ignored) {
        }
        // Reset only after any in-flight GL rollback would have run; detach is
        // session teardown — post GL reset so ordering matches onSessionEnded.
        postGl(() -> {
            rollbackGuestIfNeeded();
            state.reset();
        });
        attached = false;
    }

    /** Peer left — cancel and roll back any guest apply before host commit. */
    public void onSessionPeerDisconnected() {
        final CoopTradeCancelEvent cancel = state.onDisconnect();
        if (cancel == null) {
            // Host may have committed — keep apply; or idle.
            return;
        }
        cancelAckTimeout();
        postGl(() -> {
            rollbackGuestIfNeeded();
            closeTradeUi("Partner disconnected — trade cancelled");
            notifyHud("Trade cancelled (disconnect)");
        });
    }

    /**
     * Session ended. Reset runs <b>after</b> the GL-thread rollback finishes,
     * never before.
     */
    public void onSessionEnded() {
        cancelAckTimeout();
        postGl(() -> {
            rollbackGuestIfNeeded();
            state.reset();
            closeTradeUi(null);
        });
    }

    /** After (re)connect / session ready — exchange in-flight trade log phases. */
    public void onSessionReadyReconcile() {
        if (!Config.ascendant() || !attached) {
            return;
        }
        final List<CoopTradeReconcileEvent> events = state.buildReconcileRequests(true);
        for (final CoopTradeReconcileEvent ev : events) {
            CoopSession.get().send(ev);
        }
    }

    // ---- UI actions ----

    public void inviteTrade() {
        if (!Config.ascendant() || !CoopHooks.isOverworldReady()) {
            return;
        }
        if (!CoopOverworldRuntime.get().getParty().inParty()) {
            notifyHud("Join a party before trading");
            return;
        }
        if (!CoopOverworldRuntime.get().partnersNearby()) {
            notifyHud("Partner is too far away to trade");
            return;
        }
        if (MapStage.getInstance().isInMap()) {
            notifyHud("Trade on the overworld");
            return;
        }
        final AdventurePlayer ap = Current.player();
        final int timeout = Math.max(5, Config.instance().getConfigData().coopTradeInviteTimeoutSeconds);
        final boolean host = CoopHooks.isWorldAuthority();
        final CoopTradeInviteEvent invite = state.beginInvite(
                ap != null ? ap.getName() : "Player", timeout, host);
        if (invite == null) {
            notifyHud("Cannot trade right now");
            return;
        }
        CoopSession.get().send(invite);
        notifyHud("Trade invite sent");
    }

    public void acceptTradeInvite() {
        final CoopTradeResponseEvent resp = state.respondInvite(true);
        if (resp != null) {
            CoopSession.get().send(resp);
            openTradeUi();
        }
    }

    public void declineTradeInvite() {
        final CoopTradeResponseEvent resp = state.respondInvite(false);
        if (resp != null) {
            CoopSession.get().send(resp);
            notifyHud("Declined trade");
        }
    }

    public void updateLocalOffer(final CoopTradeOffer offer) {
        if (!state.isOpen()) {
            return;
        }
        final CoopTradeRole role = localRole();
        final CoopTradeOfferEvent event = new CoopTradeOfferEvent(
                state.getTradeId(), role, offer, 0);
        state.setBagLookup(this::bagForRole);
        final CoopTradeOfferEvent accepted = state.acceptOffer(event);
        if (accepted == null) {
            notifyHud("Offer rejected");
            return;
        }
        CoopSession.get().send(accepted);
        refreshUi();
    }

    public void setLocalConfirmed(final boolean confirmed) {
        if (!state.isOpen()) {
            return;
        }
        final CoopTradeRole role = localRole();
        final CoopTradeConfirmEvent event = new CoopTradeConfirmEvent(
                state.getTradeId(), role, confirmed,
                state.getLocalOfferVersion(), state.getPeerOfferVersion());
        final boolean host = CoopHooks.isWorldAuthority();
        final Object result = state.acceptConfirm(event, host);
        if (result == null) {
            notifyHud("Confirm rejected (stale offer?)");
            return;
        }
        if (result instanceof CoopTradeExecuteEvent) {
            final CoopTradeExecuteEvent exec = (CoopTradeExecuteEvent) result;
            CoopSession.get().send(exec);
            // Host waits for guest ack — does not apply yet.
            refreshUi();
            return;
        }
        if (result instanceof CoopTradeCancelEvent) {
            CoopSession.get().send((CoopTradeCancelEvent) result);
            postGl(() -> closeTradeUi("Trade cancelled"));
            return;
        }
        CoopSession.get().send(event);
        refreshUi();
    }

    public void cancelTrade(final String reason) {
        cancelAckTimeout();
        final CoopTradeCancelEvent cancel = state.cancel(reason != null ? reason : "cancelled");
        if (cancel == null) {
            // Host apply in progress — cancel rejected until apply finishes.
            notifyHud("Trade commit in progress");
            return;
        }
        CoopSession.get().send(cancel);
        postGl(() -> {
            rollbackGuestIfNeeded();
            closeTradeUi("Trade cancelled");
        });
    }

    // ---- Wire handlers ----

    @Override
    public void onTradeInvite(final CoopTradeInviteEvent event) {
        if (event == null || !Config.ascendant()) {
            return;
        }
        final boolean host = CoopHooks.isWorldAuthority();
        if (!state.receiveInvite(event, host)) {
            return;
        }
        final boolean forceQueue = isHudBusy();
        final CoopInviteUiState.Prompt shown = CoopOverworldRuntime.get().getInviteUi()
                .enqueue(CoopInviteUiState.PromptKind.TRADE, event.getInviteId(),
                        event.getFromPlayer(), "", forceQueue);
        notifyHud(cap(event.getFromPlayer()) + " wants to trade");
        if (shown != null) {
            postGl(() -> GameHUD.getInstance().showCoopTradeInviteDialog(event.getFromPlayer()));
        }
    }

    @Override
    public void onTradeResponse(final CoopTradeResponseEvent event) {
        if (event == null) {
            return;
        }
        final boolean host = CoopHooks.isWorldAuthority();
        if (!state.applyPeerResponse(event, host)) {
            return;
        }
        if (!event.isAccepted()) {
            notifyHud("Partner declined the trade");
            return;
        }
        openTradeUi();
    }

    @Override
    public void onTradeOffer(final CoopTradeOfferEvent event) {
        if (event == null || !state.isOpen()) {
            return;
        }
        if (event.getFromRole() == localRole()) {
            return;
        }
        final CoopTradeOfferEvent accepted = state.acceptOffer(event);
        if (accepted == null) {
            if (CoopHooks.isWorldAuthority()) {
                final CoopTradeCancelEvent cancel = state.cancel("invalid offer");
                if (cancel != null) {
                    CoopSession.get().send(cancel);
                }
                postGl(() -> closeTradeUi("Invalid offer — trade cancelled"));
            }
            return;
        }
        if (CoopHooks.isWorldAuthority()) {
            CoopSession.get().send(accepted);
        }
        refreshUi();
    }

    @Override
    public void onTradeConfirm(final CoopTradeConfirmEvent event) {
        if (event == null || !state.isOpen()) {
            return;
        }
        if (event.getFromRole() == localRole()) {
            return;
        }
        final boolean host = CoopHooks.isWorldAuthority();
        final Object result = state.acceptConfirm(event, host);
        if (result == null) {
            return; // stale versions or rejected
        }
        if (result instanceof CoopTradeExecuteEvent) {
            final CoopTradeExecuteEvent exec = (CoopTradeExecuteEvent) result;
            CoopSession.get().send(exec);
            refreshUi();
            return;
        }
        if (result instanceof CoopTradeCancelEvent) {
            CoopSession.get().send((CoopTradeCancelEvent) result);
            postGl(() -> closeTradeUi("Trade cancelled"));
            return;
        }
        if (host) {
            CoopSession.get().send(event);
        }
        refreshUi();
    }

    @Override
    public void onTradeCancel(final CoopTradeCancelEvent event) {
        if (event == null) {
            return;
        }
        cancelAckTimeout();
        if (!state.receiveCancel(event)) {
            return;
        }
        postGl(() -> {
            rollbackGuestIfNeeded();
            closeTradeUi("Trade cancelled: " + event.getReason());
        });
    }

    @Override
    public void onTradeExecute(final CoopTradeExecuteEvent event) {
        if (event == null) {
            return;
        }
        final boolean host = CoopHooks.isWorldAuthority();
        if (!state.receiveExecute(event, host)) {
            return;
        }
        if (host) {
            // Host waits for guest ack.
            refreshUi();
            return;
        }
        // Guest applies first on the GL thread (idempotent by trade id).
        postGl(() -> guestApplyAndAck(event));
    }

    @Override
    public void onTradeAck(final CoopTradeAckEvent event) {
        if (event == null) {
            return;
        }
        if (event.getFromRole() == CoopTradeRole.GUEST && CoopHooks.isWorldAuthority()) {
            final Object result = state.receiveGuestAck(event);
            if (result instanceof CoopTradeCancelEvent) {
                CoopSession.get().send((CoopTradeCancelEvent) result);
                postGl(() -> closeTradeUi("Guest apply failed — nothing changed"));
                return;
            }
            if (result instanceof CoopTradeAckEvent
                    && ((CoopTradeAckEvent) result).getFromRole() == CoopTradeRole.HOST) {
                // Idempotent re-complete.
                CoopSession.get().send((CoopTradeAckEvent) result);
                return;
            }
            if (!(result instanceof CoopTradeAckEvent)) {
                return;
            }
            postGl(() -> hostApplyAfterGuestAck(event));
            return;
        }
        if (event.getFromRole() == CoopTradeRole.HOST && event.isSuccess()) {
            cancelAckTimeout();
            if (state.receiveHostComplete(event)) {
                postGl(() -> {
                    try {
                        final AdventurePlayer ap = Current.player();
                        if (ap != null) {
                            CoopCharacterStore.savePlayer(ap);
                        }
                    } catch (final Exception ignored) {
                    }
                    closeTradeUi("Trade complete");
                    notifyHud("Trade complete");
                });
            }
        }
    }

    @Override
    public void onTradeReconcile(final CoopTradeReconcileEvent event) {
        if (event == null || !Config.ascendant()) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (event.isRequest()) {
            // Reply with our log phase for this trade id.
            final CoopTradeLog.Entry local = tradeLog.get(event.getTradeId());
            final CoopTradeLog.Phase phase = local != null ? local.phase : CoopTradeLog.Phase.NONE;
            CoopSession.get().send(new CoopTradeReconcileEvent(
                    event.getTradeId(), localRole(), phase, false));
        }
        final CoopTradeLog.ReconcileAction action = state.applyReconcile(event, now);
        handleReconcileAction(action, event.getTradeId());
    }

    // ---- Two-phase apply ----

    private void guestApplyAndAck(final CoopTradeExecuteEvent exec) {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            final CoopTradeAckEvent fail = state.markGuestApplied(false, "no player", null,
                    System.currentTimeMillis());
            if (fail != null) {
                CoopSession.get().send(fail);
            }
            final CoopTradeCancelEvent cancel = state.cancel("no player");
            if (cancel != null) {
                CoopSession.get().send(cancel);
            }
            closeTradeUi("Trade failed");
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeOffer give = exec.getGuestOffer();
        final CoopTradeOffer recv = exec.getHostOffer();
        final long now = System.currentTimeMillis();
        final CoopTradeApply.Result result = CoopTradeApply.applyLocalIdempotent(
                exec.getTradeId(), CoopTradeRole.GUEST, tradeLog, bag, give, recv, snap);
        if (!result.applied) {
            final CoopTradeAckEvent fail = state.markGuestApplied(false, result.detail, null, now);
            if (fail != null) {
                CoopSession.get().send(fail);
            }
            closeTradeUi("Trade failed — nothing changed");
            return;
        }
        final CoopTradeAckEvent ack = state.markGuestApplied(true, "", snap, now);
        if (ack != null) {
            CoopSession.get().send(ack);
        }
        scheduleAckTimeout();
        refreshUi();
    }

    private void hostApplyAfterGuestAck(final CoopTradeAckEvent guestAck) {
        final AdventurePlayer ap = Current.player();
        // Atomic claim — cancel cannot interleave between claim and complete.
        final CoopTradeExecuteEvent exec = state.beginHostApply();
        if (ap == null || exec == null) {
            if (tradeLog.hasLocalApply(
                    guestAck != null ? guestAck.getTradeId() : state.getTradeId(),
                    CoopTradeRole.HOST)) {
                final CoopTradeAckEvent complete = new CoopTradeAckEvent(
                        guestAck != null ? guestAck.getTradeId() : state.getTradeId(),
                        CoopTradeRole.HOST, true, "complete");
                CoopSession.get().send(complete);
                closeTradeUi("Trade complete");
                return;
            }
            final CoopTradeCancelEvent cancel = state.abortHostApply("host apply missing state");
            if (cancel != null) {
                CoopSession.get().send(cancel);
            }
            closeTradeUi("Trade failed — nothing changed");
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeApply.Result result = CoopTradeApply.applyLocalIdempotent(
                exec.getTradeId(), CoopTradeRole.HOST, tradeLog, bag,
                exec.getHostOffer(), exec.getGuestOffer(), snap);
        if (!result.applied) {
            // Commit point not reached — guest may roll back.
            final CoopTradeCancelEvent cancel = state.abortHostApply(
                    "host apply failed: " + result.detail);
            if (cancel != null) {
                CoopSession.get().send(cancel);
            }
            closeTradeUi("Trade failed — guest will roll back");
            return;
        }
        final CoopTradeAckEvent complete = state.markHostCompleted();
        if (complete != null) {
            CoopSession.get().send(complete);
        }
        try {
            CoopCharacterStore.savePlayer(ap);
        } catch (final Exception ignored) {
        }
        closeTradeUi("Trade complete");
        notifyHud("Trade complete");
    }

    private void handleReconcileAction(final CoopTradeLog.ReconcileAction action, final long tradeId) {
        if (action == null || action == CoopTradeLog.ReconcileAction.NONE) {
            return;
        }
        switch (action) {
            case RESEND_GUEST_ACK:
                CoopSession.get().send(new CoopTradeAckEvent(tradeId, CoopTradeRole.GUEST, true, "reconcile"));
                break;
            case RESEND_HOST_COMPLETE:
                CoopSession.get().send(new CoopTradeAckEvent(tradeId, CoopTradeRole.HOST, true, "complete"));
                break;
            case COMPLETE_GUEST:
                cancelAckTimeout();
                postGl(() -> {
                    // Keep applied bags; discard rollback snap (already done in state).
                    try {
                        final AdventurePlayer ap = Current.player();
                        if (ap != null) {
                            CoopCharacterStore.savePlayer(ap);
                        }
                    } catch (final Exception ignored) {
                    }
                    closeTradeUi("Trade complete (reconciled)");
                    notifyHud("Trade complete");
                });
                break;
            case ROLLBACK_GUEST:
                cancelAckTimeout();
                postGl(() -> {
                    rollbackGuestIfNeeded();
                    closeTradeUi("Trade cancelled (reconciled)");
                    notifyHud("Trade rolled back");
                });
                break;
            case APPLY_HOST:
                if (CoopHooks.isWorldAuthority()) {
                    postGl(() -> hostApplyAfterGuestAck(
                            new CoopTradeAckEvent(tradeId, CoopTradeRole.GUEST, true, "reconcile")));
                }
                break;
            case ABORT:
                postGl(() -> closeTradeUi("Trade aborted"));
                break;
            default:
                break;
        }
    }

    private void scheduleAckTimeout() {
        cancelAckTimeout();
        final int sec = Math.max(1, Config.instance().getConfigData().coopTradeAckTimeoutSeconds);
        final ScheduledExecutorService exec = timers;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        ackTimeoutFuture = exec.schedule(() -> {
            final CoopTradeState.TimeoutOutcome outcome =
                    state.expireGuestAckIfNeeded(System.currentTimeMillis(), sec * 1000L);
            if (outcome == CoopTradeState.TimeoutOutcome.ALREADY_COMPLETE) {
                postGl(() -> {
                    closeTradeUi("Trade complete");
                    notifyHud("Trade complete");
                });
            } else if (outcome == CoopTradeState.TimeoutOutcome.RECONCILE) {
                // Do NOT roll back — host may have committed; advertise log phase.
                postGl(() -> {
                    notifyHud("Trade pending reconcile…");
                    for (final CoopTradeReconcileEvent ev : state.buildReconcileRequests(true)) {
                        CoopSession.get().send(ev);
                    }
                });
            } else if (outcome == CoopTradeState.TimeoutOutcome.ROLLBACK) {
                postGl(() -> {
                    rollbackGuestIfNeeded();
                    closeTradeUi("Trade timed out — rolled back");
                    notifyHud("Trade timed out — rolled back");
                });
            }
        }, sec, TimeUnit.SECONDS);
    }

    private void cancelAckTimeout() {
        final ScheduledFuture<?> f = ackTimeoutFuture;
        ackTimeoutFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    private void rollbackGuestIfNeeded() {
        if (!state.hasGuestRollbackSnap()) {
            return;
        }
        // Guard: never roll back after host commit.
        if (tradeLog.isAtLeast(state.getTradeId(), CoopTradeLog.Phase.HOST_COMMITTED)) {
            return;
        }
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        state.rollbackGuestApply(new AdventurePlayerTradeBag(ap));
    }

    private void bindTradeLogPath() {
        try {
            final AdventurePlayer ap = Current.player();
            final String name = ap != null ? ap.getName() : "player";
            final Path dir = Paths.get(ForgeConstants.USER_ADVENTURE_DIR,
                    Config.instance().getPlane(), "characters");
            tradeLog.setPersistPath(dir.resolve(sanitize(name) + ".tradelog"));
        } catch (final Exception ignored) {
            tradeLog.setPersistPath(null);
        }
    }

    private static String sanitize(final String name) {
        if (name == null || name.isEmpty()) {
            return "player";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ---- helpers ----

    private CoopTradeRole localRole() {
        return CoopHooks.isWorldAuthority() ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
    }

    private CoopTradeBag bagForRole(final CoopTradeRole role) {
        if (role == null) {
            return null;
        }
        // Only the local character bag is available on this process.
        if (role == localRole()) {
            final AdventurePlayer ap = Current.player();
            return ap != null ? new AdventurePlayerTradeBag(ap) : null;
        }
        return null;
    }

    private void openTradeUi() {
        postGl(() -> {
            try {
                Forge.switchScene(TradeScene.instance());
            } catch (final Exception e) {
                notifyHud("Could not open trade window");
            }
        });
    }

    private void closeTradeUi(final String message) {
        if (message != null && !message.isEmpty()) {
            notifyHud(message);
        }
        if (TradeScene.isOpen()) {
            try {
                Forge.switchScene(GameScene.instance());
            } catch (final Exception ignored) {
            }
        }
    }

    private void refreshUi() {
        postGl(() -> {
            if (TradeScene.isOpen()) {
                TradeScene.instance().refreshFromState();
            }
        });
    }

    private static boolean isHudBusy() {
        try {
            return GameHUD.getInstance().isDialogOnlyInput();
        } catch (final Exception e) {
            return false;
        }
    }

    private static String cap(final String name) {
        if (name == null || name.isEmpty()) {
            return "Partner";
        }
        return CoopTradeWireLimits.clampName(name);
    }

    private static void notifyHud(final String msg) {
        postGl(() -> {
            try {
                GameHUD.getInstance().addNotification(CoopTradeWireLimits.clampText(msg));
            } catch (final Exception ignored) {
            }
        });
    }

    private static void postGl(final Runnable r) {
        if (r == null) {
            return;
        }
        try {
            if (Gdx.app != null) {
                Gdx.app.postRunnable(r);
                return;
            }
        } catch (final Exception ignored) {
        }
        r.run();
    }
}
