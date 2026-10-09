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

        EnemyThemeData theme = new EnemyThemeData();
        theme.id = "elf_tribal";
        theme.tags = new String[]{"Elf"};
        theme.colors = new String[]{"green"};
        theme.creatureTypes = new String[]{"Elf"};
        theme.standardRecipe = new EnemyThemeRecipeData();
        theme.standardRecipe.count = 60;
        theme.standardRecipe.colors = new String[]{"Green"};
        theme.standardRecipe.tribe = "Elf";
        theme.standardRecipe.rares = 0.15f;

        Deck deck = EnemyThemeDecks.fillStandardRecipe(theme, window, 99L);
        Assert.assertNotNull(deck);
        Assert.assertTrue(deck.getMain().countAll() >= 40,
                "recipe deck too small: " + deck.getMain().countAll());

        Set<String> illegal = new HashSet<>();
        int nonBasics = 0;
        for (var e : deck.getMain()) {
            PaperCard pc = e.getKey();
            if (pc.getRules().getType().isBasicLand())
                continue;
            nonBasics += e.getValue();
            if (!printedInWindow(pc.getName(), window))
                illegal.add(pc.getName());
        }
        Assert.assertTrue(illegal.isEmpty(),
                "Standard recipe included cards outside window: " + illegal);
        Assert.assertTrue(nonBasics > 0,
                "expected at least one non-basic from the Standard window");

        EnemyThemeDecks.clearCache();
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
                current.standardRecipe = new EnemyThemeRecipeData();
                current.standardRecipe.count = 60;
                current.standardRecipe.tribe = current.id.contains("goblin") ? "Goblin"
                        : current.id.contains("elf") ? "Elf"
                        : current.id.contains("zombie") ? "Zombie"
                        : current.id.contains("vampire") ? "Vampire"
                        : current.id.contains("dragon") ? "Dragon"
                        : current.id.contains("soldier") ? "Soldier"
                        : current.id.contains("knight") ? "Knight"
                        : current.id.contains("spirit") ? "Spirit"
                        : current.id.contains("kraken") ? "Kraken"
                        : "Merfolk";
                current.standardRecipe.colors = new String[]{"Blue"};
                current.colors = new String[]{current.standardRecipe.colors[0].toLowerCase(Locale.ROOT)};
                current.creatureTypes = new String[]{current.standardRecipe.tribe};
            } else if (current != null && t.startsWith("\"preferredCommanders\"")) {
                current.preferredCommanders = jsonStringArray(t.substring(t.indexOf('[')));
            } else if (current != null && t.startsWith("\"tags\"")) {
                current.tags = jsonStringArray(t.substring(t.indexOf('[')));
            } else if (current != null && t.startsWith("\"colors\"") && !t.contains("standardRecipe")) {
                // first colors array on the theme
                if (current.colors == null || current.colors.length <= 1)
                    current.colors = jsonStringArray(t.substring(t.indexOf('[')));
            }
        }
        if (current != null)
            list.add(current);
        return list;
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
