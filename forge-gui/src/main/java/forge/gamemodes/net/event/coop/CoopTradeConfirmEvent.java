package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: set or clear this side's confirmation. Carries <b>both</b> offer versions
 * (mine and theirs) taken from the wire. When both sides confirm matching
 * current versions each side independently escrows its own offer.
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
}
