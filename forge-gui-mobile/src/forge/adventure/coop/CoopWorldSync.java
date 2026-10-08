package forge.adventure.coop;

import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.coop.CoopWorldHash;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * Guest rebuilds the host world from seed + plane config, verifies a hash, and
 * falls back to receiving the world blob on mismatch. Host remains the owner of
 * the world save going forward (CO2 mutations confirm on the host).
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
     * Guest path: regenerate from seed into the current WorldSave's world slot
     * (player character is left alone). Returns the local hash.
     */
    public static String rebuildFromSeed(final long seed) {
        final World world = WorldSave.getCurrentSave().getWorld();
        world.generateNew(seed);
        return hashWorld(world);
    }

    public static byte[] serializeWorld(final World world) throws IOException {
        final SaveFileData data = world.save();
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(data);
        }
        return bos.toByteArray();
    }

    public static void applyWorldBytes(final byte[] bytes) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            final SaveFileData data = (SaveFileData) ois.readObject();
            WorldSave.getCurrentSave().getWorld().load(data);
        }
    }
}
