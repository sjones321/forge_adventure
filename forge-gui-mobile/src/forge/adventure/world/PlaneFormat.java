package forge.adventure.world;

import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopSessionRole;
import forge.adventure.data.ConfigData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureTitles;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.GymUtil;

/**
 * Package K: one resolver for the current plane's duel format.
 *
 * <p>Formats live on {@link PlaneMeta}, not on the player. Legacy {@code runFormat}
 * on {@link AdventurePlayer} migrates onto the home plane when missing. Guests in
 * co-op use the host-synced format from {@link CoopSession}.
 */
public final class PlaneFormat {
    public static final String STANDARD = GymUtil.FORMAT_STANDARD;
    public static final String PAUPER = GymUtil.FORMAT_PAUPER;
    public static final String HISTORIC = GymUtil.FORMAT_HISTORIC;
    public static final String COMMANDER = GymUtil.FORMAT_COMMANDER;

    /** Display labels for New Game / portal UI (canonical stored value is Standard). */
    public static final String[] CHOICES = {
            AdventureTitles.STANDARD_FORMAT_DISPLAY,
            "Historic",
            "Pauper",
            "Commander"
    };

    private PlaneFormat() {
    }

    /** Config default for planes / saves with no format (Shandalar Standard). */
    public static String defaultFormat() {
        try {
            ConfigData cfg = Config.instance() != null ? Config.instance().getConfigData() : null;
            if (cfg != null && cfg.kDefaultPlaneFormat != null && !cfg.kDefaultPlaneFormat.isEmpty()) {
                return normalize(cfg.kDefaultPlaneFormat);
            }
        } catch (Throwable ignored) {
        }
        return STANDARD;
    }

    public static String normalize(String format) {
        if (format == null || format.isEmpty()) {
            return defaultFormat();
        }
        String f = format.trim();
        if (f.equalsIgnoreCase(PAUPER)) {
            return PAUPER;
        }
        if (f.equalsIgnoreCase(HISTORIC)) {
            return HISTORIC;
        }
        if (f.equalsIgnoreCase(COMMANDER)) {
            return COMMANDER;
        }
        // Player-facing name is Shandalar Standard; keep Bellwarden aliases for old saves/inputs.
        if (f.equalsIgnoreCase(STANDARD)
                || f.equalsIgnoreCase("Shandalar") || f.equalsIgnoreCase("Shandalar Standard")
                || f.equalsIgnoreCase("Bellwarden") || f.equalsIgnoreCase("Bellwarden Standard")) {
            return STANDARD;
        }
        return defaultFormat();
    }

    /** Map a CHOICES label or raw token to the stored canonical format. */
    public static String fromChoiceLabel(String label) {
        return normalize(label);
    }

    /** UI label for a canonical format. */
    public static String displayName(String format) {
        String f = normalize(format);
        if (STANDARD.equals(f)) {
            return AdventureTitles.STANDARD_FORMAT_DISPLAY;
        }
        return f;
    }

    public static boolean isKnown(String format) {
        if (format == null || format.isEmpty()) {
            return false;
        }
        String f = format.trim();
        return f.equalsIgnoreCase(STANDARD) || f.equalsIgnoreCase(PAUPER)
                || f.equalsIgnoreCase(HISTORIC) || f.equalsIgnoreCase(COMMANDER)
                || f.equalsIgnoreCase("Shandalar") || f.equalsIgnoreCase("Shandalar Standard")
                || f.equalsIgnoreCase("Bellwarden") || f.equalsIgnoreCase("Bellwarden Standard");
    }

    /**
     * Format stored on {@code meta}, or empty when unset (caller applies fallback).
     */
    public static String raw(PlaneMeta meta) {
        if (meta == null) {
            return "";
        }
        String f = meta.getFormat();
        return f != null ? f.trim() : "";
    }

    /** Resolve a plane's format with config default when unset. */
    public static String resolve(PlaneMeta meta) {
        String raw = raw(meta);
        if (raw.isEmpty()) {
            return defaultFormat();
        }
        return normalize(raw);
    }

    /**
     * The format that EN1, gyms, League and status should use right now.
     *
     * <ol>
     *   <li>Commander-mode run → always {@link #COMMANDER}</li>
     *   <li>Co-op guest: host-synced plane format (unknown → host default)</li>
     *   <li>Current plane meta when set</li>
     *   <li>Legacy player {@code runFormat} (pre-K saves / home migration)</li>
     *   <li>{@link #defaultFormat()}</li>
     * </ol>
     */
    public static String resolveCurrent() {
        try {
            AdventurePlayer p = Current.player();
            if (p != null && p.isCommanderMode()) {
                return COMMANDER;
            }
        } catch (Throwable ignored) {
        }

        try {
            CoopSession session = CoopSession.get();
            if (session != null && session.getRole() == CoopSessionRole.GUEST) {
                String guest = session.getGuestPlaneFormat();
                if (guest != null && !guest.isEmpty()) {
                    return normalize(guest);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            WorldSave save = WorldSave.getCurrentSave();
            if (save != null && save.getMultiverse() != null && Config.ascendant()) {
                PlaneMeta meta = save.getMultiverse().getCurrentMeta();
                String raw = raw(meta);
                if (!raw.isEmpty()) {
                    return normalize(raw);
                }
            }
        } catch (Throwable ignored) {
        }

        try {
            AdventurePlayer p = Current.player();
            if (p != null) {
                String legacy = p.getLegacyRunFormat();
                if (legacy != null && !legacy.isEmpty()) {
                    return normalize(legacy);
                }
            }
        } catch (Throwable ignored) {
        }

        return defaultFormat();
    }

    /**
     * Persist {@code format} on the plane (fixed once chosen). Also mirrors onto the
     * player's legacy {@code runFormat} when writing the home plane so old loaders
     * still see a value.
     */
    public static void setPlaneFormat(PlaneMeta meta, String format) {
        if (meta == null) {
            return;
        }
        String canonical = normalize(format);
        meta.setFormat(canonical);
        try {
            if (PlaneMeta.HOME_ID.equals(meta.getId())) {
                AdventurePlayer p = Current.player();
                if (p != null) {
                    p.setLegacyRunFormat(canonical);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Migrate a pre-K {@code runFormat} onto the home plane when the plane has none.
     * Set planes without a format keep the default until the portal dialog sets one.
     */
    public static void migrateLegacyRunFormat(MultiverseState multi, String legacyRunFormat) {
        if (multi == null) {
            return;
        }
        PlaneMeta home = multi.getMeta(PlaneMeta.HOME_ID);
        if (home == null) {
            return;
        }
        if (!raw(home).isEmpty()) {
            return;
        }
        if (legacyRunFormat != null && !legacyRunFormat.isEmpty() && isKnown(legacyRunFormat)) {
            home.setFormat(normalize(legacyRunFormat));
        } else {
            home.setFormat(defaultFormat());
        }
    }

    /** Whether overworld fights require a format-legal deck (tunable; default off). */
    public static boolean strictOverworldLegalDecks() {
        try {
            ConfigData cfg = Config.instance() != null ? Config.instance().getConfigData() : null;
            return cfg != null && cfg.kStrictOverworldLegalDecks;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Whether shops / non-enemy rewards should prefer the Standard window pool.
     * Historic / Pauper / Commander planes use a broader (or Pauper-filtered) pool.
     */
    public static boolean favorsStandardWindowPool() {
        return STANDARD.equals(resolveCurrent());
    }

    public static boolean favorsPauperPool() {
        return PAUPER.equals(resolveCurrent());
    }
}
