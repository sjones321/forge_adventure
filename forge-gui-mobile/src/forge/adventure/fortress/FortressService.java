package forge.adventure.fortress;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.coop.CoopHooks;
import forge.adventure.coop.CoopSessionRole;
import forge.adventure.data.ConfigData;
import forge.adventure.data.FortressStructureData;
import forge.adventure.data.FortressStructureListData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.FortressStorageHook;
import forge.adventure.player.InventoryBagType;
import forge.adventure.player.PlayerSkills;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.PointOfInterestMapSprite;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live FT1 fortress orchestration: claim site, persist per-plane, build/demolish,
 * INV1 material-only storage hook (shed required; no retrieval UI yet ⇒ overflow
 * gear is never swallowed). All public entry points are Ascendant-gated.
 *
 * <p>Co-op (simpler correct option, no protocol bump): guests are kept out of the
 * host's fortress with a clear message. Structure sync is deferred to CO4.
 */
public final class FortressService {
    public static final String BANNER_ITEM = "Fortress Banner";

    private static final FortressService INSTANCE = new FortressService();

    private FortressInstance current;
    private final FortressBuildMode buildMode = new FortressBuildMode();
    /** Optional listener so MapStage can refresh collision/sprites after place/demolish. */
    private Runnable structureChangeListener;

    public static FortressService get() {
        return INSTANCE;
    }

    private FortressService() {
    }

    public FortressInstance getCurrent() {
        return current;
    }

    public boolean hasFortress() {
        return current != null && current.isClaimed();
    }

    public FortressBuildMode getBuildMode() {
        return buildMode;
    }

    public void setStructureChangeListener(Runnable listener) {
        structureChangeListener = listener;
    }

    public void clear() {
        clear(false);
    }

    /**
     * @param warnNgPlus when true, notify that fortress storage was wiped (NG+).
     */
    public void clear(boolean warnNgPlus) {
        boolean hadStorage = current != null && !current.getStorage().isEmpty();
        buildMode.close();
        current = null;
        refreshStorageHook();
        if (warnNgPlus && hadStorage) {
            try {
                GameHUD.getInstance().addNotification(
                        "NG+: fortress storage cleared (no retrieval UI yet — materials were wiped).");
            } catch (Throwable ignored) {
            }
        }
    }

    public SaveFileData saveCurrent() {
        if (current == null || !current.isClaimed())
            return null;
        return current.save();
    }

    public void loadCurrent(SaveFileData data) {
        buildMode.close();
        if (data == null) {
            current = null;
            refreshStorageHook();
            return;
        }
        FortressInstance inst = new FortressInstance();
        inst.load(data);
        current = inst.isClaimed() ? inst : null;
        refreshStorageHook();
    }

    public void replaceCurrent(FortressInstance next) {
        buildMode.close();
        current = next;
        refreshStorageHook();
    }

    public static FortressInstance fromSave(SaveFileData data) {
        if (data == null)
            return null;
        FortressInstance inst = new FortressInstance();
        inst.load(data);
        return inst.isClaimed() ? inst : null;
    }

    public boolean guestForbidden() {
        return isGuestRole(forge.adventure.coop.CoopSession.get().getRole());
    }

    /** Pure helper for tests and co-op gates. */
    public static boolean isGuestRole(CoopSessionRole role) {
        return role == CoopSessionRole.GUEST;
    }

    public boolean canModifyWorld() {
        return !CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority();
    }

    /**
     * Guests may not enter a fortress POI (no structure sync without a protocol bump).
     */
    public boolean guestMayEnterFortress() {
        return guestMayEnterFortress(forge.adventure.coop.CoopSession.get().getRole());
    }

    public static boolean guestMayEnterFortress(CoopSessionRole role) {
        return !isGuestRole(role);
    }

    public String guestFortressDeniedMessage() {
        return "Guests cannot enter the host's fortress yet (no structure sync). "
                + "Co-op fortress visits come in CO4.";
    }

    public static boolean isFortressPoi(PointOfInterest poi) {
        return poi != null && poi.getData() != null
                && "fortress".equalsIgnoreCase(poi.getData().type);
    }

