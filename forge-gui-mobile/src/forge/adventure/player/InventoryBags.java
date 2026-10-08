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
 * Ascendant INV1 bag capacities and grant checks. Contents stay on {@link AdventurePlayer}
 * (inventoryItems, boosters, materials, currencies); this class tracks slot/stack limits,
 * classifies items, and never deletes on overflow.
 * <p>
 * Pure enough for headless tests — pass a {@link ConfigData} (or defaults).
 */
public class InventoryBags implements Serializable {
    private static final long serialVersionUID = 1L;

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
    private int materialsSlots;
    private int materialsMaxStack;

    private transient FortressStorageHook fortressStorage = FortressStorageHook.NONE;
    private String lastRefuseMessage;

    public InventoryBags() {
        this(new ConfigData());
    }

    public InventoryBags(ConfigData cfg) {
        resetToDefaults(cfg);
    }

    public void resetToDefaults(ConfigData cfg) {
        ConfigData c = cfg != null ? cfg : new ConfigData();
        backpackSlots = Math.max(1, c.backpackSlots);
        backpackMaxStack = Math.max(1, c.backpackMaxStack);
        packsSlots = Math.max(1, c.packsSlots);
        packsMaxStack = Math.max(1, c.packsMaxStack);
        currencySlots = Math.max(1, c.currencySlots);
        currencyMaxStack = Math.max(1, c.currencyMaxStack);
        materialsSlots = Math.max(1, c.materialsSlots);
        materialsMaxStack = Math.max(1, c.materialsMaxStack);
        lastRefuseMessage = null;
    }

    public void setFortressStorage(FortressStorageHook hook) {
        fortressStorage = hook != null ? hook : FortressStorageHook.NONE;
    }

    public FortressStorageHook getFortressStorage() {
        return fortressStorage;
    }

    public String getLastRefuseMessage() {
        return lastRefuseMessage;
    }

    public int getSlots(InventoryBagType type) {
        switch (type) {
            case BACKPACK: return backpackSlots;
            case PACKS: return packsSlots;
            case CURRENCY: return currencySlots;
            case MATERIALS: return materialsSlots;
            default: return 0;
        }
    }

    public int getMaxStack(InventoryBagType type) {
        switch (type) {
            case BACKPACK: return backpackMaxStack;
            case PACKS: return packsMaxStack;
            case CURRENCY: return currencyMaxStack;
            case MATERIALS: return materialsMaxStack;
            default: return 1;
        }
    }

    public void setSlots(InventoryBagType type, int slots) {
        slots = Math.max(1, slots);
        switch (type) {
            case BACKPACK: backpackSlots = slots; break;
            case PACKS: packsSlots = slots; break;
            case CURRENCY: currencySlots = slots; break;
            case MATERIALS: materialsSlots = slots; break;
            default: break;
        }
    }

    public void setMaxStack(InventoryBagType type, int maxStack) {
        maxStack = Math.max(1, maxStack);
        switch (type) {
            case BACKPACK: backpackMaxStack = maxStack; break;
            case PACKS: packsMaxStack = maxStack; break;
            case CURRENCY: currencyMaxStack = maxStack; break;
            case MATERIALS: materialsMaxStack = maxStack; break;
            default: break;
        }
    }

