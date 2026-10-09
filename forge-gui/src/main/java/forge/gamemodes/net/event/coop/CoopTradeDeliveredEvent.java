package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: this side has granted itself the peer's escrowed offer and persisted
 * {@code DELIVERED}. Received goods are never reversed.
 */
public class CoopTradeDeliveredEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeRole fromRole;

    public CoopTradeDeliveredEvent(final long tradeId, final CoopTradeRole fromRole) {
        this.tradeId = tradeId;
        this.fromRole = fromRole != null ? fromRole : CoopTradeRole.GUEST;
    }

    public long getTradeId() {
        return tradeId;
    }

    public CoopTradeRole getFromRole() {
        return fromRole;
    }
}
