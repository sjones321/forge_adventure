package forge.adventure.coop;

import com.badlogic.gdx.Application;
import com.badlogic.gdx.Gdx;
import forge.CardStorageReader;
import forge.StaticData;
import forge.adventure.AdventureTestUserDir;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
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
 * CO1 guest save-model: co-op {@code .chr} persists across sessions under
 * {@code characters/guest/<characterId>.chr}; solo WorldSave is never overwritten
 * by join/leave. Exercises {@link CoopSession#applyGuestJoinSaveModel()} /
 * {@link CoopSession#applyGuestLeaveSaveModel(boolean)}.
 */
public class CoopGuestCharacterPersistTest {

    private static final String GUEST_NAME = "CoopGuestPersist";
    private static final int SOLO_GOLD = 100;
    private static final int LOOT_GOLD = 250;
    private static final String LOOT_MATERIAL = "ore_iron";
    private static final int LOOT_MATERIAL_AMOUNT = 7;
    private static final String LOOT_ITEM = "Coop Loot Charm";
    /** Card-list line stored in the .chr payload. */
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
        // Surefire sets forge.test.userDir before any class loads; fail fast if missing.
        testUserDir = AdventureTestUserDir.configuredTestUserDir();

        // Snapshot the real OS user dir (not the Surefire test home).
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);

        // Suite listener installs GuiBase + Localizer; re-check isolation here.
        AdventureTestUserDir.requireIsolatedUserDir();

        Assert.assertTrue(CoopCharacterStore.charactersDir().getAbsolutePath()
                        .startsWith(testUserDir.toString()),
                "co-op characters dir must be under forge.test.userDir");
        Assert.assertTrue(CoopCharacterStore.guestCharactersDir().getAbsolutePath()
                        .contains(File.separator + "guest"),
                "guest co-op files must live under characters/guest/");
    }

    @AfterClass
    public static void assertRealUserDirUntouched() throws Exception {
        AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                "CoopGuestCharacterPersistTest");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();

        player = WorldSave.getCurrentSave().getPlayer();
        prepareSoloPlayer(player, SOLO_GOLD);
        characterId = player.getCharacterId();
        soloGoldSnapshot = player.getGold();
        soloPlayerSnapshot = player.save();

        deleteGuestAndLegacyFiles(characterId, GUEST_NAME);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        clearQueuedGdxApp();
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

        final CoopSession session = CoopSession.get();
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();

        Assert.assertTrue(CoopCharacterStore.exists(player), "first join must seed .chr");
        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(characterId).isFile());
        final SaveFileData seeded = CoopCharacterStore.readRaw(characterId);
        Assert.assertNotNull(seeded);
        Assert.assertEquals(seeded.readInt("gold"), SOLO_GOLD);
        Assert.assertEquals(seeded.readString("name"), GUEST_NAME);
        Assert.assertEquals(seeded.readString("characterId"), characterId);
        Assert.assertEquals(player.getGold(), SOLO_GOLD, "seed keeps the in-memory solo player");

        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "leave restores solo gold");
    }

    @Test
    public void twoSessionsPreserveGuestLootAndLeaveSoloUntouched() throws Exception {
        final CoopSession session = CoopSession.get();

        // --- Session 1: first join seeds from solo, earn loot, leave ---
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        Assert.assertTrue(CoopCharacterStore.exists(player));
        giveCoopLoot(player);
        assertHasCoopLoot(player);

        session.applyGuestLeaveSaveModel(true);

        // Solo WorldSave restored; co-op .chr still has the loot.
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "solo gold must be unchanged after leave");
        Assert.assertEquals(player.getMaterial(LOOT_MATERIAL), 0, "solo must not keep co-op materials");
        Assert.assertFalse(inventoryHas(player, LOOT_ITEM), "solo must not keep co-op items");
        final SaveFileData chrAfterSession1 = CoopCharacterStore.readRaw(characterId);
        Assert.assertNotNull(chrAfterSession1);
        Assert.assertEquals(chrAfterSession1.readInt("gold"), SOLO_GOLD + LOOT_GOLD);
        Assert.assertTrue(rawHasMaterial(chrAfterSession1, LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
        Assert.assertTrue(rawHasItem(chrAfterSession1, LOOT_ITEM));
        // Embed a cards payload into the co-op .chr (same file AdventurePlayer.save writes).
        embedCardsPayload(characterId, LOOT_CARD_LINE);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(characterId), "Coop Loot Bolt"));

        // --- Session 2: rejoin must load .chr, not re-export solo ---
        // Reload solo without cards so AdventurePlayer.load does not need StaticData.
        player.load(soloPlayerSnapshot);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);
        // Strip cards from .chr before loadOrSeed (avoids StaticData); loot gold/items/mats remain.
        stripCardsPayload(characterId);
        Assert.assertTrue(rawHasMaterial(CoopCharacterStore.readRaw(characterId),
                LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));

        setGuestJoining(session);
        session.applyGuestJoinSaveModel();

        assertHasCoopLoot(player);
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD,
                "second join must load co-op .chr loot, not the solo player");

        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "solo still unchanged after second leave");
    }

    @Test
    public void chrPayloadKeepsCardsAcrossAtomicSaveAndOldExportWipesThem() throws Exception {
        // Own setup: CardDb must be present so suite order cannot leave StaticData null
        // when a .chr cards payload is round-tripped through AdventurePlayer.load.
        ensureMinimalCardDb();

        final CoopSession session = CoopSession.get();
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        session.applyGuestLeaveSaveModel(true);
        embedCardsPayload(characterId, LOOT_CARD_LINE);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(characterId), "Coop Loot Bolt"));

        // Atomic re-save of the same payload must keep cards.
        final SaveFileData withCards = CoopCharacterStore.readRaw(characterId);
        CoopCharacterStore.writeRawForTests(characterId, withCards);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(characterId), "Coop Loot Bolt"));

        // Old export-always join overwrites .chr from solo (no cards) — loot cards lost.
        player.load(soloPlayerSnapshot);
        stashThenExportAlwaysThenLoad();
        Assert.assertFalse(rawHasCard(CoopCharacterStore.readRaw(characterId), "Coop Loot Bolt"),
                "old export-always join wipes the cards payload from .chr");
    }

    /**
     * Documents the pre-fix bug: always exporting the solo player over the co-op
     * {@code .chr} on join wipes session loot.
     */
    @Test
    public void oldStashExportBehaviourLosesCoopLootAcrossSessions() throws Exception {
        final CoopSession session = CoopSession.get();

        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        giveCoopLoot(player);
        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readInt("gold"), SOLO_GOLD + LOOT_GOLD);

        player.load(soloPlayerSnapshot);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);

        stashThenExportAlwaysThenLoad();
        Assert.assertEquals(player.getGold(), soloGoldSnapshot,
                "old export-always path reloads the solo snapshot into the session player");
        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readInt("gold"), soloGoldSnapshot,
                "old export-always path overwrites .chr with solo — co-op loot is lost");
        Assert.assertFalse(rawHasMaterial(CoopCharacterStore.readRaw(characterId),
                LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
    }

    @Test
    public void corruptChrAbortsJoinRestoresSoloAndDoesNotOverwriteChr() throws Exception {
        final File guestFile = CoopCharacterStore.guestCharacterFile(characterId);
        Files.createDirectories(guestFile.getParentFile().toPath());
        final byte[] corrupt = "not-a-valid-chr-payload".getBytes(StandardCharsets.UTF_8);
        Files.write(guestFile.toPath(), corrupt);

        final CoopSession session = CoopSession.get();
        setGuestJoining(session);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);

        try {
            session.applyGuestJoinSaveModel();
            Assert.fail("corrupt .chr must fail the join save-model");
        } catch (final Exception expected) {
            // load exception surfaces to join()
        }

        Assert.assertEquals(session.getRole(), CoopSessionRole.NONE);
        Assert.assertEquals(session.getState(), CoopSession.State.DISCONNECTED);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot,
                "solo stash must be restored after a failed join load");
        Assert.assertEquals(Files.readAllBytes(guestFile.toPath()), corrupt,
                "corrupt .chr must not be overwritten on join failure");
        Assert.assertFalse(session.blocksLocalWorldSave(),
                "after sync restore, autosave must not stay blocked");
    }

    @Test
    public void hostExportToSharedNamePathDoesNotClobberGuestChr() throws Exception {
        final CoopSession session = CoopSession.get();
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        giveCoopLoot(player);
        session.applyGuestLeaveSaveModel(true);

        final SaveFileData guestBefore = CoopCharacterStore.readRaw(characterId);
        Assert.assertEquals(guestBefore.readInt("gold"), SOLO_GOLD + LOOT_GOLD);

        // Simulate old host export writing characters/<name>.chr (shared name key).
        player.load(soloPlayerSnapshot);
        CoopCharacterStore.writeLegacyRawForTests(GUEST_NAME, player.save());
        Assert.assertTrue(CoopCharacterStore.legacyCharacterFile(GUEST_NAME).isFile());

        // Guest namespace file must be untouched.
        final SaveFileData guestAfter = CoopCharacterStore.readRaw(characterId);
        Assert.assertEquals(guestAfter.readInt("gold"), SOLO_GOLD + LOOT_GOLD,
                "host/name-keyed export must not overwrite characters/guest/<id>.chr");
        Assert.assertTrue(rawHasMaterial(guestAfter, LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
    }

    @Test
    public void restartHostOrJoinWhileGuestRestoresSoloFirst() throws Exception {
        final CoopSession session = CoopSession.get();
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        giveCoopLoot(player);
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD);

        // host()/join() both call this before switching roles.
        session.endPreviousSessionForRestart("restarting host");

        Assert.assertEquals(session.getRole(), CoopSessionRole.NONE);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot,
                "restart from guest must restore solo before a new host/join");
        Assert.assertTrue(CoopCharacterStore.exists(characterId),
                "co-op .chr must be persisted on guest→restart");
        Assert.assertEquals(CoopCharacterStore.readRaw(characterId).readInt("gold"),
                SOLO_GOLD + LOOT_GOLD);

        // Second restart path: join while already guest.
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD);
        player.giveGold(10);
        session.endPreviousSessionForRestart("restarting join");
        Assert.assertEquals(player.getGold(), soloGoldSnapshot,
                "restarting join while guest must restore solo first");
    }

    @Test
    public void blocksLocalWorldSaveUntilGlSoloRestoreFinishes() throws Exception {
        final CoopSession session = CoopSession.get();
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();

        final List<Runnable> queued = installQueuingGdxApp();
        try {
            session.applyGuestLeaveSaveModel(true);
            // Mimic disconnectInternal flipping role before the GL restore runs.
            setField(session, "role", CoopSessionRole.NONE);
            setField(session, "state", CoopSession.State.DISCONNECTED);

            Assert.assertFalse(queued.isEmpty(), "restore must be posted to the GL thread");
            Assert.assertTrue(session.isGuestSoloRestorePending(),
                    "restore must stay pending until the queued GL runnable finishes");
            Assert.assertTrue(session.blocksLocalWorldSave(),
                    "autosave must stay blocked after role flips to NONE while restore pending");

            // Do not run the queued TransitionScreen path headless (native pixmap Error).
            // Completing the restore clears the pending flag the same way the GL finally block does.
            final Field pending = CoopSession.class.getDeclaredField("guestSoloRestorePending");
            pending.setAccessible(true);
            ((AtomicBoolean) pending.get(session)).set(false);

            Assert.assertFalse(session.isGuestSoloRestorePending());
            Assert.assertFalse(session.blocksLocalWorldSave(),
                    "autosave must unblock only after GL restore completes");
        } finally {
            clearQueuedGdxApp();
            final Field pending = CoopSession.class.getDeclaredField("guestSoloRestorePending");
            pending.setAccessible(true);
            ((AtomicBoolean) pending.get(session)).set(false);
            // Restore never ran on GL — put solo gold back for tearDown.
            player.load(soloPlayerSnapshot);
        }
    }

    @Test
    public void sameDisplayNameDifferentCharacterIdsGetSeparateCoopFiles() throws Exception {
        final CoopSession session = CoopSession.get();
        final String id1 = characterId;

        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        giveCoopLoot(player);
        session.applyGuestLeaveSaveModel(true);
        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(id1).isFile());

        // Second solo save: same display name, new stable id (e.g. another slot).
        player.load(soloPlayerSnapshot);
        setField(player, "characterId", null);
        setField(player, "name", GUEST_NAME);
        final String id2 = player.getCharacterId();
        Assert.assertNotEquals(id1, id2);
        final SaveFileData secondSolo = player.save();
        Assert.assertEquals(secondSolo.readString("characterId"), id2);

        setGuestJoining(session);
        session.applyGuestJoinSaveModel();
        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(id2).isFile());
        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(id1).isFile(),
                "first character's co-op file must remain");
        Assert.assertEquals(CoopCharacterStore.readRaw(id1).readInt("gold"), SOLO_GOLD + LOOT_GOLD);
        Assert.assertEquals(CoopCharacterStore.readRaw(id2).readInt("gold"), SOLO_GOLD,
                "second same-named save must seed its own co-op character");

        // Keep tearDown cleanup covering both ids.
        deleteGuestAndLegacyFiles(id2, GUEST_NAME);
        characterId = id1;
        soloPlayerSnapshot = secondSolo; // tearDown will reload something valid
        prepareSoloPlayer(player, SOLO_GOLD);
        setField(player, "characterId", id1);
        soloPlayerSnapshot = player.save();
    }

    @Test
    public void legacyNameKeyedChrMigratesIntoGuestNamespace() throws Exception {
        player.load(soloPlayerSnapshot);
        CoopCharacterStore.writeLegacyRawForTests(GUEST_NAME, player.save());
        Assert.assertTrue(CoopCharacterStore.legacyCharacterFile(GUEST_NAME).isFile());
        Assert.assertFalse(CoopCharacterStore.guestCharacterFile(characterId).isFile());

        final CoopSession session = CoopSession.get();
        setGuestJoining(session);
        session.applyGuestJoinSaveModel();

        Assert.assertTrue(CoopCharacterStore.guestCharacterFile(characterId).isFile(),
                "legacy .chr must migrate to characters/guest/<id>.chr");
        Assert.assertFalse(CoopCharacterStore.legacyCharacterFile(GUEST_NAME).isFile(),
                "legacy name-keyed file should be moved away");
    }

    /** Replicates the pre-fix join character steps: stash, export solo over .chr, load. */
    private void stashThenExportAlwaysThenLoad() throws Exception {
        final CoopSession session = CoopSession.get();
        final Field restoreDone = CoopSession.class.getDeclaredField("guestRestoreDone");
        restoreDone.setAccessible(true);
        ((AtomicBoolean) restoreDone.get(session)).set(false);

        final Method stash = CoopSession.class.getDeclaredMethod("stashGuestSave");
        stash.setAccessible(true);
        stash.invoke(session);

        // Old exportCurrentPlayer(): write solo player over the co-op file.
        CoopCharacterStore.savePlayer(WorldSave.getCurrentSave().getPlayer());
        final Field idField = CoopSession.class.getDeclaredField("guestCharacterId");
        idField.setAccessible(true);
        idField.set(session, characterId);
        CoopCharacterStore.loadPlayer(WorldSave.getCurrentSave().getPlayer(), characterId);
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
        final CardStorageReader reader = new CardStorageReader(
                emptyCards.toString(), null, true);
        new StaticData(reader, null,
                emptyEditions.toString(),
                emptyCustomEd.toString(),
                emptyBlock.toString(),
                "latest", true, true);
        Assert.assertNotNull(StaticData.instance());
        Assert.assertNotNull(StaticData.instance().getCommonCards(),
                "cards test setup must install a non-null CardDb");
        cardDbReady = true;
    }

    private static void prepareSoloPlayer(final AdventurePlayer p, final int gold) throws Exception {
        setField(p, "name", GUEST_NAME);
        setField(p, "characterId", null);
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
        // Mint a stable id for this solo save (persisted on next save()).
        Assert.assertNotNull(p.getCharacterId());
        Assert.assertFalse(p.getCharacterId().isEmpty());
        // Validate UUID shape for TR1 peer-id consumers.
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
        Assert.assertTrue(inventoryHas(p, LOOT_ITEM), "expected item " + LOOT_ITEM);
    }

    private static void embedCardsPayload(final String characterId, final String cardLine)
            throws Exception {
        final SaveFileData data = CoopCharacterStore.readRaw(characterId);
        Assert.assertNotNull(data);
        data.storeObject("cards", new String[]{cardLine});
        CoopCharacterStore.writeRawForTests(characterId, data);
    }

    private static void stripCardsPayload(final String characterId) throws Exception {
        final SaveFileData data = CoopCharacterStore.readRaw(characterId);
        Assert.assertNotNull(data);
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

    private static boolean rawHasItem(final SaveFileData data, final String itemName) {
        final Object raw = data.readObject("inventory");
        if (!(raw instanceof ItemData[])) {
            return false;
        }
        for (final ItemData i : (ItemData[]) raw) {
            if (i != null && itemName.equals(i.name)) {
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

    private static void setGuestJoining(final CoopSession session) throws Exception {
        setField(session, "role", CoopSessionRole.GUEST);
        setField(session, "state", CoopSession.State.JOINING);
        final Field restoreDone = CoopSession.class.getDeclaredField("guestRestoreDone");
        restoreDone.setAccessible(true);
        ((AtomicBoolean) restoreDone.get(session)).set(false);
        final Field pending = CoopSession.class.getDeclaredField("guestSoloRestorePending");
        pending.setAccessible(true);
        ((AtomicBoolean) pending.get(session)).set(false);
    }

    private static void resetSessionFields() throws Exception {
        final CoopSession session = CoopSession.get();
        setField(session, "role", CoopSessionRole.NONE);
        setField(session, "state", CoopSession.State.IDLE);
        setField(session, "guestWorldBackup", null);
        setField(session, "guestPlayerBackup", null);
        setField(session, "guestMultiverseBackup", null);
        setField(session, "guestCharacterId", null);
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
        final File legacy = CoopCharacterStore.legacyCharacterFile(name);
        if (legacy.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            legacy.delete();
        }
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
            if ("getVersion".equals(n) || "getJavaHeap".equals(n) || "getNativeHeap".equals(n)
                    || "getMaxListeners".equals(n)) {
                return 0;
            }
            if ("logLevel".equals(n) || "getLogLevel".equals(n)) {
                return Application.LOG_NONE;
            }
            if ("getClipboard".equals(n) || "getAudio".equals(n) || "getInput".equals(n)
                    || "getFiles".equals(n) || "getNet".equals(n) || "getGraphics".equals(n)
                    || "getApplicationListener".equals(n) || "getPreferences".equals(n)) {
                return null;
            }
            if ("exit".equals(n) || "log".equals(n) || "debug".equals(n) || "error".equals(n)
                    || "setLogLevel".equals(n) || "addLifecycleListener".equals(n)
                    || "removeLifecycleListener".equals(n)) {
                return null;
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
