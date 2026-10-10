package forge.adventure.coop;

import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;

/**
 * CO5: host-side sanity checks for partner create/snapshot payloads.
 * Caps names, blob size, and inbound snapshot rate (final snapshots bypass rate limit).
 */
public final class CoopPartnerValidator {
    public static final int MAX_NAME_CHARS = 32;
    /** Default max partner blob size (deflated SaveFileData). */
    public static final int DEFAULT_MAX_BLOB_BYTES = 2 * 1024 * 1024;

    private long lastSnapshotMs;
    private int snapshotsInWindow;

    public CoopPartnerValidator() {
    }

    public static String capName(final String name) {
        if (name == null) {
            return "";
        }
        final String trimmed = name.trim();
        if (trimmed.length() <= MAX_NAME_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, MAX_NAME_CHARS);
    }

    public static int maxBlobBytes() {
        try {
            final int configured = Config.instance().getConfigData().coopPartnerMaxBlobBytes;
            if (configured > 0) {
                return configured;
            }
        } catch (final Exception ignored) {
        }
        return DEFAULT_MAX_BLOB_BYTES;
    }

    public static boolean blobSizeOk(final byte[] blob) {
        return blob != null && blob.length > 0 && blob.length <= maxBlobBytes();
    }

    /**
     * Rate-limit partner snapshots. Returns true when the snapshot may be accepted.
     * Callers must skip this for final leave snapshots.
     */
    public synchronized boolean acceptSnapshot() {
        final int maxPerMinute;
        try {
            maxPerMinute = Math.max(1, Config.instance().getConfigData().coopPartnerSnapshotMaxPerMinute);
        } catch (final Exception e) {
            return true;
        }
        final long now = System.currentTimeMillis();
        if (now - lastSnapshotMs > 60_000L) {
            lastSnapshotMs = now;
            snapshotsInWindow = 1;
            return true;
        }
        if (snapshotsInWindow >= maxPerMinute) {
            return false;
        }
        snapshotsInWindow++;
        return true;
    }

    public synchronized void resetRateLimit() {
        lastSnapshotMs = 0L;
        snapshotsInWindow = 0;
    }

    /**
     * Soft-validate a partner player blob after decode: name length and presence.
     * Returns null when acceptable, else a short reject reason.
     */
    public static String validateDecoded(final SaveFileData data) {
        if (data == null) {
            return "empty partner";
        }
        final String name = data.readString("name");
        if (name == null || name.isEmpty()) {
            return "partner missing name";
        }
        if (name.length() > MAX_NAME_CHARS) {
            return "partner name too long";
        }
        return null;
    }
}
