package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Loads and indexes {@code world/gathering_methods.json} (Package B2).
 */
public final class GatheringMethodListData {
    private static GatheringMethodData.FileRoot root;
    private static final ObjectMap<String, GatheringMethodData.MethodUpgrade> methodsById = new ObjectMap<>();
    private static final ObjectMap<String, GatheringMethodData.ToolEnchantment> enchantsById = new ObjectMap<>();
    private static final ObjectMap<String, GatheringMethodData.Outpost> outpostsById = new ObjectMap<>();

    static {
        reload();
    }

    private GatheringMethodListData() {
    }

    public static void reload() {
        methodsById.clear();
        enchantsById.clear();
        outpostsById.clear();
        root = new GatheringMethodData.FileRoot();
        FileHandle handle = Config.instance().getFile(Paths.GATHERING_METHODS);
        if (handle == null || !handle.exists())
            return;
        Json json = new Json();
        GatheringMethodData.FileRoot loaded = json.fromJson(GatheringMethodData.FileRoot.class, handle);
        if (loaded == null)
            return;
        root = loaded;
        if (root.methodUpgrades != null) {
            for (GatheringMethodData.MethodUpgrade m : new Array.ArrayIterator<>(root.methodUpgrades)) {
                if (m != null && m.id != null && !m.id.isEmpty())
                    methodsById.put(m.id, m);
            }
        }
        if (root.enchantments != null) {
            for (GatheringMethodData.ToolEnchantment e : new Array.ArrayIterator<>(root.enchantments)) {
                if (e != null && e.id != null && !e.id.isEmpty())
                    enchantsById.put(e.id, e);
            }
        }
        if (root.outposts != null) {
            for (GatheringMethodData.Outpost o : new Array.ArrayIterator<>(root.outposts)) {
                if (o != null && o.id != null && !o.id.isEmpty())
                    outpostsById.put(o.id, o);
            }
        }
    }

    public static GatheringMethodData.MethodUpgrade getMethod(String id) {
        return id == null ? null : methodsById.get(id);
    }

    public static GatheringMethodData.ToolEnchantment getEnchantment(String id) {
        return id == null ? null : enchantsById.get(id);
    }

    public static GatheringMethodData.Outpost getOutpost(String id) {
        return id == null ? null : outpostsById.get(id);
    }

    public static Array<GatheringMethodData.MethodUpgrade> getMethods() {
        return root != null && root.methodUpgrades != null ? root.methodUpgrades : new Array<>();
    }

    public static Array<GatheringMethodData.ToolEnchantment> getEnchantments() {
        return root != null && root.enchantments != null ? root.enchantments : new Array<>();
    }

    public static Array<GatheringMethodData.Outpost> getOutposts() {
        return root != null && root.outposts != null ? root.outposts : new Array<>();
    }

    /** Highest-rank method upgrade for a skill at or below {@code rank}, or null. */
    public static GatheringMethodData.MethodUpgrade methodForSkillRank(String skill, int rank) {
        Array<GatheringMethodData.MethodUpgrade> all = methodsUpToRank(skill, rank);
        if (all.size == 0)
            return null;
        GatheringMethodData.MethodUpgrade best = all.first();
        for (GatheringMethodData.MethodUpgrade m : new Array.ArrayIterator<>(all)) {
            if (m.rank > best.rank)
                best = m;
        }
        return best;
    }

    /**
     * All method upgrades for {@code skill} with rank ≤ {@code rank}, sorted ascending.
     * Ranks stack: callers should apply every returned upgrade.
     */
    public static Array<GatheringMethodData.MethodUpgrade> methodsUpToRank(String skill, int rank) {
        Array<GatheringMethodData.MethodUpgrade> out = new Array<>();
        if (skill == null || rank < 1)
            return out;
        for (GatheringMethodData.MethodUpgrade m : new Array.ArrayIterator<>(getMethods())) {
            if (m == null || m.skill == null)
                continue;
            if (skill.equalsIgnoreCase(m.skill) && m.rank >= 1 && m.rank <= rank)
                out.add(m);
        }
        out.sort((a, b) -> Integer.compare(a.rank, b.rank));
        return out;
    }

    /** True when the player has unlocked this method id (or any higher rank for that skill). */
    public static boolean isMethodIdKnown(String id) {
        return getMethod(id) != null;
    }
}
