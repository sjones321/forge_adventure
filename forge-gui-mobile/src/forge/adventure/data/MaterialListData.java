package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads and indexes {@code world/materials.json}. Stable lookup API for gathering (B/B2),
 * reagent crafting (A2), recipes (E), and town requests (H).
 */
public final class MaterialListData {
    private static Array<MaterialData> materialList;
    private static final ObjectMap<String, MaterialData> byId = new ObjectMap<>();
    /** Plane id the cache was loaded for; cleared/reloaded on world switch. */
    private static String loadedPlane;

    /**
     * Package A → color-line id renames. Applied on player load so counts are never lost.
     * Order matters where an old id is also a new id (e.g. marble): run chained migrations
     * via {@link #migrateMaterialCounts(Map)}.
     */
    private static final String[][] ID_MIGRATIONS = {
            // Sacred stone: old T3 marble becomes sunstone before granite takes the marble id.
            {"marble", "sunstone"},
            {"granite", "marble"},
            {"rough_stone", "limestone"},
            // Dead things (old swamp herbs line).
            {"nightshade", "bone_fragments"},
            {"bone", "ancient_bone"},
            // Old Delving crystal gather line → waters reagents (A2 prismatic_tN are separate).
            {"quartz", "spring_water"},
            {"azure", "glacier_ice"},
            {"prismatic", "purified_water"},
            // Note: "aether" stays as a rare crystal drop id (not migrated).
    };

    /** Old toolbelt family keys → current tool family keys. */
    private static final String[][] TOOL_FAMILY_MIGRATIONS = {
            {"stone", "sacred_stone"},
            {"herbs", "plants"},
            {"crystal", "waters"},
    };

    /**
     * materialSchema 3: ore line rename by tier (single-pass lookup, never chained).
     * copper→ore_iron, iron→ore_mithral, mithril→ore_adamant, adamant→ore_rune.
     */
    public static final int MATERIAL_SCHEMA_ORE_LINE = 3;

    private static final Map<String, String> ORE_LINE_ID_MAP;
    static {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("copper", "ore_iron");
        m.put("iron", "ore_mithral");
        m.put("mithril", "ore_adamant");
        m.put("adamant", "ore_rune");
        ORE_LINE_ID_MAP = Collections.unmodifiableMap(m);
    }

    /**
     * Tool / gear display-name renames for schema 3 (single-pass).
     * Old T1 Copper→Iron, T2 Iron→Mithral, T3 Mithril→Adamant, T4 Adamant→Rune;
     * classic "Mithril" armor spelling → "Mithral".
     */
    private static final Map<String, String> ORE_LINE_ITEM_NAME_MAP;
    static {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        String[] tools = {"Hatchet", "Pickaxe", "Chisel", "Sickle", "Probe", "Spanner"};
        for (String t : tools) {
            m.put("Copper " + t, "Iron " + t);
            m.put("Iron " + t, "Mithral " + t);
            m.put("Mithril " + t, "Adamant " + t);
            m.put("Adamant " + t, "Rune " + t);
        }
        m.put("Mithril Boots", "Mithral Boots");
        m.put("Mithril Shield", "Mithral Shield");
        m.put("Mithril Armor", "Mithral Armor");
        ORE_LINE_ITEM_NAME_MAP = Collections.unmodifiableMap(m);
    }

    private MaterialListData() {
    }

