package forge.ai.llm;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Debounced writer for {@link LlmSettings}. Text fields call {@link #scheduleSave()} on
 * each change; blur / leaving the screen call {@link #flush()}. Saves are coalesced so a
 * keystroke burst does not rewrite the file once per character.
 */
public final class LlmSettingsPersistence {
    public static final long DEFAULT_DEBOUNCE_MS = 400L;

    private final LlmSettings settings;
    private final File file;
    private final long debounceMs;
    private final ScheduledExecutorService exec;
    private final AtomicInteger saveCount = new AtomicInteger();
    private final Object lock = new Object();
    private ScheduledFuture<?> pending;
    private volatile boolean closed;

    public LlmSettingsPersistence(LlmSettings settings) {
        this(settings, LlmSettings.settingsFile(), DEFAULT_DEBOUNCE_MS);
    }

    public LlmSettingsPersistence(LlmSettings settings, File file, long debounceMs) {
        this.settings = settings;
        this.file = file;
        this.debounceMs = Math.max(1L, debounceMs);
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-settings-save");
            t.setDaemon(true);
            return t;
        });
    }

    public LlmSettings getSettings() {
        return settings;
    }

    /** Number of successful disk writes (for tests). */
    public int getSaveCount() {
        return saveCount.get();
    }

    /** Schedule a save after {@code debounceMs} of idle. Repeated calls reset the timer. */
    public void scheduleSave() {
        if (closed) {
            return;
        }
        synchronized (lock) {
            if (pending != null) {
                pending.cancel(false);
            }
            pending = exec.schedule(this::saveNowQuietly, debounceMs, TimeUnit.MILLISECONDS);
        }
    }

    /** Cancel any pending debounce and write immediately. */
    public void flush() throws IOException {
        synchronized (lock) {
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            saveLocked();
        }
    }

    /** Like {@link #flush()} but swallows IO errors (UI blur / leave paths). */
    public void flushQuietly() {
        try {
            flush();
        } catch (IOException ignored) {
        }
    }

    public void close() {
        closed = true;
        flushQuietly();
        exec.shutdownNow();
    }

    private void saveNowQuietly() {
        try {
            saveNow();
        } catch (IOException ignored) {
        }
    }

    /** Serialize all disk writes so debounce and flush cannot interleave or tear the file. */
    private void saveNow() throws IOException {
        synchronized (lock) {
            saveLocked();
        }
    }

    /** Caller must hold {@link #lock}. */
    private void saveLocked() throws IOException {
        settings.save(file);
        saveCount.incrementAndGet();
    }
}
