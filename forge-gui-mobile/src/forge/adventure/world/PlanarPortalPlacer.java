package forge.adventure.world;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.player.StandardWindow;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * MV2: place Planar Gate POIs on the home plane (to Standard / unlocked sets)
 * and ensure set planes have a return gate to home. Collision-free, clears
 * terrain, and can apply pending home gates after mastery on another plane.
 */
public final class PlanarPortalPlacer {
    private static final float GATE_EXCLUSION_TILES = 6f;

    private PlanarPortalPlacer() {
    }

    /**
     * On the live home world, ensure one Planar Gate exists per set in the
     * Standard window. Also places any {@link MultiverseState} pending gates.
     * Idempotent.
     */
    public static int ensureHomePortals(World world, StandardWindow window, long seed) {
        return ensureHomePortals(world, window, seed, null);
    }

    public static int ensureHomePortals(World world, StandardWindow window, long seed, MultiverseState multi) {
        if (world == null || !ascendantGatesEnabled()) {
            return 0;
        }
        SetPlaneGenerator.ensurePlanarGateRegistered();
        PointOfInterestData template = PointOfInterestData.getPointOfInterest(SetPlaneGenerator.PLANAR_GATE_POI);
        if (template == null) {
            return 0;
        }
        Set<String> existingTargets = existingPortalTargets(world);
        List<String> wanted = new ArrayList<>();
        if (window != null && window.isActive()) {
            for (String code : window.getSets()) {
                if (code == null || code.isEmpty()) {
                    continue;
                }
                // CORE is not a set plane.
                if (StandardWindow.CORE_COLLECTION.equalsIgnoreCase(code)) {
                    continue;
                }
                if (!SetPlaneRules.isKnownEdition(code)) {
                    continue;
                }
                wanted.add(SetPlaneGenerator.planeIdForSet(code));
            }
        }
        if (multi != null) {
            for (String pending : multi.getPendingHomeGatePlaneIds()) {
                if (pending != null && !pending.isEmpty() && !wanted.contains(pending)) {
                    wanted.add(pending);
                }
            }
        }
        int placed = 0;
        Random rng = new Random(seed ^ 0xC0FFEEL);
        int attemptIndex = 0;
        for (String planeId : wanted) {
            if (existingTargets.contains(planeId)) {
                if (multi != null) {
                    multi.clearPendingHomeGate(planeId);
                }
                continue;
            }
            String display = "Planar Gate";
            String code = SetPlaneGenerator.setCodeFromPlaneId(planeId);
            if (!code.isEmpty()) {
                display = "Portal to " + SetPlaneGenerator.displayNameForSet(code);
            }
            PointOfInterestData gate = copyGate(template, planeId, display);
            Vector2 pos = pickCollisionFreeSpot(world, rng, attemptIndex);
            attemptIndex++;
            if (pos == null) {
                continue;
            }
            PointOfInterest poi = new PointOfInterest(gate, pos, rng);
            poi.setDisplayName(gate.displayName);
            poi.setTargetPlane(planeId);
            world.addPointOfInterest(poi);
            world.clearTerrainAroundWorld(pos.x, pos.y, 3);
            existingTargets.add(planeId);
            if (multi != null) {
                multi.clearPendingHomeGate(planeId);
            }
            placed++;
        }
        if (placed > 0) {
            notifyCoopHashRefresh();
        }
        return placed;
    }

