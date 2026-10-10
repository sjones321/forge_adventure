package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO5 host → guest: partner snapshot stored. Guest shows "progress saved".
 */
public class CoopPartnerSnapshotAckEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String profileId;
    private final long sequence;
    private final boolean accepted;
    private final String reason;

    public CoopPartnerSnapshotAckEvent(final String profileId, final long sequence,
                                       final boolean accepted, final String reason) {
        this.profileId = profileId != null ? profileId : "";
        this.sequence = sequence;
        this.accepted = accepted;
        this.reason = reason != null ? reason : "";
    }

    public String getProfileId() {
        return profileId;
    }

    public long getSequence() {
        return sequence;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getReason() {
        return reason;
    }
}
