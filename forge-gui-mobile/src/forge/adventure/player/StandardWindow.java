package forge.adventure.player;

import forge.adventure.stage.GameHUD;
import forge.adventure.util.Config;
import forge.adventure.util.AdventureOverrides;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.item.SealedTemplate;
import forge.item.generation.BoosterGenerator;
import org.apache.commons.lang3.tuple.Pair;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Rotating Standard for Adventure: up to three sets are legal at once. Mastering the newest set
 * (4x each common/uncommon, 2x each rare, 1x each mythic) unlocks one new set per world; when a
 * fourth set arrives the oldest rotates out. Shops, loot, Spell Smith and enemy deck generation
 * draw from the window plus a curated, rotating staples list for the current format.
 */
public final class StandardWindow {
    public static final int WINDOW_SIZE = 3;
    public static final int STAPLES_PER_ROTATION = 12;
    /** Pseudo-set: the Core Set Collection, i.e. all sets listed in coreCollectionSets. */
    public static final String CORE_COLLECTION = "CORE";

    private static String[] coreCollectionSets() {
        String[] sets = Config.instance().getConfigData().coreCollectionSets;
        return sets == null ? new String[0] : sets;
    }

    /** A booster drawing each slot from all core sets: 10 commons, 3 uncommons, 1 rare or mythic. */
    public static Deck generateCoreCollectionBooster() {
        String sets = String.join(" ", coreCollectionSets());
        List<Pair<String, Integer>> slots = new ArrayList<>();
        slots.add(Pair.of("Common " + sets, 10));
        slots.add(Pair.of("Uncommon " + sets, 3));
        slots.add(Pair.of("RareMythic " + sets, 1));
        List<PaperCard> cards = BoosterGenerator.getBoosterPack(new SealedTemplate(CORE_COLLECTION, slots));
        Deck pack = new Deck(setName(CORE_COLLECTION) + " Booster");
        pack.getMain().add(cards);
        pack.setComment(CORE_COLLECTION);
        return pack;
    }

    private final List<String> sets = new ArrayList<>();
    /** Every set code ever added to the window (including rotated-out and CORE). Used for the craftable pool. */
    private final List<String> unlockedHistory = new ArrayList<>();
    private boolean setUnlockedThisWorld;

    public List<String> getSets() {
        return Collections.unmodifiableList(sets);
    }

    public List<String> getUnlockedHistory() {
        return Collections.unmodifiableList(unlockedHistory);
    }

    public boolean isActive() {
        return !sets.isEmpty();
    }

    public boolean isSetUnlockedThisWorld() {
        return setUnlockedThisWorld;
    }

    /** Called at New Game+: a new world allows one more set unlock. */
    public void startNewWorld() {
        setUnlockedThisWorld = false;
    }

    public void clear() {
        sets.clear();
        unlockedHistory.clear();
        setUnlockedThisWorld = false;
        choicePending = false;
    }

    public void init(List<String> startingSets) {
        clear();
        for (String s : startingSets)
            if (s != null && !sets.contains(s)) {
                sets.add(s);
                recordUnlock(s);
            }
    }

    public String newestSet() {
        return sets.isEmpty() ? null : sets.get(sets.size() - 1);
    }

    /** Adds a set; returns the set that rotated out, or null. */
    public String addSet(String code) {
        if (code == null || sets.contains(code))
            return null;
        sets.add(code);
        recordUnlock(code);
        setUnlockedThisWorld = true;
        return sets.size() > WINDOW_SIZE ? sets.remove(0) : null;
    }

    private void recordUnlock(String code) {
        if (code != null && !unlockedHistory.contains(code))
            unlockedHistory.add(code);
    }

    /**
     * True if this set code was ever unlocked, or is one of the real sets inside an unlocked
     * {@link #CORE_COLLECTION} entry.
     */
    public boolean isEverUnlocked(String code) {
        if (code == null)
            return false;
        if (unlockedHistory.contains(code))
            return true;
        if (unlockedHistory.contains(CORE_COLLECTION)) {
            for (String core : coreCollectionSets()) {
                if (code.equals(core))
                    return true;
            }
        }
        return false;
    }

