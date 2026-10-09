package forge.adventure.player;

import forge.adventure.util.AtomicJsonFiles;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub for package L Hall of Fame. AC1 writes set-completion entries here so
 * CS1 / the Hall UI can pick them up later. Stored next to achievements under
 * {@link AccountStore}.
 */
public final class HallOfFame {
    private static HallOfFame instance;

    private final Path file;
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private boolean loaded;

    public HallOfFame() {
        this(AccountStore.hallOfFameFile().toPath());
    }

    public HallOfFame(Path file) {
        this.file = file;
    }

    public static synchronized HallOfFame get() {
        if (instance == null) {
            instance = new HallOfFame();
        }
        return instance;
    }

    public static synchronized void setInstance(HallOfFame hof) {
        instance = hof;
    }

    public static synchronized void resetInstance() {
        instance = null;
    }

    public Path getFile() {
        return file;
    }

    public synchronized List<Map<String, Object>> getEntries() {
        ensureLoaded();
        return List.copyOf(entries);
    }

    /**
     * Append a Hall entry. {@code kind} e.g. {@code set_complete} /
     * {@code all_sets_complete}. Never throws.
     */
    public synchronized void record(String kind, String title, String detail) {
        ensureLoaded();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", kind == null ? "" : kind);
        e.put("title", title == null ? "" : title);
        e.put("detail", detail == null ? "" : detail);
        e.put("at", System.currentTimeMillis());
        entries.add(e);
        saveQuietly();
    }

    public synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;
        String text = AtomicJsonFiles.readUtf8OrEmpty(file);
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        try {
            com.badlogic.gdx.utils.JsonValue root = new com.badlogic.gdx.utils.JsonReader().parse(text);
            if (root == null) {
                return;
            }
            com.badlogic.gdx.utils.JsonValue arr = root.isArray() ? root : root.get("entries");
            if (arr == null || !arr.isArray()) {
                return;
            }
            for (com.badlogic.gdx.utils.JsonValue child = arr.child; child != null; child = child.next) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("kind", child.getString("kind", ""));
                e.put("title", child.getString("title", ""));
                e.put("detail", child.getString("detail", ""));
                e.put("at", child.getLong("at", 0L));
                entries.add(e);
            }
        } catch (Throwable ignored) {
            // Corrupt HoF must never break achievements.
        }
    }

    private void saveQuietly() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\n  \"entries\": [\n");
            for (int i = 0; i < entries.size(); i++) {
                Map<String, Object> e = entries.get(i);
                if (i > 0) {
                    sb.append(",\n");
                }
                sb.append("    {\"kind\": \"").append(esc(String.valueOf(e.get("kind"))))
                        .append("\", \"title\": \"").append(esc(String.valueOf(e.get("title"))))
                        .append("\", \"detail\": \"").append(esc(String.valueOf(e.get("detail"))))
                        .append("\", \"at\": ").append(e.get("at")).append('}');
            }
            sb.append("\n  ]\n}\n");
            AtomicJsonFiles.writeUtf8Atomic(file, sb.toString());
        } catch (Exception ignored) {
        }
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
