package forge.adventure.player;

/**
 * INV1 overflow sink for fortress storage (FT1+). Until a fortress exists, returns false
 * so callers refuse the grant with a clear message instead of deleting items.
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
