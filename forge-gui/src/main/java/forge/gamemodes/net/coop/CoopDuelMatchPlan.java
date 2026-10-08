package forge.gamemodes.net.coop;

import forge.deck.Deck;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Headless match-building step for CO3: describes who sits where before
 * {@code RegisteredPlayer} / GUI objects are created. Team 0 = host (+ guest when
 * joined); team 1 = enemies. Used by tests and by the mobile runtime.
 */
public final class CoopDuelMatchPlan {
    public enum SeatKind {
        HOST_HUMAN,
        GUEST_HUMAN,
        ENEMY_AI
    }

    public static final class Seat {
        public final SeatKind kind;
        public final int teamNumber;
        public final String displayName;
        public final String avatarId;
        public final Deck deck;
        public final int startingLife;
        public final int manaShards;
        public final int freeMulligans;
        public final int startingHandBonus;
        public final int lobbySlot;
        /** True when this seat's GUI comes from {@code FServerManager.getGui(slot)}. */
        public final boolean remoteGui;

        Seat(final SeatKind kind, final int teamNumber, final String displayName, final String avatarId,
             final Deck deck, final int startingLife, final int manaShards, final int freeMulligans,
             final int startingHandBonus, final int lobbySlot, final boolean remoteGui) {
            this.kind = kind;
            this.teamNumber = teamNumber;
            this.displayName = displayName;
            this.avatarId = avatarId;
            this.deck = deck;
            this.startingLife = startingLife;
            this.manaShards = manaShards;
            this.freeMulligans = freeMulligans;
            this.startingHandBonus = startingHandBonus;
            this.lobbySlot = lobbySlot;
            this.remoteGui = remoteGui;
        }
    }

    private final List<Seat> seats;
    private final boolean coOp;
    private final int humanCount;
    private final float lifeFactorApplied;
    private final int enemyExtraCardsApplied;

    private CoopDuelMatchPlan(final List<Seat> seats, final boolean coOp, final int humanCount,
                              final float lifeFactorApplied, final int enemyExtraCardsApplied) {
        this.seats = Collections.unmodifiableList(new ArrayList<>(seats));
        this.coOp = coOp;
        this.humanCount = humanCount;
        this.lifeFactorApplied = lifeFactorApplied;
        this.enemyExtraCardsApplied = enemyExtraCardsApplied;
    }

    public List<Seat> getSeats() {
        return seats;
    }

    public boolean isCoOp() {
        return coOp;
    }

    public int getHumanCount() {
        return humanCount;
    }

    public float getLifeFactorApplied() {
        return lifeFactorApplied;
    }

    public int getEnemyExtraCardsApplied() {
        return enemyExtraCardsApplied;
    }

    public int countTeam(final int team) {
        int n = 0;
        for (final Seat s : seats) {
            if (s.teamNumber == team) {
                n++;
            }
        }
        return n;
    }

    public int countKind(final SeatKind kind) {
        int n = 0;
        for (final Seat s : seats) {
            if (s.kind == kind) {
                n++;
            }
        }
        return n;
    }

    /**
     * Build a plan: host (+ optional guest) on team 0, enemies on team 1.
     *
     * @param guestJoined when false, solo (host only on team 0)
     * @param enemyBaseLife life before co-op scaling
     * @param lifeFactor ConfigData.coopDuelEnemyLifeFactor
     * @param enemyExtraCards ConfigData.coopDuelEnemyExtraCards
     */
    public static CoopDuelMatchPlan build(final String hostName, final String hostAvatarId, final Deck hostDeck,
                                          final int hostLife, final int hostShards, final int hostMulligans,
                                          final boolean guestJoined, final CoopFightLoadout guestLoadout,
                                          final Deck guestDeck,
                                          final List<EnemySpec> enemies,
                                          final float lifeFactor, final int enemyExtraCards) {
        final int humans = CoopDuelScaling.humanCount(guestJoined);
        final List<Seat> seats = new ArrayList<>();
        seats.add(new Seat(SeatKind.HOST_HUMAN, 0,
                CoopDuelWireLimits.clampString(hostName, CoopDuelWireLimits.MAX_NAME_LEN),
                CoopDuelWireLimits.clampString(hostAvatarId, CoopDuelWireLimits.MAX_AVATAR_ID_LEN),
                hostDeck, Math.max(1, hostLife), Math.max(0, hostShards), Math.max(0, hostMulligans),
                0, 0, false));

        if (guestJoined && guestLoadout != null && guestDeck != null) {
            seats.add(new Seat(SeatKind.GUEST_HUMAN, 0,
                    guestLoadout.getPlayerName(),
                    guestLoadout.getAvatarId(),
                    guestDeck,
                    guestLoadout.getStartingLife() + guestLoadout.getLifeModifier(),
                    guestLoadout.getManaShards() + guestLoadout.getExtraManaShards(),
                    guestLoadout.getFreeMulligans(),
                    guestLoadout.getChangeStartCards(),
                    1, true));
        }

        final float appliedLife = humans >= 2 ? (lifeFactor > 0f ? lifeFactor : CoopDuelScaling.DEFAULT_LIFE_FACTOR) : 1f;
        final int appliedExtra = CoopDuelScaling.scaleEnemyExtraCards(humans, enemyExtraCards);
        if (enemies != null) {
            int slot = guestJoined ? 2 : 1;
            for (final EnemySpec e : enemies) {
                if (e == null || e.deck == null) {
                    continue;
                }
                final int life = CoopDuelScaling.scaleEnemyLife(e.baseLife, humans, lifeFactor);
                seats.add(new Seat(SeatKind.ENEMY_AI, 1,
                        CoopDuelWireLimits.clampString(e.name, CoopDuelWireLimits.MAX_NAME_LEN),
                        CoopDuelWireLimits.clampString(e.avatarId, CoopDuelWireLimits.MAX_AVATAR_ID_LEN),
                        e.deck, life, 0, e.freeMulligans, appliedExtra, slot++, false));
            }
        }
        return new CoopDuelMatchPlan(seats, guestJoined, humans, appliedLife, appliedExtra);
    }

    /** Minimal enemy description for the planner. */
    public static final class EnemySpec {
        public final String name;
        public final String avatarId;
        public final Deck deck;
        public final int baseLife;
        public final int freeMulligans;

        public EnemySpec(final String name, final String avatarId, final Deck deck,
                         final int baseLife, final int freeMulligans) {
            this.name = name;
            this.avatarId = avatarId;
            this.deck = deck;
            this.baseLife = baseLife;
            this.freeMulligans = freeMulligans;
        }
    }
}
