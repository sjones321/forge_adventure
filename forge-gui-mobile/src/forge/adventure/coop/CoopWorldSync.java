package forge.adventure.coop;

import com.badlogic.gdx.math.Vector2;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.WorldData;
import forge.adventure.pointofintrest.PointOfInterest;
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
import forge.gamemodes.net.event.coop.CoopPlanarGateEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Guest rebuilds the host world from seed + plane config into a dedicated
 * session {@link World}, replays the host's planar-gate list, then verifies a
 * hash against the host's <em>live</em> world. On mismatch the session refuses
 * — there is no world-blob fallback.
 *
 * <p>{@link World#generateNew} / {@link World#load} must be called on the GL
 * thread (see {@link CoopSession}).
 *
 * <p><strong>MV2 co-op hashing:</strong> the host hashes its live world on the
 * GL thread (including gate terrain clears) and sends that hash plus an exact,
 * capped gate list. The guest regenerates from seed, applies the host's gates
 * at the host's positions (never computing its own Standard window), then
 * hashes. Matching proves guest == host.
 *
 * <p>MV2 customisation runs only when the host sends a non-empty
 * {@code mv2SetCode} (stamped on {@link PlaneMeta} at materialize for known
 * editions only). Pre-MV2 / unknown set codes rebuild as plain template worlds.
 */
public final class CoopWorldSync {
    /** Soft cap on planar gates shipped on the wire (Standard window + pending + return). */
    public static final int MAX_PLANAR_GATES_ON_WIRE = 64;

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
     * Host: hash the <em>live</em> world on the GL thread. Does not regenerate.
     * Call after plane load/switch and after any gate change; send the same hash
     * the guest will compare against after replaying {@link #collectPlanarGates}.
     */
    public static String hashPlaneForOffer(final WorldSave save) {
        if (save == null || save.getWorld() == null) {
            return hashWorld(null);
        }
        return hashWorld(save.getWorld());
    }

    /**
     * Host-stamped MV2 set code for the wire: known editions only (same filter as
     * materialize / registration). Empty for home, pre-MV2, or unknown codes.
     */
    public static String hostMv2SetCode(final WorldSave save) {
        if (save == null || save.getMultiverse() == null) {
            return "";
        }
        final PlaneMeta meta = save.getMultiverse().getCurrentMeta();
        if (meta == null) {
            return "";
        }
        return SetPlaneRules.restrictableSetCode(meta.getSetCode());
    }

    /**
     * Collect planar gates / legacy return portals from the live world for the wire.
     * Each entry is destination set code (empty = home) plus world position.
     * Sorted first, then capped at {@link #MAX_PLANAR_GATES_ON_WIRE} so the kept
     * set is deterministic (not "first N in map iteration order").
     */
    public static CoopPlanarGateEntry[] collectPlanarGates(final World world) {
        if (world == null) {
            return new CoopPlanarGateEntry[0];
        }
        final List<CoopPlanarGateEntry> list = new ArrayList<>();
        for (final PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi == null) {
                continue;
            }
            final String target = poi.getTargetPlane();
            if (target == null || target.isEmpty()) {
                continue;
            }
            // PlanarGate POIs and any legacy portal POI that carries a targetPlane.
            final PointOfInterestData d = poi.getData();
            final boolean isGate = d != null && (SetPlaneGenerator.PLANAR_GATE_POI.equals(d.name)
                    || "planar_gate".equals(d.type));
            final boolean legacyReturn = !isGate && (PlaneMeta.HOME_ID.equalsIgnoreCase(target)
                    || SetPlaneGenerator.setCodeFromPlaneId(target).length() > 0);
            if (!isGate && !legacyReturn) {
                continue;
            }
            final String setCode = PlaneMeta.HOME_ID.equalsIgnoreCase(target)
                    ? ""
                    : SetPlaneGenerator.setCodeFromPlaneId(target);
            final Vector2 pos = poi.getPosition() != null ? poi.getPosition() : poi.getCenter();
            if (pos == null) {
                continue;
            }
            list.add(new CoopPlanarGateEntry(setCode, pos.x, pos.y));
        }
        list.sort(Comparator
                .comparing(CoopPlanarGateEntry::getSetCode)
                .thenComparingDouble(CoopPlanarGateEntry::getX)
                .thenComparingDouble(CoopPlanarGateEntry::getY));
        if (list.size() > MAX_PLANAR_GATES_ON_WIRE) {
            return list.subList(0, MAX_PLANAR_GATES_ON_WIRE)
                    .toArray(new CoopPlanarGateEntry[0]);
        }
        return list.toArray(new CoopPlanarGateEntry[0]);
    }

    /**
     * Guest: place the host's gates at exact positions and clear terrain there.
     * Never consults the guest's Standard window.
     */
    public static int applyHostGates(final World target, final CoopPlanarGateEntry[] gates) {
        if (target == null || gates == null || gates.length == 0) {
            return 0;
        }
        SetPlaneGenerator.ensurePlanarGateRegistered();
        int placed = 0;
        final int limit = Math.min(gates.length, MAX_PLANAR_GATES_ON_WIRE);
        for (int i = 0; i < limit; i++) {
            final CoopPlanarGateEntry g = gates[i];
            if (g == null) {
                continue;
            }
            if (PlanarPortalPlacer.placeGateAt(target, g.getSetCode(), g.getX(), g.getY())) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * Guest mid-session gate update: rebuild from seed, replay host gates, and
     * report whether the local hash matches the host's live hash.
     * Used by {@code CoopGateUpdateEvent} handling and non-GL unit tests.
     */
    public static boolean gateUpdateMatchesHost(final World target, final long seed,
                                                final String worldConfigPath, final String mv2SetCode,
                                                final CoopPlanarGateEntry[] gates,
                                                final String hostLiveHash) {
        final String local = rebuildFromSeed(target, seed, worldConfigPath, mv2SetCode, gates);
        return CoopWorldHash.matches(local, hostLiveHash);
    }

    /**
     * Rebuild for co-op hash verification: generate from seed (+ optional MV2
     * customisation), replay host gates, return the live-equivalent hash.
     */
    public static String rebuildFromSeed(final World target, final long seed) {
        return rebuildFromSeed(target, seed, Paths.WORLD, "", null);
    }

    public static String rebuildFromSeed(final World target, final long seed, final String worldConfigPath) {
        return rebuildFromSeed(target, seed, worldConfigPath, "", null);
    }

    public static String rebuildFromSeed(final World target, final long seed,
                                         final String worldConfigPath, final String mv2SetCode,
                                         final CoopPlanarGateEntry[] gates) {
        if (target == null) {
            throw new IllegalArgumentException("target world required");
        }
        final String path = worldConfigPath != null && !worldConfigPath.isEmpty()
                ? worldConfigPath : Paths.WORLD;
        generateBaseContent(target, seed, path, mv2SetCode);
        applyHostGates(target, gates);
        return hashWorld(target);
    }

    /**
     * Shared host materialize / guest generate: world grid + optional MV2 mix, no gates.
     * {@code mv2SetCode} empty → plain template (home or pre-MV2 set plane).
     */
    public static void generateBaseContent(final World target, final long seed,
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
     * Host materialize pipeline: base content, then return portal for gameplay.
     * Returns the <em>live</em> (post-gate) hash. {@code setCode} must already be
     * known-edition-filtered by the caller (see {@link SetPlaneRules#restrictableSetCode}).
     */
    public static String buildSetPlaneWorld(final World target, final long seed,
                                            final String worldConfigPath, final String setCode,
                                            final boolean placeGates) {
        generateBaseContent(target, seed, worldConfigPath, setCode);
        if (placeGates && setCode != null && !setCode.isEmpty()) {
            PlanarPortalPlacer.ensureReturnPortal(target, setCode, seed);
        }
        return hashWorld(target);
    }

    /**
     * Apply MV2 biome customisation when {@code mv2SetCode} is non-empty.
     * Host stamps / offers only known editions ({@link #hostMv2SetCode}); the guest
     * trusts that stamp and does not re-derive a set code from the plane id.
     */
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
}
