package forge.adventure.util;

import com.badlogic.gdx.utils.Array;

import forge.adventure.data.ConfigData;
import forge.adventure.data.MaterialData;
import forge.adventure.data.MaterialListData;
import forge.adventure.player.AdventurePlayer;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ascendant Package A2: mana-reagent cost for crafting a card.
 * Dust cost stays in {@link AdventurePlayer#craftCost}; this is the material side.
 *
 * <ul>
 *   <li>One reagent per colored mana symbol at the rarity's tier (hybrid = either color;
 *       Phyrexian = its color).</li>
 *   <li>Colorless cards: ore equal to mana value, capped / min via ConfigData; scrap may replace ore.</li>
 *   <li>Lands / no-cost cards: one reagent per color identity, or 1 ore if colorless.</li>
 *   <li>Any-color mana producers: 1 Prismatic of the card's tier.</li>
 * </ul>
 */
public final class CardReagentCost {
    public enum Kind {
        /** Fixed color letter (W/U/B/R/G); any material with that color+tier pays. */
        COLOR,
        /** Hybrid: pay with any one of the listed colors. */
        HYBRID,
        /** Colorless ore/scrap pool. */
        COLORLESS,
        /** Prismatic reagent of the tier. */
        PRISMATIC
    }

    /** One requirement line (may be OR across colors for hybrids). */
    public static final class Line {
        public final Kind kind;
        public final int count;
        public final int tier;
        /** Single color for COLOR; ignored otherwise. */
        public final String color;
        /** Acceptable colors for HYBRID (uppercase letters). */
        public final List<String> colors;

        private Line(Kind kind, int count, int tier, String color, List<String> colors) {
            this.kind = kind;
            this.count = count;
            this.tier = tier;
            this.color = color;
            this.colors = colors != null ? colors : List.of();
        }

        public static Line color(String color, int count, int tier) {
            return new Line(Kind.COLOR, count, tier, color, null);
        }

        public static Line hybrid(List<String> colors, int count, int tier) {
            return new Line(Kind.HYBRID, count, tier, null, colors);
        }

        public static Line colorless(int count, int tier) {
            return new Line(Kind.COLORLESS, count, tier, "C", null);
        }

        public static Line prismatic(int count, int tier) {
            return new Line(Kind.PRISMATIC, count, tier, null, null);
        }
    }

    private final List<Line> lines;
    private final int tier;

    private CardReagentCost(List<Line> lines, int tier) {
        this.lines = lines;
        this.tier = tier;
    }

    public List<Line> getLines() {
        return lines;
    }

