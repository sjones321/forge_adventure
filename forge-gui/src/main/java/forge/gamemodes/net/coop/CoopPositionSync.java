package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;

/**
 * Headless CO2 position-sample validation and send-cadence helper. Does not
 * touch LibGDX — safe for unit tests next to {@code CoopSessionConnectionTest}.
 */
public final class CoopPositionSync {
    private final CoopRateLimiter inboundLimiter;
    private final float minSendIntervalMs;
    private volatile long lastSendMs;
    private volatile CoopPlayerMoveEvent lastAccepted;

    public CoopPositionSync(final int maxInboundPerSecond, final float sendHz) {
        final int max = Math.max(1, maxInboundPerSecond);
        this.inboundLimiter = new CoopRateLimiter(max, 1000L);
        final float hz = sendHz <= 0f ? 15f : sendHz;
        this.minSendIntervalMs = 1000f / hz;
        this.lastSendMs = 0L;
    }

    public CoopRateLimiter getInboundLimiter() {
        return inboundLimiter;
    }

    /** Whether enough time has passed to emit another outbound sample. */
    public boolean shouldSend(final long nowMs) {
        if (lastSendMs <= 0L) {
            return true;
        }
        return (nowMs - lastSendMs) >= minSendIntervalMs;
    }

    public void markSent(final long nowMs) {
        lastSendMs = nowMs;
    }

    /**
     * Validate and accept an inbound move. Returns the accepted event, or
     * {@code null} if rate-limited / out of bounds / strings too long.
     */
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
        final String name = CoopWireLimits.clampString(event.getPlayerName(), CoopWireLimits.MAX_PLAYER_NAME_LEN);
        final String avatar = CoopWireLimits.clampString(event.getAvatarId(), CoopWireLimits.MAX_AVATAR_ID_LEN);
        if (event.getPlayerName() != null && event.getPlayerName().length() > CoopWireLimits.MAX_PLAYER_NAME_LEN) {
            return null;
        }
        if (event.getAvatarId() != null && event.getAvatarId().length() > CoopWireLimits.MAX_AVATAR_ID_LEN) {
            return null;
        }
        final CoopPlayerMoveEvent accepted = new CoopPlayerMoveEvent(
                event.getX(), event.getY(), event.getFacing(), event.getClientTimeMs(), name, avatar);
        lastAccepted = accepted;
        return accepted;
    }

    public CoopPlayerMoveEvent getLastAccepted() {
        return lastAccepted;
    }

    public void clear() {
        lastAccepted = null;
        lastSendMs = 0L;
        inboundLimiter.reset();
    }

    /**
     * Linear interpolation helper for partner drawing. {@code t} is clamped to
     * {@code [0,1]}.
     */
    public static float lerp(final float a, final float b, final float t) {
        final float u = t < 0f ? 0f : (t > 1f ? 1f : t);
        return a + (b - a) * u;
    }
}
