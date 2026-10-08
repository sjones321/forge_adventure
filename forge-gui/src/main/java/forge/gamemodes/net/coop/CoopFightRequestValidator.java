package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.coop.CoopEnemyEncounterRequestEvent;
import forge.gamemodes.net.event.coop.CoopFightRequestResultEvent;

/**
 * Host-side validation for guest enemy-encounter requests (CO3). Enemy ownership
 * is host-only; the guest asks via {@link CoopEnemyEncounterRequestEvent} and the
 * host decides. CO2 supplies enemy existence / position checks via
 * {@link EnemyLookup}; the default denies everything so feature/set-start builds
 * alone. After #18 merges, CO2 calls {@code CoopHooks.notifyGuestEnemyEncounter}
 * which CO3 handles; this validator still bounds and rate-limits the wire event.
 */
public final class CoopFightRequestValidator {
    public interface EnemyLookup {
        /**
         * @return true when the host owns a live enemy with this id near enough
         *         to the guest coordinates for a fight
         */
        boolean enemyExistsNear(long enemyId, String enemyDataId, float guestX, float guestY);
    }

    /** Safe default until CO2 wires real enemy authority. */
    public static final EnemyLookup DENY_ALL = (enemyId, enemyDataId, guestX, guestY) -> false;

    private final CoopDuelRateLimiter rateLimiter;
    private volatile EnemyLookup enemyLookup = DENY_ALL;
    private volatile boolean hostOnly = true;

    public CoopFightRequestValidator() {
        this(CoopDuelRateLimiter.perSecond(CoopDuelWireLimits.MAX_DUEL_REQUESTS_PER_SECOND));
    }

    public CoopFightRequestValidator(final CoopDuelRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter != null
                ? rateLimiter
                : CoopDuelRateLimiter.perSecond(CoopDuelWireLimits.MAX_DUEL_REQUESTS_PER_SECOND);
    }

    public void setEnemyLookup(final EnemyLookup lookup) {
        this.enemyLookup = lookup != null ? lookup : DENY_ALL;
    }

    /** When true (default), only the host may accept. */
    public void setHostOnly(final boolean hostOnly) {
        this.hostOnly = hostOnly;
    }

    /**
     * Validate an inbound guest encounter request on the host.
     * @return result event to send (ACCEPT or DENY); never null
     */
    public CoopFightRequestResultEvent validateOnHost(final CoopEnemyEncounterRequestEvent request,
                                                      final boolean callerIsHost) {
        if (!callerIsHost) {
            return deny(0L, "not host");
        }
        if (request == null) {
            return deny(0L, "null");
        }
        if (!rateLimiter.tryAcquire()) {
            return deny(request.getRequestId(), "rate limited");
        }
        // CO2: guest-local request ids are positive; host-local ids are negative and
        // must never arrive on the wire. Enemy ids from the host registry are positive.
        if (request.getRequestId() <= 0L || request.getEnemyId() <= 0L) {
            return deny(request.getRequestId(), "bad ids");
        }
        final String enc = request.getEnemyDataId();
        if (enc == null || enc.isEmpty() || enc.length() > CoopDuelWireLimits.MAX_NAME_LEN) {
            return deny(request.getRequestId(), "bad encounter");
        }
        if (!Float.isFinite(request.getGuestX()) || !Float.isFinite(request.getGuestY())) {
            return deny(request.getRequestId(), "bad coords");
        }
        if (Math.abs(request.getGuestX()) > 1_000_000f || Math.abs(request.getGuestY()) > 1_000_000f) {
            return deny(request.getRequestId(), "coords out of range");
        }
        final EnemyLookup lookup = enemyLookup;
        if (!lookup.enemyExistsNear(request.getEnemyId(), enc, request.getGuestX(), request.getGuestY())) {
            return deny(request.getRequestId(), "enemy not available");
        }
        return new CoopFightRequestResultEvent(request.getRequestId(),
                CoopFightRequestResultEvent.Decision.ACCEPT, "");
    }

    /** Guest ignores ACCEPT results that somehow claim to come from a non-host path. */
    public boolean guestMayApplyResult(final CoopFightRequestResultEvent result, final boolean fromHost) {
        if (result == null) {
            return false;
        }
        if (hostOnly && !fromHost) {
            return false;
        }
        return result.getRequestId() > 0L;
    }

    private static CoopFightRequestResultEvent deny(final long id, final String reason) {
        return new CoopFightRequestResultEvent(id, CoopFightRequestResultEvent.Decision.DENY,
                CoopDuelWireLimits.clampString(reason, CoopDuelWireLimits.MAX_TEXT_LEN));
    }
}
