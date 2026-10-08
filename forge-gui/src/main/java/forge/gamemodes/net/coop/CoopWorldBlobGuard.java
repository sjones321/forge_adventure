package forge.gamemodes.net.coop;

import forge.gamemodes.net.FilteredJavaObjectInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Serializable;
import java.util.Objects;

/**
 * Headless-friendly checks for {@code CoopWorldDataEvent} payloads: size cap,
 * filtered deserialize, and hash-before-use. Adventure applies the result to a
 * session {@code World}; tests use this directly.
 */
public final class CoopWorldBlobGuard {
    private CoopWorldBlobGuard() {
    }

    public static void checkSize(final byte[] bytes) throws IOException {
        if (bytes == null) {
            throw new IOException("Empty world blob");
        }
        if (bytes.length > CoopPorts.MAX_WORLD_BLOB_BYTES) {
            throw new IOException("World blob too large (" + bytes.length + " > "
                    + CoopPorts.MAX_WORLD_BLOB_BYTES + ")");
        }
    }

    /**
     * Deserialize under {@link FilteredJavaObjectInputStream}. Returns the root
     * object; caller must still verify domain hash before trusting contents.
     */
    public static Object deserializeFiltered(final byte[] bytes) throws IOException, ClassNotFoundException {
        checkSize(bytes);
        try (FilteredJavaObjectInputStream ois =
                     new FilteredJavaObjectInputStream(new ByteArrayInputStream(bytes))) {
            return ois.readObject();
        }
    }

    public static void requireHashMatch(final String expected, final String actual) throws IOException {
        if (!Objects.equals(expected, actual)) {
            throw new IOException("World blob hash mismatch (expected " + expected + ", got " + actual + ")");
        }
    }

    /** Minimal serializable payload for unit tests (not used on the live wire). */
    public static final class TestPayload implements Serializable {
        private static final long serialVersionUID = 1L;
        public final long seed;
        public final String label;

        public TestPayload(final long seed, final String label) {
            this.seed = seed;
            this.label = label;
        }
    }
}
