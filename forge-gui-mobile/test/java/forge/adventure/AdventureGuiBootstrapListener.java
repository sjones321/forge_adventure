package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import forge.GuiMobile;
import forge.adventure.data.SettingData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.util.Localizer;
import org.testng.ISuite;
import org.testng.ISuiteListener;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Installs {@link GuiMobile} + {@link Localizer} before any adventure test touches
 * {@code Config} / {@code ForgeConstants} / {@code WorldSave}. Without this, the
 * first headless {@code Config.instance()} NPEs inside {@code ForgeConstants}
 * clinit, and any early {@code SetPlaneRules} → {@code Current.planeId} path
 * poisons {@code WorldSave} via an uninitialized Localizer in
 * {@code AdventurePlayer.clearDecks}.
 */
public final class AdventureGuiBootstrapListener implements ISuiteListener {
    @Override
    public void onStart(final ISuite suite) {
        final String assets = Files.exists(Paths.get("./forge-gui")) ? "./forge-gui/"
                : Files.exists(Paths.get("./res")) ? "./" : "../forge-gui/";
        if (!(GuiBase.getInterface() instanceof GuiMobile)) {
            GuiBase.setInterface(new GuiMobile(assets));
        }
        // Localizer before any WorldSave / AdventurePlayer construction.
        try {
            Localizer.getInstance().initialize("en-US", ForgeConstants.LANG_DIR);
        } catch (Throwable t) {
            System.err.println("AdventureGuiBootstrapListener: Localizer init failed: " + t.getMessage());
        }
        // Write Ascendant settings before the first Config.instance() in the suite.
        final File dir = new File(ForgeConstants.USER_ADVENTURE_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        final SettingData settings = new SettingData();
        settings.plane = "Shandalar Ascendant";
        settings.width = 1280;
        settings.height = 720;
        settings.videomode = "720p";
        new FileHandle(ForgeConstants.USER_ADVENTURE_DIR + "settings.json")
                .writeString(new Json(JsonWriter.OutputType.json).prettyPrint(settings), false, "UTF-8");
    }
}
