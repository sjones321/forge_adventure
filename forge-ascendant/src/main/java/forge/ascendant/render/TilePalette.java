package forge.ascendant.render;

import com.badlogic.gdx.graphics.Color;
import forge.ascendant.world.BiomeId;
import forge.ascendant.world.Terrain;

/** Stage-1 palette: solid biome-tinted tiles (Ninja sprites overlay for towns). */
public final class TilePalette {
    private TilePalette() {}

    public static Color color(byte terrain, byte biome) {
        if (terrain == Terrain.OCEAN) {
            return new Color(0.15f, 0.28f, 0.55f, 1f);
        }
        if (terrain == Terrain.WATER) {
            return new Color(0.25f, 0.45f, 0.70f, 1f);
        }
        if (terrain == Terrain.BRIDGE) {
            return new Color(0.45f, 0.32f, 0.18f, 1f);
        }
        if (terrain == Terrain.ROAD) {
            return new Color(0.55f, 0.48f, 0.35f, 1f);
        }
        if (terrain == Terrain.HOUSE) {
            return new Color(0.35f, 0.22f, 0.18f, 1f);
        }
        if (terrain == Terrain.TOWN) {
            return new Color(0.50f, 0.50f, 0.42f, 1f);
        }
        if (terrain == Terrain.TREE) {
            return tint(biome, 0.12f, 0.38f, 0.16f);
        }
        if (terrain == Terrain.ROCK || terrain == Terrain.RIDGE) {
            return tint(biome, 0.40f, 0.38f, 0.36f);
        }
        // grass
        return switch (biome) {
            case BiomeId.WHITE -> new Color(0.55f, 0.72f, 0.38f, 1f);
            case BiomeId.BLUE -> new Color(0.40f, 0.62f, 0.48f, 1f);
            case BiomeId.BLACK -> new Color(0.28f, 0.40f, 0.28f, 1f);
            case BiomeId.RED -> new Color(0.48f, 0.42f, 0.28f, 1f);
            case BiomeId.GREEN -> new Color(0.22f, 0.52f, 0.24f, 1f);
            default -> new Color(0.50f, 0.55f, 0.35f, 1f); // wastes
        };
    }

    private static Color tint(byte biome, float r, float g, float b) {
        Color base = color(Terrain.GRASS, biome);
        return new Color(
                (base.r + r) * 0.5f,
                (base.g + g) * 0.5f,
                (base.b + b) * 0.5f,
                1f);
    }
}
