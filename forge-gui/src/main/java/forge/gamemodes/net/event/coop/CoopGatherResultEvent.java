package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: host → guest gather confirmation or denial. On accept the guest applies
 * their own loot/XP locally; the host already claimed the node.
 */
public class CoopGatherResultEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long nodeId;
    private final boolean accepted;
    private final String claimedBy;
    private final String materialId;
    private final int amount;
    private final String reason;

    public CoopGatherResultEvent(final long nodeId, final boolean accepted, final String claimedBy,
                                 final String materialId, final int amount, final String reason) {
        this.nodeId = nodeId;
        this.accepted = accepted;
        this.claimedBy = claimedBy == null ? "" : claimedBy;
        this.materialId = materialId == null ? "" : materialId;
        this.amount = amount;
        this.reason = reason == null ? "" : reason;
    }

    public long getNodeId() {
        return nodeId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getClaimedBy() {
        return claimedBy;
    }

    public String getMaterialId() {
        return materialId;
    }

    public int getAmount() {
        return amount;
    }

    public String getReason() {
        return reason;
    }
}
