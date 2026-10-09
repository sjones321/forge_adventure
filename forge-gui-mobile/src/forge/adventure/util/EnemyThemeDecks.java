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
import forge.deck.DeckgenUtil;
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
 * fills a theme recipe from the current Standard window.
 * <p>
 * Gyms, League and story fights keep hand-picked / {@code preparedDeck} lists and
 * never enter this path. MV2 set-plane {@code $generate} hooks should run before
 * EN1 in {@link EnemyData#generateDeck} when both are present.
 */
public final class EnemyThemeDecks {
    private static final Logger LOG = Logger.getLogger(EnemyThemeDecks.class.getName());

    public static final String FORMAT_STANDARD = GymUtil.FORMAT_STANDARD;
    public static final String FORMAT_PAUPER = GymUtil.FORMAT_PAUPER;
    public static final String FORMAT_HISTORIC = GymUtil.FORMAT_HISTORIC;
    public static final String FORMAT_COMMANDER = GymUtil.FORMAT_COMMANDER;

    private static final String[] FORMAT_FALLBACK_ORDER = {
            FORMAT_HISTORIC, FORMAT_PAUPER, FORMAT_COMMANDER, FORMAT_STANDARD
    };

    private static EnemyThemeCatalogData catalog;
    private static Map<String, EnemyThemeData> byId;
    private static Map<String, List<EnemyThemeData>> byTag;
    private static boolean loadAttempted;
    /** Null = use Config; non-null overrides for unit tests. */
    private static Boolean forceEnabledForTests;

    private EnemyThemeDecks() {
    }

    /** Drop cached catalog (plane switch / tests). */
    public static void clearCache() {
        catalog = null;
        byId = null;
        byTag = null;
        loadAttempted = false;
        forceEnabledForTests = null;
    }

    /**
     * Test hook: install a catalog without touching {@link Config} file IO.
     */
    public static void loadCatalogForTests(EnemyThemeCatalogData data) {
        clearCache();
        loadAttempted = true;
        byId = new HashMap<>();
        byTag = new HashMap<>();
        catalog = data != null ? data : new EnemyThemeCatalogData();
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

    /** Test hook: force EN1 on/off without a live Ascendant Config. */
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
        if (catalog == null || catalog.themes == null)
            return Collections.emptyList();
        List<EnemyThemeData> out = new ArrayList<>();
        for (EnemyThemeData t : catalog.themes) {
            if (t != null && t.id != null && !t.id.isEmpty())
                out.add(t);
        }
        return out;
    }

    public static EnemyThemeData getTheme(String themeId) {
        ensureLoaded();
        if (themeId == null || byId == null)
            return null;
        return byId.get(themeId);
    }

    /**
     * Picks a theme for an enemy at spawn from its quest tags. Returns null when
     * EN1 is off, the enemy is a boss, or no theme matches.
     */
    public static String pickThemeId(EnemyData data, Random rng) {
        if (!isEnabled() || data == null || data.boss)
            return null;
        ensureLoaded();
        if (byTag == null || byTag.isEmpty())
            return null;
        List<EnemyThemeData> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
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
        // Also match enemy name as a soft tag (e.g. enemy named "Goblin").
        if (data.name != null) {
            List<EnemyThemeData> list = byTag.get(normalizeTag(data.name));
            if (list != null) {
                for (EnemyThemeData t : list) {
                    if (t.id != null && seen.add(t.id))
                        candidates.add(t);
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
     * Falls back to nearest available theme/format deck, then {@code stockFallback}.
     * Never throws; never returns null (empty deck at worst).
     */
    public static Deck resolveDeck(EnemyData enemy, boolean isFantasyMode, boolean useGeneticAI) {
        Deck stock = null;
        try {
            stock = loadStockDeck(enemy, isFantasyMode, useGeneticAI);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: stock deck load failed", e);
        }
        if (!isEnabled() || enemy == null || enemy.themeId == null || enemy.themeId.isEmpty())
            return stock != null ? stock : new Deck("EN1 empty");

        try {
            String format = resolveFormat();
            Deck themed = resolveForThemeAndFormat(enemy.themeId, format);
            if (themed != null && !themed.isEmpty())
                return themed;
            LOG.warning("EN1: no deck for theme=" + enemy.themeId + " format=" + format
                    + "; falling back to stock");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: theme deck resolve failed; falling back to stock", e);
        }
        return stock != null ? stock : new Deck("EN1 empty");
    }

    /**
     * Resolve a theme deck for an explicit format. Used by tests and the deck
     * generator. Returns null when nothing usable is found (caller falls back).
     */
    public static Deck resolveForThemeAndFormat(String themeId, String format) {
        ensureLoaded();
        if (themeId == null || themeId.isEmpty())
            return null;
        EnemyThemeData theme = byId != null ? byId.get(themeId) : null;
        if (theme == null) {
            LOG.warning("EN1: unknown theme " + themeId);
            return tryAnyThemeDeck(format);
        }

        String fmt = normalizeFormat(format);
        Deck deck = loadFixedOrRecipe(theme, fmt);
        if (deck != null && !deck.isEmpty())
            return deck;

        // Same theme, other formats.
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

        // Other themes sharing a tag.
        if (theme.tags != null) {
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

        return tryAnyThemeDeck(fmt);
    }

    /**
     * Builds a Bellwarden Standard deck from the theme recipe using only cards
     * legal in {@code window}. Never throws; returns an empty deck on failure.
     */
    public static Deck fillStandardRecipe(EnemyThemeData theme, StandardWindow window, long seed) {
        Deck empty = new Deck(theme != null && theme.id != null ? theme.id + " Standard" : "EN1 Standard");
        try {
            if (theme == null)
                return empty;
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

            List<PaperCard> pool = buildWindowPool(window, colors, tribe, recipe);
            if (pool.isEmpty()) {
                LOG.warning("EN1: Standard recipe pool empty for " + theme.id);
                return padWithBasics(empty, target, colors);
            }

            Random rng = new Random(seed);
            Collections.shuffle(pool, rng);

            Deck deck = new Deck(theme.id + " Standard");
            CardPool main = deck.getOrCreate(DeckSection.Main);
            Set<String> used = new HashSet<>();

            // Prefer key cards when legal in the window.
            String[] keys = recipe.keyCards != null && recipe.keyCards.length > 0
                    ? recipe.keyCards : theme.keyCards;
            if (keys != null) {
                for (String name : keys) {
                    if (name == null || name.isEmpty() || used.contains(name))
                        continue;
                    if (window != null && window.isActive() && !window.isStandardLegal(name))
                        continue;
                    if (isAdventureBanned("standard", name))
                        continue;
                    PaperCard pc = cardByName(name);
                    if (pc == null || !isStandardWindowLegal(pc, window))
                        continue;
                    int copies = pc.getRules().getType().isBasicLand() ? 0
                            : Math.min(4, target / 10);
                    if (copies <= 0)
                        continue;
                    for (int i = 0; i < copies && main.countAll() < target; i++)
                        main.add(pc);
                    used.add(name);
                }
            }

            int spellTarget = Math.max(1, Math.round(target * 0.6f));
            for (PaperCard pc : pool) {
                if (main.countAll() >= spellTarget)
                    break;
                if (pc == null || used.contains(pc.getName()))
                    continue;
                if (pc.getRules().getType().isLand())
                    continue;
                if (!isStandardWindowLegal(pc, window))
                    continue;
                int max = canHaveAnyNumber(pc) ? 4 : 4;
                int have = main.countByName(pc.getName());
                int add = Math.min(max - have, spellTarget - main.countAll());
                for (int i = 0; i < add; i++)
                    main.add(pc);
                used.add(pc.getName());
            }

            padWithBasics(deck, target, colors);

            // Final pass: drop anything outside the window (never crash; just strip).
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
            return deck;
        } catch (Exception e) {
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
            FileHandle folder = Config.instance().getFile(dir);
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
            // Convention fallback when directory listing is unavailable.
            if (out.isEmpty()) {
                for (int n = 1; n <= 4; n++) {
                    String rel = dir + fmt + "_" + n + ".dck";
                    FileHandle fh = Config.instance().getFile(rel);
                    if (fh != null && fh.exists())
                        out.add(rel);
                }
            }
            Collections.sort(out);
        } catch (Exception e) {
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

    // ---- internals ----------------------------------------------------------

    private static void ensureLoaded() {
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
        } catch (Exception ignored) {
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
        } catch (Exception ignored) {
        }
        String path = paths.get(pick);
        return loadDck(path);
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
        } catch (Exception ignored) {
        }
        return fillStandardRecipe(theme, window, seed);
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
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: failed to load " + relativePath, e);
            return null;
        }
    }

    private static Deck tryAnyThemeDeck(String format) {
        ensureLoaded();
        if (byId == null)
            return null;
        for (EnemyThemeData t : byId.values()) {
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
        // Replicate the stock path without re-entering EN1 (themeId cleared on a copy).
        EnemyData stock = new EnemyData(enemy);
        stock.themeId = null;
        stock.preparedDeck = enemy.preparedDeck;
        if (stock.preparedDeck != null)
            return stock.preparedDeck;
        if (stock.deck == null || stock.deck.length == 0)
            return new Deck(stock.getName());
        boolean canUseGeneticAI = useGeneticAI && stock.life > 16;
        if (stock.randomizeDeck)
            return CardUtil.getDeck(forge.util.Aggregates.random(stock.deck), true, isFantasyMode,
                    stock.colors, stock.life > 13, canUseGeneticAI);
        int idx = 0;
        try {
            idx = Current.player().getEnemyDeckNumber(stock.getName(), stock.deck.length);
        } catch (Exception ignored) {
        }
        return CardUtil.getDeck(stock.deck[idx], true, isFantasyMode, stock.colors,
                stock.life > 13, canUseGeneticAI);
    }

    private static int standardTargetSize() {
        ConfigData cfg = Config.instance().getConfigData();
        if (cfg != null && cfg.en1StandardDeckSize > 0)
            return cfg.en1StandardDeckSize;
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

    private static List<PaperCard> buildWindowPool(StandardWindow window, String[] colors,
                                                   String tribe, EnemyThemeRecipeData recipe) {
        List<PaperCard> out = new ArrayList<>();
        try {
            if (StaticData.instance() == null || FModel.getMagicDb() == null)
                return out;
            byte allowed = colorMask(colors);
            Set<String> seen = new HashSet<>();
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
                    continue;
                if (!isStandardWindowLegal(pc, window))
                    continue;
                if (isAdventureBanned("standard", pc.getName()))
                    continue;
                if (pc.getRules().getType().isBasicLand())
                    continue;
                if (allowed != 0 && !pc.getRules().getType().isLand()
                        && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                boolean tribal = tribe != null && !tribe.isEmpty()
                        && pc.getRules().getType().hasSubtype(tribe);
                boolean synergy = false;
                if (!tribal && tribe != null && pc.getRules().getOracleText() != null)
                    synergy = pc.getRules().getOracleText().toLowerCase(Locale.ROOT)
                            .contains(tribe.toLowerCase(Locale.ROOT));
                if (recipe != null && recipe.mechanics != null) {
                    String text = pc.getRules().getOracleText();
                    if (text != null) {
                        String lower = text.toLowerCase(Locale.ROOT);
                        for (String m : recipe.mechanics) {
                            if (m != null && !m.isEmpty() && lower.contains(m.toLowerCase(Locale.ROOT))) {
                                synergy = true;
                                break;
                            }
                        }
                    }
                }
                if (!tribal && !synergy && tribe != null && !tribe.isEmpty())
                    continue;
                if (seen.add(pc.getName()))
                    out.add(pc);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: buildWindowPool failed", e);
        }
        return out;
    }

    private static boolean isStandardWindowLegal(PaperCard pc, StandardWindow window) {
        if (pc == null)
            return false;
        if (pc.getRules().getType().isBasicLand())
            return true;
        if (window == null || !window.isActive())
            return true;
        return window.isStandardLegal(pc.getName());
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
        Deck deck = DeckgenUtil.generateRandomCommanderDeck(commander, DeckFormat.Commander, true, false);
        if (deck == null)
            deck = new Deck(theme.id + " Commander");
        deck.setName(theme.id + " Commander");
        // Ensure commander section is set.
        if (deck.getCommanders().isEmpty()) {
            deck.getOrCreate(DeckSection.Commander).add(commander);
        }
        return deck;
    }

    private static PaperCard pickCommander(EnemyThemeData theme, long seed) {
        if (theme.preferredCommanders != null) {
            for (String name : theme.preferredCommanders) {
                PaperCard pc = cardByName(name);
                if (pc != null && DeckFormat.Commander.isLegalCommander(pc.getRules()))
                    return pc;
            }
        }
        String tribe = firstCreatureType(theme);
        byte allowed = colorMask(theme.colors);
        List<PaperCard> candidates = new ArrayList<>();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (pc == null || pc.getRules() == null)
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
            // Relax tribe requirement; keep colors.
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
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
        // Self-contained builder (no Current.world / adventure Config) so the
        // EN1 generator and legality tests can run headless.
        Deck deck = new Deck(theme.id + " " + format);
        CardPool main = deck.getOrCreate(DeckSection.Main);
        String tribe = firstCreatureType(theme);
        String[] colors = theme.colors != null ? theme.colors : new String[]{"blue"};
        byte allowed = colorMask(colors);
        GameFormat forgeFormat = forgeFormatFor(format);
        Random rng = new Random(seed);

        List<PaperCard> tribal = new ArrayList<>();
        List<PaperCard> synergy = new ArrayList<>();
        try {
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
                if (pc == null || pc.getRules() == null)
                    continue;
                if (pc.getRules().getType().isBasicLand() || pc.getRules().getType().isLand())
                    continue;
                if (!cardLegalInFixedFormat(pc, format, forgeFormat))
                    continue;
                if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                        && !pc.getRules().getColorIdentity().isColorless())
                    continue;
                if (tribe != null && pc.getRules().getType().hasSubtype(tribe))
                    tribal.add(pc);
                else if (tribe != null && pc.getRules().getOracleText() != null
                        && pc.getRules().getOracleText().toLowerCase(Locale.ROOT)
                        .contains(tribe.toLowerCase(Locale.ROOT)))
                    synergy.add(pc);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "EN1: constructed pool build failed", e);
        }

        Collections.shuffle(tribal, rng);
        Collections.shuffle(synergy, rng);
        int spellTarget = Math.max(1, Math.round(target * 0.6f));
        Set<String> used = new HashSet<>();
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
            int copies = 1 + rng.nextInt(3);
            for (int i = 0; i < copies && main.countAll() < spellTarget; i++)
                main.add(pc);
        }
        padWithBasics(deck, target, colors);
        return sanitizeConstructed(deck, format, target, colors);
    }

    private static Deck sanitizeConstructed(Deck deck, String format, int target, String[] colors) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        GameFormat forgeFormat = forgeFormatFor(format);
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
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
        // Cap at 4 copies.
        Map<String, Integer> counts = new HashMap<>();
        for (PaperCard pc : keep) {
            int n = counts.getOrDefault(pc.getName(), 0);
            if (!pc.getRules().getType().isBasicLand() && n >= 4)
                continue;
            main.add(pc);
            counts.put(pc.getName(), n + 1);
        }
        padWithBasics(deck, target, colors);
        // Trim if over target.
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
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validates a fixed deck against Forge format legality. Returns null if OK,
     * otherwise a problem description.
     */
    public static String legalityProblem(Deck deck, String format) {
        if (deck == null)
            return "deck is null";
        String fmt = normalizeFormat(format);
        try {
            if (FORMAT_COMMANDER.equals(fmt)) {
                String problem = DeckFormat.Commander.getDeckConformanceProblem(deck);
                if (problem != null)
                    return problem;
                if (deck.getMain().countAll() != 99 && deck.getMain().countAll() != 100) {
                    // Forge counts main without commander as 99; accept either layout.
                    int total = deck.getMain().countAll() + deck.getCommanders().size();
                    if (total != 100)
                        return "Commander deck should total 100 cards (main+commander), has " + total;
                }
                return null;
            }
            GameFormat gf = forgeFormatFor(fmt);
            if (gf != null && !gf.isDeckLegal(deck)) {
                return "illegal in Forge " + fmt;
            }
            // Also enforce constructed size and 4-of for our adventure lists.
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
                if (!cardLegalInFixedFormat(pc, fmt, gf))
                    return pc.getName() + " is not " + fmt + "-legal";
            }
            return null;
        } catch (Exception e) {
            return "legality check failed: " + e.getMessage();
        }
    }
}
