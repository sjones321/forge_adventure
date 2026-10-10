package forge.adventure;

import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.AchievementRewardData;
import forge.adventure.player.AchievementProgress;
import forge.adventure.player.AchievementRewards;
import forge.adventure.player.AchievementService;
import forge.adventure.util.AdventureTitles;
import forge.adventure.util.EnemyThemeDecks;
import forge.adventure.util.GymUtil;
import forge.adventure.world.PlaneFormat;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Player-facing rename: Shandalar Standard / Shandalar Completionist.
 * Stored ids and Bellwarden input aliases stay valid.
 */
public class ShandalarStandardNamingTest {

    private Path achievementsFile;
    private AchievementService svc;
    private Path shippedDefs;

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();
        Path dir = AdventureTestUserDir.configuredTestUserDir().resolve("shandalar-naming");
        Files.createDirectories(dir);
        achievementsFile = dir.resolve("achievements-" + System.nanoTime() + ".json");
        svc = AchievementService.forTest(achievementsFile.toFile());
        AchievementService.setInstance(svc);
        shippedDefs = Path.of("forge-gui/res/adventure/common/world/achievements.json");
        if (!Files.isRegularFile(shippedDefs)) {
            shippedDefs = Path.of("../forge-gui/res/adventure/common/world/achievements.json");
        }
    }

    @AfterMethod
    public void tearDown() {
        AchievementService.resetInstance();
        AchievementListData.clear();
    }

    @Test
    public void standardFormatDisplayAndAllAliasesResolve() {
        Assert.assertEquals(PlaneFormat.displayName(GymUtil.FORMAT_STANDARD),
                AdventureTitles.STANDARD_FORMAT_DISPLAY);
        Assert.assertEquals(AdventureTitles.STANDARD_FORMAT_DISPLAY, "Shandalar Standard");
        Assert.assertEquals(PlaneFormat.CHOICES[0], "Shandalar Standard");

        String[] aliases = {
                "Standard", "standard",
                "Shandalar", "Shandalar Standard",
                "Bellwarden", "Bellwarden Standard"
        };
        for (String alias : aliases) {
            Assert.assertEquals(PlaneFormat.normalize(alias), GymUtil.FORMAT_STANDARD, alias);
            Assert.assertTrue(PlaneFormat.isKnown(alias), alias);
            Assert.assertEquals(EnemyThemeDecks.normalizeFormatForTest(alias),
                    GymUtil.FORMAT_STANDARD, "EN1 " + alias);
        }
    }

    @Test
    public void completionistAchievementUsesShandalarDisplayName() throws Exception {
        Assert.assertTrue(Files.isRegularFile(shippedDefs), shippedDefs.toString());
        AchievementListData.clear();
        AchievementListData.loadFromPath(shippedDefs);
        AchievementData def = AchievementListData.get("bellwarden_completionist");
        Assert.assertNotNull(def);
        Assert.assertEquals(def.id, "bellwarden_completionist");
        Assert.assertEquals(def.name, "Shandalar Completionist");
        Assert.assertTrue(def.description.contains("every Shandalar set"));
        Assert.assertTrue(def.description.contains("set plane"));
        Assert.assertNotNull(def.reward);
        Assert.assertEquals(def.reward.type, "title");
        Assert.assertEquals(def.reward.id, AdventureTitles.COMPLETIONIST_TITLE_ID);
        Assert.assertEquals(AdventureTitles.COMPLETIONIST_TITLE_ID, "Bellwarden Completionist");
        Assert.assertEquals(AdventureTitles.titleDisplayName(def.reward.id),
                "Shandalar Completionist");
    }

    @Test
    public void oldSaveKeepsStoredTitleIdAndShowsShandalarDisplay() throws Exception {
        // Mimic an older account file: owned + equipped under the historical title id.
        String oldJson = "{\n"
                + "  \"version\": 1,\n"
                + "  \"unlocked\": {\"bellwarden_completionist\": 1},\n"
                + "  \"completedSets\": [],\n"
                + "  \"titles\": [\"" + AdventureTitles.COMPLETIONIST_TITLE_ID + "\"],\n"
                + "  \"equippedTitle\": \"" + AdventureTitles.COMPLETIONIST_TITLE_ID + "\",\n"
                + "  \"trophies\": [],\n"
                + "  \"cardStyles\": [],\n"
                + "  \"pendingCardStyles\": [],\n"
                + "  \"counters\": {}\n"
                + "}\n";
        Files.writeString(achievementsFile, oldJson, StandardCharsets.UTF_8);

        AchievementService loaded = AchievementService.forTest(achievementsFile.toFile());
        AchievementProgress p = loaded.getProgress();
        Assert.assertTrue(p.getTitles().contains(AdventureTitles.COMPLETIONIST_TITLE_ID),
                "stored title id must survive load");
        Assert.assertEquals(p.getEquippedTitle(), AdventureTitles.COMPLETIONIST_TITLE_ID,
                "equipped title id must stay equipped");
        Assert.assertEquals(AdventureTitles.titleDisplayName(p.getEquippedTitle()),
                "Shandalar Completionist");
        Assert.assertEquals(AdventureTitles.titleDisplayName(
                        p.getTitles().iterator().next()),
                "Shandalar Completionist");

        // Round-trip must keep the historical id, not rewrite to the display name.
        loaded.save();
        String saved = Files.readString(achievementsFile, StandardCharsets.UTF_8);
        Assert.assertTrue(saved.contains("\"Bellwarden Completionist\""),
                "save must keep stored title id");
        Assert.assertFalse(saved.contains("\"Shandalar Completionist\""),
                "display name must not replace the stored id");
        Assert.assertTrue(saved.contains("\"equippedTitle\""), saved);

        AchievementProgress again = AchievementService.parseProgress(saved);
        Assert.assertEquals(again.getEquippedTitle(), AdventureTitles.COMPLETIONIST_TITLE_ID);
        Assert.assertTrue(again.getTitles().contains(AdventureTitles.COMPLETIONIST_TITLE_ID));
    }

    @Test
    public void grantingTitleRewardStoresHistoricalIdAndAutoEquips() {
        AchievementProgress p = new AchievementProgress();
        AchievementData def = new AchievementData();
        def.id = "bellwarden_completionist";
        def.name = "Shandalar Completionist";
        AchievementRewardData reward = new AchievementRewardData();
        reward.type = "title";
        reward.id = AdventureTitles.COMPLETIONIST_TITLE_ID;
        def.reward = reward;

        Assert.assertTrue(AchievementRewards.grant(p, reward, def));
        Assert.assertTrue(p.getTitles().contains(AdventureTitles.COMPLETIONIST_TITLE_ID));
        Assert.assertEquals(p.getEquippedTitle(), AdventureTitles.COMPLETIONIST_TITLE_ID);
        Assert.assertEquals(AdventureTitles.titleDisplayName(p.getEquippedTitle()),
                AdventureTitles.COMPLETIONIST_TITLE_DISPLAY);
    }
}
