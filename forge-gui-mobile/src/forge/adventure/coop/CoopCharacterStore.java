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
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Guest (and host) character files — the player's {@link AdventurePlayer} kept
 * separately from the host's world save. Saves use a temp file plus atomic move
 * so the bag and TR1 trade log commit together or not at all.
 */
public final class CoopCharacterStore {
    private CoopCharacterStore() {
    }

    public static File charactersDir() {
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
        final File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
            oos.flush();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean loadPlayer(final AdventurePlayer target, final String characterName)
            throws IOException, ClassNotFoundException {
        final File file = characterFile(characterName);
        if (!file.exists()) {
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
     * Snapshot the current save's player into a character file so Join can
     * reuse it without touching the host world.
     */
    public static void exportCurrentPlayer() throws IOException {
        savePlayer(WorldSave.getCurrentSave().getPlayer());
    }

    private static String sanitize(final String name) {
        if (name == null || name.isEmpty()) {
            return "player";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
