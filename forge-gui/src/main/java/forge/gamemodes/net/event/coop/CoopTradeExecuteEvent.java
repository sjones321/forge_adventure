package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.event.NetEvent;

/**
 * TR1: host-authoritative commit. Both peers apply the swap atomically on the
 * GL thread — either both character inventories change or neither does.
 */
public class CoopTradeExecuteEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long tradeId;
    private final String hostPlayer;
    private final String guestPlayer;
    private final CoopTradeOffer hostOffer;
    private final CoopTradeOffer guestOffer;

    public CoopTradeExecuteEvent(final long tradeId, final String hostPlayer, final String guestPlayer,
                                 final CoopTradeOffer hostOffer, final CoopTradeOffer guestOffer) {
        this.tradeId = tradeId;
        this.hostPlayer = hostPlayer;
        this.guestPlayer = guestPlayer;
        this.hostOffer = hostOffer != null ? hostOffer : CoopTradeOffer.empty();
        this.guestOffer = guestOffer != null ? guestOffer : CoopTradeOffer.empty();
    }

    public long getTradeId() {
        return tradeId;
    }

    public String getHostPlayer() {
        return hostPlayer;
    }

    public String getGuestPlayer() {
        return guestPlayer;
    }

    public CoopTradeOffer getHostOffer() {
        return hostOffer;
    }

    public CoopTradeOffer getGuestOffer() {
        return guestOffer;
    }
}
