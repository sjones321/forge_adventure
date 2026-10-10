package forge.game;

import com.google.common.collect.Lists;
import forge.ai.AITest;
import forge.ai.LobbyPlayerAi;
import forge.deck.Deck;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlaySpellAbility;
import forge.game.player.RegisteredPlayer;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.spellability.AbilityStatic;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gui.interfaces.IGuiGame;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.util.ITriggerEvent;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DS4 Wren round 3: land + spell only; stack restore by id; storm exact;
 * tests go through {@link PlayerControllerHuman} take-back gate.
 */
public class TakeBackDs4Test extends AITest {

    /**
     * Human controller that queues priority choices and installs {@link InputPassPriority}
     * so {@link PlayerControllerHuman#canTakeBackLastAction()} is exercisable.
     */
    private static final class ScriptedPch extends PlayerControllerHuman {
        private final Queue<List<SpellAbility>> queued = new ArrayDeque<>();
        private Runnable whilePriority;

        ScriptedPch(final Game game, final Player p, final LobbyPlayerHuman lobby) {
            super(game, p, lobby);
            // Lenient UI stub so InputQueue observers can call setCurrentPlayer / showMessage.
            // Auto-confirm dialogs so spell casts do not block on InputConfirm.showAndWait.
            final IGuiGame gui = Mockito.mock(IGuiGame.class, Mockito.RETURNS_DEFAULTS);
            Mockito.when(gui.isLibgdxPort()).thenReturn(true);
            Mockito.when(gui.confirm(Mockito.any(), Mockito.anyString())).thenReturn(true);
            Mockito.when(gui.confirm(Mockito.any(), Mockito.anyString(), Mockito.anyBoolean(), Mockito.anyList()))
                    .thenReturn(true);
            setGui(gui);
        }

        void queue(final SpellAbility sa) {
            queued.add(Collections.singletonList(sa));
        }

        void whilePriority(final Runnable r) {
            whilePriority = r;
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            final InputPassPriority input = new InputPassPriority(this);
            getInputQueue().setInput(input);
            try {
                if (!queued.isEmpty()) {
                    return queued.poll();
                }
                if (whilePriority != null) {
                    final Runnable r = whilePriority;
                    whilePriority = null;
                    r.run();
                }
                return null;
            } finally {
                if (getInputQueue().getInput() == input) {
                    getInputQueue().removeInput(input);
                }
            }
        }

        @Override
        public boolean playChosenSpellAbility(final SpellAbility sa) {
            return PlaySpellAbility.playSpellAbility(this, getPlayer(), sa);
        }

        /** Skip the GUI ability picker — tests queue the exact SA to play. */
        @Override
        public SpellAbility getAbilityToPlay(final Card hostCard, final List<SpellAbility> abilities,
                final ITriggerEvent triggerEvent) {
            return abilities == null || abilities.isEmpty() ? null : abilities.get(0);
        }

        @Override
        public boolean confirmPayment(final CostPart costPart, final String question, final SpellAbility sa) {
            return true;
        }

        @Override
        public boolean confirmAction(final SpellAbility sa, final PlayerActionConfirmMode mode, final String message,
                final List<String> options, final Card cardToShow, final java.util.Map<String, Object> params) {
            return true;
        }

        @Override
        public List<OptionalCostValue> chooseOptionalCosts(final SpellAbility choosen,
                final List<OptionalCostValue> optionalCost) {
            return Collections.emptyList();
        }

        @Override
        public List<SpellAbility> orderSimultaneousSa(final List<SpellAbility> activePlayerSAs) {
            return activePlayerSAs;
        }
    }

    private static final class HumanLobby extends LobbyPlayerHuman {
        HumanLobby(final String name) {
            super(name);
        }

        @Override
        public Player createIngamePlayer(final Game game, final int id) {
            final Player player = new Player(getName(), game, id);
            final ScriptedPch ctrl = new ScriptedPch(game, player, this);
            player.setFirstController(ctrl);
            return player;
        }
    }

