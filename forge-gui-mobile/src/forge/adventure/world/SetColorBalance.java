package forge.adventure.world;

import forge.card.CardEdition;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MV2: WUBRG + colorless color mix for one Magic set, derived from card mana costs.
 * Used to bias set-plane biome sizes / weights.
 */
public final class SetColorBalance {
    public final float white;
    public final float blue;
    public final float black;
    public final float red;
    public final float green;
    public final float colorless;
    public final int cardCount;

    public SetColorBalance(float white, float blue, float black, float red, float green, float colorless, int cardCount) {
        this.white = white;
        this.blue = blue;
        this.black = black;
        this.red = red;
        this.green = green;
        this.colorless = colorless;
        this.cardCount = cardCount;
    }

    public float share(String biomeName) {
        if (biomeName == null) {
            return 0f;
        }
        return switch (biomeName.toLowerCase()) {
            case "white" -> white;
            case "blue" -> blue;
            case "black" -> black;
            case "red" -> red;
            case "green" -> green;
            case "colorless", "waste", "wastes" -> colorless;
            default -> 0f;
        };
    }

    public Map<String, Float> asMap() {
        Map<String, Float> m = new LinkedHashMap<>();
        m.put("white", white);
        m.put("blue", blue);
        m.put("black", black);
        m.put("red", red);
        m.put("green", green);
        m.put("colorless", colorless);
        return m;
    }

    /** Equal-weight fallback when a set cannot be read (tests / missing DB). */
    public static SetColorBalance equal() {
        float s = 1f / 6f;
        return new SetColorBalance(s, s, s, s, s, s, 0);
    }

    public static SetColorBalance fromFractions(float w, float u, float b, float r, float g, float c) {
        float sum = w + u + b + r + g + c;
        if (sum <= 0f) {
            return equal();
        }
        return new SetColorBalance(w / sum, u / sum, b / sum, r / sum, g / sum, c / sum, 0);
    }

    /**
     * Counts colored mana shards across obtainable cards in {@code setCode}.
     * Basics and tokens are skipped. Missing editions yield {@link #equal()}.
     */
    public static SetColorBalance fromSet(String setCode) {
        if (setCode == null || setCode.isEmpty()) {
            return equal();
        }
        try {
            CardEdition ed = FModel.getMagicDb().getEditions().get(setCode);
            if (ed == null) {
                return equal();
            }
            float w = 0, u = 0, b = 0, r = 0, g = 0, c = 0;
            int n = 0;
            for (PaperCard card : FModel.getMagicDb().getCommonCards().getAllCards(ed)) {
                if (card == null || card.getRules() == null) {
                    continue;
                }
                if (card.getRules().getType().isBasicLand()) {
                    continue;
                }
                ManaCost cost = card.getRules().getManaCost();
                if (cost == null || cost.isNoCost()) {
                    // Colorless artifacts / lands without a cost still nudge colorless.
                    c += 1f;
                    n++;
                    continue;
                }
                float cw = cost.getShardCount(ManaCostShard.WHITE);
                float cu = cost.getShardCount(ManaCostShard.BLUE);
                float cb = cost.getShardCount(ManaCostShard.BLACK);
                float cr = cost.getShardCount(ManaCostShard.RED);
                float cg = cost.getShardCount(ManaCostShard.GREEN);
                float cc = cost.getShardCount(ManaCostShard.COLORLESS);
                float colored = cw + cu + cb + cr + cg;
                if (colored + cc <= 0f) {
                    // Generic-only / hybrid without shards counted — use color identity.
                    byte id = card.getRules().getColorIdentity().getColor();
                    if (id == 0) {
                        c += 1f;
                    } else {
                        if ((id & MagicColor.WHITE) != 0) w += 1f;
                        if ((id & MagicColor.BLUE) != 0) u += 1f;
                        if ((id & MagicColor.BLACK) != 0) b += 1f;
                        if ((id & MagicColor.RED) != 0) r += 1f;
                        if ((id & MagicColor.GREEN) != 0) g += 1f;
                    }
                } else {
                    w += cw;
                    u += cu;
                    b += cb;
                    r += cr;
                    g += cg;
                    c += cc;
                }
                n++;
            }
            if (n == 0 || w + u + b + r + g + c <= 0f) {
                return equal();
            }
            SetColorBalance bal = fromFractions(w, u, b, r, g, c);
            return new SetColorBalance(bal.white, bal.blue, bal.black, bal.red, bal.green, bal.colorless, n);
        } catch (Throwable t) {
            return equal();
        }
    }
}
