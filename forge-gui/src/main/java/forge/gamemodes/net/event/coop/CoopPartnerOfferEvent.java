package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO5 host → guest: the partner character blob for this world, or a create prompt.
 * Plain-data only — deflated {@code SaveFileData} bytes, never live player graphs.
 */
public class CoopPartnerOfferEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String profileId;
    /** True when the host has no partner yet and the guest must create one. */
    private final boolean needCreate;
    /** Host setting: guest may copy one solo deck into the new partner. */
    private final boolean allowCopySoloDeck;
    /** Deflated partner SaveFileData; empty when {@link #needCreate}. */
    private final byte[] partnerBlob;

    public CoopPartnerOfferEvent(final String profileId, final boolean needCreate,
                                 final boolean allowCopySoloDeck, final byte[] partnerBlob) {
        this.profileId = profileId != null ? profileId : "";
        this.needCreate = needCreate;
        this.allowCopySoloDeck = allowCopySoloDeck;
        this.partnerBlob = partnerBlob != null ? partnerBlob.clone() : new byte[0];
    }

    public String getProfileId() {
        return profileId;
    }

    public boolean isNeedCreate() {
        return needCreate;
    }

    public boolean isAllowCopySoloDeck() {
        return allowCopySoloDeck;
    }

    public byte[] getPartnerBlob() {
        return partnerBlob.clone();
    }
}
