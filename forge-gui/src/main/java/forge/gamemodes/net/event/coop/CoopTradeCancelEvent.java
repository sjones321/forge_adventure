package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: cancel an open trade (decline, disconnect, invalid offer, timeout).
 * No partial swap is applied.
 */
public class CoopTradeCancelEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final String reason;

    public CoopTradeCancelEvent(final long tradeId, final String reason) {
        this.tradeId = tradeId;
        this.reason = reason != null ? reason : "";
    }

    public long getTradeId() {
        return tradeId;
    }

    public String getReason() {
        return reason;
    }
}
