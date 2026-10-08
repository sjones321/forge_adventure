package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: party partner nearby — ask whether to enter the same town / dungeon /
 * delve. Declining is always fine; nobody is pulled without ACCEPT.
 */
public class CoopLocationInviteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long inviteId;
    private final String fromPlayer;
    private final String poiId;
    private final String displayName;
    private final int timeoutSeconds;

    public CoopLocationInviteEvent(final long inviteId, final String fromPlayer, final String poiId,
                                   final String displayName, final int timeoutSeconds) {
        this.inviteId = inviteId;
        this.fromPlayer = fromPlayer == null ? "" : fromPlayer;
        this.poiId = poiId == null ? "" : poiId;
        this.displayName = displayName == null ? "" : displayName;
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getInviteId() {
        return inviteId;
    }

    public String getFromPlayer() {
        return fromPlayer;
    }

    public String getPoiId() {
        return poiId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
