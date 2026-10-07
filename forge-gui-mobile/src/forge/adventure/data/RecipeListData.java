package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Loads and indexes {@code world/recipes.json}. Stable lookup API for Package F.
 */
public final class RecipeListData {
    private static Array<RecipeData> recipeList;
    private static final ObjectMap<String, RecipeData> byId = new ObjectMap<>();

    static {
        reload();
    }

    private RecipeListData() {
    }

    /** Reload from disk (tests / hot-swap). Safe if the file is missing. */
    public static void reload() {
        byId.clear();
        recipeList = new Array<>();
        FileHandle handle = Config.instance().getFile(Paths.RECIPES);
        if (handle == null || !handle.exists())
            return;
        Json json = new Json();
        Array<RecipeData> loaded = json.fromJson(Array.class, RecipeData.class, handle);
        if (loaded == null)
            return;
        recipeList = loaded;
        for (RecipeData r : new Array.ArrayIterator<>(recipeList)) {
            if (r != null && r.id != null && !r.id.isEmpty())
                byId.put(r.id, r);
        }
    }

    public static RecipeData get(String id) {
        if (id == null)
            return null;
        return byId.get(id);
    }

    public static Array<RecipeData> getAll() {
        return recipeList != null ? recipeList : new Array<>();
    }

    /** Recipes for a station key (forge / workshop / apothecary / jeweler), unsorted. */
    public static Array<RecipeData> forStation(String station) {
        Array<RecipeData> out = new Array<>();
        if (station == null || station.isEmpty())
            return out;
        String key = station.trim().toLowerCase();
        for (RecipeData r : new Array.ArrayIterator<>(getAll())) {
            if (r != null && key.equals(r.stationKey()))
                out.add(r);
        }
        return out;
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }
}
