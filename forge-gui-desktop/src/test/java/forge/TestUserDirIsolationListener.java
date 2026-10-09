package forge;

import forge.localinstance.properties.ForgeProfileProperties;
import org.testng.Assert;
import org.testng.ISuite;
import org.testng.ISuiteListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Suite-wide guard for forge-gui-desktop: Surefire must set
 * {@code forge.test.userDir} → {@code target/test-user-home} before any test
 * loads ForgeConstants. Snapshots the real OS Forge user dir on start and fails
 * the suite if anything appears or changes there (including {@code forge.log}).
 *
 * <p>Does not touch {@code ForgeConstants} here — that class needs GuiBase for
 * {@code ASSETS_DIR}. Isolation is enforced by the Surefire property plus the
 * real-dir snapshot.
 */
public final class TestUserDirIsolationListener implements ISuiteListener {
    private Path realUserDir;
    private Path testUserDir;
    private Map<String, FileStamp> realSnapshot;

    @Override
    public void onStart(final ISuite suite) {
        final String prop = System.getProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        if (prop == null || prop.isBlank()) {
            throw new IllegalStateException(
                    ForgeProfileProperties.TEST_USER_DIR_PROPERTY
                            + " must be set by Surefire to ${project.build.directory}/test-user-home");
        }
        testUserDir = Paths.get(prop).toAbsolutePath().normalize();
        realUserDir = defaultRealUserDir();
        // Real dir must not be the test home (misconfigured Surefire).
        Assert.assertFalse(realUserDir.normalize().equals(testUserDir),
                "test-user-home must not equal the real Forge user dir: " + testUserDir);
        try {
            realSnapshot = snapshot(realUserDir);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not snapshot real user dir: " + e.getMessage(), e);
        }
    }

    @Override
    public void onFinish(final ISuite suite) {
        if (realUserDir == null || realSnapshot == null) {
            return;
        }
        try {
            final Map<String, FileStamp> after = snapshot(realUserDir);
            Assert.assertEquals(after, realSnapshot,
                    "Real Forge user dir was modified by tests (forge.log / settings must stay under "
                            + testUserDir + ").\nBefore=" + realSnapshot + "\nAfter=" + after);
            // Extra: no forge.log under the real home if the dir was created mid-suite.
            final Path realLog = realUserDir.resolve("forge.log");
            Assert.assertFalse(Files.exists(realLog) && !realSnapshot.containsKey("forge.log"),
                    "forge.log written outside test-user-home: " + realLog);
        } catch (final IOException e) {
            throw new IllegalStateException("Could not re-snapshot real user dir: " + e.getMessage(), e);
        }
    }

    private static Path defaultRealUserDir() {
        final String home = System.getProperty("user.home");
        final String os = System.getProperty("os.name", "");
        if (os.toLowerCase().contains("mac os x")) {
            return Paths.get(home, "Library", "Application Support", "Forge");
        }
        if (os.toLowerCase().contains("windows")) {
            final String appdata = System.getenv("APPDATA");
            if (appdata != null && !appdata.isEmpty()) {
                return Paths.get(appdata, "Forge");
            }
        }
        return Paths.get(home, ".forge");
    }

    private static Map<String, FileStamp> snapshot(final Path root) throws IOException {
        final Map<String, FileStamp> out = new LinkedHashMap<>();
        if (root == null || !Files.exists(root)) {
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                out.put(root.relativize(file).toString().replace('\\', '/'),
                        new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis(), sha256(file)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    out.put(root.relativize(dir).toString().replace('\\', '/') + "/",
                            new FileStamp(-1L, attrs.lastModifiedTime().toMillis(), ""));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return out;
    }

    private static String sha256(final Path file) throws IOException {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (final Exception e) {
            throw new IOException("sha-256 failed for " + file + ": " + e.getMessage(), e);
        }
    }

    private static final class FileStamp {
        private final long size;
        private final long mtimeMs;
        private final String sha256;

        private FileStamp(final long size, final long mtimeMs, final String sha256) {
            this.size = size;
            this.mtimeMs = mtimeMs;
            this.sha256 = sha256 != null ? sha256 : "";
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof FileStamp)) {
                return false;
            }
            final FileStamp that = (FileStamp) o;
            return size == that.size && mtimeMs == that.mtimeMs && Objects.equals(sha256, that.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, mtimeMs, sha256);
        }

        @Override
        public String toString() {
            return "FileStamp{size=" + size + ", mtime=" + mtimeMs + ", sha=" + sha256 + '}';
        }
    }
}