    public int getTier() {
        return tier;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    /** Build the reagent cost for a craft printing (rarity + rules from that card). */
    public static CardReagentCost forCard(PaperCard card) {
        if (card == null || card.getRules() == null)
            return new CardReagentCost(List.of(), 0);
        CardRarity rarity = card.getRarity();
        int tier = MaterialListData.reagentTierForRarity(rarity);
        if (tier <= 0)
            return new CardReagentCost(List.of(), 0);

        CardRules rules = card.getRules();
        if (producesAnyColorMana(rules))
            return new CardReagentCost(List.of(Line.prismatic(1, tier)), tier);

        ManaCost manaCost = rules.getManaCost();
        // Lands and other cards without a mana cost: color identity (or 1 ore if colorless).
        if (manaCost == null || manaCost.isNoCost()) {
            return fromColorIdentity(rules.getColorIdentity(), tier);
        }

        List<Line> fromCost = fromManaCost(manaCost, tier);
        if (!fromCost.isEmpty())
            return new CardReagentCost(mergeLines(fromCost), tier);

        // No colored symbols: colorless → ore by CMC; colored indicator / similar → identity colors.
        if (rules.getColor() == null || rules.getColor().isColorless()) {
            int ore = colorlessOreAmount(manaCost != null ? manaCost.getCMC() : 0);
            return new CardReagentCost(List.of(Line.colorless(ore, tier)), tier);
        }
        return fromColorIdentity(rules.getColorIdentity(), tier);
    }

    private static CardReagentCost fromColorIdentity(ColorSet identity, int tier) {
        if (identity == null || identity.isColorless()) {
            int ore = colorlessOreAmount(0); // lands / no-cost colorless → minimum ore
            return new CardReagentCost(List.of(Line.colorless(ore, tier)), tier);
        }
        List<Line> lines = new ArrayList<>();
        for (MagicColor.Color c : identity.getOrderedColors()) {
            String letter = colorLetter(c.getColorMask());
            if (letter != null)
                lines.add(Line.color(letter, 1, tier));
        }
        return new CardReagentCost(lines, tier);
    }

    private static List<Line> fromManaCost(ManaCost manaCost, int tier) {
        List<Line> lines = new ArrayList<>();
        if (manaCost == null || manaCost.isNoCost())
            return lines;
        for (ManaCostShard shard : manaCost) {
            if (shard == null)
                continue;
            byte mask = shard.getColorMask();
            if (mask == 0)
                continue; // generic, X, snow, pure C — not colored reagents
            List<String> colors = colorsFromMask(mask);
            if (colors.isEmpty())
                continue;
            if (shard.isPhyrexian() && colors.size() == 1) {
                // Mono Phyrexian: need that color.
                lines.add(Line.color(colors.get(0), 1, tier));
            } else if (colors.size() > 1 || shard.isHybrid()) {
                // Hybrid (incl. hybrid Phyrexian, {2/W}, {C/W}): either color.
                lines.add(Line.hybrid(colors, 1, tier));
            } else {
                lines.add(Line.color(colors.get(0), 1, tier));
            }
        }
        return lines;
    }

    private static List<Line> mergeLines(List<Line> raw) {
        // Merge identical COLOR lines; leave hybrids/prismatic/colorless as separate entries.
        Map<String, Integer> colorCounts = new LinkedHashMap<>();
        List<Line> out = new ArrayList<>();
        for (Line line : raw) {
            if (line.kind == Kind.COLOR && line.color != null) {
                colorCounts.merge(line.color, line.count, Integer::sum);
            } else {
                out.add(line);
            }
        }
        List<Line> merged = new ArrayList<>();
        for (Map.Entry<String, Integer> e : colorCounts.entrySet())
            merged.add(Line.color(e.getKey(), e.getValue(), raw.isEmpty() ? 1 : raw.get(0).tier));
        merged.addAll(out);
        return merged;
    }

    private static int colorlessOreAmount(int manaValue) {
        ConfigData config = Config.instance().getConfigData();
        int min = Math.max(1, config.colorlessOreMin);
        int cap = Math.max(min, config.colorlessOreCap);
        int mv = Math.max(0, manaValue);
        if (mv <= 0)
            return min;
        return Math.min(cap, Math.max(min, mv));
    }

    /**
     * True when a mana ability adds mana of any color ({@code Produced$ Any})
     * or the oracle describes adding mana of any color (Command Tower, Exotic Orchard, …).
     */
    public static boolean producesAnyColorMana(CardRules rules) {
        if (rules == null)
            return false;
        if (faceProducesAny(rules.getMainPart()))
            return true;
        if (faceProducesAny(rules.getOtherPart()))
            return true;
        String oracle = rules.getOracleText();
        if (oracle != null) {
            String lower = oracle.toLowerCase(Locale.ROOT);
            if (lower.contains("mana of any color"))
                return true;
        }
        return false;
    }

    private static boolean faceProducesAny(ICardFace face) {
        if (face == null)
            return false;
        Iterable<String> abilities = face.getAbilities();
        if (abilities == null)
            return false;
        for (String ab : abilities) {
            if (ab == null)
                continue;
            String compact = ab.replace(" ", "");
            if (compact.contains("Produced$Any") || compact.contains("Produced$ComboAny"))
                return true;
        }
        return false;
    }

    private static List<String> colorsFromMask(byte mask) {
        List<String> out = new ArrayList<>(5);
        if ((mask & MagicColor.WHITE) != 0)
            out.add("W");
        if ((mask & MagicColor.BLUE) != 0)
            out.add("U");
        if ((mask & MagicColor.BLACK) != 0)
            out.add("B");
        if ((mask & MagicColor.RED) != 0)
            out.add("R");
        if ((mask & MagicColor.GREEN) != 0)
            out.add("G");
        return out;
    }

    private static String colorLetter(byte mask) {
        List<String> c = colorsFromMask(mask);
        return c.isEmpty() ? null : c.get(0);
    }

    /** Total owned units that can pay a COLOR line. */
    public static int ownedForColor(AdventurePlayer player, String color, int tier) {
        int sum = 0;
        for (MaterialData m : new Array.ArrayIterator<>(MaterialListData.reagentsForColorTier(color, tier)))
            sum += player.getMaterial(m.id);
        return sum;
    }

    /** Ore + scrap owned for a colorless line. */
    public static int ownedColorless(AdventurePlayer player, int tier) {
        int sum = 0;
        MaterialData ore = MaterialListData.oreForTier(tier);
        MaterialData scrap = MaterialListData.scrapForTier(tier);
        if (ore != null)
            sum += player.getMaterial(ore.id);
        if (scrap != null)
            sum += player.getMaterial(scrap.id);
        return sum;
    }

    public static int ownedPrismatic(AdventurePlayer player, int tier) {
        MaterialData p = MaterialListData.prismaticForTier(tier);
        return p != null ? player.getMaterial(p.id) : 0;
    }

    public boolean canAfford(AdventurePlayer player) {
        return missing(player).isEmpty();
    }

    /**
     * Human-readable missing pieces for UI (empty if affordable).
     */
    public List<String> missing(AdventurePlayer player) {
        List<String> out = new ArrayList<>();
        if (player == null)
            return List.of("No player");
        for (Line line : lines) {
            int have = ownedForLine(player, line);
            if (have >= line.count)
                continue;
            out.add("Need " + line.count + "× " + labelForLine(line) + " (have " + have + ")");
        }
        return out;
    }

    public int ownedForLine(AdventurePlayer player, Line line) {
        return switch (line.kind) {
            case COLOR -> ownedForColor(player, line.color, line.tier);
            case HYBRID -> {
                int best = 0;
                for (String c : line.colors)
                    best = Math.max(best, ownedForColor(player, c, line.tier));
                // Hybrids need `count` units from a single chosen color (or split? → single color pick).
                // Paying count across colors: allow summing across OR colors for affordability of count=1
                // typical hybrids are count=1. For count>1, require best single color >= count
                // (each hybrid symbol is its own line with count 1 after fromManaCost).
                yield best;
            }
            case COLORLESS -> ownedColorless(player, line.tier);
            case PRISMATIC -> ownedPrismatic(player, line.tier);
        };
    }

    /** Preferred display label (primary material name, or "Ore/Scrap", or Prismatic). */
    public static String labelForLine(Line line) {
        return switch (line.kind) {
            case COLOR -> {
                MaterialData primary = MaterialListData.primaryReagent(line.color, line.tier);
                yield primary != null ? primary.getDisplayName() : ("color " + line.color);
            }
            case HYBRID -> {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < line.colors.size(); i++) {
                    if (i > 0)
                        sb.append(" or ");
                    MaterialData primary = MaterialListData.primaryReagent(line.colors.get(i), line.tier);
                    sb.append(primary != null ? primary.getDisplayName() : line.colors.get(i));
                }
                yield sb.toString();
            }
            case COLORLESS -> {
                MaterialData ore = MaterialListData.oreForTier(line.tier);
                MaterialData scrap = MaterialListData.scrapForTier(line.tier);
                String o = ore != null ? ore.getDisplayName() : "Ore";
                String s = scrap != null ? scrap.getDisplayName() : "Scrap";
                yield o + "/" + s;
            }
            case PRISMATIC -> {
                MaterialData p = MaterialListData.prismaticForTier(line.tier);
                yield p != null ? p.getDisplayName() : ("Prismatic T" + line.tier);
            }
        };
    }

