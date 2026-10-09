package forge.adventure;

import forge.adventure.coop.CoopSession;
import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.EnemyThemeDecks;
import forge.adventure.util.GymUtil;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneFormat;
import forge.adventure.world.PlaneMeta;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopSessionCode;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.coop.CoopWireLimits;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopPlaneSwitchEvent;
import forge.gamemodes.net.event.coop.CoopPlanarGateEntry;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;

/**
 * Package K: plane format storage, migration, resolver fallbacks, Commander mode,
 * and co-op {@code planeFormat} on world offer / plane switch.
 */
public class PlaneFormatTest {

    private AdventureModes savedAdventureMode;

    @BeforeMethod
    public void clearGuestFollow() {
        CoopSession.get().testClearGuestPlaneFollow();
        CoopSession.get().testClearHostingForHello();
        savedAdventureMode = null;
    }

    @AfterMethod
    public void tearDown() throws Exception {
        restoreAdventureMode();
        CoopSession.get().testClearGuestPlaneFollow();
        CoopSession.get().testClearHostingForHello();
        RewardData.invalidateCardPool();
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
        // Pre-K blob: no "format" key at all (not an empty string value).
        PlaneMeta meta = PlaneMeta.home(2L);
        SaveFileData data = meta.save();
        Assert.assertTrue(data.containsKey("format"));
        data.remove("format");
        Assert.assertFalse(data.containsKey("format"));

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
    public void guestUnknownFormatUsesHostDefaultNotLocalPlane() throws Exception {
        // Local plane is Historic; unknown wire token must not fall through to it.
        MultiverseState multi = Current.player() != null
                && forge.adventure.world.WorldSave.getCurrentSave() != null
                && forge.adventure.world.WorldSave.getCurrentSave().getMultiverse() != null
                ? forge.adventure.world.WorldSave.getCurrentSave().getMultiverse() : null;
        PlaneMeta prior = null;
        String priorFmt = null;
        if (multi != null && multi.getCurrentMeta() != null) {
            prior = multi.getCurrentMeta();
            priorFmt = prior.getFormat();
            prior.setFormat(GymUtil.FORMAT_HISTORIC);
        }
        try {
            Assert.assertEquals(CoopSession.testAcceptGuestPlaneFormat("Vintage"),
                    PlaneFormat.defaultFormat());
            CoopSession.get().testApplyGuestPlaneFormat("Vintage");
            Assert.assertEquals(CoopSession.get().getGuestPlaneFormat(), PlaneFormat.defaultFormat());
            Assert.assertEquals(PlaneFormat.resolveCurrent(), PlaneFormat.defaultFormat());
        } finally {
            if (prior != null) {
                prior.setFormat(priorFmt != null ? priorFmt : "");
            }
            CoopSession.get().testClearGuestPlaneFollow();
        }
    }

    @Test
    public void worldOfferPlaneFormatRoundTrips() throws Exception {
        final CoopWorldOfferEvent offer = new CoopWorldOfferEvent(
                "Host", "Shandalar Ascendant", "cfg", 42L, "hash",
                36743, 36744, "home", "world/world.json", "DMU",
                new CoopPlanarGateEntry[0], GymUtil.FORMAT_HISTORIC);
        Assert.assertEquals(offer.getPlaneFormat(), GymUtil.FORMAT_HISTORIC);
        Assert.assertEquals(offer.getMv2SetCode(), "DMU");

        final CoopWorldOfferEvent decoded = roundTrip(offer);
        Assert.assertEquals(decoded.getPlaneFormat(), GymUtil.FORMAT_HISTORIC);
        Assert.assertEquals(decoded.getMv2SetCode(), "DMU");
        Assert.assertEquals(decoded.getWorldPlaneId(), "home");
    }

    @Test
    public void planeSwitchPlaneFormatRoundTrips() throws Exception {
        final CoopPlaneSwitchEvent sw = new CoopPlaneSwitchEvent(
                "Shandalar Ascendant", "set_dmu", "world/set_plane_world.json",
                "cfg", 7L, "hash", 10f, 20f, "ONE",
                new CoopPlanarGateEntry[0], GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(sw.getPlaneFormat(), GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(sw.getMv2SetCode(), "ONE");

        final CoopPlaneSwitchEvent decoded = roundTrip(sw);
        Assert.assertEquals(decoded.getPlaneFormat(), GymUtil.FORMAT_PAUPER);
        Assert.assertEquals(decoded.getMv2SetCode(), "ONE");
        Assert.assertEquals(decoded.getWorldPlaneId(), "set_dmu");
    }

    @Test
    public void wireLimitsRejectOverlongPlaneFormat() {
        final StringBuilder longFmt = new StringBuilder();
        for (int i = 0; i < CoopWireLimits.MAX_PLANE_FORMAT_LEN + 5; i++) {
            longFmt.append('x');
        }
        Assert.assertNull(CoopWireLimits.acceptPlaneFormat(longFmt.toString()));
        Assert.assertEquals(CoopWireLimits.acceptPlaneFormat("Pauper"), "Pauper");
        Assert.assertEquals(CoopWireLimits.acceptPlaneFormat(null), "");
    }

    @Test
    public void unknownFormatIsNotKnown() {
        Assert.assertFalse(PlaneFormat.isKnown("Vintage"));
        Assert.assertFalse(PlaneFormat.isKnown(""));
        Assert.assertTrue(PlaneFormat.isKnown(GymUtil.FORMAT_STANDARD));
    }

    @Test
    public void guestRejectsUnknownFormatGracefully() {
        Assert.assertEquals(CoopSession.testAcceptGuestPlaneFormat("Vintage"),
                PlaneFormat.defaultFormat());
        Assert.assertEquals(CoopSession.testAcceptGuestPlaneFormat(""),
                PlaneFormat.defaultFormat());
        Assert.assertEquals(CoopSession.testAcceptGuestPlaneFormat(null),
                PlaneFormat.defaultFormat());
        Assert.assertEquals(CoopSession.testAcceptGuestPlaneFormat("pauper"), GymUtil.FORMAT_PAUPER);
        final StringBuilder longFmt = new StringBuilder();
        for (int i = 0; i < CoopWireLimits.MAX_PLANE_FORMAT_LEN + 3; i++) {
            longFmt.append('Z');
        }
        Assert.assertEquals(CoopSession.testAcceptGuestPlaneFormat(longFmt.toString()),
                PlaneFormat.defaultFormat());
    }

    @Test
    public void commanderModeResolveFormatWireAndRewardPool() throws Exception {
        setAdventureMode(AdventureModes.Commander);
        try {
            Assert.assertTrue(Current.player().isCommanderMode());
            Assert.assertEquals(PlaneFormat.resolveCurrent(), GymUtil.FORMAT_COMMANDER);
            Assert.assertEquals(EnemyThemeDecks.resolveFormatForTest(), GymUtil.FORMAT_COMMANDER);
            Assert.assertEquals(CoopSession.testHostWirePlaneFormat(), GymUtil.FORMAT_COMMANDER);
            Assert.assertTrue(RewardData.cardPoolUsesCommanderBreadth());
            Assert.assertEquals(Current.player().getRunFormat(), GymUtil.FORMAT_COMMANDER);
        } finally {
            restoreAdventureMode();
        }
    }

    @Test
    public void guestPlaneFormatChangeInvalidatesCardPool() {
        RewardData.getAllCards();
        Assert.assertTrue(RewardData.isCardPoolCached());
        CoopSession.get().testApplyGuestPlaneFormat(GymUtil.FORMAT_PAUPER);
        Assert.assertFalse(RewardData.isCardPoolCached(),
                "guest offer/switch must invalidate the reward pool");
        Assert.assertEquals(CoopSession.get().getGuestPlaneFormat(), GymUtil.FORMAT_PAUPER);
    }

    @Test
    public void invalidateCardPoolClearsCache() {
        RewardData.getAllCards();
        Assert.assertTrue(RewardData.isCardPoolCached());
        RewardData.invalidateCardPool();
        Assert.assertFalse(RewardData.isCardPoolCached());
    }

    @Test
    public void oldProtocolPeerIsRefusedViaRealOnHello() {
        final String code = CoopSessionCode.generate();
        final CoopSession session = CoopSession.get();
        session.testPrepareHostingForHello(code);
        try {
            final int oldProtocol = CoopPorts.PROTOCOL_VERSION - 1;
            Assert.assertTrue(oldProtocol >= 1);
            session.testHostOnHello(new CoopHelloEvent(oldProtocol,
                    CoopVersion.buildHash(), CoopVersion.cardDataHash(),
                    "Guest", "Guest", code));
            final String err = session.getLastError();
            Assert.assertNotNull(err);
            Assert.assertTrue(err.toLowerCase().contains("protocol"), err);
            Assert.assertTrue(err.contains(String.valueOf(CoopPorts.PROTOCOL_VERSION)), err);
            Assert.assertTrue(err.contains(String.valueOf(oldProtocol)), err);
            Assert.assertEquals(session.getState(), CoopSession.State.HOSTING);
        } finally {
            session.testClearHostingForHello();
        }
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
    public void strictOverworldTunableWired() {
        ConfigData cfg = Config.instance() != null ? Config.instance().getConfigData() : null;
        Assert.assertNotNull(cfg);
        Assert.assertFalse(cfg.kStrictOverworldLegalDecks);
        Assert.assertFalse(PlaneFormat.strictOverworldLegalDecks());
        final boolean previous = cfg.kStrictOverworldLegalDecks;
        try {
            cfg.kStrictOverworldLegalDecks = true;
            Assert.assertTrue(PlaneFormat.strictOverworldLegalDecks());
        } finally {
            cfg.kStrictOverworldLegalDecks = previous;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(final T event) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(event);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }

    private void setAdventureMode(final AdventureModes mode) throws Exception {
        AdventurePlayer p = Current.player();
        Assert.assertNotNull(p, "test bootstrap must provide a current player");
        Field f = AdventurePlayer.class.getDeclaredField("adventureMode");
        f.setAccessible(true);
        if (savedAdventureMode == null) {
            savedAdventureMode = (AdventureModes) f.get(p);
        }
        f.set(p, mode);
    }

    private void restoreAdventureMode() throws Exception {
        if (savedAdventureMode == null) {
            return;
        }
        AdventurePlayer p = Current.player();
        if (p != null) {
            Field f = AdventurePlayer.class.getDeclaredField("adventureMode");
            f.setAccessible(true);
            f.set(p, savedAdventureMode);
        }
        savedAdventureMode = null;
    }
}
