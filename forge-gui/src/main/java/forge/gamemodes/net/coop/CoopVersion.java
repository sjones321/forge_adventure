package forge.gamemodes.net.coop;

import forge.util.BuildInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
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

    /**
     * Build identity exchanged on hello: version string plus co-op protocol version. Deliberately not the build
     * timestamp, so two players who each build the same commit can play together; the card-data hash covers
     * card differences.
     */
    public static String buildHash() {
        final StringBuilder sb = new StringBuilder();
        sb.append(BuildInfo.getVersionString());
        sb.append("|coop-protocol-").append(CoopPorts.PROTOCOL_VERSION);
        return sha256Hex(sb.toString());
    }

    /**
     * Hash of the loaded card database (or a test override).
     * @throws IllegalStateException if StaticData / card scripts are unavailable
     */
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
        final String localCard;
        try {
            localCard = cardDataHash();
        } catch (final RuntimeException e) {
            return "Card data unavailable — cannot verify co-op version.";
        }
        if (!cardDataMatches(localCard, remoteCardHash)) {
            return "Card data hash mismatch — both players must have the same card database.";
        }
        return null;
    }

    /**
     * Fingerprint every loaded card by name, edition, art index, oracle text,
     * and ability/script lines (keywords, abilities, statics, triggers,
     * replacements). Sorted for stability. Fails hard if StaticData is missing.
     */
    private static String defaultCardDataHash() {
        try {
            final Class<?> staticData = Class.forName("forge.StaticData");
            final Object instance = staticData.getMethod("instance").invoke(null);
            if (instance == null) {
                throw new IllegalStateException("StaticData.instance() is null");
            }
            final Object common = staticData.getMethod("getCommonCards").invoke(instance);
            if (common == null) {
                throw new IllegalStateException("StaticData common cards unavailable");
            }
            final Collection<?> all = (Collection<?>) common.getClass().getMethod("getAllCards").invoke(common);
            if (all == null || all.isEmpty()) {
                throw new IllegalStateException("Card database is empty");
            }
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
            md.update("cards-v3".getBytes(StandardCharsets.UTF_8));
            for (final Object card : cards) {
                final String name = safeInvoke(card, "getName");
                final String edition = safeInvoke(card, "getEdition");
                int artIndex = 0;
                try {
                    artIndex = (Integer) card.getClass().getMethod("getArtIndex").invoke(card);
                } catch (final ReflectiveOperationException ignored) {
                }
                String oracle = "";
                final StringBuilder script = new StringBuilder();
                try {
                    final Object rules = card.getClass().getMethod("getRules").invoke(card);
                    if (rules != null) {
                        final Object oracleObj = rules.getClass().getMethod("getOracleText").invoke(rules);
                        oracle = oracleObj != null ? oracleObj.toString() : "";
                        appendFaceScript(script, rules, "getMainPart");
                        appendFaceScript(script, rules, "getOtherPart");
                    }
                } catch (final ReflectiveOperationException ignored) {
                }
                md.update(name.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(edition.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(Integer.toString(artIndex).getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(oracle.getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
                md.update(script.toString().getBytes(StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            final byte[] dig = md.digest();
            final StringBuilder sb = new StringBuilder(dig.length * 2);
            for (final byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final ReflectiveOperationException | ClassCastException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Card data hash unavailable: " + e.getMessage(), e);
        }
    }

    private static void appendFaceScript(final StringBuilder script, final Object rules, final String getter) {
        try {
            final Object face = rules.getClass().getMethod(getter).invoke(rules);
            if (face == null) {
                return;
            }
            appendIterable(script, face, "getKeywords");
            appendIterable(script, face, "getAbilities");
            appendIterable(script, face, "getStaticAbilities");
            appendIterable(script, face, "getTriggers");
            appendIterable(script, face, "getReplacements");
            final Object nonAbility = face.getClass().getMethod("getNonAbilityText").invoke(face);
            if (nonAbility != null) {
                script.append(nonAbility).append('\n');
            }
        } catch (final ReflectiveOperationException ignored) {
        }
    }

    private static void appendIterable(final StringBuilder script, final Object face, final String method) {
        try {
            final Object it = face.getClass().getMethod(method).invoke(face);
            if (it instanceof Iterable) {
                for (final Object line : (Iterable<?>) it) {
                    if (line != null) {
                        script.append(line).append('\n');
                    }
                }
            }
        } catch (final ReflectiveOperationException ignored) {
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