    /** History set codes with CORE expanded to its real sets (for craftable-pool checks). */
    public List<String> expandedHistoryCodes() {
        List<String> codes = new ArrayList<>();
        for (String code : unlockedHistory) {
            if (CORE_COLLECTION.equals(code))
                Collections.addAll(codes, coreCollectionSets());
            else if (!codes.contains(code))
                codes.add(code);
        }
        return codes;
    }

    // ---------------------------------------------------------------- packs for shops

    /** Window set codes with the Core Set Collection expanded to its real sets. */
    public List<String> expandedCodes() {
        List<String> codes = new ArrayList<>();
        for (String code : sets) {
            if (CORE_COLLECTION.equals(code))
                Collections.addAll(codes, coreCollectionSets());
            else
                codes.add(code);
        }
        return codes;
    }

    /** A booster of a random set in the window. */
    public Deck randomWindowBooster(Random random) {
        if (sets.isEmpty())
            return null;
        String code = sets.get(random.nextInt(sets.size()));
        if (CORE_COLLECTION.equals(code))
            return generateCoreCollectionBooster();
        if (AdventureOverrides.instance().getBoosterTemplate(code) == null)
            return null;
        return forge.adventure.util.AdventureEventController.instance().generateBooster(code);
    }

    /** A color booster (11 common, 3 uncommon, 1 rare/mythic, 1 land of that color) drawn from the window's sets. */
    public Deck colorPack(String color) {
        String setList = String.join(" ", expandedCodes());
        String c = ":color(\"" + color + "\")";
        List<Pair<String, Integer>> slots = new ArrayList<>();
        slots.add(Pair.of("Common" + c + ":!Land " + setList, 11));
        slots.add(Pair.of("Uncommon" + c + ":!Land " + setList, 3));
        slots.add(Pair.of("RareMythic" + c + ":!Land " + setList, 1));
        try {
            List<PaperCard> cards = BoosterGenerator.getBoosterPack(new SealedTemplate(color, slots));
            Deck pack = new Deck(color + " Booster Pack");
            pack.getMain().add(cards);
            pack.setComment(color);
            return pack;
        } catch (Exception e) {
            return null; // e.g. no cards of that color in the window
        }
    }

    // ---------------------------------------------------------------- legality / pools

    /** True if the card is legal for card pools: printed in a window set, or one of this rotation's staples. */
    public boolean allows(PaperCard pc, boolean commander) {
        if (!isActive())
            return true;
        String name = pc.getName();
        // With a Commander deck in the save, both staples lists are in play; color staples are always in
        return legalNames().contains(name) || activeStaples(false).contains(name)
                || (commander && activeStaples(true).contains(name)) || unlockedColorStaples().contains(name);
    }

    /** Legal in Standard right now: printed in a window set, one of this rotation's staples, or an unlocked color staple. */
    public boolean isStandardLegal(String name) {
        return !isActive() || legalNames().contains(name) || activeStaples(false).contains(name)
                || unlockedColorStaples().contains(name);
    }

    // ---------------------------------------------------------------- color staples

    private static final String[] COLORS = {"white", "blue", "black", "red", "green"};
    private static final PlayerSkills.Skill[] COLOR_SKILLS = {PlayerSkills.Skill.WHITE, PlayerSkills.Skill.BLUE,
            PlayerSkills.Skill.BLACK, PlayerSkills.Skill.RED, PlayerSkills.Skill.GREEN};
    private static Map<PlayerSkills.Skill, LinkedHashMap<String, Integer>> colorStapleLists;

    /** Per color skill: card name -> level that unlocks it (from common/staples_<color>.txt). */
    public static Map<PlayerSkills.Skill, LinkedHashMap<String, Integer>> colorStapleLists() {
        if (colorStapleLists == null) {
            Map<PlayerSkills.Skill, LinkedHashMap<String, Integer>> out = new HashMap<>();
            for (int i = 0; i < COLORS.length; i++) {
                LinkedHashMap<String, Integer> list = new LinkedHashMap<>();
                for (String line : loadStaples("staples_" + COLORS[i] + ".txt")) {
                    String[] parts = line.split(" ", 2);
                    try {
                        list.put(parts[1].trim(), Integer.parseInt(parts[0]));
                    } catch (Exception ignored) {
                    }
                }
                out.put(COLOR_SKILLS[i], list);
            }
            // Colorless staples are unlocked by Spellsmithing, land staples by Exploration
            out.put(PlayerSkills.Skill.SPELLSMITHING, levelList("staples_colorless.txt"));
            out.put(PlayerSkills.Skill.EXPLORATION, levelList("staples_lands.txt"));
            colorStapleLists = out;
        }
        return colorStapleLists;
    }

