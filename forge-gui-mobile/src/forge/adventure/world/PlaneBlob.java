package forge.adventure.world;

import forge.adventure.util.SaveFileData;

/**
 * Serialized per-plane state (MV1). Only the current plane is inflated into
 * live {@link World} / stage / POI objects; inactive planes stay as blobs.
 */
public final class PlaneBlob {
    private PlaneBlob() {
    }

    public static SaveFileData pack(SaveFileData world, SaveFileData worldStage,
                                    SaveFileData pointOfInterestChanges, PlaneMeta meta) {
        SaveFileData data = new SaveFileData();
        if (meta != null) {
            data.store("meta", meta.save());
        }
        if (world != null) {
            data.store("world", world);
        }
        if (worldStage != null) {
            data.store("worldStage", worldStage);
        }
        if (pointOfInterestChanges != null) {
            data.store("pointOfInterestChanges", pointOfInterestChanges);
        }
        return data;
    }

    public static PlaneMeta readMeta(SaveFileData blob) {
        if (blob == null) {
            return null;
        }
        SaveFileData metaData = blob.readSubData("meta");
        if (metaData == null) {
            return null;
        }
        PlaneMeta meta = new PlaneMeta();
        meta.load(metaData);
        return meta;
    }

    public static SaveFileData world(SaveFileData blob) {
        return blob == null ? null : blob.readSubData("world");
    }

    public static SaveFileData worldStage(SaveFileData blob) {
        return blob == null ? null : blob.readSubData("worldStage");
    }

    public static SaveFileData poiChanges(SaveFileData blob) {
        return blob == null ? null : blob.readSubData("pointOfInterestChanges");
    }
}
