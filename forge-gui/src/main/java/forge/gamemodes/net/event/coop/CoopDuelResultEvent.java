package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest: match ended. Each peer applies rewards / XP / penalties to their
 * own character (CO1 isolation on the guest).
 */
public class CoopDuelResultEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long duelId;
    private final boolean teamWon;
    private final int gold;
    private final int xp;
    private final int lifePenalty;
    private final boolean boss;
    private final String encounterId;

    public CoopDuelResultEvent(final long duelId, final boolean teamWon, final int gold, final int xp,
                               final int lifePenalty, final boolean boss, final String encounterId) {
        this.duelId = duelId;
        this.teamWon = teamWon;
        this.gold = gold;
        this.xp = xp;
        this.lifePenalty = lifePenalty;
        this.boss = boss;
        this.encounterId = encounterId != null ? encounterId : "";
    }

    public long getDuelId() {
        return duelId;
    }

    public boolean isTeamWon() {
        return teamWon;
    }

    public int getGold() {
        return gold;
    }

    public int getXp() {
        return xp;
    }

    public int getLifePenalty() {
        return lifePenalty;
    }

    public boolean isBoss() {
        return boss;
    }

    public String getEncounterId() {
        return encounterId;
    }
}
