package forge.adventure.data;

import com.badlogic.gdx.graphics.g2d.Sprite;
import forge.adventure.util.Config;

import java.io.Serial;
import java.io.Serializable;

/**
 * One gatherable / droppable material from {@code world/materials.json}.
 * Stable API for gathering (B / B2), reagent card crafting (A2), recipes (E), and town requests (H).
 */
public class MaterialData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String id;
    public String name;
    /**
     * Family key for tools, nodes, and reagents:
     * logs, ore, ash, sacred_stone, waters, dead, plants, scrap,
     * gems, crystal, pearls, feathers, hide, brine, prismatic, boss.
     */
    public String family;
    /** Tier 1-4 (boss materials use 4). Card rarity maps to reagent tier in A2. */
    public int tier = 1;
    /** Gathering / crafting skill name (may be empty for drop-only / crafted reagents). */
    public String skill = "";
    public int levelRequired = 1;
    /** Base XP granted when gathering one unit. */
    public int xp = 0;
    public String iconName = "Item";
    /** Primary biome for node spawning; empty if drop-only / crafted. */
    public String biome = "";
    /**
     * Overworld / interior node kind for B2 and D:
     * tree, vein, vent, stone, water, plant, remains, scrap (empty if not a node).
     */
    public String nodeType = "";
    /**
     * Mana color letter for reagent use (A2): W, U, B, R, G, or C.
     * Empty for non-reagent families (gems, boss, etc.).
     */
    public String color = "";
    /** Gold shops pay when the player sells one unit. */
    public int sellPrice = 5;
    /** Spellsmithing refine-to-dust mapping; null if not refinable. */
    public DustRefine dustRefine;

    public static class DustRefine implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        /** common / uncommon / rare / mythic */
        public String rarity = "common";
        public int amount = 1;
    }

    public Sprite sprite() {
        return Config.instance().getItemSprite(iconName != null ? iconName : "Item");
    }

    public String getDisplayName() {
        return name != null ? name : id;
    }

    /** True when this material can spawn as an overworld gather node. */
    public boolean isGatherNode() {
        return biome != null && !biome.isEmpty()
                && skill != null && !skill.isEmpty()
                && nodeType != null && !nodeType.isEmpty();
    }

    /**
     * Toolbelt family that gates gathering this material.
     * Ash uses the mining pickaxe ({@code ore}); dead things use the foraging sickle ({@code plants}).
     */
    public String toolFamily() {
        if (family == null)
            return "";
        switch (family.toLowerCase()) {
            case "ash":
                return "ore";
            case "dead":
                return "plants";
            case "crystal":
            case "pearls":
                return "waters";
            case "sacred_stone":
            case "stone":
                return "sacred_stone";
            case "herbs":
                return "plants";
            default:
                return family.toLowerCase();
        }
    }
}
