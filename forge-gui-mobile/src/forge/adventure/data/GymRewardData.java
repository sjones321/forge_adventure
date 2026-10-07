package forge.adventure.data;

/**
 * Rewards granted for clearing a gym leader or the League.
 * Dust is applied directly (no Reward.Type.Dust yet); gold/material/card go through RewardData.
 */
public class GymRewardData {
    public int gold = 0;
    public int dustCommon = 0;
    public int dustUncommon = 0;
    public int dustRare = 0;
    public int dustMythic = 0;
    /** Unique gym material id from materials.json. */
    public String material;
    public int materialCount = 1;
    /** Theme staple card name granted on first clear. */
    public String stapleCard;
}
