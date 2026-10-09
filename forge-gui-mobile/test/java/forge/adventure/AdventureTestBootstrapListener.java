package forge.adventure;

import forge.error.ExceptionHandler;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeProfileProperties;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.util.Localizer;
import org.jupnp.UpnpServiceConfiguration;
import org.testng.ISuite;
import org.testng.ISuiteListener;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Suite-wide bootstrap before any adventure test touches {@code Config} /
 * {@link forge.localinstance.properties.ForgeConstants}.
 *
 * <p>Surefire must set {@code forge.test.userDir} to
 * {@code ${project.build.directory}/test-user-home} (see forge-gui-mobile pom)
 * on the JVM command line ({@code argLine} + {@code systemPropertyVariables}).
 * This listener installs GuiBase + Localizer, fail-fast checks isolation, and
 * snapshots the real OS Forge user dir (size/mtime/sha-256) so {@link #onFinish}
 * fails the suite if anything wrote outside the Surefire test home.
 * It does <em>not</em> write {@code settings.json} — that is
 * {@link AdventureGuiBootstrapListener} under the isolated tree only.
 */
public final class AdventureTestBootstrapListener implements ISuiteListener {
    static {
        // Class load is before onStart / any @BeforeClass. Fail immediately if
        // Surefire forgot the property so ForgeConstants cannot bind to ~/.forge.
        final String testUser = System.getProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        if (testUser == null || testUser.isBlank()) {
            throw new ExceptionInInitializerError(
                    ForgeProfileProperties.TEST_USER_DIR_PROPERTY
                            + " must be set on the Surefire JVM (argLine -D and/or "
                            + "systemPropertyVariables) before AdventureTestBootstrapListener loads. "
                            + "Without it, ForgeConstants / ExceptionHandler.bind to the real user dir "
                            + "and can lock forge.log.");
        }
    }

    private Path realUserDir;
    private Map<String, AdventureTestUserDir.FileStamp> realSnapshot;

    @Override
    public void onStart(final ISuite suite) {
        // 1) Property-only check (no ForgeConstants yet).
        final Path testUserDir = AdventureTestUserDir.configuredTestUserDir();

        // 2) Snapshot the real profile BEFORE anything in this listener can touch it.
        try {
            realUserDir = AdventureTestUserDir.defaultRealUserDir();
            realSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        } catch (final IOException e) {
            throw new IllegalStateException(
                    "AdventureTestBootstrapListener: could not snapshot real user dir "
                            + AdventureTestUserDir.defaultRealUserDir()
                            + ". If forge.log is locked, a Forge JVM called "
                            + "ExceptionHandler.registerErrorHandling() against the real profile "
                            + "(often Forge.create / Main before forge.test.userDir bound "
                            + "ForgeConstants.LOG_FILE). activeLog="
                            + ExceptionHandler.getActiveLogFile()
                            + "; expected writes under " + testUserDir
                            + ": " + e.getMessage(), e);
        }

        // 3) Install GuiBase, then bind ForgeConstants under the test home.
        final String assets = Files.exists(Paths.get("./forge-gui")) ? "./forge-gui/"
                : Files.exists(Paths.get("./res")) ? "./" : "../forge-gui/";
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new HeadlessAssetsGui(assets));
        }
        try {
            Localizer.getInstance().initialize("en-US", assets + "res/languages");
        } catch (final Throwable t) {
            System.err.println("AdventureTestBootstrapListener: Localizer init failed: " + t.getMessage());
        }
        AdventureTestUserDir.requireIsolatedUserDir();

        // 4) Loud guards: logging must not already point at the real profile, and
        //    bootstrap itself must not have mutated the real dir.
        final File activeLog = ExceptionHandler.getActiveLogFile();
        if (activeLog != null) {
            final Path activePath = activeLog.getAbsoluteFile().toPath().normalize();
            if (!activePath.startsWith(testUserDir)) {
                throw new IllegalStateException(
                        "ExceptionHandler already logging to " + activePath
                                + " outside " + ForgeProfileProperties.TEST_USER_DIR_PROPERTY
                                + "=" + testUserDir
                                + ". registerErrorHandling() ran before test isolation; "
                                + "LOG_FILE=" + ForgeConstants.LOG_FILE);
            }
        }
        final Path logFile = Paths.get(ForgeConstants.LOG_FILE).toAbsolutePath().normalize();
        if (!logFile.startsWith(testUserDir)) {
            throw new IllegalStateException(
                    "ForgeConstants.LOG_FILE=" + logFile
                            + " is outside " + ForgeProfileProperties.TEST_USER_DIR_PROPERTY
                            + "=" + testUserDir);
        }
        try {
            AdventureTestUserDir.assertUnchanged(realUserDir, realSnapshot,
                    "AdventureTestBootstrapListener.onStart (after binding ForgeConstants)");
        } catch (final Throwable t) {
            throw new IllegalStateException(
                    "REAL Forge user dir was mutated while binding test isolation "
                            + "(expected writes under " + testUserDir + "): "
                            + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()), t);
        }
    }

    @Override
    public void onFinish(final ISuite suite) {
        if (realUserDir == null || realSnapshot == null) {
            return;
        }
        try {
            AdventureTestUserDir.assertUnchanged(realUserDir, realSnapshot,
                    "AdventureTestBootstrapListener");
        } catch (final Throwable t) {
            throw new IllegalStateException(
                    "REAL Forge user dir was mutated by tests (expected writes under "
                            + AdventureTestUserDir.configuredTestUserDir() + "): "
                            + (t.getMessage() != null ? t.getMessage() : t.getClass().getName()), t);
        }
    }

    /** Minimal IGuiBase so ForgeConstants.ASSETS_DIR can resolve in headless TestNG. */
    private static final class HeadlessAssetsGui implements IGuiBase {
        private final String assetsDir;

        private HeadlessAssetsGui(final String assetsDir) {
            this.assetsDir = assetsDir.endsWith("/") || assetsDir.endsWith(File.separator)
                    ? assetsDir : assetsDir + File.separator;
        }

        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return false; }
        @Override public String getCurrentVersion() { return "test"; }
        @Override public void invokeInEdtNow(final Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtLater(final Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtAndWait(final Runnable proc) { proc.run(); }
        @Override public void runBackgroundTask(final String message, final Runnable task) { task.run(); }
        @Override public boolean isGuiThread() { return true; }
        @Override public String getAssetsDir() { return assetsDir; }
        @Override public ImageFetcher getImageFetcher() { return null; }
        @Override public ISkinImage getSkinIcon(final FSkinProp skinProp) { return null; }
        @Override public ISkinImage getUnskinnedIcon(final String path) { return null; }
        @Override public ISkinImage getCardArt(final PaperCard card, final boolean backFace) { return null; }
        @Override public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background,
                final String overlayFilename, final float opacity) { return null; }
        @Override public void clearImageCache() { }
        @Override public String encodeSymbols(final String str, final boolean formatReminderText) { return str; }
        @Override public int getAvatarCount() { return 0; }
        @Override public int getSleevesCount() { return 0; }
        @Override public float getScreenScale() { return 1f; }
        @Override public void preventSystemSleep(final boolean preventSleep) { }
        @Override public void download(final GuiDownloadService service, final Consumer<Boolean> callback) {
            callback.accept(false);
        }
        @Override public void copyToClipboard(final String text) { }
        @Override public void browseToUrl(final String url) throws IOException, URISyntaxException { }
        @Override public void showCardList(final String title, final String message, final List<PaperCard> list) { }
        @Override public boolean showBoxedProduct(final String title, final String message, final List<PaperCard> list) {
            return false;
        }
        @Override public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) { }
        @Override public void showImageDialog(final ISkinImage image, final String message, final String title) { }
        @Override public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                final List<String> options, final int defaultOption) { return defaultOption; }
        @Override public String showInputDialog(final String message, final String title, final FSkinProp icon,
                final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
            return initialInput;
        }
        @Override public String showFileDialog(final String title, final String defaultDir) { return defaultDir; }
        @Override public File getSaveFile(final File defaultFile) { return defaultFile; }
        @Override public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
                final int remainingObjectsMax, final List<T> sourceChoices, final List<T> destChoices) {
            return destChoices;
        }
        @Override public <T> List<T> getChoices(final String message, final int min, final int max,
                final Collection<T> choices, final Collection<T> selected,
                final FSerializableFunction<T, String> display) {
            return new ArrayList<>(selected);
        }
        @Override public PaperCard chooseCard(final String title, final String message, final List<PaperCard> list) {
            return list.isEmpty() ? null : list.get(0);
        }
        @Override public boolean isSupportedAudioFormat(final File file) { return false; }
        @Override public IAudioClip createAudioClip(final String filename) { return null; }
        @Override public IAudioMusic createAudioMusic(final String filename) { return null; }
        @Override public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }
        @Override public void showSpellShop() { }
        @Override public void showBazaar() { }
        @Override public IGuiGame getNewGuiGame() { return null; }
        @Override public HostedMatch hostMatch() { return null; }
        @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
        @Override public boolean hasNetGame() { return false; }
    }
}
