package forge.adventure;

import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.util.EnemyCoopPartners;
import forge.adventure.util.EnemyThemeDecks;
import forge.deck.Deck;
import forge.gamemodes.net.coop.CoopDuelMatchPlan;
import forge.gamemodes.net.coop.CoopDuelRewards;
import forge.gamemodes.net.coop.CoopDuelScaling;
import forge.gamemodes.net.coop.CoopFightLoadout;
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
import java.util.Set;

/**
 * EN2 behavior: deterministic partner pairing, exclusions, scaling switch, loot rolls,
 * and creature-type tag coverage (every tag has 2+ themes).
 */
public class EnemyCoopPartnersTest {

    @BeforeMethod
    public void setUp() {
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(true);
        EnemyThemeDecks.loadCatalogForTests(sampleCatalog());
        EnemyCoopPartners.setEnabledForTests(true);
    }

    @AfterMethod
    public void tearDown() {
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(null);
        EnemyCoopPartners.setEnabledForTests(null);
    }

    @Test
    public void partnerThemePickIsDeterministicForHostAndGuest() {
        final long seed = 99L;
        final String a = EnemyCoopPartners.pickPartnerThemeId("goblin_tribal", seed);
        final String b = EnemyCoopPartners.pickPartnerThemeId("goblin_tribal", seed);
        Assert.assertEquals(a, b);
        Assert.assertEquals(a, "goblin_burn");

        final String merfolk = EnemyCoopPartners.pickPartnerThemeId("merfolk_tribal", seed);
        Assert.assertEquals(merfolk, "merfolk_tempo");
        Assert.assertEquals(EnemyCoopPartners.pickPartnerThemeId("merfolk_tempo", seed), "merfolk_tribal");

        final String sea = EnemyCoopPartners.pickPartnerThemeId("kraken_leviathan", seed);
        Assert.assertEquals(sea, "serpent_leviathan");
    }

    @Test
    public void partnerThemeNeverMirrorsPrimary() {
        for (final String id : new String[]{"goblin_tribal", "merfolk_tribal", "kraken_leviathan", "spirit_tempo"}) {
            for (long seed = 0; seed < 32; seed++) {
                final String partner = EnemyCoopPartners.pickPartnerThemeId(id, seed);
                Assert.assertNotNull(partner, "expected partner for " + id);
                Assert.assertNotEquals(partner, id);
            }
        }
    }

    @Test
    public void singleThemeTagFallsBackToBiome() {
        // Inject a lonely theme with a unique tag.
        final EnemyThemeCatalogData cat = sampleCatalog();
        final EnemyThemeData lonely = theme("lonely_theme", "LonelyType", "blue", "Merfolk");
        final EnemyThemeData[] expanded = new EnemyThemeData[cat.themes.length + 1];
        System.arraycopy(cat.themes, 0, expanded, 0, cat.themes.length);
        expanded[cat.themes.length] = lonely;
        cat.themes = expanded;
        EnemyThemeDecks.loadCatalogForTests(cat);

        Assert.assertNull(EnemyCoopPartners.pickPartnerThemeId("lonely_theme", 1L));

        final EnemyData primary = new EnemyData();
        primary.name = "Lonely Mob";
        primary.themeId = "lonely_theme";
        primary.questTags = new String[]{"LonelyType"};
        primary.life = 20;

        final EnemyData biomeBuddy = new EnemyData();
        biomeBuddy.name = "Goblin Scout";
        biomeBuddy.questTags = new String[]{"Goblin"};
        biomeBuddy.life = 18;

        final EnemyCoopPartners.PartnerPlan plan = EnemyCoopPartners.planPartner(
                primary, Collections.singletonList(biomeBuddy), 7L,
                1.5f, 1, 1.0f, 0, 1);
        Assert.assertTrue(plan.partnerBuilt);
        Assert.assertEquals(plan.source, EnemyCoopPartners.PartnerSource.BIOME_FALLBACK);
        Assert.assertNotNull(plan.partner);
        Assert.assertEquals(plan.partner.name, "Goblin Scout");
        Assert.assertNotNull(plan.partner.themeId);
        Assert.assertEquals(plan.lifeFactor, 1.0f, 0.001f);
        Assert.assertEquals(plan.extraCards, 0);
    }

