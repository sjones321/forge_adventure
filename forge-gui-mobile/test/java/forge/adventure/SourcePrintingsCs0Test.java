package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.ImageKeys;
import forge.StaticData;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AccountStore;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.CardUtil;
import forge.adventure.util.Config;
import forge.adventure.util.GymUtil;
import forge.adventure.util.Reward;
import forge.adventure.util.SourcePrintings;
import forge.adventure.world.WorldSave;
import forge.card.CardEdition;
import forge.deck.Deck;
import forge.item.BoosterPack;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * CS0 behaviour: Ascendant rewards, shops, packs and Spell Smith use source-set
 * printings; junk/generic sources use a rotation/normal printing;
 * {@code useAllCardVariants=true} changes nothing under Ascendant.
 *
 * <p>Self-bootstraps Config + card DB in {@link BeforeClass} (same pattern as
 * {@link FightRewardsRw1Test}) so suite order cannot leave {@code Config} /
 * {@code StaticData} null or unpinned after RW1/AC1 teardown. Uses Surefire
 * {@code forge.test.userDir}; never touches the real user folder.
 */
@Test(singleThreaded = true)
public class SourcePrintingsCs0Test {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static Path realUserDir;
    private static java.util.Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static StaticData magicDb;
    private static StaticData previousStaticData;
    private static ConfigData ascendantConfig;
    private static ConfigData stockConfig;
    private static String initError;
    private static boolean savedUseAllCardVariants;

    @BeforeClass
    public void bootstrapConfigAndCardDb() {
        try {
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
            AdventureTestUserDir.requireIsolatedUserDir();
            AccountStore.setAdventureRootOverrideForTest(new File(ForgeConstants.USER_ADVENTURE_DIR));

            Path forgeGuiDir = resolveForgeGuiDir();
            Assert.assertTrue(Files.isDirectory(forgeGuiDir.resolve("res/editions")),
                    "editions dir missing under " + forgeGuiDir);
            Assert.assertTrue(Files.isDirectory(forgeGuiDir.resolve("res/cardsfolder")),
                    "cardsfolder missing under " + forgeGuiDir);

            try {
                Lang.createInstance("en-US");
                String langDir = forgeGuiDir.resolve("res/languages").toAbsolutePath().normalize()
                        + File.separator;
                Localizer.getInstance().initialize("en-US", langDir);
                ImageKeys.initializeDirs("", new HashMap<>(), "", "", "", "", "", "", "");
            } catch (Throwable ignored) {
                // Suite bootstrap may already have initialized these.
            }

            previousStaticData = readStaticDataInstance();

            // CardUtil / SourcePrintings / CardDb.lazyLoad need FModel's StaticData and
            // StaticData.instance() to be the *same* object. RW1/AC1 AfterClass can unpin
            // lastInstance to null; never pin a second StaticData or lazy lookup breaks.
            ensureFModelMagicDb();
            magicDb = FModel.getMagicDb();
            Assert.assertNotNull(magicDb, "FModel magic DB required for CS0");
            Assert.assertNotNull(magicDb.getCommonCards(), "FModel common cards required");
            Assert.assertNotNull(magicDb.getEditions().get("ZEN"), "ZEN edition required");
            pinStaticData(magicDb);

            Path cfgPath = forgeGuiDir.resolve("res/adventure/Shandalar Ascendant/config.json");
            Assert.assertTrue(Files.isRegularFile(cfgPath), "Ascendant config missing: " + cfgPath);
            ascendantConfig = new Json().fromJson(ConfigData.class, new FileHandle(cfgPath.toFile()));
            Assert.assertNotNull(ascendantConfig);
            Assert.assertTrue(ascendantConfig.ascendantRules);
            ascendantConfig.cs0SourcePrintings = true;
            // Tests rely on the full edition catalogue (ANB / LEA / …), not a plane allow-list.
            ascendantConfig.allowedEditions = null;
            Assert.assertTrue(ascendantConfig.cs0SourcePrintings, "Ascendant config must enable CS0");
            Assert.assertTrue(containsIgnoreCase(ascendantConfig.restrictedEditions, "UST"),
                    "Ascendant restrictedEditions must list UST");

            stockConfig = new ConfigData();
            stockConfig.ascendantRules = false;
            stockConfig.cs0SourcePrintings = true; // unused when not Ascendant

            // RW1/AC1 AfterClass calls Config.resetInstanceForTest() — reinstall ourselves.
            Config.resetInstanceForTest();
            Config.instance();
            Config.installConfigDataForTest(ascendantConfig);
            Assert.assertTrue(Config.ascendant());
            Assert.assertTrue(SourcePrintings.enabled());
            SourcePrintings.clearCaches();

            // Eagerly prove the printings these tests assert on (no silent skips later).
            warmRequiredPrintings();

            savedUseAllCardVariants = Config.instance().getSettingData().useAllCardVariants;
        } catch (Throwable t) {
            initError = t.getClass().getSimpleName() + ": " + t.getMessage();
            t.printStackTrace();
        }
    }

