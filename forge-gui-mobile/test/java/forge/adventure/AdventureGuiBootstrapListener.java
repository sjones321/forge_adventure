package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import forge.GuiMobile;
import forge.adventure.data.SettingData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;
import org.testng.ISuite;
import org.testng.ISuiteListener;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Installs {@link GuiMobile} + {@link Localizer} + {@link FModel} before any
 * adventure test touches {@code Config} / {@code WorldSave} / {@code CardUtil}.
 * Without this, early {@code SetPlaneRules.generateEnemyDeck} poisons
 * {@code CardUtil} via {@code FModel.getFormats()} before editions load, and
 * {@code WorldSave} clinit NPEs on an uninitialized Localizer.
 *
 * <p>Must run <em>after</em> {@link AdventureTestBootstrapListener} so
 * Ascendant {@code settings.json} is written only under the Surefire
 * {@code test-user-home} {@link ForgeConstants#USER_ADVENTURE_DIR}, never the
 * developer's real profile.
 */
public final class AdventureGuiBootstrapListener implements ISuiteListener {
    @Override
    public void onStart(final ISuite suite) {
        final String testUser = System.getProperty(
                forge.localinstance.properties.ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        if (testUser == null || testUser.isBlank()) {
            throw new IllegalStateException(
                    "AdventureGuiBootstrapListener requires forge.test.userDir "
                            + "(AdventureTestBootstrapListener / Surefire test-user-home). "
                            + "Refusing to write settings to the real USER_DIR="
                            + ForgeConstants.USER_DIR);
        }
        AdventureTestUserDir.assertConstantsUse(java.nio.file.Paths.get(testUser));
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
        // Ascendant settings → isolated USER_ADVENTURE_DIR only.
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
        // FModel before CardUtil clinit (generateEnemyDeck / formats predicates).
        try {
            FModel.initialize(null, preferences -> {
                preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, true);
                preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                return null;
            });
        } catch (Throwable t) {
            System.err.println("AdventureGuiBootstrapListener: FModel init failed: " + t.getMessage());
        }
    }
}
