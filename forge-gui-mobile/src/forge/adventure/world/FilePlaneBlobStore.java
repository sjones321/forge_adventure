package forge.adventure.world;

import forge.adventure.util.SaveFileData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Side-file store: {@code <slotFileWithoutExt>.planes/<planeId>.pln}.
 */
public final class FilePlaneBlobStore implements PlaneBlobStore {
    private final File planesDir;

    public FilePlaneBlobStore(File planesDir) {
        this.planesDir = planesDir;
    }

    /** Directory beside a {@code .sav} file: {@code name.sav} → {@code name.planes/}. */
    public static File planesDirForSaveFile(String saveFilePath) {
        if (saveFilePath == null) {
            throw new IllegalArgumentException("saveFilePath required");
        }
        String base = saveFilePath.endsWith(".sav")
                ? saveFilePath.substring(0, saveFilePath.length() - 4)
                : saveFilePath;
        return new File(base + ".planes");
    }

    public File getPlanesDir() {
        return planesDir;
    }

    private File fileFor(String planeId) {
        // Keep plane ids filesystem-safe (no path separators).
        String safe = planeId.replace('/', '_').replace('\\', '_').replace("..", "_");
        return new File(planesDir, safe + ".pln");
    }

    @Override
    public void write(String planeId, SaveFileData blob) throws IOException {
        if (planeId == null || blob == null) {
            throw new IllegalArgumentException("planeId and blob required");
        }
        if (!planesDir.exists() && !planesDir.mkdirs()) {
            throw new IOException("Cannot create planes dir " + planesDir);
        }
        File target = fileFor(planeId);
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(blob);
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace " + target);
        }
        if (!tmp.renameTo(target)) {
            throw new IOException("Cannot rename plane blob to " + target);
        }
    }

    @Override
    public SaveFileData read(String planeId) throws IOException {
        File f = fileFor(planeId);
        if (!f.isFile()) {
            throw new IOException("Missing plane side file: " + f);
        }
        try (FileInputStream fis = new FileInputStream(f);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            Object obj = ois.readObject();
            if (!(obj instanceof SaveFileData)) {
                throw new IOException("Corrupt plane blob: " + f);
            }
            return (SaveFileData) obj;
        } catch (ClassNotFoundException e) {
            throw new IOException("Corrupt plane blob: " + f, e);
        }
    }

    @Override
    public boolean exists(String planeId) {
        return planeId != null && fileFor(planeId).isFile();
    }

    @Override
    public void delete(String planeId) throws IOException {
        File f = fileFor(planeId);
        if (f.isFile() && !f.delete()) {
            throw new IOException("Cannot delete " + f);
        }
    }

    @Override
    public List<String> listIds() {
        List<String> ids = new ArrayList<>();
        File[] files = planesDir.listFiles((dir, name) -> name.endsWith(".pln"));
        if (files == null) {
            return ids;
        }
        for (File f : files) {
            String name = f.getName();
            ids.add(name.substring(0, name.length() - 4));
        }
        return ids;
    }
}