    @Test
    public void bossesGymAndNextEnemyExcluded() {
        final EnemyData boss = new EnemyData();
        boss.name = "Boss Merfolk";
        boss.boss = true;
        boss.themeId = "merfolk_tribal";
        boss.questTags = new String[]{"Merfolk"};
        Assert.assertFalse(EnemyCoopPartners.eligibleForPartner(boss));
        Assert.assertFalse(EnemyCoopPartners.planPartner(boss, Collections.emptyList(), 1L).partnerBuilt);

        final EnemyData gym = new EnemyData();
        gym.name = "Gym Leader";
        gym.themeId = "goblin_tribal";
        gym.questTags = new String[]{"Goblin"};
        gym.preparedDeck = new Deck("Gym");
        Assert.assertFalse(EnemyCoopPartners.eligibleForPartner(gym));

        final EnemyData pack = new EnemyData();
        pack.name = "Goblin Pack";
        pack.themeId = "goblin_tribal";
        pack.questTags = new String[]{"Goblin"};
        pack.nextEnemy = new EnemyData();
        pack.nextEnemy.name = "Goblin";
        Assert.assertFalse(EnemyCoopPartners.eligibleForPartner(pack));
        final EnemyCoopPartners.PartnerPlan plan = EnemyCoopPartners.planPartner(
                pack, Collections.emptyList(), 3L, 1.5f, 1, 1.0f, 0, 1);
        Assert.assertFalse(plan.partnerBuilt);
        // Keep single-enemy boosts when no partner could be built.
        Assert.assertEquals(plan.lifeFactor, 1.5f, 0.001f);
        Assert.assertEquals(plan.extraCards, 1);
    }

    @Test
    public void scalingDropsWithPartnerKeepsBoostsWithout() {
        Assert.assertEquals(CoopDuelScaling.effectiveLifeFactor(true, 1.0f, 1.5f), 1.0f, 0.001f);
        Assert.assertEquals(CoopDuelScaling.effectiveExtraCards(true, 0, 1), 0);
        Assert.assertEquals(CoopDuelScaling.effectiveLifeFactor(false, 1.0f, 1.5f), 1.5f, 0.001f);
        Assert.assertEquals(CoopDuelScaling.effectiveExtraCards(false, 0, 1), 1);

        final EnemyData primary = merfolkEnemy("merfolk_tribal");
        final EnemyCoopPartners.PartnerPlan withPartner = EnemyCoopPartners.planPartner(
                primary, Collections.emptyList(), 11L, 1.5f, 1, 1.0f, 0, 1);
        Assert.assertTrue(withPartner.partnerBuilt);
        Assert.assertEquals(withPartner.lifeFactor, 1.0f, 0.001f);
        Assert.assertEquals(withPartner.extraCards, 0);

        // Life on the match plan with partner factors: 40 * 1.0 = 40 (not 60).
        final Deck d = new Deck("E");
        final CoopDuelMatchPlan plan = CoopDuelMatchPlan.build(
                "Host", "h", d, 20, 0, 0,
                true,
                CoopFightLoadout.builder().playerName("Guest").avatarId("g").startingLife(20).build(),
                d,
                java.util.Arrays.asList(
                        new CoopDuelMatchPlan.EnemySpec("A", "a", d, 40, 0),
                        new CoopDuelMatchPlan.EnemySpec("B", "b", d, 40, 0)),
                withPartner.lifeFactor,
                withPartner.extraCards);
        Assert.assertEquals(plan.countTeam(0), 2);
        Assert.assertEquals(plan.countTeam(1), 2);
        Assert.assertEquals(plan.getLifeFactorApplied(), 1.0f, 0.001f);
        Assert.assertEquals(plan.getEnemyExtraCardsApplied(), 0);
        for (final CoopDuelMatchPlan.Seat seat : plan.getSeats()) {
            if (seat.kind == CoopDuelMatchPlan.SeatKind.ENEMY_AI) {
                Assert.assertEquals(seat.startingLife, 40);
                Assert.assertEquals(seat.startingHandBonus, 0);
            }
        }
    }

