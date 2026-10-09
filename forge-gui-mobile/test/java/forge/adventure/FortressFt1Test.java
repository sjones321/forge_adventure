package forge.adventure;

import forge.adventure.data.ConfigData;
import forge.adventure.data.FortressStructureData;
import forge.adventure.data.FortressStructureListData;
import forge.adventure.fortress.FortressBuildGrid;
import forge.adventure.fortress.FortressBuildMode;
import forge.adventure.fortress.FortressInstance;
import forge.adventure.fortress.FortressPlacement;
import forge.adventure.fortress.PlacedStructure;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.CompressedPlaneBlob;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneBlob;
import forge.adventure.world.PlaneMeta;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * FT1 behavior tests: banner validity, one fortress per plane, per-plane persistence
 * across plane switch + compressed-blob round trip, footprint/rotate/demolish refund,
 * structure JSON load, old saves with no fortress.
 */
public class FortressFt1Test {

    private ConfigData cfg;

    @BeforeMethod
    public void setUp() throws Exception {
        cfg = new ConfigData();
        cfg.ascendantRules = true;
        cfg.fortressBannerMinDistanceTiles = 8f;
        cfg.fortressMaxPerPlane = 1;
        cfg.fortressDemolishRefundPercent = 50f;
        cfg.fortressBuildableOriginX = 0;
        cfg.fortressBuildableOriginY = 0;
        cfg.fortressBuildableWidth = 10;
        cfg.fortressBuildableHeight = 10;

        Path structures = resolveRes("forge-gui/res/adventure/common/world/structures_fortress.json");
        FortressStructureListData.loadFromPath(structures);
        Assert.assertTrue(FortressStructureListData.size() > 0, "structures_fortress.json must load");
    }

    private static Path resolveRes(String relative) {
        Path p = Paths.get(relative);
        if (Files.isRegularFile(p))
            return p.toAbsolutePath().normalize();
        Path alt = Paths.get("..").resolve(relative);
        if (Files.isRegularFile(alt))
            return alt.toAbsolutePath().normalize();
        return p.toAbsolutePath().normalize();
    }

    // ---- Banner placement validity ----

    @Test
    public void bannerRejectedNearTownOrPoi() {
        List<int[]> pois = Arrays.asList(new int[]{10, 10}, new int[]{50, 50});
        Assert.assertFalse(FortressPlacement.isFarEnoughFromPois(12, 10, pois, 8f),
                "within 8 tiles of a town must reject");
        Assert.assertTrue(FortressPlacement.isFarEnoughFromPois(30, 10, pois, 8f),
                "far from all POIs must accept");
    }

    @Test
    public void bannerRejectedWhenNotWalkableOrGuestOrAtCap() {
        Assert.assertEquals(
                FortressPlacement.validate(true, false, false, true, 0, cfg),
                FortressPlacement.RejectReason.NOT_WALKABLE);
        Assert.assertEquals(
                FortressPlacement.validate(true, true, true, true, 0, cfg),
                FortressPlacement.RejectReason.GUEST_FORBIDDEN);
        Assert.assertEquals(
                FortressPlacement.validate(true, false, true, true, 1, cfg),
                FortressPlacement.RejectReason.PLANE_AT_CAP);
        Assert.assertEquals(
                FortressPlacement.validate(true, false, true, true, 0, cfg),
                FortressPlacement.RejectReason.OK);
        Assert.assertEquals(
                FortressPlacement.validate(false, false, true, true, 0, cfg),
                FortressPlacement.RejectReason.NOT_ASCENDANT);
    }

    @Test
    public void oneFortressPerPlaneEnforcedByCap() {
        cfg.fortressMaxPerPlane = 1;
        Assert.assertEquals(
                FortressPlacement.validate(true, false, true, true, 0, cfg),
                FortressPlacement.RejectReason.OK);
        Assert.assertEquals(
                FortressPlacement.validate(true, false, true, true, 1, cfg),
                FortressPlacement.RejectReason.PLANE_AT_CAP);
        cfg.fortressMaxPerPlane = 2;
        Assert.assertEquals(
                FortressPlacement.validate(true, false, true, true, 1, cfg),
                FortressPlacement.RejectReason.OK);
    }

    // ---- Structure JSON ----

