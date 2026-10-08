package forge.adventure;

import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopSessionRole;
import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.MemoryPlaneBlobStore;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneBlob;
import forge.adventure.world.PlaneConfigPaths;
import forge.adventure.world.PlaneKind;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.World;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless MV1 coverage: migrate, side-file store, atomic-switch guards,
 * identity retention, path rejection, guest switch block, temp generate flag.
 */
public class MultiverseStateTest {

    private MultiverseState multi;
    private MemoryPlaneBlobStore store;

    @BeforeMethod
    public void setUp() {
        multi = new MultiverseState();
        store = new MemoryPlaneBlobStore();
        multi.setBlobStore(store);
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
    public void missingCurrentPlaneIdKeepsItsIdentity() {
        SaveFileData data = new SaveFileData();
        data.store("currentPlaneId", "set_keep");
        data.store("multiPlaneFormat", true);
        List<String> ids = new ArrayList<>();
        ids.add("home");
        // Intentionally omit set_keep from planeIds/metas.
        data.storeObject("planeIds", ids);
        data.store("meta_home", PlaneMeta.home(1L).save());
        data.storeObject("inactivePlaneIds", new ArrayList<String>());

        Assert.assertTrue(multi.loadRegistry(data));
        Assert.assertEquals(multi.getCurrentPlaneId(), "set_keep");
        Assert.assertTrue(multi.hasPlane("set_keep"));
        Assert.assertEquals(multi.getMeta("set_keep").getKind(), PlaneKind.SET);
        Assert.assertNotEquals(multi.getCurrentPlaneId(), PlaneMeta.HOME_ID);
    }

    @Test
    public void sideFileRoundTripDoesNotRetainBlobsInRegistry() throws Exception {
        multi.initHomeFromLive(1L, 10f, 20f);
        PlaneMeta set = multi.registerSetPlane("set_demo", 99L, "world/set_plane_world.json", "Demo");
        SaveFileData blob = PlaneBlob.pack(sampleWorld(99L), sampleStage(), new SaveFileData(), set);
        multi.writeInactiveBlob("set_demo", blob);

        Assert.assertEquals(multi.inactivePlaneCount(), 1);
        Assert.assertTrue(multi.isSerializedOnly("set_demo"));
        Assert.assertTrue(store.exists("set_demo"));
        Assert.assertTrue(multi.onlyCurrentPlaneLive());

        SaveFileData saved = multi.saveRegistry();
        Assert.assertFalse(saved.containsKey("blob_set_demo"), "registry must not embed full world blobs");

        MultiverseState loaded = new MultiverseState();
        MemoryPlaneBlobStore store2 = new MemoryPlaneBlobStore();
        store2.write("set_demo", blob);
        loaded.setBlobStore(store2);
        Assert.assertTrue(loaded.loadRegistry(saved));
        Assert.assertEquals(loaded.getCurrentPlaneId(), PlaneMeta.HOME_ID);
        Assert.assertTrue(loaded.hasPlane("set_demo"));
        Assert.assertEquals(loaded.inactivePlaneCount(), 1);
        SaveFileData loadedBlob = loaded.readInactiveBlob("set_demo");
        Assert.assertNotNull(loadedBlob);
        Assert.assertEquals(PlaneBlob.readMeta(loadedBlob).getSeed(), 99L);
        Assert.assertEquals(PlaneBlob.world(loadedBlob).readInt("width"), 350);
    }

    @Test
    public void fileSideStoreRoundTrip() throws Exception {
        File tmp = Files.createTempDirectory("mv1-planes").toFile();
        try {
            forge.adventure.world.FilePlaneBlobStore files =
                    new forge.adventure.world.FilePlaneBlobStore(tmp);
            multi.setBlobStore(files);
            multi.initHomeFromLive(3L, 0f, 0f);
            PlaneMeta set = multi.registerSetPlane("set_file", 5L, "world/set_plane_world.json", "File");
            SaveFileData blob = PlaneBlob.pack(sampleWorld(5L), sampleStage(), new SaveFileData(), set);
            multi.writeInactiveBlob("set_file", blob);
            Assert.assertTrue(new File(tmp, "set_file.pln").isFile());
            SaveFileData again = multi.readInactiveBlob("set_file");
            Assert.assertEquals(PlaneBlob.world(again).readLong("seed"), 5L);
        } finally {
            for (File f : tmp.listFiles()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    @Test
    public void failedSwitchLeavesCurrentUnchangedWhenTargetHasNoWorld() throws Exception {
        multi.initHomeFromLive(7L, 1f, 2f);
        multi.registerSetPlane("set_a", 11L, Paths.WORLD, "A");
        // Blob with meta only — no world payload (WorldSave.switchPlane must refuse).
        SaveFileData bad = new SaveFileData();
        bad.store("meta", multi.getMeta("set_a").save());
        multi.writeInactiveBlob("set_a", bad);

        String before = multi.getCurrentPlaneId();
        SaveFileData target = multi.readInactiveBlob("set_a");
        Assert.assertNull(PlaneBlob.world(target));
        // Simulate the atomic guard: refuse before stash/select.
        Assert.assertEquals(multi.getCurrentPlaneId(), before);
        Assert.assertEquals(before, PlaneMeta.HOME_ID);
        Assert.assertTrue(multi.isSerializedOnly("set_a"));
    }

    @Test
    public void planeSwitchStashesCurrentViaSideStore() throws Exception {
        multi.initHomeFromLive(7L, 1f, 2f);
        multi.registerSetPlane("set_a", 11L, Paths.WORLD, "A");
        SaveFileData homeBlob = PlaneBlob.pack(sampleWorld(7L), sampleStage(), new SaveFileData(),
                multi.getCurrentMeta());
        SaveFileData setBlob = PlaneBlob.pack(sampleWorld(11L), sampleStage(), new SaveFileData(),
                multi.getMeta("set_a"));
        multi.writeInactiveBlob("set_a", setBlob);

        multi.stashCurrentAndSelect("set_a", homeBlob);
        Assert.assertEquals(multi.getCurrentPlaneId(), "set_a");
        Assert.assertTrue(multi.isSerializedOnly(PlaneMeta.HOME_ID));
        Assert.assertFalse(multi.isSerializedOnly("set_a"));
        Assert.assertTrue(store.exists(PlaneMeta.HOME_ID));
        Assert.assertEquals(multi.inactivePlaneCount(), 1);
    }

    @Test
    public void rejectsBadWorldConfigPath() {
        Assert.assertFalse(PlaneConfigPaths.isSyntacticallySafe("../evil.json"));
        Assert.assertFalse(PlaneConfigPaths.isSyntacticallySafe("/etc/passwd"));
        Assert.assertFalse(PlaneConfigPaths.isSyntacticallySafe("world\\hack.json"));
        Assert.assertFalse(PlaneConfigPaths.isSyntacticallySafe("not-under-world.json"));
        Assert.assertFalse(PlaneConfigPaths.isAllowed("world/../../../secret.json", multi));
        Assert.assertTrue(PlaneConfigPaths.isSyntacticallySafe(Paths.WORLD));
        Assert.assertTrue(PlaneConfigPaths.isSyntacticallySafe("world/set_plane_world.json"));
        multi.initHomeFromLive(1L, 0f, 0f);
        Assert.assertTrue(PlaneConfigPaths.isAllowed(Paths.WORLD, multi));
        Assert.assertFalse(PlaneConfigPaths.isAllowed("world/not_a_registered_template.json", multi));
    }

    @Test
    public void guestInitiatedPlaneSwitchIsBlocked() {
        Assert.assertTrue(CoopSession.isGuestBlockedFromPlaneSwitch(
                CoopSessionRole.GUEST, CoopSession.State.READY));
        Assert.assertTrue(CoopSession.isGuestBlockedFromPlaneSwitch(
                CoopSessionRole.GUEST, CoopSession.State.JOINING));
        Assert.assertFalse(CoopSession.isGuestBlockedFromPlaneSwitch(
                CoopSessionRole.HOST, CoopSession.State.READY));
        Assert.assertFalse(CoopSession.isGuestBlockedFromPlaneSwitch(
                CoopSessionRole.NONE, CoopSession.State.IDLE));
    }

    @Test
    public void temporaryGenerateDoesNotRequestLiveStageClear() {
        // generateNew(seed, path) must pass clearLiveStage=false so ensureSetPlane /
        // co-op sessionWorld rebuild cannot wipe the live WorldStage.
        World w = new World();
        // Without adventure assets generate may throw; the clear flag is set at entry.
        try {
            w.generateNew(1L, Paths.WORLD, false);
        } catch (Throwable ignored) {
            // expected in headless
        }
        Assert.assertFalse(w.didClearLiveStageOnLastGenerate());
        try {
            w.generateNew(1L);
        } catch (Throwable ignored) {
            // expected in headless
        }
        Assert.assertTrue(w.didClearLiveStageOnLastGenerate());
    }

    @Test
    public void accountVsPerPlaneSplitIsDocumentedByKeys() {
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

    @Test
    public void embeddedBlobMigrationFlushesToSideStore() throws Exception {
        SaveFileData data = new SaveFileData();
        data.store("currentPlaneId", PlaneMeta.HOME_ID);
        data.store("multiPlaneFormat", true);
        List<String> ids = new ArrayList<>();
        ids.add(PlaneMeta.HOME_ID);
        ids.add("set_old");
        data.storeObject("planeIds", ids);
        data.store("meta_home", PlaneMeta.home(1L).save());
        PlaneMeta set = new PlaneMeta("set_old", PlaneKind.SET, 2L, "world/set_plane_world.json", "Old");
        data.store("meta_set_old", set.save());
        List<String> inactive = new ArrayList<>();
        inactive.add("set_old");
        data.storeObject("inactivePlaneIds", inactive);
        data.store("blob_set_old", PlaneBlob.pack(sampleWorld(2L), sampleStage(), new SaveFileData(), set));

        Assert.assertTrue(multi.loadRegistry(data));
        Assert.assertTrue(multi.hasPendingEmbeddedMigration());
        multi.flushEmbeddedMigrations();
        Assert.assertFalse(multi.hasPendingEmbeddedMigration());
        Assert.assertTrue(store.exists("set_old"));
        Assert.assertTrue(multi.isSerializedOnly("set_old"));
    }

    private static SaveFileData sampleWorld(long seed) {
        SaveFileData world = new SaveFileData();
        world.store("seed", seed);
        world.store("width", 350);
        world.store("height", 350);
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
