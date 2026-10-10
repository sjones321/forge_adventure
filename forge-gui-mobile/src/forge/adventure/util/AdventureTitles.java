package forge.adventure.util;

/**
 * Player-facing titles for Adventure worlds and modes.
 * <p>
 * Internal plane folder ids (for example {@code Shandalar Ascendant}), save keys,
 * {@link Config#ascendant()}, and protocol strings stay on the Ascendant id so old
 * saves keep loading. This class only maps what players and readers see.
 */
public final class AdventureTitles {
    /** Content-pack folder / settings id (do not rename without a save migration). */
    public static final String ASCENDANT_PLANE_ID = "Shandalar Ascendant";
    /** Stock Shandalar world folder id — display name stays {@code Shandalar}. */
    public static final String STOCK_SHANDALAR_PLANE_ID = "Shandalar";
    /** Official game title for this Forge Ascendant content pack / mode. */
    public static final String GAME_TITLE = "Shandalar Ascendant";
    /**
     * Stored achievement title reward id (saves / account JSON). Do not rename —
     * old saves keep this string; use {@link #titleDisplayName(String)} for UI.
     */
    public static final String COMPLETIONIST_TITLE_ID = "Bellwarden Completionist";
    /** Player-facing label for {@link #COMPLETIONIST_TITLE_ID}. */
    public static final String COMPLETIONIST_TITLE_DISPLAY = "Shandalar Completionist";
    /** Player-facing Standard format label (canonical stored value stays {@code Standard}). */
    public static final String STANDARD_FORMAT_DISPLAY = "Shandalar Standard";

    private AdventureTitles() {
    }

    /**
     * Display label for an owned / equipped title id. Stored ids stay unchanged
     * so old account files keep the title.
     */
    public static String titleDisplayName(String titleId) {
        if (titleId == null || titleId.isEmpty()) {
            return "";
        }
        if (COMPLETIONIST_TITLE_ID.equals(titleId)) {
            return COMPLETIONIST_TITLE_DISPLAY;
        }
        return titleId;
    }

    /**
     * Status / profile name-line suffix for an equipped title id (markup matches
     * {@code PlayerStatisticScene}). Empty when nothing is equipped.
     */
    public static String statusTitleSuffix(String equippedTitleId) {
        if (equippedTitleId == null || equippedTitleId.isEmpty()) {
            return "";
        }
        return "  [%80][DARK_GRAY]" + titleDisplayName(equippedTitleId);
    }

    /** Full Status name line: gender/name prefix plus optional equipped title. */
    public static String statusPlayerNameLine(String nameWithGenderMarkup, String equippedTitleId) {
        if (nameWithGenderMarkup == null) {
            nameWithGenderMarkup = "";
        }
        return nameWithGenderMarkup + statusTitleSuffix(equippedTitleId);
    }

    /** Label shown in world / mode pickers for a plane folder id. */
    public static String planeDisplayName(String planeId) {
        if (planeId == null) {
            return "";
        }
        if (ASCENDANT_PLANE_ID.equals(planeId)) {
            return GAME_TITLE;
        }
        return planeId;
    }

    /** Inverse of {@link #planeDisplayName(String)} for combo-box selections. */
    public static String planeIdFromDisplayName(String displayName) {
        if (displayName == null) {
            return "";
        }
        if (GAME_TITLE.equals(displayName)) {
            return ASCENDANT_PLANE_ID;
        }
        return displayName;
    }

    public static boolean isAscendantPlaneId(String planeId) {
        return ASCENDANT_PLANE_ID.equals(planeId);
    }

    /** Window / app title when the Ascendant content pack is selected. */
    public static String windowTitle(String versionString) {
        return GAME_TITLE + " - " + versionString;
    }

    /** Stock Forge window title (any non-Ascendant plane, including Shandalar). */
    public static String forgeWindowTitle(String versionString) {
        return "Forge - " + versionString;
    }

    public static String resolveWindowTitle(String planeId, String versionString) {
        if (isAscendantPlaneId(planeId)) {
            return windowTitle(versionString);
        }
        return forgeWindowTitle(versionString);
    }
}
