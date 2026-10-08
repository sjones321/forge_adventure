package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** CO2 hook: invite the peer into a party (opt-in; never forced). */
public class CoopPartyInviteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String fromPlayer;
    private final long inviteId;

    public CoopPartyInviteEvent(final String fromPlayer, final long inviteId) {
        this.fromPlayer = fromPlayer;
        this.inviteId = inviteId;
    }

    public String getFromPlayer() {
        return fromPlayer;
    }

    public long getInviteId() {
        return inviteId;
    }
}
