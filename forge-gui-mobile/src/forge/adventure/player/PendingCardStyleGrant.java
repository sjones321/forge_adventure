package forge.adventure.player;

/**
 * CS1 pending grant recorded by AC1 when a set (or all-sets) achievement unlocks.
 * CS1 later applies the style once the player owns the card again.
 */
public final class PendingCardStyleGrant {
    public final String styleId;
    public final String setCode;
    public final String achievementId;
    public final long atMillis;

    public PendingCardStyleGrant(String styleId, String setCode, String achievementId, long atMillis) {
        this.styleId = styleId == null ? "" : styleId;
        this.setCode = setCode == null ? "" : setCode;
        this.achievementId = achievementId == null ? "" : achievementId;
        this.atMillis = atMillis;
    }
}
