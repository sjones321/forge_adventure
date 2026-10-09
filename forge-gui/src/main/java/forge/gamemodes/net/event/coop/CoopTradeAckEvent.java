package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1 two-phase commit ack.
 *
 * <ul>
 *   <li>Guest → host after applying (or failing to apply) {@link CoopTradeExecuteEvent}.
 *       Host applies only on {@code success=true}.</li>
 *   <li>Host → guest after the host has applied (commit point) — the guest discards
 *       its rollback snapshot. If this ack is lost, the guest must <b>not</b> roll
 *       back after host commit; it reconciles via {@link CoopTradeReconcileEvent}.</li>
 * </ul>
 */
public class CoopTradeAckEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeRole fromRole;
    private final boolean success;
    private final String detail;

    public CoopTradeAckEvent(final long tradeId, final CoopTradeRole fromRole,
                             final boolean success, final String detail) {
        this.tradeId = tradeId;
        this.fromRole = fromRole != null ? fromRole : CoopTradeRole.GUEST;
        this.success = success;
        this.detail = detail != null ? detail : "";
    }

    public long getTradeId() {
        return tradeId;
    }

    public CoopTradeRole getFromRole() {
        return fromRole;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getDetail() {
        return detail;
    }
}
