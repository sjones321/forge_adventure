package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Loads and indexes {@code world/materials.json}. Stable lookup API for later packages.
 */
public final class MaterialListData {
    private static Array<MaterialData> materialList;
    private static final ObjectMap<String, MaterialData> byId = new ObjectMap<>();

    static {
        reload();
    }

    private MaterialListData() {
    }

    /** Reload from disk (tests / hot-swap). Safe if the file is missing. */
    public static void reload() {
        byId.clear();
        materialList = new Array<>();
        FileHandle handle = Config.instance().getFile(Paths.MATERIALS);
        if (handle == null || !handle.exists())
            return;
        Json json = new Json();
        Array<MaterialData> loaded = json.fromJson(Array.class, MaterialData.class, handle);
        if (loaded == null)
            return;
        materialList = loaded;
        for (MaterialData m : new Array.ArrayIterator<>(materialList)) {
            if (m != null && m.id != null && !m.id.isEmpty())
                byId.put(m.id, m);
        }
    }

    public static MaterialData get(String id) {
        if (id == null)
            return null;
        return byId.get(id);
    }

    public static Array<MaterialData> getAll() {
        return materialList != null ? materialList : new Array<>();
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }
}
