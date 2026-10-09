package forge.adventure.player;

import forge.adventure.util.Config;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Account-wide Ascendant data that lives <em>outside</em> the save slot and is
 * kept through prestige / NG+. Hall of Fame, prestige tree and achievements
 * (AC1) all sit in {@code USER_ADVENTURE_DIR/account/} (not per plane).
 *
 * <p>Co-op: each machine has its own {@link ForgeConstants#USER_ADVENTURE_DIR},
 * so a guest's account files stay local and are never part of the host world.
 */
public final class AccountStore {
    public static final String ACHIEVEMENTS_FILE = "achievements.json";
    /** Reserved for package L / AC1 HoF stubs. */
    public static final String HALL_OF_FAME_FILE = "hall_of_fame.json";
    /** Reserved for package M. */
    public static final String PRESTIGE_FILE = "prestige.json";

    private AccountStore() {
    }

    /** {@code USER_ADVENTURE_DIR/account/}. */
    public static File accountDir() {
        File dir = new File(ForgeConstants.USER_ADVENTURE_DIR + "account");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File achievementsFile() {
        File dest = new File(accountDir(), ACHIEVEMENTS_FILE);
        migrateLegacyAchievementsIfNeeded(dest, new File(ForgeConstants.USER_ADVENTURE_DIR));
        return dest;
    }

    public static File hallOfFameFile() {
        return new File(accountDir(), HALL_OF_FAME_FILE);
    }

    /**
     * Move a legacy per-plane achievements file into the account-wide path when
     * the new path is missing. Prefer the current plane's file, then Ascendant.
     */
    static void migrateLegacyAchievementsIfNeeded(File dest) {
        migrateLegacyAchievementsIfNeeded(dest, new File(ForgeConstants.USER_ADVENTURE_DIR));
    }

    /**
     * Migrate under an explicit adventure root (production uses
     * {@link ForgeConstants#USER_ADVENTURE_DIR}).
     */
    static void migrateLegacyAchievementsIfNeeded(File dest, File adventureRoot) {
        if (dest == null || dest.isFile() || adventureRoot == null) {
            return;
        }
        File legacy = findLegacyAchievementsFile(adventureRoot);
        if (legacy == null || !legacy.isFile()) {
            return;
        }
        try {
            Path parent = dest.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.copy(legacy.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            // Keep the legacy copy as a backup (rename) so a failed write never loses data.
            File bak = new File(legacy.getParentFile(), ACHIEVEMENTS_FILE + ".migrated");
            //noinspection ResultOfMethodCallIgnored
            legacy.renameTo(bak);
        } catch (IOException ignored) {
            // Leave the legacy file; next load can retry.
        }
    }

    /** Visible for tests. */
    static File findLegacyAchievementsFile() {
        return findLegacyAchievementsFile(new File(ForgeConstants.USER_ADVENTURE_DIR));
    }

    static File findLegacyAchievementsFile(File adventureRoot) {
        if (adventureRoot == null) {
            return null;
        }
        String plane = null;
        try {
            if (Config.instance() != null) {
                plane = Config.instance().getPlane();
            }
        } catch (Throwable ignored) {
        }
        if (plane != null && !plane.isEmpty()) {
            File f = legacyFileForPlane(adventureRoot, plane);
            if (f.isFile()) {
                return f;
            }
        }
        File ascendant = legacyFileForPlane(adventureRoot, "Shandalar Ascendant");
        if (ascendant.isFile()) {
            return ascendant;
        }
        // Scan adventure dir for any */account/achievements.json.
        File[] children = adventureRoot.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (!child.isDirectory() || "account".equals(child.getName())) {
                continue;
            }
            File f = new File(new File(child, "account"), ACHIEVEMENTS_FILE);
            if (f.isFile()) {
                return f;
            }
        }
        return null;
    }

    static File legacyFileForPlane(String plane) {
        return legacyFileForPlane(new File(ForgeConstants.USER_ADVENTURE_DIR), plane);
    }

    static File legacyFileForPlane(File adventureRoot, String plane) {
        return new File(new File(new File(adventureRoot, plane), "account"), ACHIEVEMENTS_FILE);
    }

    /** Test helper: account dir under an explicit root. */
    public static File accountDir(File root) {
        File dir = new File(root, "account");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File achievementsFile(File root) {
        File dest = new File(accountDir(root), ACHIEVEMENTS_FILE);
        migrateLegacyAchievementsIfNeeded(dest, root);
        return dest;
    }
}
