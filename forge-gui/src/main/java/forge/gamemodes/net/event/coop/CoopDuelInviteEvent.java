package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO3 hook: opt-in "Join the fight?" prompt when a party partner starts a duel
 * nearby. No answer / not in a party → solo fight. Game port is
 * {@link forge.gamemodes.net.coop.CoopPorts#GAME_PORT}.
 */
public class CoopDuelInviteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long inviteId;
    private final String hostPlayer;
    private final String encounterId;
    private final int timeoutSeconds;

    public CoopDuelInviteEvent(final long inviteId, final String hostPlayer,
                               final String encounterId, final int timeoutSeconds) {
        this.inviteId = inviteId;
        this.hostPlayer = hostPlayer;
        this.encounterId = encounterId;
        this.timeoutSeconds = timeoutSeconds;
    }

    public long getInviteId() {
        return inviteId;
    }

    public String getHostPlayer() {
        return hostPlayer;
    }

    public String getEncounterId() {
        return encounterId;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
