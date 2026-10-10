package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO5 host → guest: the partner character blob for this world, or a create prompt.
 * Carries the host's live Standard window set codes so the partner follows rotation.
 * Optional {@link #rejectReason} explains a prior create rejection (empty when none).
 */
public class CoopPartnerOfferEvent implements NetEvent {
    private static final long serialVersionUID = 3L;

    private final String profileId;
    private final boolean needCreate;
    private final boolean allowCopySoloDeck;
    private final byte[] partnerBlob;
    /** Host live Standard window set codes (oldest first); may be empty. */
    private final String[] hostStandardSets;
    /** When re-prompting after a rejected create; empty when not a reject. */
    private final String rejectReason;

    public CoopPartnerOfferEvent(final String profileId, final boolean needCreate,
                                 final boolean allowCopySoloDeck, final byte[] partnerBlob,
                                 final String[] hostStandardSets) {
        this(profileId, needCreate, allowCopySoloDeck, partnerBlob, hostStandardSets, "");
    }

    public CoopPartnerOfferEvent(final String profileId, final boolean needCreate,
                                 final boolean allowCopySoloDeck, final byte[] partnerBlob,
                                 final String[] hostStandardSets, final String rejectReason) {
        this.profileId = profileId != null ? profileId : "";
        this.needCreate = needCreate;
        this.allowCopySoloDeck = allowCopySoloDeck;
        this.partnerBlob = partnerBlob != null ? partnerBlob.clone() : new byte[0];
        this.hostStandardSets = copySets(hostStandardSets);
        this.rejectReason = rejectReason != null ? rejectReason : "";
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

    public String[] getHostStandardSets() {
        return copySets(hostStandardSets);
    }

    /** Host reject reason for a prior create; empty when this is not a reject re-prompt. */
    public String getRejectReason() {
        return rejectReason != null ? rejectReason : "";
    }

    private static String[] copySets(final String[] src) {
        if (src == null || src.length == 0) {
            return new String[0];
        }
        final String[] out = new String[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }
}
