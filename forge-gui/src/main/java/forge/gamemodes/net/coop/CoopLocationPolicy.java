package forge.gamemodes.net.coop;

/**
 * CO2 interior / location rule (v1).
 *
 * <p><b>Rule:</b> both players roam the overworld freely. Entering a town,
 * dungeon or delve invites a nearby party partner. Accept → the partner enters
 * the <em>same</em> interior. Decline / timeout → the partner stays outside.
 * Only one shared interior is active at a time: while either player is inside,
 * the other cannot open a <em>different</em> interior (they may wait on the
 * overworld, or enter the same one if still invited). Different interiors at
 * once are deferred past v1.
 */
public final class CoopLocationPolicy {
    public enum InteriorOccupancy { NONE, LOCAL, PARTNER, BOTH }

    private volatile InteriorOccupancy occupancy = InteriorOccupancy.NONE;
    private volatile String activePoiId = "";
    private volatile long pendingInviteId;
    private volatile String pendingPoiId = "";

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

    /**
     * Whether the local player may begin entering {@code poiId}. Blocked when
     * the partner is already inside a different POI.
     */
    public boolean canEnter(final String poiId) {
        final String id = CoopWireLimits.clampString(poiId, CoopWireLimits.MAX_POI_ID_LEN);
        if (id.isEmpty()) {
            return false;
        }
        if (occupancy == InteriorOccupancy.NONE) {
            return true;
        }
        // Same interior is always fine (re-entry / accept path).
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
        if (occupancy == InteriorOccupancy.LOCAL || occupancy == InteriorOccupancy.BOTH) {
            occupancy = InteriorOccupancy.BOTH;
        } else {
            occupancy = InteriorOccupancy.PARTNER;
        }
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
        if (occupancy == InteriorOccupancy.BOTH) {
            occupancy = InteriorOccupancy.LOCAL;
        } else if (occupancy == InteriorOccupancy.PARTNER) {
            occupancy = InteriorOccupancy.NONE;
            activePoiId = "";
        }
    }

    public void setPendingInvite(final long inviteId, final String poiId) {
        pendingInviteId = inviteId;
        pendingPoiId = CoopWireLimits.clampString(poiId, CoopWireLimits.MAX_POI_ID_LEN);
    }

    public void clearPending() {
        pendingInviteId = 0L;
        pendingPoiId = "";
    }

    /** Disconnect / session end. */
    public void reset() {
        occupancy = InteriorOccupancy.NONE;
        activePoiId = "";
        clearPending();
    }
}
