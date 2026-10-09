package forge.adventure;

import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeProfileProperties;
import org.testng.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only helper (shared with CO1): point Forge's user / adventure dirs at a
 * fresh temp folder via {@link ForgeProfileProperties#TEST_USER_DIR_PROPERTY}
 * <em>before</em> {@link ForgeConstants} class-init, and assert the real OS
 * user dir was not written.
 *
 * <p>MV2 registers {@link AdventureTestUserDirIsolationListener} for the whole
 * forge-gui-mobile surefire run; CO1's {@code CoopGuestCharacterPersistTest}
 * can also call {@link #installTempUserDir()} directly when the suite listener
 * is not present.
 */
public final class AdventureTestUserDir {
    private AdventureTestUserDir() {
    }

    /** Linux/macOS/Windows default userDir used for the "real dir" snapshot (pre-ForgeConstants). */
    public static Path defaultRealUserDir() {
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

    /**
     * Create a temp user dir and set {@code forge.test.userDir} so the next
     * {@link ForgeConstants} / {@link ForgeProfileProperties#load} uses it.
     * Call before GuiBase touches {@code ForgeConstants}.
     */
    public static Path installTempUserDir() throws IOException {
        final Path temp = Files.createTempDirectory("forge-test-user-");
        System.setProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY, temp.toAbsolutePath().toString());
        return temp;
    }

    /**
     * Ensure {@code forge.test.userDir} is set (surefire may already have set it).
     * Returns the absolute path that will become {@link ForgeConstants#USER_DIR}.
     */
    public static Path ensureTestUserDirProperty() throws IOException {
        final String existing = System.getProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        if (existing != null && !existing.isBlank()) {
            final Path p = Paths.get(existing.trim()).toAbsolutePath();
            Files.createDirectories(p);
            System.setProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY, p.toString());
            return p;
        }
        return installTempUserDir();
    }

    public static void clearTempUserDirProperty() {
        System.clearProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        System.clearProperty(ForgeProfileProperties.TEST_CACHE_DIR_PROPERTY);
    }

    /**
     * Snapshot every file under {@code root}: relative path → size + mtime + sha-256.
     * Directories use size=-1 and empty hash.
     */
    public static Map<String, FileStamp> snapshot(final Path root) throws IOException {
        final Map<String, FileStamp> out = new LinkedHashMap<>();
        if (root == null || !Files.exists(root)) {
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
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

    public static void assertUnchanged(final Path root, final Map<String, FileStamp> before,
            final String context) throws IOException {
        final Map<String, FileStamp> after = snapshot(root);
        Assert.assertEquals(after, before,
                context + ": real Forge user dir was modified.\nBefore=" + before + "\nAfter=" + after);
    }

    /** Confirm production constants land inside the installed temp user dir. */
    public static void assertConstantsUse(final Path tempUserDir) {
        final String temp = tempUserDir.toAbsolutePath().normalize().toString();
        final String user = Paths.get(ForgeConstants.USER_DIR).toAbsolutePath().normalize().toString();
        final String adventure = Paths.get(ForgeConstants.USER_ADVENTURE_DIR).toAbsolutePath().normalize().toString();
        Assert.assertTrue(user.startsWith(temp),
                "USER_DIR must be under temp user dir: " + user + " vs " + temp);
        Assert.assertTrue(adventure.startsWith(temp),
                "USER_ADVENTURE_DIR must be under temp user dir: " + adventure + " vs " + temp);
        Assert.assertEquals(ForgeProfileProperties.getUserDir(), ForgeConstants.USER_DIR);
    }

    public static void deleteRecursive(final Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static String sha256(final Path file) throws IOException {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                final byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    public static final class FileStamp {
        final long size;
        final long mtimeMillis;
        final String sha256;

        FileStamp(final long size, final long mtimeMillis) {
            this(size, mtimeMillis, "");
        }

        FileStamp(final long size, final long mtimeMillis, final String sha256) {
            this.size = size;
            this.mtimeMillis = mtimeMillis;
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
            return size == that.size
                    && mtimeMillis == that.mtimeMillis
                    && Objects.equals(sha256, that.sha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, mtimeMillis, sha256);
        }

        @Override
        public String toString() {
            return "size=" + size + ",mtime=" + mtimeMillis + ",sha256=" + sha256;
        }
    }
}