    private Game enableTakeBackHuman() {
        final List<RegisteredPlayer> players = Lists.newArrayList();
        final Deck d1 = new Deck();
        players.add(new RegisteredPlayer(d1).setPlayer(new LobbyPlayerAi("p2", null)));
        players.add(new RegisteredPlayer(d1).setPlayer(new HumanLobby("p1")));
        final GameRules rules = new GameRules(GameType.Constructed);
        final Match match = new Match(rules, players, "Test");
        final Game game = new Game(players, rules, match);
        final Player p = game.getPlayers().get(1);
        game.setAge(GameStage.Play);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getPhaseHandler().onStackResolved();
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = false;
        game.TAKE_BACK_ENABLED = true;
        return game;
    }

    private ScriptedPch pch(final Player p) {
        return (ScriptedPch) p.getController();
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
        for (int i = 0; i < 60 && !game.isGameOver(); i++) {
            game.getPhaseHandler().mainLoopStep();
            if (done.getAsBoolean()) {
                return true;
            }
        }
        return done.getAsBoolean();
    }

    /** {@link PlayerControllerHuman#tryTakeBackLastAction()} requires a Game-* thread name. */
    private static boolean tryTakeBackOnGameThread(final PlayerControllerHuman ctrl) {
        final Thread t = Thread.currentThread();
        final String old = t.getName();
        t.setName("Game-test");
        try {
            return ctrl.tryTakeBackLastAction();
        } finally {
            t.setName(old);
        }
    }

