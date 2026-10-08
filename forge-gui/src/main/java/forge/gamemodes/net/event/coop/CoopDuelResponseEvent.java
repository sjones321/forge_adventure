package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/** CO3 hook: accept or decline a duel invite. */
public class CoopDuelResponseEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long inviteId;
    private final boolean accepted;
    /** Selected decklist text when accepting (via DeckSerializer); empty when declining. */
    private final String decklistText;

    public CoopDuelResponseEvent(final long inviteId, final boolean accepted, final String decklistText) {
        this.inviteId = inviteId;
        this.accepted = accepted;
        this.decklistText = decklistText != null ? decklistText : "";
    }

    public long getInviteId() {
        return inviteId;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public String getDecklistText() {
        return decklistText;
    }
}
