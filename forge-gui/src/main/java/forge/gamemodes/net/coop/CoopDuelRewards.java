package forge.gamemodes.net.coop;

/**
 * Per-side reward application for CO3. Each peer applies XP / gold / penalties to
 * their own character only. The guest's rewards go through CO1 isolation and must
 * never be written into the guest's normal world save mid-session in a way that
 * breaks restore.
 */
public final class CoopDuelRewards {
    public enum Side {
        HOST,
        GUEST
    }

    public static final class ResultPayload {
        public final long duelId;
        public final boolean teamWon;
        public final int gold;
        public final int xp;
        public final int lifePenalty;
        public final boolean boss;
        public final String encounterId;

        public ResultPayload(final long duelId, final boolean teamWon, final int gold, final int xp,
                             final int lifePenalty, final boolean boss, final String encounterId) {
            this.duelId = duelId;
            this.teamWon = teamWon;
            this.gold = Math.max(0, Math.min(gold, CoopDuelWireLimits.MAX_STAT));
            this.xp = Math.max(0, Math.min(xp, CoopDuelWireLimits.MAX_STAT));
            this.lifePenalty = Math.max(0, Math.min(lifePenalty, CoopDuelWireLimits.MAX_STAT));
            this.boss = boss;
            this.encounterId = CoopDuelWireLimits.clampString(encounterId, CoopDuelWireLimits.MAX_NAME_LEN);
        }
    }

    /** Mutable character facade for tests (and adapters over AdventurePlayer). */
    public interface CharacterSink {
        void addGold(int amount);

        void addXp(int amount);

        void applyLifePenalty(int amount);

        void recordWin(boolean boss);

        void recordLoss();

        int getGold();

        int getXp();

        int getLife();
    }

    public static final class SimpleSink implements CharacterSink {
        private int gold;
        private int xp;
        private int life = 20;
        private int wins;
        private int losses;

        @Override
        public void addGold(final int amount) {
            gold += Math.max(0, amount);
        }

        @Override
        public void addXp(final int amount) {
            xp += Math.max(0, amount);
        }

        @Override
        public void applyLifePenalty(final int amount) {
            life = Math.max(0, life - Math.max(0, amount));
        }

        @Override
        public void recordWin(final boolean boss) {
            wins++;
        }

        @Override
        public void recordLoss() {
            losses++;
        }

        @Override
        public int getGold() {
            return gold;
        }

        @Override
        public int getXp() {
            return xp;
        }

        @Override
        public int getLife() {
            return life;
        }

        public int getWins() {
            return wins;
        }

        public int getLosses() {
            return losses;
        }
    }

    /** Default loot rolls each player gets for a partnered co-op kill (EN2). */
    public static final int DEFAULT_PARTNER_LOOT_ROLLS = 1;

    private CoopDuelRewards() {
    }

    /**
     * EN2: how many loot rolls each player applies for a co-op win.
     * With a partner, use the tunable so a pair is not worth double — {@code 0} is
     * valid (no loot). Without a partner, always one roll (same as a solo kill).
     */
    public static int lootRollsPerPlayer(final boolean partnerBuilt, final int partnerLootRolls) {
        if (!partnerBuilt) {
            return 1;
        }
        // Explicit 0 must work; only fall back to the default when the tunable is negative.
        if (partnerLootRolls < 0) {
            return DEFAULT_PARTNER_LOOT_ROLLS;
        }
        return Math.max(0, Math.min(partnerLootRolls, 8));
    }

    /**
     * Apply a duel result to exactly one side's character. The other sink must
     * not be touched by this call.
     *
     * @param localSide which peer is applying
     * @param applyToWorldSave when false (guest mid-session), callers still apply
     *                         to the live character object but must not persist
     *                         into the normal WorldSave slot (CO1 isolation)
     */
    public static void applyToOwnCharacter(final Side localSide, final CharacterSink own,
                                           final ResultPayload result, final boolean applyToWorldSave) {
        if (own == null || result == null || localSide == null) {
            return;
        }
        // applyToWorldSave is a documentation / caller-policy flag; the sink is
        // always the local character. Guest adapters no-op world-slot writes.
        if (result.teamWon) {
            own.addGold(result.gold);
            own.addXp(result.xp);
            own.recordWin(result.boss);
        } else {
            own.applyLifePenalty(result.lifePenalty);
            own.recordLoss();
        }
        // Silence unused warning for the policy flag in headless path.
        if (!applyToWorldSave && localSide == Side.GUEST) {
            // Guest: rewards stay on the live character / character file only.
        }
    }

    /**
     * Split helper: given host and guest sinks, each call applies only to the
     * requested side (tests assert the other is unchanged).
     */
    public static void applyHostOnly(final CharacterSink host, final CharacterSink guest,
                                     final ResultPayload result) {
        final int gGold = guest != null ? guest.getGold() : 0;
        final int gXp = guest != null ? guest.getXp() : 0;
        final int gLife = guest != null ? guest.getLife() : 0;
        applyToOwnCharacter(Side.HOST, host, result, true);
        if (guest != null) {
            assertUnchanged(guest, gGold, gXp, gLife);
        }
    }

    public static void applyGuestOnly(final CharacterSink host, final CharacterSink guest,
                                      final ResultPayload result) {
        final int hGold = host != null ? host.getGold() : 0;
        final int hXp = host != null ? host.getXp() : 0;
        final int hLife = host != null ? host.getLife() : 0;
        applyToOwnCharacter(Side.GUEST, guest, result, false);
        if (host != null) {
            assertUnchanged(host, hGold, hXp, hLife);
        }
    }

    private static void assertUnchanged(final CharacterSink s, final int gold, final int xp, final int life) {
        if (s.getGold() != gold || s.getXp() != xp || s.getLife() != life) {
            throw new IllegalStateException("other side's character was mutated");
        }
    }
}