    public void applyUpgrade(ItemData upgrade) {
        if (upgrade == null || upgrade.bagUpgrade == null || upgrade.bagUpgrade.isEmpty())
            return;
        InventoryBagType type = parseBagUpgrade(upgrade.bagUpgrade);
        if (type == null)
            return;
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
            case "materials": case "material": case "sack": return InventoryBagType.MATERIALS;
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

    /** Distinct backpack slots used after stacking. May exceed capacity (over-capacity display). */
    public int usedBackpackSlots(Collection<ItemData> inventory, Set<Long> equippedIds, Map<String, String> toolbelt) {
        return stackGroups(backpackOccupants(inventory, equippedIds, toolbelt), backpackMaxStack).size();
    }

    public boolean isBackpackOverCapacity(Collection<ItemData> inventory, Set<Long> equippedIds, Map<String, String> toolbelt) {
        return usedBackpackSlots(inventory, equippedIds, toolbelt) > backpackSlots;
    }

    public int usedPackSlots(Collection<Deck> boosters) {
        if (boosters == null || boosters.isEmpty())
            return 0;
        // Packs do not stack by identity (each Deck is unique); one slot per pack.
        return boosters.size();
    }

    public boolean isPacksOverCapacity(Collection<Deck> boosters) {
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
        if (usedMaterialSlots(materials) > materialsSlots)
            return true;
        for (Integer c : materials.values()) {
            if (c != null && c > materialsMaxStack)
                return true;
        }
        return false;
    }

    /**
     * Currency pouch slots: built-ins that are shown + contest currencies with amount &gt; 0
     * + challenge-coin stacks in inventory.
     */
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

    /**
     * Whether one more backpack item can be accepted. Over-capacity bags keep existing items;
     * new grants are refused (or sent to fortress storage).
     */
    public boolean canAcceptBackpackItem(ItemData incoming, Collection<ItemData> inventory,
                                         Set<Long> equippedIds, Map<String, String> toolbelt) {
        lastRefuseMessage = null;
        if (incoming == null) {
            lastRefuseMessage = "Unknown item.";
            return false;
        }
        List<ItemData> occ = backpackOccupants(inventory, equippedIds, toolbelt);
        int used = stackGroups(occ, backpackMaxStack).size();
        if (isStackable(incoming)) {
            int have = 0;
            for (ItemData i : occ) {
                if (i != null && incoming.name != null && incoming.name.equalsIgnoreCase(i.name))
                    have++;
            }
            // Room in an existing stack?
            if (have > 0 && have % backpackMaxStack != 0)
                return true;
            if (used < backpackSlots)
                return true;
            if (fortressStorage.storeOverflow(InventoryBagType.BACKPACK, incoming.name, 1))
                return true;
            lastRefuseMessage = overCapacityMessage(InventoryBagType.BACKPACK);
            return false;
        }
        if (used < backpackSlots)
            return true;
        if (fortressStorage.storeOverflow(InventoryBagType.BACKPACK, incoming.name, 1))
            return true;
        lastRefuseMessage = overCapacityMessage(InventoryBagType.BACKPACK);
        return false;
    }

    public boolean canAcceptBooster(Collection<Deck> boosters) {
        lastRefuseMessage = null;
        int used = usedPackSlots(boosters);
        if (used < packsSlots)
            return true;
        if (fortressStorage.storeOverflow(InventoryBagType.PACKS, "booster", 1))
            return true;
        lastRefuseMessage = overCapacityMessage(InventoryBagType.PACKS);
        return false;
    }

    public boolean canAcceptMaterial(String id, int amount, Map<String, Integer> materials) {
        lastRefuseMessage = null;
        if (id == null || id.isEmpty() || amount <= 0) {
            lastRefuseMessage = "Invalid material.";
            return false;
        }
        int have = materials != null && materials.get(id) != null ? materials.get(id) : 0;
        boolean known = have > 0;
        if (!known && usedMaterialSlots(materials) >= materialsSlots) {
            if (fortressStorage.storeOverflow(InventoryBagType.MATERIALS, id, amount))
                return true;
            lastRefuseMessage = overCapacityMessage(InventoryBagType.MATERIALS);
            return false;
        }
        if (have >= materialsMaxStack) {
            // Already over or at cap from an old save: refuse more (never delete existing).
            if (fortressStorage.storeOverflow(InventoryBagType.MATERIALS, id, amount))
                return true;
            lastRefuseMessage = "Materials sack full for this type (max " + materialsMaxStack + ").";
            return false;
        }
        if (have + amount > materialsMaxStack) {
            // Partial fill is not done here — caller should split, or we refuse whole grant.
            if (fortressStorage.storeOverflow(InventoryBagType.MATERIALS, id, amount))
                return true;
            lastRefuseMessage = "Materials sack would exceed stack size (" + materialsMaxStack + ").";
            return false;
        }
        return true;
    }

    /**
     * Challenge-coin ItemData in the currency pouch (stackable by name).
     */
    public boolean canAcceptCurrencyItem(ItemData incoming, Map<String, Integer> contestCurrencies,
                                         Collection<ItemData> inventory) {
        lastRefuseMessage = null;
        if (incoming == null) {
            lastRefuseMessage = "Unknown currency item.";
            return false;
        }
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
            // Existing stack type already occupies a slot.
            if (have > 0)
                return true;
        }
        if (used < currencySlots)
            return true;
        if (fortressStorage.storeOverflow(InventoryBagType.CURRENCY, incoming.name, 1))
            return true;
        lastRefuseMessage = overCapacityMessage(InventoryBagType.CURRENCY);
        return false;
    }

