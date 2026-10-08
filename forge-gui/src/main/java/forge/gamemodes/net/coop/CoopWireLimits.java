package forge.gamemodes.net.coop;

import java.util.regex.Pattern;

/**
 * Bound checks for Ascendant co-op overworld messages (CO2). Tunables that
 * affect gameplay cadence live in ConfigData; these are hard safety ceilings
 * so a malicious or buggy peer cannot blow memory or flood the host.
 */
public final class CoopWireLimits {
    /** Player / peer display names. */
    public static final int MAX_PLAYER_NAME_LEN = 32;
    /** Reasons, details, display labels, material ids, etc. */
    public static final int MAX_TEXT_LEN = 200;
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
    /** Default max travel speed (px/s) for teleport rejection (+ margin). */
    public static final float DEFAULT_MAX_MOVE_SPEED_PX = 120f;
    /** Multiplier on max speed for network jitter / road/sprint bonuses. */
    public static final float MOVE_SPEED_MARGIN = 1.75f;
    /** Hard ceiling on gather amount reported on the wire. */
    public static final int MAX_GATHER_AMOUNT = 99;

    /** Avatar ids must be hero atlas paths — never arbitrary paths or textures. */
    private static final Pattern AVATAR_WHITELIST =
            Pattern.compile("^sprites/heroes/[A-Za-z0-9_]+\\.atlas$");

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

    /** True when the avatar id is a whitelisted {@code sprites/heroes/*.atlas} path. */
    public static boolean isAllowedAvatarId(final String avatarId) {
        if (avatarId == null || avatarId.isEmpty() || avatarId.length() > MAX_AVATAR_ID_LEN) {
            return false;
        }
        return AVATAR_WHITELIST.matcher(avatarId).matches();
    }

    /**
     * Reject over-long names (do not silently clamp for security-sensitive fields).
     * @return clamped name, or {@code null} if over the limit / empty after trim
     */
    public static String acceptPlayerName(final String name) {
        if (name == null) {
            return "";
        }
        if (name.length() > MAX_PLAYER_NAME_LEN) {
            return null;
        }
        return name;
    }

    public static String acceptText(final String text) {
        if (text == null) {
            return "";
        }
        if (text.length() > MAX_TEXT_LEN) {
            return null;
        }
        return text;
    }
}
