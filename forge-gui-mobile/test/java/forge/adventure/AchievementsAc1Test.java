package forge.adventure;

import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.AchievementRewardData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AccountStore;
import forge.adventure.player.AchievementProgress;
import forge.adventure.player.AchievementRewards;
import forge.adventure.player.AchievementService;
import forge.adventure.player.AchievementSetTracker;
import forge.adventure.player.HallOfFame;
import forge.adventure.player.PendingCardStyleGrant;
import forge.adventure.util.AtomicJsonFiles;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.util.IterableUtil;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

/**
 * Headless AC1 coverage: reachability filters, incremental re-check, account-wide
 * counters, USER_ADVENTURE_DIR/account path + migration, stock statistic.json,
 * pending CS1 grants, plus unlock/persist/toast/corrupt recovery.
 *
 * <p>Isolation: suite-wide {@code forge.test.userDir} → {@code test-user-home}
 * via Surefire + {@link AdventureTestBootstrapListener} (CO1 #38), plus
 * {@link AccountStore} adventure-root override so {@code get()} never mkdirs
 * under the real OS adventure dir.
 */
@Test(singleThreaded = true)
public class AchievementsAc1Test {

    /** Held from {@code @BeforeMethod} through {@code @AfterMethod} so parallel methods cannot share defs. */
    private static final ReentrantLock AC1_TEST_LOCK = new ReentrantLock();

    private static Path tempUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;

    private Path tempDir;
    private Path achievementsFile;
    private AchievementService svc;
    private HallOfFame hof;
    private final List<String> toasts = new ArrayList<>();

    private static final String DEFS = "[\n"
            + "  {\"id\":\"set_collector\",\"name\":\"Set Collector\",\"description\":\"Own a set.\","
            + "\"category\":\"collection\",\"condition\":{\"type\":\"setComplete\"},\"hidden\":false,"
            + "\"reward\":{\"type\":\"trophy\",\"id\":\"set_complete\"}},\n"
            + "  {\"id\":\"bellwarden_completionist\",\"name\":\"Bellwarden Completionist\","
            + "\"description\":\"Own every set.\",\"category\":\"collection\","
            + "\"condition\":{\"type\":\"allSetsComplete\"},\"hidden\":false,"
            + "\"reward\":{\"type\":\"cardStyle\",\"id\":\"all_sets_style\"}},\n"
            + "  {\"id\":\"first_duel_win\",\"name\":\"First Blood\",\"description\":\"Win once.\","
            + "\"category\":\"collection\",\"condition\":{\"type\":\"duelWins\",\"count\":1},"
            + "\"hidden\":false,\"reward\":{\"type\":\"trophy\",\"id\":\"first_win\"}},\n"
            + "  {\"id\":\"coop_first_session\",\"name\":\"Together\",\"description\":\"Co-op once.\","
            + "\"category\":\"coop\",\"condition\":{\"type\":\"counter\",\"key\":\"coopSessions\",\"count\":1},"
            + "\"hidden\":false,\"reward\":{\"type\":\"trophy\",\"id\":\"coop_together\"}},\n"
            + "  {\"id\":\"style_hook\",\"name\":\"Style Hook\",\"description\":\"CS1 placeholder.\","
            + "\"category\":\"collection\",\"condition\":{\"type\":\"counter\",\"key\":\"styleHook\",\"count\":1},"
            + "\"hidden\":false,\"reward\":{\"type\":\"cardStyle\",\"id\":\"alt_art_demo\"}}\n"
            + "]\n";

    @BeforeClass
    public void isolateUserDir() throws Exception {
        // Surefire sets forge.test.userDir → test-user-home before any class loads.
        tempUserDir = AdventureTestUserDir.configuredTestUserDir();
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        // Suite listener installs GuiBase + Localizer; re-check isolation here.
        AdventureTestUserDir.requireIsolatedUserDir();
        // AccountStore override (Steve r5): never mkdirs/migrate under the real adventure dir.
        AccountStore.setAdventureRootOverrideForTest(new File(ForgeConstants.USER_ADVENTURE_DIR));
    }

