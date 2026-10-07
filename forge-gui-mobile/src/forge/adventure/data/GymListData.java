package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Loads {@code world/gyms.json}: eight gyms plus the League.
 * Lookup API for GymScene, LeagueScene, and badge checks used by packages E / J.
 * Reloads when the adventure plane changes so the first world's data is not kept.
 */
public final class GymListData {
    private static Array<GymData> gyms = new Array<>();
    private static final ObjectMap<String, GymData> byId = new ObjectMap<>();
    private static final ObjectMap<String, GymData> byBadge = new ObjectMap<>();
    private static LeagueData league;
    /** Plane id the cache was loaded for; null until first load. */
    private static String loadedPlane;

    static {
        reload();
    }

    private GymListData() {
    }

    /** Reload from disk for the current plane. Safe if the file is missing. */
    public static void reload() {
        byId.clear();
        byBadge.clear();
        gyms = new Array<>();
        league = null;
        loadedPlane = Config.instance().getPlane();
        FileHandle handle = Config.instance().getFile(Paths.GYMS);
        if (handle == null || !handle.exists())
            return;
        Json json = new Json();
        Root root = json.fromJson(Root.class, handle);
        if (root == null)
            return;
        if (root.gyms != null) {
            for (GymData g : root.gyms) {
                if (g == null || g.id == null || g.id.isEmpty())
                    continue;
                gyms.add(g);
                byId.put(g.id, g);
                if (g.badgeId != null && !g.badgeId.isEmpty())
                    byBadge.put(g.badgeId, g);
            }
        }
        league = root.league;
    }

    /** Reloads when the adventure plane no longer matches the cached data. */
    private static void ensureCurrentWorld() {
        String plane = Config.instance().getPlane();
        if (loadedPlane == null || !loadedPlane.equals(plane)) {
            forge.adventure.player.BanLists.clear();
            reload();
        }
    }

    public static Array<GymData> getAll() {
        ensureCurrentWorld();
        return gyms;
    }

    public static GymData get(String id) {
        ensureCurrentWorld();
        if (id == null)
            return null;
        return byId.get(id);
    }

    public static GymData getByBadge(String badgeId) {
        ensureCurrentWorld();
        if (badgeId == null)
            return null;
        return byBadge.get(badgeId);
    }

    public static LeagueData getLeague() {
        ensureCurrentWorld();
        return league;
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }

    /** True when {@code badgeId} is defined in the current world's gyms.json. */
    public static boolean isValidBadge(String badgeId) {
        return getByBadge(badgeId) != null;
    }

    /** Root object of gyms.json. */
    public static class Root {
        public GymData[] gyms;
        public LeagueData league;
    }
}
