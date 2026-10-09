package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AchievementService;
import forge.adventure.player.AchievementSetTracker;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.sound.SoundSystem;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.util.Lang;
import forge.util.Localizer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiGame;
import org.jupnp.UpnpServiceConfiguration;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * AC1 production-path hooks: {@link AdventurePlayer#create} rebuilds nameCounts
 * (save A → save B), and sell / salvage / auto-salvage / {@link AdventurePlayer#removeLostCardFromPools}
 * un-own the last copy via {@code afterCardsRemoved}. Does not use
 * {@link AchievementSetTracker#setNameCountForTest} or call
 * {@link AchievementService#onPlayerCollectionReady} / {@code applyRemoveNames} directly.
 *
 * <p>Isolation: {@link AdventureTestUserDir} + {@code forge.test.userDir} (CO1 #38)
 * before {@link Config#instance()} / {@code AdventurePlayer.create}.
 */
@Test(singleThreaded = true)
public class AchievementsAc1PlayerHooksTest {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static Path tempUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static StaticData magicDb;
    private static ConfigData ascendantConfig;
    private static String initError;

    private Path tempDir;
    private AchievementService svc;

    private static final String DEFS = "[\n"
            + "  {\"id\":\"hook_marker\",\"name\":\"Hook Marker\",\"description\":\"unused\","
            + "\"category\":\"collection\",\"condition\":{\"type\":\"counter\",\"key\":\"never\",\"count\":99},"
            + "\"hidden\":true,\"reward\":{\"type\":\"trophy\",\"id\":\"t\"}}\n"
            + "]\n";

    @BeforeClass
    public void bootstrapRealDbAndAscendant() {
        try {
            // Snapshot real OS user dir BEFORE ForgeConstants / profile load can touch it.
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
            tempUserDir = AchievementsAc1Test.ensureIsolatedUserDir();

            Path forgeGuiDir = resolveForgeGuiDir();
            // GuiBase before ForgeConstants clinit (ASSETS_DIR).
            if (GuiBase.getInterface() == null) {
                GuiBase.setInterface(new HeadlessAssetsGui(forgeGuiDir));
            }
            AdventureTestUserDir.assertConstantsUse(tempUserDir);

            Lang.createInstance("en-US");
            String langDir = forgeGuiDir.resolve("res/languages").toAbsolutePath().normalize()
                    + File.separator;
            Localizer.getInstance().initialize("en-US", langDir);
            ImageKeys.initializeDirs("", new HashMap<>(), "", "", "", "", "", "", "");
            SoundSystem.instance.setIgnorePlayRequests(true);

            String res = forgeGuiDir.resolve("res").toAbsolutePath().normalize() + File.separator;
            String cards = res + "cardsfolder" + File.separator;
            String editions = res + "editions" + File.separator;
            String tokens = res + "tokenscripts" + File.separator;
            String blocks = res + "blockdata" + File.separator;
            String customCards = forgeGuiDir.resolve("custom").toAbsolutePath().normalize()
                    + File.separator + "cards" + File.separator;
            String customEditions = forgeGuiDir.resolve("custom").toAbsolutePath().normalize()
                    + File.separator + "editions" + File.separator;

            final CardStorageReader reader = new CardStorageReader(cards, null, false);
            CardStorageReader customReader = null;
            try {
                if (Files.isDirectory(Path.of(customCards))) {
                    customReader = new CardStorageReader(customCards, null, false);
                }
            } catch (Exception ignored) {
            }
            final CardStorageReader tokenReader = new CardStorageReader(tokens, null, false);
            magicDb = new StaticData(reader, tokenReader, customReader, null, editions,
                    customEditions, blocks, "",
                    "Latest Art All Editions", true, false, false, false);
            pinStaticData(magicDb);
            AchievementSetTracker.setMagicDbForTest(magicDb);

            Path cfgPath = forgeGuiDir.resolve("res/adventure/Shandalar Ascendant/config.json");
            Assert.assertTrue(Files.isRegularFile(cfgPath), "Ascendant config missing: " + cfgPath);
            ascendantConfig = new Json().fromJson(ConfigData.class, new FileHandle(cfgPath.toFile()));
            Assert.assertNotNull(ascendantConfig);
            Assert.assertTrue(ascendantConfig.ascendantRules);

            Config.resetInstanceForTest();
            Config.instance(); // settings.json under isolated USER_ADVENTURE_DIR
            Config.installConfigDataForTest(ascendantConfig);
            Assert.assertTrue(Config.ascendant(), "hooks require Ascendant rules");
        } catch (Throwable t) {
            initError = t.getClass().getSimpleName() + ": " + t.getMessage();
            t.printStackTrace();
        }
    }

    @AfterClass(alwaysRun = true)
    public void assertRealUserDirUntouched() throws Exception {
        if (realUserDirSnapshot != null) {
            AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                    "AchievementsAc1PlayerHooksTest");
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {
        LOCK.lock();
        try {
            Assert.assertNull(initError, "bootstrap failed: " + initError);
            pinStaticData(magicDb);
            AchievementSetTracker.setMagicDbForTest(magicDb);
            Config.installConfigDataForTest(ascendantConfig);
            // Keep auto-salvage keep-copies at the Ascendant default unless a test overrides it.
            Config.instance().getConfigData().autoSalvageKeepCopies = ascendantConfig.autoSalvageKeepCopies;

            AchievementService.resetInstance();
            AchievementListData.clear();
            AchievementListData.loadFromJsonText(DEFS);
            tempDir = Files.createTempDirectory("ac1-player-hooks");
            Path achievementsFile = tempDir.resolve("account").resolve("achievements.json");
            Files.createDirectories(achievementsFile.getParent());
            svc = AchievementService.forTest(achievementsFile.toFile());
            svc.setToastEnabled(false);
            AchievementService.setInstance(svc);
            // Avoid scanning every Bellwarden set when create() rebuilds ownership.
            svc.getSetTracker().setReachableForTest(Collections.emptyList());
        } catch (Exception e) {
            LOCK.unlock();
            throw e;
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        try {
            AchievementService.resetInstance();
            AchievementListData.clear();
            RewardData.invalidateRewardFilterCache();
            if (tempDir != null && Files.isDirectory(tempDir)) {
                try (var walk = Files.walk(tempDir)) {
                    walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    @Test
    public void nameCountsRebuildOnPlayerReadyDropsPriorSave() {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        PaperCard shock = card("Shock");
        PaperCard bolt = card("Lightning Bolt");

        // Save A (new game): collection includes Shock.
        player.create("SaveA", deckWith(shock, 2), true, 0, 0, false, false,
                easyDiff(), AdventureModes.Constructed);

        AchievementSetTracker tracker = svc.getSetTracker();
        Assert.assertTrue(tracker.isNameCountsReady());
        Assert.assertEquals(tracker.ownedCount("Shock"), 2);
        Assert.assertEquals(tracker.ownedCount("Lightning Bolt"), 0);

        // Save B (new game): different starter — A's cards must not count.
        player.create("SaveB", deckWith(bolt, 1), true, 0, 0, false, false,
                easyDiff(), AdventureModes.Constructed);

        Assert.assertEquals(tracker.ownedCount("Shock"), 0, "prior save ownership must be dropped");
        Assert.assertEquals(tracker.ownedCount("Lightning Bolt"), 1);
        Assert.assertFalse(tracker.getNameCounts().containsKey("Shock"));
        Assert.assertTrue(tracker.isNameCountsReady());
    }

    @DataProvider(name = "lastCopyRemovalPaths")
    public Object[][] lastCopyRemovalPaths() {
        return new Object[][] {
                { "sell" },
                { "salvage" },
                { "autoSalvage" },
                { "deckRemoval" },
        };
    }

    @Test(dataProvider = "lastCopyRemovalPaths")
    public void lastCopyRemovalUnOwnsCard(String path) {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        PaperCard shock = card("Shock");
        PaperCard plains = card("Plains");

        // Starter deck has no Shock so salvage / auto-salvage are not blocked by deck copies.
        player.create("Remover", deckWith(plains, 1), true, 0, 0, false, false,
                easyDiff(), AdventureModes.Constructed);

        AchievementSetTracker tracker = svc.getSetTracker();

        switch (path) {
            case "sell" -> {
                player.addCard(shock, 1);
                Assert.assertEquals(tracker.ownedCount("Shock"), 1);
                int sold = player.sellCard(shock, 1);
                Assert.assertEquals(sold, 1, "sellCard must sell the last free copy");
            }
            case "salvage" -> {
                player.addCard(shock, 1);
                Assert.assertEquals(tracker.ownedCount("Shock"), 1);
                int salvaged = player.salvageCard(shock, 1);
                Assert.assertEquals(salvaged, 1, "salvageCard must salvage the last free copy");
            }
            case "autoSalvage" -> {
                Config.instance().getConfigData().autoSalvageKeepCopies = 0;
                player.setAutoSalvage(true);
                Assert.assertTrue(player.isAutoSalvage());
                // addCard → maybeAutoSalvage → salvageCard → afterCardsRemoved
                player.addCard(shock, 1);
            }
            case "deckRemoval" -> {
                player.addCard(shock, 1);
                Assert.assertEquals(tracker.ownedCount("Shock"), 1);
                // Production deck-change path that drops a card from the collection pool.
                player.removeLostCardFromPools(shock);
            }
            default -> Assert.fail("unknown path: " + path);
        }

        Assert.assertEquals(tracker.ownedCount("Shock"), 0,
                path + " must un-own the last copy through the production path");
        Assert.assertFalse(tracker.getNameCounts().containsKey("Shock"),
                path + " must remove the name from nameCounts");
        Assert.assertEquals(player.getCards().count(shock), 0);
    }

    private static PaperCard card(String name) {
        List<PaperCard> all = magicDb.getCommonCards().getAllCards(name);
        Assert.assertFalse(all == null || all.isEmpty(), name + " must resolve from real card DB");
        return all.get(0);
    }

    private static Deck deckWith(PaperCard card, int count) {
        Deck deck = new Deck("hooks");
        deck.getMain().add(card, count);
        return deck;
    }

    private static DifficultyData easyDiff() {
        DifficultyData d = new DifficultyData();
        d.name = "Easy";
        d.startingLife = 20;
        d.startingMoney = 100;
        d.startingShards = 0;
        d.sellFactor = 0.2f;
        d.startItems = new String[0];
        return d;
    }

    private static void pinStaticData(StaticData data) {
        try {
            Field instance = StaticData.class.getDeclaredField("lastInstance");
            instance.setAccessible(true);
            instance.set(null, data);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Path resolveForgeGuiDir() {
        for (String rel : new String[] { "forge-gui", "../forge-gui" }) {
            Path p = Path.of(rel).toAbsolutePath().normalize();
            if (Files.isDirectory(p.resolve("res/editions"))) {
                return p;
            }
        }
        throw new IllegalStateException("Could not locate forge-gui/res/editions from "
                + Path.of(".").toAbsolutePath());
    }

    /** Minimal GuiBase so ForgeConstants.ASSETS_DIR points at forge-gui/ if touched. */
    private static final class HeadlessAssetsGui implements IGuiBase {
        private final String assetsDir;

        private HeadlessAssetsGui(Path forgeGui) {
            String abs = forgeGui.toAbsolutePath().normalize().toString();
            if (!abs.endsWith(File.separator)) {
                abs = abs + File.separator;
            }
            this.assetsDir = abs;
        }

        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return false; }
        @Override public String getCurrentVersion() { return "ac1-hooks-test"; }
        @Override public void invokeInEdtNow(Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtLater(Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtAndWait(Runnable proc) { proc.run(); }
        @Override public void runBackgroundTask(String message, Runnable task) { task.run(); }
        @Override public boolean isGuiThread() { return true; }
        @Override public String getAssetsDir() { return assetsDir; }
        @Override public ImageFetcher getImageFetcher() { return null; }
        @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return null; }
        @Override public ISkinImage getUnskinnedIcon(String path) { return null; }
        @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return null; }
        @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return null; }
        @Override public void clearImageCache() { }
        @Override public String encodeSymbols(String str, boolean formatReminderText) { return str; }
        @Override public int getAvatarCount() { return 0; }
        @Override public int getSleevesCount() { return 0; }
        @Override public float getScreenScale() { return 1f; }
        @Override public void preventSystemSleep(boolean preventSleep) { }
        @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { callback.accept(false); }
        @Override public void copyToClipboard(String text) { }
        @Override public void browseToUrl(String url) throws IOException, URISyntaxException { }
        @Override public void showCardList(String title, String message, List<PaperCard> list) { }
        @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
        @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) { }
        @Override public void showImageDialog(ISkinImage image, String message, String title) { }
        @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) { return defaultOption; }
        @Override public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) { return initialInput; }
        @Override public String showFileDialog(String title, String defaultDir) { return defaultDir; }
        @Override public File getSaveFile(File defaultFile) { return defaultFile; }
        @Override public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) { return destChoices; }
        @Override public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) { return new ArrayList<>(selected); }
        @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) { return list.isEmpty() ? null : list.get(0); }
        @Override public boolean isSupportedAudioFormat(File file) { return false; }
        @Override public IAudioClip createAudioClip(String filename) { return null; }
        @Override public IAudioMusic createAudioMusic(String filename) { return null; }
        @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
        @Override public void showSpellShop() { }
        @Override public void showBazaar() { }
        @Override public IGuiGame getNewGuiGame() { return null; }
        @Override public HostedMatch hostMatch() { return null; }
        @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
        @Override public boolean hasNetGame() { return false; }
    }
}
