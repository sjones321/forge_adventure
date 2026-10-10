package forge.adventure.player;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.AchievementConditionData;
import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.stage.GameHUD;
import forge.adventure.util.AdventureTitles;
import forge.adventure.util.AtomicJsonFiles;
import forge.adventure.util.Config;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.item.PaperCard;
import forge.model.FModel;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ascendant account-wide achievements (AC1). Progress lives outside the save
 * under {@link AccountStore}; guest co-op sessions keep their own local file and
 * never send achievement state over the wire.
 */
public final class AchievementService {
    private static AchievementService instance;

    private final Path file;
    private AchievementProgress progress = new AchievementProgress();
    private final AchievementSetTracker setTracker = new AchievementSetTracker();
    private Consumer<String> toastSink;
    private boolean toastEnabled = true;
    private int toastMaxPerPass = 5;
    /** Achievement ids toasted this JVM session (toast fires once per unlock). */
    private final Set<String> toastedIds = new LinkedHashSet<>();
    private boolean loaded;
    private HallOfFame hallOfFame;

    public AchievementService() {
        this(AccountStore.achievementsFile().toPath());
    }

    public AchievementService(Path file) {
        this.file = file;
        applyConfigTunables();
    }

    public AchievementSetTracker getSetTracker() {
        return setTracker;
    }

    public void setHallOfFame(HallOfFame hallOfFame) {
        this.hallOfFame = hallOfFame;
    }

    public static synchronized AchievementService get() {
        if (instance == null) {
            instance = new AchievementService();
        }
        return instance;
    }

    /** Test helper: replace the singleton. */
    public static synchronized void setInstance(AchievementService svc) {
        instance = svc;
    }

    /** Test helper: drop the singleton so the next {@link #get()} rebuilds. */
    public static synchronized void resetInstance() {
        instance = null;
    }

    public Path getFile() {
        return file;
    }

    public AchievementProgress getProgress() {
        ensureLoaded();
        return progress;
    }

    public void setToastSink(Consumer<String> toastSink) {
        this.toastSink = toastSink;
    }

    public void setToastEnabled(boolean toastEnabled) {
        this.toastEnabled = toastEnabled;
    }

    public void setToastMaxPerPass(int toastMaxPerPass) {
        this.toastMaxPerPass = Math.max(0, toastMaxPerPass);
    }

    private void applyConfigTunables() {
        try {
            if (!Config.ascendant()) {
                return;
            }
            ConfigData cfg = Config.instance().getConfigData();
            if (cfg != null) {
                toastEnabled = cfg.achievementToastEnabled;
                toastMaxPerPass = Math.max(0, cfg.achievementToastMaxPerPass);
            }
        } catch (Throwable ignored) {
            // Headless / early boot.
        }
    }

