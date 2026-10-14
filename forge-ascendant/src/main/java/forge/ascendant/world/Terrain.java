package forge.ascendant.world;

/** Terrain codes — keep aligned with tools/worldgen-prototype/planegen/core.py names. */
public final class Terrain {
    public static final byte GRASS = 0;
    public static final byte ROAD = 1;
    public static final byte TREE = 2;
    public static final byte ROCK = 3;
    public static final byte RIDGE = 4;
    public static final byte WATER = 5;
    public static final byte OCEAN = 6;
    public static final byte BRIDGE = 7;
    public static final byte TOWN = 8;
    public static final byte HOUSE = 9;

    private Terrain() {}

    public static boolean blocksMovement(byte t) {
        return t == TREE || t == ROCK || t == RIDGE || t == WATER || t == OCEAN || t == HOUSE;
    }

    public static boolean isWater(byte t) {
        return t == WATER || t == OCEAN;
    }
}
