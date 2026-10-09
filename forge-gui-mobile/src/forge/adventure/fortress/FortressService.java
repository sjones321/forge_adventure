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
import forge.adventure.stage.PointOfInterestMapSprite;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live FT1 fortress orchestration: claim site, persist per-plane, build/demolish,
 * INV1 storage hook. All public entry points are Ascendant-gated.
 */
public final class FortressService {
    private static final FortressService INSTANCE = new FortressService();

    private FortressInstance current;
    private final FortressBuildMode buildMode = new FortressBuildMode();

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

    public void clear() {
        buildMode.close();
        current = null;
        detachStorageHook();
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
            detachStorageHook();
            return;
        }
        FortressInstance inst = new FortressInstance();
        inst.load(data);
        current = inst.isClaimed() ? inst : null;
        attachStorageHook();
    }

    /** Swap live fortress when switching planes (null = target plane has none). */
    public void replaceCurrent(FortressInstance next) {
        buildMode.close();
        current = next;
        attachStorageHook();
    }

    public static FortressInstance fromSave(SaveFileData data) {
        if (data == null)
            return null;
        FortressInstance inst = new FortressInstance();
        inst.load(data);
        return inst.isClaimed() ? inst : null;
    }

    public boolean guestForbidden() {
        return forge.adventure.coop.CoopSession.get().getRole() == CoopSessionRole.GUEST;
    }

    public boolean canModifyWorld() {
        // Solo (no READY session) may always modify; in co-op only the host.
        return !CoopHooks.isOverworldReady() || CoopHooks.isWorldAuthority();
    }

    /**
     * Plant the Fortress Banner at the player's overworld tile.
     * @return status message for HUD / console
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

        float px = player.getWorldPosX();
        float py = player.getWorldPosY();
        int tileSize = world.getTileSize();
        int tileX = (int) (px / tileSize);
        int tileY = (int) (py / tileSize);

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
        PointOfInterest poi = new PointOfInterest(data, new Vector2(px, py), MyRandom.getRandom());
        poi.setDisplayName("Fortress");
        world.addPointOfInterest(poi);

        FortressInstance inst = new FortressInstance();
        inst.setPlaneId(save.getCurrentPlaneId());
        inst.setPoiId(poi.getID());
        inst.setWorldPos(px, py);
        inst.setBuildableZone(config.fortressBuildableOriginX, config.fortressBuildableOriginY,
                config.fortressBuildableWidth, config.fortressBuildableHeight);
        current = inst;
        attachStorageHook();

        // Refresh overworld sprite for the current chunk.
        try {
            WorldStage.getInstance().getSpriteGroup().addActor(new PointOfInterestMapSprite(poi));
        } catch (Throwable ignored) {
            // Headless / stage not ready.
        }

        // Consume one banner if the player has the item.
        consumeBanner(player);

        return FortressPlacement.message(FortressPlacement.RejectReason.OK, config);
    }

    private void consumeBanner(AdventurePlayer player) {
        try {
            player.removeItem("Fortress Banner");
        } catch (Throwable ignored) {
        }
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

    /**
     * Attempt to place the currently selected structure at the cursor.
     */
    public String tryPlaceAtCursor() {
        if (!buildMode.isActive())
            return "Build mode is not active.";
        if (!canModifyWorld() || guestForbidden())
            return "Guests cannot build in the host's fortress.";
        FortressStructureData def = buildMode.selectedStructure();
        if (def == null)
            return "No structures loaded.";
        AdventurePlayer player = Current.player();
        int level = player.getSkills().getLevel(PlayerSkills.Skill.CONSTRUCTION);
        FortressBuildGrid.PlaceReject reject = FortressBuildGrid.canPlace(
                current, def, buildMode.getCursorX(), buildMode.getCursorY(),
                buildMode.getRotationDeg(), level);
        if (reject != FortressBuildGrid.PlaceReject.OK)
            return placeRejectMessage(reject, def);

        String costBlock = checkAndPayCost(player, def);
        if (costBlock != null)
            return costBlock;

        if (!FortressBuildGrid.place(current, def, buildMode.getCursorX(), buildMode.getCursorY(),
                buildMode.getRotationDeg(), level))
            return "Could not place structure.";

        if (def.xp > 0)
            player.getSkills().addXp(PlayerSkills.Skill.CONSTRUCTION, def.xp);
        return "Built " + def.displayName() + ".";
    }

    public String tryDemolishAtCursor() {
        if (!buildMode.isActive())
            return "Build mode is not active.";
        if (!canModifyWorld() || guestForbidden())
            return "Guests cannot demolish in the host's fortress.";
        int idx = FortressBuildGrid.structureAt(current, buildMode.getCursorX(), buildMode.getCursorY());
        if (idx < 0)
            return "No structure here.";
        Map<String, Integer> refund = FortressBuildGrid.demolish(current, idx, cfg());
        AdventurePlayer player = Current.player();
        for (Map.Entry<String, Integer> e : refund.entrySet())
            player.addMaterial(e.getKey(), e.getValue());
        return "Demolished (refund " + (int) cfg().fortressDemolishRefundPercent + "%).";
    }

    private String checkAndPayCost(AdventurePlayer player, FortressStructureData def) {
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
        if (def.gold > 0)
            player.takeGold(def.gold);
        if (def.materials != null) {
            for (ObjectMap.Entry<String, Integer> e : def.materials) {
                if (e.key == null || e.value == null || e.value <= 0)
                    continue;
                player.takeMaterial(e.key, e.value);
            }
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
            case UNKNOWN_STRUCTURE:
                return "Unknown structure.";
            default:
                return "Cannot place.";
        }
    }

    private void attachStorageHook() {
        try {
            AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
            if (player == null || player.getBags() == null)
                return;
            if (current == null || !current.isClaimed() || !hasStorageStructure(current)) {
                player.getBags().setFortressStorage(FortressStorageHook.NONE);
                return;
            }
            final FortressInstance inst = current;
            player.getBags().setFortressStorage(new FortressStorageHook() {
                @Override
                public boolean storeOverflow(InventoryBagType bag, String key, int amount) {
                    if (key == null || amount <= 0)
                        return false;
                    // Materials only for FT1; gear/boosters wait for LG1 logistics.
                    if (bag != InventoryBagType.MATERIALS && bag != InventoryBagType.OVERFLOW)
                        return false;
                    inst.addStored(key, amount);
                    return true;
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private void detachStorageHook() {
        try {
            AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
            if (player != null && player.getBags() != null)
                player.getBags().setFortressStorage(FortressStorageHook.NONE);
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
