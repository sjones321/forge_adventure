package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.coop.CoopFightLoadout;
import forge.gamemodes.net.event.NetEvent;

/**
 * Guest → host: adventure loadout for a joined fight (plain bounded data).
 * Avatars are names/ids, never textures.
 */
public class CoopFightLoadoutEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long duelId;
    private final CoopFightLoadout loadout;
    private final String decklistText;

    public CoopFightLoadoutEvent(final long duelId, final CoopFightLoadout loadout, final String decklistText) {
        this.duelId = duelId;
        this.loadout = loadout;
        this.decklistText = decklistText != null ? decklistText : "";
    }

    public long getDuelId() {
        return duelId;
    }

    public CoopFightLoadout getLoadout() {
        return loadout;
    }

    public String getDecklistText() {
        return decklistText;
    }
}
