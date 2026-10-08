package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: position / facing / avatar-id samples (10–20 Hz). Avatars travel as
 * names/ids only — never textures. Both peers send; each draws the other as a
 * partner sprite.
 */
public class CoopPlayerMoveEvent implements NetEvent {
    private static final long serialVersionUID = 2L;

    private final float x;
    private final float y;
    /** Facing as {@code AnimationDirections} ordinal (0–8). */
    private final float facing;
    private final long clientTimeMs;
    private final String playerName;
    /** Sprite atlas path / hero id (e.g. {@code sprites/heroes/Human_m.atlas}). */
    private final String avatarId;

    public CoopPlayerMoveEvent(final float x, final float y, final float facing, final long clientTimeMs) {
        this(x, y, facing, clientTimeMs, "", "");
    }

    public CoopPlayerMoveEvent(final float x, final float y, final float facing, final long clientTimeMs,
                               final String playerName, final String avatarId) {
        this.x = x;
        this.y = y;
        this.facing = facing;
        this.clientTimeMs = clientTimeMs;
        this.playerName = playerName == null ? "" : playerName;
        this.avatarId = avatarId == null ? "" : avatarId;
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

    public String getPlayerName() {
        return playerName;
    }

    public String getAvatarId() {
        return avatarId;
    }
}
