package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: replace this side's offer. Changing an offer clears both confirmations
 * and bumps that side's offer version. Payload is plain data only.
 */
public class CoopTradeOfferEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeRole fromRole;
    private final CoopTradeOffer offer;
    /** Version assigned by the sender after accepting this offer locally. */
    private final int offerVersion;

    public CoopTradeOfferEvent(final long tradeId, final CoopTradeRole fromRole,
                               final CoopTradeOffer offer, final int offerVersion) {
        this.tradeId = tradeId;
        this.fromRole = fromRole != null ? fromRole : CoopTradeRole.GUEST;
        this.offer = offer != null ? offer : CoopTradeOffer.empty();
        this.offerVersion = Math.max(0, offerVersion);
    }

    public long getTradeId() {
        return tradeId;
    }

    public CoopTradeRole getFromRole() {
        return fromRole;
    }

    public CoopTradeOffer getOffer() {
        return offer;
    }

    public int getOfferVersion() {
        return offerVersion;
    }
}
