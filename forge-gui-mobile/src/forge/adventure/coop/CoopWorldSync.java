package forge.adventure.coop;

import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.coop.CoopWorldHash;

/**
 * Guest rebuilds the host world from seed + plane config into a dedicated
 * session {@link World}, then verifies a hash. On mismatch the session refuses
 * — there is no world-blob fallback.
 *
 * <p>{@link World#generateNew} / {@link World#load} must be called on the GL
 * thread (see {@link CoopSession}).
 *
 * <p>MV1: hashes and rebuilds use the host's <em>current</em> plane world.json
 * (home or set-plane template), not only {@link Paths#WORLD}.
 */
public final class CoopWorldSync {
    private CoopWorldSync() {
    }

    public static String planeConfigHash() {
        String worldPath = Paths.WORLD;
        try {
            worldPath = WorldSave.getCurrentSave().getWorld().getWorldConfigPath();
        } catch (final Exception ignored) {
            // Solo / early init
        }
        return planeConfigHash(worldPath);
    }

    public static String planeConfigHash(final String worldConfigPath) {
        try {
            final String plane = Config.instance().getPlane();
            final String raw = Config.instance().getFile("config.json").readString();
            final String path = worldConfigPath != null && !worldConfigPath.isEmpty()
                    ? worldConfigPath : Paths.WORLD;
            final String worldRaw = Config.instance().getFile(path).readString();
            return CoopVersion.sha256Hex(plane + '|' + path + '|' + raw + '|' + worldRaw);
        } catch (final Exception e) {
            return CoopVersion.sha256Hex("plane-config:error:" + e.getMessage());
        }
    }

    public static String hashWorld(final World world) {
        if (world == null) {
            return CoopWorldHash.hash(0, 0, 0, null, null);
        }
        return CoopWorldHash.hash(
                world.getSeed(),
                world.getWidthInTiles(),
                world.getHeightInTiles(),
                world.getBiomeMap(),
                world.terrainMap);
    }

    /**
     * Regenerate into a dedicated session {@link World} (does not touch the
     * guest's saved WorldSave). Returns the local hash. Call on the GL thread.
     */
    public static String rebuildFromSeed(final World target, final long seed) {
        return rebuildFromSeed(target, seed, Paths.WORLD);
    }

    public static String rebuildFromSeed(final World target, final long seed, final String worldConfigPath) {
        if (!target.generateNew(seed, worldConfigPath)) {
            throw new IllegalStateException("World generation failed");
        }
        return hashWorld(target);
    }
}
