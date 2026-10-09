package forge.adventure.coop;

import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Guest (and host) character files — the player's {@link AdventurePlayer} kept
 * separately from the host's world save. CO1: the guest's co-op {@code .chr} is
 * the source of truth across sessions; the solo WorldSave is never written by
 * co-op join/leave.
 */
public final class CoopCharacterStore {
    /** Test-only override for {@link #charactersDir()}; null uses the real path. */
    private static volatile File charactersDirOverride;

    private CoopCharacterStore() {
    }

    /** Package-visible test hook: redirect character files to a temp directory. */
    static void setCharactersDirOverrideForTests(final File dir) {
        charactersDirOverride = dir;
    }

    public static File charactersDir() {
        final File override = charactersDirOverride;
        if (override != null) {
            //noinspection ResultOfMethodCallIgnored
            override.mkdirs();
            return override;
        }
        final File dir = new File(ForgeConstants.USER_ADVENTURE_DIR
                + Config.instance().getPlane() + File.separator + "characters");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    public static File characterFile(final String characterName) {
        final String safe = sanitize(characterName);
        return new File(charactersDir(), safe + ".chr");
    }

    public static boolean exists(final String characterName) {
        return characterFile(characterName).isFile();
    }

    /**
     * Persist {@code player} to its co-op {@code .chr} via temp file + atomic move
     * so a crash mid-write cannot leave a truncated character file.
     */
    public static void savePlayer(final AdventurePlayer player) throws IOException {
        if (player == null) {
            return;
        }
        final File file = characterFile(player.getName());
        final File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        final SaveFileData data = player.save();
        final File tmp = new File(parent != null ? parent : new File("."),
                file.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
        }
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

    public static boolean loadPlayer(final AdventurePlayer target, final String characterName)
            throws IOException, ClassNotFoundException {
        final File file = characterFile(characterName);
        if (!file.isFile()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            final SaveFileData data = (SaveFileData) ois.readObject();
            target.load(data);
            return true;
        }
    }

    /**
     * Guest join character model: on the first join (no {@code .chr} yet), seed
     * from the current solo player once. On later joins, load the existing
     * co-op {@code .chr} instead of re-exporting the solo player.
     *
     * @return {@code true} if this call seeded a new {@code .chr} from solo
     */
    public static boolean loadOrSeedForJoin(final AdventurePlayer target)
            throws IOException, ClassNotFoundException {
        if (target == null) {
            return false;
        }
        final String name = target.getName();
        if (!exists(name)) {
            savePlayer(target);
            return true;
        }
        loadPlayer(target, name);
        return false;
    }

    /**
     * Snapshot the current save's player into a character file (host export /
     * first-time seed). Prefer {@link #loadOrSeedForJoin} on the guest join path.
     */
    public static void exportCurrentPlayer() throws IOException {
        savePlayer(WorldSave.getCurrentSave().getPlayer());
    }

    /** Package-visible: read the raw {@code .chr} payload (tests / diagnostics). */
    static SaveFileData readRaw(final String characterName)
            throws IOException, ClassNotFoundException {
        final File file = characterFile(characterName);
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            return (SaveFileData) ois.readObject();
        }
    }

    /**
     * Package-visible test helper: write a raw {@code .chr} payload using the same
     * atomic temp+move path as {@link #savePlayer}.
     */
    static void writeRawForTests(final String characterName, final SaveFileData data)
            throws IOException {
        if (data == null) {
            return;
        }
        final File file = characterFile(characterName);
        final File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        final File tmp = new File(parent != null ? parent : new File("."),
                file.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
        }
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

    private static String sanitize(final String name) {
        if (name == null || name.isEmpty()) {
            return "player";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
