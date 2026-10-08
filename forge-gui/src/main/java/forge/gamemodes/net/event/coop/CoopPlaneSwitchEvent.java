package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * MV1: host → guest when the host changes the live overworld plane mid-session.
 * Guest rebuilds {@code sessionWorld} from seed + world config path and verifies
 * {@link #worldHash}. Adventure pack id stays in {@link #adventurePlaneId}
 * (same meaning as {@link CoopWorldOfferEvent#getPlaneId()}).
 */
public class CoopPlaneSwitchEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String adventurePlaneId;
    private final String worldPlaneId;
    private final String worldConfigPath;
    private final String planeConfigHash;
    private final long worldSeed;
    private final String worldHash;
    private final float spawnX;
    private final float spawnY;

    public CoopPlaneSwitchEvent(final String adventurePlaneId, final String worldPlaneId,
                                final String worldConfigPath, final String planeConfigHash,
                                final long worldSeed, final String worldHash,
                                final float spawnX, final float spawnY) {
        this.adventurePlaneId = adventurePlaneId;
        this.worldPlaneId = worldPlaneId;
        this.worldConfigPath = worldConfigPath;
        this.planeConfigHash = planeConfigHash;
        this.worldSeed = worldSeed;
        this.worldHash = worldHash;
        this.spawnX = spawnX;
        this.spawnY = spawnY;
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
}
