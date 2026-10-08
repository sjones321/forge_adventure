package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Guest → host: local rebuild did not match {@link CoopWorldOfferEvent#getWorldHash()};
 * please send the world blob.
 */
public class CoopWorldRequestEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String localWorldHash;
    private final String reason;

    public CoopWorldRequestEvent(final String localWorldHash, final String reason) {
        this.localWorldHash = localWorldHash;
        this.reason = reason != null ? reason : "world hash mismatch";
    }

    public String getLocalWorldHash() {
        return localWorldHash;
    }

    public String getReason() {
        return reason;
    }
}
