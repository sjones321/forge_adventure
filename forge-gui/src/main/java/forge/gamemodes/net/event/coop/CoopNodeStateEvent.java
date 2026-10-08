package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: host-authoritative resource-node spawn / despawn / claim broadcast.
 * Guests apply these; they never invent nodes.
 */
public class CoopNodeStateEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum Action { SPAWN, DESPAWN, CLAIMED }

    private final long nodeId;
    private final Action action;
    private final String materialId;
    private final float x;
    private final float y;
    private final String claimedBy;

    public CoopNodeStateEvent(final long nodeId, final Action action, final String materialId,
                              final float x, final float y, final String claimedBy) {
        this.nodeId = nodeId;
        this.action = action == null ? Action.DESPAWN : action;
        this.materialId = materialId == null ? "" : materialId;
        this.x = x;
        this.y = y;
        this.claimedBy = claimedBy == null ? "" : claimedBy;
    }

    public long getNodeId() {
        return nodeId;
    }

    public Action getAction() {
        return action;
    }

    public String getMaterialId() {
        return materialId;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public String getClaimedBy() {
        return claimedBy;
    }
}
