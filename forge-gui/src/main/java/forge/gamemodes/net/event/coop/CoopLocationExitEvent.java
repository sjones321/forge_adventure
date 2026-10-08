package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: peer left an interior back to the overworld. Receiver calls
 * {@code CoopLocationPolicy#markPartnerExited()}.
 */
public class CoopLocationExitEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String poiId;
    private final String fromPlayer;

    public CoopLocationExitEvent(final String poiId, final String fromPlayer) {
        this.poiId = poiId == null ? "" : poiId;
        this.fromPlayer = fromPlayer == null ? "" : fromPlayer;
    }

    public String getPoiId() {
        return poiId;
    }

    public String getFromPlayer() {
        return fromPlayer;
    }
}
