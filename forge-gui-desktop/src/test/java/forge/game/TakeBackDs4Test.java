package forge.game;

import com.google.common.collect.Lists;
import forge.ai.AITest;
import forge.ai.ComputerUtilMana;
import forge.ai.LobbyPlayerAi;
import com.google.common.eventbus.Subscribe;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.deck.Deck;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.player.PlaySpellAbility;
import forge.game.player.RegisteredPlayer;
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
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DS4 Wren rounds 3–4: land + spell only; stack restore by id; storm exact;
 * H-A / M3 / top-revealed / random-discard-cost; tests go through
 * {@link PlayerControllerHuman} take-back gate.
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
            // Real latch wait so InputPassPriority.showAndWait blocks the game-loop thread (M3).
            try {
                Mockito.doAnswer(inv -> {
                    final CountDownLatch latch = inv.getArgument(0);
                    latch.await();
                    return null;
                }).when(gui).awaitInput(Mockito.any());
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
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
                // M3: pending take-back after InputPassPriority.stop() from the GUI path.
                if (getGame().hasPendingTakeBack(getPlayer())) {
                    return null;
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

        /** Auto-pay from the mana pool so cost-path tests (Sonic Burst) can finish. */
        @Override
        public boolean payManaCost(final ManaCost toPay, final CostPartMana costPartMana, final SpellAbility sa,
                final String prompt, final ManaConversionMatrix matrix, final boolean effect) {
            return ComputerUtilMana.payManaCost(new Cost(toPay, effect), getPlayer(), sa, effect);
        }

        @Override
        public boolean chooseTargetsFor(final SpellAbility currentAbility) {
            if (!currentAbility.usesTargeting()) {
                return true;
            }
            if (!currentAbility.getTargets().isEmpty()) {
                return true;
            }
            final Player opp = getPlayer().getOpponents().get(0);
            if (currentAbility.canTarget(opp)) {
                currentAbility.getTargets().add(opp);
                return true;
            }
            return false;
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
        // Library-top MayPlay (Courser) can require the unfiltered scan.
        for (final SpellAbility sa : land.getAllPossibleAbilities(p, false)) {
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

    private void addFloatingMana(final Player p, final byte color, final int amount) {
        final Card src = createCard("Mountain", p);
        for (int i = 0; i < amount; i++) {
            p.getManaPool().addMana(new Mana(color, src, null, p));
        }
    }

    private void addFloatingMana(final Player p, final int colorAtom, final int amount) {
        addFloatingMana(p, (byte) colorAtom, amount);
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
        // Prowess triggers on noncreature spells — Pact of the Titan is an Instant.
        final Card pact = addCardToZone("Pact of the Titan", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final int powerBefore = spear.getNetPower();
        final ScriptedPch ctrl = pch(p);
        final SpellAbility cast = spellAbility(pact, p);
        Assert.assertNotNull(cast);
        ctrl.queue(cast);
        ctrl.whilePriority(() -> {
            Assert.assertTrue(tryTakeBackOnGameThread(ctrl));
            Assert.assertEquals(countCardsWithName(game, "Pact of the Titan", ZoneType.Hand), 1);
            Assert.assertTrue(game.getStack().isEmpty(), "stack must be empty after take-back");
            Assert.assertTrue(game.getStack().getSimultaneousStackEntries().isEmpty(),
                    "simultaneous prowess must not linger after take-back");
            Assert.assertEquals(spear.getNetPower(), powerBefore,
                    "C2: prowess power must be exact (not re-fired)");
        });
        Assert.assertTrue(driveUntil(game, () -> countCardsWithName(game, "Pact of the Titan", ZoneType.Hand) == 1));
        Assert.assertTrue(game.getStack().isEmpty());
        Assert.assertTrue(game.getStack().getSimultaneousStackEntries().isEmpty());
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

    /** H-A: spell then activated ability must invalidate the prior spell snapshot. */
    @Test
    public void abilityAfterSpellInvalidatesTakeBack() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        addCards("Plains", 3, p);
        final Card memnite = addCardToZone("Memnite", p, ZoneType.Hand);
        final Card herald = addCard("Herald of Anafenza", p);
        herald.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final ScriptedPch ctrl = pch(p);
        final SpellAbility cast = spellAbility(memnite, p);
        final SpellAbility outlast = findSAWithPrefix(herald, "Outlast");
        Assert.assertNotNull(cast);
        Assert.assertNotNull(outlast);
        ctrl.queue(cast);
        ctrl.queue(outlast);
        final AtomicBoolean afterAbility = new AtomicBoolean(false);
        ctrl.whilePriority(() -> {
            // After ability activation: prior spell snapshot must be gone.
            afterAbility.set(true);
            Assert.assertFalse(game.canTakeBack(p), "H-A: ability after spell must invalidate take-back");
            Assert.assertFalse(ctrl.canTakeBackLastAction());
        });
        Assert.assertTrue(driveUntil(game, afterAbility::get));
        Assert.assertFalse(game.canTakeBack(p));
    }

    /** Play-with-top-revealed: Courser land from library top bumps the epoch. */
    @Test
    public void playLandFromLibraryTopBumpsEpoch() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        addCard("Courser of Kruphix", p);
        final Card forest = createCard("Forest", p);
        forest.setGameTimestamp(game.getNextTimestamp());
        // Index 0 is top of library (TopLibrary property).
        p.getZone(ZoneType.Library).add(forest, 0);
        Assert.assertEquals(p.getCardsIn(ZoneType.Library).get(0), forest);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        Assert.assertTrue(game.captureTakeBackSnapshot(p));
        final long epochBefore = game.getInformationEpoch();
        final ScriptedPch ctrl = pch(p);
        final SpellAbility playTop = landAbility(forest, p);
        Assert.assertNotNull(playTop, "Courser must allow playing the top land");
        Assert.assertTrue(PlaySpellAbility.playSpellAbility(ctrl, p, playTop));
        Assert.assertTrue(game.getInformationEpoch() > epochBefore,
                "playing from library top must bump information epoch");
        Assert.assertFalse(game.canTakeBack(p), "top-revealed play must clear take-back");
        Assert.assertTrue(forest.isInZone(ZoneType.Battlefield),
                "Courser MayPlay should put the top land onto the battlefield");
    }

    /** Random discard as a COST (Sonic Burst) bumps the epoch. */
    @Test
    public void randomDiscardCostBumpsEpoch() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        fillLibrary(p, 8);
        fillLibrary(opp, 8);
        final Card burst = addCardToZone("Sonic Burst", p, ZoneType.Hand);
        addCardToZone("Memnite", p, ZoneType.Hand); // discard fodder
        addFloatingMana(p, ManaAtom.RED, 1);
        addFloatingMana(p, ManaAtom.COLORLESS, 1);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        Assert.assertTrue(game.captureTakeBackSnapshot(p));
        final long epochBefore = game.getInformationEpoch();
        final ScriptedPch ctrl = pch(p);
        final SpellAbility cast = spellAbility(burst, p);
        Assert.assertNotNull(cast);
        ctrl.queue(cast);
        final AtomicBoolean done = new AtomicBoolean(false);
        ctrl.whilePriority(() -> {
            done.set(true);
            Assert.assertTrue(game.getInformationEpoch() > epochBefore,
                    "random discard cost must bump information epoch");
            Assert.assertFalse(game.canTakeBack(p), "random discard cost must clear take-back");
        });
        Assert.assertTrue(driveUntil(game, done::get));
        Assert.assertTrue(game.getInformationEpoch() > epochBefore);
        Assert.assertFalse(game.canTakeBack(p));
    }

    /** M3: restore runs on the game-loop thread; clicks during restore are rejected. */
    @Test
    public void takeBackRestoreRunsOnGameLoopThreadAndRejectsClicks() throws Exception {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        final Card land = addCardToZone("Forest", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        Assert.assertTrue(game.captureTakeBackSnapshot(p));
        Assert.assertTrue(PlaySpellAbility.playSpellAbility(pch(p), p, landAbility(land, p)));

        final ScriptedPch ctrl = pch(p);
        final AtomicReference<String> restoreThread = new AtomicReference<>();
        final AtomicBoolean clickAccepted = new AtomicBoolean(false);
        final CountDownLatch inRestore = new CountDownLatch(1);
        final CountDownLatch clickDone = new CountDownLatch(1);

        final GameSnapshot real = new GameSnapshot(game);
        real.makeCopy();
        final GameSnapshot instrumented = new GameSnapshot(game) {
            @Override
            public void restoreGameState(final Game currentGame) {
                restoreThread.set(Thread.currentThread().getName());
                Assert.assertTrue(currentGame.isTakeBackInProgress());
                inRestore.countDown();
                try {
                    Assert.assertTrue(clickDone.await(3, TimeUnit.SECONDS), "click probe timed out");
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Assert.fail("interrupted");
                }
                real.restoreGameState(currentGame);
            }
        };
        // Point the instrumented wrapper at the same copied board as `real`.
        final java.lang.reflect.Field ng = GameSnapshot.class.getDeclaredField("newGame");
        ng.setAccessible(true);
        ng.set(instrumented, ng.get(real));
        final java.lang.reflect.Field snap = Game.class.getDeclaredField("takeBackSnapshot");
        snap.setAccessible(true);
        final java.lang.reflect.Field owner = Game.class.getDeclaredField("takeBackOwner");
        owner.setAccessible(true);
        final java.lang.reflect.Field epoch = Game.class.getDeclaredField("takeBackEpoch");
        epoch.setAccessible(true);
        snap.set(game, instrumented);
        owner.set(game, p);
        epoch.set(game, game.getInformationEpoch());

        final CountDownLatch loopReady = new CountDownLatch(1);
        final CountDownLatch loopDone = new CountDownLatch(1);
        final AtomicReference<Throwable> loopError = new AtomicReference<>();
        final Thread loop = new Thread(() -> {
            try {
                final InputPassPriority ipp = new InputPassPriority(ctrl) {
                    @Override
                    public void showAndWait() {
                        // Signal only once the latch wait is about to block.
                        getController().getInputQueue().setInput(this);
                        loopReady.countDown();
                        awaitLatchRelease();
                    }
                };
                ipp.showMessageInitial();
                ipp.showAndWait(); // released by takeBackLastAction → stop()
                // PhaseHandler equivalent: consume pending on the loop thread.
                Assert.assertTrue(game.hasPendingTakeBack(p), "pending take-back after input release");
                ctrl.resolvePendingTakeBack();
            } catch (final Throwable t) {
                loopError.set(t);
            } finally {
                loopDone.countDown();
            }
        }, "Game-loop");
        loop.start();
        Assert.assertTrue(loopReady.await(3, TimeUnit.SECONDS));

        // GUI-thread request: sets pending + stops input (no GameAction.invoke).
        final Thread gui = new Thread(ctrl::takeBackLastAction, "EDT-fake");
        gui.start();
        gui.join(3000);

        Assert.assertTrue(inRestore.await(3, TimeUnit.SECONDS), "restore did not start");
        // Probe click while restore is in progress. Accepted OK would stop() and remove the input.
        final InputPassPriority probe = new InputPassPriority(ctrl);
        ctrl.getInputQueue().setInput(probe);
        probe.showMessageInitial();
        ctrl.selectButtonOk();
        Assert.assertSame(ctrl.getInputQueue().getInput(), probe,
                "OK click must be rejected during take-back restore (input must stay)");
        clickAccepted.set(ctrl.getInputQueue().getInput() != probe);
        clickDone.countDown();

        Assert.assertTrue(loopDone.await(5, TimeUnit.SECONDS));
        if (loopError.get() != null) {
            throw new AssertionError(loopError.get());
        }
        Assert.assertNotNull(restoreThread.get());
        Assert.assertTrue(restoreThread.get().startsWith("Game"),
                "restore must run on game-loop thread, was: " + restoreThread.get());
        Assert.assertFalse(game.isTakeBackInProgress());
    }

    /** pushForRestore keeps multi-entry order and does not fire cast events. */
    @Test
    public void pushForRestorePreservesOrderWithoutCastEvent() {
        final Game game = enableTakeBackHuman();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        final Card a = addCard("Memnite", p);
        final Card b = addCard("Grizzly Bears", opp);
        final SpellAbility saA = new AbilityStatic(a, Cost.Zero, null) {
            @Override public void resolve() { }
        };
        final SpellAbility saB = new AbilityStatic(b, Cost.Zero, null) {
            @Override public void resolve() { }
        };
        saA.setActivatingPlayer(p);
        saB.setActivatingPlayer(opp);

        final AtomicBoolean castEvent = new AtomicBoolean(false);
        final Object subscriber = new Object() {
            @Subscribe
            public void onCast(final GameEventSpellAbilityCast event) {
                castEvent.set(true);
            }
        };
        game.subscribeToEvents(subscriber);

        // Snapshot order top→bottom when iterating: first pushed-for-restore with addLast is top.
        game.getStack().pushForRestore(saA, 1001);
        game.getStack().pushForRestore(saB, 1002);
        Assert.assertFalse(castEvent.get(), "pushForRestore must not fire GameEventSpellAbilityCast");

        final Iterator<SpellAbilityStackInstance> it = game.getStack().iterator();
        Assert.assertTrue(it.hasNext());
        Assert.assertEquals(it.next().getId(), 1001, "first restored entry stays on top");
        Assert.assertTrue(it.hasNext());
        Assert.assertEquals(it.next().getId(), 1002, "second restored entry stays below");
        Assert.assertFalse(it.hasNext());
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

        // H2: controller gate must concede on CATASTROPHIC via this.concede(), and release input.
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
        }
        Assert.assertTrue(p.hasLost() || game.isGameOver(),
                "H2: CATASTROPHIC must concede via PlayerControllerHuman.concede()");
        // concede() → onGameOver releases human input queues.
        Assert.assertNull(ctrl.getInputQueue().getInput(),
                "CATASTROPHIC concede must release InputPassPriority");
    }

    @Test
    public void snapshotCostTimesCaptureAndFullTakeBack() {
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
        long captureTotalNs = 0L;
        long captureMaxNs = 0L;
        long takeBackTotalNs = 0L;
        long takeBackMaxNs = 0L;
        for (int i = 0; i < runs; i++) {
            final long c0 = System.nanoTime();
            Assert.assertTrue(game.captureTakeBackSnapshot(p));
            final long cdt = System.nanoTime() - c0;
            captureTotalNs += cdt;
            captureMaxNs = Math.max(captureMaxNs, cdt);

            Assert.assertTrue(PlaySpellAbility.playSpellAbility(pch(p), p, landAbility(land, p)));
            final long t0 = System.nanoTime();
            Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
            final long dt = System.nanoTime() - t0;
            takeBackTotalNs += dt;
            takeBackMaxNs = Math.max(takeBackMaxNs, dt);
        }
        final long captureAvgMs = (captureTotalNs / runs) / 1_000_000L;
        final long captureMaxMs = captureMaxNs / 1_000_000L;
        final long takeBackAvgMs = (takeBackTotalNs / runs) / 1_000_000L;
        final long takeBackMaxMs = takeBackMaxNs / 1_000_000L;
        System.out.println("DS4 captureTakeBackSnapshot cost (large board ~80 creatures + 48 lands): avg="
                + captureAvgMs + "ms max=" + captureMaxMs + "ms over " + runs + " runs");
        System.out.println("DS4 full takeBack cost (backup+restore, large board ~80 creatures + 48 lands): avg="
                + takeBackAvgMs + "ms max=" + takeBackMaxMs + "ms over " + runs + " runs");
        Assert.assertTrue(captureMaxMs < 5000L, "captureTakeBackSnapshot too slow: " + captureMaxMs + "ms");
        Assert.assertTrue(takeBackMaxMs < 5000L, "full takeBack too slow: " + takeBackMaxMs + "ms");
    }
}
