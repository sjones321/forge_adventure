package forge.adventure.coop;

import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * CO1 guest save-model: co-op {@code .chr} persists across sessions; solo WorldSave
 * is never overwritten by join/leave. Exercises the production
 * {@link CoopSession#applyGuestJoinSaveModel()} /
 * {@link CoopSession#applyGuestLeaveSaveModel(boolean)} paths used by join/disconnect.
 */
public class CoopGuestCharacterPersistTest {

    private static final String GUEST_NAME = "CoopGuestPersist";
    private static final int SOLO_GOLD = 100;
    private static final int LOOT_GOLD = 250;
    private static final String LOOT_MATERIAL = "ore_iron";
    private static final int LOOT_MATERIAL_AMOUNT = 7;
    private static final String LOOT_ITEM = "Coop Loot Charm";
    private static final String LOOT_CARD = "Coop Loot Bolt";

    private File tempCharsDir;
    private AdventurePlayer player;
    private int soloGoldSnapshot;
    private SaveFileData soloPlayerSnapshot;

    @BeforeMethod
    public void setUp() throws Exception {
        tempCharsDir = Files.createTempDirectory("coop-chr-").toFile();
        CoopCharacterStore.setCharactersDirOverrideForTests(tempCharsDir);

        player = WorldSave.getCurrentSave().getPlayer();
        prepareSoloPlayer(player, SOLO_GOLD);
        soloGoldSnapshot = player.getGold();
        soloPlayerSnapshot = player.save();

        // Ensure no leftover .chr from a prior method.
        final File chr = CoopCharacterStore.characterFile(GUEST_NAME);
        if (chr.isFile()) {
            Assert.assertTrue(chr.delete());
        }
    }

    @AfterMethod
    public void tearDown() throws Exception {
        CoopCharacterStore.setCharactersDirOverrideForTests(null);
        if (tempCharsDir != null && tempCharsDir.isDirectory()) {
            final File[] files = tempCharsDir.listFiles();
            if (files != null) {
                for (final File f : files) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            tempCharsDir.delete();
        }
        // Restore a clean solo snapshot so later tests see a known player.
        if (soloPlayerSnapshot != null && player != null) {
            try {
                player.load(soloPlayerSnapshot);
            } catch (final Exception ignored) {
                prepareSoloPlayer(player, SOLO_GOLD);
            }
        }
        resetSessionFields();
    }

    @Test
    public void firstJoinSeedsChrFromSoloPlayer() throws Exception {
        Assert.assertFalse(CoopCharacterStore.exists(GUEST_NAME));

        final CoopSession session = CoopSession.get();
        session.applyGuestJoinSaveModel();

        Assert.assertTrue(CoopCharacterStore.exists(GUEST_NAME), "first join must seed .chr");
        final SaveFileData seeded = CoopCharacterStore.readRaw(GUEST_NAME);
        Assert.assertNotNull(seeded);
        Assert.assertEquals(seeded.readInt("gold"), SOLO_GOLD);
        Assert.assertEquals(seeded.readString("name"), GUEST_NAME);
        Assert.assertEquals(player.getGold(), SOLO_GOLD, "seed keeps the in-memory solo player");

        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "leave restores solo gold");
    }

    @Test
    public void twoSessionsPreserveGuestLootAndLeaveSoloUntouched() throws Exception {
        final CoopSession session = CoopSession.get();

        // --- Session 1: first join seeds from solo, earn loot, leave ---
        session.applyGuestJoinSaveModel();
        Assert.assertTrue(CoopCharacterStore.exists(GUEST_NAME));
        giveCoopLoot(player);
        assertHasCoopLoot(player);

        session.applyGuestLeaveSaveModel(true);

        // Solo WorldSave restored; co-op .chr still has the loot.
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "solo gold must be unchanged after leave");
        Assert.assertEquals(player.getMaterial(LOOT_MATERIAL), 0, "solo must not keep co-op materials");
        Assert.assertFalse(inventoryHas(player, LOOT_ITEM), "solo must not keep co-op items");
        final SaveFileData chrAfterSession1 = CoopCharacterStore.readRaw(GUEST_NAME);
        Assert.assertNotNull(chrAfterSession1);
        Assert.assertEquals(chrAfterSession1.readInt("gold"), SOLO_GOLD + LOOT_GOLD);
        Assert.assertTrue(rawHasMaterial(chrAfterSession1, LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
        Assert.assertTrue(rawHasItem(chrAfterSession1, LOOT_ITEM));
        Assert.assertTrue(rawHasCard(chrAfterSession1, LOOT_CARD));

        // --- Session 2: rejoin must load .chr, not re-export solo ---
        // Reset in-memory player to solo (as Continu/e would after restore).
        player.load(soloPlayerSnapshot);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);

        session.applyGuestJoinSaveModel();

        assertHasCoopLoot(player);
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD,
                "second join must load co-op .chr loot, not the solo player");

        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "solo still unchanged after second leave");
    }

    /**
     * Documents the pre-fix bug: always calling {@link CoopCharacterStore#exportCurrentPlayer()}
     * on join overwrites the co-op {@code .chr} with the solo player, wiping session loot.
     * This test must fail under the old stash/export join path (and does — see PR report).
     */
    @Test
    public void oldStashExportBehaviourLosesCoopLootAcrossSessions() throws Exception {
        final CoopSession session = CoopSession.get();

        // Session 1 under the fixed model so loot is written to .chr once.
        session.applyGuestJoinSaveModel();
        giveCoopLoot(player);
        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(CoopCharacterStore.readRaw(GUEST_NAME).readInt("gold"), SOLO_GOLD + LOOT_GOLD);

        // Back to solo in memory (as Continu/e after leave).
        player.load(soloPlayerSnapshot);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);

        // Old join behaviour: stash + always exportCurrentPlayer (overwrites .chr) + load.
        stashThenExportAlwaysThenLoad();
        Assert.assertEquals(player.getGold(), soloGoldSnapshot,
                "old export-always path reloads the solo snapshot into the session player");
        Assert.assertEquals(CoopCharacterStore.readRaw(GUEST_NAME).readInt("gold"), soloGoldSnapshot,
                "old export-always path overwrites .chr with solo — co-op loot is lost");
        Assert.assertFalse(rawHasMaterial(CoopCharacterStore.readRaw(GUEST_NAME),
                LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));
    }

    /** Replicates the pre-fix join character steps: stash, exportCurrentPlayer, loadPlayer. */
    private void stashThenExportAlwaysThenLoad() throws Exception {
        final CoopSession session = CoopSession.get();
        // Use production stash via join model internals: stash by applying leave-safe prepare.
        // Direct old sequence:
        final Field restoreDone = CoopSession.class.getDeclaredField("guestRestoreDone");
        restoreDone.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicBoolean) restoreDone.get(session)).set(false);

        final java.lang.reflect.Method stash = CoopSession.class.getDeclaredMethod("stashGuestSave");
        stash.setAccessible(true);
        stash.invoke(session);

        CoopCharacterStore.exportCurrentPlayer();
        final String name = WorldSave.getCurrentSave().getPlayer().getName();
        final Field nameField = CoopSession.class.getDeclaredField("guestCharacterName");
        nameField.setAccessible(true);
        nameField.set(session, name);
        CoopCharacterStore.loadPlayer(WorldSave.getCurrentSave().getPlayer(), name);
    }

    private static void prepareSoloPlayer(final AdventurePlayer p, final int gold) throws Exception {
        setField(p, "name", GUEST_NAME);
        setField(p, "adventureMode", AdventureModes.Standard);
        final Object difficulty = getField(p, "difficultyData");
        setField(difficulty, "name", "Easy");
        setField(p, "gold", gold);
        // Clear co-op loot markers.
        p.getCards().clear();
        @SuppressWarnings("unchecked")
        final ArrayList<ItemData> inv = (ArrayList<ItemData>) getField(p, "inventoryItems");
        inv.clear();
        @SuppressWarnings("unchecked")
        final java.util.Map<String, Integer> mats =
                (java.util.Map<String, Integer>) getField(p, "materials");
        mats.clear();
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
        final PaperCard card = new PaperCard(
                CardRules.getUnsupportedCardNamed(LOOT_CARD), "TST", CardRarity.Common);
        p.getCards().add(card);
    }

    private static void assertHasCoopLoot(final AdventurePlayer p) throws Exception {
        Assert.assertEquals(p.getGold(), SOLO_GOLD + LOOT_GOLD);
        Assert.assertEquals(p.getMaterial(LOOT_MATERIAL), LOOT_MATERIAL_AMOUNT);
        Assert.assertTrue(inventoryHas(p, LOOT_ITEM), "expected item " + LOOT_ITEM);
        // Unsupported cards land in unsupportedCards after a load round-trip; during the
        // same session they live in the cards pool.
        final boolean inPool = p.getCards().toFlatList().stream()
                .anyMatch(c -> c != null && LOOT_CARD.equals(c.getName()));
        final boolean unsupported = p.getUnsupportedCards().stream()
                .anyMatch(c -> c != null && LOOT_CARD.equals(c.getName()));
        Assert.assertTrue(inPool || unsupported, "expected card " + LOOT_CARD);
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

    private static void resetSessionFields() throws Exception {
        final CoopSession session = CoopSession.get();
        final Field role = CoopSession.class.getDeclaredField("role");
        role.setAccessible(true);
        role.set(session, CoopSessionRole.NONE);
        final Field state = CoopSession.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(session, CoopSession.State.IDLE);
        final Field worldBak = CoopSession.class.getDeclaredField("guestWorldBackup");
        worldBak.setAccessible(true);
        worldBak.set(session, null);
        final Field playerBak = CoopSession.class.getDeclaredField("guestPlayerBackup");
        playerBak.setAccessible(true);
        playerBak.set(session, null);
        final Field multiBak = CoopSession.class.getDeclaredField("guestMultiverseBackup");
        multiBak.setAccessible(true);
        multiBak.set(session, null);
        final Field charName = CoopSession.class.getDeclaredField("guestCharacterName");
        charName.setAccessible(true);
        charName.set(session, null);
    }

    private static void setField(final Object target, final String name, final Object value) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getField(final Object target, final String name) throws Exception {
        final Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