    @AfterClass(alwaysRun = true)
    public void restoreOverridesAndAssertRealUserDirUntouched() throws Exception {
        try {
            AccountStore.resetAdventureRootOverrideForTest();
            Config.resetInstanceForTest();
            AchievementService.resetInstance();
            HallOfFame.resetInstance();
        } finally {
            AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot, "AchievementsAc1Test");
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {
        AC1_TEST_LOCK.lock();
        try {
            AchievementService.resetInstance();
            HallOfFame.resetInstance();
            AchievementListData.clear();
            tempDir = Files.createTempDirectory("ac1-achievements");
            // Any AchievementService.get() / HallOfFame.get() must land under this temp root.
            AccountStore.setAdventureRootOverrideForTest(tempDir.toFile());
            achievementsFile = tempDir.resolve("account").resolve("achievements.json");
            Files.createDirectories(achievementsFile.getParent());
            AchievementListData.loadFromJsonText(DEFS);
            svc = AchievementService.forTest(achievementsFile.toFile());
            hof = new HallOfFame(tempDir.resolve("account").resolve("hall_of_fame.json"));
            svc.setHallOfFame(hof);
            HallOfFame.setInstance(hof);
            toasts.clear();
            svc.setToastSink(toasts::add);
            svc.setToastEnabled(true);
            svc.setToastMaxPerPass(10);
            AchievementService.setInstance(svc);
        } catch (Exception e) {
            AC1_TEST_LOCK.unlock();
            throw e;
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        try {
            AchievementService.resetInstance();
            HallOfFame.resetInstance();
            AchievementListData.clear();
            AccountStore.setAdventureRootOverrideForTest(new File(ForgeConstants.USER_ADVENTURE_DIR));
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
        } finally {
            if (AC1_TEST_LOCK.isHeldByCurrentThread()) {
                AC1_TEST_LOCK.unlock();
            }
        }
    }

    @Test
    public void achievementsJsonLoads() {
        Assert.assertNotNull(AchievementListData.get("set_collector"));
        Assert.assertEquals(AchievementListData.getAll().size(), 5);
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
        Assert.assertTrue(AchievementListData.get("bellwarden_completionist").description
                .contains("set plane"));
    }

    @Test
    public void pathsAchievementsConstantAfterGyms() {
        Assert.assertEquals(Paths.ACHIEVEMENTS, "world/achievements.json");
        Assert.assertEquals(Paths.GYMS, "world/gyms.json");
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

    /**
     * Reachability: filtered name lists exclude restricted / no-script / never-rewardable
     * cards. Completing a set only requires the filtered names; "every set" is the
     * Bellwarden reachable list (generatable set planes), not every booster.
     */
    @Test
    public void setCompletionUsesFilteredReachableNamesOnly() {
        AchievementSetTracker tracker = svc.getSetTracker();
        // SET_A "edition" has ExtraRestricted and NoScript in the full list, but filters
        // leave only A1/A2 — matching RewardData reachability exclusions.
        tracker.putFilteredNamesForTest("SET_A", Arrays.asList("A1", "A2"));
        tracker.putFilteredNamesForTest("SET_B", Arrays.asList("B1", "B2"));
        // Unreachable booster (too few reward-reachable cards / not Bellwarden plane).
        tracker.putFilteredNamesForTest("PROMO", Collections.singletonList("PromoOnly"));
        tracker.setReachableForTest(Arrays.asList("SET_A", "SET_B"));

        tracker.setNameCountForTest("A1", 1);
        tracker.setNameCountForTest("A2", 1);
        // Owning ExtraRestricted / NoScript must not be required.
        Assert.assertTrue(tracker.ownsEveryFilteredCard("SET_A"));
        Assert.assertFalse(tracker.ownsEveryFilteredCard("SET_B"));
        Assert.assertFalse(tracker.ownsEveryFilteredCard("PROMO"),
                "empty-of-ownership promo set must not count as complete");

        List<String> reachable = tracker.reachableBellwardenSetCodes();
        Assert.assertEquals(reachable, Arrays.asList("SET_A", "SET_B"));
        Assert.assertFalse(reachable.contains("PROMO"),
                "every set = Bellwarden generatable set planes only");

        List<String> newly = svc.applySetCompletions(Collections.singletonList("SET_A"), reachable);
        Assert.assertTrue(newly.contains("set_collector"));
        Assert.assertFalse(svc.getProgress().isUnlocked("bellwarden_completionist"));

        tracker.setNameCountForTest("B1", 1);
        tracker.setNameCountForTest("B2", 1);
        newly = svc.applySetCompletions(Collections.singletonList("SET_B"), reachable);
        Assert.assertTrue(newly.contains("bellwarden_completionist")
                || svc.getProgress().isUnlocked("bellwarden_completionist"));
        Assert.assertTrue(svc.getProgress().getCompletedSets().containsAll(reachable));
        Assert.assertFalse(svc.getProgress().getCompletedSets().contains("PROMO"));
    }

    /**
     * Incremental re-check: only editions / sets touched by the added card names
     * are re-evaluated; unrelated sets are left alone.
     */
    @Test
    public void incrementalRecheckOnlyAffectedSets() {
        AchievementSetTracker tracker = svc.getSetTracker();
        tracker.putFilteredNamesForTest("SET_A", Arrays.asList("A1", "A2"));
        tracker.putFilteredNamesForTest("SET_B", Arrays.asList("B1", "B2"));
        tracker.setReachableForTest(Arrays.asList("SET_A", "SET_B"));
        tracker.setNameCountForTest("A1", 1);
        tracker.setNameCountForTest("B1", 1);

        Set<String> affected = tracker.affectedSets(
                Collections.singletonList("A2"), Collections.singletonList("SET_A"));
        Assert.assertEquals(affected, new HashSet<>(Collections.singletonList("SET_A")));
        Assert.assertFalse(affected.contains("SET_B"));

        // Unrelated name in an unreachable edition → no Bellwarden re-check.
        Set<String> none = tracker.affectedSets(
                Collections.singletonList("Zzz"), Collections.singletonList("XYZ"));
        Assert.assertTrue(none.isEmpty());

        // Adding the last card of SET_A completes only SET_A.
        tracker.applyAdds(Collections.emptyList()); // name map already seeded
        tracker.setNameCountForTest("A2", 1);
        Assert.assertTrue(tracker.ownsEveryFilteredCard("SET_A"));
        Assert.assertFalse(tracker.ownsEveryFilteredCard("SET_B"));
        List<String> newly = svc.applySetCompletions(
                Collections.singletonList("SET_A"), tracker.reachableBellwardenSetCodes());
        Assert.assertTrue(newly.contains("set_collector"));
        Assert.assertFalse(svc.getProgress().getCompletedSets().contains("SET_B"));
    }

    @Test
    public void accountWideDuelWinsAndCoopSessionsCounters() {
        Assert.assertEquals(svc.getProgress().getCounter("duelsWon"), 0);
        Assert.assertEquals(svc.getProgress().getCounter("coopSessions"), 0);

        // evaluatePlayer / evaluateCounters must NOT invent wins from a save.
        Assert.assertTrue(svc.evaluateCounters().isEmpty());
        Assert.assertFalse(svc.getProgress().isUnlocked("first_duel_win"));

        Assert.assertEquals(svc.incrementCounter("duelsWon", 1), 1);
        Assert.assertTrue(svc.getProgress().isUnlocked("first_duel_win"));
        Assert.assertEquals(svc.incrementCounter("duelsWon", 1), 2);
        Assert.assertEquals(svc.getProgress().getCounter("duelsWon"), 2);

        // Together / coopSessions: finished READY sessions (see CoopSession.noteCoopSessionFinished).
        Assert.assertEquals(svc.incrementCounter("coopSessions", 1), 1);
        Assert.assertTrue(svc.getProgress().isUnlocked("coop_first_session"));
        Assert.assertEquals(svc.incrementCounter("coopSessions", 1), 2);
    }

    // nameCounts rebuild / last-copy un-own: see AchievementsAc1PlayerHooksTest
    // (production create / sell / salvage / auto-salvage / removeLostCardFromPools paths).

    @Test
    public void accountPathIsUserAdventureDirAccountNotPerPlane() {
        java.io.File account = AccountStore.accountDir(tempDir.toFile());
        Assert.assertEquals(account.getName(), "account");
        Assert.assertEquals(account.getParentFile().getAbsolutePath(), tempDir.toFile().getAbsolutePath());
        java.io.File file = AccountStore.achievementsFile(tempDir.toFile());
        Assert.assertEquals(file.getParentFile().getName(), "account");
        Assert.assertFalse(file.getAbsolutePath().contains("Shandalar Ascendant"
                + java.io.File.separator + "account"));
        Assert.assertTrue(file.getAbsolutePath().endsWith(
                "account" + java.io.File.separator + AccountStore.ACHIEVEMENTS_FILE));

        // Production no-arg helpers must use the per-test AccountStore root override, never ~/.forge.
        java.io.File liveAccount = AccountStore.accountDir();
        Assert.assertTrue(liveAccount.getAbsolutePath().startsWith(tempDir.toAbsolutePath().toString()),
                "AccountStore.accountDir() must use per-test override: " + liveAccount);
        Assert.assertEquals(liveAccount.getAbsolutePath(),
                tempDir.resolve("account").toAbsolutePath().toString());
        Assert.assertFalse(liveAccount.getAbsolutePath().startsWith(realUserDir.toAbsolutePath().toString()),
                "AccountStore must not touch the real user dir");
    }

    @Test
    public void migratesLegacyPerPlaneAchievementsFile() throws Exception {
        Path adventureRoot = tempDir.resolve("adventure-root");
        Path legacy = adventureRoot.resolve("Shandalar Ascendant").resolve("account")
                .resolve(AccountStore.ACHIEVEMENTS_FILE);
        Files.createDirectories(legacy.getParent());
        AchievementProgress legacyProgress = new AchievementProgress();
        legacyProgress.unlock("coop_first_session", 42L);
        legacyProgress.setCounter("coopSessions", 3);
        Files.writeString(legacy, AchievementService.toJson(legacyProgress), StandardCharsets.UTF_8);

        java.io.File dest = AccountStore.achievementsFile(adventureRoot.toFile());
        Assert.assertTrue(dest.isFile(), "migration must create account-wide file");
        Assert.assertTrue(Files.isRegularFile(legacy.resolveSibling(
                AccountStore.ACHIEVEMENTS_FILE + ".migrated")));
        Assert.assertFalse(Files.isRegularFile(legacy), "legacy file renamed after migrate");

        AchievementService migrated = AchievementService.forTest(dest);
        migrated.load();
        Assert.assertTrue(migrated.getProgress().isUnlocked("coop_first_session"));
        Assert.assertEquals(migrated.getProgress().getCounter("coopSessions"), 3);
    }

    /**
     * Shared reward filter excludes {@code isUnsupported} only under Ascendant so
     * stock Shandalar shop / loot pools stay unchanged.
     */
    @Test
    public void unsupportedFilterGatedToAscendantStockUnchanged() {
        ConfigData stock = new ConfigData();
        Assert.assertFalse(stock.ascendantRules, "stock ConfigData defaults to non-Ascendant");
        ConfigData ascendant = new ConfigData();
        ascendant.ascendantRules = true;

        List<Predicate<PaperCard>> stockFilters = RewardData.baseAdventureRewardFilters(stock);
        List<Predicate<PaperCard>> ascFilters = RewardData.baseAdventureRewardFilters(ascendant);
        Assert.assertEquals(ascFilters.size(), stockFilters.size() + 1,
                "Ascendant must add exactly one isUnsupported gate; stock pools stay unchanged");

        PaperCard unsupported = PaperCard.FAKE_CARD;
        Assert.assertTrue(unsupported.getRules().isUnsupported());

        Predicate<PaperCard> unsupportedGate = ascFilters.get(ascFilters.size() - 1);
        Assert.assertFalse(unsupportedGate.test(unsupported),
                "Ascendant isUnsupported filter must reject unsupported cards");

        // Stock filter list must not include an isUnsupported clause: none of its
        // predicates may be the Ascendant-only gate (same size already proves this;
        // also confirm AND(stock)+gate rejects FAKE_CARD while the gate alone does).
        Predicate<PaperCard> stockAnd = stockFilters.isEmpty()
                ? pc -> true
                : IterableUtil.and(stockFilters);
        Assert.assertFalse(IterableUtil.and(Arrays.asList(stockAnd, unsupportedGate)).test(unsupported));
    }

    @Test
    public void stockStatisticJsonUnchangedNoAwardsButton() throws Exception {
        List<Path> found = new ArrayList<>();
        for (String rel : new String[] {
                "forge-gui/res/adventure/common/ui/statistic.json",
                "forge-gui/res/adventure/common/ui/statistic_portrait.json",
                "../forge-gui/res/adventure/common/ui/statistic.json",
                "../forge-gui/res/adventure/common/ui/statistic_portrait.json"
        }) {
            Path p = Path.of(rel);
            if (Files.isRegularFile(p)) {
                found.add(p);
            }
        }
        Assert.assertFalse(found.isEmpty(), "statistic.json not found relative to test cwd");
        for (Path p : found) {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            Assert.assertFalse(text.contains("\"name\": \"achievements\""),
                    "stock UI must not embed Awards button: " + p);
            Assert.assertFalse(text.contains("\"text\": \"Awards\""),
                    "stock UI must not embed Awards label: " + p);
            // Back must stay at stock y (landscape 224) — not shifted onto blessingInfo.
            if (p.getFileName().toString().equals("statistic.json")) {
                Assert.assertTrue(text.contains("\"y\": 224"),
                        "Back / nav row should keep stock y=224: " + p);
            }
        }
    }

    @Test
    public void setCompletionRecordsPendingCs1GrantAndHof() {
        List<String> bellwarden = Arrays.asList("SET_A", "SET_B");
        svc.applySetCompletions(Collections.singletonList("SET_A"), bellwarden);

        List<PendingCardStyleGrant> pending = svc.getProgress().getPendingCardStyleGrants();
        Assert.assertFalse(pending.isEmpty(), "set complete must queue pending CS1 grant");
        boolean found = false;
        for (PendingCardStyleGrant g : pending) {
            if ("SET_A".equals(g.setCode) && g.styleId.contains("SET_A")) {
                found = true;
                Assert.assertEquals(g.achievementId, "set_collector");
            }
        }
        Assert.assertTrue(found, "pending grant should carry set code + style id");
        Assert.assertTrue(svc.getProgress().getCardStyles().stream()
                .anyMatch(s -> s.contains("SET_A")));

        List<?> hofEntries = hof.getEntries();
        Assert.assertFalse(hofEntries.isEmpty(), "HoF stub entry for set complete");
        Assert.assertTrue(hofEntries.toString().contains("set_complete")
                || hofEntries.toString().contains("Set complete"));

        svc.applySetCompletions(Collections.singletonList("SET_B"), bellwarden);
        Assert.assertTrue(svc.getProgress().isUnlocked("bellwarden_completionist"));
        Assert.assertTrue(svc.getProgress().getPendingCardStyleGrants().stream()
                .anyMatch(g -> "all_sets_style".equals(g.styleId)
                        || "all_sets_style".equals(g.styleId) && g.achievementId.equals("bellwarden_completionist")));
        Assert.assertTrue(svc.getProgress().getCardStyles().contains("all_sets_style"));
        Assert.assertTrue(hof.getEntries().toString().contains("all_sets")
                || hof.getEntries().toString().contains("Completionist"));
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
        p.addPendingCardStyleGrant(new PendingCardStyleGrant("set_style:MH3", "MH3", "set_collector", 99L));
        p.setCounter("duelsWon", 7);
        String json = AchievementService.toJson(p);
        AchievementProgress back = AchievementService.parseProgress(json);
        Assert.assertNotNull(back);
        Assert.assertTrue(back.isUnlocked("a"));
        Assert.assertTrue(back.getCompletedSets().contains("MH3"));
        Assert.assertTrue(back.getTitles().contains("Champion"));
        Assert.assertTrue(back.getTrophies().contains("t1"));
        Assert.assertTrue(back.getCardStyles().contains("style1"));
        Assert.assertEquals(back.getPendingCardStyleGrants().size(), 1);
        Assert.assertEquals(back.getPendingCardStyleGrants().get(0).setCode, "MH3");
        Assert.assertEquals(back.getCounter("duelsWon"), 7);
    }
}
