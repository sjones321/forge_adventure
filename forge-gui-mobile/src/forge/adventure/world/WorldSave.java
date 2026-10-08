package forge.adventure.world;

import com.badlogic.gdx.utils.TimeUtils;
import forge.Forge;
import com.badlogic.gdx.Gdx;
import forge.OverlayText;
import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.RewardData;
import forge.adventure.player.StandardWindow;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.MapViewScene;
import forge.adventure.scene.SaveLoadScene;
import forge.adventure.stage.PointOfInterestMapSprite;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.*;
import forge.card.CardEdition;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.gamemodes.limited.SealedDeckBuilder;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.Aggregates;

import java.io.*;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Represents everything that will be saved, like the player and the world.
 *
 * <p>Ascendant MV1: one save holds a home plane plus optional set planes.
 * Only the current plane's {@link World} / stage / POI changes are live;
 * other planes stay serialized in {@link MultiverseState}.
 */
public class WorldSave {

    static final public int AUTO_SAVE_SLOT = -1;
    static final public int QUICK_SAVE_SLOT = -2;
    static final public int INVALID_SAVE_SLOT = -3;
    static final WorldSave currentSave = new WorldSave();
    public WorldSaveHeader header = new WorldSaveHeader();
    private final AdventurePlayer player = new AdventurePlayer();
    private final World world = new World();
    private final PointOfInterestChanges.Map pointOfInterestChanges = new PointOfInterestChanges.Map();
    private final MultiverseState multiverse = new MultiverseState();


    private final SignalList onLoadList = new SignalList();
    private static long lastPreviewTimestamp = 0L;
    private static final long COOLDOWN_WINDOW_MS = 800L;
    private static boolean firstCapture = true;

    public final World getWorld() {
        return world;
    }

    public AdventurePlayer getPlayer() {
        return player;
    }

    public MultiverseState getMultiverse() {
        return multiverse;
    }

    /** MV1 current plane instance id ({@link PlaneMeta#HOME_ID} for legacy / home). */
    public String getCurrentPlaneId() {
        return multiverse.getCurrentPlaneId();
    }

    public void onLoad(Runnable run) {
        onLoadList.add(run);
    }

    public PointOfInterestChanges getPointOfInterestChanges(String id) {
        if (id == null) { // fallback
            return new PointOfInterestChanges();
        }

        PointOfInterestChanges changes = pointOfInterestChanges.get(id);
        if (changes == null) {
            changes = new PointOfInterestChanges();
            pointOfInterestChanges.put(id, changes);
        }

        return changes;
    }

