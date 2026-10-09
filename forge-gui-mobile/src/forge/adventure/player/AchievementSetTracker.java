package forge.adventure.player;

import forge.StaticData;
import forge.adventure.data.RewardData;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cached set-completion helpers for AC1. Filters out Ascendant {@code restrictedCards},
 * unsupported (no-script) cards, and anything the adventure reward pool can never give
 * ({@link RewardData#isAdventureRewardReachableName(String)}).
 *
 * <p>"Every set" = Bellwarden booster sets that can host a generatable set plane
 * (enough reward-reachable main-list cards; matches MV2's {@code MIN_SET_POOL_SIZE}).
 *
 * <p>{@code nameCounts} is rebuilt on every player load / new game and adjusted on
 * add and remove — never carried across saves.
 */
public final class AchievementSetTracker {
    /** Same floor as MV2 {@code SetPlaneRules.MIN_SET_POOL_SIZE}. */
    public static final int MIN_SET_PLANE_CARDS = 12;

    private final Map<String, Set<String>> setNamesCache = new HashMap<>();
    private final Map<String, Integer> nameCounts = new HashMap<>();
    private List<String> reachableCache;
    private boolean nameCountsReady;
    /** Test override when {@link StaticData#instance()} is unstable across threads. */
    private static volatile StaticData magicDbOverride;

    public AchievementSetTracker() {
    }

    public static void setMagicDbForTest(StaticData db) {
        magicDbOverride = db;
    }

    public void clearCaches() {
        setNamesCache.clear();
        nameCounts.clear();
        nameCountsReady = false;
        reachableCache = null;
    }

    /** Invalidate reachable-set list and per-set name caches (player / filter change). */
    public void invalidateReachable() {
        reachableCache = null;
        setNamesCache.clear();
    }

    public boolean isNameCountsReady() {
        return nameCountsReady;
    }

    /**
     * Distinct main-list card names for {@code setCode} after AC1 reachability filters.
     * Cached per set code.
     */
    public Set<String> filteredNames(String setCode) {
        if (setCode == null || setCode.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> cached = setNamesCache.get(setCode);
        if (cached != null) {
            return cached;
        }
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            StaticData db = magicDb();
            if (db == null) {
                setNamesCache.put(setCode, Collections.unmodifiableSet(names));
                return setNamesCache.get(setCode);
            }
            CardEdition ed = db.getEditions().get(setCode);
            if (ed == null || ed.getCards() == null) {
                setNamesCache.put(setCode, Collections.unmodifiableSet(names));
                return setNamesCache.get(setCode);
            }
            for (CardEdition.EditionEntry e : ed.getCards()) {
                if (e == null || e.name() == null || e.name().isEmpty()) {
                    continue;
                }
                StaticData dbForNames = magicDb();
                if (dbForNames != null
                        ? RewardData.isAdventureRewardReachableName(e.name(), dbForNames)
                        : RewardData.isAdventureRewardReachableName(e.name())) {
                    names.add(e.name());
                }
            }
        } catch (Throwable ignored) {
            // Card DB / Config unavailable in some headless tests.
        }
        Set<String> frozen = Collections.unmodifiableSet(names);
        setNamesCache.put(setCode, frozen);
        return frozen;
    }

    /**
     * All main-list names for a set with no reward filter (real-data assertions).
     */
    public static Set<String> rawMainListNames(String setCode) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        try {
            StaticData db = magicDb();
            if (db == null) {
                return names;
            }
            CardEdition ed = db.getEditions().get(setCode);
            if (ed == null || ed.getCards() == null) {
                return names;
            }
            for (CardEdition.EditionEntry e : ed.getCards()) {
                if (e != null && e.name() != null && !e.name().isEmpty()) {
                    names.add(e.name());
                }
            }
        } catch (Throwable ignored) {
        }
        return names;
    }

    private static StaticData magicDb() {
        if (magicDbOverride != null) {
            return magicDbOverride;
        }
        try {
            StaticData fromModel = FModel.getMagicDb();
            if (fromModel != null) {
                return fromModel;
            }
        } catch (Throwable ignored) {
        }
        try {
            return StaticData.instance();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Bellwarden sets that can actually get a set plane: CORE / EXPANSION / DRAFT
     * boosters with at least {@link #MIN_SET_PLANE_CARDS} reward-reachable main cards.
     */
    public List<String> reachableBellwardenSetCodes() {
        if (reachableCache != null) {
            return reachableCache;
        }
        List<String> out = new ArrayList<>();
        try {
            for (CardEdition ed : StandardWindow.boosterSets()) {
                if (ed == null || ed.getCode() == null) {
                    continue;
                }
                if (filteredNames(ed.getCode()).size() >= MIN_SET_PLANE_CARDS) {
                    out.add(ed.getCode());
                }
            }
        } catch (Throwable ignored) {
        }
        reachableCache = Collections.unmodifiableList(out);
        return reachableCache;
    }

    /** Warm the reachable-set cache (safe to call off the GL thread). */
    public void precomputeReachable() {
        reachableBellwardenSetCodes();
    }

    /** Rebuild the name→count map from the full collection. */
    public void rebuildNameCounts(CardPool collection) {
        nameCounts.clear();
        if (collection != null) {
            for (Map.Entry<PaperCard, Integer> e : collection) {
                if (e.getKey() == null || e.getKey().getName() == null) {
                    continue;
                }
                String name = e.getKey().getName();
                int n = e.getValue() == null ? 0 : e.getValue();
                if (n > 0) {
                    nameCounts.merge(name, n, Integer::sum);
                }
            }
        }
        nameCountsReady = true;
    }

    /** Apply adds to the name→count map. */
    public void applyAdds(Iterable<PaperCard> added) {
        if (added == null) {
            return;
        }
        for (PaperCard pc : added) {
            if (pc == null || pc.getName() == null) {
                continue;
            }
            nameCounts.merge(pc.getName(), 1, Integer::sum);
        }
        nameCountsReady = true;
    }

    /** Apply removals; selling the last copy un-owns the card. */
    public void applyRemoves(Iterable<PaperCard> removed) {
        if (removed == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (PaperCard pc : removed) {
            if (pc != null && pc.getName() != null) {
                names.add(pc.getName());
            }
        }
        applyRemoveNames(names);
    }

    /** Decrement ownership by card name (one count per entry). */
    public void applyRemoveNames(Iterable<String> names) {
        if (names == null) {
            return;
        }
        for (String name : names) {
            if (name == null) {
                continue;
            }
            Integer cur = nameCounts.get(name);
            if (cur == null) {
                continue;
            }
            int next = cur - 1;
            if (next <= 0) {
                nameCounts.remove(name);
            } else {
                nameCounts.put(name, next);
            }
        }
    }

    public int ownedCount(String cardName) {
        if (cardName == null) {
            return 0;
        }
        Integer n = nameCounts.get(cardName);
        return n == null ? 0 : n;
    }

    public Map<String, Integer> getNameCounts() {
        return Collections.unmodifiableMap(nameCounts);
    }

    public boolean ownsEveryFilteredCard(String setCode) {
        Set<String> names = filteredNames(setCode);
        if (names.isEmpty()) {
            return false;
        }
        for (String name : names) {
            if (ownedCount(name) < 1) {
                return false;
            }
        }
        return true;
    }

    public int ownedFilteredCount(String setCode) {
        int n = 0;
        for (String name : filteredNames(setCode)) {
            if (ownedCount(name) >= 1) {
                n++;
            }
        }
        return n;
    }

    /**
     * Set codes to re-check after {@code added} cards enter the collection:
     * the printings' editions plus any reachable set that lists that card name.
     */
    public Set<String> affectedSets(Iterable<PaperCard> added) {
        if (added == null) {
            return new HashSet<>();
        }
        List<String> names = new ArrayList<>();
        List<String> editions = new ArrayList<>();
        for (PaperCard pc : added) {
            if (pc == null) {
                continue;
            }
            if (pc.getName() != null) {
                names.add(pc.getName());
            }
            if (pc.getEdition() != null && !pc.getEdition().isEmpty()) {
                editions.add(pc.getEdition());
            }
        }
        return affectedSets(names, editions);
    }

    /**
     * Re-check only editions of the added cards (and reachable sets that list
     * those names). Used by production via {@link #affectedSets(Iterable)} and
     * by headless tests without a {@link PaperCard} database.
     */
    public Set<String> affectedSets(Collection<String> cardNames, Collection<String> editions) {
        Set<String> out = new HashSet<>();
        List<String> reachable = reachableBellwardenSetCodes();
        if (editions != null) {
            out.addAll(editions);
        }
        if (cardNames != null) {
            for (String name : cardNames) {
                if (name == null) {
                    continue;
                }
                for (String code : reachable) {
                    if (filteredNames(code).contains(name)) {
                        out.add(code);
                    }
                }
            }
        }
        // Only re-check sets we actually care about.
        out.retainAll(new HashSet<>(reachable));
        return out;
    }

    /**
     * Test helper: install a fixed filtered name list for a set (no card DB).
     */
    public void putFilteredNamesForTest(String setCode, Collection<String> names) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (names != null) {
            set.addAll(names);
        }
        setNamesCache.put(setCode, Collections.unmodifiableSet(set));
        reachableCache = null;
    }

    public void setReachableForTest(List<String> codes) {
        reachableCache = codes == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(codes));
    }

    public void setNameCountForTest(String name, int count) {
        if (name == null) {
            return;
        }
        if (count <= 0) {
            nameCounts.remove(name);
        } else {
            nameCounts.put(name, count);
        }
        nameCountsReady = true;
    }
}
