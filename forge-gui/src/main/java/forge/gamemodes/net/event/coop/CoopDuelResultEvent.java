package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest: match ended (once per MATCH, not per game). Outcome only —
 * winning team, duel id, enemy id, and (EN2) host-authoritative loot-roll count.
 * Each peer runs its own local reward / DuelScene result path; the host never
 * applies guest-supplied reward numbers.
 */
public class CoopDuelResultEvent implements NetEvent {
    private static final long serialVersionUID = 3L;

    private final long duelId;
    /** 0 = humans (team 0) won; 1 = enemies won; negative = unknown / draw. */
    private final int winningTeam;
    private final long enemyId;
    private final String encounterId;
    /**
     * EN2: how many times each peer should roll {@code EnemySprite#getRewards()}
     * on a win. Host is authoritative (0 is valid — no loot). Clamped 0–8.
     */
    private final int lootRolls;

    public CoopDuelResultEvent(final long duelId, final int winningTeam, final long enemyId,
                               final String encounterId) {
        this(duelId, winningTeam, enemyId, encounterId, 1);
    }

    public CoopDuelResultEvent(final long duelId, final int winningTeam, final long enemyId,
                               final String encounterId, final int lootRolls) {
        this.duelId = duelId;
        this.winningTeam = winningTeam;
        this.enemyId = enemyId;
        this.encounterId = encounterId != null ? encounterId : "";
        this.lootRolls = Math.max(0, Math.min(lootRolls, 8));
    }

    public long getDuelId() {
        return duelId;
    }

    public int getWinningTeam() {
        return winningTeam;
    }

    public boolean isTeamWon() {
        return winningTeam == 0;
    }

    public long getEnemyId() {
        return enemyId;
    }

    public String getEncounterId() {
        return encounterId;
    }

    /** EN2 host-authoritative loot rolls for the local win path (0 allowed). */
    public int getLootRolls() {
        return lootRolls;
    }
}
