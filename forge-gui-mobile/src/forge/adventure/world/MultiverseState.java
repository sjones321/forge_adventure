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
 * <p>Only the current plane is held as a live {@link World}. Inactive planes are
 * stored as <strong>compressed byte blobs inside the {@code .sav}</strong> (and
 * kept compressed in RAM). They are decompressed only when switching to them.
 *
 * <p>Layout inside the save's {@code multiverse} sub-data:
 * <pre>
 *   currentPlaneId, planeIds[], meta_&lt;id&gt;, inactivePlaneIds[],
 *   cz_&lt;id&gt; = byte[] (Deflater-compressed PlaneBlob)
 * </pre>
 */
public final class MultiverseState {
    private String currentPlaneId = PlaneMeta.HOME_ID;
    private final LinkedHashMap<String, PlaneMeta> metas = new LinkedHashMap<>();
    /** Inactive plane ids with a compressed payload in {@link #compressedBlobs}. */
    private final LinkedHashSet<String> inactivePlaneIds = new LinkedHashSet<>();
    /** Compressed payloads — never held as live {@link World} / inflated SaveFileData. */
    private final LinkedHashMap<String, byte[]> compressedBlobs = new LinkedHashMap<>();
    private boolean multiPlaneFormat;

    public String getCurrentPlaneId() {
        if (currentPlaneId != null && !currentPlaneId.isEmpty()) {
            return currentPlaneId;
        }
        return PlaneMeta.HOME_ID;
    }

