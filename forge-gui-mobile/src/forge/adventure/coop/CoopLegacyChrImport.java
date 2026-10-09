package forge.adventure.coop;

import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CO5 one-time import of pre-CO5 co-op characters from
 * {@code characters/<name>.chr} or {@code characters/guest/<id>.chr}.
 * Tracks which files have been imported so they are not silently duplicated
 * into every world.
 */
public final class CoopLegacyChrImport {
    private static final String IMPORTED_MARK = "coop-legacy-imported.txt";

    private CoopLegacyChrImport() {
    }

    public static File charactersDir() {
        return new File(ForgeConstants.USER_ADVENTURE_DIR
                + Config.instance().getPlane() + File.separator + "characters");
    }

    public static File guestCharactersDir() {
        return new File(charactersDir(), "guest");
    }

    public static List<File> listLegacyChrFiles() {
        final List<File> out = new ArrayList<>();
        collectChr(charactersDir(), out);
        collectChr(guestCharactersDir(), out);
        return out;
    }

    /**
     * Legacy files available for import: not yet marked imported. Host-export
     * files matching the current solo character name are listed but flagged via
     * {@link #isLikelySoloHostExport(File, String)}.
     */
    public static List<File> listImportableChrFiles() {
        final Set<String> imported = readImportedMarks();
        final List<File> out = new ArrayList<>();
        for (final File f : listLegacyChrFiles()) {
            if (!imported.contains(canonicalKey(f))) {
                out.add(f);
            }
        }
        return out;
    }

    public static boolean isLikelySoloHostExport(final File chr, final String soloCharacterName) {
        if (chr == null || soloCharacterName == null || soloCharacterName.isEmpty()) {
            return false;
        }
        final String base = chr.getName();
        if (!base.endsWith(".chr")) {
            return false;
        }
        final String stem = base.substring(0, base.length() - 4);
        final String safeSolo = soloCharacterName.replaceAll("[^a-zA-Z0-9._-]", "_");
        return stem.equalsIgnoreCase(safeSolo) && chr.getParentFile() != null
                && ! "guest".equals(chr.getParentFile().getName());
    }

    public static SaveFileData readChr(final File file) throws IOException, ClassNotFoundException {
        if (file == null || !file.isFile()) {
            return null;
        }
        // Re-encode through the same filtered inflate path as wire partner blobs.
        final byte[] raw;
        try (FileInputStream fis = new FileInputStream(file)) {
            raw = fis.readAllBytes();
        }
        // Legacy .chr is already deflated SaveFileData — decode with wire limits.
        return CoopPartnerCodec.decode(raw);
    }

    public static byte[] encodeChrFile(final File file) throws IOException, ClassNotFoundException {
        final SaveFileData data = readChr(file);
        return CoopPartnerCodec.encode(data);
    }

    public static boolean hasLegacyCharacters() {
        return !listImportableChrFiles().isEmpty();
    }

    public static void markImported(final File file) {
        if (file == null) {
            return;
        }
        try {
            final File mark = importedMarkFile();
            final Set<String> existing = readImportedMarks();
            existing.add(canonicalKey(file));
            final StringBuilder sb = new StringBuilder();
            for (final String k : existing) {
                sb.append(k).append('\n');
            }
            Files.writeString(mark.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (final Exception ignored) {
        }
    }

    private static void collectChr(final File dir, final List<File> out) {
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
            }
        }
    }

    private static File importedMarkFile() {
        final File dir = new File(ForgeConstants.USER_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, IMPORTED_MARK);
    }

    private static Set<String> readImportedMarks() {
        final Set<String> out = new HashSet<>();
        try {
            final File mark = importedMarkFile();
            if (!mark.isFile()) {
                return out;
            }
            for (final String line : Files.readAllLines(mark.toPath(), StandardCharsets.UTF_8)) {
                if (line != null && !line.trim().isEmpty()) {
                    out.add(line.trim());
                }
            }
        } catch (final Exception ignored) {
        }
        return out;
    }

    private static String canonicalKey(final File f) {
        try {
            return f.getCanonicalPath();
        } catch (final Exception e) {
            return f.getAbsolutePath();
        }
    }
}
