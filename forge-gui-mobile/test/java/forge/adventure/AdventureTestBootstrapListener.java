package forge.adventure;

import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Suite-wide bootstrap before any adventure test touches {@code Config} /
 * {@link forge.localinstance.properties.ForgeConstants}.
 *
 * <p>Surefire must set {@code forge.test.userDir} (see forge-gui-mobile pom).
 * This listener only installs GuiBase + Localizer and fail-fast checks isolation —
 * it does <em>not</em> write {@code settings.json}. MV2 should reuse
 * {@code forge.test.userDir} rather than a second user-dir mechanism.
 */
public final class AdventureTestBootstrapListener implements ISuiteListener {
    @Override
    public void onStart(final ISuite suite) {
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
        // Bind ForgeConstants.USER_* under forge.test.userDir (Surefire) and fail if not.
        AdventureTestUserDir.requireIsolatedUserDir();
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
