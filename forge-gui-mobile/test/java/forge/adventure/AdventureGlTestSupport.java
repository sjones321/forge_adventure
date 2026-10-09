package forge.adventure;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import forge.GuiMobile;
import forge.adventure.data.SettingData;
import forge.adventure.util.Config;
import forge.assets.Assets;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.Localizer;

import java.io.File;
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
            writeAscendantSettings();

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

    private static void writeAscendantSettings() {
        File dir = new File(ForgeConstants.USER_ADVENTURE_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        SettingData settings = new SettingData();
        settings.plane = "Shandalar Ascendant";
        settings.width = 1280;
        settings.height = 720;
        settings.videomode = "720p";
        FileHandle out = new FileHandle(ForgeConstants.USER_ADVENTURE_DIR + "settings.json");
        Json json = new Json(JsonWriter.OutputType.json);
        out.writeString(json.prettyPrint(settings), false, "UTF-8");
        // Reset Config singleton so the next instance() picks up Ascendant settings.
        try {
            java.lang.reflect.Field f = Config.class.getDeclaredField("currentConfig");
            f.setAccessible(true);
            f.set(null, null);
        } catch (ReflectiveOperationException ignored) {
            // Config may already be Ascendant
        }
    }
}
