package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.BanLists;
import forge.adventure.player.StandardWindow;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    public static final int MIN_ON_THEME_NONLAND_FIXED = 20;
    public static final int MIN_LANDS_60 = 16;
    public static final int MAX_LANDS_60 = 18;
    public static final int TARGET_LANDS_60 = 17;

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
            if (catalog.themes == null)
                return;
            for (EnemyThemeData t : catalog.themes) {
                if (t == null || t.id == null || t.id.isEmpty())
                    continue;
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
                    if (isRestrictedCardName(name) || isAdventureBanned("standard", name))
                        continue;
                    if (!windowLegalName(window, name))
                        continue;
                    PaperCard pc = cardByName(name);
                    if (pc == null || isAlchemyOrDigitalOnly(pc) || !isStandardWindowLegal(pc, window))
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

            padWithBasics(deck, target, colors);
            stripIllegalStandard(deck, window, target, colors);
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

    /** True when the card is Alchemy (Y-set / rebalanced A-) or digital-only ONLINE Y-set. */
    public static boolean isAlchemyOrDigitalOnly(PaperCard pc) {
        if (pc == null)
            return true;
        try {
            if (pc.isRebalanced())
                return true;
        } catch (Throwable ignored) {
        }
        String name = pc.getName();
        if (name != null && name.startsWith("A-"))
            return true;
        String ed = pc.getEdition();
        if (ed != null) {
            String code = ed.trim();
            if (code.length() >= 2 && (code.charAt(0) == 'Y' || code.charAt(0) == 'y'))
                return true;
            if (code.startsWith("OM") || code.startsWith("om"))
                return true;
        }
        return false;
    }

    public static boolean isRestrictedCardName(String name) {
        if (name == null || name.isEmpty())
            return false;
        return restrictedNames().contains(name);
    }

    public static boolean isOnTheme(PaperCard pc, EnemyThemeData theme) {
        if (pc == null || theme == null || pc.getRules() == null)
            return false;
        if (pc.getRules().getType().isBasicLand())
            return false;
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
        if (theme.creatureTypes != null) {
            for (String t : theme.creatureTypes) {
                if (t != null && !t.isEmpty() && pc.getRules().getType().hasSubtype(t))
                    return true;
            }
        }
        String oracle = pc.getRules().getOracleText();
        if (oracle != null) {
            String lower = oracle.toLowerCase(Locale.ROOT);
            if (theme.creatureTypes != null) {
                for (String t : theme.creatureTypes) {
                    if (t != null && !t.isEmpty() && lower.contains(t.toLowerCase(Locale.ROOT)))
                        return true;
                }
            }
            if (theme.mechanics != null) {
                for (String m : theme.mechanics) {
                    if (m != null && !m.isEmpty() && lower.contains(m.toLowerCase(Locale.ROOT)))
                        return true;
                }
            }
            if (theme.tags != null) {
                for (String tag : theme.tags) {
                    if (tag != null && !tag.isEmpty()
                            && lower.contains(tag.toLowerCase(Locale.ROOT)))
                        return true;
                }
            }
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
     * no Alchemy, no Ascendant restricted cards. Returns null if OK.
     */
    public static String themeQualityProblem(Deck deck, EnemyThemeData theme, String format) {
        if (deck == null)
            return "deck is null";
        if (theme == null)
            return "theme is null";
        String alchemy = alchemyOrRestrictedProblem(deck);
        if (alchemy != null)
            return alchemy;
        int onTheme = countOnThemeNonLand(deck, theme);
        if (onTheme < MIN_ON_THEME_NONLAND_FIXED)
            return "only " + onTheme + " on-theme non-lands (need ~"
                    + MIN_ON_THEME_NONLAND_FIXED + ")";
        String fmt = normalizeFormat(format);
        if (FORMAT_PAUPER.equals(fmt) || FORMAT_HISTORIC.equals(fmt)) {
            int lands = countLands(deck);
            if (lands < MIN_LANDS_60 || lands > MAX_LANDS_60)
                return "lands=" + lands + " (need " + MIN_LANDS_60 + "-" + MAX_LANDS_60 + ")";
            int main = deck.getMain().countAll();
            if (main < 60)
                return "main deck has " + main + " cards (need 60+)";
        }
        return null;
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
        String[] colors = theme != null && theme.colors != null ? theme.colors : new String[]{"blue"};
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
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
        // Commander section
        if (deck.has(DeckSection.Commander)) {
            CardPool cmd = deck.get(DeckSection.Commander);
            List<PaperCard> cmdKeep = new ArrayList<>();
            for (PaperCard pc : cmd.toFlatList()) {
                if (pc == null || isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
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

        if (FORMAT_COMMANDER.equals(format)) {
            int cmdN = deck.getCommanders() != null ? deck.getCommanders().size() : 0;
            int need = 100 - cmdN;
            padWithBasics(deck, need, colors);
            while (main.countAll() > need) {
                PaperCard remove = null;
                for (PaperCard pc : main.toFlatList()) {
                    if (pc.getRules().getType().isBasicLand()) {
                        remove = pc;
                        break;
                    }
                }
                if (remove == null)
                    break;
                main.remove(remove);
            }
        } else {
            padWithBasics(deck, 60, colors);
            while (main.countAll() > 60) {
                PaperCard remove = null;
                for (PaperCard pc : main.toFlatList()) {
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
        return deck;
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
                if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
                    continue;
                // Color before legality (cheaper).
                if (allowed != 0 && !pc.getRules().getType().isLand()
                        && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                if (!legal.contains(pc.getName()))
                    continue;
                if (isAdventureBanned("standard", pc.getName()))
                    continue;
                if (tribe != null && !tribe.isEmpty()) {
                    boolean tribal = pc.getRules().getType().hasSubtype(tribe);
                    boolean synergy = false;
                    if (!tribal && pc.getRules().getOracleText() != null)
                        synergy = pc.getRules().getOracleText().toLowerCase(Locale.ROOT)
                                .contains(tribe.toLowerCase(Locale.ROOT));
                    if (!tribal && !synergy && recipe != null && recipe.mechanics != null) {
                        String text = pc.getRules().getOracleText();
                        if (text != null) {
                            String lower = text.toLowerCase(Locale.ROOT);
                            for (String m : recipe.mechanics) {
                                if (m != null && !m.isEmpty()
                                        && lower.contains(m.toLowerCase(Locale.ROOT))) {
                                    synergy = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!tribal && !synergy)
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
        if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
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
        padWithBasics(deck, target, colors);
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

    private static Deck padWithBasics(Deck deck, int target, String[] colors) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        int guard = 0;
        while (main.countAll() < target && guard++ < target * 2) {
            String basic = "Island";
            if (colors != null && colors.length > 0) {
                String c = colors[main.countAll() % colors.length];
                basic = basicForColor(c);
            }
            PaperCard land = cardByName(basic);
            if (land == null)
                land = cardByName("Wastes");
            if (land == null)
                break;
            main.add(land);
        }
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

    private static PaperCard cardByName(String name) {
        try {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
            if (pc != null)
                return pc;
            return FModel.getMagicDb().getCommonCards().getUniqueByName(name);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean canHaveAnyNumber(PaperCard pc) {
        return DeckFormat.canHaveAnyNumberOf(pc);
    }

    // ---- generation helpers (tools / tests) ---------------------------------

    /**
     * Builds a fixed-format deck for a theme (Historic, Pauper, or Commander).
     * Used by the deck generator tool; not the runtime Standard path.
     */
    public static Deck buildFixedDeck(EnemyThemeData theme, String format, long seed) {
        String fmt = normalizeFormat(format);
        Random previous = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(seed));
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
        if (commander == null) {
            LOG.warning("EN1: no commander for " + theme.id);
            return new Deck(theme.id + " Commander");
        }
        Random rng = new Random(seed);
        Deck deck = new Deck(theme.id + " Commander");
        deck.getOrCreate(DeckSection.Commander).add(commander);
        CardPool main = deck.getOrCreate(DeckSection.Main);
        byte ci = commander.getRules().getColorIdentity().getColor();
        String[] colors = theme.colors != null ? theme.colors : colorsFromMask(ci);

        List<PaperCard> tribal = new ArrayList<>();
        List<PaperCard> synergy = new ArrayList<>();
        List<PaperCard> filler = new ArrayList<>();
        collectThemedPool(theme, FORMAT_COMMANDER, null, ci, tribal, synergy, filler);

        Collections.shuffle(tribal, rng);
        Collections.shuffle(synergy, rng);
        Collections.shuffle(filler, rng);

        int landTarget = 36;
        int spellTarget = 99 - landTarget;
        Set<String> used = new HashSet<>();
        used.add(commander.getName());

        for (PaperCard pc : tribal) {
            if (main.countAll() >= spellTarget)
                break;
            if (!used.add(pc.getName()))
                continue;
            if (!pc.getRules().getColorIdentity().hasNoColorsExcept(ci)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            main.add(pc);
        }
        for (PaperCard pc : synergy) {
            if (main.countAll() >= spellTarget)
                break;
            if (!used.add(pc.getName()))
                continue;
            if (!pc.getRules().getColorIdentity().hasNoColorsExcept(ci)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            main.add(pc);
        }
        for (PaperCard pc : filler) {
            if (main.countAll() >= spellTarget)
                break;
            if (!used.add(pc.getName()))
                continue;
            if (!pc.getRules().getColorIdentity().hasNoColorsExcept(ci)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            main.add(pc);
        }
        padWithBasics(deck, 99, colors);
        while (main.countAll() > 99) {
            PaperCard remove = null;
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isBasicLand()) {
                    remove = pc;
                    break;
                }
            }
            if (remove == null)
                break;
            main.remove(remove);
        }
        return deck;
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
        if (out.isEmpty())
            out.add("blue");
        return out.toArray(new String[0]);
    }

    private static PaperCard pickCommander(EnemyThemeData theme, long seed) {
        if (theme.preferredCommanders != null) {
            for (String name : theme.preferredCommanders) {
                if (isRestrictedCardName(name))
                    continue;
                PaperCard pc = cardByName(name);
                if (pc != null && !isAlchemyOrDigitalOnly(pc)
                        && DeckFormat.Commander.isLegalCommander(pc.getRules()))
                    return pc;
            }
        }
        String tribe = firstCreatureType(theme);
        byte allowed = colorMask(theme.colors);
        List<PaperCard> candidates = new ArrayList<>();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
                continue;
            CardRules rules = pc.getRules();
            if (!DeckFormat.Commander.isLegalCommander(rules))
                continue;
            if (tribe != null && !tribe.isEmpty() && !rules.getType().hasSubtype(tribe))
                continue;
            if (allowed != 0 && !rules.getColorIdentity().hasNoColorsExcept(allowed)
                    && !rules.getColorIdentity().isColorless())
                continue;
            candidates.add(pc);
        }
        if (candidates.isEmpty()) {
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
                    continue;
                if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
                    continue;
                if (!DeckFormat.Commander.isLegalCommander(pc.getRules()))
                    continue;
                if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                candidates.add(pc);
            }
        }
        if (candidates.isEmpty())
            return null;
        return candidates.get(new Random(seed).nextInt(candidates.size()));
    }

    private static Deck buildConstructedDeck(EnemyThemeData theme, String format, int target, long seed) {
        Deck deck = new Deck(theme.id + " " + format);
        CardPool main = deck.getOrCreate(DeckSection.Main);
        String[] colors = theme.colors != null ? theme.colors : new String[]{"blue"};
        byte allowed = colorMask(colors);
        GameFormat forgeFormat = forgeFormatFor(format);
        Random rng = new Random(seed);

        List<PaperCard> tribal = new ArrayList<>();
        List<PaperCard> synergy = new ArrayList<>();
        List<PaperCard> filler = new ArrayList<>();
        collectThemedPool(theme, format, forgeFormat, allowed, tribal, synergy, filler);

        // If tribe is too thin (e.g. Kraken commons), relax to synergy+color then color-only.
        if (tribal.size() + synergy.size() < MIN_ON_THEME_NONLAND_FIXED) {
            tribal.clear();
            synergy.clear();
            filler.clear();
            // Treat all creatureTypes / tags as soft oracle matches already in collect;
            // rebuild without requiring subtype — expand tribe list.
            collectThemedPoolRelaxed(theme, format, forgeFormat, allowed, tribal, synergy, filler);
        }
        if (tribal.size() + synergy.size() < MIN_ON_THEME_NONLAND_FIXED) {
            // Last resort for constructed: drop color filter for on-theme types only.
            tribal.clear();
            synergy.clear();
            collectThemedPoolRelaxed(theme, format, forgeFormat, (byte) 0, tribal, synergy, filler);
        }

        Collections.shuffle(tribal, rng);
        Collections.shuffle(synergy, rng);
        Collections.shuffle(filler, rng);

        int landTarget = TARGET_LANDS_60;
        int spellTarget = target - landTarget;
        Set<String> used = new HashSet<>();

        // Prefer key cards.
        if (theme.keyCards != null) {
            for (String name : theme.keyCards) {
                if (name == null || used.contains(name) || isRestrictedCardName(name))
                    continue;
                PaperCard pc = cardByName(name);
                if (pc == null || isAlchemyOrDigitalOnly(pc))
                    continue;
                if (pc.getRules().getType().isLand())
                    continue;
                if (!cardLegalInFixedFormat(pc, format, forgeFormat))
                    continue;
                int copies = FORMAT_PAUPER.equals(format) ? 4 : 2 + rng.nextInt(3);
                for (int i = 0; i < copies && main.countAll() < spellTarget; i++)
                    main.add(pc);
                used.add(name);
            }
        }

        for (PaperCard pc : tribal) {
            if (main.countAll() >= spellTarget)
                break;
            if (!used.add(pc.getName()))
                continue;
            int copies = FORMAT_PAUPER.equals(format) ? 4 : 2 + rng.nextInt(3);
            for (int i = 0; i < copies && main.countAll() < spellTarget; i++)
                main.add(pc);
        }
        for (PaperCard pc : synergy) {
            if (main.countAll() >= spellTarget)
                break;
            if (!used.add(pc.getName()))
                continue;
            int copies = FORMAT_PAUPER.equals(format) ? 4 : 1 + rng.nextInt(3);
            for (int i = 0; i < copies && main.countAll() < spellTarget; i++)
                main.add(pc);
        }
        for (PaperCard pc : filler) {
            if (main.countAll() >= spellTarget)
                break;
            if (!used.add(pc.getName()))
                continue;
            int copies = 1 + rng.nextInt(2);
            for (int i = 0; i < copies && main.countAll() < spellTarget; i++)
                main.add(pc);
        }
        padWithBasics(deck, target, colors);
        return sanitizeConstructed(deck, format, target, colors);
    }

    private static void collectThemedPool(EnemyThemeData theme, String format, GameFormat forgeFormat,
                                          byte allowed, List<PaperCard> tribal, List<PaperCard> synergy,
                                          List<PaperCard> filler) {
        String[] types = theme.creatureTypes != null ? theme.creatureTypes : new String[0];
        try {
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
                    continue;
                if (pc.getRules().getType().isBasicLand() || pc.getRules().getType().isLand())
                    continue;
                if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
                    continue;
                if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                if (!cardLegalInFixedFormat(pc, format, forgeFormat))
                    continue;
                boolean isTribal = false;
                for (String t : types) {
                    if (t != null && pc.getRules().getType().hasSubtype(t)) {
                        isTribal = true;
                        break;
                    }
                }
                if (isTribal) {
                    tribal.add(pc);
                    continue;
                }
                if (isOnTheme(pc, theme))
                    synergy.add(pc);
                else
                    filler.add(pc);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: themed pool build failed", e);
        }
    }

    /** Soften subtype requirement: oracle / mechanic / tag matches count as tribal. */
    private static void collectThemedPoolRelaxed(EnemyThemeData theme, String format,
                                                 GameFormat forgeFormat, byte allowed,
                                                 List<PaperCard> tribal, List<PaperCard> synergy,
                                                 List<PaperCard> filler) {
        try {
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
                    continue;
                if (pc.getRules().getType().isBasicLand() || pc.getRules().getType().isLand())
                    continue;
                if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
                    continue;
                if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                if (!cardLegalInFixedFormat(pc, format, forgeFormat))
                    continue;
                if (isOnTheme(pc, theme))
                    tribal.add(pc);
                else
                    filler.add(pc);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: relaxed pool build failed", e);
        }
    }

    private static Deck sanitizeConstructed(Deck deck, String format, int target, String[] colors) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        GameFormat forgeFormat = forgeFormatFor(format);
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (isRestrictedCardName(pc.getName()) || isAlchemyOrDigitalOnly(pc))
                continue;
            if (pc.getRules().getType().isBasicLand()) {
                keep.add(pc);
                continue;
            }
            if (!cardLegalInFixedFormat(pc, format, forgeFormat))
                continue;
            keep.add(pc);
        }
        main.clear();
        Map<String, Integer> counts = new HashMap<>();
        for (PaperCard pc : keep) {
            int n = counts.getOrDefault(pc.getName(), 0);
            if (!pc.getRules().getType().isBasicLand() && n >= 4)
                continue;
            main.add(pc);
            counts.put(pc.getName(), n + 1);
        }
        // Trim spells if we have too many lands after pad, then set land band.
        padWithBasics(deck, target, colors);
        while (main.countAll() > target) {
            PaperCard remove = null;
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isBasicLand()) {
                    remove = pc;
                    break;
                }
            }
            if (remove == null)
                break;
            main.remove(remove);
        }
        // Enforce 16–18 lands: if too many lands, drop basics; if too few, add.
        int lands = countLands(deck);
        while (lands > MAX_LANDS_60) {
            PaperCard remove = null;
            for (PaperCard pc : main.toFlatList()) {
                if (pc.getRules().getType().isBasicLand()) {
                    remove = pc;
                    break;
                }
            }
            if (remove == null)
                break;
            main.remove(remove);
            lands--;
        }
        while (lands < MIN_LANDS_60 && main.countAll() < target) {
            String basic = basicForColor(colors != null && colors.length > 0 ? colors[0] : "blue");
            PaperCard land = cardByName(basic);
            if (land == null)
                break;
            main.add(land);
            lands++;
        }
        // If over target after adding lands, trim non-theme non-lands first.
        while (main.countAll() > target) {
            PaperCard remove = null;
            for (PaperCard pc : main.toFlatList()) {
                if (!pc.getRules().getType().isBasicLand()) {
                    remove = pc;
                    break;
                }
            }
            if (remove == null)
                break;
            main.remove(remove);
        }
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
            if (isRestrictedCardName(pc.getName()))
                return pc.getName() + " is Ascendant-restricted";
            if (isAlchemyOrDigitalOnly(pc))
                return pc.getName() + " is Alchemy/digital-only";
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
