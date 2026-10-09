package forge.adventure;

import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeProfileProperties;
import org.testng.Assert;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Test-only helper for Forge user-dir isolation.
 *
 * <p>{@code forge.test.userDir} must be set by Surefire
 * ({@code ${project.build.directory}/test-user-home}) before any test class
 * loads {@link ForgeConstants}. This class snapshots the real OS user dir and
 * fail-fast checks that {@link ForgeConstants#USER_DIR} is under the test dir.
 * MV2 should reuse the same Surefire property rather than a second mechanism.
 */
public final class AdventureTestUserDir {
    private AdventureTestUserDir() {
    }

    /** Linux/macOS/Windows default userDir for the "real dir" snapshot. */
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
     * Surefire-configured test user dir ({@code forge.test.userDir}).
     * Fails fast if the property is missing (order-dependent @BeforeClass install is not enough).
     */
    public static Path configuredTestUserDir() {
        final String prop = System.getProperty(ForgeProfileProperties.TEST_USER_DIR_PROPERTY);
        if (prop == null || prop.isBlank()) {
            throw new IllegalStateException(
                    ForgeProfileProperties.TEST_USER_DIR_PROPERTY
                            + " must be set by Surefire systemPropertyVariables before ForgeConstants loads"
                            + " (expected ${project.build.directory}/test-user-home)");
        }
        return Paths.get(prop).toAbsolutePath().normalize();
    }

    /**
     * Fail fast: property set and {@link ForgeConstants#USER_DIR} / adventure dir
     * resolve under the Surefire test user home. Call after GuiBase is installed
     * (so ForgeConstants can initialize).
     */
    public static void requireIsolatedUserDir() {
        final Path expected = configuredTestUserDir();
        assertConstantsUse(expected);
    }

    /** Snapshot every file under {@code root}: relative path → size + mtime. */
    public static Map<String, FileStamp> snapshot(final Path root) throws IOException {
        final Map<String, FileStamp> out = new LinkedHashMap<>();
        if (root == null || !Files.exists(root)) {
            return out;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                out.put(root.relativize(file).toString().replace('\\', '/'),
                        new FileStamp(attrs.size(), attrs.lastModifiedTime().toMillis()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                if (!dir.equals(root)) {
                    out.put(root.relativize(dir).toString().replace('\\', '/') + "/",
                            new FileStamp(-1L, attrs.lastModifiedTime().toMillis()));
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

    /** Confirm production constants land inside the Surefire test user dir. */
    public static void assertConstantsUse(final Path testUserDir) {
        final String expected = testUserDir.toAbsolutePath().normalize().toString();
        final String user = Paths.get(ForgeConstants.USER_DIR).toAbsolutePath().normalize().toString();
        final String adventure = Paths.get(ForgeConstants.USER_ADVENTURE_DIR).toAbsolutePath().normalize().toString();
        Assert.assertTrue(user.startsWith(expected),
                "USER_DIR must be under forge.test.userDir: " + user + " vs " + expected
                        + " (ForgeConstants likely loaded before Surefire set the property)");
        Assert.assertTrue(adventure.startsWith(expected),
                "USER_ADVENTURE_DIR must be under forge.test.userDir: " + adventure + " vs " + expected);
        Assert.assertEquals(ForgeProfileProperties.getUserDir(), ForgeConstants.USER_DIR);
    }

    public static final class FileStamp {
        final long size;
        final long mtimeMillis;

        FileStamp(final long size, final long mtimeMillis) {
            this.size = size;
            this.mtimeMillis = mtimeMillis;
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
            return size == that.size && mtimeMillis == that.mtimeMillis;
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, mtimeMillis);
        }

        @Override
        public String toString() {
            return "size=" + size + ",mtime=" + mtimeMillis;
        }
    }
}
