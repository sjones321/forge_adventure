package forge.gamemodes.net.coop;

import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.client.NetGameController;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.HostingServer;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.HeadlessNetworkGuiGame;
import forge.net.PortAllocator;
import forge.net.TestUtils;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.util.MyRandom;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Loopback E2E for the CO3 duel path CoopDuelRuntime uses: {@link FServerManager}
 * with a co-op session gate + {@link FGameClient} over TCP, then an Adventure-style
 * {@link HostedMatch} (team-0 host+guest, team-1 AI) driven to match end with an
 * outcome-only {@link CoopDuelResultEvent}.
 *
 * <p>Host seat uses in-process {@link ProtocolGuiGame}; guest seat uses the real
 * {@code RemoteClientGuiGame} from {@link FServerManager#getGui(1)} so prompts and
 * deltas travel over the Netty path. Guest local GUI auto-answers like
 * {@code HeadlessNetworkClient}.
 */
public class CoopDuelServerE2ETest {

    private FServerManager server;
    private FGameClient guestClient;
    private AutoRespondGuestGui guestLocalGui;
    private int port;

    @AfterMethod
    public void tearDown() {
        try {
            if (guestLocalGui != null) {
                guestLocalGui.shutdown();
            }
        } catch (final Exception ignored) {
        }
        guestLocalGui = null;
        try {
            if (guestClient != null) {
                guestClient.close();
            }
        } catch (final Exception ignored) {
        }
        guestClient = null;
        try {
            if (server != null && HostingServer.isHosting()) {
                server.clearCoopSessionGate();
                server.stopServer();
                server.setLobby(null);
            }
        } catch (final Exception ignored) {
        }
        server = null;
        // Let the prior loopback bind release before the next E2E test allocates a port.
        try {
            Thread.sleep(200);
        } catch (final InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Deck landDeck(final String name, final String card) {
        final Deck d = new Deck(name);
        d.getMain().add(card, 40);
        return d;
    }

    /**
     * Client-side GUI that auto-OKs prompts and picks a starting player so the
     * RemoteClientGuiGame path does not block the game thread forever.
     */
    private static final class AutoRespondGuestGui extends HeadlessNetworkGuiGame {
        private final String username;
        private volatile IGameController gameController;
        private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "CoopE2E-GuestAuto");
            t.setDaemon(true);
            return t;
        });
        private volatile ScheduledFuture<?> pending;

        AutoRespondGuestGui(final String username) {
            this.username = username;
        }

        void shutdown() {
            if (pending != null) {
                pending.cancel(false);
            }
            exec.shutdownNow();
        }

        private void schedule(final Runnable action, final long delayMs) {
            if (pending != null) {
                pending.cancel(false);
            }
            pending = exec.schedule(() -> {
                try {
                    if (gameController != null) {
                        action.run();
                    }
                } catch (final Exception ignored) {
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void setOriginalGameController(final PlayerView view, final IGameController controller) {
            super.setOriginalGameController(view, controller);
            if (controller != null) {
                gameController = controller;
            }
        }

        @Override
        public void setGameController(final PlayerView player, final IGameController controller) {
            super.setGameController(player, controller);
            if (controller != null) {
                gameController = controller;
            }
        }

        @Override
        public void showPromptMessage(final PlayerView playerView, final String message,
                                     final forge.game.card.CardView card) {
            if (message != null && message.contains("Click on the portrait") && gameController != null) {
                schedule(() -> {
                    final GameView gv = getGameView();
                    if (gv == null || gv.getPlayers() == null || gv.getPlayers().isEmpty()) {
                        return;
                    }
                    PlayerView pick = null;
                    for (final PlayerView pv : gv.getPlayers()) {
                        if (pv.getName() != null && pv.getName().equalsIgnoreCase(username)) {
                            pick = pv;
                            break;
                        }
                    }
                    if (pick == null) {
                        pick = gv.getPlayers().iterator().next();
                    }
                    gameController.selectPlayer(pick, null);
                }, 80);
            }
        }

        @Override
        public void updateButtons(final PlayerView owner, final boolean okEnabled,
                                  final boolean cancelEnabled, final boolean focusOk) {
            if (gameController != null && okEnabled) {
                schedule(() -> gameController.selectButtonOk(), 40);
            }
        }

        @Override
        public void updateButtons(final PlayerView owner, final String label1, final String label2,
                                  final boolean enable1, final boolean enable2, final boolean focus1) {
            if (gameController == null) {
                return;
            }
            if (enable1) {
                schedule(() -> gameController.selectButtonOk(), 40);
            } else if (enable2) {
                schedule(() -> gameController.selectButtonCancel(), 40);
            }
        }

        @Override
        public void afterGameEnd() {
            super.afterGameEnd();
            shutdown();
        }
    }

    @Test(timeOut = 180_000)
    public void fServerManagerAndFGameClientDriveAdventureStyleMatchToCompletion() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        MyRandom.setRandom(new Random(29));

        port = PortAllocator.allocatePort();
        final String guestName = CoopDuelIdentity.normalizeUsername("Guest");
        final String sessionCode = CoopDuelIdentity.normalizeSessionCode("ABCD1234");

        server = FServerManager.getInstance();
        final ServerGameLobby lobby = new ServerGameLobby();
        server.setLobby(lobby);
        server.setCoopSessionGate(guestName, sessionCode);
        server.startServer(port, "127.0.0.1", Boolean.FALSE);
        assertTrue(HostingServer.isHosting(), "co-op duel game server hosting");

        guestLocalGui = new AutoRespondGuestGui(guestName);
        guestClient = new FGameClient(guestName, guestLocalGui, "127.0.0.1", port, sessionCode);
        guestClient.connect();

        IGuiGame remoteGui = null;
        final long connectDeadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < connectDeadline) {
            remoteGui = server.getGui(1);
            if (remoteGui != null) {
                break;
            }
            Thread.sleep(50);
        }
        assertNotNull(remoteGui, "FServerManager.getGui(1) after FGameClient connect");

        final RegisteredPlayer hostRp = new RegisteredPlayer(landDeck("Host", "Drifting Meadow"))
                .setPlayer(new LobbyPlayerHuman("Host"));
        hostRp.setTeamNumber(0);
        hostRp.setStartingLife(20);
        final RegisteredPlayer guestRp = new RegisteredPlayer(landDeck("Guest", "Drifting Meadow"))
                .setPlayer(new LobbyPlayerHuman(guestName));
        guestRp.setTeamNumber(0);
        guestRp.setStartingLife(20);
        final RegisteredPlayer enemyRp = new RegisteredPlayer(landDeck("Enemy", "Plains"))
                .setPlayer(GamePlayerUtil.createAiPlayer("Enemy"));
        enemyRp.setTeamNumber(1);
        enemyRp.setStartingLife(20);

        final CoopDuelInProcessTest.RecordingRemote hostRemote = new CoopDuelInProcessTest.RecordingRemote();
        final ProtocolGuiGame hostGui = new ProtocolGuiGame(hostRemote);
        final Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
        guis.put(hostRp, hostGui);
        guis.put(guestRp, remoteGui);

        final AtomicReference<CoopDuelResultEvent> outcome = new AtomicReference<>();
        final AtomicInteger endGameCalls = new AtomicInteger();
        final HostedMatch match = new HostedMatch();
        // Constructed rules + Adventure-style seating (team 0 host+guest, team 1 AI).
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);
        rules.setWarnAboutAICards(false);

        match.setEndGameHook(() -> {
            endGameCalls.incrementAndGet();
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                final forge.game.Game g = match.getGame();
                final Runnable emit = () -> {
                    if (outcome.get() != null) {
                        return;
                    }
                    final RegisteredPlayer winner = match.getMatch().getWinner();
                    final int team = winner != null ? winner.getTeamNumber() : -1;
                    outcome.set(new CoopDuelResultEvent(99L, team, 7L, "Enemy"));
                };
                if (g != null) {
                    g.getAction().invoke(emit);
                } else {
                    emit.run();
                }
            } else {
                for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
                    if (hc != null) {
                        hc.nextGameDecision(NextGameDecision.CONTINUE);
                    }
                }
            }
        });
        match.setQuitAsConcede(hc -> hc != null && hc.getPlayer() != null
                && hc.getPlayer().getRegisteredPlayer() == guestRp);
        server.setCoopGuestDisconnectHook(() -> {
            for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
                if (hc != null && hc.getPlayer() != null
                        && hc.getPlayer().getRegisteredPlayer() == guestRp) {
                    final forge.game.Game g = match.getGame();
                    if (g != null) {
                        g.getAction().invoke(hc::concede);
                    } else {
                        hc.concede();
                    }
                    return;
                }
            }
        });

        match.startMatch(rules, EnumSet.of(GameType.Constructed),
                List.of(hostRp, guestRp, enemyRp), guis, null);

        // Warm until host openView, answer start-player / mulligan prompts, then
        // concede both humans on the server (guest also auto-OKs over the socket).
        final long warmDeadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < warmDeadline && hostRemote.myPlayers == null) {
            answerHost(hostRemote, hostGui, false);
        }
        assertNotNull(hostRemote.myPlayers, "host openView over ProtocolGuiGame");

        // DS1: while the loopback match is live, prove the guest modern-cast path —
        // NetGameController.selectCard over the real FGameClient wire.
        assertGuestModernCastOverLoopback(guestName, hostRemote, hostGui);

        final long deadline = System.currentTimeMillis() + 45_000;
        int answered = 0;
        while (System.currentTimeMillis() < deadline) {
            final GameView gv = match.getGameView();
            if (gv != null && gv.isGameOver()) {
                break;
            }
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                break;
            }
            final boolean concedePhase = answered >= 3;
            if (answerHost(hostRemote, hostGui, concedePhase)) {
                answered++;
                continue;
            }
            if (concedePhase) {
                break;
            }
        }
        for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
            if (hc != null && hc.getPlayer() != null
                    && !hc.getPlayer().hasLost() && !hc.getPlayer().conceded()) {
                forge.gui.GuiBase.getInterface().invokeInEdtNow(hc::concede);
            }
        }
        final long settle = System.currentTimeMillis() + 30_000;
        while (outcome.get() == null && System.currentTimeMillis() < settle) {
            Thread.sleep(100);
            try {
                forge.gui.GuiBase.getInterface().invokeInEdtAndWait(() -> { });
            } catch (final Exception ignored) {
            }
            // Keep answering host prompts so a blocked game thread can finish.
            try {
                answerHost(hostRemote, hostGui, true);
            } catch (final Exception ignored) {
            }
        }

        assertTrue(endGameCalls.get() >= 1, "endGameHook ran: " + endGameCalls.get());
        assertNotNull(outcome.get(), "match outcome after FServerManager flush");
        assertEquals(outcome.get().getDuelId(), 99L);
        assertEquals(outcome.get().getEnemyId(), 7L);
        assertTrue(hostRemote.fullStates >= 1 || hostRemote.myPlayers != null,
                "host ProtocolGuiGame received match traffic");
        assertTrue(match.getMatch() == null || match.getMatch().isMatchOver()
                || outcome.get() != null);
    }

    /**
     * DS1: combat-declare flags arm while InputAttack/InputBlock would be active,
     * clear on explicit stop, reset path, and match end ({@code afterGameEnd}).
     */
    @Test
    public void setCombatDeclareInputLifecycleAndMatchEndClear() {
        final HeadlessNetworkGuiGame gui = new HeadlessNetworkGuiGame();
        assertFalse(gui.isCombatDeclareAttackersInput());
        assertFalse(gui.isCombatDeclareBlockersInput());

        // InputAttack.showMessage
        gui.setCombatDeclareInput(true, false);
        assertTrue(gui.isCombatDeclareAttackersInput());
        assertFalse(gui.isCombatDeclareBlockersInput());
        // InputAttack.onStop
        gui.setCombatDeclareInput(false, false);
        assertFalse(gui.isCombatDeclareAttackersInput());

        // InputBlock.showMessage / onStop
        gui.setCombatDeclareInput(false, true);
        assertTrue(gui.isCombatDeclareBlockersInput());
        gui.setCombatDeclareInput(false, false);
        assertFalse(gui.isCombatDeclareBlockersInput());

        // Match end must clear a stale flag.
        gui.setCombatDeclareInput(true, false);
        assertTrue(gui.isCombatDeclareAttackersInput());
        gui.afterGameEnd();
        assertFalse(gui.isCombatDeclareAttackersInput());
        assertFalse(gui.isCombatDeclareBlockersInput());

        gui.setCombatDeclareInput(false, true);
        gui.afterGameEnd();
        assertFalse(gui.isCombatDeclareBlockersInput());
    }

    /**
     * DS1: on a live FServerManager + FGameClient match, send a modern-cast
     * {@code selectCard} through {@link NetGameController} and assert it hits the wire.
     */
    private void assertGuestModernCastOverLoopback(
            final String guestName,
            final CoopDuelInProcessTest.RecordingRemote hostRemote,
            final ProtocolGuiGame hostGui) throws Exception {
        PlayerView guestView = null;
        IGameController netCtrl = null;
        CardView castCard = null;
        final long readyDeadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < readyDeadline
                && (netCtrl == null || castCard == null)) {
            answerHost(hostRemote, hostGui, false);
            if (netCtrl == null) {
                for (final PlayerView p : guestLocalGui.getLocalPlayers()) {
                    if (p == null) {
                        continue;
                    }
                    final IGameController c = guestLocalGui.getGameController(p);
                    if (c instanceof NetGameController) {
                        guestView = p;
                        netCtrl = c;
                        break;
                    }
                }
            }
            if (castCard == null) {
                final GameView ggv = guestLocalGui.getGameView();
                if (ggv != null && ggv.getPlayers() != null) {
                    for (final PlayerView p : ggv.getPlayers()) {
                        if (p == null || p.getName() == null
                                || !p.getName().equalsIgnoreCase(guestName)
                                || p.getHand() == null) {
                            continue;
                        }
                        for (final CardView c : p.getHand()) {
                            if (c != null) {
                                castCard = c;
                                guestView = p;
                                break;
                            }
                        }
                        if (castCard != null) {
                            break;
                        }
                    }
                }
            }
            Thread.sleep(50);
        }
        assertNotNull(guestView, "guest player view over loopback");
        assertNotNull(netCtrl, "guest NetGameController from FGameClient");
        assertTrue(netCtrl instanceof NetGameController,
                "guest seat must be NetGameController, was " + netCtrl.getClass().getName());
        assertNotNull(castCard, "guest hand card synced over loopback");

        final AtomicInteger selectCardSends = new AtomicInteger();
        final AtomicReference<CardView> sentCard = new AtomicReference<>();
        final FGameClient clientWire = guestClient;
        final NetGameController countingNet = new NetGameController(new forge.gamemodes.net.client.IToServer() {
            @Override
            public void send(final forge.gamemodes.net.event.NetEvent event) {
                if (event instanceof forge.gamemodes.net.event.GuiGameEvent ev
                        && ev.getMethod() == forge.gamemodes.net.ProtocolMethod.selectCard) {
                    selectCardSends.incrementAndGet();
                    sentCard.set((CardView) ev.getObjects()[0]);
                }
                clientWire.send(event);
            }

            @Override
            public Object sendAndWait(final forge.gamemodes.net.event.IdentifiableNetEvent event) {
                send(event);
                return clientWire.sendAndWait(event);
            }
        });
        guestLocalGui.setGameController(guestView, countingNet);

        final CardView toCast = castCard;
        forge.gui.GuiBase.getInterface().invokeInEdtNow(
                () -> countingNet.selectCard(toCast, null, null));
        try {
            forge.gui.GuiBase.getInterface().invokeInEdtAndWait(() -> { });
        } catch (final Exception ignored) {
        }
        Thread.sleep(200);
        answerHost(hostRemote, hostGui, false);

        assertEquals(selectCardSends.get(), 1,
                "NetGameController selectCard sent once over FGameClient loopback");
        assertNotNull(sentCard.get());
        assertEquals(sentCard.get().getId(), toCast.getId(),
                "loopback selectCard carried the hand card id");
    }

    /** Answer one host updateButtons prompt (incl. multiplayer start-player pick). */
    private static boolean answerHost(final CoopDuelInProcessTest.RecordingRemote remote,
                                      final ProtocolGuiGame gui,
                                      final boolean concedeNow) throws Exception {
        final forge.gamemodes.net.event.GuiGameEvent ub =
                remote.buttonPrompts.poll(200, TimeUnit.MILLISECONDS);
        if (ub == null) {
            return false;
        }
        final Object[] args = ub.getObjects();
        final PlayerView owner = (PlayerView) args[0];
        if (remote.myPlayers == null || owner == null || !remote.myPlayers.contains(owner)) {
            return false;
        }
        final IGameController controller = gui.getGameController(owner);
        if (controller == null) {
            return false;
        }
        final boolean okEnabled = args.length < 4 || Boolean.TRUE.equals(args[3]);
        final forge.gui.interfaces.IGuiBase guiBase = forge.gui.GuiBase.getInterface();
        if (!okEnabled) {
            final GameView gv = gui.getGameView();
            if (gv != null && gv.getPlayers() != null) {
                for (final PlayerView p : gv.getPlayers()) {
                    final PlayerView pick = p;
                    guiBase.invokeInEdtNow(() -> controller.selectPlayer(pick, null));
                    return true;
                }
            }
        }
        if (concedeNow) {
            guiBase.invokeInEdtNow(controller::concede);
            return true;
        }
        guiBase.invokeInEdtNow(controller::selectButtonOk);
        return true;
    }
}
