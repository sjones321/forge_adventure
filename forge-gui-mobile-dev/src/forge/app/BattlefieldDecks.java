package forge.app;

import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.game.GameType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Saved Forge decks for the duel picker.
 * Reads .dck files from the decks folder next to the launcher and the real
 * profile decks folder. Import writes a new .dck next to the launcher.
 * The match itself stays on {@code forge.test.userDir}.
 */
final class BattlefieldDecks {
    static final String GREEN_LABEL =
            "Green test list: 4 Genesis Wave, 22 Forest, 4 Llanowar Elves, 4 Grizzly Bears, 2 Giant Growth";
    static final String ASH_LABEL =
            "Ash test list: 24 Mountain, 4 Raging Goblin, 4 Shock, 4 Lightning Bolt";

    enum Kind { FILE, GREEN_TEST, ASH_TEST }

    static final class Choice {
        final Kind kind;
        final String label;
        final Path file;

        Choice(Kind kind, String label, Path file) {
            this.kind = kind;
            this.label = label;
            this.file = file;
        }
    }

    final Path root;
    final int savedCount;
    final String note;
    final List<Choice> saved;

    private BattlefieldDecks(Path root, int savedCount, String note, List<Choice> saved) {
        this.root = root;
        this.savedCount = savedCount;
        this.note = note;
        this.saved = saved;
    }

    List<Choice> forYou() {
        List<Choice> rows = new ArrayList<>();
        rows.add(new Choice(Kind.GREEN_TEST, GREEN_LABEL, null));
        rows.addAll(saved);
        return rows;
    }

    List<Choice> forAsh() {
        List<Choice> rows = new ArrayList<>();
        rows.add(new Choice(Kind.ASH_TEST, ASH_LABEL, null));
        rows.addAll(saved);
        return rows;
    }

    /**
     * Same folder Forge uses when {@code forge.test.userDir} is not set:
     * {@code decksDir} from forge-gui/forge.profile.properties, otherwise %APPDATA%\Forge\decks.
     */
    static Path realDecksRoot() {
        Path profile = Path.of("forge-gui", "forge.profile.properties");
        String override = readDecksDir(profile);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String app = System.getenv("APPDATA");
        if (app != null && !app.isBlank()) {
            return Path.of(app, "Forge", "decks");
        }
        String os = System.getProperty("os.name", "");
        String home = System.getProperty("user.home", ".");
        if (os.toLowerCase(Locale.ROOT).contains("mac")) {
            return Path.of(home, "Library", "Application Support", "Forge", "decks");
        }
        return Path.of(home, ".forge", "decks");
    }

    /** Folders the picker reads. The first is the decks folder next to the launcher. */
    static List<Path> deckRoots() {
        List<Path> roots = new ArrayList<>();
        Path local = Path.of("decks").toAbsolutePath().normalize();
        roots.add(local);
        Path real = realDecksRoot().toAbsolutePath().normalize();
        if (!real.equals(local)) roots.add(real);
        return roots;
    }

    static Path writeRoot() {
        return deckRoots().get(0);
    }

