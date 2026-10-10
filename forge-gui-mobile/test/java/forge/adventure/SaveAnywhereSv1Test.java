package forge.adventure;

import com.badlogic.gdx.math.Rectangle;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.PlayerSprite;
import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopSessionRole;
import forge.adventure.data.AdventureQuestData;
import forge.adventure.data.AdventureQuestStage;
import forge.adventure.data.ConfigData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.MapStage;
import forge.adventure.util.AdventureQuestController;
import forge.adventure.util.Config;
import forge.adventure.util.InteriorMapSave;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.card.ColorSet;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * SV1: save inside maps, restore position, pre-duel autosave, quest-safe ENTERPOI.
 * Real save/load behaviour — no source greps. GL tests use {@link AdventureGlTestSupport}
 * and the Surefire {@code forge.test.userDir} isolation.
 */
public class SaveAnywhereSv1Test {

    private static final int SLOT = 91;
    private static final long WORLD_SEED = 0x515AFE01L;

    @BeforeMethod
    public void setUp() {
        AdventureTestUserDir.requireIsolatedUserDir();
        InteriorMapSave.clearPending();
        resetCoopSession();
    }

    @AfterMethod
    public void tearDown() {
        InteriorMapSave.clearPending();
        resetCoopSession();
        deleteSlot(SLOT);
        deleteSlot(WorldSave.AUTO_SAVE_SLOT);
    }

    @Test
    public void oldSaveWithoutInteriorFieldDoesNotPendRestore() {
        SaveFileData data = new SaveFileData();
        data.store("worldPosX", 10f);
        data.store("worldPosY", 20f);
        InteriorMapSave.readPending(data);
        Assert.assertFalse(InteriorMapSave.hasPending(),
                "Old saves without interior keys must load on the world map");
    }

    @Test
    public void interiorKeysRoundTripThroughSaveFileData() {
        SaveFileData data = new SaveFileData();
        data.store(InteriorMapSave.KEY_POI_ID, "poi-test-1");
        data.store(InteriorMapSave.KEY_MAP_PATH, "../common/maps/map/cave/cave_bear.tmx");
        data.store(InteriorMapSave.KEY_ENTRANCE_ID, 3);
        data.store(InteriorMapSave.KEY_POS_X, 120.5f);
        data.store(InteriorMapSave.KEY_POS_Y, 80.25f);

        InteriorMapSave.readPending(data);
        Assert.assertTrue(InteriorMapSave.hasPending());
        Assert.assertEquals(InteriorMapSave.pendingPoiIdForTest(), "poi-test-1");
        InteriorMapSave.clearPending();
        Assert.assertFalse(InteriorMapSave.hasPending());
    }

    @Test
    public void stockModeDoesNotWriteInteriorKeys() {
        ConfigData stock = new ConfigData();
        stock.ascendantRules = false;
        Config.installConfigDataForTest(stock);
        try {
            SaveFileData data = new SaveFileData();
            InteriorMapSave.write(data);
            Assert.assertFalse(data.containsKey(InteriorMapSave.KEY_POI_ID),
                    "Stock modes must not write SV1 interior fields");
        } finally {
            Config.resetInstanceForTest();
        }
    }

    @Test
    public void autosaveBeforeDuelSkippedDuringCoopSession() throws Exception {
        assumeAscendantConfig();
        setCoopActiveHost();
        Assert.assertTrue(CoopSession.get().isActive());
        File auto = new File(WorldSave.getSaveFile(WorldSave.AUTO_SAVE_SLOT));
        if (auto.isFile()) {
            Assert.assertTrue(auto.delete());
        }
        InteriorMapSave.maybeAutosaveBeforeDuel();
        Assert.assertFalse(auto.isFile(),
                "Autosave must not run during an active co-op session");
    }

    @Test(groups = "gl", timeOut = 300_000)
    public void saveLoadInsideDungeonKeepsPositionAndDeletedEnemy() {
        AdventureGlTestSupport.runOnGl(() -> {
            PointOfInterest dungeon = generateWorldAndPickDungeon();
            TileMapScene.instance().load(dungeon);
            MapStage map = MapStage.getInstance();
            Assert.assertTrue(map.isInMap());
            Assert.assertFalse(map.enemies.isEmpty(), "Test dungeon must have enemies");

            EnemySprite victim = map.enemies.get(0);
            int victimId = victim.getId();
            map.getChanges().deleteObject(victimId);
            victim.remove();
            map.enemies.remove(victim);

            PlayerSprite player = map.getPlayerSprite();
            float savedX = player.getX() + 8f;
            float savedY = player.getY() + 4f;
            if (!map.tryRestorePlayerPosition(savedX, savedY)) {
                savedX = player.getX();
                savedY = player.getY();
            }

            Assert.assertTrue(WorldSave.getCurrentSave().save("sv1-dungeon", SLOT));
            MapStage.getInstance().clearIsInMap();
            InteriorMapSave.clearPending();

            Assert.assertTrue(WorldSave.load(SLOT));
            Assert.assertTrue(InteriorMapSave.hasPending(), "Interior fields must be pending after load");
            Assert.assertTrue(InteriorMapSave.prepareRestoredMap());
            MapStage restored = MapStage.getInstance();
            Assert.assertTrue(restored.isInMap());
            Assert.assertEquals(TileMapScene.instance().rootPoint.getID(), dungeon.getID());
            Assert.assertEquals(restored.getPlayerSprite().getX(), savedX, 0.01f);
            Assert.assertEquals(restored.getPlayerSprite().getY(), savedY, 0.01f);
            Assert.assertTrue(restored.getChanges().isObjectDeleted(victimId),
                    "Killed enemy must stay deleted via PointOfInterestChanges");
            Assert.assertNull(restored.getEnemyByID(victimId),
                    "Deleted enemy must not respawn on the map");
        });
    }

