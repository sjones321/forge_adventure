package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.ImageKeys;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeCoreData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.data.GymRewardData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AccountStore;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.coop.CoopDuelRuntime;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.EnemyCoopPartners;
import forge.adventure.util.EnemyThemeDecks;
import forge.adventure.util.FightRewards;
import forge.adventure.util.GymUtil;
import forge.adventure.util.Reward;
import forge.adventure.world.PlaneFormat;
import forge.adventure.world.WorldSave;
import forge.card.CardRarity;
import forge.deck.Deck;
import forge.gamemodes.net.coop.CoopDuelRewards;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RW1 behavior: themed-fight signature + current-set card rewards through
 * {@link RewardData#generateThemedFightRewards} / {@link FightRewards}.
 *
 * <p>Self-bootstraps Config + card DB in {@link BeforeClass} (same pattern as
 * {@link AchievementsAc1RealDbTest}) so suite order cannot leave
 * {@code Config}/{@code StaticData} null after AC1/EN1 teardown.
 * Uses Surefire {@code forge.test.userDir}; never touches the real user folder.
 */
public class FightRewardsRw1Test {

    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static StaticData magicDb;
    private static StaticData previousStaticData;
    private static ConfigData classAscendantConfig;
    private static String initError;

    private ConfigData ascendantConfig;
    private ConfigData stockConfig;
    private List<String> standardWindowSnapshot;
    private int dustCommonSnapshot;
    private int goldSnapshot;

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

            // FightRewards / CardUtil / CardDb.lazyLoad all need FModel's StaticData and
            // StaticData.instance() to be the *same* object. AC1's AfterClass can unpin
            // lastInstance to null; never pin a second StaticData or lazy lookup breaks.
            ensureFModelMagicDb();
            magicDb = FModel.getMagicDb();
            Assert.assertNotNull(magicDb, "FModel magic DB required for RW1");
            Assert.assertNotNull(magicDb.getCommonCards(), "FModel common cards required");
            pinStaticData(magicDb);

            Path cfgPath = forgeGuiDir.resolve("res/adventure/Shandalar Ascendant/config.json");
            Assert.assertTrue(Files.isRegularFile(cfgPath), "Ascendant config missing: " + cfgPath);
            classAscendantConfig = new Json().fromJson(ConfigData.class, new FileHandle(cfgPath.toFile()));
            Assert.assertNotNull(classAscendantConfig);
            Assert.assertTrue(classAscendantConfig.ascendantRules);
            classAscendantConfig.rw1FightRewards = true;
            classAscendantConfig.rw1SignatureCardCount = 1;
            classAscendantConfig.rw1CurrentSetCardShare = 1.0f;

            // AC1 AfterClass calls Config.resetInstanceForTest() — reinstall ourselves.
            Config.resetInstanceForTest();
            Config.instance();
            Config.installConfigDataForTest(classAscendantConfig);
            Assert.assertTrue(Config.ascendant());
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

    @AfterClass(alwaysRun = true)
    public void restorePinnedStateAndAssertRealUserDirUntouched() throws Exception {
        try {
            FightRewards.clearTestOverrides();
            RewardData.invalidateCardPool();
            RewardData.invalidateRewardFilterCache();
            EnemyThemeDecks.clearCache();
            EnemyThemeDecks.setEnabledForTests(null);
            EnemyCoopPartners.setEnabledForTests(null);
            pinStaticData(previousStaticData);
            AccountStore.resetAdventureRootOverrideForTest();
            Config.resetInstanceForTest();
        } finally {
            if (realUserDirSnapshot != null) {
                AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                        "FightRewardsRw1Test");
            }
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {
        Assert.assertNull(initError, "RW1 BeforeClass bootstrap failed: " + initError);
        Assert.assertNotNull(magicDb, "RW1 card DB not loaded");
        pinStaticData(magicDb);
        AdventureTestUserDir.requireIsolatedUserDir();

        FightRewards.clearTestOverrides();
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(true);
        EnemyThemeDecks.loadCatalogForTests(merfolkCatalog());
        EnemyCoopPartners.setEnabledForTests(true);

        Assert.assertNotNull(classAscendantConfig, "Ascendant config missing from BeforeClass");
        ascendantConfig = classAscendantConfig;
        ascendantConfig.rw1FightRewards = true;
        ascendantConfig.rw1SignatureCardCount = 1;
        ascendantConfig.rw1CurrentSetCardShare = 1.0f;

        stockConfig = new ConfigData();
        stockConfig.ascendantRules = false;

        // Reinstall — do not leave Config null if a prior suite class reset it.
        Config.resetInstanceForTest();
        Config.instance();
        Config.installConfigDataForTest(ascendantConfig);
        Assert.assertTrue(Config.ascendant());
        RewardData.invalidateCardPool();

        // Home-plane newest set: rotation ending in ZEN.
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        player.getStandardWindow().init(List.of("M11", "M12", "ZEN"));
        Assert.assertEquals(player.getStandardWindow().newestSet(), "ZEN");
        player.setLegacyRunFormat(GymUtil.FORMAT_STANDARD);
        standardWindowSnapshot = new ArrayList<>(player.getStandardWindow().getSets());
        dustCommonSnapshot = player.getDust(CardRarity.Common);
        goldSnapshot = player.getGold();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        FightRewards.clearTestOverrides();
        RewardData.invalidateCardPool();
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(null);
        EnemyCoopPartners.setEnabledForTests(null);
        try {
            AdventurePlayer player = AdventurePlayer.current();
            player.setLegacyRunFormat(GymUtil.FORMAT_STANDARD);
            if (standardWindowSnapshot != null) {
                player.getStandardWindow().init(standardWindowSnapshot);
            }
            int dustNow = player.getDust(CardRarity.Common);
            if (dustNow != dustCommonSnapshot) {
                player.addDust(CardRarity.Common, dustCommonSnapshot - dustNow);
            }
            int goldNow = player.getGold();
            if (goldNow != goldSnapshot) {
                player.takeGold(goldNow - goldSnapshot);
            }
        } catch (Throwable ignored) {
        }
        try {
            Current.setLatestDeck(null);
        } catch (Throwable ignored) {
        }
        // Keep Config + StaticData pinned for the rest of this class; AfterClass restores.
        if (classAscendantConfig != null) {
            Config.installConfigDataForTest(classAscendantConfig);
        }
        pinStaticData(magicDb);
    }

    @Test
    public void appliesOnlyForAscendantThemedNonBoss() {
        EnemyData themed = merfolkEnemy();
        Assert.assertTrue(FightRewards.applies(themed));

        EnemyData boss = merfolkEnemy();
        boss.boss = true;
        Assert.assertFalse(FightRewards.applies(boss));

        EnemyData noTheme = merfolkEnemy();
        noTheme.themeId = null;
        Assert.assertFalse(FightRewards.applies(noTheme));

        Config.installConfigDataForTest(stockConfig);
        Assert.assertFalse(Config.ascendant());
        Assert.assertFalse(FightRewards.applies(themed), "stock world must keep deckCard loot");
    }

    @Test
    public void appliesToFightCoversUnthemedRegularEnemiesOnly() {
        EnemyData dungeon = merfolkEnemy();
        dungeon.themeId = null; // dungeon / town / cave enemies never get an EN1 theme
        Assert.assertFalse(FightRewards.applies(dungeon));
        Assert.assertTrue(FightRewards.appliesToFight(dungeon, false),
                "unthemed regular enemies use the current-set rewards too");
        Assert.assertTrue(FightRewards.appliesToFight(merfolkEnemy(), false), "themed still applies");

        Assert.assertFalse(FightRewards.appliesToFight(dungeon, true), "quest fights keep their tables");
        EnemyData boss = merfolkEnemy();
        boss.themeId = null;
        boss.boss = true;
        Assert.assertFalse(FightRewards.appliesToFight(boss, false), "bosses keep their tables");
        EnemyData gym = merfolkEnemy();
        gym.themeId = null;
        gym.preparedDeck = new forge.deck.Deck("gym");
        Assert.assertFalse(FightRewards.appliesToFight(gym, false), "gyms / League keep their tables");

        ascendantConfig.rw1AllRegularFights = false;
        try {
            Assert.assertFalse(FightRewards.appliesToFight(dungeon, false), "flag off = themed only");
            Assert.assertTrue(FightRewards.appliesToFight(merfolkEnemy(), false));
        } finally {
            ascendantConfig.rw1AllRegularFights = true;
        }

        Config.installConfigDataForTest(stockConfig);
        Assert.assertFalse(FightRewards.appliesToFight(dungeon, false), "stock world unchanged");
    }

    @Test
    public void unthemedWinGivesADeckSignaturePlusZenCards() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");
        RewardData.invalidateCardPool();

        EnemyData enemy = merfolkEnemy();
        enemy.themeId = null; // e.g. a red-white enemy: no EN1 theme matches it
        List<PaperCard> deck = deckWithCoreCards("merfolk_tribal");
        Set<String> deckNames = new HashSet<>();
        for (PaperCard pc : deck) {
            deckNames.add(pc.getName());
        }
        Array<Reward> rewards = FightRewards.generate(enemy, null, deck, true);
        List<PaperCard> cards = cardRewards(rewards);
        Assert.assertTrue(cards.stream().anyMatch(c -> deckNames.contains(c.getName())),
                "one signature card from the deck it played; cards=" + names(cards));
        for (PaperCard pc : cards) {
            if (!deckNames.contains(pc.getName())) {
                Assert.assertTrue(FightRewards.isEdition(pc, "ZEN"),
                        "non-signature cards are ZEN printings; editions=" + editions(cards));
            }
        }
    }

    @Test
    public void deckSignatureSkipsBasicsAndHandlesEmptyDecks() {
        assumeCardDb();
        Assert.assertTrue(FightRewards.pickDeckSignatures(null, 1, new java.util.Random(1)).isEmpty());
        PaperCard island = forge.model.FModel.getMagicDb().getCommonCards().getCard("Island");
        Assert.assertNotNull(island);
        Assert.assertTrue(FightRewards.pickDeckSignatures(List.of(island, island), 1, new java.util.Random(1)).isEmpty(),
                "basic lands are never a signature");
    }

    @Test
    public void merfolkTribalWinOnZenGivesExactlyOneCorePlusZenCards() {
        // Spec: merfolk_tribal on a ZEN plane → exactly one merfolk core card plus ZEN cards.
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");
        RewardData.invalidateCardPool();

        EnemyData enemy = merfolkEnemy();
        Assert.assertEquals(enemy.themeId, "merfolk_tribal");
        List<PaperCard> deck = deckWithCoreCards("merfolk_tribal");
        Assert.assertFalse(deck.isEmpty(), "need resolvable merfolk core cards in DB");

        Array<Reward> rewards = RewardData.generateThemedFightRewards(enemy, null, deck, true);
        List<PaperCard> cards = cardRewards(rewards);
        Assert.assertTrue(cards.size() >= 3, "expected signature + set cards; got " + names(cards));

        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        long coreCount = cards.stream().filter(c -> core.contains(c.getName())).count();
        Assert.assertEquals(coreCount, 1L,
                "exactly one merfolk core card; cards=" + names(cards));

        long zenNonCore = cards.stream()
                .filter(c -> !core.contains(c.getName()))
                .filter(c -> FightRewards.isEdition(c, "ZEN"))
                .count();
        long nonCore = cards.stream().filter(c -> !core.contains(c.getName())).count();
        Assert.assertTrue(nonCore >= 2, "plus ZEN cards; " + names(cards));
        Assert.assertEquals(zenNonCore, nonCore,
                "every non-signature card is a ZEN printing; editions=" + editions(cards));
    }

    @Test
    public void homePlaneUsesNewestRotationSet() {
        assumeCardDb();
        // No override → currentSetCode reads newest window set (ZEN from setUp).
        FightRewards.setCurrentSetCodeForTest(null);
        Assert.assertEquals(FightRewards.currentSetCode(), "ZEN");

        EnemyData enemy = merfolkEnemy();
        List<PaperCard> deck = deckWithCoreCards("merfolk_tribal");
        Array<Reward> rewards = RewardData.generateThemedFightRewards(enemy, null, deck, true);
        List<PaperCard> cards = cardRewards(rewards);
        Assert.assertTrue(cards.size() >= 3, "home plane: signature + newest-set cards; " + names(cards));

        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        Assert.assertEquals(cards.stream().filter(c -> core.contains(c.getName())).count(), 1L,
                "exactly one core signature on home plane; " + names(cards));
        long zenNonCore = cards.stream()
                .filter(c -> !core.contains(c.getName()))
                .filter(c -> FightRewards.isEdition(c, "ZEN"))
                .count();
        long nonCore = cards.stream().filter(c -> !core.contains(c.getName())).count();
        Assert.assertEquals(zenNonCore, nonCore,
                "home plane remaining cards from newest rotation set ZEN; editions=" + editions(cards));
    }

    @Test
    public void unownedSignatureWeightingPrefersMissingCards() {
        List<String> names = List.of("Owned Staple", "Missing Staple", "Also Owned");
        FightRewards.setOwnedCountOverrideForTest(n -> {
            if ("Missing Staple".equals(n)) {
                return 0;
            }
            return 4;
        });

        Map<String, Integer> hits = new HashMap<>();
        Random rng = new Random(7);
        for (int i = 0; i < 300; i++) {
            String pick = FightRewards.weightedPick(new ArrayList<>(names), rng);
            hits.merge(pick, 1, Integer::sum);
        }
        Assert.assertTrue(hits.getOrDefault("Missing Staple", 0)
                        > hits.getOrDefault("Owned Staple", 0),
                "unowned should win more often: " + hits);
        Assert.assertTrue(hits.getOrDefault("Missing Staple", 0)
                        > hits.getOrDefault("Also Owned", 0),
                "unowned should win more often: " + hits);
    }

    @Test
    public void en2PairEachPlayerGetsSignatureFromCreditedEnemyCorePlusZen() {
        // Two players beat an EN2 pair: host credited with primary (merfolk_tribal),
        // guest with partner (merfolk_tempo). Each rolls via pending lootRolls.
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");

        EnemyData primary = merfolkEnemy();
        primary.questTags = new String[]{"Merfolk"};
        final long enemyId = 42L;
        CoopDuelRuntime.HostedCoopEnemyBuild build = CoopDuelRuntime.buildHostedCoopEnemies(
                primary, enemyId, Collections.emptyList(), new Deck("Host"), false, 0);
        Assert.assertTrue(build.partnerBuilt, "EN2 pair must build a partner");
        Assert.assertNotNull(build.partner);
        Assert.assertEquals(build.lootRollsPerPlayer, CoopDuelRewards.DEFAULT_PARTNER_LOOT_ROLLS);
        Assert.assertNotEquals(build.partner.themeId, primary.themeId);

        EnemyData hostCredit = FightRewards.creditedLootEnemy(primary, build.partner, true);
        EnemyData guestCredit = FightRewards.creditedLootEnemy(primary, build.partner, false);
        Assert.assertEquals(hostCredit.themeId, "merfolk_tribal");
        Assert.assertEquals(guestCredit.themeId, build.partner.themeId);

        List<PaperCard> hostDeck = deckWithCoreCards(hostCredit.themeId);
        List<PaperCard> guestDeck = deckWithCoreCards(guestCredit.themeId);
        Assert.assertFalse(hostDeck.isEmpty());
        Assert.assertFalse(guestDeck.isEmpty());

        // Same bookkeeping WorldStage.setWinner uses: set → consume → N× getRewards/RW1.
        WorldStage.PendingLootRolls hostPending = new WorldStage.PendingLootRolls();
        hostPending.set(build.lootRollsPerPlayer);
        int hostRolls = hostPending.consume();
        Assert.assertEquals(hostRolls, 1);
        Array<Reward> hostLoot = new Array<>();
        for (int i = 0; i < hostRolls; i++) {
            hostLoot.addAll(RewardData.generateThemedFightRewards(hostCredit, null, hostDeck, true));
        }

        WorldStage.PendingLootRolls guestPending = new WorldStage.PendingLootRolls();
        guestPending.set(build.lootRollsPerPlayer);
        int guestRolls = guestPending.consume();
        Assert.assertEquals(guestRolls, 1);
        Array<Reward> guestLoot = FightRewards.rollViaPendingLootRolls(
                guestCredit, guestDeck, build.lootRollsPerPlayer);

        List<PaperCard> hostCards = cardRewards(hostLoot);
        List<PaperCard> guestCards = cardRewards(guestLoot);
        Set<String> tribalCore = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        Set<String> partnerCore = new HashSet<>(FightRewards.coreNames(guestCredit.themeId));

        Assert.assertEquals(hostCards.stream().filter(c -> tribalCore.contains(c.getName())).count(), 1L,
                "host: exactly one signature from credited primary core; " + names(hostCards));
        Assert.assertEquals(guestCards.stream().filter(c -> partnerCore.contains(c.getName())).count(), 1L,
                "guest: exactly one signature from credited partner core; " + names(guestCards));

        // Guest signature must come from the partner theme, not merely "any merfolk".
        Assert.assertTrue(guestCards.stream().anyMatch(c -> partnerCore.contains(c.getName())),
                "guest signature in partner core " + guestCredit.themeId);

        long hostZen = hostCards.stream()
                .filter(c -> !tribalCore.contains(c.getName()))
                .filter(c -> FightRewards.isEdition(c, "ZEN")).count();
        long hostRest = hostCards.stream().filter(c -> !tribalCore.contains(c.getName())).count();
        Assert.assertEquals(hostZen, hostRest, "host current-set cards are ZEN; " + editions(hostCards));

        long guestZen = guestCards.stream()
                .filter(c -> !partnerCore.contains(c.getName()))
                .filter(c -> FightRewards.isEdition(c, "ZEN")).count();
        long guestRest = guestCards.stream().filter(c -> !partnerCore.contains(c.getName())).count();
        Assert.assertEquals(guestZen, guestRest, "guest current-set cards are ZEN; " + editions(guestCards));

        // WorldStage credit routing: guest credit (different theme) uses FightRewards path.
        Array<Reward> routed = WorldStage.rollLootForCredit(null, guestCredit,
                deckFromCards(guestDeck));
        Assert.assertEquals(cardRewards(routed).stream()
                        .filter(c -> partnerCore.contains(c.getName())).count(), 1L,
                "rollLootForCredit credits partner theme");
    }

    @Test
    public void goldAndNonCardRowsUnchangedShape() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");
        EnemyData enemy = merfolkEnemy();
        // Force gold to always appear.
        for (RewardData r : enemy.rewards) {
            if ("gold".equals(r.type)) {
                r.probability = 1f;
                r.count = 25;
                r.addMaxCount = 0;
            }
        }
        Array<Reward> rewards = RewardData.generateThemedFightRewards(
                enemy, null, deckWithCoreCards("merfolk_tribal"), true);
        boolean gold = false;
        for (int i = 0; i < rewards.size; i++) {
            if (rewards.get(i).getType() == Reward.Type.Gold) {
                gold = true;
                Assert.assertEquals(rewards.get(i).getCount(), 25);
            }
        }
        Assert.assertTrue(gold, "gold row must still grant through FightRewards");
    }

    @Test
    public void gymLeagueRewardPathUnchanged() {
        // GymUtil.grantRewards never consults FightRewards / theme cores.
        // Still needs Config + StaticData.instance() pinned — pickStapleReward uses CardUtil.
        assumeCardDb();
        GymRewardData gym = new GymRewardData();
        gym.gold = 40;
        gym.dustCommon = 3;
        gym.stapleCard = null;
        int dustBefore = WorldSave.getCurrentSave().getPlayer().getDust(forge.card.CardRarity.Common);
        Array<Reward> out = GymUtil.grantRewards(gym);
        int dustAfter = WorldSave.getCurrentSave().getPlayer().getDust(forge.card.CardRarity.Common);
        Assert.assertEquals(dustAfter - dustBefore, 3);
        boolean gold = false;
        for (int i = 0; i < out.size; i++) {
            if (out.get(i).getType() == Reward.Type.Gold) {
                gold = true;
                Assert.assertEquals(out.get(i).getCount(), 40);
            }
        }
        Assert.assertTrue(gold);

        EnemyData boss = merfolkEnemy();
        boss.boss = true;
        Assert.assertFalse(FightRewards.applies(boss), "bosses keep their own reward tables");
    }

    @Test
    public void configJsonHasRw1Block() throws Exception {
        String text = Files.readString(resolveAscendantConfig(), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("\"rw1FightRewards\""));
        Assert.assertTrue(text.contains("\"rw1SignatureCardCount\""));
        Assert.assertTrue(text.contains("\"rw1CurrentSetCardShare\""));
        Assert.assertTrue(text.contains("\"rw1AllRegularFights\""));
        // No BOM.
        Assert.assertFalse(text.charAt(0) == '\uFEFF');
    }

    @Test
    public void pickSignaturesIntersectsCoreAndDeck() {
        assumeCardDb();
        List<PaperCard> deck = deckWithCoreCards("merfolk_tribal");
        List<String> core = FightRewards.coreNames("merfolk_tribal");
        Assert.assertFalse(core.isEmpty());

        List<PaperCard> sigs = FightRewards.pickSignatures("merfolk_tribal", deck, 1, new Random(11));
        Assert.assertEquals(sigs.size(), 1);
        Assert.assertTrue(core.contains(sigs.get(0).getName()), sigs.get(0).getName());
        // Name must have been in the played deck.
        Set<String> deckNames = new HashSet<>();
        for (PaperCard pc : deck) {
            deckNames.add(pc.getName());
        }
        Assert.assertTrue(deckNames.contains(sigs.get(0).getName()));
    }

    @Test
    public void pauperPlaneSetPoolIsCommonsOnly() {
        assumeCardDb();
        AdventurePlayer.current().setLegacyRunFormat(GymUtil.FORMAT_PAUPER);
        RewardData.invalidateCardPool();
        Assert.assertEquals(PlaneFormat.resolveCurrent(), GymUtil.FORMAT_PAUPER);
        Assert.assertTrue(PlaneFormat.favorsPauperPool());

        List<PaperCard> pool = FightRewards.formatAwareSetPool("ZEN", Collections.emptySet());
        Assert.assertFalse(pool.isEmpty(), "Pauper ZEN pool must be non-empty");
        for (PaperCard pc : pool) {
            Assert.assertEquals(pc.getRarity(), CardRarity.Common,
                    "Pauper set pool must not pay out set rares/mythics: "
                            + pc.getName() + "[" + pc.getEdition() + "/" + pc.getRarity() + "]");
            Assert.assertTrue(FightRewards.isEdition(pc, "ZEN"), pc.getEdition());
        }
    }

    @Test
    public void setPoolAppliesAdventureRewardFilterRestrictedAndAlchemy() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");
        RewardData.invalidateCardPool();

        List<PaperCard> pool = FightRewards.formatAwareSetPool("ZEN", Collections.emptySet());
        Assert.assertFalse(pool.isEmpty(), "ZEN set pool must be non-empty");
        for (PaperCard pc : pool) {
            Assert.assertTrue(RewardData.isAdventureRewardReachable(pc),
                    "set pool must apply adventureRewardFilter: " + pc.getName());
        }
        // Power-nine / restricted names from Ascendant config never appear.
        Set<String> restricted = new HashSet<>();
        if (ascendantConfig.restrictedCards != null) {
            Collections.addAll(restricted, ascendantConfig.restrictedCards);
        }
        Assert.assertTrue(restricted.contains("Black Lotus"));
        Assert.assertTrue(pool.stream().noneMatch(pc -> restricted.contains(pc.getName())),
                "restricted cards must be excluded from the set pool");

        // Ban a live ZEN name via restrictedCards and confirm the rebuild drops it.
        String banned = pool.get(0).getName();
        List<String> extended = new ArrayList<>(restricted);
        extended.add(banned);
        ascendantConfig.restrictedCards = extended.toArray(new String[0]);
        Config.installConfigDataForTest(ascendantConfig);
        RewardData.invalidateCardPool();
        List<PaperCard> after = FightRewards.formatAwareSetPool("ZEN", Collections.emptySet());
        Assert.assertTrue(after.stream().noneMatch(pc -> banned.equals(pc.getName())),
                "newly restricted name must leave the set pool: " + banned);
    }

    @Test
    public void noCurrentSetDeckFallbackKeepsExactlyOneSignature() {
        assumeCardDb();
        // Window inactive / empty current set → remaining cards from deck, core excluded.
        FightRewards.setCurrentSetCodeForTest("");
        Assert.assertEquals(FightRewards.currentSetCode(), "");

        EnemyData enemy = merfolkEnemy();
        List<PaperCard> deck = deckWithCoreCards("merfolk_tribal");
        Assert.assertTrue(deck.size() >= 4, "need core + filler for deck fallback");

        Array<Reward> rewards = RewardData.generateThemedFightRewards(enemy, null, deck, true);
        List<PaperCard> cards = cardRewards(rewards);
        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        long coreCount = cards.stream().filter(c -> core.contains(c.getName())).count();
        Assert.assertEquals(coreCount, 1L,
                "no-current-set: exactly one signature; cards=" + names(cards));

        List<PaperCard> nonSig = cards.stream()
                .filter(c -> !core.contains(c.getName()))
                .collect(java.util.stream.Collectors.toList());
        Assert.assertFalse(nonSig.isEmpty(), "deck fallback should still grant non-signature cards");
        Set<String> deckNames = new HashSet<>();
        for (PaperCard pc : deck) {
            deckNames.add(pc.getName());
        }
        for (PaperCard pc : nonSig) {
            Assert.assertTrue(deckNames.contains(pc.getName()),
                    "no-set fallback pick must come from the played deck: " + pc.getName());
            Assert.assertFalse(core.contains(pc.getName()),
                    "deck fallback must exclude theme core so signature stays unique");
        }
    }

    @Test
    public void guestMirrorWithoutThemeUsesHostDuelResultCredit() {
        // Guest SPAWN mirrors have no themeId; host sends credit on CoopDuelResultEvent.
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");

        EnemyData mirror = merfolkEnemy();
        mirror.themeId = null; // catalog SPAWN — applies() would be false locally
        Assert.assertFalse(FightRewards.applies(mirror));

        List<PaperCard> partnerDeck = deckWithCoreCards("merfolk_tempo");
        Assert.assertFalse(partnerDeck.isEmpty());
        String[] wireDeck = FightRewards.creditPlayedDeckNames(
                "merfolk_tempo", deckFromCards(partnerDeck));
        Assert.assertTrue(wireDeck.length >= 1, "host must send played-deck names");

        // Catalog id (EnemyData.name), not display nameOverride ("… Tidecaller").
        CoopDuelResultEvent event = new CoopDuelResultEvent(
                99L, 0, 42L, "Merfolk Scout", 1,
                "Merfolk Scout", "merfolk_tempo", wireDeck);

        CoopDuelRuntime.GuestLootCredit resolved =
                CoopDuelRuntime.guestLootCreditFromEvent(mirror, event);
        Assert.assertNotNull(resolved.credit);
        Assert.assertEquals(resolved.credit.themeId, "merfolk_tempo",
                "wire themeId stamped onto guest credit");
        Assert.assertEquals(resolved.credit.name, "Merfolk Scout",
                "creditEnemyDataId is the catalog id");
        Assert.assertTrue(FightRewards.applies(resolved.credit),
                "credited enemy must enter RW1 even when the mirror had no theme");
        Assert.assertNotNull(resolved.creditDeck);
        Assert.assertFalse(resolved.creditDeck.getMain().isEmpty());

        Array<Reward> loot = FightRewards.rollViaPendingLootRolls(
                resolved.credit, FightRewards.deckCardsForRewards(resolved.creditDeck),
                event.getLootRolls());
        List<PaperCard> cards = cardRewards(loot);
        Set<String> tempoCore = new HashSet<>(FightRewards.coreNames("merfolk_tempo"));
        Assert.assertEquals(cards.stream().filter(c -> tempoCore.contains(c.getName())).count(), 1L,
                "guest-mirror path: exactly one partner-theme signature; " + names(cards));
        Assert.assertTrue(cards.stream().noneMatch(c ->
                        FightRewards.coreNames("merfolk_tribal").contains(c.getName())
                                && !tempoCore.contains(c.getName())),
                "must not fall back to guest biome / primary theme");
    }

    @Test
    public void coopHostWinCreditsPrimaryEnemyPlayedDeckNotStaleLatest() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");

        EnemyData primary = merfolkEnemy();
        primary.questTags = new String[]{"Merfolk"};
        // Distinct primary deck: only one core name so the signature is forced.
        List<String> core = FightRewards.coreNames("merfolk_tribal");
        Assert.assertFalse(core.isEmpty());
        String playedCore = core.get(0);
        Deck primaryPlayed = new Deck("primary-played");
        PaperCard playedPc = cardByName(playedCore);
        Assert.assertNotNull(playedPc, playedCore);
        primaryPlayed.getMain().add(playedPc);
        // Pads must NOT be theme-core names (Unsummon is in merfolk_tribal core).
        for (String pad : List.of("Cancel", "Serum Visions", "Blue Elemental Blast")) {
            PaperCard p = cardByName(pad);
            if (p != null && !core.contains(p.getName())) {
                primaryPlayed.getMain().add(p);
            }
        }

        // Stale solo latestDeck — must NOT be used for co-op host credit.
        Deck stale = new Deck("stale-solo");
        for (int i = 1; i < Math.min(core.size(), 4); i++) {
            PaperCard p = cardByName(core.get(i));
            if (p != null && !playedCore.equals(p.getName())) {
                stale.getMain().add(p);
            }
        }
        Current.setLatestDeck(stale);

        CoopDuelRuntime.HostedCoopEnemyBuild build = CoopDuelRuntime.buildHostedCoopEnemies(
                primary, 7L, Collections.emptyList(), new Deck("Host"), false, 0);
        Assert.assertNotNull(build.primaryDeck, "host must retain enemies.get(0).deck");

        // Simulate finishHostMatch host credit: primary + the deck that seat played.
        // (build.primaryDeck is whatever generateDeck returned; pin our known deck.)
        Array<Reward> loot = WorldStage.rollLootForCredit(null, primary, primaryPlayed);
        List<PaperCard> cards = cardRewards(loot);
        Set<String> tribal = new HashSet<>(core);
        Assert.assertEquals(cards.stream().filter(c -> tribal.contains(c.getName())).count(), 1L,
                "exactly one signature from the primary played deck; " + names(cards));
        Assert.assertTrue(cards.stream().anyMatch(c -> playedCore.equals(c.getName())),
                "signature must be the core card that was in the played deck, not stale latest");
        // Stale-only core names must not appear as the signature.
        Set<String> staleOnly = new HashSet<>();
        for (PaperCard pc : stale.getMain().toFlatList()) {
            if (pc != null && !playedCore.equals(pc.getName())) {
                staleOnly.add(pc.getName());
            }
        }
        Assert.assertTrue(cards.stream().noneMatch(c -> staleOnly.contains(c.getName())),
                "must not credit stale Current.latestDeck; cards=" + names(cards));

        // Wire candidates for no-partner guest also come from the primary played deck.
        String[] wire = FightRewards.creditPlayedDeckNames("merfolk_tribal", primaryPlayed);
        Assert.assertTrue(Arrays.asList(wire).contains(playedCore));
        Assert.assertFalse(Arrays.asList(wire).isEmpty());
    }

    @Test
    public void emptyWireCandidatesDoNotFullCoreFallbackOnGuest() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");
        EnemyData mirror = merfolkEnemy();
        mirror.themeId = null;
        // Host meaning: empty list = no signature (deck had no core intersection).
        CoopDuelResultEvent event = new CoopDuelResultEvent(
                3L, 0, 1L, "Merfolk Scout", 1, "Merfolk Scout", "merfolk_tribal", new String[0]);
        CoopDuelRuntime.GuestLootCredit resolved =
                CoopDuelRuntime.guestLootCreditFromEvent(mirror, event);
        Assert.assertTrue(FightRewards.applies(resolved.credit));
        Assert.assertTrue(resolved.creditDeck.getMain().isEmpty());

        Array<Reward> loot = WorldStage.rollLootForCredit(null, resolved.credit, resolved.creditDeck);
        List<PaperCard> cards = cardRewards(loot);
        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        Assert.assertEquals(cards.stream().filter(c -> core.contains(c.getName())).count(), 0L,
                "empty wire candidates must not full-core-fallback; cards=" + names(cards));
    }

    @Test
    public void wireCreditUsedEvenWhenThemesMatch() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("");
        EnemyData primary = merfolkEnemy();
        // Same theme as credit — previously skipped the credit path.
        EnemyData credit = new EnemyData(primary);
        credit.colors = "UB"; // distinct colours/reward table marker
        credit.rewards = new RewardData[]{
                deckCardRow(3, 0, new String[]{"Common"}),
                goldRow(50)
        };
        for (RewardData r : credit.rewards) {
            if ("gold".equals(r.type)) {
                r.probability = 1f;
            }
        }
        List<PaperCard> deck = deckWithCoreCards("merfolk_tribal");
        // Fake mob data with matching theme.
        EnemyData mobData = merfolkEnemy();
        Assert.assertEquals(mobData.themeId, credit.themeId);

        Array<Reward> routed = WorldStage.rollLootForCredit(null, credit, deckFromCards(deck));
        boolean gold50 = false;
        for (int i = 0; i < routed.size; i++) {
            if (routed.get(i).getType() == Reward.Type.Gold && routed.get(i).getCount() == 50) {
                gold50 = true;
            }
        }
        Assert.assertTrue(gold50, "matching themes must still use credited reward table");
        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        Assert.assertEquals(cardRewards(routed).stream().filter(c -> core.contains(c.getName())).count(), 1L);
    }

    @Test
    public void guestThinSetGetsDeckFallbackFromWirePlayedDeck() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest(""); // thin / no current set
        EnemyData mirror = merfolkEnemy();
        mirror.themeId = null;
        List<PaperCard> played = deckWithCoreCards("merfolk_tempo");
        String[] wire = FightRewards.creditPlayedDeckNames("merfolk_tempo", deckFromCards(played));
        Assert.assertTrue(wire.length >= 2, "wire must carry core + non-core deck names");

        CoopDuelResultEvent event = new CoopDuelResultEvent(
                5L, 0, 2L, "Merfolk Scout", 1, "Merfolk Scout", "merfolk_tempo", wire);
        CoopDuelRuntime.GuestLootCredit resolved =
                CoopDuelRuntime.guestLootCreditFromEvent(mirror, event);
        Array<Reward> loot = WorldStage.rollLootForCredit(null, resolved.credit, resolved.creditDeck);
        List<PaperCard> cards = cardRewards(loot);
        Set<String> tempoCore = new HashSet<>(FightRewards.coreNames("merfolk_tempo"));
        Assert.assertEquals(cards.stream().filter(c -> tempoCore.contains(c.getName())).count(), 1L,
                "exactly one signature; " + names(cards));
        List<PaperCard> nonSig = cards.stream().filter(c -> !tempoCore.contains(c.getName()))
                .collect(Collectors.toList());
        Assert.assertFalse(nonSig.isEmpty(), "thin set: guest must get deck-fallback like the host");
        Set<String> wireNames = new HashSet<>(Arrays.asList(wire));
        for (PaperCard pc : nonSig) {
            Assert.assertTrue(wireNames.contains(pc.getName()),
                    "fallback from wire played-deck sample: " + pc.getName());
        }
    }

    // ---- helpers ----

    private static void assumeCardDb() {
        Assert.assertNull(initError, "RW1 BeforeClass bootstrap failed: " + initError);
        Assert.assertNotNull(magicDb, "RW1 pinned card DB required");
        // Re-pin every assume: AC1/EN1 AfterClass can null StaticData.instance(), and
        // CardDb.lazyLoad reads that — not FModel.getMagicDb() alone.
        pinStaticData(magicDb);
        if (classAscendantConfig != null) {
            Config.installConfigDataForTest(classAscendantConfig);
        }
        // getAllCards (not CardUtil.getCardByName): CardUtil List.of(unique) NPEs when
        // a name is not yet lazy-loaded and StaticData was unpinned mid-suite.
        Assert.assertNotNull(cardByName("Island"), "card DB must resolve Island");
    }

    /** Resolve a card via the pinned DB (safe when CardUtil would NPE). */
    private static PaperCard cardByName(String name) {
        if (magicDb == null || magicDb.getCommonCards() == null || name == null) {
            return null;
        }
        List<PaperCard> all = magicDb.getCommonCards().getAllCards(name);
        if (all == null || all.isEmpty()) {
            return null;
        }
        return all.get(0);
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
        throw new IllegalStateException(
                "Could not locate forge-gui/res/editions from " + Path.of(".").toAbsolutePath());
    }

    private static EnemyData merfolkEnemy() {
        EnemyData e = new EnemyData();
        e.name = "Merfolk Scout";
        e.themeId = "merfolk_tribal";
        e.boss = false;
        e.colors = "U";
        e.life = 20;
        e.rewards = new RewardData[]{
                deckCardRow(2, 0, new String[]{"Common", "Uncommon"}),
                goldRow(10)
        };
        return e;
    }

    private static RewardData deckCardRow(int count, int addMax, String[] rarity) {
        RewardData r = new RewardData();
        r.type = "deckCard";
        r.probability = 1f;
        r.count = count;
        r.addMaxCount = addMax;
        r.rarity = rarity;
        return r;
    }

    private static RewardData goldRow(int count) {
        RewardData r = new RewardData();
        r.type = "gold";
        r.probability = 0.5f;
        r.count = count;
        r.addMaxCount = 0;
        return r;
    }

    private static List<PaperCard> deckWithCoreCards(String themeId) {
        List<PaperCard> out = new ArrayList<>();
        for (String name : FightRewards.coreNames(themeId)) {
            PaperCard pc = cardByName(name);
            if (pc != null) {
                out.add(pc);
            }
            if (out.size() >= 12) {
                break;
            }
        }
        // Pad with a few non-core names so intersection matters.
        for (String pad : List.of("Island", "Cancel", "Unsummon")) {
            PaperCard pc = cardByName(pad);
            if (pc != null) {
                out.add(pc);
            }
        }
        return out;
    }

    private static Deck deckFromCards(List<PaperCard> cards) {
        Deck d = new Deck("credit");
        for (PaperCard pc : cards) {
            if (pc != null) {
                d.getMain().add(pc);
            }
        }
        return d;
    }

    private static List<PaperCard> cardRewards(Array<Reward> rewards) {
        List<PaperCard> out = new ArrayList<>();
        for (int i = 0; i < rewards.size; i++) {
            Reward r = rewards.get(i);
            if (r.getType() == Reward.Type.Card && r.getCard() != null) {
                out.add(r.getCard());
            }
        }
        return out;
    }

    private static List<String> names(List<PaperCard> cards) {
        List<String> out = new ArrayList<>();
        for (PaperCard pc : cards) {
            out.add(pc.getName() + "[" + pc.getEdition() + "]");
        }
        return out;
    }

    private static List<String> editions(List<PaperCard> cards) {
        List<String> out = new ArrayList<>();
        for (PaperCard pc : cards) {
            out.add(pc.getEdition());
        }
        return out;
    }

    private static EnemyThemeCatalogData merfolkCatalog() {
        EnemyThemeCatalogData cat = new EnemyThemeCatalogData();
        cat.themes = new EnemyThemeData[]{
                themeWithCore("merfolk_tribal", "Merfolk"),
                themeWithCore("merfolk_tempo", "Merfolk")
        };
        return cat;
    }

    private static EnemyThemeData themeWithCore(String id, String tag) {
        EnemyThemeData t = new EnemyThemeData();
        t.id = id;
        t.tags = new String[]{tag};
        t.colors = new String[]{"blue"};
        t.creatureTypes = new String[]{tag};
        t.standardRecipe = new EnemyThemeRecipeData();
        t.standardRecipe.count = 60;
        t.standardRecipe.colors = new String[]{"Blue"};
        t.standardRecipe.tribe = tag;
        t.core = loadCoreCards(id);
        return t;
    }

    private static String[] loadCoreCards(String themeId) {
        for (Path p : List.of(
                Paths.get("forge-gui/res/adventure/common/world/enemy_cores/" + themeId + ".json"),
                Paths.get("../forge-gui/res/adventure/common/world/enemy_cores/" + themeId + ".json"),
                Paths.get("res/adventure/common/world/enemy_cores/" + themeId + ".json"))) {
            if (Files.isRegularFile(p)) {
                EnemyThemeCoreData parsed = new Json().fromJson(EnemyThemeCoreData.class,
                        new FileHandle(p.toFile()));
                if (parsed != null && parsed.cards != null) {
                    return parsed.cards;
                }
            }
        }
        return new String[]{"Lord of Atlantis", "Merfolk Looter", "Counterspell", "Island"};
    }

    private static Path resolveAscendantConfig() {
        for (Path p : List.of(
                Paths.get("forge-gui/res/adventure/Shandalar Ascendant/config.json"),
                Paths.get("../forge-gui/res/adventure/Shandalar Ascendant/config.json"),
                Paths.get("res/adventure/Shandalar Ascendant/config.json"))) {
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("Ascendant config.json not found");
    }
}
