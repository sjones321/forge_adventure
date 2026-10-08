package forge.adventure.util;

import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * INV1 side-by-side item compare: marks each stat better (green ↑) / worse (red ↓) / equal.
 * Works for gear, tools and jewelry. Pure logic — safe for headless tests.
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
        out.add(cardsDiff("Start-of-battle", battleCards(a), battleCards(b)));
        out.add(cardsDiff("Command zone", commandCards(a), commandCards(b)));
        out.add(opponentDiff(a, b));
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
        return ("0".equals(d.left) || "—".equals(d.left) || "1.00×".equals(d.left) || "none".equals(d.left))
                && ("0".equals(d.right) || "—".equals(d.right) || "1.00×".equals(d.right) || "none".equals(d.right));
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

    private static Diff goldDiff(EffectData a, EffectData b) {
        float cand = gold(a);
        float eq = gold(b);
        // goldModifier: lower multiplier = better shop prices when > 0; inactive when <= 0
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

    private static Diff cardsDiff(String label, String cand, String eq) {
        Trend t;
        if (Objects.equals(cand, eq))
            t = Trend.EQUAL;
        else if ("none".equals(eq) && !"none".equals(cand))
            t = Trend.BETTER;
        else if (!"none".equals(eq) && "none".equals(cand))
            t = Trend.WORSE;
        else
            t = Trend.INFO;
        return new Diff(label, eq, cand, t);
    }

    private static Diff opponentDiff(EffectData a, EffectData b) {
        String cand = opponentSummary(a);
        String eq = opponentSummary(b);
        Trend t = Objects.equals(cand, eq) ? Trend.EQUAL
                : ("none".equals(eq) ? Trend.BETTER : ("none".equals(cand) ? Trend.WORSE : Trend.INFO));
        return new Diff("Opponent effects", eq, cand, t);
    }

    private static Diff socketDiff(ItemData cand, ItemData eq) {
        // Tool socket count is tier-gated at runtime (ConfigData); compare shows tier as a proxy.
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

    private static String battleCards(EffectData e) {
        if (e == null || e.startBattleWithCard == null || e.startBattleWithCard.length == 0)
            return "none";
        return e.startBattleWithCard.length + " card(s)";
    }

    private static String commandCards(EffectData e) {
        if (e == null || e.startBattleWithCardInCommandZone == null || e.startBattleWithCardInCommandZone.length == 0)
            return "none";
        return e.startBattleWithCardInCommandZone.length + " card(s)";
    }

    private static String opponentSummary(EffectData e) {
        if (e == null || e.opponent == null)
            return "none";
        String d = e.opponent.getDescription();
        return d == null || d.isEmpty() ? "yes" : "yes";
    }
}
