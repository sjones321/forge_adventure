package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * DS4: host → guest take-back confirmation or denial. Matched by
 * {@link #getRequestId()}. Hosts ignore inbound results.
 */
public class CoopTakeBackResultEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final int playerId;
    private final boolean accepted;
    private final String reason;

    public CoopTakeBackResultEvent(final long requestId, final int playerId, final boolean accepted,
                                   final String reason) {
        this.requestId = requestId;
        this.playerId = playerId;
        this.accepted = accepted;
        this.reason = reason == null ? "" : reason;
    }

    public long getRequestId() {
        return requestId;
    }

    public int getPlayerId() {
        return playerId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }
}
