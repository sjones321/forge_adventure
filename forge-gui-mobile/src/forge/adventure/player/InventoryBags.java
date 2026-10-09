package forge.adventure.player;

import forge.adventure.data.ConfigData;
import forge.adventure.data.ItemData;
import forge.deck.Deck;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Ascendant INV1 bag capacities, Craft Pouch tiers, Overflow stash, and grant routing.
 * Contents for backpack/packs/currency stay on {@link AdventurePlayer}; materials live in the
 * player's materials map (Craft Pouch). Overflow entries are stored here.
 * <p>
 * Pure enough for headless tests — pass a {@link ConfigData} (or defaults).
 */
public class InventoryBags implements Serializable {
    private static final long serialVersionUID = 2L;

    public static final String MESSAGE_OVERFLOW = "Bag full — sent to Overflow";

    /** Toolbelt family order matching INV1 (axe, pickaxe, sickle, chisel, bucket/probe, salvage). */
    public static final String[] TOOLBELT_FAMILIES = {
            "logs", "ore", "plants", "sacred_stone", "waters", "scrap"
    };
    public static final String[] TOOLBELT_LABELS = {
            "Axe", "Pick", "Sickle", "Chisel", "Probe", "Salvage"
    };

    public static final String CURRENCY_GOLD = "gold";
    public static final String CURRENCY_SHARDS = "shards";
    public static final String CURRENCY_DUST_C = "dust_common";
    public static final String CURRENCY_DUST_U = "dust_uncommon";
    public static final String CURRENCY_DUST_R = "dust_rare";
    public static final String CURRENCY_DUST_M = "dust_mythic";
    public static final String CURRENCY_GYM = "gym_coin";
    public static final String CURRENCY_TOURNAMENT = "tournament_coin";
    public static final String CURRENCY_GP = "grand_prix_coin";

    private int backpackSlots;
    private int backpackMaxStack;
    private int packsSlots;
    private int packsMaxStack;
    private int currencySlots;
    private int currencyMaxStack;

    /** Craft Pouch tier (Mastery Surge only). Capacities derived from config + tier. */
    private int craftPouchTier = CraftPouchTier.SATCHEL.index;
    private int materialsSlots;
    private int materialsMaxStack;

    private int overflowCap;
    private float overflowSlowMinFactor;
    private final ArrayList<OverflowEntry> overflow = new ArrayList<>();

    private transient FortressStorageHook fortressStorage = FortressStorageHook.NONE;
    private String lastMessage;
    /** @deprecated use {@link #getLastMessage()} — kept for older test names. */
    private String lastRefuseMessage;

    // Cached config values for pouch tier tables
    private transient ConfigData cfgCache;

    public InventoryBags() {
        this(new ConfigData());
    }

    public InventoryBags(ConfigData cfg) {
        resetToDefaults(cfg);
    }

    public void resetToDefaults(ConfigData cfg) {
        ConfigData c = cfg != null ? cfg : new ConfigData();
        cfgCache = c;
        backpackSlots = Math.max(1, c.backpackSlots);
        backpackMaxStack = Math.max(1, c.backpackMaxStack);
        packsSlots = Math.max(1, c.packsSlots);
        packsMaxStack = Math.max(1, c.packsMaxStack);
        currencySlots = Math.max(1, c.currencySlots);
        currencyMaxStack = Math.max(1, c.currencyMaxStack);
        craftPouchTier = CraftPouchTier.SATCHEL.index;
        applyCraftPouchTierCapacities();
        overflowCap = Math.max(1, c.overflowCap);
        overflowSlowMinFactor = c.overflowSlowMinFactor > 0f && c.overflowSlowMinFactor <= 1f
                ? c.overflowSlowMinFactor : 0.5f;
        overflow.clear();
        lastMessage = null;
        lastRefuseMessage = null;
    }

    private ConfigData cfg() {
        return cfgCache != null ? cfgCache : new ConfigData();
    }

    public void setConfigCache(ConfigData cfg) {
        cfgCache = cfg != null ? cfg : new ConfigData();
    }

