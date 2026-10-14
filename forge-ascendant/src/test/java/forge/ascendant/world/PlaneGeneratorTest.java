package forge.ascendant.world;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class PlaneGeneratorTest {
    @Test
    public void sameSeedIsDeterministicAndStampsTowns() throws Exception {
        Path maps = resolveMaps();
        if (!Files.isRegularFile(maps.resolve("map/ascendant/havenbrook.tmx"))) {
            System.out.println("Skip: havenbrook.tmx not found at " + maps);
            return;
        }
        PlaneProfile profile = PlaneProfile.loadClasspath("/profiles/home_shandalar.json");
        // Shrink for test speed via a tiny profile override — generate full size is OK but slow in CI;
        // use seed compare on two full gens only if size is reduced. Here we assert key invariants on one gen
        // and byte-equality on a smaller synthetic check via HashNoise.
        PlaneGenerator gen = new PlaneGenerator(profile, maps);
        long seed = 1L;
        PlaneData a = gen.generate(seed);
        PlaneData b = gen.generate(seed);
        Assert.assertEquals(a.size, b.size);
        Assert.assertArrayEquals(a.terrain, b.terrain);
        Assert.assertArrayEquals(a.biome, b.biome);
        Assert.assertEquals(2, a.sites.size());
        Assert.assertTrue(a.sites.get(0).home);
        Assert.assertEquals("Havenbrook", a.sites.get(0).name);
        Assert.assertEquals("Plains Market", a.sites.get(1).name);
        Assert.assertFalse("spawn must be walkable", a.blocks(a.spawnX, a.spawnY));
        // Road should exist somewhere between home and plains (sample midline)
        Site home = a.sites.get(0);
        Site plains = a.sites.get(1);
        int mx = (home.x + home.w / 2 + plains.x + plains.w / 2) / 2;
        int my = (home.y + home.h / 2 + plains.y + plains.h / 2) / 2;
        boolean roadNear = false;
        for (int dy = -8; dy <= 8 && !roadNear; dy++) {
            for (int dx = -8; dx <= 8; dx++) {
                byte t = a.terrainAt(mx + dx, my + dy);
                if (t == Terrain.ROAD || t == Terrain.BRIDGE) {
                    roadNear = true;
                    break;
                }
            }
        }
        Assert.assertTrue("expected road between towns", roadNear);
    }

    @Test
    public void hashNoiseIsStable() {
        HashNoise n = new HashNoise(42);
        float a = n.fbm(100, 200, "obst", 3, 13f);
        float b = n.fbm(100, 200, "obst", 3, 13f);
        Assert.assertEquals(a, b, 0f);
        Assert.assertNotEquals(a, n.fbm(101, 200, "obst", 3, 13f), 0f);
    }

    @Test
    public void coastlineIsNotAPureCircle() throws Exception {
        Path maps = resolveMaps();
        if (!Files.isRegularFile(maps.resolve("map/ascendant/havenbrook.tmx"))) {
            System.out.println("Skip: havenbrook.tmx not found at " + maps);
            return;
        }
        PlaneProfile profile = PlaneProfile.loadClasspath("/profiles/home_shandalar.json");
        PlaneData plane = new PlaneGenerator(profile, maps).generate(7L);
        int n = plane.size;
        int cx = n / 2;
        int cy = n / 2;
        float[] radii = new float[32];
        int hits = 0;
        for (int a = 0; a < radii.length; a++) {
            double ang = a * (Math.PI * 2 / radii.length);
            float dx = (float) Math.cos(ang);
            float dy = (float) Math.sin(ang);
            float hit = 0;
            for (float r = 500; r < n; r += 2) {
                int x = cx + Math.round(dx * r);
                int y = cy + Math.round(dy * r);
                if (x < 0 || y < 0 || x >= n || y >= n) {
                    break;
                }
                if (plane.terrainAt(x, y) == Terrain.OCEAN) {
                    hit = r;
                    break;
                }
            }
            if (hit > 0) {
                radii[hits++] = hit;
            }
        }
        Assert.assertTrue("need enough coast samples", hits >= 24);
        float min = radii[0];
        float max = radii[0];
        for (int i = 1; i < hits; i++) {
            min = Math.min(min, radii[i]);
            max = Math.max(max, radii[i]);
        }
        Assert.assertTrue("coast radius span too circular: min=" + min + " max=" + max, max - min > 250f);
    }

    private static Path resolveMaps() {
        Path[] candidates = {
                Paths.get("forge-gui/res/adventure/common/maps"),
                Paths.get("../forge-gui/res/adventure/common/maps")
        };
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c.toAbsolutePath().normalize();
            }
        }
        return candidates[0];
    }
}
