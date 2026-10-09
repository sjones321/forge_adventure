package forge.adventure.util;

import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgeProfileProperties;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EN1 test isolation: redirect the Forge user / adventure dirs to a temp folder so
 * tests cannot overwrite a developer's real {@code USER_ADVENTURE_DIR/settings.json}
 * (or saves / prefs under the real user dir).
 * <p>
 * {@link ForgeConstants#USER_DIR} and friends are {@code static final} and initialized
 * once; this helper patches them via {@code sun.misc.Unsafe} for the duration of a test
 * class, and restores the originals on {@link #close()}.
 */
public final class En1TestUserDir implements AutoCloseable {

    private final Path realUserDir;
    private final Path realAdventureDir;
    private final Path tempUserDir;
    private final String fingerprintBefore;
    private final Map<String, String> originalConstants = new LinkedHashMap<>();
    private final String originalProfileUserDir;
    private boolean closed;

    private En1TestUserDir(Path realUserDir, Path realAdventureDir, Path tempUserDir,
                           String fingerprintBefore, String originalProfileUserDir) {
        this.realUserDir = realUserDir;
        this.realAdventureDir = realAdventureDir;
        this.tempUserDir = tempUserDir;
        this.fingerprintBefore = fingerprintBefore;
        this.originalProfileUserDir = originalProfileUserDir;
    }

    /**
     * Snapshot the real user adventure tree, point profile + {@link ForgeConstants}
     * user paths at a fresh temp dir, and reset {@link Config} so it re-reads settings
     * from the temp location.
     */
    public static En1TestUserDir install() throws Exception {
        // Force ForgeConstants class init before we read/patch.
        String bakedUser = ForgeConstants.USER_DIR;
        Path realUserDir = Paths.get(bakedUser).toAbsolutePath().normalize();
        Path realAdventureDir = Paths.get(ForgeConstants.USER_ADVENTURE_DIR).toAbsolutePath().normalize();
        String fingerprintBefore = fingerprintTree(realUserDir);

        Path tempUserDir = Files.createTempDirectory("en1-forge-user-");
        Path tempAdventure = tempUserDir.resolve("adventure");
        Path tempPrefs = tempUserDir.resolve("preferences");
        Files.createDirectories(tempAdventure);
        Files.createDirectories(tempPrefs);

        String originalProfileUserDir = ForgeProfileProperties.getUserDir();
        En1TestUserDir iso = new En1TestUserDir(realUserDir, realAdventureDir, tempUserDir,
                fingerprintBefore, originalProfileUserDir);

        String tempUserSlash = tempUserDir.toAbsolutePath().normalize() + File.separator;
        String sep = ForgeConstants.PATH_SEPARATOR;

        iso.patchConstant("USER_DIR", tempUserSlash);
        iso.patchConstant("USER_ADVENTURE_DIR", tempUserSlash + "adventure" + sep);
        iso.patchConstant("USER_PREFS_DIR", tempUserSlash + "preferences" + sep);
        iso.patchConstant("USER_QUEST_DIR", tempUserSlash + "quest" + sep);
        iso.patchConstant("USER_CONQUEST_DIR", tempUserSlash + "conquest" + sep);
        iso.patchConstant("USER_GAMES_DIR", tempUserSlash + "games" + sep);
        iso.patchConstant("USER_PUZZLE_DIR", tempUserSlash + "puzzle" + sep);
        iso.patchConstant("ACHIEVEMENTS_DIR", tempUserSlash + "achievements" + sep);
        iso.patchConstant("USER_CUSTOM_DIR", tempUserSlash + "custom" + sep);
        iso.patchConstant("LOG_FILE", tempUserSlash + "forge.log");
        iso.patchConstant("MAIN_PREFS_FILE", tempUserSlash + "preferences" + sep + "forge.preferences");

        ForgeProfileProperties.setUserDirWithoutSave(tempUserSlash);
        Config.resetInstanceForTests();
        return iso;
    }

    public Path tempUserDir() {
        return tempUserDir;
    }

    public Path tempAdventureDir() {
        return tempUserDir.resolve("adventure");
    }

    public Path realAdventureDir() {
        return realAdventureDir;
    }

    public Path realUserDir() {
        return realUserDir;
    }

    /** Fail if anything under the real Forge user dir changed since {@link #install()}. */
    public void assertRealUserDirUnchanged() throws Exception {
        String after = fingerprintTree(realUserDir);
        if (!fingerprintBefore.equals(after)) {
            throw new AssertionError(
                    "EN1 test mutated the real Forge user dir (" + realUserDir + ").\n"
                            + "before:\n" + fingerprintBefore + "\nafter:\n" + after);
        }
        // Extra guard on the adventure settings path Steve cares about.
        Path settings = realAdventureDir.resolve("settings.json");
        String beforeSettings = lineFor(fingerprintBefore, "adventure/settings.json");
        String afterSettings = Files.isRegularFile(settings)
                ? ("adventure/settings.json=" + sha256(settings) + " size=" + Files.size(settings))
                : "adventure/settings.json=<missing>";
        if (beforeSettings == null) {
            beforeSettings = "adventure/settings.json=<missing>";
        }
        if (!beforeSettings.equals(afterSettings)
                && !(beforeSettings.endsWith("=<missing>") && afterSettings.endsWith("=<missing>"))) {
            throw new AssertionError(
                    "Real USER_ADVENTURE_DIR/settings.json changed during EN1 tests.\n"
                            + beforeSettings + "\n" + afterSettings);
        }
    }

    @Override
    public void close() {
        if (closed)
            return;
        closed = true;
        try {
            for (Map.Entry<String, String> e : originalConstants.entrySet()) {
                setStaticFinalString(ForgeConstants.class, e.getKey(), e.getValue());
            }
            if (originalProfileUserDir != null) {
                ForgeProfileProperties.setUserDirWithoutSave(originalProfileUserDir);
            }
            Config.resetInstanceForTests();
        } catch (Exception e) {
            throw new RuntimeException("failed to restore Forge user dir after EN1 tests", e);
        }
    }

    private void patchConstant(String fieldName, String value) throws Exception {
        Field f = ForgeConstants.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        Object cur = f.get(null);
        originalConstants.put(fieldName, cur == null ? null : cur.toString());
        setStaticFinalString(ForgeConstants.class, fieldName, value);
    }

    private static void setStaticFinalString(Class<?> clazz, String fieldName, String value) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        Object unsafe = unsafe();
        Object base = unsafe.getClass().getMethod("staticFieldBase", Field.class).invoke(unsafe, f);
        long offset = (Long) unsafe.getClass().getMethod("staticFieldOffset", Field.class).invoke(unsafe, f);
        unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class)
                .invoke(unsafe, base, offset, value);
    }

    private static Object unsafe() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return theUnsafe.get(null);
    }

    /**
     * Stable fingerprint of every regular file under {@code root} (relative path, size, sha256).
     * Missing root → sentinel string.
     */
    static String fingerprintTree(Path root) throws Exception {
        if (root == null || !Files.isDirectory(root)) {
            return "<missing:" + root + ">";
        }
        List<String> lines = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .forEach(p -> {
                        try {
                            String rel = root.relativize(p).toString().replace('\\', '/');
                            lines.add(rel + "=" + sha256(p) + " size=" + Files.size(p));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        if (lines.isEmpty()) {
            return "<empty:" + root.toAbsolutePath().normalize() + ">";
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    private static String lineFor(String fingerprint, String relativePath) {
        if (fingerprint == null)
            return null;
        String prefix = relativePath + "=";
        for (String line : fingerprint.split("\n")) {
            if (line.startsWith(prefix) || line.equals(relativePath + "=<missing>"))
                return line;
        }
        return null;
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(md.digest());
    }

    /** True when {@code path} is inside the repo {@code forge-gui/res} tree (generator target). */
    public static boolean isRepoAdventureRes(Path path) {
        if (path == null)
            return false;
        Path norm = path.toAbsolutePath().normalize();
        String s = norm.toString().replace('\\', '/');
        return s.contains("/forge-gui/res/adventure/") || s.endsWith("/forge-gui/res/adventure")
                || s.contains("/res/adventure/common/decks/enemy");
    }
}
