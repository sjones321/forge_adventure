package forge.adventure.coop;

import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.World;
import forge.gamemodes.net.FilteredJavaObjectInputStream;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.coop.CoopWorldHash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

/**
 * Guest rebuilds the host world from seed + plane config, verifies a hash, and
 * falls back to receiving the world blob on mismatch. Network bytes are always
 * filtered through {@link FilteredJavaObjectInputStream} / {@link WireClassFilter};
 * the hash is verified <em>before</em> {@link World#load} runs.
 */
public final class CoopWorldSync {
    private CoopWorldSync() {
    }

    public static String planeConfigHash() {
        try {
            final String plane = Config.instance().getPlane();
            final String raw = Config.instance().getFile("config.json").readString();
            final String worldRaw = Config.instance().getFile("world/world.json").readString();
            return CoopVersion.sha256Hex(plane + '|' + raw + '|' + worldRaw);
        } catch (final Exception e) {
            return CoopVersion.sha256Hex("plane-config:error:" + e.getMessage());
        }
    }

    public static String hashWorld(final World world) {
        if (world == null) {
            return CoopWorldHash.hash(0, 0, 0, null, null);
        }
        return CoopWorldHash.hash(
                world.getSeed(),
                world.getWidthInTiles(),
                world.getHeightInTiles(),
                world.getBiomeMap(),
                world.terrainMap);
    }

    /**
     * Regenerate into a dedicated session {@link World} (does not touch the
     * guest's saved WorldSave). Returns the local hash.
     */
    public static String rebuildFromSeed(final World target, final long seed) {
        target.generateNew(seed);
        return hashWorld(target);
    }

    public static byte[] serializeWorld(final World world) throws IOException {
        final SaveFileData data = world.save();
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(data);
        }
        return bos.toByteArray();
    }

    /**
     * Deserialize, verify size + hash against {@code expectedHash}, then load
     * into {@code target}. Never uses a plain ObjectInputStream.
     *
     * @throws IOException if the blob is oversized, hash-mismatched, or filtered out
     */
    public static void applyWorldBytesVerified(final World target, final byte[] bytes,
                                               final String expectedHash) throws IOException, ClassNotFoundException {
        if (bytes == null) {
            throw new IOException("Empty world blob");
        }
        if (bytes.length > CoopPorts.MAX_WORLD_BLOB_BYTES) {
            throw new IOException("World blob too large (" + bytes.length + " > "
                    + CoopPorts.MAX_WORLD_BLOB_BYTES + ")");
        }
        final SaveFileData data;
        try (FilteredJavaObjectInputStream ois =
                     new FilteredJavaObjectInputStream(new ByteArrayInputStream(bytes))) {
            final Object obj = ois.readObject();
            if (!(obj instanceof SaveFileData)) {
                throw new IOException("World blob was not SaveFileData");
            }
            data = (SaveFileData) obj;
        }
        // Peek maps under the wire filter to hash before mutating target.
        final World probe = new World();
        final IOException[] loadError = {null};
        SaveFileData.runWithWireFilter(() -> {
            try {
                probe.load(data);
            } catch (final RuntimeException e) {
                loadError[0] = new IOException("Failed to probe-load world blob: " + e.getMessage(), e);
            }
        });
        if (loadError[0] != null) {
            throw loadError[0];
        }
        final String actual = hashWorld(probe);
        if (!CoopWorldHash.matches(actual, expectedHash)) {
            throw new IOException("World blob hash mismatch (expected "
                    + expectedHash + ", got " + actual + ")");
        }
        // Hash OK — apply into the session world under the same filter.
        SaveFileData.runWithWireFilter(() -> target.load(data));
    }
}
