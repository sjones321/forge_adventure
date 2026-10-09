package forge.adventure.coop;

import forge.Forge;
import forge.adventure.data.WorldData;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.PlanarPortalPlacer;
import forge.adventure.world.SetPlaneGenerator;
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
 * <p><strong>MV2 co-op hashing:</strong> planar-gate placement clears terrain, so
 * gates are kept <em>out</em> of the hashed world. Host and guest both hash a
 * gate-free rebuild ({@link #rebuildFromSeed}). After the hash matches, the guest
 * places gates locally for gameplay ({@link #applyGatesAfterCoopHash}). This is
 * consistent for home (window gates) and set planes (return portal).
 *
 * <p>MV2 customisation runs only when the host sends a non-empty
 * {@code mv2SetCode} (stamped on {@link PlaneMeta} at materialize). Pre-MV2 set
 * planes with empty setCode rebuild as plain template worlds.
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
     * Host: hash the current plane the same way the guest will rebuild it —
     * gate-free staging regenerate. Never hash the live world (gates may have
     * cleared terrain).
     */
    public static String hashPlaneForOffer(final WorldSave save) {
        if (save == null || save.getWorld() == null) {
            return hashWorld(null);
        }
        final World staging = new World();
        try {
            final PlaneMeta meta = save.getMultiverse().getCurrentMeta();
            final String planeId = save.getCurrentPlaneId();
            final String path = save.getWorld().getWorldConfigPath();
            final long seed = save.getWorld().getSeed();
            final String mv2SetCode = meta != null ? meta.getSetCode() : "";
            return rebuildFromSeed(staging, seed, path, planeId, mv2SetCode);
        } finally {
            Forge.safeDispose(staging);
        }
    }

    /** Regenerate into a dedicated session {@link World}. Call on the GL thread. */
    public static String rebuildFromSeed(final World target, final long seed) {
        return rebuildFromSeed(target, seed, Paths.WORLD, null, "");
    }

    public static String rebuildFromSeed(final World target, final long seed, final String worldConfigPath) {
        return rebuildFromSeed(target, seed, worldConfigPath, null, "");
    }

    /**
     * @deprecated use {@link #rebuildFromSeed(World, long, String, String, String)}
     * with an explicit {@code mv2SetCode} (empty for home / pre-MV2).
     */
    @Deprecated
    public static String rebuildFromSeed(final World target, final long seed,
                                         final String worldConfigPath, final String worldPlaneId) {
        // Legacy: infer set code from plane id only for known editions — prefer explicit mv2SetCode.
        final String inferred = worldPlaneId != null
                ? SetPlaneGenerator.setCodeFromPlaneId(worldPlaneId) : "";
        return rebuildFromSeed(target, seed, worldConfigPath, worldPlaneId, inferred);
    }

    /**
     * Rebuild for co-op hash verification. Applies MV2 set customisation only when
     * {@code mv2SetCode} is non-empty (host-authoritative). Does <strong>not</strong>
     * place planar gates — those stay out of the hashed terrain.
     */
    public static String rebuildFromSeed(final World target, final long seed,
                                         final String worldConfigPath, final String worldPlaneId,
                                         final String mv2SetCode) {
        if (target == null) {
            throw new IllegalArgumentException("target world required");
        }
        final String path = worldConfigPath != null && !worldConfigPath.isEmpty()
                ? worldConfigPath : Paths.WORLD;
        generateHashedContent(target, seed, path, mv2SetCode);
        return hashWorld(target);
    }

    /**
     * Shared host/guest generation for the co-op hash (no gates).
     * {@code mv2SetCode} empty → plain template (home or pre-MV2 set plane).
     */
    public static void generateHashedContent(final World target, final long seed,
                                             final String worldConfigPath, final String mv2SetCode) {
        if (target == null) {
            return;
        }
        final String path = worldConfigPath != null && !worldConfigPath.isEmpty()
                ? worldConfigPath : Paths.WORLD;
        applyMv2Customization(target, seed, mv2SetCode);
        if (!target.generateNew(seed, path, false)) {
            throw new IllegalStateException("World generation failed");
        }
    }

    /**
     * Host materialize pipeline: hashed content, then gates for gameplay.
     * Returns the co-op hash (pre-gate). Used by {@link WorldSave#materializeSetPlane}.
     */
    public static String buildSetPlaneWorld(final World target, final long seed,
                                            final String worldConfigPath, final String setCode,
                                            final boolean placeGates) {
        generateHashedContent(target, seed, worldConfigPath, setCode);
        final String coopHash = hashWorld(target);
        if (placeGates && setCode != null && !setCode.isEmpty()) {
            PlanarPortalPlacer.ensureReturnPortal(target, setCode, seed);
        }
        return coopHash;
    }

    /** Apply MV2 biome customisation only when the host stamped a set code. */
    public static void applyMv2Customization(final World target, final long seed, final String mv2SetCode) {
        if (target == null || mv2SetCode == null || mv2SetCode.isEmpty()) {
            return;
        }
        try {
            final WorldData custom = SetPlaneGenerator.prepareSetPlaneData(mv2SetCode, seed);
            target.overrideWorldData(custom);
        } catch (final Exception e) {
            System.err.println("MV2 co-op set-plane customise failed for " + mv2SetCode + ": " + e.getMessage());
        }
    }

    /**
     * After a co-op hash match: place gates for local gameplay. Must not run
     * before hashing. Home uses the Standard window; set planes get a return portal
     * when {@code mv2SetCode} is non-empty.
     */
    public static void applyGatesAfterCoopHash(final World target, final long seed,
                                               final String worldPlaneId, final String mv2SetCode,
                                               final StandardWindow window, final MultiverseState multi) {
        if (target == null) {
            return;
        }
        if (worldPlaneId == null || worldPlaneId.isEmpty()
                || PlaneMeta.HOME_ID.equalsIgnoreCase(worldPlaneId)) {
            PlanarPortalPlacer.ensureHomePortals(target, window, seed, multi);
            return;
        }
        if (mv2SetCode != null && !mv2SetCode.isEmpty()) {
            PlanarPortalPlacer.ensureReturnPortal(target, mv2SetCode, seed);
        }
    }

    /** @deprecated use {@link #applyMv2Customization} with host-stamped set code. */
    @Deprecated
    public static void applySetPlaneCustomization(final World target, final long seed,
                                                  final String worldPlaneId) {
        if (worldPlaneId == null || PlaneMeta.HOME_ID.equalsIgnoreCase(worldPlaneId)) {
            return;
        }
        applyMv2Customization(target, seed, SetPlaneGenerator.setCodeFromPlaneId(worldPlaneId));
    }

    /** @deprecated gates must not run before co-op hash — use {@link #applyGatesAfterCoopHash}. */
    @Deprecated
    public static void applySetPlaneGates(final World target, final long seed,
                                          final String worldPlaneId) {
        if (worldPlaneId == null || PlaneMeta.HOME_ID.equalsIgnoreCase(worldPlaneId)) {
            return;
        }
        final String setCode = SetPlaneGenerator.setCodeFromPlaneId(worldPlaneId);
        if (!setCode.isEmpty()) {
            PlanarPortalPlacer.ensureReturnPortal(target, setCode, seed);
        }
    }
}