    public static boolean isInsideFortressMap() {
        try {
            if (!MapStage.getInstance().isInMap())
                return false;
            TileMapScene scene = TileMapScene.instance();
            return scene != null && isFortressPoi(scene.rootPoint);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean playerHasBanner(AdventurePlayer player) {
        return player != null && player.hasItem(BANNER_ITEM);
    }

    /**
     * Plant the Fortress Banner. Requires the banner item (console included).
     * POI is offset off the player's tile.
     */
    public String plantBanner() {
        if (!Config.ascendant())
            return FortressPlacement.message(FortressPlacement.RejectReason.NOT_ASCENDANT, null);
        if (!canModifyWorld() || guestForbidden())
            return FortressPlacement.message(FortressPlacement.RejectReason.GUEST_FORBIDDEN, cfg());

        WorldSave save = WorldSave.getCurrentSave();
        World world = save.getWorld();
        AdventurePlayer player = save.getPlayer();
        ConfigData config = cfg();

        if (!playerHasBanner(player))
            return FortressPlacement.message(FortressPlacement.RejectReason.NO_BANNER, config);

        float playerX = player.getWorldPosX();
        float playerY = player.getWorldPosY();
        Vector2 plantPos = FortressWorldHelper.poiPosAwayFromPlayer(world, playerX, playerY);
        if (FortressWorldHelper.sameTile(plantPos.x, plantPos.y, playerX, playerY, world.getTileSize()))
            return "Cannot plant the fortress on your tile.";

        int tileSize = world.getTileSize();
        int tileX = (int) (plantPos.x / tileSize);
        int tileY = (int) (plantPos.y / tileSize);

        boolean walkable = !world.isColliding(tileX, tileY);
        List<int[]> poiTiles = collectPoiTiles(world, tileSize);
        boolean far = FortressPlacement.isFarEnoughFromPois(tileX, tileY, poiTiles,
                config.fortressBannerMinDistanceTiles);
        int existing = countFortressesOnPlane(world);

        FortressPlacement.RejectReason reason = FortressPlacement.validate(
                true, false, walkable, far, existing, config);
        if (reason != FortressPlacement.RejectReason.OK)
            return FortressPlacement.message(reason, config);

        PointOfInterestData template = PointOfInterestData.getPointOfInterest(FortressInstance.POI_TEMPLATE_NAME);
        if (template == null)
            return FortressPlacement.message(FortressPlacement.RejectReason.MISSING_TEMPLATE, config);

        PointOfInterestData data = new PointOfInterestData(template);
        PointOfInterest poi = new PointOfInterest(data, plantPos, MyRandom.getRandom());
        poi.setDisplayName("Fortress");
        if (!FortressWorldHelper.plantPoi(world, poi))
            return "Could not plant fortress POI (chunk out of bounds).";

        // Consume banner before committing instance state.
        player.removeItem(BANNER_ITEM);

        FortressInstance inst = new FortressInstance();
        inst.setPlaneId(save.getCurrentPlaneId());
        inst.setPoiId(poi.getID());
        inst.setWorldPos(plantPos.x, plantPos.y);
        inst.setBuildableZone(config.fortressBuildableOriginX, config.fortressBuildableOriginY,
                config.fortressBuildableWidth, config.fortressBuildableHeight);
        current = inst;
        refreshStorageHook();

        try {
            WorldStage.getInstance().getSpriteGroup().addActor(new PointOfInterestMapSprite(poi));
        } catch (Throwable ignored) {
        }

        return FortressPlacement.message(FortressPlacement.RejectReason.OK, config);
    }

    public static List<int[]> collectPoiTiles(World world, int tileSize) {
        List<int[]> out = new ArrayList<>();
        if (world == null)
            return out;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi == null || poi.getData() == null)
                continue;
            Vector2 pos = poi.getPosition();
            out.add(new int[]{(int) (pos.x / tileSize), (int) (pos.y / tileSize)});
        }
        return out;
    }

