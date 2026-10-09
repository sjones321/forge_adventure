package forge.adventure.fortress;

import forge.adventure.data.FortressStructureData;
import forge.adventure.util.SaveFileData;

import java.io.Serializable;

/**
 * One player-placed structure inside a fortress instance (FT1).
 */
public final class PlacedStructure implements Serializable {
    private static final long serialVersionUID = 1L;

    public String structureId = "";
    public int gridX;
    public int gridY;
    /** 0 / 90 / 180 / 270. */
    public int rotationDeg;

    public PlacedStructure() {
    }

    public PlacedStructure(String structureId, int gridX, int gridY, int rotationDeg) {
        this.structureId = structureId != null ? structureId : "";
        this.gridX = gridX;
        this.gridY = gridY;
        this.rotationDeg = FortressStructureData.normalizeRotation(rotationDeg);
    }

    public SaveFileData save() {
        SaveFileData data = new SaveFileData();
        data.store("structureId", structureId);
        data.store("gridX", gridX);
        data.store("gridY", gridY);
        data.store("rotationDeg", rotationDeg);
        return data;
    }

    public void load(SaveFileData data) {
        if (data == null)
            return;
        structureId = data.readString("structureId");
        if (structureId == null)
            structureId = "";
        gridX = data.readInt("gridX");
        gridY = data.readInt("gridY");
        rotationDeg = FortressStructureData.normalizeRotation(data.readInt("rotationDeg"));
    }
}
