package forge.gamemodes.net.coop;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Fingerprint of a generated overworld so the guest can verify a local rebuild
 * against the host before falling back to a full world transfer.
 *
 * <p>Pure Java — no Adventure / libGDX dependency — so headless tests can cover
 * match and mismatch without loading a World.
 */
public final class CoopWorldHash {
    private CoopWorldHash() {
    }

    /**
     * Hash world identity from seed, dimensions and map grids.
     * {@code biomeMap} / {@code terrainMap} may be null (hash then covers seed+size only).
     */
    public static String hash(final long seed, final int width, final int height,
                              final long[][] biomeMap, final int[][] terrainMap) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update("coop-world-v1".getBytes(StandardCharsets.UTF_8));
            final ByteBuffer buf = ByteBuffer.allocate(8 + 4 + 4);
            buf.putLong(seed);
            buf.putInt(width);
            buf.putInt(height);
            md.update(buf.array());
            if (biomeMap != null) {
                for (int x = 0; x < biomeMap.length; x++) {
                    final long[] col = biomeMap[x];
                    if (col == null) {
                        continue;
                    }
                    final ByteBuffer row = ByteBuffer.allocate(col.length * Long.BYTES);
                    for (final long v : col) {
                        row.putLong(v);
                    }
                    md.update(row.array());
                }
            }
            if (terrainMap != null) {
                for (int x = 0; x < terrainMap.length; x++) {
                    final int[] col = terrainMap[x];
                    if (col == null) {
                        continue;
                    }
                    final ByteBuffer row = ByteBuffer.allocate(col.length * Integer.BYTES);
                    for (final int v : col) {
                        row.putInt(v);
                    }
                    md.update(row.array());
                }
            }
            final byte[] dig = md.digest();
            final StringBuilder sb = new StringBuilder(dig.length * 2);
            for (final byte b : dig) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(final String a, final String b) {
        return a != null && a.equals(b);
    }
}
