package forge.adventure;

import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.AchievementRewardData;
import forge.adventure.player.AchievementProgress;
import forge.adventure.player.AchievementRewards;
import forge.adventure.player.AchievementService;
import forge.adventure.util.AtomicJsonFiles;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Headless AC1 coverage: JSON load, unlock/persist, prestige survival,
 * set / all-sets conditions, toast-once, corrupt-file recovery, UTF-8 no BOM.
 */
public class AchievementsAc1Test {

    private Path tempDir;
    private Path achievementsFile;
    private AchievementService svc;
    private final List<String> toasts = new ArrayList<>();

    private static final String DEFS = "[\n"
            + "  {\"id\":\"set_collector\",\"name\":\"Set Collector\",\"description\":\"Own a set.\","
            + "\"category\":\"collection\",\"condition\":{\"type\":\"setComplete\"},\"hidden\":false,"
            + "\"reward\":{\"type\":\"trophy\",\"id\":\"set_complete\"}},\n"
            + "  {\"id\":\"bellwarden_completionist\",\"name\":\"Bellwarden Completionist\","
            + "\"description\":\"Own every set.\",\"category\":\"collection\","
            + "\"condition\":{\"type\":\"allSetsComplete\"},\"hidden\":false,"
            + "\"reward\":{\"type\":\"title\",\"id\":\"Bellwarden Completionist\"}},\n"
            + "  {\"id\":\"coop_first_session\",\"name\":\"Together\",\"description\":\"Co-op once.\","
            + "\"category\":\"coop\",\"condition\":{\"type\":\"counter\",\"key\":\"coopSessions\",\"count\":1},"
            + "\"hidden\":false,\"reward\":{\"type\":\"trophy\",\"id\":\"coop_together\"}},\n"
            + "  {\"id\":\"style_hook\",\"name\":\"Style Hook\",\"description\":\"CS1 placeholder.\","
            + "\"category\":\"collection\",\"condition\":{\"type\":\"counter\",\"key\":\"styleHook\",\"count\":1},"
            + "\"hidden\":false,\"reward\":{\"type\":\"cardStyle\",\"id\":\"alt_art_demo\"}}\n"
            + "]\n";

    @BeforeMethod
    public void setUp() throws Exception {
        AchievementService.resetInstance();
        AchievementListData.clear();
        tempDir = Files.createTempDirectory("ac1-achievements");
        achievementsFile = tempDir.resolve("achievements.json");
        AchievementListData.loadFromJsonText(DEFS);
        svc = AchievementService.forTest(achievementsFile.toFile());
        toasts.clear();
        svc.setToastSink(toasts::add);
        svc.setToastEnabled(true);
        svc.setToastMaxPerPass(10);
        AchievementService.setInstance(svc);
    }

