package forge.adventure.data;

import java.io.Serial;
import java.io.Serializable;

/**
 * One talent node from {@code world/skill_trees.json}.
 * Effects use {@link EffectData} and/or Spell Smith price multipliers for non-duel passives.
 */
public class SkillTreeNodeData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String id;
    /** Branch key within the skill (e.g. fortitude, tokens, radiance). */
    public String branch = "";
    public String branchName = "";
    /** Depth in the branch; higher tiers usually require earlier nodes. */
    public int tier = 1;
    /** Talent points to buy one rank. */
    public int cost = 1;
    public int maxRanks = 1;
    /** Node ids that must have at least one rank before this can be bought. */
    public String[] requires;
    /** Mutually exclusive siblings: buying one blocks the others. */
    public String[] exclusiveWith;
    /** Minimum skill level required to buy. */
    public int levelRequired = 1;
    /**
     * When true, ranks only apply in duels if the node is placed in a perk slot.
     * Non-duel passives (prices, move speed) are always on.
     */
    public boolean duelPerk = false;
    public boolean capstone = false;
    /** Level-99 cosmetic + perk; owning it sets a skill-cape flag. */
    public boolean skillCape = false;
    public String description = "";
    public EffectData effect;
    /**
     * Multiplier on Spell Smith prices when this node has ranks (non-duel, always on).
     * 1 = no change; 0.85 = 15% cheaper.
     */
    public float spellSmithPriceFactor = 1f;

    public boolean hasEffect() {
        return effect != null;
    }

    public String displayDescription() {
        return description != null ? description : id;
    }
}
