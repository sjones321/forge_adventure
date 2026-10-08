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
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Guest (and host) character files — the player's {@link AdventurePlayer} kept
 * separately from the host's world save. CO1: guest brings their character and
 * saves it locally when the session ends.
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
        final SaveFileData data = player.save();
        try (FileOutputStream fos = new FileOutputStream(file);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
        }
    }

    public static boolean loadPlayer(final AdventurePlayer target, final String characterName) throws IOException, ClassNotFoundException {
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
