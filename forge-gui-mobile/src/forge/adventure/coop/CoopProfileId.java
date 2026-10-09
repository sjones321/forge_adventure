package forge.adventure.coop;

import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * CO5: stable per-install co-op profile id stored in the user folder (not per character).
 * One partner character per profile per host world.
 */
public final class CoopProfileId {
    /** Relative filename under {@link ForgeConstants#USER_DIR}. */
    public static final String FILE_NAME = "coop-profile.id";

    /** Max length accepted on the wire / as a map key. */
    public static final int MAX_LENGTH = 64;

    private static volatile String cached;
    /** Test-only override; null uses the real user-dir file. */
    private static volatile File fileOverride;

    private CoopProfileId() {
    }

    /** Package-visible test hook. */
    static void setFileOverrideForTests(final File file) {
        fileOverride = file;
        cached = null;
    }

    /** Clear the in-memory cache (tests). */
    static void clearCacheForTests() {
        cached = null;
    }

    public static File profileFile() {
        final File override = fileOverride;
        if (override != null) {
            return override;
        }
        return new File(ForgeConstants.USER_DIR, FILE_NAME);
    }

    /**
     * Returns the install profile id, creating and persisting one on first call.
     * Always UTF-8 without BOM.
     */
    public static String getOrCreate() {
        final String hit = cached;
        if (hit != null && !hit.isEmpty()) {
            return hit;
        }
        synchronized (CoopProfileId.class) {
            if (cached != null && !cached.isEmpty()) {
                return cached;
            }
            final File file = profileFile();
            try {
                if (file.isFile()) {
                    final String existing = Files.readString(file.toPath(), StandardCharsets.UTF_8).trim();
                    final String sanitized = sanitize(existing);
                    if (!sanitized.isEmpty()) {
                        cached = sanitized;
                        return cached;
                    }
                }
                final String created = UUID.randomUUID().toString();
                writeAtomic(file, created);
                cached = created;
                return cached;
            } catch (final IOException e) {
                // Fall back to an ephemeral id so join can still proceed; next launch retries.
                final String ephemeral = UUID.randomUUID().toString();
                cached = ephemeral;
                return cached;
            }
        }
    }

    /** Validate a peer-supplied profile id (length + charset). Empty → invalid. */
    public static boolean isValid(final String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            final char c = id.charAt(i);
            if (!(c >= '0' && c <= '9'
                    || c >= 'a' && c <= 'z'
                    || c >= 'A' && c <= 'Z'
                    || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    public static String sanitize(final String id) {
        if (id == null) {
            return "";
        }
        final String trimmed = id.trim();
        return isValid(trimmed) ? trimmed : "";
    }

    private static void writeAtomic(final File file, final String id) throws IOException {
        final File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        final File tmp = new File(parent != null ? parent : new File("."),
                file.getName() + ".tmp");
        Files.writeString(tmp.toPath(), id, StandardCharsets.UTF_8);
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
