package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: set or clear this side's confirmation. When both sides are confirmed the
 * host validates again and broadcasts {@link CoopTradeExecuteEvent}.
 */
public class CoopTradeConfirmEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final String fromPlayer;
    private final boolean confirmed;

    public CoopTradeConfirmEvent(final long tradeId, final String fromPlayer, final boolean confirmed) {
        this.tradeId = tradeId;
        this.fromPlayer = fromPlayer;
        this.confirmed = confirmed;
    }

    public long getTradeId() {
        return tradeId;
    }

    public String getFromPlayer() {
        return fromPlayer;
    }

    public boolean isConfirmed() {
        return confirmed;
    }
}
