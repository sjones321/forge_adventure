package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.io.Serializable;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains the information for the point of interests like towns and dungeons
 */
public class PointOfInterestData implements Serializable {
    public String name;
    public String type;
    public int count;
    public String spriteAtlas;
    public String sprite;
    public String map;
    public float radiusFactor;
    public float offsetX=0f;
    public float offsetY=0f;
    public boolean active = true;
    public String[] questTags = new String[0];
    public DialogData.ActionData.QuestFlag[] questFlagsToActivate = new DialogData.ActionData.QuestFlag[0];
    public String displayName;
    /**
     * MV2: when set, portals inside this POI's map travel to this plane id
     * (e.g. {@code home}, {@code set_dmu}). Optional in JSON.
     */
    public String targetPlane;




    private static Array<PointOfInterestData> pointOfInterestList;
    public static Array<PointOfInterestData> getAllPointOfInterest() {
        if (pointOfInterestList == null) {
            try {
                Json json = new Json();
                FileHandle handle = Config.instance().getFile(Paths.POINTS_OF_INTEREST);
                if (handle != null && handle.exists()) {
                    pointOfInterestList = json.fromJson(Array.class, PointOfInterestData.class, handle);
                }
            } catch (Throwable t) {
                // Headless tests / missing Config — keep a mutable runtime list.
                pointOfInterestList = null;
            }
            if (pointOfInterestList == null) {
                pointOfInterestList = new Array<>();
            }
        }
        return pointOfInterestList;
    }

    /** MV2: register a runtime POI definition (Planar Gate) if missing from JSON. */
    public static void registerRuntime(PointOfInterestData data) {
        if (data == null || data.name == null) {
            return;
        }
        Array<PointOfInterestData> all = getAllPointOfInterest();
        if (all == null) {
            pointOfInterestList = new Array<>();
            all = pointOfInterestList;
        }
        for (int i = 0; i < all.size; i++) {
            PointOfInterestData existing = all.get(i);
            if (existing != null && data.name.equals(existing.name)) {
                all.set(i, data);
                return;
            }
        }
        all.add(data);
    }

    /** Test helper: reset the cached POI list (headless registration). */
    public static void clearRuntimeCacheForTests() {
        pointOfInterestList = null;
    }
    public static PointOfInterestData getPointOfInterest(String name) {
        if (name == null) {
            return null;
        }
        Array<PointOfInterestData> all = getAllPointOfInterest();
        if (all == null) {
            return null;
        }
        for (PointOfInterestData data : new Array.ArrayIterator<>(all)) {
            if (data != null && name.equals(data.name)) {
                return data;
            }
        }
        return null;
    }
    public PointOfInterestData()
    {

    }
    public PointOfInterestData(PointOfInterestData other)
    {
        name=other.name;
        type=other.type;
        count=other.count;
        spriteAtlas=other.spriteAtlas;
        sprite=other.sprite;
        map=other.map;
        radiusFactor=other.radiusFactor;
        offsetX=other.offsetX;
        offsetY=other.offsetY;
        active=other.active;
        questTags = other.questTags.clone();
        displayName= other.displayName;
        questFlagsToActivate = other.questFlagsToActivate;
        targetPlane = other.targetPlane;
    }

    public String getDisplayName() {
        if (displayName == null || displayName.isEmpty()) {
            return name!=null?name:"";
        }
        return displayName;
    }
}