    /** On a generated set plane, ensure a return portal to home exists. */
    public static int ensureReturnPortal(World world, String setCode, long seed) {
        if (world == null || !ascendantGatesEnabled()) {
            return 0;
        }
        SetPlaneGenerator.ensurePlanarGateRegistered();
        PointOfInterestData template = PointOfInterestData.getPointOfInterest(SetPlaneGenerator.PLANAR_GATE_POI);
        if (template == null) {
            return 0;
        }
        // Biome injection may already have placed a PlanarGate POI while road/sprite
        // setup failed to clear terrain — always clear at the existing home gate so
        // the live hash includes the walkable pad guests will replay.
        if (existingPortalTargets(world).contains(PlaneMeta.HOME_ID)) {
            clearTerrainForTarget(world, PlaneMeta.HOME_ID);
            notifyCoopHashRefresh();
            return 0;
        }
        PointOfInterestData gate = copyGate(template, PlaneMeta.HOME_ID, "Portal to Home");
        Random rng = new Random(seed ^ (setCode != null ? setCode.hashCode() : 0));
        Vector2 pos = pickCollisionFreeSpot(world, rng, 0);
        if (pos == null) {
            return 0;
        }
        PointOfInterest poi = new PointOfInterest(gate, pos, rng);
        poi.setDisplayName("Portal to Home");
        poi.setTargetPlane(PlaneMeta.HOME_ID);
        world.addPointOfInterest(poi);
        world.clearTerrainAroundWorld(pos.x, pos.y, 3);
        notifyCoopHashRefresh();
        return 1;
    }

    /**
     * Place a Planar Gate at an exact world position (co-op guest replay / tests).
     * {@code setCode} empty → target home. Always clears terrain at the host spot
     * (even when the destination POI already exists from biome injection).
     *
     * @return true when a new gate was placed
     */
    public static boolean placeGateAt(World world, String setCode, float x, float y) {
        if (world == null || !ascendantGatesEnabled()) {
            return false;
        }
        SetPlaneGenerator.ensurePlanarGateRegistered();
        PointOfInterestData template = PointOfInterestData.getPointOfInterest(SetPlaneGenerator.PLANAR_GATE_POI);
        if (template == null) {
            return false;
        }
        final String planeId = (setCode == null || setCode.isEmpty())
                ? PlaneMeta.HOME_ID
                : SetPlaneGenerator.planeIdForSet(setCode);
        // Host coordinates are authoritative for the walkable pad even if gen already
        // registered a same-target POI (sprite/road clear may have been skipped).
        world.clearTerrainAroundWorld(x, y, 3);
        if (existingPortalTargets(world).contains(planeId)) {
            return false;
        }
        String display = "Planar Gate";
        if (PlaneMeta.HOME_ID.equals(planeId)) {
            display = "Portal to Home";
        } else if (setCode != null && !setCode.isEmpty()) {
            display = "Portal to " + SetPlaneGenerator.displayNameForSet(setCode);
        }
        PointOfInterestData gate = copyGate(template, planeId, display);
        // Deterministic sprite pick from position so guest POI geometry matches host.
        Random rng = new Random((((long) Float.floatToIntBits(x)) << 32)
                ^ Float.floatToIntBits(y) ^ planeId.hashCode());
        PointOfInterest poi = new PointOfInterest(gate, new Vector2(x, y), rng);
        poi.setDisplayName(display);
        poi.setTargetPlane(planeId);
        world.addPointOfInterest(poi);
        return true;
    }

