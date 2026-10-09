package forge.adventure.player;

import forge.adventure.util.Config;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;

/**
 * Account-wide Ascendant data that lives <em>outside</em> the save slot and is
 * kept through prestige / NG+. Hall of Fame, prestige tree and achievements
 * (AC1) all sit in this directory next to each other.
 *
 * <p>Co-op: each machine has its own {@link ForgeConstants#USER_ADVENTURE_DIR},
 * so a guest's account files stay local and are never part of the host world.
 */
public final class AccountStore {
    public static final String ACHIEVEMENTS_FILE = "achievements.json";
    /** Reserved for package L. */
    public static final String HALL_OF_FAME_FILE = "hall_of_fame.json";
    /** Reserved for package M. */
    public static final String PRESTIGE_FILE = "prestige.json";

    private AccountStore() {
    }

    /** {@code USER_ADVENTURE_DIR/<plane>/account/}. */
    public static File accountDir() {
        String plane = "Shandalar Ascendant";
        try {
            if (Config.instance() != null && Config.instance().getPlane() != null
                    && !Config.instance().getPlane().isEmpty()) {
                plane = Config.instance().getPlane();
            }
        } catch (Throwable ignored) {
            // Config may be unavailable in headless tests.
        }
        File dir = new File(ForgeConstants.USER_ADVENTURE_DIR + plane + File.separator + "account");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File achievementsFile() {
        return new File(accountDir(), ACHIEVEMENTS_FILE);
    }

    /** Test helper: account dir under an explicit root (does not touch USER_ADVENTURE_DIR). */
    public static File accountDir(File root) {
        File dir = new File(root, "account");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File achievementsFile(File root) {
        return new File(accountDir(root), ACHIEVEMENTS_FILE);
    }
}
