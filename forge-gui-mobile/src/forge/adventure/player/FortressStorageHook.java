package forge.adventure.player;

/**
 * Optional INV1 overflow sink for fortress storage (FT1+). When Overflow is at cap,
 * {@link InventoryBags#placeInOverflow} tries this hook before auto-selling.
 */
public interface FortressStorageHook {
    /**
     * Try to store overflow of the given bag kind.
     *
     * @param bag     which bag overflowed
     * @param key     item name, material id, currency id, or "booster"
     * @param amount  units that could not fit
     * @return true if everything was stored (caller treats the add as successful)
     */
    boolean storeOverflow(InventoryBagType bag, String key, int amount);

    /** No-op hook used until fortress storage exists. */
    FortressStorageHook NONE = (bag, key, amount) -> false;
}
