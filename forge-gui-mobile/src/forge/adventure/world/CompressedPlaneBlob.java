package forge.adventure.world;

import forge.adventure.util.SaveFileData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Compress / decompress MV1 plane payloads for storage inside the {@code .sav}.
 * Inactive planes stay as {@code byte[]} in RAM and are inflated only on switch.
 */
public final class CompressedPlaneBlob {
    private CompressedPlaneBlob() {
    }

    public static byte[] compress(SaveFileData blob) throws IOException {
        if (blob == null) {
            throw new IllegalArgumentException("blob required");
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        try (DeflaterOutputStream def = new DeflaterOutputStream(bos, new Deflater(Deflater.BEST_SPEED));
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(blob);
        }
        return bos.toByteArray();
    }

    public static SaveFileData decompress(byte[] compressed) throws IOException {
        if (compressed == null || compressed.length == 0) {
            throw new IOException("Empty compressed plane blob");
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
             InflaterInputStream inf = new InflaterInputStream(bis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            Object obj = ois.readObject();
            if (!(obj instanceof SaveFileData)) {
                throw new IOException("Corrupt compressed plane blob");
            }
            return (SaveFileData) obj;
        } catch (ClassNotFoundException e) {
            throw new IOException("Corrupt compressed plane blob", e);
        }
    }

    /** True when {@code held} looks like a compressed payload (non-null, non-empty). */
    public static boolean isCompressedPayload(byte[] held) {
        return held != null && held.length > 0;
    }
}
