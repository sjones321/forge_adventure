package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ascendant: per-biome looks for gather nodes (world/node_variants.json).
 * Maps a material family (e.g. logs) and a materials.json biome key (forest, plains, ...) to atlas regions.
 * The pick is deterministic per tile so a saved or co-op-mirrored node keeps the same look.
 */
public final class NodeVariants {
    private static Map<String, Map<String, List<String>>> variants;

    private NodeVariants() {
    }

    private static synchronized Map<String, Map<String, List<String>>> data() {
        if (variants != null)
            return variants;
        variants = new HashMap<>();
        try {
            FileHandle file = Config.instance().getFile(Paths.NODE_VARIANTS);
            if (file == null || !file.exists())
                return variants;
            JsonValue root = new JsonReader().parse(file);
            for (JsonValue family = root.child; family != null; family = family.next) {
                Map<String, List<String>> byBiome = new HashMap<>();
                for (JsonValue biome = family.child; biome != null; biome = biome.next) {
                    List<String> regions = new ArrayList<>();
                    for (JsonValue r = biome.child; r != null; r = r.next)
                        regions.add(r.asString());
                    if (!regions.isEmpty())
                        byBiome.put(biome.name.toLowerCase(), regions);
                }
                variants.put(family.name.toLowerCase(), byBiome);
            }
        } catch (Exception ignored) {
            // No variants: nodes keep their material's own region.
        }
        return variants;
    }

    /** The atlas region for this material at world position (x, y), or null to keep the material's own. */
    public static String regionFor(MaterialData material, World world, float x, float y) {
        if (material == null || material.family == null || world == null)
            return null;
        Map<String, List<String>> byBiome = data().get(material.family.toLowerCase());
        if (byBiome == null)
            return null;
        try {
            int tx = (int) (x / world.getTileSize());
            int ty = (int) (y / world.getTileSize());
            int biomeIndex = World.highestBiome(world.getBiome(tx, ty));
            List<BiomeData> biomes = world.getData().GetBiomes();
            if (biomeIndex < 0 || biomeIndex >= biomes.size() || biomes.get(biomeIndex) == null)
                return null;
            String key = MaterialListData.materialBiomeForWorldBiome(biomes.get(biomeIndex).name);
            List<String> regions = key != null ? byBiome.get(key) : null;
            if (regions == null || regions.isEmpty())
                return null;
            int pick = Math.floorMod(tx * 73856093 ^ ty * 19349663, regions.size());
            return regions.get(pick);
        } catch (Exception e) {
            return null;
        }
    }
}
