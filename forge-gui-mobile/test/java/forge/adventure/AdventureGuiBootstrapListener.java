package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import forge.adventure.data.SettingData;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;
import org.testng.ISuite;
import org.testng.ISuiteListener;

import java.io.File;
import java.nio.file.Paths;

/**
 * After {@link AdventureTestBootstrapListener}: write Ascendant {@code settings.json}
 * under the Surefire {@code test-user-home} tree and initialize {@link FModel}
 * before any adventure test touches {@code Config} / {@code WorldSave} / {@code CardUtil}.
 *
 * <p>Does <em>not</em> replace the headless GuiBase from the bootstrap listener —
 * {@code GuiMobile} needs a device adapter and breaks CO1 gold/sound paths.
 * GL tests install {@code GuiMobile} via {@link AdventureGlTestSupport}.
 */
public final class AdventureGuiBootstrapListener implements ISuiteListener {
    @Override
    public void onStart(final ISuite suite) {
        final String testUser = System.getProperty(
                forge.localinstance.properties.ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        if (testUser == null || testUser.isBlank()) {
            // Do not touch ForgeConstants here — reading USER_DIR would bind LOG_FILE to
            // the real profile when the property is missing.
            throw new IllegalStateException(
                    "AdventureGuiBootstrapListener requires forge.test.userDir "
                            + "(AdventureTestBootstrapListener / Surefire test-user-home). "
                            + "Refusing to write settings to the real user profile.");
        }
        if (GuiBase.getInterface() == null) {
            throw new IllegalStateException(
                    "AdventureGuiBootstrapListener requires GuiBase from AdventureTestBootstrapListener");
        }
        AdventureTestUserDir.assertConstantsUse(Paths.get(testUser));
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
