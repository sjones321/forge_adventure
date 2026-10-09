package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: set or clear this side's confirmation for a specific offer version.
 * A confirm for a stale version is ignored. When both sides confirm matching
 * current versions the host broadcasts {@link CoopTradeExecuteEvent}.
 */
public class CoopTradeConfirmEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeRole fromRole;
    private final boolean confirmed;
    /** Must match the current offer version for {@code fromRole}. */
    private final int offerVersion;

    public CoopTradeConfirmEvent(final long tradeId, final CoopTradeRole fromRole,
                                 final boolean confirmed, final int offerVersion) {
        this.tradeId = tradeId;
        this.fromRole = fromRole != null ? fromRole : CoopTradeRole.GUEST;
        this.confirmed = confirmed;
        this.offerVersion = Math.max(0, offerVersion);
    }

    public long getTradeId() {
        return tradeId;
    }

    public CoopTradeRole getFromRole() {
        return fromRole;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public int getOfferVersion() {
        return offerVersion;
    }
}