    /** Clear terrain at every planar gate aimed at {@code targetPlaneId}. */
    private static void clearTerrainForTarget(World world, String targetPlaneId) {
        if (world == null || targetPlaneId == null) {
            return;
        }
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi == null || poi.getTargetPlane() == null) {
                continue;
            }
            if (!targetPlaneId.equalsIgnoreCase(poi.getTargetPlane())) {
                continue;
            }
            Vector2 pos = poi.getPosition() != null ? poi.getPosition() : poi.getCenter();
            if (pos != null) {
                world.clearTerrainAroundWorld(pos.x, pos.y, 3);
            }
        }
    }

    /** After host gate placement: refresh co-op live-world hash if hosting. */
    private static void notifyCoopHashRefresh() {
        try {
            forge.adventure.coop.CoopSession.get().refreshHostLiveWorldHash();
        } catch (Throwable ignored) {
            // Co-op optional / headless
        }
    }

    /**
     * After mastery unlocks a set: register the plane (deferred gen) and ensure
     * a home gate exists — immediately if on home, otherwise pending until home
     * is loaded / switched to.
     */
    public static void onSetUnlocked(WorldSave save, String setCode) {
        if (save == null || setCode == null || setCode.isEmpty() || !ascendantGatesEnabled()) {
            return;
        }
        if (StandardWindow.CORE_COLLECTION.equalsIgnoreCase(setCode)
                || !SetPlaneRules.isKnownEdition(setCode)) {
            return;
        }
        String planeId = SetPlaneGenerator.planeIdForSet(setCode);
        try {
            // Deferred: register meta only; materialize on first travel.
            save.registerSetPlanePending(setCode);
        } catch (Exception ignored) {
            // Registration may fail at plane cap; still try to place a gate.
        }
        MultiverseState multi = save.getMultiverse();
        if (PlaneMeta.HOME_ID.equals(save.getCurrentPlaneId())) {
            ensureHomePortals(save.getWorld(), save.getPlayer().getStandardWindow(),
                    save.getWorld() != null ? save.getWorld().getSeed() : 0L, multi);
        } else {
            multi.rememberPendingHomeGate(planeId);
            // Also try to stamp the gate into the compressed home blob when present.
            tryStampGateIntoInactiveHome(save, planeId, setCode);
        }
    }

    /**
     * Call after load / switch to home: place any missing Standard-window gates
     * and pending mastery gates.
     */
    public static int ensureMissingGatesOnLoad(WorldSave save) {
        if (save == null || save.getWorld() == null || !ascendantGatesEnabled()) {
            return 0;
        }
        SetPlaneGenerator.ensurePlanarGateRegistered();
        if (!PlaneMeta.HOME_ID.equals(save.getCurrentPlaneId())) {
            // On a set plane, ensure return portal exists.
            PlaneMeta meta = save.getMultiverse().getCurrentMeta();
            String code = meta != null ? meta.getSetCode() : "";
            return ensureReturnPortal(save.getWorld(), code, save.getWorld().getSeed());
        }
        StandardWindow window = save.getPlayer() != null ? save.getPlayer().getStandardWindow() : null;
        return ensureHomePortals(save.getWorld(), window, save.getWorld().getSeed(), save.getMultiverse());
    }

    /** Test helper: collision / town exclusion for a candidate world position. */
    public static boolean isSpotBlocked(World world, float x, float y) {
        if (world == null) {
            return true;
        }
        int tile = world.getTileSize();
        if (tile <= 0) {
            return true;
        }
        int tx = (int) (x / tile);
        int ty = (int) (y / tile);
        if (world.isColliding(tx, ty)) {
            return true;
        }
        float half = tile * GATE_EXCLUSION_TILES;
        Rectangle box = new Rectangle(x - half, y - half, half * 2f, half * 2f);
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi == null || poi.getBoundingRectangle() == null) {
                continue;
            }
            if (box.overlaps(poi.getBoundingRectangle())) {
                return true;
            }
            // Extra: stay clear of towns/capitals by type.
            PointOfInterestData d = poi.getData();
            if (d != null && d.type != null
                    && ("town".equals(d.type) || "capital".equals(d.type) || "planar_gate".equals(d.type))) {
                if (poi.getCenter().dst(x, y) < tile * (GATE_EXCLUSION_TILES + 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Testable: pick a walkable, non-town spot for a planar gate. */
    public static Vector2 pickCollisionFreeSpot(World world, Random rng, int index) {
        if (world.getData() == null) {
            return null;
        }
        int tile = world.getTileSize();
        int w = world.getData().width;
        int h = world.getData().height;
        if (w <= 0 || h <= 0 || tile <= 0) {
            return null;
        }
        float cx = (float) (world.getData().playerStartPosX * w * tile);
        float cy = (float) (world.getData().playerStartPosY * h * tile);
        // Vary radius and angle by index; retry with jitter so ring slots don't collide.
        for (int attempt = 0; attempt < 48; attempt++) {
            int slot = index * 7 + attempt;
            float radius = tile * (20 + (slot % 12) * 8 + rng.nextInt(6));
            double angle = (slot * 2.399963229728653) + rng.nextDouble() * 0.35;
            float x = cx + (float) (Math.cos(angle) * radius);
            float y = cy + (float) (Math.sin(angle) * radius);
            x = Math.max(tile * 3, Math.min((w - 3) * (float) tile, x));
            y = Math.max(tile * 3, Math.min((h - 3) * (float) tile, y));
            if (!isSpotBlocked(world, x, y)) {
                return new Vector2(x, y);
            }
        }
        return null;
    }

    private static void tryStampGateIntoInactiveHome(WorldSave save, String planeId, String setCode) {
        MultiverseState multi = save.getMultiverse();
        if (!multi.hasCompressedBlob(PlaneMeta.HOME_ID)) {
            return;
        }
        try {
            forge.adventure.util.SaveFileData blob = multi.readInactiveBlob(PlaneMeta.HOME_ID);
            if (blob == null) {
                return;
            }
            World home = new World();
            try {
                forge.adventure.util.SaveFileData worldData = PlaneBlob.world(blob);
                if (worldData == null) {
                    return;
                }
                home.load(worldData);
                StandardWindow window = save.getPlayer().getStandardWindow();
                int placed = ensureHomePortals(home, window, home.getSeed(), multi);
                if (placed <= 0 && existingPortalTargets(home).contains(planeId)) {
                    multi.clearPendingHomeGate(planeId);
                    return;
                }
                if (placed > 0 || existingPortalTargets(home).contains(planeId)) {
                    PlaneMeta homeMeta = PlaneBlob.readMeta(blob);
                    if (homeMeta == null) {
                        homeMeta = multi.getMeta(PlaneMeta.HOME_ID);
                    }
                    forge.adventure.util.SaveFileData stage = PlaneBlob.worldStage(blob);
                    forge.adventure.util.SaveFileData poi = PlaneBlob.poiChanges(blob);
                    forge.adventure.util.SaveFileData packed = PlaneBlob.pack(
                            home.save(),
                            stage != null ? stage : new forge.adventure.util.SaveFileData(),
                            poi != null ? poi : new forge.adventure.util.SaveFileData(),
                            homeMeta);
                    multi.writeInactiveBlob(PlaneMeta.HOME_ID, packed);
                    multi.clearPendingHomeGate(planeId);
                }
            } finally {
                forge.Forge.safeDispose(home);
            }
        } catch (Exception e) {
            // Pending gate remains for ensureMissingGatesOnLoad.
            multi.rememberPendingHomeGate(planeId);
        }
    }

    /** Ascendant gates; true in headless when Config is unavailable so co-op hash tests can run. */
    private static boolean ascendantGatesEnabled() {
        try {
            return Config.ascendant();
        } catch (Throwable t) {
            return true;
        }
    }

    private static PointOfInterestData copyGate(PointOfInterestData template, String targetPlane, String display) {
        PointOfInterestData gate = new PointOfInterestData(template);
        gate.name = SetPlaneGenerator.PLANAR_GATE_POI;
        gate.targetPlane = targetPlane;
        gate.displayName = display;
        gate.count = 1;
        gate.type = "planar_gate";
        return gate;
    }

    public static Set<String> existingPortalTargets(World world) {
        Set<String> out = new HashSet<>();
        if (world == null) {
            return out;
        }
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi == null) {
                continue;
            }
            String t = poi.getTargetPlane();
            if (t != null && !t.isEmpty()) {
                out.add(t);
            }
            PointOfInterestData d = poi.getData();
            if (d != null && SetPlaneGenerator.PLANAR_GATE_POI.equals(d.name)
                    && d.targetPlane != null && !d.targetPlane.isEmpty()) {
                out.add(d.targetPlane);
            }
        }
        return out;
    }
}
