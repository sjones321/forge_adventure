package forge.adventure.util;

import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * INV1 side-by-side item compare: marks each stat better (green ↑) / worse (red ↓) / equal.
 * Opponent effects are compared field-by-field; start-of-battle and command-zone cards by name
 * (added / removed). Includes {@code colorView}. Works for gear, tools and jewelry.
 */
public final class ItemCompare {
    private ItemCompare() {}

    public enum Trend {
        BETTER, WORSE, EQUAL, INFO
    }

    public static final class Diff {
        public final String label;
        public final String left;
        public final String right;
        public final Trend trend;

        public Diff(String label, String left, String right, Trend trend) {
            this.label = label;
            this.left = left;
            this.right = right;
            this.trend = trend;
        }
    }

    /** Compare {@code candidate} against {@code equipped}. Null sides are treated as empty. */
    public static List<Diff> compare(ItemData candidate, ItemData equipped) {
        List<Diff> out = new ArrayList<>();
        EffectData a = candidate != null ? candidate.effect : null;
        EffectData b = equipped != null ? equipped.effect : null;

        out.add(intDiff("Life", life(a), life(b), true));
        out.add(intDiff("Starting cards", startCards(a), startCards(b), true));
        out.add(pctDiff("Move speed", moveSpeed(a), moveSpeed(b), true));
        out.add(goldDiff(a, b));
        out.add(intDiff("Card rewards", cardRewards(a), cardRewards(b), true));
        out.add(intDiff("Mana shards", manaShards(a), manaShards(b), true));
        out.add(intDiff("Mulligans", mulligans(a), mulligans(b), true));
        out.add(boolDiff("Manasight (colorView)", colorView(a), colorView(b), true));
        out.addAll(cardNameDiffs("Start-of-battle", battleCards(a), battleCards(b)));
        out.addAll(cardNameDiffs("Command zone", commandCards(a), commandCards(b)));
        out.addAll(opponentFieldDiffs(a, b));
        out.add(socketDiff(candidate, equipped));
        out.add(toolTierDiff(candidate, equipped));
        out.add(gatheringDiff(candidate, equipped));
        return out;
    }

    /** Markup for Ascendant inventory labels: green ↑ / red ↓. */
    public static String formatMarkup(Diff d) {
        if (d == null)
            return "";
        String arrow;
        String color;
        switch (d.trend) {
            case BETTER:
                arrow = "↑";
                color = "66ff66";
                break;
            case WORSE:
                arrow = "↓";
                color = "ff6666";
                break;
            case INFO:
                arrow = "±";
                color = "ffcc66";
                break;
            default:
                arrow = "=";
                color = "cccccc";
                break;
        }
        return "[#" + color + "]" + d.label + ": " + d.left + " → " + d.right + " " + arrow + "[]";
    }

