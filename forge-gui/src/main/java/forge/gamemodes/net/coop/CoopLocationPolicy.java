package forge.gamemodes.net.coop;

/**
 * CO2 interior / location rule (v1) with invite expiry.
 *
 * <p>Accept only marks the partner as "accepted invite" — they are marked
 * inside when they actually enter ({@link #markLocalEntered} /
 * {@link #markPartnerEntered}).
 */
public final class CoopLocationPolicy {
    public enum InteriorOccupancy { NONE, LOCAL, PARTNER, BOTH }

    private volatile InteriorOccupancy occupancy = InteriorOccupancy.NONE;
    private volatile String activePoiId = "";
    private volatile long pendingInviteId;
    private volatile String pendingPoiId = "";
    private volatile long pendingSinceMs;
    /** Peer accepted the come-along invite but has not entered yet. */
    private volatile boolean partnerAcceptedPending;

    public InteriorOccupancy getOccupancy() {
        return occupancy;
    }

    public String getActivePoiId() {
        return activePoiId;
    }

    public long getPendingInviteId() {
        return pendingInviteId;
    }

    public String getPendingPoiId() {
        return pendingPoiId;
    }

    public boolean isPartnerAcceptedPending() {
        return partnerAcceptedPending;
    }

    public boolean canEnter(final String poiId) {
        final String id = CoopWireLimits.clampString(poiId, CoopWireLimits.MAX_POI_ID_LEN);
        if (id.isEmpty()) {
            return false;
        }
        if (occupancy == InteriorOccupancy.NONE) {
            return true;
        }
        return id.equals(activePoiId);
    }

    public void markLocalEntered(final String poiId) {
        final String id = CoopWireLimits.clampString(poiId, CoopWireLimits.MAX_POI_ID_LEN);
        activePoiId = id;
        if (occupancy == InteriorOccupancy.PARTNER || occupancy == InteriorOccupancy.BOTH) {
            occupancy = InteriorOccupancy.BOTH;
        } else {
            occupancy = InteriorOccupancy.LOCAL;
        }
        clearPending();
    }

    public void markPartnerEntered(final String poiId) {
        final String id = CoopWireLimits.clampString(poiId, CoopWireLimits.MAX_POI_ID_LEN);
        activePoiId = id;
        partnerAcceptedPending = false;
        if (occupancy == InteriorOccupancy.LOCAL || occupancy == InteriorOccupancy.BOTH) {
            occupancy = InteriorOccupancy.BOTH;
        } else {
            occupancy = InteriorOccupancy.PARTNER;
        }
    }

    /** Peer accepted the invite; do not mark inside until they enter. */
    public void markPartnerAcceptedInvite() {
        partnerAcceptedPending = true;
    }

    public void markLocalExited() {
        if (occupancy == InteriorOccupancy.BOTH) {
            occupancy = InteriorOccupancy.PARTNER;
        } else if (occupancy == InteriorOccupancy.LOCAL) {
            occupancy = InteriorOccupancy.NONE;
            activePoiId = "";
        }
    }

    public void markPartnerExited() {
        partnerAcceptedPending = false;
        if (occupancy == InteriorOccupancy.BOTH) {
            occupancy = InteriorOccupancy.LOCAL;
        } else if (occupancy == InteriorOccupancy.PARTNER) {
            occupancy = InteriorOccupancy.NONE;
            activePoiId = "";
        }
    }

    public void setPendingInvite(final long inviteId, final String poiId, final long nowMs) {
        pendingInviteId = inviteId;
        pendingPoiId = CoopWireLimits.clampString(poiId, CoopWireLimits.MAX_POI_ID_LEN);
        pendingSinceMs = nowMs;
    }

    public void setPendingInvite(final long inviteId, final String poiId) {
        setPendingInvite(inviteId, poiId, System.currentTimeMillis());
    }

    public boolean expireIfNeeded(final long nowMs, final long timeoutMs) {
        if (timeoutMs <= 0L || pendingInviteId <= 0L || pendingSinceMs <= 0L) {
            return false;
        }
        if (nowMs - pendingSinceMs < timeoutMs) {
            return false;
        }
        clearPending();
        return true;
    }

    public void clearPending() {
        pendingInviteId = 0L;
        pendingPoiId = "";
        pendingSinceMs = 0L;
    }

    public void reset() {
        occupancy = InteriorOccupancy.NONE;
        activePoiId = "";
        partnerAcceptedPending = false;
        clearPending();
    }
}
