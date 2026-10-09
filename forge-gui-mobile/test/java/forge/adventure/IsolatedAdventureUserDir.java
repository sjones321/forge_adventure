package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import forge.adventure.data.SettingData;
import forge.adventure.util.Config;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeProfileProperties;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Redirects Forge's user / adventure dirs to a fresh temp folder for the
 * duration of a test suite so headless tests never touch the developer's real
 * {@code USER_ADVENTURE_DIR} (settings.json, saves, characters).
 *
 * <p>MV2 (#30) is adding a shared suite listener for the same purpose; this
 * helper matches that intent. Until MV2 merges, TR1 installs it from
 * {@link AdventureIsolatedUserDirListener} / {@code CoopTradeEscrowE2ETest}.
 *
 * <p>Does <b>not</b> call {@link ForgeProfileProperties#setUserDir} — that
 * persists {@code forge.profile.properties} into the assets tree.
 */
public final class IsolatedAdventureUserDir {
    private static final Object LOCK = new Object();
    private static IsolatedAdventureUserDir active;
    /** Nested install depth so suite listener + {@code @BeforeClass} can both call install. */
    private static int installDepth;

    private final File realAdventureDir;
    private final Map<String, FileMeta> beforeSnapshot;
    private final File tempUserRoot;
    private final String previousProfileUserDir;
    private final String previousConstUserDir;
    private final String previousConstAdventureDir;

    private IsolatedAdventureUserDir(final File realAdventureDir,
                                     final Map<String, FileMeta> beforeSnapshot,
                                     final File tempUserRoot,
                                     final String previousProfileUserDir,
                                     final String previousConstUserDir,
                                     final String previousConstAdventureDir) {
        this.realAdventureDir = realAdventureDir;
        this.beforeSnapshot = beforeSnapshot;
        this.tempUserRoot = tempUserRoot;
        this.previousProfileUserDir = previousProfileUserDir;
        this.previousConstUserDir = previousConstUserDir;
        this.previousConstAdventureDir = previousConstAdventureDir;
    }

    /**
     * Idempotent nested install: returns the active redirect or creates one.
     * Pair each call with {@link #restoreAndAssertUntouched()}.
     */
    public static IsolatedAdventureUserDir install() throws Exception {
        synchronized (LOCK) {
            if (active != null) {
                installDepth++;
                return active;
            }
            // Touch ForgeConstants so USER_* are initialized from the real profile first.
            final String realUser = ForgeConstants.USER_DIR;
            final String realAdv = ForgeConstants.USER_ADVENTURE_DIR;
            final File realAdventure = new File(realAdv).getCanonicalFile();
            final Map<String, FileMeta> snap = snapshotTree(realAdventure);

            final File tempRoot = Files.createTempDirectory("tr1-forge-user").toFile();
            final File tempAdv = new File(tempRoot, "adventure");
            //noinspection ResultOfMethodCallIgnored
            tempAdv.mkdirs();
            writeAscendantSettings(tempAdv);

            final String tempUser = ensureSlash(tempRoot.getCanonicalPath());
            final String tempAdventure = ensureSlash(tempAdv.getCanonicalPath());

            final String prevProfile = ForgeProfileProperties.getUserDir();
            setProfileUserDirNoSave(tempUser);
            setStaticFinalString(ForgeConstants.class, "USER_DIR", tempUser);
            setStaticFinalString(ForgeConstants.class, "USER_ADVENTURE_DIR", tempAdventure);
            resetConfigSingleton();

            active = new IsolatedAdventureUserDir(realAdventure, snap, tempRoot,
                    prevProfile, realUser, realAdv);
            installDepth = 1;
            return active;
        }
    }

    public static boolean isInstalled() {
        synchronized (LOCK) {
            return active != null;
        }
    }

    /**
     * Restore previous dirs and assert the real adventure tree was not written
     * (existence / size / mtime unchanged for every previously-seen path, and
     * no new paths appeared). Nested installs only fully restore on the last
     * matching call.
     */
    public static void restoreAndAssertUntouched() throws Exception {
        synchronized (LOCK) {
            if (active == null) {
                return;
            }
            if (installDepth > 1) {
                installDepth--;
                return;
            }
            installDepth = 0;
            final IsolatedAdventureUserDir inst = active;
            active = null;
            try {
                setProfileUserDirNoSave(inst.previousProfileUserDir);
                setStaticFinalString(ForgeConstants.class, "USER_DIR", inst.previousConstUserDir);
                setStaticFinalString(ForgeConstants.class, "USER_ADVENTURE_DIR",
                        inst.previousConstAdventureDir);
                resetConfigSingleton();
                assertSnapshotUnchanged(inst.realAdventureDir, inst.beforeSnapshot);
            } finally {
                deleteTree(inst.tempUserRoot);
            }
        }
    }

    public File getTempUserRoot() {
        return tempUserRoot;
    }

    public File getTempAdventureDir() {
        return new File(tempUserRoot, "adventure");
    }

    private static void writeAscendantSettings(final File adventureDir) {
        final SettingData settings = new SettingData();
        settings.plane = "Shandalar Ascendant";
        settings.width = 1280;
        settings.height = 720;
        settings.videomode = "720p";
        final FileHandle out = new FileHandle(new File(adventureDir, "settings.json").getPath());
        out.writeString(new Json(JsonWriter.OutputType.json).prettyPrint(settings), false, "UTF-8");
    }

    private static void resetConfigSingleton() {
        try {
            final Field f = Config.class.getDeclaredField("currentConfig");
            f.setAccessible(true);
            f.set(null, null);
        } catch (final ReflectiveOperationException ignored) {
        }
    }

    /** Set profile userDir without calling save() (avoids writing assets profile). */
    private static void setProfileUserDirNoSave(final String dir) throws Exception {
        final Field f = ForgeProfileProperties.class.getDeclaredField("userDir");
        f.setAccessible(true);
        f.set(null, dir);
    }

    private static void setStaticFinalString(final Class<?> clazz, final String name,
                                             final String value) throws Exception {
        final Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        final Object unsafe = unsafeField.get(null);
        final Method staticFieldBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);
        final Method staticFieldOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
        final Method putObject = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
        final Object base = staticFieldBase.invoke(unsafe, field);
        final long offset = (Long) staticFieldOffset.invoke(unsafe, field);
        putObject.invoke(unsafe, base, offset, value);
    }

    private static String ensureSlash(final String path) {
        if (path.endsWith(File.separator) || path.endsWith("/")) {
            return path;
        }
        return path + File.separator;
    }

    static final class FileMeta {
        final boolean exists;
        final long size;
        final long mtime;

        FileMeta(final boolean exists, final long size, final long mtime) {
            this.exists = exists;
            this.size = size;
            this.mtime = mtime;
        }
    }

    static Map<String, FileMeta> snapshotTree(final File root) throws IOException {
        final Map<String, FileMeta> out = new HashMap<>();
        if (root == null) {
            return out;
        }
        if (!root.exists()) {
            out.put(".", new FileMeta(false, -1L, -1L));
            return out;
        }
        final Path rootPath = root.toPath().toAbsolutePath().normalize();
        out.put(".", new FileMeta(true, root.isFile() ? root.length() : -1L, root.lastModified()));
        if (root.isDirectory()) {
            Files.walk(rootPath).forEach(p -> {
                final Path rel = rootPath.relativize(p);
                final String key = rel.toString().replace('\\', '/');
                if (key.isEmpty()) {
                    return;
                }
                final File f = p.toFile();
                out.put(key, new FileMeta(true, f.isFile() ? f.length() : -1L, f.lastModified()));
            });
        }
        return out;
    }

    static void assertSnapshotUnchanged(final File root, final Map<String, FileMeta> before)
            throws IOException {
        final Map<String, FileMeta> after = snapshotTree(root);
        for (final Map.Entry<String, FileMeta> e : before.entrySet()) {
            final FileMeta b = e.getValue();
            final FileMeta a = after.get(e.getKey());
            if (a == null) {
                if (b.exists) {
                    throw new AssertionError("real user adventure path deleted: " + e.getKey());
                }
                continue;
            }
            if (a.exists != b.exists) {
                throw new AssertionError("real user adventure existence changed: " + e.getKey()
                        + " before=" + b.exists + " after=" + a.exists);
            }
            if (a.exists && (a.size != b.size || a.mtime != b.mtime)) {
                throw new AssertionError("real user adventure file mutated: " + e.getKey()
                        + " size " + b.size + "→" + a.size
                        + " mtime " + b.mtime + "→" + a.mtime);
            }
        }
        for (final String key : after.keySet()) {
            if (!before.containsKey(key) && after.get(key).exists) {
                throw new AssertionError("new path under real user adventure dir: " + key);
            }
        }
    }

    static void deleteTree(final File f) {
        if (f == null || !f.exists()) {
            return;
        }
        final File[] kids = f.listFiles();
        if (kids != null) {
            for (final File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
