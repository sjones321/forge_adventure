package forge.adventure.world;

import forge.Forge;
import forge.screens.TransitionScreen;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * MV2: show a loading {@link TransitionScreen} while a set plane materializes.
 * World/GL work runs inside the transition runnable (GL thread).
 */
public final class SetPlaneLoading {
    private static final AtomicReference<String> LAST_MESSAGE = new AtomicReference<>();
    private static final AtomicBoolean LOADING_REQUESTED = new AtomicBoolean(false);
    /** When set, tests run work immediately and record the loading request. */
    private static volatile BiConsumer<String, Runnable> testHook;

    private SetPlaneLoading() {
    }

    /**
     * Show a generating-world loading screen, then run {@code work} on the GL path.
     * Falls back to running {@code work} immediately if TransitionScreen is unavailable.
     *
     * @return true when a loading screen was shown (or requested via test hook)
     */
    public static boolean runWithLoadingScreen(final String message, final Runnable work) {
        final String msg = message != null && !message.isEmpty() ? message : "Generating world…";
        LAST_MESSAGE.set(msg);
        LOADING_REQUESTED.set(false);

        final BiConsumer<String, Runnable> hook = testHook;
        if (hook != null) {
            LOADING_REQUESTED.set(true);
            hook.accept(msg, work);
            return true;
        }

        try {
            Forge.setTransitionScreen(new TransitionScreen(() -> {
                try {
                    if (work != null) {
                        work.run();
                    }
                } finally {
                    try {
                        Forge.clearTransitionScreen();
                    } catch (Exception ignored) {
                    }
                }
            }, null, false, true, msg));
            LOADING_REQUESTED.set(true);
            return true;
        } catch (Throwable t) {
            if (work != null) {
                work.run();
            }
            return false;
        }
    }

    public static String lastLoadingMessage() {
        return LAST_MESSAGE.get();
    }

    public static boolean wasLoadingScreenRequested() {
        return LOADING_REQUESTED.get();
    }

    /** Test-only: run work immediately while still recording a loading request. */
    public static void setTestHook(final BiConsumer<String, Runnable> hook) {
        testHook = hook;
    }

    public static void clearTestHook() {
        testHook = null;
        LAST_MESSAGE.set(null);
        LOADING_REQUESTED.set(false);
    }
}
