package forge.gamemodes.net.coop;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Short shared secret the host displays and the guest must enter on Join.
 * Not a substitute for TLS — it stops casual LAN joiners from attaching without
 * the code shown on the host screen.
 */
public final class CoopSessionCode {
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private CoopSessionCode() {
    }

    public static String generate() {
        return generate(CoopPorts.SESSION_CODE_LENGTH);
    }

    public static String generate(final int length) {
        final char[] out = new char[length];
        for (int i = 0; i < out.length; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return new String(out);
    }

    public static String normalize(final String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    public static boolean matches(final String expected, final String provided) {
        final String a = normalize(expected);
        final String b = normalize(provided);
        if (a.isEmpty() || b.isEmpty() || a.length() != b.length()) {
            return false;
        }
        // Constant-time compare for the short code.
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
