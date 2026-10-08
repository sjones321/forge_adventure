package forge.adventure;

import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneBlob;
import forge.adventure.world.PlaneKind;
import forge.adventure.world.PlaneMeta;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless MV1 coverage: legacy migrate, plane registry, switch stash, and
 * "only current plane loaded" (inactive planes stay as blobs).
 */
public class MultiverseStateTest {

    private MultiverseState multi;

    @BeforeMethod
    public void setUp() {
        multi = new MultiverseState();
    }

    @Test
    public void legacySaveMigratesToSingleHomePlane() {
        multi.migrateLegacyHome(42L, 100f, 200f);
        Assert.assertEquals(multi.getCurrentPlaneId(), PlaneMeta.HOME_ID);
        Assert.assertEquals(multi.listPlanes().size(), 1);
        PlaneMeta home = multi.getCurrentMeta();
        Assert.assertEquals(home.getKind(), PlaneKind.HOME);
        Assert.assertEquals(home.getSeed(), 42L);
        Assert.assertEquals(home.getPlayerPosX(), 100f);
        Assert.assertEquals(home.getPlayerPosY(), 200f);
        Assert.assertEquals(multi.inactivePlaneCount(), 0);
        Assert.assertTrue(multi.onlyCurrentPlaneLive());
    }

    @Test
    public void missingMultiverseBlockIsDetectedForMigration() {
        Assert.assertFalse(multi.loadRegistry(null));
        Assert.assertFalse(multi.loadRegistry(new SaveFileData()));
        Assert.assertFalse(multi.isMultiPlaneFormat());
    }

    @Test
    public void registryRoundTripPreservesInactiveBlobs() {
        multi.initHomeFromLive(1L, 10f, 20f);
        PlaneMeta set = multi.registerSetPlane("set_demo", 99L, "world/set_plane_world.json", "Demo");
        SaveFileData world = new SaveFileData();
        world.store("seed", 99L);
        world.store("width", 350);
        world.store("height", 350);
        world.store("worldConfigPath", set.getWorldConfigPath());
        SaveFileData stage = new SaveFileData();
        stage.storeObject("timeouts", new ArrayList<Float>());
        stage.storeObject("names", new ArrayList<String>());
        stage.storeObject("x", new ArrayList<Float>());
        stage.storeObject("y", new ArrayList<Float>());
        stage.storeObject("questStageIDs", new ArrayList<String>());
        stage.store("globalTimer", 0f);
        SaveFileData poi = new SaveFileData();
        SaveFileData blob = PlaneBlob.pack(world, stage, poi, set);
        multi.putInactiveBlob("set_demo", blob);

        Assert.assertEquals(multi.inactivePlaneCount(), 1);
        Assert.assertTrue(multi.isSerializedOnly("set_demo"));
        Assert.assertFalse(multi.isSerializedOnly(PlaneMeta.HOME_ID));
        Assert.assertTrue(multi.onlyCurrentPlaneLive());

        SaveFileData saved = multi.saveRegistry();
        MultiverseState loaded = new MultiverseState();
        Assert.assertTrue(loaded.loadRegistry(saved));
        Assert.assertEquals(loaded.getCurrentPlaneId(), PlaneMeta.HOME_ID);
        Assert.assertTrue(loaded.hasPlane("set_demo"));
        Assert.assertEquals(loaded.inactivePlaneCount(), 1);
        Assert.assertTrue(loaded.isSerializedOnly("set_demo"));
        SaveFileData loadedBlob = loaded.getInactiveBlob("set_demo");
        Assert.assertNotNull(loadedBlob);
        Assert.assertEquals(PlaneBlob.readMeta(loadedBlob).getSeed(), 99L);
        Assert.assertEquals(PlaneBlob.world(loadedBlob).readInt("width"), 350);
        // Inactive plane was never inflated into a live World object.
        Assert.assertTrue(loaded.onlyCurrentPlaneLive());
    }

    @Test
    public void planeSwitchStashesCurrentAndLoadsOnlyTarget() {
        multi.initHomeFromLive(7L, 1f, 2f);
        multi.registerSetPlane("set_a", 11L, Paths.WORLD, "A");
        SaveFileData homeBlob = PlaneBlob.pack(sampleWorld(7L), sampleStage(), new SaveFileData(),
                multi.getCurrentMeta());
        SaveFileData setBlob = PlaneBlob.pack(sampleWorld(11L), sampleStage(), new SaveFileData(),
                multi.getMeta("set_a"));
        multi.putInactiveBlob("set_a", setBlob);

        multi.stashCurrentAndSelect("set_a", homeBlob);
        Assert.assertEquals(multi.getCurrentPlaneId(), "set_a");
        Assert.assertTrue(multi.isSerializedOnly(PlaneMeta.HOME_ID));
        Assert.assertFalse(multi.isSerializedOnly("set_a"));
        Assert.assertNull(multi.getInactiveBlob("set_a"));
        Assert.assertNotNull(multi.getInactiveBlob(PlaneMeta.HOME_ID));
        Assert.assertEquals(multi.inactivePlaneCount(), 1);
        Assert.assertTrue(multi.onlyCurrentPlaneLive());
    }

    @Test
    public void planeMetaSaveLoadKeepsConfigPath() {
        PlaneMeta meta = new PlaneMeta("set_x", PlaneKind.SET, 5L, "world/set_plane_world.json", "X");
        meta.setPlayerPos(3f, 4f);
        PlaneMeta again = new PlaneMeta();
        again.load(meta.save());
        Assert.assertEquals(again.getId(), "set_x");
        Assert.assertEquals(again.getKind(), PlaneKind.SET);
        Assert.assertEquals(again.getWorldConfigPath(), "world/set_plane_world.json");
        Assert.assertEquals(again.getPlayerPosX(), 3f);
        Assert.assertEquals(again.getPlayerPosY(), 4f);
    }

    @Test
    public void accountVsPerPlaneSplitIsDocumentedByKeys() {
        // Account-level keys live on AdventurePlayer; per-plane keys are in PlaneBlob.
        SaveFileData blob = PlaneBlob.pack(sampleWorld(1L), sampleStage(), new SaveFileData(),
                PlaneMeta.home(1L));
        Assert.assertTrue(blob.containsKey("world"));
        Assert.assertTrue(blob.containsKey("worldStage"));
        Assert.assertTrue(blob.containsKey("pointOfInterestChanges"));
        Assert.assertTrue(blob.containsKey("meta"));
        Assert.assertFalse(blob.containsKey("player"));
        Assert.assertFalse(blob.containsKey("materials"));
        Assert.assertFalse(blob.containsKey("cards"));
    }

    private static SaveFileData sampleWorld(long seed) {
        SaveFileData world = new SaveFileData();
        world.store("seed", seed);
        world.store("width", 8);
        world.store("height", 8);
        world.store("worldConfigPath", Paths.WORLD);
        return world;
    }

    private static SaveFileData sampleStage() {
        SaveFileData stage = new SaveFileData();
        List<Float> emptyF = new ArrayList<>();
        List<String> emptyS = new ArrayList<>();
        stage.storeObject("timeouts", emptyF);
        stage.storeObject("names", emptyS);
        stage.storeObject("x", emptyF);
        stage.storeObject("y", emptyF);
        stage.storeObject("questStageIDs", emptyS);
        stage.store("globalTimer", 0f);
        return stage;
    }
}
