package forge.adventure;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Snapshot must skip locked/unreadable files (e.g. {@code forge.log} while Forge
 * is open) instead of aborting the suite via {@link AdventureTestBootstrapListener}.
 */
public class AdventureTestUserDirTest {

    @Test
    public void snapshotSkipsUnreadableFilesWithoutAborting() throws Exception {
        final Path root = Files.createTempDirectory("adv-userdir-snap");
        try {
            final Path ok = root.resolve("ok.txt");
            Files.writeString(ok, "readable");
            final Path locked = root.resolve("forge.log");
            Files.writeString(locked, "locked-by-forge");
            Assert.assertTrue(locked.toFile().setReadable(false),
                    "test setup must clear read permission on forge.log");

            final Map<String, AdventureTestUserDir.FileStamp> snap = AdventureTestUserDir.snapshot(root);

            Assert.assertTrue(snap.containsKey("ok.txt"), "readable files must be stamped");
            Assert.assertFalse(snap.containsKey("forge.log"),
                    "unreadable forge.log must be skipped, not abort the snapshot");
        } finally {
            final Path locked = root.resolve("forge.log");
            if (Files.exists(locked)) {
                locked.toFile().setReadable(true);
            }
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(final Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (final Exception ignored) {
                    }
                });
    }
}
