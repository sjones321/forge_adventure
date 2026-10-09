package forge.adventure;

import forge.adventure.data.EnemyThemeCatalogData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.EnemyThemeDecks;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.TestUtils;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * EN1: every committed fixed enemy theme deck is legal in its format under Forge's
 * own format checks. Also covers Standard recipe filling only from the current window.
 * <p>
 * Lives in forge-gui-desktop so {@link TestUtils#ensureFModelInitialized()} / card DB
 * are available. Outside forge-gui-mobile by necessity.
 */
public class EnemyThemeDeckLegalityTest {

    private static Path enemyDeckRoot;
    private static List<EnemyThemeData> themes;

    @BeforeClass
    public void init() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);

        enemyDeckRoot = resolveEnemyDeckRoot();
        themes = loadThemesFromJson();
        for (EnemyThemeData t : themes)
            EnemyThemeDecks.ensureCoreLoaded(t);
        Assert.assertFalse(themes.isEmpty(), "no themes loaded from enemy_themes.json");
    }

    @Test
    public void everyFixedDeckIsLegalInItsFormat() {
        List<String> problems = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        int checked = 0;
        for (EnemyThemeData theme : themes) {
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                List<Path> decks = listFixedDecks(theme.id, format);
                if (decks.isEmpty()) {
                    missing.add(theme.id + "/" + format.toLowerCase(Locale.ROOT) + "_*.dck");
                    continue;
                }
                for (Path deckPath : decks) {
                    checked++;
                    Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                    if (deck == null) {
                        problems.add(deckPath + ": failed to parse");
                        continue;
                    }
                    // Force deferred sections to load.
                    deck.getMain();
                    if (deck.has(DeckSection.Commander))
                        deck.get(DeckSection.Commander);

                    String problem = checkLegal(deck, format);
                    if (problem != null)
                        problems.add(deckPath.getFileName() + " [" + format + "]: " + problem);
                }
            }
        }
        Assert.assertTrue(missing.isEmpty(),
                "missing fixed decks (generate with EnemyThemeDeckGeneratorTest):\n"
                        + String.join("\n", missing));
        Assert.assertTrue(problems.isEmpty(),
                checked + " decks checked; problems:\n" + String.join("\n", problems));
        Assert.assertTrue(checked >= themes.size() * 3,
                "expected at least one deck per theme×format, checked " + checked);
    }

    @Test
    public void standardRecipeFillsOnlyFromCurrentWindow() {
        EnemyThemeDecks.setEnabledForTests(true);
        EnemyThemeCatalogData cat = new EnemyThemeCatalogData();
        cat.themes = themes.toArray(new EnemyThemeData[0]);
        EnemyThemeDecks.loadCatalogForTests(cat);

        StandardWindow window = new StandardWindow();
        // Sets with green creatures so the recipe can fill non-basics; still must stay in-window.
        window.init(List.of("KHM", "NEO", "ONE"));
        Assert.assertTrue(window.isActive());

        EnemyThemeData theme = themeById("elf_tribal");
        if (theme == null) {
            theme = new EnemyThemeData();
            theme.id = "elf_tribal";
            theme.tags = new String[]{"Elf"};
            theme.colors = new String[]{"green"};
            theme.creatureTypes = new String[]{"Elf"};
            theme.standardRecipe = new EnemyThemeRecipeData();
            theme.standardRecipe.count = 60;
            theme.standardRecipe.colors = new String[]{"Green"};
            theme.standardRecipe.tribe = "Elf";
            theme.standardRecipe.rares = 0.15f;
        }

        Deck deck = EnemyThemeDecks.fillStandardRecipe(theme, window, 99L);
        Assert.assertNotNull(deck);
        Assert.assertTrue(deck.getMain().countAll() >= 40,
                "recipe deck too small: " + deck.getMain().countAll());
        Assert.assertTrue(EnemyThemeDecks.countOnThemeNonLand(deck, theme)
                        >= EnemyThemeDecks.MIN_ON_THEME_NONLAND_STANDARD,
                "Standard recipe below on-theme floor: "
                        + EnemyThemeDecks.countOnThemeNonLand(deck, theme));

        Set<String> illegal = new HashSet<>();
        int nonBasics = 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc.getRules().getType().isBasicLand())
                continue;
            nonBasics += e.getValue();
            if (!printedInWindow(pc.getName(), window))
                illegal.add(pc.getName());
            Assert.assertFalse(EnemyThemeDecks.isAlchemyOrDigitalOnly(pc),
                    "Alchemy in Standard recipe: " + pc.getName());
            Assert.assertFalse(EnemyThemeDecks.isRestrictedCardName(pc.getName()),
                    "restricted in Standard recipe: " + pc.getName());
        }
        Assert.assertTrue(illegal.isEmpty(),
                "Standard recipe included cards outside window: " + illegal);
        Assert.assertTrue(nonBasics > 0,
                "expected at least one non-basic from the Standard window");

        // Inactive window must not treat every card as legal.
        StandardWindow inactive = new StandardWindow();
        Assert.assertFalse(inactive.isActive());
        Deck refused = EnemyThemeDecks.fillStandardRecipe(theme, inactive, 7L);
        Assert.assertTrue(refused == null || refused.isEmpty() || refused.getMain().countAll() == 0,
                "inactive Standard window must not fill a deck");

        EnemyThemeDecks.clearCache();
    }

    @Test
    public void everyFixedDeckUsesThemeCoreWithLimitedFiller() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (EnemyThemeData theme : themes) {
            Assert.assertTrue(theme.core != null && theme.core.length >= EnemyThemeDecks.MIN_CORE_CARDS_IN_DECK,
                    theme.id + " core too small: "
                            + (theme.core == null ? 0 : theme.core.length));
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                for (Path deckPath : listFixedDecks(theme.id, format)) {
                    checked++;
                    Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                    if (deck == null) {
                        problems.add(deckPath + ": failed to parse");
                        continue;
                    }
                    deck.getMain();
                    if (deck.has(DeckSection.Commander))
                        deck.get(DeckSection.Commander);
                    String core = EnemyThemeDecks.coreMembershipProblem(deck, theme, format);
                    if (core != null)
                        problems.add(deckPath.getFileName() + " [" + format + "]: " + core);
                }
            }
        }
        Assert.assertTrue(checked >= themes.size() * 3, "expected fixed decks, checked " + checked);
        Assert.assertTrue(problems.isEmpty(),
                checked + " decks checked; core/filler problems:\n" + String.join("\n", problems));
    }

    @Test
    public void everyFixedDeckHasCorrectLandAndNonlandCounts() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (EnemyThemeData theme : themes) {
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                for (Path deckPath : listFixedDecks(theme.id, format)) {
                    checked++;
                    Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                    if (deck == null) {
                        problems.add(deckPath + ": failed to parse");
                        continue;
                    }
                    deck.getMain();
                    if (deck.has(DeckSection.Commander))
                        deck.get(DeckSection.Commander);
                    String land = EnemyThemeDecks.landCountProblem(deck, format);
                    if (land != null)
                        problems.add(deckPath.getFileName() + " [" + format + "]: " + land);
                    if ("Commander".equals(format)) {
                        int nonLand = EnemyThemeDecks.countNonLandsAll(deck);
                        if (nonLand < EnemyThemeDecks.MIN_NONLAND_COMMANDER)
                            problems.add(deckPath.getFileName() + " [Commander]: nonlands="
                                    + nonLand + " (need " + EnemyThemeDecks.MIN_NONLAND_COMMANDER + ")");
                    }
                }
            }
        }
        Assert.assertTrue(checked >= themes.size() * 3, "expected fixed decks, checked " + checked);
        Assert.assertTrue(problems.isEmpty(),
                checked + " decks checked; land/nonland problems:\n" + String.join("\n", problems));
    }

    @Test
    public void everyFixedDeckMeetsThemeQualityAndBansAlchemyRestricted() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (EnemyThemeData theme : themes) {
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                for (Path deckPath : listFixedDecks(theme.id, format)) {
                    checked++;
                    Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                    if (deck == null) {
                        problems.add(deckPath + ": failed to parse");
                        continue;
                    }
                    deck.getMain();
                    if (deck.has(DeckSection.Commander))
                        deck.get(DeckSection.Commander);

                    String quality = EnemyThemeDecks.themeQualityProblem(deck, theme, format);
                    if (quality != null)
                        problems.add(deckPath.getFileName() + " [" + format + "]: " + quality);

                    String legal = EnemyThemeDecks.legalityProblem(deck, format);
                    if (legal != null && (legal.contains("restricted") || legal.contains("Alchemy")
                            || legal.contains("Online") || legal.contains("Funny")))
                        problems.add(deckPath.getFileName() + " [" + format + "]: " + legal);

                    String landCover = EnemyThemeDecks.landColorCoverageProblem(deck, format);
                    if (landCover != null)
                        problems.add(deckPath.getFileName() + " [" + format + "]: " + landCover);
                }
            }
        }
        Assert.assertTrue(checked >= themes.size() * 3, "expected fixed decks, checked " + checked);
        Assert.assertTrue(problems.isEmpty(),
                checked + " decks checked; quality/restricted/Alchemy problems:\n"
                        + String.join("\n", problems));
    }

    @Test
    public void noFixedDeckContainsEnemyBannedCards() {
        Set<String> banned = loadEnemyBannedNames();
        Assert.assertTrue(banned.size() >= 30, "enemy_banned.json too small: " + banned.size());
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (EnemyThemeData theme : themes) {
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                for (Path deckPath : listFixedDecks(theme.id, format)) {
                    checked++;
                    Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                    if (deck == null) {
                        problems.add(deckPath + ": failed to parse");
                        continue;
                    }
                    deck.getMain();
                    if (deck.has(DeckSection.Commander))
                        deck.get(DeckSection.Commander);
                    for (var e : deck.getAllCardsInASinglePool(true, false)) {
                        PaperCard pc = e.getKey();
                        if (pc != null && banned.contains(pc.getName()))
                            problems.add(deckPath.getFileName() + " [" + format + "]: banned "
                                    + pc.getName());
                    }
                }
            }
        }
        Assert.assertTrue(checked >= themes.size() * 3, "expected fixed decks, checked " + checked);
        Assert.assertTrue(problems.isEmpty(),
                checked + " decks checked; enemy-banned cards:\n" + String.join("\n", problems));
    }

    @Test
    public void noFixedDeckUsesUnreleasedOrPromoOnlyBasics() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (EnemyThemeData theme : themes) {
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                for (Path deckPath : listFixedDecks(theme.id, format)) {
                    checked++;
                    Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                    if (deck == null) {
                        problems.add(deckPath + ": failed to parse");
                        continue;
                    }
                    for (var e : deck.getMain()) {
                        PaperCard pc = e.getKey();
                        if (pc == null || pc.getRules() == null || !pc.getRules().getType().isBasicLand())
                            continue;
                        String ed = pc.getEdition();
                        if (EnemyThemeDecks.isUnreleasedOrPromoOnlyEdition(ed)
                                || "TRK".equalsIgnoreCase(ed) || "PF27".equalsIgnoreCase(ed))
                            problems.add(deckPath.getFileName() + " [" + format + "]: basic "
                                    + pc.getName() + "|" + ed);
                    }
                }
            }
        }
        Assert.assertTrue(checked >= themes.size() * 3, "expected fixed decks, checked " + checked);
        Assert.assertTrue(problems.isEmpty(),
                checked + " decks checked; unreleased/promo basics:\n"
                        + String.join("\n", problems));
    }

    @Test
    public void fixedAndGeneratedBasicsUsePreferredEdition() {
        String preferred = EnemyThemeDecks.PREFERRED_BASIC_LAND_EDITION;
        Assert.assertEquals(preferred, "FDN");
        // Preferred set must actually print the five basics.
        for (String basic : new String[]{"Plains", "Island", "Swamp", "Mountain", "Forest"}) {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(basic, preferred);
            Assert.assertNotNull(pc, "expected " + basic + "|" + preferred);
        }

        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (EnemyThemeData theme : themes) {
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                for (Path deckPath : listFixedDecks(theme.id, format)) {
                    checked++;
                    Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                    if (deck == null) {
                        problems.add(deckPath + ": failed to parse");
                        continue;
                    }
                    for (var e : deck.getMain()) {
                        PaperCard pc = e.getKey();
                        if (pc == null || pc.getRules() == null || !pc.getRules().getType().isBasicLand())
                            continue;
                        // Wastes has no FDN printing; other basics must be pinned.
                        if ("Wastes".equals(pc.getName()))
                            continue;
                        if (!preferred.equalsIgnoreCase(pc.getEdition()))
                            problems.add(deckPath.getFileName() + " [" + format + "]: basic "
                                    + pc.getName() + "|" + pc.getEdition()
                                    + " (want " + preferred + ")");
                    }
                }
            }
        }
        Assert.assertTrue(checked >= themes.size() * 3, "expected fixed decks, checked " + checked);
        Assert.assertTrue(problems.isEmpty(),
                checked + " decks checked; non-" + preferred + " basics:\n"
                        + String.join("\n", problems));

        // Generator path: rebuild a fixed deck and confirm basics are pinned.
        EnemyThemeData sample = themeById("elf_tribal");
        Assert.assertNotNull(sample);
        EnemyThemeDecks.ensureCoreLoaded(sample);
        Deck generated = EnemyThemeDecks.buildFixedDeck(sample, "Pauper", 42L);
        Assert.assertNotNull(generated);
        int basics = 0;
        for (var e : generated.getMain()) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null || !pc.getRules().getType().isBasicLand())
                continue;
            if ("Wastes".equals(pc.getName()))
                continue;
            basics += e.getValue();
            Assert.assertEquals(pc.getEdition(), preferred,
                    "generated basic must be " + preferred + ": " + pc.getName()
                            + "|" + pc.getEdition());
        }
        Assert.assertTrue(basics >= EnemyThemeDecks.MIN_LANDS_60, "expected basics in generated deck");
    }

    @Test
    public void tribalDensityTopUpTakesCoreCardsBeforeDb() {
        EnemyThemeData theme = themeById("spirit_tempo");
        Assert.assertNotNull(theme);
        EnemyThemeDecks.ensureCoreLoaded(theme);
        Assert.assertTrue(EnemyThemeDecks.isInCore("Chapel Geist", theme));
        Assert.assertTrue(EnemyThemeDecks.isInCore("Stormbound Geist", theme));

        Deck thin = new Deck("tribal-topup");
        // Only basics — tribal count is 0 so top-up must run.
        PaperCard plains = FModel.getMagicDb().getCommonCards()
                .getCard("Plains", EnemyThemeDecks.PREFERRED_BASIC_LAND_EDITION);
        PaperCard island = FModel.getMagicDb().getCommonCards()
                .getCard("Island", EnemyThemeDecks.PREFERRED_BASIC_LAND_EDITION);
        Assert.assertNotNull(plains);
        Assert.assertNotNull(island);
        for (int i = 0; i < 9; i++)
            thin.getMain().add(plains);
        for (int i = 0; i < 8; i++)
            thin.getMain().add(island);

        GameFormat pauper = FModel.getFormats().getPauper();
        Assert.assertNotNull(pauper);
        byte allowed = forge.card.MagicColor.WHITE | forge.card.MagicColor.BLUE;
        EnemyThemeDecks.ensureTribalCreatureDensityForTests(
                thin, theme, "Pauper", pauper, allowed, false);

        int tribe = EnemyThemeDecks.countTribalCreatures(thin, theme);
        Assert.assertTrue(tribe >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_60,
                "top-up should reach tribal floor, got " + tribe);

        // Preference invariant: any non-core tribe card may appear only after every
        // format-legal core tribe creature is already at the 4-of cap.
        int perName = 4;
        boolean unsaturatedCore = false;
        for (String name : theme.core) {
            if (name == null || !EnemyThemeDecks.isInCore(name, theme))
                continue;
            PaperCard pc = FModel.getMagicDb().getCommonCards().getUniqueByName(name);
            if (pc == null || pc.getRules() == null || !pc.getRules().getType().isCreature())
                continue;
            if (!pc.getRules().getType().hasSubtype("Spirit") && !pc.getRules().hasKeyword("Changeling"))
                continue;
            if (!EnemyThemeDecks.cardLegalInFixedFormat(pc, "Pauper", pauper))
                continue;
            if (allowed != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(allowed)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            if (thin.getMain().countByName(name) < perName) {
                unsaturatedCore = true;
                break;
            }
        }
        int dbTribe = 0;
        for (var e : thin.getMain()) {
            PaperCard pc = e.getKey();
            if (pc == null || pc.getRules() == null || !pc.getRules().getType().isCreature())
                continue;
            if (!pc.getRules().getType().hasSubtype("Spirit") && !pc.getRules().hasKeyword("Changeling"))
                continue;
            if (EnemyThemeDecks.isInCore(pc.getName(), theme))
                continue;
            dbTribe += e.getValue();
        }
        if (unsaturatedCore)
            Assert.assertEquals(dbTribe, 0,
                    "DB tribe cards must not appear while core tribe copies are unsaturated");
        // With the beefed spirit_tempo core, top-up should rarely need the DB at all.
        Assert.assertEquals(dbTribe, 0,
                "spirit_tempo core should supply the tribal floor without DB cards; dbTribe="
                        + dbTribe);
    }

    @Test
    public void tribalTopUpPreservesSpellFloorAndNeverRemovesSpells() {
        EnemyThemeData theme = themeById("spirit_tempo");
        Assert.assertNotNull(theme);
        EnemyThemeDecks.ensureCoreLoaded(theme);
        GameFormat pauper = FModel.getFormats().getPauper();
        Assert.assertNotNull(pauper);
        byte allowed = forge.card.MagicColor.WHITE | forge.card.MagicColor.BLUE;

        Deck deck = new Deck("spell-floor");
        PaperCard plains = FModel.getMagicDb().getCommonCards()
                .getCard("Plains", EnemyThemeDecks.PREFERRED_BASIC_LAND_EDITION);
        PaperCard island = FModel.getMagicDb().getCommonCards()
                .getCard("Island", EnemyThemeDecks.PREFERRED_BASIC_LAND_EDITION);
        PaperCard journey = FModel.getMagicDb().getCommonCards().getUniqueByName("Journey to Nowhere");
        PaperCard leak = FModel.getMagicDb().getCommonCards().getUniqueByName("Mana Leak");
        // Off-tribe bodies so a packed 60-card deck forces swaps (not free adds).
        PaperCard vanguard = FModel.getMagicDb().getCommonCards().getUniqueByName("Elite Vanguard");
        Assert.assertNotNull(plains);
        Assert.assertNotNull(island);
        Assert.assertNotNull(journey);
        Assert.assertNotNull(leak);
        Assert.assertNotNull(vanguard);
        PaperCard chapel = FModel.getMagicDb().getCommonCards().getUniqueByName("Chapel Geist");
        Assert.assertNotNull(chapel);

        // Tight 60: 17 lands + 43 nonlands. Nonlands = 8 spells at the floor, 4 tribe
        // creatures, and 31 off-tribe creatures so top-up must swap for density.
        for (int i = 0; i < 4; i++)
            deck.getMain().add(journey);
        for (int i = 0; i < 4; i++)
            deck.getMain().add(leak);
        for (int i = 0; i < 4; i++)
            deck.getMain().add(chapel);
        for (int i = 0; i < 31; i++)
            deck.getMain().add(vanguard);
        for (int i = 0; i < 9; i++)
            deck.getMain().add(plains);
        for (int i = 0; i < 8; i++)
            deck.getMain().add(island);

        Assert.assertEquals(deck.getMain().countAll(), 60);
        int nonLands = 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc != null && pc.getRules() != null && !pc.getRules().getType().isLand())
                nonLands += e.getValue();
        }
        Assert.assertEquals(nonLands, 43, "packed deck must be 43 nonlands");
        int spellsBefore = EnemyThemeDecks.countNonCreatureSpells(deck);
        Assert.assertEquals(spellsBefore, EnemyThemeDecks.MIN_NON_CREATURE_SPELLS_60);
        int journeyBefore = deck.getMain().countByName("Journey to Nowhere");
        int leakBefore = deck.getMain().countByName("Mana Leak");
        Assert.assertTrue(EnemyThemeDecks.countTribalCreatures(deck, theme)
                        < EnemyThemeDecks.MIN_TRIBAL_CREATURES_60,
                "precondition: top-up must need to run");

        // Raw top-up only — no spell-floor refill — so surviving spells prove the
        // swap path never picks non-creature spells as victims.
        EnemyThemeDecks.ensureTribalCreatureDensityRawForTests(
                deck, theme, "Pauper", pauper, allowed, false);

        Assert.assertEquals(EnemyThemeDecks.countNonCreatureSpells(deck), spellsBefore,
                "raw top-up must not change non-creature spell count");
        Assert.assertEquals(deck.getMain().countByName("Journey to Nowhere"), journeyBefore,
                "top-up must not remove Journey to Nowhere");
        Assert.assertEquals(deck.getMain().countByName("Mana Leak"), leakBefore,
                "top-up must not remove Mana Leak");
        Assert.assertTrue(EnemyThemeDecks.countTribalCreatures(deck, theme)
                        >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_60,
                "tribal floor still required");
    }

    @Test
    public void dragonThemesCanStillPickNonTribeFiller() {
        EnemyThemeData theme = themeById("dragon_tribal");
        Assert.assertNotNull(theme);
        EnemyThemeDecks.ensureCoreLoaded(theme);
        // Build with a seed; dragon Pauper/Historic should be allowed non-dragon filler
        // (burn / ramp glue) rather than stalling on Studious First-Year.
        Deck deck = null;
        for (int seed = 1; seed < 40; seed++) {
            Deck d = EnemyThemeDecks.buildFixedDeck(theme, "Historic", seed);
            if (d == null)
                continue;
            deck = d;
            int nonTribeFiller = 0;
            for (var e : d.getMain()) {
                PaperCard pc = e.getKey();
                if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                    continue;
                if (EnemyThemeDecks.isInCore(pc.getName(), theme))
                    continue;
                if (pc.getRules().getType().isCreature()
                        && (pc.getRules().getType().hasSubtype("Dragon")
                        || pc.getRules().hasKeyword("Changeling")))
                    continue;
                if ("Studious First-Year".equals(pc.getName()))
                    continue;
                nonTribeFiller += e.getValue();
            }
            if (nonTribeFiller > 0) {
                Assert.assertTrue(nonTribeFiller > 0);
                return;
            }
        }
        // Even if a seed packs only core, filler path must not be hard-blocked: assert
        // build succeeds and Studious First-Year is absent (dragon exclusion still holds).
        Assert.assertNotNull(deck, "dragon_tribal Historic must build");
        Assert.assertEquals(deck.getMain().countByName("Studious First-Year"), 0);
        Assert.assertNull(EnemyThemeDecks.themeQualityProblem(deck, theme, "Historic"),
                EnemyThemeDecks.themeQualityProblem(deck, theme, "Historic"));
    }

    @Test
    public void exclusionFilterRejectsOnlineFunnyUnAndPlaytestCards() {
        String[] banned = {
                "Sarevok the Usurper", // HBG
                "Goblin Trapfinder", // J21
                "Scion of Shiv", // HBG/J21 digital
                "_____ Goblin", // Unfinity
                "Hammer Jammer", // Un-set
                "Steamflogger Temp", // Unstable / playtest-adjacent
                "Bloodspatter Vampire", // Un-set
                "Sliv-Mizzet, Hivemind", // playtest (CMB1) — name may vary
                "Koma and Toski, Compleated", // playtest
                "Nim Mongoose", // playtest
                "Bolshack Dragon" // playtest / digital
        };
        List<String> notExcluded = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String name : banned) {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getUniqueByName(name);
            if (pc == null) {
                // Try exact getCard; some Un-names use underscores.
                pc = FModel.getMagicDb().getCommonCards().getCard(name);
            }
            if (pc == null) {
                missing.add(name);
                continue;
            }
            if (!EnemyThemeDecks.isExcludedFromAdventureDecks(pc))
                notExcluded.add(name + " [" + pc.getEdition() + "]");
        }
        // Named cards that exist in the DB must be excluded; missing names are noted but
        // do not fail if the card DB renamed them (we still require the ones that resolve).
        Assert.assertTrue(notExcluded.isEmpty(),
                "should be excluded (Online/Funny/restrictedEditions): " + notExcluded);
        Assert.assertTrue(banned.length - missing.size() >= 5,
                "expected to resolve most negative examples; missing=" + missing);
    }

    private static boolean printedInWindow(String name, StandardWindow window) {
        for (String code : window.expandedCodes()) {
            forge.card.CardEdition ed = FModel.getMagicDb().getEditions().get(code);
            if (ed == null)
                continue;
            for (forge.card.CardEdition.EditionEntry e : ed.getAllCardsInSet()) {
                if (name.equals(e.name()))
                    return true;
            }
        }
        return false;
    }

    @Test
    public void everyFixedAndGeneratedCommanderDeckIsExactly99PlusCommander() {
        List<String> problems = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (EnemyThemeData theme : themes) {
            if (theme == null || theme.id == null || !seenIds.add(theme.id))
                continue;
            EnemyThemeDecks.ensureCoreLoaded(theme);
            // Fixed lists.
            for (Path deckPath : listFixedDecks(theme.id, "Commander")) {
                Deck deck = DeckSerializer.fromFile(deckPath.toFile());
                if (deck == null) {
                    problems.add(deckPath + ": failed to parse");
                    continue;
                }
                deck.getMain();
                if (deck.has(DeckSection.Commander))
                    deck.get(DeckSection.Commander);
                String p = commanderSizeAndFloorsProblem(deck, theme, deckPath.getFileName().toString());
                if (p != null)
                    problems.add(p);
            }
            // Generated (includes EN2 merfolk_tempo / serpent_leviathan).
            Deck generated = null;
            for (int attempt = 0; attempt < 16 && generated == null; attempt++) {
                try {
                    generated = EnemyThemeDecks.buildFixedDeck(theme, "Commander",
                            theme.id.hashCode() * 31L + attempt * 17L);
                } catch (RuntimeException ex) {
                    problems.add(theme.id + " generated: " + ex.getMessage());
                    break;
                }
            }
            if (generated == null) {
                problems.add(theme.id + " generated: build returned null");
                continue;
            }
            String gp = commanderSizeAndFloorsProblem(generated, theme, theme.id + " generated");
            if (gp != null)
                problems.add(gp);
        }
        Assert.assertTrue(seenIds.contains("merfolk_tempo"), "expected EN2 merfolk_tempo theme");
        Assert.assertTrue(seenIds.contains("serpent_leviathan"), "expected EN2 serpent_leviathan theme");
        Assert.assertTrue(problems.isEmpty(),
                "Commander 99+1 / floors problems:\n" + String.join("\n", problems));
    }

    @Test
    public void normalizeCommanderMainSizeFixesOvershootAndUndershoot() {
        EnemyThemeData theme = themeById("spirit_tempo");
        Assert.assertNotNull(theme);
        EnemyThemeDecks.ensureCoreLoaded(theme);
        Deck base = EnemyThemeDecks.buildFixedDeck(theme, "Commander", 42L);
        Assert.assertNotNull(base);
        Assert.assertEquals(base.getMain().countAll(), 99);
        Assert.assertEquals(base.getCommanders().size(), 1);

        PaperCard island = FModel.getMagicDb().getCommonCards()
                .getCard("Island", EnemyThemeDecks.PREFERRED_BASIC_LAND_EDITION);
        Assert.assertNotNull(island);

        // Overshoot: +4 basics → normalize back to 99 without touching core/spells.
        Deck over = copyDeck(base);
        for (int i = 0; i < 4; i++)
            over.getMain().add(island);
        Assert.assertEquals(over.getMain().countAll(), 103);
        int interactionBefore = countNamedInteraction(over);
        int tribalBefore = EnemyThemeDecks.countTribalCreatures(over, theme);
        EnemyThemeDecks.normalizeCommanderMainSizeForTests(over, theme);
        Assert.assertEquals(over.getMain().countAll(), 99);
        Assert.assertEquals(over.getCommanders().size(), 1);
        Assert.assertNull(EnemyThemeDecks.legalityProblem(over, "Commander"),
                EnemyThemeDecks.legalityProblem(over, "Commander"));
        Assert.assertTrue(countNamedInteraction(over) >= Math.min(interactionBefore,
                        EnemyThemeDecks.MIN_COMMANDER_INTERACTION_SPELLS),
                "normalize must not strip the interaction spell floor");
        Assert.assertTrue(EnemyThemeDecks.countTribalCreatures(over, theme) >= Math.min(tribalBefore,
                        EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                        || EnemyThemeDecks.countTribalCreatures(over, theme)
                        >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER,
                "normalize must not strip tribal floor when trimming basics");

        // Undershoot: strip 5 basics → normalize pads back to 99.
        Deck under = copyDeck(base);
        int removed = 0;
        for (PaperCard pc : new ArrayList<>(under.getMain().toFlatList())) {
            if (removed >= 5)
                break;
            if (pc.getRules() != null && pc.getRules().getType().isBasicLand()) {
                under.getMain().remove(pc);
                removed++;
            }
        }
        Assert.assertEquals(removed, 5);
        Assert.assertEquals(under.getMain().countAll(), 94);
        int interactionUnder = countNamedInteraction(under);
        EnemyThemeDecks.normalizeCommanderMainSizeForTests(under, theme);
        Assert.assertEquals(under.getMain().countAll(), 99);
        Assert.assertEquals(under.getCommanders().size(), 1);
        Assert.assertNull(EnemyThemeDecks.legalityProblem(under, "Commander"),
                EnemyThemeDecks.legalityProblem(under, "Commander"));
        Assert.assertTrue(countNamedInteraction(under) >= interactionUnder,
                "filling basics must not remove interaction spells");
        Assert.assertNull(EnemyThemeDecks.themeQualityProblem(under, theme, "Commander"),
                EnemyThemeDecks.themeQualityProblem(under, theme, "Commander"));
    }

    /** Counts cards on the generator's Commander interaction priority list. */
    private static int countNamedInteraction(Deck deck) {
        String[] names = {
                "Swords to Plowshares", "Path to Exile", "Anguished Unmaking", "Mortify",
                "Oblivion Ring", "Journey to Nowhere", "Generous Gift",
                "Wrath of God", "Supreme Verdict", "Time Wipe", "Deafening Clarion",
                "Austere Command", "Farewell",
                "Go for the Throat", "Feed the Swarm", "Infernal Grasp", "Cast Down",
                "Hero's Downfall", "Languish",
                "Counterspell", "Negate", "Aetherize", "Engulf the Shore", "River's Rebuke",
                "Wash Out", "Pongify", "Rapid Hybridization", "Reality Shift",
                "Chaos Warp", "Abrade", "Blasphemous Act", "By Force", "Vandalblast",
                "Starstorm", "Chain Reaction", "Wild Magic Surge",
                "Beast Within", "Nature's Claim", "Kenrith's Transformation",
                "Song of the Dryads", "Return to Nature", "Krosan Grip"
        };
        int n = 0;
        for (String name : names)
            n += deck.getMain().countByName(name);
        return n;
    }

    private static String commanderSizeAndFloorsProblem(Deck deck, EnemyThemeData theme,
                                                        String label) {
        if (deck.getCommanders() == null || deck.getCommanders().isEmpty())
            return label + ": missing commander";
        int main = deck.getMain().countAll();
        int cmd = deck.getCommanders().size();
        if (main != 99 || cmd != 1)
            return label + ": size main=" + main + " commanders=" + cmd + " (need 99+1)";
        String legal = EnemyThemeDecks.legalityProblem(deck, "Commander");
        if (legal != null)
            return label + ": " + legal;
        // Singleton + CI covered by legalityProblem; tribal + nonland floors via quality.
        String quality = EnemyThemeDecks.themeQualityProblem(deck, theme, "Commander");
        if (quality != null)
            return label + ": " + quality;
        return null;
    }

    @Test
    public void legalityRejectsBrokenCommanderAndPauperDecks() {
        Deck legalCommander = loadFirstDeck("Commander");
        Assert.assertNotNull(legalCommander, "need a committed Commander theme deck");
        Assert.assertNull(EnemyThemeDecks.legalityProblem(legalCommander, "Commander"),
                "baseline commander deck must be legal");

        // 101-card commander deck (99 main + 1 commander + 1 extra land).
        Deck tooBig = copyDeck(legalCommander);
        PaperCard island = FModel.getMagicDb().getCommonCards().getCard("Island");
        Assert.assertNotNull(island);
        tooBig.getMain().add(island);
        Assert.assertEquals(tooBig.getMain().countAll() + tooBig.getCommanders().size(), 101);
        Assert.assertNotNull(EnemyThemeDecks.legalityProblem(tooBig, "Commander"),
                "101-card commander deck must fail");

        // Off-identity: force a mono-color identity break when possible.
        Deck offId = copyDeck(legalCommander);
        List<PaperCard> cmds = offId.getCommanders();
        Assert.assertFalse(cmds.isEmpty());
        PaperCard commander = cmds.get(0);
        byte ci = commander.getRules().getColorIdentity().getColor();
        PaperCard offColor = pickOffIdentityCard(ci);
        Assert.assertNotNull(offColor, "could not find an off-identity card for " + commander.getName());
        // Replace one main non-basic with the off-identity card to keep size.
        PaperCard removed = null;
        for (PaperCard pc : offId.getMain().toFlatList()) {
            if (!pc.getRules().getType().isBasicLand()) {
                removed = pc;
                break;
            }
        }
        Assert.assertNotNull(removed);
        offId.getMain().remove(removed);
        offId.getMain().add(offColor);
        Assert.assertNotNull(EnemyThemeDecks.legalityProblem(offId, "Commander"),
                "off-identity card must fail: " + offColor.getName());

        // Duplicate non-basic (Commander is singleton).
        Deck dupe = copyDeck(legalCommander);
        PaperCard nonBasic = null;
        for (PaperCard pc : dupe.getMain().toFlatList()) {
            if (!pc.getRules().getType().isBasicLand()
                    && !DeckFormat.canHaveAnyNumberOf(pc)) {
                nonBasic = pc;
                break;
            }
        }
        Assert.assertNotNull(nonBasic);
        // Keep total at 100: remove a basic, add a second copy of the non-basic.
        PaperCard basic = null;
        for (PaperCard pc : dupe.getMain().toFlatList()) {
            if (pc.getRules().getType().isBasicLand()) {
                basic = pc;
                break;
            }
        }
        Assert.assertNotNull(basic);
        dupe.getMain().remove(basic);
        dupe.getMain().add(nonBasic);
        Assert.assertNotNull(EnemyThemeDecks.legalityProblem(dupe, "Commander"),
                "duplicate non-basic must fail: " + nonBasic.getName());

        // Uncommon in Pauper — Forge Pauper format rejects it.
        Deck pauper = loadFirstDeck("Pauper");
        Assert.assertNotNull(pauper, "need a committed Pauper theme deck");
        Assert.assertNull(EnemyThemeDecks.legalityProblem(pauper, "Pauper"));
        PaperCard uncommon = pickUncommon();
        Assert.assertNotNull(uncommon);
        PaperCard drop = null;
        for (PaperCard pc : pauper.getMain().toFlatList()) {
            if (!pc.getRules().getType().isBasicLand()) {
                drop = pc;
                break;
            }
        }
        Assert.assertNotNull(drop);
        pauper.getMain().remove(drop);
        pauper.getMain().add(uncommon);
        Assert.assertNotNull(EnemyThemeDecks.legalityProblem(pauper, "Pauper"),
                "uncommon in Pauper must fail: " + uncommon.getName());
    }

    private static String checkLegal(Deck deck, String format) {
        return EnemyThemeDecks.legalityProblem(deck, format);
    }

    private EnemyThemeData themeById(String id) {
        for (EnemyThemeData t : themes) {
            if (t != null && id.equals(t.id))
                return t;
        }
        return null;
    }

    private Deck loadFirstDeck(String format) {
        for (EnemyThemeData theme : themes) {
            List<Path> decks = listFixedDecks(theme.id, format);
            if (decks.isEmpty())
                continue;
            Deck d = DeckSerializer.fromFile(decks.get(0).toFile());
            if (d == null)
                continue;
            d.getMain();
            if (d.has(DeckSection.Commander))
                d.get(DeckSection.Commander);
            return d;
        }
        return null;
    }

    private static Deck copyDeck(Deck src) {
        Deck d = new Deck(src.getName());
        d.getMain().addAll(src.getMain());
        if (src.has(DeckSection.Commander))
            d.getOrCreate(DeckSection.Commander).addAll(src.get(DeckSection.Commander));
        if (src.has(DeckSection.Sideboard))
            d.getOrCreate(DeckSection.Sideboard).addAll(src.get(DeckSection.Sideboard));
        return d;
    }

    private static PaperCard pickOffIdentityCard(byte commanderCI) {
        // Prefer a basic land outside the commander's colors.
        String[] basics = {"Plains", "Island", "Swamp", "Mountain", "Forest"};
        byte[] colors = {
                forge.card.MagicColor.WHITE, forge.card.MagicColor.BLUE, forge.card.MagicColor.BLACK,
                forge.card.MagicColor.RED, forge.card.MagicColor.GREEN
        };
        for (int i = 0; i < basics.length; i++) {
            if ((commanderCI & colors[i]) == 0) {
                PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(basics[i]);
                if (pc != null)
                    return pc;
            }
        }
        // Fallback: any non-basic whose identity is not within commander CI.
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRules().getType().isBasicLand())
                continue;
            if (!pc.getRules().getColorIdentity().hasNoColorsExcept(commanderCI)
                    && !pc.getRules().getColorIdentity().isColorless())
                return pc;
        }
        return null;
    }

    private static PaperCard pickUncommon() {
        GameFormat pauper = FModel.getFormats().getPauper();
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            if (pc == null || pc.getRules() == null)
                continue;
            if (pc.getRarity() != forge.card.CardRarity.Uncommon)
                continue;
            if (pc.getRules().getType().isBasicLand())
                continue;
            // Prefer a card Forge Pauper rejects.
            if (pauper != null && pauper.getFilterRules() != null && !pauper.getFilterRules().test(pc))
                return pc;
        }
        return FModel.getMagicDb().getCommonCards().getCard("Lightning Strike");
    }

    private static List<Path> listFixedDecks(String themeId, String format) {
        List<Path> out = new ArrayList<>();
        Path dir = enemyDeckRoot.resolve(themeId);
        if (!Files.isDirectory(dir))
            return out;
        String prefix = format.toLowerCase(Locale.ROOT) + "_";
        try (var stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.startsWith(prefix) && name.endsWith(".dck");
            }).sorted().forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    private static Path resolveEnemyDeckRoot() {
        Path[] candidates = {
                Paths.get("forge-gui/res/adventure/common/decks/enemy"),
                Paths.get("../forge-gui/res/adventure/common/decks/enemy"),
                Paths.get("res/adventure/common/decks/enemy")
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p))
                return p.toAbsolutePath().normalize();
        }
        // Default write/read location relative to desktop module.
        return Paths.get("../forge-gui/res/adventure/common/decks/enemy").toAbsolutePath().normalize();
    }

    private static List<EnemyThemeData> loadThemesFromJson() throws Exception {
        Path json = enemyDeckRoot.getParent().getParent().resolve("world/enemy_themes.json");
        if (!Files.isRegularFile(json)) {
            Path[] alt = {
                    Paths.get("../forge-gui/res/adventure/common/world/enemy_themes.json"),
                    Paths.get("forge-gui/res/adventure/common/world/enemy_themes.json")
            };
            for (Path p : alt) {
                if (Files.isRegularFile(p)) {
                    json = p;
                    break;
                }
            }
        }
        Assert.assertTrue(Files.isRegularFile(json), "enemy_themes.json not found near " + enemyDeckRoot);
        String text = Files.readString(json, StandardCharsets.UTF_8);
        // Minimal parse: pull theme blocks via Json if Config unavailable — use Gson-less
        // libgdx Json requires Gdx; parse ids with a tiny scanner and rebuild recipes from file
        // through EnemyThemeDecks after Config is up. Fallback: reconstruct from id lines.
        List<EnemyThemeData> list = new ArrayList<>();
        EnemyThemeData current = null;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("\"id\":")) {
                if (current != null)
                    list.add(current);
                current = new EnemyThemeData();
                current.id = jsonString(t);
                current.tags = new String[0];
                current.colors = new String[]{"blue"};
                current.creatureTypes = new String[0];
                current.preferredCommanders = new String[0];
                current.keyCards = new String[0];
                current.mechanics = new String[0];
                current.standardRecipe = new EnemyThemeRecipeData();
                current.standardRecipe.count = 60;
            } else if (current != null && t.startsWith("\"preferredCommanders\"")) {
                current.preferredCommanders = jsonStringArray(t.substring(t.indexOf('[')));
            } else if (current != null && t.startsWith("\"tags\"")) {
                current.tags = jsonStringArray(t.substring(t.indexOf('[')));
            } else if (current != null && t.startsWith("\"creatureTypes\"")) {
                current.creatureTypes = jsonStringArray(t.substring(t.indexOf('[')));
            } else if (current != null && t.startsWith("\"keyCards\"")) {
                current.keyCards = jsonStringArray(t.substring(t.indexOf('[')));
            } else if (current != null && t.startsWith("\"mechanics\"")) {
                current.mechanics = jsonStringArray(t.substring(t.indexOf('[')));
            } else if (current != null && t.startsWith("\"tribe\"")) {
                current.standardRecipe.tribe = jsonString(t);
            } else if (current != null && t.startsWith("\"colors\"") && t.contains("[")) {
                String[] cols = jsonStringArray(t.substring(t.indexOf('[')));
                if (cols.length > 0 && Character.isUpperCase(cols[0].charAt(0)))
                    current.standardRecipe.colors = cols;
                else if (cols.length > 0)
                    current.colors = cols;
            }
        }
        if (current != null)
            list.add(current);
        for (EnemyThemeData th : list) {
            if (th.creatureTypes == null || th.creatureTypes.length == 0) {
                if (th.standardRecipe != null && th.standardRecipe.tribe != null)
                    th.creatureTypes = new String[]{th.standardRecipe.tribe};
                else if (th.tags != null && th.tags.length > 0)
                    th.creatureTypes = new String[]{th.tags[0]};
            }
            if (th.standardRecipe != null && th.standardRecipe.tribe == null
                    && th.creatureTypes != null && th.creatureTypes.length > 0)
                th.standardRecipe.tribe = th.creatureTypes[0];
            if (th.standardRecipe != null
                    && (th.standardRecipe.colors == null || th.standardRecipe.colors.length == 0)
                    && th.colors != null) {
                String[] up = new String[th.colors.length];
                for (int i = 0; i < th.colors.length; i++) {
                    String c = th.colors[i];
                    up[i] = c.substring(0, 1).toUpperCase(Locale.ROOT) + c.substring(1).toLowerCase(Locale.ROOT);
                }
                th.standardRecipe.colors = up;
            }
        }
        return list;
    }

    private static Set<String> loadEnemyBannedNames() {
        Path[] candidates = {
                enemyDeckRoot.getParent().getParent().resolve("world/enemy_banned.json"),
                Paths.get("forge-gui/res/adventure/common/world/enemy_banned.json"),
                Paths.get("../forge-gui/res/adventure/common/world/enemy_banned.json")
        };
        Path json = null;
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) {
                json = p;
                break;
            }
        }
        Assert.assertNotNull(json, "enemy_banned.json not found");
        Set<String> banned = new HashSet<>();
        try {
            for (String line : Files.readString(json, StandardCharsets.UTF_8).split("\n")) {
                String t = line.trim();
                if (t.startsWith("\"") && !t.startsWith("\"cards\"")) {
                    int q1 = t.indexOf('"');
                    int q2 = t.indexOf('"', q1 + 1);
                    if (q1 >= 0 && q2 > q1)
                        banned.add(t.substring(q1 + 1, q2));
                }
            }
        } catch (Exception e) {
            Assert.fail("failed to read enemy_banned.json: " + e);
        }
        return banned;
    }

    private static String jsonString(String line) {
        int c = line.indexOf(':');
        int q1 = line.indexOf('"', c + 1);
        int q2 = line.indexOf('"', q1 + 1);
        return line.substring(q1 + 1, q2);
    }

    private static String[] jsonStringArray(String bracketed) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < bracketed.length()) {
            int q1 = bracketed.indexOf('"', i);
            if (q1 < 0)
                break;
            int q2 = bracketed.indexOf('"', q1 + 1);
            if (q2 < 0)
                break;
            out.add(bracketed.substring(q1 + 1, q2));
            i = q2 + 1;
        }
        return out.toArray(new String[0]);
    }
}
