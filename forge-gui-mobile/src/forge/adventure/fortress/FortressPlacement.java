package forge.adventure.fortress;

import forge.adventure.data.ConfigData;

import java.util.Collection;

/**
 * Banner placement rules for FT1. Pure logic — no LibGDX / WorldSave dependency so tests
 * can drive it with fake tile / POI lists.
 */
public final class FortressPlacement {
    private FortressPlacement() {
    }

    public enum RejectReason {
        OK,
        NOT_ASCENDANT,
        GUEST_FORBIDDEN,
        NOT_WALKABLE,
        TOO_CLOSE_TO_POI,
        PLANE_AT_CAP,
        MISSING_TEMPLATE,
        NO_BANNER
    }

    /**
     * Tile-distance check from a candidate tile to every existing POI tile.
     * {@code minDistanceTiles} is inclusive of the exclusion radius (must be strictly
     * greater than or equal to this distance).
     */
    public static boolean isFarEnoughFromPois(int tileX, int tileY,
                                              Collection<int[]> poiTileXY,
                                              float minDistanceTiles) {
        if (poiTileXY == null || poiTileXY.isEmpty())
            return true;
        float min = Math.max(0f, minDistanceTiles);
        for (int[] poi : poiTileXY) {
            if (poi == null || poi.length < 2)
                continue;
            float dx = tileX - poi[0];
            float dy = tileY - poi[1];
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist < min)
                return false;
        }
        return true;
    }

    public static RejectReason validate(boolean ascendant, boolean guestForbidden,
                                        boolean walkable, boolean farEnough,
                                        int existingFortressesOnPlane, ConfigData cfg) {
        if (!ascendant)
            return RejectReason.NOT_ASCENDANT;
        if (guestForbidden)
            return RejectReason.GUEST_FORBIDDEN;
        if (!walkable)
            return RejectReason.NOT_WALKABLE;
        if (!farEnough)
            return RejectReason.TOO_CLOSE_TO_POI;
        int max = cfg != null ? Math.max(0, cfg.fortressMaxPerPlane) : 1;
        if (existingFortressesOnPlane >= max)
            return RejectReason.PLANE_AT_CAP;
        return RejectReason.OK;
    }

    public static String message(RejectReason reason, ConfigData cfg) {
        float dist = cfg != null ? cfg.fortressBannerMinDistanceTiles : 8f;
        switch (reason) {
            case OK:
                return "Fortress claimed.";
            case NOT_ASCENDANT:
                return "Fortresses require Shandalar Ascendant.";
            case GUEST_FORBIDDEN:
                return "Guests cannot plant a fortress banner in the host's world.";
            case NOT_WALKABLE:
                return "Need walkable land to plant the banner.";
            case TOO_CLOSE_TO_POI:
                return "Too close to a town or landmark (need " + (int) dist + "+ tiles clear).";
            case PLANE_AT_CAP:
                return "This plane already has a fortress.";
            case MISSING_TEMPLATE:
                return "Fortress template missing from points_of_interest.";
            case NO_BANNER:
                return "You need a Fortress Banner to claim a site.";
            default:
                return "Cannot plant banner here.";
        }
    }
}
