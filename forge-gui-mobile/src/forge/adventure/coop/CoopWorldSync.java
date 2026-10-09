package forge.adventure.coop;

import forge.adventure.data.WorldData;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.PlanarPortalPlacer;
import forge.adventure.world.SetPlaneGenerator;
import forge.adventure.world.SetPlaneRules;
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
 *
 * <p>MV2: set planes reproduce {@link SetPlaneGenerator} customisation and
 * return-gate terrain clears deterministically so host and guest hashes match.
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
        return rebuildFromSeed(target, seed, Paths.WORLD, null);
    }

    public static String rebuildFromSeed(final World target, final long seed, final String worldConfigPath) {
        return rebuildFromSeed(target, seed, worldConfigPath, null);
    }

    /**
     * Rebuild for co-op hash verification. When {@code worldPlaneId} is a set
     * plane, applies the same MV2 customisation + return-gate placement the host
     * used in {@code WorldSave.materializeSetPlane}, so biome and terrain hashes match.
     */
    public static String rebuildFromSeed(final World target, final long seed,
                                         final String worldConfigPath, final String worldPlaneId) {
        if (target == null) {
            throw new IllegalArgumentException("target world required");
        }
        final String path = worldConfigPath != null && !worldConfigPath.isEmpty()
                ? worldConfigPath : Paths.WORLD;
        applySetPlaneCustomization(target, seed, worldPlaneId);
        if (!target.generateNew(seed, path, false)) {
            throw new IllegalStateException("World generation failed");
        }
        applySetPlaneGates(target, seed, worldPlaneId);
        return hashWorld(target);
    }

    /**
     * Host/guest shared: override world data from the set's colour mix when
     * {@code worldPlaneId} names a known edition set plane.
     */
    public static void applySetPlaneCustomization(final World target, final long seed,
                                                  final String worldPlaneId) {
        if (target == null || worldPlaneId == null || worldPlaneId.isEmpty()
                || PlaneMeta.HOME_ID.equalsIgnoreCase(worldPlaneId)) {
            return;
        }
        final String setCode = SetPlaneGenerator.setCodeFromPlaneId(worldPlaneId);
        if (setCode.isEmpty()) {
            return;
        }
        // Mirror WorldSave.materializeSetPlane: only customise known editions.
        if (!SetPlaneRules.isKnownEdition(setCode)) {
            return;
        }
        try {
            final WorldData custom = SetPlaneGenerator.prepareSetPlaneData(setCode, seed);
            target.overrideWorldData(custom);
        } catch (final Exception e) {
            System.err.println("MV2 co-op set-plane customise failed for " + setCode + ": " + e.getMessage());
        }
    }

    /**
     * Host/guest shared: place the return Planar Gate and clear terrain around it
     * using the same seed the host used at materialize time.
     */
    public static void applySetPlaneGates(final World target, final long seed,
                                          final String worldPlaneId) {
        if (target == null || worldPlaneId == null || worldPlaneId.isEmpty()
                || PlaneMeta.HOME_ID.equalsIgnoreCase(worldPlaneId)) {
            return;
        }
        final String setCode = SetPlaneGenerator.setCodeFromPlaneId(worldPlaneId);
        if (setCode.isEmpty()) {
            return;
        }
        PlanarPortalPlacer.ensureReturnPortal(target, setCode, seed);
    }
}
