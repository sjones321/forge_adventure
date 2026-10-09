package forge.adventure.fortress;

import forge.adventure.data.FortressStructureData;
import forge.adventure.util.SaveFileData;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One player-placed structure inside a fortress instance (FT1).
 * Stores the materials/gold actually paid so demolish refunds match payment
 * (not the current catalog cost).
 */
public final class PlacedStructure implements Serializable {
    private static final long serialVersionUID = 1L;

    public String structureId = "";
    public int gridX;
    public int gridY;
    /** 0 / 90 / 180 / 270. */
    public int rotationDeg;
    /** Materials actually spent when this was built. */
    public final LinkedHashMap<String, Integer> paidMaterials = new LinkedHashMap<>();
    /** Gold actually spent when this was built. */
    public int paidGold;

    public PlacedStructure() {
    }

    public PlacedStructure(String structureId, int gridX, int gridY, int rotationDeg) {
        this.structureId = structureId != null ? structureId : "";
        this.gridX = gridX;
        this.gridY = gridY;
        this.rotationDeg = FortressStructureData.normalizeRotation(rotationDeg);
    }

    public void setPaidCost(Map<String, Integer> materials, int gold) {
        paidMaterials.clear();
        if (materials != null) {
            for (Map.Entry<String, Integer> e : materials.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0)
                    continue;
                paidMaterials.put(e.getKey(), e.getValue());
            }
        }
        paidGold = Math.max(0, gold);
    }

    public Map<String, Integer> getPaidMaterials() {
        return Collections.unmodifiableMap(paidMaterials);
    }

    public SaveFileData save() {
        SaveFileData data = new SaveFileData();
        data.store("structureId", structureId);
        data.store("gridX", gridX);
        data.store("gridY", gridY);
        data.store("rotationDeg", rotationDeg);
        data.store("paidGold", paidGold);
        List<String> ids = new ArrayList<>(paidMaterials.keySet());
        List<Integer> counts = new ArrayList<>();
        for (String id : ids)
            counts.add(paidMaterials.get(id));
        data.storeObject("paidMaterialIds", ids);
        data.storeObject("paidMaterialCounts", counts);
        return data;
    }

    public void load(SaveFileData data) {
        paidMaterials.clear();
        paidGold = 0;
        if (data == null)
            return;
        structureId = data.readString("structureId");
        if (structureId == null)
            structureId = "";
        gridX = data.readInt("gridX");
        gridY = data.readInt("gridY");
        rotationDeg = FortressStructureData.normalizeRotation(data.readInt("rotationDeg"));
        paidGold = Math.max(0, data.readInt("paidGold"));
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) data.readObject("paidMaterialIds");
        @SuppressWarnings("unchecked")
        List<Integer> counts = (List<Integer>) data.readObject("paidMaterialCounts");
        if (ids != null && counts != null) {
            int n = Math.min(ids.size(), counts.size());
            for (int i = 0; i < n; i++) {
                String id = ids.get(i);
                Integer c = counts.get(i);
                if (id != null && c != null && c > 0)
                    paidMaterials.put(id, c);
            }
        }
    }
}
