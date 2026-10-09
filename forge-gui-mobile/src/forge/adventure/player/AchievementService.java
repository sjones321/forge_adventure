package forge.adventure.player;

import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import forge.adventure.data.AchievementConditionData;
import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementListData;
import forge.adventure.data.ConfigData;
import forge.adventure.stage.GameHUD;
import forge.adventure.util.AtomicJsonFiles;
import forge.adventure.util.Config;
import forge.card.CardEdition;
import forge.deck.CardPool;
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
    private Consumer<String> toastSink;
    private boolean toastEnabled = true;
    private int toastMaxPerPass = 5;
    /** Achievement ids toasted this JVM session (toast fires once per unlock). */
    private final Set<String> toastedIds = new LinkedHashSet<>();
    private boolean loaded;

    public AchievementService() {
        this(AccountStore.achievementsFile().toPath());
    }

    public AchievementService(Path file) {
        this.file = file;
        applyConfigTunables();
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
            JsonValue counters = root.get("counters");
            if (counters != null && counters.isObject()) {
                for (JsonValue child = counters.child; child != null; child = child.next) {
                    if (child.name != null) {
                        p.setCounter(child.name, child.asInt());
                    }
                }
            }
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
        sb.append(",\n  \"trophies\": ");
        appendStringArray(sb, p.getTrophies());
        sb.append(",\n  \"cardStyles\": ");
        appendStringArray(sb, p.getCardStyles());
        sb.append(",\n  \"counters\": ");
        appendIntMap(sb, p.getCounters());
        sb.append("\n}\n");
        return sb.toString();
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
     * Evaluate collection-driven achievements (set completion / all-sets).
     * Call after cards enter the collection. Ascendant-only at the call site.
     *
     * @return newly unlocked achievement ids (may include per-set synthetic keys)
     */
    public List<String> evaluateCollection(CardPool collection) {
        return evaluateCollection(collection, bellwardenSetCodes());
    }

    public synchronized List<String> evaluateCollection(CardPool collection, Collection<String> setCodes) {
        ensureLoaded();
        if (collection == null) {
            return Collections.emptyList();
        }
        Collection<String> codes = setCodes == null ? Collections.emptyList() : setCodes;
        List<String> justCompleted = new ArrayList<>();
        for (String code : codes) {
            if (code == null || code.isEmpty() || progress.getCompletedSets().contains(code)) {
                continue;
            }
            if (ownsEveryDistinctMainCard(collection, code)) {
                justCompleted.add(code);
            }
        }
        return applySetCompletions(justCompleted, codes);
    }

    /**
     * Record set completions and grant set / all-sets achievements. Used by the
     * collection path and by tests that stub ownership without a card database.
     *
     * @param justCompleted set codes newly completed this pass
     * @param allBellwardenCodes full Bellwarden pool (for all-sets)
     */
    public synchronized List<String> applySetCompletions(Collection<String> justCompleted,
                                                         Collection<String> allBellwardenCodes) {
        ensureLoaded();
        List<String> newly = new ArrayList<>();
        int toastsLeft = toastMaxPerPass;
        boolean dirty = false;
        Collection<String> codes = allBellwardenCodes == null ? Collections.emptyList() : allBellwardenCodes;

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
        }

        if (dirty) {
            saveQuietly();
        }
        return newly;
    }

    /**
     * Evaluate counter-only achievements against {@link AchievementProgress} counters.
     * Used by tests and by callers that bump counters without a full player snapshot.
     */
    public synchronized List<String> evaluateCounters() {
        ensureLoaded();
        List<String> newly = new ArrayList<>();
        int toastsLeft = toastMaxPerPass;
        for (AchievementData def : AchievementListData.getAll()) {
            if (def == null || def.condition == null || def.condition.type == null) {
                continue;
            }
            if (!"counter".equalsIgnoreCase(def.condition.type)) {
                continue;
            }
            if (progress.isUnlocked(def.id)) {
                continue;
            }
            if (progress.getCounter(def.condition.key) >= Math.max(1, def.condition.count)) {
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

    /**
     * Evaluate player-state achievements (badges, league, skills, duel wins, counters).
     */
    public synchronized List<String> evaluatePlayer(AdventurePlayer player) {
        ensureLoaded();
        if (player == null) {
            return Collections.emptyList();
        }
        // Derive durable counters from live stats so they survive without separate hooks.
        progress.setCounter("duelsWon", player.getStatistic().totalWins());

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

    /** Full pass: collection then player. */
    public List<String> evaluateAll(AdventurePlayer player) {
        List<String> out = new ArrayList<>();
        if (player != null) {
            out.addAll(evaluateCollection(player.getCards()));
            out.addAll(evaluatePlayer(player));
        }
        return out;
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
                maybeToast(def);
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
        if (toastSink != null) {
            toastSink.accept(msg);
            return;
        }
        try {
            GameHUD hud = GameHUD.getInstance();
            if (hud != null) {
                hud.addNotification(msg);
            }
        } catch (Throwable ignored) {
            // HUD unavailable in tests / menus.
        }
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
                int need = bellwardenSetCodes().size();
                return new ProgressView(progress.isUnlocked(def.id), have, need,
                        have + "/" + need + " sets");
            }
            case "allsetscomplete": {
                int have = progress.getCompletedSets().size();
                int need = bellwardenSetCodes().size();
                boolean done = need > 0 && have >= need
                        && progress.getCompletedSets().containsAll(bellwardenSetCodes());
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
                return player.getStatistic().totalWins() >= Math.max(1, c.count);
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

    /**
     * Own at least one copy of every distinct card name in the set's main card list
     * ({@link CardEdition#getCards()}, not bonus sheets).
     */
    public static boolean ownsEveryDistinctMainCard(CardPool collection, String setCode) {
        Set<String> names = distinctMainCardNames(setCode);
        if (names.isEmpty() || collection == null) {
            return false;
        }
        for (String name : names) {
            if (collection.countByName(name) < 1) {
                return false;
            }
        }
        return true;
    }

    public static Set<String> distinctMainCardNames(String setCode) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (setCode == null || setCode.isEmpty()) {
            return names;
        }
        try {
            if (FModel.getMagicDb() == null) {
                return names;
            }
            CardEdition ed = FModel.getMagicDb().getEditions().get(setCode);
            if (ed == null) {
                return names;
            }
            List<CardEdition.EditionEntry> cards = ed.getCards();
            if (cards == null) {
                return names;
            }
            for (CardEdition.EditionEntry e : cards) {
                if (e != null && e.name() != null && !e.name().isEmpty()) {
                    names.add(e.name());
                }
            }
        } catch (Throwable ignored) {
            // Card DB unavailable in some headless tests.
        }
        return names;
    }

    /** Bellwarden sets = CORE / EXPANSION / DRAFT booster sets (same pool as StandardWindow). */
    public static List<String> bellwardenSetCodes() {
        try {
            List<String> out = new ArrayList<>();
            for (CardEdition ed : StandardWindow.boosterSets()) {
                if (ed != null && ed.getCode() != null) {
                    out.add(ed.getCode());
                }
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
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