    @AfterMethod
    public void tearDown() throws Exception {
        AchievementService.resetInstance();
        AchievementListData.clear();
        if (tempDir != null && Files.isDirectory(tempDir)) {
            try (var walk = Files.walk(tempDir)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    @Test
    public void achievementsJsonLoads() {
        Assert.assertNotNull(AchievementListData.get("set_collector"));
        Assert.assertEquals(AchievementListData.getAll().size(), 4);
        AchievementData set = AchievementListData.get("set_collector");
        Assert.assertEquals(set.name, "Set Collector");
        Assert.assertEquals(set.condition.type, "setComplete");
        Assert.assertFalse(set.hidden);
        Assert.assertEquals(set.reward.type, "trophy");
    }

    @Test
    public void shippedAchievementsJsonParses() throws Exception {
        Path shipped = Path.of("../forge-gui/res/adventure/common/world/achievements.json");
        if (!Files.isRegularFile(shipped)) {
            shipped = Path.of("forge-gui/res/adventure/common/world/achievements.json");
        }
        Assert.assertTrue(Files.isRegularFile(shipped), "shipped achievements.json missing: " + shipped.toAbsolutePath());
        AchievementListData.clear();
        AchievementListData.loadFromPath(shipped);
        Assert.assertTrue(AchievementListData.getAll().size() >= 5);
        Assert.assertNotNull(AchievementListData.get("set_collector"));
        Assert.assertNotNull(AchievementListData.get("bellwarden_completionist"));
    }

    @Test
    public void unlockAndPersist() throws Exception {
        svc.getProgress().setCounter("coopSessions", 1);
        List<String> unlocked = svc.evaluateCounters();
        Assert.assertTrue(unlocked.contains("coop_first_session"));
        Assert.assertTrue(svc.getProgress().isUnlocked("coop_first_session"));
        Assert.assertTrue(svc.getProgress().getTrophies().contains("coop_together"));

        AchievementService reloaded = AchievementService.forTest(achievementsFile.toFile());
        reloaded.load();
        Assert.assertTrue(reloaded.getProgress().isUnlocked("coop_first_session"));
        Assert.assertEquals(reloaded.getProgress().getCounter("coopSessions"), 1);
        Assert.assertTrue(reloaded.getProgress().getTrophies().contains("coop_together"));
    }

    @Test
    public void survivesPrestigeAndNgPlus() throws Exception {
        svc.getProgress().setCounter("coopSessions", 1);
        svc.evaluateCounters();
        Assert.assertTrue(Files.isRegularFile(achievementsFile));
        String before = Files.readString(achievementsFile, StandardCharsets.UTF_8);

        // Prestige / NG+ wipe the save slot, not the account folder next to HoF/prestige.
        Path saveSlot = tempDir.resolve("1_save_slot.sav");
        Files.writeString(saveSlot, "fake-save");
        Files.deleteIfExists(saveSlot);

        AchievementService after = AchievementService.forTest(achievementsFile.toFile());
        after.load();
        Assert.assertTrue(after.getProgress().isUnlocked("coop_first_session"));
        Assert.assertEquals(Files.readString(achievementsFile, StandardCharsets.UTF_8), before);
    }

    @Test
    public void setCompletionAndAllSets() {
        List<String> bellwarden = Arrays.asList("SET_A", "SET_B");
        List<String> first = svc.applySetCompletions(Collections.singletonList("SET_A"), bellwarden);
        Assert.assertTrue(first.contains("set_collector"));
        Assert.assertFalse(first.contains("bellwarden_completionist"));
        Assert.assertTrue(svc.getProgress().getCompletedSets().contains("SET_A"));
        Assert.assertTrue(svc.getProgress().getTrophies().contains("set_complete:SET_A"));

        List<String> second = svc.applySetCompletions(Collections.singletonList("SET_B"), bellwarden);
        Assert.assertTrue(svc.getProgress().getCompletedSets().containsAll(bellwarden));
        Assert.assertTrue(second.contains("bellwarden_completionist")
                || svc.getProgress().isUnlocked("bellwarden_completionist"));
        Assert.assertTrue(svc.getProgress().getTitles().contains("Bellwarden Completionist"));
        Assert.assertTrue(svc.getProgress().getTrophies().contains("set_complete:SET_B"));
    }

    @Test
    public void toastFiresOnce() {
        svc.getProgress().setCounter("coopSessions", 1);
        svc.evaluateCounters();
        Assert.assertEquals(toasts.size(), 1);
        Assert.assertTrue(toasts.get(0).contains("Together"));
        Assert.assertTrue(svc.wasToasted("coop_first_session"));

        int before = toasts.size();
        svc.evaluateCounters();
        Assert.assertEquals(toasts.size(), before);
    }

    @Test
    public void setCompletionToastOncePerSet() {
        List<String> bellwarden = Arrays.asList("SET_A", "SET_B");
        svc.applySetCompletions(Collections.singletonList("SET_A"), bellwarden);
        int afterFirst = toasts.size();
        Assert.assertTrue(afterFirst >= 1);
        svc.applySetCompletions(Collections.singletonList("SET_A"), bellwarden);
        Assert.assertEquals(toasts.size(), afterFirst, "repeat set must not re-toast");
        svc.applySetCompletions(Collections.singletonList("SET_B"), bellwarden);
        Assert.assertTrue(toasts.size() > afterFirst, "new set should toast");
    }

    @Test
    public void corruptFileRecoversWithoutWipe() throws Exception {
        svc.getProgress().setCounter("coopSessions", 1);
        svc.evaluateCounters();
        Assert.assertTrue(svc.getProgress().isUnlocked("coop_first_session"));

        Files.writeString(achievementsFile, "{not valid json!!!", StandardCharsets.UTF_8);

        AchievementService recovered = AchievementService.forTest(achievementsFile.toFile());
        recovered.load();
        Assert.assertTrue(recovered.getProgress().isUnlocked("coop_first_session"),
                "corrupt file must fall back to .bak without crashing or wiping");
        Path corrupt = achievementsFile.resolveSibling(achievementsFile.getFileName() + ".corrupt");
        Assert.assertTrue(Files.isRegularFile(corrupt) || recovered.getProgress().isUnlocked("coop_first_session"));
    }

    @Test
    public void missingFileLoadsEmpty() {
        Assert.assertFalse(Files.exists(achievementsFile));
        svc.load();
        Assert.assertTrue(svc.getProgress().getUnlocked().isEmpty());
        Assert.assertTrue(svc.getProgress().getCompletedSets().isEmpty());
    }

    @Test
    public void writeIsUtf8WithoutBom() throws Exception {
        svc.getProgress().setCounter("coopSessions", 1);
        svc.evaluateCounters();
        byte[] raw = Files.readAllBytes(achievementsFile);
        Assert.assertFalse(raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB
                && (raw[2] & 0xFF) == 0xBF, "must not write UTF-8 BOM");
        String text = new String(raw, StandardCharsets.UTF_8);
        Assert.assertTrue(text.contains("\"coop_first_session\""));
        Assert.assertEquals(AtomicJsonFiles.stripBom("\uFEFFabc"), "abc");
    }

    @Test
    public void cardStyleRewardHookStoresForCs1() {
        AchievementData def = AchievementListData.get("style_hook");
        Assert.assertNotNull(def);
        AchievementRewardData reward = def.reward;
        Assert.assertEquals(reward.type, "cardStyle");
        boolean granted = AchievementRewards.grant(svc.getProgress(), reward, def);
        Assert.assertTrue(granted);
        Assert.assertTrue(svc.getProgress().getCardStyles().contains("alt_art_demo"));
    }

    @Test
    public void progressRoundTripJson() {
        AchievementProgress p = new AchievementProgress();
        p.unlock("a", 123L);
        p.addCompletedSet("MH3");
        p.addTitle("Champion");
        p.addTrophy("t1");
        p.addCardStyle("style1");
        p.setCounter("duelsWon", 7);
        String json = AchievementService.toJson(p);
        AchievementProgress back = AchievementService.parseProgress(json);
        Assert.assertNotNull(back);
        Assert.assertTrue(back.isUnlocked("a"));
        Assert.assertTrue(back.getCompletedSets().contains("MH3"));
        Assert.assertTrue(back.getTitles().contains("Champion"));
        Assert.assertTrue(back.getTrophies().contains("t1"));
        Assert.assertTrue(back.getCardStyles().contains("style1"));
        Assert.assertEquals(back.getCounter("duelsWon"), 7);
    }
}
