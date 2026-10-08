package forge.adventure.data;

import com.badlogic.gdx.graphics.g2d.Sprite;
import forge.adventure.util.Config;

import java.io.Serializable;
import java.util.UUID;

/**
 * Data class that will be used to read Json configuration files
 * ItemData
 * contains the information for equipment and items.
 */
public class ItemData implements Serializable, Cloneable {
    private static final long serialVersionUID = 1L;
    public String name;
    public String equipmentSlot;
    public EffectData effect;
    public String description; //Manual description of the item.
    public String iconName;
    public boolean questItem=false;
    public int cost=1000;
    
    public boolean usableOnWorldMap;
    public boolean usableInPoi;
    public boolean isCracked;
    public boolean isEquipped;
    public Long longID;
    public String commandOnUse;
    public int shardsNeeded;
    public DialogData dialogOnUse;

    /**
     * Ascendant gathering tool family key matching materials.json {@code family}
     * (logs, ore, stone, herbs, crystal, scrap). Empty/null = not a tool.
     * Tools live on the toolbelt, not in equipment slots (Package B / E).
     */
    public String toolFamily;
    /** Tool tier 1-4; caps the material tier that can be gathered. */
    public int toolTier = 0;

    // ---- Ascendant INV1 bags ----
    /** When true, identical items share a backpack/currency stack (up to bag max stack). */
    public boolean stackable = false;
    /**
     * Currency pouch key when this item is a contest/challenge coin (e.g. {@code gym_coin}).
     * Non-empty → classified into the Currency bag.
     */
    public String currencyId;
    /**
     * Bag upgrade target when used/crafted: {@code backpack}, {@code packs},
     * {@code currency}, or {@code materials}.
     */
    public String bagUpgrade;
    /** Slots added when this bag upgrade is applied. */
    public int bagBonusSlots = 0;
    /** Max-stack increase when this bag upgrade is applied. */
    public int bagBonusStack = 0;


    public ItemData()
    {

    }
    public ItemData(ItemData cpy)
    {
        name              = cpy.name;
        equipmentSlot     = cpy.equipmentSlot;
        effect            = new EffectData(cpy.effect);
        description       = cpy.description;
        iconName          = cpy.iconName;
        questItem         = cpy.questItem;
        cost              = cpy.cost;
        usableInPoi       = cpy.usableInPoi;
        usableOnWorldMap  = cpy.usableOnWorldMap;
        commandOnUse      = cpy.commandOnUse;
        shardsNeeded      = cpy.shardsNeeded;
        dialogOnUse       = cpy.dialogOnUse;
        toolFamily        = cpy.toolFamily;
        toolTier          = cpy.toolTier;
        stackable         = cpy.stackable;
        currencyId        = cpy.currencyId;
        bagUpgrade        = cpy.bagUpgrade;
        bagBonusSlots     = cpy.bagBonusSlots;
        bagBonusStack     = cpy.bagBonusStack;
    }

    public boolean isGatheringTool() {
        return toolFamily != null && !toolFamily.isEmpty() && toolTier > 0;
    }

    public Sprite sprite() {
        return Config.instance().getItemSprite(iconName);
    }

    public String getDescription() {
        String result = "";
        String translatedDescription = forge.Forge.getLocalizer().getMessageorUseDefault(
            "adv.item." + makeKey(name) + ".description", "");
        String baseDescription = !translatedDescription.isEmpty() ? translatedDescription : this.description;
        if(baseDescription != null && !baseDescription.isEmpty())
            result += baseDescription + "\n";
        if(this.equipmentSlot != null && !this.equipmentSlot.isEmpty())
            result += "Slot: " + this.equipmentSlot + "\n";
        if(isGatheringTool())
            result += "Toolbelt: " + toolFamily + " (tier " + toolTier + ")\n";
        if(effect != null)
            result += effect.getDescription();
        if(shardsNeeded != 0)
            result +=  shardsNeeded+" [+Shards]";
        return result;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return forge.Forge.getLocalizer().getMessageorUseDefault(
            "adv.item." + makeKey(name) + ".displayName", name);
    }

    //Builds a .properties key from this item's English name by dropping the
    //characters that would otherwise break a properties key (space, ':'),
    //keeping everything else (letters, digits, apostrophes, hyphens...) as-is.
    //e.g. "Silver Challenge Coin" -> "SilverChallengeCoin"
    private static String makeKey(String text) {
        if (text == null) return "";
        return text.replace(":", "").replace(" ", "");
    }

    @Override
    public ItemData clone() {
        try {
            ItemData clone = (ItemData) super.clone();
            clone.longID = UUID.randomUUID().getMostSignificantBits();
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError();
        }
    }
}
