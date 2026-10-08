package forge.gamemodes.net.coop;

import forge.util.BuildInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Hard version check for Ascendant co-op. Unlike classic online login (which
 * only warns), co-op refuses to connect unless both the build identity and the
 * loaded card-data hash match.
 */
public final class CoopVersion {
    /** Optional override for tests / headless runs that do not load StaticData. */
    private static volatile Supplier<String> cardDataHashSupplier = CoopVersion::defaultCardDataHash;

    private CoopVersion() {
    }

    public static void setCardDataHashSupplier(final Supplier<String> supplier) {
        cardDataHashSupplier = supplier != null ? supplier : CoopVersion::defaultCardDataHash;
    }

    /** Build identity exchanged on hello. Includes version string and build timestamp when present. */
    public static String buildHash() {
        final StringBuilder sb = new StringBuilder();
        sb.append(BuildInfo.getVersionString());
        final Date ts = BuildInfo.getTimestamp();
        if (ts != null) {
            sb.append('|').append(ts.getTime());
        }
        return sha256Hex(sb.toString());
    }

    /** Hash of the loaded card database (or a test override). */
    public static String cardDataHash() {
        return cardDataHashSupplier.get();
    }

    public static boolean buildMatches(final String local, final String remote) {
        return Objects.equals(local, remote);
    }

    public static boolean cardDataMatches(final String local, final String remote) {
        return Objects.equals(local, remote);
    }

    /**
     * Combined check used by the host when answering {@code CoopHelloEvent}.
     * @return null if OK, otherwise a short reject reason
     */
    public static String mismatchReason(final String remoteBuildHash, final String remoteCardHash) {
        if (!buildMatches(buildHash(), remoteBuildHash)) {
            return "Build hash mismatch — both players must use the same Forge build.";
        }
        if (!cardDataMatches(cardDataHash(), remoteCardHash)) {
            return "Card data hash mismatch — both players must have the same card database.";
        }
        return null;
    }

    /**
     * Default card-data fingerprint. Prefers a live StaticData digest when the
     * card DB is loaded; otherwise returns a stable placeholder so headless
     * protocol tests can still exchange matching hashes via
     * {@link #setCardDataHashSupplier}.
     */
    private static String defaultCardDataHash() {
        try {
            final Class<?> staticData = Class.forName("forge.StaticData");
            final Object instance = staticData.getMethod("instance").invoke(null);
            if (instance == null) {
                return sha256Hex("card-data:unavailable");
            }
            final Object common = staticData.getMethod("getCommonCards").invoke(instance);
            final Object all = common.getClass().getMethod("getAllCards").invoke(common);
            final int size = ((java.util.Collection<?>) all).size();
            // Sample first/last names for a cheap but discriminating fingerprint.
            String first = "";
            String last = "";
            int i = 0;
            for (final Object card : (java.util.Collection<?>) all) {
                final String name = String.valueOf(card.getClass().getMethod("getName").invoke(card));
                if (i == 0) {
                    first = name;
                }
                last = name;
                i++;
            }
            return sha256Hex("cards|" + size + '|' + first + '|' + last);
        } catch (final ReflectiveOperationException | ClassCastException e) {
            return sha256Hex("card-data:unavailable");
        }
    }

    public static String sha256Hex(final String input) {
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(final byte[] input) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] dig = md.digest(input);
            final StringBuilder sb = new StringBuilder(dig.length * 2);
            for (final byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            // SHA-256 is required on every JDK Forge supports.
            throw new IllegalStateException(e);
        }
    }
}
