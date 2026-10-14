package forge.ascendant.world;

/** Deterministic coordinate noise (no whole-plane lattices). */
public final class HashNoise {
    private final long seed;

    public HashNoise(long seed) {
        this.seed = seed;
    }

    public float value2d(int x, int y, String channel) {
        long h = seed;
        h = mix(h ^ channel.hashCode());
        h = mix(h ^ x);
        h = mix(h ^ ((long) y * 0x9E3779B97F4A7C15L));
        // [0,1)
        return ((h >>> 11) & 0x1fffff) / (float) 0x1fffff;
    }

    public float fbm(int x, int y, String channel, int octaves, float scale) {
        float sum = 0f;
        float amp = 1f;
        float norm = 0f;
        float s = scale;
        for (int i = 0; i < octaves; i++) {
            float nx = x / s;
            float ny = y / s;
            int ix = (int) Math.floor(nx);
            int iy = (int) Math.floor(ny);
            float fx = nx - ix;
            float fy = ny - iy;
            float v00 = value2d(ix, iy, channel + i);
            float v10 = value2d(ix + 1, iy, channel + i);
            float v01 = value2d(ix, iy + 1, channel + i);
            float v11 = value2d(ix + 1, iy + 1, channel + i);
            float v0 = v00 + (v10 - v00) * smooth(fx);
            float v1 = v01 + (v11 - v01) * smooth(fx);
            sum += (v0 + (v1 - v0) * smooth(fy)) * amp;
            norm += amp;
            amp *= 0.5f;
            s *= 0.5f;
        }
        return sum / norm;
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
