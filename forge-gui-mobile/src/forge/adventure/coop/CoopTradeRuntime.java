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
import forge.gamemodes.net.coop.CoopTradeState;
import forge.gamemodes.net.coop.CoopTradeWireLimits;
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeExecuteEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

/**
 * Ascendant TR1 player trading. Face-to-face in a co-op session only.
 * Host validates offers; both confirm; swap applies atomically on the GL thread.
 * Disconnect cancels cleanly with no partial swap. Invite goes through
 * {@link CoopInviteUiState} (never replaces exit-dungeon).
 */
public final class CoopTradeRuntime implements CoopHooks.OverworldListener {
    private static final CoopTradeRuntime INSTANCE = new CoopTradeRuntime();

    private final CoopTradeState state = new CoopTradeState(
            new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                    CoopTradeWireLimits.DEFAULT_WINDOW_MS));
    private volatile boolean attached;

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
        state.setBagLookup(this::bagFor);
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
        state.reset();
        attached = false;
    }

    /** Peer left the session — cancel any open trade with no partial swap. */
    public void onSessionPeerDisconnected() {
        final CoopTradeCancelEvent cancel = state.onDisconnect();
        if (cancel != null) {
            try {
                CoopSession.get().send(cancel);
            } catch (final Exception ignored) {
            }
            postGl(() -> {
                closeTradeUi("Partner disconnected — trade cancelled");
                notifyHud("Trade cancelled (disconnect)");
            });
        }
    }

    public void onSessionEnded() {
        state.reset();
        postGl(() -> closeTradeUi(null));
    }

    // ---- UI actions ----

    /** Invite the nearby party partner to trade. */
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
        final CoopTradeInviteEvent invite = state.beginInvite(
                ap != null ? ap.getName() : "Player", timeout);
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
        final AdventurePlayer ap = Current.player();
        final String name = ap != null ? ap.getName() : state.getLocalName();
        final CoopTradeOfferEvent event = new CoopTradeOfferEvent(state.getTradeId(), name, offer);
        final boolean host = CoopHooks.isWorldAuthority();
        // Always validate against the local bag before sending.
        state.setBagLookup(this::bagFor);
        final CoopTradeOfferEvent accepted = state.acceptOffer(event, true);
        if (accepted == null) {
            notifyHud("Offer rejected");
            return;
        }
        CoopSession.get().send(accepted);
        if (host) {
            // Host also keeps peer side; broadcast is the event itself.
        }
        postGl(() -> {
            if (TradeScene.isOpen()) {
                TradeScene.instance().refreshFromState();
            }
        });
    }

    public void setLocalConfirmed(final boolean confirmed) {
        if (!state.isOpen()) {
            return;
        }
        final AdventurePlayer ap = Current.player();
        final String name = ap != null ? ap.getName() : state.getLocalName();
        final CoopTradeConfirmEvent event = new CoopTradeConfirmEvent(state.getTradeId(), name, confirmed);
        final boolean host = CoopHooks.isWorldAuthority();
        final Object result = state.acceptConfirm(event, true, host);
        if (result == null) {
            notifyHud("Confirm rejected");
            return;
        }
        if (result instanceof CoopTradeExecuteEvent) {
            final CoopTradeExecuteEvent exec = (CoopTradeExecuteEvent) result;
            CoopSession.get().send(exec);
            applyExecuteOnGl(exec);
            return;
        }
        CoopSession.get().send(event);
        postGl(() -> {
            if (TradeScene.isOpen()) {
                TradeScene.instance().refreshFromState();
            }
        });
    }

    public void cancelTrade(final String reason) {
        final CoopTradeCancelEvent cancel = state.cancel(reason != null ? reason : "cancelled");
        CoopSession.get().send(cancel);
        postGl(() -> closeTradeUi("Trade cancelled"));
    }

    // ---- Wire handlers ----

    @Override
    public void onTradeInvite(final CoopTradeInviteEvent event) {
        if (event == null || !Config.ascendant()) {
            return;
        }
        if (!state.receiveInvite(event)) {
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
        final String peer = CoopSession.get().getPeerName();
        if (!state.applyPeerResponse(event, peer, CoopHooks.isWorldAuthority())) {
            return;
        }
        if (!event.isAccepted()) {
            notifyHud(cap(peer) + " declined the trade");
            return;
        }
        openTradeUi();
    }

    @Override
    public void onTradeOffer(final CoopTradeOfferEvent event) {
        if (event == null || !state.isOpen()) {
            return;
        }
        final boolean fromLocal = isLocalName(event.getFromPlayer());
        if (fromLocal) {
            return;
        }
        // Host validates against peer-attested ownership on the offer lines
        // (bag lookup may be null for the guest on the host process).
        final CoopTradeOfferEvent accepted = state.acceptOffer(event, false);
        if (accepted == null) {
            if (CoopHooks.isWorldAuthority()) {
                final CoopTradeCancelEvent cancel = state.cancel("invalid offer");
                CoopSession.get().send(cancel);
                postGl(() -> closeTradeUi("Invalid offer — trade cancelled"));
            }
            return;
        }
        // Mirror peer offer to the other side when we are host.
        if (CoopHooks.isWorldAuthority()) {
            CoopSession.get().send(accepted);
        }
        postGl(() -> {
            if (TradeScene.isOpen()) {
                TradeScene.instance().refreshFromState();
            }
        });
    }

    @Override
    public void onTradeConfirm(final CoopTradeConfirmEvent event) {
        if (event == null || !state.isOpen()) {
            return;
        }
        final boolean fromLocal = isLocalName(event.getFromPlayer());
        if (fromLocal) {
            return;
        }
        final boolean host = CoopHooks.isWorldAuthority();
        final Object result = state.acceptConfirm(event, false, host);
        if (result == null) {
            return;
        }
        if (result instanceof CoopTradeExecuteEvent) {
            final CoopTradeExecuteEvent exec = (CoopTradeExecuteEvent) result;
            CoopSession.get().send(exec);
            applyExecuteOnGl(exec);
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
        postGl(() -> {
            if (TradeScene.isOpen()) {
                TradeScene.instance().refreshFromState();
            }
        });
    }

    @Override
    public void onTradeCancel(final CoopTradeCancelEvent event) {
        if (event == null) {
            return;
        }
        state.receiveCancel(event);
        postGl(() -> closeTradeUi("Trade cancelled: " + event.getReason()));
    }

    @Override
    public void onTradeExecute(final CoopTradeExecuteEvent event) {
        if (event == null) {
            return;
        }
        if (!state.receiveExecute(event) && state.getStatus() != CoopTradeState.Status.EXECUTING) {
            return;
        }
        applyExecuteOnGl(event);
    }

    // ---- Apply / UI ----

    private void applyExecuteOnGl(final CoopTradeExecuteEvent exec) {
        postGl(() -> {
            final AdventurePlayer ap = Current.player();
            if (ap == null) {
                state.cancel("no player");
                closeTradeUi("Trade failed");
                return;
            }
            // Guest saves stay isolated — only mutate the local character.
            final CoopTradeBag local = new AdventurePlayerTradeBag(ap);
            final boolean weAreHost = CoopHooks.isWorldAuthority();
            state.receiveExecute(exec);
            final CoopTradeApply.Result result = state.applyPending(local, null, weAreHost);
            if (result.applied) {
                try {
                    CoopCharacterStore.savePlayer(ap);
                } catch (final Exception ignored) {
                }
                closeTradeUi("Trade complete");
                notifyHud("Trade complete");
            } else {
                final CoopTradeCancelEvent cancel = state.cancel("apply failed: " + result.detail);
                CoopSession.get().send(cancel);
                closeTradeUi("Trade failed — nothing changed");
            }
        });
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

    private CoopTradeBag bagFor(final String name) {
        final AdventurePlayer ap = Current.player();
        if (ap == null || name == null) {
            return null;
        }
        if (name.equalsIgnoreCase(ap.getName()) || name.equalsIgnoreCase(state.getLocalName())) {
            return new AdventurePlayerTradeBag(ap);
        }
        // Peer inventory is not on this process — ownership uses offer.available.
        return null;
    }

    private static boolean isLocalName(final String name) {
        if (name == null) {
            return false;
        }
        final AdventurePlayer ap = Current.player();
        return ap != null && name.equalsIgnoreCase(ap.getName());
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
