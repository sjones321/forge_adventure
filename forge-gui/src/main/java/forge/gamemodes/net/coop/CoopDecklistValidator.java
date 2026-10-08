package forge.gamemodes.net.coop;

import forge.deck.Deck;
import forge.deck.io.DeckSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Parse and validate a {@link DeckSerializer} text payload for CO3. Rejects
 * oversized decks and card names that fail the supplied card-DB check. Never
 * accepts live {@code PaperCard}/{@code Deck} objects from the wire.
 *
 * <p>Counts and names are taken from the text lines so headless tests (and hosts
 * before deferred section load) do not depend on FModel being initialised.
 */
public final class CoopDecklistValidator {
    public enum RejectReason {
        OK,
        NULL_OR_EMPTY,
        TOO_LARGE_TEXT,
        PARSE_FAILED,
        TOO_MANY_MAIN,
        TOO_MANY_SIDEBOARD,
        BAD_CARD_NAME,
        EMPTY_MAIN
    }

    public static final class Result {
        public final RejectReason reason;
        public final Deck deck;
        public final String detail;

        Result(final RejectReason reason, final Deck deck, final String detail) {
            this.reason = reason;
            this.deck = deck;
            this.detail = detail != null ? detail : "";
        }

        public boolean ok() {
            return reason == RejectReason.OK && deck != null;
        }
    }

    private CoopDecklistValidator() {
    }

    /**
     * @param cardNameOk returns true when the card name exists in the local card DB
     *                   (tests may accept any non-empty name or an allowlist)
     */
    public static Result validate(final String decklistText, final Predicate<String> cardNameOk) {
        if (decklistText == null || decklistText.isEmpty()) {
            return new Result(RejectReason.NULL_OR_EMPTY, null, "empty");
        }
        if (!CoopDuelWireLimits.decklistSizeOk(decklistText)) {
            return new Result(RejectReason.TOO_LARGE_TEXT, null, "decklist too large");
        }

        final SectionCounts counts = countSections(decklistText);
        if (counts.mainCards <= 0) {
            return new Result(RejectReason.EMPTY_MAIN, null, "empty main");
        }
        if (counts.mainCards > CoopDuelWireLimits.MAX_DECK_CARDS) {
            return new Result(RejectReason.TOO_MANY_MAIN, null, "main " + counts.mainCards);
        }
        if (counts.sideboardCards > CoopDuelWireLimits.MAX_SIDEBOARD_CARDS) {
            return new Result(RejectReason.TOO_MANY_SIDEBOARD, null, "sideboard " + counts.sideboardCards);
        }

        final Predicate<String> check = cardNameOk != null ? cardNameOk : name -> name != null && !name.isEmpty();
        for (final String name : counts.cardNames) {
            if (name == null || name.isEmpty() || name.length() > CoopDuelWireLimits.MAX_TEXT_LEN) {
                return new Result(RejectReason.BAD_CARD_NAME, null, String.valueOf(name));
            }
            if (!check.test(name)) {
                return new Result(RejectReason.BAD_CARD_NAME, null, name);
            }
        }

        final Deck deck;
        try {
            deck = DeckSerializer.fromDecklistText(decklistText);
        } catch (final Exception e) {
            return new Result(RejectReason.PARSE_FAILED, null, "parse failed");
        }
        if (deck == null) {
            return new Result(RejectReason.PARSE_FAILED, null, "parse returned null");
        }
        return new Result(RejectReason.OK, deck, "");
    }

    /** Round-trip helper for tests: serialize then validate. */
    public static Result roundTrip(final Deck deck, final Predicate<String> cardNameOk) {
        if (deck == null) {
            return new Result(RejectReason.NULL_OR_EMPTY, null, "null deck");
        }
        final String text = DeckSerializer.toDecklistText(deck);
        return validate(text, cardNameOk);
    }

    /** Case-insensitive allowlist predicate from known names (tests). */
    public static Predicate<String> allowlist(final String... names) {
        final java.util.Set<String> set = new java.util.HashSet<>();
        if (names != null) {
            for (final String n : names) {
                if (n != null) {
                    set.add(n.toLowerCase(Locale.ROOT));
                }
            }
        }
        return name -> name != null && set.contains(name.toLowerCase(Locale.ROOT));
    }

    private static final class SectionCounts {
        int mainCards;
        int sideboardCards;
        final List<String> cardNames = new ArrayList<>();
    }

    /**
     * Scan DeckSerializer-style text for {@code [Main]} / {@code [Sideboard]} card
     * lines: {@code N Card Name} or {@code Card Name}.
     */
    static SectionCounts countSections(final String text) {
        final SectionCounts out = new SectionCounts();
        String section = "";
        for (final String raw : text.split("\\R", -1)) {
            final String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1).trim().toLowerCase(Locale.ROOT);
                continue;
            }
            if ("metadata".equals(section) || "general".equals(section) || section.isEmpty()) {
                continue;
            }
            final ParsedLine parsed = parseCardLine(line);
            if (parsed == null) {
                continue;
            }
            out.cardNames.add(parsed.name);
            if ("main".equals(section) || "cards".equals(section)) {
                out.mainCards += parsed.count;
            } else if ("sideboard".equals(section)) {
                out.sideboardCards += parsed.count;
            } else {
                // Other sections (Commander, etc.) still contribute names for DB checks
                // but do not count toward main/sideboard caps beyond command limits.
                out.mainCards += 0;
            }
        }
        return out;
    }

    private static final class ParsedLine {
        final int count;
        final String name;

        ParsedLine(final int count, final String name) {
            this.count = count;
            this.name = name;
        }
    }

    private static ParsedLine parseCardLine(final String line) {
        // "4 Island" / "Island" / "1x Island" / "SB: 1 Island"
        String s = line;
        if (s.regionMatches(true, 0, "SB:", 0, 3)) {
            s = s.substring(3).trim();
        }
        if (s.isEmpty() || s.startsWith("#") || s.contains("=")) {
            return null;
        }
        int count = 1;
        String name = s;
        final int sp = s.indexOf(' ');
        if (sp > 0) {
            String first = s.substring(0, sp).trim();
            if (first.endsWith("x") || first.endsWith("X")) {
                first = first.substring(0, first.length() - 1);
            }
            try {
                count = Integer.parseInt(first);
                name = s.substring(sp + 1).trim();
            } catch (final NumberFormatException ignored) {
                count = 1;
                name = s;
            }
        }
        // Strip set codes in parentheses if present: "Island (LEA)"
        final int paren = name.indexOf('(');
        if (paren > 0) {
            name = name.substring(0, paren).trim();
        }
        if (name.isEmpty() || count < 1) {
            return null;
        }
        if (count > CoopDuelWireLimits.MAX_DECK_CARDS) {
            count = CoopDuelWireLimits.MAX_DECK_CARDS + 1; // force TOO_MANY
        }
        return new ParsedLine(count, name);
    }
}
