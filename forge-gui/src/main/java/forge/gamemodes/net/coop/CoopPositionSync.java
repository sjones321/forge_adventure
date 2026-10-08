package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;

/**
 * Headless CO2 position-sample validation and send-cadence helper. Rejects
 * teleports (speed above max×margin), bad avatars, and over-long names.
 */
public final class CoopPositionSync {
    private final CoopRateLimiter inboundLimiter;
    private final float minSendIntervalMs;
    private final float maxSpeedPxPerSec;
    private volatile long lastSendMs;
    private volatile CoopPlayerMoveEvent lastAccepted;
    private volatile long lastAcceptedWallMs;

    public CoopPositionSync(final int maxInboundPerSecond, final float sendHz) {
        this(maxInboundPerSecond, sendHz, CoopWireLimits.DEFAULT_MAX_MOVE_SPEED_PX);
    }

    public CoopPositionSync(final int maxInboundPerSecond, final float sendHz, final float maxSpeedPxPerSec) {
        final int max = Math.max(1, maxInboundPerSecond);
        this.inboundLimiter = new CoopRateLimiter(max, 1000L);
        final float hz = sendHz <= 0f ? 15f : sendHz;
        this.minSendIntervalMs = 1000f / hz;
        this.maxSpeedPxPerSec = maxSpeedPxPerSec > 0f ? maxSpeedPxPerSec : CoopWireLimits.DEFAULT_MAX_MOVE_SPEED_PX;
        this.lastSendMs = 0L;
    }

    public CoopRateLimiter getInboundLimiter() {
        return inboundLimiter;
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
        // Teleport / speed check against last accepted sample.
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
        final CoopPlayerMoveEvent accepted = new CoopPlayerMoveEvent(
                event.getX(), event.getY(), event.getFacing(), event.getClientTimeMs(), name, avatar);
        lastAccepted = accepted;
        lastAcceptedWallMs = nowMs;
        return accepted;
    }

    public CoopPlayerMoveEvent getLastAccepted() {
        return lastAccepted;
    }

    public void clear() {
        lastAccepted = null;
        lastAcceptedWallMs = 0L;
        lastSendMs = 0L;
        inboundLimiter.reset();
    }

    public static float lerp(final float a, final float b, final float t) {
        final float u = t < 0f ? 0f : (t > 1f ? 1f : t);
        return a + (b - a) * u;
    }
}
