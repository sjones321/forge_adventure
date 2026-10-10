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
     * MV1 {@code CoopPlaneSwitchEvent} (host plane follow) = 7.
     * MV2 = 8: live world hash + host gate list + {@code mv2SetCode} on offer/switch.
     * EN2 (#44) = 9: host-authoritative {@code lootRolls} on {@code CoopDuelResultEvent}.
     * Package K = 10: {@code planeFormat} plain-data field on world offer / plane switch.
     * RW1 (#49) = 11: guest loot credit (enemy data id, theme id, signature candidates)
     * on {@code CoopDuelResultEvent}.
     * DS4 (#55) = 12: {@code TrackableProperty.CanTakeBack} appended — checksum sampler
     * sends ordinals; a DS4 host vs base guest both claiming 11 can
     * {@code ArrayIndexOutOfBounds}. Take-back itself is still single-player / no co-op wire.
     *
     * <p>Rule: set to {@code (feature/set-start PROTOCOL_VERSION) + 1} at review time;
     * Steve checks the number at merge. Do not pre-assign numbers for in-flight PRs.
     */
    public static final int PROTOCOL_VERSION = 12;

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