    /**
     * Resolve concrete material id → count to spend. Empty if not affordable.
     * Prefers primary-family reagents, then alts; ore before scrap.
     */
    public Map<String, Integer> resolvePayment(AdventurePlayer player) {
        Map<String, Integer> pay = new LinkedHashMap<>();
        if (player == null || !canAfford(player))
            return pay;
        for (Line line : lines) {
            if (!takeInto(pay, player, line)) {
                pay.clear();
                return pay;
            }
        }
        return pay;
    }

    private boolean takeInto(Map<String, Integer> pay, AdventurePlayer player, Line line) {
        int need = line.count;
        return switch (line.kind) {
            case COLOR -> allocateColor(pay, player, line.color, line.tier, need);
            case HYBRID -> {
                // Pick the OR color the player can cover best (most owned after already reserved).
                String best = null;
                int bestHave = -1;
                for (String c : line.colors) {
                    int have = ownedForColor(player, c, line.tier) - reservedForColor(pay, c, line.tier);
                    if (have > bestHave) {
                        bestHave = have;
                        best = c;
                    }
                }
                yield best != null && allocateColor(pay, player, best, line.tier, need);
            }
            case COLORLESS -> allocateColorless(pay, player, line.tier, need);
            case PRISMATIC -> {
                MaterialData p = MaterialListData.prismaticForTier(line.tier);
                if (p == null)
                    yield false;
                int have = player.getMaterial(p.id) - pay.getOrDefault(p.id, 0);
                if (have < need)
                    yield false;
                pay.merge(p.id, need, Integer::sum);
                yield true;
            }
        };
    }

