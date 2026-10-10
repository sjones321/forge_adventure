package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.CardStorageReader;
import forge.ImageKeys;
import forge.StaticData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AccountStore;
import forge.adventure.player.AchievementService;
import forge.adventure.player.AchievementSetTracker;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.sound.SoundSystem;
import forge.util.Lang;
import forge.util.Localizer;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AC1 production-path hooks: {@link AdventurePlayer#create} rebuilds nameCounts
 * (save A → save B), and sell / salvage / auto-salvage / ante-loss
 * ({@link AdventurePlayer#removeLostCardFromPools}) un-own the last copy via
 * {@code afterCardsRemoved}. Deck edits that only move cards between deck slots
 * correctly do not un-own cards. Does not use
 * {@link AchievementSetTracker#setNameCountForTest} or call
 * {@link AchievementService#onPlayerCollectionReady} / {@code applyRemoveNames} directly.
 *
 * <p>Isolation: suite-wide {@code forge.test.userDir} / {@code test-user-home}
 * + {@link AdventureTestBootstrapListener} (CO1 #38), plus {@link AccountStore}
 * adventure-root override.
 */
@Test(singleThreaded = true)
public class AchievementsAc1PlayerHooksTest {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private static Path tempUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;
    private static StaticData magicDb;
    private static ConfigData ascendantConfig;
    private static String initError;

    private Path tempDir;
    private AchievementService svc;

    private static final String DEFS = "[\n"
            + "  {\"id\":\"hook_marker\",\"name\":\"Hook Marker\",\"description\":\"unused\","
            + "\"category\":\"collection\",\"condition\":{\"type\":\"counter\",\"key\":\"never\",\"count\":99},"
            + "\"hidden\":true,\"reward\":{\"type\":\"trophy\",\"id\":\"t\"}}\n"
            + "]\n";

    @BeforeClass
    public void bootstrapRealDbAndAscendant() {
        try {
            tempUserDir = AdventureTestUserDir.configuredTestUserDir();
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
            // Suite listener owns GuiBase; fail fast if USER_* is not under test-user-home.
            AdventureTestUserDir.requireIsolatedUserDir();
            AccountStore.setAdventureRootOverrideForTest(new File(ForgeConstants.USER_ADVENTURE_DIR));

            Path forgeGuiDir = resolveForgeGuiDir();
            Lang.createInstance("en-US");
            String langDir = forgeGuiDir.resolve("res/languages").toAbsolutePath().normalize()
                    + File.separator;
            Localizer.getInstance().initialize("en-US", langDir);
            ImageKeys.initializeDirs("", new HashMap<>(), "", "", "", "", "", "", "");
            SoundSystem.instance.setIgnorePlayRequests(true);

            String res = forgeGuiDir.resolve("res").toAbsolutePath().normalize() + File.separator;
            String cards = res + "cardsfolder" + File.separator;
            String editions = res + "editions" + File.separator;
            String tokens = res + "tokenscripts" + File.separator;
            String blocks = res + "blockdata" + File.separator;
            String customCards = forgeGuiDir.resolve("custom").toAbsolutePath().normalize()
                    + File.separator + "cards" + File.separator;
            String customEditions = forgeGuiDir.resolve("custom").toAbsolutePath().normalize()
                    + File.separator + "editions" + File.separator;

            final CardStorageReader reader = new CardStorageReader(cards, null, false);
            CardStorageReader customReader = null;
            try {
                if (Files.isDirectory(Path.of(customCards))) {
                    customReader = new CardStorageReader(customCards, null, false);
                }
            } catch (Exception ignored) {
            }
            final CardStorageReader tokenReader = new CardStorageReader(tokens, null, false);
            magicDb = new StaticData(reader, tokenReader, customReader, null, editions,
                    customEditions, blocks, "",
                    "Latest Art All Editions", true, false, false, false);
            pinStaticData(magicDb);
            AchievementSetTracker.setMagicDbForTest(magicDb);

            Path cfgPath = forgeGuiDir.resolve("res/adventure/Shandalar Ascendant/config.json");
            Assert.assertTrue(Files.isRegularFile(cfgPath), "Ascendant config missing: " + cfgPath);
            ascendantConfig = new Json().fromJson(ConfigData.class, new FileHandle(cfgPath.toFile()));
            Assert.assertNotNull(ascendantConfig);
            Assert.assertTrue(ascendantConfig.ascendantRules);

            Config.resetInstanceForTest();
            Config.instance(); // settings.json under isolated USER_ADVENTURE_DIR
            Config.installConfigDataForTest(ascendantConfig);
            Assert.assertTrue(Config.ascendant(), "hooks require Ascendant rules");
        } catch (Throwable t) {
            initError = t.getClass().getSimpleName() + ": " + t.getMessage();
            t.printStackTrace();
        }
    }

    @AfterClass(alwaysRun = true)
    public void restoreOverridesAndAssertRealUserDirUntouched() throws Exception {
        try {
            AccountStore.resetAdventureRootOverrideForTest();
            Config.resetInstanceForTest();
            AchievementService.resetInstance();
            AchievementSetTracker.setMagicDbForTest(null);
            // Unpin any reward-filter pin left by this class (or a shared suite race).
            RewardData.invalidateRewardFilterCache();
        } finally {
            if (realUserDirSnapshot != null) {
                AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot,
                        "AchievementsAc1PlayerHooksTest");
            }
        }
    }

    @BeforeMethod
    public void setUp() throws Exception {
        LOCK.lock();
        try {
            Assert.assertNull(initError, "bootstrap failed: " + initError);
            pinStaticData(magicDb);
            AchievementSetTracker.setMagicDbForTest(magicDb);
            Config.installConfigDataForTest(ascendantConfig);
            // Keep auto-salvage keep-copies at the Ascendant default unless a test overrides it.
            Config.instance().getConfigData().autoSalvageKeepCopies = ascendantConfig.autoSalvageKeepCopies;

            AchievementService.resetInstance();
            AchievementListData.clear();
            AchievementListData.loadFromJsonText(DEFS);
            tempDir = Files.createTempDirectory("ac1-player-hooks");
            AccountStore.setAdventureRootOverrideForTest(tempDir.toFile());
            Path achievementsFile = tempDir.resolve("account").resolve("achievements.json");
            Files.createDirectories(achievementsFile.getParent());
            svc = AchievementService.forTest(achievementsFile.toFile());
            svc.setToastEnabled(false);
            AchievementService.setInstance(svc);
            // Avoid scanning every Shandalar set when create() rebuilds ownership.
            svc.getSetTracker().setReachableForTest(Collections.emptyList());
        } catch (Exception e) {
            LOCK.unlock();
            throw e;
        }
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        try {
            AchievementService.resetInstance();
            AchievementListData.clear();
            RewardData.invalidateRewardFilterCache();
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
            if (LOCK.isHeldByCurrentThread()) {
                LOCK.unlock();
            }
        }
    }

    @Test
    public void nameCountsRebuildOnPlayerReadyDropsPriorSave() {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        PaperCard shock = card("Shock");
        PaperCard bolt = card("Lightning Bolt");

        // Save A (new game): collection includes Shock.
        player.create("SaveA", deckWith(shock, 2), true, 0, 0, false, false,
                easyDiff(), AdventureModes.Constructed);

        AchievementSetTracker tracker = svc.getSetTracker();
        Assert.assertTrue(tracker.isNameCountsReady());
        Assert.assertEquals(tracker.ownedCount("Shock"), 2);
        Assert.assertEquals(tracker.ownedCount("Lightning Bolt"), 0);

        // Save B (new game): different starter — A's cards must not count.
        player.create("SaveB", deckWith(bolt, 1), true, 0, 0, false, false,
                easyDiff(), AdventureModes.Constructed);

        Assert.assertEquals(tracker.ownedCount("Shock"), 0, "prior save ownership must be dropped");
        Assert.assertEquals(tracker.ownedCount("Lightning Bolt"), 1);
        Assert.assertFalse(tracker.getNameCounts().containsKey("Shock"));
        Assert.assertTrue(tracker.isNameCountsReady());
    }

    @DataProvider(name = "lastCopyRemovalPaths")
    public Object[][] lastCopyRemovalPaths() {
        return new Object[][] {
                { "sell" },
                { "salvage" },
                { "autoSalvage" },
                { "anteLoss" },
        };
    }

    /**
     * Last-copy removal un-owns via production paths. {@code anteLoss} covers
     * {@link AdventurePlayer#removeLostCardFromPools} (ante stake lost). Deck edits
     * that only add/remove from a deck slot correctly do <em>not</em> un-own cards.
     */
    @Test(dataProvider = "lastCopyRemovalPaths")
    public void lastCopyRemovalUnOwnsCard(String path) {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        PaperCard shock = card("Shock");
        PaperCard plains = card("Plains");

        // Starter deck has no Shock so salvage / auto-salvage are not blocked by deck copies.
        player.create("Remover", deckWith(plains, 1), true, 0, 0, false, false,
                easyDiff(), AdventureModes.Constructed);

        AchievementSetTracker tracker = svc.getSetTracker();

        switch (path) {
            case "sell" -> {
                player.addCard(shock, 1);
                Assert.assertEquals(tracker.ownedCount("Shock"), 1);
                int sold = player.sellCard(shock, 1);
                Assert.assertEquals(sold, 1, "sellCard must sell the last free copy");
            }
            case "salvage" -> {
                player.addCard(shock, 1);
                Assert.assertEquals(tracker.ownedCount("Shock"), 1);
                int salvaged = player.salvageCard(shock, 1);
                Assert.assertEquals(salvaged, 1, "salvageCard must salvage the last free copy");
            }
            case "autoSalvage" -> {
                Config.instance().getConfigData().autoSalvageKeepCopies = 0;
                player.setAutoSalvage(true);
                Assert.assertTrue(player.isAutoSalvage());
                // addCard → maybeAutoSalvage → salvageCard → afterCardsRemoved
                player.addCard(shock, 1);
            }
            case "anteLoss" -> {
                player.addCard(shock, 1);
                Assert.assertEquals(tracker.ownedCount("Shock"), 1);
                // Ante loss removes a copy from the collection pool. Deck edits alone
                // (moving cards between slots without ante) correctly do not un-own.
                player.removeLostCardFromPools(shock);
            }
            default -> Assert.fail("unknown path: " + path);
        }

        Assert.assertEquals(tracker.ownedCount("Shock"), 0,
                path + " must un-own the last copy through the production path");
        Assert.assertFalse(tracker.getNameCounts().containsKey("Shock"),
                path + " must remove the name from nameCounts");
        Assert.assertEquals(player.getCards().count(shock), 0);
    }

    @DataProvider(name = "commanderDeckEditPaths")
    public Object[][] commanderDeckEditPaths() {
        return new Object[][] {
                { "clearDeck" },
                { "deleteDeck" },
                { "copyDeck" },
        };
    }

    /**
     * clearDeck / deleteDeck / copyDeck must drop AC1 set-name caches the same way
     * {@link AdventurePlayer#setDeckCommander} does (RemNonCommanderDecks filter).
     */
    @Test(dataProvider = "commanderDeckEditPaths")
    public void commanderDeckEditsInvalidateAchievementCaches(String path) {
        AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        PaperCard plains = card("Plains");
        player.create("CmdEdit", deckWith(plains, 1), true, 0, 0, false, false,
                easyDiff(), AdventureModes.Constructed);

        AchievementSetTracker tracker = svc.getSetTracker();
        // Seed a fake filtered-name entry that invalidateReachable must drop.
        tracker.putFilteredNamesForTest("AC1CMD", Collections.singleton("OnlyInTest"));
        Assert.assertTrue(tracker.filteredNames("AC1CMD").contains("OnlyInTest"));

        player.getSelectedDeck().getTags().add(AdventurePlayer.COMMANDER_DECK_TAG);
        Assert.assertTrue(player.hasCommanderDeck());

        switch (path) {
            case "clearDeck" -> player.clearDeck();
            case "deleteDeck" -> {
                player.addDeck();
                player.setSelectedDeckSlot(1);
                player.getSelectedDeck().getTags().add(AdventurePlayer.COMMANDER_DECK_TAG);
                player.deleteDeck();
            }
            case "copyDeck" -> {
                int copied = player.copyDeck();
                Assert.assertTrue(copied >= 0, "copyDeck needs a free slot");
            }
            default -> Assert.fail("unknown path: " + path);
        }

        Assert.assertFalse(tracker.filteredNames("AC1CMD").contains("OnlyInTest"),
                path + " must call onCommanderDeckChanged and clear setNamesCache");
    }

    private static PaperCard card(String name) {
        List<PaperCard> all = magicDb.getCommonCards().getAllCards(name);
        Assert.assertFalse(all == null || all.isEmpty(), name + " must resolve from real card DB");
        return all.get(0);
    }

    private static Deck deckWith(PaperCard card, int count) {
        Deck deck = new Deck("hooks");
        deck.getMain().add(card, count);
        return deck;
    }

    private static DifficultyData easyDiff() {
        DifficultyData d = new DifficultyData();
        d.name = "Easy";
        d.startingLife = 20;
        d.startingMoney = 100;
        d.startingShards = 0;
        d.sellFactor = 0.2f;
        d.startItems = new String[0];
        return d;
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

    private static Path resolveForgeGuiDir() {
        for (String rel : new String[] { "forge-gui", "../forge-gui" }) {
            Path p = Path.of(rel).toAbsolutePath().normalize();
            if (Files.isDirectory(p.resolve("res/editions"))) {
                return p;
            }
        }
        throw new IllegalStateException("Could not locate forge-gui/res/editions from "
                + Path.of(".").toAbsolutePath());
    }
}
