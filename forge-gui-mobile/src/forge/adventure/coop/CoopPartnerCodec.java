package forge.adventure.coop;

import forge.adventure.util.SaveFileData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * CO5: encode/decode partner {@link SaveFileData} as a deflated byte blob for the host
 * world save and for plain-data wire events. Network peers never receive live
 * {@code AdventurePlayer} / {@code PaperCard} graphs — only this blob inside a
 * registered NetEvent; the host validates size before inflate.
 */
public final class CoopPartnerCodec {
    private CoopPartnerCodec() {
    }

    /** Deflate + ObjectOutputStream of a {@link SaveFileData}. */
    public static byte[] encode(final SaveFileData data) throws IOException {
        if (data == null) {
            return new byte[0];
        }
        final ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(256, data.size() * 64));
        try (DeflaterOutputStream def = new DeflaterOutputStream(bos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
        }
        return bos.toByteArray();
    }

    /** Inflate a blob produced by {@link #encode}. */
    public static SaveFileData decode(final byte[] blob) throws IOException, ClassNotFoundException {
        if (blob == null || blob.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bis = new ByteArrayInputStream(blob);
             InflaterInputStream inf = new InflaterInputStream(bis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            final Object obj = ois.readObject();
            if (!(obj instanceof SaveFileData)) {
                throw new IOException("Partner blob is not SaveFileData");
            }
            return (SaveFileData) obj;
        }
    }

    /**
     * Persist the partners map into a nested {@link SaveFileData} for the world save.
     * Keys are profile ids; values are encoded player blobs stored as raw bytes.
     */
    public static SaveFileData encodeMap(final Map<String, SaveFileData> partners) {
        final SaveFileData out = new SaveFileData();
        if (partners == null || partners.isEmpty()) {
            return out;
        }
        for (final Map.Entry<String, SaveFileData> e : partners.entrySet()) {
            final String id = CoopProfileId.sanitize(e.getKey());
            if (id.isEmpty() || e.getValue() == null) {
                continue;
            }
            try {
                out.put(id, encode(e.getValue()));
            } catch (final IOException ignored) {
                // Skip corrupt entries rather than aborting the host save.
            }
        }
        return out;
    }

    /** Load partners from a world-save nested blob. Missing/empty → empty map. */
    public static Map<String, SaveFileData> decodeMap(final SaveFileData stored) {
        final Map<String, SaveFileData> out = new HashMap<>();
        if (stored == null || stored.isEmpty()) {
            return out;
        }
        for (final Map.Entry<String, byte[]> e : stored.entrySet()) {
            final String id = CoopProfileId.sanitize(e.getKey());
            if (id.isEmpty() || e.getValue() == null || e.getValue().length == 0) {
                continue;
            }
            try {
                final SaveFileData player = decode(e.getValue());
                if (player != null) {
                    out.put(id, player);
                }
            } catch (final Exception ignored) {
                // Skip corrupt partner slots so the rest of the world still loads.
            }
        }
        return out;
    }
}
