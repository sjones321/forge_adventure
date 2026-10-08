package forge.adventure.world;

import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MV1 multi-plane registry for one {@link WorldSave}.
 *
 * <p>Only the current plane is held as live world/stage/POI state on the save.
 * Inactive planes are referenced by id and loaded on demand from a
 * {@link PlaneBlobStore} (side files). Full world blobs are not kept in RAM.
 * Account-level character data lives on {@link forge.adventure.player.AdventurePlayer}.
 */
public final class MultiverseState {
    private String currentPlaneId = PlaneMeta.HOME_ID;
    private final LinkedHashMap<String, PlaneMeta> metas = new LinkedHashMap<>();
    /** Inactive plane ids whose payloads live in {@link #blobStore}. */
    private final LinkedHashSet<String> inactivePlaneIds = new LinkedHashSet<>();
    private boolean multiPlaneFormat;
    private PlaneBlobStore blobStore = new MemoryPlaneBlobStore();
    /** Embedded blobs found while loading a pre-side-file save; flushed then cleared. */
    private final LinkedHashMap<String, SaveFileData> pendingEmbeddedMigration = new LinkedHashMap<>();

    public void setBlobStore(PlaneBlobStore store) {
        this.blobStore = store != null ? store : new MemoryPlaneBlobStore();
    }

    public PlaneBlobStore getBlobStore() {
        return blobStore;
    }

    public String getCurrentPlaneId() {
        return currentPlaneId != null && !currentPlaneId.isEmpty() ? currentPlaneId : PlaneMeta.HOME_ID;
    }

    public boolean isMultiPlaneFormat() {
        return multiPlaneFormat;
    }

    public PlaneMeta getCurrentMeta() {
        PlaneMeta meta = metas.get(getCurrentPlaneId());
        if (meta == null) {
            meta = ensureMetaForCurrent(0L, Paths.WORLD);
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

    /** True when a plane id is inactive (side-file / store) and not the live current plane. */
    public boolean isSerializedOnly(String planeId) {
        return planeId != null
                && !planeId.equals(getCurrentPlaneId())
                && inactivePlaneIds.contains(planeId);
    }

    public int inactivePlaneCount() {
        return inactivePlaneIds.size();
    }

    public Set<String> getInactivePlaneIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(inactivePlaneIds));
    }

    /**
     * Load an inactive plane blob on demand. Does not retain the payload in this registry.
     */
    public SaveFileData readInactiveBlob(String planeId) throws IOException {
        if (planeId == null) {
            return null;
        }
        SaveFileData pending = pendingEmbeddedMigration.get(planeId);
        if (pending != null) {
            return pending;
        }
        if (!blobStore.exists(planeId)) {
            return null;
        }
        return blobStore.read(planeId);
    }

