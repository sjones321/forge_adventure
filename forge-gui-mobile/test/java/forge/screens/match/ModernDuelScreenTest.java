package forge.screens.match;

import forge.game.card.CardView;
import forge.game.phase.PhaseType;
import forge.game.zone.ZoneType;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.trackable.Tracker;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Headless DS1 coverage: preference gating and drop classification.
 * Stock path stays off when preference is Never / Auto without Ascendant.
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
                PhaseType.MAIN1, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.CAST);
    }

    @Test
    public void handDropOffBoardCancels() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand), true, null, false, false,
                PhaseType.MAIN1, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
    }

    @Test
    public void handDropInHandIsReorder() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Hand), true, null, false, true,
                PhaseType.MAIN1, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.REORDER);
    }

    @Test
    public void permanentDragOutsideCombatIsNoop() {
        final ModernDuelActions.Decision d = ModernDuelActions.decide(
                zoneCard(ZoneType.Battlefield), false, "someone", true, false,
                PhaseType.MAIN1, false);
        Assert.assertEquals(d.kind, ModernDuelActions.Kind.NONE);
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
