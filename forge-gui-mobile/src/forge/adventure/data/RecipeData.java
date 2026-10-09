package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

import java.io.Serial;
import java.io.Serializable;

/**
 * One craft recipe from {@code world/recipes.json}.
 * Stable format for Package F (gear lines, potions, jewelry, tools).
 *
 * <pre>
 * {
 *   "id": "copper_blade",
 *   "station": "forge",
 *   "result": "Bronze Sword",
 *   "resultType": "item",
 *   "materials": { "ore_iron": 3, "limestone": 1 },
 *   "gold": 25,
 *   "skill": "Smithing",
 *   "levelRequired": 1,
 *   "xp": 40
 * }
 * </pre>
 *
 * {@code station}: forge | workshop | apothecary | jeweler<br>
 * {@code resultType}: item (default) | tool | potion | method | tool_enchant<br>
 * {@code result}: item display name for item/tool; display label for potion/method/enchant<br>
 * {@code blessing}: optional EffectData when resultType is potion (next-duel blessing)<br>
 * {@code methodId}: gathering_methods.json method upgrade id when resultType is method<br>
 * {@code enchantmentId}: enchantment id when resultType is tool_enchant<br>
 * {@code toolFamily}: toolbelt family to socket when resultType is tool_enchant
 */
public class RecipeData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String id;
    /** Station key: forge, workshop, apothecary, jeweler. */
    public String station;
    /** Item name (ItemData.name) for item/tool, or display label for potion/method/enchant. */
    public String result;
    /**
     * item (default), tool, potion, method (B2 gathering method), or tool_enchant (B2 socket).
     * Tools use Package B's toolbelt; potions use {@link #blessing}.
     */
    public String resultType = "item";
    /** Material id → count required. */
    public ObjectMap<String, Integer> materials = new ObjectMap<>();
    public int gold = 0;
    /** Crafting skill display name: Smithing, Woodworking, Alchemy, Jewelcrafting. */
    public String skill = "";
    public int levelRequired = 1;
    /** Skill XP granted on a successful craft. */
    public int xp = 0;
    /** Next-duel blessing when {@code resultType} is {@code potion}; ignored otherwise. */
    public EffectData blessing;
    /** gathering_methods.json method upgrade id when {@code resultType} is {@code method}. */
    public String methodId;
    /** Enchantment id when {@code resultType} is {@code tool_enchant}. */
    public String enchantmentId;
    /** Toolbelt family to socket when {@code resultType} is {@code tool_enchant}. */
    public String toolFamily;

    public ObjectMap<String, Integer> getMaterials() {
        return materials != null ? materials : new ObjectMap<>();
    }

    public String getDisplayResult() {
        return result != null ? result : id;
    }

    public boolean isPotion() {
        return "potion".equalsIgnoreCase(resultType);
    }

    public boolean isTool() {
        return "tool".equalsIgnoreCase(resultType);
    }

    public boolean isMethod() {
        return "method".equalsIgnoreCase(resultType);
    }

    public boolean isToolEnchant() {
        return "tool_enchant".equalsIgnoreCase(resultType);
    }

    /** Normalized station key, or empty. */
    public String stationKey() {
        return station != null ? station.trim().toLowerCase() : "";
    }
}
