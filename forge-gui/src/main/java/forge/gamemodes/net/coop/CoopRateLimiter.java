package forge.gamemodes.net.coop;

/**
 * Token-bucket style rate limiter for co-op position and request messages.
 * Thread-safe for a single peer (host tracks the guest; guest tracks the host).
 */
public final class CoopRateLimiter {
    private final int maxPerWindow;
    private final long windowMs;
    private volatile long windowStartMs;
    private volatile int count;

    public CoopRateLimiter(final int maxPerWindow, final long windowMs) {
        if (maxPerWindow < 1) {
            throw new IllegalArgumentException("maxPerWindow");
        }
        if (windowMs < 1L) {
            throw new IllegalArgumentException("windowMs");
        }
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
        // 0 = not started; first tryAcquire(now) opens the window at {@code now}
        // so injected clocks in tests are not fighting System.currentTimeMillis().
        this.windowStartMs = 0L;
        this.count = 0;
    }

    /** @return true if the event is allowed under the current budget */
    public synchronized boolean tryAcquire() {
        return tryAcquire(System.currentTimeMillis());
    }

    /** Testable overload with an injected clock. */
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

    public int getMaxPerWindow() {
        return maxPerWindow;
    }

    public long getWindowMs() {
        return windowMs;
    }
}
