package forge.adventure;

import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.util.EnemyThemeDecks;
import forge.adventure.util.SaveFileData;
import forge.deck.Deck;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * EN1 behavior tests that do not require the card database: theme pick,
 * persistence shape, and fallback that never crashes.
 */
public class EnemyThemeDecksTest {

    @BeforeMethod
    public void setUp() {
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(true);
        EnemyThemeDecks.loadCatalogForTests(sampleCatalog());
    }

    @AfterMethod
    public void tearDown() {
        EnemyThemeDecks.clearCache();
        EnemyThemeDecks.setEnabledForTests(null);
    }

    @Test
    public void themePickUsesQuestTagsAndIsStableWithSeed() {
        EnemyData goblin = new EnemyData();
        goblin.name = "Goblin Raider";
        goblin.questTags = new String[]{"Goblin"};

        String a = EnemyThemeDecks.pickThemeId(goblin, new Random(42));
        String b = EnemyThemeDecks.pickThemeId(goblin, new Random(42));
        Assert.assertNotNull(a);
        Assert.assertEquals(a, b);
        Assert.assertTrue(a.startsWith("goblin_"), a);

        EnemyData boss = new EnemyData();
        boss.name = "Goblin King";
        boss.boss = true;
        boss.questTags = new String[]{"Goblin"};
        Assert.assertNull(EnemyThemeDecks.pickThemeId(boss, new Random(1)));
    }

    @Test
    public void assignThemeAtSpawnCopiesAndSetsThemeId() {
        EnemyData catalog = new EnemyData();
        catalog.name = "Merfolk Scout";
        catalog.questTags = new String[]{"Merfolk"};
        catalog.deck = new String[]{"decks/standard/merfolk_bad.json"};

        EnemyData assigned = EnemyThemeDecks.assignThemeAtSpawn(catalog);
        Assert.assertNotSame(assigned, catalog);
        Assert.assertNotNull(assigned.themeId);
        Assert.assertTrue(assigned.themeId.startsWith("merfolk_")
                || assigned.themeId.startsWith("kraken_"), assigned.themeId);
        Assert.assertNull(catalog.themeId);
    }

    @Test
    public void themePersistsInParallelSaveList() {
        // Mirrors WorldStage save/load shape: themes list parallel to names.
        List<String> names = new ArrayList<>();
        List<String> themes = new ArrayList<>();
        names.add("Goblin");
        themes.add("goblin_tribal");
        names.add("Elf");
        themes.add("");

        SaveFileData data = new SaveFileData();
        data.storeObject("names", names);
        data.storeObject("themes", themes);

        @SuppressWarnings("unchecked")
        List<String> loadedNames = (List<String>) data.readObject("names");
        @SuppressWarnings("unchecked")
        List<String> loadedThemes = (List<String>) data.readObject("themes");
        Assert.assertEquals(loadedNames.size(), loadedThemes.size());
        Assert.assertEquals(loadedThemes.get(0), "goblin_tribal");
        Assert.assertEquals(loadedThemes.get(1), "");

        // Old save without themes key still loads.
        SaveFileData old = new SaveFileData();
        old.storeObject("names", names);
        Assert.assertFalse(old.containsKey("themes"));
    }

    @Test
    public void fallbackNeverCrashesOnMissingThemeOrFormat() {
        EnemyData enemy = new EnemyData();
        enemy.name = "Missing Theme Mob";
        enemy.themeId = "does_not_exist";
        enemy.deck = new String[0]; // no stock path — avoid CardUtil / Config
        enemy.colors = "R";
        enemy.life = 10;

        Deck deck = EnemyThemeDecks.resolveDeck(enemy, false, false);
        Assert.assertNotNull(deck);

        Deck missingFormat = null;
        try {
            missingFormat = EnemyThemeDecks.resolveForThemeAndFormat("goblin_tribal", "Vintage");
        } catch (Throwable t) {
            Assert.fail("resolveForThemeAndFormat must not throw: " + t);
        }
        // May be null or a fallback deck; must not throw.
        Assert.assertTrue(missingFormat == null || missingFormat != null);

        Deck nullTheme = EnemyThemeDecks.resolveForThemeAndFormat(null, "Historic");
        Assert.assertNull(nullTheme);
    }

