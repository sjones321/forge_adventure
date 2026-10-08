package forge.gamemodes.net.coop;

/**
 * Bound checks for Ascendant co-op overworld messages (CO2). Tunables that
 * affect gameplay cadence live in ConfigData; these are hard safety ceilings
 * so a malicious or buggy peer cannot blow memory or flood the host.
 */
public final class CoopWireLimits {
    public static final int MAX_PLAYER_NAME_LEN = 48;
    public static final int MAX_AVATAR_ID_LEN = 128;
    public static final int MAX_MATERIAL_ID_LEN = 64;
    public static final int MAX_ENEMY_DATA_ID_LEN = 64;
    public static final int MAX_POI_ID_LEN = 96;
    public static final int MAX_DISPLAY_NAME_LEN = 96;
    public static final int MAX_REASON_LEN = 160;
    public static final int MAX_DETAIL_LEN = 160;

    /** Absolute world-coordinate ceiling (tiles * tileSize well below this). */
    public static final float MAX_COORD_ABS = 1_000_000f;
    /** Facing ordinal must be in {@code [0, MAX_FACING_ORDINAL]}. */
    public static final int MAX_FACING_ORDINAL = 8;
    /** Gather / interact range in world pixels (host validates guest distance). */
    public static final float DEFAULT_INTERACT_RANGE_PX = 96f;
    /** Hard ceiling on gather amount reported on the wire. */
    public static final int MAX_GATHER_AMOUNT = 99;

    private CoopWireLimits() {
    }

    public static String clampString(final String value, final int maxLen) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLen) {
            return value;
        }
        return value.substring(0, maxLen);
    }

    public static boolean isFinite(final float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }

    public static boolean coordsInBounds(final float x, final float y) {
        return isFinite(x) && isFinite(y)
                && Math.abs(x) <= MAX_COORD_ABS
                && Math.abs(y) <= MAX_COORD_ABS;
    }

    public static boolean facingInBounds(final float facing) {
        if (!isFinite(facing)) {
            return false;
        }
        final int ord = Math.round(facing);
        return ord >= 0 && ord <= MAX_FACING_ORDINAL && Math.abs(facing - ord) < 0.01f;
    }
}
