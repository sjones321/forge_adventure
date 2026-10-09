package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AccountStore;
import forge.adventure.player.AchievementSetTracker;
import forge.adventure.util.Config;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.util.IterableUtil;
import forge.util.Lang;
import forge.util.Localizer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiGame;
import org.jupnp.UpnpServiceConfiguration;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * AC1 real-data reachability against the live card / edition DB (not test setters).
 * ZEN keeps every main-list card under Ascendant filters; LEA excludes Black Lotus
 * and the Moxen via {@code restrictedCards}.
 *
 * <p>Isolation: {@link AdventureTestUserDir} + {@code forge.test.userDir} (CO1 #38)
 * so reward-filter / Config touches cannot rewrite the real user dir.
 */
public class AchievementsAc1RealDbTest {

    private static Path tempUserDir;
    private static Path realUserDir;
    private static java.util.Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static StaticData magicDb;
    private static ConfigData ascendantConfig;
    private static String initError;

    @BeforeClass
    public void loadRealCardDb() {
        try {
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
            tempUserDir = AchievementsAc1Test.ensureIsolatedUserDir();
            AccountStore.setAdventureRootOverrideForTest(tempUserDir.toFile());

            Path forgeGuiDir = resolveForgeGuiDir();
            Assert.assertTrue(Files.isDirectory(forgeGuiDir.resolve("res/editions")),
                    "editions dir missing under " + forgeGuiDir);
            Assert.assertTrue(Files.isDirectory(forgeGuiDir.resolve("res/cardsfolder")),
                    "cardsfolder missing under " + forgeGuiDir);

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
            Assert.assertNotNull(ascendantConfig.restrictedCards);
            Assert.assertTrue(ascendantConfig.restrictedCards.length > 0);
            Assert.assertTrue(ascendantConfig.ascendantRules);

            // Production filter path (not a test-written predicate).
            List<Predicate<PaperCard>> filters = RewardData.baseAdventureRewardFilters(ascendantConfig);
            Assert.assertFalse(filters.isEmpty(), "production Ascendant filters must be non-empty");
            Set<String> restricted = new HashSet<>(List.of(ascendantConfig.restrictedCards));
            Predicate<PaperCard> filter = IterableUtil.and(filters);
            RewardData.installRewardFilterForTest(filter, restricted);
        } catch (Throwable t) {
            initError = t.getClass().getSimpleName() + ": " + t.getMessage();
            t.printStackTrace();
        }
    }

    @AfterClass(alwaysRun = true)
    public void resetPinnedStateAndAssertRealUserDirUntouched() throws Exception {
        try {
            RewardData.invalidateRewardFilterCache();
            AchievementSetTracker.setMagicDbForTest(null);
            pinStaticData(null);
            GuiBase.setInterface(null);
            AccountStore.resetAdventureRootOverrideForTest();
            Config.resetInstanceForTest();
        } finally {
            if (realUserDirSnapshot != null) {
                AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                        "AchievementsAc1RealDbTest");
            }
        }
    }

    @Test(timeOut = 600_000)
    public void zenKeepsEveryCardLeaExcludesLotusAndMoxen() {
        Assert.assertNull(initError, "real card DB failed to load: " + initError);
        Assert.assertNotNull(magicDb);
        Assert.assertNotNull(ascendantConfig);
        pinStaticData(magicDb);
        AchievementSetTracker.setMagicDbForTest(magicDb);

        AchievementSetTracker tracker = new AchievementSetTracker();
        Set<String> zenRaw = AchievementSetTracker.rawMainListNames("ZEN");
        Set<String> leaRaw = AchievementSetTracker.rawMainListNames("LEA");
        Assert.assertFalse(zenRaw.isEmpty(), "ZEN main list should load from editions");
        Assert.assertFalse(leaRaw.isEmpty(), "LEA main list should load from editions");
        Assert.assertTrue(leaRaw.contains("Black Lotus"), "LEA should list Black Lotus");
        Assert.assertTrue(leaRaw.contains("Mox Pearl"), "LEA should list Mox Pearl");

        // Sanity: common-cards DB + pinned filter must accept a ZEN staple.
        // Use getAllCards (not getCard) so ImageKeys art lookup is not required.
        List<PaperCard> guides = magicDb.getCommonCards().getAllCards("Goblin Guide");
        Assert.assertFalse(guides == null || guides.isEmpty(), "Goblin Guide must resolve from real card DB");
        Assert.assertTrue(RewardData.isAdventureRewardReachable(guides.get(0)),
                "pinned filter must accept Goblin Guide");
        Assert.assertTrue(RewardData.isAdventureRewardReachableName("Goblin Guide", magicDb),
                "isAdventureRewardReachableName(Goblin Guide) must be true");

        Set<String> zenFiltered = tracker.filteredNames("ZEN");
        Set<String> leaFiltered = tracker.filteredNames("LEA");
        Set<String> restricted = new HashSet<>(List.of(ascendantConfig.restrictedCards));

        // ZEN has no Ascendant-restricted names; every main-list card that production
        // baseAdventureRewardFilters(ascendantConfig) accepts must appear.
        Assert.assertTrue(java.util.Collections.disjoint(zenRaw, restricted),
                "ZEN should not list Ascendant restrictedCards");
        Set<String> zenMissing = new HashSet<>(zenRaw);
        zenMissing.removeAll(zenFiltered);
        Assert.assertTrue(zenMissing.isEmpty(),
                "ZEN should keep every main-list card under production Ascendant filters; missing="
                        + zenMissing.stream().limit(20).toList()
                        + " (raw=" + zenRaw.size() + " filtered=" + zenFiltered.size() + ")");

        // LEA: power nine restricted names must be excluded by the same production filter.
        Assert.assertFalse(leaFiltered.contains("Black Lotus"));
        for (String mox : new String[] {
                "Mox Pearl", "Mox Sapphire", "Mox Jet", "Mox Ruby", "Mox Emerald"
        }) {
            Assert.assertTrue(leaRaw.contains(mox), "LEA should list " + mox);
            Assert.assertFalse(leaFiltered.contains(mox), "LEA filtered list must exclude " + mox);
            Assert.assertTrue(restricted.contains(mox), mox + " should be in Ascendant restrictedCards");
        }
        Assert.assertTrue(restricted.contains("Black Lotus"));
        Assert.assertTrue(leaFiltered.size() < leaRaw.size(),
                "LEA filtered list must be smaller than the raw main list");
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
        throw new IllegalStateException("Could not locate forge-gui/res/editions from " + Path.of(".").toAbsolutePath());
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
        @Override public String getCurrentVersion() { return "ac1-test"; }
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
