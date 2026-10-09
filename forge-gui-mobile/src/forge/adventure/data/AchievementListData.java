package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.util.AtomicJsonFiles;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads {@code world/achievements.json}. Safe if the file or Config is missing.
 */
public final class AchievementListData {
    private static List<AchievementData> list = Collections.emptyList();
    private static Map<String, AchievementData> byId = Collections.emptyMap();
    private static String loadedPlane;

    private AchievementListData() {
    }

    /** Reload from disk. Never throws. */
    public static void reload() {
        list = Collections.emptyList();
        byId = Collections.emptyMap();
        try {
            loadedPlane = Config.instance().getPlane();
            FileHandle handle = Config.instance().getFile(Paths.ACHIEVEMENTS);
            if (handle == null || !handle.exists()) {
                return;
            }
            loadFromJsonText(AtomicJsonFiles.stripBom(handle.readString("UTF-8")));
        } catch (Throwable ignored) {
            loadedPlane = null;
            list = Collections.emptyList();
            byId = Collections.emptyMap();
        }
    }

    /** Reloads when the adventure plane no longer matches the cached data. */
    public static void ensureLoaded() {
        String plane = null;
        try {
            plane = Config.instance().getPlane();
        } catch (Throwable ignored) {
            return;
        }
        if (list == Collections.<AchievementData>emptyList() || plane == null
                || !plane.equals(loadedPlane)) {
            reload();
        }
    }

    /** Test helper: load UTF-8 JSON from a filesystem path. */
    public static void loadFromPath(Path path) throws Exception {
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        loadFromJsonText(AtomicJsonFiles.stripBom(text));
    }

    /** Test helper / internal: parse JSON text (no BOM). */
    public static void loadFromJsonText(String text) {
        list = Collections.emptyList();
        byId = Collections.emptyMap();
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        Json json = new Json();
        json.setIgnoreUnknownFields(true);
        Array<AchievementData> loaded = json.fromJson(Array.class, AchievementData.class, text);
        if (loaded == null) {
            return;
        }
        List<AchievementData> out = new ArrayList<>();
        Map<String, AchievementData> map = new LinkedHashMap<>();
        for (AchievementData a : new Array.ArrayIterator<>(loaded)) {
            if (a == null || a.id == null || a.id.isEmpty()) {
                continue;
            }
            out.add(a);
            map.put(a.id, a);
        }
        list = Collections.unmodifiableList(out);
        byId = Collections.unmodifiableMap(map);
    }

    public static List<AchievementData> getAll() {
        ensureLoaded();
        return list;
    }

    public static AchievementData get(String id) {
        ensureLoaded();
        return byId.get(id);
    }

    /** Test helper: clear cache. */
    public static void clear() {
        list = Collections.emptyList();
        byId = Collections.emptyMap();
        loadedPlane = null;
    }
}