    public synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        load();
    }

    /**
     * Load progress. Missing file → empty progress. Corrupt file → try {@code .bak},
     * else empty progress; never deletes the corrupt file (renames to {@code .corrupt})
     * and never wipes a good in-memory state mid-session.
     */
    public synchronized void load() {
        AchievementProgress loadedProgress = readProgressResilient(file);
        progress = loadedProgress;
        loaded = true;
    }

    static AchievementProgress readProgressResilient(Path path) {
        String text = AtomicJsonFiles.readUtf8OrEmpty(path);
        AchievementProgress parsed = parseProgress(text);
        if (parsed != null) {
            return parsed;
        }
        if (path != null) {
            Path bak = path.resolveSibling(path.getFileName().toString() + ".bak");
            String bakText = AtomicJsonFiles.readUtf8OrEmpty(bak);
            AchievementProgress fromBak = parseProgress(bakText);
            if (fromBak != null) {
                quarantineCorrupt(path);
                return fromBak;
            }
            if (Files.isRegularFile(path)) {
                quarantineCorrupt(path);
            }
        }
        return new AchievementProgress();
    }

    private static void quarantineCorrupt(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            Path corrupt = path.resolveSibling(path.getFileName().toString() + ".corrupt");
            Files.move(path, corrupt, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // Leave the corrupt file in place rather than delete it.
        }
    }

    /**
     * Parse account progress JSON. Returns {@code null} when the text is present
     * but corrupt (so callers can fall back to {@code .bak}). Empty/missing text
     * returns a fresh empty progress.
     */
    public static AchievementProgress parseProgress(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new AchievementProgress();
        }
        try {
            JsonValue root = new JsonReader().parse(text);
            if (root == null || !root.isObject()) {
                return null;
            }
            AchievementProgress p = new AchievementProgress();
            p.setVersion(root.getInt("version", AchievementProgress.VERSION));
            JsonValue unlocked = root.get("unlocked");
            if (unlocked != null && unlocked.isObject()) {
                for (JsonValue child = unlocked.child; child != null; child = child.next) {
                    if (child.name != null) {
                        p.unlock(child.name, child.asLong());
                    }
                }
            }
            JsonValue sets = root.get("completedSets");
            if (sets != null && sets.isArray()) {
                for (JsonValue child = sets.child; child != null; child = child.next) {
                    p.addCompletedSet(child.asString());
                }
            }
            JsonValue titles = root.get("titles");
            if (titles != null && titles.isArray()) {
                for (JsonValue child = titles.child; child != null; child = child.next) {
                    p.addTitle(child.asString());
                }
            }
            String equippedTitle = root.getString("equippedTitle", null);
            if (equippedTitle != null && !equippedTitle.isEmpty()) {
                // Requires titles already loaded above; orphans clear, then migrate.
                p.setEquippedTitle(equippedTitle);
            }
            JsonValue trophies = root.get("trophies");
            if (trophies != null && trophies.isArray()) {
                for (JsonValue child = trophies.child; child != null; child = child.next) {
                    p.addTrophy(child.asString());
                }
            }
            JsonValue styles = root.get("cardStyles");
            if (styles != null && styles.isArray()) {
                for (JsonValue child = styles.child; child != null; child = child.next) {
                    p.addCardStyle(child.asString());
                }
            }
            JsonValue pending = root.get("pendingCardStyles");
            if (pending != null && pending.isArray()) {
                for (JsonValue child = pending.child; child != null; child = child.next) {
                    p.addPendingCardStyleGrant(new PendingCardStyleGrant(
                            child.getString("styleId", ""),
                            child.getString("setCode", ""),
                            child.getString("achievementId", ""),
                            child.getLong("at", 0L)));
                }
            }
            JsonValue counters = root.get("counters");
            if (counters != null && counters.isObject()) {
                for (JsonValue child = counters.child; child != null; child = child.next) {
                    if (child.name != null) {
                        p.setCounter(child.name, child.asInt());
                    }
                }
            }
            // Pre-equip-field account files: owned titles only → wear one after load.
            p.migrateEquippedTitleIfMissing();
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Persist progress atomically (UTF-8, no BOM). Failure is swallowed. */
    public synchronized void saveQuietly() {
        try {
            save();
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() throws Exception {
        ensureLoaded();
        AtomicJsonFiles.writeUtf8Atomic(file, toJson(progress));
    }

    /** Plain-data JSON (no class names, UTF-8, no BOM). */
    public static String toJson(AchievementProgress p) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\n");
        sb.append("  \"version\": ").append(p.getVersion()).append(",\n");
        sb.append("  \"unlocked\": ");
        appendLongMap(sb, p.getUnlocked());
        sb.append(",\n  \"completedSets\": ");
        appendStringArray(sb, p.getCompletedSets());
        sb.append(",\n  \"titles\": ");
        appendStringArray(sb, p.getTitles());
        if (p.getEquippedTitle() != null && !p.getEquippedTitle().isEmpty()) {
            sb.append(",\n  \"equippedTitle\": \"").append(escapeJson(p.getEquippedTitle())).append('"');
        }
        sb.append(",\n  \"trophies\": ");
        appendStringArray(sb, p.getTrophies());
        sb.append(",\n  \"cardStyles\": ");
        appendStringArray(sb, p.getCardStyles());
        sb.append(",\n  \"pendingCardStyles\": ");
        appendPending(sb, p.getPendingCardStyleGrants());
        sb.append(",\n  \"counters\": ");
        appendIntMap(sb, p.getCounters());
        sb.append("\n}\n");
        return sb.toString();
    }

    private static void appendPending(StringBuilder sb, List<PendingCardStyleGrant> grants) {
        sb.append('[');
        boolean first = true;
        for (PendingCardStyleGrant g : grants) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append("{\"styleId\": \"").append(escapeJson(g.styleId))
                    .append("\", \"setCode\": \"").append(escapeJson(g.setCode))
                    .append("\", \"achievementId\": \"").append(escapeJson(g.achievementId))
                    .append("\", \"at\": ").append(g.atMillis).append('}');
        }
        sb.append(']');
    }

    private static void appendStringArray(StringBuilder sb, Collection<String> values) {
        sb.append('[');
        boolean first = true;
        for (String v : values) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('"').append(escapeJson(v)).append('"');
        }
        sb.append(']');
    }

    private static void appendLongMap(StringBuilder sb, Map<String, Long> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Long> e : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\": ").append(e.getValue() == null ? 0 : e.getValue());
        }
        sb.append('}');
    }

    private static void appendIntMap(StringBuilder sb, Map<String, Integer> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append('"').append(escapeJson(e.getKey())).append("\": ").append(e.getValue() == null ? 0 : e.getValue());
        }
        sb.append('}');
    }

    private static String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---- evaluation ----

    /**
     * Call on every player load and new game. Rebuilds {@code nameCounts} from
     * this collection (so save A's cards never count for save B), invalidates
     * the player-dependent reward filter, computes the reachable-set cache once
     * synchronously (no background warm racing {@link #evaluateCollection}),
     * and evaluates already-complete sets once.
     */
    public synchronized List<String> onPlayerCollectionReady(CardPool collection) {
        ensureLoaded();
        RewardData.invalidateRewardFilterCache();
        setTracker.invalidateReachable();
        setTracker.rebuildNameCounts(collection);
        // One sync scan at load — publishes an immutable reachable list under the
        // tracker lock before evaluation reads it. Avoids the prior warm-thread +
        // evaluateCollection double-write into HashMap caches.
        setTracker.precomputeReachable();
        return evaluateCollection(collection);
    }

    /**
     * Commander-deck tag changes alter the RemNonCommanderDecks reward filter;
     * drop reachable / per-set name caches so the next evaluation rescans.
     */
    public synchronized void onCommanderDeckChanged() {
        ensureLoaded();
        RewardData.invalidateRewardFilterCache();
        setTracker.invalidateReachable();
    }

    /**
     * Incremental collection check after cards are added. Re-checks only
     * affected reachable sets. Does not rebuild the full name map when counts
     * are already ready for this player.
     */
    public synchronized List<String> onCardsAdded(CardPool collection, Iterable<PaperCard> added) {
        ensureLoaded();
        if (collection == null) {
            return Collections.emptyList();
        }
        if (!setTracker.isNameCountsReady()) {
            setTracker.rebuildNameCounts(collection);
        } else {
            setTracker.applyAdds(added);
        }
        Set<String> affected = setTracker.affectedSets(added);
        if (affected.isEmpty() && added != null) {
            return Collections.emptyList();
        }
        List<String> justCompleted = new ArrayList<>();
        for (String code : affected) {
            if (progress.getCompletedSets().contains(code)) {
                continue;
            }
            if (setTracker.ownsEveryFilteredCard(code)) {
                justCompleted.add(code);
            }
        }
        return applySetCompletions(justCompleted, setTracker.reachableBellwardenSetCodes());
    }

    /**
     * After sell / salvage / auto-salvage / deck-loss removals: decrement
     * nameCounts so selling the last copy un-owns the card. Does not revoke
     * already-unlocked set achievements.
     */
    public synchronized void onCardsRemoved(Iterable<PaperCard> removed) {
        ensureLoaded();
        if (removed == null) {
            return;
        }
        if (!setTracker.isNameCountsReady()) {
            return;
        }
        setTracker.applyRemoves(removed);
    }

    /**
     * Full collection rebuild + set evaluation (player load / rare full sync).
     * Not for every Awards screen open. Uses the reachable cache when already
     * warmed by {@link #onPlayerCollectionReady}; otherwise scans once here.
     */
    public synchronized List<String> evaluateCollection(CardPool collection) {
        ensureLoaded();
        if (collection == null) {
            return Collections.emptyList();
        }
        setTracker.rebuildNameCounts(collection);
        List<String> reachable = setTracker.reachableBellwardenSetCodes();
        List<String> justCompleted = new ArrayList<>();
        for (String code : reachable) {
            if (progress.getCompletedSets().contains(code)) {
                continue;
            }
            if (setTracker.ownsEveryFilteredCard(code)) {
                justCompleted.add(code);
            }
        }
        return applySetCompletions(justCompleted, reachable);
    }

    /**
     * Record set completions and grant set / all-sets achievements. Used by the
     * collection path and by tests that stub ownership without a card database.
     *
     * @param justCompleted set codes newly completed this pass
     * @param allReachableCodes Shandalar sets that can host a set plane
     */
    public synchronized List<String> applySetCompletions(Collection<String> justCompleted,
                                                         Collection<String> allReachableCodes) {
        ensureLoaded();
        List<String> newly = new ArrayList<>();
        int toastsLeft = toastMaxPerPass;
        boolean dirty = false;
        Collection<String> codes = allReachableCodes == null ? Collections.emptyList() : allReachableCodes;

        if (justCompleted != null) {
            for (String code : justCompleted) {
                if (code == null || code.isEmpty()) {
                    continue;
                }
                if (!progress.addCompletedSet(code)) {
                    continue;
                }
                dirty = true;
                for (AchievementData def : AchievementListData.getAll()) {
                    if (def.condition == null || def.condition.type == null) {
                        continue;
                    }
                    if (!"setcomplete".equalsIgnoreCase(def.condition.type)) {
                        continue;
                    }
                    if (def.condition.set != null && !def.condition.set.isEmpty()
                            && !def.condition.set.equalsIgnoreCase(code)) {
                        continue;
                    }
                    if (tryUnlock(def, code, newly, toastsLeft)) {
                        toastsLeft--;
                        dirty = true;
                    }
                    recordSetCompleteExtras(def, code);
                }
            }
        }

        for (AchievementData def : AchievementListData.getAll()) {
            if (def.condition == null || def.condition.type == null) {
                continue;
            }
            if (!"allsetscomplete".equalsIgnoreCase(def.condition.type)) {
                continue;
            }
            if (codes.isEmpty()) {
                continue;
            }
            if (!progress.getCompletedSets().containsAll(codes)) {
                continue;
            }
            if (tryUnlock(def, null, newly, toastsLeft)) {
                toastsLeft--;
                dirty = true;
            }
            if (progress.isUnlocked(def.id)) {
                recordAllSetsCompleteExtras(def);
            }
        }

        if (dirty) {
            saveQuietly();
        }
        return newly;
    }

    private void recordSetCompleteExtras(AchievementData def, String setCode) {
        long now = System.currentTimeMillis();
        String styleId = "set_style:" + setCode;
        if (def != null && def.reward != null && "cardstyle".equalsIgnoreCase(def.reward.type)
                && def.reward.id != null && !def.reward.id.isEmpty()) {
            styleId = def.reward.id + ":" + setCode;
        }
        String achId = def == null ? "set_collector" : def.id;
        if (progress.addPendingCardStyleGrant(new PendingCardStyleGrant(styleId, setCode, achId, now))) {
            hof().record("set_complete", "Set complete: " + setName(setCode),
                    "Pending CS1 style " + styleId);
        }
    }

    private void recordAllSetsCompleteExtras(AchievementData def) {
        long now = System.currentTimeMillis();
        String styleId = "all_sets_style";
        if (def != null && def.reward != null && def.reward.id != null
                && "cardstyle".equalsIgnoreCase(def.reward.type)) {
            styleId = def.reward.id;
        }
        String achId = def == null ? "bellwarden_completionist" : def.id;
        if (progress.addPendingCardStyleGrant(new PendingCardStyleGrant(styleId, "", achId, now))) {
            hof().record("all_sets_complete", AdventureTitles.COMPLETIONIST_TITLE_ID,
                    "Pending CS1 style " + styleId);
        }
    }

    private HallOfFame hof() {
        if (hallOfFame != null) {
            return hallOfFame;
        }
        return HallOfFame.get();
    }

    /**
     * Increment an account-wide counter and evaluate counter / duelWins achievements.
     * Used for {@code duelsWon} and {@code coopSessions}.
     */
    public synchronized int incrementCounter(String key, int by) {
        ensureLoaded();
        int next = progress.incrementCounter(key, by);
        evaluateCounters();
        saveQuietly();
        return next;
    }

    /**
     * Evaluate counter-only and duelWins achievements against account counters.
     */
    public synchronized List<String> evaluateCounters() {
        ensureLoaded();
        List<String> newly = new ArrayList<>();
        int toastsLeft = toastMaxPerPass;
        for (AchievementData def : AchievementListData.getAll()) {
            if (def == null || def.condition == null || def.condition.type == null) {
                continue;
            }
            String type = def.condition.type.toLowerCase(Locale.ROOT);
            if (!"counter".equals(type) && !"duelwins".equals(type)) {
                continue;
            }
            if (progress.isUnlocked(def.id)) {
                continue;
            }
            boolean met;
            if ("duelwins".equals(type)) {
                met = progress.getCounter("duelsWon") >= Math.max(1, def.condition.count);
            } else {
                met = progress.getCounter(def.condition.key) >= Math.max(1, def.condition.count);
            }
            if (met && tryUnlock(def, null, newly, toastsLeft)) {
                toastsLeft--;
            }
        }
        if (!newly.isEmpty()) {
            saveQuietly();
        }
        return newly;
    }

    /**
     * Evaluate player-state achievements (badges, league, skills). Does
     * <em>not</em> copy save-local duel wins into the account counter.
     */
    public synchronized List<String> evaluatePlayer(AdventurePlayer player) {
        ensureLoaded();
        if (player == null) {
            return Collections.emptyList();
        }
        List<String> newly = new ArrayList<>();
        int toastsLeft = toastMaxPerPass;
        for (AchievementData def : AchievementListData.getAll()) {
            if (def == null || def.condition == null || def.condition.type == null) {
                continue;
            }
            if (progress.isUnlocked(def.id)) {
                continue;
            }
            if (isMet(def.condition, player, progress)) {
                if (tryUnlock(def, null, newly, toastsLeft)) {
                    toastsLeft--;
                }
            }
        }
        if (!newly.isEmpty()) {
            saveQuietly();
        }
        return newly;
    }

    private boolean tryUnlock(AchievementData def, String setQualifier, List<String> newly, int toastsLeft) {
        if (def == null || def.id == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean unlocked = progress.unlock(def.id, now);
        if (unlocked) {
            AchievementRewards.grant(progress, def.reward, def, setQualifier);
            newly.add(def.id);
            if (toastsLeft > 0) {
                maybeToast(def, setQualifier);
            }
            return true;
        }
        // Already unlocked (e.g. set_collector): still grant per-set trophy / toast once per set.
        if (setQualifier != null && !setQualifier.isEmpty()) {
            String toastKey = def.id + ":" + setQualifier;
            boolean granted = AchievementRewards.grant(progress, def.reward, def, setQualifier);
            if (granted && toastsLeft > 0 && !toastedIds.contains(toastKey)) {
                maybeToast(def, setQualifier);
                return true;
            }
            return granted;
        }
        return false;
    }

    private void maybeToast(AchievementData def) {
        maybeToast(def, null);
    }

    private void maybeToast(AchievementData def, String setQualifier) {
        if (!toastEnabled || def == null) {
            return;
        }
        String key = setQualifier == null ? def.id : def.id + ":" + setQualifier;
        if (!toastedIds.add(key)) {
            return;
        }
        String name = def.name != null ? def.name : def.id;
        String msg = "Achievement unlocked: " + name;
        if (setQualifier != null && !setQualifier.isEmpty()) {
            msg = msg + " (" + setName(setQualifier) + ")";
        }
        emitToast(msg);
    }

    private void emitToast(String msg) {
        final String text = msg;
        Runnable show = () -> {
            if (toastSink != null) {
                toastSink.accept(text);
                return;
            }
            try {
                GameHUD hud = GameHUD.getInstance();
                if (hud != null) {
                    hud.addNotification(text);
                }
            } catch (Throwable ignored) {
                // HUD unavailable in tests / menus.
            }
        };
        try {
            if (Gdx.app != null) {
                Gdx.app.postRunnable(show);
                return;
            }
        } catch (Throwable ignored) {
        }
        show.run();
    }

    /** Whether this achievement's toast has already fired this session. */
    public boolean wasToasted(String achievementId) {
        return toastedIds.contains(achievementId);
    }

    // ---- progress helpers for UI ----

    public ProgressView progressView(AchievementData def) {
        ensureLoaded();
        if (def == null || def.condition == null || def.condition.type == null) {
            return new ProgressView(progress.isUnlocked(def != null ? def.id : null), 0, 0, "");
        }
        String type = def.condition.type.toLowerCase(Locale.ROOT);
        switch (type) {
            case "setcomplete": {
                int have = progress.getCompletedSets().size();
                int need = setTracker.reachableBellwardenSetCodes().size();
                return new ProgressView(progress.isUnlocked(def.id), have, need,
                        have + "/" + need + " sets");
            }
            case "allsetscomplete": {
                List<String> reachable = setTracker.reachableBellwardenSetCodes();
                int have = 0;
                for (String code : reachable) {
                    if (progress.getCompletedSets().contains(code)) {
                        have++;
                    }
                }
                int need = reachable.size();
                boolean done = need > 0 && have >= need;
                return new ProgressView(done || progress.isUnlocked(def.id), have, need,
                        have + "/" + need + " sets");
            }
            case "counter": {
                int have = progress.getCounter(def.condition.key);
                int need = Math.max(1, def.condition.count);
                return new ProgressView(progress.isUnlocked(def.id), have, need, have + "/" + need);
            }
            case "duelwins": {
                int have = progress.getCounter("duelsWon");
                int need = Math.max(1, def.condition.count);
                return new ProgressView(progress.isUnlocked(def.id), have, need, have + "/" + need);
            }
            case "badgecount": {
                int need = Math.max(1, def.condition.count);
                return new ProgressView(progress.isUnlocked(def.id), -1, need, "");
            }
            case "skilllevel": {
                int need = def.condition.level > 0 ? def.condition.level : Math.max(1, def.condition.count);
                return new ProgressView(progress.isUnlocked(def.id), -1, need, "Lv " + need);
            }
            default:
                return new ProgressView(progress.isUnlocked(def.id), -1, -1, "");
        }
    }

    public static final class ProgressView {
        public final boolean unlocked;
        public final int current;
        public final int target;
        public final String label;

        public ProgressView(boolean unlocked, int current, int target, String label) {
            this.unlocked = unlocked;
            this.current = current;
            this.target = target;
            this.label = label == null ? "" : label;
        }
    }

    // ---- condition / set helpers ----

    static boolean isMet(AchievementConditionData c, AdventurePlayer player, AchievementProgress progress) {
        if (c == null || c.type == null || player == null) {
            return false;
        }
        String type = c.type.toLowerCase(Locale.ROOT);
        switch (type) {
            case "counter":
                return progress.getCounter(c.key) >= Math.max(1, c.count);
            case "duelwins":
                return progress.getCounter("duelsWon") >= Math.max(1, c.count);
            case "badgecount":
                return player.getBadgeCount() >= Math.max(1, c.count);
            case "leaguechampion":
                return player.isLeagueCleared();
            case "skilllevel": {
                int need = c.level > 0 ? c.level : Math.max(1, c.count);
                if (c.skill == null || c.skill.isEmpty()) {
                    return false;
                }
                try {
                    PlayerSkills.Skill skill = PlayerSkills.Skill.valueOf(c.skill.trim().toUpperCase(Locale.ROOT));
                    return player.getSkills().getLevel(skill) >= need;
                } catch (IllegalArgumentException e) {
                    return false;
                }
            }
            case "setcomplete":
            case "allsetscomplete":
                // Handled in evaluateCollection.
                return false;
            default:
                return false;
        }
    }

    /** Reachable Shandalar set codes (generatable set planes). */
    public List<String> reachableBellwardenSetCodes() {
        return setTracker.reachableBellwardenSetCodes();
    }

    private static String setName(String code) {
        try {
            CardEdition ed = FModel.getMagicDb().getEditions().get(code);
            if (ed != null && ed.getName() != null) {
                return ed.getName();
            }
        } catch (Throwable ignored) {
        }
        return code;
    }

    /** Whether account achievements should run (Ascendant gate). */
    public static boolean active() {
        try {
            return Config.ascendant();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Test helper: point at a temp file and empty definitions are fine. */
    public static AchievementService forTest(File achievementsFile) {
        AchievementService svc = new AchievementService(achievementsFile.toPath());
        svc.setToastEnabled(true);
        return svc;
    }
}
