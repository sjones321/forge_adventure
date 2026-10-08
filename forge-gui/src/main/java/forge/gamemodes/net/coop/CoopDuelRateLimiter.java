package forge.gamemodes.net.coop;

/**
 * Token-bucket rate limiter for co-op duel requests (fight invite, join response,
 * loadout, guest fight request). Thread-safe for a single peer.
 */
public final class CoopDuelRateLimiter {
    private final int maxPerWindow;
    private final long windowMs;
    private volatile long windowStartMs;
    private volatile int count;

    public CoopDuelRateLimiter(final int maxPerWindow, final long windowMs) {
        if (maxPerWindow < 1) {
            throw new IllegalArgumentException("maxPerWindow");
        }
        if (windowMs < 1L) {
            throw new IllegalArgumentException("windowMs");
        }
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
        this.windowStartMs = 0L;
        this.count = 0;
    }

    public static CoopDuelRateLimiter perSecond(final int maxPerSecond) {
        return new CoopDuelRateLimiter(maxPerSecond, 1000L);
    }

    public synchronized boolean tryAcquire() {
        return tryAcquire(System.currentTimeMillis());
    }

    public synchronized boolean tryAcquire(final long nowMs) {
        if (windowStartMs <= 0L || nowMs - windowStartMs >= windowMs) {
            windowStartMs = nowMs;
            count = 0;
        }
        if (count >= maxPerWindow) {
            return false;
        }
        count++;
        return true;
    }

    public synchronized void reset() {
        windowStartMs = 0L;
        count = 0;
    }
}