    @Test
    public void lootRollsPerPlayerNotDoubleForPair() {
        Assert.assertEquals(CoopDuelRewards.lootRollsPerPlayer(true, 1), 1);
        Assert.assertEquals(CoopDuelRewards.lootRollsPerPlayer(true, 2), 2);
        Assert.assertEquals(CoopDuelRewards.lootRollsPerPlayer(false, 2), 1);

        final EnemyData primary = merfolkEnemy("merfolk_tribal");
        final EnemyCoopPartners.PartnerPlan plan = EnemyCoopPartners.planPartner(
                primary, Collections.emptyList(), 5L, 1.5f, 1, 1.0f, 0, 1);
        Assert.assertTrue(plan.partnerBuilt);
        Assert.assertEquals(plan.lootRollsPerPlayer, 1);

        // Each side still applies only its own rewards (CO3 isolation).
        final CoopDuelRewards.SimpleSink host = new CoopDuelRewards.SimpleSink();
        final CoopDuelRewards.SimpleSink guest = new CoopDuelRewards.SimpleSink();
        final int rolls = plan.lootRollsPerPlayer;
        for (int i = 0; i < rolls; i++) {
            CoopDuelRewards.applyHostOnly(host, guest,
                    new CoopDuelRewards.ResultPayload(1L, true, 10, 20, 0, false, "Merfolk"));
        }
        Assert.assertEquals(host.getGold(), 10 * rolls);
        Assert.assertEquals(guest.getGold(), 0);
        for (int i = 0; i < rolls; i++) {
            CoopDuelRewards.applyGuestOnly(host, guest,
                    new CoopDuelRewards.ResultPayload(1L, true, 10, 20, 0, false, "Merfolk"));
        }
        Assert.assertEquals(guest.getGold(), 10 * rolls);
        Assert.assertEquals(host.getGold(), 10 * rolls);
    }

    @Test
    public void encounterSeedStableAndSameTypePartnerBuilt() {
        final EnemyData primary = merfolkEnemy("merfolk_tribal");
        final long s1 = EnemyCoopPartners.encounterSeed(42L, primary);
        final long s2 = EnemyCoopPartners.encounterSeed(42L, primary);
        Assert.assertEquals(s1, s2);
        Assert.assertEquals(s1, 42L);

        final EnemyData partner = EnemyCoopPartners.buildSameTypePartner(primary, "merfolk_tempo");
        Assert.assertNotNull(partner);
        Assert.assertEquals(partner.themeId, "merfolk_tempo");
        Assert.assertNull(partner.nextEnemy);
        Assert.assertEquals(partner.teamNumber, 1);
        Assert.assertFalse(partner.boss);
    }

    @Test
    public void everyCreatureTypeTagHasTwoOrMoreThemesInCatalogFile() throws Exception {
        // Use the committed JSON (not the in-memory sample) so content gaps fail CI.
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(true);
        // Force reload from disk by clearing test catalog.
        final Path path = resolveEnemyThemesJson();
        Assert.assertTrue(Files.isRegularFile(path), "missing " + path);
        // Parse tags → theme ids without needing Config / card DB.
        final String text = Files.readString(path, StandardCharsets.UTF_8);
        Assert.assertFalse(text.startsWith("\uFEFF"));
        final Map<String, Set<String>> tagToThemes = new HashMap<>();
        String currentId = null;
        for (final String line : text.split("\n")) {
            final String t = line.trim();
            if (t.startsWith("\"id\":")) {
                final int q1 = t.indexOf('"', 5);
                final int q2 = t.indexOf('"', q1 + 1);
                currentId = q1 >= 0 && q2 > q1 ? t.substring(q1 + 1, q2) : null;
            } else if (currentId != null && t.startsWith("\"tags\"")) {
                for (final String tag : extractStrings(t)) {
                    tagToThemes.computeIfAbsent(tag.toLowerCase(java.util.Locale.ROOT), k -> new HashSet<>())
                            .add(currentId);
                }
            }
        }
        Assert.assertTrue(tagToThemes.containsKey("merfolk"));
        Assert.assertTrue(tagToThemes.get("merfolk").size() >= 2, tagToThemes.get("merfolk").toString());
        Assert.assertTrue(tagToThemes.containsKey("kraken"));
        Assert.assertTrue(tagToThemes.get("kraken").size() >= 2, tagToThemes.get("kraken").toString());
        final List<String> shortTags = new ArrayList<>();
        for (final Map.Entry<String, Set<String>> e : tagToThemes.entrySet()) {
            if (e.getValue().size() < 2) {
                shortTags.add(e.getKey() + "=" + e.getValue());
            }
        }
        Assert.assertTrue(shortTags.isEmpty(), "EN2: every creature-type tag needs 2+ themes: " + shortTags);
    }

