package forge.adventure.data;

/**
 * Elite Four + Champion challenge unlocked with all eight gym badges.
 */
public class LeagueData {
    public String id = "league";
    public String name = "Shandalar League";
    public GymFighterData[] eliteFour;
    public GymFighterData champion;
    public GymRewardData rewards;
    public GymRewardData rematchRewards;
}
