package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: host starts two-phase commit. The guest applies first and acks; the host
 * applies only after a successful {@link CoopTradeAckEvent}. Failure or
 * disconnect before that ack means neither side keeps changes.
 */
public class CoopTradeExecuteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final CoopTradeOffer hostOffer;
    private final CoopTradeOffer guestOffer;
    private final int hostOfferVersion;
    private final int guestOfferVersion;

    public CoopTradeExecuteEvent(final long tradeId,
                                 final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer,
                                 final int hostOfferVersion, final int guestOfferVersion) {
        this.tradeId = tradeId;
        this.hostOffer = hostOffer != null ? hostOffer : CoopTradeOffer.empty();
        this.guestOffer = guestOffer != null ? guestOffer : CoopTradeOffer.empty();
        this.hostOfferVersion = Math.max(0, hostOfferVersion);
        this.guestOfferVersion = Math.max(0, guestOfferVersion);
    }

    public long getTradeId() {
        return tradeId;
    }

    public CoopTradeOffer getHostOffer() {
        return hostOffer;
    }

    public CoopTradeOffer getGuestOffer() {
        return guestOffer;
    }

    public int getHostOfferVersion() {
        return hostOfferVersion;
    }

    public int getGuestOfferVersion() {
        return guestOfferVersion;
    }
}
