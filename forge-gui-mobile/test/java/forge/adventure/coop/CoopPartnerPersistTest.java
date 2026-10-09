package forge.adventure.coop;

import forge.adventure.AdventureTestUserDir;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.gamemodes.net.event.coop.CoopPartnerCreateEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotEvent;
import forge.localinstance.properties.ForgeConstants;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * CO5 partner behaviour against {@code forge.test.userDir}.
 * Uses public/package APIs only (no reflection).
 */
public class CoopPartnerPersistTest {

    private static final String PROFILE_A = "11111111-1111-1111-1111-111111111111";
    private static final String PROFILE_B = "22222222-2222-2222-2222-222222222222";
    private static final int SOLO_GOLD = 100;
    private static final int LOOT_GOLD = 250;
    private static final String LOOT_MATERIAL = "ore_iron";
    private static final int LOOT_MATERIAL_AMOUNT = 7;

    private static Path testUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;

    private AdventurePlayer player;
    private SaveFileData soloPlayerSnapshot;
    private Path soloSavPath;
    private byte[] soloSavBytesBefore;

    @BeforeClass
    public static void initIsolatedUserDirAndGui() throws Exception {
        testUserDir = AdventureTestUserDir.configuredTestUserDir();
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        AdventureTestUserDir.requireIsolatedUserDir();
        Assert.assertTrue(CoopProfileId.profileFile().getAbsolutePath().startsWith(testUserDir.toString()));
    }

