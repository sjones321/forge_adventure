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
    /**
     * Currently equipped title id (same string stored in {@link #titles}).
     * Kept as the historical id (e.g. {@code Bellwarden Completionist}); UI uses
     * {@link forge.adventure.util.AdventureTitles#titleDisplayName(String)}.
     */
    private String equippedTitle;
    private final Set<String> trophies = new LinkedHashSet<>();
    /**
     * CS1 hook: unlocked card-style ids. Stored now so achievement rewards of
     * type {@code cardStyle} persist; CS1 applies them later.
     */
    private final Set<String> cardStyles = new LinkedHashSet<>();
    /**
     * Pending CS1 grants from set / all-sets completion (style id + set code).
     * Distinct from {@link #cardStyles} so CS1 can apply them when ready.
     */
    private final List<PendingCardStyleGrant> pendingCardStyleGrants = new ArrayList<>();
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

    /** Stored equipped title id, or null when none. */
    public String getEquippedTitle() {
        return equippedTitle;
    }

    /**
     * Equip a owned title by stored id. Cleared when {@code titleId} is null/empty.
     * Unknown ids are still accepted on load so old saves never drop an equipped title.
     */
    public void setEquippedTitle(String titleId) {
        if (titleId == null || titleId.isEmpty()) {
            equippedTitle = null;
            return;
        }
        equippedTitle = titleId;
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

    public List<PendingCardStyleGrant> getPendingCardStyleGrants() {
        return Collections.unmodifiableList(pendingCardStyleGrants);
    }

    /** @return true if newly recorded (deduped by styleId + setCode) */
    public boolean addPendingCardStyleGrant(PendingCardStyleGrant grant) {
        if (grant == null || grant.styleId.isEmpty()) {
            return false;
        }
        for (PendingCardStyleGrant existing : pendingCardStyleGrants) {
            if (existing.styleId.equals(grant.styleId) && existing.setCode.equals(grant.setCode)) {
                return false;
            }
        }
        pendingCardStyleGrants.add(grant);
        cardStyles.add(grant.styleId);
        return true;
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

    /** @return new counter value */
    public int incrementCounter(String key, int by) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        int next = Math.max(0, getCounter(key) + by);
        counters.put(key, next);
        return next;
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
        if (equippedTitle != null && !equippedTitle.isEmpty()) {
            root.put("equippedTitle", equippedTitle);
        }
        root.put("trophies", new ArrayList<>(trophies));
        root.put("cardStyles", new ArrayList<>(cardStyles));
        List<Map<String, Object>> pending = new ArrayList<>();
        for (PendingCardStyleGrant g : pendingCardStyleGrants) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("styleId", g.styleId);
            m.put("setCode", g.setCode);
            m.put("achievementId", g.achievementId);
            m.put("at", g.atMillis);
            pending.add(m);
        }
        root.put("pendingCardStyles", pending);
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
        Object equipped = root.get("equippedTitle");
        if (equipped != null) {
            String eq = String.valueOf(equipped);
            if (!eq.isEmpty()) {
                p.equippedTitle = eq;
            }
        }
        addStrings(p.trophies, root.get("trophies"));
        addStrings(p.cardStyles, root.get("cardStyles"));
        Object pendingObj = root.get("pendingCardStyles");
        if (pendingObj instanceof List) {
            for (Object o : (List<?>) pendingObj) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) o;
                String styleId = m.get("styleId") == null ? "" : String.valueOf(m.get("styleId"));
                String setCode = m.get("setCode") == null ? "" : String.valueOf(m.get("setCode"));
                String achId = m.get("achievementId") == null ? "" : String.valueOf(m.get("achievementId"));
                long at = m.get("at") instanceof Number ? ((Number) m.get("at")).longValue() : 0L;
                p.pendingCardStyleGrants.add(new PendingCardStyleGrant(styleId, setCode, achId, at));
            }
        }
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
