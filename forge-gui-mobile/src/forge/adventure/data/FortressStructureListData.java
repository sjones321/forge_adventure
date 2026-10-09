package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and indexes {@code world/structures_fortress.json} (FT1).
 */
public final class FortressStructureListData {
    private static Array<FortressStructureData> list = new Array<>();
    private static final ObjectMap<String, FortressStructureData> byId = new ObjectMap<>();

    static {
        reload();
    }

    private FortressStructureListData() {
    }

    public static void reload() {
        byId.clear();
        list = new Array<>();
        try {
            FileHandle handle = Config.instance().getFile(Paths.STRUCTURES_FORTRESS);
            if (handle == null || !handle.exists())
                return;
            loadFromJsonText(handle.readString("UTF-8"));
        } catch (Throwable ignored) {
            // Headless / missing Config — leave empty until loadFromPath.
        }
    }

    /** Test helper: load UTF-8 JSON from a filesystem path. */
    public static void loadFromPath(Path path) throws Exception {
        byId.clear();
        list = new Array<>();
        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        if (text.length() > 0 && text.charAt(0) == '\uFEFF')
            text = text.substring(1);
        loadFromJsonText(text);
    }

    /** Test helper: load from JSON text (no BOM). */
    public static void loadFromJsonText(String text) {
        byId.clear();
        list = new Array<>();
        if (text == null || text.isEmpty())
            return;
        Json json = new Json();
        @SuppressWarnings("unchecked")
        Array<FortressStructureData> loaded = json.fromJson(Array.class, FortressStructureData.class, text);
        if (loaded == null)
            return;
        list = loaded;
        for (FortressStructureData s : new Array.ArrayIterator<>(list)) {
            if (s != null && s.id != null && !s.id.isEmpty())
                byId.put(s.id, s);
        }
    }

    public static FortressStructureData get(String id) {
        if (id == null)
            return null;
        return byId.get(id);
    }

    public static Array<FortressStructureData> getAll() {
        return list != null ? list : new Array<>();
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }

    public static int size() {
        return list != null ? list.size : 0;
    }
}