    private void applyCraftPouchTierCapacities() {
        ConfigData c = cfg();
        CraftPouchTier tier = CraftPouchTier.fromIndex(craftPouchTier);
        switch (tier) {
            case SATCHEL:
                materialsSlots = Math.max(1, c.craftPouchSatchelSlots);
                materialsMaxStack = Math.max(1, c.craftPouchSatchelStack);
                break;
            case PACK:
                materialsSlots = Math.max(1, c.craftPouchPackSlots);
                materialsMaxStack = Math.max(1, c.craftPouchPackStack);
                break;
            case HAULER:
                materialsSlots = Math.max(1, c.craftPouchHaulerSlots);
                materialsMaxStack = Math.max(1, c.craftPouchHaulerStack);
                break;
            case BOTTOMLESS:
                materialsSlots = Integer.MAX_VALUE / 4;
                materialsMaxStack = Integer.MAX_VALUE / 4;
                break;
            default:
                materialsSlots = Math.max(1, c.craftPouchSatchelSlots);
                materialsMaxStack = Math.max(1, c.craftPouchSatchelStack);
        }
    }

    public CraftPouchTier getCraftPouchTier() {
        return CraftPouchTier.fromIndex(craftPouchTier);
    }

    public int getCraftPouchTierIndex() {
        return craftPouchTier;
    }

    /** Advance Craft Pouch to the next Mastery Surge tier. Returns false if already Bottomless. */
    public boolean upgradeCraftPouchTier() {
        CraftPouchTier next = getCraftPouchTier().next();
        if (next == null)
            return false;
        craftPouchTier = next.index;
        applyCraftPouchTierCapacities();
        return true;
    }

    public void setCraftPouchTier(int tierIndex) {
        craftPouchTier = CraftPouchTier.fromIndex(tierIndex).index;
        applyCraftPouchTierCapacities();
    }

    public void setFortressStorage(FortressStorageHook hook) {
        fortressStorage = hook != null ? hook : FortressStorageHook.NONE;
    }

    public FortressStorageHook getFortressStorage() {
        return fortressStorage;
    }

    public String getLastMessage() {
        return lastMessage;
    }

    /** Compatibility alias used by older callers/tests. */
    public String getLastRefuseMessage() {
        return lastRefuseMessage != null ? lastRefuseMessage : lastMessage;
    }

    private void setMsg(String msg) {
        lastMessage = msg;
        lastRefuseMessage = msg;
    }

    public int getOverflowCap() {
        return Math.max(1, overflowCap);
    }

    public void setOverflowCap(int cap) {
        overflowCap = Math.max(1, cap);
    }

    public void addOverflowCapBonus(int bonus) {
        if (bonus > 0)
            overflowCap += bonus;
    }

    public List<OverflowEntry> getOverflow() {
        return Collections.unmodifiableList(overflow);
    }

    public int overflowCount() {
        return overflow.size();
    }

    public boolean hasOverflow() {
        return !overflow.isEmpty();
    }

    /**
     * Movement multiplier while overloaded: 1.0 at empty, scales down to
     * {@code overflowSlowMinFactor} (~0.5) when overflow is full.
     */
    public float overflowSpeedFactor() {
        if (overflow.isEmpty())
            return 1f;
        float fill = Math.min(1f, overflow.size() / (float) getOverflowCap());
        return 1f - fill * (1f - overflowSlowMinFactor);
    }

    public int getSlots(InventoryBagType type) {
        switch (type) {
            case BACKPACK: return backpackSlots;
            case PACKS: return packsSlots;
            case CURRENCY: return currencySlots;
            case MATERIALS: return materialsSlots;
            case OVERFLOW: return getOverflowCap();
            default: return 0;
        }
    }

    public int getMaxStack(InventoryBagType type) {
        switch (type) {
            case BACKPACK: return backpackMaxStack;
            case PACKS: return packsMaxStack;
            case CURRENCY: return currencyMaxStack;
            case MATERIALS: return materialsMaxStack;
            case OVERFLOW: return 1;
            default: return 1;
        }
    }