    private static int reservedForColor(Map<String, Integer> pay, String color, int tier) {
        int n = 0;
        for (MaterialData m : new Array.ArrayIterator<>(MaterialListData.reagentsForColorTier(color, tier)))
            n += pay.getOrDefault(m.id, 0);
        return n;
    }

    private static boolean allocateColor(Map<String, Integer> pay, AdventurePlayer player,
                                         String color, int tier, int need) {
        int remaining = need;
        for (MaterialData m : new Array.ArrayIterator<>(MaterialListData.reagentsForColorTier(color, tier))) {
            int have = player.getMaterial(m.id) - pay.getOrDefault(m.id, 0);
            if (have <= 0)
                continue;
            int take = Math.min(have, remaining);
            pay.merge(m.id, take, Integer::sum);
            remaining -= take;
            if (remaining <= 0)
                return true;
        }
        return remaining <= 0;
    }

    private static boolean allocateColorless(Map<String, Integer> pay, AdventurePlayer player,
                                             int tier, int need) {
        int remaining = need;
        MaterialData ore = MaterialListData.oreForTier(tier);
        MaterialData scrap = MaterialListData.scrapForTier(tier);
        if (ore != null) {
            int have = player.getMaterial(ore.id) - pay.getOrDefault(ore.id, 0);
            int take = Math.min(Math.max(0, have), remaining);
            if (take > 0) {
                pay.merge(ore.id, take, Integer::sum);
                remaining -= take;
            }
        }
        if (remaining > 0 && scrap != null) {
            int have = player.getMaterial(scrap.id) - pay.getOrDefault(scrap.id, 0);
            int take = Math.min(Math.max(0, have), remaining);
            if (take > 0) {
                pay.merge(scrap.id, take, Integer::sum);
                remaining -= take;
            }
        }
        return remaining <= 0;
    }

    /** Compact summary for list suffixes, e.g. {@code 2 Wild herbs + 1 Limestone}. */
    public String shortSummary() {
        if (lines.isEmpty())
            return "no reagents";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0)
                sb.append(" + ");
            Line line = lines.get(i);
            sb.append(line.count).append(" ").append(labelForLine(line));
        }
        return sb.toString();
    }

    /**
     * Detail line with have/need counts for the craft screen.
     * {@code anyMissing} is true when at least one line is short.
     */
    public String detailSummary(AdventurePlayer player) {
        if (lines.isEmpty())
            return "Reagents: none";
        StringBuilder sb = new StringBuilder("Reagents: ");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0)
                sb.append(", ");
            Line line = lines.get(i);
            int have = ownedForLine(player, line);
            sb.append(labelForLine(line)).append(" ").append(have).append("/").append(line.count);
        }
        return sb.toString();
    }

    public boolean anyMissing(AdventurePlayer player) {
        return !canAfford(player);
    }
}
