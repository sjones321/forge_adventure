package forge.adventure.coop;

import com.google.common.io.ByteStreams;
import forge.adventure.util.SaveFileData;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.WireStreamLimits;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * CO5: encode/decode partner {@link SaveFileData} as a deflated byte blob.
 * Decode uses the same {@link WireClassFilter} + {@link WireStreamLimits} as the
 * multiplayer wire (including a decompressed-byte ceiling), and nested
 * {@code SaveFileData} reads. Catches {@link OutOfMemoryError} / {@link Throwable}
 * so a bad blob cannot kill Netty.
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

    /**
     * Inflate a blob produced by {@link #encode}. Applies wire class filter and
     * stream limits. Returns null on failure (never throws into Netty).
     */
    public static SaveFileData decodeSafe(final byte[] blob) {
        try {
            return decode(blob);
        } catch (final OutOfMemoryError oom) {
            System.err.println("CO5 partner blob OOM: " + oom);
            return null;
        } catch (final Throwable t) {
            System.err.println("CO5 partner blob rejected: " + t);
            return null;
        }
    }

    /** Inflate a blob; throws on hard failures (tests). Prefer {@link #decodeSafe}. */
    public static SaveFileData decode(final byte[] blob) throws IOException, ClassNotFoundException {
        if (blob == null || blob.length == 0) {
            return null;
        }
        if (!CoopPartnerValidator.blobSizeOk(blob)) {
            throw new IOException("Partner blob size rejected (" + blob.length + ")");
        }
        SaveFileData.beginWireFilteredReads();
        try (ByteArrayInputStream bis = new ByteArrayInputStream(blob);
             InflaterInputStream inf = new InflaterInputStream(bis);
             InputStream bounded = ByteStreams.limit(inf, WireStreamLimits.MAX_DECOMPRESSED_BYTES);
             ObjectInputStream ois = new FilteredPartnerInputStream(bounded)) {
            final Object obj = ois.readObject();
            if (!(obj instanceof SaveFileData)) {
                throw new IOException("Partner blob is not SaveFileData");
            }
            return (SaveFileData) obj;
        } finally {
            SaveFileData.endWireFilteredReads();
        }
    }

    /**
     * ObjectInputStream that runs every resolved class through
     * {@link WireClassFilter} and installs {@link WireStreamLimits}.
     */
    static final class FilteredPartnerInputStream extends ObjectInputStream {
        FilteredPartnerInputStream(final InputStream in) throws IOException {
            super(in);
            WireStreamLimits.applyTo(this);
        }

        @Override
        protected Class<?> resolveClass(final ObjectStreamClass desc)
                throws IOException, ClassNotFoundException {
            WireClassFilter.checkAllowed(desc.getName());
            return super.resolveClass(desc);
        }

        @Override
        protected Class<?> resolveProxyClass(final String[] interfaces) throws IOException {
            throw new InvalidClassException("dynamic proxy",
                    "proxy classes are not permitted in partner blobs");
        }
    }
}
