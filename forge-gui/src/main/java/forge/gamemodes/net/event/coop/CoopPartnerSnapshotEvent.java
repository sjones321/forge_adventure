package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO5 guest → host: full partner character snapshot after a duel, debounced
 * inventory change, or leave. Host validates, stores, and acks with
 * {@link CoopPartnerSnapshotAckEvent}.
 */
public class CoopPartnerSnapshotEvent implements NetEvent {
    private static final long serialVersionUID = 2L;

    private final String profileId;
    private final long sequence;
    /** True for the final leave snapshot — host must not rate-limit these. */
    private final boolean finalSnapshot;
    private final byte[] partnerBlob;

    public CoopPartnerSnapshotEvent(final String profileId, final long sequence,
                                    final boolean finalSnapshot, final byte[] partnerBlob) {
        this.profileId = profileId != null ? profileId : "";
        this.sequence = sequence;
        this.finalSnapshot = finalSnapshot;
        this.partnerBlob = partnerBlob != null ? partnerBlob.clone() : new byte[0];
    }

    public String getProfileId() {
        return profileId;
    }

    public long getSequence() {
        return sequence;
    }

    public boolean isFinalSnapshot() {
        return finalSnapshot;
    }

    public byte[] getPartnerBlob() {
        return partnerBlob.clone();
    }
}
