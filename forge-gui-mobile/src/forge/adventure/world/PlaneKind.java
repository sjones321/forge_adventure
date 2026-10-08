package forge.adventure.world;

/**
 * MV1 plane kinds inside one Ascendant save.
 * Distinct from {@link forge.adventure.util.Config#getPlane()} (adventure content pack).
 */
public enum PlaneKind {
    HOME,
    SET;

    public static PlaneKind fromSave(String raw) {
        if (raw == null || raw.isEmpty()) {
            return HOME;
        }
        try {
            return PlaneKind.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return HOME;
        }
    }
}
