package forge.adventure.world;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.player.StandardWindow;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.Config;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * MV2: place Planar Gate POIs on the home plane (to Standard / unlocked sets)
 * and ensure set planes have a return gate to home.
 */
public final class PlanarPortalPlacer {
    private PlanarPortalPlacer() {
    }

    /**
     * On the live home world, ensure one Planar Gate exists per set in the
     * Standard window (and optionally drifted sets). Idempotent.
     */
    public static int ensureHomePortals(World world, StandardWindow window, long seed) {
        if (!Config.ascendant() || world == null || window == null || !window.isActive()) {
            return 0;
        }
        SetPlaneGenerator.ensurePlanarGateData(PlaneMeta.HOME_ID, "Planar Gate");
        PointOfInterestData template = PointOfInterestData.getPointOfInterest(SetPlaneGenerator.PLANAR_GATE_POI);
        if (template == null) {
            return 0;
        }
        Set<String> existingTargets = existingPortalTargets(world);
        int placed = 0;
        Random rng = new Random(seed ^ 0xC0FFEEL);
        List<String> sets = window.getSets();
        int index = 0;
        for (String code : sets) {
            if (code == null || code.isEmpty()) {
                continue;
            }
            String planeId = SetPlaneGenerator.planeIdForSet(code);
            if (existingTargets.contains(planeId)) {
                index++;
                continue;
            }
            PointOfInterestData gate = copyGate(template, planeId,
                    "Portal to " + SetPlaneGenerator.displayNameForSet(code));
            Vector2 pos = pickSpot(world, rng, index);
            if (pos == null) {
                continue;
            }
            PointOfInterest poi = new PointOfInterest(gate, pos, rng);
            poi.setDisplayName(gate.displayName);
            poi.setTargetPlane(planeId);
            world.addPointOfInterest(poi);
            existingTargets.add(planeId);
            placed++;
            index++;
        }
        return placed;
    }

    /**
     * On a generated set plane, ensure a return portal to home exists.
     */
    public static int ensureReturnPortal(World world, String setCode, long seed) {
        if (!Config.ascendant() || world == null) {
            return 0;
        }
        SetPlaneGenerator.ensurePlanarGateData(PlaneMeta.HOME_ID, "Portal to Home");
        PointOfInterestData template = PointOfInterestData.getPointOfInterest(SetPlaneGenerator.PLANAR_GATE_POI);
        if (template == null) {
            return 0;
        }
        Set<String> existing = existingPortalTargets(world);
        if (existing.contains(PlaneMeta.HOME_ID)) {
            return 0;
        }
        PointOfInterestData gate = copyGate(template, PlaneMeta.HOME_ID, "Portal to Home");
        Random rng = new Random(seed ^ (setCode != null ? setCode.hashCode() : 0));
        Vector2 pos = pickSpot(world, rng, 0);
        if (pos == null) {
            return 0;
        }
        PointOfInterest poi = new PointOfInterest(gate, pos, rng);
        poi.setDisplayName("Portal to Home");
        poi.setTargetPlane(PlaneMeta.HOME_ID);
        world.addPointOfInterest(poi);
        return 1;
    }

    /** After mastery unlocks a set, place its home-plane portal if the live world is home. */
    public static void onSetUnlocked(WorldSave save, String setCode) {
        if (!Config.ascendant() || save == null || setCode == null || setCode.isEmpty()) {
            return;
        }
        try {
            save.ensureSetPlaneForSet(setCode);
        } catch (Exception ignored) {
            // Generation may fail in headless; portal placement still attempted below.
        }
        if (PlaneMeta.HOME_ID.equals(save.getCurrentPlaneId())) {
            ensureHomePortals(save.getWorld(), save.getPlayer().getStandardWindow(),
                    save.getWorld() != null ? save.getWorld().getSeed() : 0L);
        }
    }

    private static PointOfInterestData copyGate(PointOfInterestData template, String targetPlane, String display) {
        PointOfInterestData gate = new PointOfInterestData(template);
        gate.name = SetPlaneGenerator.PLANAR_GATE_POI;
        gate.targetPlane = targetPlane;
        gate.displayName = display;
        gate.count = 1;
        return gate;
    }

    private static Set<String> existingPortalTargets(World world) {
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
            if (d != null && d.targetPlane != null && !d.targetPlane.isEmpty()) {
                out.add(d.targetPlane);
            }
        }
        return out;
    }

    private static Vector2 pickSpot(World world, Random rng, int index) {
        if (world.getData() == null) {
            return null;
        }
        int tile = world.getTileSize();
        int w = world.getData().width;
        int h = world.getData().height;
        if (w <= 0 || h <= 0 || tile <= 0) {
            return null;
        }
        // Ring around the player start so gates are discoverable without overlapping towns.
        float cx = (float) (world.getData().playerStartPosX * w * tile);
        float cy = (float) (world.getData().playerStartPosY * h * tile);
        float radius = tile * (18 + index * 10);
        double angle = (index * 2.399963f) + rng.nextDouble() * 0.4; // golden-angle-ish
        float x = cx + (float) (Math.cos(angle) * radius);
        float y = cy + (float) (Math.sin(angle) * radius);
        x = Math.max(tile * 2, Math.min((w - 2) * tile, x));
        y = Math.max(tile * 2, Math.min((h - 2) * tile, y));
        return new Vector2(x, y);
    }
}
