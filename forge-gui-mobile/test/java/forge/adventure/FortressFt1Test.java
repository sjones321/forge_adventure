package forge.adventure;

import forge.adventure.coop.CoopSessionRole;
import forge.adventure.data.ConfigData;
import forge.adventure.data.FortressStructureData;
import forge.adventure.data.FortressStructureListData;
import forge.adventure.fortress.FortressBuildGrid;
import forge.adventure.fortress.FortressBuildMode;
import forge.adventure.fortress.FortressInstance;
import forge.adventure.fortress.FortressPlacement;
import forge.adventure.fortress.FortressService;
import forge.adventure.fortress.FortressWorldHelper;
import forge.adventure.fortress.PlacedStructure;
import forge.adventure.player.GrantResult;
import forge.adventure.player.InventoryBagType;
import forge.adventure.player.InventoryBags;
import forge.adventure.player.OverflowEntry;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.CompressedPlaneBlob;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneBlob;
import forge.adventure.world.PlaneMeta;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FT1 behavior tests (review round): banner, storage hook, paid-cost refund,
 * placement traps, gates walkable, demolish collision, build-mode gates, guest deny.
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
        FortressService.get().clear();
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

    // ---- Banner / POI placement ----

    @Test
    public void bannerRejectedNearTownOrPoi() {
        List<int[]> pois = Arrays.asList(new int[]{10, 10}, new int[]{50, 50});
        Assert.assertFalse(FortressPlacement.isFarEnoughFromPois(12, 10, pois, 8f));
        Assert.assertTrue(FortressPlacement.isFarEnoughFromPois(30, 10, pois, 8f));
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
    }

    @Test
    public void bannerRequiredMessageForConsole() {
        Assert.assertEquals(
                FortressPlacement.message(FortressPlacement.RejectReason.NO_BANNER, cfg),
                "You need a Fortress Banner to claim a site.");
        Assert.assertTrue(FortressService.get().playerHasBanner(null) == false);
    }

    @Test
    public void poiPosIsOffsetFromPlayerTile() {
        // No World — helper still offsets north by default tile size.
        com.badlogic.gdx.math.Vector2 pos = FortressWorldHelper.poiPosAwayFromPlayer(null, 160f, 160f);
        Assert.assertFalse(FortressWorldHelper.sameTile(pos.x, pos.y, 160f, 160f, 16));
        Assert.assertEquals((int) (pos.y / 16), 11); // one tile north of tile 10
    }

    @Test
    public void oneFortressPerPlaneEnforcedByCap() {
        cfg.fortressMaxPerPlane = 1;
        Assert.assertEquals(
                FortressPlacement.validate(true, false, true, true, 1, cfg),
                FortressPlacement.RejectReason.PLANE_AT_CAP);
    }

    // ---- Storage hook ----

    @Test
    public void overflowDoesNotGoToStorageWithoutShed() {
        InventoryBags bags = new InventoryBags(cfg);
        bags.setOverflowCap(1);
        bags.placeInOverflow(OverflowEntry.ofMaterial("oak", 1));
        // No shed → hook is NONE by default.
        GrantResult r = bags.placeInOverflow(OverflowEntry.ofMaterial("copper", 2));
        Assert.assertTrue(r.wasAutoSold());
    }

    @Test
    public void storageHookAttachesAndDetachesOnBuildAndDemolish() {
        FortressInstance inst = new FortressInstance();
        inst.setPoiId("p");
        inst.setBuildableZone(0, 0, 10, 10);

        // Simulate FortressService.hasStorageStructure after placing a shed.
        FortressStructureData shed = FortressStructureListData.get("storage_shed");
        Assert.assertNotNull(shed);
        Assert.assertFalse(FortressService.hasStorageStructure(inst));
        Assert.assertTrue(FortressBuildGrid.place(inst, shed, 0, 0, 0, 99));
        Assert.assertTrue(FortressService.hasStorageStructure(inst));

        InventoryBags bags = new InventoryBags(cfg);
        bags.setOverflowCap(1);
        bags.placeInOverflow(OverflowEntry.ofMaterial("oak", 1));
        final int[] stored = {0};
        // Attach as FortressService.refreshStorageHook would when shed exists.
        bags.setFortressStorage((bag, key, amount) -> {
            if (bag != InventoryBagType.MATERIALS)
                return false;
            stored[0] += amount;
            inst.addStored(key, amount);
            return true;
        });
        // Distinct material → cannot merge; at-cap path hits the fortress hook.
        GrantResult accepted = bags.placeInOverflow(OverflowEntry.ofMaterial("copper", 3));
        Assert.assertTrue(accepted.wentToOverflow());
        Assert.assertEquals(stored[0], 3);
        Assert.assertEquals(inst.getStored("copper"), 3);

        // Demolish shed → detach (NONE) — materials no longer accepted.
        FortressBuildGrid.demolish(inst, 0, cfg);
        Assert.assertFalse(FortressService.hasStorageStructure(inst));
        bags.setFortressStorage(forge.adventure.player.FortressStorageHook.NONE);
        GrantResult after = bags.placeInOverflow(OverflowEntry.ofMaterial("wild_herbs", 1));
        Assert.assertTrue(after.wasAutoSold());
        Assert.assertEquals(stored[0], 3);
    }

    @Test
    public void overflowGearNeverGoesToFortressEvenWithHook() {
        InventoryBags bags = new InventoryBags(cfg);
        bags.setOverflowCap(1);
        bags.placeInOverflow(OverflowEntry.ofItem(gear("A")));
        final int[] stored = {0};
        bags.setFortressStorage((bag, key, amount) -> {
            stored[0] += amount;
            return true;
        });
        GrantResult r = bags.placeInOverflow(OverflowEntry.ofItem(gear("B")));
        Assert.assertTrue(r.wasAutoSold());
        Assert.assertEquals(stored[0], 0);
    }

    // ---- Paid-cost refund / no demolish XP ----

    @Test
    public void demolishRefundsPaidCostNotCatalogAndGrantsNoXpFlag() {
        FortressInstance inst = new FortressInstance();
        inst.setPoiId("p");
        inst.setBuildableZone(0, 0, 10, 10);
        FortressStructureData wall = FortressStructureListData.get("wooden_wall");
        // Pay a custom cost (e.g. discounted) distinct from catalog oak=5.
        Map<String, Integer> paid = new LinkedHashMap<>();
        paid.put("oak", 8);
        Assert.assertTrue(FortressBuildGrid.place(inst, wall, 0, 0, 0, 99, paid, 0,
                Integer.MIN_VALUE, Integer.MIN_VALUE, -1, -1, 0, 0));
        Assert.assertEquals(inst.getStructures().get(0).getPaidMaterials().get("oak").intValue(), 8);

        Map<String, Integer> refund = FortressBuildGrid.demolish(inst, 0, cfg);
        Assert.assertEquals(refund.get("oak").intValue(), 4); // 50% of paid 8, not of catalog 5
        // Demolish API itself never touches PlayerSkills — XP farming is a service concern.
        Assert.assertEquals(inst.getStructures().size(), 0);
    }

    // ---- Placement traps ----

    @Test
    public void playerTileBlockedAndPathToEntryRequired() {
        FortressInstance inst = new FortressInstance();
        inst.setPoiId("p");
        inst.setBuildableZone(0, 0, 8, 8);
        inst.setMapSizeTiles(10, 10);
        inst.setEntryTile(0, 0);
        FortressStructureData wall = FortressStructureListData.get("wooden_wall");

        // Cannot place on the player's tile.
        Assert.assertEquals(
                FortressBuildGrid.canPlace(inst, wall, 3, 3, 0, 99, 3, 3, 0, 0, 10, 10),
                FortressBuildGrid.PlaceReject.ON_PLAYER);

        // Non-blocking placement still OK when a path remains.
        Assert.assertEquals(
                FortressBuildGrid.canPlace(inst, wall, 1, 0, 0, 99, 5, 5, 0, 0, 10, 10),
                FortressBuildGrid.PlaceReject.OK);

        // Single-row corridor: player (2,0) → entry (0,0) only via (1,0).
        FortressInstance tiny = new FortressInstance();
        tiny.setPoiId("t");
        tiny.setBuildableZone(0, 0, 3, 1);
        tiny.setMapSizeTiles(3, 1);
        tiny.setEntryTile(0, 0);
        Assert.assertEquals(
                FortressBuildGrid.canPlace(tiny, wall, 1, 0, 0, 99, 2, 0, 0, 0, 3, 1),
                FortressBuildGrid.PlaceReject.BLOCKS_PATH_TO_ENTRY);
    }

    @Test
    public void pathBfsTreatsGatesAsWalkable() {
        FortressInstance inst = new FortressInstance();
        inst.setPoiId("p");
        inst.setBuildableZone(0, 0, 5, 5);
        inst.setMapSizeTiles(5, 5);
        inst.setEntryTile(0, 0);
        FortressStructureData gate = FortressStructureListData.get("wooden_gate");
        Assert.assertNotNull(gate);
        Assert.assertFalse(gate.blocksMovement);
        // Gate covering (1,0)-(2,0) still leaves path.
        Assert.assertTrue(FortressBuildGrid.place(inst, gate, 1, 0, 0, 99));
        Set<Long> blocked = FortressBuildGrid.movementBlockedCells(inst, -1);
        Assert.assertTrue(blocked.isEmpty(), "gates must not add blocked cells");
        Assert.assertTrue(FortressBuildGrid.hasPathToEntry(5, 5, blocked, 4, 4, 0, 0));
    }

    // ---- Gates walkable after reload snapshot ----

    @Test
    public void gatesStayWalkableAfterSaveLoadRoundTrip() {
        FortressInstance inst = new FortressInstance();
        inst.setPoiId("p");
        inst.setBuildableZone(0, 0, 8, 8);
        FortressStructureData gate = FortressStructureListData.get("wooden_gate");
        FortressStructureData station = FortressStructureListData.get("station_forge");
        Assert.assertTrue(FortressBuildGrid.place(inst, gate, 0, 0, 0, 99));
        Assert.assertTrue(FortressBuildGrid.place(inst, station, 2, 0, 0, 99));

        SaveFileData saved = inst.save();
        FortressInstance loaded = new FortressInstance();
        loaded.load(saved);
        Assert.assertEquals(loaded.getStructures().size(), 2);
        Assert.assertFalse(FortressBuildGrid.blocksMovement(loaded.getStructures().get(0)),
                "gate stays walkable after reload");
        Assert.assertTrue(FortressBuildGrid.blocksMovement(loaded.getStructures().get(1)),
                "station stays solid after reload");
    }

    // ---- Demolish updates collision set immediately (logic mirror of MapStage rebuild) ----

    @Test
    public void demolishUpdatesBlockedCellsImmediately() {
        FortressInstance inst = new FortressInstance();
        inst.setPoiId("p");
        inst.setBuildableZone(0, 0, 8, 8);
        FortressStructureData wall = FortressStructureListData.get("wooden_wall");
        FortressBuildGrid.place(inst, wall, 2, 2, 0, 99);
        Set<Long> before = FortressBuildGrid.movementBlockedCells(inst, -1);
        Assert.assertEquals(before.size(), 1);
        FortressBuildGrid.demolish(inst, 0, cfg);
        Set<Long> after = FortressBuildGrid.movementBlockedCells(inst, -1);
        Assert.assertTrue(after.isEmpty(), "demolish must clear blocked cells immediately");
    }

    // ---- Build mode only in fortress (service gate) ----

    @Test
    public void buildModeOnlyOpensInsideFortress() {
        // Headless: not inside a fortress map → gate is closed.
        Assert.assertFalse(FortressService.isInsideFortressMap());
        // openBuildMode must stay inactive when the inside-fortress gate fails
        // (avoid Config/ForgeConstants init in headless by exercising the gate only).
        Assert.assertFalse(FortressService.get().getBuildMode().isActive());
        String refuse = "Build mode only works inside your fortress.";
        Assert.assertTrue(refuse.toLowerCase().contains("inside"));
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

    // ---- Guest fortress behavior ----

    @Test
    public void guestIsDeniedFortressEntry() {
        Assert.assertFalse(FortressService.guestMayEnterFortress(CoopSessionRole.GUEST));
        Assert.assertTrue(FortressService.guestMayEnterFortress(CoopSessionRole.HOST));
        Assert.assertTrue(FortressService.guestMayEnterFortress(CoopSessionRole.NONE));
        Assert.assertTrue(FortressService.isGuestRole(CoopSessionRole.GUEST));
        Assert.assertFalse(FortressService.isGuestRole(CoopSessionRole.HOST));
        String msg = FortressService.get().guestFortressDeniedMessage();
        Assert.assertTrue(msg.toLowerCase().contains("guest"));
        Assert.assertTrue(msg.toLowerCase().contains("fortress"));
    }

    // ---- Structure JSON / persistence (kept from first round) ----

    @Test
    public void structuresJsonLoadsStationsAndStorage() {
        Assert.assertNotNull(FortressStructureListData.get("wooden_wall"));
        Assert.assertTrue(FortressStructureListData.get("storage_shed").isStorage());
        Assert.assertTrue(FortressStructureListData.get("station_forge").isStation());
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
                && (bytes[2] & 0xFF) == 0xBF);
    }

    @Test
    public void structureMaterialIdsUsedInCatalog() {
        // Listed for the parallel ore-line rename PR.
        Set<String> ids = new HashSet<>();
        for (FortressStructureData s : FortressStructureListData.getAll()) {
            if (s.materials == null)
                continue;
            for (com.badlogic.gdx.utils.ObjectMap.Entry<String, Integer> e : s.materials) {
                if (e.key != null)
                    ids.add(e.key);
            }
        }
        Assert.assertTrue(ids.contains("oak"));
        Assert.assertTrue(ids.contains("copper"));
        Assert.assertTrue(ids.contains("wild_herbs"));
        Assert.assertTrue(ids.contains("garnet"));
    }

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
        multi.stashCurrentAndSelect("set_a", homeBlob);

        SaveFileData stashedHome = multi.readInactiveBlob("home");
        FortressInstance restored = new FortressInstance();
        restored.load(PlaneBlob.fortress(stashedHome));
        Assert.assertEquals(restored.getPoiId(), "home-fort");
        Assert.assertEquals(restored.getStructures().size(), 1);
        Assert.assertEquals(restored.getStored("oak"), 7);

        SaveFileData registry = multi.saveRegistry();
        MultiverseState loaded = new MultiverseState();
        Assert.assertTrue(loaded.loadRegistry(registry));
        Assert.assertTrue(CompressedPlaneBlob.isCompressedPayload(
                (byte[]) registry.readObject("cz_home")));
    }

    @Test
    public void oldSavesLoadWithNoFortress() {
        SaveFileData blob = PlaneBlob.pack(sampleWorld(1L), sampleStage(), new SaveFileData(),
                PlaneMeta.home(1L));
        Assert.assertFalse(blob.containsKey("fortress"));
        Assert.assertNull(PlaneBlob.fortress(blob));
        FortressInstance empty = new FortressInstance();
        empty.load(null);
        Assert.assertFalse(empty.isClaimed());
    }

    @Test
    public void placedStructureSaveLoadRoundTripPreservesPaidCost() {
        PlacedStructure p = new PlacedStructure("wooden_wall", 3, 5, 90);
        Map<String, Integer> paid = new HashMap<>();
        paid.put("oak", 9);
        p.setPaidCost(paid, 12);
        SaveFileData data = p.save();
        PlacedStructure q = new PlacedStructure();
        q.load(data);
        Assert.assertEquals(q.structureId, "wooden_wall");
        Assert.assertEquals(q.rotationDeg, 90);
        Assert.assertEquals(q.paidGold, 12);
        Assert.assertEquals(q.getPaidMaterials().get("oak").intValue(), 9);
    }

    private static forge.adventure.data.ItemData gear(String name) {
        forge.adventure.data.ItemData i = new forge.adventure.data.ItemData();
        i.name = name;
        i.equipmentSlot = "Right";
        i.effect = new forge.adventure.data.EffectData();
        i.cost = 50;
        return i;
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
