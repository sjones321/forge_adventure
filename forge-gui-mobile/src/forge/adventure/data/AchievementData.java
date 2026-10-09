package forge.adventure.data;

/**
 * One entry from {@code world/achievements.json}.
 */
public class AchievementData {
    public String id;
    public String name;
    public String description;
    public String category;
    public AchievementConditionData condition;
    public boolean hidden;
    public AchievementRewardData reward;
}
