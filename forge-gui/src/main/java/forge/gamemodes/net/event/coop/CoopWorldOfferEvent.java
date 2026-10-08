package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest after a successful hello: enough info for the guest to rebuild
 * the world from seed + plane config and verify {@link #worldHash}.
 *
 * <p>MV1: {@link #worldPlaneId} / {@link #worldConfigPath} identify the host's
 * current overworld plane inside the adventure pack ({@link #planeId}).
 */
public class CoopWorldOfferEvent implements NetEvent {
    private static final long serialVersionUID = 2L;

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

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort) {
        this(hostPlayerName, planeId, planeConfigHash, worldSeed, worldHash, gamePort, overworldPort,
                null, null);
    }

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort,
                               final String worldPlaneId, final String worldConfigPath) {
        this.hostPlayerName = hostPlayerName;
        this.planeId = planeId;
        this.planeConfigHash = planeConfigHash;
        this.worldSeed = worldSeed;
        this.worldHash = worldHash;
        this.gamePort = gamePort;
        this.overworldPort = overworldPort;
        this.worldPlaneId = worldPlaneId;
        this.worldConfigPath = worldConfigPath;
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
}
