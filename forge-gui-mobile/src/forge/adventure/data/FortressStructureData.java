package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

import java.io.Serial;
import java.io.Serializable;

/**
 * One fortress structure definition from {@code world/structures_fortress.json} (FT1).
 *
 * <pre>
 * {
 *   "id": "wooden_wall",
 *   "name": "Wooden Wall",
 *   "footprintW": 1,
 *   "footprintH": 1,
 *   "sprite": "Block",
 *   "iconName": "ManaShard",
 *   "materials": { "oak": 5 },
 *   "gold": 0,
 *   "constructionLevel": 1,
 *   "tier": 1,
 *   "stationType": "",
 *   "xp": 15,
 *   "blocksMovement": true
 * }
 * </pre>
 *
 * {@code stationType}: empty, or forge / workshop / apothecary / jeweler / spellsmith / storage
 */
public class FortressStructureData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String id;
    public String name = "";
    public int footprintW = 1;
    public int footprintH = 1;
    /** Placeholder atlas region (buildings.atlas or item icon). */
    public String sprite = "";
    public String iconName = "";
    /** Material id → count required to build. */
    public ObjectMap<String, Integer> materials = new ObjectMap<>();
    public int gold = 0;
    public int constructionLevel = 1;
    public int tier = 1;
    /** Station key when this structure is a crafting station; empty otherwise. */
    public String stationType = "";
    /** Construction XP granted on successful place. */
    public int xp = 10;
    public boolean blocksMovement = true;

    public String displayName() {
        return name != null && !name.isEmpty() ? name : (id != null ? id : "");
    }

    public String stationKey() {
        return stationType == null ? "" : stationType.trim().toLowerCase();
    }

    public boolean isStation() {
        String s = stationKey();
        return !s.isEmpty() && !"storage".equals(s);
    }

    public boolean isStorage() {
        return "storage".equals(stationKey());
    }

    /** Footprint width after rotation (90/270 swaps axes). */
    public int rotatedW(int rotationDeg) {
        int r = normalizeRotation(rotationDeg);
        return (r == 90 || r == 270) ? Math.max(1, footprintH) : Math.max(1, footprintW);
    }

    /** Footprint height after rotation (90/270 swaps axes). */
    public int rotatedH(int rotationDeg) {
        int r = normalizeRotation(rotationDeg);
        return (r == 90 || r == 270) ? Math.max(1, footprintW) : Math.max(1, footprintH);
    }

    public static int normalizeRotation(int rotationDeg) {
        int r = rotationDeg % 360;
        if (r < 0)
            r += 360;
        if (r < 45 || r >= 315)
            return 0;
        if (r < 135)
            return 90;
        if (r < 225)
            return 180;
        return 270;
    }
}
