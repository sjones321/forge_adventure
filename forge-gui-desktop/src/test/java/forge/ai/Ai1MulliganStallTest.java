package forge.ai;

import forge.MulliganDefs;
import forge.ai.llm.LlmOpponent;
import forge.ai.llm.LlmSettings;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameStage;
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
        runForcedMulliganGame(/*useLlmStub*/ false);
    }

    @Test(timeOut = 60_000)
    public void forcedMulliganToZeroStillPlaysTurns_llmStub() throws Exception {
        LlmSettings s = new LlmSettings();
        s.setEnabled(true);
        s.setBaseUrl("http://127.0.0.1:9/v1");
        s.setModel("stub");
        s.setApiKey("");
        s.setTimeoutSeconds(1);
        s.setMulliganMinHandSize(0); // allow forced path to reach 0 via always-mull controller
        s.setPriorityWatchdogSeconds(5);
        LlmOpponent.activateForTests(s);
        // Stub never used for mulligan by ForcedMulliganController; still must not hang on spells.
        LlmOpponent.setAskClientForTests(prompt -> null);
        runForcedMulliganGame(/*useLlmStub*/ true);
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

        AtomicReference<Game> gameRef = new AtomicReference<>();
        AtomicInteger handAtPlay = new AtomicInteger(-1);
        AtomicBoolean reachedPlay = new AtomicBoolean();

        Thread t = new Thread(() -> {
            Game game = createAiAiGame(new LobbyPlayerAi("p1", null), new LobbyPlayerAi("p2", null));
            gameRef.set(game);
            game.getAction().startGame(null, () -> {
                reachedPlay.set(true);
                Player p1 = game.getPlayers().get(0);
                handAtPlay.set(p1.getCardsIn(ZoneType.Hand).size());
                for (Player p : game.getPlayers()) {
                    p.concede();
                }
            });
        }, "ai1-floor-game");
        t.setDaemon(true);
        t.start();
        t.join(25_000);

        AssertJUnit.assertTrue("game must finish mulligan and reach play", reachedPlay.get());
        AssertJUnit.assertTrue("LLM forever-mulligan must stop at floor (hand >= 5), got "
                + handAtPlay.get(), handAtPlay.get() >= 5);
        AssertJUnit.assertTrue("stub should have been asked while above floor", asks.get() >= 1);
        AssertJUnit.assertFalse("game thread should not still be stuck in mulligan", t.isAlive());
    }

    @Test
    public void emptyHandKeepsEvenWhenControllerSaysMulligan() {
        Game game = initAndCreateGame();
        Player ai = game.getPlayers().get(1);
        // Empty the hand
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
        // Empty AI hand; give opponent a land so the game can progress.
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

    private void runForcedMulliganGame(boolean useLlmStub) throws Exception {
        AtomicBoolean reachedPlay = new AtomicBoolean();
        AtomicInteger handAtPlay = new AtomicInteger(-1);
        AtomicInteger turnsSeen = new AtomicInteger();
        AtomicReference<Game> gameRef = new AtomicReference<>();

        LobbyPlayerAi forced = new ForcedMulliganLobby("mulliganer");
        LobbyPlayerAi other = new LobbyPlayerAi("keeper", null);

        Thread t = new Thread(() -> {
            Game game = createAiAiGame(forced, other);
            gameRef.set(game);
            game.AI_TIMEOUT = 3;
            game.getAction().startGame(null, () -> {
                reachedPlay.set(true);
                Player mull = game.getPlayers().stream()
                        .filter(p -> "mulliganer".equals(p.getName()))
                        .findFirst().orElse(game.getPlayers().get(0));
                handAtPlay.set(mull.getCardsIn(ZoneType.Hand).size());
            });
        }, "ai1-forced-mull-" + useLlmStub);
        t.setDaemon(true);
        t.start();

        long deadline = System.currentTimeMillis() + 45_000;
        while (System.currentTimeMillis() < deadline) {
            Game g = gameRef.get();
            if (g != null && reachedPlay.get()) {
                turnsSeen.set(g.getPhaseHandler().getTurn());
                if (g.getPhaseHandler().getTurn() >= 2 || g.isGameOver()) {
                    break;
                }
            }
            if (!t.isAlive() && reachedPlay.get()) {
                break;
            }
            Thread.sleep(50);
        }

        Game g = gameRef.get();
        if (g != null && !g.isGameOver()) {
            for (Player p : g.getPlayers()) {
                p.concede();
            }
        }
        t.join(5_000);

        AssertJUnit.assertTrue("must leave mulligan (stall bug)", reachedPlay.get());
        AssertJUnit.assertEquals("forced mulligan-to-0 should leave empty hand", 0, handAtPlay.get());
        AssertJUnit.assertTrue("empty-hand AI must take turns (turn>=2 or game ended), turn="
                        + turnsSeen.get() + " alive=" + t.isAlive(),
                turnsSeen.get() >= 2 || (g != null && g.isGameOver()));
    }

    private Game createAiAiGame(LobbyPlayerAi a, LobbyPlayerAi b) {
        List<RegisteredPlayer> players = new ArrayList<>();
        players.add(new RegisteredPlayer(minimalDeck("Plains")).setPlayer(a));
        players.add(new RegisteredPlayer(minimalDeck("Mountain")).setPlayer(b));
        GameRules rules = new GameRules(GameType.Constructed);
        Match match = new Match(rules, players, "AI1Mulligan");
        return match.createGame();
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
