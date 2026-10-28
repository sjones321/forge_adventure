package forge.app;

import forge.deck.DeckFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arena export text, and lists written so they can be pasted into Arena.
 * No card database. Names go into a .dck and Forge resolves them at match start.
 */
final class BattlefieldArenaList {
    enum Section { MAIN, SIDE, COMMANDER, SKIP }

    static final class CardLine {
        final int count;
        final String name;

        CardLine(int count, String name) {
            this.count = count;
            this.name = name;
        }
    }

    static final class Parsed {
        final String name;
        final DeckFormat format;
        final List<CardLine> main;
        final List<CardLine> side;
        final List<CardLine> commander;
        final String error;

        private Parsed(String name, DeckFormat format,
                List<CardLine> main, List<CardLine> side, List<CardLine> commander, String error) {
            this.name = name;
            this.format = format;
            this.main = main;
            this.side = side;
            this.commander = commander;
            this.error = error;
        }

        static Parsed fail(String error) {
            return new Parsed("", DeckFormat.Constructed, List.of(), List.of(), List.of(), error);
        }

        int cards() {
            return count(main) + count(side) + count(commander);
        }

        private static int count(List<CardLine> lines) {
            int n = 0;
            for (CardLine line : lines) n += line.count;
            return n;
        }
    }

    private static final Pattern NAME_LINE = Pattern.compile(
            "^(?:name\\s*:\\s*|name\\s+|~)\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern FORMAT_LINE = Pattern.compile(
            "^(?:format|deck\\s*type)\\s*:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNTED = Pattern.compile(
            "^(?:(SB|MB|CM)\\s*:\\s*)?(?:(\\d{1,3})\\s*[xX]?\\s+)?(.+?)\\s*$");
    private static final Pattern SET_TAIL = Pattern.compile(
            "\\s+(?:[\\(\\[\\{][A-Za-z0-9]{2,6}[\\)\\]\\}]|\\|\\s*[A-Za-z0-9]{2,6})(?:\\s+\\S+)?\\s*(?:\\*[FE]\\*|\\(F\\))?\\s*$");
    private static final Pattern FOIL_TAIL = Pattern.compile("\\s+(?:\\*[FE]\\*|\\(F\\))\\s*$");
    private static final Pattern TOTALS = Pattern.compile(
            "^\\d+\\s+cards?\\b", Pattern.CASE_INSENSITIVE);

