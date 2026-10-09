package forge.adventure.data;

/**
 * Condition block inside {@code world/achievements.json}.
 * Types: {@code setComplete}, {@code allSetsComplete}, {@code counter},
 * {@code badgeCount}, {@code leagueChampion}, {@code skillLevel}, {@code duelWins}.
 */
public class AchievementConditionData {
    /** Condition kind (required). */
    public String type;
    /** Counter key when {@code type} is {@code counter}. */
    public String key;
    /** Threshold for counters, badge counts, duel wins, skill levels. */
    public int count;
    /** Skill enum name when {@code type} is {@code skillLevel} (e.g. {@code DUELING}). */
    public String skill;
    /** Skill level threshold (defaults to {@link #count} when unset). */
    public int level;
    /**
     * Optional set code for {@code setComplete}. When omitted, any newly completed
     * set grants progress / unlocks the achievement and a per-set trophy.
     */
    public String set;
}
