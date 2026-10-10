package forge.ai;

import forge.MulliganDefs;
import forge.ai.llm.LlmOpponent;
import forge.ai.llm.LlmSettings;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;
import org.testng.AssertJUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI1: mulligan-to-0 must not stall the game; LLM forever-mulligan stops at the floor;
 * empty-hand AI still takes turns. Uses forge.test.userDir; stubs the LLM (no network).
 */
public class Ai1MulliganStallTest extends AITest {

    private Path llmDir;
    private String previousLlmDir;

    @BeforeMethod
    public void isolateLlmDir() throws Exception {
        LlmOpponent.deactivateForTests();
        previousLlmDir = System.getProperty("forge.llm.dir");
        llmDir = Files.createTempDirectory("ai1-mulligan-stall-");
        System.setProperty("forge.llm.dir", llmDir.toAbsolutePath().toString());
        MyRandom.setRandom(new Random(42));
        FModel.getMagicDb().setMulliganRule(MulliganDefs.MulliganRule.London);
    }

    @AfterMethod(alwaysRun = true)
    public void restoreLlmDir() {
        LlmOpponent.deactivateForTests();
        if (previousLlmDir == null) {
            System.clearProperty("forge.llm.dir");
        } else {
            System.setProperty("forge.llm.dir", previousLlmDir);
        }
    }

    @Test(timeOut = 60_000)
    public void forcedMulliganToZeroStillPlaysTurns_llmOff() throws Exception {
        AssertJUnit.assertFalse(LlmOpponent.isActive());
        runForcedMulliganGame();
    }