    /** Reads a "<level> <card name>" staples file. */
    private static LinkedHashMap<String, Integer> levelList(String file) {
        LinkedHashMap<String, Integer> list = new LinkedHashMap<>();
        for (String line : loadStaples(file)) {
            String[] parts = line.split(" ", 2);
            try {
                list.put(parts[1].trim(), Integer.parseInt(parts[0]));
            } catch (Exception ignored) {
            }
        }
        return list;
    }

    /** A multicolor staple: unlocked when both colors' skills reach the level. */
    public record PairStaple(int level, PlayerSkills.Skill a, PlayerSkills.Skill b, String name) {
    }

    private static List<PairStaple> pairStaples;

    private static PlayerSkills.Skill colorSkill(char c) {
        return switch (c) {
            case 'W' -> PlayerSkills.Skill.WHITE;
            case 'U' -> PlayerSkills.Skill.BLUE;
            case 'B' -> PlayerSkills.Skill.BLACK;
            case 'R' -> PlayerSkills.Skill.RED;
            case 'G' -> PlayerSkills.Skill.GREEN;
            default -> null;
        };
    }

    public static List<PairStaple> pairStaples() {
        if (pairStaples == null) {
            List<PairStaple> out = new ArrayList<>();
            for (String line : loadStaples("staples_multicolor.txt")) {
                String[] parts = line.split(" ", 3);
                try {
                    PlayerSkills.Skill a = colorSkill(parts[1].charAt(0)), b = colorSkill(parts[1].charAt(1));
                    if (a != null && b != null)
                        out.add(new PairStaple(Integer.parseInt(parts[0]), a, b, parts[2].trim()));
                } catch (Exception ignored) {
                }
            }
            pairStaples = out;
        }
        return pairStaples;
    }

    /** Color staples the player has unlocked through their color skill levels. */
    public static Set<String> unlockedColorStaples() {
        Set<String> out = new HashSet<>();
        PlayerSkills skills = AdventurePlayer.current().getSkills();
        for (Map.Entry<PlayerSkills.Skill, LinkedHashMap<String, Integer>> e : colorStapleLists().entrySet()) {
            int level = skills.getLevel(e.getKey());
            for (Map.Entry<String, Integer> s : e.getValue().entrySet())
                if (level >= s.getValue())
                    out.add(s.getKey());
        }
        for (PairStaple p : pairStaples())
            if (Math.min(skills.getLevel(p.a()), skills.getLevel(p.b())) >= p.level())
                out.add(p.name());
        int exploration = skills.getLevel(PlayerSkills.Skill.EXPLORATION);
        for (ComboLand l : comboLands())
            if (exploration >= l.explorationLevel() && skills.getLevel(l.skill()) >= l.skillLevel())
                out.add(l.name());
        return out;
    }

    /** A utility land: unlocked when Exploration and a color skill (or Spellsmithing) both reach their levels. */
    public record ComboLand(int explorationLevel, PlayerSkills.Skill skill, int skillLevel, String name) {
    }

    private static List<ComboLand> comboLands;

    public static List<ComboLand> comboLands() {
        if (comboLands == null) {
            List<ComboLand> out = new ArrayList<>();
            for (String line : loadStaples("staples_utility_lands.txt")) {
                String[] parts = line.split(" ", 4);
                try {
                    char c = parts[1].charAt(0);
                    PlayerSkills.Skill skill = c == 'C' ? PlayerSkills.Skill.SPELLSMITHING : colorSkill(c);
                    if (skill != null)
                        out.add(new ComboLand(Integer.parseInt(parts[0]), skill, Integer.parseInt(parts[2]), parts[3].trim()));
                } catch (Exception ignored) {
                }
            }
            comboLands = out;
        }
        return comboLands;
    }