    @Test(groups = "gl", timeOut = 300_000)
    public void blockedSavedPositionFallsBackToEntrance() {
        AdventureGlTestSupport.runOnGl(() -> {
            PointOfInterest dungeon = generateWorldAndPickDungeon();
            TileMapScene.instance().load(dungeon);
            MapStage map = MapStage.getInstance();
            PlayerSprite player = map.getPlayerSprite();
            float entranceX = player.getX();
            float entranceY = player.getY();

            float blockedX = entranceX + 64f;
            float blockedY = entranceY + 64f;
            map.collisionRect.add(new Rectangle(blockedX - 2f, blockedY - 2f, 40f, 40f));

            Assert.assertFalse(map.tryRestorePlayerPosition(blockedX, blockedY),
                    "Blocked position must be rejected");
            Assert.assertEquals(player.getX(), entranceX, 0.01f);
            Assert.assertEquals(player.getY(), entranceY, 0.01f);
        });
    }

    @Test(groups = "gl", timeOut = 300_000)
    public void autosaveWrittenAtDuelStartNotDuringCoop() {
        AdventureGlTestSupport.runOnGl(() -> {
            PointOfInterest dungeon = generateWorldAndPickDungeon();
            TileMapScene.instance().load(dungeon);
            Assert.assertFalse(MapStage.getInstance().enemies.isEmpty());

            File auto = new File(WorldSave.getSaveFile(WorldSave.AUTO_SAVE_SLOT));
            if (auto.isFile()) {
                Assert.assertTrue(auto.delete());
            }

            PlayerSprite player = MapStage.getInstance().getPlayerSprite();
            EnemySprite enemy = MapStage.getInstance().enemies.get(0);
            DuelScene.instance().initDuels(player, enemy);

            Assert.assertTrue(auto.isFile(), "SV1 must write the rotating autosave at duel start");
            long written = auto.lastModified();

            setCoopActiveHost();
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            InteriorMapSave.maybeAutosaveBeforeDuel();
            Assert.assertEquals(auto.lastModified(), written,
                    "Autosave must not rewrite during an active co-op session");
        });
    }

    @Test(groups = "gl", timeOut = 300_000)
    public void loadInsideQuestDungeonDoesNotDoubleCompleteTravelStage() {
        AdventureGlTestSupport.runOnGl(() -> {
            PointOfInterest dungeon = generateWorldAndPickDungeon();

            AdventureQuestStage stage = new AdventureQuestStage();
            stage.id = 1;
            stage.objective = AdventureQuestController.ObjectiveTypes.Travel;
            stage.count3 = 1;
            stage.setTargetPOI(dungeon);
            stage.checkPrerequisites(Collections.emptyList());
            Assert.assertEquals(stage.getStatus(), AdventureQuestController.QuestStatus.ACTIVE);

            AdventureQuestData quest = new AdventureQuestData();
            quest.name = "SV1 Quest Safety";
            quest.stages = new AdventureQuestStage[]{stage};
            WorldSave.getCurrentSave().getPlayer().addQuest(quest, false);

            TileMapScene.instance().load(dungeon);
            AdventureQuestController.instance().updateEnteredPOI(dungeon);
            Assert.assertEquals(stage.getStatus(), AdventureQuestController.QuestStatus.COMPLETE);

            Assert.assertTrue(WorldSave.getCurrentSave().save("sv1-quest", SLOT));
            MapStage.getInstance().clearIsInMap();
            InteriorMapSave.clearPending();

            Assert.assertTrue(WorldSave.load(SLOT));
            Assert.assertTrue(InteriorMapSave.prepareRestoredMap());
            AdventureQuestController.instance().updateEnteredPOI(TileMapScene.instance().rootPoint);

            AdventureQuestData loaded = findQuest("SV1 Quest Safety");
            Assert.assertNotNull(loaded, "Quest must survive save/load");
            Assert.assertEquals(loaded.stages[0].getStatus(),
                    AdventureQuestController.QuestStatus.COMPLETE,
                    "Done Travel stage must not re-complete or reset on interior load");
        });
    }

