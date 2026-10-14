package forge.ascendant.world;

public final class BiomeId {
    public static final byte WASTES = 0;
    public static final byte WHITE = 1;
    public static final byte BLUE = 2;
    public static final byte BLACK = 3;
    public static final byte RED = 4;
    public static final byte GREEN = 5;

    public static final String[] NAMES = {"wastes", "white", "blue", "black", "red", "green"};

    private BiomeId() {}

    public static byte byName(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equalsIgnoreCase(name)) {
                return (byte) i;
            }
        }
        throw new IllegalArgumentException("Unknown biome: " + name);
    }
}
