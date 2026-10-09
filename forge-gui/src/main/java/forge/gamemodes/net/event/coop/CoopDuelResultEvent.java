package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopWireLimits;
import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest: match ended (once per MATCH, not per game). Outcome only —
 * winning team, duel id, enemy id, (EN2) host-authoritative loot-roll count,
 * and (RW1) host-authoritative guest loot credit (enemy data id, theme id,
 * signature-candidate card names from the credited enemy's played deck).
 * Each peer runs its own local reward / DuelScene result path; the host never
 * applies guest-supplied reward numbers.
 */
public class CoopDuelResultEvent implements NetEvent {
    private static final long serialVersionUID = 4L;

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
    /**
     * RW1: catalog name (enemy data id) of the enemy the <em>guest</em> is credited
     * with for loot. Empty when unused. Length-capped.
     */
    private final String creditEnemyDataId;
    /**
     * RW1: theme id for the guest's credited enemy (EN1/EN2 core). Host-authoritative
     * so guest mirrors without {@code themeId} still get the right signature pool.
     */
    private final String creditThemeId;
    /**
     * RW1: card names from the credited enemy's played deck that are in its theme
     * core (signature candidates). Plain strings only; length-capped.
     */
    private final String[] signatureCandidates;

    public CoopDuelResultEvent(final long duelId, final int winningTeam, final long enemyId,
                               final String encounterId) {
        this(duelId, winningTeam, enemyId, encounterId, 1, "", "", null);
    }

    public CoopDuelResultEvent(final long duelId, final int winningTeam, final long enemyId,
                               final String encounterId, final int lootRolls) {
        this(duelId, winningTeam, enemyId, encounterId, lootRolls, "", "", null);
    }

    public CoopDuelResultEvent(final long duelId, final int winningTeam, final long enemyId,
                               final String encounterId, final int lootRolls,
                               final String creditEnemyDataId, final String creditThemeId,
                               final String[] signatureCandidates) {
        this.duelId = duelId;
        this.winningTeam = winningTeam;
        this.enemyId = enemyId;
        this.encounterId = encounterId != null ? encounterId : "";
        this.lootRolls = Math.max(0, Math.min(lootRolls, 8));
        this.creditEnemyDataId = CoopWireLimits.clampString(
                creditEnemyDataId, CoopWireLimits.MAX_ENEMY_DATA_ID_LEN);
        this.creditThemeId = CoopWireLimits.clampString(
                creditThemeId, CoopWireLimits.MAX_THEME_ID_LEN);
        this.signatureCandidates = clampCandidates(signatureCandidates);
    }

    private static String[] clampCandidates(final String[] raw) {
        if (raw == null || raw.length == 0) {
            return new String[0];
        }
        final int n = Math.min(raw.length, CoopWireLimits.MAX_SIGNATURE_CANDIDATES);
        final String[] out = new String[n];
        int w = 0;
        for (int i = 0; i < n; i++) {
            final String s = CoopWireLimits.clampString(raw[i], CoopWireLimits.MAX_CARD_NAME_LEN);
            if (s == null || s.isEmpty()) {
                continue;
            }
            out[w++] = s;
        }
        if (w == out.length) {
            return out;
        }
        final String[] trimmed = new String[w];
        System.arraycopy(out, 0, trimmed, 0, w);
        return trimmed;
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

    /** RW1: guest-credited enemy catalog name (may be empty). */
    public String getCreditEnemyDataId() {
        return creditEnemyDataId != null ? creditEnemyDataId : "";
    }

    /** RW1: guest-credited theme id (may be empty). */
    public String getCreditThemeId() {
        return creditThemeId != null ? creditThemeId : "";
    }

    /** RW1: host-authoritative signature candidate names (never null). */
    public String[] getSignatureCandidates() {
        return signatureCandidates != null ? signatureCandidates.clone() : new String[0];
    }
}
