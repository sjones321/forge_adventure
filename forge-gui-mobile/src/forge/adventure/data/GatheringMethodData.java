package forge.adventure.data;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

import java.io.Serial;
import java.io.Serializable;

/**
 * Data models for {@code world/gathering_methods.json} (Package B2):
 * method upgrades, tool enchantments, and outpost camp definitions.
 */
public final class GatheringMethodData {
    private GatheringMethodData() {
    }

    /** Root file shape. */
    public static class FileRoot implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public Array<MethodUpgrade> methodUpgrades = new Array<>();
        public Array<ToolEnchantment> enchantments = new Array<>();
        public Array<Outpost> outposts = new Array<>();
    }

    /** One crafted gathering-method upgrade for a skill. */
    public static class MethodUpgrade implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public String id;
        public String skill;
        public int rank = 1;
        public String name;
        public String description;
        /** Effect key applied while gathering with this skill at this rank+. */
        public String effect;
        public float effectValue = 1f;

        public String getDisplayName() {
            return name != null ? name : id;
        }
    }

    /** A socketable tool enchantment (filled with a gem or crystal). */
    public static class ToolEnchantment implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public String id;
        public String name;
        public String description;
        /** Material id spent / shown as the socket gem. */
        public String socketMaterial;
        public String effect;
        public float value = 0f;

        public String getDisplayName() {
            return name != null ? name : id;
        }
    }

    /** Claimable resource camp definition. */
    public static class Outpost implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public String id;
        public String name;
        public String family;
        public String skill;
        /** Hours of production held before storage is full. */
        public float storageCapHours = 72f;
        public Array<OutpostLevel> levels = new Array<>();

        public String getDisplayName() {
            return name != null ? name : id;
        }

        public OutpostLevel levelData(int level) {
            if (levels == null)
                return null;
            for (OutpostLevel l : new Array.ArrayIterator<>(levels)) {
                if (l != null && l.level == level)
                    return l;
            }
            return null;
        }

        public int maxLevel() {
            int max = 0;
            if (levels == null)
                return 0;
            for (OutpostLevel l : new Array.ArrayIterator<>(levels)) {
                if (l != null && l.level > max)
                    max = l.level;
            }
            return max;
        }
    }

    /** One build / upgrade tier for an outpost. */
    public static class OutpostLevel implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public int level = 1;
        public String materialId;
        public float outputPerHour = 2f;
        public int gold = 0;
        public ObjectMap<String, Integer> materials = new ObjectMap<>();

        public ObjectMap<String, Integer> getMaterials() {
            return materials != null ? materials : new ObjectMap<>();
        }
    }
}
