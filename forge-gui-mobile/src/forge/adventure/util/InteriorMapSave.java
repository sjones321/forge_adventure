package forge.adventure.util;

import forge.adventure.character.PlayerSprite;
import forge.adventure.coop.CoopSession;
import forge.adventure.data.ConfigData;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.Scene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.MapStage;
import forge.adventure.world.WorldSave;

/**
 * SV1: Ascendant save/load of the player's position inside a POI map (dungeon/town),
 * plus the shared pre-duel autosave hook.
 *
 * <p>Old saves without the interior keys load on the world map as before.
 * Stock modes never write or restore these fields.
 */
public final class InteriorMapSave {
    public static final String KEY_POI_ID = "interiorPoiId";
    public static final String KEY_MAP_PATH = "interiorMapPath";
    public static final String KEY_ENTRANCE_ID = "interiorEntranceId";
    public static final String KEY_POS_X = "interiorPosX";
    public static final String KEY_POS_Y = "interiorPosY";

    private static String pendingPoiId;
    private static String pendingMapPath;
    private static int pendingEntranceId;
    private static float pendingPosX;
    private static float pendingPosY;
    private static boolean pending;

    private InteriorMapSave() {
    }

    public static boolean saveAnywhereEnabled() {
        if (!Config.ascendant()) {
            return false;
        }
        ConfigData cfg = Config.instance().getConfigData();
        return cfg == null || cfg.sv1SaveAnywhere;
    }

    public static boolean autosaveBeforeFightEnabled() {
        if (!Config.ascendant()) {
            return false;
        }
        ConfigData cfg = Config.instance().getConfigData();
        return cfg == null || cfg.sv1AutosaveBeforeFight;
    }

    /**
     * Manual save is allowed on Ascendant everywhere except during a dialog
     * (and shops/duels, which are other scenes — the pause menu is not open there).
     * Stock modes stay world-map only.
     */
    public static boolean allowsManualSave() {
        MapStage map = MapStage.instance;
        if (!saveAnywhereEnabled()) {
            return map == null || !map.isInMap();
        }
        return map == null || !map.isDialogOnlyInput();
    }

    /** Persist interior location into the player {@link SaveFileData} when in a map. */
    public static void write(SaveFileData data) {
        if (data == null || !saveAnywhereEnabled()) {
            return;
        }
        // Use the live instance only — never construct MapStage from a headless player.save().
        MapStage map = MapStage.instance;
        if (map == null || !map.isInMap()) {
            return;
        }
        TileMapScene scene = TileMapScene.instance();
        if (scene.rootPoint == null) {
            return;
        }
        PlayerSprite player = map.getPlayerSprite();
        if (player == null) {
            return;
        }
        data.store(KEY_POI_ID, scene.rootPoint.getID());
        String mapPath = map.getLoadedMapPath();
        if (mapPath == null || mapPath.isEmpty()) {
            mapPath = scene.getCurrentMapPath();
        }
        data.store(KEY_MAP_PATH, mapPath != null ? mapPath : "");
        data.store(KEY_ENTRANCE_ID, map.getLastSpawnTargetId());
        data.store(KEY_POS_X, player.getX());
        data.store(KEY_POS_Y, player.getY());
    }

    /** Read interior keys after player load; missing keys mean world-map spawn. */
    public static void readPending(SaveFileData data) {
        clearPending();
        if (data == null || !data.containsKey(KEY_POI_ID)) {
            return;
        }
        String poiId = data.readString(KEY_POI_ID);
        if (poiId == null || poiId.isEmpty()) {
            return;
        }
        pendingPoiId = poiId;
        pendingMapPath = data.containsKey(KEY_MAP_PATH) ? data.readString(KEY_MAP_PATH) : "";
        pendingEntranceId = data.containsKey(KEY_ENTRANCE_ID) ? data.readInt(KEY_ENTRANCE_ID) : 0;
        pendingPosX = data.containsKey(KEY_POS_X) ? data.readFloat(KEY_POS_X) : Float.NaN;
        pendingPosY = data.containsKey(KEY_POS_Y) ? data.readFloat(KEY_POS_Y) : Float.NaN;
        pending = true;
    }

    public static void clearPending() {
        pending = false;
        pendingPoiId = null;
        pendingMapPath = null;
        pendingEntranceId = 0;
        pendingPosX = Float.NaN;
        pendingPosY = Float.NaN;
    }

    public static boolean hasPending() {
        return pending;
    }

    /**
     * After a successful {@link WorldSave#load}, return the scene to enter:
     * the restored map when interior fields are present, otherwise the overworld.
     */
    public static Scene sceneAfterLoad() {
        if (prepareRestoredMap()) {
            return TileMapScene.instance();
        }
        return GameScene.instance();
    }

    /**
     * Load the saved POI/map into {@link TileMapScene} and place the player.
     * Does not switch scenes — caller should {@code Forge.switchScene} the result of
     * {@link #sceneAfterLoad()}. Quest ENTERPOI fires once from {@link TileMapScene#enter()}.
     *
     * @return true when the map was prepared
     */
    public static boolean prepareRestoredMap() {
        if (!pending || !saveAnywhereEnabled()) {
            clearPending();
            return false;
        }
        PointOfInterest poi = findPoiById(pendingPoiId);
        if (poi == null || poi.getData() == null) {
            clearPending();
            return false;
        }
        String mapPath = pendingMapPath;
        int entranceId = pendingEntranceId;
        float posX = pendingPosX;
        float posY = pendingPosY;
        clearPending();

        TileMapScene scene = TileMapScene.instance();
        scene.loadForRestore(poi, mapPath, entranceId);
        MapStage.getInstance().tryRestorePlayerPosition(posX, posY);
        return true;
    }

    /**
     * SV1 autosave into the rotating auto slot right before a duel starts.
     * Ascendant only; skipped during any active co-op session. Does not change
     * the co-op guest/host save path itself.
     */
    public static void maybeAutosaveBeforeDuel() {
        if (!autosaveBeforeFightEnabled()) {
            return;
        }
        if (CoopSession.get().isActive()) {
            return;
        }
        WorldSave current = WorldSave.getCurrentSave();
        if (current == null) {
            return;
        }
        current.autoSave();
    }

    static PointOfInterest findPoiById(String poiId) {
        if (poiId == null || poiId.isEmpty()) {
            return null;
        }
        WorldSave save = WorldSave.getCurrentSave();
        if (save == null || save.getWorld() == null) {
            return null;
        }
        for (PointOfInterest poi : save.getWorld().getAllPointOfInterest()) {
            if (poi != null && poiId.equals(poi.getID())) {
                return poi;
            }
        }
        return null;
    }

    /** Test helper: expose pending POI id after {@link #readPending}. */
    public static String pendingPoiIdForTest() {
        return pendingPoiId;
    }
}