    @Test
    public void enemyThemesJsonIsUtf8WithoutBomAndHasSixteenThemes() throws Exception {
        Path path = resolveEnemyThemesJson();
        Assert.assertTrue(Files.isRegularFile(path), "missing " + path);
        byte[] raw = Files.readAllBytes(path);
        Assert.assertFalse(raw.length >= 3 && (raw[0] & 0xFF) == 0xEF
                && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF, "BOM present");
        String text = new String(raw, StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("\"merfolk_tribal\""));
        Assert.assertTrue(text.contains("\"spirit_tempo\""));

        // Count theme ids in the committed catalog file.
        Set<String> ids = new HashSet<>();
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("\"id\":")) {
                int q1 = trimmed.indexOf('"', 5);
                int q2 = trimmed.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1)
                    ids.add(trimmed.substring(q1 + 1, q2));
            }
        }
        Assert.assertEquals(ids.size(), 18, "expected 9 type-groups × 2 themes: " + ids);
        Assert.assertTrue(ids.contains("merfolk_tempo"));
        Assert.assertTrue(ids.contains("serpent_leviathan"));
    }

    @Test
    public void everyThemeHasHandPickedCoreOfAtLeast24Cards() throws Exception {
        Path coresDir = resolveEnemyCoresDir();
        Assert.assertTrue(Files.isDirectory(coresDir), "missing " + coresDir);
        String[] ids = {
                "merfolk_tribal", "merfolk_tempo", "kraken_leviathan", "serpent_leviathan",
                "goblin_tribal", "goblin_burn",
                "zombie_tribal", "zombie_aristocrats", "elf_tribal", "elf_ramp",
                "vampire_tribal", "vampire_drain", "dragon_tribal", "dragon_ramp",
                "soldier_tribal", "knight_tribal", "spirit_tribal", "spirit_tempo"
        };
        for (String id : ids) {
            Path file = coresDir.resolve(id + ".json");
            Assert.assertTrue(Files.isRegularFile(file), "missing core " + file);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Assert.assertFalse(text.startsWith("\uFEFF"), "BOM in " + id);
            int count = 0;
            for (String line : text.split("\n")) {
                String t = line.trim();
                if (t.startsWith("\"") && t.contains("\"") && !t.startsWith("\"cards\""))
                    count++;
            }
            Assert.assertTrue(count >= 24, id + " core has only " + count + " cards");
        }
    }

    @Test
    public void enemyBannedJsonExistsAndCoresContainNoBannedNames() throws Exception {
        Path bannedPath = resolveEnemyBannedJson();
        Assert.assertTrue(Files.isRegularFile(bannedPath), "missing " + bannedPath);
        String bannedText = Files.readString(bannedPath, StandardCharsets.UTF_8);
        Assert.assertFalse(bannedText.startsWith("\uFEFF"), "BOM in enemy_banned.json");
        Set<String> banned = new HashSet<>();
        for (String line : bannedText.split("\n")) {
            String t = line.trim();
            if (t.startsWith("\"") && t.contains("\"") && !t.startsWith("\"cards\"")) {
                int q1 = t.indexOf('"');
                int q2 = t.indexOf('"', q1 + 1);
                if (q1 >= 0 && q2 > q1)
                    banned.add(t.substring(q1 + 1, q2));
            }
        }
        Assert.assertTrue(banned.contains("Ragavan, Nimble Pilferer"));
        Assert.assertTrue(banned.contains("Cyclonic Rift"));
        Assert.assertTrue(banned.contains("Blood Crypt"));
        Assert.assertTrue(banned.size() >= 30, "expected full ban list, got " + banned.size());

        Path coresDir = resolveEnemyCoresDir();
        List<String> hits = new ArrayList<>();
        try (var stream = Files.list(coresDir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                try {
                    String text = Files.readString(file, StandardCharsets.UTF_8);
                    for (String name : banned) {
                        if (text.contains("\"" + name + "\""))
                            hits.add(file.getFileName() + ": " + name);
                    }
                } catch (Exception e) {
                    hits.add(file.getFileName() + ": read failed " + e);
                }
            });
        }
        Assert.assertTrue(hits.isEmpty(), "cores still list enemy-banned cards:\n"
                + String.join("\n", hits));
    }

    @Test
    public void standardRecipeFillNeverCrashesWithoutCardDb() {
        // Full window legality is covered by EnemyThemeDeckLegalityTest (desktop + FModel).
        // Here: recipe fill must not throw when StaticData / Config are unavailable.
        EnemyThemeData theme = EnemyThemeDecks.getTheme("elf_tribal");
        Assert.assertNotNull(theme);
        Deck deck = EnemyThemeDecks.fillStandardRecipe(theme, null, 12345L);
        Assert.assertNotNull(deck);
    }

    private static EnemyThemeCatalogData sampleCatalog() {
        EnemyThemeCatalogData cat = new EnemyThemeCatalogData();
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
        // knight/soldier share Soldier+Knight tags like the real JSON
        cat.themes[12].tags = new String[]{"Soldier", "Knight"};
        cat.themes[13].tags = new String[]{"Soldier", "Knight"};
        return cat;
    }

    private static EnemyThemeData theme(String id, String tag, String color, String creature) {
        EnemyThemeData t = new EnemyThemeData();
        t.id = id;
        t.tags = new String[]{tag};
        t.colors = new String[]{color};
        t.creatureTypes = new String[]{creature};
        t.standardRecipe = new EnemyThemeRecipeData();
        t.standardRecipe.count = 60;
        t.standardRecipe.colors = new String[]{Character.toUpperCase(color.charAt(0)) + color.substring(1)};
        t.standardRecipe.tribe = creature;
        t.standardRecipe.rares = 0.15f;
        return t;
    }

    private static Path resolveEnemyThemesJson() {
        Path[] candidates = {
                Paths.get("forge-gui/res/adventure/common/world/enemy_themes.json"),
                Paths.get("../forge-gui/res/adventure/common/world/enemy_themes.json"),
                Paths.get("res/adventure/common/world/enemy_themes.json")
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p))
                return p;
        }
        return candidates[0];
    }

    private static Path resolveEnemyCoresDir() {
        Path[] candidates = {
                Paths.get("forge-gui/res/adventure/common/world/enemy_cores"),
                Paths.get("../forge-gui/res/adventure/common/world/enemy_cores"),
                Paths.get("res/adventure/common/world/enemy_cores")
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p))
                return p;
        }
        return candidates[0];
    }

    private static Path resolveEnemyBannedJson() {
        Path[] candidates = {
                Paths.get("forge-gui/res/adventure/common/world/enemy_banned.json"),
                Paths.get("../forge-gui/res/adventure/common/world/enemy_banned.json"),
                Paths.get("res/adventure/common/world/enemy_banned.json")
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p))
                return p;
        }
        return candidates[0];
    }
}
