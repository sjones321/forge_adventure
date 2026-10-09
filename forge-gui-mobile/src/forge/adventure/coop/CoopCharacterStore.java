package forge.adventure.coop;

import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
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
 * Guest co-op character files — the player's {@link AdventurePlayer} kept
 * separately from the host's world save and from the solo WorldSave.
 *
 * <p>Files live under {@code characters/guest/<characterId>.chr} so a host
 * (or another solo save with the same display name) cannot overwrite them.
 * Legacy {@code characters/<name>.chr} files (including old same-name host
 * exports) are migrated on first join.
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

    /** Guest co-op namespace — never shared with host exports or name-keyed legacy files. */
    public static File guestCharactersDir() {
        final File dir = new File(charactersDir(), "guest");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** Guest co-op file for a stable {@link AdventurePlayer#getCharacterId()}. */
    public static File guestCharacterFile(final String characterId) {
        return new File(guestCharactersDir(), sanitize(characterId) + ".chr");
    }

    /**
     * Legacy name-keyed path ({@code characters/<name>.chr}). Kept for migration
     * only — new writes go to {@link #guestCharacterFile}.
     */
    public static File legacyCharacterFile(final String characterName) {
        return new File(charactersDir(), sanitize(characterName) + ".chr");
    }

    /** @deprecated use {@link #guestCharacterFile(String)} */
    public static File characterFile(final String characterNameOrId) {
        return guestCharacterFile(characterNameOrId);
    }

    public static boolean exists(final AdventurePlayer player) {
        if (player == null) {
            return false;
        }
        migrateLegacyIfNeeded(player);
        return guestCharacterFile(player.getCharacterId()).isFile();
    }

    public static boolean exists(final String characterId) {
        return guestCharacterFile(characterId).isFile();
    }

    /**
     * Persist {@code player} to its guest co-op {@code .chr} via temp file + atomic
     * move so a crash mid-write cannot leave a truncated character file.
     */
    public static void savePlayer(final AdventurePlayer player) throws IOException {
        if (player == null) {
            return;
        }
        migrateLegacyIfNeeded(player);
        final File file = guestCharacterFile(player.getCharacterId());
        writeAtomic(file, player.save());
    }

    /**
     * Load guest {@code .chr} for {@code characterId} into {@code target}.
     * After load, forces {@code target}'s character id to the file key so a
     * pre-PR payload without {@code characterId} cannot mint a new id and orphan
     * this file. When the payload lacked an id, rewrites the file with the bound id.
     */
    public static boolean loadPlayer(final AdventurePlayer target, final String characterId)
            throws IOException, ClassNotFoundException {
        if (target == null) {
            return false;
        }
        if (characterId == null || characterId.isEmpty()) {
            return false;
        }
        migrateLegacyIfNeeded(target.getName(), characterId);
        final File file = guestCharacterFile(characterId);
        if (!file.isFile()) {
            return false;
        }
        final SaveFileData data;
        try (FileInputStream fis = new FileInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            data = (SaveFileData) ois.readObject();
        }
        final boolean lackedId = !data.containsKey("characterId")
                || data.readString("characterId") == null
                || data.readString("characterId").isEmpty();
        target.load(data);
        // File key wins — never keep a reminted id from an old payload.
        target.bindCharacterId(characterId);
        if (lackedId) {
            try {
                writeAtomic(file, target.save());
            } catch (final IOException e) {
                // Bound in memory; leave will persist. Do not fail the join.
                System.err.println("Could not rewrite characterId into guest .chr: " + e.getMessage());
            }
        }
        return true;
    }

    /**
     * Guest join character model: on the first join (no guest {@code .chr} yet),
     * seed from the current solo player once. On later joins, load the existing
     * co-op {@code .chr} instead of re-exporting the solo player.
     *
     * <p>Corrupt files are quarantined to {@code .chr.corrupt} and the join reseeds
     * from the current (solo) player so the player is not stuck forever.
     *
     * @return {@code true} if this call seeded a new {@code .chr} from solo
     */
    public static boolean loadOrSeedForJoin(final AdventurePlayer target)
            throws IOException, ClassNotFoundException {
        if (target == null) {
            return false;
        }
        migrateLegacyIfNeeded(target);
        final String id = target.getCharacterId();
        final File file = guestCharacterFile(id);
        if (!file.isFile()) {
            savePlayer(target);
            return true;
        }
        try {
            loadPlayer(target, id);
            return false;
        } catch (final IOException | ClassNotFoundException | RuntimeException e) {
            quarantineCorrupt(file);
            // Reseed from the current player (caller restores solo stash first when
            // load may have partially mutated the in-memory player).
            throw new CoopCorruptChrException(file, e);
        }
    }

    /**
     * Move a corrupt guest {@code .chr} aside so join can reseed. Best-effort.
     */
    static void quarantineCorrupt(final File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        final File quarantined = new File(file.getParentFile(), file.getName() + ".corrupt");
        try {
            Files.move(file.toPath(), quarantined.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            try {
                Files.copy(file.toPath(), quarantined.toPath(), StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (final IOException ignored) {
            }
        }
    }

    /**
     * Move a legacy {@code characters/<name>.chr} into {@code characters/guest/<id>.chr}
     * when the guest file is missing. No-op if already migrated or absent.
     * Old same-name host exports under the legacy path are treated as the guest character.
     */
    static void migrateLegacyIfNeeded(final AdventurePlayer player) {
        if (player == null) {
            return;
        }
        migrateLegacyIfNeeded(player.getName(), player.getCharacterId());
    }

    static void migrateLegacyIfNeeded(final String characterName, final String characterId) {
        if (characterId == null || characterId.isEmpty()) {
            return;
        }
        final File guest = guestCharacterFile(characterId);
        if (guest.isFile()) {
            return;
        }
        final File legacy = legacyCharacterFile(characterName);
        if (!legacy.isFile()) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        guestCharactersDir().mkdirs();
        try {
            Files.move(legacy.toPath(), guest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            // Best-effort: copy then delete if rename across volumes fails.
            try {
                Files.copy(legacy.toPath(), guest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                legacy.delete();
            } catch (final IOException ignored) {
            }
        }
    }

    private static void writeAtomic(final File file, final SaveFileData data) throws IOException {
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

    /** Package-visible: read the raw guest {@code .chr} payload (tests / diagnostics). */
    static SaveFileData readRaw(final String characterId)
            throws IOException, ClassNotFoundException {
        final File file = guestCharacterFile(characterId);
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
     * Package-visible test helper: write a raw guest {@code .chr} payload using the
     * same atomic temp+move path as {@link #savePlayer}.
     */
    static void writeRawForTests(final String characterId, final SaveFileData data)
            throws IOException {
        if (data == null) {
            return;
        }
        writeAtomic(guestCharacterFile(characterId), data);
    }

    /** Write a legacy name-keyed file (tests — pre-PR / host-export migration). */
    static void writeLegacyRawForTests(final String characterName, final SaveFileData data)
            throws IOException {
        if (data == null) {
            return;
        }
        writeAtomic(legacyCharacterFile(characterName), data);
    }

    private static String sanitize(final String name) {
        if (name == null || name.isEmpty()) {
            return "player";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /**
     * Thrown when a guest {@code .chr} cannot be loaded; the file has been
     * quarantined to {@code .corrupt}. Join restores the solo stash and reseeds.
     */
    public static final class CoopCorruptChrException extends IOException {
        private static final long serialVersionUID = 1L;
        private final File quarantinedFrom;

        CoopCorruptChrException(final File file, final Throwable cause) {
            super("Corrupt co-op character file: " + (file != null ? file.getName() : "?"), cause);
            this.quarantinedFrom = file;
        }

        public File getQuarantinedFrom() {
            return quarantinedFrom;
        }
    }
}