    @Test
    public void structuresJsonLoadsStationsAndStorage() {
        Assert.assertNotNull(FortressStructureListData.get("wooden_wall"));
        Assert.assertNotNull(FortressStructureListData.get("storage_shed"));
        Assert.assertTrue(FortressStructureListData.get("storage_shed").isStorage());
        Assert.assertTrue(FortressStructureListData.get("station_forge").isStation());
        Assert.assertEquals(FortressStructureListData.get("station_forge").stationKey(), "forge");
        Assert.assertEquals(FortressStructureListData.get("wooden_gate").rotatedW(90), 1);
        Assert.assertEquals(FortressStructureListData.get("wooden_gate").rotatedH(90), 2);
    }

    @Test
    public void structuresJsonIsUtf8WithoutBom() throws Exception {
        Path path = resolveRes("forge-gui/res/adventure/common/world/structures_fortress.json");
        byte[] bytes = Files.readAllBytes(path);
        Assert.assertFalse(bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF, "JSON must not have UTF-8 BOM");
        String text = new String(bytes, StandardCharsets.UTF_8);
        Assert.assertTrue(text.trim().startsWith("["));
    }

    // ---- Footprint / rotate / demolish ----

    @Test
    public void footprintCollisionRotationAndDemolishRefund() {
        FortressInstance inst = new FortressInstance();
        inst.setPlaneId("home");
        inst.setPoiId("poi-1");
        inst.setBuildableZone(0, 0, 10, 10);

        FortressStructureData wall = FortressStructureListData.get("wooden_wall");
        FortressStructureData gate = FortressStructureListData.get("wooden_gate");
        Assert.assertNotNull(wall);
        Assert.assertNotNull(gate);

        Assert.assertEquals(FortressBuildGrid.canPlace(inst, wall, 0, 0, 0, 99),
                FortressBuildGrid.PlaceReject.OK);
        Assert.assertTrue(FortressBuildGrid.place(inst, wall, 0, 0, 0, 99));
        Assert.assertEquals(FortressBuildGrid.canPlace(inst, wall, 0, 0, 0, 99),
                FortressBuildGrid.PlaceReject.COLLISION);

        // Gate is 2x1; rotate 90 → 1x2; place next to wall.
        Assert.assertTrue(FortressBuildGrid.place(inst, gate, 1, 0, 90, 99));
        Assert.assertEquals(inst.getStructures().size(), 2);

        // Out of bounds.
        Assert.assertEquals(FortressBuildGrid.canPlace(inst, wall, 9, 9, 0, 99),
                FortressBuildGrid.PlaceReject.OK);
        Assert.assertEquals(FortressBuildGrid.canPlace(inst, gate, 9, 0, 0, 99),
                FortressBuildGrid.PlaceReject.OUT_OF_BOUNDS);

        // Level gate.
        Assert.assertEquals(FortressBuildGrid.canPlace(inst, gate, 3, 3, 0, 1),
                FortressBuildGrid.PlaceReject.LEVEL_TOO_LOW);

        int idx = FortressBuildGrid.structureAt(inst, 0, 0);
        Assert.assertEquals(idx, 0);
        Map<String, Integer> refund = FortressBuildGrid.demolish(inst, idx, cfg);
        Assert.assertEquals(inst.getStructures().size(), 1);
        Assert.assertEquals(refund.get("oak").intValue(), 2); // 50% of 5 oak, floored
    }

    @Test
    public void buildModeKeyboardActionsMoveRotateAndCycle() {
        FortressInstance inst = new FortressInstance();
        inst.setPoiId("p");
        inst.setBuildableZone(0, 0, 8, 8);
        FortressBuildMode mode = new FortressBuildMode();
        mode.open(inst, 50);
        Assert.assertTrue(mode.isActive());
        Assert.assertEquals(mode.handleKey(com.badlogic.gdx.Input.Keys.RIGHT), "moved");
        Assert.assertEquals(mode.getCursorX(), 1);
        Assert.assertEquals(mode.handleKey(com.badlogic.gdx.Input.Keys.R), "rotate");
        Assert.assertEquals(mode.getRotationDeg(), 90);
        Assert.assertEquals(mode.handleKey(com.badlogic.gdx.Input.Keys.ESCAPE), "exit");
    }

    // ---- Per-plane persistence ----

