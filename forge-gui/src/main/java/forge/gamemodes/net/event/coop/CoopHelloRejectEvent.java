package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** Host → guest: hard reject (version / card DB / protocol / busy). */
public class CoopHelloRejectEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String reason;

    public CoopHelloRejectEvent(final String reason) {
        this.reason = reason != null ? reason : "Connection refused";
    }

    public String getReason() {
        return reason;
    }
}
