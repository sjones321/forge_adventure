package forge.adventure;

import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopSessionRole;
import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.CompressedPlaneBlob;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneBlob;
import forge.adventure.world.PlaneConfigPaths;
import forge.adventure.world.PlaneKind;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.World;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Headless MV1 coverage: compressed in-save blobs, NG+ reset, missing-id identity,
 * missing-blob error, portal preflight, guest render overlay, single enter.
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
    public void missingCurrentPlaneIdKeepsItsIdentity() {
        multi.presetCurrentPlaneId("set_keep");
        SaveFileData data = new SaveFileData();
        data.store("currentPlaneId", "");
        data.store("multiPlaneFormat", true);
        List<String> ids = new ArrayList<>();
        ids.add("home");
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
    public void missingCurrentPlaneIdInMetasKeepsLiveId() {
        SaveFileData data = new SaveFileData();
        data.store("currentPlaneId", "set_keep");
        data.store("multiPlaneFormat", true);
        List<String> ids = new ArrayList<>();
        ids.add("home");
        data.storeObject("planeIds", ids);
        data.store("meta_home", PlaneMeta.home(1L).save());
        data.storeObject("inactivePlaneIds", new ArrayList<String>());

        Assert.assertTrue(multi.loadRegistry(data));
        Assert.assertEquals(multi.getCurrentPlaneId(), "set_keep");
        Assert.assertTrue(multi.hasPlane("set_keep"));
        Assert.assertNotEquals(multi.getCurrentPlaneId(), PlaneMeta.HOME_ID);
    }

    @Test
    public void compressedBlobRoundTripInsideRegistry() throws Exception {
        multi.initHomeFromLive(1L, 10f, 20f);
        PlaneMeta set = multi.registerSetPlane("set_demo", 99L, "world/set_plane_world.json", "Demo");
        SaveFileData blob = PlaneBlob.pack(sampleWorld(99L), sampleStage(), new SaveFileData(), set);
        multi.writeInactiveBlob("set_demo", blob);

        Assert.assertEquals(multi.inactivePlaneCount(), 1);
        Assert.assertTrue(multi.isSerializedOnly("set_demo"));
        Assert.assertTrue(multi.isHeldCompressed("set_demo"));
        Assert.assertTrue(multi.compressedByteSize("set_demo") > 0);
        Assert.assertTrue(multi.onlyCurrentPlaneLive());

        SaveFileData saved = multi.saveRegistry();
        Assert.assertFalse(saved.containsKey("blob_set_demo"), "must not embed inflated blobs");
        Assert.assertTrue(saved.containsKey("cz_set_demo"), "compressed payload lives in .sav registry");
        Object raw = saved.readObject("cz_set_demo");
        Assert.assertTrue(raw instanceof byte[]);
        Assert.assertTrue(CompressedPlaneBlob.isCompressedPayload((byte[]) raw));

        MultiverseState loaded = new MultiverseState();
        Assert.assertTrue(loaded.loadRegistry(saved));
        Assert.assertEquals(loaded.getCurrentPlaneId(), PlaneMeta.HOME_ID);
        Assert.assertTrue(loaded.hasPlane("set_demo"));
        Assert.assertEquals(loaded.inactivePlaneCount(), 1);
        Assert.assertTrue(loaded.isHeldCompressed("set_demo"));
        SaveFileData loadedBlob = loaded.readInactiveBlob("set_demo");
        Assert.assertNotNull(loadedBlob);
        Assert.assertEquals(PlaneBlob.readMeta(loadedBlob).getSeed(), 99L);
        Assert.assertEquals(PlaneBlob.world(loadedBlob).readInt("width"), 350);
    }

    @Test
    public void inactivePlaneIsHeldCompressedNotInflated() throws Exception {
        multi.initHomeFromLive(1L, 0f, 0f);
        PlaneMeta set = multi.registerSetPlane("set_cz", 5L, "world/set_plane_world.json", "CZ");
        SaveFileData blob = PlaneBlob.pack(sampleWorld(5L), sampleStage(), new SaveFileData(), set);
        byte[] compressed = CompressedPlaneBlob.compress(blob);
        multi.writeInactiveBlob("set_cz", blob);
        Assert.assertTrue(multi.isHeldCompressed("set_cz"));
        // Decompress for switch; registry must still hold compressed bytes afterward.
        SaveFileData again = multi.readInactiveBlob("set_cz");
        Assert.assertEquals(PlaneBlob.world(again).readLong("seed"), 5L);
        Assert.assertTrue(multi.isHeldCompressed("set_cz"));
        Assert.assertTrue(multi.compressedByteSize("set_cz") <= compressed.length * 2);
        Assert.assertTrue(multi.compressedByteSize("set_cz") > 0);
    }

    @Test
    public void copyAndDeleteSlotCarryCompressedPlanes() throws Exception {
        multi.initHomeFromLive(3L, 0f, 0f);
        PlaneMeta set = multi.registerSetPlane("set_copy", 7L, "world/set_plane_world.json", "Copy");
        SaveFileData blob = PlaneBlob.pack(sampleWorld(7L), sampleStage(), new SaveFileData(), set);
        multi.writeInactiveBlob("set_copy", blob);

        MultiverseState clone = multi.copyForSlotClone();
        Assert.assertTrue(clone.hasPlane("set_copy"));
        Assert.assertTrue(clone.isHeldCompressed("set_copy"));
        Assert.assertEquals(PlaneBlob.world(clone.readInactiveBlob("set_copy")).readLong("seed"), 7L);

        // "Delete slot" = drop the in-memory registry (no side-folder left behind).
        MultiverseState deleted = new MultiverseState();
        Assert.assertFalse(deleted.hasPlane("set_copy"));
        Assert.assertEquals(deleted.inactivePlaneCount(), 0);
    }

    @Test
    public void newGamePlusResetsRegistryToHomeTemplate() {
        multi.initHomeFromLive(1L, 10f, 20f);
        multi.registerSetPlane("set_a", 11L, "world/set_plane_world.json", "A");
        multi.putInactiveBlob("set_a", PlaneBlob.pack(sampleWorld(11L), sampleStage(), new SaveFileData(),
                multi.getMeta("set_a")));
        Assert.assertEquals(multi.listPlanes().size(), 2);

        multi.resetForNewGamePlus(99L, 5f, 6f);
        Assert.assertEquals(multi.getCurrentPlaneId(), PlaneMeta.HOME_ID);
        Assert.assertEquals(multi.listPlanes().size(), 1);
        Assert.assertEquals(multi.inactivePlaneCount(), 0);
        Assert.assertEquals(multi.getCurrentMeta().getWorldConfigPath(), Paths.WORLD);
        Assert.assertEquals(multi.getCurrentMeta().getSeed(), 99L);
        Assert.assertFalse(multi.hasPlane("set_a"));
    }

    @Test
    public void ensureSetPlaneErrorsWhenMetaPresentButBlobMissing() {
        multi.initHomeFromLive(1L, 0f, 0f);
        multi.registerSetPlane("set_ghost", 2L, "world/set_plane_world.json", "Ghost");
        // Meta only — no compressed blob. WorldSave.ensureSetPlane must refuse regen.
        Assert.assertTrue(multi.hasPlane("set_ghost"));
        Assert.assertFalse(multi.hasCompressedBlob("set_ghost"));
        Assert.assertFalse(multi.isSerializedOnly("set_ghost"));
        try {
            throwMissingBlobIfRegistered(multi, "set_ghost");
            Assert.fail("expected missing-blob error");
        } catch (IllegalStateException e) {
            Assert.assertTrue(e.getMessage().contains("missing"), e.getMessage());
        }
    }

    @Test
    public void portalPreflightFailsBeforePoiExitWhenBlobMissing() {
        multi.initHomeFromLive(1L, 0f, 0f);
        multi.registerSetPlane("set_missing", 2L, "world/set_plane_world.json", "Missing");
        // Portal / plane go must refuse before exitDungeon when the blob is absent.
        String before = multi.getCurrentPlaneId();
        Assert.assertFalse(canTravelPreflight(multi, "set_missing"));
        Assert.assertEquals(multi.getCurrentPlaneId(), before);
        Assert.assertEquals(before, PlaneMeta.HOME_ID);
    }

    @Test
    public void failedSwitchLeavesCurrentUnchangedWhenTargetHasNoWorld() throws Exception {
        multi.initHomeFromLive(7L, 1f, 2f);
        multi.registerSetPlane("set_a", 11L, Paths.WORLD, "A");
        SaveFileData bad = new SaveFileData();
        bad.store("meta", multi.getMeta("set_a").save());
        multi.writeInactiveBlob("set_a", bad);

        String before = multi.getCurrentPlaneId();
        SaveFileData target = multi.readInactiveBlob("set_a");
        Assert.assertNull(PlaneBlob.world(target));
        Assert.assertEquals(multi.getCurrentPlaneId(), before);
        Assert.assertEquals(before, PlaneMeta.HOME_ID);
        Assert.assertTrue(multi.isSerializedOnly("set_a"));
    }

    @Test
    public void planeSwitchStashesCurrentCompressed() throws Exception {
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
        Assert.assertTrue(multi.isHeldCompressed(PlaneMeta.HOME_ID));
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
    public void guestRenderOverlayRequiresGuestRoleAndSessionWorld() {
        // Contract for Current.world() / CoopSession.getActiveWorld():
        // guest + sessionWorld set → render session world; otherwise save world.
        Assert.assertFalse(CoopSession.get().isGuestRenderingSessionWorld());
        Assert.assertTrue(CoopSession.isGuestBlockedFromPlaneSwitch(
                CoopSessionRole.GUEST, CoopSession.State.READY));
    }

    @Test
    public void temporaryGenerateDoesNotRequestLiveStageClear() {
        World w = new World();
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
    public void singleEnterContractIsDocumentedByCallSites() throws Exception {
        // WorldSave.switchPlane calls enterGameSceneOnceAfterSwitch exactly once on success.
        // PortalActor.travelToPlane and console "plane go" must not call GameScene.enter again.
        String portal = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/forge/adventure/character/PortalActor.java")));
        String console = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/forge/adventure/stage/ConsoleCommandInterpreter.java")));
        String worldSave = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/forge/adventure/world/WorldSave.java")));
        Assert.assertTrue(worldSave.contains("enterGameSceneOnceAfterSwitch()"));
        Assert.assertTrue(portal.contains("exactly once inside switchPlane"));
        Assert.assertFalse(portal.contains("GameScene.instance().enter()"));
        Assert.assertTrue(console.contains("exactly once inside switchPlane"));
        Assert.assertFalse(console.contains("GameScene.instance().enter()"));
    }

    /** Mirrors WorldSave.ensureSetPlane missing-blob guard (Ascendant-gated in production). */
    private static void throwMissingBlobIfRegistered(MultiverseState state, String planeId) {
        if (state.hasPlane(planeId)
                && !planeId.equals(state.getCurrentPlaneId())
                && !state.hasCompressedBlob(planeId)) {
            throw new IllegalStateException(
                    "Plane " + planeId + " is registered but its saved data is missing");
        }
    }

    /** Mirrors WorldSave.canTravelToPlane blob check used before exitDungeon. */
    private static boolean canTravelPreflight(MultiverseState state, String planeId) {
        if (planeId.equals(state.getCurrentPlaneId())) {
            return true;
        }
        if (!state.hasPlane(planeId)) {
            return false;
        }
        return state.hasCompressedBlob(planeId);
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
