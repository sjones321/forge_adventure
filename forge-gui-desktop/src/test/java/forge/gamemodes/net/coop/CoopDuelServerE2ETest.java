package forge.gamemodes.net.coop;

import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.HostingServer;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * Loopback E2E for the CO3 duel path CoopDuelRuntime uses: {@link FServerManager}
 * with a co-op session gate + {@link FGameClient} over TCP, then an Adventure-style
 * {@link HostedMatch} (team-0 host+guest, team-1 AI) driven to match end with an
 * outcome-only {@link CoopDuelResultEvent}.
 *
 * <p>Does not boot LibGDX / Adventure UI — it exercises the same server, client,
 * lobby, remote GUI, quit-as-concede, and endGameHook flush ordering as the
 * mobile runtime.
 */
public class CoopDuelServerE2ETest {

    private FServerManager server;
    private FGameClient guestClient;
    private int port;

    @AfterMethod
    public void tearDown() {
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
    }

    private static Deck landDeck(final String name, final String card) {
        final Deck d = new Deck(name);
        d.getMain().add(card, 40);
        return d;
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

        // Guest connects over loopback with the co-op session code (LoginEvent).
        final IGuiGame guestLocalGui = new forge.net.HeadlessNetworkGuiGame();
        guestClient = new FGameClient(guestName, guestLocalGui, "127.0.0.1", port, sessionCode);
        guestClient.connect();

        // Wait for remote GUI slot (same as CoopDuelRuntime.startHostedCoopMatch).
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

        final RegisteredPlayer host = new RegisteredPlayer(landDeck("Host", "Drifting Meadow"))
                .setPlayer(new LobbyPlayerHuman("Host"));
        host.setTeamNumber(0);
        host.setStartingLife(20);
        final RegisteredPlayer guest = new RegisteredPlayer(landDeck("Guest", "Drifting Meadow"))
                .setPlayer(GamePlayerUtil.getGuiPlayer(guestName, 0, 0, false));
        guest.setTeamNumber(0);
        guest.setStartingLife(20);
        final RegisteredPlayer enemy = new RegisteredPlayer(landDeck("Enemy", "Plains"))
                .setPlayer(GamePlayerUtil.createAiPlayer("Enemy"));
        enemy.setTeamNumber(1);
        enemy.setStartingLife(20);

        // Host uses an in-process ProtocolGuiGame (headless); guest seat uses the
        // RemoteClientGuiGame from FServerManager — same split as CoopDuelRuntime.
        final forge.gamemodes.net.ProtocolGuiGame hostGui =
                new forge.gamemodes.net.ProtocolGuiGame(new CoopDuelInProcessTest.RecordingRemote());
        final Map<RegisteredPlayer, IGuiGame> guis = new HashMap<>();
        guis.put(host, hostGui);
        guis.put(guest, remoteGui);

        final AtomicReference<CoopDuelResultEvent> outcome = new AtomicReference<>();
        final AtomicInteger endGameCalls = new AtomicInteger();
        final HostedMatch match = new HostedMatch();
        final GameRules rules = new GameRules(GameType.Adventure);
        rules.setGamesPerMatch(1);
        rules.setManaBurn(false);
        rules.setWarnAboutAICards(false);

        match.setEndGameHook(() -> {
            endGameCalls.incrementAndGet();
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                // After hook returns HostedMatch flushes — queue outcome like CO3.
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
        // Guest QUIT → concede seat only.
        match.setQuitAsConcede(hc -> hc != null && hc.getPlayer() != null
                && hc.getPlayer().getRegisteredPlayer() == guest);
        // Duel socket drop → concede guest (wired like CoopDuelRuntime).
        server.setCoopGuestDisconnectHook(() -> {
            for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
                if (hc != null && hc.getPlayer() != null
                        && hc.getPlayer().getRegisteredPlayer() == guest) {
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

        match.startMatch(rules, EnumSet.of(GameType.Adventure),
                List.of(host, guest, enemy), guis, null);

        // Concede both humans so the AI wins and the match ends.
        final long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            final GameView gv = match.getGameView();
            if (gv != null && gv.isGameOver()) {
                break;
            }
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                break;
            }
            for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
                if (hc != null && hc.getPlayer() != null
                        && !hc.getPlayer().hasLost() && !hc.getPlayer().conceded()) {
                    forge.gui.GuiBase.getInterface().invokeInEdtNow(hc::concede);
                }
            }
            Thread.sleep(100);
        }
        final long settle = System.currentTimeMillis() + 15_000;
        while (outcome.get() == null && System.currentTimeMillis() < settle) {
            Thread.sleep(100);
        }

        assertTrue(endGameCalls.get() >= 1, "endGameHook ran: " + endGameCalls.get());
        assertNotNull(outcome.get(), "match outcome after FServerManager flush");
        assertEquals(outcome.get().getDuelId(), 99L);
        assertEquals(outcome.get().getEnemyId(), 7L);
        assertTrue(match.getMatch() == null || match.getMatch().isMatchOver()
                || outcome.get() != null);
    }
}
