package forge.adventure.player;

/**
 * Ascendant INV1 bag kinds. Stock Adventure keeps a flat inventory and never uses these.
 */
public enum InventoryBagType {
    BACKPACK("Backpack"),
    PACKS("Packs"),
    CURRENCY("Currency"),
    /** Craft Pouch — gathered/crafting materials with deep stacks. */
    MATERIALS("Craft Pouch"),
    /** Overflow stash for grants that did not fit a bag. */
    OVERFLOW("Overflow");

    public final String label;

    InventoryBagType(String label) {
        this.label = label;
    }

    /** Tabs shown in the inventory UI (includes Overflow). */
    public static InventoryBagType[] uiTabs() {
        return values();
    }
}
