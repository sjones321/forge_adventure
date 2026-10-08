package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** Graceful session end. Guest should save their character locally on receipt. */
public class CoopDisconnectEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String reason;

    public CoopDisconnectEvent(final String reason) {
        this.reason = reason != null ? reason : "disconnected";
    }

    public String getReason() {
        return reason;
    }
}
