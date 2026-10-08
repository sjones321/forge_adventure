package forge.adventure.player;

/**
 * Ascendant INV1 Craft Pouch tiers. Advanced only via Mastery Surge — not craftable or buyable.
 */
public enum CraftPouchTier {
    SATCHEL(0, "Satchel"),
    PACK(1, "Pack"),
    HAULER(2, "Hauler's Sack"),
    BOTTOMLESS(3, "Bottomless");

    public final int index;
    public final String label;

    CraftPouchTier(int index, String label) {
        this.index = index;
        this.label = label;
    }

    public static CraftPouchTier fromIndex(int i) {
        CraftPouchTier[] all = values();
        if (i < 0)
            return SATCHEL;
        if (i >= all.length)
            return BOTTOMLESS;
        return all[i];
    }

    public CraftPouchTier next() {
        CraftPouchTier[] all = values();
        if (index + 1 >= all.length)
            return null;
        return all[index + 1];
    }

    public boolean isBottomless() {
        return this == BOTTOMLESS;
    }
}
