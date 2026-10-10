package forge.game;

import forge.ai.AITest;
import forge.ai.PlayerControllerAi;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlaySpellAbility;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DS4: take back last land/spell/ability via a dedicated pre-action {@link GameSnapshot}.
 * Tests drive {@link forge.game.phase.PhaseHandler#mainLoopStep()} — not a copy of its logic.
 */
public class TakeBackDs4Test extends AITest {

    /** Queues top-level choices; {@link #isAI()} is false so PhaseHandler captures snapshots. */
    private static final class ScriptedHumanController extends PlayerControllerAi {
        private final Queue<List<SpellAbility>> queued = new ArrayDeque<>();
        private Runnable beforePass;

        ScriptedHumanController(final Game game, final Player p) {
            super(game, p, p.getLobbyPlayer());
        }

        void queue(final SpellAbility sa) {
            queued.add(Collections.singletonList(sa));
        }

        void onBeforePass(final Runnable r) {
            beforePass = r;
        }

        @Override
        public boolean isAI() {
            return false;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            if (!queued.isEmpty()) {
                return queued.poll();
            }
            if (beforePass != null) {
                final Runnable r = beforePass;
                beforePass = null;
                r.run();
            }
            return null; // pass
        }

        @Override
        public boolean playChosenSpellAbility(final SpellAbility sa) {
            return PlaySpellAbility.playSpellAbility(this, player, sa);
        }
    }

    private Game enableTakeBack() {
        final Game game = initAndCreateGame();
        // Dedicated take-back must not require the global experimental cancel restore.
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        game.TAKE_BACK_ENABLED = true;
        return game;
    }

    private ScriptedHumanController installScripted(final Game game, final Player p) {
        final ScriptedHumanController ctrl = new ScriptedHumanController(game, p);
        p.dangerouslySetController(ctrl);
        return ctrl;
    }

    private SpellAbility landAbility(final Card land, final Player p) {
        for (final SpellAbility sa : land.getAllPossibleAbilities(p, true)) {
            if (sa.isLandAbility()) {
                return sa;
            }
        }
        return null;
    }

    private SpellAbility spellAbility(final Card card, final Player p) {
        for (final SpellAbility sa : card.getAllPossibleAbilities(p, true)) {
            if (sa.isSpell()) {
                return sa;
            }
        }
        return null;
    }

    private boolean driveUntil(final Game game, final java.util.function.BooleanSupplier done) {
        for (int i = 0; i < 40 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
            if (done.getAsBoolean()) {
                return true;
            }
        }
        return done.getAsBoolean();
    }

    @Test
    public void takeBackLandRestoresHandAndDrop() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        fillLibrary(p, 8);
        fillLibrary(opp, 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final ScriptedHumanController ctrl = installScripted(game, p);
        final SpellAbility landSa = landAbility(plains, p);
        Assert.assertNotNull(landSa);
        ctrl.queue(landSa);
        ctrl.onBeforePass(() -> {
            Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));
            Assert.assertEquals(p.getLandsPlayedThisTurn(), 1);
            Assert.assertTrue(game.canTakeBack(p));
            Assert.assertTrue(p.getView().canTakeBack());
            Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
            Assert.assertTrue(plains.isInZone(ZoneType.Hand), "land back in hand");
            Assert.assertEquals(p.getLandsPlayedThisTurn(), 0, "land drop available again");
            Assert.assertFalse(game.canTakeBack(p));
        });

        Assert.assertTrue(driveUntil(game, () -> plains.isInZone(ZoneType.Hand)
                && p.getLandsPlayedThisTurn() == 0
                && !game.canTakeBack(p)), "take-back should complete via mainLoopStep");
    }

    @Test
    public void takeBackSpellClearsStackAndDoesNotResolve() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        final Card memnite = addCardToZone("Memnite", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final ScriptedHumanController ctrl = installScripted(game, p);
        final SpellAbility castSa = spellAbility(memnite, p);
        Assert.assertNotNull(castSa);
        ctrl.queue(castSa);
        ctrl.onBeforePass(() -> {
            Assert.assertFalse(game.getStack().isEmpty(), "spell should be on the stack");
            Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Hand), 0);
            Assert.assertTrue(game.canTakeBack(p));
            Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
            Assert.assertTrue(game.getStack().isEmpty(), "Critical 1: stack fully reverted");
            Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Hand), 1,
                    "spell card restored to hand");
            Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Battlefield), 0,
                    "spell must not resolve");
        });

        Assert.assertTrue(driveUntil(game, () -> countCardsWithName(game, "Memnite", ZoneType.Hand) == 1
                && game.getStack().isEmpty()
                && !game.canTakeBack(p)), "spell take-back via mainLoopStep");
        Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Battlefield), 0);
    }

    @Test
    public void takeBackThenCancelDoesNotReapplyAction() {
        final Game game = enableTakeBack();
        // Cancel path needs experimental restore — take-back must still reset previousGameState.
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = true;
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final ScriptedHumanController ctrl = installScripted(game, p);
        ctrl.queue(landAbility(plains, p));
        ctrl.onBeforePass(() -> {
            Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));
            // Simulate the next priority stash having overwritten previousGameState with post-land.
            game.stashGameState();
            Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
            Assert.assertTrue(plains.isInZone(ZoneType.Hand));
            // Critical 2: cancel restore must not put the land back on the battlefield.
            Assert.assertTrue(game.restoreGameState(), "cancel path should have a re-stashed state");
            Assert.assertTrue(plains.isInZone(ZoneType.Hand),
                    "cancel after take-back must not re-apply the taken-back land");
        });

        Assert.assertTrue(driveUntil(game, () -> plains.isInZone(ZoneType.Hand)
                && p.getLandsPlayedThisTurn() == 0));
    }

    @Test
    public void takeBackRefusedAfterDrawRevealAndOpponent() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        fillLibrary(p, 8);
        fillLibrary(opp, 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final ScriptedHumanController ctrl = installScripted(game, p);
        ctrl.queue(landAbility(plains, p));
        final AtomicReference<String> fail = new AtomicReference<>();
        ctrl.onBeforePass(() -> {
            try {
                Assert.assertTrue(game.canTakeBack(p));
                p.drawCards(1);
                Assert.assertFalse(game.canTakeBack(p), "draw locks take-back");

                game.captureTakeBackSnapshot(p);
                Assert.assertTrue(game.canTakeBack(p));
                game.getAction().reveal(p.getCardsIn(ZoneType.Hand), p, false, "test reveal");
                Assert.assertFalse(game.canTakeBack(p), "reveal locks take-back");

                game.captureTakeBackSnapshot(p);
                Assert.assertTrue(game.canTakeBack(p));
                game.bumpInformationEpoch(); // opponent / AI decision
                Assert.assertFalse(game.canTakeBack(p), "opponent/AI decision locks take-back");

                // Pass also locks (exercised when this runnable returns null from choose).
            } catch (final AssertionError e) {
                fail.set(e.getMessage());
            }
        });

        Assert.assertTrue(driveUntil(game, () -> fail.get() != null
                || (plains.isInZone(ZoneType.Battlefield) && !game.canTakeBack(p))));
        if (fail.get() != null) {
            Assert.fail(fail.get());
        }
        Assert.assertNotNull(opp);
    }

    @Test
    public void failedRestoreLeavesStateUnchanged() throws Exception {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Assert.assertTrue(game.captureTakeBackSnapshot(p));
        Assert.assertTrue(PlaySpellAbility.playSpellAbility(
                installScripted(game, p), p, landAbility(plains, p)));
        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));

        Assert.assertEquals(game.takeBack(oppOrOther(game, p)), TakeBackResult.NOT_AVAILABLE);
        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));

        final GameSnapshot broken = new GameSnapshot(game);
        final java.lang.reflect.Field snap = Game.class.getDeclaredField("takeBackSnapshot");
        snap.setAccessible(true);
        snap.set(game, broken);
        final java.lang.reflect.Field owner = Game.class.getDeclaredField("takeBackOwner");
        owner.setAccessible(true);
        owner.set(game, p);
        final java.lang.reflect.Field epoch = Game.class.getDeclaredField("takeBackEpoch");
        epoch.setAccessible(true);
        epoch.set(game, game.getInformationEpoch());

        Assert.assertEquals(game.takeBack(p), TakeBackResult.RESTORE_FAILED);
        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield), "board unchanged after failed restore");
    }

    @Test
    public void snapshotCostLargeBoard() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        fillLibrary(p, 40);
        fillLibrary(opp, 40);
        addCards("Plains", 12, p);
        addCards("Island", 12, p);
        addCards("Swamp", 12, opp);
        addCards("Mountain", 12, opp);
        for (int i = 0; i < 40; i++) {
            addCard("Runeclaw Bear", p);
            addCard("Grizzly Bears", opp);
        }
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final int warmup = 2;
        for (int i = 0; i < warmup; i++) {
            final GameSnapshot warm = new GameSnapshot(game);
            warm.makeCopy();
        }
        final int runs = 5;
        long totalNs = 0L;
        long maxNs = 0L;
        for (int i = 0; i < runs; i++) {
            final long t0 = System.nanoTime();
            final GameSnapshot snap = new GameSnapshot(game);
            snap.makeCopy();
            final long dt = System.nanoTime() - t0;
            totalNs += dt;
            maxNs = Math.max(maxNs, dt);
        }
        final long avgMs = (totalNs / runs) / 1_000_000L;
        final long maxMs = maxNs / 1_000_000L;
        System.out.println("DS4 snapshot cost (large board ~80 creatures + 48 lands): avg="
                + avgMs + "ms max=" + maxMs + "ms over " + runs + " runs");
        // Sanity: copy should finish in well under a few seconds on CI.
        Assert.assertTrue(maxMs < 5000L, "snapshot copy too slow: " + maxMs + "ms");
    }

    private static Player oppOrOther(final Game game, final Player p) {
        for (final Player o : game.getPlayers()) {
            if (o != p) {
                return o;
            }
        }
        return p;
    }
}