    public static int countFortressesOnPlane(World world) {
        if (world == null)
            return 0;
        int n = 0;
        for (PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi != null && poi.getData() != null && "fortress".equalsIgnoreCase(poi.getData().type))
                n++;
        }
        return n;
    }

    public String openBuildMode() {
        if (!Config.ascendant())
            return "Fortresses require Shandalar Ascendant.";
        if (!canModifyWorld() || guestForbidden())
            return "Guests cannot build in the host's fortress.";
        if (!isInsideFortressMap())
            return "Build mode only works inside your fortress.";
        if (!hasFortress())
            return "No fortress claimed on this plane.";
        int level = Current.player().getSkills().getLevel(PlayerSkills.Skill.CONSTRUCTION);
        buildMode.open(current, level);
        return "Build mode — arrows move, R rotate, Enter place, Del demolish, Esc exit.";
    }

    public String closeBuildMode() {
        buildMode.close();
        return "Left build mode.";
    }

    public String tryPlaceAtCursor() {
        if (!buildMode.isActive())
            return "Build mode is not active.";
        if (!canModifyWorld() || guestForbidden())
            return "Guests cannot build in the host's fortress.";
        if (!isInsideFortressMap())
            return "Build mode only works inside your fortress.";
        FortressStructureData def = buildMode.selectedStructure();
        if (def == null)
            return "No structures loaded.";
        AdventurePlayer player = Current.player();
        int level = player.getSkills().getLevel(PlayerSkills.Skill.CONSTRUCTION);

        int[] playerTile = playerGridInFortress();
        java.util.Set<Long> mapCollision = mapCollisionCells();
        FortressBuildGrid.PlaceReject reject = FortressBuildGrid.canPlace(
                current, def, buildMode.getCursorX(), buildMode.getCursorY(),
                buildMode.getRotationDeg(), level,
                playerTile[0], playerTile[1],
                current.getEntryGridX(), current.getEntryGridY(),
                current.getMapWidthTiles(), current.getMapHeightTiles(),
                mapCollision);
        if (reject != FortressBuildGrid.PlaceReject.OK)
            return placeRejectMessage(reject, def);

        Map<String, Integer> paid = FortressBuildGrid.catalogCost(def);
        String costBlock = checkAndPayCost(player, def, paid);
        if (costBlock != null)
            return costBlock;

        if (!FortressBuildGrid.place(current, def, buildMode.getCursorX(), buildMode.getCursorY(),
                buildMode.getRotationDeg(), level, paid, def.gold,
                playerTile[0], playerTile[1],
                current.getEntryGridX(), current.getEntryGridY(),
                current.getMapWidthTiles(), current.getMapHeightTiles(),
                mapCollision))
            return "Could not place structure.";

        // XP only on successful place — never on demolish.
        if (def.xp > 0)
            player.getSkills().addXp(PlayerSkills.Skill.CONSTRUCTION, def.xp);

        refreshStorageHook();
        notifyStructureChanged();
        return "Built " + def.displayName() + ".";
    }

    public String tryDemolishAtCursor() {
        if (!buildMode.isActive())
            return "Build mode is not active.";
        if (!canModifyWorld() || guestForbidden())
            return "Guests cannot demolish in the host's fortress.";
        if (!isInsideFortressMap())
            return "Build mode only works inside your fortress.";
        int idx = FortressBuildGrid.structureAt(current, buildMode.getCursorX(), buildMode.getCursorY());
        if (idx < 0)
            return "No structure here.";
        // Demolish grants no XP; refund from paid snapshot.
        Map<String, Integer> refund = FortressBuildGrid.demolish(current, idx, cfg());
        AdventurePlayer player = Current.player();
        for (Map.Entry<String, Integer> e : refund.entrySet())
            player.addMaterial(e.getKey(), e.getValue());
        refreshStorageHook();
        notifyStructureChanged();
        return "Demolished (refund " + (int) cfg().fortressDemolishRefundPercent
                + "% of paid materials).";
    }

    private void notifyStructureChanged() {
        if (structureChangeListener != null) {
            try {
                structureChangeListener.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /** Player grid tile inside the fortress map, or MIN_VALUE when unavailable. */
    public int[] playerGridInFortress() {
        try {
            MapStage stage = MapStage.getInstance();
            float x = stage.getPlayerSprite().getX();
            float y = stage.getPlayerSprite().getY();
            // Use the loaded map's real tile size — never assume 16px.
            float tw = stage.getMapTileWidth();
            float th = stage.getMapTileHeight();
            return playerGridInFortress(tw, th, x, y);
        } catch (Throwable t) {
            return new int[]{Integer.MIN_VALUE, Integer.MIN_VALUE};
        }
    }

    public int[] playerGridInFortress(float tileW, float tileH, float playerX, float playerY) {
        return new int[]{(int) (playerX / Math.max(1f, tileW)), (int) (playerY / Math.max(1f, tileH))};
    }

    /** Map collision layer cells for path-to-entry BFS (empty when not in a map). */
    public java.util.Set<Long> mapCollisionCells() {
        try {
            return MapStage.getInstance().fortressPathBlockedCells();
        } catch (Throwable t) {
            return java.util.Collections.emptySet();
        }
    }

    private String checkAndPayCost(AdventurePlayer player, FortressStructureData def,
                                   Map<String, Integer> paidOut) {
        if (def.gold > 0 && player.getGold() < def.gold)
            return "Need " + def.gold + " gold.";
        if (def.materials != null) {
            for (ObjectMap.Entry<String, Integer> e : def.materials) {
                if (e.key == null || e.value == null || e.value <= 0)
                    continue;
                if (player.getMaterial(e.key) < e.value)
                    return "Need more " + e.key + " (" + e.value + ").";
            }
        }
        LinkedHashMap<String, Integer> paid = new LinkedHashMap<>();
        if (def.gold > 0)
            player.takeGold(def.gold);
        if (def.materials != null) {
            for (ObjectMap.Entry<String, Integer> e : def.materials) {
                if (e.key == null || e.value == null || e.value <= 0)
                    continue;
                player.takeMaterial(e.key, e.value);
                paid.put(e.key, e.value);
            }
        }
        if (paidOut != null) {
            paidOut.clear();
            paidOut.putAll(paid);
        }
        return null;
    }

    private static String placeRejectMessage(FortressBuildGrid.PlaceReject reject, FortressStructureData def) {
        switch (reject) {
            case OUT_OF_BOUNDS:
                return "Outside the buildable zone.";
            case COLLISION:
                return "Footprint blocked.";
            case LEVEL_TOO_LOW:
                return "Construction level " + def.constructionLevel + " required.";
            case ON_PLAYER:
                return "Cannot build on your tile.";
            case BLOCKS_PATH_TO_ENTRY:
                return "That would block the path to the fortress entrance.";
            case UNKNOWN_STRUCTURE:
                return "Unknown structure.";
            default:
                return "Cannot place.";
        }
    }

    /** Attach materials-only hook when a shed exists; otherwise detach. Call after every build/demolish/load. */
    public void refreshStorageHook() {
        try {
            AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
            if (player == null || player.getBags() == null)
                return;
            if (current == null || !current.isClaimed() || !hasStorageStructure(current)) {
                player.getBags().setFortressStorage(FortressStorageHook.NONE);
                return;
            }
            final FortressInstance inst = current;
            player.getBags().setFortressStorage((bag, key, amount) -> {
                // Materials only — InventoryBags also gates by OverflowEntry.Kind.MATERIAL.
                if (key == null || amount <= 0)
                    return false;
                if (bag != InventoryBagType.MATERIALS && bag != InventoryBagType.OVERFLOW)
                    return false;
                inst.addStored(key, amount);
                return true;
            });
        } catch (Throwable ignored) {
        }
    }

    public static boolean hasStorageStructure(FortressInstance inst) {
        if (inst == null)
            return false;
        for (PlacedStructure p : inst.getStructures()) {
            FortressStructureData def = FortressStructureListData.get(p.structureId);
            if (def != null && def.isStorage())
                return true;
        }
        return false;
    }

    private static ConfigData cfg() {
        return Config.instance().getConfigData();
    }
}
