package forge.screens.match;

import forge.game.card.CardView;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.trackable.Tracker;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Headless DS1 coverage: preference gating, drop classification, and pad helpers.
 * Stock path stays off when preference is Never / Auto without Ascendant.
 * <p>Avoids {@code PhaseType} enum init (needs Localizer); hand/non-combat
 * decisions only need a null phase.
 */
public class ModernDuelScreenTest {

    @Test
    public void autoDefaultsOnOnlyForAscendant() {
        Assert.assertTrue(ModernDuelScreen.resolve("Auto", true));
        Assert.assertFalse(ModernDuelScreen.resolve("Auto", false));
        Assert.assertFalse(ModernDuelScreen.resolve(null, false));
        Assert.assertTrue(ModernDuelScreen.resolve(null, true));
    }

    @Test
    public void alwaysAndNeverOverrideAscendant() {
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
    public void handDropOnBoardIsCast() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand), true, null, true, false,
                null, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.CAST);
    }

    @Test
    public void handDropOffBoardCancels() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand), true, null, false, false,
                null, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
    }

    @Test
    public void handDropInHandIsReorder() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand), true, null, false, true,
                null, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.REORDER);
    }

    @Test
    public void permanentDragOutsideCombatIsNoop() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Battlefield), false, "someone", true, false,
                null, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
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
    }

    private static CardView zoneCard(final ZoneType zone) {
        return new CardView(1, new Tracker()) {
            @Override
            public ZoneType getZone() {
                return zone;
            }
        };
    }
}