    /** @deprecated use {@link #readInactiveBlob(String)}; kept for older call sites. */
    public SaveFileData getInactiveBlob(String planeId) {
        try {
            return readInactiveBlob(planeId);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Bootstrap after new game or legacy load: current live world becomes home.
     */
    public void initHomeFromLive(long seed, float posX, float posY) {
        metas.clear();
        inactivePlaneIds.clear();
        pendingEmbeddedMigration.clear();
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
     * Register a new set plane meta (payload written separately to the blob store).
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
        if (!PlaneConfigPaths.isSyntacticallySafe(path)) {
            throw new IllegalArgumentException("Invalid worldConfigPath: " + path);
        }
        PlaneMeta meta = new PlaneMeta(planeId, PlaneKind.SET, seed, path,
                displayName != null && !displayName.isEmpty() ? displayName : planeId);
        metas.put(planeId, meta);
        multiPlaneFormat = true;
        return meta;
    }

    /**
     * Persist a plane payload to the blob store without keeping it in RAM.
     * Allowed for the current plane when stashing it during an atomic switch
     * (it is marked inactive; {@link #selectCurrentPlane} then changes current).
     */
    public void writeInactiveBlob(String planeId, SaveFileData blob) throws IOException {
        if (planeId == null || blob == null) {
            return;
        }
        blobStore.write(planeId, blob);
        inactivePlaneIds.add(planeId);
        pendingEmbeddedMigration.remove(planeId);
        PlaneMeta meta = PlaneBlob.readMeta(blob);
        if (meta != null) {
            metas.put(planeId, meta);
        }
        multiPlaneFormat = true;
    }

    /** @deprecated prefer {@link #writeInactiveBlob}; swallows IO errors. */
    public void putInactiveBlob(String planeId, SaveFileData blob) {
        try {
            writeInactiveBlob(planeId, blob);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store plane " + planeId, e);
        }
    }

    /**
     * After a successful staging load: mark {@code fromPlaneId} inactive (caller
     * writes its blob), select {@code targetPlaneId} as current, drop target from inactive set.
     * Does not load world data.
     */
    public void selectCurrentPlane(String targetPlaneId) {
        if (targetPlaneId == null || targetPlaneId.isEmpty()) {
            throw new IllegalArgumentException("targetPlaneId required");
        }
        if (!metas.containsKey(targetPlaneId)) {
            throw new IllegalArgumentException("Unknown plane: " + targetPlaneId);
        }
        inactivePlaneIds.remove(targetPlaneId);
        currentPlaneId = targetPlaneId;
        multiPlaneFormat = true;
    }

    public void markInactive(String planeId) {
        if (planeId != null && !planeId.equals(getCurrentPlaneId())) {
            inactivePlaneIds.add(planeId);
        }
    }

    /**
     * Stash helper used by tests: write current blob then select target.
     * Production switch uses staging load before calling this sequence.
     */
    public void stashCurrentAndSelect(String targetPlaneId, SaveFileData currentBlob) {
        String from = getCurrentPlaneId();
        if (currentBlob != null && from != null) {
            putInactiveBlob(from, currentBlob);
        }
        selectCurrentPlane(targetPlaneId);
    }

    public SaveFileData saveRegistry() {
        SaveFileData data = new SaveFileData();
        data.store("currentPlaneId", getCurrentPlaneId());
        data.store("multiPlaneFormat", multiPlaneFormat);
        data.store("sideFiles", true);
        List<String> ids = new ArrayList<>(metas.keySet());
        data.storeObject("planeIds", ids);
        for (Map.Entry<String, PlaneMeta> e : metas.entrySet()) {
            data.store("meta_" + e.getKey(), e.getValue().save());
        }
        List<String> inactiveIds = new ArrayList<>(inactivePlaneIds);
        data.storeObject("inactivePlaneIds", inactiveIds);
        // Intentionally do NOT embed full blobs — side files hold them.
        return data;
    }

    /**
     * Load MV1 registry. Returns false when the save has no multiverse block
     * (caller should {@link #migrateLegacyHome}).
     *
     * <p>Embedded {@code blob_*} keys from older MV1 saves are collected into
     * {@link #pendingEmbeddedMigration} for the caller to flush to the side store.
     */
    public boolean loadRegistry(SaveFileData data) {
        metas.clear();
        inactivePlaneIds.clear();
        pendingEmbeddedMigration.clear();
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
            // Keep the live plane's identity — never relabel a set world as home.
            ensureMetaForCurrent(0L, Paths.WORLD);
        }
        @SuppressWarnings("unchecked")
        List<String> inactiveIds = (List<String>) data.readObject("inactivePlaneIds");
        if (inactiveIds != null) {
            for (String planeId : inactiveIds) {
                if (planeId == null || planeId.equals(currentPlaneId)) {
                    continue;
                }
                inactivePlaneIds.add(planeId);
                SaveFileData embedded = data.readSubData("blob_" + planeId);
                if (embedded != null) {
                    pendingEmbeddedMigration.put(planeId, embedded);
                    PlaneMeta blobMeta = PlaneBlob.readMeta(embedded);
                    if (blobMeta != null) {
                        metas.put(planeId, blobMeta);
                    }
                }
            }
        }
        // Also scan for any blob_* keys not listed (defensive).
        for (String key : data.keySet()) {
            if (key != null && key.startsWith("blob_")) {
                String planeId = key.substring("blob_".length());
                if (planeId.isEmpty() || planeId.equals(currentPlaneId)) {
                    continue;
                }
                if (!pendingEmbeddedMigration.containsKey(planeId)) {
                    SaveFileData embedded = data.readSubData(key);
                    if (embedded != null) {
                        pendingEmbeddedMigration.put(planeId, embedded);
                        inactivePlaneIds.add(planeId);
                    }
                }
            }
        }
        return true;
    }

    /**
     * Flush embedded blobs from an old in-save format into the side-file store.
     */
    public void flushEmbeddedMigrations() throws IOException {
        if (pendingEmbeddedMigration.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SaveFileData> e : new ArrayList<>(pendingEmbeddedMigration.entrySet())) {
            blobStore.write(e.getKey(), e.getValue());
            inactivePlaneIds.add(e.getKey());
            pendingEmbeddedMigration.remove(e.getKey());
        }
    }

    public boolean hasPendingEmbeddedMigration() {
        return !pendingEmbeddedMigration.isEmpty();
    }

    /**
     * If {@code currentPlaneId} has no meta, synthesize one that keeps that id
     * (home vs set by id), never forcing a rename to {@link PlaneMeta#HOME_ID}.
     */
    public PlaneMeta ensureMetaForCurrent(long seed, String worldConfigPath) {
        String id = getCurrentPlaneId();
        PlaneMeta existing = metas.get(id);
        if (existing != null) {
            return existing;
        }
        PlaneKind kind = PlaneMeta.HOME_ID.equals(id) ? PlaneKind.HOME : PlaneKind.SET;
        String path = worldConfigPath != null && !worldConfigPath.isEmpty() ? worldConfigPath : Paths.WORLD;
        PlaneMeta meta = new PlaneMeta(id, kind, seed, path, id);
        metas.put(id, meta);
        return meta;
    }

    /** True when no inactive plane payload is retained in the registry maps (side-file mode). */
    public boolean onlyCurrentPlaneLive() {
        return !inactivePlaneIds.contains(getCurrentPlaneId()) && pendingEmbeddedMigration.isEmpty();
    }
}
