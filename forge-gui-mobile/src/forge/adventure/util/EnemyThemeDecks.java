package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeCoreData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.BanLists;
import forge.adventure.player.StandardWindow;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * EN1: enemy decks by format and theme.
 * Ordinary overworld enemies pick a theme at spawn and keep it across save/load.
 * Historic / Pauper / Commander use fixed {@code .dck} lists; Bellwarden Standard
 * fills a theme recipe from the current Standard window (or falls back to the
 * theme's fixed Historic list when the window is inactive or the recipe is thin).
 * <p>
 * Gyms, League and story fights keep hand-picked / {@code preparedDeck} lists and
 * never enter this path. MV2 set-plane {@code $generate} hooks should run before
 * EN1 in {@link EnemyData#generateDeck} when both are present.
 * <p>
 * 2HG guest-side themed decks are deferred (host-side EN1 only for now).
 */
public final class EnemyThemeDecks {
    private static final Logger LOG = Logger.getLogger(EnemyThemeDecks.class.getName());

    public static final String FORMAT_STANDARD = GymUtil.FORMAT_STANDARD;
    public static final String FORMAT_PAUPER = GymUtil.FORMAT_PAUPER;
    public static final String FORMAT_HISTORIC = GymUtil.FORMAT_HISTORIC;
    public static final String FORMAT_COMMANDER = GymUtil.FORMAT_COMMANDER;

    /** Minimum on-theme non-land cards for a Standard recipe before falling back. */
    public static final int MIN_ON_THEME_NONLAND_STANDARD = 18;
    /** Minimum on-theme non-land cards for a committed fixed deck. */
    public static final int MIN_ON_THEME_NONLAND_FIXED = 14;
    /** Minimum copies from the hand-picked theme core in a fixed deck. */
    public static final int MIN_CORE_CARDS_IN_DECK = 24;
    /** Maximum generated non-core non-land filler slots in a 60-card fixed deck. */
    public static final int MAX_FILLER_NONLAND = 8;
    /** Maximum generated non-core non-land filler slots in a Commander fixed deck. */
    public static final int MAX_FILLER_COMMANDER = 10;
    public static final int MIN_LANDS_60 = 16;
    public static final int MAX_LANDS_60 = 18;
    public static final int TARGET_LANDS_60 = 17;
    /** Commander mana-base band (total lands including fixing). */
    public static final int MIN_LANDS_COMMANDER = 35;
    public static final int MAX_LANDS_COMMANDER = 39;
    public static final int TARGET_LANDS_COMMANDER = 36;
    public static final int TARGET_LANDS_COMMANDER_RAMP = 37;
    /** Minimum non-land cards in a Commander fixed deck (main + commander). */
    public static final int MIN_NONLAND_COMMANDER = 58;
    /** Minimum Dragon creature cards in dragon_* Historic decks. */
    public static final int MIN_DRAGONS_HISTORIC = 12;
    /** Minimum tribe creatures (incl. changelings) in 60-card tribal decks. */
    public static final int MIN_TRIBAL_CREATURES_60 = 16;
    /** Minimum tribe creatures (incl. changelings) in Commander tribal decks. */
    public static final int MIN_TRIBAL_CREATURES_COMMANDER = 25;
    /** Minimum non-creature spells in a 60-card fixed deck. */
    public static final int MIN_NON_CREATURE_SPELLS_60 = 8;
    /** Max average CMC for non-ramp 60-card themes. */
    public static final float MAX_AVG_CMC_NON_RAMP = 3.75f;
    /**
     * Stable set code for basic lands in generated and fixed EN1 decks.
     * Prefer Foundations over whatever printing happens to sort first / newest.
     */
    public static final String PREFERRED_BASIC_LAND_EDITION = "FDN";

    private static final String[] FORMAT_FALLBACK_ORDER = {
            FORMAT_HISTORIC, FORMAT_PAUPER, FORMAT_COMMANDER, FORMAT_STANDARD
    };

    private static final Object LOCK = new Object();

    private static EnemyThemeCatalogData catalog;
    private static Map<String, EnemyThemeData> byId;
    private static Map<String, List<EnemyThemeData>> byTag;
    private static boolean loadAttempted;
    /** Null = use Config; non-null overrides for unit tests. */
    private static Boolean forceEnabledForTests;

    /** Window-key → legal name set (mirrors {@link StandardWindow#legalNames()}). */
    private static String cachedWindowKey;
    private static Set<String> cachedWindowLegalNames;
    /** Pool cache: windowKey|colors|tribe|mechanics → unique cards. */
    private static final Map<String, List<PaperCard>> windowPoolCache = new HashMap<>();
    private static Set<String> cachedRestrictedNames;
    private static Set<String> cachedRestrictedEditions;
    private static Set<String> cachedEnemyBannedNames;

    private EnemyThemeDecks() {
    }

    /** Drop cached catalog and pools (plane switch / tests). Does not clear the enabled override. */
    public static void clearCache() {
        synchronized (LOCK) {
            catalog = null;
            byId = null;
            byTag = null;
            loadAttempted = false;
            cachedWindowKey = null;
            cachedWindowLegalNames = null;
            windowPoolCache.clear();
            cachedRestrictedNames = null;
            cachedRestrictedEditions = null;
            cachedEnemyBannedNames = null;
        }
    }

    /**
     * Test hook: install a catalog without touching {@link Config} file IO.
     * Preserves {@link #setEnabledForTests(Boolean)}.
     */
    public static void loadCatalogForTests(EnemyThemeCatalogData data) {
        synchronized (LOCK) {
            byId = new HashMap<>();
            byTag = new HashMap<>();
            loadAttempted = true;
            catalog = data != null ? data : new EnemyThemeCatalogData();
            windowPoolCache.clear();
            cachedWindowKey = null;
            cachedWindowLegalNames = null;
            cachedRestrictedNames = null;
            cachedRestrictedEditions = null;
            cachedEnemyBannedNames = null;
            if (catalog.themes == null)
                return;
            for (EnemyThemeData t : catalog.themes) {
                if (t == null || t.id == null || t.id.isEmpty())
                    continue;
                if (t.core == null || t.core.length == 0)
                    attachCore(t);
                byId.put(t.id, t);
                if (t.tags == null)
                    continue;
                for (String tag : t.tags) {
                    if (tag == null || tag.isEmpty())
                        continue;
                    byTag.computeIfAbsent(normalizeTag(tag), k -> new ArrayList<>()).add(t);
                }
            }
        }
    }

    /** Test hook: force EN1 on/off without a live Ascendant Config. Pass null to clear. */
    public static void setEnabledForTests(Boolean enabled) {
        forceEnabledForTests = enabled;
    }

    /** Ensure {@link EnemyThemeData#core} is populated from {@code world/enemy_cores/<id>.json}. */
    public static void ensureCoreLoaded(EnemyThemeData theme) {
        attachCore(theme);
    }

    /** Load {@code world/enemy_cores/<id>.json} into {@link EnemyThemeData#core} when present. */
    private static void attachCore(EnemyThemeData theme) {
        if (theme == null || theme.id == null || theme.id.isEmpty())
            return;
        if (theme.core != null && theme.core.length > 0)
            return;
        try {
            FileHandle handle = Config.instance().getFile(Paths.ENEMY_CORES_DIR + theme.id + ".json");
            if (handle == null || !handle.exists()) {
                // Desktop / test working directories: resolve beside enemy_themes.json.
                java.nio.file.Path alt = java.nio.file.Paths.get(
                        "forge-gui/res/adventure/common/world/enemy_cores/" + theme.id + ".json");
                if (!Files.isRegularFile(alt))
                    alt = java.nio.file.Paths.get(
                            "../forge-gui/res/adventure/common/world/enemy_cores/" + theme.id + ".json");
                if (Files.isRegularFile(alt))
                    handle = new FileHandle(alt.toFile());
            }
            if (handle == null || !handle.exists()) {
                LOG.warning("EN1: missing core for " + theme.id);
                theme.core = new String[0];
                return;
            }
            EnemyThemeCoreData parsed = new Json().fromJson(EnemyThemeCoreData.class, handle);
            theme.core = parsed != null && parsed.cards != null ? parsed.cards : new String[0];
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: failed to load core for " + theme.id, e);
            theme.core = new String[0];
        }
    }

    public static boolean isEnabled() {
        if (forceEnabledForTests != null)
            return forceEnabledForTests;
        if (!Config.ascendant())
            return false;
        ConfigData cfg = Config.instance().getConfigData();
        return cfg == null || cfg.en1EnemyThemeDecks;
    }

    public static List<EnemyThemeData> allThemes() {
        ensureLoaded();
        synchronized (LOCK) {
            if (catalog == null || catalog.themes == null)
                return Collections.emptyList();
            List<EnemyThemeData> out = new ArrayList<>();
            for (EnemyThemeData t : catalog.themes) {
                if (t != null && t.id != null && !t.id.isEmpty())
                    out.add(t);
            }
            return out;
        }
    }

    public static EnemyThemeData getTheme(String themeId) {
        ensureLoaded();
        synchronized (LOCK) {
            if (themeId == null || byId == null)
                return null;
            return byId.get(themeId);
        }
    }

    /**
     * Themes that list {@code tag} (case-insensitive). Empty when none. Used by EN2
     * partner pairing and the creature-type tag coverage test.
     */
    public static List<EnemyThemeData> themesForTag(String tag) {
        ensureLoaded();
        synchronized (LOCK) {
            if (tag == null || tag.isEmpty() || byTag == null)
                return Collections.emptyList();
            List<EnemyThemeData> list = byTag.get(normalizeTag(tag));
            if (list == null || list.isEmpty())
                return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(list));
        }
    }

    /**
     * Every distinct theme tag → theme count. EN2 requires each creature-type tag
     * to have two or more themes so a pair never mirrors.
     */
    public static Map<String, Integer> themeCountsByTag() {
        ensureLoaded();
        synchronized (LOCK) {
            Map<String, Integer> counts = new HashMap<>();
            if (byTag == null)
                return counts;
            for (Map.Entry<String, List<EnemyThemeData>> e : byTag.entrySet()) {
                if (e.getKey() == null || e.getValue() == null)
                    continue;
                counts.put(e.getKey(), e.getValue().size());
            }
            return counts;
        }
    }

    /**
     * Picks a theme for an enemy at spawn from its quest tags. Returns null when
     * EN1 is off, the enemy is a boss, or no theme matches.
     */
    public static String pickThemeId(EnemyData data, Random rng) {
        if (!isEnabled() || data == null || data.boss)
            return null;
        ensureLoaded();
        List<EnemyThemeData> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        synchronized (LOCK) {
            if (byTag == null || byTag.isEmpty())
                return null;
            if (data.questTags != null) {
                for (String tag : data.questTags) {
                    if (tag == null || tag.isEmpty())
                        continue;
                    List<EnemyThemeData> list = byTag.get(normalizeTag(tag));
                    if (list == null)
                        continue;
                    for (EnemyThemeData t : list) {
                        if (t.id != null && seen.add(t.id))
                            candidates.add(t);
                    }
                }
            }
            if (data.name != null) {
                List<EnemyThemeData> list = byTag.get(normalizeTag(data.name));
                if (list != null) {
                    for (EnemyThemeData t : list) {
                        if (t.id != null && seen.add(t.id))
                            candidates.add(t);
                    }
                }
            }
        }
        if (candidates.isEmpty())
            return null;
        Random r = rng != null ? rng : MyRandom.getRandom();
        return candidates.get(r.nextInt(candidates.size())).id;
    }

    /**
     * Copies enemy data and assigns a theme id when EN1 applies. Returns the
     * (possibly copied) data; never throws.
     */
    public static EnemyData assignThemeAtSpawn(EnemyData catalogEnemy) {
        try {
            if (!isEnabled() || catalogEnemy == null)
                return catalogEnemy;
            String themeId = pickThemeId(catalogEnemy, MyRandom.getRandom());
            if (themeId == null || themeId.isEmpty())
                return catalogEnemy;
            EnemyData copy = new EnemyData(catalogEnemy);
            copy.themeId = themeId;
            return copy;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: theme assign failed; using catalog enemy", e);
            return catalogEnemy;
        }
    }

    /**
     * Resolves a deck for the enemy's theme and the current run format.
     * Builds once per enemy instance (caches on {@link EnemyData#preparedDeck})
     * and only loads the stock deck when themed resolution fails.
     * Never throws; never returns null (empty deck at worst).
     */
    public static Deck resolveDeck(EnemyData enemy, boolean isFantasyMode, boolean useGeneticAI) {
        if (enemy != null && enemy.preparedDeck != null && !enemy.preparedDeck.isEmpty())
            return enemy.preparedDeck;

        if (!isEnabled() || enemy == null || enemy.themeId == null || enemy.themeId.isEmpty()) {
            Deck stock = loadStockDeck(enemy, isFantasyMode, useGeneticAI);
            if (enemy != null)
                enemy.preparedDeck = stock;
            return stock;
        }

        try {
            String format = resolveFormat();
            Deck themed = resolveForThemeAndFormat(enemy.themeId, format);
            if (themed != null && !themed.isEmpty()) {
                enemy.preparedDeck = themed;
                return themed;
            }
            LOG.warning("EN1: no deck for theme=" + enemy.themeId + " format=" + format
                    + "; falling back to stock");
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "EN1: theme deck resolve failed; falling back to stock", e);
        }
        Deck stock = loadStockDeck(enemy, isFantasyMode, useGeneticAI);
        enemy.preparedDeck = stock;
        return stock;
    }

    /**
     * Resolve a theme deck for an explicit format. Used by tests and the deck
     * generator. Returns null when nothing usable is found (caller falls back).
     */
    public static Deck resolveForThemeAndFormat(String themeId, String format) {
        try {
            ensureLoaded();
            if (themeId == null || themeId.isEmpty())
                return null;
            EnemyThemeData theme;
            synchronized (LOCK) {
                theme = byId != null ? byId.get(themeId) : null;
            }
            if (theme == null) {
                LOG.warning("EN1: unknown theme " + themeId);
                return tryAnyThemeDeck(format);
            }

            String fmt = normalizeFormat(format);
            Deck deck = loadFixedOrRecipe(theme, fmt);
            if (deck != null && !deck.isEmpty())
                return deck;

            for (String alt : FORMAT_FALLBACK_ORDER) {
                if (alt.equals(fmt))
                    continue;
                deck = loadFixedOrRecipe(theme, alt);
                if (deck != null && !deck.isEmpty()) {
                    LOG.warning("EN1: theme " + themeId + " missing " + fmt
                            + "; using " + alt);
                    return deck;
                }
            }

            synchronized (LOCK) {
                if (theme.tags != null && byTag != null) {
                    for (String tag : theme.tags) {
                        List<EnemyThemeData> siblings = byTag.get(normalizeTag(tag));
                        if (siblings == null)
                            continue;
                        for (EnemyThemeData sib : siblings) {
                            if (sib == theme || sib.id == null)
                                continue;
                            deck = loadFixedOrRecipe(sib, fmt);
                            if (deck != null && !deck.isEmpty()) {
                                LOG.warning("EN1: theme " + themeId + " empty; using sibling "
                                        + sib.id + " for " + fmt);
                                return deck;
                            }
                        }
                    }
                }
            }

            return tryAnyThemeDeck(fmt);
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "EN1: resolveForThemeAndFormat failed", e);
            return null;
        }
    }

    /**
     * Builds a Bellwarden Standard deck from the theme recipe using only cards
     * legal in {@code window}. Requires an active window; never treats an inactive
     * window as "everything legal". When the on-theme floor cannot be met even
     * after relaxing tribe then colors, returns an empty deck so the caller can
     * fall back to the theme's fixed Historic list. Never pads a mostly-basics deck.
     */
    public static Deck fillStandardRecipe(EnemyThemeData theme, StandardWindow window, long seed) {
        Deck empty = new Deck(theme != null && theme.id != null ? theme.id + " Standard" : "EN1 Standard");
        try {
            if (theme == null)
                return empty;
            if (window == null || !window.isActive()) {
                LOG.warning("EN1: Standard window inactive; refusing all-legal fill for "
                        + theme.id);
                return empty;
            }
            EnemyThemeRecipeData recipe = theme.standardRecipe;
            if (recipe == null) {
                recipe = new EnemyThemeRecipeData();
                recipe.colors = theme.colors;
                recipe.tribe = firstCreatureType(theme);
                recipe.count = standardTargetSize();
            }
            int target = recipe.count > 0 ? recipe.count : standardTargetSize();
            String tribe = recipe.tribe != null && !recipe.tribe.isEmpty()
                    ? recipe.tribe : firstCreatureType(theme);
            String[] colors = recipe.colors != null && recipe.colors.length > 0
                    ? recipe.colors : theme.colors;

            // 1) tribe + colors, 2) colors only, 3) any window-legal (still in-window).
            List<PaperCard> pool = buildWindowPool(window, colors, tribe, recipe);
            if (countOnThemeInPool(pool, theme) < MIN_ON_THEME_NONLAND_STANDARD)
                pool = buildWindowPool(window, colors, null, recipe);
            if (countOnThemeInPool(pool, theme) < MIN_ON_THEME_NONLAND_STANDARD)
                pool = buildWindowPool(window, null, null, recipe);
            if (countOnThemeInPool(pool, theme) < MIN_ON_THEME_NONLAND_STANDARD) {
                LOG.warning("EN1: Standard recipe below on-theme floor for " + theme.id
                        + "; caller should use Historic fallback");
                return empty;
            }

            Random rng = new Random(seed);
            Collections.shuffle(pool, rng);

            Deck deck = new Deck(theme.id + " Standard");
            CardPool main = deck.getOrCreate(DeckSection.Main);
            Set<String> used = new HashSet<>();

            String[] keys = recipe.keyCards != null && recipe.keyCards.length > 0
                    ? recipe.keyCards : theme.keyCards;
            if (keys != null) {
                for (String name : keys) {
                    if (name == null || name.isEmpty() || used.contains(name))
                        continue;
                    if (isRestrictedCardName(name) || isEnemyBanned(name)
                            || isAdventureBanned("standard", name))
                        continue;
                    if (!windowLegalName(window, name))
                        continue;
                    PaperCard pc = cardByName(name);
                    if (pc == null || isExcludedFromAdventureDecks(pc) || !isStandardWindowLegal(pc, window))
                        continue;
                    if (pc.getRules().getType().isBasicLand())
                        continue;
                    int copies = Math.min(4, 2);
                    for (int i = 0; i < copies && main.countAll() < target - TARGET_LANDS_60; i++)
                        main.add(pc);
                    used.add(name);
                }
            }

            int spellTarget = target - TARGET_LANDS_60;
            List<PaperCard> onTheme = new ArrayList<>();
            List<PaperCard> other = new ArrayList<>();
            for (PaperCard pc : pool) {
                if (pc == null || used.contains(pc.getName()))
                    continue;
                if (pc.getRules().getType().isLand())
                    continue;
                if (isOnTheme(pc, theme))
                    onTheme.add(pc);
                else
                    other.add(pc);
            }
            Collections.shuffle(onTheme, rng);
            Collections.shuffle(other, rng);

            for (PaperCard pc : onTheme) {
                if (main.countAll() >= spellTarget)
                    break;
                addCopies(main, pc, 2 + rng.nextInt(3), spellTarget, used);
            }
            if (countOnThemeNonLand(deck, theme) < MIN_ON_THEME_NONLAND_STANDARD) {
                LOG.warning("EN1: Standard fill produced thin on-theme count for " + theme.id);
                return empty;
            }
            for (PaperCard pc : other) {
                if (main.countAll() >= spellTarget)
                    break;
                addCopies(main, pc, 1 + rng.nextInt(3), spellTarget, used);
            }

            // Pad lands from actual spell colors (not a null/relaxed color filter).
            String[] padColors = colorsFromMask(spellColorMask(deck));
            if (padColors.length == 0)
                padColors = colors;
            rebuildBasicLands(deck, padColors, target);
            stripIllegalStandard(deck, window, target, padColors);
            if (countOnThemeNonLand(deck, theme) < MIN_ON_THEME_NONLAND_STANDARD)
                return empty;
            return deck;
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "EN1: Standard recipe fill failed for "
                    + (theme != null ? theme.id : "?"), e);
            return empty;
        }
    }

    /** Relative deck directory for a theme: {@code decks/enemy/<themeId>/}. */
    public static String themeDeckDir(String themeId) {
        return "decks/enemy/" + themeId + "/";
    }

    /**
     * Discovers fixed deck paths for a theme and format under
     * {@code decks/enemy/<theme>/<format>_N.dck}.
     */
    public static List<String> listFixedDeckPaths(String themeId, String format) {
        List<String> out = new ArrayList<>();
        try {
            String fmt = formatFileToken(format);
            String dir = themeDeckDir(themeId);
            FileHandle folder = null;
            try {
                folder = Config.instance().getFile(dir);
            } catch (Throwable ignored) {
                folder = null;
            }
            if (folder != null && folder.exists() && folder.isDirectory()) {
                FileHandle[] children = folder.list(".dck");
                if (children != null) {
                    for (FileHandle child : children) {
                        String name = child.name().toLowerCase(Locale.ROOT);
                        if (name.startsWith(fmt + "_") && name.endsWith(".dck"))
                            out.add(dir + child.name());
                    }
                }
            }
            if (out.isEmpty()) {
                for (int n = 1; n <= 4; n++) {
                    String rel = dir + fmt + "_" + n + ".dck";
                    FileHandle fh = null;
                    try {
                        fh = Config.instance().getFile(rel);
                    } catch (Throwable ignored) {
                        fh = null;
                    }
                    if (fh != null && fh.exists())
                        out.add(rel);
                }
            }
            Collections.sort(out);
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "EN1: listFixedDeckPaths failed for " + themeId, e);
        }
        return out;
    }

    /**
     * Absolute filesystem directory for writing generated decks (tools / tests).
     * Prefer common adventure resources.
     */
    public static File commonEnemyDeckDir(String themeId) {
        String base = Config.instance().getCommonFilePath(themeDeckDir(themeId));
        return new File(base);
    }

    // ---- theme / quality helpers (public for tests) -------------------------

    /**
     * True when every printing of this card is unusable in Ascendant enemy decks:
     * Alchemy/rebalanced, Online-only, Funny/Un-sets, {@code restrictedEditions},
     * or the EN1 overworld power-level ban list ({@link Paths#ENEMY_BANNED}).
     * Cards that also have a normal paper printing are allowed (unless name-banned).
     */
    public static boolean isExcludedFromAdventureDecks(PaperCard pc) {
        if (pc == null)
            return true;
        String name = pc.getName();
        if (name == null || name.isEmpty())
            return true;
        if (isRestrictedCardName(name) || isEnemyBanned(name))
            return true;
        try {
            if (pc.isRebalanced())
                return true;
        } catch (Throwable ignored) {
        }
        if (name.startsWith("A-"))
            return true;

        Collection<PaperCard> prints;
        try {
            prints = FModel.getMagicDb().getCommonCards().getAllCards(name);
        } catch (Throwable e) {
            prints = null;
        }
        if (prints == null || prints.isEmpty()) {
            // Fall back to the single printing we have.
            return isBadEditionCode(pc.getEdition());
        }
        for (PaperCard print : prints) {
            if (print == null)
                continue;
            if (!isBadEditionCode(print.getEdition()))
                return false;
        }
        return true;
    }

    /** @deprecated use {@link #isExcludedFromAdventureDecks(PaperCard)} */
    public static boolean isAlchemyOrDigitalOnly(PaperCard pc) {
        return isExcludedFromAdventureDecks(pc);
    }

    public static boolean isRestrictedCardName(String name) {
        if (name == null || name.isEmpty())
            return false;
        return restrictedNames().contains(name);
    }

    /** True when {@code name} is on the EN1 overworld power-level ban list. */
    public static boolean isEnemyBanned(String name) {
        if (name == null || name.isEmpty())
            return false;
        return enemyBannedNames().contains(name);
    }

    /**
     * On-theme: key/commander names, creature-type subtypes (plus Changeling),
     * tribal oracle references to those types (word-boundary), or named keywords
     * from {@code theme.mechanics}. Does not substring-match generic words like
     * "token" or "damage".
     */
    public static boolean isOnTheme(PaperCard pc, EnemyThemeData theme) {
        if (pc == null || theme == null || pc.getRules() == null)
            return false;
        if (pc.getRules().getType().isBasicLand())
            return false;
        if (isInCore(pc.getName(), theme))
            return true;
        if (theme.keyCards != null) {
            for (String k : theme.keyCards) {
                if (k != null && k.equals(pc.getName()))
                    return true;
            }
        }
        if (theme.preferredCommanders != null) {
            for (String k : theme.preferredCommanders) {
                if (k != null && k.equals(pc.getName()))
                    return true;
            }
        }
        boolean changeling = false;
        try {
            changeling = pc.getRules().hasKeyword("Changeling");
        } catch (Throwable ignored) {
        }
        if (theme.creatureTypes != null) {
            for (String t : theme.creatureTypes) {
                if (t == null || t.isEmpty())
                    continue;
                if (changeling || pc.getRules().getType().hasSubtype(t))
                    return true;
            }
            // Tribal support: oracle mentions a theme creature type as a whole word.
            String oracle = pc.getRules().getOracleText();
            if (oracle != null) {
                for (String t : theme.creatureTypes) {
                    if (t == null || t.isEmpty())
                        continue;
                    if (wordMatches(oracle, t))
                        return true;
                }
            }
        }
        if (theme.mechanics != null) {
            for (String m : theme.mechanics) {
                if (m == null || m.isEmpty())
                    continue;
                // Only treat real keywords / short named mechanics — skip fuzzy generics.
                if (isGenericMechanicToken(m))
                    continue;
                try {
                    if (pc.getRules().hasKeyword(m))
                        return true;
                    // Capitalize for keyword lookup variants.
                    String titled = m.substring(0, 1).toUpperCase(Locale.ROOT) + m.substring(1);
                    if (pc.getRules().hasKeyword(titled))
                        return true;
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    private static boolean isGenericMechanicToken(String m) {
        String s = m.toLowerCase(Locale.ROOT);
        return s.equals("token") || s.equals("damage") || s.equals("burn")
                || s.equals("mana") || s.equals("life") || s.equals("+1/+1")
                || s.equals("graveyard") || s.equals("zombie") || s.equals("goblin")
                || s.equals("dragon") || s.equals("spirit") || s.equals("soldier")
                || s.equals("knight") || s.equals("merfolk") || s.equals("kraken")
                || s.equals("leviathan") || s.equals("octopus") || s.equals("elf")
                || s.equals("vampire");
    }

    private static boolean wordMatches(String text, String word) {
        if (text == null || word == null || word.isEmpty())
            return false;
        Pattern p = Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE);
        return p.matcher(text).find();
    }

    private static boolean isBadEditionCode(String code) {
        if (code == null || code.isEmpty())
            return true;
        String ed = code.trim();
        // Not-yet-released / promo-fest codes must never appear in enemy decks.
        if (ed.equalsIgnoreCase("TRK") || ed.equalsIgnoreCase("PF27"))
            return true;
        if (ed.length() >= 2 && (ed.charAt(0) == 'Y' || ed.charAt(0) == 'y'))
            return true;
        if (ed.regionMatches(true, 0, "OM", 0, 2))
            return true;
        if (restrictedEditionCodes().contains(ed))
            return true;
        try {
            CardEdition edition = FModel.getMagicDb().getEditions().get(ed);
            if (edition == null)
                return true;
            CardEdition.Type type = edition.getType();
            if (type == CardEdition.Type.ONLINE || type == CardEdition.Type.FUNNY)
                return true;
            if (edition.getBorderColor() == CardEdition.BorderColor.SILVER)
                return true;
        } catch (Throwable e) {
            return true;
        }
        return false;
    }

    /**
     * Basics must use ordinary printings from released non-promo sets.
     * Unreleased dates, promo-only editions, TRK, and PF27 fail this check.
     */
    public static boolean isUnreleasedOrPromoOnlyEdition(String code) {
        if (code == null || code.isEmpty())
            return true;
        String ed = code.trim();
        if (ed.equalsIgnoreCase("TRK") || ed.equalsIgnoreCase("PF27"))
            return true;
        try {
            CardEdition edition = FModel.getMagicDb().getEditions().get(ed);
            if (edition == null)
                return true;
            if (edition.getType() == CardEdition.Type.PROMO)
                return true;
            Date date = edition.getDate();
            if (date != null && date.after(new Date()))
                return true;
        } catch (Throwable e) {
            return true;
        }
        return false;
    }

    public static int countOnThemeNonLand(Deck deck, EnemyThemeData theme) {
        if (deck == null)
            return 0;
        int n = 0;
        for (PaperCard pc : deck.getAllCardsInASinglePool(true, false).toFlatList()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRules().getType().isLand())
                continue;
            if (isOnTheme(pc, theme))
                n++;
        }
        return n;
    }

    public static int countLands(Deck deck) {
        if (deck == null)
            return 0;
        int n = 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc != null && pc.getRules() != null && pc.getRules().getType().isLand())
                n += e.getValue();
        }
        return n;
    }

    /**
     * Theme-quality gate for fixed decks: on-theme floor, land band for 60-card,
     * non-creature spells, land colors covering spells/commander, sane average CMC,
     * no Alchemy/Un/Online-only / Ascendant restricted cards. Returns null if OK.
     */
    public static String themeQualityProblem(Deck deck, EnemyThemeData theme, String format) {
        if (deck == null)
            return "deck is null";
        if (theme == null)
            return "theme is null";
        String alchemy = alchemyOrRestrictedProblem(deck);
        if (alchemy != null)
            return alchemy;
        String fmt = normalizeFormat(format);
        String coreProblem = coreMembershipProblem(deck, theme, fmt);
        if (coreProblem != null)
            return coreProblem;
        int onTheme = countOnThemeNonLand(deck, theme);
        if (onTheme < MIN_ON_THEME_NONLAND_FIXED)
            return "only " + onTheme + " on-theme non-lands (need ~"
                    + MIN_ON_THEME_NONLAND_FIXED + ")";
        String landCover = landColorCoverageProblem(deck, fmt);
        if (landCover != null)
            return landCover;
        String landBand = landCountProblem(deck, fmt);
        if (landBand != null)
            return landBand;
        if (FORMAT_PAUPER.equals(fmt) || FORMAT_HISTORIC.equals(fmt)) {
            int main = deck.getMain().countAll();
            if (main < 60)
                return "main deck has " + main + " cards (need 60+)";
            int spells = countNonCreatureSpells(deck);
            if (spells < MIN_NON_CREATURE_SPELLS_60)
                return "only " + spells + " non-creature spells (need ~"
                        + MIN_NON_CREATURE_SPELLS_60 + ")";
            if (expectsLowCurve(theme)) {
                float avg = averageNonLandCmc(deck);
                if (avg > MAX_AVG_CMC_NON_RAMP)
                    return "average CMC " + String.format(Locale.ROOT, "%.2f", avg)
                            + " too high for non-ramp theme (max " + MAX_AVG_CMC_NON_RAMP + ")";
            }
            if (FORMAT_HISTORIC.equals(fmt) && theme.id != null && theme.id.contains("dragon")) {
                int dragons = countTribalCreatures(deck, theme);
                if (dragons < MIN_DRAGONS_HISTORIC)
                    return "only " + dragons + " Dragons (need " + MIN_DRAGONS_HISTORIC + ")";
                int sprawl = 0;
                int forests = 0;
                for (var e : deck.getMain()) {
                    PaperCard pc = e.getKey();
                    if (pc == null)
                        continue;
                    if ("Utopia Sprawl".equals(pc.getName()))
                        sprawl += e.getValue();
                    if ("Forest".equals(pc.getName()) || "Snow-Covered Forest".equals(pc.getName()))
                        forests += e.getValue();
                }
                if (sprawl > forests)
                    return "Utopia Sprawl x" + sprawl + " with only " + forests + " Forests";
            }
            String tribal = tribalCreatureCountProblem(deck, theme, fmt);
            if (tribal != null)
                return tribal;
        }
        if (FORMAT_COMMANDER.equals(fmt)) {
            int nonLand = countNonLandsAll(deck);
            if (nonLand < MIN_NONLAND_COMMANDER)
                return "only " + nonLand + " nonland cards (need " + MIN_NONLAND_COMMANDER + ")";
            String tribal = tribalCreatureCountProblem(deck, theme, fmt);
            if (tribal != null)
                return tribal;
        }
        return null;
    }

    /** Land-count gate: 16–18 for 60-card; 35–39 for Commander. */
    public static String landCountProblem(Deck deck, String format) {
        if (deck == null)
            return "deck is null";
        String fmt = normalizeFormat(format);
        int lands = countLands(deck);
        if (FORMAT_COMMANDER.equals(fmt)) {
            if (lands < MIN_LANDS_COMMANDER || lands > MAX_LANDS_COMMANDER)
                return "lands=" + lands + " (need " + MIN_LANDS_COMMANDER + "-"
                        + MAX_LANDS_COMMANDER + ")";
            return null;
        }
        if (FORMAT_PAUPER.equals(fmt) || FORMAT_HISTORIC.equals(fmt)) {
            if (lands < MIN_LANDS_60 || lands > MAX_LANDS_60)
                return "lands=" + lands + " (need " + MIN_LANDS_60 + "-" + MAX_LANDS_60 + ")";
        }
        return null;
    }

    public static int countCreatureType(Deck deck, String type) {
        if (deck == null || type == null)
            return 0;
        int n = 0;
        for (var e : deck.getAllCardsInASinglePool(true, false)) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null)
                continue;
            if (!pc.getRules().getType().isCreature())
                continue;
            boolean changeling = false;
            try {
                changeling = pc.getRules().hasKeyword("Changeling");
            } catch (Throwable ignored) {
            }
            if (changeling || pc.getRules().getType().hasSubtype(type))
                n += e.getValue();
        }
        return n;
    }

    /** Creatures matching any of {@code theme.creatureTypes}, counting changelings. */
    public static int countTribalCreatures(Deck deck, EnemyThemeData theme) {
        if (deck == null || theme == null || theme.creatureTypes == null
                || theme.creatureTypes.length == 0)
            return 0;
        int n = 0;
        for (var e : deck.getAllCardsInASinglePool(true, false)) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null || !pc.getRules().getType().isCreature())
                continue;
            boolean changeling = false;
            try {
                changeling = pc.getRules().hasKeyword("Changeling");
            } catch (Throwable ignored) {
            }
            if (changeling) {
                n += e.getValue();
                continue;
            }
            for (String t : theme.creatureTypes) {
                if (t != null && !t.isEmpty() && pc.getRules().getType().hasSubtype(t)) {
                    n += e.getValue();
                    break;
                }
            }
        }
        return n;
    }

    /** Tribal themes need a real creature-type density, not merely core membership. */
    public static String tribalCreatureCountProblem(Deck deck, EnemyThemeData theme, String format) {
        if (deck == null || theme == null || !isTribalTheme(theme))
            return null;
        String fmt = normalizeFormat(format);
        int have = countTribalCreatures(deck, theme);
        int need = FORMAT_COMMANDER.equals(fmt) ? MIN_TRIBAL_CREATURES_COMMANDER
                : MIN_TRIBAL_CREATURES_60;
        if (have < need)
            return "only " + have + " tribe creatures (need ~" + need + ")";
        return null;
    }

    private static boolean isTribalTheme(EnemyThemeData theme) {
        if (theme == null || theme.id == null || theme.creatureTypes == null
                || theme.creatureTypes.length == 0)
            return false;
        String id = theme.id;
        // Strict tribal lists + creature-type specialty themes (EN1/EN2).
        return id.contains("tribal") || "spirit_tempo".equals(id) || "merfolk_tempo".equals(id)
                || "kraken_leviathan".equals(id) || "serpent_leviathan".equals(id);
    }

    public static int countNonLandsAll(Deck deck) {
        if (deck == null)
            return 0;
        int n = 0;
        for (var e : deck.getAllCardsInASinglePool(true, false)) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null)
                continue;
            if (!pc.getRules().getType().isLand())
                n += e.getValue();
        }
        return n;
    }

    /** True when {@code name} is listed in the theme's hand-picked core (or preferred commanders). */
    public static boolean isInCore(String name, EnemyThemeData theme) {
        if (name == null || theme == null)
            return false;
        if (theme.core != null) {
            for (String c : theme.core) {
                if (name.equals(c))
                    return true;
            }
        }
        // Preferred commanders count as core staples for Commander lists.
        if (theme.preferredCommanders != null) {
            for (String c : theme.preferredCommanders) {
                if (name.equals(c))
                    return true;
            }
        }
        if (theme.keyCards != null) {
            for (String c : theme.keyCards) {
                if (name.equals(c))
                    return true;
            }
        }
        return false;
    }

    /** Count non-land copies (main + commander) whose names are in the theme core. */
    public static int countCoreCards(Deck deck, EnemyThemeData theme) {
        if (deck == null || theme == null)
            return 0;
        int n = 0;
        for (var e : deck.getAllCardsInASinglePool(true, false)) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRules().getType().isLand())
                continue;
            if (isInCore(pc.getName(), theme))
                n += e.getValue();
        }
        return n;
    }

    /** Non-land cards that are not in the theme core (generated filler). */
    public static int countFillerNonLand(Deck deck, EnemyThemeData theme) {
        if (deck == null || theme == null)
            return 0;
        int n = 0;
        for (var e : deck.getAllCardsInASinglePool(true, false)) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRules().getType().isLand())
                continue;
            if (isInCore(pc.getName(), theme))
                continue;
            // Tribe creatures that meet the tribal floor are not "filler".
            if (isTribalTheme(theme) && countsAsTribalCreature(pc, theme))
                continue;
            n += e.getValue();
        }
        return n;
    }

    /** Core size / filler gates for fixed decks. Returns null if OK. */
    public static String coreMembershipProblem(Deck deck, EnemyThemeData theme) {
        return coreMembershipProblem(deck, theme, null);
    }

    public static String coreMembershipProblem(Deck deck, EnemyThemeData theme, String format) {
        if (theme == null || theme.core == null || theme.core.length < MIN_CORE_CARDS_IN_DECK)
            return "theme core has fewer than " + MIN_CORE_CARDS_IN_DECK + " named cards";
        int core = countCoreCards(deck, theme);
        if (core < MIN_CORE_CARDS_IN_DECK)
            return "only " + core + " core cards (need " + MIN_CORE_CARDS_IN_DECK + ")";
        int filler = countFillerNonLand(deck, theme);
        int maxFiller = FORMAT_COMMANDER.equals(normalizeFormat(format))
                ? MAX_FILLER_COMMANDER : MAX_FILLER_NONLAND;
        // Heuristic when format omitted: Commander-sized decks use the EDH filler cap.
        if (format == null && deck != null && deck.has(DeckSection.Commander))
            maxFiller = MAX_FILLER_COMMANDER;
        if (filler > maxFiller)
            return "filler non-lands=" + filler + " (max " + maxFiller + ")";
        return null;
    }

    public static int countNonCreatureSpells(Deck deck) {
        if (deck == null)
            return 0;
        int n = 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRules().getType().isLand() || pc.getRules().getType().isCreature())
                continue;
            n += e.getValue();
        }
        return n;
    }

    /** Low-curve archetypes (not ramp / fat tribal like dragons or sea monsters). */
    private static boolean expectsLowCurve(EnemyThemeData theme) {
        if (theme == null || theme.id == null)
            return true;
        String id = theme.id;
        return !id.contains("ramp") && !id.contains("dragon") && !id.contains("kraken")
                && !id.contains("serpent") && !id.contains("leviathan");
    }

    public static float averageNonLandCmc(Deck deck) {
        if (deck == null)
            return 0f;
        int total = 0;
        int count = 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                continue;
            int cmc = pc.getRules().getManaCost().getCMC();
            total += cmc * e.getValue();
            count += e.getValue();
        }
        return count == 0 ? 0f : (float) total / count;
    }

    /** Lands must produce every color used by spells (and commander identity). */
    public static String landColorCoverageProblem(Deck deck, String format) {
        if (deck == null)
            return "deck is null";
        byte need = spellColorMask(deck);
        String fmt = normalizeFormat(format);
        if (FORMAT_COMMANDER.equals(fmt) && deck.getCommanders() != null) {
            for (PaperCard cmd : deck.getCommanders()) {
                if (cmd != null && cmd.getRules() != null)
                    need |= cmd.getRules().getColorIdentity().getColor();
            }
        }
        if (need == 0)
            return null;
        byte have = landColorMask(deck);
        byte missing = (byte) (need & ~have);
        if (missing == 0)
            return null;
        return "lands missing colors for spells/commander: mask=" + missing;
    }

    public static byte spellColorMask(Deck deck) {
        byte m = 0;
        if (deck == null)
            return 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                continue;
            m |= pc.getRules().getColor().getColor();
            m |= pc.getRules().getManaCost().getColorProfile();
        }
        return m;
    }

    public static byte landColorMask(Deck deck) {
        byte m = 0;
        if (deck == null)
            return 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null || !pc.getRules().getType().isLand())
                continue;
            String name = pc.getName();
            if ("Plains".equals(name) || "Snow-Covered Plains".equals(name))
                m |= MagicColor.WHITE;
            else if ("Island".equals(name) || "Snow-Covered Island".equals(name))
                m |= MagicColor.BLUE;
            else if ("Swamp".equals(name) || "Snow-Covered Swamp".equals(name))
                m |= MagicColor.BLACK;
            else if ("Mountain".equals(name) || "Snow-Covered Mountain".equals(name))
                m |= MagicColor.RED;
            else if ("Forest".equals(name) || "Snow-Covered Forest".equals(name))
                m |= MagicColor.GREEN;
            else {
                // Nonbasics: use color identity as a proxy for mana produced.
                m |= pc.getRules().getColorIdentity().getColor();
            }
        }
        return m;
    }

    // ---- internals ----------------------------------------------------------

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loadAttempted)
                return;
            loadAttempted = true;
            byId = new HashMap<>();
            byTag = new HashMap<>();
            catalog = new EnemyThemeCatalogData();
            try {
                FileHandle handle = Config.instance().getFile(Paths.ENEMY_THEMES);
                if (handle == null || !handle.exists()) {
                    LOG.warning("EN1: missing " + Paths.ENEMY_THEMES);
                    return;
                }
                EnemyThemeCatalogData parsed = new Json().fromJson(EnemyThemeCatalogData.class, handle);
                if (parsed == null || parsed.themes == null)
                    return;
                catalog = parsed;
                for (EnemyThemeData t : parsed.themes) {
                    if (t == null || t.id == null || t.id.isEmpty())
                        continue;
                    attachCore(t);
                    byId.put(t.id, t);
                    if (t.tags == null)
                        continue;
                    for (String tag : t.tags) {
                        if (tag == null || tag.isEmpty())
                            continue;
                        byTag.computeIfAbsent(normalizeTag(tag), k -> new ArrayList<>()).add(t);
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "EN1: failed to load enemy_themes.json", e);
                catalog = new EnemyThemeCatalogData();
                byId = new HashMap<>();
                byTag = new HashMap<>();
            }
        }
    }

    private static String normalizeTag(String tag) {
        return tag.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeFormat(String format) {
        if (format == null || format.isEmpty())
            return FORMAT_STANDARD;
        String f = format.trim();
        if (f.equalsIgnoreCase(FORMAT_PAUPER))
            return FORMAT_PAUPER;
        if (f.equalsIgnoreCase(FORMAT_HISTORIC))
            return FORMAT_HISTORIC;
        if (f.equalsIgnoreCase(FORMAT_COMMANDER))
            return FORMAT_COMMANDER;
        if (f.equalsIgnoreCase(FORMAT_STANDARD) || f.equalsIgnoreCase("Bellwarden")
                || f.equalsIgnoreCase("Bellwarden Standard"))
            return FORMAT_STANDARD;
        return f;
    }

    private static String formatFileToken(String format) {
        String f = normalizeFormat(format);
        return f.toLowerCase(Locale.ROOT);
    }

    private static String resolveFormat() {
        try {
            AdventurePlayer p = Current.player();
            if (p != null)
                return normalizeFormat(p.getRunFormat());
        } catch (Throwable ignored) {
        }
        return FORMAT_STANDARD;
    }

    private static Deck loadFixedOrRecipe(EnemyThemeData theme, String format) {
        String fmt = normalizeFormat(format);
        if (FORMAT_STANDARD.equals(fmt))
            return fillStandardForTheme(theme);

        List<String> paths = listFixedDeckPaths(theme.id, fmt);
        if (paths.isEmpty())
            return null;
        int pick = Math.floorMod(theme.id.hashCode(), paths.size());
        try {
            AdventurePlayer p = Current.player();
            if (p != null)
                pick = Math.floorMod(p.getEnemyDeckNumber(theme.id, paths.size()), paths.size());
        } catch (Throwable ignored) {
        }
        String path = paths.get(pick);
        Deck deck = loadDck(path);
        if (deck == null)
            return null;
        return sanitizeLoadedFixed(deck, fmt, theme);
    }

    private static Deck fillStandardForTheme(EnemyThemeData theme) {
        StandardWindow window = null;
        long seed = theme.id != null ? theme.id.hashCode() : 0L;
        try {
            AdventurePlayer p = Current.player();
            if (p != null) {
                window = p.getStandardWindow();
                seed ^= (long) p.getEnemyDeckNumber(theme.id, 997) * 31L;
            }
        } catch (Throwable ignored) {
        }
        if (window == null || !window.isActive()) {
            // Inactive window ≠ everything legal — use the theme's fixed Historic list.
            return loadFixedDeckOnly(theme, FORMAT_HISTORIC);
        }
        Deck filled = fillStandardRecipe(theme, window, seed);
        if (filled != null && !filled.isEmpty()
                && countOnThemeNonLand(filled, theme) >= MIN_ON_THEME_NONLAND_STANDARD)
            return filled;
        return loadFixedDeckOnly(theme, FORMAT_HISTORIC);
    }

    /** Load a fixed .dck without Standard recipe recursion. */
    private static Deck loadFixedDeckOnly(EnemyThemeData theme, String format) {
        String fmt = normalizeFormat(format);
        if (FORMAT_STANDARD.equals(fmt))
            return null;
        List<String> paths = listFixedDeckPaths(theme.id, fmt);
        if (paths.isEmpty())
            return null;
        int pick = Math.floorMod(theme.id.hashCode(), paths.size());
        try {
            AdventurePlayer p = Current.player();
            if (p != null)
                pick = Math.floorMod(p.getEnemyDeckNumber(theme.id, paths.size()), paths.size());
        } catch (Throwable ignored) {
        }
        Deck deck = loadDck(paths.get(pick));
        if (deck == null)
            return null;
        return sanitizeLoadedFixed(deck, fmt, theme);
    }

    private static Deck loadDck(String relativePath) {
        try {
            FileHandle fh = Config.instance().getFile(relativePath);
            if (fh == null || !fh.exists())
                return null;
            Deck deck = DeckSerializer.fromFile(fh.file());
            if (deck == null || deck.isEmpty())
                return null;
            return deck;
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "EN1: failed to load " + relativePath, e);
            return null;
        }
    }

    private static Deck sanitizeLoadedFixed(Deck deck, String format, EnemyThemeData theme) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (isRestrictedCardName(pc.getName()) || isExcludedFromAdventureDecks(pc))
                continue;
            if (pc.getRules().getType().isBasicLand()) {
                keep.add(pc);
                continue;
            }
            if (FORMAT_PAUPER.equals(format) || FORMAT_HISTORIC.equals(format)) {
                if (!cardLegalInFixedFormat(pc, format, forgeFormatFor(format)))
                    continue;
            }
            keep.add(pc);
        }
        if (deck.has(DeckSection.Commander)) {
            CardPool cmd = deck.get(DeckSection.Commander);
            List<PaperCard> cmdKeep = new ArrayList<>();
            for (PaperCard pc : cmd.toFlatList()) {
                if (pc == null || isRestrictedCardName(pc.getName()) || isExcludedFromAdventureDecks(pc))
                    continue;
                cmdKeep.add(pc);
            }
            cmd.clear();
            for (PaperCard pc : cmdKeep)
                cmd.add(pc);
        }
        main.clear();
        for (PaperCard pc : keep)
            main.add(pc);

        // Pad lands from spell colors / commander identity — not bare theme.colors.
        String[] padColors = colorsForPadding(deck, theme);
        if (FORMAT_COMMANDER.equals(format)) {
            int cmdN = deck.getCommanders() != null ? deck.getCommanders().size() : 0;
            int need = 100 - cmdN;
            rebuildBasicLands(deck, padColors, need);
        } else {
            rebuildBasicLands(deck, padColors, 60);
        }
        return deck;
    }

    /** Colors for land padding: commander CI if present, else colors of spells in the deck. */
    private static String[] colorsForPadding(Deck deck, EnemyThemeData theme) {
        byte mask = 0;
        if (deck != null && deck.getCommanders() != null) {
            for (PaperCard cmd : deck.getCommanders()) {
                if (cmd != null && cmd.getRules() != null)
                    mask |= cmd.getRules().getColorIdentity().getColor();
            }
        }
        if (mask == 0)
            mask = spellColorMask(deck);
        if (mask == 0 && theme != null)
            mask = colorMask(theme.colors);
        String[] fromMask = colorsFromMask(mask);
        if (fromMask.length > 0)
            return fromMask;
        return theme != null && theme.colors != null && theme.colors.length > 0
                ? theme.colors : new String[]{"blue"};
    }

    private static Deck tryAnyThemeDeck(String format) {
        ensureLoaded();
        List<EnemyThemeData> themes;
        synchronized (LOCK) {
            if (byId == null)
                return null;
            themes = new ArrayList<>(byId.values());
        }
        for (EnemyThemeData t : themes) {
            Deck d = loadFixedOrRecipe(t, format);
            if (d != null && !d.isEmpty()) {
                LOG.warning("EN1: using unrelated theme " + t.id + " for format " + format);
                return d;
            }
        }
        return null;
    }

    private static Deck loadStockDeck(EnemyData enemy, boolean isFantasyMode, boolean useGeneticAI) {
        if (enemy == null)
            return new Deck("EN1 empty");
        EnemyData stock = new EnemyData(enemy);
        stock.themeId = null;
        stock.preparedDeck = enemy.preparedDeck;
        if (stock.preparedDeck != null)
            return stock.preparedDeck;
        if (stock.deck == null || stock.deck.length == 0)
            return new Deck(stock.getName());
        try {
            boolean canUseGeneticAI = useGeneticAI && stock.life > 16;
            if (stock.randomizeDeck)
                return CardUtil.getDeck(forge.util.Aggregates.random(stock.deck), true, isFantasyMode,
                        stock.colors, stock.life > 13, canUseGeneticAI);
            int idx = 0;
            try {
                idx = Current.player().getEnemyDeckNumber(stock.getName(), stock.deck.length);
            } catch (Throwable ignored) {
            }
            if (idx < 0 || idx >= stock.deck.length)
                idx = 0;
            return CardUtil.getDeck(stock.deck[idx], true, isFantasyMode, stock.colors,
                    stock.life > 13, canUseGeneticAI);
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "EN1: stock CardUtil.getDeck failed", e);
            return new Deck(stock.getName());
        }
    }

    private static int standardTargetSize() {
        try {
            ConfigData cfg = Config.instance().getConfigData();
            if (cfg != null && cfg.en1StandardDeckSize > 0)
                return cfg.en1StandardDeckSize;
        } catch (Throwable ignored) {
        }
        return 60;
    }

    private static String firstCreatureType(EnemyThemeData theme) {
        if (theme.creatureTypes != null && theme.creatureTypes.length > 0
                && theme.creatureTypes[0] != null && !theme.creatureTypes[0].isEmpty())
            return theme.creatureTypes[0];
        if (theme.tags != null && theme.tags.length > 0)
            return theme.tags[0];
        return null;
    }

    private static Set<String> restrictedNames() {
        synchronized (LOCK) {
            if (cachedRestrictedNames != null)
                return cachedRestrictedNames;
            Set<String> set = new HashSet<>();
            try {
                ConfigData cfg = Config.instance().getConfigData();
                if (cfg != null && cfg.restrictedCards != null)
                    Collections.addAll(set, cfg.restrictedCards);
            } catch (Throwable ignored) {
            }
            // Always exclude Path of Ancestry even if Config is unavailable in tests.
            set.add("Path of Ancestry");
            cachedRestrictedNames = set;
            return set;
        }
    }

    private static Set<String> restrictedEditionCodes() {
        synchronized (LOCK) {
            if (cachedRestrictedEditions != null)
                return cachedRestrictedEditions;
            Set<String> set = new HashSet<>();
            try {
                ConfigData cfg = Config.instance().getConfigData();
                if (cfg != null && cfg.restrictedEditions != null)
                    Collections.addAll(set, cfg.restrictedEditions);
            } catch (Throwable ignored) {
            }
            // Always block Un-/playtest / mystery-booster codes even without Config.
            Collections.addAll(set, "UST", "UGL", "UNH", "UND", "UNF", "PUST",
                    "CMB1", "CMB2", "MB2", "MBC", "HHO", "PCEL", "DA1", "PPC1",
                    "HTR", "HTR17", "HTR18", "HTR19", "HTR20", "TRK", "PF27");
            cachedRestrictedEditions = set;
            return set;
        }
    }

    private static Set<String> enemyBannedNames() {
        synchronized (LOCK) {
            if (cachedEnemyBannedNames != null)
                return cachedEnemyBannedNames;
            Set<String> set = new HashSet<>();
            try {
                FileHandle handle = Config.instance().getFile(Paths.ENEMY_BANNED);
                if (handle == null || !handle.exists()) {
                    java.nio.file.Path alt = java.nio.file.Paths.get(
                            "forge-gui/res/adventure/common/world/enemy_banned.json");
                    if (!Files.isRegularFile(alt))
                        alt = java.nio.file.Paths.get(
                                "../forge-gui/res/adventure/common/world/enemy_banned.json");
                    if (Files.isRegularFile(alt))
                        handle = new FileHandle(alt.toFile());
                }
                if (handle != null && handle.exists()) {
                    EnemyThemeCoreData parsed = new Json().fromJson(EnemyThemeCoreData.class, handle);
                    if (parsed != null && parsed.cards != null)
                        Collections.addAll(set, parsed.cards);
                }
            } catch (Throwable e) {
                LOG.log(Level.WARNING, "EN1: failed to load enemy_banned.json", e);
            }
            // Hard-coded fallback so tests still see the power-level list without Config.
            if (set.isEmpty()) {
                Collections.addAll(set,
                        "Ragavan, Nimble Pilferer", "Dockside Extortionist", "Deflecting Swat",
                        "Skullclamp", "Goblin Recruiter", "Necropotence", "Bolas's Citadel",
                        "Sheoldred, the Apocalypse", "Aetherflux Reservoir", "Toxic Deluge",
                        "Damnation", "Deadly Rollick", "Living Death", "Bitterblossom",
                        "Phyrexian Altar", "Grave Pact", "Dictate of Erebos", "Rhystic Study",
                        "Cyclonic Rift", "Mystic Remora", "Fierce Guardianship", "Thassa's Oracle",
                        "The Scarab God", "Smothering Tithe", "Teferi's Protection", "Esper Sentinel",
                        "Collected Company", "Chord of Calling", "Blood Crypt", "Breeding Pool",
                        "Godless Shrine", "Hallowed Fountain", "Overgrown Tomb", "Sacred Foundry",
                        "Steam Vents", "Stomping Ground", "Temple Garden", "Watery Grave");
            }
            cachedEnemyBannedNames = set;
            return set;
        }
    }

    private static Set<String> windowLegalNames(StandardWindow window) {
        if (window == null || !window.isActive())
            return Collections.emptySet();
        String key = String.join(",", window.getSets());
        synchronized (LOCK) {
            if (cachedWindowLegalNames != null && key.equals(cachedWindowKey))
                return cachedWindowLegalNames;
            Set<String> names = window.legalNames();
            cachedWindowKey = key;
            cachedWindowLegalNames = names;
            return names;
        }
    }

    private static boolean windowLegalName(StandardWindow window, String name) {
        return windowLegalNames(window).contains(name);
    }

    private static List<PaperCard> buildWindowPool(StandardWindow window, String[] colors,
                                                   String tribe, EnemyThemeRecipeData recipe) {
        List<PaperCard> out = new ArrayList<>();
        try {
            if (window == null || !window.isActive())
                return out;
            if (StaticData.instance() == null || FModel.getMagicDb() == null)
                return out;

            String windowKey = String.join(",", window.getSets());
            String colorKey = colors == null ? "*" : String.join(",", colors);
            String tribeKey = tribe == null ? "*" : tribe;
            String mechKey = "";
            if (recipe != null && recipe.mechanics != null)
                mechKey = String.join(",", recipe.mechanics);
            String cacheKey = windowKey + "|" + colorKey + "|" + tribeKey + "|" + mechKey;
            synchronized (LOCK) {
                List<PaperCard> cached = windowPoolCache.get(cacheKey);
                if (cached != null)
                    return new ArrayList<>(cached);
            }

            Set<String> legal = windowLegalNames(window);
            byte allowed = colorMask(colors);
            Set<String> seen = new HashSet<>();
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
                    continue;
                if (pc.getRules().getType().isBasicLand())
                    continue;
                if (isRestrictedCardName(pc.getName()) || isExcludedFromAdventureDecks(pc))
                    continue;
                // Color before legality (cheaper).
                if (allowed != 0 && !pc.getRules().getType().isLand()
                        && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                if (!legal.contains(pc.getName()))
                    continue;
                if (isEnemyBanned(pc.getName()) || isAdventureBanned("standard", pc.getName()))
                    continue;
                if (tribe != null && !tribe.isEmpty()) {
                    boolean tribal = pc.getRules().getType().hasSubtype(tribe);
                    boolean changeling = false;
                    try {
                        changeling = pc.getRules().hasKeyword("Changeling");
                    } catch (Throwable ignored) {
                    }
                    boolean synergy = false;
                    if (!tribal && !changeling && pc.getRules().getOracleText() != null)
                        synergy = wordMatches(pc.getRules().getOracleText(), tribe);
                    if (!tribal && !changeling && !synergy && recipe != null
                            && recipe.mechanics != null) {
                        for (String m : recipe.mechanics) {
                            if (m == null || m.isEmpty() || isGenericMechanicToken(m))
                                continue;
                            try {
                                if (pc.getRules().hasKeyword(m)
                                        || pc.getRules().hasKeyword(
                                        m.substring(0, 1).toUpperCase(Locale.ROOT) + m.substring(1))) {
                                    synergy = true;
                                    break;
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (!tribal && !changeling && !synergy)
                        continue;
                }
                if (seen.add(pc.getName()))
                    out.add(pc);
            }
            synchronized (LOCK) {
                windowPoolCache.put(cacheKey, new ArrayList<>(out));
            }
        } catch (Throwable e) {
            LOG.log(Level.WARNING, "EN1: buildWindowPool failed", e);
        }
        return out;
    }

    private static int countOnThemeInPool(List<PaperCard> pool, EnemyThemeData theme) {
        if (pool == null)
            return 0;
        int n = 0;
        for (PaperCard pc : pool) {
            if (pc != null && !pc.getRules().getType().isLand() && isOnTheme(pc, theme))
                n++;
        }
        return n;
    }

    /**
     * Bellwarden Standard for enemy recipes: printed in a set currently in the
     * window (or a basic land). Inactive / null window → non-basics are illegal
     * (never "everything is legal").
     */
    private static boolean isStandardWindowLegal(PaperCard pc, StandardWindow window) {
        if (pc == null)
            return false;
        if (pc.getRules().getType().isBasicLand())
            return true;
        if (window == null || !window.isActive())
            return false;
        if (isRestrictedCardName(pc.getName()) || isExcludedFromAdventureDecks(pc))
            return false;
        return windowLegalNames(window).contains(pc.getName());
    }

    private static void stripIllegalStandard(Deck deck, StandardWindow window, int target,
                                             String[] colors) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (pc.getRules().getType().isBasicLand() || isStandardWindowLegal(pc, window))
                keep.add(pc);
        }
        main.clear();
        for (PaperCard pc : keep)
            main.add(pc);
        String[] pad = colorsFromMask(spellColorMask(deck));
        if (pad.length == 0)
            pad = colors;
        rebuildBasicLands(deck, pad, target);
    }

    private static void addCopies(CardPool main, PaperCard pc, int want, int spellTarget,
                                  Set<String> used) {
        if (pc == null || used.contains(pc.getName()))
            return;
        int max = canHaveAnyNumber(pc) ? 4 : 4;
        int have = main.countByName(pc.getName());
        int add = Math.min(max - have, Math.min(want, spellTarget - main.countAll()));
        for (int i = 0; i < add; i++)
            main.add(pc);
        used.add(pc.getName());
    }

    private static byte colorMask(String[] colors) {
        if (colors == null || colors.length == 0)
            return 0;
        byte m = 0;
        for (String c : colors) {
            if (c == null)
                continue;
            m |= MagicColor.fromName(c.trim().toLowerCase(Locale.ROOT));
        }
        return m;
    }

    /** Strip all lands and rebuild basics from {@code colors} so every color is represented. */
    private static void rebuildBasicLands(Deck deck, String[] colors, int totalTarget) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> nonLands = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (pc.getRules().getType().isLand())
                continue;
            nonLands.add(pc);
        }
        main.clear();
        for (PaperCard pc : nonLands)
            main.add(pc);

        String[] cols = colors != null && colors.length > 0 ? colors : new String[]{"blue"};
        List<String> basics = new ArrayList<>();
        for (String c : cols) {
            String b = basicForColor(c);
            if (!basics.contains(b))
                basics.add(b);
        }
        if (basics.isEmpty())
            basics.add("Island");

        int landTarget;
        if (totalTarget == 60) {
            while (main.countAll() > 60 - MIN_LANDS_60) {
                List<PaperCard> flat = new ArrayList<>(main.toFlatList());
                if (flat.isEmpty())
                    break;
                PaperCard remove = null;
                for (int i = flat.size() - 1; i >= 0; i--) {
                    if (flat.get(i).getRules().getType().isCreature()) {
                        remove = flat.get(i);
                        break;
                    }
                }
                if (remove == null)
                    remove = flat.get(flat.size() - 1);
                main.remove(remove);
            }
            landTarget = Math.min(MAX_LANDS_60, Math.max(MIN_LANDS_60, 60 - main.countAll()));
            while (main.countAll() + landTarget > 60) {
                List<PaperCard> flat = new ArrayList<>(main.toFlatList());
                if (flat.isEmpty())
                    break;
                main.remove(flat.get(flat.size() - 1));
                landTarget = Math.min(MAX_LANDS_60, Math.max(MIN_LANDS_60, 60 - main.countAll()));
            }
        } else {
            landTarget = Math.max(0, totalTarget - main.countAll());
        }

        int added = 0;
        for (String b : basics) {
            if (added >= landTarget)
                break;
            PaperCard land = cardByName(b);
            if (land == null)
                land = cardByName("Wastes");
            if (land == null)
                break;
            main.add(land);
            added++;
        }
        while (added < landTarget) {
            String b = basics.get(added % basics.size());
            PaperCard land = cardByName(b);
            if (land == null)
                break;
            main.add(land);
            added++;
        }
        // For 60-card decks never exceed MAX_LANDS_60 — thin spell counts fail quality
        // instead of silently growing the mana base. Commander may pad to exact total.
        if (totalTarget != 60) {
            int guard = 0;
            while (main.countAll() < totalTarget && guard++ < totalTarget * 2) {
                String b = basics.get(main.countAll() % basics.size());
                PaperCard land = cardByName(b);
                if (land == null)
                    break;
                main.add(land);
            }
        } else {
            // Top up to 60 only while staying within the land band.
            int guard = 0;
            while (main.countAll() < totalTarget && countLandsInPool(main) < MAX_LANDS_60
                    && guard++ < totalTarget) {
                String b = basics.get(main.countAll() % basics.size());
                PaperCard land = cardByName(b);
                if (land == null)
                    break;
                main.add(land);
            }
        }
        while (main.countAll() > totalTarget) {
            List<PaperCard> flat = new ArrayList<>(main.toFlatList());
            PaperCard remove = null;
            for (PaperCard pc : flat) {
                if (pc.getRules().getType().isBasicLand()) {
                    remove = pc;
                    break;
                }
            }
            if (remove == null)
                break;
            main.remove(remove);
        }
    }

    private static int countLandsInPool(CardPool main) {
        int n = 0;
        for (PaperCard pc : main.toFlatList()) {
            if (pc != null && pc.getRules() != null && pc.getRules().getType().isLand())
                n++;
        }
        return n;
    }

    private static Deck padWithBasics(Deck deck, int target, String[] colors) {
        rebuildBasicLands(deck, colors, target);
        return deck;
    }

    private static String basicForColor(String colorName) {
        if (colorName == null)
            return "Wastes";
        return switch (colorName.toLowerCase(Locale.ROOT)) {
            case "white", "w" -> "Plains";
            case "blue", "u" -> "Island";
            case "black", "b" -> "Swamp";
            case "red", "r" -> "Mountain";
            case "green", "g" -> "Forest";
            default -> "Wastes";
        };
    }

    private static boolean isBasicLandName(String name) {
        if (name == null)
            return false;
        return "Plains".equals(name) || "Island".equals(name) || "Swamp".equals(name)
                || "Mountain".equals(name) || "Forest".equals(name) || "Wastes".equals(name);
    }

    /**
     * Basics are pinned to {@link #PREFERRED_BASIC_LAND_EDITION} when that printing
     * exists and is released / non-promo. Other names use the first good paper printing.
     */
    private static PaperCard cardByName(String name) {
        if (isBasicLandName(name)) {
            PaperCard pinned = basicLandFromPreferredSet(name);
            if (pinned != null)
                return pinned;
        }
        try {
            Collection<PaperCard> all = FModel.getMagicDb().getCommonCards().getAllCards(name);
            if (all != null) {
                PaperCard any = null;
                PaperCard good = null;
                PaperCard released = null;
                for (PaperCard p : all) {
                    if (p == null)
                        continue;
                    if (any == null)
                        any = p;
                    if (isBadEditionCode(p.getEdition()))
                        continue;
                    if (good == null)
                        good = p;
                    // Prefer ordinary released non-promo printings.
                    if (!isUnreleasedOrPromoOnlyEdition(p.getEdition())) {
                        released = p;
                        break;
                    }
                }
                if (released != null)
                    return released;
                if (good != null)
                    return good;
                // All printings excluded — still return one so callers can detect exclusion.
                if (any != null)
                    return any;
            }
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
            if (pc != null)
                return pc;
            return FModel.getMagicDb().getCommonCards().getUniqueByName(name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Prefer {@link #PREFERRED_BASIC_LAND_EDITION} for basics when available. */
    private static PaperCard basicLandFromPreferredSet(String name) {
        try {
            PaperCard pc = FModel.getMagicDb().getCommonCards()
                    .getCard(name, PREFERRED_BASIC_LAND_EDITION);
            if (pc == null)
                return null;
            if (isBadEditionCode(pc.getEdition()) || isUnreleasedOrPromoOnlyEdition(pc.getEdition()))
                return null;
            return pc;
        } catch (Throwable e) {
            return null;
        }
    }

    /** Rewrite a card to a preferred (non-Online/Funny) printing when one exists. */
    private static PaperCard preferPaperPrinting(PaperCard pc) {
        if (pc == null)
            return null;
        PaperCard better = cardByName(pc.getName());
        return better != null ? better : pc;
    }

    private static boolean canHaveAnyNumber(PaperCard pc) {
        return DeckFormat.canHaveAnyNumberOf(pc);
    }

    // ---- generation helpers (tools / tests) ---------------------------------

    /**
     * Builds a fixed-format deck for a theme (Historic, Pauper, or Commander).
     * Hand-picked {@link EnemyThemeData#core} first, then limited generated filler,
     * then lands (16–18 for 60-card; 35–39 for Commander with CI fixing).
     */
    public static Deck buildFixedDeck(EnemyThemeData theme, String format, long seed) {
        String fmt = normalizeFormat(format);
        Random previous = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(seed));
            attachCore(theme);
            if (FORMAT_COMMANDER.equals(fmt))
                return buildCommanderDeck(theme, seed);
            if (FORMAT_PAUPER.equals(fmt))
                return buildConstructedDeck(theme, FORMAT_PAUPER, 60, seed);
            if (FORMAT_HISTORIC.equals(fmt))
                return buildConstructedDeck(theme, FORMAT_HISTORIC, 60, seed);
            return fillStandardRecipe(theme, null, seed);
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    private static Deck buildCommanderDeck(EnemyThemeData theme, long seed) {
        PaperCard commander = pickCommander(theme, seed);
        Deck deck = new Deck(theme.id + " Commander");
        deck.setName(theme.id + " Commander");
        if (commander == null) {
            LOG.warning("EN1: no commander for " + theme.id);
            return deck;
        }
        deck.getOrCreate(DeckSection.Commander).add(preferPaperPrinting(commander));
        byte ci = commander.getRules().getColorIdentity().getColor();
        CardPool main = deck.getOrCreate(DeckSection.Main);

        List<PaperCard> coreLegal = resolveCoreCards(theme, FORMAT_COMMANDER, null, ci, true);
        // Never put the commander into the main deck (singleton / command zone).
        coreLegal.removeIf(pc -> pc != null && pc.getName().equals(commander.getName()));
        int cmdN = deck.getCommanders().size();
        int landBudget = commanderLandBudget(theme, ci);
        int nonLandSlots = 100 - cmdN - landBudget; // ~61–67
        int maxFiller = MAX_FILLER_COMMANDER;
        int wantCore = Math.max(MIN_CORE_CARDS_IN_DECK, nonLandSlots - maxFiller);

        addCoreToPool(main, coreLegal, FORMAT_COMMANDER, wantCore, true);
        stripCommanderDuplicates(deck);
        // Keep topping up from the expanded core until the non-land budget is met.
        topUpCoreForSize(main, coreLegal, FORMAT_COMMANDER, wantCore);
        stripCommanderDuplicates(deck);

        int fillerRoom = Math.min(maxFiller, Math.max(0, nonLandSlots - countNonLands(main)));
        addFillerNonLand(main, theme, FORMAT_COMMANDER, null, ci, fillerRoom, true);
        stripCommanderDuplicates(deck);
        enforceFillerCap(deck, theme, maxFiller);
        // If still thin on non-lands, pull more core (not more lands).
        topUpCoreForSize(main, coreLegal, FORMAT_COMMANDER, nonLandSlots);
        stripCommanderDuplicates(deck);
        enforceFillerCap(deck, theme, maxFiller);
        trimToBudgets(main, theme, nonLandSlots, maxFiller);

        // Mana base: exactly landBudget lands (basics + fixing), never pad to 99 with basics.
        rebuildCommanderManaBase(deck, ci, landBudget);
        stripCommanderDuplicates(deck);
        enforceFillerCap(deck, theme, maxFiller);

        // Exact 99 main: if short, add more core; if over, cut basics then filler.
        int needMain = 100 - cmdN;
        topUpCoreForSize(main, coreLegal, FORMAT_COMMANDER, needMain - landBudget);
        rebuildCommanderManaBase(deck, ci, landBudget);
        while (deck.getMain().countAll() > needMain) {
            PaperCard remove = null;
            for (PaperCard pc : deck.getMain().toFlatList()) {
                if (pc.getRules().getType().isBasicLand()) {
                    remove = pc;
                    break;
                }
            }
            if (remove == null) {
                for (PaperCard pc : deck.getMain().toFlatList()) {
                    if (pc.getRules().getType().isLand()) {
                        remove = pc;
                        break;
                    }
                }
            }
            if (remove == null)
                break;
            deck.getMain().remove(remove);
        }
        enforceFillerCap(deck, theme, maxFiller);
        ensureTribalCreatureDensity(deck, theme, FORMAT_COMMANDER, null, ci, true);
        stripCommanderDuplicates(deck);
        enforceFillerCap(deck, theme, maxFiller);
        // Trim excess non-lands (never strip the land band), then rebuild the mana base.
        while (countNonLands(main) > needMain - landBudget) {
            PaperCard remove = null;
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isLand())
                    continue;
                if (countsAsTribalCreature(pc, theme))
                    continue;
                if (isInCore(pc.getName(), theme))
                    continue;
                remove = pc;
                break;
            }
            if (remove == null) {
                for (PaperCard pc : main.toFlatList()) {
                    if (!pc.getRules().getType().isLand() && !countsAsTribalCreature(pc, theme)) {
                        remove = pc;
                        break;
                    }
                }
            }
            if (remove == null)
                break;
            main.remove(remove);
        }
        rebuildCommanderManaBase(deck, ci, landBudget);
        stripCommanderDuplicates(deck);
        while (deck.getMain().countAll() > needMain) {
            PaperCard remove = null;
            for (PaperCard pc : deck.getMain().toFlatList()) {
                if (pc.getRules().getType().isLand())
                    continue;
                if (countsAsTribalCreature(pc, theme))
                    continue;
                remove = pc;
                break;
            }
            if (remove == null) {
                for (PaperCard pc : deck.getMain().toFlatList()) {
                    if (!pc.getRules().getType().isBasicLand()) {
                        remove = pc;
                        break;
                    }
                }
            }
            if (remove == null)
                break;
            deck.getMain().remove(remove);
        }
        if (countLands(deck) < landBudget)
            rebuildCommanderManaBase(deck, ci, landBudget);
        while (deck.getMain().countAll() < needMain && countLands(deck) < MAX_LANDS_COMMANDER) {
            String[] pad = colorsFromMask(ci);
            if (pad.length == 0)
                pad = new String[]{"blue"};
            PaperCard land = cardByName(basicForColor(pad[deck.getMain().countAll() % pad.length]));
            if (land == null)
                break;
            main.add(land);
        }
        enforceFillerCap(deck, theme, maxFiller);
        return deck;
    }

    /** Strip lands and rebuild exactly {@code landBudget} CI-matched basics + fixing. */
    private static void rebuildCommanderManaBase(Deck deck, byte ci, int landBudget) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> nonLands = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRules().getType().isLand())
                continue;
            nonLands.add(pc);
        }
        main.clear();
        for (PaperCard pc : nonLands)
            main.add(pc);

        String[] pad = colorsFromMask(ci);
        if (pad.length == 0)
            pad = new String[]{"blue"};
        List<String> basics = new ArrayList<>();
        for (String c : pad) {
            String b = basicForColor(c);
            if (!basics.contains(b))
                basics.add(b);
        }
        if (basics.isEmpty())
            basics.add("Island");

        // Seed with fixing for multicolor, then fill remaining with basics.
        int fixingWant = Integer.bitCount(ci & 0xFF) >= 2 ? Math.min(12, 4 + Integer.bitCount(ci & 0xFF) * 2) : 0;
        int added = 0;
        if (fixingWant > 0) {
            // Temporarily add basics so addCommanderFixing has victims to swap — then trim to budget.
            for (int i = 0; i < landBudget && i < basics.size() * 4; i++) {
                PaperCard land = cardByName(basics.get(i % basics.size()));
                if (land != null) {
                    main.add(land);
                    added++;
                }
            }
            addCommanderFixing(deck, ci);
            // Count lands now (basics + fixing).
            added = 0;
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isLand())
                    added++;
            }
        }
        while (added < landBudget) {
            PaperCard land = cardByName(basics.get(added % basics.size()));
            if (land == null)
                break;
            main.add(land);
            added++;
        }
        while (countLands(deck) > landBudget) {
            PaperCard remove = null;
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isBasicLand()) {
                    remove = pc;
                    break;
                }
            }
            if (remove == null) {
                for (PaperCard pc : main.toFlatList()) {
                    if (pc.getRules().getType().isLand()) {
                        remove = pc;
                        break;
                    }
                }
            }
            if (remove == null)
                break;
            main.remove(remove);
        }
    }

    /** Remove non-core non-lands until ≤ {@code maxFiller} (main + commander). */
    private static void enforceFillerCap(Deck deck, EnemyThemeData theme) {
        enforceFillerCap(deck, theme, MAX_FILLER_NONLAND);
    }

    private static void enforceFillerCap(Deck deck, EnemyThemeData theme, int maxFiller) {
        if (deck == null || theme == null)
            return;
        int guard = 0;
        while (countFillerNonLand(deck, theme) > maxFiller && guard++ < 80) {
            PaperCard victim = null;
            for (var e : deck.getMain()) {
                PaperCard pc = e.getKey();
                if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                    continue;
                if (isInCore(pc.getName(), theme))
                    continue;
                // Keep tribe creatures that satisfy the tribal floor.
                if (isTribalTheme(theme) && countsAsTribalCreature(pc, theme))
                    continue;
                victim = pc;
                break;
            }
            if (victim == null)
                break;
            deck.getMain().remove(victim);
        }
    }

    private static int commanderLandBudget(EnemyThemeData theme, byte ci) {
        boolean ramp = theme != null && theme.id != null
                && (theme.id.contains("ramp") || theme.id.contains("dragon"));
        int colors = Integer.bitCount(ci & 0xFF);
        if (ramp) {
            if (colors <= 1)
                return 36;
            if (colors == 2)
                return 38;
            return 39;
        }
        if (colors <= 1)
            return 35;
        if (colors == 2)
            return 36;
        return Math.min(MAX_LANDS_COMMANDER, 38);
    }

    private static int countNonLands(CardPool main) {
        int n = 0;
        for (PaperCard pc : main.toFlatList()) {
            if (pc != null && pc.getRules() != null && !pc.getRules().getType().isLand())
                n++;
        }
        return n;
    }

    /** Swap some basics for CI-legal fixing in multicolor Commander decks. */
    private static void addCommanderFixing(Deck deck, byte ci) {
        int colors = Integer.bitCount(ci & 0xFF);
        if (colors < 2)
            return;
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<String> fixers = new ArrayList<>();
        Collections.addAll(fixers, "Command Tower", "Exotic Orchard",
                "Evolving Wilds", "Terramorphic Expanse", "Myriad Landscape", "Ash Barrens");
        // Bounce / check lands within CI — no shocklands (EN1 power-level ban).
        if ((ci & MagicColor.WHITE) != 0 && (ci & MagicColor.BLUE) != 0)
            Collections.addAll(fixers, "Azorius Chancery", "Glacial Fortress", "Tranquil Cove");
        if ((ci & MagicColor.BLUE) != 0 && (ci & MagicColor.BLACK) != 0)
            Collections.addAll(fixers, "Dimir Aqueduct", "Drowned Catacomb", "Dismal Backwater");
        if ((ci & MagicColor.BLACK) != 0 && (ci & MagicColor.RED) != 0)
            Collections.addAll(fixers, "Rakdos Carnarium", "Dragonskull Summit", "Bloodfell Caves");
        if ((ci & MagicColor.RED) != 0 && (ci & MagicColor.GREEN) != 0)
            Collections.addAll(fixers, "Gruul Turf", "Rootbound Crag", "Rugged Highlands");
        if ((ci & MagicColor.GREEN) != 0 && (ci & MagicColor.WHITE) != 0)
            Collections.addAll(fixers, "Selesnya Sanctuary", "Sunpetal Grove", "Blossoming Sands");
        if ((ci & MagicColor.WHITE) != 0 && (ci & MagicColor.BLACK) != 0)
            Collections.addAll(fixers, "Orzhov Basilica", "Isolated Chapel", "Scoured Barrens");
        if ((ci & MagicColor.BLUE) != 0 && (ci & MagicColor.RED) != 0)
            Collections.addAll(fixers, "Izzet Boilerworks", "Sulfur Falls", "Swiftwater Cliffs");
        if ((ci & MagicColor.BLACK) != 0 && (ci & MagicColor.GREEN) != 0)
            Collections.addAll(fixers, "Golgari Rot Farm", "Woodland Cemetery", "Jungle Hollow");
        if ((ci & MagicColor.RED) != 0 && (ci & MagicColor.WHITE) != 0)
            Collections.addAll(fixers, "Boros Garrison", "Clifftop Retreat", "Wind-Scarred Crag");
        if ((ci & MagicColor.GREEN) != 0 && (ci & MagicColor.BLUE) != 0)
            Collections.addAll(fixers, "Simic Growth Chamber", "Hinterland Harbor", "Thornwood Falls");
        if (colors >= 3)
            Collections.addAll(fixers, "Cascading Cataracts", "Reliquary Tower", "Rogue's Passage");

        int want = Math.min(12, 4 + colors * 2);
        int added = 0;
        Set<String> used = new HashSet<>();
        for (String name : fixers) {
            if (added >= want)
                break;
            if (!used.add(name))
                continue;
            PaperCard pc = cardByName(name);
            if (pc == null || isExcludedFromAdventureDecks(pc) || isRestrictedCardName(name))
                continue;
            if (!pc.getRules().getType().isLand())
                continue;
            if (!pc.getRules().getColorIdentity().hasNoColorsExcept(ci)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            // Replace a basic.
            PaperCard basic = null;
            for (PaperCard c : main.toFlatList()) {
                if (c.getRules().getType().isBasicLand()) {
                    basic = c;
                    break;
                }
            }
            if (basic == null)
                break;
            main.remove(basic);
            main.add(preferPaperPrinting(pc));
            added++;
        }
    }

    private static String[] colorsFromMask(byte ci) {
        List<String> out = new ArrayList<>();
        if ((ci & MagicColor.WHITE) != 0)
            out.add("white");
        if ((ci & MagicColor.BLUE) != 0)
            out.add("blue");
        if ((ci & MagicColor.BLACK) != 0)
            out.add("black");
        if ((ci & MagicColor.RED) != 0)
            out.add("red");
        if ((ci & MagicColor.GREEN) != 0)
            out.add("green");
        return out.toArray(new String[0]);
    }

    private static PaperCard pickCommander(EnemyThemeData theme, long seed) {
        byte themeColors = colorMask(theme.colors);
        // 1) Preferred commanders inside the theme's colors.
        PaperCard inTheme = firstPreferredCommander(theme, themeColors, true);
        if (inTheme != null)
            return inTheme;
        // 2) Preferred commanders that add colors (Ur-Dragon, Edgar, Millicent, …).
        PaperCard anyPreferred = firstPreferredCommander(theme, themeColors, false);
        if (anyPreferred != null)
            return anyPreferred;
        String tribe = firstCreatureType(theme);
        byte allowed = themeColors;
        List<PaperCard> inColor = new ArrayList<>();
        List<PaperCard> anyColor = new ArrayList<>();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (isRestrictedCardName(pc.getName()) || isExcludedFromAdventureDecks(pc))
                continue;
            CardRules rules = pc.getRules();
            if (!DeckFormat.Commander.isLegalCommander(rules))
                continue;
            if (tribe != null && !tribe.isEmpty() && !rules.getType().hasSubtype(tribe)
                    && !rules.hasKeyword("Changeling"))
                continue;
            boolean within = allowed == 0
                    || rules.getColorIdentity().hasNoColorsExcept(allowed)
                    || rules.getColorIdentity().isColorless();
            if (within)
                inColor.add(pc);
            else
                anyColor.add(pc);
        }
        List<PaperCard> candidates = !inColor.isEmpty() ? inColor : anyColor;
        if (candidates.isEmpty())
            return null;
        return candidates.get(new Random(seed).nextInt(candidates.size()));
    }

    private static PaperCard firstPreferredCommander(EnemyThemeData theme, byte themeColors,
                                                     boolean requireWithinThemeColors) {
        if (theme == null || theme.preferredCommanders == null)
            return null;
        for (String name : theme.preferredCommanders) {
            if (isRestrictedCardName(name) || isEnemyBanned(name))
                continue;
            PaperCard pc = cardByName(name);
            if (pc == null || isExcludedFromAdventureDecks(pc)
                    || !DeckFormat.Commander.isLegalCommander(pc.getRules()))
                continue;
            if (requireWithinThemeColors && themeColors != 0) {
                byte ci = pc.getRules().getColorIdentity().getColor();
                if ((ci & ~themeColors) != 0 && !pc.getRules().getColorIdentity().isColorless())
                    continue;
            }
            return pc;
        }
        return null;
    }

    private static Deck buildConstructedDeck(EnemyThemeData theme, String format, int target, long seed) {
        GameFormat forgeFormat = forgeFormatFor(format);
        Deck deck = new Deck(theme.id + " " + format);
        deck.setName(theme.id + " " + format);
        byte allowed = colorMask(theme.colors);
        CardPool main = deck.getOrCreate(DeckSection.Main);

        List<PaperCard> coreLegal = resolveCoreCards(theme, format, forgeFormat, allowed, false);
        int landTarget = TARGET_LANDS_60;
        int nonLandSlots = target - landTarget;
        int wantCore = Math.max(MIN_CORE_CARDS_IN_DECK, nonLandSlots - MAX_FILLER_NONLAND);
        addCoreToPool(main, coreLegal, format, wantCore, false);

        // Prefer keyCards / archetype staples among remaining core names first.
        // Key cards are already ordered first in resolveCoreCards; do not inject
        // extras that would spend the filler budget.

        int haveCore = 0;
        for (PaperCard pc : main.toFlatList()) {
            if (pc != null && !pc.getRules().getType().isLand() && isInCore(pc.getName(), theme))
                haveCore++;
        }
        if (haveCore < MIN_CORE_CARDS_IN_DECK)
            addCoreToPool(main, coreLegal, format, MIN_CORE_CARDS_IN_DECK, false);

        // Prefer core spells to meet the non-creature floor before spending filler.
        ensureMinNonCreatureSpellsFromCore(deck, theme, format, forgeFormat, allowed, nonLandSlots);
        int fillerRoom = Math.min(MAX_FILLER_NONLAND,
                Math.max(0, nonLandSlots - countNonLands(main)));
        addFillerNonLand(main, theme, format, forgeFormat, allowed, fillerRoom, false);
        trimToBudgets(main, theme, nonLandSlots);
        // If still thin (scarce Pauper tribe), spend remaining filler then stop — lands cap at 18.
        fillerRoom = Math.min(MAX_FILLER_NONLAND - countFillerInPool(main, theme),
                Math.max(0, nonLandSlots - countNonLands(main)));
        if (fillerRoom > 0)
            addFillerNonLand(main, theme, format, forgeFormat, allowed, fillerRoom, false);
        trimToBudgets(main, theme, nonLandSlots);
        // Top up core copies so lands can fill to 60 within the 16–18 band.
        topUpCoreForSize(main, coreLegal, format, nonLandSlots);

        String[] pad = colorsFromMask(spellColorMask(deck));
        if (pad.length == 0)
            pad = theme.colors != null ? theme.colors : new String[]{"blue"};
        rebuildBasicLands(deck, pad, target);
        enforceFillerCap(deck, theme);
        // If still under 60 after land cap, spend remaining core 4-ofs then filler.
        if (deck.getMain().countAll() < target) {
            topUpCoreForSize(main, coreLegal, format, target - MIN_LANDS_60);
            fillerRoom = Math.min(MAX_FILLER_NONLAND - countFillerInPool(main, theme),
                    Math.max(0, (target - MIN_LANDS_60) - countNonLands(main)));
            if (fillerRoom > 0)
                addFillerNonLand(main, theme, format, forgeFormat, allowed, fillerRoom, false);
            trimToBudgets(main, theme, target - MIN_LANDS_60);
            rebuildBasicLands(deck, pad, target);
            enforceFillerCap(deck, theme);
        }
        Deck done = finalizeConstructed(deck, theme, format, target);
        enforceFillerCap(done, theme);
        if (FORMAT_HISTORIC.equals(format) && theme.id != null && theme.id.contains("dragon"))
            ensureHistoricDragonDensity(done, theme, forgeFormat, allowed);
        ensureTribalCreatureDensity(done, theme, format, forgeFormat, allowed, false);
        return done;
    }

    /**
     * Raise tribe creature count toward {@link #MIN_TRIBAL_CREATURES_60} /
     * {@link #MIN_TRIBAL_CREATURES_COMMANDER}. Tops up from the theme's hand-picked
     * core first; only expands into the broader card DB when core tribe copies are
     * exhausted (changelings count).
     */
    private static void ensureTribalCreatureDensity(Deck deck, EnemyThemeData theme, String format,
                                                    GameFormat forgeFormat, byte allowed,
                                                    boolean singleton) {
        if (deck == null || theme == null || !isTribalTheme(theme))
            return;
        String fmt = normalizeFormat(format);
        int need = FORMAT_COMMANDER.equals(fmt) ? MIN_TRIBAL_CREATURES_COMMANDER
                : MIN_TRIBAL_CREATURES_60;
        if (countTribalCreatures(deck, theme) >= need)
            return;
        List<PaperCard> tribeCore = new ArrayList<>();
        Set<String> seenTribe = new HashSet<>();
        for (PaperCard pc : resolveCoreCards(theme, fmt, forgeFormat, allowed, singleton)) {
            if (!countsAsTribalCreature(pc, theme))
                continue;
            if (pc == null || !seenTribe.add(pc.getName()))
                continue;
            tribeCore.add(pc);
        }
        // Phase 1: exhaust hand-picked core tribe creatures before touching the DB.
        addTribalCreaturesFromPool(deck, theme, fmt, tribeCore, need, singleton);

        // Phase 2: only if still short, expand from the broader pool.
        if (countTribalCreatures(deck, theme) < need) {
            List<PaperCard> tribeDb = new ArrayList<>();
            try {
                for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                    if (pc == null || pc.getRules() == null || !countsAsTribalCreature(pc, theme))
                        continue;
                    if (!seenTribe.add(pc.getName()))
                        continue;
                    if (isExcludedFromAdventureDecks(pc) || isRestrictedCardName(pc.getName()))
                        continue;
                    if (forgeFormat != null && !cardLegalInFixedFormat(pc, fmt, forgeFormat))
                        continue;
                    if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                            && !pc.getRules().getColorIdentity().isColorless())
                        continue;
                    tribeDb.add(preferPaperPrinting(pc));
                    if (tribeDb.size() >= need * 3)
                        break;
                }
            } catch (Throwable ignored) {
            }
            addTribalCreaturesFromPool(deck, theme, fmt, tribeDb, need, singleton);
        }
        if (!FORMAT_COMMANDER.equals(fmt)) {
            String[] pad = colorsFromMask(spellColorMask(deck));
            if (pad.length == 0)
                pad = theme.colors != null ? theme.colors : new String[]{"blue"};
            rebuildBasicLands(deck, pad, 60);
        }
        enforceFillerCap(deck, theme, FORMAT_COMMANDER.equals(fmt) ? MAX_FILLER_COMMANDER
                : MAX_FILLER_NONLAND);
    }

    /**
     * Add tribe creatures from {@code pool} until {@code need} or the pool is exhausted
     * at per-name caps. Swaps non-tribe non-lands when the non-land budget is tight.
     */
    private static void addTribalCreaturesFromPool(Deck deck, EnemyThemeData theme, String fmt,
                                                   List<PaperCard> pool, int need,
                                                   boolean singleton) {
        if (deck == null || pool == null || pool.isEmpty())
            return;
        CardPool main = deck.getOrCreate(DeckSection.Main);
        int perName = singleton ? 1 : 4;
        int guard = 0;
        while (countTribalCreatures(deck, theme) < need && guard++ < 200) {
            boolean added = false;
            for (PaperCard pc : pool) {
                if (countTribalCreatures(deck, theme) >= need)
                    break;
                int cur = main.countByName(pc.getName());
                if (cur >= perName)
                    continue;
                // Commander: never duplicate the commander in main.
                if (FORMAT_COMMANDER.equals(fmt) && deck.getCommanders() != null) {
                    boolean isCmd = false;
                    for (PaperCard cmd : deck.getCommanders()) {
                        if (cmd != null && cmd.getName().equals(pc.getName())) {
                            isCmd = true;
                            break;
                        }
                    }
                    if (isCmd)
                        continue;
                }
                // Swap a non-tribe non-land when the non-land budget is tight.
                if (!FORMAT_COMMANDER.equals(fmt) && main.countAll() >= 60 - MIN_LANDS_60) {
                    PaperCard victim = null;
                    for (PaperCard c : main.toFlatList()) {
                        if (c.getRules().getType().isLand())
                            continue;
                        if (countsAsTribalCreature(c, theme))
                            continue;
                        victim = c;
                        break;
                    }
                    if (victim != null)
                        main.remove(victim);
                } else if (FORMAT_COMMANDER.equals(fmt)) {
                    PaperCard victim = null;
                    for (PaperCard c : main.toFlatList()) {
                        if (c.getRules().getType().isLand())
                            continue;
                        if (countsAsTribalCreature(c, theme))
                            continue;
                        if (isInCore(c.getName(), theme))
                            continue;
                        victim = c;
                        break;
                    }
                    if (victim == null) {
                        for (PaperCard c : main.toFlatList()) {
                            if (!c.getRules().getType().isLand() && !countsAsTribalCreature(c, theme)) {
                                victim = c;
                                break;
                            }
                        }
                    }
                    if (victim != null)
                        main.remove(victim);
                }
                main.add(preferPaperPrinting(pc));
                added = true;
            }
            if (!added)
                break;
        }
    }

    /**
     * Test hook: run tribal density top-up (core before DB) on an existing deck.
     */
    public static void ensureTribalCreatureDensityForTests(Deck deck, EnemyThemeData theme,
                                                           String format, GameFormat forgeFormat,
                                                           byte allowed, boolean singleton) {
        ensureTribalCreatureDensity(deck, theme, format, forgeFormat, allowed, singleton);
    }

    private static boolean countsAsTribalCreature(PaperCard pc, EnemyThemeData theme) {
        if (pc == null || pc.getRules() == null || !pc.getRules().getType().isCreature())
            return false;
        try {
            if (pc.getRules().hasKeyword("Changeling"))
                return true;
        } catch (Throwable ignored) {
        }
        if (theme.creatureTypes == null)
            return false;
        for (String t : theme.creatureTypes) {
            if (t != null && pc.getRules().getType().hasSubtype(t))
                return true;
        }
        return false;
    }

    /**
     * Historic dragon themes: at least {@link #MIN_DRAGONS_HISTORIC} Dragon creatures,
     * strip Studious First-Year, and keep Utopia Sprawl ≤ Forest count.
     */
    private static void ensureHistoricDragonDensity(Deck deck, EnemyThemeData theme,
                                                    GameFormat forgeFormat, byte allowed) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        // Remove changeling filler.
        List<PaperCard> strip = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if ("Studious First-Year".equals(pc.getName()))
                strip.add(pc);
        }
        for (PaperCard pc : strip)
            main.remove(pc);

        int dragons = countCreatureType(deck, "Dragon");
        if (dragons < MIN_DRAGONS_HISTORIC) {
            List<PaperCard> pool = new ArrayList<>();
            for (PaperCard pc : resolveCoreCards(theme, FORMAT_HISTORIC, forgeFormat, allowed, false)) {
                if (pc.getRules().getType().isCreature() && pc.getRules().getType().hasSubtype("Dragon"))
                    pool.add(pc);
            }
            for (PaperCard pc : pool) {
                if (countCreatureType(deck, "Dragon") >= MIN_DRAGONS_HISTORIC)
                    break;
                int cur = main.countByName(pc.getName());
                if (cur >= 4)
                    continue;
                // Swap a non-dragon non-land if needed to keep size.
                if (main.countAll() >= 60 - MIN_LANDS_60) {
                    PaperCard victim = null;
                    for (PaperCard c : main.toFlatList()) {
                        if (c.getRules().getType().isLand())
                            continue;
                        if (c.getRules().getType().isCreature() && c.getRules().getType().hasSubtype("Dragon"))
                            continue;
                        if ("Utopia Sprawl".equals(c.getName()) || "Dragonstorm".equals(c.getName()))
                            continue;
                        victim = c;
                        break;
                    }
                    if (victim != null)
                        main.remove(victim);
                }
                main.add(preferPaperPrinting(pc));
            }
        }

        // Utopia Sprawl needs a Forest; trim excess sprawl or add Forests via land rebuild.
        int sprawl = 0;
        int forests = 0;
        for (var e : main) {
            if ("Utopia Sprawl".equals(e.getKey().getName()))
                sprawl += e.getValue();
            if ("Forest".equals(e.getKey().getName()))
                forests += e.getValue();
        }
        while (sprawl > forests && sprawl > 0) {
            PaperCard u = null;
            for (PaperCard pc : main.toFlatList()) {
                if ("Utopia Sprawl".equals(pc.getName())) {
                    u = pc;
                    break;
                }
            }
            if (u == null)
                break;
            main.remove(u);
            sprawl--;
            // Prefer another Dragon or Cultivate over leaving a hole.
            PaperCard replacement = null;
            for (PaperCard pc : resolveCoreCards(theme, FORMAT_HISTORIC, forgeFormat, allowed, false)) {
                if (pc.getRules().getType().isCreature() && pc.getRules().getType().hasSubtype("Dragon")
                        && main.countByName(pc.getName()) < 4) {
                    replacement = pc;
                    break;
                }
            }
            if (replacement == null) {
                PaperCard cultivate = cardByName("Cultivate");
                if (cultivate != null && main.countByName("Cultivate") < 4)
                    replacement = cultivate;
            }
            if (replacement != null)
                main.add(preferPaperPrinting(replacement));
        }
        String[] pad = colorsFromMask(spellColorMask(deck));
        if (pad.length == 0)
            pad = theme.colors != null ? theme.colors : new String[]{"red", "green"};
        rebuildBasicLands(deck, pad, 60);
        enforceFillerCap(deck, theme);
    }

    /** Raise non-land count toward {@code wantNonLand} using remaining core copy slots (max 4). */
    private static void topUpCoreForSize(CardPool main, List<PaperCard> coreLegal, String format,
                                         int wantNonLand) {
        if (coreLegal == null || coreLegal.isEmpty())
            return;
        int guard = 0;
        while (countNonLands(main) < wantNonLand && guard++ < 200) {
            boolean added = false;
            for (PaperCard pc : coreLegal) {
                if (countNonLands(main) >= wantNonLand)
                    break;
                int cur = main.countByName(pc.getName());
                int max = FORMAT_COMMANDER.equals(format) ? 1 : 4;
                if (cur >= max)
                    continue;
                main.add(preferPaperPrinting(pc));
                added = true;
            }
            if (!added)
                break;
        }
    }

    private static void stripCommanderDuplicates(Deck deck) {
        if (deck == null || !deck.has(DeckSection.Commander))
            return;
        Set<String> cmds = new HashSet<>();
        for (PaperCard pc : deck.getCommanders()) {
            if (pc != null)
                cmds.add(pc.getName());
        }
        if (cmds.isEmpty())
            return;
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc != null && cmds.contains(pc.getName()))
                continue;
            keep.add(pc);
        }
        main.clear();
        for (PaperCard pc : keep)
            main.add(pc);
    }

    private static int countFillerInPool(CardPool main, EnemyThemeData theme) {
        int n = 0;
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                continue;
            if (!isInCore(pc.getName(), theme))
                n++;
        }
        return n;
    }

    /** Add non-creature spells from the theme core only (does not spend filler budget). */
    private static void ensureMinNonCreatureSpellsFromCore(Deck deck, EnemyThemeData theme,
                                                          String format, GameFormat forgeFormat,
                                                          byte allowed, int maxNonLand) {
        int have = countNonCreatureSpells(deck);
        if (have >= MIN_NON_CREATURE_SPELLS_60)
            return;
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> coreLegal = resolveCoreCards(theme, format, forgeFormat, allowed, false);
        int perName = FORMAT_PAUPER.equals(format) ? 4 : 3;
        for (PaperCard pc : coreLegal) {
            if (have >= MIN_NON_CREATURE_SPELLS_60)
                break;
            if (pc.getRules().getType().isCreature() || pc.getRules().getType().isLand())
                continue;
            if (countNonLands(main) >= maxNonLand)
                break;
            int cur = main.countByName(pc.getName());
            PaperCard print = preferPaperPrinting(pc);
            for (int i = cur; i < perName && have < MIN_NON_CREATURE_SPELLS_60
                    && countNonLands(main) < maxNonLand; i++) {
                main.add(print);
                have++;
            }
        }
    }

    private static List<PaperCard> resolveCoreCards(EnemyThemeData theme, String format,
                                                    GameFormat forgeFormat, byte allowed,
                                                    boolean singleton) {
        List<PaperCard> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (theme == null || theme.core == null)
            return out;
        // Prefer keyCards order first when they appear in the core.
        List<String> ordered = new ArrayList<>();
        if (theme.keyCards != null) {
            for (String k : theme.keyCards) {
                if (k != null && isInCore(k, theme))
                    ordered.add(k);
            }
        }
        for (String name : theme.core) {
            if (name != null && !ordered.contains(name))
                ordered.add(name);
        }
        for (String name : ordered) {
            if (name == null || !seen.add(name) || isRestrictedCardName(name))
                continue;
            PaperCard pc = cardByName(name);
            if (pc == null || isExcludedFromAdventureDecks(pc))
                continue;
            if (pc.getRules().getType().isLand())
                continue;
            if (forgeFormat != null && !cardLegalInFixedFormat(pc, format, forgeFormat))
                continue;
            if (FORMAT_COMMANDER.equals(format) && allowed != 0
                    && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            if (!FORMAT_COMMANDER.equals(format) && allowed != 0
                    && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            out.add(preferPaperPrinting(pc));
        }
        return out;
    }

    private static void addCoreToPool(CardPool main, List<PaperCard> coreLegal, String format,
                                      int wantCopies, boolean singleton) {
        if (coreLegal == null || coreLegal.isEmpty() || wantCopies <= 0)
            return;
        int have = 0;
        for (PaperCard pc : main.toFlatList()) {
            if (pc != null && !pc.getRules().getType().isLand())
                have++;
        }
        int perName = singleton ? 1 : (FORMAT_PAUPER.equals(format) ? 4 : 3);
        for (PaperCard pc : coreLegal) {
            if (have >= wantCopies)
                break;
            int cur = main.countByName(pc.getName());
            int max = singleton ? 1 : Math.min(4, perName);
            PaperCard print = preferPaperPrinting(pc);
            for (int i = cur; i < max && have < wantCopies; i++) {
                main.add(print);
                have++;
            }
        }
    }

    private static void addFillerNonLand(CardPool main, EnemyThemeData theme, String format,
                                         GameFormat forgeFormat, byte allowed, int want,
                                         boolean singleton) {
        if (want <= 0)
            return;
        List<PaperCard> pool = new ArrayList<>();
        try {
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
                    continue;
                if (pc.getRules().getType().isLand())
                    continue;
                if (isInCore(pc.getName(), theme))
                    continue;
                if (!isOnTheme(pc, theme))
                    continue;
                if (isExcludedFromAdventureDecks(pc) || isRestrictedCardName(pc.getName()))
                    continue;
                if (forgeFormat != null && !cardLegalInFixedFormat(pc, format, forgeFormat))
                    continue;
                if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                pool.add(preferPaperPrinting(pc));
            }
        } catch (Throwable ignored) {
        }
        Collections.shuffle(pool, MyRandom.getRandom());
        // Prefer tribe creatures for tribal themes so filler does not spend slots on
        // off-tribe "flash/flying" matches (e.g. Undersea Invader in spirit_tempo).
        if (isTribalTheme(theme)) {
            pool.sort((a, b) -> Boolean.compare(
                    countsAsTribalCreature(b, theme),
                    countsAsTribalCreature(a, theme)));
        }
        int fillerCap = FORMAT_COMMANDER.equals(format) ? MAX_FILLER_COMMANDER : MAX_FILLER_NONLAND;
        int added = 0;
        for (PaperCard pc : pool) {
            if (added >= want)
                break;
            // Skip changeling / off-tribal fodder for dragon themes.
            if (theme != null && theme.id != null && theme.id.contains("dragon")
                    && "Studious First-Year".equals(pc.getName()))
                continue;
            // Tribal constructed: spend filler on tribe creatures before off-tribe glue.
            if (isTribalTheme(theme) && !FORMAT_COMMANDER.equals(format)
                    && !countsAsTribalCreature(pc, theme)) {
                // Allow a little non-tribe only if we somehow cannot fill with tribe.
                boolean anyTribeLeft = false;
                for (PaperCard t : pool) {
                    if (countsAsTribalCreature(t, theme)
                            && main.countByName(t.getName()) < (singleton ? 1
                            : (FORMAT_PAUPER.equals(format) ? 4 : 2))) {
                        anyTribeLeft = true;
                        break;
                    }
                }
                if (anyTribeLeft)
                    continue;
            }
            if (countFillerInPool(main, theme) >= fillerCap)
                break;
            int max = singleton ? 1 : (FORMAT_PAUPER.equals(format) ? 4 : 2);
            int cur = main.countByName(pc.getName());
            int room = Math.min(max - cur,
                    Math.min(want - added, fillerCap - countFillerInPool(main, theme)));
            for (int i = 0; i < room; i++) {
                main.add(pc);
                added++;
            }
        }
    }

    /** Drop excess non-lands (prefer dropping filler, then extras) to fit budget. */
    private static void trimToBudgets(CardPool main, EnemyThemeData theme, int maxNonLand) {
        trimToBudgets(main, theme, maxNonLand, MAX_FILLER_NONLAND);
    }

    private static void trimToBudgets(CardPool main, EnemyThemeData theme, int maxNonLand, int maxFiller) {
        // Hard-cap filler first.
        while (countFillerInPool(main, theme) > maxFiller) {
            PaperCard victim = null;
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isLand())
                    continue;
                if (!isInCore(pc.getName(), theme)) {
                    victim = pc;
                    break;
                }
            }
            if (victim == null)
                break;
            main.remove(victim);
        }
        while (countNonLands(main) > maxNonLand) {
            PaperCard victim = null;
            // Prefer removing filler.
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isLand())
                    continue;
                if (!isInCore(pc.getName(), theme)) {
                    victim = pc;
                    break;
                }
            }
            if (victim == null) {
                for (PaperCard pc : main.toFlatList()) {
                    if (!pc.getRules().getType().isLand()) {
                        victim = pc;
                        break;
                    }
                }
            }
            if (victim == null)
                break;
            main.remove(victim);
        }
    }

    private static List<String> colorSelection(String[] colors) {
        List<String> out = new ArrayList<>();
        if (colors == null || colors.length == 0) {
            out.add("blue");
            return out;
        }
        for (String c : colors) {
            if (c == null || c.isEmpty())
                continue;
            out.add(c.trim().toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty())
            out.add("blue");
        // DeckgenUtil color gens support 1–3 or 5; clamp 4→3.
        if (out.size() == 4)
            return out.subList(0, 3);
        return out;
    }

    private static void stripExcludedFromMain(Deck deck, String format, GameFormat forgeFormat) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (pc.getRules().getType().isBasicLand())
                continue; // lands rebuilt later
            if (isExcludedFromAdventureDecks(pc) || isRestrictedCardName(pc.getName()))
                continue;
            if (!cardLegalInFixedFormat(pc, format, forgeFormat))
                continue;
            keep.add(preferPaperPrinting(pc));
        }
        main.clear();
        for (PaperCard pc : keep)
            main.add(pc);
    }

    private static void stripExcludedAndOffIdentity(Deck deck, byte ci) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (pc.getRules().getType().isBasicLand())
                continue;
            if (isExcludedFromAdventureDecks(pc) || isRestrictedCardName(pc.getName()))
                continue;
            if (!pc.getRules().getColorIdentity().hasNoColorsExcept(ci)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            if (!pc.getRules().getType().isBasicLand() && !canHaveAnyNumber(pc)
                    && !seen.add(pc.getName()))
                continue; // singleton
            keep.add(preferPaperPrinting(pc));
        }
        main.clear();
        for (PaperCard pc : keep)
            main.add(pc);
    }

    private static void injectKeyCards(Deck deck, EnemyThemeData theme, String format,
                                       GameFormat forgeFormat, byte allowed, boolean singleton) {
        if (theme.keyCards == null)
            return;
        CardPool main = deck.getOrCreate(DeckSection.Main);
        for (String name : theme.keyCards) {
            if (name == null || isRestrictedCardName(name))
                continue;
            PaperCard pc = cardByName(name);
            if (pc == null || isExcludedFromAdventureDecks(pc))
                continue;
            if (pc.getRules().getType().isLand())
                continue;
            if (!cardLegalInFixedFormat(pc, format, forgeFormat))
                continue;
            if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            int copies = singleton ? 1 : (FORMAT_PAUPER.equals(format) ? 4 : 2);
            int have = main.countByName(pc.getName());
            PaperCard print = preferPaperPrinting(pc);
            for (int i = have; i < copies; i++)
                main.add(print);
        }
    }

    private static void injectThemeCreatures(Deck deck, EnemyThemeData theme, String format,
                                             GameFormat forgeFormat, byte allowed, int want,
                                             boolean singleton) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        int have = 0;
        for (PaperCard pc : main.toFlatList()) {
            if (pc != null && isOnTheme(pc, theme) && pc.getRules().getType().isCreature())
                have++;
        }
        if (have >= want)
            return;
        List<PaperCard> pool = new ArrayList<>();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (!pc.getRules().getType().isCreature())
                continue;
            if (!isOnTheme(pc, theme))
                continue;
            if (isExcludedFromAdventureDecks(pc) || isRestrictedCardName(pc.getName()))
                continue;
            if (forgeFormat != null && !cardLegalInFixedFormat(pc, format, forgeFormat))
                continue;
            if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            pool.add(preferPaperPrinting(pc));
        }
        Collections.shuffle(pool, MyRandom.getRandom());
        Set<String> used = new HashSet<>();
        for (PaperCard pc : main.toFlatList())
            used.add(pc.getName());
        for (PaperCard pc : pool) {
            if (have >= want)
                break;
            if (singleton && !used.add(pc.getName()))
                continue;
            if (!singleton && used.contains(pc.getName()) && main.countByName(pc.getName()) >= 4)
                continue;
            // Swap out an off-theme creature when possible to keep size stable.
            PaperCard victim = null;
            for (PaperCard c : main.toFlatList()) {
                if (c.getRules().getType().isCreature() && !isOnTheme(c, theme)) {
                    victim = c;
                    break;
                }
            }
            if (victim != null)
                main.remove(victim);
            int copies = singleton ? 1 : (FORMAT_PAUPER.equals(format) ? 4 : 2);
            PaperCard print = preferPaperPrinting(pc);
            for (int i = 0; i < copies && have < want; i++) {
                main.add(print);
                have++;
            }
            used.add(pc.getName());
        }
    }

    private static void injectThemeSpells(Deck deck, EnemyThemeData theme, String format,
                                          GameFormat forgeFormat, byte allowed, int want,
                                          boolean singleton) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<String> names = archetypeSpellNames(theme);
        List<PaperCard> pool = new ArrayList<>();
        for (String name : names) {
            PaperCard pc = cardByName(name);
            if (pc == null || isExcludedFromAdventureDecks(pc) || isRestrictedCardName(name))
                continue;
            if (pc.getRules().getType().isLand() || pc.getRules().getType().isCreature())
                continue;
            if (forgeFormat != null && !cardLegalInFixedFormat(pc, format, forgeFormat))
                continue;
            if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            pool.add(pc);
        }
        // Also pull legal non-creature spells that are on-theme (tribal lords etc. may be creatures).
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRules().getType().isLand() || pc.getRules().getType().isCreature())
                continue;
            if (!isOnTheme(pc, theme))
                continue;
            if (isExcludedFromAdventureDecks(pc) || isRestrictedCardName(pc.getName()))
                continue;
            if (forgeFormat != null && !cardLegalInFixedFormat(pc, format, forgeFormat))
                continue;
            if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            pool.add(pc);
        }
        Collections.shuffle(pool, MyRandom.getRandom());
        int have = countNonCreatureSpells(deck);
        Set<String> used = new HashSet<>();
        for (PaperCard pc : main.toFlatList())
            used.add(pc.getName());
        for (PaperCard pc : pool) {
            if (have >= want)
                break;
            int copies = singleton ? 1 : (FORMAT_PAUPER.equals(format) ? 4 : 2);
            int cur = main.countByName(pc.getName());
            PaperCard print = preferPaperPrinting(pc);
            for (int i = cur; i < copies && have < want; i++) {
                main.add(print);
                have++;
            }
        }
    }

    private static List<String> archetypeSpellNames(EnemyThemeData theme) {
        List<String> names = new ArrayList<>();
        if (theme.keyCards != null)
            names.addAll(Arrays.asList(theme.keyCards));
        String id = theme.id != null ? theme.id : "";
        if (id.contains("burn") || id.contains("goblin")) {
            Collections.addAll(names, "Lightning Bolt", "Shock", "Lava Spike", "Searing Blaze",
                    "Goblin Grenade", "Fireblast", "Chain Lightning", "Skewer the Critics");
        }
        if (id.contains("ramp") || id.contains("dragon")) {
            Collections.addAll(names, "Cultivate", "Rampant Growth", "Farseek", "Kodama's Reach",
                    "Utopia Sprawl", "Sakura-Tribe Elder", "Nature's Lore", "Three Visits");
        }
        if (id.contains("vampire") || id.contains("drain")) {
            Collections.addAll(names, "Blood Artist", "Sign in Blood", "Infernal Grasp", "Go for the Throat",
                    "Fatal Push", "Feed the Swarm");
        }
        if (id.contains("zombie")) {
            Collections.addAll(names, "Village Rites", "Deadly Dispute", "Feed the Swarm",
                    "Go for the Throat", "Infernal Grasp", "Unearth");
        }
        if (id.contains("elf")) {
            Collections.addAll(names, "Harvest Time", "Elven Chorus", "Collected Company",
                    "Chord of Calling", "Natural Order", "Heroic Intervention");
        }
        if (id.contains("merfolk") || id.contains("kraken") || id.contains("spirit_tempo")) {
            Collections.addAll(names, "Counterspell", "Remand", "Mana Leak", "Negate",
                    "Brainstorm", "Ponder", "Preordain", "Opt");
        }
        if (id.contains("knight") || id.contains("soldier") || id.contains("spirit_tribal")) {
            Collections.addAll(names, "Swords to Plowshares", "Path to Exile", "Raise the Alarm",
                    "History of Benalia", "Secure the Wastes", "Brave the Elements");
        }
        // Universal interaction / draw staples by color.
        byte cols = colorMask(theme.colors);
        if ((cols & MagicColor.BLUE) != 0)
            Collections.addAll(names, "Counterspell", "Brainstorm", "Ponder", "Negate");
        if ((cols & MagicColor.BLACK) != 0)
            Collections.addAll(names, "Go for the Throat", "Infernal Grasp", "Sign in Blood", "Duress");
        if ((cols & MagicColor.RED) != 0)
            Collections.addAll(names, "Lightning Bolt", "Shock", "Abrade", "Lightning Strike");
        if ((cols & MagicColor.GREEN) != 0)
            Collections.addAll(names, "Rampant Growth", "Cultivate", "Nature's Claim", "Beast Within");
        if ((cols & MagicColor.WHITE) != 0)
            Collections.addAll(names, "Swords to Plowshares", "Path to Exile", "Raise the Alarm");
        return names;
    }

    private static void ensureMinNonCreatureSpells(Deck deck, EnemyThemeData theme, String format,
                                                   GameFormat forgeFormat, byte allowed) {
        int have = countNonCreatureSpells(deck);
        if (have >= MIN_NON_CREATURE_SPELLS_60)
            return;
        injectThemeSpells(deck, theme, format, forgeFormat, allowed,
                MIN_NON_CREATURE_SPELLS_60 + 4, false);
    }

    private static Deck finalizeConstructed(Deck deck, EnemyThemeData theme, String format, int target) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        // Cap at 4 copies.
        Map<String, Integer> counts = new HashMap<>();
        List<PaperCard> flat = new ArrayList<>(main.toFlatList());
        main.clear();
        for (PaperCard pc : flat) {
            if (pc.getRules().getType().isBasicLand()) {
                main.add(pc);
                continue;
            }
            int n = counts.getOrDefault(pc.getName(), 0);
            if (n >= 4)
                continue;
            main.add(preferPaperPrinting(pc));
            counts.put(pc.getName(), n + 1);
        }
        String[] pad = colorsFromMask(spellColorMask(deck));
        if (pad.length == 0 && theme.colors != null)
            pad = theme.colors;
        rebuildBasicLands(deck, pad, target);
        return deck;
    }

    private static GameFormat forgeFormatFor(String format) {
        try {
            if (FORMAT_PAUPER.equals(format))
                return FModel.getFormats().getPauper();
            if (FORMAT_HISTORIC.equals(format))
                return FModel.getFormats().getHistoric();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * True when a card is legal in the fixed EN1 format under Forge's own rules.
     */
    public static boolean cardLegalInFixedFormat(PaperCard pc, String format, GameFormat forgeFormat) {
        if (pc == null)
            return false;
        if (pc.getRules().getType().isBasicLand())
            return true;
        if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
            return false;
        String fmt = normalizeFormat(format);
        if (FORMAT_PAUPER.equals(fmt)) {
            if (isAdventureBanned("pauper", pc.getName()))
                return false;
            if (forgeFormat != null && forgeFormat.getFilterRules() != null)
                return forgeFormat.getFilterRules().test(pc);
            return pc.getRarity() == CardRarity.Common || pc.getRarity() == CardRarity.BasicLand;
        }
        if (FORMAT_HISTORIC.equals(fmt)) {
            if (isAdventureBanned("historic", pc.getName()))
                return false;
            if (forgeFormat != null && forgeFormat.getFilterRules() != null)
                return forgeFormat.getFilterRules().test(pc);
            return true;
        }
        if (FORMAT_COMMANDER.equals(fmt)) {
            return !isAdventureBanned("commander", pc.getName());
        }
        return true;
    }

    private static boolean isAdventureBanned(String format, String cardName) {
        try {
            return BanLists.isBanned(format, cardName);
        } catch (Throwable e) {
            return false;
        }
    }

    private static String alchemyOrRestrictedProblem(Deck deck) {
        for (var e : deck.getAllCardsInASinglePool(true, false)) {
            PaperCard pc = e.getKey();
            if (pc == null)
                continue;
            if (isEnemyBanned(pc.getName()))
                return pc.getName() + " is EN1 enemy-banned";
            if (isRestrictedCardName(pc.getName()))
                return pc.getName() + " is Ascendant-restricted";
            if (isExcludedFromAdventureDecks(pc))
                return pc.getName() + " is Alchemy/Online/Funny/restricted-edition only";
        }
        return null;
    }

    /**
     * Validates a fixed deck against Forge format legality. Returns null if OK,
     * otherwise a problem description.
     * <p>
     * Also rejects Ascendant {@code restrictedCards} and Alchemy / digital-only cards.
     * Commander: exactly 100 cards total (main + commander section), Forge
     * {@link DeckFormat#Commander} conformance (legal commander, singleton except
     * basics, color identity), plus explicit total-size / singleton / identity checks.
     * Historic / Pauper: Forge {@link GameFormat} filters from {@link FModel#getFormats()}.
     */
    public static String legalityProblem(Deck deck, String format) {
        if (deck == null)
            return "deck is null";
        String fmt = normalizeFormat(format);
        try {
            String ar = alchemyOrRestrictedProblem(deck);
            if (ar != null)
                return ar;

            if (FORMAT_COMMANDER.equals(fmt))
                return commanderLegalityProblem(deck);

            GameFormat gf = forgeFormatFor(fmt);
            if (gf == null)
                return "Forge " + fmt + " format is unavailable";
            if (!gf.isDeckLegal(deck))
                return "illegal in Forge " + fmt;
            for (var e : deck.getAllCardsInASinglePool()) {
                PaperCard pc = e.getKey();
                if (pc == null)
                    continue;
                if (pc.getRules().getType().isBasicLand())
                    continue;
                if (gf.getFilterRules() != null && !gf.getFilterRules().test(pc))
                    return pc.getName() + " is not " + fmt + "-legal (Forge format)";
                if (!cardLegalInFixedFormat(pc, fmt, gf))
                    return pc.getName() + " is not " + fmt + "-legal";
            }
            int size = deck.getMain().countAll();
            if (size < 60)
                return "main deck has " + size + " cards (need 60+)";
            Map<String, Integer> counts = new HashMap<>();
            for (var e : deck.getMain()) {
                PaperCard pc = e.getKey();
                if (pc.getRules().getType().isBasicLand() || canHaveAnyNumber(pc))
                    continue;
                int n = counts.getOrDefault(pc.getName(), 0) + e.getValue();
                counts.put(pc.getName(), n);
                if (n > 4)
                    return pc.getName() + " appears " + n + " times";
            }
            return null;
        } catch (Exception e) {
            return "legality check failed: " + e.getMessage();
        }
    }

    /**
     * Commander legality: Forge {@link DeckFormat#Commander} / {@link forge.game.GameType#Commander}
     * deck format, exact 100-card total, singleton (basics exempt), legal commander, color identity.
     */
    public static String commanderLegalityProblem(Deck deck) {
        if (deck == null)
            return "deck is null";
        String ar = alchemyOrRestrictedProblem(deck);
        if (ar != null)
            return ar;

        DeckFormat commanderFormat = forge.game.GameType.Commander.getDeckFormat();
        if (commanderFormat == null)
            commanderFormat = DeckFormat.Commander;

        String forgeProblem = commanderFormat.getDeckConformanceProblem(deck);
        if (forgeProblem != null)
            return forgeProblem;

        List<PaperCard> commanders = deck.getCommanders();
        if (commanders == null || commanders.isEmpty())
            return "missing commander";
        if (commanders.size() > 2)
            return "too many commanders";
        for (PaperCard cmd : commanders) {
            if (cmd == null || !commanderFormat.isLegalCommander(cmd.getRules()))
                return (cmd != null ? cmd.getName() : "?") + " is not a legal commander";
        }

        int main = deck.getMain().countAll();
        int cmdCount = commanders.size();
        int total = main + cmdCount;
        if (total != 100)
            return "Commander deck must total exactly 100 cards (main+commander), has " + total
                    + " (main=" + main + ", commanders=" + cmdCount + ")";

        Map<String, Integer> counts = new HashMap<>();
        for (var e : deck.getAllCardsInASinglePool(true, false)) {
            PaperCard pc = e.getKey();
            if (pc == null)
                continue;
            if (pc.getRules().getType().isBasicLand() || canHaveAnyNumber(pc))
                continue;
            int n = counts.getOrDefault(pc.getName(), 0) + e.getValue();
            counts.put(pc.getName(), n);
            if (n > 1)
                return pc.getName() + " appears " + n + " times (Commander is singleton)";
        }

        String identityProblem = commanderFormat.getCommanderConformanceProblem(deck);
        if (identityProblem != null)
            return identityProblem;

        return null;
    }
}
