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
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.util.IterableUtil;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * AC1 real-data reachability against the live card / edition DB (not test setters).
 * ZEN keeps every main-list card under Ascendant filters; LEA excludes Black Lotus
 * and the Moxen via {@code restrictedCards}.
 *
 * <p>Isolation: suite-wide {@code forge.test.userDir} / {@code test-user-home}
 * + {@link AdventureTestBootstrapListener} (CO1 #38), plus {@link AccountStore}
 * adventure-root override.
 */
public class AchievementsAc1RealDbTest {

    private static Path tempUserDir;
    private static Path realUserDir;
    private static java.util.Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static StaticData magicDb;
    private static StaticData previousStaticData;
    private static ConfigData ascendantConfig;
    private static String initError;

    @BeforeClass
    public void loadRealCardDb() {
        try {
            tempUserDir = AdventureTestUserDir.configuredTestUserDir();
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
            AdventureTestUserDir.requireIsolatedUserDir();
            AccountStore.setAdventureRootOverrideForTest(new File(ForgeConstants.USER_ADVENTURE_DIR));

            Path forgeGuiDir = resolveForgeGuiDir();
            Assert.assertTrue(Files.isDirectory(forgeGuiDir.resolve("res/editions")),
                    "editions dir missing under " + forgeGuiDir);
            Assert.assertTrue(Files.isDirectory(forgeGuiDir.resolve("res/cardsfolder")),
                    "cardsfolder missing under " + forgeGuiDir);

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

            previousStaticData = readStaticDataInstance();
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
            // Reset AC1 pins; restore prior StaticData; leave suite GuiBase to bootstrap.
            RewardData.invalidateRewardFilterCache();
            AchievementSetTracker.setMagicDbForTest(null);
            pinStaticData(previousStaticData);
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

    private static StaticData readStaticDataInstance() {
        try {
            Field instance = StaticData.class.getDeclaredField("lastInstance");
            instance.setAccessible(true);
            return (StaticData) instance.get(null);
        } catch (ReflectiveOperationException e) {
            return null;
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
}
