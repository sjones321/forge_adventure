package forge.gamemodes.net.coop;

/**
 * Hard ceilings for TR1 trade wire payloads. Gameplay cadence tunables live in
 * ConfigData; these stop a buggy/malicious peer from blowing memory or flooding.
 */
public final class CoopTradeWireLimits {
    /** Display / player names (matches {@link CoopWireLimits#MAX_PLAYER_NAME_LEN}). */
    public static final int MAX_NAME_LEN = CoopWireLimits.MAX_PLAYER_NAME_LEN;
    /** Reasons, details, material ids, item names, card names. */
    public static final int MAX_TEXT_LEN = CoopWireLimits.MAX_TEXT_LEN;
    public static final int MAX_SET_CODE_LEN = 16;
    public static final int MAX_MATERIAL_LINES = 32;
    public static final int MAX_ITEM_LINES = 32;
    public static final int MAX_CARD_LINES = 64;
    public static final int MAX_STACK_COUNT = 9999;
    public static final int MAX_GOLD = 1_000_000_000;
    /** Trade request / offer / confirm messages per peer per window. */
    public static final int DEFAULT_MAX_PER_WINDOW = 8;
    public static final long DEFAULT_WINDOW_MS = 1000L;

    private CoopTradeWireLimits() {
    }

    public static String clampName(final String value) {
        return CoopWireLimits.clampString(value, MAX_NAME_LEN);
    }

    public static String clampText(final String value) {
        return CoopWireLimits.clampString(value, MAX_TEXT_LEN);
    }

    public static boolean nameLenOk(final String value) {
        return value != null && value.length() <= MAX_NAME_LEN;
    }

    public static boolean textLenOk(final String value) {
        return value == null || value.length() <= MAX_TEXT_LEN;
    }
}
