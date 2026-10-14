package forge.ascendant.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Generated plane (stage-1 stores full grids; later becomes skeleton + chunk noise). */
public final class PlaneData {
    public final long seed;
    public final PlaneProfile profile;
    public final int size;
    public final byte[] terrain;
    public final byte[] biome;
    public final List<Site> sites;
    public final int spawnX;
    public final int spawnY;

    public PlaneData(long seed, PlaneProfile profile, byte[] terrain, byte[] biome,
                     List<Site> sites, int spawnX, int spawnY) {
        this.seed = seed;
        this.profile = profile;
        this.size = profile.size;
        this.terrain = terrain;
        this.biome = biome;
        this.sites = Collections.unmodifiableList(new ArrayList<>(sites));
        this.spawnX = spawnX;
        this.spawnY = spawnY;
    }

    public int index(int x, int y) {
        return y * size + x;
    }

    public byte terrainAt(int x, int y) {
        if (x < 0 || y < 0 || x >= size || y >= size) {
            return Terrain.OCEAN;
        }
        return terrain[index(x, y)];
    }

    public byte biomeAt(int x, int y) {
        if (x < 0 || y < 0 || x >= size || y >= size) {
            return BiomeId.BLUE;
        }
        return biome[index(x, y)];
    }

    public boolean blocks(int x, int y) {
        return Terrain.blocksMovement(terrainAt(x, y));
    }

    public Site siteAt(int x, int y) {
        for (Site s : sites) {
            if (s.containsTile(x, y)) {
                return s;
            }
        }
        return null;
    }
}
