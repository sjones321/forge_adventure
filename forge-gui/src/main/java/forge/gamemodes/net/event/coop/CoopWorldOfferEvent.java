package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest after a successful hello: enough info for the guest to rebuild
 * the world from seed + plane config and verify {@link #worldHash}.
 */
public class CoopWorldOfferEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String hostPlayerName;
    private final String planeId;
    private final String planeConfigHash;
    private final long worldSeed;
    private final String worldHash;
    private final int gamePort;
    private final int overworldPort;

    public CoopWorldOfferEvent(final String hostPlayerName, final String planeId, final String planeConfigHash,
                               final long worldSeed, final String worldHash,
                               final int gamePort, final int overworldPort) {
        this.hostPlayerName = hostPlayerName;
        this.planeId = planeId;
        this.planeConfigHash = planeConfigHash;
        this.worldSeed = worldSeed;
        this.worldHash = worldHash;
        this.gamePort = gamePort;
        this.overworldPort = overworldPort;
    }

    public String getHostPlayerName() {
        return hostPlayerName;
    }

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
}
