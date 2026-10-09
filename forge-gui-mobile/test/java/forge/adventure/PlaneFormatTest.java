package forge.adventure;

import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopWorldSync;
import forge.adventure.data.ConfigData;
import forge.adventure.util.Config;
import forge.adventure.util.GymUtil;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneFormat;
import forge.adventure.world.PlaneMeta;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Package K: plane format storage, migration, resolver fallbacks, and co-op
 * {@code mv2SetCode} packing (no protocol bump).
 */
public class PlaneFormatTest {

    @BeforeMethod
    public void clearGuestFollow() {
        CoopSession.get().testClearGuestPlaneFollow();
    }

    @AfterMethod
    public void tearDown() {
        CoopSession.get().testClearGuestPlaneFollow();
    }

    @Test
    public void normalizeMapsBellwardenAliasesToStandard() {
        Assert.assertEquals(PlaneFormat.normalize("Bellwarden Standard"), GymUtil.FORMAT_STANDARD);
        Assert.assertEquals(PlaneFormat.normalize("Bellwarden"), GymUtil.FORMAT_STANDARD);
        Assert.assertEquals(PlaneFormat.normalize("standard"), GymUtil.FORMAT_STANDARD);
        Assert.assertEquals(PlaneFormat.normalize("Pauper"), GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(PlaneFormat.normalize("Historic"), GymUtil.FORMAT_HISTORIC);
        Assert.assertEquals(PlaneFormat.normalize("Commander"), GymUtil.FORMAT_COMMANDER);
    }

    @Test
    public void unsetPlaneUsesConfigDefault() {
        PlaneMeta meta = PlaneMeta.home(1L);
        Assert.assertEquals(PlaneFormat.raw(meta), "");
        Assert.assertEquals(PlaneFormat.resolve(meta), PlaneFormat.defaultFormat());
        Assert.assertEquals(PlaneFormat.defaultFormat(), GymUtil.FORMAT_STANDARD);
    }

    @Test
    public void planeMetaFormatRoundTripsInSave() {
        PlaneMeta meta = new PlaneMeta("set_demo", forge.adventure.world.PlaneKind.SET,
                9L, "world/set_plane_world.json", "Demo");
        meta.setFormat(GymUtil.FORMAT_PAUPER);
        SaveFileData saved = meta.save();
        Assert.assertTrue(saved.containsKey("format"));
        Assert.assertEquals(saved.readString("format"), GymUtil.FORMAT_PAUPER);

        PlaneMeta loaded = new PlaneMeta();
        loaded.load(saved);
        Assert.assertEquals(loaded.getFormat(), GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(PlaneFormat.resolve(loaded), GymUtil.FORMAT_PAUPER);
    }

    @Test
    public void missingFormatKeyLoadsAsUnset() {
        PlaneMeta meta = PlaneMeta.home(2L);
        SaveFileData data = meta.save();
        data.store("format", ""); // explicit empty
        PlaneMeta loaded = new PlaneMeta();
        loaded.load(data);
        Assert.assertEquals(PlaneFormat.raw(loaded), "");
        Assert.assertEquals(PlaneFormat.resolve(loaded), PlaneFormat.defaultFormat());
    }

    @Test
    public void migrateLegacyRunFormatOntoHomeOnlyWhenUnset() {
        MultiverseState multi = new MultiverseState();
        multi.initHomeFromLive(3L, 0f, 0f);
        Assert.assertEquals(PlaneFormat.raw(multi.getMeta(PlaneMeta.HOME_ID)), "");

        PlaneFormat.migrateLegacyRunFormat(multi, GymUtil.FORMAT_HISTORIC);
        Assert.assertEquals(multi.getMeta(PlaneMeta.HOME_ID).getFormat(), GymUtil.FORMAT_HISTORIC);

        // Second migrate must not overwrite a chosen format.
        PlaneFormat.migrateLegacyRunFormat(multi, GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(multi.getMeta(PlaneMeta.HOME_ID).getFormat(), GymUtil.FORMAT_HISTORIC);
    }

    @Test
    public void migrateUnknownLegacyUsesDefault() {
        MultiverseState multi = new MultiverseState();
        multi.initHomeFromLive(4L, 0f, 0f);
        PlaneFormat.migrateLegacyRunFormat(multi, "Vintage");
        Assert.assertEquals(multi.getMeta(PlaneMeta.HOME_ID).getFormat(), PlaneFormat.defaultFormat());
    }

    @Test
    public void guestSyncedFormatWinsInResolver() {
        CoopSession.get().testFollowHostPlane("set_dmu", GymUtil.FORMAT_COMMANDER);
        Assert.assertEquals(PlaneFormat.resolveCurrent(), GymUtil.FORMAT_COMMANDER);
        CoopSession.get().testClearGuestPlaneFollow();
    }

    @Test
    public void mv2WirePackUnpackPreservesSetCodeAndFormat() {
        String wire = CoopWorldSync.packMv2Wire("DMU", GymUtil.FORMAT_HISTORIC);
        Assert.assertEquals(wire, "DMU" + CoopWorldSync.PLANE_FORMAT_WIRE_MARK + GymUtil.FORMAT_HISTORIC);
        Assert.assertEquals(CoopWorldSync.unpackMv2SetCode(wire), "DMU");
        Assert.assertEquals(CoopWorldSync.unpackPlaneFormat(wire), GymUtil.FORMAT_HISTORIC);
    }

    @Test
    public void mv2WireHomePlaneFormatOnly() {
        String wire = CoopWorldSync.packMv2Wire("", GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(wire, CoopWorldSync.PLANE_FORMAT_WIRE_MARK + GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(CoopWorldSync.unpackMv2SetCode(wire), "");
        Assert.assertEquals(CoopWorldSync.unpackPlaneFormat(wire), GymUtil.FORMAT_PAUPER);
    }

    @Test
    public void mv2WireWithoutMarkIsPreKCompatible() {
        Assert.assertEquals(CoopWorldSync.unpackMv2SetCode("ONE"), "ONE");
        Assert.assertEquals(CoopWorldSync.unpackPlaneFormat("ONE"), "");
        Assert.assertEquals(CoopWorldSync.packMv2Wire("ONE", ""), "ONE");
        Assert.assertEquals(CoopWorldSync.packMv2Wire("ONE", null), "ONE");
    }

    @Test
    public void choiceLabelsRoundTrip() {
        for (String label : PlaneFormat.CHOICES) {
            String canonical = PlaneFormat.fromChoiceLabel(label);
            Assert.assertTrue(PlaneFormat.isKnown(canonical), label);
            Assert.assertEquals(PlaneFormat.displayName(canonical), label.equals("Bellwarden Standard")
                    ? "Bellwarden Standard" : canonical);
        }
    }

    @Test
    public void strictOverworldDefaultOff() {
        ConfigData cfg = Config.instance() != null ? Config.instance().getConfigData() : null;
        if (cfg != null) {
            Assert.assertFalse(cfg.kStrictOverworldLegalDecks);
        }
        Assert.assertFalse(PlaneFormat.strictOverworldLegalDecks());
    }
}
