package forge.adventure.player;

import forge.adventure.data.ItemData;
import forge.deck.Deck;

import java.io.Serializable;

/**
 * One Overflow stash slot. Overflow items cannot be used, equipped, or sold until moved
 * into a bag with room (auto-sell past the overflow cap is handled separately).
 */
public class OverflowEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Kind {
        ITEM, BOOSTER, MATERIAL, CURRENCY
    }

    public Kind kind;
    /** Item name, material id, currency id, or booster name. */
    public String key;
    public ItemData item;
    public Deck booster;
    /** Units for material / currency entries. */
    public int amount = 1;

    public OverflowEntry() {}

    public static OverflowEntry ofItem(ItemData item) {
        OverflowEntry e = new OverflowEntry();
        e.kind = Kind.ITEM;
        e.item = item;
        e.key = item != null ? item.name : null;
        e.amount = 1;
        return e;
    }

    public static OverflowEntry ofBooster(Deck booster) {
        OverflowEntry e = new OverflowEntry();
        e.kind = Kind.BOOSTER;
        e.booster = booster;
        e.key = booster != null ? booster.getName() : "booster";
        e.amount = 1;
        return e;
    }

    public static OverflowEntry ofMaterial(String id, int amount) {
        OverflowEntry e = new OverflowEntry();
        e.kind = Kind.MATERIAL;
        e.key = id;
        e.amount = Math.max(1, amount);
        return e;
    }

    public static OverflowEntry ofCurrency(String id, int amount) {
        OverflowEntry e = new OverflowEntry();
        e.kind = Kind.CURRENCY;
        e.key = id;
        e.amount = Math.max(1, amount);
        return e;
    }

    public String displayName() {
        if (kind == Kind.ITEM && item != null)
            return item.getDisplayName();
        if (kind == Kind.BOOSTER && booster != null)
            return booster.getName() != null ? booster.getName() : "Booster";
        if (key != null)
            return key + (amount > 1 ? " ×" + amount : "");
        return "Item";
    }
}
