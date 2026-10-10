package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import com.badlogic.gdx.utils.OrderedMap;
import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.AchievementRewardData;
import forge.adventure.data.UIData;
import forge.adventure.player.AccountStore;
import forge.adventure.player.AchievementProgress;
import forge.adventure.player.AchievementRewards;
import forge.adventure.player.AchievementService;
import forge.adventure.util.AdventureTitles;
import forge.adventure.util.EnemyThemeDecks;
import forge.adventure.util.GymUtil;
import forge.adventure.world.PlaneFormat;
import forge.localinstance.properties.ForgeConstants;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Player-facing rename: Shandalar Standard / Shandalar Completionist.
 * Stored ids and Bellwarden input aliases stay valid.
 */
public class ShandalarStandardNamingTest {

    private Path adventureRoot;
    private Path achievementsFile;
    private Path shippedDefs;

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();
        AchievementService.resetInstance();
        AchievementListData.clear();
        adventureRoot = AdventureTestUserDir.configuredTestUserDir()
                .resolve("shandalar-naming-" + System.nanoTime());
        Files.createDirectories(adventureRoot);
        AccountStore.setAdventureRootOverrideForTest(adventureRoot.toFile());
        achievementsFile = AccountStore.achievementsFile().toPath();
        Files.createDirectories(achievementsFile.getParent());
        shippedDefs = Path.of("forge-gui/res/adventure/common/world/achievements.json");
        if (!Files.isRegularFile(shippedDefs)) {
            shippedDefs = Path.of("../forge-gui/res/adventure/common/world/achievements.json");
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        try {
            AchievementService.resetInstance();
            AchievementListData.clear();
            // Keep AccountStore off the real ~/.forge adventure tree (same as AC1 tests).
            AccountStore.setAdventureRootOverrideForTest(new File(ForgeConstants.USER_ADVENTURE_DIR));
            if (adventureRoot != null && Files.isDirectory(adventureRoot)) {
                try (var walk = Files.walk(adventureRoot)) {
                    walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        } finally {
            // leave override on USER_ADVENTURE_DIR (isolated test user dir)
        }
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

    /**
     * Older AC1 account files listed owned titles only — no {@code equippedTitle}
     * key. Load through {@link AccountStore#achievementsFile()} +
     * {@link AchievementService#get()} and keep the title owned, equipped
     * (migrated), and shown as Shandalar Completionist on Status.
     */
    @Test
    public void oldAccountStoreSaveWithoutEquippedTitleFieldLoadsOwnedEquippedAndStatusLine()
            throws Exception {
        String titleId = AdventureTitles.COMPLETIONIST_TITLE_ID;
        // Exact shape of a pre-equip-field achievements.json (titles array only).
        String oldJson = "{\n"
                + "  \"version\": 1,\n"
                + "  \"unlocked\": {\"bellwarden_completionist\": 1700000000000},\n"
                + "  \"completedSets\": [\"MH3\", \"ONE\"],\n"
                + "  \"titles\": [\"" + titleId + "\"],\n"
                + "  \"trophies\": [\"set_complete:MH3\"],\n"
                + "  \"cardStyles\": [],\n"
                + "  \"pendingCardStyles\": [],\n"
                + "  \"counters\": {\"duelsWon\": 12}\n"
                + "}\n";
        Assert.assertFalse(oldJson.contains("equippedTitle"),
                "fixture must omit equippedTitle like older builds");
        Path accountFile = AccountStore.achievementsFile().toPath();
        Assert.assertEquals(accountFile.normalize(), achievementsFile.normalize());
        Files.writeString(accountFile, oldJson, StandardCharsets.UTF_8);

        // Production path: singleton loads AccountStore.achievementsFile().
        AchievementService.resetInstance();
        AchievementService svc = AchievementService.get();
        Assert.assertEquals(svc.getFile().normalize(), accountFile.normalize());

        AchievementProgress p = svc.getProgress();
        Assert.assertTrue(p.getTitles().contains(titleId), "owned title must survive load");
        Assert.assertEquals(p.getEquippedTitle(), titleId,
                "missing equippedTitle field must migrate to wear the owned Completionist title");
        Assert.assertEquals(AdventureTitles.titleDisplayName(p.getEquippedTitle()),
                "Shandalar Completionist");
        Assert.assertEquals(
                AdventureTitles.statusTitleLabelText(p.getEquippedTitle()),
                "[%80][DARK_GRAY]Shandalar Completionist",
                "Status playerTitle label must show the display name");

        // Round-trip via the same AccountStore path: stored id kept, equip persisted.
        svc.save();
        String saved = Files.readString(accountFile, StandardCharsets.UTF_8);
        Assert.assertTrue(saved.contains("\"Bellwarden Completionist\""),
                "save must keep historical title id");
        Assert.assertFalse(saved.contains("\"Shandalar Completionist\""),
                "display name must not replace the stored id");
        Assert.assertTrue(saved.contains("\"equippedTitle\""),
                "round-trip should persist equippedTitle after migration");

        AchievementService.resetInstance();
        AchievementService reloaded = AchievementService.get();
        AchievementProgress again = reloaded.getProgress();
        Assert.assertTrue(again.getTitles().contains(titleId));
        Assert.assertEquals(again.getEquippedTitle(), titleId);
        Assert.assertEquals(AdventureTitles.titleDisplayName(again.getEquippedTitle()),
                AdventureTitles.COMPLETIONIST_TITLE_DISPLAY);
        Assert.assertTrue(AdventureTitles.statusTitleLabelText(again.getEquippedTitle())
                .contains("Shandalar Completionist"));
    }

    @Test
    public void missingEquippedTitleAutoEquipsFirstOwnedTitle() throws Exception {
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"unlocked\": {},\n"
                + "  \"completedSets\": [],\n"
                + "  \"titles\": [\"Centurion\", \"" + AdventureTitles.COMPLETIONIST_TITLE_ID + "\"],\n"
                + "  \"trophies\": [],\n"
                + "  \"cardStyles\": [],\n"
                + "  \"pendingCardStyles\": [],\n"
                + "  \"counters\": {}\n"
                + "}\n";
        Assert.assertFalse(json.contains("equippedTitle"));
        Files.writeString(AccountStore.achievementsFile().toPath(), json, StandardCharsets.UTF_8);
        AchievementService.resetInstance();
        AchievementProgress p = AchievementService.get().getProgress();
        Assert.assertEquals(p.getEquippedTitle(), "Centurion",
                "first owned title in file order must be equipped when field is missing");
    }

    @Test
    public void orphanedEquippedTitleIsNotShownAndMigratesToOwned() throws Exception {
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"unlocked\": {},\n"
                + "  \"completedSets\": [],\n"
                + "  \"titles\": [\"Centurion\"],\n"
                + "  \"equippedTitle\": \"" + AdventureTitles.COMPLETIONIST_TITLE_ID + "\",\n"
                + "  \"trophies\": [],\n"
                + "  \"cardStyles\": [],\n"
                + "  \"pendingCardStyles\": [],\n"
                + "  \"counters\": {}\n"
                + "}\n";
        Files.writeString(AccountStore.achievementsFile().toPath(), json, StandardCharsets.UTF_8);
        AchievementService.resetInstance();
        AchievementProgress p = AchievementService.get().getProgress();
        Assert.assertEquals(p.getEquippedTitle(), "Centurion",
                "equipped id not in titles must clear and fall back to first owned");
    }

    /**
     * Mid-era file that already has {@code equippedTitle} set to the historical
     * id must keep that equip through AccountStore load and round-trip.
     */
    @Test
    public void accountStoreSaveWithEquippedTitleFieldKeepsEquipAndDisplay() throws Exception {
        String titleId = AdventureTitles.COMPLETIONIST_TITLE_ID;
        String json = "{\n"
                + "  \"version\": 1,\n"
                + "  \"unlocked\": {\"bellwarden_completionist\": 1},\n"
                + "  \"completedSets\": [],\n"
                + "  \"titles\": [\"" + titleId + "\", \"Centurion\"],\n"
                + "  \"equippedTitle\": \"" + titleId + "\",\n"
                + "  \"trophies\": [],\n"
                + "  \"cardStyles\": [],\n"
                + "  \"pendingCardStyles\": [],\n"
                + "  \"counters\": {}\n"
                + "}\n";
        Path accountFile = AccountStore.achievementsFile().toPath();
        Files.writeString(accountFile, json, StandardCharsets.UTF_8);

        AchievementService.resetInstance();
        AchievementProgress p = AchievementService.get().getProgress();
        Assert.assertTrue(p.getTitles().contains(titleId));
        Assert.assertTrue(p.getTitles().contains("Centurion"));
        Assert.assertEquals(p.getEquippedTitle(), titleId,
                "explicit equippedTitle must not be replaced by another owned title");
        Assert.assertEquals(AdventureTitles.titleDisplayName(p.getEquippedTitle()),
                "Shandalar Completionist");
        Assert.assertTrue(AdventureTitles.statusTitleLabelText(p.getEquippedTitle())
                .contains("Shandalar Completionist"));

        AchievementService.get().save();
        AchievementService.resetInstance();
        AchievementProgress again = AchievementService.get().getProgress();
        Assert.assertEquals(again.getEquippedTitle(), titleId);
        Assert.assertTrue(again.getTitles().contains(titleId));
        Assert.assertTrue(again.getTitles().contains("Centurion"));
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

    /**
     * Status {@code playerTitle} must not overlap playerName, avatar, or blessingInfo
     * in either statistic layout (same stage-rect approach as AC1 Awards).
     */
    @Test
    public void playerTitleLabelClearOfNameAvatarBlessingInBothLayouts() throws Exception {
        assertPlayerTitleClear(resolveUi("statistic.json"));
        assertPlayerTitleClear(resolveUi("statistic_portrait.json"));
    }

    private static Path resolveUi(String fileName) throws Exception {
        for (String rel : new String[] {
                "forge-gui/res/adventure/common/ui/" + fileName,
                "../forge-gui/res/adventure/common/ui/" + fileName
        }) {
            Path p = Path.of(rel);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        throw new IllegalStateException("UI layout not found: " + fileName);
    }

    private static void assertPlayerTitleClear(Path layoutFile) {
        UIData data = new Json().fromJson(UIData.class, new FileHandle(layoutFile.toFile()));
        Assert.assertTrue(data.yDown, layoutFile + " must be yDown");
        float layoutH = data.height;
        float[] title = null;
        List<float[]> others = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (OrderedMap<String, String> el : data.elements) {
            if (el == null) {
                continue;
            }
            String name = null;
            Float x = null;
            Float y = null;
            Float w = null;
            Float h = null;
            for (ObjectMap.Entry property : new OrderedMap.OrderedMapEntries<>(el)) {
                String key = property.key == null ? null : property.key.toString();
                Object val = property.value;
                if ("name".equals(key)) {
                    name = val == null ? null : val.toString();
                } else if ("x".equals(key)) {
                    x = asFloat(val);
                } else if ("y".equals(key)) {
                    y = asFloat(val);
                } else if ("width".equals(key)) {
                    w = asFloat(val);
                } else if ("height".equals(key)) {
                    h = asFloat(val);
                }
            }
            if (name == null || name.isEmpty() || "lastScreen".equals(name)) {
                continue;
            }
            if (x == null || y == null || w == null || h == null || w <= 0 || h <= 0) {
                continue;
            }
            float[] stage = new float[] { x, layoutH - y - h, w, h };
            if ("playerTitle".equals(name)) {
                title = stage;
            } else if ("playerName".equals(name) || "avatar".equals(name)
                    || "blessingInfo".equals(name)) {
                others.add(stage);
                names.add(name);
            }
        }
        Assert.assertNotNull(title, "playerTitle missing from " + layoutFile.getFileName());
        for (int i = 0; i < others.size(); i++) {
            Assert.assertFalse(rectsOverlap(title, others.get(i)),
                    "playerTitle " + Arrays.toString(title) + " overlaps " + names.get(i)
                            + " " + Arrays.toString(others.get(i)) + " in " + layoutFile.getFileName());
        }
    }

    private static Float asFloat(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return ((Number) v).floatValue();
        }
        try {
            return Float.parseFloat(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean rectsOverlap(float[] a, float[] b) {
        return a[0] < b[0] + b[2] && a[0] + a[2] > b[0]
                && a[1] < b[1] + b[3] && a[1] + a[3] > b[1];
    }
}
