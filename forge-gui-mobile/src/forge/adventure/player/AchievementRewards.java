package forge.adventure.player;

import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementRewardData;

/**
 * Applies achievement rewards. Titles and trophies are granted now; {@code cardStyle}
 * is stored on {@link AchievementProgress} as a clean CS1 hook (no card-style
 * system is built here).
 */
public final class AchievementRewards {
    private AchievementRewards() {
    }

    /**
     * Grant the reward on {@code progress}. Safe with nulls; unknown types are ignored.
     *
     * @return true if something was newly recorded
     */
    public static boolean grant(AchievementProgress progress, AchievementData achievement) {
        if (progress == null || achievement == null || achievement.reward == null) {
            return false;
        }
        return grant(progress, achievement.reward, achievement);
    }

    /**
     * Grant {@code reward}. For set-complete trophies, pass a qualifier (set code)
     * so each set gets its own trophy id ({@code trophyId:SET}).
     */
    public static boolean grant(AchievementProgress progress, AchievementRewardData reward,
                                AchievementData achievement) {
        return grant(progress, reward, achievement, null);
    }

    public static boolean grant(AchievementProgress progress, AchievementRewardData reward,
                                AchievementData achievement, String qualifier) {
        if (progress == null || reward == null || reward.type == null) {
            return false;
        }
        String type = reward.type.trim().toLowerCase();
        String id = reward.id != null && !reward.id.isEmpty() ? reward.id
                : (reward.value != null ? reward.value : (achievement != null ? achievement.id : null));
        if (id == null || id.isEmpty()) {
            return false;
        }
        if (qualifier != null && !qualifier.isEmpty()
                && ("trophy".equals(type) || "cosmetic".equals(type))) {
            id = id + ":" + qualifier;
        }
        switch (type) {
            case "title":
                String title = reward.value != null && !reward.value.isEmpty() ? reward.value : id;
                return progress.addTitle(title);
            case "trophy":
            case "cosmetic":
                return progress.addTrophy(id);
            case "cardstyle":
                // CS1 hook: persist the unlock only; style application comes later.
                return progress.addCardStyle(id);
            default:
                return false;
        }
    }
}
