package forge.gamemodes.net.coop;

/**
 * TCP ports for Ascendant co-op. The game port is Forge's existing multiplayer
 * duel port (CO3); the overworld port is dedicated to the Adventure session
 * handshake and shared-world traffic (CO1/CO2).
 */
public final class CoopPorts {
    /** Existing Forge online / duel lobby port. Reserved for CO3 co-op duels. */
    public static final int GAME_PORT = 36743;
    /** Ascendant co-op overworld session port (handshake, world sync, CO2). */
    public static final int OVERWORLD_PORT = 36744;

    /** Wire protocol version for {@code CoopHelloEvent}. Bump when message shapes change. */
    public static final int PROTOCOL_VERSION = 2;

    /**
     * Hard cap on {@code CoopWorldDataEvent} payload size (bytes). Worlds that
     * exceed this are refused before any deserialization.
     */
    public static final int MAX_WORLD_BLOB_BYTES = 32 * 1024 * 1024;

    /** Length of the short session code shown by the host. */
    public static final int SESSION_CODE_LENGTH = 6;

    private CoopPorts() {
    }
}
