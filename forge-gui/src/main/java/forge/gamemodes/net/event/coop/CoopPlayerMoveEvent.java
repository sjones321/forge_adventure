package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2 hook: guest → host position/facing samples (10–20 Hz). Host confirms and
 * broadcasts peer sprites. CO1 registers the type; CO2 fills the gameplay.
 */
public class CoopPlayerMoveEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final float x;
    private final float y;
    private final float facing;
    private final long clientTimeMs;

    public CoopPlayerMoveEvent(final float x, final float y, final float facing, final long clientTimeMs) {
        this.x = x;
        this.y = y;
        this.facing = facing;
        this.clientTimeMs = clientTimeMs;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getFacing() {
        return facing;
    }

    public long getClientTimeMs() {
        return clientTimeMs;
    }
}