    static BattlefieldDecks scan() {
        List<Path> roots = deckRoots();
        Path root = roots.get(0);
        dropDuplicateImports(root);
        List<Path> files = new ArrayList<>();
        boolean folder = false;
        for (Path candidate : roots) {
            if (!Files.isDirectory(candidate)) continue;
            folder = true;
            try (var walk = Files.walk(candidate)) {
                walk.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dck"))
                        .map(path -> path.toAbsolutePath().normalize())
                        .forEach(path -> {
                            if (!files.contains(path)) files.add(path);
                        });
            } catch (IOException failed) {
                System.err.println("Deck picker could not list " + candidate + ": " + failed.getMessage());
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
        List<Choice> saved = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Path file : files) {
            String name = file.getFileName().toString();
            if (name.toLowerCase(Locale.ROOT).endsWith(".dck")) {
                name = name.substring(0, name.length() - 4);
            }
            try {
                Deck deck = DeckSerializer.fromFile(file.toFile());
                if (deck != null && deck.getName() != null && !deck.getName().isBlank()) {
                    name = deck.getName().trim();
                }
            } catch (RuntimeException failed) {
                name = file.getFileName().toString();
            }
            names.add(name);
        }
        for (int i = 0; i < files.size(); i++) {
            String name = names.get(i);
            int copies = 0;
            for (String other : names) {
                if (other.equalsIgnoreCase(name)) copies++;
            }
            if (copies > 1) {
                Path parent = files.get(i).getParent();
                String folderName = parent == null || parent.getFileName() == null
                        ? "" : parent.getFileName().toString();
                if (!folderName.isBlank()) name = name + " (" + folderName + ")";
            }
            saved.add(new Choice(Kind.FILE, name, files.get(i)));
        }
        String note;
        if (saved.isEmpty()) {
            note = "Copy a list from Arena, then Import. The green test list and Ash's test list are ready too.";
            if (!folder) {
                System.err.println("Deck picker: no decks folder yet at " + root);
            }
        } else {
            note = saved.size() + (saved.size() == 1 ? " saved deck. " : " saved decks. ")
                    + "Copy a list from Arena and Import, or click a deck and Start.";
        }
        System.err.println("Deck picker: " + note);
        return new BattlefieldDecks(root, saved.size(), note, List.copyOf(saved));
    }

    /**
     * Writes a Forge .dck from Arena (or Arena-ready) text. The same list
     * is not written again — the existing file is returned.
     */
    static Path alreadyImported(String text) {
        BattlefieldArenaList.Parsed parsed = BattlefieldArenaList.parse(text);
        if (parsed.error != null) return null;
        return findByFingerprint(fingerprint(parsed));
    }

    static Path importArena(String text) throws IOException {
        BattlefieldArenaList.Parsed parsed = BattlefieldArenaList.parse(text);
        if (parsed.error != null) {
            throw new IllegalArgumentException(parsed.error);
        }
        String mark = fingerprint(parsed);
        Path already = findByFingerprint(mark);
        if (already != null) return already;
        Path dir = writeRoot();
        Files.createDirectories(dir);
        Path file = uniqueFile(dir, parsed.name);
        Files.writeString(file, toDck(parsed), StandardCharsets.UTF_8);
        return file.toAbsolutePath().normalize();
    }

    static final class Line {
        final boolean heading;
        final String text;
        final String card;
        final int count;

        Line(boolean heading, String text, String card, int count) {
            this.heading = heading;
            this.text = text;
            this.card = card;
            this.count = count;
        }
    }

    static final class Sheet {
        final String name;
        final String format;
        final boolean fileDeck;
        final List<Line> lines;

        Sheet(String name, String format, boolean fileDeck, List<Line> lines) {
            this.name = name;
            this.format = format;
            this.fileDeck = fileDeck;
            this.lines = lines;
        }
    }

    static Sheet sheetOf(Choice choice) {
        if (choice == null || choice.kind == Kind.GREEN_TEST) {
            return new Sheet("Green test list", "Constructed", false, List.of(
                    heading("Main", 36),
                    card("Forest", 22),
                    card("Llanowar Elves", 4),
                    card("Grizzly Bears", 4),
                    card("Giant Growth", 2),
                    card("Genesis Wave", 4)
            ));
        }
        if (choice.kind == Kind.ASH_TEST) {
            return new Sheet("Ash test list", "Constructed", false, List.of(
                    heading("Main", 36),
                    card("Mountain", 24),
                    card("Raging Goblin", 4),
                    card("Shock", 4),
                    card("Lightning Bolt", 4)
            ));
        }
        return sheetOfFile(choice.file, choice.label);
    }

    static Sheet sheetOfFile(Path file, String fallbackName) {
        String name = fallbackName == null || fallbackName.isBlank() ? "Saved list" : fallbackName;
        String format = "";
        List<Line> lines = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return new Sheet(name, format, true, List.of());
        }
        try {
            String section = "";
            List<String> body = new ArrayList<>();
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    flushSection(lines, section, body);
                    body = new ArrayList<>();
                    section = line.substring(1, line.length() - 1).trim();
                    continue;
                }
                if (section.equalsIgnoreCase("metadata")) {
                    if (line.regionMatches(true, 0, "Name=", 0, 5) && line.length() > 5) {
                        name = line.substring(5).trim();
                    } else if (line.regionMatches(true, 0, "Deck Type=", 0, 10)) {
                        format = line.substring(10).trim();
                    }
                    continue;
                }
                body.add(line);
            }
            flushSection(lines, section, body);
        } catch (IOException failed) {
            lines.add(new Line(true, "Could not read that file.", "", 0));
        }
        return new Sheet(name, format, true, List.copyOf(lines));
    }

    static Path rename(Path file, String newName) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("That list is not a saved file.");
        }
        String title = newName == null ? "" : newName.replace('\n', ' ').trim();
        if (title.isBlank()) throw new IllegalArgumentException("Type a name first.");
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (text.contains("Name=")) {
            text = text.replaceFirst("(?m)^Name=.*$", "Name=" + title);
        } else if (text.contains("[metadata]")) {
            text = text.replaceFirst("\\[metadata\\]", "[metadata]" + System.lineSeparator() + "Name=" + title);
        } else {
            text = "[metadata]" + System.lineSeparator() + "Name=" + title + System.lineSeparator() + text;
        }
        Path dir = file.getParent() == null ? writeRoot() : file.getParent();
        Path dest = dir.resolve(safeFile(title) + ".dck");
        if (!dest.equals(file) && Files.exists(dest)) dest = uniqueFile(dir, title);
        Files.writeString(dest, text, StandardCharsets.UTF_8);
        if (!dest.equals(file)) Files.deleteIfExists(file);
        return dest.toAbsolutePath().normalize();
    }

    static void delete(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException("That list is not a saved file.");
        }
        Files.delete(file);
    }

    private static void flushSection(List<Line> lines, String section, List<String> body) {
        if (section == null || section.isBlank() || section.equalsIgnoreCase("metadata")) return;
        if (body == null || body.isEmpty()) return;
        int total = 0;
        List<Line> cards = new ArrayList<>();
        for (String row : body) {
            int count = 1;
            String name = row;
            int space = row.indexOf(' ');
            if (space > 0) {
                try {
                    count = Integer.parseInt(row.substring(0, space));
                    name = row.substring(space + 1).trim();
                } catch (NumberFormatException ignored) {
                    count = 1;
                    name = row;
                }
            }
            if (name.isBlank()) continue;
            total += count;
            cards.add(card(name, count));
        }
        if (cards.isEmpty()) return;
        lines.add(heading(section, total));
        lines.addAll(cards);
    }

    private static Line heading(String section, int total) {
        String label = section;
        if (total > 0) label = section + "  ·  " + total;
        return new Line(true, label, "", 0);
    }

    private static Line card(String name, int count) {
        return new Line(false, count + "  " + name, name, count);
    }

    private static String fingerprint(BattlefieldArenaList.Parsed parsed) {
        List<String> rows = new ArrayList<>();
        addPrint(rows, "main", parsed.main);
        addPrint(rows, "commander", parsed.commander);
        addPrint(rows, "sideboard", parsed.side);
        rows.sort(String::compareTo);
        return String.join("\n", rows);
    }

    private static void addPrint(List<String> rows, String section, List<BattlefieldArenaList.CardLine> cards) {
        if (cards == null) return;
        for (BattlefieldArenaList.CardLine line : cards) {
            rows.add(section + "\t" + line.count + "\t" + line.name.toLowerCase(Locale.ROOT));
        }
    }

    private static String fingerprintOfFile(Path file) {
        try {
            Sheet sheet = sheetOfFile(file, "");
            List<String> rows = new ArrayList<>();
            String section = "main";
            for (Line line : sheet.lines) {
                if (line.heading) {
                    section = line.text.replaceAll("\\s+·.*", "").trim().toLowerCase(Locale.ROOT);
                    continue;
                }
                rows.add(section + "\t" + line.count + "\t" + line.card.toLowerCase(Locale.ROOT));
            }
            rows.sort(String::compareTo);
            return String.join("\n", rows);
        } catch (RuntimeException failed) {
            return "";
        }
    }

    private static Path findByFingerprint(String mark) {
        if (mark == null || mark.isBlank()) return null;
        for (Path root : deckRoots()) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                Path hit = walk.filter(path -> Files.isRegularFile(path)
                                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dck"))
                        .filter(path -> mark.equals(fingerprintOfFile(path)))
                        .findFirst()
                        .orElse(null);
                if (hit != null) return hit.toAbsolutePath().normalize();
            } catch (IOException ignored) {
                // Next folder.
            }
        }
        return null;
    }

    /** Same list saved as Imported list, Imported list-2, … — keep one. */
    private static void dropDuplicateImports(Path root) {
        if (root == null || !Files.isDirectory(root)) return;
        Map<String, List<Path>> groups = new LinkedHashMap<>();
        try (var walk = Files.walk(root, 1)) {
            walk.filter(path -> Files.isRegularFile(path)
                            && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".dck"))
                    .forEach(path -> {
                        String mark = fingerprintOfFile(path);
                        if (mark == null || mark.isBlank()) return;
                        groups.computeIfAbsent(mark, ignored -> new ArrayList<>()).add(path);
                    });
        } catch (IOException ignored) {
            return;
        }
        for (List<Path> copies : groups.values()) {
            if (copies.size() < 2) continue;
            copies.sort(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)));
            for (int i = 1; i < copies.size(); i++) {
                try {
                    Files.deleteIfExists(copies.get(i));
                } catch (IOException ignored) {
                    // Leave the extra copy.
                }
            }
        }
    }

    static String toDck(BattlefieldArenaList.Parsed parsed) {
        StringBuilder out = new StringBuilder();
        out.append("[metadata]").append(System.lineSeparator());
        out.append("Name=").append(parsed.name.replace('\n', ' ')).append(System.lineSeparator());
        if (parsed.format != null) {
            out.append("Deck Type=").append(parsed.format.name()).append(System.lineSeparator());
        }
        writeSection(out, "Main", parsed.main);
        writeSection(out, "Commander", parsed.commander);
        writeSection(out, "Sideboard", parsed.side);
        return out.toString();
    }

    private static void writeSection(StringBuilder out, String title, List<BattlefieldArenaList.CardLine> lines) {
        if (lines == null || lines.isEmpty()) return;
        out.append(System.lineSeparator()).append('[').append(title).append(']').append(System.lineSeparator());
        for (BattlefieldArenaList.CardLine line : lines) {
            out.append(line.count).append(' ').append(line.name).append(System.lineSeparator());
        }
    }

    private static Path uniqueFile(Path dir, String name) {
        String base = safeFile(name);
        Path file = dir.resolve(base + ".dck");
        int n = 2;
        while (Files.exists(file)) {
            file = dir.resolve(base + "-" + n + ".dck");
            n++;
        }
        return file;
    }

    private static String safeFile(String name) {
        String raw = name == null ? "" : name.trim();
        if (raw.isBlank()) raw = "Imported list";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < 32 || "<>:\"/\\|?*".indexOf(ch) >= 0) out.append(' ');
            else out.append(ch);
        }
        String cleaned = out.toString().trim().replaceAll(" +", " ");
        if (cleaned.isBlank()) cleaned = "Imported list";
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60).trim();
        return cleaned;
    }

    static Deck toDeck(Choice choice) {
        if (choice == null || choice.kind == Kind.GREEN_TEST) return greenTest();
        if (choice.kind == Kind.ASH_TEST) return ashTest();
        Deck deck = DeckSerializer.fromFile(choice.file.toFile());
        if (deck == null) {
            throw new IllegalStateException("Could not read " + choice.file.getFileName());
        }
        boolean any = false;
        for (Map.Entry<DeckSection, CardPool> section : deck) {
            if (section.getValue() != null && !section.getValue().isEmpty()) any = true;
        }
        if (!any) {
            String name = deck.getName() == null || deck.getName().isBlank()
                    ? choice.file.getFileName().toString() : deck.getName();
            throw new IllegalStateException(name + " has no cards Forge could read.");
        }
        return deck;
    }

    static String rulesProblem(Deck yours, Deck theirs) {
        GameType you = gameType(yours);
        GameType ash = gameType(theirs);
        if (you != ash) {
            return "Those decks use different rules (" + you + " and " + ash
                    + "). Pick two of the same kind.";
        }
        return null;
    }

    static GameType gameType(Deck deck) {
        DeckFormat format = deck == null ? null : deck.getDeckFormat();
        if (format == null) return GameType.Constructed;
        return switch (format) {
            case Commander -> GameType.Commander;
            case Brawl -> GameType.Brawl;
            case Oathbreaker -> GameType.Oathbreaker;
            case TinyLeaders -> GameType.TinyLeaders;
            default -> GameType.Constructed;
        };
    }

    /**
     * London mulligans that cost no card, from the deck's Forge format.
     * Commander and Brawl start at 1. Constructed and everything else start at 0.
     * Adventure rewards add more through {@link BattlefieldLive#grantFreeMulligans}.
     */
    static int formatFreeMulligans(GameType type) {
        if (type == GameType.Commander || type == GameType.Brawl) return 1;
        return 0;
    }

    private static Deck greenTest() {
        Deck yours = new Deck("Green test list");
        yours.getMain().add("Forest", 22);
        yours.getMain().add("Llanowar Elves", 4);
        yours.getMain().add("Grizzly Bears", 4);
        yours.getMain().add("Giant Growth", 2);
        yours.getMain().add("Genesis Wave", 4);
        return yours;
    }

    private static Deck ashTest() {
        Deck theirs = new Deck("Ash test list");
        theirs.getMain().add("Mountain", 24);
        theirs.getMain().add("Raging Goblin", 4);
        theirs.getMain().add("Shock", 4);
        theirs.getMain().add("Lightning Bolt", 4);
        return theirs;
    }

    private static String readDecksDir(Path profile) {
        if (!Files.isRegularFile(profile)) return null;
        Properties props = new Properties();
        try (var in = Files.newInputStream(profile)) {
            props.load(in);
        } catch (IOException failed) {
            return null;
        }
        String value = props.getProperty("decksDir");
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }
}
