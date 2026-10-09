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

    /**
     * Wire protocol version for {@code CoopHelloEvent}. Bump when message shapes change.
     * Do not pre-assign future numbers — at review time set this to
     * {@code (feature/set-start PROTOCOL_VERSION) + 1}.
     * History: CO2=5, CO3=6, MV1 plane-follow=7, MV2 live hash/gates=8,
     * EN2 host-authoritative {@code lootRolls} on {@code CoopDuelResultEvent}=9,
     * Package K {@code planeFormat} on world offer / plane switch=10,
     * MV2 mid-session gate-delta ({@code CoopGateUpdateEvent} /
     * {@code CoopWorldResyncRequestEvent} + resync requestId)=11.
     */
    public static final int PROTOCOL_VERSION = 11;

    /** Length of the short session code shown by the host. */
    public static final int SESSION_CODE_LENGTH = 8;

    /** Failed session-code attempts from one address before lockout. */
    public static final int SESSION_CODE_MAX_FAILURES = 5;

    /** Lockout duration after too many bad session codes (milliseconds). */
    public static final long SESSION_CODE_LOCKOUT_MS = 5L * 60L * 1000L;

    /** Exact guest-side refusal when seed rebuild hash does not match the host. */
    public static final String WORLD_HASH_MISMATCH_MESSAGE =
            "Builds or world data differ; update both copies";

    /** Guest/host status when a mid-session gate update fails the live hash check. */
    public static final String GATE_UPDATE_MISMATCH_MESSAGE =
            "Gate update hash mismatch — requesting world resync";

    private CoopPorts() {
    }
}
