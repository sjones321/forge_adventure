package forge.ascendant.world;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Stage-1 Java port of the WG1 home-plane generator — enough to walk at real scale.
 * Knob names match profiles/home_shandalar.json / the Python prototype.
 */
public final class PlaneGenerator {
    private final PlaneProfile profile;
    private final Path mapsRoot;

    public PlaneGenerator(PlaneProfile profile, Path mapsRoot) {
        this.profile = profile;
        this.mapsRoot = mapsRoot;
    }

    public PlaneData generate(long seed) throws IOException {
        final int n = profile.size;
        final int cx = n / 2;
        final int cy = n / 2;
        byte[] biome = new byte[n * n];
        byte[] terrain = new byte[n * n];
        HashNoise noise = new HashNoise(seed);

        layoutBiomes(biome, noise, n, cx, cy);
        paintSeaAndCoast(terrain, biome, noise, n, cx, cy);
        paintObstacles(terrain, biome, noise, n, cx, cy);
        paintRivers(terrain, n, cx, cy, seed);

        List<Site> sites = new ArrayList<>();
        Site home = stampHome(terrain, sites, n, cx, cy);
        Site plains = stampPlainsTown(terrain, biome, sites, n, cx, cy, seed);
        // Road from Havenbrook's south gate (low y after TMX flip) toward plains centre
        int homeGateX = home.x + home.w / 2;
        int homeGateY = home.y - 1;
        carveRoad(terrain, n, homeGateX, Math.max(1, homeGateY),
                plains.x + plains.w / 2, plains.y + plains.h / 2);

        int spawnX = homeGateX;
        int spawnY = Math.max(2, home.y - 3);
        ensureSpawnClear(terrain, n, spawnX, spawnY);

        return new PlaneData(seed, profile, terrain, biome, sites, spawnX, spawnY);
    }