    static boolean looksLikeDeck(String text) {
        if (text == null || text.isBlank()) return false;
        String trimmed = text.trim();
        if (looksLikeAddress(trimmed) || looksLikePersonName(trimmed)) return false;
        int cards = 0;
        boolean section = false;
        for (String raw : trimmed.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || isNoise(line)) continue;
            if (sectionOf(line) != null) {
                if (sectionOf(line) != Section.SKIP) section = true;
                continue;
            }
            if (NAME_LINE.matcher(line).matches() || FORMAT_LINE.matcher(line).matches()) continue;
            CardLine card = cardLine(line, Section.MAIN);
            if (card != null) cards++;
        }
        return cards >= 2 || (cards >= 1 && (section || trimmed.contains("(")));
    }

    static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return Parsed.fail("Copy a list from Arena first, then Import.");
        }
        String name = "";
        DeckFormat hinted = null;
        Section section = Section.MAIN;
        boolean sawHeader = false;
        List<CardLine> main = new ArrayList<>();
        List<CardLine> side = new ArrayList<>();
        List<CardLine> commander = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = stripBom(raw).trim();
            if (line.isEmpty() || isNoise(line)) continue;
            Matcher named = NAME_LINE.matcher(line);
            if (named.matches()) {
                String title = cleanTitle(named.group(1));
                if (!title.isBlank()) name = title;
                continue;
            }
            Matcher fmt = FORMAT_LINE.matcher(line);
            if (fmt.matches()) {
                hinted = formatOf(fmt.group(1));
                continue;
            }
            Section header = sectionOf(line);
            if (header != null) {
                section = header;
                sawHeader = true;
                continue;
            }
            if (!sawHeader && name.isBlank() && looksLikeTitle(line)) {
                name = cleanTitle(line);
                continue;
            }
            CardLine card = cardLine(line, section);
            if (card == null) continue;
            Section dest = destFor(line, section);
            if (dest == Section.SKIP) continue;
            switch (dest) {
                case SIDE -> side.add(card);
                case COMMANDER -> commander.add(card);
                default -> main.add(card);
            }
            sawHeader = true;
        }
        if (main.isEmpty() && commander.isEmpty()) {
            return Parsed.fail("That paste is not a deck list. Copy the list from Arena, then Import.");
        }
        if (name.isBlank() || name.equalsIgnoreCase("deck")) name = "Imported list";
        DeckFormat format = hinted != null ? hinted : inferFormat(name, main, commander);
        return new Parsed(name, format, List.copyOf(main), List.copyOf(side), List.copyOf(commander), null);
    }

    private static Section destFor(String line, Section section) {
        String upper = line.trim().toUpperCase(Locale.ROOT);
        if (upper.startsWith("SB:") || upper.startsWith("SB :")) return Section.SIDE;
        if (upper.startsWith("CM:") || upper.startsWith("CM :") || line.contains("#!Commander")) {
            return Section.COMMANDER;
        }
        return section;
    }

    private static CardLine cardLine(String line, Section section) {
        if (section == Section.SKIP) return null;
        String work = line.replace("#!Commander", "").trim();
        Matcher m = COUNTED.matcher(work);
        if (!m.matches()) return null;
        String rest = m.group(3);
        if (rest == null) return null;
        rest = FOIL_TAIL.matcher(rest).replaceFirst("");
        rest = SET_TAIL.matcher(rest).replaceFirst("");
        rest = rest.replace('\u00a0', ' ').trim();
        if (rest.isBlank() || !hasLetter(rest) || rest.length() > 120) return null;
        if (sectionOf(rest) != null) return null;
        int count = 1;
        if (m.group(2) != null) {
            try {
                count = Integer.parseInt(m.group(2));
            } catch (NumberFormatException ignored) {
                count = 1;
            }
        }
        if (count < 1 || count > 99) return null;
        return new CardLine(count, rest);
    }

    private static Section sectionOf(String line) {
        String key = line.toLowerCase(Locale.ROOT)
                .replaceAll("^[/#*~]+\\s*", "")
                .replaceAll("[:\\s]+$", "");
        return switch (key) {
            case "deck", "main", "mainboard", "main board", "cards", "card" -> Section.MAIN;
            case "side", "sideboard", "side board", "sb" -> Section.SIDE;
            case "commander", "commanders", "command" -> Section.COMMANDER;
            case "companion", "companions" -> Section.SIDE;
            case "about", "maybeboard", "maybe board", "considering" -> Section.SKIP;
            default -> null;
        };
    }

    private static DeckFormat formatOf(String raw) {
        if (raw == null) return null;
        String key = raw.toLowerCase(Locale.ROOT);
        if (key.contains("historic brawl") || key.contains("standard brawl") || key.contains("brawl")) {
            return DeckFormat.Brawl;
        }
        if (key.contains("commander") || key.contains("edh")) return DeckFormat.Commander;
        if (key.contains("oathbreaker")) return DeckFormat.Oathbreaker;
        if (key.contains("tiny")) return DeckFormat.TinyLeaders;
        if (key.contains("standard") || key.contains("pioneer") || key.contains("modern")
                || key.contains("legacy") || key.contains("vintage") || key.contains("pauper")
                || key.contains("historic") || key.contains("explorer") || key.contains("alchemy")
                || key.contains("constructed")) {
            return DeckFormat.Constructed;
        }
        return null;
    }

    private static DeckFormat inferFormat(String name, List<CardLine> main, List<CardLine> commander) {
        if (commander.isEmpty()) return DeckFormat.Constructed;
        if (name != null && name.toLowerCase(Locale.ROOT).contains("brawl")) return DeckFormat.Brawl;
        int body = Parsed.count(main) + Parsed.count(commander);
        // Arena Brawl is 60. Historic Brawl and Commander are 100. 100 + a commander
        // is tagged Commander here; put Format: Brawl on the list if it is Historic Brawl.
        if (body < 80) return DeckFormat.Brawl;
        return DeckFormat.Commander;
    }

    private static boolean looksLikeTitle(String line) {
        Matcher m = COUNTED.matcher(line);
        if (m.matches() && m.group(2) != null) return false;
        if (line.length() > 80 || !hasLetter(line)) return false;
        return !line.contains("(") && !line.contains("[");
    }

    private static boolean looksLikeAddress(String text) {
        String first = text.split("\\R", 2)[0].trim();
        return first.matches("(?i)^[\\w.\\-]+\\.[a-z]{2,}(?::\\d+)?$")
                || first.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?$")
                || first.matches("(?i)^[\\w.\\-]+:\\d{2,5}$");
    }

    private static boolean looksLikePersonName(String text) {
        String first = text.split("\\R", 2)[0].trim();
        return text.split("\\R").length == 1 && first.length() <= 40 && !first.matches(".*\\d.*")
                && cardLine(first, Section.MAIN) == null;
    }

    private static boolean isNoise(String line) {
        if (line.startsWith("#") || line.startsWith("//") || line.startsWith(";")) return true;
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("created") || lower.startsWith("exported")
                || lower.startsWith("copied from") || lower.startsWith("https://")
                || lower.startsWith("http://")) {
            return true;
        }
        return TOTALS.matcher(line).find();
    }

    private static boolean hasLetter(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) return true;
        }
        return false;
    }

    private static String cleanTitle(String raw) {
        if (raw == null) return "";
        String title = raw.replace('\u00a0', ' ').trim();
        title = title.replaceAll("[\\[\\]\"']", "");
        if (title.equalsIgnoreCase("deck") || title.equalsIgnoreCase("about")) return "";
        if (title.length() > 80) title = title.substring(0, 80).trim();
        return title;
    }

    private static String stripBom(String line) {
        if (line != null && !line.isEmpty() && line.charAt(0) == '\ufeff') {
            return line.substring(1);
        }
        return line;
    }
}
