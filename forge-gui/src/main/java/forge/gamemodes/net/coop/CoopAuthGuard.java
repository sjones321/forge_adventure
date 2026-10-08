package forge.gamemodes.net.coop;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-address session-code failure tracking. After
 * {@link CoopPorts#SESSION_CODE_MAX_FAILURES} failures, the address is locked
 * out for {@link CoopPorts#SESSION_CODE_LOCKOUT_MS}. Host state is unaffected.
 */
public final class CoopAuthGuard {
    private static final class Attempt {
        final AtomicInteger failures = new AtomicInteger();
        volatile long lockoutUntilMs;
    }

    private final ConcurrentHashMap<String, Attempt> byAddress = new ConcurrentHashMap<>();

    public boolean isLockedOut(final String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        final Attempt a = byAddress.get(address);
        if (a == null) {
            return false;
        }
        final long until = a.lockoutUntilMs;
        if (until <= 0L) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            a.lockoutUntilMs = 0L;
            a.failures.set(0);
            return false;
        }
        return true;
    }

    /** @return remaining lockout milliseconds, or 0 if not locked */
    public long lockoutRemainingMs(final String address) {
        if (address == null || address.isEmpty()) {
            return 0L;
        }
        final Attempt a = byAddress.get(address);
        if (a == null || a.lockoutUntilMs <= 0L) {
            return 0L;
        }
        final long rem = a.lockoutUntilMs - System.currentTimeMillis();
        return Math.max(0L, rem);
    }

    /**
     * Record a failed session-code (or pre-auth) attempt.
     * @return true if this failure triggered or extends a lockout
     */
    public boolean recordFailure(final String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }
        final Attempt a = byAddress.computeIfAbsent(address, k -> new Attempt());
        final int n = a.failures.incrementAndGet();
        if (n >= CoopPorts.SESSION_CODE_MAX_FAILURES) {
            a.lockoutUntilMs = System.currentTimeMillis() + CoopPorts.SESSION_CODE_LOCKOUT_MS;
            return true;
        }
        return false;
    }

    /** Clear failures after a successful hello. */
    public void recordSuccess(final String address) {
        if (address == null || address.isEmpty()) {
            return;
        }
        byAddress.remove(address);
    }

    /** Test helper: inject a frozen clock by setting lockout directly. */
    void forceLockout(final String address, final long untilMs) {
        final Attempt a = byAddress.computeIfAbsent(address, k -> new Attempt());
        a.failures.set(CoopPorts.SESSION_CODE_MAX_FAILURES);
        a.lockoutUntilMs = untilMs;
    }
}
