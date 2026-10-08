package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Decklist as text (produced/consumed via {@link forge.deck.io.DeckSerializer}).
 * CO3 uses this instead of serializing {@code Deck} / {@code PaperCard} graphs.
 *
 * <p>CO1 only defines the wire shape; duel setup lives in CO3.
 */
public class CoopDecklistEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    /** Slot / purpose label, e.g. {@code "guest-main"} or {@code "host-sideboard"}. */
    private final String label;
    /** Full decklist text (UTF-8), never a live Deck object. */
    private final String decklistText;

    public CoopDecklistEvent(final String label, final String decklistText) {
        this.label = label;
        this.decklistText = decklistText != null ? decklistText : "";
    }

    public String getLabel() {
        return label;
    }

    public String getDecklistText() {
        return decklistText;
    }
}
