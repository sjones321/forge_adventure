package forge.adventure.coop;

import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.InflaterInputStream;

/**
 * CO5 one-time import of pre-CO5 co-op characters from
 * {@code characters/<name>.chr} or {@code characters/guest/<id>.chr}.
 * Does not write or delete those files; the guest chooses whether to bring one in.
 */
public final class CoopLegacyChrImport {
    private CoopLegacyChrImport() {
    }

    public static File charactersDir() {
        return new File(ForgeConstants.USER_ADVENTURE_DIR
                + Config.instance().getPlane() + File.separator + "characters");
    }

    public static File guestCharactersDir() {
        return new File(charactersDir(), "guest");
    }

    /** All readable legacy {@code .chr} files under characters/ and characters/guest/. */
    public static List<File> listLegacyChrFiles() {
        final List<File> out = new ArrayList<>();
        collectChr(charactersDir(), out, false);
        collectChr(guestCharactersDir(), out, true);
        return out;
    }

    private static void collectChr(final File dir, final List<File> out, final boolean recursiveOnlyFiles) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        final File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (final File f : files) {
            if (f.isFile() && f.getName().endsWith(".chr")) {
                out.add(f);
            } else if (!recursiveOnlyFiles && f.isDirectory() && "guest".equals(f.getName())) {
                // guest/ handled by the second call
            }
        }
    }

    public static SaveFileData readChr(final File file) throws IOException, ClassNotFoundException {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (FileInputStream fis = new FileInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            final Object obj = ois.readObject();
            if (!(obj instanceof SaveFileData)) {
                throw new IOException("Not a character SaveFileData: " + file.getName());
            }
            return (SaveFileData) obj;
        }
    }

    /** Encode a legacy {@code .chr} for the create wire event. */
    public static byte[] encodeChrFile(final File file) throws IOException, ClassNotFoundException {
        final SaveFileData data = readChr(file);
        return CoopPartnerCodec.encode(data);
    }

    /**
     * True when any legacy co-op {@code .chr} exists for this install/plane.
     * Used to offer the one-time import on first join after the update.
     */
    public static boolean hasLegacyCharacters() {
        return !listLegacyChrFiles().isEmpty();
    }
}
