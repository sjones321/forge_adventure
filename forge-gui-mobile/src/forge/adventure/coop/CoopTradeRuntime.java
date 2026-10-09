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
 * Ascendant TR1 player trading (review r4): host-assigned SecureRandom trade
 * ids, claim-before-GL apply, cancel disabled after Execute, no guest rollback
 * after ack except host abort / reconcile ABORTED, line-only rollback, slot-tied
 * trade log saved with every phase change.
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
        tradeLog.setListener(entry -> saveCharacterQuietly());
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
        bindTradeLogSlot();
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
        postGl(() -> {
            state.onTeardown();
            if (state.isGuestRollbackPermitted()) {
                rollbackGuestIfNeeded();
            }
            state.reset();
        });
        attached = false;
    }

    public void onSessionPeerDisconnected() {
        state.onDisconnect();
        cancelAckTimeout();
        postGl(() -> {
            // After guest ack: never roll back on disconnect.
            if (state.isGuestRollbackPermitted()) {
                rollbackGuestIfNeeded();
            }
            if (state.getStatus() == CoopTradeState.Status.NEEDS_RECONCILE) {
                notifyHud("Trade pending reconcile…");
                closeTradeUi(null);
            } else {
                closeTradeUi("Partner disconnected — trade cancelled");
                notifyHud("Trade cancelled (disconnect)");
            }
        });
    }

    /**
     * Session ended. Reset runs after any permitted GL rollback; after guest
     * ack, teardown does not roll back.
     */
    public void onSessionEnded() {
        cancelAckTimeout();
        postGl(() -> {
            state.onTeardown();
            if (state.isGuestRollbackPermitted()) {
                rollbackGuestIfNeeded();
            }
            state.reset();
            closeTradeUi(null);
        });
    }

    public void onSessionReadyReconcile() {
        if (!Config.ascendant() || !attached) {
            return;
        }
        bindTradeLogSlot();
        final List<CoopTradeReconcileEvent> events = state.buildReconcileRequests(true);
        for (final CoopTradeReconcileEvent ev : events) {
            CoopSession.get().send(ev);
        }
    }

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
        // Wire version: strictly greater than current local counter.
        final int nextVer = state.getLocalOfferVersion() + 1;
        final CoopTradeOfferEvent event = new CoopTradeOfferEvent(
                state.getTradeId(), role, offer, nextVer);
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
            CoopSession.get().send((CoopTradeExecuteEvent) result);
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
        if (!state.isCancelAllowed()) {
            notifyHud("Cannot cancel after Execute");
            return;
        }
        cancelAckTimeout();
        final CoopTradeCancelEvent cancel = state.cancel(reason != null ? reason : "cancelled");
        if (cancel == null) {
            notifyHud("Cannot cancel right now");
            return;
        }
        CoopSession.get().send(cancel);
        postGl(() -> {
            if (state.isGuestRollbackPermitted()) {
                rollbackGuestIfNeeded();
            }
            closeTradeUi("Trade cancelled");
        });
    }

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
                    postGl(() -> closeTradeUi("Invalid offer — trade cancelled"));
                }
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
            return;
        }
        if (result instanceof CoopTradeExecuteEvent) {
            CoopSession.get().send((CoopTradeExecuteEvent) result);
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
            if (state.isGuestRollbackPermitted()) {
                rollbackGuestIfNeeded();
            }
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
            refreshUi();
            return;
        }
        // Claim under the lock BEFORE queuing GL work.
        final CoopTradeExecuteEvent claimed = state.claimGuestApply(event.getTradeId());
        if (claimed == null) {
            return;
        }
        postGl(() -> guestApplyAndAck(claimed));
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
                    saveCharacterQuietly();
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
            final CoopTradeLog.Entry local = tradeLog.get(event.getTradeId());
            final CoopTradeLog.Phase phase = local != null ? local.phase : CoopTradeLog.Phase.NONE;
            CoopSession.get().send(new CoopTradeReconcileEvent(
                    event.getTradeId(), localRole(), phase, false));
        }
        final CoopTradeLog.ReconcileAction action = state.applyReconcile(event, now);
        handleReconcileAction(action, event.getTradeId());
    }

    private void guestApplyAndAck(final CoopTradeExecuteEvent exec) {
        if (!state.isGuestApplyClaimed(exec.getTradeId())) {
            // Claim released (disconnect/teardown) before this GL runnable — do nothing.
            return;
        }
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            final CoopTradeAckEvent fail = state.markGuestApplied(false, "no player",
                    System.currentTimeMillis());
            if (fail != null) {
                CoopSession.get().send(fail);
            }
            closeTradeUi("Trade failed");
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeOffer give = exec.getGuestOffer();
        final CoopTradeOffer recv = exec.getHostOffer();
        final long now = System.currentTimeMillis();
        if (!state.isGuestApplyClaimed(exec.getTradeId())) {
            return;
        }
        final CoopTradeApply.Result result = CoopTradeApply.applyLocalIdempotent(
                exec.getTradeId(), CoopTradeRole.GUEST, tradeLog, bag, give, recv, snap);
        if (!result.applied) {
            final CoopTradeAckEvent fail = state.markGuestApplied(false, result.detail, now);
            if (fail != null) {
                CoopSession.get().send(fail);
            }
            closeTradeUi("Trade failed — nothing changed");
            return;
        }
        final CoopTradeAckEvent ack = state.markGuestApplied(true, "", now);
        if (ack != null) {
            CoopSession.get().send(ack);
        }
        scheduleAckTimeout();
        refreshUi();
    }

    private void hostApplyAfterGuestAck(final CoopTradeAckEvent guestAck) {
        final AdventurePlayer ap = Current.player();
        final long claimId = guestAck != null ? guestAck.getTradeId() : state.getTradeId();
        final CoopTradeExecuteEvent exec = state.beginHostApply(claimId);
        if (ap == null || exec == null) {
            if (tradeLog.hasLocalApply(claimId, CoopTradeRole.HOST)) {
                CoopSession.get().send(new CoopTradeAckEvent(claimId, CoopTradeRole.HOST, true, "complete"));
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
        saveCharacterQuietly();
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
                    saveCharacterQuietly();
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
                postGl(() -> {
                    notifyHud("Trade pending reconcile…");
                    for (final CoopTradeReconcileEvent ev : state.buildReconcileRequests(true)) {
                        CoopSession.get().send(ev);
                    }
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
        if (!state.isGuestRollbackPermitted() || !state.hasGuestRollbackLines()) {
            return;
        }
        if (tradeLog.isAtLeast(state.getTradeId(), CoopTradeLog.Phase.HOST_COMMITTED)) {
            return;
        }
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        state.rollbackGuestApply(new AdventurePlayerTradeBag(ap));
        saveCharacterQuietly();
    }

    private void bindTradeLogSlot() {
        try {
            final AdventurePlayer ap = Current.player();
            final String name = ap != null ? ap.getName() : "player";
            final String slot = sanitize(name);
            tradeLog.bindSlot(slot);
            final Path dir = Paths.get(ForgeConstants.USER_ADVENTURE_DIR,
                    Config.instance().getPlane(), "characters");
            tradeLog.setPersistPath(dir.resolve(slot + ".tradelog"));
        } catch (final Exception ignored) {
            tradeLog.setPersistPath(null);
        }
    }

    private static void saveCharacterQuietly() {
        try {
            final AdventurePlayer ap = Current.player();
            if (ap != null) {
                CoopCharacterStore.savePlayer(ap);
            }
        } catch (final Exception ignored) {
        }
    }

    private static String sanitize(final String name) {
        if (name == null || name.isEmpty()) {
            return "player";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private CoopTradeRole localRole() {
        return CoopHooks.isWorldAuthority() ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
    }

    private CoopTradeBag bagForRole(final CoopTradeRole role) {
        if (role == null) {
            return null;
        }
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