    @Test(timeOut = 60_000)
    public void forcedMulliganToZeroStillPlaysTurns_llmStub() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setApiKey("");
        s.setTimeoutSeconds(1);
        // Floor 0 so ForcedMulliganController can reach an empty hand; LLM stub unused for mulligan.
        s.setMulliganMinHandSize(0);
        s.setPriorityWatchdogSeconds(5);
        LlmOpponent.activateForTests(s);
        LlmOpponent.setAskClientForTests(prompt -> null);
        runForcedMulliganGame();
    }

    @Test(timeOut = 30_000)
    public void foreverMulliganLlmStubStopsAtFloorAndGameStarts() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setApiKey("");
        s.setTimeoutSeconds(1);
        s.setMulliganMinHandSize(5);
        s.setPriorityWatchdogSeconds(5);
        LlmOpponent.activateForTests(s);
        AtomicInteger asks = new AtomicInteger();
        LlmOpponent.setAskClientForTests(prompt -> {
            asks.incrementAndGet();
            return "{\"keep\": false, \"reason\": \"mull forever\"}";
        });

        LobbyPlayerAi p1Lobby = new LobbyPlayerAi("p1", null);
        LobbyPlayerAi p2Lobby = new LobbyPlayerAi("p2", null);
        Match match = createMatch(p1Lobby, p2Lobby);
        Game game = match.createGame();
        game.AI_TIMEOUT = 3;
        Player tracked = game.getRegisteredPlayers().get(0);

        CountDownLatch reachedPlay = new CountDownLatch(1);
        AtomicInteger handAtPlay = new AtomicInteger(-1);
        AtomicReference<Throwable> gameError = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                match.startGame(game, () -> {
                    handAtPlay.set(tracked.getCardsIn(ZoneType.Hand).size());
                    reachedPlay.countDown();
                    for (Player p : new ArrayList<>(game.getRegisteredPlayers())) {
                        if (!p.hasLost()) {
                            p.concede();
                        }
                    }
                });
            } catch (Throwable e) {
                gameError.set(e);
                reachedPlay.countDown();
            }
        }, "ai1-floor-game");
        t.setDaemon(true);
        t.start();

        AssertJUnit.assertTrue("game must finish mulligan and reach play",
                reachedPlay.await(25, TimeUnit.SECONDS));
        if (gameError.get() != null) {
            throw new AssertionError("game thread failed", gameError.get());
        }
        AssertJUnit.assertTrue("LLM forever-mulligan must stop at floor (hand >= 5), got "
                + handAtPlay.get(), handAtPlay.get() >= 5);
        AssertJUnit.assertTrue("stub should have been asked while above floor", asks.get() >= 1);
        t.join(5_000);
        AssertJUnit.assertFalse("game thread should not still be stuck in mulligan", t.isAlive());
    }

    @Test
    public void chooseKeepHandFloorStopsForeverMulliganStub() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setTimeoutSeconds(1);
        s.setMulliganMinHandSize(5);
        LlmOpponent.activateForTests(s);
        AtomicInteger asks = new AtomicInteger();
        LlmOpponent.setAskClientForTests(prompt -> {
            asks.incrementAndGet();
            return "{\"keep\": false, \"reason\": \"mull forever\"}";
        });

        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        fillLibrary(ai, 40);
        // Build a 7-card hand, then shrink to simulate London post-tuck sizes.
        for (int i = 0; i < 7; i++) {
            addCardToZone("Plains", ai, ZoneType.Hand);
        }
        AssertJUnit.assertEquals(Boolean.FALSE, LlmOpponent.chooseKeepHand(ai, 0));
        while (ai.getCardsIn(ZoneType.Hand).size() > 6) {
            game.getAction().moveTo(ZoneType.Exile, ai.getCardsIn(ZoneType.Hand).get(0), null, null);
        }
        AssertJUnit.assertEquals(Boolean.FALSE, LlmOpponent.chooseKeepHand(ai, 1));
        while (ai.getCardsIn(ZoneType.Hand).size() > 5) {
            game.getAction().moveTo(ZoneType.Exile, ai.getCardsIn(ZoneType.Hand).get(0), null, null);
        }
        AssertJUnit.assertEquals(Boolean.TRUE, LlmOpponent.chooseKeepHand(ai, 2));
        AssertJUnit.assertEquals("only hands above the floor ask the LLM", 2, asks.get());
    }

    @Test
    public void emptyHandKeepsEvenWhenControllerSaysMulligan() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        for (Card c : new ArrayList<>(ai.getCardsIn(ZoneType.Hand))) {
            ai.getGame().getAction().moveTo(ZoneType.Exile, c, null, null);
        }
        AssertJUnit.assertTrue(ai.getCardsIn(ZoneType.Hand).isEmpty());
        PlayerControllerAi ctrl = (PlayerControllerAi) ai.getController();
        AssertJUnit.assertTrue("empty hand must keep", ctrl.mulliganKeepHand(ai, 7));
    }

    @Test(timeOut = 15_000)
    public void emptyHandAiStillTakesPriorityTurns() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        Player opp = game.getPlayers().get(0);
        fillLibrary(ai, 40);
        fillLibrary(opp, 40);
        for (Card c : new ArrayList<>(ai.getCardsIn(ZoneType.Hand))) {
            game.getAction().moveTo(ZoneType.Exile, c, null, null);
        }
        addCard("Mountain", opp);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, ai);
        game.AI_TIMEOUT = 3;

        int startTurn = game.getPhaseHandler().getTurn();
        int steps = 0;
        while (!game.isGameOver() && steps < 80
                && game.getPhaseHandler().getTurn() <= startTurn + 1) {
            game.getPhaseHandler().mainLoopStep();
            steps++;
        }
        AssertJUnit.assertTrue("empty-hand AI must advance the phase machine, steps=" + steps, steps > 0);
        AssertJUnit.assertTrue("turn or game should progress",
                game.isGameOver() || game.getPhaseHandler().getTurn() > startTurn
                        || game.getPhaseHandler().getPhase() != PhaseType.MAIN1);
    }

    // ---- helpers ----

    private void runForcedMulliganGame() throws Exception {
        ForcedMulliganLobby forced = new ForcedMulliganLobby("mulliganer");
        LobbyPlayerAi other = new LobbyPlayerAi("keeper", null);
        Match match = createMatch(forced, other);
        Game game = match.createGame();
        game.AI_TIMEOUT = 3;

        Player mulliganer = null;
        for (Player p : game.getRegisteredPlayers()) {
            if ("mulliganer".equals(p.getName())) {
                mulliganer = p;
                break;
            }
        }
        AssertJUnit.assertNotNull("mulliganer player must exist", mulliganer);
        final Player tracked = mulliganer;

        CountDownLatch reachedPlay = new CountDownLatch(1);
        AtomicInteger handAtPlay = new AtomicInteger(-1);
        AtomicInteger turnsSeen = new AtomicInteger();
        AtomicBoolean gameOver = new AtomicBoolean();
        AtomicReference<Throwable> gameError = new AtomicReference<>();

        Thread t = new Thread(() -> {
            try {
                match.startGame(game, () -> {
                    handAtPlay.set(tracked.getCardsIn(ZoneType.Hand).size());
                    reachedPlay.countDown();
                });
                gameOver.set(true);
            } catch (Throwable e) {
                gameError.set(e);
                reachedPlay.countDown();
            }
        }, "ai1-forced-mull");
        t.setDaemon(true);
        t.start();

        AssertJUnit.assertTrue("must leave mulligan (stall bug)",
                reachedPlay.await(40, TimeUnit.SECONDS));
        if (gameError.get() != null) {
            throw new AssertionError("game thread failed", gameError.get());
        }
        AssertJUnit.assertEquals("forced mulligan-to-0 should leave empty hand", 0, handAtPlay.get());

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            turnsSeen.set(game.getPhaseHandler().getTurn());
            if (game.isGameOver() || turnsSeen.get() >= 2) {
                break;
            }
            Thread.sleep(50);
        }
        if (!game.isGameOver()) {
            for (Player p : new ArrayList<>(game.getRegisteredPlayers())) {
                if (!p.hasLost()) {
                    p.concede();
                }
            }
        }
        t.join(5_000);

        AssertJUnit.assertTrue("empty-hand AI must take turns (turn>=2 or game ended), turn="
                        + turnsSeen.get() + " alive=" + t.isAlive() + " over=" + game.isGameOver(),
                turnsSeen.get() >= 2 || game.isGameOver() || gameOver.get());
    }

    private Match createMatch(LobbyPlayerAi a, LobbyPlayerAi b) {
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(minimalDeck("Plains")).setPlayer(a));
        players.add(new RegisteredPlayer(minimalDeck("Mountain")).setPlayer(b));
        GameRules rules = new GameRules(GameType.Constructed);
        return new Match(rules, players, "AI1Mulligan");
    }

    private static Deck minimalDeck(String landName) {
        PaperCard land = FModel.getMagicDb().getCommonCards().getCard(landName);
        AssertJUnit.assertNotNull(landName, land);
        Deck deck = new Deck(landName + " AI1");
        for (int i = 0; i < 40; i++) {
            deck.getMain().add(land);
        }
        return deck;
    }

    /** AI lobby that always mulligans until the hand is empty (then keep). */
    private static final class ForcedMulliganLobby extends LobbyPlayerAi {
        ForcedMulliganLobby(String name) {
            super(name, null);
        }

        @Override
        public Player createIngamePlayer(Game game, final int id) {
            Player ai = new Player(getName(), game, id);
            ai.setFirstController(new ForcedMulliganController(game, ai, this));
            return ai;
        }
    }

    private static final class ForcedMulliganController extends PlayerControllerAi {
        ForcedMulliganController(Game game, Player player, LobbyPlayerAi lobby) {
            super(game, player, lobby);
        }

        @Override
        public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
            if (getPlayer().getCardsIn(ZoneType.Hand).isEmpty()) {
                return true;
            }
            return false;
        }
    }
}