    /** Raw field (may be null/empty before init); used by load paths that must not rename. */
    public String getCurrentPlaneIdRaw() {
        return currentPlaneId;
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

    public boolean hasCompressedBlob(String planeId) {
        return planeId != null && CompressedPlaneBlob.isCompressedPayload(compressedBlobs.get(planeId));
    }

    /** True when inactive and compressed payload is present (not the live plane). */
    public boolean isSerializedOnly(String planeId) {
        return planeId != null
                && !planeId.equals(getCurrentPlaneId())
                && inactivePlaneIds.contains(planeId)
                && hasCompressedBlob(planeId);
    }

    public int inactivePlaneCount() {
        return inactivePlaneIds.size();
    }

    public Set<String> getInactivePlaneIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(inactivePlaneIds));
    }

    /** Test helper: payload is held compressed (not an inflated SaveFileData). */
    public boolean isHeldCompressed(String planeId) {
        return hasCompressedBlob(planeId);
    }

    public int compressedByteSize(String planeId) {
        byte[] b = compressedBlobs.get(planeId);
        return b == null ? 0 : b.length;
    }

    /**
     * Decompress an inactive plane on demand. Does not retain the inflated form.
     */
    public SaveFileData readInactiveBlob(String planeId) throws IOException {
        if (planeId == null) {
            return null;
        }
        byte[] compressed = compressedBlobs.get(planeId);
        if (!CompressedPlaneBlob.isCompressedPayload(compressed)) {
            return null;
        }
        return CompressedPlaneBlob.decompress(compressed);
    }

    /** @deprecated use {@link #readInactiveBlob(String)} */
    public SaveFileData getInactiveBlob(String planeId) {
        try {
            return readInactiveBlob(planeId);
        } catch (IOException e) {
            return null;
        }
    }

    public void initHomeFromLive(long seed, float posX, float posY) {
        metas.clear();
        inactivePlaneIds.clear();
        compressedBlobs.clear();
        PlaneMeta home = PlaneMeta.home(seed);
        home.setPlayerPos(posX, posY);
        metas.put(home.getId(), home);
        currentPlaneId = home.getId();
        multiPlaneFormat = true;
    }

    public void migrateLegacyHome(long seed, float posX, float posY) {
        initHomeFromLive(seed, posX, posY);
        multiPlaneFormat = true;
    }

    /**
     * NG+: drop every set plane and restart as a fresh home plane on the home template.
     */
    public void resetForNewGamePlus(long seed, float posX, float posY) {
        initHomeFromLive(seed, posX, posY);
    }

    public void rememberCurrentPosition(float posX, float posY) {
        getCurrentMeta().setPlayerPos(posX, posY);
    }

    public void updateCurrentSeed(long seed) {
        getCurrentMeta().setSeed(seed);
    }

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
        // MV2: stamp set code when the plane id encodes one (set_dmu → DMU).
        String code = SetPlaneGenerator.setCodeFromPlaneId(planeId);
        if (!code.isEmpty()) {
            meta.setSetCode(code);
        }
        metas.put(planeId, meta);
        multiPlaneFormat = true;
        return meta;
    }

    /**
     * Compress and retain an inactive plane payload in RAM (and later in the .sav).
     */
    public void writeInactiveBlob(String planeId, SaveFileData blob) throws IOException {
        if (planeId == null || blob == null) {
            return;
        }
        byte[] compressed = CompressedPlaneBlob.compress(blob);
        compressedBlobs.put(planeId, compressed);
        inactivePlaneIds.add(planeId);
        PlaneMeta meta = PlaneBlob.readMeta(blob);
        if (meta != null) {
            metas.put(planeId, meta);
        }
        multiPlaneFormat = true;
    }

    public void putInactiveBlob(String planeId, SaveFileData blob) {
        try {
            writeInactiveBlob(planeId, blob);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to compress plane " + planeId, e);
        }
    }

    public void selectCurrentPlane(String targetPlaneId) {
        if (targetPlaneId == null || targetPlaneId.isEmpty()) {
            throw new IllegalArgumentException("targetPlaneId required");
        }
        if (!metas.containsKey(targetPlaneId)) {
            throw new IllegalArgumentException("Unknown plane: " + targetPlaneId);
        }
        inactivePlaneIds.remove(targetPlaneId);
        compressedBlobs.remove(targetPlaneId);
        currentPlaneId = targetPlaneId;
        multiPlaneFormat = true;
    }

    public void markInactive(String planeId) {
        if (planeId != null && !planeId.equals(getCurrentPlaneId())) {
            inactivePlaneIds.add(planeId);
        }
    }

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
        data.store("compressedInSave", true);
        List<String> ids = new ArrayList<>(metas.keySet());
        data.storeObject("planeIds", ids);
        for (Map.Entry<String, PlaneMeta> e : metas.entrySet()) {
            data.store("meta_" + e.getKey(), e.getValue().save());
        }
        List<String> inactiveIds = new ArrayList<>(inactivePlaneIds);
        data.storeObject("inactivePlaneIds", inactiveIds);
        for (String planeId : inactiveIds) {
            byte[] compressed = compressedBlobs.get(planeId);
            if (CompressedPlaneBlob.isCompressedPayload(compressed)) {
                data.storeObject("cz_" + planeId, compressed);
            }
        }
        return data;
    }

    /**
     * Load MV1 registry. Returns false when there is no multiverse block
     * (caller should {@link #migrateLegacyHome}).
     *
     * <p>Previous side-file / uncompressed-blob formats from earlier MV1 drafts
     * are not supported. Saves from {@code feature/set-start} without a
     * multiverse block still migrate to home.
     */
    public boolean loadRegistry(SaveFileData data) {
        metas.clear();
        inactivePlaneIds.clear();
        compressedBlobs.clear();
        if (data == null || !data.containsKey("currentPlaneId")) {
            // Do not force-rename here — leave field alone for migrateLegacyHome.
            multiPlaneFormat = false;
            return false;
        }
        multiPlaneFormat = true;
        String id = data.readString("currentPlaneId");
        if (id != null && !id.isEmpty()) {
            currentPlaneId = id;
        }
        // Empty/missing value: keep the live plane id preset by the caller.
        // Never force-rename the current plane to home here.
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
                Object raw = data.readObject("cz_" + planeId);
                if (raw instanceof byte[] compressed && CompressedPlaneBlob.isCompressedPayload(compressed)) {
                    compressedBlobs.put(planeId, compressed);
                    inactivePlaneIds.add(planeId);
                }
            }
        }
        return true;
    }

    /**
     * If {@code currentPlaneId} has no meta, synthesize one that keeps that id.
     * Never renames the current plane to {@link PlaneMeta#HOME_ID}.
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

    /** Prefer calling before {@link #loadRegistry} when the live plane id is already known. */
    public void presetCurrentPlaneId(String planeId) {
        if (planeId != null && !planeId.isEmpty()) {
            currentPlaneId = planeId;
        }
    }

    public boolean onlyCurrentPlaneLive() {
        return !inactivePlaneIds.contains(getCurrentPlaneId())
                && !compressedBlobs.containsKey(getCurrentPlaneId());
    }

    /** Deep-copy compressed payloads for slot copy tests. */
    public MultiverseState copyForSlotClone() throws IOException {
        MultiverseState clone = new MultiverseState();
        SaveFileData saved = saveRegistry();
        clone.loadRegistry(saved);
        return clone;
    }
}
