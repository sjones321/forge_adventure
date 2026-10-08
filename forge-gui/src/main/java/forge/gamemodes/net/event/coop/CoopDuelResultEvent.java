package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest: match ended (once per MATCH, not per game). Outcome only —
 * winning team, duel id, enemy id. Each peer runs its own local reward /
 * DuelScene result path; the host never applies guest-supplied reward numbers.
 */
public class CoopDuelResultEvent implements NetEvent {
    private static final long serialVersionUID = 2L;

    private final long duelId;
    /** 0 = humans (team 0) won; 1 = enemies won; negative = unknown / draw. */
    private final int winningTeam;
    private final long enemyId;
    private final String encounterId;

    public CoopDuelResultEvent(final long duelId, final int winningTeam, final long enemyId,
                               final String encounterId) {
        this.duelId = duelId;
        this.winningTeam = winningTeam;
        this.enemyId = enemyId;
        this.encounterId = encounterId != null ? encounterId : "";
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
}