    public void setSlots(InventoryBagType type, int slots) {
        slots = Math.max(1, slots);
        switch (type) {
            case BACKPACK: backpackSlots = slots; break;
            case PACKS: packsSlots = slots; break;
            case CURRENCY: currencySlots = slots; break;
            case MATERIALS:
                // Craft Pouch slots come from Mastery Surge tiers, not craftable upgrades.
                if (!getCraftPouchTier().isBottomless())
                    materialsSlots = slots;
                break;
            case OVERFLOW: overflowCap = slots; break;
            default: break;
        }
    }

    public void setMaxStack(InventoryBagType type, int maxStack) {
        maxStack = Math.max(1, maxStack);
        switch (type) {
            case BACKPACK: backpackMaxStack = maxStack; break;
            case PACKS: packsMaxStack = maxStack; break;
            case CURRENCY: currencyMaxStack = maxStack; break;
            case MATERIALS:
                if (!getCraftPouchTier().isBottomless())
                    materialsMaxStack = maxStack;
                break;
            default: break;
        }
    }

    public void applyUpgrade(ItemData upgrade) {
        if (upgrade == null || upgrade.bagUpgrade == null || upgrade.bagUpgrade.isEmpty())
            return;
        InventoryBagType type = parseBagUpgrade(upgrade.bagUpgrade);
        if (type == null || type == InventoryBagType.MATERIALS || type == InventoryBagType.OVERFLOW)
            return; // Craft Pouch tiers are Mastery Surge only
        if (upgrade.bagBonusSlots > 0)
            setSlots(type, getSlots(type) + upgrade.bagBonusSlots);
        if (upgrade.bagBonusStack > 0)
            setMaxStack(type, getMaxStack(type) + upgrade.bagBonusStack);
    }

