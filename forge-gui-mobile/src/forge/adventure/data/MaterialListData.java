package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Loads and indexes {@code world/materials.json}. Stable lookup API for later packages.
 */
public final class MaterialListData {
    private static Array<MaterialData> materialList;
    private static final ObjectMap<String, MaterialData> byId = new ObjectMap<>();

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

    /** Gatherable materials for a materials.json biome key (excludes gems/boss/hide drop-only). */
    public static Array<MaterialData> getGatherablesForBiome(String materialBiome) {
        Array<MaterialData> out = new Array<>();
        if (materialBiome == null || materialBiome.isEmpty() || materialList == null)
            return out;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m == null || m.id == null)
                continue;
            if (materialBiome.equalsIgnoreCase(m.biome) && m.skill != null && !m.skill.isEmpty()
                    && !"gems".equalsIgnoreCase(m.family) && !"boss".equalsIgnoreCase(m.family)
                    && !"hide".equalsIgnoreCase(m.family))
                out.add(m);
        }
        return out;
    }

    /** All gem materials (Mining rare rolls). */
    public static Array<MaterialData> getGems() {
        Array<MaterialData> out = new Array<>();
        if (materialList == null)
            return out;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m != null && "gems".equalsIgnoreCase(m.family))
                out.add(m);
        }
        return out;
    }
}
