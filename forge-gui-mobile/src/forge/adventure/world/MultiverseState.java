package forge.adventure.world;

import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MV1 multi-plane registry for one {@link WorldSave}.
 *
 * <p>Only the current plane is held as live world/stage/POI state on the save;
 * every other plane stays as a {@link PlaneBlob} in {@link #inactiveBlobs}.
 * Account-level character data lives on {@link forge.adventure.player.AdventurePlayer}.
 */
public final class MultiverseState {
    private String currentPlaneId = PlaneMeta.HOME_ID;
    private final LinkedHashMap<String, PlaneMeta> metas = new LinkedHashMap<>();
    /** Inactive plane payloads — never inflated until switched to. */
    private final LinkedHashMap<String, SaveFileData> inactiveBlobs = new LinkedHashMap<>();
    private boolean multiPlaneFormat;

    public String getCurrentPlaneId() {
        return currentPlaneId != null && !currentPlaneId.isEmpty() ? currentPlaneId : PlaneMeta.HOME_ID;
    }

    public boolean isMultiPlaneFormat() {
        return multiPlaneFormat;
    }

    public PlaneMeta getCurrentMeta() {
        PlaneMeta meta = metas.get(getCurrentPlaneId());
        if (meta == null) {
            meta = PlaneMeta.home(0);
            metas.put(meta.getId(), meta);
        }
        return meta;
    }

    public PlaneMeta getMeta(String planeId) {
        return metas.get(planeId);
    }

    public List<PlaneMeta> listPlanes() {
        return Collections.unmodifiableList(new ArrayList<>(metas.values()));
    }

    public boolean hasPlane(String planeId) {
        return planeId != null && metas.containsKey(planeId);
    }

    public boolean isInactiveLoaded(String planeId) {
        return planeId != null && inactiveBlobs.containsKey(planeId);
    }

    /** True when a plane id has a stored blob and is not the live current plane. */
    public boolean isSerializedOnly(String planeId) {
        return planeId != null
                && !planeId.equals(getCurrentPlaneId())
                && inactiveBlobs.containsKey(planeId);
    }

    public int inactivePlaneCount() {
        return inactiveBlobs.size();
    }

    public SaveFileData getInactiveBlob(String planeId) {
        return inactiveBlobs.get(planeId);
    }

    /**
     * Bootstrap after new game or legacy load: current live world becomes home.
     */
    public void initHomeFromLive(long seed, float posX, float posY) {
        metas.clear();
        inactiveBlobs.clear();
        PlaneMeta home = PlaneMeta.home(seed);
        home.setPlayerPos(posX, posY);
        metas.put(home.getId(), home);
        currentPlaneId = home.getId();
        multiPlaneFormat = true;
    }

    /**
     * Legacy save (no MV1 keys): wrap the single world as home. No data loss.
     */
    public void migrateLegacyHome(long seed, float posX, float posY) {
        initHomeFromLive(seed, posX, posY);
        multiPlaneFormat = true;
    }

    public void rememberCurrentPosition(float posX, float posY) {
        getCurrentMeta().setPlayerPos(posX, posY);
    }

    public void updateCurrentSeed(long seed) {
        getCurrentMeta().setSeed(seed);
    }

    /**
     * Register a new set plane meta (blob filled after generation).
     */
    public PlaneMeta registerSetPlane(String planeId, long seed, String worldConfigPath, String displayName) {
        if (planeId == null || planeId.isEmpty() || PlaneMeta.HOME_ID.equals(planeId)) {
            throw new IllegalArgumentException("Invalid set plane id: " + planeId);
        }
        if (metas.containsKey(planeId)) {
            return metas.get(planeId);
        }
        String path = worldConfigPath;
        if (path == null || path.isEmpty()) {
            path = "world/set_plane_world.json";
        }
        PlaneMeta meta = new PlaneMeta(planeId, PlaneKind.SET, seed, path,
                displayName != null && !displayName.isEmpty() ? displayName : planeId);
        metas.put(planeId, meta);
        multiPlaneFormat = true;
        return meta;
    }

    /**
     * Stash the live plane as an inactive blob and mark {@code targetPlaneId} current.
     * Caller loads the target into live World/stage/POI afterwards.
     */
    public void stashCurrentAndSelect(String targetPlaneId, SaveFileData currentBlob) {
        if (targetPlaneId == null || targetPlaneId.isEmpty()) {
            throw new IllegalArgumentException("targetPlaneId required");
        }
        if (!metas.containsKey(targetPlaneId) && !targetPlaneId.equals(getCurrentPlaneId())) {
            throw new IllegalArgumentException("Unknown plane: " + targetPlaneId);
        }
        String from = getCurrentPlaneId();
        if (currentBlob != null && from != null) {
            inactiveBlobs.put(from, currentBlob);
        }
        inactiveBlobs.remove(targetPlaneId);
        currentPlaneId = targetPlaneId;
        multiPlaneFormat = true;
    }

    public void putInactiveBlob(String planeId, SaveFileData blob) {
        if (planeId == null || blob == null) {
            return;
        }
        if (planeId.equals(getCurrentPlaneId())) {
            return;
        }
        inactiveBlobs.put(planeId, blob);
        PlaneMeta meta = PlaneBlob.readMeta(blob);
        if (meta != null) {
            metas.put(planeId, meta);
        }
        multiPlaneFormat = true;
    }

    public SaveFileData saveRegistry() {
        SaveFileData data = new SaveFileData();
        data.store("currentPlaneId", getCurrentPlaneId());
        data.store("multiPlaneFormat", multiPlaneFormat);
        List<String> ids = new ArrayList<>(metas.keySet());
        data.storeObject("planeIds", ids);
        for (Map.Entry<String, PlaneMeta> e : metas.entrySet()) {
            data.store("meta_" + e.getKey(), e.getValue().save());
        }
        List<String> inactiveIds = new ArrayList<>(inactiveBlobs.keySet());
        data.storeObject("inactivePlaneIds", inactiveIds);
        for (Map.Entry<String, SaveFileData> e : inactiveBlobs.entrySet()) {
            data.store("blob_" + e.getKey(), e.getValue());
        }
        return data;
    }

    /**
     * Load MV1 registry. Returns false when the save has no multiverse block
     * (caller should {@link #migrateLegacyHome}).
     */
    public boolean loadRegistry(SaveFileData data) {
        metas.clear();
        inactiveBlobs.clear();
        if (data == null || !data.containsKey("currentPlaneId")) {
            currentPlaneId = PlaneMeta.HOME_ID;
            multiPlaneFormat = false;
            return false;
        }
        multiPlaneFormat = true;
        String id = data.readString("currentPlaneId");
        currentPlaneId = id != null && !id.isEmpty() ? id : PlaneMeta.HOME_ID;
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) data.readObject("planeIds");
        if (ids != null) {
            for (String planeId : ids) {
                if (planeId == null) {
                    continue;
                }
                SaveFileData metaData = data.readSubData("meta_" + planeId);
                PlaneMeta meta = new PlaneMeta();
                if (metaData != null) {
                    meta.load(metaData);
                } else {
                    meta = new PlaneMeta(planeId, PlaneMeta.HOME_ID.equals(planeId)
                            ? PlaneKind.HOME : PlaneKind.SET, 0, Paths.WORLD, planeId);
                }
                metas.put(planeId, meta);
            }
        }
        if (!metas.containsKey(currentPlaneId)) {
            metas.put(PlaneMeta.HOME_ID, PlaneMeta.home(0));
            currentPlaneId = PlaneMeta.HOME_ID;
        }
        @SuppressWarnings("unchecked")
        List<String> inactiveIds = (List<String>) data.readObject("inactivePlaneIds");
        if (inactiveIds != null) {
            for (String planeId : inactiveIds) {
                if (planeId == null || planeId.equals(currentPlaneId)) {
                    continue;
                }
                SaveFileData blob = data.readSubData("blob_" + planeId);
                if (blob != null) {
                    inactiveBlobs.put(planeId, blob);
                    PlaneMeta blobMeta = PlaneBlob.readMeta(blob);
                    if (blobMeta != null) {
                        metas.put(planeId, blobMeta);
                    }
                }
            }
        }
        return true;
    }

    /** Test helper: how many live World objects this registry implies (always 0 or concepts). */
    public boolean onlyCurrentPlaneLive() {
        return !inactiveBlobs.containsKey(getCurrentPlaneId());
    }
}
