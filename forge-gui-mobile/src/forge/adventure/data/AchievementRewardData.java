package forge.adventure.data;

/**
 * Reward block inside {@code world/achievements.json}.
 * Supported now: {@code title}, {@code trophy}. Reserved for CS1: {@code cardStyle}.
 * {@code cosmetic} is accepted and stored the same way as trophies for future use.
 */
public class AchievementRewardData {
    /** Reward kind: title, trophy, cardStyle, cosmetic. */
    public String type;
    /** Stable id (trophy id, style id) or display title text. */
    public String id;
    /** Optional display override (title text when {@link #id} is a key). */
    public String value;
}
