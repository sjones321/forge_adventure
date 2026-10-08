package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** Host → guest: accept or deny a {@link CoopEnemyEncounterRequestEvent}. */
public class CoopFightRequestResultEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum Decision {
        ACCEPT,
        DENY
    }

    private final long requestId;
    private final Decision decision;
    private final String reason;

    public CoopFightRequestResultEvent(final long requestId, final Decision decision, final String reason) {
        this.requestId = requestId;
        this.decision = decision != null ? decision : Decision.DENY;
        this.reason = reason != null ? reason : "";
    }

    public long getRequestId() {
        return requestId;
    }

    public Decision getDecision() {
        return decision;
    }

    public String getReason() {
        return reason;
    }
}
