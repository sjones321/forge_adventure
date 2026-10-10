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

    /**
     * Stored equipped title id when it is still owned, else null.
     * Orphaned ids (not in {@link #titles}) are treated as unequipped for display.
     */
    public String getEquippedTitle() {
        if (equippedTitle == null || equippedTitle.isEmpty()) {
            return null;
        }
        if (!titles.contains(equippedTitle)) {
            return null;
        }
        return equippedTitle;
    }

    /**
     * Equip an owned title by stored id. Cleared when {@code titleId} is null/empty
     * or not in {@link #titles}.
     */
    public void setEquippedTitle(String titleId) {
        if (titleId == null || titleId.isEmpty() || !titles.contains(titleId)) {
            equippedTitle = null;
            return;
        }
        equippedTitle = titleId;
    }

    /**
     * Drop an equipped id that is no longer owned, then if still unequipped and any
     * titles remain, equip the first owned title (pre-{@code equippedTitle} saves).
     *
     * @return true if equippedTitle changed
     */
    public boolean migrateEquippedTitleIfMissing() {
        boolean changed = false;
        if (equippedTitle != null && !equippedTitle.isEmpty() && !titles.contains(equippedTitle)) {
            equippedTitle = null;
            changed = true;
        }
        if (equippedTitle != null && !equippedTitle.isEmpty()) {
            return changed;
        }
        if (titles.isEmpty()) {
            return changed;
        }
        equippedTitle = titles.iterator().next();
        return true;
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
}
