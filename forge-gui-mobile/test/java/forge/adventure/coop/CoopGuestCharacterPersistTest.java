package forge.adventure.coop;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import forge.CardStorageReader;
import forge.StaticData;
import forge.adventure.AdventureTestUserDir;
import forge.adventure.data.ItemData;
import forge.adventure.data.WorldData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.coop.CoopPorts;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CO1 guest save-model: co-op {@code .chr} under {@code characters/guest/<characterId>.chr}.
 * Drives the real {@link CoopSession#join}/{@link CoopSession#host}/{@link CoopSession#disconnect}
 * entry points (network bypassed in-process).
 */
public class CoopGuestCharacterPersistTest {

    private static final String GUEST_NAME = "CoopGuestPersist";
    private static final String JOIN_ADDR = "127.0.0.1";
    private static final String JOIN_CODE = "ABCD1234";
    private static final int SOLO_GOLD = 100;
    private static final int LOOT_GOLD = 250;
    private static final String LOOT_MATERIAL = "ore_iron";
    private static final int LOOT_MATERIAL_AMOUNT = 7;
    private static final String LOOT_ITEM = "Coop Loot Charm";
    private static final String LOOT_CARD_LINE = "1 Coop Loot Bolt";

    private static Path testUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static boolean cardDbReady;

    private AdventurePlayer player;
    private String characterId;
    private int soloGoldSnapshot;
    private SaveFileData soloPlayerSnapshot;

    @BeforeClass
    public static void initIsolatedUserDirAndGui() throws Exception {
        testUserDir = AdventureTestUserDir.configuredTestUserDir();
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        AdventureTestUserDir.requireIsolatedUserDir();
        Assert.assertTrue(Config.ascendant(), "suite must load Shandalar Ascendant");
        Assert.assertTrue(CoopCharacterStore.charactersDir().getAbsolutePath()
                        .startsWith(testUserDir.toString()),
                "co-op characters dir must be under forge.test.userDir");
        Assert.assertEquals(JOIN_CODE.length(), CoopPorts.SESSION_CODE_LENGTH);
    }

    @AfterClass
    public static void assertRealUserDirUntouched() throws Exception {
        AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                "CoopGuestCharacterPersistTest");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();
        CoopSession.setTestBypassNetwork(true);
        ensureWorldLoadedForCoop();

        player = WorldSave.getCurrentSave().getPlayer();
        prepareSoloPlayer(player, SOLO_GOLD);
        characterId = player.getCharacterId();
        // Consume mint flag so join path is not forced to autosave in every test.
        player.consumeCharacterIdNeedsPersist();
        soloGoldSnapshot = player.getGold();
        soloPlayerSnapshot = player.save();

        deleteGuestAndLegacyFiles(characterId, GUEST_NAME);
        resetSessionFields();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        clearQueuedGdxApp();
        CoopSession.setTestBypassNetwork(false);
        try {
            CoopSession.get().disconnect();
        } catch (final Exception ignored) {
        }
        if (soloPlayerSnapshot != null && player != null) {
            try {
                player.load(soloPlayerSnapshot);
            } catch (final Exception ignored) {
                prepareSoloPlayer(player, SOLO_GOLD);
            }
        }
        resetSessionFields();
        if (characterId != null) {
            deleteGuestAndLegacyFiles(characterId, GUEST_NAME);
        }
    }

    @Test
    public void firstJoinSeedsChrFromSoloPlayer() throws Exception {
        Assert.assertFalse(CoopCharacterStore.exists(player));

        joinAsGuest();
        Assert.assertEquals(CoopSession.get().getRole(), CoopSessionRole.GUEST);
        Assert.assertTrue(CoopCharacterStore.exists(player), "first join must seed .chr");
        final SaveFileData seeded = CoopCharacterStore.readRaw(characterId);
        Assert.assertNotNull(seeded);
        Assert.assertEquals(seeded.readInt("gold"), SOLO_GOLD);
        Assert.assertEquals(seeded.readString("characterId"), characterId);

        CoopSession.get().disconnect();
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "leave restores solo gold");
    }

    @Test
    public void twoSessionsPreserveGuestLootAndLeaveSoloUntouched() throws Exception {
        joinAsGuest();
        giveCoopLoot(player);
        assertHasCoopLoot(player);
        CoopSession.get().disconnect();

        Assert.assertEquals(player.getGold(), soloGoldSnapshot);
        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readInt("gold"), SOLO_GOLD + LOOT_GOLD);
        Assert.assertTrue(rawHasMaterial(CoopCharacterStore.readRaw(characterId),
                LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));

        player.load(soloPlayerSnapshot);
        stripCardsPayload(characterId);

        joinAsGuest();
        assertHasCoopLoot(player);
        CoopSession.get().disconnect();
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);
    }

    @Test
    public void prePrChrWithoutCharacterIdKeepsProgressAcrossTwoJoinLeaveCycles() throws Exception {
        // Real pre-PR payload: loot on disk, no characterId key (legacy name file).
        giveCoopLoot(player);
        final SaveFileData prePr = player.save();
        prePr.remove("characterId");
        Assert.assertFalse(prePr.containsKey("characterId"));
        player.load(soloPlayerSnapshot); // back to solo gold; keep same characterId
        player.bindCharacterId(characterId);
        CoopCharacterStore.writeLegacyRawForTests(GUEST_NAME, prePr);
        Assert.assertFalse(CoopCharacterStore.guestCharacterFile(characterId).isFile());

        // Session 1: migrate + bind file key (must not remint).
        joinAsGuest();
        Assert.assertEquals(player.getCharacterId(), characterId, "load must bind file key");
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD);
        player.giveGold(10);
        CoopSession.get().disconnect();

        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(characterId).isFile());
        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readInt("gold"),
                SOLO_GOLD + LOOT_GOLD + 10);
        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readString("characterId"),
                characterId);

        // Session 2: same file, progress carries; no orphan guest file.
        player.load(soloPlayerSnapshot);
        player.bindCharacterId(characterId);
        joinAsGuest();
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD + 10);
        player.giveGold(5);
        CoopSession.get().disconnect();

        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readInt("gold"),
                SOLO_GOLD + LOOT_GOLD + 15);
        final File[] guestFiles = CoopCharacterStore.guestCharactersDir()
                .listFiles((dir, name) -> name.endsWith(".chr") && !name.endsWith(".corrupt"));
        Assert.assertNotNull(guestFiles);
        Assert.assertEquals(guestFiles.length, 1, "exactly one guest .chr after two cycles");
        Assert.assertEquals(guestFiles[0].getName(), sanitizeId(characterId) + ".chr");
    }

    @Test
    public void restartJoinWhileGuestRestoresSoloSynchronouslyBeforeRestash() throws Exception {
        joinAsGuest();
        giveCoopLoot(player);
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD);

        final List<Runnable> queued = installQueuingGdxApp();
        try {
            // Restart path must restore solo inline (not merely post to GL).
            CoopSession.get().host(true);
            Assert.assertEquals(CoopSession.get().getRole(), CoopSessionRole.HOST);
            Assert.assertEquals(player.getGold(), soloGoldSnapshot,
                    "sync restore before host must put solo back");
            Assert.assertFalse(CoopSession.get().isGuestSoloRestorePending());

            // Join again: stash must be solo, not co-op loot.
            joinAsGuest();
            Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD,
                    "second join loads persisted co-op .chr");
            // Drain GL posts (dispose, etc.) — must not clobber the joined player.
            for (final Runnable r : new ArrayList<>(queued)) {
                try {
                    r.run();
                } catch (final Throwable ignored) {
                }
            }
            Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD,
                    "queued GL work must not overwrite the new session player after sync restart");
        } finally {
            clearQueuedGdxApp();
        }
    }

    @Test
    public void joinRefusesWhileSoloRestorePendingOnQueuedGdx() throws Exception {
        joinAsGuest();
        giveCoopLoot(player);

        final List<Runnable> queued = installQueuingGdxApp();
        try {
            CoopSession.get().disconnect(); // async GL restore posted
            Assert.assertFalse(queued.isEmpty());
            Assert.assertTrue(CoopSession.get().isGuestSoloRestorePending());
            Assert.assertTrue(CoopSession.get().blocksLocalWorldSave());

            try {
                joinAsGuest();
                Assert.fail("join must refuse while solo restore is pending");
            } catch (final IllegalStateException expected) {
                Assert.assertTrue(expected.getMessage().contains("solo restore"));
            }

            // Finish the queued restore (TransitionScreen fails headless → wrapped runs).
            for (final Runnable r : new ArrayList<>(queued)) {
                try {
                    r.run();
                } catch (final Throwable ignored) {
                    // UnsatisfiedLinkError from TransitionScreen — clear pending manually.
                }
            }
            final Field pending = CoopSession.class.getDeclaredField("guestSoloRestorePending");
            pending.setAccessible(true);
            ((AtomicBoolean) pending.get(CoopSession.get())).set(false);
            player.load(soloPlayerSnapshot);
        } finally {
            clearQueuedGdxApp();
        }
    }

    @Test
    public void corruptChrIsQuarantinedAndReseededFromSolo() throws Exception {
        final File guestFile = CoopCharacterStore.guestCharacterFile(characterId);
        Files.createDirectories(guestFile.getParentFile().toPath());
        Files.write(guestFile.toPath(), "not-a-valid-chr-payload".getBytes(StandardCharsets.UTF_8));

        joinAsGuest();
        Assert.assertEquals(CoopSession.get().getRole(), CoopSessionRole.GUEST);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "reseed keeps solo state");
        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(characterId).isFile());
        Assert.assertTrue(new File(guestFile.getParentFile(), guestFile.getName() + ".corrupt").isFile(),
                "corrupt payload must be quarantined");
        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readInt("gold"), SOLO_GOLD);

        CoopSession.get().disconnect();
    }

    @Test
    public void hostExportToSharedNamePathDoesNotClobberGuestChr() throws Exception {
        joinAsGuest();
        giveCoopLoot(player);
        CoopSession.get().disconnect();

        player.load(soloPlayerSnapshot);
        CoopCharacterStore.writeLegacyRawForTests(GUEST_NAME, player.save());

        final SaveFileData guestAfter = CoopCharacterStore.readRaw(characterId);
        Assert.assertEquals(guestAfter.readInt("gold"), SOLO_GOLD + LOOT_GOLD);
    }

    @Test
    public void sameDisplayNameDifferentCharacterIdsGetSeparateCoopFiles() throws Exception {
        final String id1 = characterId;
        joinAsGuest();
        giveCoopLoot(player);
        CoopSession.get().disconnect();

        player.load(soloPlayerSnapshot);
        setField(player, "characterId", null);
        setField(player, "name", GUEST_NAME);
        final String id2 = player.getCharacterId();
        Assert.assertNotEquals(id1, id2);
        player.consumeCharacterIdNeedsPersist();

        joinAsGuest();
        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(id1).isFile());
        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(id2).isFile());
        Assert.assertEquals(CoopCharacterStore.readRaw(id1).readInt("gold"), SOLO_GOLD + LOOT_GOLD);
        Assert.assertEquals(CoopCharacterStore.readRaw(id2).readInt("gold"), SOLO_GOLD);
        CoopSession.get().disconnect();

        deleteGuestAndLegacyFiles(id2, GUEST_NAME);
        characterId = id1;
        prepareSoloPlayer(player, SOLO_GOLD);
        player.bindCharacterId(id1);
        soloPlayerSnapshot = player.save();
    }

    @Test
    public void chrPayloadKeepsCardsAcrossAtomicSaveAndOldExportWipesThem() throws Exception {
        ensureMinimalCardDb();

        joinAsGuest();
        CoopSession.get().disconnect();
        embedCardsPayload(characterId, LOOT_CARD_LINE);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(characterId), "Coop Loot Bolt"));

        final SaveFileData withCards = CoopCharacterStore.readRaw(characterId);
        CoopCharacterStore.writeRawForTests(characterId, withCards);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(characterId), "Coop Loot Bolt"));

        player.load(soloPlayerSnapshot);
        // Old export-always: overwrite guest file from solo (no cards).
        CoopCharacterStore.savePlayer(player);
        Assert.assertFalse(rawHasCard(CoopCharacterStore.readRaw(characterId), "Coop Loot Bolt"));
    }

    @Test
    public void blocksLocalWorldSaveUntilGlSoloRestoreFinishes() throws Exception {
        joinAsGuest();
        final List<Runnable> queued = installQueuingGdxApp();
        try {
            CoopSession.get().disconnect();
            Assert.assertFalse(queued.isEmpty());
            Assert.assertTrue(CoopSession.get().isGuestSoloRestorePending());
            Assert.assertTrue(CoopSession.get().blocksLocalWorldSave());

            final Field pending = CoopSession.class.getDeclaredField("guestSoloRestorePending");
            pending.setAccessible(true);
            ((AtomicBoolean) pending.get(CoopSession.get())).set(false);
            Assert.assertFalse(CoopSession.get().blocksLocalWorldSave());
        } finally {
            clearQueuedGdxApp();
            player.load(soloPlayerSnapshot);
        }
    }

    private void joinAsGuest() throws Exception {
        CoopSession.get().join(JOIN_ADDR, JOIN_CODE);
    }

    private static void ensureWorldLoadedForCoop() {
        final WorldData data = new WorldData();
        data.width = 8;
        data.height = 8;
        data.tileSize = 16;
        WorldSave.getCurrentSave().getWorld().installTestWorldGrid(data, 1L);
        Assert.assertNotNull(WorldSave.getCurrentSave().getWorld().getData());
    }

    private static void ensureMinimalCardDb() throws Exception {
        if (cardDbReady && StaticData.instance() != null
                && StaticData.instance().getCommonCards() != null) {
            return;
        }
        final Path emptyCards = Files.createTempDirectory("coop-test-cards");
        final Path emptyEditions = Files.createTempDirectory("coop-test-editions");
        final Path emptyCustomEd = Files.createTempDirectory("coop-test-custom-ed");
        final Path emptyBlock = Files.createTempDirectory("coop-test-block");
        final CardStorageReader reader = new CardStorageReader(emptyCards.toString(), null, true);
        new StaticData(reader, null,
                emptyEditions.toString(), emptyCustomEd.toString(), emptyBlock.toString(),
                "latest", true, true);
        Assert.assertNotNull(StaticData.instance().getCommonCards());
        cardDbReady = true;
    }

    private static void prepareSoloPlayer(final AdventurePlayer p, final int gold) throws Exception {
        setField(p, "name", GUEST_NAME);
        setField(p, "characterId", null);
        setField(p, "characterIdNeedsPersist", false);
        setField(p, "adventureMode", AdventureModes.Standard);
        final Object difficulty = getField(p, "difficultyData");
        setField(difficulty, "name", "Easy");
        setField(p, "gold", gold);
        p.getCards().clear();
        @SuppressWarnings("unchecked")
        final ArrayList<ItemData> inv = (ArrayList<ItemData>) getField(p, "inventoryItems");
        inv.clear();
        @SuppressWarnings("unchecked")
        final Map<String, Integer> mats = (Map<String, Integer>) getField(p, "materials");
        mats.clear();
        Assert.assertNotNull(p.getCharacterId());
        UUID.fromString(p.getCharacterId());
    }

    private static void giveCoopLoot(final AdventurePlayer p) throws Exception {
        p.giveGold(LOOT_GOLD);
        Assert.assertTrue(p.addMaterial(LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
        final ItemData item = new ItemData();
        item.name = LOOT_ITEM;
        item.longID = 42L;
        item.cost = 10;
        @SuppressWarnings("unchecked")
        final ArrayList<ItemData> inv = (ArrayList<ItemData>) getField(p, "inventoryItems");
        inv.add(item);
    }

    private static void assertHasCoopLoot(final AdventurePlayer p) throws Exception {
        Assert.assertEquals(p.getGold(), SOLO_GOLD + LOOT_GOLD);
        Assert.assertEquals(p.getMaterial(LOOT_MATERIAL), LOOT_MATERIAL_AMOUNT);
        Assert.assertTrue(inventoryHas(p, LOOT_ITEM));
    }

    private static void embedCardsPayload(final String characterId, final String cardLine)
            throws Exception {
        final SaveFileData data = CoopCharacterStore.readRaw(characterId);
        data.storeObject("cards", new String[]{cardLine});
        CoopCharacterStore.writeRawForTests(characterId, data);
    }

    private static void stripCardsPayload(final String characterId) throws Exception {
        final SaveFileData data = CoopCharacterStore.readRaw(characterId);
        data.storeObject("cards", new String[0]);
        CoopCharacterStore.writeRawForTests(characterId, data);
    }

    private static boolean inventoryHas(final AdventurePlayer p, final String itemName) throws Exception {
        @SuppressWarnings("unchecked")
        final List<ItemData> inv = (List<ItemData>) getField(p, "inventoryItems");
        for (final ItemData i : inv) {
            if (i != null && itemName.equals(i.name)) {
                return true;
            }
        }
        return false;
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

    private static boolean rawHasCard(final SaveFileData data, final String cardName) {
        final Object raw = data.readObject("cards");
        if (!(raw instanceof String[])) {
            return false;
        }
        for (final String line : (String[]) raw) {
            if (line != null && line.contains(cardName)) {
                return true;
            }
        }
        return false;
    }

    private static void resetSessionFields() throws Exception {
        final CoopSession session = CoopSession.get();
        setField(session, "role", CoopSessionRole.NONE);
        setField(session, "state", CoopSession.State.IDLE);
        setField(session, "guestWorldBackup", null);
        setField(session, "guestPlayerBackup", null);
        setField(session, "guestMultiverseBackup", null);
        setField(session, "guestCharacterId", null);
        setField(session, "server", null);
        setField(session, "client", null);
        final Field restoreDone = CoopSession.class.getDeclaredField("guestRestoreDone");
        restoreDone.setAccessible(true);
        ((AtomicBoolean) restoreDone.get(session)).set(false);
        final Field pending = CoopSession.class.getDeclaredField("guestSoloRestorePending");
        pending.setAccessible(true);
        ((AtomicBoolean) pending.get(session)).set(false);
    }

    private static void deleteGuestAndLegacyFiles(final String id, final String name) {
        final File guest = CoopCharacterStore.guestCharacterFile(id);
        if (guest.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            guest.delete();
        }
        final File corrupt = new File(guest.getParentFile(), guest.getName() + ".corrupt");
        if (corrupt.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            corrupt.delete();
        }
        final File legacy = CoopCharacterStore.legacyCharacterFile(name);
        if (legacy.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
    }

    private static String sanitizeId(final String id) {
        return id.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static List<Runnable> installQueuingGdxApp() {
        final List<Runnable> queue = new ArrayList<>();
        final InvocationHandler handler = (proxy, method, args) -> {
            final String n = method.getName();
            if ("postRunnable".equals(n)) {
                queue.add((Runnable) args[0]);
                return null;
            }
            if ("getType".equals(n)) {
                return Application.ApplicationType.HeadlessDesktop;
            }
            if ("getVersion".equals(n) || "getJavaHeap".equals(n) || "getNativeHeap".equals(n)) {
                return 0;
            }
            if ("getLogLevel".equals(n)) {
                return Application.LOG_NONE;
            }
            final Class<?> rt = method.getReturnType();
            if (rt == boolean.class) {
                return false;
            }
            if (rt == int.class || rt == long.class || rt == float.class || rt == double.class) {
                return 0;
            }
            return null;
        };
        Gdx.app = (Application) Proxy.newProxyInstance(
                Application.class.getClassLoader(),
                new Class<?>[]{Application.class},
                handler);
        return queue;
    }

    private static void clearQueuedGdxApp() {
        Gdx.app = null;
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
