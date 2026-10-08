package forge.adventure.coop;

import forge.adventure.util.Config;
import forge.adventure.world.World;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.coop.CoopWorldHash;

/**
 * Guest rebuilds the host world from seed + plane config into a dedicated
 * session {@link World}, then verifies a hash. On mismatch the session refuses
 * — there is no world-blob fallback.
 *
 * <p>{@link World#generateNew} / {@link World#load} must be called on the GL
 * thread (see {@link CoopSession}).
 */
public final class CoopWorldSync {
    private CoopWorldSync() {
    }

    public static String planeConfigHash() {
        try {
            final String plane = Config.instance().getPlane();
            final String raw = Config.instance().getFile("config.json").readString();
            final String worldRaw = Config.instance().getFile("world/world.json").readString();
            return CoopVersion.sha256Hex(plane + '|' + raw + '|' + worldRaw);
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
        if (!target.generateNew(seed)) {
            throw new IllegalStateException("World generation failed");
        }
        return hashWorld(target);
    }
}