    public boolean canAcceptContestCurrency(String id, int amount, Map<String, Integer> contestCurrencies,
                                            Collection<ItemData> inventory) {
        lastRefuseMessage = null;
        if (id == null || id.isEmpty() || amount <= 0) {
            lastRefuseMessage = "Invalid currency.";
            return false;
        }
        int have = contestCurrencies != null && contestCurrencies.get(id) != null ? contestCurrencies.get(id) : 0;
        boolean known = have > 0;
        if (!known && usedCurrencySlots(contestCurrencies, inventory) >= currencySlots) {
            if (fortressStorage.storeOverflow(InventoryBagType.CURRENCY, id, amount))
                return true;
            lastRefuseMessage = overCapacityMessage(InventoryBagType.CURRENCY);
            return false;
        }
        if (have + amount > currencyMaxStack) {
            if (fortressStorage.storeOverflow(InventoryBagType.CURRENCY, id, amount))
                return true;
            lastRefuseMessage = "Currency pouch stack limit (" + currencyMaxStack + ").";
            return false;
        }
        return true;
    }

    public String overCapacityMessage(InventoryBagType type) {
        return type.label + " is full (" + getSlots(type) + " slots). Free space or upgrade the bag."
                + " Fortress storage is not available yet.";
    }

    public String capacityLabel(InventoryBagType type, int used) {
        int cap = getSlots(type);
        String base = used + "/" + cap;
        return used > cap ? base + " OVER" : base;
    }

    /**
     * Group stackable items into stacks of at most maxStack; non-stackable = one group each.
     * Returns one entry per occupied slot.
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

    /** Save capacities as parallel arrays for {@link forge.adventure.util.SaveFileData}. */
    public void store(Map<String, Object> out) {
        if (out == null)
            return;
        out.put("bagTypes", new String[]{
                InventoryBagType.BACKPACK.name(), InventoryBagType.PACKS.name(),
                InventoryBagType.CURRENCY.name(), InventoryBagType.MATERIALS.name()
        });
        out.put("bagSlots", new int[]{backpackSlots, packsSlots, currencySlots, materialsSlots});
        out.put("bagMaxStacks", new int[]{backpackMaxStack, packsMaxStack, currencyMaxStack, materialsMaxStack});
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
            if (i < slots.length && slots[i] > 0)
                setSlots(t, slots[i]);
            if (maxStacks != null && i < maxStacks.length && maxStacks[i] > 0)
                setMaxStack(t, maxStacks[i]);
        }
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
                && materialsSlots == that.materialsSlots && materialsMaxStack == that.materialsMaxStack;
    }

    @Override
    public int hashCode() {
        return Objects.hash(backpackSlots, backpackMaxStack, packsSlots, packsMaxStack,
                currencySlots, currencyMaxStack, materialsSlots, materialsMaxStack);
    }
}
