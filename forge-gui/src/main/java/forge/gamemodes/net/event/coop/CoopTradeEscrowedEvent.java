package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: this side has removed its own offer into escrow and persisted
 * {@code ESCROWED}. Peer may deliver only after receiving this.
 */
public class CoopTradeEscrowedEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeRole fromRole;

    public CoopTradeEscrowedEvent(final long tradeId, final CoopTradeRole fromRole) {
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
