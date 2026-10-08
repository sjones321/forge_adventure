package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: host → guest gather confirmation or denial. Matched by
 * {@link #getRequestId()} (and {@code claimedBy}); hosts ignore inbound results.
 */
public class CoopGatherResultEvent implements NetEvent {
    private static final long serialVersionUID = 2L;

    private final long requestId;
    private final long nodeId;
    private final boolean accepted;
    private final String claimedBy;
    private final String materialId;
    private final int amount;
    private final String reason;

    public CoopGatherResultEvent(final long requestId, final long nodeId, final boolean accepted,
                                 final String claimedBy, final String materialId, final int amount,
                                 final String reason) {
        this.requestId = requestId;
        this.nodeId = nodeId;
        this.accepted = accepted;
        this.claimedBy = claimedBy == null ? "" : claimedBy;
        this.materialId = materialId == null ? "" : materialId;
        this.amount = amount;
        this.reason = reason == null ? "" : reason;
    }

    public long getRequestId() {
        return requestId;
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
