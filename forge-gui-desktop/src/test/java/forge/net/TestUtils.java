package forge.net;

import forge.StaticData;
import forge.util.IHasForgeLog;
import forge.gamemodes.net.NetworkChecksumUtil;
import forge.gamemodes.net.server.RemoteClientGuiGame;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.properties.ForgeProfileProperties;
import forge.model.FModel;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Bootstrap and shared utilities for network test infrastructure.
 * {@link #ensureFModelInitialized()} is the entry point — all test classes call it
 * to set up HeadlessGuiDesktop, load card data, and configure test preferences.
 */
public final class TestUtils {

    private TestUtils() {} // Utility class

    /**
     * Format bytes in human-readable form (B, KB, MB, GB).
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024L * 1024L * 1024L) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }

    /**
     * Surefire must set {@code forge.test.userDir} before this runs — otherwise
     * {@link FModel#initialize} → {@code ExceptionHandler.pruneForgeLogs} can delete
     * archives under the real user profile.
     */
    private static void requireTestUserDirBound() {
        final String prop = System.getProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        if (prop == null || prop.isBlank()) {
            throw new IllegalStateException(
                    ForgeProfileProperties.TEST_USER_DIR_PROPERTY
                            + " must be set by Surefire before TestUtils.ensureFModelInitialized() "
                            + "(expected ${project.build.directory}/test-user-home)");
        }
        final Path expected = Paths.get(prop).toAbsolutePath().normalize();
        // Touching ForgeConstants here binds USER_DIR under the property (GuiBase is set).
        final Path user = Paths.get(ForgeConstants.USER_DIR).toAbsolutePath().normalize();
        final Path logFile = Paths.get(ForgeConstants.LOG_FILE).toAbsolutePath().normalize();
        if (!user.startsWith(expected) || !logFile.startsWith(expected)) {
            throw new IllegalStateException(
                    "ForgeConstants bound outside test user dir: USER_DIR=" + user
                            + " LOG_FILE=" + logFile + " expected under " + expected
                            + " (ForgeConstants likely loaded before Surefire set the property)");
        }
    }

    /**
     * Ensure FModel is initialized with HeadlessGuiDesktop for testing.
     * Thread-safe. Always ensures HeadlessGuiDesktop is active, even if another
     * test class set a different GuiBase interface (e.g. GuiDesktop).
     */
    public static synchronized void ensureFModelInitialized() {
        if (!(GuiBase.getInterface() instanceof HeadlessGuiDesktop)) {
            GuiBase.setInterface(new HeadlessGuiDesktop());
        }
        // Bind / verify isolation BEFORE FModel.initialize → pruneForgeLogs.
        requireTestUserDirBound();
        if (StaticData.instance() == null) {
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
                return null;
            });
        }
        // Always ensure runtime test preferences regardless of initialization order —
        // another test class may have initialized FModel before us
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.NET_BANDWIDTH_LOGGING, true);
        FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");

        // Use -Dforge.checksum.mode=production to test with production (sampled) checksum
        boolean useStable = !"production".equalsIgnoreCase(System.getProperty("forge.checksum.mode"));
        NetworkChecksumUtil.setStableChecksum(useStable);

        // Delta sync enabled by default in tests; use -Dforge.deltasync=false to disable
        String deltaSyncProp = System.getProperty("forge.deltasync");
        RemoteClientGuiGame.useDeltaSync = !"false".equalsIgnoreCase(deltaSyncProp);

        IHasForgeLog.netLog.info("[TestConfig] checksum={}, deltasync={}",
                useStable ? "stable" : "sampled",
                RemoteClientGuiGame.useDeltaSync ? "on" : "off");
    }
}
