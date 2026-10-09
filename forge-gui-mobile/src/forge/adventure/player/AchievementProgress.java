package forge.adventure.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory account-wide achievement state. Persisted as UTF-8 JSON (no BOM)
 * beside Hall of Fame / prestige under {@link AccountStore}.
 */
public final class AchievementProgress {
    public static final int VERSION = 1;

    private int version = VERSION;
    /** Achievement id → unlock epoch millis. */
    private final Map<String, Long> unlocked = new LinkedHashMap<>();
    /** Set codes whose main card list is fully owned (at least one of each name). */
    private final Set<String> completedSets = new LinkedHashSet<>();
    private final Set<String> titles = new LinkedHashSet<>();
    private final Set<String> trophies = new LinkedHashSet<>();
    /**
     * CS1 hook: unlocked card-style ids. Stored now so achievement rewards of
     * type {@code cardStyle} persist; CS1 applies them later.
     */
    private final Set<String> cardStyles = new LinkedHashSet<>();
    private final Map<String, Integer> counters = new LinkedHashMap<>();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public boolean isUnlocked(String id) {
        return id != null && unlocked.containsKey(id);
    }

    public Long unlockedAt(String id) {
        return unlocked.get(id);
    }

    public Map<String, Long> getUnlocked() {
        return Collections.unmodifiableMap(unlocked);
    }

    /** @return true if newly unlocked */
    public boolean unlock(String id, long whenMillis) {
        if (id == null || id.isEmpty() || unlocked.containsKey(id)) {
            return false;
        }
        unlocked.put(id, whenMillis);
        return true;
    }

    public Set<String> getCompletedSets() {
        return Collections.unmodifiableSet(completedSets);
    }

    /** @return true if this set was newly recorded */
    public boolean addCompletedSet(String setCode) {
        if (setCode == null || setCode.isEmpty()) {
            return false;
        }
        return completedSets.add(setCode);
    }

    public void clearCompletedSets() {
        completedSets.clear();
    }

    public Set<String> getTitles() {
        return Collections.unmodifiableSet(titles);
    }

    public boolean addTitle(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }
        return titles.add(title);
    }

    public Set<String> getTrophies() {
        return Collections.unmodifiableSet(trophies);
    }

    public boolean addTrophy(String trophyId) {
        if (trophyId == null || trophyId.isEmpty()) {
            return false;
        }
        return trophies.add(trophyId);
    }

    public Set<String> getCardStyles() {
        return Collections.unmodifiableSet(cardStyles);
    }

    public boolean addCardStyle(String styleId) {
        if (styleId == null || styleId.isEmpty()) {
            return false;
        }
        return cardStyles.add(styleId);
    }

    public int getCounter(String key) {
        if (key == null) {
            return 0;
        }
        Integer v = counters.get(key);
        return v == null ? 0 : v;
    }

    public void setCounter(String key, int value) {
        if (key == null || key.isEmpty()) {
            return;
        }
        counters.put(key, Math.max(0, value));
    }

    public Map<String, Integer> getCounters() {
        return Collections.unmodifiableMap(counters);
    }

    // ---- serialization helpers (plain maps/lists for Gson/libGDX Json) ----

    public Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", version);
        root.put("unlocked", new LinkedHashMap<>(unlocked));
        root.put("completedSets", new ArrayList<>(completedSets));
        root.put("titles", new ArrayList<>(titles));
        root.put("trophies", new ArrayList<>(trophies));
        root.put("cardStyles", new ArrayList<>(cardStyles));
        root.put("counters", new LinkedHashMap<>(counters));
        return root;
    }

    @SuppressWarnings("unchecked")
    public static AchievementProgress fromMap(Map<?, ?> root) {
        AchievementProgress p = new AchievementProgress();
        if (root == null) {
            return p;
        }
        Object ver = root.get("version");
        if (ver instanceof Number) {
            p.version = ((Number) ver).intValue();
        }
        Object unlockedObj = root.get("unlocked");
        if (unlockedObj instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) unlockedObj).entrySet()) {
                if (e.getKey() == null) {
                    continue;
                }
                String id = String.valueOf(e.getKey());
                long when = 0L;
                if (e.getValue() instanceof Number) {
                    when = ((Number) e.getValue()).longValue();
                }
                p.unlocked.put(id, when);
            }
        }
        addStrings(p.completedSets, root.get("completedSets"));
        addStrings(p.titles, root.get("titles"));
        addStrings(p.trophies, root.get("trophies"));
        addStrings(p.cardStyles, root.get("cardStyles"));
        Object countersObj = root.get("counters");
        if (countersObj instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) countersObj).entrySet()) {
                if (e.getKey() == null || !(e.getValue() instanceof Number)) {
                    continue;
                }
                p.counters.put(String.valueOf(e.getKey()), ((Number) e.getValue()).intValue());
            }
        }
        return p;
    }

    private static void addStrings(Set<String> target, Object listObj) {
        if (!(listObj instanceof List)) {
            return;
        }
        for (Object o : (List<?>) listObj) {
            if (o != null) {
                String s = String.valueOf(o);
                if (!s.isEmpty()) {
                    target.add(s);
                }
            }
        }
    }
}
