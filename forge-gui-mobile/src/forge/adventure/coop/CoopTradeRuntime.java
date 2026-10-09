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
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ascendant TR1 player trading with two-phase commit.
 *
 * <p>Guest applies first and acks; host applies only after a successful ack.
 * Failure or disconnect before the ack leaves both bags unchanged. If the guest
 * applied but the host complete ack never arrives, the guest rolls back after
 * {@code coopTradeAckTimeoutSeconds} (see {@link CoopTradeState#expireGuestAckIfNeeded}).
 *
 * <p>Peers are identified by {@link CoopTradeRole}. Offer changes bump a version;
 * confirms for a stale version are ignored. Invite goes through
 * {@link CoopInviteUiState} (never replaces exit-dungeon).
 */
public final class CoopTradeRuntime implements CoopHooks.OverworldListener {
    private static final CoopTradeRuntime INSTANCE = new CoopTradeRuntime();

    private final CoopTradeState state = new CoopTradeState(
            new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                    CoopTradeWireLimits.DEFAULT_WINDOW_MS));
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

    public synchronized void attach() {
        if (attached || !Config.ascendant()) {
            return;
        }
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
        state.reset();
        attached = false;
    }

    /** Peer left — cancel and roll back any guest apply. */
    public void onSessionPeerDisconnected() {
        final CoopTradeCancelEvent cancel = state.onDisconnect();
        if (cancel == null) {
            return;
        }
        cancelAckTimeout();
        postGl(() -> {
            rollbackGuestIfNeeded();
            closeTradeUi("Partner disconnected — trade cancelled");
            notifyHud("Trade cancelled (disconnect)");
        });
    }

    public void onSessionEnded() {
        cancelAckTimeout();
        postGl(this::rollbackGuestIfNeeded);
        state.reset();
        postGl(() -> closeTradeUi(null));
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
                state.getTradeId(), role, confirmed, state.getLocalOfferVersion());
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
                CoopSession.get().send(cancel);
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
            return; // stale version or rejected
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
        state.receiveCancel(event);
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
        // Guest applies first on the GL thread.
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

    // ---- Two-phase apply ----

    private void guestApplyAndAck(final CoopTradeExecuteEvent exec) {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            final CoopTradeAckEvent fail = state.markGuestApplied(false, "no player", null,
                    System.currentTimeMillis());
            if (fail != null) {
                CoopSession.get().send(fail);
            }
            CoopSession.get().send(state.cancel("no player"));
            closeTradeUi("Trade failed");
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeOffer give = exec.getGuestOffer();
        final CoopTradeOffer recv = exec.getHostOffer();
        final CoopTradeApply.Result result = CoopTradeApply.applyLocal(bag, give, recv, snap);
        if (!result.applied) {
            final CoopTradeAckEvent fail = state.markGuestApplied(false, result.detail, null,
                    System.currentTimeMillis());
            if (fail != null) {
                CoopSession.get().send(fail);
            }
            closeTradeUi("Trade failed — nothing changed");
            return;
        }
        final CoopTradeAckEvent ack = state.markGuestApplied(true, "", snap, System.currentTimeMillis());
        if (ack != null) {
            CoopSession.get().send(ack);
        }
        scheduleAckTimeout();
        refreshUi();
    }

    private void hostApplyAfterGuestAck(final CoopTradeAckEvent guestAck) {
        final AdventurePlayer ap = Current.player();
        final CoopTradeExecuteEvent exec = state.getPendingExecute();
        if (ap == null || exec == null) {
            final CoopTradeCancelEvent cancel = state.cancel("host apply missing state");
            CoopSession.get().send(cancel);
            closeTradeUi("Trade failed — nothing changed");
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeApply.Result result = CoopTradeApply.applyLocal(
                bag, exec.getHostOffer(), exec.getGuestOffer(), snap);
        if (!result.applied) {
            // Guest already applied — tell them to roll back via cancel.
            final CoopTradeCancelEvent cancel = state.cancel("host apply failed: " + result.detail);
            CoopSession.get().send(cancel);
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

    private void scheduleAckTimeout() {
        cancelAckTimeout();
        final int sec = Math.max(1, Config.instance().getConfigData().coopTradeAckTimeoutSeconds);
        final ScheduledExecutorService exec = timers;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        ackTimeoutFuture = exec.schedule(() -> {
            if (state.expireGuestAckIfNeeded(System.currentTimeMillis(), sec * 1000L)) {
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
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        state.rollbackGuestApply(new AdventurePlayerTradeBag(ap));
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