    /** Color staples newly unlocked when a color skill goes from one level to another. */
    public static List<String> newlyUnlockedStaples(PlayerSkills.Skill skill, int before, int after) {
        List<String> out = new ArrayList<>();
        LinkedHashMap<String, Integer> list = colorStapleLists().get(skill);
        if (list != null)
            for (Map.Entry<String, Integer> s : list.entrySet())
                if (before < s.getValue() && after >= s.getValue())
                    out.add(s.getKey());
        // Multicolor: the pair's effective level is the lower of the two colors
        PlayerSkills skills = AdventurePlayer.current().getSkills();
        for (PairStaple p : pairStaples()) {
            if (p.a() != skill && p.b() != skill)
                continue;
            int other = skills.getLevel(p.a() == skill ? p.b() : p.a());
            if (Math.min(before, other) < p.level() && Math.min(after, other) >= p.level())
                out.add(p.name());
        }
        // Utility lands: need Exploration and the paired skill; either one leveling can complete it
        for (ComboLand l : comboLands()) {
            boolean isExplore = skill == PlayerSkills.Skill.EXPLORATION, isPaired = skill == l.skill();
            if (!isExplore && !isPaired)
                continue;
            int exploreBefore = isExplore ? before : skills.getLevel(PlayerSkills.Skill.EXPLORATION);
            int exploreAfter = isExplore ? after : exploreBefore;
            int pairBefore = isPaired ? before : skills.getLevel(l.skill());
            int pairAfter = isPaired ? after : pairBefore;
            boolean was = exploreBefore >= l.explorationLevel() && pairBefore >= l.skillLevel();
            boolean now = exploreAfter >= l.explorationLevel() && pairAfter >= l.skillLevel();
            if (!was && now)
                out.add(l.name());
        }
        return out;
    }

    private Set<String> cachedNames;
    private String cachedKey;

    private Set<String> legalNames() {
        String key = String.join(",", sets);
        if (cachedNames == null || !key.equals(cachedKey)) {
            Set<String> names = new HashSet<>();
            for (String code : expandedCodes()) {
                CardEdition ed = FModel.getMagicDb().getEditions().get(code);
                if (ed == null)
                    continue;
                for (CardEdition.EditionEntry e : ed.getAllCardsInSet())
                    names.add(e.name());
            }
            cachedNames = names;
            cachedKey = key;
        }
        return cachedNames;
    }

    /** A rotating subset of the curated staples list, stable for a given window. */
    public Set<String> activeStaples(boolean commander) {
        List<String> all = loadStaples(commander ? "staples_commander.txt" : "staples_standard.txt");
        if (all.size() <= STAPLES_PER_ROTATION)
            return new HashSet<>(all);
        List<String> shuffled = new ArrayList<>(all);
        Collections.shuffle(shuffled, new Random(String.join(",", sets).hashCode()));
        return new HashSet<>(shuffled.subList(0, STAPLES_PER_ROTATION));
    }

    private static final Map<String, List<String>> STAPLES = new HashMap<>();

