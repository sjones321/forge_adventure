package forge.ascendant;

import forge.ascendant.world.PlaneData;
import forge.ascendant.world.PlaneProfile;

import java.nio.file.Path;

/** Single session object — no static game-state singletons. */
public final class AscendantSession {
    public final Path assetRoot;
    public final Path saveDir;
    public final PlaneProfile profile;
    public PlaneData plane;
    public float playerX;
    public float playerY;
    public final boolean[] exploredChunks;
    public boolean showFullMap;
    public String statusMessage = "";
    public float statusTimer;

    public AscendantSession(Path assetRoot, Path saveDir, PlaneProfile profile, PlaneData plane) {
        this.assetRoot = assetRoot;
        this.saveDir = saveDir;
        this.profile = profile;
        this.plane = plane;
        this.playerX = plane.spawnX + 0.5f;
        this.playerY = plane.spawnY + 0.5f;
        int chunks = (profile.size + profile.chunk - 1) / profile.chunk;
        this.exploredChunks = new boolean[chunks * chunks];
        markExplored();
    }

    public void markExplored() {
        int cs = profile.chunk;
        int chunks = (profile.size + cs - 1) / cs;
        int cx = Math.floorDiv((int) playerX, cs);
        int cy = Math.floorDiv((int) playerY, cs);
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = cx + dx;
                int y = cy + dy;
                if (x >= 0 && y >= 0 && x < chunks && y < chunks) {
                    exploredChunks[y * chunks + x] = true;
                }
            }
        }
    }

    public void toast(String msg) {
        statusMessage = msg;
        statusTimer = 2.5f;
    }
}
