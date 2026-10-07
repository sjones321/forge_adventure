package forge.adventure.data;

/**
 * One of the eight Ascendant gyms. Theme drives decks, town placement, and badge perk.
 */
public class GymData {
    public String id;
    public String name;
    /** W / U / B / R / G / C / Guild / Rainbow — display and filter hint. */
    public String theme;
    public String badgeId;
    public String badgeName;
    /** Short perk text shown on Skills / Unlocks. */
    public String badgePerk;
    /** Applied in duels via {@link forge.adventure.player.AdventurePlayer#badgePerks()}. */
    public EffectData badgeEffect;
    public GymFighterData[] trainers;
    public GymFighterData leader;
    public GymRewardData rewards;
    public GymRewardData rematchRewards;
}
