package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and caches Ascendant LT1 {@code townsfolk.json}.
 */
public final class TownsfolkListData {
    private static Array<TownsfolkData> cached;

    private TownsfolkListData() {
    }

    public static Array<TownsfolkData> getAll() {
        if (cached == null) {
            cached = new Array<>();
            try {
                FileHandle handle = Config.instance().getFile(Paths.TOWNSFOLK);
                if (handle != null && handle.exists()) {
                    Array<TownsfolkData> loaded = new Json().fromJson(Array.class, TownsfolkData.class, handle);
                    if (loaded != null) {
                        cached = loaded;
                    }
                }
            } catch (Throwable t) {
                System.err.println("LT1: failed to load townsfolk.json: " + t.getMessage());
                cached = new Array<>();
            }
        }
        return cached;
    }

    public static TownsfolkData get(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (TownsfolkData data : new Array.ArrayIterator<>(getAll())) {
            if (data != null && id.equals(data.id)) {
                return data;
            }
        }
        return null;
    }

    /** Headless / test load from an absolute path (does not use Config). */
    public static Array<TownsfolkData> loadFromPath(Path path) throws Exception {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        Array<TownsfolkData> loaded = new Json().fromJson(Array.class, TownsfolkData.class, json);
        return loaded != null ? loaded : new Array<>();
    }

    public static void clearCacheForTests() {
        cached = null;
    }
}
