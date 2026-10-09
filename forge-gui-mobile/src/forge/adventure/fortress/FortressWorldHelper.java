package forge.adventure.fortress;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.world.World;

import java.util.List;

/**
 * FT1 planting helper that does <strong>not</strong> touch {@code World.java}.
 * Uses the live chunk list from {@link World#getPointsOfInterest(int, int)} so FT1
 * and MV2 (which owns {@code World.addPointOfInterest}) merge cleanly.
 */
public final class FortressWorldHelper {
    private FortressWorldHelper() {
    }

    /**
     * Add a POI to the world's chunk map. No-op when the chunk is out of bounds
     * (the empty list returned by World is not live in that case).
     *
     * @return true if the POI was added to a live chunk list
     */
    public static boolean plantPoi(World world, PointOfInterest poi) {
        if (world == null || poi == null || poi.getPosition() == null)
            return false;
        int tileSize = world.getTileSize();
        int chunkSize = world.getChunkSize();
        if (tileSize <= 0 || chunkSize <= 0)
            return false;
        Vector2 pos = poi.getPosition();
        int chunkX = (int) ((pos.x / tileSize) / chunkSize);
        int chunkY = (int) ((pos.y / tileSize) / chunkSize);
        if (chunkX < 0 || chunkY < 0
                || chunkX >= world.getWidthInChunks()
                || chunkY >= world.getHeightInChunks())
            return false;
        List<PointOfInterest> live = world.getPointsOfInterest(chunkX, chunkY);
        // Out-of-bounds World helper returns a fresh empty list — refuse those.
        if (live == null)
            return false;
        live.add(poi);
        return true;
    }

    /**
     * World-pixel position for the fortress POI: one tile north of the player so the
     * marker does not land on the player's tile. Falls back to east / west / south
     * if that tile is colliding.
     */
    public static Vector2 poiPosAwayFromPlayer(World world, float playerX, float playerY) {
        int tileSize = world != null ? Math.max(1, world.getTileSize()) : 16;
        int px = (int) (playerX / tileSize);
        int py = (int) (playerY / tileSize);
        int[][] deltas = {{0, 1}, {1, 0}, {-1, 0}, {0, -1}};
        for (int[] d : deltas) {
            int tx = px + d[0];
            int ty = py + d[1];
            if (world != null && world.isColliding(tx, ty))
                continue;
            return new Vector2(tx * tileSize + tileSize / 2f, ty * tileSize + tileSize / 2f);
        }
        // Last resort: still offset north so we never plant exactly on the player tile.
        return new Vector2(playerX, playerY + tileSize);
    }

    public static boolean sameTile(float ax, float ay, float bx, float by, int tileSize) {
        int t = Math.max(1, tileSize);
        return (int) (ax / t) == (int) (bx / t) && (int) (ay / t) == (int) (by / t);
    }
}
