package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * DS4: guest → host request to take back the requesting player's last
 * land/spell/ability. Plain data only — the host restores from its local
 * {@code GameSnapshot} and resyncs; no game graph travels the wire.
 */
public class CoopTakeBackRequestEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final int playerId;
    private final long clientTimeMs;

    public CoopTakeBackRequestEvent(final long requestId, final int playerId, final long clientTimeMs) {
        this.requestId = requestId;
        this.playerId = playerId;
        this.clientTimeMs = clientTimeMs;
    }

    public long getRequestId() {
        return requestId;
    }

    public int getPlayerId() {
        return playerId;
    }

    public long getClientTimeMs() {
        return clientTimeMs;
    }
}
