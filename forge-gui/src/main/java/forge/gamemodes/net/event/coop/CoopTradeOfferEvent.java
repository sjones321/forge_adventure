package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: replace this side's offer. Changing an offer clears both confirmations
 * on the host. Payload is plain data only.
 */
public class CoopTradeOfferEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final String fromPlayer;
    private final CoopTradeOffer offer;

    public CoopTradeOfferEvent(final long tradeId, final String fromPlayer, final CoopTradeOffer offer) {
        this.tradeId = tradeId;
        this.fromPlayer = fromPlayer;
        this.offer = offer != null ? offer : CoopTradeOffer.empty();
    }

    public long getTradeId() {
        return tradeId;
    }

    public String getFromPlayer() {
        return fromPlayer;
    }

    public CoopTradeOffer getOffer() {
        return offer;
    }
}
