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

    private AdventureTitles() {
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
