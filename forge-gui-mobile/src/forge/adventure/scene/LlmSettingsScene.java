package forge.adventure.scene;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.ui.CheckBox;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.FocusListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.ai.llm.LlmOpponent;
import forge.ai.llm.LlmSettings;
import forge.ai.llm.LlmSettingsPersistence;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.localinstance.properties.ForgeConstants;

/**
 * Ascendant AI1: in-game settings for the optional LLM opponent. Controller-first
 * (same selectable table pattern as {@link SettingsScene}). The API key is stored
 * only in {@code llm_opponent.properties} under the Forge user folder — never in
 * the world save — and is masked in the UI. Saves are debounced (idle / blur /
 * leave), not written on every keystroke.
 */
public class LlmSettingsScene extends UIScene {
    private static LlmSettingsScene object;

    private final Table settingGroup;
    private final LlmSettings draft;
    private LlmSettingsPersistence persistence;
    private CheckBox enableBox;
    private TextField urlField;
    private TextField modelField;
    private TextField keyField;
    private TextField timeoutField;
    private TextraLabel statusLabel;
    private boolean testing;
    private boolean syncingFields;

    private LlmSettingsScene() {
        super(Forge.isLandscapeMode() ? "ui/settings.json" : "ui/settings_portrait.json");
        System.setProperty("forge.llm.dir", ForgeConstants.USER_DIR);
        draft = LlmSettings.load();
        persistence = new LlmSettingsPersistence(draft);

        settingGroup = new Table();
        addHeader("LLM opponent (Bellwarden: Planes of Nothing)");
        addHint("Optional. Key decisions only; Forge AI handles the rest and is always the fallback.");

        enableBox = Controls.newCheckBox("");
        enableBox.setChecked(draft.isEnabled());
        enableBox.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (syncingFields) {
                    return;
                }
                draft.setEnabled(((CheckBox) actor).isChecked());
                persistence.scheduleSave();
            }
        });
        addLabel("Enable LLM opponent");
        settingGroup.add(enableBox).align(Align.right);

        urlField = addTextRow("Endpoint URL", draft.getBaseUrl(), false,
                text -> draft.setBaseUrl(text));
        modelField = addTextRow("Model name", draft.getModel(), false,
                text -> draft.setModel(text));
        keyField = addTextRow("API key (local only, masked)", draft.getApiKey(), true,
                text -> draft.setApiKey(text));
        timeoutField = addTextRow("Timeout (seconds)", String.valueOf(draft.getTimeoutSeconds()), false, text -> {
            try {
                draft.setTimeoutSeconds(Integer.parseInt(text.trim()));
            } catch (NumberFormatException ignored) {
            }
        });
        timeoutField.setTextFieldFilter((f, c) -> Character.isDigit(c));

        TextraButton testBtn = Controls.newTextButton("Test connection", this::runTest);
        addLabel("Test");
        settingGroup.add(testBtn).align(Align.right).pad(2);

        statusLabel = Controls.newTextraLabel("");
        statusLabel.setWrap(true);
        settingGroup.row().space(5);
        settingGroup.add(statusLabel).colspan(2).align(Align.left).pad(4).width(Forge.isLandscapeMode() ? 340 : 200).expand();

        addHint("Hosted: OpenAI-compatible /v1 URL + key. Local: Ollama :11434/v1, LM Studio :1234/v1,"
                + " llama.cpp :8080/v1 (key optional). Test works with Enable off. See docs/Adventure/AI-Opponent.md.");

        settingGroup.row();
        ui.onButtonPress("return", LlmSettingsScene.this::back);

        ScrollPane scrollPane = ui.findActor("settings");
        scrollPane.setActor(settingGroup);
        addToSelectable(settingGroup);
    }

    public static LlmSettingsScene instance() {
        if (object == null) {
            object = new LlmSettingsScene();
        }
        return object;
    }

    /** Rebuild on enter so the fields match the on-disk file. */
    @Override
    public void enter() {
        System.setProperty("forge.llm.dir", ForgeConstants.USER_DIR);
        if (persistence != null) {
            persistence.close();
        }
        LlmSettings disk = LlmSettings.load();
        draft.setEnabled(disk.isEnabled());
        draft.setBaseUrl(disk.getBaseUrl());
        draft.setApiKey(disk.getApiKey());
        draft.setModel(disk.getModel());
        draft.setTimeoutSeconds(disk.getTimeoutSeconds());
        persistence = new LlmSettingsPersistence(draft);
        syncingFields = true;
        try {
            if (enableBox != null) {
                enableBox.setChecked(draft.isEnabled());
            }
            if (urlField != null) {
                urlField.setText(draft.getBaseUrl());
            }
            if (modelField != null) {
                modelField.setText(draft.getModel());
            }
            if (keyField != null) {
                keyField.setText(draft.getApiKey() == null ? "" : draft.getApiKey());
            }
            if (timeoutField != null) {
                timeoutField.setText(String.valueOf(draft.getTimeoutSeconds()));
            }
            if (statusLabel != null) {
                statusLabel.setText("");
            }
        } finally {
            syncingFields = false;
        }
        super.enter();
    }

    public boolean back() {
        applyFieldsToDraft();
        try {
            persistence.flush();
        } catch (Exception e) {
            if (statusLabel != null) {
                statusLabel.setText("Could not save settings: " + draft.redact(String.valueOf(e.getMessage())));
            }
        }
        Forge.switchToLast();
        return true;
    }

    /**
     * Builds a snapshot from the on-screen fields without mutating the live draft used for
     * saves / duel activation, then tests that snapshot (Enable may be off).
     */
    private void runTest() {
        if (testing) {
            return;
        }
        final LlmSettings snapshot = snapshotFromFields();
        testing = true;
        statusLabel.setText("Testing…");
        new Thread(() -> {
            LlmOpponent.TestResult result = LlmOpponent.testConnection(snapshot);
            String msg = snapshot.redact(result.getMessage());
            final String safe = (result.isSuccess() ? "Success: " : "Failed: ") + msg;
            Gdx.app.postRunnable(() -> {
                testing = false;
                statusLabel.setText(safe);
                showDialog(createGenericDialog("LLM test", safe,
                        Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
            });
        }, "llm-settings-test").start();
    }

    /** On-screen values only; does not write into {@link #draft}. */
    private LlmSettings snapshotFromFields() {
        LlmSettings s = new LlmSettings();
        s.setEnabled(enableBox != null && enableBox.isChecked());
        s.setBaseUrl(urlField != null ? urlField.getText() : "");
        s.setModel(modelField != null ? modelField.getText() : "");
        s.setApiKey(keyField != null ? keyField.getText() : "");
        try {
            s.setTimeoutSeconds(Integer.parseInt(timeoutField.getText().trim()));
        } catch (Exception e) {
            s.setTimeoutSeconds(draft.getTimeoutSeconds());
        }
        return s;
    }

    private void applyFieldsToDraft() {
        if (enableBox != null) {
            draft.setEnabled(enableBox.isChecked());
        }
        if (urlField != null) {
            draft.setBaseUrl(urlField.getText());
        }
        if (modelField != null) {
            draft.setModel(modelField.getText());
        }
        if (keyField != null) {
            draft.setApiKey(keyField.getText());
        }
        if (timeoutField != null) {
            try {
                draft.setTimeoutSeconds(Integer.parseInt(timeoutField.getText().trim()));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void addHeader(String name) {
        TextraLabel label = Controls.newTextraLabel("[%150]" + name);
        label.setWrap(true);
        settingGroup.row().space(8);
        settingGroup.add(label).colspan(2).align(Align.left).pad(6, 2, 2, 2).expand();
    }

    private void addHint(String text) {
        TextraLabel label = Controls.newTextraLabel("[%80]" + text);
        label.setWrap(true);
        settingGroup.row().space(4);
        int w = Forge.isLandscapeMode() ? 360 : 220;
        settingGroup.add(label).colspan(2).align(Align.left).pad(2).width(w).expand();
    }

    private TextField addTextRow(String name, String value, boolean password, java.util.function.Consumer<String> onChange) {
        TextField field = Controls.newTextField(value == null ? "" : value);
        if (password) {
            field.setPasswordMode(true);
            field.setPasswordCharacter('*');
        }
        field.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (syncingFields) {
                    return;
                }
                onChange.accept(((TextField) actor).getText());
                persistence.scheduleSave();
            }
        });
        field.addListener(new FocusListener() {
            @Override
            public void keyboardFocusChanged(FocusEvent event, Actor actor, boolean focused) {
                if (focused || syncingFields) {
                    return;
                }
                onChange.accept(((TextField) actor).getText());
                persistence.flushQuietly();
            }
        });
        addLabel(name);
        settingGroup.add(field).align(Align.right).width(Forge.isLandscapeMode() ? 200 : 140);
        return field;
    }

    private void addLabel(String name) {
        TextraLabel label = Controls.newTextraLabel(name);
        label.setWrap(true);
        settingGroup.row().space(5);
        int w = Forge.isLandscapeMode() ? 160 : 80;
        settingGroup.add(label).align(Align.left).pad(2, 2, 2, 5).width(w).expand();
    }

    @Override
    public void dispose() {
        if (persistence != null) {
            persistence.close();
        }
        if (stage != null) {
            stage.dispose();
        }
    }

    /** Ascendant-only entry; stock worlds never open this screen. */
    public static boolean openIfAscendant() {
        if (!Config.ascendant()) {
            return false;
        }
        Forge.switchScene(instance());
        return true;
    }
}