    /** Suite listener normally initializes FModel; call again only when missing. */
    private static void ensureFModelMagicDb() {
        try {
            if (FModel.getMagicDb() != null && FModel.getMagicDb().getCommonCards() != null) {
                return;
            }
        } catch (Throwable ignored) {
        }
        FModel.initialize(null, preferences -> {
            preferences.setPref(
                    forge.localinstance.properties.ForgePreferences.FPref.LOAD_CARD_SCRIPTS_LAZILY, true);
            preferences.setPref(
                    forge.localinstance.properties.ForgePreferences.FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(
                    forge.localinstance.properties.ForgePreferences.FPref.ENFORCE_DECK_LEGALITY, false);
            return null;
        });
    }

    /**
     * Force-load editions and cards the hard asserts need. Must run with
     * {@link StaticData#instance()} pinned to {@link #magicDb}.
     */
    private static void warmRequiredPrintings() {
        Assert.assertNotNull(magicDb.getEditions().get("ANB"), "ANB (ONLINE) required");
        Assert.assertNotNull(magicDb.getEditions().get("V15"), "V15 (COLLECTOR) required");
        Assert.assertNotNull(magicDb.getEditions().get("UST"), "UST (FUNNY/restricted) required");
        Assert.assertNotNull(magicDb.getEditions().get("PLST"), "PLST required");
        Assert.assertNotNull(magicDb.getEditions().get("MB1"), "MB1 required");
        Assert.assertEquals(magicDb.getEditions().get("ANB").getType(), CardEdition.Type.ONLINE);
        Assert.assertEquals(magicDb.getEditions().get("V15").getType(),
                CardEdition.Type.COLLECTOR_EDITION);
        Assert.assertEquals(magicDb.getEditions().get("UST").getType(), CardEdition.Type.FUNNY);

        // Lazy DB: getAllCards loads the script, then getCard(name, set) can resolve.
        List<PaperCard> shockAll = magicDb.getCommonCards().getAllCards("Shock");
        Assert.assertNotNull(shockAll, "Shock printings required");
        Assert.assertFalse(shockAll.isEmpty(), "Shock printings required");
        PaperCard anbShock = magicDb.getCommonCards().getCard("Shock", "ANB");
        if (anbShock == null) {
            for (PaperCard pc : shockAll) {
                if (pc != null && "ANB".equalsIgnoreCase(pc.getEdition())) {
                    anbShock = pc;
                    break;
                }
            }
        }
        Assert.assertNotNull(anbShock, "ANB Shock printing required after warm-load");

        List<PaperCard> lotusAll = magicDb.getCommonCards().getAllCards("Black Lotus");
        Assert.assertNotNull(lotusAll, "Black Lotus printings required");
        Assert.assertFalse(lotusAll.isEmpty(), "Black Lotus printings required");
        PaperCard lotus = SourcePrintings.printingFromRotation("Black Lotus", List.of());
        Assert.assertNotNull(lotus, "Black Lotus must resolve via most-recent normal fallback");
    }

    @AfterClass(alwaysRun = true)
    public void restorePinnedStateAndAssertRealUserDirUntouched() throws Exception {
        try {
            if (Config.instance() != null && Config.instance().getSettingData() != null) {
                Config.instance().getSettingData().useAllCardVariants = savedUseAllCardVariants;
            }
            SourcePrintings.clearCaches();
            RewardData.invalidateCardPool();
            RewardData.invalidateRewardFilterCache();
            pinStaticData(previousStaticData);
            AccountStore.resetAdventureRootOverrideForTest();
            Config.resetInstanceForTest();
        } finally {
            if (realUserDirSnapshot != null) {
                AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                        "SourcePrintingsCs0Test");
            }
        }
    }

