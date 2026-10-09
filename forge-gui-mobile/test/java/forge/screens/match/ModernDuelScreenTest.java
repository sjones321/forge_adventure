package forge.screens.match;

import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.trackable.Tracker;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Headless DS1 coverage: per-match preference gating, drop classification,
 * pickup rules, combat drag guards, gesture policy, and pad helpers.
 */
public class ModernDuelScreenTest {

    @BeforeMethod
    public void resetModernController() {
        ModernDuelController.get().reset();
    }

    @Test
    public void autoOnOnlyForAdventureAscendantDuel() {
        Assert.assertTrue(ModernDuelScreen.resolve("Auto", true));
        Assert.assertTrue(ModernDuelScreen.resolve(null, true));
        Assert.assertFalse(ModernDuelScreen.resolve("Auto", false));
        Assert.assertFalse(ModernDuelScreen.resolve(null, false));
    }

    @Test
    public void alwaysAndNeverOverrideMatchContext() {
        Assert.assertTrue(ModernDuelScreen.resolve("Always", false));
        Assert.assertTrue(ModernDuelScreen.resolve("always", true));
        Assert.assertFalse(ModernDuelScreen.resolve("Never", true));
        Assert.assertFalse(ModernDuelScreen.resolve("never", false));
    }

    @Test
    public void preferenceDefaultIsAuto() {
        Assert.assertEquals(FPref.UI_MODERN_DUEL_SCREEN.getDefault(), "Auto");
    }

    @Test
    public void invalidateIsSafeWithoutGui() {
        ModernDuelScreen.invalidate();
        ModernDuelScreen.invalidate();
    }

