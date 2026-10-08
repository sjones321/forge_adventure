package forge.gamemodes.net.coop;

import forge.util.LogSafe;

/**
 * Shared username / session-code normalisation for the co-op duel channel so the
 * host gate and the guest LoginEvent agree.
 */
public final class CoopDuelIdentity {
    private CoopDuelIdentity() {
    }

    public static String normalizeUsername(final String raw) {
        final String clamped = CoopDuelWireLimits.clampString(raw, CoopDuelWireLimits.MAX_NAME_LEN);
        final String cleaned = LogSafe.forDisplay(clamped, CoopDuelWireLimits.MAX_NAME_LEN);
        return cleaned != null ? cleaned.trim() : "";
    }

    public static String normalizeSessionCode(final String raw) {
        if (raw == null) {
            return "";
        }
        return CoopSessionCode.normalize(raw);
    }

    public static boolean usernamesMatch(final String a, final String b) {
        final String na = normalizeUsername(a);
        final String nb = normalizeUsername(b);
        return !na.isEmpty() && na.equalsIgnoreCase(nb);
    }
}
