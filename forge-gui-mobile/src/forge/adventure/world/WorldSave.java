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
import forge.adventure.coop.WorldPartners;
import forge.adventure.fortress.FortressService;
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
 * inactive planes stay as Deflater-compressed byte blobs inside the {@code .sav}
 * (and compressed in RAM). They decompress only on a plane switch.
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
    /** CO5: guest partner characters keyed by profile id (host world save only). */
    private final WorldPartners partners = new WorldPartners();
    /**
     * Slot last successfully loaded (or saved). Used by co-op host partner flushes so
     * they never fall back to stale {@code lastActiveSave} / hidden slot 0.
     */
    private int loadedSlot = INVALID_SAVE_SLOT;
    private String lastPlaneSwitchError = "";
    /** Test hook: count of {@link forge.adventure.scene.GameScene#enter()} after switches. */
    private int planeSwitchEnterCount;


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

    /** CO5: world-bound partner characters (host save). Missing on old saves → empty. */
    public WorldPartners getPartners() {
        return partners;
    }

    /**
     * CO5: after a guest leaves (or a failed partner load), wipe in-memory world and
     * player so StartScene hides Save/Resume and autosave cannot write partner data
     * into a solo slot. Disk solo {@code .sav} files are never touched.
     */
    public void unloadAfterGuestSession() {
        try {
            world.unloadData();
        } catch (final Exception ignored) {
        }
        try {
            player.unload();
        } catch (final Exception ignored) {
        }
        try {
            partners.clear();
        } catch (final Exception ignored) {
        }
        try {
            pointOfInterestChanges.clear();
        } catch (final Exception ignored) {
        }
        try {
            GamePlayerUtil.getGuiPlayer().setName("");
        } catch (final Exception ignored) {
        }
        // H2: do not let a later host partner flush overwrite the pre-join slot.
        loadedSlot = INVALID_SAVE_SLOT;
    }

    /** MV1 current plane instance id ({@link PlaneMeta#HOME_ID} for legacy / home). */
    public String getCurrentPlaneId() {
        return multiverse.getCurrentPlaneId();
    }

    public String getLastPlaneSwitchError() {
        return lastPlaneSwitchError != null ? lastPlaneSwitchError : "";
    }

    /** Visible for tests — how many times a successful switch entered GameScene. */
    public int getPlaneSwitchEnterCount() {
        return planeSwitchEnterCount;
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
        EnemyThemeDecks.clearCache();
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
                    // Prefer live/top-level plane id so an empty registry field keeps identity.
                    String liveHint = mainData.readString("currentPlaneId");
                    if (liveHint != null && !liveHint.isEmpty()) {
                        currentSave.multiverse.presetCurrentPlaneId(liveHint);
                    }
                    SaveFileData multi = mainData.readSubData("multiverse");
                    if (multi == null || !currentSave.multiverse.loadRegistry(multi)) {
                        currentSave.multiverse.migrateLegacyHome(
                                currentSave.world.getSeed(),
                                currentSave.player.getWorldPosX(),
                                currentSave.player.getWorldPosY());
                    } else {
                        currentSave.multiverse.ensureMetaForCurrent(
                                currentSave.world.getSeed(),
                                currentSave.world.getWorldConfigPath());
                        currentSave.multiverse.updateCurrentSeed(currentSave.world.getSeed());
                        currentSave.multiverse.rememberCurrentPosition(
                                currentSave.player.getWorldPosX(),
                                currentSave.player.getWorldPosY());
                    }
                    // Schema 3 ore-line: migrate inactive plane node ids only when the player
                    // save is still below schema 3. Schema-3 loads keep compressed blobs as-is.
                    boolean needOreMigrate = currentSave.player.getLoadedMaterialSchema()
                            < forge.adventure.data.MaterialListData.MATERIAL_SCHEMA_ORE_LINE;
                    currentSave.multiverse.setOreLineInactiveMigrationNeeded(needOreMigrate);
                    if (needOreMigrate) {
                        try {
                            currentSave.multiverse.migrateInactivePlanesForOreLineIfNeeded();
                        } catch (IOException ignored) {
                            // Leave blobs as stored; live-plane WorldStage.load still migrates nodes.
                        }
                    }
                    // MV2: PlanarGate definition + any missing home/set gates.
                    try {
                        SetPlaneGenerator.ensurePlanarGateRegistered();
                        PlanarPortalPlacer.ensureMissingGatesOnLoad(currentSave);
                    } catch (Exception ignored) {
                        // Gate placement must not block load.
                    }
                    // H1: cache live co-op hash after plane load + gates.
                    try {
                        forge.adventure.coop.CoopSession.get().refreshHostLiveWorldHash();
                    } catch (Exception ignored) {
                    }
                } else {
                    currentSave.multiverse.initHomeFromLive(
                            currentSave.world.getSeed(),
                            currentSave.player.getWorldPosX(),
                            currentSave.player.getWorldPosY());
                }

                // FT1: per-plane fortress on the live plane (missing → no fortress).
                if (Config.ascendant() && mainData.containsKey("fortress")) {
                    FortressService.get().loadCurrent(mainData.readSubData("fortress"));
                } else {
                    FortressService.get().clear();
                }

                // CO5: world-bound partners (missing → empty; old saves still load).
                if (Config.ascendant() && mainData.containsKey("partners")) {
                    currentSave.partners.load(mainData.readSubData("partners"));
                } else {
                    currentSave.partners.clear();
                }

                currentSave.onLoadList.emit();

            }
        } catch (ClassNotFoundException | IOException e) {
            e.printStackTrace();
            return false;
        }
        // Track the slot that is actually loaded so partner flushes hit the same file.
        currentSave.loadedSlot = currentSlot;
        try {
            Config.instance().getSettingData().lastActiveSave = WorldSave.filename(currentSlot);
            Config.instance().saveSettings();
        } catch (final Exception ignored) {
        }
        return true;
    }

    /** Slot currently backed by {@link #currentSave}, or {@link #INVALID_SAVE_SLOT}. */
    public int getLoadedSlot() {
        return loadedSlot;
    }

    /** Test / host-create helper: remember which slot this in-memory save belongs to. */
    public void setLoadedSlot(final int slot) {
        loadedSlot = slot;
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
        currentSave.partners.clear();
        FortressService.get().clear();
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
        // H2: New Game is not bound to a prior load slot until the player saves.
        currentSave.loadedSlot = INVALID_SAVE_SLOT;
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
        // MV2: Planar Gates on the home plane for the starting Standard window.
        try {
            if (Config.ascendant() && currentSave.world != null) {
                PlanarPortalPlacer.ensureHomePortals(currentSave.world,
                        currentSave.player.getStandardWindow(), currentSave.world.getSeed());
            }
        } catch (Exception ignored) {
            // Portal placement must not break New Game.
        }

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
        // CO5: guests play a host-world partner in memory — never write local slots mid-session.
        if (forge.adventure.coop.CoopSession.get().isGuestSession()) {
            return false;
        }
        if (currentSave.world.getData() == null) {
            return false;
        }
        return save("auto save" + saveFileSuffixSafe(), AUTO_SAVE_SLOT);
    }

    public boolean quickSave() {
        if (forge.adventure.coop.CoopSession.get().isGuestSession()) {
            return false;
        }
        if (currentSave.world.getData() == null) {
            return false;
        }
        return save("quick save" + saveFileSuffixSafe(), QUICK_SAVE_SLOT);
    }

    private static String saveFileSuffixSafe() {
        try {
            return SaveLoadScene.instance().getSaveFileSuffix();
        } catch (final Throwable t) {
            return "";
        }
    }

    public boolean quickLoad() {
        return load(QUICK_SAVE_SLOT);
    }

    /**
     * Persist the current world without renaming the save-slot header
     * (CO5 host partner flush must not retitle the load UI).
     */
    public boolean savePreservingHeader(final int currentSlot) {
        final String keep = header != null && header.name != null && !header.name.isEmpty()
                ? header.name : "save";
        return save(keep, currentSlot);
    }

    public boolean save(String text, int currentSlot) {
        if (forge.adventure.coop.CoopSession.get().isGuestSession()) {
            System.err.println("Co-op guest: refusing to write WorldSave slot " + currentSlot);
            return false;
        }
        // No live world → nothing to save (CO5 guest leave leaves data null).
        if (currentSave.world.getData() == null) {
            return false;
        }
        header.name = text;

        String fileName = WorldSave.getSaveFile(currentSlot);
        String oldFileName = fileName.replace(".sav", ".old");
        new File(getSaveDir()).mkdirs();
        File currentFile = new File(fileName);
        File backupFile = new File(oldFileName);
        boolean renamedToOld = false;
        if (currentFile.exists()) {
            renamedToOld = currentFile.renameTo(backupFile);
        }

        try {
            CollectionExporter.export(currentSave.player); // collection + decks for external deck builders

            try (FileOutputStream fos = new FileOutputStream(fileName);
                 DeflaterOutputStream def = new DeflaterOutputStream(fos);
                 ObjectOutputStream oos = new ObjectOutputStream(def)) {
                currentSave.multiverse.rememberCurrentPosition(
                        currentSave.player.getWorldPosX(), currentSave.player.getWorldPosY());
                currentSave.multiverse.updateCurrentSeed(currentSave.world.getSeed());

                SaveFileData player = currentSave.player.save();
                SaveFileData world = currentSave.world.save();
                SaveFileData worldStage;
                try {
                    worldStage = WorldStage.getInstance().save();
                } catch (final Throwable t) {
                    // Headless / pre-GL: still persist player + world + partners.
                    worldStage = new SaveFileData();
                }
                SaveFileData poiChanges = currentSave.pointOfInterestChanges.save();
                SaveFileData multi = Config.ascendant() ? currentSave.multiverse.saveRegistry() : null;
                SaveFileData fortress = Config.ascendant() ? FortressService.get().saveCurrent() : null;

                String message = getExceptionMessage(player, world, worldStage, poiChanges);
                if (multi != null) {
                    message = message + getExceptionMessage(multi);
                }
                if (fortress != null) {
                    message = message + getExceptionMessage(fortress);
                }
                if (!message.isEmpty()) {
                    oos.close();
                    fos.close();
                    restoreBackup(oldFileName, fileName);
                    finish(message);
                    return false;
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
                if (fortress != null) {
                    mainData.store("fortress", fortress);
                }
                // CO5: partners map (host world). Empty on solo / pre-CO5 saves.
                if (Config.ascendant()) {
                    mainData.store("partners", currentSave.partners.save());
                }

                if (mainData.readString("IOException") != null) {
                    oos.close();
                    fos.close();
                    restoreBackup(oldFileName, fileName);
                    finish("Please check forge.log for errors.");
                    return false;
                }

                header.saveDate = new Date();
                oos.writeObject(header);
                oos.writeObject(mainData);
            }

        } catch (final Throwable e) {
            // Never leave a truncated .sav — restore .old on any failure.
            System.err.println("WorldSave.save failed: " + e);
            try {
                restoreBackup(oldFileName, fileName);
            } catch (final Exception ignored) {
            }
            finish("Please check forge.log for errors.");
            return false;
        }

        Config.instance().getSettingData().lastActiveSave = WorldSave.filename(currentSlot);
        Config.instance().saveSettings();
        currentSave.loadedSlot = currentSlot;
        if (backupFile.exists() && renamedToOld)
            backupFile.delete();
        finish(null);
        return true;
    }

    private void finish(String errors) {
        if (errors != null)
            announceError(errors);
        if (Gdx.app != null) {
            Gdx.app.postRunnable(() -> {
                OverlayText.getInstance().update("");
            });
        }
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
        try {
            currentSave.player.getCurrentGameStage().setExtraAnnouncement("Error Saving File!\n" + message);
        } catch (final Throwable t) {
            System.err.println("Error Saving File!\n" + message);
        }
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
     * NG+: regenerate on the home template and reset the plane registry to a
     * fresh single home plane (drop every set plane / compressed blob).
     */
    public void resetForNewGamePlus() {
        if (!Config.ascendant()) {
            return;
        }
        multiverse.resetForNewGamePlus(
                world.getSeed(),
                player.getWorldPosX(),
                player.getWorldPosY());
        world.setWorldConfigPath(Paths.WORLD);
        FortressService.get().clear(true); // warn: fortress storage wiped on NG+
    }

    /**
     * Create a set plane (MV1/MV2) if missing. When {@code materializeNow} is true,
     * generates immediately (console / first travel). When false, only registers
     * meta so generation is deferred until first travel (mastery unlock).
     *
     * <p>Generation uses a temporary {@link World} and never clears the live
     * {@link WorldStage}. Must run on the GL thread when materializing (textures).
     */
    public PlaneMeta ensureSetPlane(String planeId, String displayName) {
        return ensureSetPlane(planeId, displayName, true);
    }

    public PlaneMeta ensureSetPlane(String planeId, String displayName, boolean materializeNow) {
        if (!Config.ascendant()) {
            throw new IllegalStateException("Multi-plane saves require Ascendant");
        }
        if (planeId == null || planeId.isEmpty() || PlaneMeta.HOME_ID.equals(planeId)) {
            throw new IllegalArgumentException("Invalid set plane id");
        }
        PlaneMeta existing = multiverse.getMeta(planeId);
        if (existing != null) {
            if (planeId.equals(multiverse.getCurrentPlaneId())) {
                return existing;
            }
            if (multiverse.hasCompressedBlob(planeId)) {
                return existing;
            }
            // Pending MV2 registration (no blob yet) — materialize on demand.
            if (materializeNow) {
                return materializeSetPlane(planeId);
            }
            return existing;
        }
        ConfigData cfg = Config.instance().getConfigData();
        int max = cfg != null ? Math.max(1, cfg.maxPlanesPerSave) : 16;
        if (multiverse.listPlanes().size() >= max) {
            throw new IllegalStateException("Plane limit reached (" + max + ")");
        }
        String template = cfg != null && cfg.setPlaneWorldConfig != null && !cfg.setPlaneWorldConfig.isEmpty()
                ? cfg.setPlaneWorldConfig : "world/set_plane_world.json";
        if (!PlaneConfigPaths.isAllowed(template, multiverse)) {
            throw new IllegalStateException("Disallowed set-plane template: " + template);
        }
        String setCode = SetPlaneRules.restrictableSetCode(SetPlaneGenerator.setCodeFromPlaneId(planeId));
        String label = displayName != null && !displayName.isEmpty()
                ? displayName
                : (setCode.isEmpty() ? planeId : SetPlaneGenerator.displayNameForSet(setCode));
        long seed = System.nanoTime() ^ planeId.hashCode() ^ world.getSeed();
        if (!setCode.isEmpty()) {
            seed = world.getSeed() ^ ((long) setCode.hashCode() << 32) ^ planeId.hashCode();
        }
        PlaneMeta meta = multiverse.registerSetPlane(planeId, seed, template, label);
        // M1: stamp known editions only (clear otherwise).
        meta.setSetCode(setCode);
        if (!materializeNow) {
            return meta;
        }
        return materializeSetPlane(planeId);
    }

    /**
     * MV2: register a set plane without generating the world grid (deferred until
     * first portal travel). Safe to call from mastery on any plane.
     */
    public PlaneMeta registerSetPlanePending(String setCode) {
        if (setCode == null || setCode.isEmpty()) {
            throw new IllegalArgumentException("setCode required");
        }
        String planeId = SetPlaneGenerator.planeIdForSet(setCode);
        return ensureSetPlane(planeId, SetPlaneGenerator.displayNameForSet(setCode), false);
    }

    /**
     * Generate (or no-op if already present) the compressed blob for a registered
     * set plane. Call on the GL thread / via a loading screen before switchPlane.
     */
    public PlaneMeta materializeSetPlane(String planeId) {
        if (!Config.ascendant()) {
            throw new IllegalStateException("Multi-plane saves require Ascendant");
        }
        PlaneMeta meta = multiverse.getMeta(planeId);
        if (meta == null) {
            throw new IllegalStateException("Unknown plane: " + planeId);
        }
        if (planeId.equals(multiverse.getCurrentPlaneId()) || multiverse.hasCompressedBlob(planeId)) {
            return meta;
        }
        ConfigData cfg = Config.instance().getConfigData();
        String template = meta.getWorldConfigPath();
        if (template == null || template.isEmpty()) {
            template = cfg != null && cfg.setPlaneWorldConfig != null && !cfg.setPlaneWorldConfig.isEmpty()
                    ? cfg.setPlaneWorldConfig : "world/set_plane_world.json";
        }
        if (!PlaneConfigPaths.isAllowed(template, multiverse)) {
            throw new IllegalStateException("Disallowed set-plane template: " + template);
        }
        // Prefer stamped setCode; fall back to plane-id parse. Only stamp after a
        // successful MV2 customise so pre-MV2 set planes stay with empty setCode.
        String setCode = meta.getSetCode();
        if (setCode == null || setCode.isEmpty()) {
            setCode = SetPlaneGenerator.setCodeFromPlaneId(planeId);
        }
        long seed = meta.getSeed() != 0 ? meta.getSeed()
                : (world.getSeed() ^ ((long) planeId.hashCode() << 32));

        // Temporary World — must not touch the live stage. Caller must be on GL thread.
        // Live co-op hash includes gates; guest replays the host gate list.
        World generated = new World();
        final String mv2Code = SetPlaneRules.restrictableSetCode(setCode);
        try {
            forge.adventure.coop.CoopWorldSync.buildSetPlaneWorld(
                    generated, seed, template, mv2Code, true);
        } catch (Exception e) {
            Forge.safeDispose(generated);
            throw new IllegalStateException("Failed to generate set plane " + planeId
                    + ": " + e.getMessage(), e);
        }
        // M1: stamp only when MV2 customisation ran; clear when skipped (unknown/pre-MV2).
        meta.setSetCode(mv2Code);
        meta.setSeed(generated.getSeed());
        float startX = (float) (generated.getData().playerStartPosX * generated.getData().width
                * generated.getTileSize());
        float startY = (float) (generated.getData().playerStartPosY * generated.getData().height
                * generated.getTileSize());
        meta.setPlayerPos(startX, startY);
        SaveFileData emptyPoi = new PointOfInterestChanges.Map().save();
        SaveFileData blob = PlaneBlob.pack(generated.save(), emptyWorldStageData(), emptyPoi, meta);
        try {
            multiverse.writeInactiveBlob(planeId, blob);
        } catch (IOException e) {
            Forge.safeDispose(generated);
            throw new IllegalStateException("Failed to store set plane blob: " + e.getMessage(), e);
        }
        Forge.safeDispose(generated);
        return meta;
    }

    /** MV2: ensure a set plane for {@code setCode} (registers pending; materializes on travel). */
    public PlaneMeta ensureSetPlaneForSet(String setCode) {
        return registerSetPlanePending(setCode);
    }

    /**
     * Preflight for portals / console: true when travel to {@code planeId} is
     * allowed without mutating live state. Missing planes / missing blobs fail here
     * so callers can refuse <em>before</em> exiting a POI.
     */
    public boolean canTravelToPlane(String planeId) {
        lastPlaneSwitchError = "";
        if (!Config.ascendant()) {
            lastPlaneSwitchError = "Multi-plane requires Ascendant";
            return false;
        }
        if (planeId == null || planeId.isEmpty()) {
            lastPlaneSwitchError = "Missing plane id";
            return false;
        }
        if (planeId.equals(multiverse.getCurrentPlaneId())) {
            return true;
        }
        if (!multiverse.hasPlane(planeId)) {
            lastPlaneSwitchError = "Unknown plane: " + planeId;
            return false;
        }
        if (player.isOverloaded()) {
            lastPlaneSwitchError = "Overloaded — clear Overflow before planar travel.";
            return false;
        }
        if (!forge.adventure.coop.CoopSession.get().canInitiatePlaneSwitch()) {
            lastPlaneSwitchError = "Guests cannot initiate plane switches — follow the host.";
            return false;
        }
        // MV2 alignment (no gold charge on preflight).
        String alignErr = SetPlaneRules.checkTravel(planeId, player, false);
        if (alignErr != null) {
            lastPlaneSwitchError = alignErr;
            return false;
        }
        if (!multiverse.hasCompressedBlob(planeId)) {
            // Pending MV2 plane: travelable once materialized (auto-create / first portal).
            ConfigData cfg = Config.instance().getConfigData();
            PlaneMeta meta = multiverse.getMeta(planeId);
            boolean canMaterialize = cfg != null && cfg.planarPortalAutoCreate
                    && meta != null && !PlaneMeta.HOME_ID.equals(planeId);
            if (!canMaterialize) {
                lastPlaneSwitchError = "No saved data for plane " + planeId;
                return false;
            }
        }
        return true;
    }

    /**
     * Switch the live overworld to {@code planeId}.
     * <ol>
     *   <li>Decompress the target into a separate {@link World} first.</li>
     *   <li>Only on success, compress-stash the current plane and swap.</li>
     *   <li>On failure, change nothing and set {@link #getLastPlaneSwitchError()}.</li>
     * </ol>
     * Never silently regenerates a plane from seed. Enters {@code GameScene} exactly once.
     */
    public boolean switchPlane(String planeId) {
        lastPlaneSwitchError = "";
        if (!Config.ascendant()) {
            lastPlaneSwitchError = "Multi-plane requires Ascendant";
            return false;
        }
        if (planeId == null || planeId.isEmpty()) {
            lastPlaneSwitchError = "Missing plane id";
            return false;
        }
        if (planeId.equals(multiverse.getCurrentPlaneId())) {
            return true;
        }
        if (!multiverse.hasPlane(planeId)) {
            lastPlaneSwitchError = "Unknown plane: " + planeId;
            return false;
        }
        if (player.isOverloaded()) {
            lastPlaneSwitchError = "Overloaded — clear Overflow before planar travel.";
            return false;
        }
        if (!forge.adventure.coop.CoopSession.get().canInitiatePlaneSwitch()) {
            lastPlaneSwitchError = "Guests cannot initiate plane switches — follow the host.";
            return false;
        }

        final String fromId = multiverse.getCurrentPlaneId();
        multiverse.rememberCurrentPosition(player.getWorldPosX(), player.getWorldPosY());
        multiverse.updateCurrentSeed(world.getSeed());

        // MV2 deferred gen: materialize pending set planes on the GL path before swap.
        if (!multiverse.hasCompressedBlob(planeId)) {
            try {
                materializeSetPlane(planeId);
            } catch (Exception e) {
                lastPlaneSwitchError = "Cannot create plane data: " + e.getMessage();
                return false;
            }
        }

        SaveFileData targetBlob;
        try {
            targetBlob = multiverse.readInactiveBlob(planeId);
        } catch (IOException e) {
            lastPlaneSwitchError = "Cannot read plane data: " + e.getMessage();
            return false;
        }
        if (targetBlob == null) {
            lastPlaneSwitchError = "No saved data for plane " + planeId;
            return false;
        }
        SaveFileData worldData = PlaneBlob.world(targetBlob);
        if (worldData == null) {
            lastPlaneSwitchError = "Plane " + planeId + " has no world payload";
            return false;
        }
        String cfgPath = PlaneConfigPaths.normalizeOrDefault(
                PlaneBlob.readMeta(targetBlob) != null
                        ? PlaneBlob.readMeta(targetBlob).getWorldConfigPath()
                        : null);
        if (!PlaneConfigPaths.isAllowed(cfgPath, multiverse)) {
            lastPlaneSwitchError = "Rejected worldConfigPath: " + cfgPath;
            return false;
        }

        // 1) Stage into a separate World — live state untouched on failure.
        World staging = new World();
        try {
            staging.load(worldData);
        } catch (Exception e) {
            Forge.safeDispose(staging);
            lastPlaneSwitchError = "Failed to load plane " + planeId + ": " + e.getMessage();
            return false;
        }

        PlaneMeta targetMeta = PlaneBlob.readMeta(targetBlob);
        if (targetMeta == null) {
            targetMeta = multiverse.getMeta(planeId);
        }
        SaveFileData stageData = PlaneBlob.worldStage(targetBlob);
        SaveFileData poiData = PlaneBlob.poiChanges(targetBlob);

        // Snapshot current for stash + rollback.
        SaveFileData currentBlob = PlaneBlob.pack(
                world.save(),
                WorldStage.getInstance().save(),
                pointOfInterestChanges.save(),
                multiverse.getCurrentMeta(),
                FortressService.get().saveCurrent());

        final float fromPosX = player.getWorldPosX();
        final float fromPosY = player.getWorldPosY();
        try {
            // 2) Compress-stash outgoing plane, then swap live world from the staging save.
            multiverse.writeInactiveBlob(fromId, currentBlob);
            SaveFileData stagedSave = staging.save();
            world.load(stagedSave);
            pointOfInterestChanges.clear();
            if (poiData != null) {
                pointOfInterestChanges.load(poiData);
            }
            if (stageData != null) {
                WorldStage.getInstance().load(stageData);
            } else {
                WorldStage.getInstance().load(emptyWorldStageData());
            }
            // FT1: restore per-plane fortress (missing → clear).
            FortressService.get().replaceCurrent(
                    FortressService.fromSave(PlaneBlob.fortress(targetBlob)));
            multiverse.selectCurrentPlane(planeId);
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
            EnemyThemeDecks.clearCache();
            // MV2: place any missing Planar Gates on the plane we just entered.
            try {
                PlanarPortalPlacer.ensureMissingGatesOnLoad(this);
            } catch (Exception ignored) {
                // Gate placement must not fail the switch.
            }
            // H1: cache live hash after load/switch + gate ensure (GL path).
            try {
                forge.adventure.coop.CoopSession.get().refreshHostLiveWorldHash();
            } catch (Exception ignored) {
            }
            onLoadList.emit();
            enterGameSceneOnceAfterSwitch();
            notifyCoopPlaneSwitch();
            Forge.safeDispose(staging);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            lastPlaneSwitchError = "Plane switch failed: " + e.getMessage();
            // Roll back live world from the in-memory snapshot (state unchanged for the player).
            try {
                SaveFileData rollbackWorld = PlaneBlob.world(currentBlob);
                SaveFileData rollbackStage = PlaneBlob.worldStage(currentBlob);
                SaveFileData rollbackPoi = PlaneBlob.poiChanges(currentBlob);
                if (rollbackWorld != null) {
                    world.load(rollbackWorld);
                }
                pointOfInterestChanges.clear();
                if (rollbackPoi != null) {
                    pointOfInterestChanges.load(rollbackPoi);
                }
                if (rollbackStage != null) {
                    WorldStage.getInstance().load(rollbackStage);
                }
                FortressService.get().replaceCurrent(
                        FortressService.fromSave(PlaneBlob.fortress(currentBlob)));
                // Back on the source plane (this also drops its now-stale stashed blob).
                try {
                    multiverse.selectCurrentPlane(fromId);
                } catch (Exception ignored) {
                }
                multiverse.markInactive(planeId);
                // selectCurrentPlane(planeId) may already have dropped the target's only copy: put it back.
                multiverse.putInactiveBlob(planeId, targetBlob);
                player.setWorldPosX(fromPosX);
                player.setWorldPosY(fromPosY);
            } catch (Exception rollbackEx) {
                rollbackEx.printStackTrace();
            }
            Forge.safeDispose(staging);
            return false;
        }
    }

    /** Exactly one GameScene.enter() per successful switch (callers must not enter again). */
    private void enterGameSceneOnceAfterSwitch() {
        try {
            forge.adventure.scene.GameScene.instance().enter();
            planeSwitchEnterCount++;
        } catch (Exception ignored) {
            // Scene may be unavailable in headless tests
            planeSwitchEnterCount++;
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

    public static SaveFileData emptyWorldStageData() {
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