    public static String formatBlock(List<Diff> diffs) {
        if (diffs == null || diffs.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for (Diff d : diffs) {
            if (d.trend == Trend.EQUAL && isBlankPair(d))
                continue;
            if (sb.length() > 0)
                sb.append('\n');
            sb.append(formatMarkup(d));
        }
        return sb.toString();
    }

    private static boolean isBlankPair(Diff d) {
        return ("0".equals(d.left) || "—".equals(d.left) || "1.00×".equals(d.left) || "none".equals(d.left)
                || "off".equals(d.left) || "".equals(d.left))
                && ("0".equals(d.right) || "—".equals(d.right) || "1.00×".equals(d.right) || "none".equals(d.right)
                || "off".equals(d.right) || "".equals(d.right));
    }

    private static Diff intDiff(String label, int cand, int eq, boolean higherIsBetter) {
        Trend t = Trend.EQUAL;
        if (cand != eq) {
            boolean better = higherIsBetter ? cand > eq : cand < eq;
            t = better ? Trend.BETTER : Trend.WORSE;
        }
        return new Diff(label, String.valueOf(eq), String.valueOf(cand), t);
    }

    private static Diff pctDiff(String label, float cand, float eq, boolean higherIsBetter) {
        Trend t = Trend.EQUAL;
        if (Float.compare(cand, eq) != 0) {
            boolean better = higherIsBetter ? cand > eq : cand < eq;
            t = better ? Trend.BETTER : Trend.WORSE;
        }
        return new Diff(label, String.format("%.2f×", eq), String.format("%.2f×", cand), t);
    }

    private static Diff boolDiff(String label, boolean cand, boolean eq, boolean trueIsBetter) {
        Trend t = Trend.EQUAL;
        if (cand != eq) {
            boolean better = trueIsBetter ? cand : !cand;
            t = better ? Trend.BETTER : Trend.WORSE;
        }
        return new Diff(label, eq ? "on" : "off", cand ? "on" : "off", t);
    }

    private static Diff goldDiff(EffectData a, EffectData b) {
        float cand = gold(a);
        float eq = gold(b);
        Trend t = Trend.EQUAL;
        if (Float.compare(cand, eq) != 0) {
            if (cand <= 0 && eq <= 0)
                t = Trend.EQUAL;
            else if (cand <= 0)
                t = Trend.WORSE;
            else if (eq <= 0)
                t = Trend.BETTER;
            else
                t = cand < eq ? Trend.BETTER : Trend.WORSE;
        }
        return new Diff("Gold (shop)", formatGold(eq), formatGold(cand), t);
    }

    private static String formatGold(float v) {
        return v > 0f ? String.format("%.2f×", v) : "—";
    }

    /**
     * Card lists by name: show added and removed with INFO trend when both sides have cards,
     * BETTER when only candidate gains, WORSE when only equipped had them.
     */
    static List<Diff> cardNameDiffs(String label, String[] cand, String[] eq) {
        List<Diff> out = new ArrayList<>();
        Set<String> cSet = normalizeNames(cand);
        Set<String> eSet = normalizeNames(eq);
        if (cSet.isEmpty() && eSet.isEmpty()) {
            out.add(new Diff(label, "none", "none", Trend.EQUAL));
            return out;
        }
        Set<String> added = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        added.addAll(cSet);
        added.removeAll(eSet);
        Set<String> removed = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        removed.addAll(eSet);
        removed.removeAll(cSet);
        if (added.isEmpty() && removed.isEmpty()) {
            out.add(new Diff(label, joinNames(eSet), joinNames(cSet), Trend.EQUAL));
            return out;
        }
        if (!added.isEmpty() && removed.isEmpty()) {
            out.add(new Diff(label + " (+)", "—", joinNames(added), Trend.BETTER));
        } else if (added.isEmpty() && !removed.isEmpty()) {
            out.add(new Diff(label + " (−)", joinNames(removed), "—", Trend.WORSE));
        } else {
            if (!removed.isEmpty())
                out.add(new Diff(label + " (−)", joinNames(removed), "—", Trend.INFO));
            if (!added.isEmpty())
                out.add(new Diff(label + " (+)", "—", joinNames(added), Trend.INFO));
        }
        return out;
    }

    /** Opponent EffectData compared field-by-field. */
    static List<Diff> opponentFieldDiffs(EffectData a, EffectData b) {
        List<Diff> out = new ArrayList<>();
        EffectData oa = a != null ? a.opponent : null;
        EffectData ob = b != null ? b.opponent : null;
        if (oa == null && ob == null) {
            out.add(new Diff("Opponent effects", "none", "none", Trend.EQUAL));
            return out;
        }
        out.add(intDiff("Opp life", life(oa), life(ob), false)); // lower opponent life bonus is better for you
        out.add(intDiff("Opp starting cards", startCards(oa), startCards(ob), false));
        out.add(pctDiff("Opp move speed", moveSpeed(oa), moveSpeed(ob), false));
        out.add(intDiff("Opp card rewards", cardRewards(oa), cardRewards(ob), false));
        out.add(intDiff("Opp mana shards", manaShards(oa), manaShards(ob), false));
        out.add(intDiff("Opp mulligans", mulligans(oa), mulligans(ob), false));
        out.add(boolDiff("Opp colorView", colorView(oa), colorView(ob), false));
        out.addAll(cardNameDiffs("Opp start-of-battle", battleCards(oa), battleCards(ob)));
        // Invert BETTER/WORSE for opponent card gains (more cards for opponent is worse for you)
        for (int i = 0; i < out.size(); i++) {
            Diff d = out.get(i);
            if (d.label.startsWith("Opp start-of-battle")) {
                Trend t = d.trend;
                if (t == Trend.BETTER)
                    t = Trend.WORSE;
                else if (t == Trend.WORSE)
                    t = Trend.BETTER;
                out.set(i, new Diff(d.label, d.left, d.right, t));
            }
        }
        out.addAll(cardNameDiffs("Opp command zone", commandCards(oa), commandCards(ob)));
        for (int i = 0; i < out.size(); i++) {
            Diff d = out.get(i);
            if (d.label.startsWith("Opp command zone")) {
                Trend t = d.trend;
                if (t == Trend.BETTER)
                    t = Trend.WORSE;
                else if (t == Trend.WORSE)
                    t = Trend.BETTER;
                out.set(i, new Diff(d.label, d.left, d.right, t));
            }
        }
        return out;
    }

    private static Set<String> normalizeNames(String[] names) {
        Set<String> set = new LinkedHashSet<>();
        if (names == null)
            return set;
        for (String n : names) {
            if (n != null && !n.trim().isEmpty())
                set.add(n.trim());
        }
        return set;
    }

    private static String joinNames(Set<String> names) {
        if (names == null || names.isEmpty())
            return "none";
        return String.join(", ", names);
    }

    private static Diff socketDiff(ItemData cand, ItemData eq) {
        if ((cand != null && cand.isGatheringTool()) || (eq != null && eq.isGatheringTool())) {
            String left = toolSocketHint(eq);
            String right = toolSocketHint(cand);
            int c = cand != null && cand.isGatheringTool() ? cand.toolTier : 0;
            int e = eq != null && eq.isGatheringTool() ? eq.toolTier : 0;
            Trend t = c == e ? Trend.EQUAL : (c > e ? Trend.BETTER : Trend.WORSE);
            return new Diff("Enchant sockets", left, right, t);
        }
        return new Diff("Enchant sockets", "—", "—", Trend.EQUAL);
    }

    private static String toolSocketHint(ItemData item) {
        if (item == null || !item.isGatheringTool())
            return "—";
        if (item.toolTier >= 4)
            return "2";
        if (item.toolTier >= 2)
            return "1";
        return "0";
    }

    private static Diff toolTierDiff(ItemData cand, ItemData eq) {
        int c = cand != null && cand.isGatheringTool() ? cand.toolTier : 0;
        int e = eq != null && eq.isGatheringTool() ? eq.toolTier : 0;
        return intDiff("Tool tier", c, e, true);
    }

    private static Diff gatheringDiff(ItemData cand, ItemData eq) {
        String c = gatherLabel(cand);
        String e = gatherLabel(eq);
        Trend t = Objects.equals(c, e) ? Trend.EQUAL
                : ("none".equals(e) ? Trend.BETTER : ("none".equals(c) ? Trend.WORSE : Trend.INFO));
        return new Diff("Gathering", e, c, t);
    }

    private static String gatherLabel(ItemData item) {
        if (item == null || !item.isGatheringTool())
            return "none";
        return item.toolFamily + " T" + item.toolTier;
    }

    private static int life(EffectData e) { return e == null ? 0 : e.lifeModifier; }
    private static int startCards(EffectData e) { return e == null ? 0 : e.changeStartCards; }
    private static float moveSpeed(EffectData e) { return e == null ? 1f : e.moveSpeed; }
    private static float gold(EffectData e) { return e == null ? -1f : e.goldModifier; }
    private static int cardRewards(EffectData e) { return e == null ? 0 : e.cardRewardBonus; }
    private static int manaShards(EffectData e) { return e == null ? 0 : e.extraManaShards; }
    private static int mulligans(EffectData e) { return e == null ? 0 : e.freeMulligans; }
    private static boolean colorView(EffectData e) { return e != null && e.colorView; }

    private static String[] battleCards(EffectData e) {
        return e == null ? null : e.startBattleWithCard;
    }

    private static String[] commandCards(EffectData e) {
        return e == null ? null : e.startBattleWithCardInCommandZone;
    }
}
