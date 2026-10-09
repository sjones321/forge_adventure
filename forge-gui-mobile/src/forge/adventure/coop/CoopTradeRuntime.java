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
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent;
import forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
import forge.gamemodes.net.event.coop.CoopTradeRequestEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

import java.util.List;

/**
 * Ascendant TR1 player trading — forward-only escrow.
 *
 * <p>Netty handlers only {@link #postGl postRunnable} onto the GL thread. All
 * state transitions, bag mutations and character saves run there, so there are
 * no Netty-vs-GL races. Host mints SecureRandom trade ids; guest requests.
 * Escrow removes only own goods; deliver grants peer goods only after peer
 * escrowed. Never reverse received goods. Refund own escrow only when reconcile
 * shows the peer never escrowed. Trade log lives inside the character save.
 */
public final class CoopTradeRuntime implements CoopHooks.OverworldListener {
    private static final CoopTradeRuntime INSTANCE = new CoopTradeRuntime();

    private final CoopTradeLog tradeLog = new CoopTradeLog();
    private final CoopTradeState state = new CoopTradeState(
            new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                    CoopTradeWireLimits.DEFAULT_WINDOW_MS), tradeLog);
    private volatile boolean attached;

    private CoopTradeRuntime() {
        tradeLog.setListener(entry -> syncLogToPlayerAndSave());
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
        loadTradeLogFromPlayer();
        CoopSession.get().addOverworldListener(this);
        state.setBagLookup(this::bagForRole);
        attached = true;
    }

    public synchronized void detach() {
        if (!attached) {
            return;
        }
        try {
            CoopSession.get().removeOverworldListener(this);
        } catch (final Exception ignored) {
        }
        postGl(() -> {
            state.onTeardown();
            state.reset();
        });
        attached = false;
    }

    public void onSessionPeerDisconnected() {
        postGl(() -> {
            state.onDisconnect();
            if (state.getStatus() == CoopTradeState.Status.NEEDS_RECONCILE
                    || state.getStatus() == CoopTradeState.Status.ESCROWED
                    || state.getStatus() == CoopTradeState.Status.DELIVERED) {
                notifyHud("Trade pending… reconnect to finish");
                closeTradeUi(null);
            } else {
                closeTradeUi("Partner disconnected — trade cancelled");
                notifyHud("Trade cancelled (disconnect)");
            }
        });
    }

    public void onSessionEnded() {
        postGl(() -> {
            state.onTeardown();
            // Keep NEEDS_RECONCILE / in-flight log; do not abandon.
            if (state.getStatus() != CoopTradeState.Status.NEEDS_RECONCILE
                    && state.getStatus() != CoopTradeState.Status.ESCROWED
                    && state.getStatus() != CoopTradeState.Status.DELIVERED) {
                state.reset();
            }
            closeTradeUi(null);
        });
    }

    public void onSessionReadyReconcile() {
        if (!Config.ascendant() || !attached) {
            return;
        }
        postGl(() -> {
            loadTradeLogFromPlayer();
            final List<CoopTradeReconcileEvent> events = state.buildReconcileRequests(true);
            for (final CoopTradeReconcileEvent ev : events) {
                CoopSession.get().send(ev);
            }
        });
    }

    public void inviteTrade() {
        postGl(this::inviteTradeGl);
    }

    private void inviteTradeGl() {
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
        final String name = ap != null ? ap.getName() : "Player";
        final int timeout = Math.max(5, Config.instance().getConfigData().coopTradeInviteTimeoutSeconds);
        if (CoopHooks.isWorldAuthority()) {
            final CoopTradeInviteEvent invite = state.beginInvite(name, timeout);
            if (invite == null) {
                notifyHud("Cannot trade right now");
                return;
            }
            CoopSession.get().send(invite);
            notifyHud("Trade invite sent");
        } else {
            final CoopTradeRequestEvent req = state.beginRequest(name);
            if (req == null) {
                notifyHud("Cannot trade right now");
                return;
            }
            CoopSession.get().send(req);
            notifyHud("Trade request sent");
        }
    }

    public void acceptTradeInvite() {
        postGl(() -> {
            final CoopTradeResponseEvent resp = state.respondInvite(true);
            if (resp != null) {
                CoopSession.get().send(resp);
                openTradeUi();
            }
        });
    }

    public void declineTradeInvite() {
        postGl(() -> {
            final CoopTradeResponseEvent resp = state.respondInvite(false);
            if (resp != null) {
                CoopSession.get().send(resp);
                notifyHud("Declined trade");
            }
        });
    }

    public void updateLocalOffer(final CoopTradeOffer offer) {
        postGl(() -> {
            if (!state.isOpen()) {
                return;
            }
            final CoopTradeRole role = localRole();
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
        });
    }

    public void setLocalConfirmed(final boolean confirmed) {
        postGl(() -> {
            if (!state.isOpen()) {
                return;
            }
            final CoopTradeRole role = localRole();
            final CoopTradeConfirmEvent event = new CoopTradeConfirmEvent(
                    state.getTradeId(), role, confirmed,
                    state.getLocalOfferVersion(), state.getPeerOfferVersion());
            final CoopTradeState.ConfirmResult result = state.acceptConfirm(event);
            if (result == CoopTradeState.ConfirmResult.IGNORED) {
                notifyHud("Confirm rejected (stale offer?)");
                return;
            }
            if (result == CoopTradeState.ConfirmResult.CANCELLED) {
                final CoopTradeCancelEvent cancel = state.getLastCancel();
                if (cancel != null) {
                    CoopSession.get().send(cancel);
                }
                closeTradeUi("Trade cancelled");
                return;
            }
            CoopSession.get().send(event);
            if (result == CoopTradeState.ConfirmResult.BEGIN_ESCROW) {
                performEscrow();
            } else {
                refreshUi();
            }
        });
    }

    public void cancelTrade(final String reason) {
        postGl(() -> {
            if (!state.isCancelAllowed()) {
                notifyHud("Cannot cancel after confirm");
                return;
            }
            final CoopTradeCancelEvent cancel = state.cancel(reason != null ? reason : "cancelled");
            if (cancel == null) {
                notifyHud("Cannot cancel right now");
                return;
            }
            CoopSession.get().send(cancel);
            closeTradeUi("Trade cancelled");
        });
    }

    @Override
    public void onTradeRequest(final CoopTradeRequestEvent event) {
        postGl(() -> {
            if (event == null || !Config.ascendant() || !CoopHooks.isWorldAuthority()) {
                return;
            }
            final AdventurePlayer ap = Current.player();
            final String hostName = ap != null ? ap.getName() : "Host";
            final int timeout = Math.max(5,
                    Config.instance().getConfigData().coopTradeInviteTimeoutSeconds);
            final CoopTradeInviteEvent invite = state.acceptRequest(
                    event, hostName, timeout, System.currentTimeMillis());
            if (invite == null) {
                return;
            }
            CoopSession.get().send(invite);
            notifyHud(cap(event.getFromPlayer()) + " requested a trade");
        });
    }

    @Override
    public void onTradeInvite(final CoopTradeInviteEvent event) {
        postGl(() -> {
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
                try {
                    GameHUD.getInstance().showCoopTradeInviteDialog(event.getFromPlayer());
                } catch (final Exception ignored) {
                }
            }
        });
    }

    @Override
    public void onTradeResponse(final CoopTradeResponseEvent event) {
        postGl(() -> {
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
        });
    }

    @Override
    public void onTradeOffer(final CoopTradeOfferEvent event) {
        postGl(() -> {
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
                        closeTradeUi("Invalid offer — trade cancelled");
                    }
                }
                return;
            }
            if (CoopHooks.isWorldAuthority()) {
                CoopSession.get().send(accepted);
            }
            refreshUi();
        });
    }

    @Override
    public void onTradeConfirm(final CoopTradeConfirmEvent event) {
        postGl(() -> {
            if (event == null || !state.isOpen()) {
                return;
            }
            if (event.getFromRole() == localRole()) {
                return;
            }
            final CoopTradeState.ConfirmResult result = state.acceptConfirm(event);
            if (result == CoopTradeState.ConfirmResult.IGNORED) {
                return;
            }
            if (result == CoopTradeState.ConfirmResult.CANCELLED) {
                final CoopTradeCancelEvent cancel = state.getLastCancel();
                if (cancel != null) {
                    CoopSession.get().send(cancel);
                }
                closeTradeUi("Trade cancelled");
                return;
            }
            if (CoopHooks.isWorldAuthority()) {
                CoopSession.get().send(event);
            }
            if (result == CoopTradeState.ConfirmResult.BEGIN_ESCROW) {
                performEscrow();
            } else {
                refreshUi();
            }
        });
    }

    @Override
    public void onTradeCancel(final CoopTradeCancelEvent event) {
        postGl(() -> {
            if (event == null) {
                return;
            }
            if (!state.receiveCancel(event)) {
                return;
            }
            closeTradeUi("Trade cancelled: " + event.getReason());
        });
    }

    @Override
    public void onTradeEscrowed(final CoopTradeEscrowedEvent event) {
        postGl(() -> {
            if (event == null) {
                return;
            }
            if (!state.receivePeerEscrowed(event.getTradeId(), event.getFromRole())) {
                // Still may need to deliver if shouldDeliver flips.
            }
            if (state.shouldDeliver()) {
                performDeliver();
            } else {
                refreshUi();
            }
        });
    }

    @Override
    public void onTradeDelivered(final CoopTradeDeliveredEvent event) {
        postGl(() -> {
            if (event == null) {
                return;
            }
            if (!state.receivePeerDelivered(event.getTradeId(), event.getFromRole())) {
                return;
            }
            if (state.getStatus() == CoopTradeState.Status.COMPLETED
                    || (state.getStatus() == CoopTradeState.Status.DELIVERED
                    && state.isPeerDelivered())) {
                closeTradeUi("Trade complete");
                notifyHud("Trade complete");
            } else {
                refreshUi();
            }
        });
    }

    @Override
    public void onTradeReconcile(final CoopTradeReconcileEvent event) {
        postGl(() -> {
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
        });
    }

    private void performEscrow() {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            closeTradeUi("Trade failed");
            return;
        }
        final long id = state.getTradeId();
        if (tradeLog.hasEscrowed(id)) {
            final CoopTradeEscrowedEvent again = state.markEscrowed(System.currentTimeMillis());
            if (again != null) {
                CoopSession.get().send(again);
            }
            if (state.shouldDeliver()) {
                performDeliver();
            }
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeOffer own = state.getLocalOffer();
        final CoopTradeApply.Result result = CoopTradeApply.escrowIdempotent(
                id, tradeLog, bag, own, snap);
        if (!result.applied) {
            // Goods unchanged (snap restore). abortPreEscrow is allowed before ESCROWED.
            final CoopTradeCancelEvent cancel = state.abortPreEscrow(
                    "escrow failed: " + result.detail);
            notifyHud("Escrow failed — nothing moved");
            if (cancel != null) {
                CoopSession.get().send(cancel);
            }
            closeTradeUi("Trade cancelled");
            return;
        }
        final CoopTradeEscrowedEvent escrowed = state.markEscrowed(System.currentTimeMillis());
        if (escrowed != null) {
            CoopSession.get().send(escrowed);
        }
        // syncLogToPlayerAndSave via listener
        if (state.shouldDeliver()) {
            performDeliver();
        } else {
            refreshUi();
            notifyHud("Offer locked in escrow…");
        }
    }

    private void performDeliver() {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        final long id = state.getTradeId();
        if (tradeLog.hasDelivered(id)) {
            final CoopTradeDeliveredEvent again = state.markDelivered(System.currentTimeMillis());
            if (again != null) {
                CoopSession.get().send(again);
            }
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeOffer peer = state.getPeerOffer();
        final CoopTradeApply.Result result = CoopTradeApply.deliverIdempotent(
                id, tradeLog, bag, peer, snap);
        if (!result.applied) {
            notifyHud("Deliver failed — will retry on reconnect");
            return;
        }
        final CoopTradeDeliveredEvent delivered = state.markDelivered(System.currentTimeMillis());
        if (delivered != null) {
            CoopSession.get().send(delivered);
        }
        if (state.getStatus() == CoopTradeState.Status.COMPLETED || state.isPeerDelivered()) {
            closeTradeUi("Trade complete");
            notifyHud("Trade complete");
        } else {
            refreshUi();
            notifyHud("Goods received — waiting for partner");
        }
    }

    private void handleReconcileAction(final CoopTradeLog.ReconcileAction action, final long tradeId) {
        if (action == null || action == CoopTradeLog.ReconcileAction.NONE
                || action == CoopTradeLog.ReconcileAction.IGNORE_HOSTILE) {
            return;
        }
        switch (action) {
            case RESEND_ESCROWED:
                CoopSession.get().send(new CoopTradeEscrowedEvent(tradeId, localRole()));
                break;
            case RESEND_DELIVERED:
                CoopSession.get().send(new CoopTradeDeliveredEvent(tradeId, localRole()));
                break;
            case DELIVER:
                performDeliver();
                break;
            case REFUND:
                performRefund();
                break;
            case COMPLETE:
                closeTradeUi("Trade complete (reconciled)");
                notifyHud("Trade complete");
                break;
            default:
                break;
        }
    }

    private void performRefund() {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        final long id = state.getTradeId();
        final CoopTradeLog.Entry entry = tradeLog.get(id);
        if (entry == null || tradeLog.hasDelivered(id)) {
            return;
        }
        final CoopTradeOffer own = localRole() == CoopTradeRole.HOST
                ? entry.hostOffer : entry.guestOffer;
        final CoopTradeApply.Result result = CoopTradeApply.refundEscrow(
                new AdventurePlayerTradeBag(ap), own);
        if (!result.applied) {
            notifyHud("Refund failed");
            return;
        }
        state.markRefunded(System.currentTimeMillis());
        closeTradeUi("Trade refunded — partner never escrowed");
        notifyHud("Your escrow was refunded");
    }

    private void loadTradeLogFromPlayer() {
        try {
            final AdventurePlayer ap = Current.player();
            if (ap != null) {
                tradeLog.decode(ap.getTradeLogBlob());
            }
        } catch (final Exception ignored) {
        }
    }

    private void syncLogToPlayerAndSave() {
        try {
            final AdventurePlayer ap = Current.player();
            if (ap != null) {
                ap.setTradeLogBlob(tradeLog.encode());
                CoopCharacterStore.savePlayer(ap);
            }
        } catch (final Exception ignored) {
        }
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
        // Peer bag unknown locally — confirm-time gold checks use null-safe validator.
        return null;
    }

    private void openTradeUi() {
        try {
            Forge.switchScene(TradeScene.instance());
        } catch (final Exception e) {
            notifyHud("Could not open trade window");
        }
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
        if (TradeScene.isOpen()) {
            TradeScene.instance().refreshFromState();
        }
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
        try {
            GameHUD.getInstance().addNotification(CoopTradeWireLimits.clampText(msg));
        } catch (final Exception ignored) {
        }
    }

    /**
     * Netty → GL only. When Gdx is absent (headless tests), run inline.
     */
    static void postGl(final Runnable r) {
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
