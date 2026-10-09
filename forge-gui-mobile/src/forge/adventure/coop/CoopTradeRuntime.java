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
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopTradeRequestEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Ascendant TR1 player trading — forward-only escrow.
 *
 * <p>Netty handlers only {@link #postGl postRunnable} onto the GL thread. All
 * state transitions, bag mutations and saves run there. Host mints SecureRandom
 * trade ids; guest requests. Host persists into the world save; guest into the
 * co-op {@code .chr}. Trade-log entries store local role + peer character id so
 * restart/reconcile never delivers the wrong side's offer.
 *
 * <p>Inherent two-party risk: a hostile peer claiming {@code NONE} can obtain a
 * refund of our escrow — documented in the PR.
 */
public final class CoopTradeRuntime implements CoopHooks.OverworldListener {
    private static final CoopTradeRuntime INSTANCE = new CoopTradeRuntime();

    /** Test hook: when set, replaces {@code Gdx.app.postRunnable}. */
    private static volatile Consumer<Runnable> glPoster;

    private final CoopTradeLog tradeLog = new CoopTradeLog();
    private final CoopTradeState state = new CoopTradeState(
            new CoopRateLimiter(CoopTradeWireLimits.DEFAULT_MAX_PER_WINDOW,
                    CoopTradeWireLimits.DEFAULT_WINDOW_MS), tradeLog);
    private volatile boolean attached;

    /** Per-instance test hooks (production leaves these null). */
    private volatile Consumer<NetEvent> sendOverride;
    private volatile Supplier<AdventurePlayer> playerOverride;
    private volatile Supplier<Boolean> hostOverride;
    private volatile Supplier<String> peerIdOverride;
    private volatile boolean skipSessionListener;

    CoopTradeRuntime() {
        tradeLog.setListener(entry -> syncLogToPlayerAndSave());
    }

    /** Fresh runtime for dual-peer E2E (same package). */
    static CoopTradeRuntime createForTest() {
        return new CoopTradeRuntime();
    }

    public static void setGlPoster(final Consumer<Runnable> poster) {
        glPoster = poster;
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

    void setSendOverride(final Consumer<NetEvent> send) {
        sendOverride = send;
    }

    void setPlayerOverride(final Supplier<AdventurePlayer> player) {
        playerOverride = player;
    }

    void setHostOverride(final Supplier<Boolean> host) {
        hostOverride = host;
    }

    void setPeerIdOverride(final Supplier<String> peerId) {
        peerIdOverride = peerId;
    }

    void setSkipSessionListener(final boolean skip) {
        skipSessionListener = skip;
    }

    /** Attach on the GL thread (load log, recover pending, register listener). */
    public synchronized void attach() {
        if (attached || !Config.ascendant()) {
            return;
        }
        attached = true;
        postGl(this::attachGl);
    }

    private void attachGl() {
        loadTradeLogFromPlayer();
        state.setBagLookup(this::bagForRole);
        state.recoverPendingFromLog();
        if (!skipSessionListener) {
            try {
                CoopSession.get().addOverworldListener(this);
            } catch (final Exception ignored) {
            }
        }
    }

    /** Force attach for tests (GL poster already installed). */
    void attachForTest() {
        if (attached) {
            return;
        }
        attached = true;
        attachGl();
    }

    public synchronized void detach() {
        if (!attached) {
            return;
        }
        if (!skipSessionListener) {
            try {
                CoopSession.get().removeOverworldListener(this);
            } catch (final Exception ignored) {
            }
        }
        postGl(() -> {
            state.onTeardown();
            // Keep pending escrows / NEEDS_RECONCILE across detach.
            state.resetActiveIfIdle();
            refreshPendingHud();
        });
        attached = false;
    }

    public void onSessionPeerDisconnected() {
        postGl(() -> {
            state.onDisconnect();
            // Disconnect save on GL — durable commit of pending escrow state.
            try {
                syncLogToPlayerAndSave();
            } catch (final RuntimeException ignored) {
            }
            if (state.getStatus() == CoopTradeState.Status.NEEDS_RECONCILE
                    || state.getStatus() == CoopTradeState.Status.ESCROWED
                    || state.getStatus() == CoopTradeState.Status.DELIVERED
                    || state.getStatus() == CoopTradeState.Status.DELIVER_BLOCKED
                    || state.hasPendingEscrows()) {
                notifyHud("Trade pending… reconnect to finish");
                closeTradeUi(null);
                refreshPendingHud();
            } else {
                closeTradeUi("Partner disconnected — trade cancelled");
                notifyHud("Trade cancelled (disconnect)");
            }
        });
    }

    public void onSessionEnded() {
        postGl(() -> {
            state.onTeardown();
            // Keep NEEDS_RECONCILE / in-flight log; do not abandon or wipe role.
            state.resetActiveIfIdle();
            closeTradeUi(null);
            refreshPendingHud();
        });
    }

    public void onSessionReadyReconcile() {
        if (!Config.ascendant() || !attached) {
            return;
        }
        postGl(() -> {
            loadTradeLogFromPlayer();
            state.recoverPendingFromLog();
            final String peer = connectedPeerId();
            final List<CoopTradeReconcileEvent> events =
                    state.buildReconcileRequests(true, peer);
            for (final CoopTradeReconcileEvent ev : events) {
                send(ev);
            }
            refreshPendingHud();
        });
    }

    /** Public retry after DELIVER_BLOCKED (player freed space). */
    public void retryDeliver() {
        postGl(() -> {
            if (state.shouldDeliver() || state.isDeliverBlocked()) {
                performDeliver();
            }
        });
    }

    /** Pending escrow lines for Status / party HUD. */
    public List<CoopTradeLog.Entry> pendingEscrows() {
        return tradeLog.snapshotInFlight();
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
        final AdventurePlayer ap = localPlayer();
        final String name = ap != null ? ap.getName() : "Player";
        final String peer = connectedPeerId();
        if (!peer.isEmpty() && state.hasPendingWithPeer(peer)) {
            notifyHud("Finish pending trade with " + cap(peer) + " first");
            return;
        }
        final int timeout = Math.max(5, Config.instance().getConfigData().coopTradeInviteTimeoutSeconds);
        if (isHost()) {
            final CoopTradeInviteEvent invite = state.beginInvite(name, timeout,
                    System.currentTimeMillis(), peer);
            if (invite == null) {
                notifyHud("Cannot trade right now");
                return;
            }
            send(invite);
            notifyHud("Trade invite sent");
        } else {
            final CoopTradeRequestEvent req = state.beginRequest(name);
            if (req == null) {
                notifyHud("Cannot trade right now");
                return;
            }
            send(req);
            notifyHud("Trade request sent");
        }
    }

    public void acceptTradeInvite() {
        postGl(() -> {
            final CoopTradeResponseEvent resp = state.respondInvite(true);
            if (resp != null) {
                send(resp);
                openTradeUi();
            }
        });
    }

    public void declineTradeInvite() {
        postGl(() -> {
            final CoopTradeResponseEvent resp = state.respondInvite(false);
            if (resp != null) {
                send(resp);
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
            send(accepted);
            refreshUi();
        });
    }

    public void setLocalConfirmed(final boolean confirmed) {
        postGl(() -> {
            if (!state.isOpen() && !confirmed) {
                // Allow unconfirm only while OPEN; after escrow ignore.
                return;
            }
            if (!state.isOpen()) {
                return;
            }
            final CoopTradeRole role = localRole();
            final CoopTradeConfirmEvent event = new CoopTradeConfirmEvent(
                    state.getTradeId(), role, confirmed,
                    state.getLocalOfferVersion(), state.getPeerOfferVersion());
            final CoopTradeState.ConfirmResult result = state.acceptConfirm(event);
            if (result == CoopTradeState.ConfirmResult.IGNORED) {
                if (confirmed) {
                    notifyHud("Confirm rejected (stale offer?)");
                }
                return;
            }
            if (result == CoopTradeState.ConfirmResult.CANCELLED) {
                final CoopTradeCancelEvent cancel = state.getLastCancel();
                if (cancel != null) {
                    send(cancel);
                }
                closeTradeUi("Trade cancelled");
                return;
            }
            send(event);
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
            send(cancel);
            closeTradeUi("Trade cancelled");
        });
    }

    @Override
    public void onTradeRequest(final CoopTradeRequestEvent event) {
        postGl(() -> {
            if (event == null || !Config.ascendant() || !isHost()) {
                return;
            }
            final AdventurePlayer ap = localPlayer();
            final String hostName = ap != null ? ap.getName() : "Host";
            final int timeout = Math.max(5,
                    Config.instance().getConfigData().coopTradeInviteTimeoutSeconds);
            final CoopTradeInviteEvent invite = state.acceptRequest(
                    event, hostName, timeout, System.currentTimeMillis());
            if (invite == null) {
                if (state.hasPendingWithPeer(CoopTradeWireLimits.clampName(event.getFromPlayer()))) {
                    notifyHud("Pending trade with " + cap(event.getFromPlayer()));
                }
                return;
            }
            send(invite);
            notifyHud(cap(event.getFromPlayer()) + " requested a trade");
        });
    }

    @Override
    public void onTradeInvite(final CoopTradeInviteEvent event) {
        postGl(() -> {
            if (event == null || !Config.ascendant()) {
                return;
            }
            // Host rejects guest-chosen invite ids (receiveInvite enforces).
            if (!state.receiveInvite(event, isHost())) {
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
            if (!state.applyPeerResponse(event, isHost())) {
                return;
            }
            if (!event.isAccepted()) {
                notifyHud("Partner declined the trade");
                return;
            }
            // Host: bind peer id if still empty (direct invite path).
            if (isHost() && state.getPeerCharacterId().isEmpty()) {
                state.setPeerCharacterId(connectedPeerId());
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
                if (isHost()) {
                    final CoopTradeCancelEvent cancel = state.cancel("invalid offer");
                    if (cancel != null) {
                        send(cancel);
                        closeTradeUi("Invalid offer — trade cancelled");
                    }
                }
                return;
            }
            if (isHost()) {
                send(accepted);
            }
            refreshUi();
        });
    }

    @Override
    public void onTradeConfirm(final CoopTradeConfirmEvent event) {
        postGl(() -> {
            if (event == null) {
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
                    send(cancel);
                }
                closeTradeUi("Trade cancelled");
                return;
            }
            if (isHost()) {
                send(event);
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
            state.receivePeerEscrowed(event.getTradeId(), event.getFromRole());
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
                refreshPendingHud();
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
            final String peer = connectedPeerId();
            if (event.isRequest()) {
                final CoopTradeLog.Entry local = tradeLog.get(event.getTradeId());
                // H1: only answer for entries bound to this peer.
                if (local != null && !local.peerCharacterId.isEmpty()
                        && !peer.isEmpty() && !local.matchesPeer(peer)) {
                    return;
                }
                final CoopTradeLog.Phase phase = local != null ? local.phase : CoopTradeLog.Phase.NONE;
                final CoopTradeRole role = local != null && local.localRole != null
                        ? local.localRole : localRole();
                send(new CoopTradeReconcileEvent(event.getTradeId(), role, phase, false));
            }
            final CoopTradeLog.ReconcileAction action = state.applyReconcile(event, now, peer);
            handleReconcileAction(action, event.getTradeId());
        });
    }

    private void performEscrow() {
        final AdventurePlayer ap = localPlayer();
        if (ap == null) {
            closeTradeUi("Trade failed");
            return;
        }
        if (state.getPeerCharacterId().isEmpty()) {
            state.setPeerCharacterId(connectedPeerId());
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final boolean ok = CoopTradeGlOps.performEscrow(state, tradeLog, bag, this::send);
        if (!ok && !tradeLog.hasEscrowed(state.getTradeId())) {
            final CoopTradeCancelEvent cancel = state.abortPreEscrow("escrow failed");
            notifyHud("Escrow failed — nothing moved");
            if (cancel != null) {
                send(cancel);
            }
            closeTradeUi("Trade cancelled");
            return;
        }
        if (state.getStatus() == CoopTradeState.Status.COMPLETED
                || (state.getStatus() == CoopTradeState.Status.DELIVERED && state.isPeerDelivered())) {
            closeTradeUi("Trade complete");
            notifyHud("Trade complete");
        } else if (state.isDeliverBlocked()) {
            notifyHud("Cannot receive goods: " + state.getDeliverBlockedReason()
                    + " — free space, then retry");
            refreshUi();
            refreshPendingHud();
        } else if (tradeLog.hasDelivered(state.getTradeId())) {
            refreshUi();
            notifyHud("Goods received — waiting for partner");
        } else {
            refreshUi();
            notifyHud("Offer locked in escrow…");
            refreshPendingHud();
        }
    }

    private void performDeliver() {
        final AdventurePlayer ap = localPlayer();
        if (ap == null) {
            return;
        }
        final CoopTradeBag bag = new AdventurePlayerTradeBag(ap);
        final boolean ok = CoopTradeGlOps.performDeliver(state, tradeLog, bag, this::send);
        if (!ok) {
            final String reason = state.getDeliverBlockedReason();
            notifyHud("Delivery blocked"
                    + (reason.isEmpty() ? "" : ": " + reason)
                    + " — free space, then retry");
            refreshUi();
            refreshPendingHud();
            return;
        }
        if (state.getStatus() == CoopTradeState.Status.COMPLETED || state.isPeerDelivered()) {
            closeTradeUi("Trade complete");
            notifyHud("Trade complete");
            refreshPendingHud();
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
                send(new CoopTradeEscrowedEvent(tradeId, localRole()));
                break;
            case RESEND_DELIVERED:
                send(new CoopTradeDeliveredEvent(tradeId, localRole()));
                break;
            case DELIVER:
                performDeliver();
                break;
            case REFUND: {
                final AdventurePlayer ap = localPlayer();
                if (ap != null) {
                    CoopTradeGlOps.performRefund(state, tradeLog, new AdventurePlayerTradeBag(ap));
                    closeTradeUi("Trade refunded — partner never escrowed");
                    notifyHud("Your escrow was refunded");
                    refreshPendingHud();
                }
                break;
            }
            case COMPLETE:
                closeTradeUi("Trade complete (reconciled)");
                notifyHud("Trade complete");
                refreshPendingHud();
                break;
            default:
                break;
        }
    }

    private void loadTradeLogFromPlayer() {
        CoopTradeGlOps.loadLogFromPlayer(localPlayer(), tradeLog);
    }

    private void syncLogToPlayerAndSave() {
        CoopTradeGlOps.syncLogAndSave(localPlayer(), tradeLog);
    }

    private void send(final NetEvent event) {
        if (event == null) {
            return;
        }
        final Consumer<NetEvent> over = sendOverride;
        if (over != null) {
            over.accept(event);
            return;
        }
        CoopSession.get().send(event);
    }

    private AdventurePlayer localPlayer() {
        final Supplier<AdventurePlayer> over = playerOverride;
        if (over != null) {
            return over.get();
        }
        return Current.player();
    }

    private boolean isHost() {
        final Supplier<Boolean> over = hostOverride;
        if (over != null) {
            return Boolean.TRUE.equals(over.get());
        }
        return CoopHooks.isWorldAuthority();
    }

    private String connectedPeerId() {
        final Supplier<String> over = peerIdOverride;
        if (over != null) {
            final String p = over.get();
            return p != null ? CoopTradeWireLimits.clampName(p) : "";
        }
        try {
            return CoopTradeWireLimits.clampName(CoopSession.get().getPeerName());
        } catch (final Exception e) {
            return "";
        }
    }

    private CoopTradeRole localRole() {
        // Prefer durable role from state/log when pending; else session authority.
        if (state.hasPendingEscrows() || state.getStatus() == CoopTradeState.Status.NEEDS_RECONCILE
                || state.getStatus() == CoopTradeState.Status.ESCROWED
                || state.getStatus() == CoopTradeState.Status.DELIVERED
                || state.getStatus() == CoopTradeState.Status.DELIVER_BLOCKED) {
            return state.getLocalRole();
        }
        return isHost() ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
    }

    private CoopTradeBag bagForRole(final CoopTradeRole role) {
        if (role == null) {
            return null;
        }
        if (role == localRole()) {
            final AdventurePlayer ap = localPlayer();
            return ap != null ? new AdventurePlayerTradeBag(ap) : null;
        }
        return null;
    }

    private void refreshPendingHud() {
        try {
            GameHUD.getInstance().refreshCoopPartyHud();
        } catch (final Exception ignored) {
        }
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
     * Netty → GL only. Tests may install {@link #setGlPoster}; otherwise Gdx,
     * otherwise inline.
     */
    static void postGl(final Runnable r) {
        if (r == null) {
            return;
        }
        final Consumer<Runnable> poster = glPoster;
        if (poster != null) {
            poster.accept(r);
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
