package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * MV1: host → guest when the host changes the live overworld plane mid-session.
 * Guest rebuilds {@code sessionWorld} from seed + world config path, replays
 * {@link #gates}, and verifies {@link #worldHash} against the host's live hash.
 * Adventure pack id stays in {@link #adventurePlaneId}
 * (same meaning as {@link CoopWorldOfferEvent#getPlaneId()}).
 *
 * <p>MV2: {@link #mv2SetCode} carries the host-stamped set code for rebuild
 * customisation. World hash includes planar-gate terrain clears.
 *
 * <p>{@link #requestId} is non-zero only on a quiet resync re-offer (echo of
 * {@link CoopWorldResyncRequestEvent#getRequestId()}); {@code 0} means a real
 * host plane-follow.
 */
public class CoopPlaneSwitchEvent implements NetEvent {
    private static final long serialVersionUID = 4L;

    private final String adventurePlaneId;
    private final String worldPlaneId;
    private final String worldConfigPath;
    private final String planeConfigHash;
    private final long worldSeed;
    private final String worldHash;
    private final float spawnX;
    private final float spawnY;
    private final String mv2SetCode;
    private final CoopPlanarGateEntry[] gates;
    private final long requestId;

    public CoopPlaneSwitchEvent(final String adventurePlaneId, final String worldPlaneId,
                                final String worldConfigPath, final String planeConfigHash,
                                final long worldSeed, final String worldHash,
                                final float spawnX, final float spawnY) {
        this(adventurePlaneId, worldPlaneId, worldConfigPath, planeConfigHash,
                worldSeed, worldHash, spawnX, spawnY, "", null, 0L);
    }

    public CoopPlaneSwitchEvent(final String adventurePlaneId, final String worldPlaneId,
                                final String worldConfigPath, final String planeConfigHash,
                                final long worldSeed, final String worldHash,
                                final float spawnX, final float spawnY,
                                final String mv2SetCode) {
        this(adventurePlaneId, worldPlaneId, worldConfigPath, planeConfigHash,
                worldSeed, worldHash, spawnX, spawnY, mv2SetCode, null, 0L);
    }

    public CoopPlaneSwitchEvent(final String adventurePlaneId, final String worldPlaneId,
                                final String worldConfigPath, final String planeConfigHash,
                                final long worldSeed, final String worldHash,
                                final float spawnX, final float spawnY,
                                final String mv2SetCode, final CoopPlanarGateEntry[] gates) {
        this(adventurePlaneId, worldPlaneId, worldConfigPath, planeConfigHash,
                worldSeed, worldHash, spawnX, spawnY, mv2SetCode, gates, 0L);
    }

    public CoopPlaneSwitchEvent(final String adventurePlaneId, final String worldPlaneId,
                                final String worldConfigPath, final String planeConfigHash,
                                final long worldSeed, final String worldHash,
                                final float spawnX, final float spawnY,
                                final String mv2SetCode, final CoopPlanarGateEntry[] gates,
                                final long requestId) {
        this.adventurePlaneId = adventurePlaneId;
        this.worldPlaneId = worldPlaneId;
        this.worldConfigPath = worldConfigPath;
        this.planeConfigHash = planeConfigHash;
        this.worldSeed = worldSeed;
        this.worldHash = worldHash;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
        this.mv2SetCode = mv2SetCode != null ? mv2SetCode : "";
        this.gates = copyGates(gates);
        this.requestId = requestId;
    }

    public String getAdventurePlaneId() {
        return adventurePlaneId;
    }

    /** MV1 plane instance id (e.g. {@code home}, {@code set_demo}). */
    public String getWorldPlaneId() {
        return worldPlaneId;
    }

    public String getWorldConfigPath() {
        return worldConfigPath;
    }

    public String getPlaneConfigHash() {
        return planeConfigHash;
    }

    public long getWorldSeed() {
        return worldSeed;
    }

    public String getWorldHash() {
        return worldHash;
    }

    public float getSpawnX() {
        return spawnX;
    }

    public float getSpawnY() {
        return spawnY;
    }

    /** Host-stamped MV2 set code; empty means no set customisation on rebuild. */
    public String getMv2SetCode() {
        return mv2SetCode != null ? mv2SetCode : "";
    }

    /** Host live planar gates; never null (may be empty). */
    public CoopPlanarGateEntry[] getGates() {
        return copyGates(gates);
    }

    /**
     * Non-zero when this event answers a guest {@link CoopWorldResyncRequestEvent};
     * {@code 0} for a real host plane-follow.
     */
    public long getRequestId() {
        return requestId;
    }

    private static CoopPlanarGateEntry[] copyGates(final CoopPlanarGateEntry[] src) {
        if (src == null || src.length == 0) {
            return new CoopPlanarGateEntry[0];
        }
        final CoopPlanarGateEntry[] out = new CoopPlanarGateEntry[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }
}
