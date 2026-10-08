package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Bidirectional: session is ready for CO2 (overworld) / CO3 (duels). Host is
 * the world authority; each peer keeps their own character locally.
 */
public class CoopSessionReadyEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final boolean host;
    private final String peerName;
    private final String worldHash;

    public CoopSessionReadyEvent(final boolean host, final String peerName, final String worldHash) {
        this.host = host;
        this.peerName = peerName;
        this.worldHash = worldHash;
    }

    public boolean isHost() {
        return host;
    }

    public String getPeerName() {
        return peerName;
    }

    public String getWorldHash() {
        return worldHash;
    }
}
