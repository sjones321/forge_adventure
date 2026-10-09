package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: guest requests a trade. The host mints the trade id and replies with
 * {@link CoopTradeInviteEvent}. Plain-data only — no id on the request.
 */
public class CoopTradeRequestEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String fromPlayer;

    public CoopTradeRequestEvent(final String fromPlayer) {
        this.fromPlayer = fromPlayer;
    }

    public String getFromPlayer() {
        return fromPlayer;
    }
}