    static public boolean load(int currentSlot) {
        JSONStringLoader.clearCache();
        CardUtil.clearPriceCache();
        Forge.getLocalizer().loadAdventureBundle(Config.instance().getPlanePath(Config.instance().getSettingData().plane) + "languages/");

        Forge.invokeWorldSave = true; // This is for dispose method check
        String fileName = WorldSave.getSaveFile(currentSlot);
        if (!new File(fileName).exists())
            return false;
        new File(getSaveDir()).mkdirs();
        try {
            try (FileInputStream fos = new FileInputStream(fileName);
                 InflaterInputStream inf = new InflaterInputStream(fos);
                 ObjectInputStream oos = new ObjectInputStream(inf)) {
                currentSave.header = (WorldSaveHeader) oos.readObject();
                SaveFileData mainData = (SaveFileData) oos.readObject();
                currentSave.player.load(mainData.readSubData("player"));
                GamePlayerUtil.getGuiPlayer().setName(currentSave.player.getName());
                try {
                    currentSave.world.load(mainData.readSubData("world"));
                    currentSave.pointOfInterestChanges.load(mainData.readSubData("pointOfInterestChanges"));
                    WorldStage.getInstance().load(mainData.readSubData("worldStage"));

                } catch (Exception e) {
                    System.err.println("Generating New World");
                    if (!currentSave.world.generateNew(0))
                        return false;
                }

                // MV1: multi-plane registry, or wrap legacy single-world saves as home.
                if (Config.ascendant()) {
                    SaveFileData multi = mainData.readSubData("multiverse");
                    if (multi == null || !currentSave.multiverse.loadRegistry(multi)) {
                        currentSave.multiverse.migrateLegacyHome(
                                currentSave.world.getSeed(),
                                currentSave.player.getWorldPosX(),
                                currentSave.player.getWorldPosY());
                    } else {
                        currentSave.multiverse.updateCurrentSeed(currentSave.world.getSeed());
                        currentSave.multiverse.rememberCurrentPosition(
                                currentSave.player.getWorldPosX(),
                                currentSave.player.getWorldPosY());
                    }
                } else {
                    currentSave.multiverse.initHomeFromLive(
                            currentSave.world.getSeed(),
                            currentSave.player.getWorldPosX(),
                            currentSave.player.getWorldPosY());
                }

                currentSave.onLoadList.emit();

            }
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    public static boolean isSafeFile(String name) {
        return filenameToSlot(name) != INVALID_SAVE_SLOT;
    }

    static public int filenameToSlot(String name) {
        if (name.equals("auto_save.sav"))
            return AUTO_SAVE_SLOT;
        if (name.equals("quick_save.sav"))
            return QUICK_SAVE_SLOT;
        if (!name.contains("_") || !name.endsWith(".sav"))
            return INVALID_SAVE_SLOT;
        return Integer.parseInt(name.split("_")[0]);
    }

    static public String filename(int slot) {
        if (slot == AUTO_SAVE_SLOT)
            return "auto_save.sav";
        if (slot == QUICK_SAVE_SLOT)
            return "quick_save.sav";
        return slot + "_save_slot.sav";
    }

    public static String getSaveDir() {
        return ForgeConstants.USER_ADVENTURE_DIR + Config.instance().getPlane();
    }

    public static String getSaveFile(int slot) {
        return ForgeConstants.USER_ADVENTURE_DIR + Config.instance().getPlane() + File.separator + filename(slot);
    }

    public static WorldSave getCurrentSave() {
        return currentSave;
    }

    public static WorldSave generateNewWorld(String name, boolean male, int race, int avatarIndex, ColorSet startingColorIdentity, DifficultyData diff, AdventureModes mode, int customDeckIndex, CardEdition starterEdition, long seed) {
        Forge.getLocalizer().loadAdventureBundle(Config.instance().getPlanePath(Config.instance().getSettingData().plane) + "languages/");
        currentSave.world.generateNew(seed);
        currentSave.pointOfInterestChanges.clear();
        boolean chaos = mode == AdventureModes.Chaos;
        boolean custom = mode == AdventureModes.Custom;

        if (mode == AdventureModes.Sealed) {
            createSealedStart(name, male, race, avatarIndex, diff, starterEdition, customDeckIndex);
        } else {
            Deck starterDeck = Config.instance().starterDeck(startingColorIdentity, diff, mode, customDeckIndex, starterEdition);
            currentSave.player.create(name, starterDeck, male, race, avatarIndex, chaos, custom, diff, mode);
        }

        currentSave.player.setWorldPosY((int) (currentSave.world.getData().playerStartPosY * currentSave.world.getData().height * currentSave.world.getTileSize()));
        currentSave.player.setWorldPosX((int) (currentSave.world.getData().playerStartPosX * currentSave.world.getData().width * currentSave.world.getTileSize()));
        if (Config.ascendant()) {
            currentSave.multiverse.initHomeFromLive(
                    currentSave.world.getSeed(),
                    currentSave.player.getWorldPosX(),
                    currentSave.player.getWorldPosY());
        }
        currentSave.onLoadList.emit();
        return currentSave;
    }

    /**
     * Sealed start: boosters of the chosen starter set and of the chosen core set. Some of each are
     * opened into an auto-built deck (every opened card goes to the collection); the rest stay
     * unopened in the inventory, along with some bonus gold. The two sets form the starting
     * Standard window (core set oldest, so it rotates out first).
     */
    private static void createSealedStart(String name, boolean male, int race, int avatarIndex, DifficultyData diff, CardEdition starterEdition, int coreIndex) {
        ConfigData config = Config.instance().getConfigData();
        String setCode = sealedSetCode(starterEdition);
        String coreCode = coreSetCode(coreIndex, setCode);
        int totalPacks = Math.max(1, config.sealedStartPacks);
        int openedPacks = Math.max(1, Math.min(config.sealedStartOpenedPacks, totalPacks));

        List<PaperCard> pool = new ArrayList<>();
        List<Deck> unopened = new ArrayList<>();
        for (String code : coreCode == null ? List.of(setCode) : List.of(coreCode, setCode)) {
            for (int i = 0; i < totalPacks; i++) {
                Deck booster = StandardWindow.CORE_COLLECTION.equals(code)
                        ? StandardWindow.generateCoreCollectionBooster()
                        : AdventureEventController.instance().generateBooster(code);
                if (i < openedPacks)
                    pool.addAll(booster.getMain().toFlatList());
                else
                    unopened.add(booster);
            }
        }

        Deck starterDeck = new SealedDeckBuilder(pool).buildDeck(setCode);
        starterDeck.setName(FModel.getMagicDb().getEditions().get(setCode).getName() + " Sealed");
        currentSave.player.create(name, starterDeck, male, race, avatarIndex, false, false, diff, AdventureModes.Sealed);
        currentSave.player.getStandardWindow().init(coreCode == null ? List.of(setCode) : List.of(coreCode, setCode));
        RewardData.invalidateCardPool();

        // create() already put the deck's cards in the collection; add the opened cards that didn't make the deck
        List<PaperCard> leftovers = new ArrayList<>(pool);
        for (PaperCard card : starterDeck.getAllCardsInASinglePool(true, true).toFlatList())
            leftovers.remove(card);
        for (PaperCard card : leftovers)
            currentSave.player.addCard(card);
        for (Deck booster : unopened)
            currentSave.player.addBooster(booster);
        currentSave.player.giveGold(config.sealedStartBonusGold);
        // The starting pool shouldn't count as Collecting XP; every character starts at level 1
        currentSave.player.getSkills().clear();
    }

    /** The core set picked on the New Game screen (if it has boosters and differs from the starter set). */
    private static String coreSetCode(int index, String starterCode) {
        String[] cores = Config.instance().getConfigData().coreSets;
        if (cores == null || cores.length == 0)
            return null;
        String code = cores[Math.max(0, Math.min(index, cores.length - 1))];
        if (StandardWindow.CORE_COLLECTION.equals(code))
            return code;
        if (code.equals(starterCode) || AdventureOverrides.instance().getBoosterTemplate(code) == null)
            return null;
        return code;
    }

    /** The chosen starter set if it has boosters, otherwise a random starter set that does. */
    private static String sealedSetCode(CardEdition starterEdition) {
        if (starterEdition != null && AdventureOverrides.instance().getBoosterTemplate(starterEdition.getCode()) != null)
            return starterEdition.getCode();
        List<String> candidates = new ArrayList<>();
        for (String code : Config.instance().starterEditions()) {
            if (AdventureOverrides.instance().getBoosterTemplate(code) != null)
                candidates.add(code);
        }
        return candidates.isEmpty() ? "JMP" : Aggregates.random(candidates);
    }

    public boolean autoSave() {
        if (forge.adventure.coop.CoopSession.get().blocksLocalWorldSave()) {
            return false; // Guest co-op: never write host world into local slots.
        }
        return save("auto save" + SaveLoadScene.instance().getSaveFileSuffix(), AUTO_SAVE_SLOT);
    }

    public boolean quickSave() {
        if (forge.adventure.coop.CoopSession.get().blocksLocalWorldSave()) {
            return false;
        }
        return save("quick save" + SaveLoadScene.instance().getSaveFileSuffix(), QUICK_SAVE_SLOT);
    }

    public boolean quickLoad() {
        return load(QUICK_SAVE_SLOT);
    }

    public boolean save(String text, int currentSlot) {
        if (forge.adventure.coop.CoopSession.get().blocksLocalWorldSave()) {
            System.err.println("Co-op guest: refusing to write WorldSave slot " + currentSlot);
            return false;
        }
        header.name = text;
        CollectionExporter.export(currentSave.player); // collection + decks for external deck builders

        String fileName = WorldSave.getSaveFile(currentSlot);
        String oldFileName = fileName.replace(".sav", ".old");
        new File(getSaveDir()).mkdirs();
        File currentFile = new File(fileName);
        File backupFile = new File(oldFileName);
        if (currentFile.exists())
            currentFile.renameTo(backupFile);

        try {
            try (FileOutputStream fos = new FileOutputStream(fileName);
                 DeflaterOutputStream def = new DeflaterOutputStream(fos);
                 ObjectOutputStream oos = new ObjectOutputStream(def)) {
                currentSave.multiverse.rememberCurrentPosition(
                        currentSave.player.getWorldPosX(), currentSave.player.getWorldPosY());
                currentSave.multiverse.updateCurrentSeed(currentSave.world.getSeed());

                SaveFileData player = currentSave.player.save();
                SaveFileData world = currentSave.world.save();
                SaveFileData worldStage = WorldStage.getInstance().save();
                SaveFileData poiChanges = currentSave.pointOfInterestChanges.save();
                SaveFileData multi = Config.ascendant() ? currentSave.multiverse.saveRegistry() : null;

                String message = getExceptionMessage(player, world, worldStage, poiChanges);
                if (multi != null) {
                    message = message + getExceptionMessage(multi);
                }
                if (!message.isEmpty()) {
                    oos.close();
                    fos.close();
                    restoreBackup(oldFileName, fileName);
                    finish(message);
                    return true;
                }

                SaveFileData mainData = new SaveFileData();
                mainData.store("player", player);
                mainData.store("world", world);
                mainData.store("worldStage", worldStage);
                mainData.store("pointOfInterestChanges", poiChanges);
                if (multi != null) {
                    mainData.store("multiverse", multi);
                    mainData.store("currentPlaneId", currentSave.multiverse.getCurrentPlaneId());
                }

                if (mainData.readString("IOException") != null) {
                    oos.close();
                    fos.close();
                    restoreBackup(oldFileName, fileName);
                    finish("Please check forge.log for errors.");
                    return true;
                }

                header.saveDate = new Date();
                oos.writeObject(header);
                oos.writeObject(mainData);
            }

        } catch (IOException e) {
            restoreBackup(oldFileName, fileName);
            finish("Please check forge.log for errors.");
            return true;
        }

        Config.instance().getSettingData().lastActiveSave = WorldSave.filename(currentSlot);
        Config.instance().saveSettings();
        if (backupFile.exists())
            backupFile.delete();
        finish(null);
        return true;
    }

    private void finish(String errors) {
        if (errors != null)
            announceError(errors);
        Gdx.app.postRunnable(() -> {
            OverlayText.getInstance().update("");
        });
    }

    public void restoreBackup(String oldFilename, String currentFilename) {
        File f = new File(currentFilename);
        if (f.exists())
            f.delete();
        File b = new File(oldFilename);
        if (b.exists())
            b.renameTo(new File(currentFilename));
    }

    public String getExceptionMessage(SaveFileData... datas) {
        StringBuilder message = new StringBuilder();

        for (SaveFileData data : datas) {
          String s = data.readString("IOException");
          if (s != null)
              message.append(s).append("\n");
        }

        return message.toString();
    }

    private void announceError(String message) {
        currentSave.player.getCurrentGameStage().setExtraAnnouncement("Error Saving File!\n" + message);
    }

    public void clearChanges() {
        pointOfInterestChanges.clear();
    }

    public void clearBookmarks() {
        for (PointOfInterest poi : currentSave.world.getAllPointOfInterest()) {
            if (poi == null)
                continue;
            PointOfInterestMapSprite mapSprite = WorldStage.getInstance().getMapSprite(poi);
            if (mapSprite != null)
                mapSprite.setBookmarked(false, poi);
            PointOfInterestChanges p = pointOfInterestChanges.get(poi.getID());
            if (p == null)
                continue;
            p.setIsBookmarked(false);
            p.save();
        }
        MapViewScene.instance().clearBookMarks();
    }

    // prevent spam of preview if user repeatedly/accidentally reopen scene that request previews
    public static void requestPreview() {
        final long currentTimestamp = TimeUtils.millis();

        if (firstCapture) {
            firstCapture = false;
            // init once
            currentSave.header.createPreview();
            return;
        }

        // If 800ms has not passed since the last successful generation, skip
        if (currentTimestamp - lastPreviewTimestamp < COOLDOWN_WINDOW_MS) {
            return;
        }

        // Update the timestamp immediately to lock out parallel thread spam on the spot
        lastPreviewTimestamp = currentTimestamp;
        Gdx.app.postRunnable(new Runnable() {
            @Override
            public void run() {
                currentSave.header.createPreview();
            }
        });
    }

    public static void dispose() {
        Forge.safeDispose(currentSave.world);
    }

    /**
     * Create a set plane (MV1) if missing, generated from the Ascendant set-plane
     * template and a unique seed. Does not switch to it.
     */
    public PlaneMeta ensureSetPlane(String planeId, String displayName) {
        if (!Config.ascendant()) {
            throw new IllegalStateException("Multi-plane saves require Ascendant");
        }
        if (planeId == null || planeId.isEmpty() || PlaneMeta.HOME_ID.equals(planeId)) {
            throw new IllegalArgumentException("Invalid set plane id");
        }
        PlaneMeta existing = multiverse.getMeta(planeId);
        if (existing != null && multiverse.getInactiveBlob(planeId) != null) {
            return existing;
        }
        if (existing != null && planeId.equals(multiverse.getCurrentPlaneId())) {
            return existing;
        }
        ConfigData cfg = Config.instance().getConfigData();
        int max = cfg != null ? Math.max(1, cfg.maxPlanesPerSave) : 16;
        if (multiverse.listPlanes().size() >= max && existing == null) {
            throw new IllegalStateException("Plane limit reached (" + max + ")");
        }
        String template = cfg != null && cfg.setPlaneWorldConfig != null && !cfg.setPlaneWorldConfig.isEmpty()
                ? cfg.setPlaneWorldConfig : "world/set_plane_world.json";
        long seed = System.nanoTime() ^ planeId.hashCode() ^ world.getSeed();
        PlaneMeta meta = multiverse.registerSetPlane(planeId, seed, template,
                displayName != null ? displayName : planeId);

        // Generate into a temporary World, pack as inactive blob, dispose grids.
        World generated = new World();
        if (!generated.generateNew(seed, template)) {
            throw new IllegalStateException("Failed to generate set plane " + planeId);
        }
        meta.setSeed(generated.getSeed());
        float startX = (float) (generated.getData().playerStartPosX * generated.getData().width
                * generated.getTileSize());
        float startY = (float) (generated.getData().playerStartPosY * generated.getData().height
                * generated.getTileSize());
        meta.setPlayerPos(startX, startY);
        SaveFileData emptyPoi = new PointOfInterestChanges.Map().save();
        SaveFileData blob = PlaneBlob.pack(generated.save(), emptyWorldStageData(), emptyPoi, meta);
        multiverse.putInactiveBlob(planeId, blob);
        Forge.safeDispose(generated);
        return meta;
    }

    /**
     * Switch the live overworld to {@code planeId}. Only the target plane is
     * loaded; the previous plane is serialized into the multiverse registry.
     *
     * @return true on success
     */
    public boolean switchPlane(String planeId) {
        if (!Config.ascendant()) {
            return false;
        }
        if (planeId == null || planeId.isEmpty()) {
            return false;
        }
        if (planeId.equals(multiverse.getCurrentPlaneId())) {
            return true;
        }
        if (!multiverse.hasPlane(planeId)) {
            return false;
        }
        if (player.isOverloaded()) {
            return false;
        }

        multiverse.rememberCurrentPosition(player.getWorldPosX(), player.getWorldPosY());
        multiverse.updateCurrentSeed(world.getSeed());

        SaveFileData currentBlob = PlaneBlob.pack(
                world.save(),
                WorldStage.getInstance().save(),
                pointOfInterestChanges.save(),
                multiverse.getCurrentMeta());

        SaveFileData targetBlob = multiverse.getInactiveBlob(planeId);
        if (targetBlob == null && !planeId.equals(PlaneMeta.HOME_ID)) {
            return false;
        }

        multiverse.stashCurrentAndSelect(planeId, currentBlob);

        try {
            SaveFileData worldData = PlaneBlob.world(targetBlob);
            SaveFileData stageData = PlaneBlob.worldStage(targetBlob);
            SaveFileData poiData = PlaneBlob.poiChanges(targetBlob);
            PlaneMeta targetMeta = PlaneBlob.readMeta(targetBlob);
            if (targetMeta == null) {
                targetMeta = multiverse.getMeta(planeId);
            }
            if (worldData == null) {
                // Should not happen for registered planes; regenerate as last resort.
                String path = targetMeta != null ? targetMeta.getWorldConfigPath() : Paths.WORLD;
                long seed = targetMeta != null ? targetMeta.getSeed() : 0L;
                if (!world.generateNew(seed, path)) {
                    return false;
                }
            } else {
                world.load(worldData);
            }
            pointOfInterestChanges.clear();
            if (poiData != null) {
                pointOfInterestChanges.load(poiData);
            }
            if (stageData != null) {
                WorldStage.getInstance().load(stageData);
            } else {
                WorldStage.getInstance().load(emptyWorldStageData());
            }
            float px = targetMeta != null ? targetMeta.getPlayerPosX() : player.getWorldPosX();
            float py = targetMeta != null ? targetMeta.getPlayerPosY() : player.getWorldPosY();
            if (px == 0f && py == 0f && world.getData() != null) {
                px = (float) (world.getData().playerStartPosX * world.getData().width * world.getTileSize());
                py = (float) (world.getData().playerStartPosY * world.getData().height * world.getTileSize());
            }
            player.setWorldPosX(px);
            player.setWorldPosY(py);
            multiverse.rememberCurrentPosition(px, py);
            multiverse.updateCurrentSeed(world.getSeed());
            CardUtil.clearPriceCache();
            onLoadList.emit();
            notifyCoopPlaneSwitch();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Host co-op: tell the guest to follow onto the host's current plane.
     * Keeps CO1–CO3 hook signatures; uses {@link forge.gamemodes.net.event.coop.CoopPlaneSwitchEvent}.
     */
    private void notifyCoopPlaneSwitch() {
        try {
            forge.adventure.coop.CoopSession.get().offerCurrentPlaneToGuest();
        } catch (Exception ignored) {
            // Co-op optional
        }
    }

    static SaveFileData emptyWorldStageData() {
        SaveFileData emptyStage = new SaveFileData();
        emptyStage.storeObject("timeouts", new ArrayList<Float>());
        emptyStage.storeObject("names", new ArrayList<String>());
        emptyStage.storeObject("x", new ArrayList<Float>());
        emptyStage.storeObject("y", new ArrayList<Float>());
        emptyStage.storeObject("questStageIDs", new ArrayList<String>());
        emptyStage.store("globalTimer", 0f);
        return emptyStage;
    }
}