    @BeforeMethod
    public void setUp() {
        LOCK.lock();
        try {
            Assert.assertNull(initError, "bootstrap failed: " + initError);
            Assert.assertNotNull(magicDb, "CS0 card DB not loaded");
            pinStaticData(magicDb);
            AdventureTestUserDir.requireIsolatedUserDir();

            Assert.assertNotNull(ascendantConfig, "Ascendant config missing from BeforeClass");
            Config.resetInstanceForTest();
            Config.instance();
            Config.installConfigDataForTest(ascendantConfig);
            Config.instance().getSettingData().useAllCardVariants = false;
            Assert.assertTrue(Config.ascendant());
            Assert.assertTrue(SourcePrintings.enabled());
            SourcePrintings.clearCaches();
            RewardData.invalidateCardPool();
            ensurePlayerWithRotation(List.of("ZEN", "WWK", "ROE"));
        } catch (RuntimeException e) {
            LOCK.unlock();
            throw e;
        } catch (Exception e) {
            LOCK.unlock();
            throw new RuntimeException(e);
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() {
        try {
            if (ascendantConfig != null) {
                // Tests may temporarily set allowedEditions; always restore the open catalogue.
                ascendantConfig.allowedEditions = null;
                Config.installConfigDataForTest(ascendantConfig);
            }
            if (Config.instance() != null && Config.instance().getSettingData() != null) {
                Config.instance().getSettingData().useAllCardVariants = false;
            }
            SourcePrintings.clearCaches();
            RewardData.invalidateCardPool();
            pinStaticData(magicDb);
        } finally {
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    @Test
    public void zenSourceRewardsAreZenPrintings() {
        RewardData data = new RewardData();
        data.type = "randomCard";
        data.count = 8;
        data.editions = new String[]{"ZEN"};
        data.cardTypes = new String[]{"Creature"};

        List<PaperCard> cards = generateMany(data, 24);
        Assert.assertFalse(cards.isEmpty(), "ZEN-sourced rewards must produce cards");
        for (PaperCard pc : cards) {
            Assert.assertEquals(pc.getEdition(), "ZEN",
                    "reward from ZEN source must be ZEN printing: " + pc.getName()
                            + " was " + pc.getEdition());
        }
    }

    @Test
    public void zenShopStockIsZenPrintings() {
        // Shop reward pin (same path as shops.json editions arrays).
        RewardData shop = new RewardData();
        shop.type = "card";
        shop.count = 6;
        shop.editions = new String[]{"ZEN"};
        shop.rarity = new String[]{"Common", "Uncommon", "Rare", "Mythic Rare"};

        List<PaperCard> stock = generateMany(shop, 18);
        Assert.assertFalse(stock.isEmpty(), "ZEN shop stock must not be empty");
        for (PaperCard pc : stock) {
            Assert.assertEquals(pc.getEdition(), "ZEN",
                    "shop stock from ZEN pool must be ZEN: " + pc.getName());
        }
    }

    @Test
    public void zenPackContentsAreZenPrintings() {
        CardEdition zen = magicDb.getEditions().get("ZEN");
        Assert.assertNotNull(zen);
        Assert.assertTrue(zen.hasBoosterTemplate(), "ZEN must be able to make boosters");

        Deck pack = CardUtil.generateBoosterPackAsDeck(zen);
        Assert.assertNotNull(pack);
        Assert.assertEquals(pack.getComment(), "ZEN");
        List<PaperCard> cards = pack.getMain().toFlatList();
        Assert.assertFalse(cards.isEmpty(), "ZEN booster must contain cards");
        for (PaperCard pc : cards) {
            Assert.assertEquals(pc.getEdition(), "ZEN",
                    "pack from ZEN must yield ZEN printings: " + pc.getName());
        }

        // Also via BoosterPack.fromSet (shop cardPackShop path).
        List<PaperCard> fromSet = BoosterPack.fromSet(zen).getCards();
        Assert.assertFalse(fromSet.isEmpty());
        for (PaperCard pc : fromSet) {
            Assert.assertEquals(pc.getEdition(), "ZEN");
        }
    }

    @Test
    public void junkShopCardUsesPrintingInsideRotation() {
        Set<String> rotation = Set.of("ZEN", "WWK", "ROE");
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        Set<String> legalNames = player.getStandardWindow().legalNames();
        Assert.assertFalse(legalNames.isEmpty(), "rotation legalNames must load from ZEN/WWK/ROE");

        // Junk / generic shop: no editions pin. Production pools are already
        // window-filtered by name; rematch printings into the rotation.
        RewardData junk = new RewardData();
        junk.type = "randomCard";
        junk.count = 10;
        junk.cardTypes = new String[]{"Creature"};

        // Build a rotation-legal pool by resolving printings (works with lazy card load).
        List<PaperCard> pool = new ArrayList<>();
        for (String name : legalNames) {
            PaperCard pc = SourcePrintings.printingFromRotation(name, rotation);
            if (pc == null || pc.getRules() == null || !pc.getRules().getType().isCreature()) {
                continue;
            }
            pool.add(pc);
            if (pool.size() >= 120) {
                break;
            }
        }
        Assert.assertFalse(pool.isEmpty(), "rotation-legal creature pool must not be empty");

        // Deliberately feed non-rotation unique stand-ins when available, then rematch.
        List<PaperCard> skewed = new ArrayList<>();
        for (PaperCard pc : pool) {
            PaperCard unique = magicDb.getCommonCards().getUniqueByName(pc.getName());
            skewed.add(unique != null ? unique : pc);
        }

        List<PaperCard> cards = CardUtil.generateCards(skewed, junk, 40, new Random(7L));
        Assert.assertFalse(cards.isEmpty(), "junk shop must produce cards from rotation pool");
        for (PaperCard pc : cards) {
            Assert.assertTrue(rotation.contains(pc.getEdition()),
                    "junk-shop card must use a printing inside the rotation; got "
                            + pc.getName() + " [" + pc.getEdition() + "]");
        }

        // Same rematch via SourcePrintings.resolve with no source editions.
        PaperCard guide = SourcePrintings.printingFromSet("Goblin Guide", "M11");
        if (guide == null) {
            guide = magicDb.getCommonCards().getUniqueByName("Goblin Guide");
        }
        Assert.assertNotNull(guide, "Goblin Guide must resolve");
        PaperCard resolved = SourcePrintings.resolve(guide, (String[]) null);
        Assert.assertEquals(resolved.getEdition(), "ZEN");
    }

    @Test
    public void useAllCardVariantsTrueChangesNothingInAscendant() {
        Config.instance().getSettingData().useAllCardVariants = true;
        Assert.assertTrue(SourcePrintings.enabled());
        Assert.assertFalse(SourcePrintings.useAllCardVariants(),
                "CS0 must ignore useAllCardVariants in Ascendant");

        RewardData data = new RewardData();
        data.type = "randomCard";
        data.count = 6;
        data.editions = new String[]{"ZEN"};

        List<PaperCard> withFlag = generateMany(data, 18);
        Assert.assertFalse(withFlag.isEmpty());
        for (PaperCard pc : withFlag) {
            Assert.assertEquals(pc.getEdition(), "ZEN",
                    "useAllCardVariants=true must not introduce non-ZEN printings");
        }

        // Spell Smith path helper: set pin still ZEN with the flag on.
        PaperCard guide = SourcePrintings.printingFromSet("Goblin Guide", "ZEN");
        Assert.assertNotNull(guide);
        Assert.assertEquals(guide.getEdition(), "ZEN");
        PaperCard again = SourcePrintings.printingFromSet("Goblin Guide", "ZEN");
        Assert.assertEquals(again.getEdition(), guide.getEdition());
        Assert.assertEquals(again.getCollectorNumber(), guide.getCollectorNumber());
    }

    @Test
    public void stockWorldStillHonoursUseAllCardVariants() {
        Config.installConfigDataForTest(stockConfig);
        Assert.assertFalse(Config.ascendant());
        Assert.assertFalse(SourcePrintings.enabled(), "CS0 must stay off for stock");

        Config.instance().getSettingData().useAllCardVariants = true;
        Assert.assertTrue(SourcePrintings.useAllCardVariants(),
                "stock world must still see useAllCardVariants");

        Config.instance().getSettingData().useAllCardVariants = false;
        Assert.assertFalse(SourcePrintings.useAllCardVariants());
    }

    @Test
    public void allowedEditionsHonouredByResolveRotationAndPinnedShops() {
        // Shock is in M12 and M14 (allowed) and also in newer cores like M21 (disallowed here).
        warmCard("Shock");
        Assert.assertNotNull(magicDb.getEditions().get("M12"), "M12 required");
        Assert.assertNotNull(magicDb.getEditions().get("M14"), "M14 required");
        Assert.assertNotNull(magicDb.getEditions().get("M21"), "M21 required");
        PaperCard m21Shock = printingInEdition("Shock", "M21");
        Assert.assertNotNull(m21Shock, "M21 Shock printing required");
        PaperCard m14Shock = printingInEdition("Shock", "M14");
        Assert.assertNotNull(m14Shock, "M14 Shock printing required");

        String[] previousAllowed = ascendantConfig.allowedEditions;
        try {
            // Warm NORMAL_CACHE while the catalogue is open (M21 is allowed / "normal").
            ascendantConfig.allowedEditions = null;
            Config.installConfigDataForTest(ascendantConfig);
            SourcePrintings.clearCaches();
            Assert.assertTrue(SourcePrintings.isNormalPrinting(m21Shock),
                    "precondition: M21 Shock is normal before the allow-list");

            // Small allow-list that excludes M21 (and ZEN) but keeps M12/M14 + the window.
            // Mutate the live ConfigData without clearCaches — proves allow-list is checked
            // outside NORMAL_CACHE (a stale intrinsic true must not win).
            ascendantConfig.allowedEditions = new String[]{"M12", "M14", "WWK", "ROE"};
            Config.instance().getConfigData().allowedEditions = ascendantConfig.allowedEditions;
            RewardData.invalidateCardPool();

            Assert.assertTrue(SourcePrintings.isAllowedEdition("M14"));
            Assert.assertFalse(SourcePrintings.isAllowedEdition("M21"));
            Assert.assertFalse(SourcePrintings.isAllowedEdition("ZEN"));
            Assert.assertFalse(SourcePrintings.isNormalPrinting(m21Shock),
                    "disallowed edition must fail isNormalPrinting even if cached earlier");

            // Preferred set pin refused when disallowed.
            Assert.assertNull(SourcePrintings.printingFromSet("Shock", "M21"),
                    "printingFromSet must refuse a disallowed edition");
            PaperCard fromM14 = SourcePrintings.printingFromSet("Shock", "M14");
            Assert.assertNotNull(fromM14);
            Assert.assertEquals(fromM14.getEdition(), "M14");

            // Empty rotation → most-recent normal among allowed only (M14 beats M12 by date).
            PaperCard fromFallback = SourcePrintings.printingFromRotation("Shock", List.of());
            Assert.assertNotNull(fromFallback, "must fall back to an allowed normal printing");
            Assert.assertTrue(Set.of("M12", "M14").contains(fromFallback.getEdition()),
                    "fallback must stay inside allowedEditions; got " + fromFallback.getEdition());
            Assert.assertNotEquals(fromFallback.getEdition(), "M21");

            // Rotation that includes a disallowed preferred set still skips it.
            PaperCard fromRotation = SourcePrintings.printingFromRotation("Shock",
                    List.of("M21", "M14", "M12"));
            Assert.assertNotNull(fromRotation);
            Assert.assertTrue(Set.of("M12", "M14").contains(fromRotation.getEdition()),
                    "rotation pick must skip disallowed M21; got " + fromRotation.getEdition());

            // resolve: disallowed source pin + disallowed candidate → allowed rematch.
            PaperCard resolvedPin = SourcePrintings.resolve(m21Shock, new String[]{"M21"});
            Assert.assertNotNull(resolvedPin);
            Assert.assertNotEquals(resolvedPin.getEdition(), "M21",
                    "resolve must not keep a disallowed source pin");
            Assert.assertTrue(SourcePrintings.isAllowedEdition(resolvedPin.getEdition()));

            PaperCard resolvedNull = SourcePrintings.resolve(m21Shock, (String[]) null);
            Assert.assertNotNull(resolvedNull);
            Assert.assertNotEquals(resolvedNull.getEdition(), "M21");
            Assert.assertTrue(SourcePrintings.isAllowedEdition(resolvedNull.getEdition()));

            // Goblin Guide only has a ZEN window printing — with ZEN disallowed, no allowed hit.
            warmCard("Goblin Guide");
            Assert.assertNull(SourcePrintings.printingFromSet("Goblin Guide", "ZEN"),
                    "ZEN pin refused when not allow-listed");
            Assert.assertNull(SourcePrintings.printingFromRotation("Goblin Guide",
                            List.of("ZEN", "WWK", "ROE")),
                    "no allowed Guide printing in WWK/ROE; must not return disallowed ZEN");
            PaperCard zenGuide = printingInEdition("Goblin Guide", "ZEN");
            Assert.assertNotNull(zenGuide);
            Assert.assertNull(SourcePrintings.resolve(zenGuide, new String[]{"ZEN"}),
                    "resolve must not return a disallowed candidate when allow-list is active");

            // ZEN-pinned shop: pin is disallowed → fall back; stock must not be ZEN.
            warmRotationCardPool();
            Assert.assertFalse(SourcePrintings.pinnedEditionsUsable(RewardData.getAllCards(),
                            new String[]{"ZEN"}),
                    "disallowed ZEN pin must be unusable");
            RewardData zenShop = new RewardData();
            zenShop.count = 8;
            zenShop.probability = 1f;
            zenShop.editions = new String[]{"ZEN"};
            List<PaperCard> stock = cardsFromGenerate(zenShop, 6);
            Assert.assertFalse(stock.isEmpty(), "disallowed-pin shop must fall back and stay stocked");
            Set<String> allowed = Set.of("M12", "M14", "WWK", "ROE");
            for (PaperCard pc : stock) {
                Assert.assertTrue(allowed.contains(pc.getEdition()),
                        "pinned-shop fallback must honour allowedEditions; got "
                                + pc.getName() + " [" + pc.getEdition() + "]");
                Assert.assertNotEquals(pc.getEdition(), "ZEN");
                Assert.assertNotEquals(pc.getEdition(), "M21");
            }
        } finally {
            // alwaysRun restore — also covered by @AfterMethod reinstall, but be explicit.
            ascendantConfig.allowedEditions = previousAllowed;
            Config.installConfigDataForTest(ascendantConfig);
            SourcePrintings.clearCaches();
            RewardData.invalidateCardPool();
        }
    }

    @Test
    public void rotationFallbackPicksNormalPrintingInsideWindow() {
        // Goblin Guide is a ZEN rare; with rotation {ZEN,WWK,ROE} must be ZEN.
        PaperCard guide = SourcePrintings.printingFromRotation("Goblin Guide",
                List.of("ZEN", "WWK", "ROE"));
        Assert.assertNotNull(guide);
        Assert.assertEquals(guide.getEdition(), "ZEN");
        Assert.assertTrue(SourcePrintings.isNormalPrinting(guide),
                "rotation pick should be a normal (non-showcase) printing");
    }

    @Test
    public void mostRecentNormalFallbackWhenOutsideRotation() {
        // Black Lotus has no ZEN printing; with empty rotation fall back to recent normal.
        PaperCard lotus = SourcePrintings.printingFromRotation("Black Lotus", List.of());
        Assert.assertNotNull(lotus);
        Assert.assertTrue(SourcePrintings.isNormalPrinting(lotus)
                        || magicDb.getEditions().get(lotus.getEdition()) != null,
                "fallback must resolve some printing of Black Lotus");
        // Prefer non-promo when possible.
        CardEdition ed = magicDb.getEditions().get(lotus.getEdition());
        Assert.assertNotNull(ed);
        Assert.assertNotEquals(ed.getType(), CardEdition.Type.PROMO,
                "most-recent normal fallback should avoid promo sets when a normal exists");
        Assert.assertNotEquals(ed.getType(), CardEdition.Type.ONLINE,
                "fallback must not pick ONLINE editions when a normal exists");
        Assert.assertNotEquals(ed.getType(), CardEdition.Type.FUNNY,
                "fallback must not pick FUNNY editions when a normal exists");
        Assert.assertFalse(SourcePrintings.isRestrictedEdition(lotus.getEdition()),
                "fallback must not pick restrictedEditions");
    }

    // ---- RewardData.generate-level behaviour (Union, pinned fallback, exclusions) ----

    @Test
    public void generateUnionRematchesPrintingsIntoRotation() {
        warmRotationCardPool();
        Set<String> rotation = Set.of("ZEN", "WWK", "ROE");

        RewardData union = new RewardData();
        union.type = "Union";
        union.count = 6;
        union.probability = 1f;
        RewardData a = new RewardData();
        a.colors = new String[]{"green"};
        a.cardTypes = new String[]{"Creature"};
        RewardData b = new RewardData();
        b.colors = new String[]{"red"};
        b.cardTypes = new String[]{"Creature"};
        union.cardUnion = new RewardData[]{a, b};

        List<PaperCard> cards = cardsFromGenerate(union, 8);
        Assert.assertFalse(cards.isEmpty(), "Union generate must stock Ascendant shops");
        for (PaperCard pc : cards) {
            Assert.assertTrue(rotation.contains(pc.getEdition()),
                    "Union reward must rematch into rotation; got "
                            + pc.getName() + " [" + pc.getEdition() + "]");
        }
    }

    @Test
    public void pinnedEditionsUsableRejectsBasicsAndReprintsAlone() {
        warmRotationCardPool();
        List<PaperCard> pool = new ArrayList<>();
        for (PaperCard pc : RewardData.getAllCards()) {
            if (pc != null) {
                pool.add(pc);
            }
        }
        Assert.assertFalse(pool.isEmpty(), "window card pool required");

        // CardPredicate edition match is non-empty for 40K/DnD (basics + Commander
        // reprints of window names) — that must NOT keep the pin.
        RewardData fortyKFilter = new RewardData();
        fortyKFilter.editions = new String[]{"40K"};
        Assert.assertFalse(CardUtil.getPredicateResult(pool, fortyKFilter).isEmpty(),
                "sanity: 40K pin hits the window via basics/reprints (old empty check)");
        Assert.assertFalse(SourcePrintings.pinnedEditionsUsable(pool, new String[]{"40K"}),
                "40K pin must be unusable without enough non-basic pin cards");

        RewardData dndFilter = new RewardData();
        dndFilter.editions = new String[]{"AFR", "HBG", "CLB", "AFC"};
        Assert.assertFalse(CardUtil.getPredicateResult(pool, dndFilter).isEmpty(),
                "sanity: DnD pins hit the window via basics/reprints");
        Assert.assertFalse(
                SourcePrintings.pinnedEditionsUsable(pool, new String[]{"AFR", "HBG", "CLB", "AFC"}),
                "DnD pin must be unusable on a ZEN/WWK/ROE window");

        Assert.assertTrue(SourcePrintings.pinnedEditionsUsable(pool, new String[]{"ZEN"}),
                "ZEN pin inside the window must stay usable");
    }

    @Test
    public void generateSpaceMarineShopFallsBackToRotation() {
        warmRotationCardPool();
        // Real Ascendant shops.json SpaceMarine entry: count 8, editions ["40K"] only.
        RewardData spaceMarine = new RewardData();
        spaceMarine.count = 8;
        spaceMarine.probability = 1f;
        spaceMarine.editions = new String[]{"40K"};

        List<PaperCard> cards = cardsFromGenerate(spaceMarine, 6);
        Assert.assertFalse(cards.isEmpty(),
                "SpaceMarine (40K) shop must fall back and stay stocked");
        Set<String> rotation = Set.of("ZEN", "WWK", "ROE");
        for (PaperCard pc : cards) {
            Assert.assertTrue(rotation.contains(pc.getEdition()),
                    "SpaceMarine fallback must use rotation printings; got "
                            + pc.getName() + " [" + pc.getEdition() + "]");
            Assert.assertNotEquals(pc.getEdition(), "40K",
                    "must not stock 40K basics/reprints after unusable pin fallback");
        }
    }

    @Test
    public void generateDnDShopFallsBackToRotation() {
        warmRotationCardPool();
        // Real Ascendant shops.json DnD entry: count 8, editions AFR/HBG/CLB/AFC.
        RewardData dnd = new RewardData();
        dnd.count = 8;
        dnd.probability = 1f;
        dnd.editions = new String[]{"AFR", "HBG", "CLB", "AFC"};

        List<PaperCard> cards = cardsFromGenerate(dnd, 6);
        Assert.assertFalse(cards.isEmpty(),
                "DnD shop must fall back and stay stocked");
        Set<String> rotation = Set.of("ZEN", "WWK", "ROE");
        Set<String> dndPins = Set.of("AFR", "HBG", "CLB", "AFC");
        for (PaperCard pc : cards) {
            Assert.assertTrue(rotation.contains(pc.getEdition()),
                    "DnD fallback must use rotation printings; got "
                            + pc.getName() + " [" + pc.getEdition() + "]");
            Assert.assertFalse(dndPins.contains(pc.getEdition()),
                    "must not stock AFR/CLB basics/reprints after unusable pin fallback");
        }
    }

    @Test
    public void generateZenPinnedShopKeepsZenPrintings() {
        warmRotationCardPool();
        // Same shape as a real set-pinned shop: count + editions only (no cardTypes).
        RewardData pinned = new RewardData();
        pinned.count = 8;
        pinned.probability = 1f;
        pinned.editions = new String[]{"ZEN"};

        List<PaperCard> cards = cardsFromGenerate(pinned, 6);
        Assert.assertFalse(cards.isEmpty(), "ZEN-pinned shop inside rotation must stock");
        for (PaperCard pc : cards) {
            Assert.assertEquals(pc.getEdition(), "ZEN",
                    "ZEN pin must keep ZEN printings via generate()");
        }
    }

    @Test
    public void generateExcludesOnlineCollectorFunnyRestrictedPlstMb1() {
        warmRotationCardPool();
        SourcePrintings.clearCaches();
        pinStaticData(magicDb);

        // Hard availability checks — do not silently skip.
        Assert.assertNotNull(magicDb.getEditions().get("ANB"), "ANB (ONLINE) required");
        Assert.assertNotNull(magicDb.getEditions().get("V15"), "V15 (COLLECTOR) required");
        Assert.assertNotNull(magicDb.getEditions().get("UST"), "UST (FUNNY/restricted) required");
        Assert.assertNotNull(magicDb.getEditions().get("PLST"), "PLST required");
        Assert.assertNotNull(magicDb.getEditions().get("MB1"), "MB1 required");
        Assert.assertEquals(magicDb.getEditions().get("ANB").getType(), CardEdition.Type.ONLINE);
        Assert.assertEquals(magicDb.getEditions().get("V15").getType(),
                CardEdition.Type.COLLECTOR_EDITION);
        Assert.assertEquals(magicDb.getEditions().get("UST").getType(), CardEdition.Type.FUNNY);
        Assert.assertTrue(SourcePrintings.isRestrictedEdition("UST"),
                "Ascendant restrictedEditions must list UST");

        // Prefer getAllCards then pick ANB — resilient when getCard(name, set) races lazy load.
        PaperCard onlineShock = magicDb.getCommonCards().getCard("Shock", "ANB");
        if (onlineShock == null) {
            for (PaperCard pc : magicDb.getCommonCards().getAllCards("Shock")) {
                if (pc != null && "ANB".equalsIgnoreCase(pc.getEdition())) {
                    onlineShock = pc;
                    break;
                }
            }
        }
        Assert.assertNotNull(onlineShock, "ANB Shock printing required");
        Assert.assertFalse(SourcePrintings.isNormalPrinting(onlineShock),
                "ONLINE Shock must fail isNormalPrinting");
        Assert.assertNull(SourcePrintings.printingFromSet("Shock", "ANB"),
                "printingFromSet must refuse ONLINE editions");

        CardEdition ustEd = magicDb.getEditions().get("UST");
        Assert.assertFalse(ustEd.getCards().isEmpty(), "UST must have cards");
        String ustName = ustEd.getCards().get(0).name();
        PaperCard ustCard = magicDb.getCommonCards().getCard(ustName, "UST");
        if (ustCard == null) {
            for (PaperCard pc : magicDb.getCommonCards().getAllCards(ustName)) {
                if (pc != null && "UST".equalsIgnoreCase(pc.getEdition())) {
                    ustCard = pc;
                    break;
                }
            }
        }
        Assert.assertNotNull(ustCard, "UST printing must load from DB for isNormalPrinting check");
        Assert.assertFalse(SourcePrintings.isNormalPrinting(ustCard),
                "restricted FUNNY UST must fail isNormalPrinting");
        Assert.assertNull(SourcePrintings.printingFromSet(ustName, "UST"),
                "printingFromSet must refuse restrictedEditions");

        // Junk / generic shop via generate() — no editions pin.
        RewardData junk = new RewardData();
        junk.type = "randomCard";
        junk.count = 8;
        junk.probability = 1f;

        List<PaperCard> cards = cardsFromGenerate(junk, 12);
        Assert.assertFalse(cards.isEmpty(), "junk generate must stock cards");
        Set<String> bannedCodes = Set.of("PLST", "MB1", "ANB", "V15", "UST");
        for (PaperCard pc : cards) {
            Assert.assertFalse(bannedCodes.contains(pc.getEdition().toUpperCase()),
                    "generate must not yield banned edition " + pc.getEdition()
                            + " for " + pc.getName());
            CardEdition ed = magicDb.getEditions().get(pc.getEdition());
            Assert.assertNotNull(ed, "edition must resolve: " + pc.getEdition());
            Assert.assertNotEquals(ed.getType(), CardEdition.Type.ONLINE,
                    "generate must exclude ONLINE: " + pc.getName() + " [" + pc.getEdition() + "]");
            Assert.assertNotEquals(ed.getType(), CardEdition.Type.COLLECTOR_EDITION,
                    "generate must exclude COLLECTOR: " + pc.getName() + " [" + pc.getEdition() + "]");
            Assert.assertNotEquals(ed.getType(), CardEdition.Type.FUNNY,
                    "generate must exclude FUNNY: " + pc.getName() + " [" + pc.getEdition() + "]");
            Assert.assertFalse(SourcePrintings.isRestrictedEdition(pc.getEdition()),
                    "generate must exclude restrictedEditions: " + pc.getEdition());
            Assert.assertTrue(SourcePrintings.isNormalPrinting(pc)
                            || Set.of("ZEN", "WWK", "ROE").contains(pc.getEdition()),
                    "generate card should be normal or in-rotation: "
                            + pc.getName() + " [" + pc.getEdition() + "]");
        }

        PaperCard byName = CardUtil.getCardByName("Shock");
        Assert.assertNotNull(byName);
        Assert.assertNotEquals(
                magicDb.getEditions().get(byName.getEdition()).getType(),
                CardEdition.Type.ONLINE);
        Assert.assertFalse(SourcePrintings.isRestrictedEdition(byName.getEdition()));
    }

    @Test
    public void gymStapleRewardRoutesThroughResolve() {
        warmRotationCardPool();
        PaperCard staple = GymUtil.pickStapleReward("Goblin Guide");
        Assert.assertNotNull(staple);
        Assert.assertEquals(staple.getEdition(), "ZEN",
                "gym staple reward must resolve to a rotation printing");
    }

    @Test
    public void fantasyLootSkipsNullCs0Rematch() {
        warmCard("Goblin Guide");
        PaperCard zenGuide = printingInEdition("Goblin Guide", "ZEN");
        Assert.assertNotNull(zenGuide);

        String[] previousAllowed = ascendantConfig.allowedEditions;
        try {
            // ZEN disallowed and Guide has no WWK/ROE printing → resolve returns null.
            ascendantConfig.allowedEditions = new String[]{"WWK", "ROE", "M12", "M14"};
            Config.installConfigDataForTest(ascendantConfig);
            SourcePrintings.clearCaches();
            Assert.assertNull(SourcePrintings.resolve(zenGuide, (String[]) null),
                    "precondition: CS0 rematch of ZEN Guide is null under this allow-list");

            Array<Reward> pool = new Array<>();
            EnemySprite.addCs0CardReward(pool, zenGuide);
            EnemySprite.addCs0CardReward(pool, null);
            Assert.assertEquals(pool.size, 0,
                    "fantasy loot must not add Reward(null) when CS0 rematch fails");
        } finally {
            ascendantConfig.allowedEditions = previousAllowed;
            Config.installConfigDataForTest(ascendantConfig);
            SourcePrintings.clearCaches();
            RewardData.invalidateCardPool();
        }
    }

    @Test
    public void pickStapleRewardTriesNextWhenPreferredRematchIsNull() {
        warmRotationCardPool();
        warmCard("Goblin Guide");
        warmCard("Shock");
        warmCard("Evolving Wilds");

        String[] previousAllowed = ascendantConfig.allowedEditions;
        try {
            // Guide's ZEN printing is disallowed; staples with WWK/ROE/M12/M14 printings remain.
            ascendantConfig.allowedEditions = new String[]{"WWK", "ROE", "M12", "M14"};
            Config.installConfigDataForTest(ascendantConfig);
            SourcePrintings.clearCaches();
            RewardData.invalidateCardPool();
            Assert.assertNull(SourcePrintings.printingFromRotation("Goblin Guide",
                            List.of("ZEN", "WWK", "ROE")),
                    "precondition: preferred Guide rematch is null");
            Assert.assertNotNull(GymUtil.pickStapleReward(null),
                    "precondition: staple pool must still yield under this allow-list");

            PaperCard staple = GymUtil.pickStapleReward("Goblin Guide");
            Assert.assertNotNull(staple,
                    "pickStapleReward must try the next staple instead of returning null");
            Assert.assertNotEquals(staple.getName(), "Goblin Guide",
                    "must not keep the failed preferred staple");
            Assert.assertTrue(SourcePrintings.isAllowedEdition(staple.getEdition()),
                    "fallback staple must be allow-listed; got " + staple.getEdition());
        } finally {
            ascendantConfig.allowedEditions = previousAllowed;
            Config.installConfigDataForTest(ascendantConfig);
            SourcePrintings.clearCaches();
            RewardData.invalidateCardPool();
        }
    }

    @Test
    public void collectorNumbersComparedNumerically() {
        // Prefer lower numeric CN: "2" before "10" (string compare would invert).
        PaperCard a = SourcePrintings.printingFromSet("Plains", "ZEN");
        Assert.assertNotNull(a);
        // Cache hit path for section / normal.
        SourcePrintings.clearCaches();
        Assert.assertTrue(SourcePrintings.isNormalPrinting(a));
        Assert.assertTrue(SourcePrintings.isNormalPrinting(a), "second call uses cache");
        String cn = CardEdition.getSortableCollectorNumber("2");
        String cn10 = CardEdition.getSortableCollectorNumber("10");
        Assert.assertTrue(cn.compareTo(cn10) < 0,
                "sortable CN must order 2 before 10");
    }

    // ------------------------------------------------------------------ helpers

    /** Force-load enough rotation cards so RewardData.initializeAllCards is non-empty. */
    private static void warmRotationCardPool() {
        pinStaticData(magicDb);
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        Set<String> names = player.getStandardWindow().legalNames();
        int n = 0;
        for (String name : names) {
            magicDb.getCommonCards().getCard(name);
            if (++n >= 400) {
                break;
            }
        }
        RewardData.invalidateCardPool();
        SourcePrintings.clearCaches();
    }

    private static void warmCard(String name) {
        pinStaticData(magicDb);
        List<PaperCard> all = magicDb.getCommonCards().getAllCards(name);
        Assert.assertNotNull(all, name + " printings required");
        Assert.assertFalse(all.isEmpty(), name + " printings required");
    }

    private static PaperCard printingInEdition(String name, String edition) {
        warmCard(name);
        PaperCard direct = magicDb.getCommonCards().getCard(name, edition);
        if (direct != null) {
            return direct;
        }
        for (PaperCard pc : magicDb.getCommonCards().getAllCards(name)) {
            if (pc != null && edition.equalsIgnoreCase(pc.getEdition())) {
                return pc;
            }
        }
        return null;
    }

    private static List<PaperCard> cardsFromGenerate(RewardData template, int rounds) {
        List<PaperCard> out = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            RewardData one = new RewardData(template);
            one.probability = 1f;
            Array<Reward> rewards = one.generate(false, true);
            for (Reward r : rewards) {
                if (r != null && r.getType() == Reward.Type.Card && r.getCard() != null) {
                    out.add(r.getCard());
                }
            }
        }
        return out;
    }

    private static List<PaperCard> generateMany(RewardData template, int total) {
        List<PaperCard> out = new ArrayList<>();
        Random rng = new Random(42L);
        // Production rematch path used by rewards/shops (CardUtil.generateCards + CS0 resolve).
        List<PaperCard> pool = new ArrayList<>();
        for (PaperCard pc : magicDb.getCommonCards().getUniqueCards()) {
            if (pc != null) {
                pool.add(pc);
            }
            if (pool.size() >= 800) {
                break;
            }
        }
        Assert.assertFalse(pool.isEmpty(), "card pool required");
        List<PaperCard> generated = CardUtil.generateCards(pool, template, total, rng);
        out.addAll(generated);
        if (template.editions != null && template.editions.length == 1) {
            String code = template.editions[0];
            for (PaperCard pc : generated) {
                Assert.assertEquals(pc.getEdition(), code,
                        "CardUtil.generateCards must rematch to source set " + code);
            }
        }
        return out;
    }

    private static void ensurePlayerWithRotation(List<String> sets) {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        Assert.assertNotNull(player, "WorldSave player required");
        StandardWindow window = player.getStandardWindow();
        Assert.assertNotNull(window);
        window.init(sets);
        Assert.assertTrue(window.isActive());
        Assert.assertEquals(new HashSet<>(window.getSets()), new HashSet<>(sets));
        RewardData.invalidateCardPool();
    }

    private static boolean containsIgnoreCase(String[] arr, String needle) {
        if (arr == null || needle == null) {
            return false;
        }
        for (String s : arr) {
            if (needle.equalsIgnoreCase(s)) {
                return true;
            }
        }
        return false;
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
        for (String rel : new String[]{"forge-gui", "../forge-gui"}) {
            Path p = Path.of(rel).toAbsolutePath().normalize();
            if (Files.isDirectory(p.resolve("res/editions"))) {
                return p;
            }
        }
        throw new IllegalStateException("Could not locate forge-gui/res/editions from "
                + Path.of(".").toAbsolutePath());
    }
}
