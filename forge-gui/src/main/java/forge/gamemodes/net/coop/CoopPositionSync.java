package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;

/**
 * Headless CO2 position-sample validation and send-cadence helper. Rejects
 * teleports (speed above max×margin) unless an explicit teleport sample is
 * armed by an allowing action, bad avatars, and over-long names.
 */
public final class CoopPositionSync {
    private final CoopRateLimiter inboundLimiter;
    private final float minSendIntervalMs;
    private volatile float maxSpeedPxPerSec;
    private final float hardSpeedCeilingPx;
    private volatile long lastSendMs;
    private volatile CoopPlayerMoveEvent lastAccepted;
    private volatile long lastAcceptedWallMs;
    /** One-shot: next inbound teleport sample may reset lastAccepted. */
    private volatile boolean teleportArmed;

    public CoopPositionSync(final int maxInboundPerSecond, final float sendHz) {
        this(maxInboundPerSecond, sendHz, CoopWireLimits.DEFAULT_MAX_MOVE_SPEED_PX);
    }

    public CoopPositionSync(final int maxInboundPerSecond, final float sendHz, final float maxSpeedPxPerSec) {
        final int max = Math.max(1, maxInboundPerSecond);
        this.inboundLimiter = new CoopRateLimiter(max, 1000L);
        final float hz = sendHz <= 0f ? 15f : sendHz;
        this.minSendIntervalMs = 1000f / hz;
        final float speed = maxSpeedPxPerSec > 0f ? maxSpeedPxPerSec : CoopWireLimits.DEFAULT_MAX_MOVE_SPEED_PX;
        this.maxSpeedPxPerSec = speed;
        this.hardSpeedCeilingPx = Math.max(speed, CoopWireLimits.HARD_MAX_MOVE_SPEED_PX);
        this.lastSendMs = 0L;
    }

    public CoopRateLimiter getInboundLimiter() {
        return inboundLimiter;
    }

    public float getMaxSpeedPxPerSec() {
        return maxSpeedPxPerSec;
    }

    /**
     * Update the walk-speed ceiling from the peer's reported (or local) max
     * speed. Clamped to the hard ceiling.
     */
    public void setMaxSpeedPxPerSec(final float maxSpeedPxPerSec) {
        if (maxSpeedPxPerSec <= 0f || !CoopWireLimits.isFinite(maxSpeedPxPerSec)) {
            return;
        }
        this.maxSpeedPxPerSec = Math.min(maxSpeedPxPerSec, hardSpeedCeilingPx);
    }

    /**
     * Arm acceptance of the next inbound teleport sample (waypoint / portal /
     * resetPlayerLocation / POI exit). Consumed on the next accepted teleport.
     */
    public void allowTeleport() {
        teleportArmed = true;
    }

    public boolean isTeleportArmed() {
        return teleportArmed;
    }

    public boolean shouldSend(final long nowMs) {
        if (lastSendMs <= 0L) {
            return true;
        }
        return (nowMs - lastSendMs) >= minSendIntervalMs;
    }

    public void markSent(final long nowMs) {
        lastSendMs = nowMs;
    }

    public CoopPlayerMoveEvent acceptInbound(final CoopPlayerMoveEvent event) {
        return acceptInbound(event, System.currentTimeMillis());
    }

    public CoopPlayerMoveEvent acceptInbound(final CoopPlayerMoveEvent event, final long nowMs) {
        if (event == null) {
            return null;
        }
        if (!inboundLimiter.tryAcquire(nowMs)) {
            return null;
        }
        if (!CoopWireLimits.coordsInBounds(event.getX(), event.getY())) {
            return null;
        }
        if (!CoopWireLimits.facingInBounds(event.getFacing())) {
            return null;
        }
        final String name = CoopWireLimits.acceptPlayerName(event.getPlayerName());
        if (name == null) {
            return null; // over-long name rejected
        }
        final String avatar = event.getAvatarId() == null ? "" : event.getAvatarId();
        if (!avatar.isEmpty() && !CoopWireLimits.isAllowedAvatarId(avatar)) {
            return null;
        }

        // Peer may advertise a higher walk speed (base × road × equipment/skill).
        if (event.getReportedMaxSpeedPx() > 0f) {
            setMaxSpeedPxPerSec(event.getReportedMaxSpeedPx());
        }

        if (event.isTeleport()) {
            if (!teleportArmed) {
                return null;
            }
            teleportArmed = false;
            final CoopPlayerMoveEvent accepted = copyAccepted(event, name, avatar);
            lastAccepted = accepted;
            lastAcceptedWallMs = nowMs;
            return accepted;
        }

        // Walk sample: speed check against last accepted.
        final CoopPlayerMoveEvent prev = lastAccepted;
        if (prev != null && lastAcceptedWallMs > 0L) {
            final long dtMs = Math.max(1L, nowMs - lastAcceptedWallMs);
            final float dx = event.getX() - prev.getX();
            final float dy = event.getY() - prev.getY();
            final float dist = (float) Math.sqrt(dx * dx + dy * dy);
            final float maxDist = maxSpeedPxPerSec * CoopWireLimits.MOVE_SPEED_MARGIN * (dtMs / 1000f);
            if (dist > maxDist && dist > 1f) {
                return null;
            }
        }
        final CoopPlayerMoveEvent accepted = copyAccepted(event, name, avatar);
        lastAccepted = accepted;
        lastAcceptedWallMs = nowMs;
        return accepted;
    }

    /** Reset last-accepted without a wire sample (local teleport before send). */
    public void resetLastAccepted(final float x, final float y, final float facing,
                                  final long nowMs, final String playerName, final String avatarId) {
        final String name = CoopWireLimits.acceptPlayerName(playerName);
        final String avatar = avatarId == null ? "" : avatarId;
        lastAccepted = new CoopPlayerMoveEvent(x, y, facing, nowMs,
                name == null ? "" : name, avatar, maxSpeedPxPerSec, true);
        lastAcceptedWallMs = nowMs;
    }

    public CoopPlayerMoveEvent getLastAccepted() {
        return lastAccepted;
    }

    public void clear() {
        lastAccepted = null;
        lastAcceptedWallMs = 0L;
        lastSendMs = 0L;
        teleportArmed = false;
        inboundLimiter.reset();
    }

    public static float lerp(final float a, final float b, final float t) {
        final float u = t < 0f ? 0f : (t > 1f ? 1f : t);
        return a + (b - a) * u;
    }

    private CoopPlayerMoveEvent copyAccepted(final CoopPlayerMoveEvent event,
                                             final String name, final String avatar) {
        return new CoopPlayerMoveEvent(
                event.getX(), event.getY(), event.getFacing(), event.getClientTimeMs(),
                name, avatar, event.getReportedMaxSpeedPx(), event.isTeleport());
    }
}