    @AfterClass
    public static void assertRealUserDirUntouched() throws Exception {
        AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot, "CoopPartnerPersistTest");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();
        CoopProfileId.clearCacheForTests();
        player = WorldSave.getCurrentSave().getPlayer();
        preparePlayer(player, "SoloHero", SOLO_GOLD);
        soloPlayerSnapshot = player.save();
        WorldSave.getCurrentSave().getPartners().clear();
        CoopSession.get().testClearGuestPlaneFollow();
        soloSavPath = Path.of(ForgeConstants.USER_ADVENTURE_DIR,
                "Shandalar Ascendant", "save_co5solo.sav");
        Files.createDirectories(soloSavPath.getParent());
        soloSavBytesBefore = writeFakeSoloSav(soloSavPath, soloPlayerSnapshot);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        if (soloPlayerSnapshot != null && player != null) {
            try {
                player.load(soloPlayerSnapshot);
            } catch (final Exception ignored) {
                preparePlayer(player, "SoloHero", SOLO_GOLD);
            }
        }
        WorldSave.getCurrentSave().getPartners().clear();
        CoopSession.get().testClearGuestPlaneFollow();
        CoopProfileId.clearCacheForTests();
    }

    @Test
    public void firstJoinCreatesPartnerInHostSave() {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 1, new byte[0], "")));
        Assert.assertTrue(WorldSave.getCurrentSave().getPartners().has(PROFILE_A));
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().get(PROFILE_A).readString("name"), "Farmhand");
    }

    @Test
    public void leaveAndRejoinRestoresPartnerWithProgress() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));

        final AdventurePlayer partner = new AdventurePlayer();
        partner.load(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        partner.giveGold(LOOT_GOLD);
        Assert.assertTrue(partner.addMaterial(LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
        final byte[] snap = CoopPartnerCodec.encode(partner.save());
        Assert.assertTrue(session.partnerSync().applySnapshotOnGl(
                new CoopPartnerSnapshotEvent(PROFILE_A, 1L, false, snap)));

        final SaveFileData partnersBlob = WorldSave.getCurrentSave().getPartners().save();
        final WorldPartners reloaded = new WorldPartners();
        reloaded.load(partnersBlob);
        Assert.assertTrue(reloaded.has(PROFILE_A));
        Assert.assertTrue(rawHasMaterial(reloaded.get(PROFILE_A), LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
    }

    @Test
    public void guestCrashLosesOnlyLastSnapshotWindow() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));

        final AdventurePlayer partner = new AdventurePlayer();
        partner.load(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        final int goldAfterFirst = partner.getGold() + 50;
        partner.giveGold(50);
        Assert.assertTrue(session.partnerSync().applySnapshotOnGl(
                new CoopPartnerSnapshotEvent(PROFILE_A, 1L, false, CoopPartnerCodec.encode(partner.save()))));

        partner.giveGold(999);
        final SaveFileData onHost = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertEquals(onHost.readInt("gold"), goldAfterFirst);
    }

    @Test
    public void guestSoloSavByteIdenticalAndSaveUnavailableAfterLeave() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        final byte[] blob = CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));

        session.testBeginGuestForPartner(PROFILE_A);
        session.applyGuestPartnerBlob(blob, new String[0]);
        Assert.assertTrue(session.isPartnerLoaded());
        WorldSave.getCurrentSave().getPlayer().giveGold(LOOT_GOLD);

        WorldSave.getCurrentSave().unloadAfterGuestSession();
        Assert.assertNull(WorldSave.getCurrentSave().getWorld().getData(), "world data null after leave");
        Assert.assertFalse(WorldSave.getCurrentSave().save("should-fail", 3), "Save unavailable");
        Assert.assertEquals(Files.readAllBytes(soloSavPath), soloSavBytesBefore, "solo .sav unchanged");
    }

    @Test
    public void legacyChrImportCreatesPartner() throws Exception {
        final File chrDir = new File(testUserDir.toFile(), "adventure/Shandalar Ascendant/characters");
        Assert.assertTrue(chrDir.mkdirs() || chrDir.isDirectory());
        final File chr = new File(chrDir, "LegacyHero.chr");
        final AdventurePlayer legacy = new AdventurePlayer();
        preparePlayer(legacy, "LegacyHero", 333);
        writeChr(chr, legacy.save());

        final byte[] legacyBlob = CoopLegacyChrImport.encodeChrFile(chr);
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Ignored", true, 0, 0, legacyBlob, "")));
        final SaveFileData stored = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertEquals(stored.readString("name"), "LegacyHero");
        Assert.assertEquals(stored.readInt("gold"), 333);
    }

    @Test
    public void twoGuestsGetTwoPartners() {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "PartnerA", true, 0, 0, new byte[0], "")));
        session.testBeginHostForPartner(PROFILE_B);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_B, "PartnerB", false, 1, 2, new byte[0], "")));
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().size(), 2);
    }

    @Test
    public void hostWithTwoWorldsKeepsSeparatePartners() {
        final WorldPartners world1 = new WorldPartners();
        final WorldPartners world2 = new WorldPartners();
        world1.putPlayer(PROFILE_A, CoopPartnerStarter.createNew("W1", true, 0, 0, ""));
        world2.putPlayer(PROFILE_A, CoopPartnerStarter.createNew("W2", true, 0, 0, ""));
        Assert.assertEquals(world1.get(PROFILE_A).readString("name"), "W1");
        Assert.assertEquals(world2.get(PROFILE_A).readString("name"), "W2");
    }

    @Test
    public void profileIdStablePerInstall() {
        final String a = CoopProfileId.getOrCreate();
        Assert.assertEquals(CoopProfileId.getOrCreate(), a);
        CoopProfileId.clearCacheForTests();
        Assert.assertEquals(CoopProfileId.getOrCreate(), a);
    }

    @Test
    public void partnersRoundTripThroughWorldSaveBlob() {
        final WorldPartners partners = WorldSave.getCurrentSave().getPartners();
        final AdventurePlayer p = CoopPartnerStarter.createNew("RoundTrip", true, 0, 0, "");
        p.giveGold(42);
        partners.putPlayer(PROFILE_A, p);
        final WorldPartners loaded = new WorldPartners();
        loaded.load(partners.save());
        Assert.assertEquals(loaded.get(PROFILE_A).readString("name"), "RoundTrip");
        Assert.assertTrue(loaded.get(PROFILE_A).readInt("gold") >= 42);
    }

    @Test
    public void finalSnapshotBypassesRateLimit() throws Exception {
        final CoopSession session = CoopSession.get();
        session.testBeginHostForPartner(PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        final byte[] blob = CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        for (int i = 0; i < 20; i++) {
            session.partnerSync().validator().acceptSnapshot();
        }
        Assert.assertFalse(session.partnerSync().validator().acceptSnapshot());
        Assert.assertTrue(session.partnerSync().applySnapshotOnGl(
                        new CoopPartnerSnapshotEvent(PROFILE_A, 99L, true, blob)),
                "final snapshot must not be rate-limited");
    }

    private static void preparePlayer(final AdventurePlayer p, final String name, final int gold) {
        final DifficultyData d = new DifficultyData();
        d.name = "Easy";
        d.startingLife = 20;
        d.startingMoney = gold;
        d.startingShards = 0;
        d.startingDifficulty = true;
        d.spawnRank = 0;
        d.enemyLifeFactor = 1f;
        d.sellFactor = 0.5f;
        d.shardSellRatio = 0.5f;
        d.goldLoss = 0.1f;
        d.lifeLoss = 0.1f;
        d.startItems = new String[0];
        p.create(name, new Deck(name), true, 0, 0, false, false, d, AdventureModes.Standard);
    }

    private static boolean rawHasMaterial(final SaveFileData data, final String id, final int amount) {
        final Object ids = data.readObject("materialIds");
        final Object counts = data.readObject("materialCounts");
        if (!(ids instanceof String[]) || !(counts instanceof int[])) {
            return false;
        }
        final String[] materialIds = (String[]) ids;
        final int[] materialCounts = (int[]) counts;
        for (int i = 0; i < materialIds.length; i++) {
            if (id.equals(materialIds[i]) && materialCounts[i] == amount) {
                return true;
            }
        }
        return false;
    }

    private static byte[] writeFakeSoloSav(final Path path, final SaveFileData player) throws Exception {
        final byte[] payload = ("SOLO|" + player.readString("name") + "|" + player.readInt("gold")).getBytes();
        Files.write(path, payload);
        return payload;
    }

    private static void writeChr(final File file, final SaveFileData data) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
        }
    }
}
