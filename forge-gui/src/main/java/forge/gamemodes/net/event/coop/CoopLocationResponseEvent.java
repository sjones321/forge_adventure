package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** CO2: accept or decline a {@link CoopLocationInviteEvent}. */
public class CoopLocationResponseEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum Action { ACCEPT, DECLINE }

    private final long inviteId;
    private final Action action;

    public CoopLocationResponseEvent(final long inviteId, final Action action) {
        this.inviteId = inviteId;
        this.action = action == null ? Action.DECLINE : action;
    }

    public long getInviteId() {
        return inviteId;
    }

    public Action getAction() {
        return action;
    }
}
