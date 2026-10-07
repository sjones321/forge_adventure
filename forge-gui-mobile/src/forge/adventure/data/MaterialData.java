package forge.adventure.data;

import com.badlogic.gdx.graphics.g2d.Sprite;
import forge.adventure.util.Config;

import java.io.Serial;
import java.io.Serializable;

/**
 * One gatherable / droppable material from {@code world/materials.json}.
 * Stable API for gathering (B), recipes (E), and town requests (H).
 */
public class MaterialData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String id;
    public String name;
    /** Family key: logs, ore, stone, herbs, crystal, scrap, gems, hide, boss. */
    public String family;
    /** Tier 1-4 (boss materials use 4). */
    public int tier = 1;
    /** Gathering / crafting skill name (may be empty for drop-only materials). */
    public String skill = "";
    public int levelRequired = 1;
    /** Base XP granted when gathering one unit (gathering package). */
    public int xp = 0;
    public String iconName = "Item";
    /** Primary biome for node spawning (gathering package); empty if drop-only. */
    public String biome = "";
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
}
