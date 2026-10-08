package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2 v1: host tells the guest whether shared world simulation is paused
 * (host in an interior or a duel). Guest keeps free overworld movement and
 * shows a banner; enemy AI / spawns / lifetimes freeze on both sides.
 */
public class CoopHostPresenceEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum Presence { OVERWORLD, INTERIOR, DUEL }

    private final Presence presence;
    private final String placeLabel;

    public CoopHostPresenceEvent(final Presence presence, final String placeLabel) {
        this.presence = presence == null ? Presence.OVERWORLD : presence;
        this.placeLabel = placeLabel == null ? "" : placeLabel;
    }

    public Presence getPresence() {
        return presence;
    }

    public String getPlaceLabel() {
        return placeLabel;
    }

    public boolean isWorldPaused() {
        return presence == Presence.INTERIOR || presence == Presence.DUEL;
    }
}
