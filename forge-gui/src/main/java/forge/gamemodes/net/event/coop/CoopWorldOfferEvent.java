package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopWireLimits;
import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest after a successful hello: enough info for the guest to rebuild
 * the world from seed + plane config, replay the host's planar gates, and verify
 * {@link #worldHash} against the host's <em>live</em> world hash.
 *
 * <p>MV1: {@link #worldPlaneId} / {@link #worldConfigPath} identify the host's
 * current overworld plane inside the adventure pack ({@link #planeId}).
 *
 * <p>MV2: {@link #mv2SetCode} is the host-stamped set code for co-op rebuild
 * customisation (empty for home / pre-MV2 / unknown editions). {@link #gates}
 * carries the host's exact gate list (capped); world hash includes gate terrain clears.
 *
 * <p>Package K: {@link #planeFormat} is the host's current plane duel format
 * (Standard / Historic / Pauper / Commander), length-capped plain data.
 */
public class CoopWorldOfferEvent implements NetEvent {
    private static final long serialVersionUID = 5L;

    private final String hostPlayerName;
    private final String planeId;
    private final String planeConfigHash;
    private final long worldSeed;
    private final String worldHash;
    private final int gamePort;
    private final int overworldPort;
    /** MV1 plane instance id; empty/null treated as home. */
    private final String worldPlaneId;
    /** Relative world.json for the host's current plane; empty → world/world.json. */
    private final String worldConfigPath;
    /** MV2: non-empty when the host materialised this plane with set customisation. */
    private final String mv2SetCode;
    /** MV2: host live planar gates (set code + position); may be empty. */
    private final CoopPlanarGateEntry[] gates;
    /** Package K: host current plane format; empty → guest applies default. */
    private final String planeFormat;

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort) {
        this(hostPlayerName, planeId, planeConfigHash, worldSeed, worldHash, gamePort, overworldPort,
                null, null, "", null, "");
    }

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort,
                               final String worldPlaneId, final String worldConfigPath) {
        this(hostPlayerName, planeId, planeConfigHash, worldSeed, worldHash, gamePort, overworldPort,
                worldPlaneId, worldConfigPath, "", null, "");
    }

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort,
                               final String worldPlaneId, final String worldConfigPath,
                               final String mv2SetCode) {
        this(hostPlayerName, planeId, planeConfigHash, worldSeed, worldHash, gamePort, overworldPort,
                worldPlaneId, worldConfigPath, mv2SetCode, null, "");
    }

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort,
                               final String worldPlaneId, final String worldConfigPath,
                               final String mv2SetCode, final CoopPlanarGateEntry[] gates) {
        this(hostPlayerName, planeId, planeConfigHash, worldSeed, worldHash, gamePort, overworldPort,
                worldPlaneId, worldConfigPath, mv2SetCode, gates, "");
    }

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort,
                               final String worldPlaneId, final String worldConfigPath,
                               final String mv2SetCode, final CoopPlanarGateEntry[] gates,
                               final String planeFormat) {
        this.hostPlayerName = hostPlayerName;
        this.planeId = planeId;
        this.planeConfigHash = planeConfigHash;
        this.worldSeed = worldSeed;
        this.worldHash = worldHash;
        this.gamePort = gamePort;
        this.overworldPort = overworldPort;
        this.worldPlaneId = worldPlaneId;
        this.worldConfigPath = worldConfigPath;
        this.mv2SetCode = mv2SetCode != null ? mv2SetCode : "";
        this.gates = copyGates(gates);
        this.planeFormat = CoopWireLimits.clampString(
                planeFormat != null ? planeFormat : "", CoopWireLimits.MAX_PLANE_FORMAT_LEN);
    }

    public String getHostPlayerName() {
        return hostPlayerName;
    }

    /** Adventure content pack id (e.g. {@code Shandalar Ascendant}). */
    public String getPlaneId() {
        return planeId;
    }

    public String getPlaneConfigHash() {
        return planeConfigHash;
    }

    public long getWorldSeed() {
        return worldSeed;
    }

    public String getWorldHash() {
        return worldHash;
    }

    public int getGamePort() {
        return gamePort;
    }

    public int getOverworldPort() {
        return overworldPort;
    }

    public String getWorldPlaneId() {
        return worldPlaneId;
    }

    public String getWorldConfigPath() {
        return worldConfigPath;
    }

    /** Host-stamped MV2 set code; empty means no set customisation on rebuild. */
    public String getMv2SetCode() {
        return mv2SetCode != null ? mv2SetCode : "";
    }

    /** Host live planar gates; never null (may be empty). */
    public CoopPlanarGateEntry[] getGates() {
        return copyGates(gates);
    }

    /** Package K: host current plane format token; empty when unset. */
    public String getPlaneFormat() {
        return planeFormat != null ? planeFormat : "";
    }

    private static CoopPlanarGateEntry[] copyGates(final CoopPlanarGateEntry[] src) {
        if (src == null || src.length == 0) {
            return new CoopPlanarGateEntry[0];
        }
        final CoopPlanarGateEntry[] out = new CoopPlanarGateEntry[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }
}