    @Test
    public void noPromptMeansNoCombatAction() {
        // Phase alone is insufficient — without InputAttack/InputBlock, combatPrompt is NONE.
        Assert.assertEquals(
                ModernDuelActions.combatPrompt(false, false, false),
                ModernDuelActions.CombatPrompt.NONE);
        final PlayerView foe = opponentPlayer();
        final CardView creature = zoneCard(ZoneType.Battlefield, 1);
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                creature, false, foe, true, false,
                ModernDuelActions.combatPrompt(false, false, false), false, true);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
        Assert.assertEquals(
                ModernDuelActions.combatPrompt(false, true, false),
                ModernDuelActions.CombatPrompt.DECLARE_ATTACKERS);
        Assert.assertEquals(
                ModernDuelActions.combatPrompt(false, false, true),
                ModernDuelActions.CombatPrompt.DECLARE_BLOCKERS);
        Assert.assertEquals(
                ModernDuelActions.combatPrompt(true, true, false),
                ModernDuelActions.CombatPrompt.NONE);
    }

    @Test
    public void handDropOnBoardIsCast() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand, 1), true, null, true, false,
                ModernDuelActions.CombatPrompt.NONE, false, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.CAST);
    }

    @Test
    public void handDropOffBoardCancels() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand, 1), true, null, false, false,
                ModernDuelActions.CombatPrompt.NONE, false, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
    }

    @Test
    public void handDropInHandIsReorder() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand, 1), true, null, false, true,
                ModernDuelActions.CombatPrompt.NONE, false, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.REORDER);
    }

    @Test
    public void permanentDragOutsideCombatIsNoop() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Battlefield, 1), false, "someone", true, false,
                ModernDuelActions.CombatPrompt.NONE, false, true);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
    }

    @Test
    public void aFallsThroughToStockWhenNoDragPossible() {
        // Outside declare prompts, battlefield pickup is refused → stock tap.
        Assert.assertFalse(ModernDuelActions.canPickup(
                false, true, ModernDuelActions.CombatPrompt.NONE, false, false));
        // Selection prompt: never pick up.
        Assert.assertFalse(ModernDuelActions.canPickup(
                true, false, ModernDuelActions.CombatPrompt.NONE, true, true));
        // Hand with nothing castable → stock A.
        Assert.assertFalse(ModernDuelActions.canPickup(
                true, false, ModernDuelActions.CombatPrompt.NONE, false, false));
        // Hand castable → pickup.
        Assert.assertTrue(ModernDuelActions.canPickup(
                true, false, ModernDuelActions.CombatPrompt.NONE, false, true));
        // Local creature during declare attackers.
        Assert.assertTrue(ModernDuelActions.canPickup(
                false, true, ModernDuelActions.CombatPrompt.DECLARE_ATTACKERS, false, false));
    }

    @Test
    public void pressWithoutHoldDoesNotPeek() {
        Assert.assertFalse(ModernDuelGestures.shouldPeekOnPress());
        Assert.assertTrue(ModernDuelGestures.shouldPeekOnLongPress(true, false));
        Assert.assertFalse(ModernDuelGestures.shouldPeekOnLongPress(true, true));
        Assert.assertFalse(ModernDuelGestures.shouldPeekOnLongPress(false, false));
        // Horizontal pan in hand is not consumed (scrolls).
        Assert.assertFalse(ModernDuelGestures.shouldConsumeHandPan(
                false, false, 20f, 2f, false));
        // Upward motion may be consumed for cast drag.
        Assert.assertTrue(ModernDuelGestures.shouldConsumeHandPan(
                false, false, 2f, -20f, false));
    }

    @Test
    public void touchIgnoresStalePadFocus() {
        ModernDuelController.get().reset();
        Assert.assertFalse(ModernDuelController.get().isTouchInputActive());
        Assert.assertFalse(ModernDuelController.get().isPadInputSeen());
        // No amber arrow at match start before any pad input.
        Assert.assertFalse(ModernDuelController.get().shouldDrawPadFocusArrow());
        Assert.assertFalse(ModernDuelGestures.shouldDrawPadFocusArrow(false, false));
        ModernDuelController.get().markPadInput();
        Assert.assertTrue(ModernDuelController.get().isPadInputSeen());
        Assert.assertTrue(ModernDuelController.get().shouldDrawPadFocusArrow());
        ModernDuelController.get().markTouchInput();
        Assert.assertTrue(ModernDuelController.get().isTouchInputActive());
        Assert.assertFalse(ModernDuelController.get().shouldDrawPadFocusArrow());
        Assert.assertFalse(ModernDuelGestures.shouldDrawPadFocusArrow(true, true));
        ModernDuelController.get().reset();
        Assert.assertFalse(ModernDuelController.get().isPadInputSeen());
        Assert.assertFalse(ModernDuelController.get().shouldDrawPadFocusArrow());
    }

    @Test
    public void noAmberArrowAtMatchStartBeforeInput() {
        ModernDuelController.get().reset();
        Assert.assertFalse(ModernDuelGestures.shouldDrawPadFocusArrow(false, false));
        Assert.assertFalse(ModernDuelController.get().shouldDrawPadFocusArrow());
        Assert.assertNull(ModernDuelController.get().getHeldCard());
    }

    @Test
    public void handZoomOnDoubleTap() {
        Assert.assertTrue(ModernDuelGestures.shouldZoomOnDoubleTap(true, 2));
        Assert.assertTrue(ModernDuelGestures.shouldZoomOnDoubleTap(true, 3));
        Assert.assertFalse(ModernDuelGestures.shouldZoomOnDoubleTap(true, 1));
        Assert.assertFalse(ModernDuelGestures.shouldZoomOnDoubleTap(false, 2));
    }

    @Test
    public void firstTapOfDoubleTapIsDeferredNotActed() {
        // Modern first tap is deferred; second tap cancels it and zooms instead.
        Assert.assertTrue(ModernDuelGestures.shouldDeferSingleTap(true, 1));
        Assert.assertFalse(ModernDuelGestures.shouldDeferSingleTap(true, 2));
        Assert.assertFalse(ModernDuelGestures.shouldDeferSingleTap(false, 1),
                "stock must act on first tap immediately");
        Assert.assertTrue(ModernDuelGestures.shouldCancelDeferredSingleTap(true, 2));
        Assert.assertTrue(ModernDuelGestures.shouldCancelDeferredSingleTap(true, 3));
        Assert.assertFalse(ModernDuelGestures.shouldCancelDeferredSingleTap(true, 1));
        Assert.assertFalse(ModernDuelGestures.shouldCancelDeferredSingleTap(false, 2));
        Assert.assertEquals(ModernDuelGestures.DOUBLE_TAP_WINDOW_SEC, 0.25f, 0.0001f);
    }

    @Test
    public void handHeldSecondARestoresControllerReorder() {
        // Different hand card → reorder (restores controller path broken by one-press cast).
        Assert.assertEquals(
                ModernDuelActions.handHeldSecondA(true, true, false, false),
                ModernDuelActions.HandHeldSecondA.REORDER);
        // Same hand card again → confirm cast.
        Assert.assertEquals(
                ModernDuelActions.handHeldSecondA(true, true, true, false),
                ModernDuelActions.HandHeldSecondA.CAST);
        // Aim at board / non-hand → cast.
        Assert.assertEquals(
                ModernDuelActions.handHeldSecondA(true, false, false, false),
                ModernDuelActions.HandHeldSecondA.CAST);
        // Missing focus → cancel.
        Assert.assertEquals(
                ModernDuelActions.handHeldSecondA(true, false, false, true),
                ModernDuelActions.HandHeldSecondA.CANCEL);
        // Battlefield hold still aims/casts on second A (not reorder).
        Assert.assertEquals(
                ModernDuelActions.handHeldSecondA(false, false, false, false),
                ModernDuelActions.HandHeldSecondA.CAST);
    }

    @Test
    public void isHandCastableFromViewsNeverNeedsGameController() {
        // Wrong zone / null → false without touching IGameController.
        Assert.assertFalse(ModernDuelActions.isHandCastableFromViews(null, null));
        Assert.assertFalse(ModernDuelActions.isHandCastableFromViews(
                zoneCard(ZoneType.Battlefield, 9), null));
        // Fresh hand CardView has no land type / mana cost → not castable (still no network).
        Assert.assertFalse(ModernDuelActions.isHandCastableFromViews(
                zoneCard(ZoneType.Hand, 10), null));
        // Heuristic pieces used by the view path.
        Assert.assertTrue(ModernDuelActions.estimateSpellCastable(0, 0));
        Assert.assertTrue(ModernDuelActions.estimateSpellCastable(2, 3));
        Assert.assertFalse(ModernDuelActions.estimateSpellCastable(3, 2));
        Assert.assertFalse(ModernDuelActions.canPlayLandFromViews(null));
        // Fresh PlayerView defaults MaxLandPlay=0 until the engine updates the view.
        final PlayerView p = new PlayerView(50, new Tracker());
        Assert.assertFalse(ModernDuelActions.canPlayLandFromViews(p));
    }

    @Test
    public void castabilityCountsUntappedLandsAsManaSources() {
        // Pool alone is not enough for CMC 3 when pool=1; +2 untapped lands → castable.
        Assert.assertEquals(ModernDuelActions.availableManaEstimate(1, 2), 3);
        Assert.assertTrue(ModernDuelActions.estimateSpellCastable(
                3, ModernDuelActions.availableManaEstimate(1, 2)));
        Assert.assertFalse(ModernDuelActions.estimateSpellCastable(
                3, ModernDuelActions.availableManaEstimate(1, 0)),
                "pool-only must not overstate castability");
        Assert.assertEquals(ModernDuelActions.availableManaEstimate(0, 0), 0);
        Assert.assertEquals(ModernDuelActions.availableManaEstimate(-1, 2), 2,
                "negative pool clamped");

        final Tracker tracker = new Tracker();
        final PlayerView controller = new PlayerView(60, tracker);
        final CardView untappedLand = battlefieldLand(tracker, 61, false);
        final CardView tappedLand = battlefieldLand(tracker, 62, true);
        final CardView creature = zoneCard(ZoneType.Battlefield, 63);
        controller.set(forge.trackable.TrackableProperty.Battlefield,
                new forge.util.collect.FCollection<>(java.util.List.of(
                        untappedLand, tappedLand, creature)));
        Assert.assertEquals(ModernDuelActions.countUntappedLands(controller), 1,
                "only untapped lands count as available mana sources");
        Assert.assertEquals(ModernDuelActions.countUntappedLands(null), 0);
        Assert.assertEquals(ModernDuelActions.availableManaEstimate(controller), 1);
    }

    @Test
    public void resetClearsCombatDeclareFlags() {
        MatchController.instance.setCombatDeclareInput(true, false);
        Assert.assertTrue(MatchController.instance.isCombatDeclareAttackersInput());
        ModernDuelController.get().reset();
        Assert.assertFalse(MatchController.instance.isCombatDeclareAttackersInput());
        Assert.assertFalse(MatchController.instance.isCombatDeclareBlockersInput());
        MatchController.instance.setCombatDeclareInput(false, true);
        Assert.assertTrue(MatchController.instance.isCombatDeclareBlockersInput());
        ModernDuelController.get().reset();
        Assert.assertFalse(MatchController.instance.isCombatDeclareBlockersInput());
    }

    @Test
    public void combatDragRefusedOutsideDeclarePrompts() {
        final PlayerView foe = opponentPlayer();
        final CardView creature = zoneCard(ZoneType.Battlefield, 1);
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                creature, false, foe, true, false,
                ModernDuelActions.CombatPrompt.NONE, false, true);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
    }

    @Test
    public void combatDragRefusedForOpponentCreatures() {
        final PlayerView foe = opponentPlayer();
        final CardView enemy = zoneCard(ZoneType.Battlefield, 2);
        final ModernDuelActions.Decision attack = ModernDuelActions.decide(
                enemy, false, foe, true, false,
                ModernDuelActions.CombatPrompt.DECLARE_ATTACKERS, false, false);
        Assert.assertEquals(attack.kind, ModernDuelActions.Kind.NONE);
        Assert.assertFalse(ModernDuelActions.canPickup(
                false, false, ModernDuelActions.CombatPrompt.DECLARE_ATTACKERS, false, false));
    }

    @Test
    public void combatDragAllowedForLocalCreatureInDeclareAttackers() {
        final Tracker tracker = new Tracker();
        final PlayerView local = new PlayerView(100, tracker);
        final PlayerView foe = new PlayerView(101, tracker);
        final CardView creature = new CardView(3, tracker) {
            @Override
            public ZoneType getZone() {
                return ZoneType.Battlefield;
            }

            @Override
            public PlayerView getController() {
                return local;
            }
        };
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                creature, false, foe, true, false,
                ModernDuelActions.CombatPrompt.DECLARE_ATTACKERS, false, true);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.ATTACK);
        Assert.assertSame(d.target, foe);
    }

    @Test
    public void noAttackerToggleOffOnSameDefender() {
        final Tracker tracker = new Tracker();
        final PlayerView foe = new PlayerView(20, tracker);
        final CardView attacker = zoneCard(ZoneType.Battlefield, 4);
        final CombatView combat = new CombatView(tracker);
        combat.addAttackingBand(java.util.List.of(attacker), foe, null, null);
        Assert.assertTrue(ModernDuelActions.wouldToggleOffAttacker(attacker, foe, combat));
        final PlayerView other = new PlayerView(21, tracker);
        Assert.assertFalse(ModernDuelActions.wouldToggleOffAttacker(attacker, other, combat));
        Assert.assertFalse(ModernDuelActions.wouldToggleOffAttacker(attacker, foe, null));
    }

    @Test
    public void noBlockToggleOffOnSameAttacker() {
        final Tracker tracker = new Tracker();
        final PlayerView foe = new PlayerView(30, tracker);
        final CardView attacker = zoneCard(ZoneType.Battlefield, 5);
        final CardView blocker = zoneCard(ZoneType.Battlefield, 6);
        final CombatView combat = new CombatView(tracker);
        combat.addAttackingBand(java.util.List.of(attacker), foe, java.util.List.of(blocker), java.util.List.of(blocker));
        Assert.assertTrue(ModernDuelActions.wouldToggleOffBlocker(blocker, attacker, combat));
        final CardView otherAttacker = zoneCard(ZoneType.Battlefield, 7);
        combat.addAttackingBand(java.util.List.of(otherAttacker), foe, null, null);
        Assert.assertFalse(ModernDuelActions.wouldToggleOffBlocker(blocker, otherAttacker, combat));
        Assert.assertFalse(ModernDuelActions.wouldToggleOffBlocker(blocker, attacker, null));
    }

    @Test
    public void padXPriorityHandThenManaThenPhase() {
        Assert.assertEquals(ModernDuelPad.chooseXTarget(true, true), ModernDuelPad.Focus.PEEK);
        Assert.assertEquals(ModernDuelPad.chooseXTarget(true, false), ModernDuelPad.Focus.PEEK);
        Assert.assertEquals(ModernDuelPad.chooseXTarget(false, true), ModernDuelPad.Focus.MANA);
        Assert.assertEquals(ModernDuelPad.chooseXTarget(false, false), ModernDuelPad.Focus.PHASE);
    }

    @Test
    public void padCycleWraps() {
        Assert.assertEquals(ModernDuelPad.cycle(0, 6, -1), 5);
        Assert.assertEquals(ModernDuelPad.cycle(5, 6, 1), 0);
        Assert.assertEquals(ModernDuelPad.cycle(2, 6, 1), 3);
        Assert.assertEquals(ModernDuelPad.cycle(0, 0, 1), 0);
    }

    @Test
    public void padReorderIndexForControllerDrop() {
        Assert.assertEquals(ModernDuelPad.reorderIndex(3, 0, 5), 0);
        Assert.assertEquals(ModernDuelPad.reorderIndex(0, 4, 5), 4);
        Assert.assertEquals(ModernDuelPad.reorderIndex(2, 2, 5), -1);
        Assert.assertEquals(ModernDuelPad.reorderIndex(-1, 1, 5), -1);
    }

    @Test
    public void padStartsIdle() {
        ModernDuelController.get().reset();
        Assert.assertEquals(ModernDuelController.get().getPadFocus(), ModernDuelPad.Focus.NONE);
        Assert.assertNull(ModernDuelController.get().getHeldCard());
        Assert.assertFalse(ModernDuelController.get().isBusy());
        Assert.assertFalse(ModernDuelController.get().isDragActive());
        Assert.assertFalse(ModernDuelController.get().isPeeking());
    }

    private static PlayerView opponentPlayer() {
        return new PlayerView(101, new Tracker());
    }

    private static CardView zoneCard(final ZoneType zone, final int id) {
        return new CardView(id, new Tracker()) {
            @Override
            public ZoneType getZone() {
                return zone;
            }
        };
    }

    private static CardView battlefieldLand(final Tracker tracker, final int id, final boolean tapped) {
        final CardView land = new CardView(id, tracker) {
            @Override
            public ZoneType getZone() {
                return ZoneType.Battlefield;
            }
        };
        land.set(forge.trackable.TrackableProperty.Tapped, tapped);
        land.getCurrentState().set(forge.trackable.TrackableProperty.Type,
                forge.card.CardType.parse("Basic Land — Forest", true));
        return land;
    }
}
