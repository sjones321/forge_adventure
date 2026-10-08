package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: guest → host request to claim / complete gathering a resource node.
 * Host validates existence, range and claim state, then replies with
 * {@link CoopGatherResultEvent}.
 */
public class CoopGatherRequestEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long nodeId;
    private final float requesterX;
    private final float requesterY;
    private final long clientTimeMs;

    public CoopGatherRequestEvent(final long nodeId, final float requesterX, final float requesterY,
                                  final long clientTimeMs) {
        this.nodeId = nodeId;
        this.requesterX = requesterX;
        this.requesterY = requesterY;
        this.clientTimeMs = clientTimeMs;
    }

    public long getNodeId() {
        return nodeId;
    }

    public float getRequesterX() {
        return requesterX;
    }

    public float getRequesterY() {
        return requesterY;
    }

    public long getClientTimeMs() {
        return clientTimeMs;
    }
}
