package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** TR1: accept or decline a trade invite. */
public class CoopTradeResponseEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long inviteId;
    private final boolean accepted;

    public CoopTradeResponseEvent(final long inviteId, final boolean accepted) {
        this.inviteId = inviteId;
        this.accepted = accepted;
    }

    public long getInviteId() {
        return inviteId;
    }

    public boolean isAccepted() {
        return accepted;
    }
}
