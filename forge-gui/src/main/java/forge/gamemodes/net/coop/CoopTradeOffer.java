package forge.gamemodes.net.coop;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain-data trade offer (TR1). No {@code PaperCard}, {@code ItemData}, or other
 * game graphs — card/item identity travels as capped strings so the wire never
 * Java-deserializes untrusted object graphs.
 */
public final class CoopTradeOffer implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int gold;
    private final List<Line> materials;
    private final List<Line> items;
    private final List<CardLine> cards;

    public CoopTradeOffer(final int gold, final List<Line> materials, final List<Line> items,
                          final List<CardLine> cards) {
        this.gold = Math.max(0, gold);
        this.materials = freezeLines(materials);
        this.items = freezeLines(items);
        this.cards = freezeCards(cards);
    }

    public static CoopTradeOffer empty() {
        return new CoopTradeOffer(0, null, null, null);
    }

    public int getGold() {
        return gold;
    }

    public List<Line> getMaterials() {
        return materials;
    }

    public List<Line> getItems() {
        return items;
    }

    public List<CardLine> getCards() {
        return cards;
    }

    public boolean isEmpty() {
        return gold <= 0 && materials.isEmpty() && items.isEmpty() && cards.isEmpty();
    }

    public int totalLines() {
        return materials.size() + items.size() + cards.size() + (gold > 0 ? 1 : 0);
    }

    /** Material or item stack: id/name + count (+ optional claimed available). */
    public static final class Line implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id;
        private final int count;
        /** Client-attested owned/tradeable count (host ownership check). */
        private final int available;

        public Line(final String id, final int count, final int available) {
            this.id = id == null ? "" : id;
            this.count = Math.max(0, count);
            this.available = Math.max(0, available);
        }

        public Line(final String id, final int count) {
            this(id, count, count);
        }

        public String getId() {
            return id;
        }

        public int getCount() {
            return count;
        }

        public int getAvailable() {
            return available;
        }
    }

    /** Card identity without {@code PaperCard}: name + set + art index. */
    public static final class CardLine implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String name;
        private final String setCode;
        private final int artIndex;
        private final int count;
        private final int available;

        public CardLine(final String name, final String setCode, final int artIndex,
                        final int count, final int available) {
            this.name = name == null ? "" : name;
            this.setCode = setCode == null ? "" : setCode;
            this.artIndex = Math.max(0, artIndex);
            this.count = Math.max(0, count);
            this.available = Math.max(0, available);
        }

        public CardLine(final String name, final String setCode, final int artIndex, final int count) {
            this(name, setCode, artIndex, count, count);
        }

        public String getName() {
            return name;
        }

        public String getSetCode() {
            return setCode;
        }

        public int getArtIndex() {
            return artIndex;
        }

        public int getCount() {
            return count;
        }

        public int getAvailable() {
            return available;
        }

        /** Stable key for bag maps (name|set|art). */
        public String key() {
            return name + '|' + setCode + '|' + artIndex;
        }
    }

    private static List<Line> freezeLines(final List<Line> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        final List<Line> copy = new ArrayList<>(src.size());
        for (final Line line : src) {
            if (line != null) {
                copy.add(line);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<CardLine> freezeCards(final List<CardLine> src) {
        if (src == null || src.isEmpty()) {
            return Collections.emptyList();
        }
        final List<CardLine> copy = new ArrayList<>(src.size());
        for (final CardLine line : src) {
            if (line != null) {
                copy.add(line);
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
