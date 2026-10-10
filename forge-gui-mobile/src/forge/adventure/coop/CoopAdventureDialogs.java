package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.badlogic.gdx.scenes.scene2d.ui.TextField;
import com.github.tommyettinger.textra.TextraButton;
import forge.Forge;
import forge.adventure.scene.UIScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.util.Controls;
import forge.gamemodes.net.coop.CoopWireLimits;

import java.util.List;
import java.util.function.Consumer;

/**
 * CO5 adventure-visible dialogs. {@code FOptionPane} overlays are only drawn via
 * {@code Classic.render}, which adventure mode skips when there is no hierarchy
 * screen — so partner create/import and leave warnings must use Scene2D dialogs
 * on {@link UIScene} / {@link GameHUD} instead.
 */
public final class CoopAdventureDialogs {
    private CoopAdventureDialogs() {
    }

    /**
     * Resolve a UIScene that can host dialogs — only the <em>current</em> scene.
     * Never fall back to a non-rendered {@code StartScene} instance (that shows
     * dialogs the player cannot see, and makes reject messages reappear between prompts).
     */
    public static UIScene dialogHost() {
        try {
            if (Forge.getCurrentScene() instanceof UIScene) {
                return (UIScene) Forge.getCurrentScene();
            }
        } catch (final Exception ignored) {
        }
        return null;
    }

    /**
     * Multi-option dialog. {@code callback} receives the selected index, or
     * {@code -1} if the host cannot show a dialog (headless → first option).
     */
    public static void showOptions(final String title, final String message,
                                   final List<String> options, final Consumer<Integer> callback) {
        if (callback == null) {
            return;
        }
        if (Gdx.app == null || options == null || options.isEmpty()) {
            callback.accept(0);
            return;
        }
        final UIScene host = dialogHost();
        if (host == null) {
            callback.accept(0);
            return;
        }
        final Dialog dialog = new Dialog(title == null ? "" : title, Controls.getSkin());
        if (message != null && !message.isEmpty()) {
            dialog.getContentTable().add(Controls.newTextraLabel(
                    CoopWireLimits.clampString(message, CoopWireLimits.MAX_TEXT_LEN)).setWrap(true))
                    .width(220f).center().pad(4f);
            dialog.getContentTable().row();
        }
        for (int i = 0; i < options.size(); i++) {
            final int idx = i;
            final String label = options.get(i) != null ? options.get(i) : ("Option " + (i + 1));
            final TextraButton btn = Controls.newTextButton(label, () -> {
                try {
                    host.removeDialog();
                } catch (final Exception ignored) {
                }
                callback.accept(idx);
            });
            dialog.getButtonTable().add(btn).pad(2f).width(Math.min(160f, 40f + label.length() * 6f));
            if ((i + 1) % 2 == 0) {
                dialog.getButtonTable().row();
            }
        }
        host.showDialog(dialog);
    }

    /** Text input dialog; {@code callback} receives typed text or {@code null} on cancel. */
    public static void showInput(final String title, final String message, final String initial,
                                 final Consumer<String> callback) {
        if (callback == null) {
            return;
        }
        if (Gdx.app == null) {
            callback.accept(initial != null ? initial : "");
            return;
        }
        final UIScene host = dialogHost();
        if (host == null) {
            callback.accept(initial != null ? initial : "");
            return;
        }
        final Dialog dialog = new Dialog(title == null ? "" : title, Controls.getSkin());
        if (message != null && !message.isEmpty()) {
            dialog.getContentTable().add(Controls.newTextraLabel(message).setWrap(true))
                    .width(220f).pad(4f);
            dialog.getContentTable().row();
        }
        final TextField field = Controls.newTextField(initial != null ? initial : "");
        dialog.getContentTable().add(field).width(200f).pad(4f);
        dialog.getButtonTable().add(Controls.newTextButton("OK", () -> {
            try {
                host.removeDialog();
            } catch (final Exception ignored) {
            }
            callback.accept(field.getText());
        })).pad(2f);
        dialog.getButtonTable().add(Controls.newTextButton("Cancel", () -> {
            try {
                host.removeDialog();
            } catch (final Exception ignored) {
            }
            callback.accept(null);
        })).pad(2f);
        host.showDialog(dialog);
    }

    /**
     * Non-blocking message. Prefer after menu switch so leave never waits on OK.
     * Falls back to GameHUD notification when no UIScene is available.
     */
    public static void showMessage(final String title, final String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        if (Gdx.app == null) {
            return;
        }
        final UIScene host = dialogHost();
        if (host != null) {
            try {
                host.showDialog(host.createGenericDialog(
                        title != null ? title : "Co-op",
                        message,
                        "OK", null,
                        host::removeDialog, null));
                return;
            } catch (final Exception ignored) {
            }
        }
        try {
            GameHUD.getInstance().addNotification(message);
        } catch (final Exception ignored) {
        }
    }
}
