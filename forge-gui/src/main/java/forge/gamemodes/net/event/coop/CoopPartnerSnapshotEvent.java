package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO5 guest → host: full partner character snapshot after a duel, debounced
 * gather/craft batches, or leave. Host validates and stores in the world save.
 */
public class CoopPartnerSnapshotEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String profileId;
    private final byte[] partnerBlob;

    public CoopPartnerSnapshotEvent(final String profileId, final byte[] partnerBlob) {
        this.profileId = profileId != null ? profileId : "";
        this.partnerBlob = partnerBlob != null ? partnerBlob.clone() : new byte[0];
    }

    public String getProfileId() {
        return profileId;
    }

    public byte[] getPartnerBlob() {
        return partnerBlob.clone();
    }
}
