package forge.gamemodes.net.coop;

/**
 * Enemy life and extra-card scaling when two humans fight together (CO3).
 * Tunables mirror the existing enemy tuning style (factors in ConfigData).
 */
public final class CoopDuelScaling {
    /** Default life multiplier for a two-player co-op duel (enemies). */
    public static final float DEFAULT_LIFE_FACTOR = 1.5f;
    /** Default extra starting cards for enemies in a two-player co-op duel. */
    public static final int DEFAULT_EXTRA_CARDS = 1;

    private CoopDuelScaling() {
    }

    /**
     * Scale enemy starting life for {@code humanCount} humans on team 0.
     * Solo (1) returns the base unchanged; two humans apply {@code lifeFactor}.
     */
    public static int scaleEnemyLife(final int baseLife, final int humanCount, final float lifeFactor) {
        final int base = Math.max(1, baseLife);
        if (humanCount < 2) {
            return base;
        }
        final float factor = lifeFactor > 0f ? lifeFactor : DEFAULT_LIFE_FACTOR;
        return Math.max(1, Math.round(base * factor));
    }

    /**
     * Extra cards enemies draw/start with when two humans are present.
     * Solo returns 0; co-op returns the tuned extra (clamped).
     */
    public static int scaleEnemyExtraCards(final int humanCount, final int extraCards) {
        if (humanCount < 2) {
            return 0;
        }
        return Math.max(0, Math.min(extraCards, 10));
    }

    /** Human count for scaling: 1 = solo, 2 = co-op (never more than 2 in v1). */
    public static int humanCount(final boolean guestJoined) {
        return guestJoined ? 2 : 1;
    }
}