    @Test(groups = "gl", timeOut = 300_000)
    public void loadInsideQuestDungeonCompletesPendingTravelStageOnce() {
        AdventureGlTestSupport.runOnGl(() -> {
            PointOfInterest dungeon = generateWorldAndPickDungeon();

            AdventureQuestStage stage = new AdventureQuestStage();
            stage.id = 2;
            stage.objective = AdventureQuestController.ObjectiveTypes.Travel;
            stage.count3 = 1;
            stage.setTargetPOI(dungeon);
            stage.checkPrerequisites(Collections.emptyList());
            Assert.assertEquals(stage.getStatus(), AdventureQuestController.QuestStatus.ACTIVE);

            AdventureQuestData quest = new AdventureQuestData();
            quest.name = "SV1 Pending Travel";
            quest.stages = new AdventureQuestStage[]{stage};
            WorldSave.getCurrentSave().getPlayer().addQuest(quest, false);

            TileMapScene.instance().load(dungeon);
            Assert.assertEquals(stage.getStatus(), AdventureQuestController.QuestStatus.ACTIVE);
            Assert.assertTrue(WorldSave.getCurrentSave().save("sv1-pending-quest", SLOT));
            MapStage.getInstance().clearIsInMap();
            InteriorMapSave.clearPending();

            Assert.assertTrue(WorldSave.load(SLOT));
            Assert.assertTrue(InteriorMapSave.prepareRestoredMap());
            AdventureQuestController.instance().updateEnteredPOI(TileMapScene.instance().rootPoint);

            AdventureQuestData loaded = findQuest("SV1 Pending Travel");
            Assert.assertNotNull(loaded);
            Assert.assertEquals(loaded.stages[0].getStatus(),
                    AdventureQuestController.QuestStatus.COMPLETE,
                    "Pending reach stage must complete on restored map-entered event");
        });
    }

    private static PointOfInterest generateWorldAndPickDungeon() {
        PointOfInterestData.clearRuntimeCacheForTests();
        forge.adventure.data.DifficultyData diff =
                Config.instance().getConfigData().difficulties[0];
        WorldSave.generateNewWorld("Sv1Hero", true, 0, 0,
                ColorSet.W, diff,
                forge.adventure.util.AdventureModes.Chaos, 0, null, WORLD_SEED);
        WorldSave save = WorldSave.getCurrentSave();
        PointOfInterest dungeon = null;
        for (PointOfInterest poi : save.getWorld().getAllPointOfInterest()) {
            if (poi == null || poi.getData() == null) {
                continue;
            }
            String type = poi.getData().type;
            if ("dungeon".equals(type) || "cave".equals(type)) {
                String map = poi.getData().map;
                if (map != null && map.contains("cave/")) {
                    dungeon = poi;
                    break;
                }
                if (dungeon == null) {
                    dungeon = poi;
                }
            }
        }
        Assert.assertNotNull(dungeon, "Generated world must include a dungeon/cave POI");
        return dungeon;
    }

    private static AdventureQuestData findQuest(String name) {
        List<AdventureQuestData> quests = WorldSave.getCurrentSave().getPlayer().getQuests();
        for (AdventureQuestData q : quests) {
            if (name.equals(q.name)) {
                return q;
            }
        }
        return null;
    }

    private static void assumeAscendantConfig() {
        if (!Config.ascendant()) {
            ConfigData cfg = new ConfigData();
            cfg.ascendantRules = true;
            cfg.sv1SaveAnywhere = true;
            cfg.sv1AutosaveBeforeFight = true;
            Config.installConfigDataForTest(cfg);
        }
    }

    private static void setCoopActiveHost() throws Exception {
        CoopSession session = CoopSession.get();
        Field role = CoopSession.class.getDeclaredField("role");
        role.setAccessible(true);
        role.set(session, CoopSessionRole.HOST);
        Field state = CoopSession.class.getDeclaredField("state");
        state.setAccessible(true);
        state.set(session, CoopSession.State.READY);
        Assert.assertTrue(session.isActive());
    }

    private static void resetCoopSession() {
        try {
            CoopSession session = CoopSession.get();
            Field role = CoopSession.class.getDeclaredField("role");
            role.setAccessible(true);
            role.set(session, CoopSessionRole.NONE);
            Field state = CoopSession.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(session, CoopSession.State.IDLE);
        } catch (Exception ignored) {
        }
    }

    private static void deleteSlot(int slot) {
        try {
            Path p = Path.of(WorldSave.getSaveFile(slot));
            Files.deleteIfExists(p);
            Files.deleteIfExists(Path.of(p.toString().replace(".sav", ".old")));
        } catch (Exception ignored) {
        }
    }
}
