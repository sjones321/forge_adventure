package forge.gamemodes.net.coop;

import forge.util.BuildInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
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
     * Fingerprint every loaded card by name + edition + art index + oracle/script
     * text so a script change or missing set diverges. Sorted for stability.
     */
    private static String defaultCardDataHash() {
        try {
            final Class<?> staticData = Class.forName("forge.StaticData");
            final Object instance = staticData.getMethod("instance").invoke(null);
            if (instance == null) {
                return sha256Hex("card-data:unavailable");
            }
            final Object common = staticData.getMethod("getCommonCards").invoke(instance);
            final Collection<?> all = (Collection<?>) common.getClass().getMethod("getAllCards").invoke(common);
            final List<Object> cards = new ArrayList<>(all);
            cards.sort(Comparator.comparing((Object c) -> safeInvoke(c, "getName"))
                    .thenComparing(c -> safeInvoke(c, "getEdition"))
                    .thenComparingInt(c -> {
                        try {
                            return (Integer) c.getClass().getMethod("getArtIndex").invoke(c);
                        } catch (final ReflectiveOperationException e) {
                            return 0;
                        }
                    }));
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("cards-v2".getBytes(StandardCharsets.UTF_8));
            for (final Object card : cards) {
                final String name = safeInvoke(card, "getName");
                final String edition = safeInvoke(card, "getEdition");
                String script = "";
                try {
                    final Object rules = card.getClass().getMethod("getRules").invoke(card);
                    if (rules != null) {
                        final Object oracle = rules.getClass().getMethod("getOracleText").invoke(rules);
                        script = oracle != null ? oracle.toString() : "";
                    }
                } catch (final ReflectiveOperationException ignored) {
                }
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(edition.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(script.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            final byte[] dig = md.digest();
            final StringBuilder sb = new StringBuilder(dig.length * 2);
            for (final byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final ReflectiveOperationException | ClassCastException | NoSuchAlgorithmException e) {
            return sha256Hex("card-data:unavailable");
        }
    }

    private static String safeInvoke(final Object target, final String method) {
        try {
            final Object v = target.getClass().getMethod(method).invoke(target);
            return v != null ? v.toString() : "";
        } catch (final ReflectiveOperationException e) {
            return "";
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
            throw new IllegalStateException(e);
        }
    }
}
