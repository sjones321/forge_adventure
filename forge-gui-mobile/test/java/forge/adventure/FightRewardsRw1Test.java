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
import forge.adventure.util.Config;
import forge.adventure.util.EnemyThemeDecks;
import forge.adventure.util.FightRewards;
import forge.adventure.util.GymUtil;
import forge.adventure.util.Reward;
import forge.adventure.world.WorldSave;
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
    public void signatureFromThemeCorePlusZenOnSetPlane() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");

        EnemyData enemy = merfolkEnemy();
        List<PaperCard> deck = deckWithCoreCards();
        Assert.assertFalse(deck.isEmpty(), "need resolvable merfolk core cards in DB");

        Array<Reward> rewards = RewardData.generateThemedFightRewards(enemy, null, deck, true);
        List<PaperCard> cards = cardRewards(rewards);
        // 1 signature + 2 deckCard rows (addMaxCount 0) under share=100%.
        Assert.assertTrue(cards.size() >= 3, "expected signature + set cards; got " + names(cards));

        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        Assert.assertTrue(cards.stream().anyMatch(c -> core.contains(c.getName())),
                "at least one signature from merfolk core; cards=" + names(cards));

        long zenCards = cards.stream().filter(c -> FightRewards.isEdition(c, "ZEN")).count();
        // Remaining card rewards are ZEN; signature may also resolve to ZEN via rotation.
        Assert.assertTrue(zenCards >= 2,
                "current-set share must yield ZEN printings; editions=" + editions(cards));
    }

    @Test
    public void homePlaneUsesNewestRotationSet() {
        assumeCardDb();
        // No override → currentSetCode reads newest window set (ZEN from setUp).
        FightRewards.setCurrentSetCodeForTest(null);
        Assert.assertEquals(FightRewards.currentSetCode(), "ZEN");

        EnemyData enemy = merfolkEnemy();
        List<PaperCard> deck = deckWithCoreCards();
        Array<Reward> rewards = RewardData.generateThemedFightRewards(enemy, null, deck, true);
        List<PaperCard> cards = cardRewards(rewards);
        Assert.assertTrue(cards.size() >= 3, "home plane: signature + newest-set cards; " + names(cards));

        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        Assert.assertTrue(cards.stream().anyMatch(c -> core.contains(c.getName())),
                "signature from core on home plane");
        long zenCards = cards.stream().filter(c -> FightRewards.isEdition(c, "ZEN")).count();
        Assert.assertTrue(zenCards >= 2,
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
    public void coopEachPlayerRollsOwnRewards() {
        assumeCardDb();
        FightRewards.setCurrentSetCodeForTest("ZEN");
        EnemyData enemy = merfolkEnemy();
        List<PaperCard> deck = deckWithCoreCards();

        // Host-authoritative roll count (EN2) still means each peer calls generate locally.
        Array<Reward> host = RewardData.generateThemedFightRewards(enemy, null, deck, true);
        Array<Reward> guest = RewardData.generateThemedFightRewards(enemy, null, deck, true);

        List<PaperCard> hostCards = cardRewards(host);
        List<PaperCard> guestCards = cardRewards(guest);
        Assert.assertFalse(hostCards.isEmpty());
        Assert.assertFalse(guestCards.isEmpty());

        Set<String> core = new HashSet<>(FightRewards.coreNames("merfolk_tribal"));
        Assert.assertTrue(hostCards.stream().anyMatch(c -> core.contains(c.getName())),
                "host signature");
        Assert.assertTrue(guestCards.stream().anyMatch(c -> core.contains(c.getName())),
                "guest signature");

        // Independent seedless rolls — not required to differ, but both must be complete RW1 packages.
        Assert.assertTrue(hostCards.stream().anyMatch(c -> FightRewards.isEdition(c, "ZEN"))
                        || hostCards.size() == 1,
                "host should include current-set cards when the row rolls any");
        Assert.assertTrue(guestCards.stream().anyMatch(c -> FightRewards.isEdition(c, "ZEN"))
                        || guestCards.size() == 1,
                "guest should include current-set cards when the row rolls any");
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
                enemy, null, deckWithCoreCards(), true);
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
        List<PaperCard> deck = deckWithCoreCards();
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

    private static List<PaperCard> deckWithCoreCards() {
        List<PaperCard> out = new ArrayList<>();
        for (String name : FightRewards.coreNames("merfolk_tribal")) {
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
        EnemyThemeData t = new EnemyThemeData();
        t.id = "merfolk_tribal";
        t.tags = new String[]{"Merfolk"};
        t.colors = new String[]{"blue"};
        t.creatureTypes = new String[]{"Merfolk"};
        t.standardRecipe = new EnemyThemeRecipeData();
        t.standardRecipe.count = 60;
        t.standardRecipe.colors = new String[]{"Blue"};
        t.standardRecipe.tribe = "Merfolk";
        // Load real core from disk so signature tests match production content.
        t.core = loadCoreCards("merfolk_tribal");
        cat.themes = new EnemyThemeData[]{t};
        return cat;
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
