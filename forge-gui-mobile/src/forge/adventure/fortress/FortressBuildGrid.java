package forge.adventure.fortress;

import forge.adventure.data.ConfigData;
import forge.adventure.data.FortressStructureData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grid placement / collision / path / demolish-refund for FT1 fortress build mode.
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
        LEVEL_TOO_LOW,
        ON_PLAYER,
        BLOCKS_PATH_TO_ENTRY
    }

    /** Footprint cells occupied by a placement. */
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

    /** True when the footprint covers the player's current grid tile. */
    public static boolean coversPlayerTile(FortressStructureData def, int gridX, int gridY,
                                           int rotationDeg, int playerGridX, int playerGridY) {
        if (def == null)
            return false;
        for (int[] c : footprintCells(def, gridX, gridY, rotationDeg)) {
            if (c[0] == playerGridX && c[1] == playerGridY)
                return true;
        }
        return false;
    }

    /**
     * Cells that block movement after a hypothetical place (existing blockers + new if it blocks).
     * Gates ({@code blocksMovement=false}) are walkable.
     */
    public static Set<Long> blockedCellsAfterPlace(FortressInstance inst, FortressStructureData def,
                                                   int gridX, int gridY, int rotationDeg) {
        return blockedCellsAfterPlace(inst, def, gridX, gridY, rotationDeg, null);
    }

    /**
     * Blocked cells after a hypothetical place: map collision layer + existing structure
     * blockers + the new footprint when it blocks movement. Gates stay walkable.
     */
    public static Set<Long> blockedCellsAfterPlace(FortressInstance inst, FortressStructureData def,
                                                   int gridX, int gridY, int rotationDeg,
                                                   Set<Long> mapCollision) {
        Set<Long> blocked = movementBlockedCells(inst, -1);
        if (mapCollision != null)
            blocked.addAll(mapCollision);
        if (def != null && def.blocksMovement) {
            for (int[] c : footprintCells(def, gridX, gridY, rotationDeg))
                blocked.add(pack(c[0], c[1]));
        }
        return blocked;
    }

    public static Set<Long> movementBlockedCells(FortressInstance inst, int ignoreIndex) {
        Set<Long> blocked = new HashSet<>();
        if (inst == null)
            return blocked;
        List<PlacedStructure> placed = inst.mutableStructures();
        for (int i = 0; i < placed.size(); i++) {
            if (i == ignoreIndex)
                continue;
            PlacedStructure p = placed.get(i);
            FortressStructureData other = lookup(p.structureId);
            if (other == null || !other.blocksMovement)
                continue;
            for (int[] c : footprintCells(other, p.gridX, p.gridY, p.rotationDeg))
                blocked.add(pack(c[0], c[1]));
        }
        return blocked;
    }

    /**
     * Rasterize a world-pixel rectangle into map tile keys for path BFS.
     * A cell is covered when the rect overlaps any part of that tile.
     */
    public static void addCellsCoveredByRect(Set<Long> out, float tileW, float tileH,
                                             float x, float y, float w, float h) {
        if (out == null || tileW <= 0f || tileH <= 0f || w <= 0f || h <= 0f)
            return;
        int x0 = (int) Math.floor(x / tileW);
        int y0 = (int) Math.floor(y / tileH);
        int x1 = (int) Math.floor((x + w - 0.001f) / tileW);
        int y1 = (int) Math.floor((y + h - 0.001f) / tileH);
        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++)
                out.add(pack(tx, ty));
        }
    }

    /**
     * Interaction zone for a solid station: same footprint expanded by {@code padTiles}
     * on each side so the player can open it without walking into the collision box.
     *
     * @return {@code [x, y, width, height]} in world pixels
     */
    public static float[] stationInteractBounds(float px, float py, int footprintW, int footprintH,
                                                float tileW, float tileH, float padTiles) {
        float tw = Math.max(1f, tileW);
        float th = Math.max(1f, tileH);
        float pad = Math.max(0f, padTiles);
        float padX = pad * tw;
        float padY = pad * th;
        int w = Math.max(1, footprintW);
        int h = Math.max(1, footprintH);
        return new float[]{px - padX, py - padY, w * tw + 2f * padX, h * th + 2f * padY};
    }

    /**
     * BFS walkability from player to entry across {@code [0,mapW) x [0,mapH)}.
     * Blocked cells are impassable. Entry/player outside the map → false.
     */
    public static boolean hasPathToEntry(int mapW, int mapH, Set<Long> blocked,
                                         int playerX, int playerY, int entryX, int entryY) {
        if (mapW <= 0 || mapH <= 0)
            return false;
        if (!inMap(mapW, mapH, playerX, playerY) || !inMap(mapW, mapH, entryX, entryY))
            return false;
        if (blocked != null && (blocked.contains(pack(playerX, playerY)) || blocked.contains(pack(entryX, entryY))))
            return false;
        if (playerX == entryX && playerY == entryY)
            return true;
        boolean[][] seen = new boolean[mapW][mapH];
        ArrayDeque<int[]> q = new ArrayDeque<>();
        q.add(new int[]{playerX, playerY});
        seen[playerX][playerY] = true;
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!q.isEmpty()) {
            int[] cur = q.removeFirst();
            for (int[] d : dirs) {
                int nx = cur[0] + d[0];
                int ny = cur[1] + d[1];
                if (!inMap(mapW, mapH, nx, ny) || seen[nx][ny])
                    continue;
                if (blocked != null && blocked.contains(pack(nx, ny)))
                    continue;
                if (nx == entryX && ny == entryY)
                    return true;
                seen[nx][ny] = true;
                q.add(new int[]{nx, ny});
            }
        }
        return false;
    }

    public static PlaceReject canPlace(FortressInstance inst, FortressStructureData def,
                                       int gridX, int gridY, int rotationDeg, int constructionLevel) {
        return canPlace(inst, def, gridX, gridY, rotationDeg, constructionLevel,
                Integer.MIN_VALUE, Integer.MIN_VALUE, -1, -1, 0, 0, null);
    }

    /**
     * Full placement check including player-tile trap and path-to-entry.
     * Pass {@code playerGridX == Integer.MIN_VALUE} to skip player/path checks (catalog only).
     * {@code mapCollision} is the map's own collision layer (TMX), merged with placed structures.
     */
    public static PlaceReject canPlace(FortressInstance inst, FortressStructureData def,
                                       int gridX, int gridY, int rotationDeg, int constructionLevel,
                                       int playerGridX, int playerGridY,
                                       int entryGridX, int entryGridY,
                                       int mapW, int mapH) {
        return canPlace(inst, def, gridX, gridY, rotationDeg, constructionLevel,
                playerGridX, playerGridY, entryGridX, entryGridY, mapW, mapH, null);
    }

    public static PlaceReject canPlace(FortressInstance inst, FortressStructureData def,
                                       int gridX, int gridY, int rotationDeg, int constructionLevel,
                                       int playerGridX, int playerGridY,
                                       int entryGridX, int entryGridY,
                                       int mapW, int mapH, Set<Long> mapCollision) {
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
        if (playerGridX != Integer.MIN_VALUE
                && coversPlayerTile(def, gridX, gridY, rotationDeg, playerGridX, playerGridY))
            return PlaceReject.ON_PLAYER;
        if (playerGridX != Integer.MIN_VALUE && mapW > 0 && mapH > 0) {
            Set<Long> blocked = blockedCellsAfterPlace(inst, def, gridX, gridY, rotationDeg, mapCollision);
            if (!hasPathToEntry(mapW, mapH, blocked, playerGridX, playerGridY, entryGridX, entryGridY))
                return PlaceReject.BLOCKS_PATH_TO_ENTRY;
        }
        return PlaceReject.OK;
    }

    public static boolean isValidPreview(FortressInstance inst, FortressStructureData def,
                                         int gridX, int gridY, int rotationDeg, int constructionLevel,
                                         int playerGridX, int playerGridY,
                                         int entryGridX, int entryGridY,
                                         int mapW, int mapH) {
        return isValidPreview(inst, def, gridX, gridY, rotationDeg, constructionLevel,
                playerGridX, playerGridY, entryGridX, entryGridY, mapW, mapH, null);
    }

    public static boolean isValidPreview(FortressInstance inst, FortressStructureData def,
                                         int gridX, int gridY, int rotationDeg, int constructionLevel,
                                         int playerGridX, int playerGridY,
                                         int entryGridX, int entryGridY,
                                         int mapW, int mapH, Set<Long> mapCollision) {
        return canPlace(inst, def, gridX, gridY, rotationDeg, constructionLevel,
                playerGridX, playerGridY, entryGridX, entryGridY, mapW, mapH, mapCollision)
                == PlaceReject.OK;
    }

    /**
     * Place without spending materials (caller handles costs / paid snapshot).
     */
    public static boolean place(FortressInstance inst, FortressStructureData def,
                                int gridX, int gridY, int rotationDeg, int constructionLevel,
                                Map<String, Integer> paidMaterials, int paidGold,
                                int playerGridX, int playerGridY,
                                int entryGridX, int entryGridY,
                                int mapW, int mapH) {
        return place(inst, def, gridX, gridY, rotationDeg, constructionLevel,
                paidMaterials, paidGold, playerGridX, playerGridY, entryGridX, entryGridY, mapW, mapH, null);
    }

    public static boolean place(FortressInstance inst, FortressStructureData def,
                                int gridX, int gridY, int rotationDeg, int constructionLevel,
                                Map<String, Integer> paidMaterials, int paidGold,
                                int playerGridX, int playerGridY,
                                int entryGridX, int entryGridY,
                                int mapW, int mapH, Set<Long> mapCollision) {
        if (canPlace(inst, def, gridX, gridY, rotationDeg, constructionLevel,
                playerGridX, playerGridY, entryGridX, entryGridY, mapW, mapH, mapCollision)
                != PlaceReject.OK)
            return false;
        PlacedStructure placed = new PlacedStructure(def.id, gridX, gridY, rotationDeg);
        placed.setPaidCost(paidMaterials, paidGold);
        inst.mutableStructures().add(placed);
        return true;
    }

    /** Convenience for tests that skip player/path checks. */
    public static boolean place(FortressInstance inst, FortressStructureData def,
                                int gridX, int gridY, int rotationDeg, int constructionLevel) {
        return place(inst, def, gridX, gridY, rotationDeg, constructionLevel,
                catalogCost(def), def != null ? def.gold : 0,
                Integer.MIN_VALUE, Integer.MIN_VALUE, -1, -1, 0, 0);
    }

    /**
     * Demolish by index. Refund is based on {@link PlacedStructure#paidMaterials} (what was
     * actually paid), never grants XP. Gold is not refunded in FT1.
     */
    public static Map<String, Integer> demolish(FortressInstance inst, int index, ConfigData cfg) {
        if (inst == null || index < 0 || index >= inst.mutableStructures().size())
            return Collections.emptyMap();
        PlacedStructure removed = inst.mutableStructures().remove(index);
        Map<String, Integer> paid = removed.paidMaterials;
        // Legacy placements (no paid snapshot) fall back to current catalog.
        if (paid == null || paid.isEmpty()) {
            FortressStructureData def = lookup(removed.structureId);
            paid = catalogCost(def);
        }
        float pct = cfg != null ? cfg.fortressDemolishRefundPercent : 50f;
        pct = Math.max(0f, Math.min(100f, pct));
        LinkedHashMap<String, Integer> refund = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : paid.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0)
                continue;
            int give = (int) Math.floor(e.getValue() * (pct / 100f));
            if (give > 0)
                refund.put(e.getKey(), give);
        }
        return refund;
    }

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

    public static Map<String, Integer> catalogCost(FortressStructureData def) {
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        if (def == null || def.materials == null)
            return out;
        for (com.badlogic.gdx.utils.ObjectMap.Entry<String, Integer> e : def.materials) {
            if (e.key == null || e.value == null || e.value <= 0)
                continue;
            out.put(e.key, e.value);
        }
        return out;
    }

    /** Gates stay walkable; stations/walls/storage block. */
    public static boolean blocksMovement(PlacedStructure p) {
        FortressStructureData def = p == null ? null : lookup(p.structureId);
        return def != null && def.blocksMovement;
    }

    private static boolean inMap(int mapW, int mapH, int x, int y) {
        return x >= 0 && y >= 0 && x < mapW && y < mapH;
    }

    private static long pack(int x, int y) {
        return (((long) x) << 32) ^ (y & 0xffffffffL);
    }

    private static FortressStructureData lookup(String id) {
        return forge.adventure.data.FortressStructureListData.get(id);
    }
}
