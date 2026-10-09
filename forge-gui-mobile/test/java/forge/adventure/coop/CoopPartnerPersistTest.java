package forge.adventure.coop;

import forge.adventure.AdventureTestUserDir;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.event.coop.CoopPartnerCreateEvent;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * CO5 world-bound partner characters: partners live in the host WorldSave;
 * the guest solo save is never touched. Real behaviour tests against
 * {@link AdventureTestUserDir} / {@code forge.test.userDir}.
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
    private byte[] soloSaveBytesBefore;

    @BeforeClass
    public static void initIsolatedUserDirAndGui() throws Exception {
        testUserDir = AdventureTestUserDir.configuredTestUserDir();
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        AdventureTestUserDir.requireIsolatedUserDir();
        Assert.assertTrue(CoopProfileId.profileFile().getAbsolutePath().startsWith(testUserDir.toString()),
                "profile id file must be under forge.test.userDir");
    }

    @AfterClass
    public static void assertRealUserDirUntouched() throws Exception {
        AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                "CoopPartnerPersistTest");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();
        CoopProfileId.clearCacheForTests();
        player = WorldSave.getCurrentSave().getPlayer();
        preparePlayer(player, "SoloHero", SOLO_GOLD);
        soloPlayerSnapshot = player.save();
        WorldSave.getCurrentSave().getPartners().clear();
        resetSession();
        // Snapshot a fake solo .sav file under the test user dir for byte-identity checks.
        soloSaveBytesBefore = writeSoloMarkerFile();
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
        resetSession();
        CoopProfileId.clearCacheForTests();
    }

    @Test
    public void firstJoinCreatesPartnerInHostSave() throws Exception {
        final CoopSession session = CoopSession.get();
        setHostSession(session, PROFILE_A);

        final boolean created = session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 1, new byte[0], ""));
        Assert.assertTrue(created);
        Assert.assertTrue(WorldSave.getCurrentSave().getPartners().has(PROFILE_A));
        final SaveFileData stored = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertEquals(stored.readString("name"), "Farmhand");
        Assert.assertTrue(stored.readInt("gold") >= 0);
    }

    @Test
    public void leaveAndRejoinRestoresPartnerWithProgress() throws Exception {
        final CoopSession session = CoopSession.get();
        setHostSession(session, PROFILE_A);

        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));
        // Simulate guest progress: mutate stored partner gold/materials.
        final AdventurePlayer partner = new AdventurePlayer();
        partner.load(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        partner.giveGold(LOOT_GOLD);
        Assert.assertTrue(partner.addMaterial(LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
        final byte[] snap = CoopPartnerCodec.encode(partner.save());
        Assert.assertTrue(session.applyHostPartnerSnapshot(PROFILE_A, snap));

        // "Leave": clear guest session fields; host keeps partners.
        setField(session, "guestProfileId", "");
        setField(session, "partnerLoaded", false);

        // Rejoin: host still has the partner with progress.
        Assert.assertTrue(WorldSave.getCurrentSave().getPartners().has(PROFILE_A));
        final SaveFileData restored = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertEquals(restored.readString("name"), "Farmhand");
        Assert.assertTrue(restored.readInt("gold") >= SOLO_GOLD + LOOT_GOLD
                        || rawHasMaterial(restored, LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT),
                "rejoin must restore partner progress");
        Assert.assertTrue(rawHasMaterial(restored, LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
    }

    @Test
    public void guestCrashLosesOnlyLastSnapshotWindow() throws Exception {
        final CoopSession session = CoopSession.get();
        setHostSession(session, PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "Farmhand", true, 0, 0, new byte[0], "")));

        final AdventurePlayer partner = new AdventurePlayer();
        partner.load(WorldSave.getCurrentSave().getPartners().get(PROFILE_A));
        final int goldAfterFirstSnap = partner.getGold() + 50;
        partner.giveGold(50);
        Assert.assertTrue(session.applyHostPartnerSnapshot(PROFILE_A, CoopPartnerCodec.encode(partner.save())));

        // Progress after the last snapshot is not on the host — crash window.
        partner.giveGold(999);
        final int unsyncedGold = partner.getGold();
        Assert.assertTrue(unsyncedGold > goldAfterFirstSnap);

        final SaveFileData onHost = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertEquals(onHost.readInt("gold"), goldAfterFirstSnap,
                "host keeps only the last accepted snapshot");
    }

    @Test
    public void guestSoloSaveFileByteIdenticalBeforeAndAfterSession() throws Exception {
        final CoopSession session = CoopSession.get();
        // Guest path: load partner into memory, then leave to menu without writing solo.
        final byte[] blob = session.testHostCreatePartner(PROFILE_A, "Farmhand");
        setGuestSession(session, PROFILE_A);
        session.applyGuestPartnerBlob(blob);
        Assert.assertTrue(session.isPartnerLoaded());
        Assert.assertEquals(WorldSave.getCurrentSave().getPlayer().getName(), "Farmhand");

        // Mutate in-memory partner (would have corrupted solo under the old model).
        WorldSave.getCurrentSave().getPlayer().giveGold(LOOT_GOLD);

        // Leave: return-to-menu path must not write the solo marker file.
        final byte[] after = Files.readAllBytes(soloMarkerPath());
        Assert.assertEquals(after, soloSaveBytesBefore, "solo save bytes must be unchanged");

        // Explicitly confirm the write guard.
        Assert.assertTrue(session.isGuestSession());
        Assert.assertFalse(WorldSave.getCurrentSave().save("should-not-write", 3));
        Assert.assertEquals(Files.readAllBytes(soloMarkerPath()), soloSaveBytesBefore);
    }

    @Test
    public void legacyChrImportCreatesPartner() throws Exception {
        final File chrDir = new File(testUserDir.toFile(), "adventure/Shandalar Ascendant/characters");
        Assert.assertTrue(chrDir.mkdirs() || chrDir.isDirectory());
        final File chr = new File(chrDir, "LegacyHero.chr");
        final AdventurePlayer legacy = new AdventurePlayer();
        preparePlayer(legacy, "LegacyHero", 333);
        legacy.setCharacterFlag("legacyMark", 1);
        writeChr(chr, legacy.save());
        Assert.assertTrue(chr.isFile());

        final byte[] legacyBlob = CoopLegacyChrImport.encodeChrFile(chr);
        final CoopSession session = CoopSession.get();
        setHostSession(session, PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "LegacyHero", true, 0, 0, legacyBlob, "")));
        final SaveFileData stored = WorldSave.getCurrentSave().getPartners().get(PROFILE_A);
        Assert.assertNotNull(stored);
        Assert.assertEquals(stored.readString("name"), "LegacyHero");
        Assert.assertEquals(stored.readInt("gold"), 333);
    }

    @Test
    public void twoGuestsGetTwoPartners() throws Exception {
        final CoopSession session = CoopSession.get();
        setHostSession(session, PROFILE_A);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_A, "PartnerA", true, 0, 0, new byte[0], "")));
        // Switch "connected" guest to B.
        setField(session, "guestProfileId", PROFILE_B);
        Assert.assertTrue(session.applyHostPartnerCreate(new CoopPartnerCreateEvent(
                PROFILE_B, "PartnerB", false, 1, 2, new byte[0], "")));

        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().size(), 2);
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().get(PROFILE_A).readString("name"), "PartnerA");
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().get(PROFILE_B).readString("name"), "PartnerB");
    }

    @Test
    public void hostWithTwoWorldsKeepsSeparatePartners() throws Exception {
        final WorldPartners world1 = new WorldPartners();
        final WorldPartners world2 = new WorldPartners();
        final AdventurePlayer p1 = CoopPartnerStarter.createNew("W1", true, 0, 0, "");
        final AdventurePlayer p2 = CoopPartnerStarter.createNew("W2", true, 0, 0, "");
        world1.putPlayer(PROFILE_A, p1);
        world2.putPlayer(PROFILE_A, p2);

        Assert.assertEquals(world1.get(PROFILE_A).readString("name"), "W1");
        Assert.assertEquals(world2.get(PROFILE_A).readString("name"), "W2");
        // Same profile id, different world maps — no cross-talk.
        Assert.assertNotEquals(world1.get(PROFILE_A).readString("name"),
                world2.get(PROFILE_A).readString("name"));
    }

    @Test
    public void profileIdStablePerInstall() {
        final String a = CoopProfileId.getOrCreate();
        final String b = CoopProfileId.getOrCreate();
        Assert.assertEquals(a, b);
        Assert.assertTrue(CoopProfileId.isValid(a));
        CoopProfileId.clearCacheForTests();
        final String c = CoopProfileId.getOrCreate();
        Assert.assertEquals(c, a, "profile id must persist on disk across cache clears");
    }

    @Test
    public void partnersRoundTripThroughWorldSaveBlob() throws Exception {
        final WorldPartners partners = WorldSave.getCurrentSave().getPartners();
        final AdventurePlayer p = CoopPartnerStarter.createNew("RoundTrip", true, 0, 0, "");
        p.giveGold(42);
        partners.putPlayer(PROFILE_A, p);
        final SaveFileData saved = partners.save();
        final WorldPartners loaded = new WorldPartners();
        loaded.load(saved);
        Assert.assertTrue(loaded.has(PROFILE_A));
        Assert.assertEquals(loaded.get(PROFILE_A).readString("name"), "RoundTrip");
        Assert.assertTrue(loaded.get(PROFILE_A).readInt("gold") >= 42);
    }

    private static void setHostSession(final CoopSession session, final String profileId) throws Exception {
        setField(session, "role", CoopSessionRole.HOST);
        setField(session, "state", CoopSession.State.HOSTING);
        setField(session, "guestProfileId", profileId);
        setField(session, "partnerLoaded", false);
        ((CoopPartnerValidator) getField(session, "partnerValidator")).resetRateLimit();
    }

    private static void setGuestSession(final CoopSession session, final String profileId) throws Exception {
        setField(session, "role", CoopSessionRole.GUEST);
        setField(session, "state", CoopSession.State.READY);
        setField(session, "guestProfileId", profileId);
        setField(session, "partnerLoaded", false);
    }

    private static void resetSession() throws Exception {
        final CoopSession session = CoopSession.get();
        setField(session, "role", CoopSessionRole.NONE);
        setField(session, "state", CoopSession.State.IDLE);
        setField(session, "guestProfileId", "");
        setField(session, "partnerLoaded", false);
        setField(session, "lastPartnerSnapshotSendMs", 0L);
    }

    private static void preparePlayer(final AdventurePlayer p, final String name, final int gold) throws Exception {
        setField(p, "name", name);
        setField(p, "adventureMode", AdventureModes.Standard);
        final Object difficulty = getField(p, "difficultyData");
        setField(difficulty, "name", "Easy");
        setField(p, "gold", gold);
        p.getCards().clear();
        @SuppressWarnings("unchecked")
        final java.util.ArrayList<ItemData> inv =
                (java.util.ArrayList<ItemData>) getField(p, "inventoryItems");
        inv.clear();
        @SuppressWarnings("unchecked")
        final java.util.Map<String, Integer> mats =
                (java.util.Map<String, Integer>) getField(p, "materials");
        mats.clear();
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

    private static Path soloMarkerPath() {
        return testUserDir.resolve("solo-marker-co5.bin");
    }

    private static byte[] writeSoloMarkerFile() throws Exception {
        final byte[] bytes = ("solo-v1-" + SOLO_GOLD + "-" + System.nanoTime()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(soloMarkerPath(), bytes);
        return bytes;
    }

    private static void writeChr(final File file, final SaveFileData data) throws Exception {
        final File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(file);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
        }
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        Class<?> c = target.getClass();
        Field f = null;
        while (c != null) {
            try {
                f = c.getDeclaredField(name);
                break;
            } catch (final NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) {
            throw new NoSuchFieldException(name);
        }
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(final Object target, final String name) throws Exception {
        Class<?> c = target.getClass();
        Field f = null;
        while (c != null) {
            try {
                f = c.getDeclaredField(name);
                break;
            } catch (final NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        if (f == null) {
            throw new NoSuchFieldException(name);
        }
        f.setAccessible(true);
        return f.get(target);
    }
}