    public static InventoryBagType parseBagUpgrade(String raw) {
        if (raw == null)
            return null;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "backpack": return InventoryBagType.BACKPACK;
            case "packs": case "pack": case "satchel": return InventoryBagType.PACKS;
            case "currency": case "pouch": return InventoryBagType.CURRENCY;
            // Materials / Craft Pouch upgrades are not craftable — ignore.
            case "materials": case "material": case "sack": return null;
            default: return null;
        }
    }

    /** Classify an item into a bag. Boosters are not ItemData — use {@link InventoryBagType#PACKS}. */
    public static InventoryBagType classifyItem(ItemData item) {
        if (item == null)
            return InventoryBagType.BACKPACK;
        if (item.currencyId != null && !item.currencyId.isEmpty())
            return InventoryBagType.CURRENCY;
        if (isChallengeCoinName(item.name))
            return InventoryBagType.CURRENCY;
        return InventoryBagType.BACKPACK;
    }

    public static boolean isChallengeCoinName(String name) {
        if (name == null)
            return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.contains("challenge coin") || n.endsWith(" gym coin")
                || n.contains("tournament coin") || n.contains("grand prix");
    }

    public static boolean isStackable(ItemData item) {
        if (item == null)
            return false;
        if (item.stackable)
            return true;
        if (item.isGatheringTool())
            return false;
        if (item.equipmentSlot != null && !item.equipmentSlot.isEmpty())
            return false;
        if (item.bagUpgrade != null && !item.bagUpgrade.isEmpty())
            return true;
        return classifyItem(item) == InventoryBagType.CURRENCY;
    }

    /** Quest items are exempt from backpack capacity. */
    public static boolean isCapacityExempt(ItemData item) {
        return item != null && item.questItem;
    }

    /**
     * Items that occupy backpack slots: not equipped gear, not toolbelt tools, not currency pouch items.
     */
    public static List<ItemData> backpackOccupants(Collection<ItemData> inventory,
                                                  Set<Long> equippedIds,
                                                  Map<String, String> toolbelt) {
        List<ItemData> out = new ArrayList<>();
        if (inventory == null)
            return out;
        for (ItemData item : inventory) {
            if (item == null)
                continue;
            if (classifyItem(item) != InventoryBagType.BACKPACK)
                continue;
            if (item.isEquipped && item.longID != null && equippedIds != null && equippedIds.contains(item.longID))
                continue;
            if (item.isGatheringTool() && toolbelt != null) {
                String eq = toolbelt.get(item.toolFamily);
                if (eq != null && eq.equalsIgnoreCase(item.name))
                    continue;
            }
            out.add(item);
        }
        return out;
    }

    /** Distinct backpack slots used after stacking. May exceed capacity before migration. */
    public int usedBackpackSlots(Collection<ItemData> inventory, Set<Long> equippedIds, Map<String, String> toolbelt) {
        List<ItemData> occ = backpackOccupants(inventory, equippedIds, toolbelt);
        // Quest items do not count toward capacity.
        occ.removeIf(InventoryBags::isCapacityExempt);
        return stackGroups(occ, backpackMaxStack).size();
    }

    public boolean isBackpackOverCapacity(Collection<ItemData> inventory, Set<Long> equippedIds, Map<String, String> toolbelt) {
        return usedBackpackSlots(inventory, equippedIds, toolbelt) > backpackSlots;
    }

    public int usedPackSlots(Iterable<Deck> boosters) {
        if (boosters == null)
            return 0;
        int n = 0;
        for (Deck ignored : boosters)
            n++;
        return n;
    }

    public boolean isPacksOverCapacity(Iterable<Deck> boosters) {
        return usedPackSlots(boosters) > packsSlots;
    }

    public int usedMaterialSlots(Map<String, Integer> materials) {
        if (materials == null || materials.isEmpty())
            return 0;
        int n = 0;
        for (Map.Entry<String, Integer> e : materials.entrySet()) {
            if (e.getValue() != null && e.getValue() > 0)
                n++;
        }
        return n;
    }

    public boolean isMaterialsOverCapacity(Map<String, Integer> materials) {
        if (materials == null)
            return false;
        if (getCraftPouchTier().isBottomless())
            return false;
        if (usedMaterialSlots(materials) > materialsSlots)
            return true;
        for (Integer c : materials.values()) {
            if (c != null && c > materialsMaxStack)
                return true;
        }
        return false;
    }

    public int usedCurrencySlots(Map<String, Integer> contestCurrencies, Collection<ItemData> inventory) {
        int n = 6; // gold, shards, 4 dusts always reserved
        if (contestCurrencies != null) {
            for (Map.Entry<String, Integer> e : contestCurrencies.entrySet()) {
                if (e.getValue() != null && e.getValue() > 0)
                    n++;
            }
        }
        if (inventory != null) {
            LinkedHashMap<String, Integer> coins = new LinkedHashMap<>();
            for (ItemData item : inventory) {
                if (item == null || classifyItem(item) != InventoryBagType.CURRENCY)
                    continue;
                String key = item.currencyId != null && !item.currencyId.isEmpty() ? item.currencyId : item.name;
                coins.merge(key, 1, Integer::sum);
            }
            n += coins.size();
        }
        return n;
    }

    public boolean isCurrencyOverCapacity(Map<String, Integer> contestCurrencies, Collection<ItemData> inventory) {
        return usedCurrencySlots(contestCurrencies, inventory) > currencySlots;
    }

    /** Whether the backpack has room for one more non-quest item (stack or free slot). */
    public boolean fitsBackpack(ItemData incoming, Collection<ItemData> inventory,
                                Set<Long> equippedIds, Map<String, String> toolbelt) {
        if (incoming == null)
            return false;
        if (isCapacityExempt(incoming))
            return true;
        List<ItemData> occ = backpackOccupants(inventory, equippedIds, toolbelt);
        occ.removeIf(InventoryBags::isCapacityExempt);
        int used = stackGroups(occ, backpackMaxStack).size();
        if (isStackable(incoming)) {
            int have = 0;
            for (ItemData i : occ) {
                if (i != null && incoming.name != null && incoming.name.equalsIgnoreCase(i.name))
                    have++;
            }
            if (have > 0 && have % backpackMaxStack != 0)
                return true;
            return used < backpackSlots;
        }
        return used < backpackSlots;
    }

    public boolean fitsBooster(Iterable<Deck> boosters) {
        return usedPackSlots(boosters) < packsSlots;
    }

    /**
     * How many units of {@code id} can still fit in the Craft Pouch given current contents.
     * Bottomless → Integer.MAX_VALUE/4. New types need a free slot.
     */
    public int materialRoom(String id, Map<String, Integer> materials) {
        if (id == null || id.isEmpty())
            return 0;
        if (getCraftPouchTier().isBottomless())
            return Integer.MAX_VALUE / 4;
        int have = materials != null && materials.get(id) != null ? materials.get(id) : 0;
        if (have <= 0 && usedMaterialSlots(materials) >= materialsSlots)
            return 0;
        return Math.max(0, materialsMaxStack - have);
    }

    public boolean fitsCurrencyItem(ItemData incoming, Map<String, Integer> contestCurrencies,
                                    Collection<ItemData> inventory) {
        if (incoming == null)
            return false;
        int used = usedCurrencySlots(contestCurrencies, inventory);
        if (isStackable(incoming)) {
            int have = 0;
            if (inventory != null) {
                for (ItemData i : inventory) {
                    if (i != null && incoming.name != null && incoming.name.equalsIgnoreCase(i.name))
                        have++;
                }
            }
            if (have > 0 && have % currencyMaxStack != 0)
                return true;
            if (have > 0)
                return true;
        }
        return used < currencySlots;
    }

    public boolean fitsContestCurrency(String id, int amount, Map<String, Integer> contestCurrencies,
                                       Collection<ItemData> inventory) {
        if (id == null || id.isEmpty() || amount <= 0)
            return false;
        return currencyRoom(id, contestCurrencies, inventory) >= amount;
    }

    /**
     * How many units of contest currency {@code id} can still fit in the Currency pouch.
     * New types need a free slot; known types are limited by {@code currencyMaxStack}.
     */
    public int currencyRoom(String id, Map<String, Integer> contestCurrencies,
                            Collection<ItemData> inventory) {
        if (id == null || id.isEmpty())
            return 0;
        int have = contestCurrencies != null && contestCurrencies.get(id) != null
                ? Math.max(0, contestCurrencies.get(id)) : 0;
        if (have <= 0 && usedCurrencySlots(contestCurrencies, inventory) >= currencySlots)
            return 0;
        return Math.max(0, currencyMaxStack - have);
    }

    // ---- Legacy canAccept* (now mean "fits in bag", not "may grant") ----

    public boolean canAcceptBackpackItem(ItemData incoming, Collection<ItemData> inventory,
                                         Set<Long> equippedIds, Map<String, String> toolbelt) {
        lastRefuseMessage = null;
        lastMessage = null;
        if (incoming == null) {
            setMsg("Unknown item.");
            return false;
        }
        if (fitsBackpack(incoming, inventory, equippedIds, toolbelt))
            return true;
        setMsg(MESSAGE_OVERFLOW);
        return false;
    }

    public boolean canAcceptBooster(Iterable<Deck> boosters) {
        lastRefuseMessage = null;
        lastMessage = null;
        if (fitsBooster(boosters))
            return true;
        setMsg(MESSAGE_OVERFLOW);
        return false;
    }

    public boolean canAcceptMaterial(String id, int amount, Map<String, Integer> materials) {
        lastRefuseMessage = null;
        lastMessage = null;
        if (id == null || id.isEmpty() || amount <= 0) {
            setMsg("Invalid material.");
            return false;
        }
        if (materialRoom(id, materials) >= amount)
            return true;
        setMsg(MESSAGE_OVERFLOW);
        return false;
    }

    public boolean canAcceptCurrencyItem(ItemData incoming, Map<String, Integer> contestCurrencies,
                                         Collection<ItemData> inventory) {
        lastRefuseMessage = null;
        lastMessage = null;
        if (incoming == null) {
            setMsg("Unknown currency item.");
            return false;
        }
        if (fitsCurrencyItem(incoming, contestCurrencies, inventory))
            return true;
        setMsg(MESSAGE_OVERFLOW);
        return false;
    }

    public boolean canAcceptContestCurrency(String id, int amount, Map<String, Integer> contestCurrencies,
                                            Collection<ItemData> inventory) {
        lastRefuseMessage = null;
        lastMessage = null;
        if (id == null || id.isEmpty() || amount <= 0) {
            setMsg("Invalid currency.");
            return false;
        }
        if (fitsContestCurrency(id, amount, contestCurrencies, inventory))
            return true;
        setMsg(MESSAGE_OVERFLOW);
        return false;
    }

    // ---- Overflow placement ----

    /**
     * Place an entry into Overflow if under cap. If at cap, try fortress hook then signal
     * auto-sell (caller performs the sale). Returns OVERFLOW or indicates AUTO_SOLD needed.
     */
    private Runnable overflowListener;

    /** Called whenever Overflow gains or loses entries (e.g. to refresh the overload slowdown). */
    public void setOverflowListener(Runnable listener) {
        overflowListener = listener;
    }

    private void overflowChanged() {
        if (overflowListener != null)
            overflowListener.run();
    }

    public GrantResult placeInOverflow(OverflowEntry entry) {
        if (entry == null)
            return GrantResult.accepted();
        // Never store empty material/currency stacks.
        if ((entry.kind == OverflowEntry.Kind.MATERIAL || entry.kind == OverflowEntry.Kind.CURRENCY)
                && entry.amount <= 0)
            return GrantResult.accepted();
        // Merge identical material / currency stacks into one Overflow slot.
        if (entry.kind == OverflowEntry.Kind.MATERIAL || entry.kind == OverflowEntry.Kind.CURRENCY) {
            for (OverflowEntry existing : overflow) {
                if (existing != null && existing.kind == entry.kind
                        && Objects.equals(existing.key, entry.key)) {
                    existing.amount += Math.max(1, entry.amount);
                    overflowChanged();
                    GrantResult r = GrantResult.overflow();
                    setMsg(r.message);
                    return r;
                }
            }
        }
        if (overflow.size() < getOverflowCap()) {
            overflow.add(entry);
            overflowChanged();
            GrantResult r = GrantResult.overflow();
            setMsg(r.message);
            return r;
        }
        // FT1: fortress storage may accept materials only (and only when a shed exists —
        // the hook itself is attached/detached by FortressService). Never swallow gear /
        // boosters / currency from Overflow until there is a retrieval UI.
        if (entry.kind == OverflowEntry.Kind.MATERIAL
                && fortressStorage.storeOverflow(InventoryBagType.MATERIALS, entry.key, entry.amount)) {
            GrantResult r = GrantResult.overflow();
            r.message = "Bag full — sent to fortress storage";
            setMsg(r.message);
            return r;
        }
        GrantResult r = new GrantResult();
        r.fate = GrantResult.Fate.AUTO_SOLD;
        r.autoSoldName = entry.displayName();
        r.message = "Overflow full — auto-sold " + entry.displayName();
        setMsg(r.message);
        return r;
    }

    public boolean removeOverflow(int index) {
        if (index < 0 || index >= overflow.size())
            return false;
        overflow.remove(index);
        overflowChanged();
        return true;
    }

    public OverflowEntry takeOverflow(int index) {
        if (index < 0 || index >= overflow.size())
            return null;
        OverflowEntry taken = overflow.remove(index);
        overflowChanged();
        return taken;
    }

    public void clearOverflow() {
        overflow.clear();
        overflowChanged();
    }

    /**
     * Restore Overflow from a save without auto-sell (cap may be raised afterward).
     * Same-material / same-currency entries are merged so old saves don't keep duplicate slots.
     */
    public void loadOverflowEntries(OverflowEntry[] entries) {
        overflow.clear();
        if (entries == null)
            return;
        for (OverflowEntry e : entries) {
            if (e == null)
                continue;
            if (e.kind == OverflowEntry.Kind.MATERIAL || e.kind == OverflowEntry.Kind.CURRENCY) {
                if (e.amount <= 0)
                    continue; // never restore zero-amount currency/material slots
                boolean merged = false;
                for (OverflowEntry existing : overflow) {
                    if (existing != null && existing.kind == e.kind
                            && Objects.equals(existing.key, e.key)) {
                        existing.amount += Math.max(1, e.amount);
                        merged = true;
                        break;
                    }
                }
                if (!merged)
                    overflow.add(e);
            } else {
                overflow.add(e);
            }
        }
    }

    /**
     * After loading an old save that is over capacity: move excess backpack/pack/currency/material
     * units into Overflow (overflow cap still applies — remainder becomes auto-sell candidates
     * returned to the caller).
     *
     * @return entries that could not fit in Overflow (caller should auto-sell)
     */
    public List<OverflowEntry> migrateOverCapacity(List<ItemData> inventory,
                                                  Set<Long> equippedIds,
                                                  Map<String, String> toolbelt,
                                                  List<Deck> boosters,
                                                  Map<String, Integer> materials,
                                                  Map<String, Integer> contestCurrencies) {
        List<OverflowEntry> autoSell = new ArrayList<>();
        if (inventory != null) {
            // Pull backpack occupants that exceed slot capacity into overflow (newest first).
            List<ItemData> occ = backpackOccupants(inventory, equippedIds, toolbelt);
            occ.removeIf(InventoryBags::isCapacityExempt);
            while (stackGroups(occ, backpackMaxStack).size() > backpackSlots && !occ.isEmpty()) {
                ItemData last = occ.remove(occ.size() - 1);
                inventory.remove(last);
                OverflowEntry e = OverflowEntry.ofItem(last);
                GrantResult r = placeInOverflow(e);
                if (r.wasAutoSold())
                    autoSell.add(e);
                occ = backpackOccupants(inventory, equippedIds, toolbelt);
                occ.removeIf(InventoryBags::isCapacityExempt);
            }
            // Currency items over capacity
            while (isCurrencyOverCapacity(contestCurrencies, inventory)) {
                ItemData coin = null;
                for (int i = inventory.size() - 1; i >= 0; i--) {
                    ItemData it = inventory.get(i);
                    if (it != null && classifyItem(it) == InventoryBagType.CURRENCY) {
                        coin = it;
                        break;
                    }
                }
                if (coin == null)
                    break;
                inventory.remove(coin);
                OverflowEntry e = OverflowEntry.ofItem(coin);
                GrantResult r = placeInOverflow(e);
                if (r.wasAutoSold())
                    autoSell.add(e);
            }
        }
        if (boosters != null) {
            while (usedPackSlots(boosters) > packsSlots && !boosters.isEmpty()) {
                Deck d = boosters.remove(boosters.size() - 1);
                OverflowEntry e = OverflowEntry.ofBooster(d);
                GrantResult r = placeInOverflow(e);
                if (r.wasAutoSold())
                    autoSell.add(e);
            }
        }
        if (materials != null && !getCraftPouchTier().isBottomless()) {
            // Trim stacks over max; overflow excess amount.
            List<String> ids = new ArrayList<>(materials.keySet());
            for (String id : ids) {
                int have = materials.getOrDefault(id, 0);
                if (have > materialsMaxStack) {
                    int excess = have - materialsMaxStack;
                    materials.put(id, materialsMaxStack);
                    OverflowEntry e = OverflowEntry.ofMaterial(id, excess);
                    GrantResult r = placeInOverflow(e);
                    if (r.wasAutoSold())
                        autoSell.add(e);
                }
            }
            // Trim distinct types over slot count.
            while (usedMaterialSlots(materials) > materialsSlots) {
                String victim = null;
                for (String id : materials.keySet()) {
                    if (materials.getOrDefault(id, 0) > 0)
                        victim = id;
                }
                if (victim == null)
                    break;
                int amt = materials.remove(victim);
                OverflowEntry e = OverflowEntry.ofMaterial(victim, amt);
                GrantResult r = placeInOverflow(e);
                if (r.wasAutoSold())
                    autoSell.add(e);
            }
        }
        return autoSell;
    }

    public String overCapacityMessage(InventoryBagType type) {
        return MESSAGE_OVERFLOW;
    }

    public String capacityLabel(InventoryBagType type, int used) {
        if (type == InventoryBagType.MATERIALS && getCraftPouchTier().isBottomless())
            return getCraftPouchTier().label + " · " + used + " types";
        int cap = getSlots(type);
        String base = used + "/" + cap;
        if (type == InventoryBagType.MATERIALS)
            base = getCraftPouchTier().label + " · " + base;
        if (type == InventoryBagType.OVERFLOW && used > 0)
            return "[#ffaa33]⚠[] " + base;
        return used > cap ? base + " OVER" : base;
    }

    /**
     * Group stackable items into stacks of at most maxStack; non-stackable = one group each.
     */
    public static List<List<ItemData>> stackGroups(List<ItemData> items, int maxStack) {
        List<List<ItemData>> groups = new ArrayList<>();
        if (items == null)
            return groups;
        int stack = Math.max(1, maxStack);
        LinkedHashMap<String, List<ItemData>> openStacks = new LinkedHashMap<>();
        for (ItemData item : items) {
            if (item == null)
                continue;
            if (!isStackable(item)) {
                List<ItemData> one = new ArrayList<>(1);
                one.add(item);
                groups.add(one);
                continue;
            }
            String key = item.name != null ? item.name.toLowerCase(Locale.ROOT) : "";
            List<ItemData> cur = openStacks.get(key);
            if (cur == null || cur.size() >= stack) {
                cur = new ArrayList<>();
                groups.add(cur);
                openStacks.put(key, cur);
            }
            cur.add(item);
        }
        return groups;
    }

    /** Save capacities + craft pouch tier + overflow for {@link forge.adventure.util.SaveFileData}. */
    public void store(Map<String, Object> out) {
        if (out == null)
            return;
        out.put("bagTypes", new String[]{
                InventoryBagType.BACKPACK.name(), InventoryBagType.PACKS.name(),
                InventoryBagType.CURRENCY.name(), InventoryBagType.MATERIALS.name()
        });
        out.put("bagSlots", new int[]{backpackSlots, packsSlots, currencySlots, materialsSlots});
        out.put("bagMaxStacks", new int[]{backpackMaxStack, packsMaxStack, currencyMaxStack, materialsMaxStack});
        out.put("craftPouchTier", craftPouchTier);
        out.put("overflowCap", overflowCap);
    }

    public void load(String[] types, int[] slots, int[] maxStacks, ConfigData defaults) {
        resetToDefaults(defaults);
        if (types == null || slots == null)
            return;
        for (int i = 0; i < types.length; i++) {
            InventoryBagType t;
            try {
                t = InventoryBagType.valueOf(types[i]);
            } catch (Exception e) {
                continue;
            }
            if (t == InventoryBagType.MATERIALS || t == InventoryBagType.OVERFLOW)
                continue; // pouch from craftPouchTier; overflow cap separate
            if (i < slots.length && slots[i] > 0)
                setSlots(t, slots[i]);
            if (maxStacks != null && i < maxStacks.length && maxStacks[i] > 0)
                setMaxStack(t, maxStacks[i]);
        }
    }

    public void loadCraftPouchAndOverflow(Integer tier, Integer cap) {
        if (tier != null)
            setCraftPouchTier(tier);
        else
            applyCraftPouchTierCapacities();
        if (cap != null && cap > 0)
            overflowCap = cap;
    }

    /** Snapshot for tests / UI. */
    public Map<InventoryBagType, int[]> snapshot() {
        LinkedHashMap<InventoryBagType, int[]> m = new LinkedHashMap<>();
        for (InventoryBagType t : InventoryBagType.values())
            m.put(t, new int[]{getSlots(t), getMaxStack(t)});
        return Collections.unmodifiableMap(m);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof InventoryBags)) return false;
        InventoryBags that = (InventoryBags) o;
        return backpackSlots == that.backpackSlots && backpackMaxStack == that.backpackMaxStack
                && packsSlots == that.packsSlots && packsMaxStack == that.packsMaxStack
                && currencySlots == that.currencySlots && currencyMaxStack == that.currencyMaxStack
                && materialsSlots == that.materialsSlots && materialsMaxStack == that.materialsMaxStack
                && craftPouchTier == that.craftPouchTier && overflowCap == that.overflowCap;
    }

    @Override
    public int hashCode() {
        return Objects.hash(backpackSlots, backpackMaxStack, packsSlots, packsMaxStack,
                currencySlots, currencyMaxStack, materialsSlots, materialsMaxStack,
                craftPouchTier, overflowCap);
    }
}
