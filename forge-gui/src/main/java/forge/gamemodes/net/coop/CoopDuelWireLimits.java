package forge.gamemodes.net.coop;

/**
 * Hard safety ceilings for Ascendant co-op duel messages (CO3). Gameplay
 * tunables (invite timeout, scaling) live in ConfigData; these bound inbound
 * wire values so a buggy or malicious peer cannot blow memory.
 */
public final class CoopDuelWireLimits {
    /** Player / avatar / encounter name length (roadmap: names 32). */
    public static final int MAX_NAME_LEN = 32;
    /** Short prompt / reason text (roadmap: text 200). */
    public static final int MAX_TEXT_LEN = 200;
    /** Decklist text ceiling (UTF-8 characters). */
    public static final int MAX_DECKLIST_CHARS = 200_000;
    /** Absolute max main-deck cards accepted from a peer decklist. */
    public static final int MAX_DECK_CARDS = 250;
    /** Absolute max sideboard cards. */
    public static final int MAX_SIDEBOARD_CARDS = 100;
    /** Absolute max command-zone / commander names in a loadout. */
    public static final int MAX_COMMAND_CARDS = 16;
    /** Absolute max effect card-name entries in a loadout. */
    public static final int MAX_EFFECT_CARD_NAMES = 64;
    /** Absolute max equipped item ids in a loadout. */
    public static final int MAX_EQUIPPED_ITEMS = 32;
    /** Life / shard / count ceiling. */
    public static final int MAX_STAT = 99_999;
    /** Avatar index / id string length. */
    public static final int MAX_AVATAR_ID_LEN = 64;
    /** Max fight / duel requests accepted per peer per second. */
    public static final int MAX_DUEL_REQUESTS_PER_SECOND = 4;

    private CoopDuelWireLimits() {
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

    public static boolean nameOk(final String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_NAME_LEN;
    }

    public static boolean textOk(final String value) {
        return value == null || value.length() <= MAX_TEXT_LEN;
    }

    public static boolean decklistSizeOk(final String text) {
        return text != null && text.length() <= MAX_DECKLIST_CHARS;
    }

    public static boolean countInRange(final int value, final int min, final int max) {
        return value >= min && value <= max;
    }
}
