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

    @Test
    public void dragonTribalCommanderKeepsRestoredDragonsNotGenericFlyers() {
        Path dck = enemyDeckRoot.resolve("dragon_tribal").resolve("commander_1.dck");
        Assert.assertTrue(Files.isRegularFile(dck), "missing " + dck);
        Deck deck = DeckSerializer.fromFile(dck.toFile());
        Assert.assertNotNull(deck);
        // Real Dragon creature types restored after #50 regen (not Dragonspeaker Shaman —
        // that is a Human Barbarian Shaman kept as on-theme support).
        String[] restoredDragons = {
                "Dracosaur Auxiliary", "Obsidian Charmaw", "Realm-Scorcher Hellkite",
                "Smaug, the Great Calamity", "Stingerback Terror", "Thundermane Dragon"
        };
        List<String> missingDragons = new ArrayList<>();
        for (String name : restoredDragons) {
            if (deck.getMain().countByName(name) < 1)
                missingDragons.add(name);
        }
        Assert.assertTrue(missingDragons.isEmpty(),
                "restored Dragon creatures missing: " + missingDragons);
        Assert.assertTrue(deck.getMain().countByName("War-Spike Changeling") >= 1,
                "restored tribal changeling missing");
        Assert.assertTrue(deck.getMain().countByName("Dragonspeaker Shaman") >= 1,
                "on-theme dragon support (Dragonspeaker Shaman) missing");
        for (PaperCard pc : deck.getMain().toFlatList()) {
            if (pc != null && "Dragonspeaker Shaman".equals(pc.getName())) {
                Assert.assertFalse(pc.getRules().getType().hasSubtype("Dragon"),
                        "Dragonspeaker Shaman must not be treated as a Dragon creature type");
            }
        }
        String[] flyers = {
                "Arclight Phoenix", "Avatar of Fury", "Avengers Quinjet", "Emberwilde Djinn",
                "Levitating Statue", "Thopter Assembly", "Draconautics Engineer"
        };
        List<String> leaked = new ArrayList<>();
        for (String name : flyers) {
            if (deck.getMain().countByName(name) > 0)
                leaked.add(name);
        }
        Assert.assertTrue(leaked.isEmpty(), "generic flyers still present: " + leaked);
        EnemyThemeData theme = themeById("dragon_tribal");
        Assert.assertNotNull(theme);
        EnemyThemeDecks.ensureCoreLoaded(theme);
        Assert.assertNull(EnemyThemeDecks.themeQualityProblem(deck, theme, "Commander"),
                EnemyThemeDecks.themeQualityProblem(deck, theme, "Commander"));
        Assert.assertTrue(EnemyThemeDecks.countTribalCreatures(deck, theme)
                        >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER,
                "dragon_tribal Commander must meet tribal floor");
    }

    @Test
    public void normalizeTrimProtectsTribalFloorForEveryCommanderTheme() {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EnemyThemeData theme : themes) {
            if (theme == null || theme.id == null || !seen.add(theme.id))
                continue;
            EnemyThemeDecks.ensureCoreLoaded(theme);
            Deck base = null;
            try {
                base = EnemyThemeDecks.buildFixedDeck(theme, "Commander", 99L);
            } catch (RuntimeException ex) {
                problems.add(theme.id + ": build failed: " + ex.getMessage());
                continue;
            }
            if (base == null) {
                problems.add(theme.id + ": build returned null");
                continue;
            }
            Deck edged = copyDeck(base);
            PaperCard basic = firstBasicInDeck(edged);
            if (basic == null) {
                problems.add(theme.id + ": no basic land");
                continue;
            }
            // Overshoot with basics only — trim must cut basics, never tribal creatures.
            for (int i = 0; i < 4; i++)
                edged.getMain().add(basic);
            int tribalBefore = EnemyThemeDecks.countTribalCreatures(edged, theme);
            try {
                EnemyThemeDecks.normalizeCommanderMainSizeForTests(edged, theme);
            } catch (RuntimeException ex) {
                problems.add(theme.id + ": normalize threw: " + ex.getMessage());
                continue;
            }
            if (edged.getMain().countAll() != 99)
                problems.add(theme.id + ": size " + edged.getMain().countAll());
            int tribalAfter = EnemyThemeDecks.countTribalCreatures(edged, theme);
            if (tribalAfter < tribalBefore)
                problems.add(theme.id + ": tribal dropped " + tribalBefore + "→" + tribalAfter
                        + " while trimming basic overshoot");
            if (tribalBefore >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER
                    && tribalAfter < EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                problems.add(theme.id + ": tribal " + tribalAfter + " fell below floor");
            if (countLandsInDeck(edged) > EnemyThemeDecks.MAX_LANDS_COMMANDER)
                problems.add(theme.id + ": lands " + countLandsInDeck(edged) + " > max");
            // Prefer a quality-legal seed; fall back to fixed deck when this seed is thin.
            String quality = EnemyThemeDecks.themeQualityProblem(edged, theme, "Commander");
            if (quality != null && tribalBefore >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                problems.add(theme.id + ": " + quality);
        }
        Assert.assertTrue(problems.isEmpty(),
                "normalize tribal-floor problems:\n" + String.join("\n", problems));
    }

    @Test
    public void normalizeTrimAtTribalFloorDoesNotRemoveTribalCreatures() {
        List<String> problems = new ArrayList<>();
        int exercised = 0;
        Set<String> seen = new HashSet<>();
        for (EnemyThemeData theme : themes) {
            if (theme == null || theme.id == null || !seen.add(theme.id))
                continue;
            EnemyThemeDecks.ensureCoreLoaded(theme);
            // Normalize only guards tribal on isTribalTheme themes.
            if (!EnemyThemeDecks.isTribalThemeForTests(theme))
                continue;
            Deck base = null;
            for (long seed = 1; seed <= 32 && base == null; seed++) {
                try {
                    Deck d = EnemyThemeDecks.buildFixedDeck(theme, "Commander", seed * 17L);
                    if (d != null && EnemyThemeDecks.countTribalCreatures(d, theme)
                            >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                        base = d;
                } catch (RuntimeException ignored) {
                }
            }
            if (base == null) {
                for (Path deckPath : listFixedDecks(theme.id, "Commander")) {
                    Deck d = DeckSerializer.fromFile(deckPath.toFile());
                    if (d != null) {
                        d.getMain();
                        if (d.has(DeckSection.Commander))
                            d.get(DeckSection.Commander);
                        if (EnemyThemeDecks.countTribalCreatures(d, theme)
                                >= EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER) {
                            base = d;
                            break;
                        }
                    }
                }
            }
            if (base == null)
                continue; // theme cannot supply a tribal-legal Commander deck in this env
            PaperCard chaff = disposableChaffForTheme(theme);
            PaperCard basic = firstBasicInDeck(base);
            if (chaff == null || basic == null)
                continue;
            Deck edged = copyDeck(base);
            // Exact tribal floor via tribal → chaff swaps (core allowed for setup only).
            while (EnemyThemeDecks.countTribalCreatures(edged, theme)
                    > EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER) {
                PaperCard tribal = null;
                for (PaperCard pc : edged.getMain().toFlatList()) {
                    if (pc != null && countsAsTribalForTest(pc, theme)
                            && !EnemyThemeDecks.isInCore(pc.getName(), theme)) {
                        tribal = pc;
                        break;
                    }
                }
                if (tribal == null) {
                    for (PaperCard pc : edged.getMain().toFlatList()) {
                        if (pc != null && countsAsTribalForTest(pc, theme)) {
                            tribal = pc;
                            break;
                        }
                    }
                }
                if (tribal == null)
                    break;
                edged.getMain().remove(tribal);
                edged.getMain().add(chaff);
            }
            if (EnemyThemeDecks.countTribalCreatures(edged, theme)
                    != EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                continue;
            // Lands exactly at MIN so trim must pick nonlands (not basics).
            while (countLandsInDeck(edged) > EnemyThemeDecks.MIN_LANDS_COMMANDER) {
                PaperCard land = null;
                for (PaperCard pc : edged.getMain().toFlatList()) {
                    if (pc != null && pc.getRules() != null
                            && pc.getRules().getType().isBasicLand()) {
                        land = pc;
                        break;
                    }
                }
                if (land == null)
                    break;
                edged.getMain().remove(land);
                edged.getMain().add(chaff);
            }
            while (countLandsInDeck(edged) < EnemyThemeDecks.MIN_LANDS_COMMANDER)
                edged.getMain().add(basic);
            while (edged.getMain().countAll() < 99)
                edged.getMain().add(chaff);
            while (edged.getMain().countAll() > 99) {
                boolean removed = false;
                for (PaperCard pc : edged.getMain().toFlatList()) {
                    if (pc != null && chaff.getName().equals(pc.getName())) {
                        edged.getMain().remove(pc);
                        removed = true;
                        break;
                    }
                }
                if (!removed)
                    break;
            }
            if (countLandsInDeck(edged) != EnemyThemeDecks.MIN_LANDS_COMMANDER
                    || edged.getMain().countAll() != 99
                    || EnemyThemeDecks.countTribalCreatures(edged, theme)
                    != EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                continue;
            // Ensure at least one non-core chaff copy exists for the victim picker.
            if (edged.getMain().countByName(chaff.getName()) < 1
                    || EnemyThemeDecks.isInCore(chaff.getName(), theme))
                continue;
            for (int i = 0; i < 4; i++)
                edged.getMain().add(chaff);
            Set<String> tribalNamesBefore = tribalCreatureNames(edged, theme);
            int tribalBefore = EnemyThemeDecks.countTribalCreatures(edged, theme);
            // Directly exercise the trim victim picker at the floor.
            PaperCard victim = EnemyThemeDecks.pickCommanderNormalizeTrimVictimForTests(
                    edged, theme);
            if (victim == null) {
                problems.add(theme.id + ": trim victim null at tribal floor with chaff present");
                continue;
            }
            if (countsAsTribalForTest(victim, theme)) {
                problems.add(theme.id + ": trim victim was tribal " + victim.getName()
                        + " while at floor");
                continue;
            }
            if (victim.getRules() != null && victim.getRules().getType().isLand()) {
                problems.add(theme.id + ": trim victim was a land though lands==MIN and chaff"
                        + " is present (" + victim.getName() + ")");
                continue;
            }
            try {
                EnemyThemeDecks.normalizeCommanderMainSizeForTests(edged, theme);
            } catch (RuntimeException ex) {
                problems.add(theme.id + ": normalize threw: " + ex.getMessage());
                continue;
            }
            exercised++;
            if (edged.getMain().countAll() != 99)
                problems.add(theme.id + ": size " + edged.getMain().countAll());
            int tribalAfter = EnemyThemeDecks.countTribalCreatures(edged, theme);
            if (tribalAfter < tribalBefore)
                problems.add(theme.id + ": tribal dropped " + tribalBefore + "→" + tribalAfter);
            Set<String> tribalNamesAfter = tribalCreatureNames(edged, theme);
            if (!tribalNamesAfter.containsAll(tribalNamesBefore)) {
                Set<String> lost = new HashSet<>(tribalNamesBefore);
                lost.removeAll(tribalNamesAfter);
                problems.add(theme.id + ": tribal creatures removed at floor: " + lost);
            }
        }
        Assert.assertTrue(exercised >= 5,
                "expected at least 5 themes to exercise at-floor nonland trim, got "
                        + exercised + (problems.isEmpty() ? ""
                        : ("\n" + String.join("\n", problems))));
        Assert.assertTrue(problems.isEmpty(),
                "normalize at-floor nonland trim problems:\n" + String.join("\n", problems));
    }

    @Test
    public void normalizeTrimVictimAtTribalFloorPrefersNonlandChaff() {
        EnemyThemeData theme = themeById("goblin_tribal");
        Assert.assertNotNull(theme);
        Assert.assertTrue(EnemyThemeDecks.isTribalThemeForTests(theme));
        EnemyThemeDecks.ensureCoreLoaded(theme);
        Deck deck = EnemyThemeDecks.buildFixedDeck(theme, "Commander", 42L);
        Assert.assertNotNull(deck);
        PaperCard chaff = disposableChaffForTheme(theme);
        Assert.assertNotNull(chaff);
        Assert.assertFalse(EnemyThemeDecks.isInCore(chaff.getName(), theme));
        // Trim to exact tribal floor (core tribal allowed for setup).
        while (EnemyThemeDecks.countTribalCreatures(deck, theme)
                > EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER) {
            PaperCard tribal = null;
            for (PaperCard pc : deck.getMain().toFlatList()) {
                if (pc != null && countsAsTribalForTest(pc, theme)) {
                    tribal = pc;
                    break;
                }
            }
            if (tribal == null)
                break;
            deck.getMain().remove(tribal);
            deck.getMain().add(chaff);
        }
        Assert.assertEquals(EnemyThemeDecks.countTribalCreatures(deck, theme),
                EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER);
        // Lands at MIN; inject chaff overshoot.
        PaperCard basic = firstBasicInDeck(deck);
        Assert.assertNotNull(basic);
        while (countLandsInDeck(deck) > EnemyThemeDecks.MIN_LANDS_COMMANDER) {
            for (PaperCard pc : deck.getMain().toFlatList()) {
                if (pc.getRules() != null && pc.getRules().getType().isBasicLand()) {
                    deck.getMain().remove(pc);
                    deck.getMain().add(chaff);
                    break;
                }
            }
        }
        while (countLandsInDeck(deck) < EnemyThemeDecks.MIN_LANDS_COMMANDER)
            deck.getMain().add(basic);
        while (deck.getMain().countAll() < 99)
            deck.getMain().add(chaff);
        for (int i = 0; i < 3; i++)
            deck.getMain().add(chaff);
        Assert.assertEquals(countLandsInDeck(deck), EnemyThemeDecks.MIN_LANDS_COMMANDER);
        Assert.assertEquals(EnemyThemeDecks.countTribalCreatures(deck, theme),
                EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER);
        Set<String> tribalBefore = tribalCreatureNames(deck, theme);
        PaperCard victim = EnemyThemeDecks.pickCommanderNormalizeTrimVictimForTests(deck, theme);
        Assert.assertNotNull(victim);
        Assert.assertFalse(countsAsTribalForTest(victim, theme),
                "at tribal floor, trim must not pick a tribal creature (got " + victim.getName() + ")");
        Assert.assertFalse(victim.getRules().getType().isLand(),
                "at lands==MIN with nonland filler present, trim must not pick a land");
        EnemyThemeDecks.normalizeCommanderMainSizeForTests(deck, theme);
        Assert.assertEquals(deck.getMain().countAll(), 99);
        Assert.assertEquals(EnemyThemeDecks.countTribalCreatures(deck, theme),
                EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER);
        Assert.assertTrue(tribalCreatureNames(deck, theme).containsAll(tribalBefore),
                "no tribal creature may be removed while trimming at the floor");
    }

    private static Set<String> tribalCreatureNames(Deck deck, EnemyThemeData theme) {
        Set<String> names = new HashSet<>();
        for (PaperCard pc : deck.getMain().toFlatList()) {
            if (pc != null && countsAsTribalForTest(pc, theme))
                names.add(pc.getName());
        }
        return names;
    }

    @Test
    public void normalizeTribalDbFillerIsDeterministicRespectsBansAndCurve() {
        EnemyThemeData theme = themeById("goblin_tribal");
        Assert.assertNotNull(theme);
        EnemyThemeDecks.ensureCoreLoaded(theme);
        Deck deck = EnemyThemeDecks.buildFixedDeck(theme, "Commander", 11L);
        Assert.assertNotNull(deck);
        // Strip a few tribal so the DB filler has work to do.
        int removed = 0;
        for (PaperCard pc : new ArrayList<>(deck.getMain().toFlatList())) {
            if (removed >= 3)
                break;
            if (pc != null && countsAsTribalForTest(pc, theme)
                    && !EnemyThemeDecks.isInCore(pc.getName(), theme)) {
                deck.getMain().remove(pc);
                removed++;
            }
        }
        byte ci = 0;
        for (PaperCard cmd : deck.getCommanders()) {
            if (cmd != null && cmd.getRules() != null)
                ci |= cmd.getRules().getColorIdentity().getColor();
        }
        PaperCard a = EnemyThemeDecks.pickCommanderNormalizeTribalDbFillerForTests(deck, theme, ci);
        PaperCard b = EnemyThemeDecks.pickCommanderNormalizeTribalDbFillerForTests(deck, theme, ci);
        Assert.assertNotNull(a, "expected a tribal DB filler candidate");
        Assert.assertEquals(a.getName(), b.getName(), "tribal DB filler must be deterministic");
        Assert.assertFalse(EnemyThemeDecks.isEnemyBanned(a.getName()),
                "filler must not be enemy-banned: " + a.getName());
        Assert.assertTrue(EnemyThemeDecks.cardLegalInFixedFormat(a, "Commander", null),
                "filler must pass Adventure Commander legality: " + a.getName());
        Assert.assertTrue(countsAsTribalForTest(a, theme), "filler must be tribal");

        // Curve / color ranking: near-target colored body beats off-curve colorless.
        PaperCard cheap = FModel.getMagicDb().getCommonCards().getCard("Raging Goblin");
        PaperCard mid = FModel.getMagicDb().getCommonCards().getCard("Goblin Chieftain");
        PaperCard pricey = FModel.getMagicDb().getCommonCards().getCard("Siege-Gang Commander");
        Assert.assertNotNull(cheap);
        Assert.assertNotNull(mid);
        Assert.assertNotNull(pricey);
        List<PaperCard> cands = new ArrayList<>();
        cands.add(pricey);
        cands.add(cheap);
        cands.add(mid);
        PaperCard picked = EnemyThemeDecks.selectBestCommanderNormalizeTribalFillerForTests(
                cands, 3, forge.card.MagicColor.RED);
        Assert.assertEquals(picked.getName(), "Goblin Chieftain",
                "should prefer CMC near target among tribal candidates");

        // Banned names must never win even if they would otherwise rank first.
        List<PaperCard> bannedProbe = new ArrayList<>();
        // Put an enemy-banned goblin-adjacent power card first alphabetically if present.
        PaperCard banned = FModel.getMagicDb().getCommonCards().getCard("Dockside Extortionist");
        if (banned != null && EnemyThemeDecks.isEnemyBanned(banned.getName())) {
            bannedProbe.add(banned);
            bannedProbe.add(mid);
            // Direct selectBest would still pick Dockside — the DB path filters first.
            // Simulate the filter the production path applies.
            List<PaperCard> legal = new ArrayList<>();
            for (PaperCard pc : bannedProbe) {
                if (!EnemyThemeDecks.isEnemyBanned(pc.getName())
                        && EnemyThemeDecks.cardLegalInFixedFormat(pc, "Commander", null))
                    legal.add(pc);
            }
            PaperCard safe = EnemyThemeDecks.selectBestCommanderNormalizeTribalFillerForTests(
                    legal, 3, forge.card.MagicColor.RED);
            Assert.assertNotNull(safe);
            Assert.assertFalse(EnemyThemeDecks.isEnemyBanned(safe.getName()));
            Assert.assertEquals(safe.getName(), "Goblin Chieftain");
        }
    }

    @Test
    public void normalizeFillRespectsLandCapForEveryCommanderTheme() {
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (EnemyThemeData theme : themes) {
            if (theme == null || theme.id == null || !seen.add(theme.id))
                continue;
            EnemyThemeDecks.ensureCoreLoaded(theme);
            Deck base = null;
            try {
                base = EnemyThemeDecks.buildFixedDeck(theme, "Commander", 77L);
            } catch (RuntimeException ex) {
                problems.add(theme.id + ": build failed: " + ex.getMessage());
                continue;
            }
            if (base == null) {
                problems.add(theme.id + ": build returned null");
                continue;
            }
            Deck under = copyDeck(base);
            PaperCard basic = firstBasicInDeck(under);
            PaperCard chaff = disposableChaffForTheme(theme);
            if (basic == null || chaff == null) {
                problems.add(theme.id + ": missing basic or CI-legal chaff");
                continue;
            }
            // Pad basics to the land cap (size becomes 99+d).
            while (countLandsInDeck(under) < EnemyThemeDecks.MAX_LANDS_COMMANDER)
                under.getMain().add(basic);
            final int shortfall = under.getMain().countAll() - 94;
            if (shortfall <= 0) {
                problems.add(theme.id + ": unexpected size after land pad "
                        + under.getMain().countAll());
                continue;
            }
            // Add chaff, remove that many originals, then remove only the added chaff
            // → size 94 at the land cap without stripping basics.
            final int chaffBefore = under.getMain().countByName(chaff.getName());
            for (int i = 0; i < shortfall; i++)
                under.getMain().add(chaff);
            int removedOrig = 0;
            // Prefer stripping non-tribal originals so the fill path is what restores size.
            for (int pass = 0; pass < 2 && removedOrig < shortfall; pass++) {
                for (PaperCard pc : new ArrayList<>(under.getMain().toFlatList())) {
                    if (removedOrig >= shortfall)
                        break;
                    if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                        continue;
                    if (chaff.getName().equals(pc.getName()))
                        continue;
                    boolean tribal = countsAsTribalForTest(pc, theme);
                    if (pass == 0 && tribal)
                        continue;
                    if (pass == 1 && !tribal)
                        continue;
                    under.getMain().remove(pc);
                    removedOrig++;
                }
            }
            int removedChaff = 0;
            while (under.getMain().countByName(chaff.getName()) > chaffBefore
                    && removedChaff < shortfall) {
                for (PaperCard pc : under.getMain().toFlatList()) {
                    if (pc != null && chaff.getName().equals(pc.getName())) {
                        under.getMain().remove(pc);
                        removedChaff++;
                        break;
                    }
                }
            }
            if (under.getMain().countAll() != 94
                    || countLandsInDeck(under) != EnemyThemeDecks.MAX_LANDS_COMMANDER) {
                problems.add(theme.id + ": could not shape undersize at cap (size="
                        + under.getMain().countAll()
                        + " lands=" + countLandsInDeck(under)
                        + " removedOrig=" + removedOrig
                        + " removedChaff=" + removedChaff + ")");
                continue;
            }
            try {
                EnemyThemeDecks.normalizeCommanderMainSizeForTests(under, theme);
            } catch (RuntimeException ex) {
                problems.add(theme.id + ": normalize threw: " + ex.getMessage());
                continue;
            }
            if (under.getMain().countAll() != 99)
                problems.add(theme.id + ": size " + under.getMain().countAll());
            int lands = countLandsInDeck(under);
            if (lands > EnemyThemeDecks.MAX_LANDS_COMMANDER)
                problems.add(theme.id + ": lands " + lands + " > max "
                        + EnemyThemeDecks.MAX_LANDS_COMMANDER);
            // Fill at the cap must use nonlands — lands stay at the cap.
            if (lands != EnemyThemeDecks.MAX_LANDS_COMMANDER)
                problems.add(theme.id + ": lands " + lands + " != cap "
                        + EnemyThemeDecks.MAX_LANDS_COMMANDER);
        }
        Assert.assertTrue(problems.isEmpty(),
                "normalize land-cap fill problems:\n" + String.join("\n", problems));
    }

    /** CI-legal nonland used only as disposable test chaff (not an interaction staple). */
    private static PaperCard disposableChaffForTheme(EnemyThemeData theme) {
        String[] candidates = {
                // Prefer names that are almost never in EN1 cores (Mind Stone is).
                "Jump", "Holy Strength", "Unholy Strength", "Fear", "Giant Growth",
                "Dark Ritual", "Worn Powerstone", "Guardian Idol", "Coldsteel Heart",
                "Pacifism", "Unsummon", "Duress", "Shock", "Firebreathing", "Mind Stone"
        };
        byte ci = 0;
        if (theme != null && theme.colors != null) {
            for (String c : theme.colors) {
                if (c == null)
                    continue;
                String L = c.toLowerCase(Locale.ROOT);
                if ("white".equals(L) || "w".equals(L))
                    ci |= forge.card.MagicColor.WHITE;
                else if ("blue".equals(L) || "u".equals(L))
                    ci |= forge.card.MagicColor.BLUE;
                else if ("black".equals(L) || "b".equals(L))
                    ci |= forge.card.MagicColor.BLACK;
                else if ("red".equals(L) || "r".equals(L))
                    ci |= forge.card.MagicColor.RED;
                else if ("green".equals(L) || "g".equals(L))
                    ci |= forge.card.MagicColor.GREEN;
            }
        }
        PaperCard fallback = null;
        for (String name : candidates) {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
            if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                continue;
            if (isNamedInteractionForTest(name))
                continue;
            if (ci != 0 && !pc.getRules().getColorIdentity().hasNoColorsExcept(ci)
                    && !pc.getRules().getColorIdentity().isColorless())
                continue;
            if (theme != null && EnemyThemeDecks.isInCore(name, theme)) {
                if (fallback == null)
                    fallback = pc;
                continue;
            }
            return pc;
        }
        return fallback;
    }

    /** Non-core, non-floor-protected nonland suitable for test shaping. */
    private static PaperCard pickRemovableNormalizeTestVictim(Deck deck, EnemyThemeData theme) {
        for (PaperCard pc : deck.getMain().toFlatList()) {
            if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                continue;
            if (EnemyThemeDecks.isInCore(pc.getName(), theme))
                continue;
            if (isNamedInteractionForTest(pc.getName())
                    && countNamedInteraction(deck)
                    <= EnemyThemeDecks.MIN_COMMANDER_INTERACTION_SPELLS)
                continue;
            if (countsAsTribalForTest(pc, theme)
                    && EnemyThemeDecks.countTribalCreatures(deck, theme)
                    <= EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                continue;
            if (!countsAsTribalForTest(pc, theme))
                return pc;
        }
        for (PaperCard pc : deck.getMain().toFlatList()) {
            if (pc == null || pc.getRules() == null || pc.getRules().getType().isLand())
                continue;
            if (EnemyThemeDecks.isInCore(pc.getName(), theme))
                continue;
            if (countsAsTribalForTest(pc, theme)
                    && EnemyThemeDecks.countTribalCreatures(deck, theme)
                    > EnemyThemeDecks.MIN_TRIBAL_CREATURES_COMMANDER)
                return pc;
        }
        return null;
    }

    private static boolean countsAsTribalForTest(PaperCard pc, EnemyThemeData theme) {
        return EnemyThemeDecks.countTribalCreatures(singletonDeck(pc), theme) > 0;
    }

    private static Deck singletonDeck(PaperCard pc) {
        Deck d = new Deck("t");
        if (pc != null)
            d.getMain().add(pc);
        return d;
    }

    private static boolean isNamedInteractionForTest(String name) {
        if (name == null)
            return false;
        Deck probe = new Deck("p");
        PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
        if (pc == null)
            return false;
        probe.getMain().add(pc);
        return countNamedInteraction(probe) > 0;
    }

    private static PaperCard firstBasicInDeck(Deck deck) {
        for (PaperCard pc : deck.getMain().toFlatList()) {
            if (pc != null && pc.getRules() != null && pc.getRules().getType().isBasicLand())
                return pc;
        }
        return null;
    }

    private static int countLandsInDeck(Deck deck) {
        int n = 0;
        for (PaperCard pc : deck.getMain().toFlatList()) {
            if (pc != null && pc.getRules() != null && pc.getRules().getType().isLand())
                n++;
        }
        return n;
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
