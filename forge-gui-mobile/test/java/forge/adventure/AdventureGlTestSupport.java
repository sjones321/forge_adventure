package forge.adventure;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.Texture;
import forge.Forge;
import forge.Graphics;
import forge.GuiMobile;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.assets.Assets;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Boots {@link GuiMobile} + LWJGL3 (same stack as {@code forge.WorldGenBench}) once
 * per JVM so adventure world-gen / materialize / co-op hash tests can run the real
 * {@link forge.adventure.world.World#generateNew} path under Xvfb/DISPLAY.
 *
 * <p>Opt-in only: tests that call this belong to TestNG group {@code gl}
 * (excluded from default surefire; enable with {@code -Pgl-tests} /
 * {@code xvfb-run -a mvn -Pgl-tests test}). Ascendant settings must already live
 * under the isolated {@link ForgeConstants#USER_ADVENTURE_DIR} from
 * {@link AdventureTestBootstrapListener} / Surefire {@code test-user-home} —
 * this class never writes the real user profile.
 */
public final class AdventureGlTestSupport {
    private static final LinkedBlockingQueue<Runnable> GL_QUEUE = new LinkedBlockingQueue<>();
    private static final CountDownLatch READY = new CountDownLatch(1);
    private static volatile boolean started;
    private static volatile boolean running = true;
    private static volatile Throwable startError;

    private AdventureGlTestSupport() {
    }

    /** Ensure the GL app, Ascendant Config, and FModel editions are ready. */
    public static synchronized void ensureReady() {
        if (READY.getCount() == 0) {
            if (startError != null) {
                throw new IllegalStateException("Adventure GL test support failed to start", startError);
            }
            return;
        }
        if (!started) {
            started = true;
            Thread t = new Thread(AdventureGlTestSupport::bootGlApp, "adventure-gl-test");
            t.setDaemon(true);
            t.start();
        }
        try {
            if (!READY.await(180, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for Adventure GL test support", startError);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (startError != null) {
            throw new IllegalStateException("Adventure GL test support failed to start", startError);
        }
    }

    /** Run work on the GL thread and wait for completion. */
    public static void runOnGl(Runnable work) {
        runOnGl(() -> {
            work.run();
            return null;
        });
    }

    public static <T> T runOnGl(Callable<T> work) {
        ensureReady();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        GL_QUEUE.add(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(300, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for GL work");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (error.get() != null) {
            throw new IllegalStateException("GL work failed: " + error.get().getMessage(), error.get());
        }
        return result.get();
    }

    private static void bootGlApp() {
        try {
            String assets = Files.exists(Paths.get("./forge-gui")) ? "./forge-gui/"
                    : Files.exists(Paths.get("./res")) ? "./" : "../forge-gui/";
            // Suite listener should already have installed GuiMobile; reinforce here.
            if (!(GuiBase.getInterface() instanceof GuiMobile)) {
                GuiBase.setInterface(new GuiMobile(assets));
            }
            try {
                Localizer.getInstance().initialize("en-US", ForgeConstants.LANG_DIR);
            } catch (Throwable ignored) {
                // Suite listener should already have initialized; ignore races.
            }
            // Settings were written to the isolated USER_ADVENTURE_DIR by the suite listeners.
            // Reset Config so instance() re-reads that Ascendant settings.json.
            try {
                java.lang.reflect.Field f = Config.class.getDeclaredField("currentConfig");
                f.setAccessible(true);
                f.set(null, null);
            } catch (ReflectiveOperationException ignored) {
            }

            Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
            cfg.setWindowedMode(64, 64);
            cfg.setTitle("AdventureGlTestSupport");
            cfg.disableAudio(true);
            new Lwjgl3Application(new ApplicationAdapter() {
                @Override
                public void create() {
                    try {
                        ((GuiMobile) GuiBase.getInterface()).captureGlThread();
                        Texture.setAssetManager(Assets.getInstance().manager());
                        // WorldStage / GameStage need a SpriteBatch via Forge.getGraphics().
                        ensureForgeGraphics();
                        if (!Config.ascendant()) {
                            throw new IllegalStateException("Expected Shandalar Ascendant plane, got "
                                    + Config.instance().getPlane());
                        }
                        FModel.initialize(null, preferences -> {
                            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, true);
                            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
                            preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                            return null;
                        });
                        // Warm adventure UI skin so Controls.newDialog (GameStage ctor) works.
                        Controls.getSkin();
                        READY.countDown();
                    } catch (Throwable t) {
                        startError = t;
                        READY.countDown();
                        try {
                            Gdx.app.exit();
                        } catch (Throwable ignored) {
                        }
                    }
                }

                @Override
                public void render() {
                    if (!running) {
                        Gdx.app.exit();
                        return;
                    }
                    Runnable task;
                    while ((task = GL_QUEUE.poll()) != null) {
                        task.run();
                    }
                }
            }, cfg);
        } catch (Throwable t) {
            startError = t;
            READY.countDown();
        }
    }

    /** Install a minimal {@link Graphics} so WorldStage can construct for save/switchPlane. */
    private static void ensureForgeGraphics() throws ReflectiveOperationException {
        if (Forge.getGraphics() != null) {
            return;
        }
        java.lang.reflect.Field gf = Forge.class.getDeclaredField("graphics");
        gf.setAccessible(true);
        gf.set(null, new Graphics(Forge.HIGH_SPRITES_CAP));
        // Touch adventure Paths.SKIN so class init is not the failure mode if skin load fails later.
        if (forge.adventure.util.Paths.SKIN == null || forge.adventure.util.Paths.SKIN.isEmpty()) {
            throw new IllegalStateException("Paths.SKIN missing");
        }
    }
}
