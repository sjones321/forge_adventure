package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads and indexes {@code world/materials.json}. Stable lookup API for gathering (B/B2),
 * reagent crafting (A2), recipes (E), and town requests (H).
 */
public final class MaterialListData {
    private static Array<MaterialData> materialList;
    private static final ObjectMap<String, MaterialData> byId = new ObjectMap<>();

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

    static {
        reload();
    }

    private MaterialListData() {
    }

    /** Reload from disk (tests / hot-swap). Safe if the file is missing. */
    public static void reload() {
        byId.clear();
        materialList = new Array<>();
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
    }

    public static MaterialData get(String id) {
        if (id == null)
            return null;
        return byId.get(id);
    }

    public static Array<MaterialData> getAll() {
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

    private static Array<MaterialData> byFamily(String family) {
        Array<MaterialData> out = new Array<>();
        if (materialList == null || family == null)
            return out;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m != null && family.equalsIgnoreCase(m.family))
                out.add(m);
        }
        return out;
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
}
