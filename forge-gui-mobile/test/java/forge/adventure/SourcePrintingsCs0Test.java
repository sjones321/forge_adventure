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
        RewardData junk = new RewardData();
        junk.type = "randomCard";
        junk.count = 10;
        junk.cardTypes = new String[]{"Creature"};
        // No editions → junk / generic source.

        List<PaperCard> cards = generateMany(junk, 30);
        Assert.assertFalse(cards.isEmpty(), "junk shop must produce cards from rotation pool");
        for (PaperCard pc : cards) {
            Assert.assertTrue(rotation.contains(pc.getEdition()),
                    "junk-shop card must use a printing inside the rotation; got "
                            + pc.getName() + " [" + pc.getEdition() + "]");
        }
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
    }

    // ------------------------------------------------------------------ helpers

    private static List<PaperCard> generateMany(RewardData template, int total) {
        List<PaperCard> out = new ArrayList<>();
        Random rng = new Random(42L);
        // generate() uses world RNG unless seedless; force seedless and rematch via CardUtil.
        for (int i = 0; i < total; i++) {
            RewardData one = new RewardData(template);
            one.count = 1;
            one.probability = 1f;
            Array<Reward> rewards = one.generate(false, true);
            for (Reward r : rewards) {
                if (r != null && r.getType() == Reward.Type.Card && r.getCard() != null) {
                    out.add(r.getCard());
                }
            }
        }
        // Also exercise CardUtil.generateCards rematch path with an explicit ZEN pool.
        if (template.editions != null && template.editions.length == 1) {
            String code = template.editions[0];
            List<PaperCard> pool = new ArrayList<>();
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null) {
                    continue;
                }
                // Unique cards may be non-ZEN; CardPredicate accepts if any printing matches.
                pool.add(pc);
                if (pool.size() >= 400) {
                    break;
                }
            }
            List<PaperCard> generated = CardUtil.generateCards(pool, template, 8, rng);
            out.addAll(generated);
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
