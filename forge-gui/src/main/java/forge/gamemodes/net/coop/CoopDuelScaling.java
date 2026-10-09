package forge.gamemodes.net.coop;

/**
 * Enemy life and extra-card scaling when two humans fight together (CO3 / EN2).
 * Tunables mirror the existing enemy tuning style (factors in ConfigData).
 * <p>
 * EN2: when a same-type partner was built, drop the single-enemy boosts
 * ({@code lifeFactor → 1.0}, no extra card). Keep the CO3 boosts when no partner
 * could be built. Both pairs of values are caller-supplied tunables.
 */
public final class CoopDuelScaling {
    /** Default life multiplier for a two-player co-op duel (enemies, no partner). */
    public static final float DEFAULT_LIFE_FACTOR = 1.5f;
    /** Default extra starting cards for enemies in a two-player co-op duel (no partner). */
    public static final int DEFAULT_EXTRA_CARDS = 1;
    /** Default life multiplier when an EN2 partner sits across from the party. */
    public static final float DEFAULT_PARTNER_LIFE_FACTOR = 1.0f;
    /** Default extra cards when an EN2 partner sits across from the party. */
    public static final int DEFAULT_PARTNER_EXTRA_CARDS = 0;

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

    /**
     * EN2: life factor for a joined co-op duel. With a partner, use
     * {@code partnerLifeFactor} (default 1.0); otherwise keep the single-enemy boost.
     */
    public static float effectiveLifeFactor(final boolean partnerBuilt, final float partnerLifeFactor,
                                            final float noPartnerLifeFactor) {
        if (partnerBuilt) {
            return partnerLifeFactor > 0f ? partnerLifeFactor : DEFAULT_PARTNER_LIFE_FACTOR;
        }
        return noPartnerLifeFactor > 0f ? noPartnerLifeFactor : DEFAULT_LIFE_FACTOR;
    }

    /**
     * EN2: extra cards for a joined co-op duel. With a partner, use
     * {@code partnerExtraCards} (default 0); otherwise keep the single-enemy boost.
     */
    public static int effectiveExtraCards(final boolean partnerBuilt, final int partnerExtraCards,
                                          final int noPartnerExtraCards) {
        if (partnerBuilt) {
            return Math.max(0, Math.min(partnerExtraCards, 10));
        }
        return Math.max(0, Math.min(noPartnerExtraCards, 10));
    }
}