    @Test
    public void fortressPersistsAcrossPlaneSwitchAndCompressedRoundTrip() throws Exception {
        FortressInstance homeFort = new FortressInstance();
        homeFort.setPlaneId("home");
        homeFort.setPoiId("home-fort");
        homeFort.setWorldPos(100f, 200f);
        homeFort.setBuildableZone(4, 4, 16, 12);
        FortressStructureData wall = FortressStructureListData.get("wooden_wall");
        FortressBuildGrid.place(homeFort, wall, 4, 4, 0, 99);
        homeFort.addStored("oak", 7);

        MultiverseState multi = new MultiverseState();
        multi.initHomeFromLive(1L, 10f, 20f);
        PlaneMeta set = multi.registerSetPlane("set_a", 11L, "world/set_plane_world.json", "A");

        SaveFileData homeBlob = PlaneBlob.pack(sampleWorld(1L), sampleStage(), new SaveFileData(),
                multi.getCurrentMeta(), homeFort.save());
        SaveFileData setBlob = PlaneBlob.pack(sampleWorld(11L), sampleStage(), new SaveFileData(), set, null);
        multi.writeInactiveBlob("set_a", setBlob);

        // Simulate switch: stash home (with fortress), select set (no fortress).
        multi.stashCurrentAndSelect("set_a", homeBlob);
        Assert.assertEquals(multi.getCurrentPlaneId(), "set_a");
        Assert.assertTrue(multi.isHeldCompressed("home"));

        SaveFileData stashedHome = multi.readInactiveBlob("home");
        Assert.assertNotNull(PlaneBlob.fortress(stashedHome));
        FortressInstance restored = new FortressInstance();
        restored.load(PlaneBlob.fortress(stashedHome));
        Assert.assertEquals(restored.getPoiId(), "home-fort");
        Assert.assertEquals(restored.getStructures().size(), 1);
        Assert.assertEquals(restored.getStored("oak"), 7);

        // Registry save/load round trip keeps fortress inside compressed blob.
        SaveFileData registry = multi.saveRegistry();
        MultiverseState loaded = new MultiverseState();
        Assert.assertTrue(loaded.loadRegistry(registry));
        SaveFileData again = loaded.readInactiveBlob("home");
        Assert.assertNotNull(PlaneBlob.fortress(again));
        FortressInstance roundTrip = new FortressInstance();
        roundTrip.load(PlaneBlob.fortress(again));
        Assert.assertEquals(roundTrip.getStructures().get(0).structureId, "wooden_wall");
        Assert.assertTrue(CompressedPlaneBlob.isCompressedPayload(
                (byte[]) registry.readObject("cz_home")));
    }

    @Test
    public void oldSavesLoadWithNoFortress() {
        // PlaneBlob without fortress key.
        SaveFileData blob = PlaneBlob.pack(sampleWorld(1L), sampleStage(), new SaveFileData(),
                PlaneMeta.home(1L));
        Assert.assertFalse(blob.containsKey("fortress"));
        Assert.assertNull(PlaneBlob.fortress(blob));

        FortressInstance empty = new FortressInstance();
        empty.load(null);
        Assert.assertFalse(empty.isClaimed());

        FortressInstance fromMissing = new FortressInstance();
        fromMissing.load(new SaveFileData());
        Assert.assertFalse(fromMissing.isClaimed());
    }

    @Test
    public void placedStructureSaveLoadRoundTrip() {
        PlacedStructure p = new PlacedStructure("wooden_wall", 3, 5, 90);
        SaveFileData data = p.save();
        PlacedStructure q = new PlacedStructure();
        q.load(data);
        Assert.assertEquals(q.structureId, "wooden_wall");
        Assert.assertEquals(q.gridX, 3);
        Assert.assertEquals(q.gridY, 5);
        Assert.assertEquals(q.rotationDeg, 90);
    }

    private static SaveFileData sampleWorld(long seed) {
        SaveFileData w = new SaveFileData();
        w.store("seed", seed);
        w.store("width", 350);
        w.store("height", 350);
        return w;
    }

    private static SaveFileData sampleStage() {
        SaveFileData s = new SaveFileData();
        s.storeObject("timeouts", new ArrayList<Float>());
        s.storeObject("names", new ArrayList<String>());
        s.storeObject("x", new ArrayList<Float>());
        s.storeObject("y", new ArrayList<Float>());
        s.storeObject("questStageIDs", new ArrayList<String>());
        s.store("globalTimer", 0f);
        return s;
    }
}
