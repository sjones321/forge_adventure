package forge.ascendant.world;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TmxStampTest {
    @Test
    public void plainsForegroundWallsBlockAndRoofsDoNot() throws Exception {
        Path tmx = resolveMaps().resolve("map/ascendant/plains_market_town.tmx");
        if (!Files.isRegularFile(tmx)) {
            System.out.println("Skip: " + tmx);
            return;
        }
        TmxStamp stamp = TmxStamp.load(tmx, 0);
        Assert.assertEquals(70, stamp.width);
        Assert.assertEquals(50, stamp.height);

        // Windmill door cell in TMX file coords (8,8) — Foreground base must block.
        Assert.assertEquals("windmill base should block",
                Terrain.HOUSE, stamp.terrain[8 * stamp.width + 8]);

        // Windmill sail cell on AboveSprites (file y=3,x=8) must NOT block.
        Assert.assertNotEquals("windmill sails should not block",
                Terrain.HOUSE, stamp.terrain[3 * stamp.width + 8]);

        // Ground path under windmill is town floor, not a wall.
        Assert.assertEquals(Terrain.TOWN, stamp.terrain[10 * stamp.width + 8]);

        // Count: Foreground parse must produce a real solid footprint.
        int houses = 0;
        for (byte t : stamp.terrain) {
            if (t == Terrain.HOUSE) {
                houses++;
            }
        }
        Assert.assertTrue("expected many Foreground house cells, got " + houses, houses > 200);
    }

    @Test
    public void havenbrookParsesForegroundDespiteSpriteLayerProperty() throws Exception {
        Path tmx = resolveMaps().resolve("map/ascendant/havenbrook.tmx");
        if (!Files.isRegularFile(tmx)) {
            System.out.println("Skip: " + tmx);
            return;
        }
        TmxStamp stamp = TmxStamp.load(tmx, 8);
        int houses = 0;
        for (byte t : stamp.terrain) {
            if (t == Terrain.HOUSE) {
                houses++;
            }
        }
        Assert.assertTrue("Havenbrook Foreground must stamp walls, got " + houses, houses > 100);
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
