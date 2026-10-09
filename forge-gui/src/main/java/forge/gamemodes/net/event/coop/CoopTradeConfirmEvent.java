package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: set or clear this side's confirmation. Carries <b>both</b> offer versions
 * (mine and theirs). A confirm whose versions do not match the current state is
 * ignored. When both sides confirm matching current versions the host broadcasts
 * {@link CoopTradeExecuteEvent}.
 */
public class CoopTradeConfirmEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeRole fromRole;
    private final boolean confirmed;
    /** Must match the current offer version for {@code fromRole}. */
    private final int myOfferVersion;
    /** Must match the current offer version for the peer role. */
    private final int theirOfferVersion;

    public CoopTradeConfirmEvent(final long tradeId, final CoopTradeRole fromRole,
                                 final boolean confirmed,
                                 final int myOfferVersion, final int theirOfferVersion) {
        this.tradeId = tradeId;
        this.fromRole = fromRole != null ? fromRole : CoopTradeRole.GUEST;
        this.confirmed = confirmed;
        this.myOfferVersion = Math.max(0, myOfferVersion);
        this.theirOfferVersion = Math.max(0, theirOfferVersion);
    }

    /**
     * @deprecated use {@link #CoopTradeConfirmEvent(long, CoopTradeRole, boolean, int, int)}
     */
    @Deprecated
    public CoopTradeConfirmEvent(final long tradeId, final CoopTradeRole fromRole,
                                 final boolean confirmed, final int offerVersion) {
        this(tradeId, fromRole, confirmed, offerVersion, 0);
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

    public int getMyOfferVersion() {
        return myOfferVersion;
    }

    public int getTheirOfferVersion() {
        return theirOfferVersion;
    }

    /** @deprecated use {@link #getMyOfferVersion()} */
    @Deprecated
    public int getOfferVersion() {
        return myOfferVersion;
    }
}
