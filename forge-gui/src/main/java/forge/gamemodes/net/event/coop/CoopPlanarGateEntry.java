package forge.gamemodes.net.event.coop;

import java.io.Serializable;

/**
 * MV2: one planar gate (or legacy return portal) on the host's live world.
 * Plain data only — set code of the destination (empty = home) plus world position.
 * Guest replays these exactly; it never recomputes its own Standard window.
 */
public final class CoopPlanarGateEntry implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Destination set code; empty string means home (return portal). */
    private final String setCode;
    private final float x;
    private final float y;

    public CoopPlanarGateEntry(final String setCode, final float x, final float y) {
        this.setCode = setCode != null ? setCode : "";
        this.x = x;
        this.y = y;
    }

    /** Destination set code; empty means {@code home}. */
    public String getSetCode() {
        return setCode != null ? setCode : "";
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }
}
