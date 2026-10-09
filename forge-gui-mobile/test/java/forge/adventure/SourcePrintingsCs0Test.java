package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
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
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p>Isolation: suite-wide {@code forge.test.userDir} / {@code test-user-home}
 * + {@link AdventureTestBootstrapListener} / {@link AdventureGuiBootstrapListener}.
 */
@Test(singleThreaded = true)
public class SourcePrintingsCs0Test {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static Path tempUserDir;
    private static Path realUserDir;
    private static java.util.Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static ConfigData ascendantConfig;
    private static ConfigData stockConfig;
    private static String initError;
    private static boolean savedUseAllCardVariants;

    @BeforeClass
    public void bootstrap() {
        try {
            tempUserDir = AdventureTestUserDir.configuredTestUserDir();
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
            AdventureTestUserDir.requireIsolatedUserDir();
            AccountStore.setAdventureRootOverrideForTest(new File(ForgeConstants.USER_ADVENTURE_DIR));

            Assert.assertNotNull(FModel.getMagicDb(), "FModel card DB required (AdventureGuiBootstrapListener)");
            Assert.assertNotNull(FModel.getMagicDb().getEditions().get("ZEN"), "ZEN edition required");

            Path forgeGuiDir = resolveForgeGuiDir();
            Path cfgPath = forgeGuiDir.resolve("res/adventure/Shandalar Ascendant/config.json");
            Assert.assertTrue(Files.isRegularFile(cfgPath), "Ascendant config missing: " + cfgPath);
            ascendantConfig = new Json().fromJson(ConfigData.class, new FileHandle(cfgPath.toFile()));
            Assert.assertNotNull(ascendantConfig);
            Assert.assertTrue(ascendantConfig.ascendantRules);
            Assert.assertTrue(ascendantConfig.cs0SourcePrintings, "Ascendant config must enable CS0");

            stockConfig = new ConfigData();
            stockConfig.ascendantRules = false;
            stockConfig.cs0SourcePrintings = true; // unused when not Ascendant

            Config.resetInstanceForTest();
            Config.instance();
            Config.installConfigDataForTest(ascendantConfig);
            Assert.assertTrue(Config.ascendant());
            Assert.assertTrue(SourcePrintings.enabled());

            savedUseAllCardVariants = Config.instance().getSettingData().useAllCardVariants;
        } catch (Throwable t) {
            initError = t.getClass().getSimpleName() + ": " + t.getMessage();
            t.printStackTrace();
        }
    }

    @AfterClass(alwaysRun = true)
    public void tearDownClass() throws Exception {
        try {
            if (Config.instance() != null && Config.instance().getSettingData() != null) {
                Config.instance().getSettingData().useAllCardVariants = savedUseAllCardVariants;
            }
            AccountStore.resetAdventureRootOverrideForTest();
            Config.resetInstanceForTest();
            RewardData.invalidateCardPool();
            RewardData.invalidateRewardFilterCache();
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
            Config.installConfigDataForTest(ascendantConfig);
            Config.instance().getSettingData().useAllCardVariants = false;
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
            Config.installConfigDataForTest(ascendantConfig);
            Config.instance().getSettingData().useAllCardVariants = false;
            RewardData.invalidateCardPool();
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
        CardEdition zen = FModel.getMagicDb().getEditions().get("ZEN");
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
            PaperCard unique = FModel.getMagicDb().getCommonCards().getUniqueByName(pc.getName());
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
            guide = FModel.getMagicDb().getCommonCards().getUniqueByName("Goblin Guide");
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
                        || FModel.getMagicDb().getEditions().get(lotus.getEdition()) != null,
                "fallback must resolve some printing of Black Lotus");
        // Prefer non-promo when possible.
        CardEdition ed = FModel.getMagicDb().getEditions().get(lotus.getEdition());
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

        // Hard availability checks — do not silently skip.
        Assert.assertNotNull(FModel.getMagicDb().getEditions().get("ANB"), "ANB (ONLINE) required");
        Assert.assertNotNull(FModel.getMagicDb().getEditions().get("V15"), "V15 (COLLECTOR) required");
        Assert.assertNotNull(FModel.getMagicDb().getEditions().get("UST"), "UST (FUNNY/restricted) required");
        Assert.assertNotNull(FModel.getMagicDb().getEditions().get("PLST"), "PLST required");
        Assert.assertNotNull(FModel.getMagicDb().getEditions().get("MB1"), "MB1 required");
        Assert.assertEquals(FModel.getMagicDb().getEditions().get("ANB").getType(), CardEdition.Type.ONLINE);
        Assert.assertEquals(FModel.getMagicDb().getEditions().get("V15").getType(),
                CardEdition.Type.COLLECTOR_EDITION);
        Assert.assertEquals(FModel.getMagicDb().getEditions().get("UST").getType(), CardEdition.Type.FUNNY);
        Assert.assertTrue(SourcePrintings.isRestrictedEdition("UST"),
                "Ascendant restrictedEditions must list UST");

        PaperCard onlineShock = SourcePrintings.printingFromSet("Shock", "ANB");
        Assert.assertNotNull(onlineShock, "ANB Shock printing required");
        Assert.assertFalse(SourcePrintings.isNormalPrinting(onlineShock),
                "ONLINE Shock must fail isNormalPrinting");

        CardEdition ustEd = FModel.getMagicDb().getEditions().get("UST");
        Assert.assertFalse(ustEd.getCards().isEmpty(), "UST must have cards");
        PaperCard ustCard = SourcePrintings.printingFromSet(ustEd.getCards().get(0).name(), "UST");
        Assert.assertNotNull(ustCard, "UST printing must resolve for isNormalPrinting check");
        Assert.assertFalse(SourcePrintings.isNormalPrinting(ustCard),
                "restricted FUNNY UST must fail isNormalPrinting");

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
            CardEdition ed = FModel.getMagicDb().getEditions().get(pc.getEdition());
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
                FModel.getMagicDb().getEditions().get(byName.getEdition()).getType(),
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
    public void collectorNumbersComparedNumerically() {
        // Prefer lower numeric CN: "2" before "10" (string compare would invert).
        PaperCard a = SourcePrintings.printingFromSet("Plains", "ZEN");
        Assert.assertNotNull(a);
        // Cache hit path for section / normal.
        SourcePrintings.clearCachesForTest();
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
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        Set<String> names = player.getStandardWindow().legalNames();
        int n = 0;
        for (String name : names) {
            FModel.getMagicDb().getCommonCards().getCard(name);
            if (++n >= 400) {
                break;
            }
        }
        RewardData.invalidateCardPool();
        SourcePrintings.clearCachesForTest();
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
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
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
