package forge.adventure.player;

/**
 * Ascendant INV1 bag kinds. Stock Adventure keeps a flat inventory and never uses these.
 */
public enum InventoryBagType {
    BACKPACK("Backpack"),
    PACKS("Packs"),
    CURRENCY("Currency"),
    MATERIALS("Materials");

    public final String label;

    InventoryBagType(String label) {
        this.label = label;
    }
}