    private static List<String> loadStaples(String file) {
        return STAPLES.computeIfAbsent(file, f -> {
            List<String> out = new ArrayList<>();
            File path = new File(Config.instance().getCommonFilePath(f));
            try (BufferedReader r = new BufferedReader(new FileReader(path))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#"))
                        out.add(line);
                }
            } catch (Exception ignored) {
            }
            return out;
        });
    }

    // ---------------------------------------------------------------- mastery

    /** Copies needed of each card in a set for mastery. */
    private static int needed(CardRarity r) {
        if (r == CardRarity.Common || r == CardRarity.Uncommon)
            return 4;
        if (r == CardRarity.Rare)
            return 2;
        if (r == CardRarity.MythicRare)
            return 1;
        return 0; // basic lands, special/bonus sheets don't count
    }

    /** Mastery requirements for a set: card name -> copies needed (first printing's rarity). */
    public static Map<String, Integer> requirements(String code) {
        Map<String, Integer> req = new LinkedHashMap<>();
        CardEdition ed = FModel.getMagicDb().getEditions().get(code);
        if (ed == null)
            return req;
        List<CardEdition.EditionEntry> cards = ed.getCards();
        if (cards == null)
            return req;
        for (CardEdition.EditionEntry e : cards) {
            int n = needed(e.rarity());
            if (n > 0)
                req.putIfAbsent(e.name(), n);
        }
        return req;
    }

    /** {owned copies counted toward mastery, copies needed} for the newest set. */
    public int[] masteryProgress(CardPool collection) {
        String code = newestSet();
        if (code == null)
            return new int[]{0, 0};
        int have = 0, need = 0;
        for (Map.Entry<String, Integer> e : requirements(code).entrySet()) {
            need += e.getValue();
            have += Math.min(e.getValue(), collection.countByName(e.getKey()));
        }
        return new int[]{have, need};
    }

    /**
     * Checks mastery of the newest set and, if complete and no set was unlocked in this world yet,
     * unlocks the next set (rotating the oldest out when the window is full).
     */
    public void checkMastery(CardPool collection) {
        if (!isActive() || setUnlockedThisWorld)
            return;
        if (choicePending)
            return;
        int[] p = masteryProgress(collection);
        if (p[1] == 0 || p[0] < p[1])
            return;
        choicePending = true;
        notifyPlayer("[GOLD]Set mastered: " + setName(newestSet()) + "![]\nOpen the Skills screen to choose your next set.");
    }

    private boolean choicePending;

    /** True when the newest set is mastered and the player still has to pick the next set. */
    public boolean isChoicePending() {
        return choicePending && !setUnlockedThisWorld;
    }

    /** Sets the player can choose as the next Standard set (every booster set not already in the window). */
    public List<CardEdition> choosableSets() {
        List<CardEdition> out = new ArrayList<>();
        for (CardEdition ed : boosterSets())
            if (!sets.contains(ed.getCode()))
                out.add(ed);
        return out;
    }

    /** The player's pick after mastery. Returns the rotated-out set, or null. */
    public String chooseNextSet(String code) {
        if (!isChoicePending() || code == null)
            return null;
        choicePending = false;
        String rotated = addSet(code);
        String msg = "New set unlocked: " + setName(code) + ".";
        if (rotated != null)
            msg += "\n" + setName(rotated) + " rotated out of Standard.";
        notifyPlayer(msg);
        return rotated;
    }

    /** Debug: undo the most recent unlock (does not bring back a rotated-out set). */
    public String undoLastUnlock() {
        if (sets.size() <= 1 || !setUnlockedThisWorld)
            return null;
        String removed = sets.remove(sets.size() - 1);
        setUnlockedThisWorld = false;
        choicePending = true;
        return removed;
    }

    private static void notifyPlayer(String msg) {
        try {
            GameHUD.getInstance().addNotification(msg);
        } catch (Exception ignored) {
        }
    }

    private static List<CardEdition> boosterSetsCache;

    /** Every real set Forge can open packs for (core sets, expansions, draft sets), newest first. */
    public static List<CardEdition> boosterSets() {
        if (boosterSetsCache == null) {
            List<CardEdition> out = new ArrayList<>();
            for (CardEdition ed : FModel.getMagicDb().getEditions().getOrderedEditions()) {
                CardEdition.Type t = ed.getType();
                if (t != CardEdition.Type.CORE && t != CardEdition.Type.EXPANSION && t != CardEdition.Type.DRAFT)
                    continue;
                if (AdventureOverrides.instance().getBoosterTemplate(ed.getCode()) == null)
                    continue;
                out.add(ed);
            }
            Collections.reverse(out);
            boosterSetsCache = out;
        }
        return boosterSetsCache;
    }

    public static String setName(String code) {
        if (CORE_COLLECTION.equals(code))
            return "Core Set Collection";
        CardEdition ed = code == null ? null : FModel.getMagicDb().getEditions().get(code);
        return ed == null ? String.valueOf(code) : ed.getName();
    }

    // ---------------------------------------------------------------- save/load

    public String[] saveSets() {
        return sets.toArray(new String[0]);
    }

    public String[] saveHistory() {
        return unlockedHistory.toArray(new String[0]);
    }

    public void load(String[] savedSets, boolean unlocked, boolean pending) {
        load(savedSets, unlocked, pending, null);
    }

    /**
     * @param savedHistory every set ever unlocked; when null/empty (old saves), seeded from the current window.
     */
    public void load(String[] savedSets, boolean unlocked, boolean pending, String[] savedHistory) {
        clear();
        if (savedSets != null)
            Collections.addAll(sets, savedSets);
        setUnlockedThisWorld = unlocked;
        choicePending = pending;
        if (savedHistory != null && savedHistory.length > 0) {
            for (String code : savedHistory)
                recordUnlock(code);
        } else {
            // Old save: history was not stored; treat the current window as everything ever unlocked.
            for (String code : sets)
                recordUnlock(code);
        }
    }
}
