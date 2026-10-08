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
    public static final int PROTOCOL_VERSION = 1;

    private CoopPorts() {
    }
}