    @Test
    public void newThemesPresentInEnemyThemesJson() throws Exception {
        final String text = Files.readString(resolveEnemyThemesJson(), StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("\"merfolk_tempo\""));
        Assert.assertTrue(text.contains("\"serpent_leviathan\""));
        final Set<String> ids = new HashSet<>();
        for (final String line : text.split("\n")) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("\"id\":")) {
                final int q1 = trimmed.indexOf('"', 5);
                final int q2 = trimmed.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1) {
                    ids.add(trimmed.substring(q1 + 1, q2));
                }
            }
        }
        Assert.assertEquals(ids.size(), 18, "expected 18 themes: " + ids);
    }

    private static EnemyData merfolkEnemy(final String themeId) {
        final EnemyData e = new EnemyData();
        e.name = "Merfolk";
        e.themeId = themeId;
        e.questTags = new String[]{"Merfolk"};
        e.life = 20;
        return e;
    }

    private static EnemyThemeCatalogData sampleCatalog() {
        final EnemyThemeCatalogData cat = new EnemyThemeCatalogData();
        cat.themes = new EnemyThemeData[]{
                theme("merfolk_tribal", "Merfolk", "blue", "Merfolk"),
                theme("merfolk_tempo", "Merfolk", "blue", "Merfolk"),
                theme("kraken_leviathan", "Kraken", "blue", "Kraken"),
                theme("serpent_leviathan", "Kraken", "blue", "Serpent"),
                theme("goblin_tribal", "Goblin", "red", "Goblin"),
                theme("goblin_burn", "Goblin", "red", "Goblin"),
                theme("zombie_tribal", "Zombie", "black", "Zombie"),
                theme("zombie_aristocrats", "Zombie", "black", "Zombie"),
                theme("elf_tribal", "Elf", "green", "Elf"),
                theme("elf_ramp", "Elf", "green", "Elf"),
                theme("vampire_tribal", "Vampire", "black", "Vampire"),
                theme("vampire_drain", "Vampire", "black", "Vampire"),
                theme("dragon_tribal", "Dragon", "red", "Dragon"),
                theme("dragon_ramp", "Dragon", "red", "Dragon"),
                theme("soldier_tribal", "Soldier", "white", "Soldier"),
                theme("knight_tribal", "Knight", "white", "Knight"),
                theme("spirit_tribal", "Spirit", "white", "Spirit"),
                theme("spirit_tempo", "Spirit", "white", "Spirit")
        };
        // Sea-monster tags shared by both sea themes (matches enemy_themes.json).
        cat.themes[2].tags = new String[]{"Kraken", "Leviathan", "Octopus", "Serpent", "Sea Monster"};
        cat.themes[3].tags = new String[]{"Kraken", "Leviathan", "Octopus", "Serpent", "Sea Monster"};
        // Soldier/Knight shared tags.
        cat.themes[14].tags = new String[]{"Soldier", "Knight"};
        cat.themes[15].tags = new String[]{"Soldier", "Knight"};
        return cat;
    }

    private static EnemyThemeData theme(final String id, final String tag, final String color, final String creature) {
        final EnemyThemeData t = new EnemyThemeData();
        t.id = id;
        t.tags = new String[]{tag};
        t.colors = new String[]{color};
        t.creatureTypes = new String[]{creature};
        t.standardRecipe = new EnemyThemeRecipeData();
        t.standardRecipe.count = 60;
        t.standardRecipe.colors = new String[]{color.substring(0, 1).toUpperCase() + color.substring(1)};
        t.standardRecipe.tribe = creature;
        return t;
    }

    private static Path resolveEnemyThemesJson() {
        final Path[] candidates = {
                Paths.get("forge-gui/res/adventure/common/world/enemy_themes.json"),
                Paths.get("../forge-gui/res/adventure/common/world/enemy_themes.json"),
                Paths.get("res/adventure/common/world/enemy_themes.json")
        };
        for (final Path p : candidates) {
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return candidates[0];
    }

    private static List<String> extractStrings(final String line) {
        final List<String> out = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            final int q1 = line.indexOf('"', i);
            if (q1 < 0) {
                break;
            }
            final int q2 = line.indexOf('"', q1 + 1);
            if (q2 < 0) {
                break;
            }
            final String s = line.substring(q1 + 1, q2);
            if (!"tags".equals(s)) {
                out.add(s);
            }
            i = q2 + 1;
        }
        return out;
    }
}
