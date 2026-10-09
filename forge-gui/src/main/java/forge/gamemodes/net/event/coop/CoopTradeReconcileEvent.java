package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeLog;
import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1 reconnect reconcile: advertise this side's log phase for a matching trade
 * id so an in-flight escrow ends consistently. Plain-data only.
 */
public class CoopTradeReconcileEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeRole fromRole;
    private final String phase;
    /** True when this is a query; false when reporting authoritative local phase. */
    private final boolean request;

    public CoopTradeReconcileEvent(final long tradeId, final CoopTradeRole fromRole,
                                   final CoopTradeLog.Phase phase, final boolean request) {
        this.tradeId = tradeId;
        this.fromRole = fromRole != null ? fromRole : CoopTradeRole.GUEST;
        this.phase = phase != null ? phase.name() : CoopTradeLog.Phase.NONE.name();
        this.request = request;
    }

    public long getTradeId() {
        return tradeId;
    }

    public CoopTradeRole getFromRole() {
        return fromRole;
    }

    public String getPhase() {
        return phase;
    }

    public CoopTradeLog.Phase getPhaseEnum() {
        try {
            return CoopTradeLog.Phase.valueOf(phase);
        } catch (final RuntimeException ex) {
            return CoopTradeLog.Phase.NONE;
        }
    }

    public boolean isRequest() {
        return request;
    }
}
