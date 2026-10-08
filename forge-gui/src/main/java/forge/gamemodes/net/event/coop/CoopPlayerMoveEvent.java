package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: position / facing / avatar-id samples (10–20 Hz). Avatars travel as
 * names/ids only — never textures. Both peers send; each draws the other as a
 * partner sprite.
 *
 * <p>Explicit {@linkplain #isTeleport() teleport} samples reset the peer's
 * last-accepted position (waypoint / portal / respawn / POI exit) and are only
 * accepted after an allowing action on the validator.
 */
public class CoopPlayerMoveEvent implements NetEvent {
    private static final long serialVersionUID = 3L;

    private final float x;
    private final float y;
    /** Facing as {@code AnimationDirections} ordinal (0–8). */
    private final float facing;
    private final long clientTimeMs;
    private final String playerName;
    /** Sprite atlas path / hero id (e.g. {@code sprites/heroes/Human_m.atlas}). */
    private final String avatarId;
    /** Sender's current max walk speed (px/s) for peer validation. */
    private final float reportedMaxSpeedPx;
    /** When true, bypasses walk speed checks if the peer has armed a teleport. */
    private final boolean teleport;

    public CoopPlayerMoveEvent(final float x, final float y, final float facing, final long clientTimeMs) {
        this(x, y, facing, clientTimeMs, "", "", 0f, false);
    }

    public CoopPlayerMoveEvent(final float x, final float y, final float facing, final long clientTimeMs,
                               final String playerName, final String avatarId) {
        this(x, y, facing, clientTimeMs, playerName, avatarId, 0f, false);
    }

    public CoopPlayerMoveEvent(final float x, final float y, final float facing, final long clientTimeMs,
                               final String playerName, final String avatarId,
                               final float reportedMaxSpeedPx, final boolean teleport) {
        this.x = x;
        this.y = y;
        this.facing = facing;
        this.clientTimeMs = clientTimeMs;
        this.playerName = playerName == null ? "" : playerName;
        this.avatarId = avatarId == null ? "" : avatarId;
        this.reportedMaxSpeedPx = reportedMaxSpeedPx;
        this.teleport = teleport;
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

    /** Sender's reported max walk speed in px/s (0 = use validator default). */
    public float getReportedMaxSpeedPx() {
        return reportedMaxSpeedPx;
    }

    public boolean isTeleport() {
        return teleport;
    }
}
