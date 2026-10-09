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
     * CO2 (merged #18) is protocol 5; CO3 is 6.
     * MV1 {@code CoopPlaneSwitchEvent} = 7.
     * MV2 = 8: live world hash + host gate list + {@code mv2SetCode} on offer/switch.
     * <p>
     * Do not pre-assign numbers for in-flight PRs. At review time, merge current
     * {@code feature/set-start} and set this to that branch's value + 1. EN2 (#44)
     * adds host-authoritative {@code lootRolls} on {@code CoopDuelResultEvent}.
     * Merge order after EN1 follow-up #46: #44 (this), then #40, #45, #42.
     */
    public static final int PROTOCOL_VERSION = 9;

    /** Length of the short session code shown by the host. */
    public static final int SESSION_CODE_LENGTH = 8;

    /** Failed session-code attempts from one address before lockout. */
    public static final int SESSION_CODE_MAX_FAILURES = 5;

    /** Lockout duration after too many bad session codes (milliseconds). */
    public static final long SESSION_CODE_LOCKOUT_MS = 5L * 60L * 1000L;

    /** Exact guest-side refusal when seed rebuild hash does not match the host. */
    public static final String WORLD_HASH_MISMATCH_MESSAGE =
            "Builds or world data differ; update both copies";

    private CoopPorts() {
    }
}
