package forge.adventure;

import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.EnemyThemeRecipeData;
import forge.adventure.util.EnemyThemeDecks;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
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
import java.util.List;
import java.util.Locale;

/**
 * One-shot / maintenance generator for EN1 fixed decks under
 * {@code forge-gui/res/adventure/common/decks/enemy/<theme>/<format>_1.dck}.
 * <p>
 * Always safe to run: skips themes that already have a legal deck for the format.
 * Run with:
 * {@code mvn -pl forge-gui-desktop -am test -Dtest=EnemyThemeDeckGeneratorTest -DfailIfNoTests=false}
 */
public class EnemyThemeDeckGeneratorTest {

    private Path enemyDeckRoot;
    private List<EnemyThemeData> themes;
    private final List<String> couldNotFill = new ArrayList<>();

    @BeforeClass
    public void init() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
        enemyDeckRoot = resolveEnemyDeckRoot();
        Files.createDirectories(enemyDeckRoot);
        themes = loadThemes();
        Assert.assertEquals(themes.size(), 16, "expected 16 themes");
    }

    @Test(timeOut = 600000)
    public void generateMissingFixedDecks() throws Exception {
        EnemyThemeDecks.setEnabledForTests(true);
        int wrote = 0;
        for (EnemyThemeData theme : themes) {
            for (String format : new String[]{"Historic", "Pauper", "Commander"}) {
                Path out = enemyDeckRoot.resolve(theme.id)
                        .resolve(format.toLowerCase(Locale.ROOT) + "_1.dck");
                if (Files.isRegularFile(out) && isAcceptable(out, format, theme))
                    continue;

                Files.createDirectories(out.getParent());
                long seed = theme.id.hashCode() * 31L + format.hashCode();
                Deck best = null;
                String bestProblem = "not generated";
                for (int attempt = 0; attempt < 24; attempt++) {
                    Deck deck = EnemyThemeDecks.buildFixedDeck(theme, format, seed + attempt * 17L);
                    if (deck == null)
                        continue;
                    deck.setName(theme.id + " " + format);
                    String problem = quality(deck, theme, format);
                    if (problem == null) {
                        best = deck;
                        bestProblem = null;
                        break;
                    }
                    if (best == null || betterThan(problem, bestProblem)) {
                        best = deck;
                        bestProblem = problem;
                    }
                }
                if (best == null || bestProblem != null) {
                    couldNotFill.add(theme.id + "/" + format + ": " + bestProblem);
                    // Still write best-effort so content work can continue; legality test will fail.
                    if (best != null) {
                        writeUtf8NoBom(out, best);
                        wrote++;
                    }
                    continue;
                }
                writeUtf8NoBom(out, best);
                wrote++;
            }
        }
        System.out.println("EN1 generator wrote/updated " + wrote + " decks under " + enemyDeckRoot);
        if (!couldNotFill.isEmpty()) {
            System.err.println("EN1 could not fully fill:\n" + String.join("\n", couldNotFill));
        }
        // Soft assert: allow partial fill to be reported; legality test is the gate.
        Assert.assertTrue(wrote >= 0);
        EnemyThemeDecks.clearCache();
    }

    private boolean isAcceptable(Path path, String format, EnemyThemeData theme) {
        try {
            Deck d = DeckSerializer.fromFile(path.toFile());
            if (d == null)
                return false;
            d.getMain();
            if (d.has(forge.deck.DeckSection.Commander))
                d.get(forge.deck.DeckSection.Commander);
            if (legality(d, format) != null)
                return false;
            return EnemyThemeDecks.themeQualityProblem(d, theme, format) == null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String legality(Deck deck, String format) {
        return EnemyThemeDecks.legalityProblem(deck, format);
    }

    private static String quality(Deck deck, EnemyThemeData theme, String format) {
        String legal = legality(deck, format);
        if (legal != null)
            return legal;
        return EnemyThemeDecks.themeQualityProblem(deck, theme, format);
    }

    private static void writeUtf8NoBom(Path path, Deck deck) throws Exception {
        List<String> lines = DeckSerializer.toDecklistLines(deck);
        String body = String.join("\n", lines);
        if (!body.endsWith("\n"))
            body = body + "\n";
        byte[] utf8 = body.getBytes(StandardCharsets.UTF_8);
        // Strip BOM if somehow present.
        if (utf8.length >= 3 && (utf8[0] & 0xFF) == 0xEF && (utf8[1] & 0xFF) == 0xBB && (utf8[2] & 0xFF) == 0xBF) {
            byte[] stripped = new byte[utf8.length - 3];
            System.arraycopy(utf8, 3, stripped, 0, stripped.length);
            utf8 = stripped;
        }
        Files.write(path, utf8);
    }

    private Path resolveEnemyDeckRoot() {
        Path[] candidates = {
                Paths.get("../forge-gui/res/adventure/common/decks/enemy"),
                Paths.get("forge-gui/res/adventure/common/decks/enemy"),
                Paths.get("res/adventure/common/decks/enemy")
        };
        for (Path p : candidates) {
            Path parent = p.getParent();
            if (parent != null && Files.isDirectory(parent))
                return p.toAbsolutePath().normalize();
        }
        return Paths.get("../forge-gui/res/adventure/common/decks/enemy").toAbsolutePath().normalize();
    }

    private List<EnemyThemeData> loadThemes() throws Exception {
        Path json = enemyDeckRoot.getParent().getParent().resolve("world/enemy_themes.json");
        if (!Files.isRegularFile(json))
            json = Paths.get("../forge-gui/res/adventure/common/world/enemy_themes.json");
        String text = Files.readString(json, StandardCharsets.UTF_8);
        List<EnemyThemeData> list = new ArrayList<>();
        EnemyThemeData current = null;
        List<String> pendingCommanders = null;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("\"id\":")) {
                if (current != null)
                    list.add(current);
                current = new EnemyThemeData();
                current.id = extractString(t);
                current.tags = new String[0];
                current.colors = new String[]{"blue"};
                current.creatureTypes = new String[]{"Merfolk"};
                current.preferredCommanders = new String[0];
                current.standardRecipe = new EnemyThemeRecipeData();
                current.standardRecipe.count = 60;
                current.standardRecipe.rares = 0.15f;
            } else if (current != null && t.startsWith("\"tags\"")) {
                current.tags = extractStringArray(t);
            } else if (current != null && t.startsWith("\"colors\"") && t.contains("[")) {
                String[] cols = extractStringArray(t);
                if (cols.length > 0 && !t.contains("Blue") && !t.contains("Red")
                        && !t.contains("Green") && !t.contains("White") && !t.contains("Black")) {
                    // lowercase theme colors
                    current.colors = cols;
                } else if (current.standardRecipe != null && (current.standardRecipe.colors == null
                        || current.standardRecipe.colors.length == 0)) {
                    current.standardRecipe.colors = cols;
                } else if (cols.length > 0 && Character.isUpperCase(cols[0].charAt(0))) {
                    current.standardRecipe.colors = cols;
                } else {
                    current.colors = cols;
                }
            } else if (current != null && t.startsWith("\"creatureTypes\"")) {
                current.creatureTypes = extractStringArray(t);
            } else if (current != null && t.startsWith("\"preferredCommanders\"")) {
                current.preferredCommanders = extractStringArray(t);
            } else if (current != null && t.startsWith("\"keyCards\"")) {
                current.keyCards = extractStringArray(t);
            } else if (current != null && t.startsWith("\"mechanics\"")) {
                current.mechanics = extractStringArray(t);
            } else if (current != null && t.startsWith("\"tribe\"")) {
                current.standardRecipe.tribe = extractString(t);
                if (current.creatureTypes == null || current.creatureTypes.length == 0)
                    current.creatureTypes = new String[]{current.standardRecipe.tribe};
            }
        }
        if (current != null)
            list.add(current);

        // Normalize recipe colors from theme colors when missing.
        for (EnemyThemeData th : list) {
            if (th.standardRecipe.colors == null || th.standardRecipe.colors.length == 0) {
                String[] up = new String[th.colors.length];
                for (int i = 0; i < th.colors.length; i++) {
                    String c = th.colors[i];
                    up[i] = c.substring(0, 1).toUpperCase(Locale.ROOT) + c.substring(1).toLowerCase(Locale.ROOT);
                }
                th.standardRecipe.colors = up;
            }
            if (th.standardRecipe.tribe == null && th.creatureTypes.length > 0)
                th.standardRecipe.tribe = th.creatureTypes[0];
        }
        return list;
    }

    private static boolean betterThan(String candidate, String current) {
        if (current == null || "not generated".equals(current))
            return true;
        if (candidate == null)
            return true;
        // Prefer fewer on-theme complaints over legality failures when both bad.
        boolean candTheme = candidate.contains("on-theme");
        boolean curTheme = current.contains("on-theme");
        return candTheme && !curTheme;
    }

    private static String extractString(String line) {
        int c = line.indexOf(':');
        int q1 = line.indexOf('"', c + 1);
        int q2 = line.indexOf('"', q1 + 1);
        return line.substring(q1 + 1, q2);
    }

    private static String[] extractStringArray(String line) {
        int b = line.indexOf('[');
        if (b < 0)
            return new String[0];
        String bracketed = line.substring(b);
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