    /** Reload from disk (tests / hot-swap / world switch). Safe if the file or Config is missing. */
    public static void reload() {
        byId.clear();
        materialList = new Array<>();
        try {
            loadedPlane = Config.instance().getPlane();
            FileHandle handle = Config.instance().getFile(Paths.MATERIALS);
            if (handle == null || !handle.exists())
                return;
            Json json = new Json();
            Array<MaterialData> loaded = json.fromJson(Array.class, MaterialData.class, handle);
            if (loaded == null)
                return;
            materialList = loaded;
            for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
                if (m != null && m.id != null && !m.id.isEmpty())
                    byId.put(m.id, m);
            }
        } catch (Throwable ignored) {
            // Headless tests / early boot: leave the cache empty until Config is ready.
            // Catch Throwable — Config.<clinit> can throw ExceptionInInitializerError.
            loadedPlane = null;
            materialList = new Array<>();
        }
    }

    /** Reloads when the adventure plane no longer matches the cached data. */
    private static void ensureCurrentWorld() {
        try {
            String plane = Config.instance().getPlane();
            if (loadedPlane == null || !loadedPlane.equals(plane)) {
                forge.adventure.player.BanLists.clear();
                reload();
            }
        } catch (Throwable ignored) {
            if (materialList == null)
                materialList = new Array<>();
        }
    }

    public static MaterialData get(String id) {
        ensureCurrentWorld();
        if (id == null)
            return null;
        return byId.get(id);
    }

    public static Array<MaterialData> getAll() {
        ensureCurrentWorld();
        return materialList != null ? materialList : new Array<>();
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }

    /**
     * Maps overworld biome {@link BiomeData#name} (green/red/…) to materials.json {@code biome}
     * (forest/mountain/…). Returns null when the biome has no gather nodes.
     */
    public static String materialBiomeForWorldBiome(String worldBiomeName) {
        if (worldBiomeName == null)
            return null;
        switch (worldBiomeName.toLowerCase()) {
            case "green":
                return "forest";
            case "red":
                return "mountain";
            case "white":
                return "plains";
            case "black":
                return "swamp";
            case "blue":
                return "island";
            case "waste":
            case "wastes":
            case "colorless":
                return "wastes";
            default:
                return null;
        }
    }

    /** Gatherable node materials for a materials.json biome key (requires {@link MaterialData#isGatherNode()}). */
    public static Array<MaterialData> getGatherablesForBiome(String materialBiome) {
        ensureCurrentWorld();
        Array<MaterialData> out = new Array<>();
        if (materialBiome == null || materialBiome.isEmpty() || materialList == null)
            return out;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m != null && m.isGatherNode() && materialBiome.equalsIgnoreCase(m.biome))
                out.add(m);
        }
        return out;
    }

    /** Gatherable materials in a biome filtered by family (e.g. mountain {@code ore} vs {@code ash}). */
    public static Array<MaterialData> getGatherablesForBiomeFamily(String materialBiome, String family) {
        Array<MaterialData> out = new Array<>();
        if (family == null)
            return getGatherablesForBiome(materialBiome);
        for (MaterialData m : new Array.ArrayIterator<>(getGatherablesForBiome(materialBiome))) {
            if (family.equalsIgnoreCase(m.family))
                out.add(m);
        }
        return out;
    }

    /** All gem materials (Mining rare rolls). */
    public static Array<MaterialData> getGems() {
        return byFamily("gems");
    }

    /** Crystal materials (Delving rare rolls). */
    public static Array<MaterialData> getCrystals() {
        return byFamily("crystal");
    }

    /** Pearl materials (Delving rare rolls). */
    public static Array<MaterialData> getPearls() {
        return byFamily("pearls");
    }

    /** Prismatic reagents (A2 craft inputs / outputs); not gatherable. */
    public static Array<MaterialData> getPrismaticReagents() {
        return byFamily("prismatic");
    }

    /**
     * Primary gather-line family for a mana color letter (W/U/B/R/G/C).
     * Used as the preferred reagent when several materials share a color+tier.
     */
    public static String primaryFamilyForColor(String color) {
        if (color == null || color.isEmpty())
            return "";
        switch (color.trim().toUpperCase()) {
            case "W":
                return "sacred_stone";
            case "U":
                return "waters";
            case "B":
                return "dead";
            case "R":
                return "ash";
            case "G":
                return "plants";
            case "C":
                return "ore";
            default:
                return "";
        }
    }

    /**
     * All materials that can pay a reagent of this mana color at this tier
     * (primary line plus drop alts like feathers/hide/brine). Excludes prismatic.
     */
    public static Array<MaterialData> reagentsForColorTier(String color, int tier) {
        ensureCurrentWorld();
        Array<MaterialData> out = new Array<>();
        if (color == null || color.isEmpty() || materialList == null)
            return out;
        String c = color.trim().toUpperCase();
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m == null || m.id == null)
                continue;
            if (m.tier != tier)
                continue;
            if (m.color == null || !c.equalsIgnoreCase(m.color.trim()))
                continue;
            if ("prismatic".equalsIgnoreCase(m.family))
                continue;
            out.add(m);
        }
        // Prefer primary gather line first for stable UI labels / payment order.
        String primary = primaryFamilyForColor(c);
        if (!primary.isEmpty()) {
            out.sort((a, b) -> {
                boolean ap = primary.equalsIgnoreCase(a.family);
                boolean bp = primary.equalsIgnoreCase(b.family);
                if (ap != bp)
                    return ap ? -1 : 1;
                String an = a.getDisplayName();
                String bn = b.getDisplayName();
                return an.compareToIgnoreCase(bn);
            });
        }
        return out;
    }

    /** Preferred reagent for a color+tier (primary family, else first alt). */
    public static MaterialData primaryReagent(String color, int tier) {
        Array<MaterialData> all = reagentsForColorTier(color, tier);
        return all.size > 0 ? all.get(0) : null;
    }

    /** Ore of the given tier, or null. */
    public static MaterialData oreForTier(int tier) {
        return firstInFamilyTier("ore", tier);
    }

    /** Scrap of the given tier (can replace ore for colorless card costs). */
    public static MaterialData scrapForTier(int tier) {
        return firstInFamilyTier("scrap", tier);
    }

    /** Prismatic reagent of the given tier (A2 any-color mana sources / crafted). */
    public static MaterialData prismaticForTier(int tier) {
        return firstInFamilyTier("prismatic", tier);
    }

    private static MaterialData firstInFamilyTier(String family, int tier) {
        ensureCurrentWorld();
        if (family == null || materialList == null)
            return null;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m != null && family.equalsIgnoreCase(m.family) && m.tier == tier)
                return m;
        }
        return null;
    }

    /**
     * Card rarity → reagent tier: common→1, uncommon→2, rare/special→3, mythic→4.
     * Returns 0 when the rarity is not craftable as dust.
     */
    public static int reagentTierForRarity(forge.card.CardRarity rarity) {
        if (rarity == null)
            return 0;
        return switch (rarity) {
            case Common -> 1;
            case Uncommon -> 2;
            case Rare, Special -> 3;
            case MythicRare -> 4;
            default -> 0;
        };
    }

    private static Array<MaterialData> byFamily(String family) {
        ensureCurrentWorld();
        Array<MaterialData> out = new Array<>();
        if (materialList == null || family == null)
            return out;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m != null && family.equalsIgnoreCase(m.family))
                out.add(m);
        }
        return out;
    }

    /** Material of the given family and tier, or null. Prefers gatherable nodes. */
    public static MaterialData getFamilyTier(String family, int tier) {
        if (family == null || materialList == null)
            return null;
        MaterialData fallback = null;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m == null || m.family == null || m.tier != tier)
                continue;
            if (!family.equalsIgnoreCase(m.family))
                continue;
            if (m.isGatherNode())
                return m;
            if (fallback == null)
                fallback = m;
        }
        return fallback;
    }

    /** Next higher-tier material in the same family, or null at the top. */
    public static MaterialData nextTierInFamily(MaterialData mat) {
        if (mat == null || mat.family == null)
            return null;
        return getFamilyTier(mat.family, mat.tier + 1);
    }

    /**
     * Rewrites material id→count map in place for Package A → color-line renames.
     * Applies {@link #ID_MIGRATIONS} as sequential whole-map passes so
     * {@code marble→sunstone} then {@code granite→marble} does not chain granite to sunstone.
     * Unknown ids after migration are left as-is (UI ignores missing definitions).
     */
    public static void migrateMaterialCounts(Map<String, Integer> materials) {
        if (materials == null || materials.isEmpty())
            return;
        for (String[] pair : ID_MIGRATIONS) {
            Integer moved = materials.remove(pair[0]);
            if (moved == null || moved <= 0)
                continue;
            materials.merge(pair[1], moved, Integer::sum);
        }
        // Drop non-positive entries.
        materials.entrySet().removeIf(e -> e.getKey() == null || e.getKey().isEmpty()
                || e.getValue() == null || e.getValue() <= 0);
    }

    /** Single-id migration (one rename step; for nodes / rewards referencing old ids). */
    public static String migrateMaterialId(String id) {
        if (id == null)
            return null;
        for (String[] pair : ID_MIGRATIONS) {
            if (pair[0].equals(id))
                return pair[1];
        }
        return id;
    }

    /**
     * Rewrites toolbelt family→toolName keys for renamed tool families.
     * Values (item names) are unchanged.
     */
    public static void migrateToolbeltFamilies(Map<String, String> toolbelt) {
        if (toolbelt == null || toolbelt.isEmpty())
            return;
        LinkedHashMap<String, String> next = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : toolbelt.entrySet()) {
            String family = e.getKey();
            String tool = e.getValue();
            if (family == null || family.isEmpty() || tool == null || tool.isEmpty())
                continue;
            for (String[] pair : TOOL_FAMILY_MIGRATIONS) {
                if (pair[0].equals(family)) {
                    family = pair[1];
                    break;
                }
            }
            // Prefer keeping an already-migrated entry if both old and new keys existed.
            next.putIfAbsent(family, tool);
        }
        toolbelt.clear();
        toolbelt.putAll(next);
    }

    /**
     * Single-pass ore-line id rewrite (schema 3). Looks up {@code id} once in the old→new
     * table (exact match, or {@code old_} / {@code _old} derived forms). Never applies the
     * table repeatedly, so old {@code iron} becomes {@code ore_mithral} and stops.
     */
    public static String migrateOreLineMaterialId(String id) {
        if (id == null || id.isEmpty())
            return id;
        String exact = ORE_LINE_ID_MAP.get(id);
        if (exact != null)
            return exact;
        // Already on the new ore_* line (exact or derived) — do not treat "_iron" as old iron.
        if (id.startsWith("ore_"))
            return id;
        for (Map.Entry<String, String> e : ORE_LINE_ID_MAP.entrySet()) {
            String old = e.getKey();
            String neu = e.getValue();
            if (id.startsWith(old + "_"))
                return neu + id.substring(old.length());
            if (id.endsWith("_" + old))
                return id.substring(0, id.length() - old.length()) + neu;
        }
        return id;
    }

    /**
     * Rewrites a material id→count map for schema 3 ore renames in one pass.
     * Builds a next map from migrated keys so old {@code iron} cannot chain into {@code ore_adamant}.
     */
    public static void migrateOreLineMaterialCounts(Map<String, Integer> materials) {
        if (materials == null || materials.isEmpty())
            return;
        LinkedHashMap<String, Integer> next = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : materials.entrySet()) {
            String id = e.getKey();
            Integer count = e.getValue();
            if (id == null || id.isEmpty() || count == null || count <= 0)
                continue;
            String migrated = migrateOreLineMaterialId(id);
            next.merge(migrated, count, Integer::sum);
        }
        materials.clear();
        materials.putAll(next);
    }

    /** Float-valued stock maps (outpost / camp storage). Single-pass, same tier rules. */
    public static void migrateOreLineMaterialFloatCounts(Map<String, Float> materials) {
        if (materials == null || materials.isEmpty())
            return;
        LinkedHashMap<String, Float> next = new LinkedHashMap<>();
        for (Map.Entry<String, Float> e : materials.entrySet()) {
            String id = e.getKey();
            Float amount = e.getValue();
            if (id == null || id.isEmpty() || amount == null || amount <= 0f)
                continue;
            String migrated = migrateOreLineMaterialId(id);
            next.merge(migrated, amount, Float::sum);
        }
        materials.clear();
        materials.putAll(next);
    }

    /** Single-pass item display-name migration for schema 3 tool/gear tiers. */
    public static String migrateOreLineItemName(String name) {
        if (name == null || name.isEmpty())
            return name;
        String mapped = ORE_LINE_ITEM_NAME_MAP.get(name);
        return mapped != null ? mapped : name;
    }

    /** Rewrites toolbelt equipped tool names for schema 3. */
    public static void migrateOreLineToolbeltItems(Map<String, String> toolbelt) {
        if (toolbelt == null || toolbelt.isEmpty())
            return;
        for (Map.Entry<String, String> e : new ArrayList<>(toolbelt.entrySet())) {
            String tool = e.getValue();
            String migrated = migrateOreLineItemName(tool);
            if (migrated != null && !migrated.equals(tool))
                toolbelt.put(e.getKey(), migrated);
        }
    }

    /**
     * Migrates {@code nodeMaterialIds} inside a world-stage {@link SaveFileData} (live or
     * inactive plane blob). Safe to call repeatedly: already-new ids are unchanged.
     *
     * @return true when any id changed
     */
    @SuppressWarnings("unchecked")
    public static boolean migrateWorldStageNodeMaterialIds(SaveFileData stage) {
        if (stage == null || !stage.containsKey("nodeMaterialIds"))
            return false;
        Object raw = stage.readObject("nodeMaterialIds");
        if (!(raw instanceof List))
            return false;
        List<String> mats = (List<String>) raw;
        boolean changed = false;
        for (int i = 0; i < mats.size(); i++) {
            String id = mats.get(i);
            String migrated = migrateOreLineMaterialId(id);
            if (migrated != null && !migrated.equals(id)) {
                mats.set(i, migrated);
                changed = true;
            }
        }
        if (changed)
            stage.storeObject("nodeMaterialIds", mats);
        return changed;
    }

    /**
     * Renames a saved item instance for schema 3 without rebuilding it from the catalog.
     * Preserves per-instance state ({@code effect}, {@code isCracked}, {@code isEquipped},
     * {@code longID}, dialogs, usability flags). Catalog fields (icon, description, tool
     * tier/family, cost) are refreshed from the renamed definition when present.
     */
    public static void migrateOreLineItemInstance(ItemData item) {
        if (item == null || item.name == null || item.name.isEmpty())
            return;
        String migrated = migrateOreLineItemName(item.name);
        if (migrated.equals(item.name))
            return;
        item.name = migrated;
        ItemData canonical = ItemListData.getItem(migrated);
        if (canonical == null)
            return;
        if (canonical.iconName != null)
            item.iconName = canonical.iconName;
        if (canonical.description != null)
            item.description = canonical.description;
        if (canonical.toolFamily != null)
            item.toolFamily = canonical.toolFamily;
        if (canonical.toolTier > 0)
            item.toolTier = canonical.toolTier;
        if (canonical.equipmentSlot != null)
            item.equipmentSlot = canonical.equipmentSlot;
        item.cost = canonical.cost;
        item.questItem = canonical.questItem;
        item.stackable = canonical.stackable;
        if (canonical.currencyId != null)
            item.currencyId = canonical.currencyId;
        if (canonical.bagUpgrade != null)
            item.bagUpgrade = canonical.bagUpgrade;
        item.bagBonusSlots = canonical.bagBonusSlots;
        item.bagBonusStack = canonical.bagBonusStack;
    }

    /** Unmodifiable view of the schema-3 ore id table (tests / docs). */
    public static Map<String, String> oreLineIdMap() {
        return ORE_LINE_ID_MAP;
    }

    /** Unmodifiable view of the schema-3 item name table (tests). */
    public static Map<String, String> oreLineItemNameMap() {
        return ORE_LINE_ITEM_NAME_MAP;
    }
}