    private void layoutBiomes(byte[] biome, HashNoise noise, int n, int cx, int cy) {
        var L = profile.layout;
        float rCore = L.get("R_CORE").getAsFloat();
        float warpA = L.get("BORDER_WARP_ANGLE").getAsFloat();
        float warpR = L.get("BORDER_WARP_RADIUS").getAsFloat();
        byte core = BiomeId.byName(L.get("core_biome").getAsString());
        var orderJson = L.getAsJsonArray("order");
        byte[] order = new byte[orderJson.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = BiomeId.byName(orderJson.get(i).getAsString());
        }
        double rot = noise.value2d(0, 0, "rot") * Math.PI * 2;
        float seaStart = profile.sea.get("SEA_START").getAsFloat();
        float rCoast = L.get("R_COAST").getAsFloat();

        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float r = (float) Math.hypot(x - cx, y - cy);
                float theta = (float) Math.atan2(y - cy, x - cx);
                float rw = warpedRadius(noise, x, y, r, theta, warpR, rCoast);
                float wa = noise.fbm(x, y, "warpA", 3, 140f);
                float tw = (float) (theta + warpA * (wa - 0.5f) * 2f + rot);
                byte b;
                if (rw < rCore) {
                    b = core;
                } else {
                    float ang = (float) ((tw % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2));
                    int sector = (int) Math.floor(ang / (Math.PI * 2 / order.length)) % order.length;
                    b = order[sector];
                }
                if (rw > seaStart && noise.value2d(x, y, "blueLean") > 0.55f) {
                    b = BiomeId.BLUE;
                }
                biome[y * n + x] = b;
            }
        }
    }

    private void paintSeaAndCoast(byte[] terrain, byte[] biome, HashNoise noise, int n, int cx, int cy) {
        float rCoast = profile.layout.get("R_COAST").getAsFloat();
        float warpR = profile.layout.get("BORDER_WARP_RADIUS").getAsFloat();
        // Scale coast jitter with map size (WG1: ~33 @ N=2000) plus a longer bay/peninsula octave.
        float coastAmp = 10f * (n / 600f);
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int i = y * n + x;
                float r = (float) Math.hypot(x - cx, y - cy);
                float theta = (float) Math.atan2(y - cy, x - cx);
                float rw = warpedRadius(noise, x, y, r, theta, warpR, rCoast);
                float coastN = noise.fbm(x, y, "coast", 5, 45f);
                float bayN = noise.fbm(x, y, "bay", 3, 160f);
                boolean ocean = rw > rCoast
                        + coastAmp * (coastN - 0.35f)
                        + warpR * 0.9f * (bayN - 0.5f);
                if (x < 4 || y < 4 || x >= n - 4 || y >= n - 4) {
                    ocean = true;
                }
                if (ocean) {
                    terrain[i] = Terrain.OCEAN;
                    biome[i] = BiomeId.BLUE;
                } else {
                    terrain[i] = Terrain.GRASS;
                }
            }
        }
        for (int y = 8; y < n - 8; y++) {
            for (int x = 8; x < n - 8; x++) {
                int i = y * n + x;
                if (terrain[i] == Terrain.OCEAN) {
                    continue;
                }
                float lake = noise.fbm(x, y, "lake", 3, 34f);
                float dens = biome[i] == BiomeId.BLACK ? 0.14f : 0.02f;
                if (lake > 1f - dens) {
                    terrain[i] = Terrain.WATER;
                }
            }
        }
    }

    private void paintObstacles(byte[] terrain, byte[] biome, HashNoise noise, int n, int cx, int cy) {
        float scale = profile.terrain.get("CLUSTER_SCALE").getAsFloat();
        float laneW = profile.terrain.get("LANE_WIDTH").getAsFloat();
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                int i = y * n + x;
                if (terrain[i] != Terrain.GRASS) {
                    continue;
                }
                float r = (float) Math.hypot(x - cx, y - cy);
                if (r < 70) {
                    continue;
                }
                String bname = BiomeId.NAMES[biome[i] & 0xff];
                float dens = profile.biomeDensity.getOrDefault(bname, 0.1f);
                float cluster = noise.fbm(x, y, "obst", 3, scale);
                float lane = noise.fbm(x, y, "lane", 2, 90f);
                if (Math.abs(lane - 0.5f) < laneW) {
                    continue;
                }
                if (cluster > 1f - dens) {
                    boolean rock = biome[i] == BiomeId.RED || noise.value2d(x, y, "rock") > 0.72f;
                    terrain[i] = rock ? Terrain.ROCK : Terrain.TREE;
                }
                if (biome[i] == BiomeId.RED && noise.fbm(x, y, "ridge", 2, 80f) > 0.88f) {
                    terrain[i] = Terrain.RIDGE;
                }
            }
        }
    }

    private void paintRivers(byte[] terrain, int n, int cx, int cy, long seed) {
        Random rng = new Random(seed ^ 0x517E1000L);
        int count = profile.terrain.get("RIVER_COUNT").getAsInt();
        int minLen = profile.terrain.get("RIVER_MIN_LEN").getAsInt();
        for (int r = 0; r < count; r++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int x = cx + (int) (Math.cos(ang) * (200 + rng.nextInt(400)));
            int y = cy + (int) (Math.sin(ang) * (200 + rng.nextInt(400)));
            if (x < 10 || y < 10 || x >= n - 10 || y >= n - 10) {
                continue;
            }
            int len = 0;
            for (int step = 0; step < 800; step++) {
                int i = y * n + x;
                if (terrain[i] == Terrain.OCEAN) {
                    break;
                }
                terrain[i] = Terrain.WATER;
                if (x + 1 < n) {
                    terrain[y * n + x + 1] = Terrain.WATER;
                }
                len++;
                // flow outward / downhill toward sea
                float best = -1e9f;
                int nx = x, ny = y;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) {
                            continue;
                        }
                        int tx = x + dx;
                        int ty = y + dy;
                        if (tx < 1 || ty < 1 || tx >= n - 1 || ty >= n - 1) {
                            continue;
                        }
                        float dist = (float) Math.hypot(tx - cx, ty - cy);
                        dist += rng.nextFloat() * 3f;
                        if (dist > best) {
                            best = dist;
                            nx = tx;
                            ny = ty;
                        }
                    }
                }
                x = nx;
                y = ny;
            }
            if (len < minLen) {
                // leave short rivers; still adds water features
            }
        }
    }

    private Site stampHome(byte[] terrain, List<Site> sites, int n, int cx, int cy) throws IOException {
        Path tmx = mapsRoot.resolve(profile.homeTown.get("tmx").getAsString());
        int skip = profile.homeTown.get("skip_rows_top").getAsInt();
        TmxStamp stamp = TmxStamp.load(tmx, skip);
        int x0 = cx - stamp.width / 2;
        int y0 = cy - stamp.height / 2;
        applyStamp(terrain, n, x0, y0, stamp);
        Site site = new Site("town", profile.homeTown.get("name").getAsString(),
                x0, y0, stamp.width, stamp.height,
                profile.homeTown.get("tmx").getAsString(), skip, true);
        sites.add(site);
        return site;
    }

    private Site stampPlainsTown(byte[] terrain, byte[] biome, List<Site> sites,
                                 int n, int cx, int cy, long seed) throws IOException {
        Path tmx = mapsRoot.resolve(profile.plainsTown.get("tmx").getAsString());
        TmxStamp stamp = TmxStamp.load(tmx, 0);
        int dist = profile.plainsTown.get("distance_from_home").getAsInt();
        Random rng = new Random(seed ^ 0x51A15500L);
        int x0 = 0;
        int y0 = 0;
        boolean found = false;
        for (int attempt = 0; attempt < 80; attempt++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            int tx = cx + (int) (Math.cos(ang) * dist);
            int ty = cy + (int) (Math.sin(ang) * dist);
            x0 = tx - stamp.width / 2;
            y0 = ty - stamp.height / 2;
            if (x0 < 20 || y0 < 20 || x0 + stamp.width >= n - 20 || y0 + stamp.height >= n - 20) {
                continue;
            }
            int sample = biome[(ty) * n + tx];
            // prefer white/plains; accept wastes as fallback near meadow
            if (sample == BiomeId.WHITE || sample == BiomeId.WASTES || attempt > 40) {
                found = true;
                break;
            }
        }
        if (!found) {
            x0 = cx + dist - stamp.width / 2;
            y0 = cy - stamp.height / 2;
        }
        applyStamp(terrain, n, x0, y0, stamp);
        // mark surrounding biome white for map tint
        for (int y = y0; y < y0 + stamp.height; y++) {
            for (int x = x0; x < x0 + stamp.width; x++) {
                if (x >= 0 && y >= 0 && x < n && y < n) {
                    biome[y * n + x] = BiomeId.WHITE;
                }
            }
        }
        Site site = new Site("town", profile.plainsTown.get("name").getAsString(),
                x0, y0, stamp.width, stamp.height,
                profile.plainsTown.get("tmx").getAsString(), 0, false);
        sites.add(site);
        return site;
    }

    private static void applyStamp(byte[] terrain, int n, int x0, int y0, TmxStamp stamp) {
        // TMX row 0 is the top of the map; world y increases upward — flip on stamp.
        for (int y = 0; y < stamp.height; y++) {
            for (int x = 0; x < stamp.width; x++) {
                int wx = x0 + x;
                int wy = y0 + (stamp.height - 1 - y);
                if (wx < 0 || wy < 0 || wx >= n || wy >= n) {
                    continue;
                }
                byte t = stamp.terrain[y * stamp.width + x];
                if (t != 0) {
                    terrain[wy * n + wx] = t;
                } else {
                    // clear stamp footprint so yards stay walkable
                    byte cur = terrain[wy * n + wx];
                    if (cur == Terrain.TREE || cur == Terrain.ROCK || cur == Terrain.WATER || cur == Terrain.OCEAN) {
                        terrain[wy * n + wx] = Terrain.TOWN;
                    }
                }
            }
        }
    }

    private void carveRoad(byte[] terrain, int n, int x0, int y0, int x1, int y1) {
        int width = Math.max(1, profile.roadWidth);
        int x = x0;
        int y = y0;
        while (x != x1 || y != y1) {
            paintRoadDisk(terrain, n, x, y, width);
            if (x < x1) {
                x++;
            } else if (x > x1) {
                x--;
            }
            if (y < y1) {
                y++;
            } else if (y > y1) {
                y--;
            }
        }
        paintRoadDisk(terrain, n, x1, y1, width);
    }

    private static void paintRoadDisk(byte[] terrain, int n, int cx, int cy, int width) {
        int r = Math.max(1, width);
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x < 0 || y < 0 || x >= n || y >= n) {
                    continue;
                }
                int i = y * n + x;
                byte t = terrain[i];
                if (t == Terrain.OCEAN || t == Terrain.HOUSE) {
                    if (t == Terrain.OCEAN) {
                        terrain[i] = Terrain.BRIDGE;
                    }
                    continue;
                }
                if (t == Terrain.WATER) {
                    terrain[i] = Terrain.BRIDGE;
                } else {
                    terrain[i] = Terrain.ROAD;
                }
            }
        }
    }

    private static void ensureSpawnClear(byte[] terrain, int n, int sx, int sy) {
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = sx + dx;
                int y = sy + dy;
                if (x < 0 || y < 0 || x >= n || y >= n) {
                    continue;
                }
                int i = y * n + x;
                if (Terrain.blocksMovement(terrain[i]) && terrain[i] != Terrain.HOUSE) {
                    terrain[i] = Terrain.GRASS;
                }
            }
        }
    }

    /**
     * Same radial warp for biomes and coastline so wedges and shore stay aligned.
     * Ellipse stretch + large lobes + fbm so the silhouette is clearly not a circle.
     */
    static float warpedRadius(HashNoise noise, int x, int y, float r, float theta,
                              float warpR, float rCoast) {
        float wr = noise.fbm(x, y, "warpR", 3, 90f);
        float ph0 = noise.value2d(0, 0, "lobePh") * (float) (Math.PI * 2);
        float ph1 = noise.value2d(1, 0, "lobePh") * (float) (Math.PI * 2);
        float ph2 = noise.value2d(2, 0, "lobePh") * (float) (Math.PI * 2);
        // Stretch into an oval first (very readable at minimap scale), then chew the edge.
        float oval = 1f + 0.28f * (float) Math.cos(2 * (theta + ph0));
        float lobes = 0.55f * (float) Math.sin(3 * theta + ph1)
                + 0.30f * (float) Math.sin(5 * theta + ph2);
        return r / oval + warpR * (wr - 0.5f) * 2f + rCoast * 0.18f * lobes;
    }
}
