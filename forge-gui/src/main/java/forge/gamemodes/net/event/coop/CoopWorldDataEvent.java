package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest: serialized world {@code SaveFileData} bytes (Adventure world
 * only — not the host's character). Fallback when seed rebuild mismatches.
 */
public class CoopWorldDataEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long worldSeed;
    private final String worldHash;
    private final byte[] worldSaveBytes;

    public CoopWorldDataEvent(final long worldSeed, final String worldHash, final byte[] worldSaveBytes) {
        this.worldSeed = worldSeed;
        this.worldHash = worldHash;
        this.worldSaveBytes = worldSaveBytes != null ? worldSaveBytes.clone() : new byte[0];
    }

    public long getWorldSeed() {
        return worldSeed;
    }

    public String getWorldHash() {
        return worldHash;
    }

    public byte[] getWorldSaveBytes() {
        return worldSaveBytes.clone();
    }
}
