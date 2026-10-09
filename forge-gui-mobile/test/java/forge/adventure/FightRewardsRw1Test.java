package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeCoreData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.data.GymRewardData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.coop.CoopDuelRuntime;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.EnemyCoopPartners;
import forge.adventure.util.EnemyThemeDecks;
import forge.adventure.util.FightRewards;
import forge.adventure.util.GymUtil;
import forge.adventure.util.Reward;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.gamemodes.net.coop.CoopDuelRewards;
import forge.item.PaperCard;
import forge.model.FModel;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * RW1 behavior: themed-fight signature + current-set card rewards through
 * {@link RewardData#generateThemedFightRewards} / {@link FightRewards}.
 * Uses Surefire {@code forge.test.userDir}; never touches the real user folder.
 */
public class FightRewardsRw1Test {

    private Path realUserDir;
    private Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private ConfigData ascendantConfig;
    private ConfigData stockConfig;

    @BeforeMethod
    public void setUp() throws Exception {
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        AdventureTestUserDir.requireIsolatedUserDir();

        FightRewards.clearTestOverrides();
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(true);
        EnemyThemeDecks.loadCatalogForTests(merfolkCatalog());
        EnemyCoopPartners.setEnabledForTests(true);

        Path cfgPath = resolveAscendantConfig();
        ascendantConfig = new Json().fromJson(ConfigData.class, new FileHandle(cfgPath.toFile()));
        Assert.assertTrue(ascendantConfig.ascendantRules);
        ascendantConfig.rw1FightRewards = true;
        ascendantConfig.rw1SignatureCardCount = 1;
        ascendantConfig.rw1CurrentSetCardShare = 1.0f;

        stockConfig = new ConfigData();
        stockConfig.ascendantRules = false;

        Config.resetInstanceForTest();
        Config.instance();
        Config.installConfigDataForTest(ascendantConfig);
        Assert.assertTrue(Config.ascendant());

        // Home-plane newest set: rotation ending in ZEN.
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        player.getStandardWindow().init(List.of("M11", "M12", "ZEN"));
        Assert.assertEquals(player.getStandardWindow().newestSet(), "ZEN");
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        try {
            FightRewards.clearTestOverrides();
            EnemyThemeDecks.clearCache();
            EnemyThemeDecks.setEnabledForTests(null);
            EnemyCoopPartners.setEnabledForTests(null);
            Config.resetInstanceForTest();
        } finally {
            if (realUserDirSnapshot != null) {
                AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                        "FightRewardsRw1Test");
            }
        }
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
    public void merfolkTribalWinOnZenGivesExactlyOneCorePlusZenCards() {
        // Spec: merfolk_tribal on a ZEN plane → exactly one merfolk core card plus ZEN cards.
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");

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

    // ---- helpers ----

    private static void assumeCardDb() {
        Assert.assertNotNull(FModel.getMagicDb(), "FModel card DB required (AdventureGuiBootstrapListener)");
        Assert.assertNotNull(FModel.getMagicDb().getCommonCards());
        PaperCard sample = forge.adventure.util.CardUtil.getCardByName("Island");
        Assert.assertNotNull(sample, "card DB must resolve Island");
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
            PaperCard pc = forge.adventure.util.CardUtil.getCardByName(name);
            if (pc != null) {
                out.add(pc);
            }
            if (out.size() >= 12) {
                break;
            }
        }
        // Pad with a few non-core names so intersection matters.
        for (String pad : List.of("Island", "Cancel", "Unsummon")) {
            PaperCard pc = forge.adventure.util.CardUtil.getCardByName(pad);
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
