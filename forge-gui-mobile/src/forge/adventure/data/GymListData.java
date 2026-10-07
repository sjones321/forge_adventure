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
 */
public final class GymListData {
    private static Array<GymData> gyms = new Array<>();
    private static final ObjectMap<String, GymData> byId = new ObjectMap<>();
    private static final ObjectMap<String, GymData> byBadge = new ObjectMap<>();
    private static LeagueData league;

    static {
        reload();
    }

    private GymListData() {
    }

    public static void reload() {
        byId.clear();
        byBadge.clear();
        gyms = new Array<>();
        league = null;
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

    public static Array<GymData> getAll() {
        return gyms;
    }

    public static GymData get(String id) {
        if (id == null)
            return null;
        return byId.get(id);
    }

    public static GymData getByBadge(String badgeId) {
        if (badgeId == null)
            return null;
        return byBadge.get(badgeId);
    }

    public static LeagueData getLeague() {
        return league;
    }

    public static boolean exists(String id) {
        return get(id) != null;
    }

    /** Root object of gyms.json. */
    public static class Root {
        public GymData[] gyms;
        public LeagueData league;
    }
}
