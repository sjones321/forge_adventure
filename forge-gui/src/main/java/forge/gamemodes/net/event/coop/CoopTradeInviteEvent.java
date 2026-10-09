package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: invite the peer to open a face-to-face trade window. Goes through the
 * co-op invite queue (never replaces an open dialog / exit-dungeon).
 */
public class CoopTradeInviteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long inviteId;
    private final String fromPlayer;
    private final int timeoutSeconds;

    public CoopTradeInviteEvent(final long inviteId, final String fromPlayer, final int timeoutSeconds) {
        this.inviteId = inviteId;
        this.fromPlayer = fromPlayer;
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getInviteId() {
        return inviteId;
    }

    public String getFromPlayer() {
        return fromPlayer;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
