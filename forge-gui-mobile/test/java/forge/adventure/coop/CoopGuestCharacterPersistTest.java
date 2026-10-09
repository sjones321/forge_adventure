package forge.adventure.coop;

import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.util.Localizer;
import org.jupnp.UpnpServiceConfiguration;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

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
    /** Card-list line stored in the .chr payload (card DB not loaded in this headless suite). */
    private static final String LOOT_CARD_LINE = "1 Coop Loot Bolt";

    private File tempCharsDir;
    private AdventurePlayer player;
    private int soloGoldSnapshot;
    private SaveFileData soloPlayerSnapshot;

    @BeforeClass
    public static void initHeadlessGui() {
        // ForgeConstants / WorldSave need assets dir + Localizer before class init.
        final String assets = Files.exists(Paths.get("./forge-gui"))
                ? "./forge-gui/"
                : Files.exists(Paths.get("../forge-gui"))
                ? "../forge-gui/"
                : "./";
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new HeadlessAssetsGui(assets));
        }
        Localizer.getInstance().initialize("en-US", assets + "res/languages");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        tempCharsDir = Files.createTempDirectory("coop-chr-").toFile();
        CoopCharacterStore.setCharactersDirOverrideForTests(tempCharsDir);

        player = WorldSave.getCurrentSave().getPlayer();
        prepareSoloPlayer(player, SOLO_GOLD);
        soloGoldSnapshot = player.getGold();
        soloPlayerSnapshot = player.save();

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
        // Embed a cards payload into the co-op .chr (same file AdventurePlayer.save writes).
        // Headless tests have no card DB, so cards are asserted at the .chr layer.
        embedCardsPayload(GUEST_NAME, LOOT_CARD_LINE);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(GUEST_NAME), "Coop Loot Bolt"));

        // --- Session 2: rejoin must load .chr, not re-export solo ---
        // Reload solo without cards so AdventurePlayer.load does not need StaticData.
        player.load(soloPlayerSnapshot);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);
        // Strip cards from .chr before loadOrSeed (avoids StaticData); loot gold/items/mats remain.
        stripCardsPayload(GUEST_NAME);
        Assert.assertTrue(rawHasMaterial(CoopCharacterStore.readRaw(GUEST_NAME),
                LOOT_MATERIAL, LOOT_MATERIAL_AMOUNT));

        session.applyGuestJoinSaveModel();

        assertHasCoopLoot(player);
        Assert.assertEquals(player.getGold(), SOLO_GOLD + LOOT_GOLD,
                "second join must load co-op .chr loot, not the solo player");

        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot, "solo still unchanged after second leave");
    }

    @Test
    public void chrPayloadKeepsCardsAcrossAtomicSaveAndOldExportWipesThem() throws Exception {
        // Seed a .chr from solo, then embed cards into the same on-disk format.
        CoopSession.get().applyGuestJoinSaveModel();
        CoopSession.get().applyGuestLeaveSaveModel(true);
        embedCardsPayload(GUEST_NAME, LOOT_CARD_LINE);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(GUEST_NAME), "Coop Loot Bolt"));

        // Atomic re-save of the same payload must keep cards.
        final SaveFileData withCards = CoopCharacterStore.readRaw(GUEST_NAME);
        CoopCharacterStore.writeRawForTests(GUEST_NAME, withCards);
        Assert.assertTrue(rawHasCard(CoopCharacterStore.readRaw(GUEST_NAME), "Coop Loot Bolt"));

        // Old export-always join overwrites .chr from solo (no cards) — loot cards lost.
        player.load(soloPlayerSnapshot);
        stashThenExportAlwaysThenLoad();
        Assert.assertFalse(rawHasCard(CoopCharacterStore.readRaw(GUEST_NAME), "Coop Loot Bolt"),
                "old export-always join wipes the cards payload from .chr");
    }

    /**
     * Documents the pre-fix bug: always calling {@link CoopCharacterStore#exportCurrentPlayer()}
     * on join overwrites the co-op {@code .chr} with the solo player, wiping session loot.
     * Confirmed to lose loot under the old stash/export join path (see PR report).
     */
    @Test
    public void oldStashExportBehaviourLosesCoopLootAcrossSessions() throws Exception {
        final CoopSession session = CoopSession.get();

        session.applyGuestJoinSaveModel();
        giveCoopLoot(player);
        session.applyGuestLeaveSaveModel(true);
        Assert.assertEquals(CoopCharacterStore.readRaw(GUEST_NAME).readInt("gold"), SOLO_GOLD + LOOT_GOLD);

        player.load(soloPlayerSnapshot);
        Assert.assertEquals(player.getGold(), soloGoldSnapshot);

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
    }

    private static void assertHasCoopLoot(final AdventurePlayer p) throws Exception {
        Assert.assertEquals(p.getGold(), SOLO_GOLD + LOOT_GOLD);
        Assert.assertEquals(p.getMaterial(LOOT_MATERIAL), LOOT_MATERIAL_AMOUNT);
        Assert.assertTrue(inventoryHas(p, LOOT_ITEM), "expected item " + LOOT_ITEM);
    }

    private static void embedCardsPayload(final String characterName, final String cardLine)
            throws Exception {
        final SaveFileData data = CoopCharacterStore.readRaw(characterName);
        Assert.assertNotNull(data);
        data.storeObject("cards", new String[]{cardLine});
        CoopCharacterStore.writeRawForTests(characterName, data);
    }

    private static void stripCardsPayload(final String characterName) throws Exception {
        final SaveFileData data = CoopCharacterStore.readRaw(characterName);
        Assert.assertNotNull(data);
        data.storeObject("cards", new String[0]);
        CoopCharacterStore.writeRawForTests(characterName, data);
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

    /** Minimal IGuiBase so ForgeConstants can resolve ASSETS_DIR in headless tests. */
    private static final class HeadlessAssetsGui implements IGuiBase {
        private final String assetsDir;

        private HeadlessAssetsGui(final String assetsDir) {
            this.assetsDir = assetsDir.endsWith("/") || assetsDir.endsWith(File.separator)
                    ? assetsDir : assetsDir + File.separator;
        }

        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return false; }
        @Override public String getCurrentVersion() { return "test"; }
        @Override public void invokeInEdtNow(final Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtLater(final Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtAndWait(final Runnable proc) { proc.run(); }
        @Override public void runBackgroundTask(final String message, final Runnable task) { task.run(); }
        @Override public boolean isGuiThread() { return true; }
        @Override public String getAssetsDir() { return assetsDir; }
        @Override public ImageFetcher getImageFetcher() { return null; }
        @Override public ISkinImage getSkinIcon(final FSkinProp skinProp) { return null; }
        @Override public ISkinImage getUnskinnedIcon(final String path) { return null; }
        @Override public ISkinImage getCardArt(final PaperCard card, final boolean backFace) { return null; }
        @Override public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background,
                final String overlayFilename, final float opacity) { return null; }
        @Override public void clearImageCache() { }
        @Override public String encodeSymbols(final String str, final boolean formatReminderText) { return str; }
        @Override public int getAvatarCount() { return 0; }
        @Override public int getSleevesCount() { return 0; }
        @Override public float getScreenScale() { return 1f; }
        @Override public void preventSystemSleep(final boolean preventSleep) { }
        @Override public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
            callback.accept(false);
        }
        @Override public void copyToClipboard(final String text) { }
        @Override public void browseToUrl(final String url) throws IOException, URISyntaxException { }
        @Override public void showCardList(final String title, final String message, final List<PaperCard> list) { }
        @Override public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) {
            return false;
        }
        @Override public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) { }
        @Override public void showImageDialog(final ISkinImage image, final String message, final String title) { }
        @Override public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                final List<String> options, final int defaultOption) { return defaultOption; }
        @Override public String showInputDialog(final String message, final String title, final FSkinProp icon,
                final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
            return initialInput;
        }
        @Override public String showFileDialog(final String title, final String defaultDir) { return defaultDir; }
        @Override public File getSaveFile(final File defaultFile) { return defaultFile; }
        @Override public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
                final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) {
            return destChoices;
        }
        @Override public <T> List<T> getChoices(final String message, final int min, final int max,
                final Collection<T> choices, final Collection<T> selected,
                final FSerializableFunction<T, String> display) {
            return new ArrayList<>(selected);
        }
        @Override public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
            return list.isEmpty() ? null : list.get(0);
        }
        @Override public boolean isSupportedAudioFormat(final File file) { return false; }
        @Override public IAudioClip createAudioClip(final String filename) { return null; }
        @Override public IAudioMusic createAudioMusic(final String filename) { return null; }
        @Override public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }
        @Override public void showSpellShop() { }
        @Override public void showBazaar() { }
        @Override public IGuiGame getNewGuiGame() { return null; }
        @Override public HostedMatch hostMatch() { return null; }
        @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
        @Override public boolean hasNetGame() { return false; }
    }
}
