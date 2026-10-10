package forge.adventure.util;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Group;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.scenes.scene2d.ui.Dialog;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.scene.UIScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;

import java.util.List;
import java.util.function.Consumer;

/**
 * Adventure-visible dialogs for {@link UIScene} / {@link GameStage} (MapStage/WorldStage).
 *
 * <p>{@code FOptionPane} / {@code SOptionPane} overlays are drawn only via
 * {@code Classic.render}. When adventure renders with no hierarchy screen
 * ({@code Forge.render} → {@code Adventure.render}), those overlays never appear —
 * use this helper (or {@link UIScene#createGenericDialog}) instead. Pure notices
 * may use {@link #hudNote(String)}.
 *
 * <p>ForgeScene / FScreen UIs that open via {@link Forge#openScreen} still draw
 * classic overlays and may keep {@code FOptionPane}.
 */
public final class AdventureDialogs {
    private AdventureDialogs() {
    }

    /**
     * Adds {@code dialog} to {@code host} and returns whether it is attached.
     * Used by production show paths and unit tests (no GL skin required when both actors
     * are supplied by the caller).
     */
    public static boolean addDialogActor(final Group host, final Actor dialog) {
        if (host == null || dialog == null) {
            return false;
        }
        host.addActor(dialog);
        return dialog.getParent() == host;
    }

    /** Best-effort HUD toast when no modal is needed (or no dialog host exists). */
    public static void hudNote(final String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        try {
            GameHUD.getInstance().addNotification(message);
        } catch (final Exception ignored) {
            // Headless / tests
        }
    }

    /**
     * Multi-option dialog on the active map/overworld {@link GameStage} dialog.
     *
     * @return true if a dialog was shown on the stage
     */
    public static boolean showMapOptions(final String message, final List<String> options,
                                         final Consumer<Integer> callback) {
        if (options == null || options.isEmpty()) {
            return false;
        }
        return showMapOptions(activeGameStage(), message, options, callback);
    }

    /**
     * Multi-option dialog on a specific {@link GameStage} (e.g. a PortalActor's map stage).
     * {@code callback} receives the selected index when a button is pressed.
     * On failure the callback is not invoked — the caller must fall back.
     *
     * @return true if a dialog was shown on the stage
     */
    public static boolean showMapOptions(final GameStage stage, final String message,
                                         final List<String> options,
                                         final Consumer<Integer> callback) {
        if (stage == null || stage.getDialog() == null || options == null || options.isEmpty()) {
            return false;
        }
        final Dialog d = stage.getDialog();
        d.getButtonTable().clear();
        d.getContentTable().clear();
        d.clearListeners();
        if (message != null && !message.isEmpty()) {
            final TextraLabel label = Controls.newTextraLabel(message);
            label.setWrap(true);
            d.getContentTable().add(label).width(250f);
        }
        for (int i = 0; i < options.size(); i++) {
            final int idx = i;
            final String text = options.get(i) != null ? options.get(i) : ("Option " + (i + 1));
            d.getButtonTable().add(Controls.newTextButton(text, () -> {
                stage.hideDialog();
                if (callback != null) {
                    callback.accept(idx);
                }
            })).width(240f).row();
        }
        d.setKeepWithinStage(true);
        stage.showDialog();
        return stage.isDialogOnlyInput();
    }

    /**
     * Yes/No confirm on the active map/overworld stage. Returns false if no stage dialog
     * is available (caller should fall back — never {@code FOptionPane} under Adventure.render).
     */
    public static boolean showMapConfirm(final String message, final String yesLabel,
                                         final String noLabel, final Runnable onYes,
                                         final Runnable onNo) {
        final String yes = yesLabel != null ? yesLabel : "Yes";
        final String no = noLabel != null ? noLabel : "No";
        return showMapOptions(message, List.of(yes, no), idx -> {
            if (idx == 0) {
                if (onYes != null) {
                    onYes.run();
                }
            } else if (idx == 1) {
                if (onNo != null) {
                    onNo.run();
                }
            }
        });
    }

    /**
     * OK-only message on a {@link UIScene} host (current scene when it is a UIScene).
     * Falls back to {@link #hudNote(String)} when no UIScene is active.
     */
    public static void showSceneMessage(final String title, final String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        try {
            if (Forge.getCurrentScene() instanceof UIScene) {
                final UIScene host = (UIScene) Forge.getCurrentScene();
                host.showDialog(host.createGenericDialog(
                        title != null ? title : "",
                        message,
                        "OK", null,
                        host::removeDialog, null));
                return;
            }
        } catch (final Exception ignored) {
        }
        hudNote(message);
    }

    /**
     * Show a freshly built {@link Dialog} on {@code host} after attaching it.
     * Returns whether the dialog ended up on that stage (for tests / callers).
     */
    public static boolean showOnStage(final Stage host, final Dialog dialog) {
        if (host == null || dialog == null) {
            return false;
        }
        if (!addDialogActor(host.getRoot(), dialog)) {
            return false;
        }
        try {
            dialog.show(host);
        } catch (final Exception ignored) {
            // show() may need a full Stage layout; attachment alone is enough for tests
        }
        return dialog.getStage() == host || dialog.getParent() != null;
    }

    /** Map dungeon stage when inside a POI; otherwise the overworld stage. */
    static GameStage activeGameStage() {
        try {
            if (MapStage.getInstance() != null && MapStage.getInstance().isInMap()) {
                return MapStage.getInstance();
            }
        } catch (final Exception ignored) {
        }
        try {
            return WorldStage.getInstance();
        } catch (final Exception ignored) {
            return null;
        }
    }
}
