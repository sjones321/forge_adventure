package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** CO2 hook: accept / decline a party invite, or leave a party. */
public class CoopPartyResponseEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum Action { ACCEPT, DECLINE, LEAVE }

    private final long inviteId;
    private final Action action;

    public CoopPartyResponseEvent(final long inviteId, final Action action) {
        this.inviteId = inviteId;
        this.action = action;
    }

    public long getInviteId() {
        return inviteId;
    }

    public Action getAction() {
        return action;
    }
}
