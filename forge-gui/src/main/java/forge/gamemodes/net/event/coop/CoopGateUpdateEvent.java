package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * MV2 follow-up: host → guest when planar gates change mid-session on the
 * current plane (mastery unlock, pending flush on load, return portal ensure).
 * Plain data only — capped {@link CoopPlanarGateEntry} list plus the host's new
 * live {@link #worldHash}. Guest rebuilds from seed, replays gates, and verifies
 * the hash; on mismatch the guest keeps its prior {@code sessionWorld}.
 *
 * <p>Does not move the guest spawn (unlike {@link CoopPlaneSwitchEvent}).
 */
public class CoopGateUpdateEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String worldPlaneId;
    private final String worldConfigPath;
    private final long worldSeed;
    private final String mv2SetCode;
    private final String worldHash;
    private final CoopPlanarGateEntry[] gates;

    public CoopGateUpdateEvent(final String worldPlaneId, final String worldConfigPath,
                               final long worldSeed, final String mv2SetCode,
                               final String worldHash, final CoopPlanarGateEntry[] gates) {
        this.worldPlaneId = worldPlaneId != null ? worldPlaneId : "";
        this.worldConfigPath = worldConfigPath != null ? worldConfigPath : "";
        this.worldSeed = worldSeed;
        this.mv2SetCode = mv2SetCode != null ? mv2SetCode : "";
        this.worldHash = worldHash != null ? worldHash : "";
        this.gates = copyGates(gates);
    }

    public String getWorldPlaneId() {
        return worldPlaneId;
    }

    public String getWorldConfigPath() {
        return worldConfigPath;
    }

    public long getWorldSeed() {
        return worldSeed;
    }

    public String getMv2SetCode() {
        return mv2SetCode != null ? mv2SetCode : "";
    }

    public String getWorldHash() {
        return worldHash != null ? worldHash : "";
    }

    /** Host live planar gates; never null (may be empty). Capped by host collect. */
    public CoopPlanarGateEntry[] getGates() {
        return copyGates(gates);
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
