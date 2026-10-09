package forge.adventure.world;

import forge.adventure.data.ConfigData;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.Config;

/**
 * MV2 planar alignment for a set plane relative to the player's Standard window.
 * Aligned planes use the normal portal cost; drifted (rotated-out) planes cost more.
 */
public enum PlaneAlignment {
    /** Set is in the current Standard window — normal portal. */
    ALIGNED,
    /** Set was unlocked but rotated out — costlier portal. */
    DRIFTED,
    /** Set has never been unlocked — not reachable via normal play. */
    LOCKED,
    /** Home plane (or no set code). */
    HOME;

    public static PlaneAlignment of(String setCode, StandardWindow window) {
        if (setCode == null || setCode.isEmpty()) {
            return HOME;
        }
        // Canonicalise case for window lists (codes are usually uppercase).
        String code = setCode;
        if (window != null) {
            for (String s : window.getSets()) {
                if (s != null && s.equalsIgnoreCase(setCode)) {
                    return ALIGNED;
                }
            }
            for (String s : window.getUnlockedHistory()) {
                if (s != null && s.equalsIgnoreCase(setCode)) {
                    return DRIFTED;
                }
            }
        }
        // Unknown / free-form plane ids (MV1 console "set_demo") stay reachable so
        // MV1 saves and debug planes are not locked out by MV2 alignment.
        if (!isKnownEdition(code)) {
            return ALIGNED;
        }
        if (window == null || !window.isActive()) {
            return LOCKED;
        }
        return LOCKED;
    }

    private static boolean isKnownEdition(String code) {
        try {
            return forge.model.FModel.getMagicDb().getEditions().get(code) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    public static PlaneAlignment ofPlane(PlaneMeta meta, StandardWindow window) {
        if (meta == null || meta.getKind() == PlaneKind.HOME || PlaneMeta.HOME_ID.equals(meta.getId())) {
            return HOME;
        }
        String code = meta.getSetCode();
        if (code == null || code.isEmpty()) {
            code = SetPlaneGenerator.setCodeFromPlaneId(meta.getId());
        }
        return of(code, window);
    }

    /** Gold charged to use a portal to this alignment. Tunable via ConfigData MV2 block. */
    public int portalGoldCost(ConfigData cfg) {
        if (cfg == null) {
            cfg = safeCfg();
        }
        return switch (this) {
            case HOME, ALIGNED -> Math.max(0, cfg != null ? cfg.alignedPortalGoldCost : 0);
            case DRIFTED -> Math.max(0, cfg != null ? cfg.rotatedOutPortalGoldCost : 500);
            case LOCKED -> -1; // sentinel: refuse
        };
    }

    public boolean isReachable() {
        return this != LOCKED;
    }

    private static ConfigData safeCfg() {
        try {
            return Config.instance().getConfigData();
        } catch (Throwable t) {
            return null;
        }
    }
}
