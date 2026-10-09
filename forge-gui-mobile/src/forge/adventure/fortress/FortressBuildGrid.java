package forge.adventure.fortress;

import forge.adventure.data.ConfigData;
import forge.adventure.data.FortressStructureData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grid placement / collision / demolish-refund for FT1 fortress build mode.
 * Pure logic — no scene or LibGDX dependency.
 */
public final class FortressBuildGrid {
    private FortressBuildGrid() {
    }

    public enum PlaceReject {
        OK,
        UNKNOWN_STRUCTURE,
        OUT_OF_BOUNDS,
        COLLISION,
        LEVEL_TOO_LOW
    }

    /** Footprint cells occupied by a placement (grid coords relative to instance origin). */
    public static List<int[]> footprintCells(FortressStructureData def, int gridX, int gridY, int rotationDeg) {
        List<int[]> cells = new ArrayList<>();
        if (def == null)
            return cells;
        int w = def.rotatedW(rotationDeg);
        int h = def.rotatedH(rotationDeg);
        for (int dx = 0; dx < w; dx++) {
            for (int dy = 0; dy < h; dy++) {
                cells.add(new int[]{gridX + dx, gridY + dy});
            }
        }
        return cells;
    }

    public static boolean inBuildableZone(FortressInstance inst, int gridX, int gridY, int w, int h) {
        if (inst == null)
            return false;
        int ox = inst.getGridOriginX();
        int oy = inst.getGridOriginY();
        int bw = inst.getGridWidth();
        int bh = inst.getGridHeight();
        return gridX >= ox && gridY >= oy
                && gridX + w <= ox + bw
                && gridY + h <= oy + bh;
    }

    public static boolean collidesExisting(FortressInstance inst, FortressStructureData def,
                                           int gridX, int gridY, int rotationDeg,
                                           int ignoreIndex) {
        if (inst == null || def == null)
            return true;
        List<int[]> want = footprintCells(def, gridX, gridY, rotationDeg);
        List<PlacedStructure> placed = inst.mutableStructures();
        for (int i = 0; i < placed.size(); i++) {
            if (i == ignoreIndex)
                continue;
            PlacedStructure p = placed.get(i);
            FortressStructureData other = lookup(p.structureId);
            if (other == null)
                continue;
            List<int[]> have = footprintCells(other, p.gridX, p.gridY, p.rotationDeg);
            for (int[] a : want) {
                for (int[] b : have) {
                    if (a[0] == b[0] && a[1] == b[1])
                        return true;
                }
            }
        }
        return false;
    }

    public static PlaceReject canPlace(FortressInstance inst, FortressStructureData def,
                                       int gridX, int gridY, int rotationDeg, int constructionLevel) {
        if (def == null)
            return PlaceReject.UNKNOWN_STRUCTURE;
        int w = def.rotatedW(rotationDeg);
        int h = def.rotatedH(rotationDeg);
        if (!inBuildableZone(inst, gridX, gridY, w, h))
            return PlaceReject.OUT_OF_BOUNDS;
        if (constructionLevel < def.constructionLevel)
            return PlaceReject.LEVEL_TOO_LOW;
        if (collidesExisting(inst, def, gridX, gridY, rotationDeg, -1))
            return PlaceReject.COLLISION;
        return PlaceReject.OK;
    }

    public static boolean isValidPreview(FortressInstance inst, FortressStructureData def,
                                         int gridX, int gridY, int rotationDeg, int constructionLevel) {
        return canPlace(inst, def, gridX, gridY, rotationDeg, constructionLevel) == PlaceReject.OK;
    }

    /**
     * Place without spending materials (caller handles costs). Returns false on reject.
     */
    public static boolean place(FortressInstance inst, FortressStructureData def,
                                int gridX, int gridY, int rotationDeg, int constructionLevel) {
        if (canPlace(inst, def, gridX, gridY, rotationDeg, constructionLevel) != PlaceReject.OK)
            return false;
        inst.mutableStructures().add(new PlacedStructure(def.id, gridX, gridY, rotationDeg));
        return true;
    }

    /**
     * Demolish structure at index; returns material refund map (floored %). Empty if bad index.
     */
    public static Map<String, Integer> demolish(FortressInstance inst, int index, ConfigData cfg) {
        if (inst == null || index < 0 || index >= inst.mutableStructures().size())
            return Collections.emptyMap();
        PlacedStructure removed = inst.mutableStructures().remove(index);
        FortressStructureData def = lookup(removed.structureId);
        if (def == null || def.materials == null || def.materials.size == 0)
            return Collections.emptyMap();
        float pct = cfg != null ? cfg.fortressDemolishRefundPercent : 50f;
        pct = Math.max(0f, Math.min(100f, pct));
        LinkedHashMap<String, Integer> refund = new LinkedHashMap<>();
        for (com.badlogic.gdx.utils.ObjectMap.Entry<String, Integer> e : def.materials) {
            if (e.key == null || e.value == null || e.value <= 0)
                continue;
            int give = (int) Math.floor(e.value * (pct / 100f));
            if (give > 0)
                refund.put(e.key, give);
        }
        return refund;
    }

    /** Index of structure covering grid cell, or -1. */
    public static int structureAt(FortressInstance inst, int gridX, int gridY) {
        if (inst == null)
            return -1;
        List<PlacedStructure> placed = inst.mutableStructures();
        for (int i = 0; i < placed.size(); i++) {
            PlacedStructure p = placed.get(i);
            FortressStructureData def = lookup(p.structureId);
            if (def == null)
                continue;
            for (int[] c : footprintCells(def, p.gridX, p.gridY, p.rotationDeg)) {
                if (c[0] == gridX && c[1] == gridY)
                    return i;
            }
        }
        return -1;
    }

    public static int nextRotation(int rotationDeg) {
        return FortressStructureData.normalizeRotation(rotationDeg + 90);
    }

    private static FortressStructureData lookup(String id) {
        return forge.adventure.data.FortressStructureListData.get(id);
    }
}
