package forge.adventure;

import forge.GuiMobile;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeProfileProperties;
import org.testng.ISuite;
import org.testng.ISuiteListener;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Suite-wide adventure test isolation for forge-gui-mobile (MV2; reuse after merge).
 *
 * <p><b>Shared with CO1 (#38):</b> uses {@link ForgeProfileProperties#TEST_USER_DIR_PROPERTY}
 * ({@code forge.test.userDir}) read inside {@link ForgeProfileProperties#load}, and
 * {@link AdventureTestUserDir} for temp install / real-dir snapshot / assert.
 * Do not add a second override hook in {@link ForgeConstants}.
 *
 * <p>Static init sets the property (if unset) before any test class loads
 * {@link ForgeConstants}. {@link #onStart} snapshots the real OS Forge user dir
 * (existence, size, mtime, sha-256 via {@link AdventureTestUserDir#snapshot}),
 * installs GuiMobile, and asserts constants resolve under the temp tree.
 * {@link #onFinish} fails the suite if the real dir changed, then deletes the temp tree.
 *
 * <p>Register first in surefire {@code listener} (before
 * {@link AdventureGuiBootstrapListener}). Surefire also sets
 * {@code forge.test.userDir} under {@code target/adventure-test-user/}.
 */
public final class AdventureTestUserDirIsolationListener implements ISuiteListener {
    static {
        // Before ForgeConstants can load. Do not reference ForgeConstants here.
        try {
            AdventureTestUserDir.ensureTestUserDirProperty();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private Path realUserDir;
    private Path testUserDir;
    private Map<String, AdventureTestUserDir.FileStamp> realSnapshot;

    @Override
    public void onStart(final ISuite suite) {
        try {
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realSnapshot = AdventureTestUserDir.snapshot(realUserDir);

            final String assets = Files.exists(Paths.get("./forge-gui")) ? "./forge-gui/"
                    : Files.exists(Paths.get("./res")) ? "./" : "../forge-gui/";
            if (!(GuiBase.getInterface() instanceof GuiMobile)) {
                GuiBase.setInterface(new GuiMobile(assets));
            }

            testUserDir = Paths.get(System.getProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY))
                    .toAbsolutePath();
            Files.createDirectories(testUserDir);
            AdventureTestUserDir.assertConstantsUse(testUserDir);
            Files.createDirectories(Paths.get(ForgeConstants.USER_ADVENTURE_DIR));

            System.out.println("AdventureTestUserDirIsolationListener: USER_DIR="
                    + ForgeConstants.USER_DIR
                    + " (real guard=" + realUserDir + ")");
        } catch (Throwable t) {
            throw new IllegalStateException("AdventureTestUserDirIsolationListener onStart failed: "
                    + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()), t);
        }
    }

    @Override
    public void onFinish(final ISuite suite) {
        try {
            AdventureTestUserDir.assertUnchanged(realUserDir, realSnapshot,
                    "AdventureTestUserDirIsolationListener");
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "REAL Forge user dir was mutated by tests (expected writes under "
                            + testUserDir + "): "
                            + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()), t);
        } finally {
            if (testUserDir != null) {
                try {
                    AdventureTestUserDir.deleteRecursive(testUserDir);
                } catch (Exception e) {
                    System.err.println("AdventureTestUserDirIsolationListener: could not delete "
                            + testUserDir + ": " + e.getMessage());
                }
            }
        }
    }
}