    @Test
    public void takeBackLandRestoresHandAndDrop() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final ScriptedPch ctrl = pch(p);
        ctrl.queue(landAbility(plains, p));
        ctrl.whilePriority(() -> {
            Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));
            Assert.assertTrue(ctrl.canTakeBackLastAction(), "PCH gate must allow take-back");
            Assert.assertTrue(tryTakeBackOnGameThread(ctrl));
            Assert.assertTrue(plains.isInZone(ZoneType.Hand));
            Assert.assertEquals(p.getLandsPlayedThisTurn(), 0);
        });

        Assert.assertTrue(driveUntil(game, () -> plains.isInZone(ZoneType.Hand)
                && p.getLandsPlayedThisTurn() == 0));
    }

    @Test
    public void takeBackSecondSpellKeepsStormCountExact() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        final Card first = addCardToZone("Memnite", p, ZoneType.Hand);
        final Card second = addCardToZone("Memnite", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final ScriptedPch ctrl = pch(p);
        final SpellAbility castFirst = spellAbility(first, p);
        final SpellAbility castSecond = spellAbility(second, p);
        Assert.assertNotNull(castFirst);
        Assert.assertNotNull(castSecond);
        // Cast first Memnite and let it resolve.
        ctrl.queue(castFirst);
        Assert.assertTrue(driveUntil(game, () -> countCardsWithName(game, "Memnite", ZoneType.Battlefield) >= 1));
        Assert.assertEquals(p.getSpellsCastThisTurn(), 1, "first spell counts for storm");

        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        ctrl.queue(castSecond);
        ctrl.whilePriority(() -> {
            Assert.assertEquals(p.getSpellsCastThisTurn(), 2, "second spell on stack bumps storm");
            Assert.assertTrue(ctrl.canTakeBackLastAction());
            Assert.assertTrue(tryTakeBackOnGameThread(ctrl));
            Assert.assertEquals(p.getSpellsCastThisTurn(), 1,
                    "C3: thisTurnCast/storm exact after taking back B");
            Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Hand), 1);
            Assert.assertTrue(game.getStack().isEmpty());
        });
        Assert.assertTrue(driveUntil(game, () -> countCardsWithName(game, "Memnite", ZoneType.Hand) == 1
                && p.getSpellsCastThisTurn() == 1));
    }

    @Test
    public void takeBackSpellPreservesOpponentTriggerOnStack() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        fillLibrary(p, 8);
        fillLibrary(opp, 8);
        // 0-mana instant (not a creature): legal while an opponent trigger is already on the stack.
        final Card pact = addCardToZone("Pact of the Titan", p, ZoneType.Hand);
        final Card swiftspear = addCard("Monastery Swiftspear", opp);
        swiftspear.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        // Simulate an opponent trigger already on the stack (respond-then-take-back).
        final SpellAbility triggerSa = new AbilityStatic(swiftspear, Cost.Zero, null) {
            @Override
            public void resolve() {
                // no-op stand-in for an opponent trigger
            }
        };
        triggerSa.setActivatingPlayer(opp);
        final int triggerId = 424242;
        game.getStack().pushForRestore(triggerSa, triggerId);
        Assert.assertNotNull(game.getStack().getStackInstanceById(triggerId));

        final ScriptedPch ctrl = pch(p);
        final SpellAbility cast = spellAbility(pact, p);
        Assert.assertNotNull(cast, "Pact of the Titan must be castable with a trigger on the stack");
        ctrl.queue(cast);
        ctrl.whilePriority(() -> {
            Assert.assertNotNull(game.getStack().getStackInstanceById(triggerId),
                    "trigger still present after human cast");
            Assert.assertTrue(ctrl.canTakeBackLastAction());
            Assert.assertTrue(tryTakeBackOnGameThread(ctrl));
            final SpellAbilityStackInstance kept = game.getStack().getStackInstanceById(triggerId);
            Assert.assertNotNull(kept, "C1: opponent stack item must survive take-back");
            Assert.assertEquals(countCardsWithName(game, "Pact of the Titan", ZoneType.Hand), 1);
        });
        Assert.assertTrue(driveUntil(game, () -> countCardsWithName(game, "Pact of the Titan", ZoneType.Hand) == 1
                && game.getStack().getStackInstanceById(triggerId) != null));
    }

    @Test
    public void takeBackDoesNotRefireProwess() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        final Card spear = addCard("Monastery Swiftspear", p);
        spear.setSickness(false);
        final Card memnite = addCardToZone("Memnite", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final int powerBefore = spear.getNetPower();
        final ScriptedPch ctrl = pch(p);
        final SpellAbility cast = spellAbility(memnite, p);
        Assert.assertNotNull(cast);
        ctrl.queue(cast);
        ctrl.whilePriority(() -> {
            Assert.assertTrue(tryTakeBackOnGameThread(ctrl));
            Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Hand), 1);
            Assert.assertEquals(spear.getNetPower(), powerBefore,
                    "C2: prowess must not re-fire or stick after take-back");
        });
        Assert.assertTrue(driveUntil(game, () -> countCardsWithName(game, "Memnite", ZoneType.Hand) == 1));
        Assert.assertEquals(spear.getNetPower(), powerBefore);
    }

    @Test
    public void abilityActivationDoesNotOfferTakeBack() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        addCards("Plains", 3, p);
        final Card herald = addCard("Herald of Anafenza", p);
        herald.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final SpellAbility outlast = findSAWithPrefix(herald, "Outlast");
        Assert.assertNotNull(outlast);
        final ScriptedPch ctrl = pch(p);
        ctrl.queue(outlast);
        final AtomicBoolean offered = new AtomicBoolean(false);
        ctrl.whilePriority(() -> {
            offered.set(ctrl.canTakeBackLastAction() || game.canTakeBack(p));
        });
        driveUntil(game, () -> herald.isTapped() || herald.hasCounters() || offered.get());
        Assert.assertFalse(game.canTakeBack(p), "H1: ability activation must not retain a snapshot");
        Assert.assertFalse(offered.get(), "no take-back after ability");
    }

    @Test
    public void catastrophicEndsDuelViaConcede() throws Exception {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Assert.assertTrue(game.captureTakeBackSnapshot(p));

        final GameSnapshot exploding = new GameSnapshot(game) {
            @Override
            public void restoreGameState(final Game currentGame) {
                throw new Error("forced restore failure");
            }
        };
        final java.lang.reflect.Field ng = GameSnapshot.class.getDeclaredField("newGame");
        ng.setAccessible(true);
        ng.set(exploding, game);
        final java.lang.reflect.Field snap = Game.class.getDeclaredField("takeBackSnapshot");
        snap.setAccessible(true);
        final java.lang.reflect.Field owner = Game.class.getDeclaredField("takeBackOwner");
        owner.setAccessible(true);
        final java.lang.reflect.Field epoch = Game.class.getDeclaredField("takeBackEpoch");
        epoch.setAccessible(true);

        snap.set(game, exploding);
        owner.set(game, p);
        epoch.set(game, game.getInformationEpoch());
        // Healthy backup restore → RESTORE_FAILED; proves Error (not only RuntimeException) is caught.
        Assert.assertEquals(game.takeBack(p), TakeBackResult.RESTORE_FAILED);

        // Both restore paths throw → CATASTROPHIC.
        Assert.assertTrue(game.captureTakeBackSnapshot(p));
        snap.set(game, exploding);
        owner.set(game, p);
        epoch.set(game, game.getInformationEpoch());
        game.takeBackBackupOverride = exploding;
        Assert.assertEquals(game.takeBack(p), TakeBackResult.CATASTROPHIC);
        game.takeBackBackupOverride = null;

        // H2: controller gate must concede on CATASTROPHIC via this.concede().
        Assert.assertTrue(game.captureTakeBackSnapshot(p));
        snap.set(game, exploding);
        owner.set(game, p);
        epoch.set(game, game.getInformationEpoch());
        game.takeBackBackupOverride = exploding;
        final ScriptedPch ctrl = pch(p);
        final InputPassPriority ipp = new InputPassPriority(ctrl);
        ctrl.getInputQueue().setInput(ipp);
        try {
            Assert.assertFalse(tryTakeBackOnGameThread(ctrl));
        } finally {
            game.takeBackBackupOverride = null;
            if (ctrl.getInputQueue().getInput() == ipp) {
                ctrl.getInputQueue().removeInput(ipp);
            }
        }
        Assert.assertTrue(p.hasLost() || game.isGameOver(),
                "H2: CATASTROPHIC must concede via PlayerControllerHuman.concede()");
    }

    @Test
    public void snapshotCostTimesFullTakeBack() {
        final Game game = enableTakeBackHuman();
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
        final Card land = addCardToZone("Forest", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        // Warmup
        for (int i = 0; i < 2; i++) {
            Assert.assertTrue(game.captureTakeBackSnapshot(p));
            Assert.assertTrue(PlaySpellAbility.playSpellAbility(pch(p), p, landAbility(land, p)));
            Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
        }

        final int runs = 5;
        long totalNs = 0L;
        long maxNs = 0L;
        for (int i = 0; i < runs; i++) {
            Assert.assertTrue(game.captureTakeBackSnapshot(p));
            Assert.assertTrue(PlaySpellAbility.playSpellAbility(pch(p), p, landAbility(land, p)));
            final long t0 = System.nanoTime();
            Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
            final long dt = System.nanoTime() - t0;
            totalNs += dt;
            maxNs = Math.max(maxNs, dt);
        }
        final long avgMs = (totalNs / runs) / 1_000_000L;
        final long maxMs = maxNs / 1_000_000L;
        System.out.println("DS4 full takeBack cost (backup+restore, large board ~80 creatures + 48 lands): avg="
                + avgMs + "ms max=" + maxMs + "ms over " + runs + " runs");
        Assert.assertTrue(maxMs < 5000L, "full takeBack too slow: " + maxMs + "ms");
    }
}
