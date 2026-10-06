package forge.gamemodes.match.input;

import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Keep / mulligan advice for the human's opening hand, shown on the mulligan prompt.
 * Port of the Field Guide (MTG Arena companion) heuristic: land count, cheap plays,
 * colors the hand's lands can't make, and the odds of hitting a third land by turn 3.
 */
public final class MulliganAdvisor {
    private static final byte[] COLORS = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN};
    private static final byte ALL = (byte) (MagicColor.WHITE | MagicColor.BLUE | MagicColor.BLACK | MagicColor.RED | MagicColor.GREEN);

    private MulliganAdvisor() {
    }

    public static String advise(Player player, boolean onPlay, int freeMulligansLeft) {
        List<Card> hand = new ArrayList<>(player.getCardsIn(ZoneType.Hand));
        if (hand.isEmpty())
            return null;
        List<Card> lands = new ArrayList<>(), spells = new ArrayList<>();
        for (Card c : hand)
            (c.isLand() ? lands : spells).add(c);
        List<Byte> sources = new ArrayList<>();
        for (Card l : lands)
            sources.add(landColors(l));

        List<Card> cheap = new ArrayList<>(), castableT3 = new ArrayList<>(), offColor = new ArrayList<>(), ramp = new ArrayList<>();
        List<Byte> plusOne = new ArrayList<>(sources);
        plusOne.add(ALL);
        byte handColors = 0;
        for (byte s : sources)
            handColors |= s;
        for (Card c : spells) {
            int mv = c.getCMC();
            if (mv <= 2 && castable(c.getManaCost(), sources))
                cheap.add(c);
            if (mv <= 3 && castable(c.getManaCost(), plusOne))
                castableT3.add(c);
            if (!lands.isEmpty() && (costColors(c.getManaCost()) & ~handColors) != 0)
                offColor.add(c);
        }
        for (Card c : cheap)
            if (!c.isLand() && !c.getManaAbilities().isEmpty() && (c.isCreature() || c.isArtifact()))
                ramp.add(c);

        int mulls = player.getStats().getMulliganCount();
        int library = player.getCardsIn(ZoneType.Library).size();
        int deckSize = library + hand.size();
        int deckLands = lands.size();
        for (Card c : player.getCardsIn(ZoneType.Library))
            if (c.isLand())
                deckLands++;
        int libLands = deckLands - lands.size();
        int draws = onPlay ? 2 : 3; // cards drawn by your turn 3
        double pLandT3 = pAtLeast(Math.max(0, 3 - lands.size()), draws, libLands, library);

        int L = lands.size();
        int lEff = L + (ramp.isEmpty() ? 0 : 1);
        int lo = 2, hi = 5;
        List<String> reasons = new ArrayList<>();
        String verdict;
        if (!ramp.isEmpty())
            reasons.add(ramp.get(0).getName() + " counts as a mana source.");
        if (lEff < lo - 1 || (lEff == lo - 1 && !(mulls >= 1 && cheap.size() >= 2))) {
            verdict = "MULLIGAN";
            reasons.add("Only " + L + " land" + (L == 1 ? "" : "s") + ". You'd need to draw lands just to play.");
        } else if (L > hi + 1 || (L > hi && mulls == 0)) {
            verdict = "MULLIGAN";
            reasons.add(L + " lands and only " + spells.size() + " spells. Flood risk.");
        } else if (lEff == lo && cheap.size() < 2 && mulls == 0) {
            if (!onPlay) {
                verdict = "KEEP";
                reasons.add(L + " lands with only " + cheap.size() + " cheap play(s), but you're on the draw: the extra card helps.");
            } else {
                verdict = "CLOSE CALL";
                reasons.add(L + " lands with only " + cheap.size() + " cheap play(s) on the play. Lean mulligan unless the spells are strong.");
            }
        } else {
            verdict = "KEEP";
            reasons.add(L + " lands, " + spells.size() + " spells, " + castableT3.size() + " castable by turn 3.");
        }
        if (!offColor.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < Math.min(3, offColor.size()); i++)
                names.append(i == 0 ? "" : ", ").append(offColor.get(i).getName());
            reasons.add("Your lands can't make the colors for: " + names + ".");
            if (verdict.equals("KEEP") && offColor.size() >= 2)
                verdict = "CLOSE CALL";
        }
        if (freeMulligansLeft > 0 && !verdict.equals("KEEP")) {
            reasons.add("This mulligan is free. Take it unless the hand is close to fine.");
            verdict = "MULLIGAN";
        } else if (freeMulligansLeft > 0) {
            reasons.add("You have a free mulligan, but this hand is fine.");
        }
        if (mulls > 0)
            reasons.add("Already mulliganed " + mulls + "x: lean toward keeping anything playable.");
        reasons.add("Odds of 3 lands by your turn 3 (" + (onPlay ? "on the play" : "on the draw") + "): "
                + Math.round(pLandT3 * 100) + "%. Deck: " + deckLands + " lands in " + deckSize + " cards.");

        StringBuilder sb = new StringBuilder("Advisor: ").append(verdict);
        for (String r : reasons)
            sb.append("\n- ").append(r);
        return sb.toString();
    }

    /** Colors a land can produce, from its mana abilities (or its basic land types). */
    private static byte landColors(Card land) {
        byte colors = 0;
        for (SpellAbility sa : land.getManaAbilities()) {
            AbilityManaPart mp = sa.getManaPart();
            if (mp == null)
                continue;
            if (mp.isAnyMana())
                return ALL;
            String produced = mp.isComboMana() ? mp.getComboColors(sa) : mp.getOrigProduced();
            if (produced == null)
                continue;
            for (String part : produced.split("\\s+")) {
                byte c = MagicColor.fromName(part);
                if (c != 0)
                    colors |= c;
            }
        }
        if (land.getType().hasSubtype("Plains")) colors |= MagicColor.WHITE;
        if (land.getType().hasSubtype("Island")) colors |= MagicColor.BLUE;
        if (land.getType().hasSubtype("Swamp")) colors |= MagicColor.BLACK;
        if (land.getType().hasSubtype("Mountain")) colors |= MagicColor.RED;
        if (land.getType().hasSubtype("Forest")) colors |= MagicColor.GREEN;
        return colors;
    }

    private static byte costColors(ManaCost cost) {
        byte colors = 0;
        if (cost == null)
            return 0;
        for (ManaCostShard shard : cost) {
            byte req = 0;
            for (byte c : COLORS)
                if (shard.canBePaidWithManaOfColor(c))
                    req |= c;
            // only count shards that need one specific color (hybrid can be paid either way)
            if (Integer.bitCount(req & 0xFF) == 1)
                colors |= req;
        }
        return colors;
    }

    /** Can the cost be paid with one mana from each source? Greedy matching of colored shards to sources. */
    private static boolean castable(ManaCost cost, List<Byte> sources) {
        if (cost == null)
            return true;
        List<ManaCostShard> shards = new ArrayList<>();
        for (ManaCostShard s : cost)
            shards.add(s);
        int total = cost.getGenericCost() + shards.size();
        if (total > sources.size())
            return false;
        List<Byte> free = new ArrayList<>(sources);
        shards.sort(Comparator.comparingInt(s -> payers(s, sources)));
        for (ManaCostShard s : shards) {
            int pick = -1;
            for (int i = 0; i < free.size(); i++) {
                if (paysShard(s, free.get(i)) && (pick < 0 || Integer.bitCount(free.get(i) & 0xFF) < Integer.bitCount(free.get(pick) & 0xFF)))
                    pick = i;
            }
            if (pick < 0)
                return false;
            free.remove(pick);
        }
        return free.size() >= cost.getGenericCost();
    }

    private static int payers(ManaCostShard s, List<Byte> sources) {
        int n = 0;
        for (byte src : sources)
            if (paysShard(s, src))
                n++;
        return n;
    }

    private static boolean paysShard(ManaCostShard s, byte source) {
        for (byte c : COLORS)
            if ((source & c) != 0 && s.canBePaidWithManaOfColor(c))
                return true;
        return s.canBePaidWithManaOfColor((byte) 0); // generic-like shards (e.g. {2/W}, {C} approximated)
    }

    /** Hypergeometric: chance of at least k "good" cards among `draws` cards from a library. */
    private static double pAtLeast(int k, int draws, int good, int total) {
        if (k <= 0)
            return 1.0;
        if (draws <= 0 || total <= 0)
            return 0.0;
        double sum = 0;
        double all = comb(total, draws);
        for (int i = k; i <= Math.min(good, draws); i++)
            sum += comb(good, i) * comb(total - good, draws - i);
        return sum / all;
    }

    private static double comb(int n, int k) {
        if (k < 0 || k > n)
            return 0;
        double r = 1;
        for (int i = 1; i <= k; i++)
            r = r * (n - k + i) / i;
        return r;
    }
}
