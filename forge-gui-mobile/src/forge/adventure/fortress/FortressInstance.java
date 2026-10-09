package forge.adventure.fortress;

import forge.adventure.util.SaveFileData;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-plane fortress state (FT1). Stored inside MV1 {@code PlaneBlob} under key {@code fortress}.
 * Old saves omit the key → no fortress.
 */
public final class FortressInstance implements Serializable {
    private static final long serialVersionUID = 1L;

    public static final String POI_TEMPLATE_NAME = "Fortress";

    private String planeId = "";
    private String poiId = "";
    private float worldX;
    private float worldY;
    /** Buildable zone origin in map tile coordinates (inclusive). */
    private int gridOriginX;
    private int gridOriginY;
    private int gridWidth = 12;
    private int gridHeight = 12;
    /** Map size in tiles (for path-to-entry BFS). Defaults match fortress_camp.tmx. */
    private int mapWidthTiles = 24;
    private int mapHeightTiles = 20;
    /** Entry tile inside the fortress map (spawn / exit). */
    private int entryGridX = 11;
    private int entryGridY = 1;
    private final List<PlacedStructure> structures = new ArrayList<>();
    /** Material id → count in fortress storage (FT1 wiring for INV1 overflow hook). */
    private final LinkedHashMap<String, Integer> storage = new LinkedHashMap<>();

    public String getPlaneId() {
        return planeId;
    }

    public void setPlaneId(String planeId) {
        this.planeId = planeId != null ? planeId : "";
    }

    public String getPoiId() {
        return poiId;
    }

    public void setPoiId(String poiId) {
        this.poiId = poiId != null ? poiId : "";
    }

    public float getWorldX() {
        return worldX;
    }

    public float getWorldY() {
        return worldY;
    }

    public void setWorldPos(float x, float y) {
        this.worldX = x;
        this.worldY = y;
    }

    public int getGridOriginX() {
        return gridOriginX;
    }

    public int getGridOriginY() {
        return gridOriginY;
    }

    public int getGridWidth() {
        return gridWidth;
    }

    public int getGridHeight() {
        return gridHeight;
    }

    public void setBuildableZone(int originX, int originY, int width, int height) {
        this.gridOriginX = originX;
        this.gridOriginY = originY;
        this.gridWidth = Math.max(1, width);
        this.gridHeight = Math.max(1, height);
    }

    public int getMapWidthTiles() {
        return mapWidthTiles;
    }

    public int getMapHeightTiles() {
        return mapHeightTiles;
    }

    public void setMapSizeTiles(int width, int height) {
        this.mapWidthTiles = Math.max(1, width);
        this.mapHeightTiles = Math.max(1, height);
    }

    public int getEntryGridX() {
        return entryGridX;
    }

    public int getEntryGridY() {
        return entryGridY;
    }

    public void setEntryTile(int x, int y) {
        this.entryGridX = x;
        this.entryGridY = y;
    }

    public List<PlacedStructure> getStructures() {
        return Collections.unmodifiableList(structures);
    }

    public List<PlacedStructure> mutableStructures() {
        return structures;
    }

    public Map<String, Integer> getStorage() {
        return Collections.unmodifiableMap(storage);
    }

    public int getStored(String materialId) {
        if (materialId == null)
            return 0;
        Integer n = storage.get(materialId);
        return n == null ? 0 : n;
    }

    public void addStored(String materialId, int amount) {
        if (materialId == null || materialId.isEmpty() || amount <= 0)
            return;
        storage.put(materialId, getStored(materialId) + amount);
    }

    public boolean takeStored(String materialId, int amount) {
        if (materialId == null || amount <= 0)
            return false;
        int have = getStored(materialId);
        if (have < amount)
            return false;
        if (have == amount)
            storage.remove(materialId);
        else
            storage.put(materialId, have - amount);
        return true;
    }

    public SaveFileData save() {
        SaveFileData data = new SaveFileData();
        data.store("planeId", planeId);
        data.store("poiId", poiId);
        data.store("worldX", worldX);
        data.store("worldY", worldY);
        data.store("gridOriginX", gridOriginX);
        data.store("gridOriginY", gridOriginY);
        data.store("gridWidth", gridWidth);
        data.store("gridHeight", gridHeight);
        data.store("mapWidthTiles", mapWidthTiles);
        data.store("mapHeightTiles", mapHeightTiles);
        data.store("entryGridX", entryGridX);
        data.store("entryGridY", entryGridY);
        data.store("structureCount", structures.size());
        for (int i = 0; i < structures.size(); i++) {
            data.store("structure_" + i, structures.get(i).save());
        }
        List<String> matIds = new ArrayList<>(storage.keySet());
        List<Integer> matCounts = new ArrayList<>();
        for (String id : matIds)
            matCounts.add(storage.get(id));
        data.storeObject("storageIds", matIds);
        data.storeObject("storageCounts", matCounts);
        return data;
    }

    public void load(SaveFileData data) {
        structures.clear();
        storage.clear();
        if (data == null)
            return;
        planeId = data.readString("planeId");
        if (planeId == null)
            planeId = "";
        poiId = data.readString("poiId");
        if (poiId == null)
            poiId = "";
        worldX = data.readFloat("worldX");
        worldY = data.readFloat("worldY");
        gridOriginX = data.readInt("gridOriginX");
        gridOriginY = data.readInt("gridOriginY");
        gridWidth = Math.max(1, data.readInt("gridWidth"));
        gridHeight = Math.max(1, data.readInt("gridHeight"));
        if (data.containsKey("mapWidthTiles"))
            mapWidthTiles = Math.max(1, data.readInt("mapWidthTiles"));
        if (data.containsKey("mapHeightTiles"))
            mapHeightTiles = Math.max(1, data.readInt("mapHeightTiles"));
        if (data.containsKey("entryGridX"))
            entryGridX = data.readInt("entryGridX");
        if (data.containsKey("entryGridY"))
            entryGridY = data.readInt("entryGridY");
        int n = data.readInt("structureCount");
        for (int i = 0; i < n; i++) {
            SaveFileData sub = data.readSubData("structure_" + i);
            if (sub == null)
                continue;
            PlacedStructure p = new PlacedStructure();
            p.load(sub);
            structures.add(p);
        }
        @SuppressWarnings("unchecked")
        List<String> matIds = (List<String>) data.readObject("storageIds");
        @SuppressWarnings("unchecked")
        List<Integer> matCounts = (List<Integer>) data.readObject("storageCounts");
        if (matIds != null && matCounts != null) {
            int m = Math.min(matIds.size(), matCounts.size());
            for (int i = 0; i < m; i++) {
                String id = matIds.get(i);
                Integer c = matCounts.get(i);
                if (id != null && c != null && c > 0)
                    storage.put(id, c);
            }
        }
    }

    /** True when this instance has been claimed (has a POI id). */
    public boolean isClaimed() {
        return poiId != null && !poiId.isEmpty();
    }
}
