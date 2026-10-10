package forge.adventure.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.Json;
import forge.Forge;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestMap;
import forge.adventure.scene.Scene;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileContent;
import forge.adventure.util.SaveFileData;
import forge.gui.GuiBase;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class that will create the world from the configuration
 */
public class World implements Disposable, SaveFileContent {
    private WorldData data;
    private Pixmap biomeImage;
    private long[][] biomeMap;
    public int[][] terrainMap;
    private static final int collisionBit = 0b10000000000000000000000000000000;
    private static final int isStructureBit = 0b01000000000000000000000000000000;
    private static final int terrainMask = collisionBit | isStructureBit;
    private int width;
    private int height;
    private SpritesDataMap mapObjectIds;
    private PointOfInterestMap mapPoiIds;
    private BiomeTexture[] biomeTexture;
    private long seed;
    private final Random random = new Random();
    private boolean worldDataLoaded = false;
    private Texture globalTexture = null;
    private final ArrayList<DrawingInformation> drawingInfoCache = new ArrayList<>(32);
    private Pixmap globalTileDrawing = null;
    private Pixmap emptyTile = null;
    /** Relative path under the adventure plane (MV1 set planes may use a template). */
    private String worldConfigPath = Paths.WORLD;
    /** Test/observe: whether the last {@link #generateNew} cleared the live WorldStage. */
    private boolean clearedLiveStageOnLastGenerate;
    /** MV2: optional in-memory world.json override applied once by {@link #loadWorldData()}. */
    private WorldData pendingWorldDataOverride;

    public Random getRandom() {
        return random;
    }

    public long getSeed() {
        return seed;
    }

    /** World JSON used for generation / co-op hash (MV1). Defaults to {@link Paths#WORLD}. */
    public String getWorldConfigPath() {
        return worldConfigPath != null && !worldConfigPath.isEmpty() ? worldConfigPath : Paths.WORLD;
    }

    /**
     * Drop cached world.json so the next generate/load reads {@code path}.
     * Call before generating a set plane from a template.
     * Disposes prior {@link BiomeTexture} pixmaps on the GL thread when the path changes.
     */
    public void setWorldConfigPath(String path) {
        String next = path != null && !path.isEmpty() ? path : Paths.WORLD;
        if (!next.equals(getWorldConfigPath()) || !worldDataLoaded) {
            disposeBiomeTexturesAsync();
            worldConfigPath = next;
            worldDataLoaded = false;
        } else {
            worldConfigPath = next;
        }
    }

    /** Biome grid used by Ascendant co-op world-hash verification (CO1). */
    public long[][] getBiomeMap() {
        return biomeMap;
    }

    static public int highestBiome(long biome) {
        return (int) (Math.log(Long.highestOneBit(biome)) / Math.log(2));
    }

    public boolean collidingTile(Rectangle boundingRect) {

        int xLeft = (int) boundingRect.getX() / getTileSize();
        int yTop = (int) boundingRect.getY() / getTileSize();
        int xRight = (int) ((boundingRect.getX() + boundingRect.getWidth()) / getTileSize());
        int yBottom = (int) ((boundingRect.getY() + boundingRect.getHeight()) / getTileSize());

        if (isColliding(xLeft, yTop))
            return true;
        if (isColliding(xLeft, yBottom))
            return true;
        if (isColliding(xRight, yBottom))
            return true;
        if (isColliding(xRight, yTop))
            return true;

        return false;
    }

    /**
     * MV2: use a pre-customised {@link WorldData} on the next generate/load instead of
     * reading world.json. Cleared after {@link #loadWorldData()} consumes it.
     */
    public void overrideWorldData(WorldData worldData) {
        pendingWorldDataOverride = worldData;
        worldDataLoaded = false;
    }

    public void loadWorldData() {
        if (worldDataLoaded)
            return;

        if (pendingWorldDataOverride != null) {
            this.data = pendingWorldDataOverride;
            pendingWorldDataOverride = null;
        } else {
            FileHandle handle = Config.instance().getFile(getWorldConfigPath());
            String rawJson = handle.readString();
            this.data = (new Json()).fromJson(WorldData.class, rawJson);
        }
        disposeBiomeTexturesAsync();
        biomeTexture = new BiomeTexture[data.GetBiomes().size() + 1];

        int biomeIndex = 0;
        for (BiomeData biome : data.GetBiomes()) {

            biomeTexture[biomeIndex] = new BiomeTexture(biome, data.tileSize);
            biomeIndex++;
        }
        biomeTexture[biomeIndex] = new BiomeTexture(data.roadTileset, data.tileSize);
        worldDataLoaded = true;
    }

    /**
     * MV2: add a POI after generation (planar gates on home / set planes).
     * Canonical owner of this helper — keep stable across parallel packages
     * (FT1 drops any duplicate).
     */
    public void addPointOfInterest(PointOfInterest poi) {
        if (poi == null || mapPoiIds == null) {
            return;
        }
        mapPoiIds.add(poi);
    }

    /** MV2: clear collision/terrain around a world-pixel position (planar gates). */
    public void clearTerrainAroundWorld(float worldX, float worldY, int size) {
        if (data == null || data.tileSize <= 0) {
            return;
        }
        clearTerrain((int) (worldX / data.tileSize), (int) (worldY / data.tileSize), size);
    }

    @Override
    public void load(SaveFileData saveFileData) {

        if (biomeImage != null) {
            biomeImage.dispose();
            biomeImage = null;
        }

        // MV1: pick the plane's world.json before loading biome definitions.
        String savedPath = saveFileData != null ? saveFileData.readString("worldConfigPath") : null;
        setWorldConfigPath(savedPath != null && !savedPath.isEmpty() ? savedPath : Paths.WORLD);

        loadWorldData();

        biomeImage = saveFileData.readPixmap("biomeImage");
        biomeMap = (long[][]) saveFileData.readObject("biomeMap");
        terrainMap = (int[][]) saveFileData.readObject("terrainMap");


        width = saveFileData.readInt("width");
        height = saveFileData.readInt("height");
        // Size chunk maps from the *saved* world dimensions, not current world.json
        // (Ascendant grew from 700→1000; old saves must keep their 700-wide grids).
        int savedChunksX = Math.max(1, width / getChunkSize());
        int savedChunksY = Math.max(1, height / getChunkSize());
        mapObjectIds = new SpritesDataMap(getChunkSize(), this.data.tileSize, savedChunksX);
        mapObjectIds.load(saveFileData.readSubData("mapObjectIds"));
        mapPoiIds = new PointOfInterestMap(getChunkSize(), this.data.tileSize, savedChunksX, savedChunksY);
        mapPoiIds.load(saveFileData.readSubData("mapPoiIds"));
        seed = saveFileData.readLong("seed");
    }

    @Override
    public SaveFileData save() {

        SaveFileData data = new SaveFileData();

        if (biomeImage != null) {
            data.store("biomeImage", biomeImage);
        }
        data.storeObject("biomeMap", biomeMap);
        data.storeObject("terrainMap", terrainMap);
        data.store("width", width);
        data.store("height", height);
        if (mapObjectIds != null) {
            data.store("mapObjectIds", mapObjectIds.save());
        }
        if (mapPoiIds != null) {
            data.store("mapPoiIds", mapPoiIds.save());
        }
        data.store("seed", seed);
        data.store("worldConfigPath", getWorldConfigPath());
        return data;
    }


    public BiomeSpriteData getObject(int id) {
        return mapObjectIds.get(id);
    }

    private static class DrawingInformation {

        private int neighbors;
        private final BiomeTexture regions;
        private final int terrain;

        public DrawingInformation(int neighbors, BiomeTexture regions, int terrain) {

            this.neighbors = neighbors;
            this.regions = regions;
            this.terrain = terrain;
        }

        public void draw(Pixmap drawingPixmap) {
            regions.drawPixmapOn(terrain, neighbors, drawingPixmap);
        }
    }

    public Pixmap getBiomeSprite(int x, int y) {
        if (x < 0 || y <= 0 || x >= width || y > height) {
            if (emptyTile == null) {
                emptyTile = new Pixmap(data.tileSize, data.tileSize, Pixmap.Format.RGBA8888);
                emptyTile.setColor(0, 0, 0, 0);
                emptyTile.fill();
            }
            return emptyTile;
        }

        // init exactly once on demand
        if (globalTileDrawing == null) {
            globalTileDrawing = new Pixmap(data.tileSize, data.tileSize, Pixmap.Format.RGBA8888);
        } else {
            // Clean the existing alpha pixels instead of instantiating a new object
            globalTileDrawing.setColor(0, 0, 0, 0);
            globalTileDrawing.fill();
        }

        long biomeIndex = getBiome(x, y);
        int biomeTerrain = getTerrainIndex(x, y);

        drawingInfoCache.clear();

        for (int i = 0; i < biomeTexture.length; i++) {
            if ((biomeIndex & 1L << i) == 0) continue;
            BiomeTexture regions = biomeTexture[i];
            if (x <= 0 || y <= 1 || x >= width - 1 || y >= height) {
                return regions.getPixmap(biomeTerrain);
            }

            int neighbors = 0b000_000_000;
            int bitIndex = 8;
            for (int ny = 1; ny > -2; ny--) {
                for (int nx = -1; nx < 2; nx++) {
                    if ((getBiome(x + nx, y + ny) & 1L << i) != 0 && (biomeTerrain == getTerrainIndex(x + nx, y + ny) || biomeTerrain == 0))
                        neighbors |= (1 << bitIndex);
                    bitIndex--;
                }
            }
            if (biomeTerrain != 0 && neighbors != 0b111_111_111) {
                bitIndex = 8;
                int baseNeighbors = 0;
                for (int ny = 1; ny > -2; ny--) {
                    for (int nx = -1; nx < 2; nx++) {
                        if ((getBiome(x + nx, y + ny) & (1L << i)) != 0)
                            baseNeighbors |= (1 << bitIndex);
                        bitIndex--;
                    }
                }
                drawingInfoCache.add(new DrawingInformation(baseNeighbors, regions, 0));
            }
            drawingInfoCache.add(new DrawingInformation(neighbors, regions, biomeTerrain));
        }

        int lastFullNeighbour = -1;
        int counter = 0;
        for (DrawingInformation info : drawingInfoCache) {
            if (info.neighbors == 0b111_111_111) lastFullNeighbour = counter;
            counter++;
        }
        counter = 0;
        if (lastFullNeighbour < 0 && !drawingInfoCache.isEmpty()) {
            drawingInfoCache.get(0).neighbors = 0b111_111_111;
        }
        for (DrawingInformation info : drawingInfoCache) {
            if (counter < lastFullNeighbour) {
                counter++;
                continue;
            }
            info.draw(globalTileDrawing);
        }

        return globalTileDrawing;
    }


    public int getTerrainIndex(int x, int y) {
        try {
            return terrainMap[x][height - y - 1] & ~terrainMask;
        } catch (ArrayIndexOutOfBoundsException e) {
            return 0;
        }
    }

    public long getBiomeMapXY(int x, int y) {
        try {
            return biomeMap[x][height - y - 1] & (~(0b1 << data.GetBiomes().size()));
        } catch (ArrayIndexOutOfBoundsException e) {
            return biomeMap[biomeMap.length - 1][biomeMap[biomeMap.length - 1].length - 1];
        }
    }

    public boolean isStructure(int x, int y) {
        try {
            return (terrainMap[x][height - y - 1] & ~isStructureBit) != 0;
        } catch (ArrayIndexOutOfBoundsException e) {
            return false;
        }
    }

    public long getBiome(int x, int y) {
        try {
            return biomeMap[x][height - y - 1];
        } catch (ArrayIndexOutOfBoundsException e) {
            return biomeMap[biomeMap.length - 1][biomeMap[biomeMap.length - 1].length - 1];
        }
    }

    public boolean isColliding(int x, int y) {
        try {
            return (terrainMap[x][height - y - 1] & collisionBit) != 0;
        } catch (ArrayIndexOutOfBoundsException e) {
            return true;
        }
    }

    public WorldData getData() {
        return data;
    }

    private void clearTerrain(int x, int y, int size) {

        for (int xclear = -size; xclear < size; xclear++)
            for (int yclear = -size; yclear < size; yclear++) {
                try {
                    terrainMap[x + xclear][height - 1 - (y + yclear)] = 0;
                } catch (ArrayIndexOutOfBoundsException ignored) {}
            }
    }

    private long measureGenerationTime(String msg, long lastTime) {
        long currentTime = System.currentTimeMillis();
        System.out.println(msg + " :\t\t" + ((currentTime - lastTime) / 1000f) + " s");
        return currentTime;
    }

    private static boolean pointBlocked(float x, float y, List<Rectangle> boxes) {
        for (Rectangle rect : boxes) {
            if (rect.contains(x, y))
                return true;
        }
        return false;
    }

    /**
     * Generate using an alternate world.json (MV1 set-plane template).
     * Does <em>not</em> clear the live {@link WorldStage} — use for temporary
     * / set-plane generation into a separate {@link World} instance.
     */
    public boolean generateNew(long seed, String configPath) {
        return generateNew(seed, configPath, false);
    }

    /**
     * @param clearLiveStage when true, clears {@link WorldStage} after generation
     *                       (new-game / live-world regen only). Temporary plane
     *                       generation must pass false.
     */
    public boolean generateNew(long seed, String configPath, boolean clearLiveStage) {
        setWorldConfigPath(configPath);
        return generateNewInternal(seed, clearLiveStage);
    }

    public boolean generateNew(long seed) {
        // Live-world regen (New Game / load failure) clears the stage.
        return generateNewInternal(seed, true);
    }

    /** @return whether the most recent generate cleared {@link WorldStage} (tests). */
    public boolean didClearLiveStageOnLastGenerate() {
        return clearedLiveStageOnLastGenerate;
    }

    private boolean generateNewInternal(long seed, boolean clearLiveStage) {
        // Record intent up front so callers/tests can see temporary generates never ask to clear.
        clearedLiveStageOnLastGenerate = clearLiveStage;
        try {
            if (GuiBase.isMobile())
                GuiBase.getInterface().preventSystemSleep(true);
            final long[] currentTime = {System.currentTimeMillis()};
            long startTime = System.currentTimeMillis();

            loadWorldData();
//////////////////
///////// initialize
//////////////////

            if (seed == 0) {
                seed = random.nextLong();
            }
            this.seed = seed;
            random.setSeed(seed);
            OpenSimplexNoise noise = new OpenSimplexNoise(seed);

            float noiseZoom = data.noiseZoomBiome;
            width = data.width;
            height = data.height;
            //save at all data
            biomeMap = new long[width][height];
            terrainMap = new int[width][height];

            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    biomeMap[x][y] = 0;
                    terrainMap[x][y] = 0;
                }
            }

            final int[] biomeIndex = {-1};
            currentTime[0] = measureGenerationTime("loading data", currentTime[0]);
            Map<BiomeStructureData, BiomeStructure> structureDataMap = new ConcurrentHashMap<>();

//////////////////
///////// calculation structure position with wavefunctioncollapse
//////////////////
            List<CompletableFuture<Long>> futures = new ArrayList<>();
            for (BiomeData biome : data.GetBiomes()) {
                if (biome.structures != null) {
                    int biomeWidth = (int) Math.round(biome.width * (double) width);
                    int biomeHeight = (int) Math.round(biome.height * (double) height);
                    for (BiomeStructureData data : biome.structures) {
                        long localSeed = seed;
                        futures.add(CompletableFuture.supplyAsync(()-> {
                            long threadStartTime = System.currentTimeMillis();
                            BiomeStructure structure = new BiomeStructure(data, localSeed, biomeWidth, biomeHeight);
                            structure.initialize();
                            structureDataMap.put(data, structure);
                            return measureGenerationTime("wavefunctioncollapse " + data.sourcePath, threadStartTime);
                        }).exceptionally(ex -> {
                            ex.printStackTrace();
                            return 0L;
                        }));
                    }
                }
            }
            CompletableFuture<?>[] futuresArray = futures.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(futuresArray).join();
            futures.clear();

//////////////////
///////// calculation each biome position based on noise and radius
//////////////////
            for (BiomeData biome : data.GetBiomes()) {

                biomeIndex[0]++;
                int biomeXStart = (int) Math.round(biome.startPointX * (double) width);
                int biomeYStart = (int) Math.round(biome.startPointY * (double) height);
                int biomeWidth = (int) Math.round(biome.width * (double) width);
                int biomeHeight = (int) Math.round(biome.height * (double) height);

                int beginX = Math.max(biomeXStart - biomeWidth / 2, 0);
                int beginY = Math.max(biomeYStart - biomeHeight / 2, 0);
                int endX = Math.min(biomeXStart + biomeWidth / 2, width);
                int endY = Math.min(biomeYStart + biomeHeight / 2, height);
                if (biome.width == 1.0 && biome.height == 1.0) {
                    beginX = 0;
                    beginY = 0;
                    endX = width;
                    endY = height;
                }
                for (int x = beginX; x < endX; x++) {
                    for (int y = beginY; y < endY; y++) {
                        //value 0-1 based on noise
                        float noiseValue = ((float) noise.eval(x / (float) width * noiseZoom, y / (float) height * noiseZoom) + 1) / 2f;
                        noiseValue *= biome.noiseWeight;
                        //value 0-1 based on dist to origin
                        float distanceValue = ((float) Math.sqrt((x - biomeXStart) * (x - biomeXStart) + (y - biomeYStart) * (y - biomeYStart))) / (Math.max(biomeWidth, biomeHeight) / 2f);
                        distanceValue *= biome.distWeight;
                        if (noiseValue + distanceValue < 1.0 || biome.invertHeight && (1 - noiseValue) + distanceValue < 1.0) {
                            Color color = biome.GetColor();
                            float[] hsv = new float[3];
                            color.toHsv(hsv);
                            int count = (int) ((noiseValue - 0.5) * 10 / 4);
                            //hsv[2]+=(count*0.2);
                            biomeMap[x][y] |= (1L << biomeIndex[0]);
                            int terrainCounter = 1;
                            terrainMap[x][y] = 0;
                            if (biome.terrain != null) {
                                for (BiomeTerrainData terrain : biome.terrain) {
                                    float terrainNoise = ((float) noise.eval(x / (float) width * (noiseZoom * terrain.resolution), y / (float) height * (noiseZoom * terrain.resolution)) + 1) / 2;
                                    if (terrainNoise >= terrain.min && terrainNoise <= terrain.max) {
                                        terrainMap[x][y] = terrainCounter;
                                        //pix.fillRectangle(x*data.miniMapTileSize, y*data.miniMapTileSize,data.miniMapTileSize,data.miniMapTileSize);
                                    }
                                    terrainCounter++;
                                }
                            }
                            if (biome.collision)
                                terrainMap[x][y] |= collisionBit;
                            if (biome.structures != null) {
                                for (BiomeStructureData data : biome.structures) {
                                    while (!structureDataMap.containsKey(data)) {
                                        try {
                                            Thread.sleep(10);
                                        } catch (InterruptedException e) {
                                            throw new RuntimeException(e);
                                        }
                                    }

                                    BiomeStructure structure = structureDataMap.get(data);
                                    int structureXStart = x - (biomeXStart - biomeWidth / 2) - (int) ((data.x * biomeWidth) - (data.width * biomeWidth / 2));
                                    int structureYStart = y - (biomeYStart - biomeHeight / 2) - (int) ((data.y * biomeHeight) - (data.height * biomeHeight / 2));

                                    int structureIndex = structure.objectID(structureXStart, structureYStart);
                                    if (structureIndex >= 0) {

                                        terrainMap[x][y] = terrainCounter + structureIndex;
                                        if (structure.collision(structureXStart, structureYStart))
                                            terrainMap[x][y] |= collisionBit;
                                        terrainMap[x][y] |= isStructureBit;

                                    }

                                    terrainCounter += structure.structureObjectCount();
                                }
                            }
                        }

                    }
                }
            }
            currentTime[0] = measureGenerationTime("biomes in total", currentTime[0]);

//////////////////
///////// set poi placement
//////////////////
            List<PointOfInterest> towns = new ArrayList<>();
            List<PointOfInterest> notTowns = new ArrayList<>();
            // Legacy 8×8 exclusion shared by every POI type (centers ≥~4 tiles apart).
            List<Rectangle> otherPoints = new ArrayList<>();
            // Town/capital-only spacing; never added to otherPoints so caves/dungeons stay dense.
            List<Rectangle> townSpacingPoints = new ArrayList<>();

            TextureAtlas mapMarker = Config.instance().getAtlas(Paths.MAP_MARKER);
            TextureData texture = mapMarker.getTextures().first().getTextureData();
            if (!texture.isPrepared())
                texture.prepare();
            Pixmap mapMarkerPixmap = texture.consumePixmap();
            clearTerrain((int) (data.width * data.playerStartPosX), (int) (data.height * data.playerStartPosY), 10);

            // Town spacing on (Ascendant): capitals first, then towns, then everything else, so capitals are
            // never skipped. Off (stock worlds): the original biome-by-biome order, so seeds give the same maps.
            List<BiomeData> biomes = data.GetBiomes();
            boolean legacyPlacement = data.minTownSpacing <= 0;
            List<int[]> placeOrder = new ArrayList<>(); // [biomeIndex, poiIndexInBiomeList]
            if (legacyPlacement) {
                for (int bi = 0; bi < biomes.size(); bi++)
                    for (int pi = 0; pi < biomes.get(bi).getPointsOfInterest().size(); pi++)
                        placeOrder.add(new int[]{bi, pi});
            }
            for (int phase = 0; phase < 3 && !legacyPlacement; phase++) {
                for (int bi = 0; bi < biomes.size(); bi++) {
                    ArrayList<PointOfInterestData> pois = biomes.get(bi).getPointsOfInterest();
                    for (int pi = 0; pi < pois.size(); pi++) {
                        PointOfInterestData poi = pois.get(pi);
                        String type = poi.type;
                        boolean isCapital = type != null && type.equals("capital");
                        boolean isTown = type != null && type.equals("town");
                        if (phase == 0 && isCapital)
                            placeOrder.add(new int[]{bi, pi});
                        else if (phase == 1 && isTown)
                            placeOrder.add(new int[]{bi, pi});
                        else if (phase == 2 && !isCapital && !isTown)
                            placeOrder.add(new int[]{bi, pi});
                    }
                }
            }

            // MV2 / small set planes: cap full-map restarts so generation cannot loop forever.
            final int maxRestarts = data.maxPoiPlacementRestarts > 0
                    ? data.maxPoiPlacementRestarts : Integer.MAX_VALUE;
            int restartCount = 0;
            boolean running = true;
            here:
            while (running) {
                mapPoiIds = new PointOfInterestMap(getChunkSize(), data.tileSize, data.width / getChunkSize(), data.height / getChunkSize());
                running = false;
                for (int[] job : placeOrder) {
                    int biomeIndex2 = job[0];
                    BiomeData biome = biomes.get(biomeIndex2);
                    PointOfInterestData poi = biome.getPointsOfInterest().get(job[1]);
                    boolean isCapital = poi.type != null && poi.type.equals("capital");
                    boolean isTown = poi.type != null && poi.type.equals("town");
                    boolean isTownOrCapital = isCapital || isTown;
                    for (int i = 0; i < poi.count; i++) {
                        for (int counter = 0; counter < 500; counter++)//tries 500 times to find a free point
                        {
                            float radius = (float) Math.sqrt(((random.nextDouble()) / 2 * poi.radiusFactor));
                            float theta = (float) (random.nextDouble() * 2 * Math.PI);
                            float x = (float) (radius * Math.cos(theta));
                            x *= (biome.width * width / 2);
                            x += (biome.startPointX * width);
                            float y = (float) (radius * Math.sin(theta));
                            y *= (biome.height * height / 2);
                            y += (height - (biome.startPointY * height));

                            y += (poi.offsetY * (biome.height * height));
                            x += (poi.offsetX * (biome.width * width));

                            if ((int) x < 0 || (int) y <= 0 || (int) y >= height || (int) x >= width || biomeIndex2 != highestBiome(getBiome((int) x, (int) y))) {
                                continue;
                            }

                            x *= data.tileSize;
                            y *= data.tileSize;

                            boolean blocked = pointBlocked(x, y, otherPoints)
                                    || (isTownOrCapital && data.minTownSpacing > 0
                                    && pointBlocked(x, y, townSpacingPoints));
                            if (blocked) {
                                boolean foundSolution = false;
                                boolean legacyGaveUp = false;
                                for (int xi = -1; xi < 2 && !foundSolution && !legacyGaveUp; xi++) {
                                    for (int yi = -1; yi < 2 && !foundSolution && !legacyGaveUp; yi++) {
                                        float nx = x + xi * data.tileSize;
                                        float ny = y + yi * data.tileSize;
                                        if (pointBlocked(nx, ny, otherPoints)) {
                                            // Stock worlds keep the original search, which stopped at the first
                                            // blocked neighbour and retried at random instead.
                                            legacyGaveUp = legacyPlacement;
                                            continue;
                                        }
                                        if (isTownOrCapital && data.minTownSpacing > 0
                                                && pointBlocked(nx, ny, townSpacingPoints))
                                            continue;
                                        foundSolution = true;
                                        x = nx;
                                        y = ny;
                                    }
                                }
                                if (!foundSolution) {
                                    if (counter == 499) {
                                        // Skip only regular towns under minTownSpacing — never capitals.
                                        if (isTown && !isCapital && data.minTownSpacing > 0) {
                                            System.err.print("Can not place town POI " + poi.name
                                                    + " with minTownSpacing=" + data.minTownSpacing
                                                    + "...Skipping instance.\n");
                                            break;
                                        }
                                        // MV2: when restart budget is exhausted, skip this instance
                                        // instead of looping forever on a tiny set plane.
                                        if (restartCount >= maxRestarts) {
                                            System.err.print("Can not place POI " + poi.name
                                                    + "...Skipping after " + restartCount + " restarts.\n");
                                            break;
                                        }
                                        System.err.print("Can not place POI " + poi.name + "...Rerunning..\n");
                                        running = true;
                                        restartCount++;
                                        towns.clear();
                                        notTowns.clear();
                                        otherPoints.clear();
                                        townSpacingPoints.clear();
                                        clearTerrain((int) (data.width * data.playerStartPosX), (int) (data.height * data.playerStartPosY), 10);
                                        storedInfo.clear();
                                        continue here;
                                    }
                                    continue;
                                }
                            }
                            // Always the legacy 8×8 box for every POI.
                            otherPoints.add(new Rectangle(
                                    x - data.tileSize * 4,
                                    y - data.tileSize * 4,
                                    data.tileSize * 8,
                                    data.tileSize * 8));
                            // Town/capital spacing lives in its own list so non-towns stay dense.
                            if (isTownOrCapital && data.minTownSpacing > 0) {
                                float half = Math.max(4f, (float) data.minTownSpacing);
                                townSpacingPoints.add(new Rectangle(
                                        x - data.tileSize * half,
                                        y - data.tileSize * half,
                                        data.tileSize * half * 2f,
                                        data.tileSize * half * 2f));
                            }
                            PointOfInterest newPoint = new PointOfInterest(poi, new Vector2(x, y), random);
                            clearTerrain((int) (x / data.tileSize), (int) (y / data.tileSize), 3);
                            mapPoiIds.add(newPoint);

                            TextureAtlas.AtlasRegion marker = mapMarker.findRegion(poi.type);

                            if (marker != null) {
                                int xInPixels = (int) ((x / data.tileSize) * data.miniMapTileSize);
                                int yInPixels = (int) ((height - (y / data.tileSize)) * data.miniMapTileSize);
                                xInPixels -= (marker.getRegionWidth() / 2);
                                yInPixels -= (marker.getRegionHeight() / 2);
                                drawPixmapLater(mapMarkerPixmap, marker.getRegionX(), marker.getRegionY(),
                                        marker.getRegionWidth(), marker.getRegionHeight(), xInPixels, yInPixels, marker.getRegionWidth(), marker.getRegionHeight());
                            }


                            if (isTownOrCapital) {
                                if (!newPoint.hasDisplayName()) {
                                    if (poi.displayName == null || poi.displayName.isEmpty()) {
                                        newPoint.setDisplayName(biome.getNewTownName());
                                    } else {
                                        newPoint.setDisplayName(poi.getDisplayName());
                                    }
                                }
                                towns.add(newPoint);
                            } else {
                                notTowns.add(newPoint);
                            }
                            break;
                        }
                    }
                }
            }
            currentTime[0] = measureGenerationTime("poi placement", currentTime[0]);

//////////////////
///////// sort towns and build roads in between
//////////////////
            List<Pair<PointOfInterest, PointOfInterest>> allSortedTowns = new ArrayList<>();

            HashSet<Long> usedEdges = new HashSet<>();//edge is first 32 bits id of first id and last 32 bits id of second
            for (int i = 0; i < towns.size() - 1; i++) {

                PointOfInterest current = towns.get(i);
                int smallestIndex = -1;
                int secondSmallestIndex = -1;
                float smallestDistance = Float.MAX_VALUE;
                for (int j = 0; j < towns.size(); j++) {

                    if (i == j || usedEdges.contains((long) i | ((long) j << 32)))
                        continue;
                    float dist = current.getPosition().dst(towns.get(j).getPosition());
                    if (dist > data.maxRoadDistance)
                        continue;
                    if (dist < smallestDistance) {
                        smallestDistance = dist;
                        secondSmallestIndex = smallestIndex;
                        smallestIndex = j;

                    }
                }
                if (smallestIndex < 0)
                    continue;
                usedEdges.add((long) i | ((long) smallestIndex << 32));
                usedEdges.add((long) i << 32 | ((long) smallestIndex));
                allSortedTowns.add(Pair.of(current, towns.get(smallestIndex)));

                if (secondSmallestIndex < 0)
                    continue;
                usedEdges.add((long) i | ((long) secondSmallestIndex << 32));
                usedEdges.add((long) i << 32 | ((long) secondSmallestIndex));
                //allSortedTowns.add(Pair.of(current, towns.get(secondSmallestIndex)));
            }
            List<Pair<PointOfInterest, PointOfInterest>> allPOIPathsToNextTown = new ArrayList<>();
            for (int i = 0; i < notTowns.size() - 1; i++) {

                PointOfInterest poi = notTowns.get(i);
                int smallestIndex = -1;
                float smallestDistance = Float.MAX_VALUE;
                for (int j = 0; j < towns.size(); j++) {

                    float dist = poi.getPosition().dst(towns.get(j).getPosition());
                    if (dist < smallestDistance) {
                        smallestDistance = dist;
                        smallestIndex = j;

                    }
                }
                if (smallestIndex < 0)
                    continue;
                allPOIPathsToNextTown.add(Pair.of(poi, towns.get(smallestIndex)));
            }
            biomeIndex[0]++;

            //reset terrain path to the next town
            for (Pair<PointOfInterest, PointOfInterest> poiToTown : allPOIPathsToNextTown) {
                futures.add(CompletableFuture.supplyAsync(()-> {
                    int startX = (int) poiToTown.getKey().getTilePosition(data.tileSize).x;
                    int startY = (int) poiToTown.getKey().getTilePosition(data.tileSize).y;
                    int x1 = (int) poiToTown.getValue().getTilePosition(data.tileSize).x;
                    int y1 = (int) poiToTown.getValue().getTilePosition(data.tileSize).y;
                    int dx = Math.abs(x1 - startX);
                    int dy = Math.abs(y1 - startY);
                    int sx = startX < x1 ? 1 : -1;
                    int sy = startY < y1 ? 1 : -1;
                    int err = dx - dy;
                    int e2;
                    for (int i = 0; i < 1000; i++) {
                        if (startX < 0 || startY <= 0 || startX >= width || startY > height) continue;
                        if ((terrainMap[startX][height - startY] & collisionBit) != 0)//clear terrain if it has collision
                            terrainMap[startX][height - startY] = 0;

                        if (startX == x1 && startY == y1)
                            break;
                        e2 = 2 * err;
                        if (e2 > -dy) {
                            err = err - dy;
                            startX = startX + sx;
                        } else if (e2 < dx) {
                            err = err + dx;
                            startY = startY + sy;
                        }
                    }
                    return 0L;
                }).exceptionally(ex -> {
                    ex.printStackTrace();
                    return 0L;
                }));
            }
            futuresArray = futures.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(futuresArray).join();
            futures.clear();
            for (Pair<PointOfInterest, PointOfInterest> townPair : allSortedTowns) {
                futures.add(CompletableFuture.supplyAsync(()-> {
                    int startX = (int) townPair.getKey().getTilePosition(data.tileSize).x;
                    int startY = (int) townPair.getKey().getTilePosition(data.tileSize).y;
                    int x1 = (int) townPair.getValue().getTilePosition(data.tileSize).x;
                    int y1 = (int) townPair.getValue().getTilePosition(data.tileSize).y;
                    for (int x = startX - 1; x < startX + 2; x++) {
                        for (int y = startY - 1; y < startY + 2; y++) {
                            if (x < 0 || y < 0 || x >= width || y >= height) continue;
                            biomeMap[x][height - y - 1] |= (1L << biomeIndex[0]);
                            terrainMap[x][height - y - 1] = 0;
                        }
                    }
                    int dx = Math.abs(x1 - startX);
                    int dy = Math.abs(y1 - startY);
                    int sx = startX < x1 ? 1 : -1;
                    int sy = startY < y1 ? 1 : -1;
                    int err = dx - dy;
                    int e2;
                    for (int i = 0; i < 1000; i++) {
                        if (startX < 0 || startY <= 0 || startX >= width || startY > height) continue;
                        biomeMap[startX][height - startY] |= (1L << biomeIndex[0]);
                        terrainMap[startX][height - startY] = 0;

                        if (startX == x1 && startY == y1)
                            break;
                        e2 = 2 * err;
                        if (e2 > -dy) {
                            err = err - dy;
                            startX = startX + sx;
                        } else if (e2 < dx) {
                            err = err + dx;
                            startY = startY + sy;
                        }
                    }
                    return 0L;
                }).exceptionally(ex -> {
                    ex.printStackTrace();
                    return 0L;
                }));
            }
            futuresArray = futures.toArray(new CompletableFuture<?>[0]);
            CompletableFuture.allOf(futuresArray).join();
            futures.clear();
            currentTime[0] = measureGenerationTime("roads", currentTime[0]);

//////////////////
///////// draw mini map
//////////////////

            Pixmap pix = new Pixmap(width * data.miniMapTileSize, height * data.miniMapTileSize, Pixmap.Format.RGBA8888);
            pix.setColor(1, 0, 0, 1);
            pix.fill();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    if (highestBiome(biomeMap[x][y]) >= data.GetBiomes().size()) {
                        Pixmap smallPixmap = createSmallPixmap(data.roadTileset.tilesetAtlas, data.roadTileset.tilesetName, 0);
                        pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                    } else {

                        BiomeData biome = data.GetBiomes().get(highestBiome(biomeMap[x][y]));
                        int terrainIndex = terrainMap[x][y] & ~terrainMask;
                        if (terrainIndex > biome.terrain.length) {
                            Pixmap smallPixmap = createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, 0);
                            pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);

                            terrainIndex -= biome.terrain.length;
                            terrainIndex--;
                            for (BiomeStructureData structData : biome.structures) {
                                if (terrainIndex >= structData.mappingInfo.length) {
                                    terrainIndex -= structData.mappingInfo.length;
                                    continue;
                                }
                                smallPixmap = createSmallPixmap(structData.structureAtlasPath, structData.mappingInfo[terrainIndex].name, 0);
                                pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                                break;
                            }
                        } else {
                            Pixmap smallPixmap = createSmallPixmap(biome.tilesetAtlas, biome.tilesetName, terrainIndex);
                            pix.drawPixmap(smallPixmap, x * data.miniMapTileSize, y * data.miniMapTileSize);
                        }

                    }

                }

            }
            for (Map.Entry<String, Pair<Pixmap, HashMap<String, Pixmap>>> entry : pixmapHash.entrySet()) {
                try {
                    entry.getValue().getLeft().dispose();
                } catch (Exception e) {
                    //e.printStackTrace();
                }
                for (Map.Entry<String, Pixmap> pairEntry : entry.getValue().getRight().entrySet()) {
                    try {
                        pairEntry.getValue().dispose();
                    } catch (Exception e) {
                        //e.printStackTrace();
                    }
                }
            }
            pixmapHash.clear();
            try {
                drawPixmapNow(pix);
            } catch (Exception e) {
                //e.printStackTrace();
            }
            currentTime[0] = measureGenerationTime("mini map", currentTime[0]);


//////////////////
///////// distribute small rocks and trees across the map
//////////////////
            mapObjectIds = new SpritesDataMap(getChunkSize(), data.tileSize, data.width / getChunkSize());
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    int invertedHeight = height - y - 1;
                    int currentBiome = highestBiome(biomeMap[x][invertedHeight]);
                    if (currentBiome >= data.GetBiomes().size())
                        continue;//roads
                    if (isStructure(x, y))
                        continue;
                    BiomeData biome = data.GetBiomes().get(currentBiome);
                    for (String name : biome.spriteNames) {
                        BiomeSpriteData sprite = data.GetBiomeSprites().getSpriteData(name);
                        double spriteNoise = (noise.eval(x / (double) width * noiseZoom * sprite.resolution, y / (double) invertedHeight * noiseZoom * sprite.resolution) + 1) / 2;
                        if (spriteNoise >= sprite.startArea && spriteNoise <= sprite.endArea) {
                            if (random.nextFloat() <= sprite.density) {
                                String spriteKey = sprite.key();
                                int key;
                                if (!mapObjectIds.containsKey(spriteKey)) {

                                    key = mapObjectIds.put(sprite.key(), sprite, data.GetBiomeSprites());
                                } else {
                                    key = mapObjectIds.intKey(spriteKey);
                                }
                                mapObjectIds.putPosition(key, new Vector2((((float) x) + .25f + random.nextFloat() / 2) * data.tileSize, (((float) y + .25f) - random.nextFloat() / 2) * data.tileSize));
                                break;//only on sprite per point
                            }
                        }
                    }
                }
            }
            mapMarkerPixmap.dispose();
            biomeImage = pix;
            measureGenerationTime("sprites", currentTime[0]);
            long elapsedMs = System.currentTimeMillis() - startTime;
            Runtime rt = Runtime.getRuntime();
            long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
            long totalMb = rt.totalMemory() / (1024L * 1024L);
            System.out.println("Generating world took :\t\t" + (elapsedMs / 1000f) + " s");
            System.out.println("World size " + width + "x" + height
                    + " | towns=" + towns.size()
                    + " | minTownSpacing=" + data.minTownSpacing
                    + " | heap used~" + usedMb + "MB / total~" + totalMb + "MB");
            if (clearLiveStage) {
                try {
                    WorldStage.getInstance().clearCache();
                } catch (Throwable t) {
                    // Headless / early-init benches may not have a fully built stage.
                    System.out.println("WorldStage.clearCache skipped: " + t.getMessage());
                }
            }

            if (GuiBase.isMobile())
                GuiBase.getInterface().preventSystemSleep(false);
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    HashMap<String, Pair<Pixmap, HashMap<String, Pixmap>>> pixmapHash = new HashMap<>();

    private Pixmap createSmallPixmap(String tilesetName, String key, int i) {

        if (i > 2) i = 2;
        String tileSetNameWithIndex;
        if (i == 0)
            tileSetNameWithIndex = (key);
        else
            tileSetNameWithIndex = (key + "_" + i);
        if (!pixmapHash.containsKey(tilesetName)) {
            TextureAtlas.AtlasRegion region;
            TextureAtlas atlas = Config.instance().getAtlas(tilesetName);
            region = atlas.findRegion(tileSetNameWithIndex);
            TextureData data = region.getTexture().getTextureData();
            if (!data.isPrepared()) {
                data.prepare();
            }
            pixmapHash.put(tilesetName, Pair.of(data.consumePixmap(), new HashMap<>()));
        }
        Pair<Pixmap, HashMap<String, Pixmap>> pair = pixmapHash.get(tilesetName);
        if (!pair.getRight().containsKey(tileSetNameWithIndex)) {
            TextureAtlas atlas = Config.instance().getAtlas(tilesetName);
            TextureAtlas.AtlasRegion region = atlas.findRegion(tileSetNameWithIndex);
            int tileSize = data.tileSize;
            Pixmap smallPixmap = new Pixmap(data.miniMapTileSize, data.miniMapTileSize, Pixmap.Format.RGBA8888);
            smallPixmap.setColor(0, 0, 0, 0);
            smallPixmap.fill();
            smallPixmap.drawPixmap(pair.getLeft(), 0, 0, region.getRegionX(), region.getRegionY(), data.miniMapTileSize, data.miniMapTileSize);
            pair.getRight().put(tileSetNameWithIndex, smallPixmap);
        }
        return pair.getRight().get(tileSetNameWithIndex);

    }

    static class DrawInfo {
        Pixmap mapMarkerPixmap;
        int regionX;
        int regionY;
        int regionWidth;
        int regionHeight;
        int x;
        int y;
        int regionWidth1;
        int regionHeight1;
    }

    final Array<DrawInfo> storedInfo = new Array<>();

    private void drawPixmapLater(Pixmap mapMarkerPixmap, int regionX, int regionY, int regionWidth, int regionHeight, int x, int y, int regionWidth1, int regionHeight1) {
        DrawInfo info = new DrawInfo();
        info.mapMarkerPixmap = mapMarkerPixmap;
        info.regionX = regionX;
        info.regionY = regionY;
        info.regionWidth = regionWidth;
        info.regionHeight = regionHeight;
        info.x = x;
        info.y = y;
        info.regionWidth1 = regionWidth1;
        info.regionHeight1 = regionHeight1;
        storedInfo.add(info);
    }

    private void drawPixmapNow(Pixmap map) {
        for (DrawInfo info : storedInfo)
            map.drawPixmap(info.mapMarkerPixmap, info.regionX, info.regionY, info.regionWidth, info.regionHeight, info.x, info.y, info.regionWidth1, info.regionHeight1);
        storedInfo.clear();
    }

    public int getWidthInTiles() {
        return width;
    }

    public int getHeightInTiles() {
        return height;
    }

    public int getWidthInPixels() {
        return width * data.tileSize;
    }

    public int getHeightInPixels() {
        return height * data.tileSize;
    }

    public int getWidthInChunks() {
        return width / getChunkSize();
    }

    public int getHeightInChunks() {
        return height / getChunkSize();
    }

    public int getTileSize() {
        return data.tileSize;
    }

    /** True when the tile is marked as a road (highest biome bit past the biome list). */
    public boolean isRoad(int tileX, int tileY) {
        if (data == null || biomeMap == null)
            return false;
        try {
            return highestBiome(getBiome(tileX, tileY)) >= data.GetBiomes().size();
        } catch (Exception e) {
            return false;
        }
    }

    public Pixmap getBiomeImage() {
        return biomeImage;
    }

    public List<Pair<Vector2, Integer>> GetMapObjects(int chunkX, int chunkY) {
        return mapObjectIds.positions(chunkX, chunkY);
    }

    public List<PointOfInterest> getPointsOfInterest(Actor player) {
        return mapPoiIds.pointsOfInterest((int) player.getX() / data.tileSize / getChunkSize(), (int) player.getY() / data.tileSize / getChunkSize());
    }

    public List<PointOfInterest> getPointsOfInterest(int chunkX, int chunkY) {
        return mapPoiIds.pointsOfInterest(chunkX, chunkY);
    }

    public PointOfInterest findPointsOfInterest(String name) {
        return mapPoiIds.findPointsOfInterest(name);
    }

    public List<PointOfInterest> getAllPointOfInterest() {
        if (mapPoiIds == null) {
            return java.util.Collections.emptyList();
        }
        return mapPoiIds.getAllPointOfInterest();
    }

    /**
     * MV2 / tests: install an empty POI map and world grid so gate placement helpers
     * can run without a full generate. No-op when {@code worldData} is null.
     */
    public void installTestWorldGrid(WorldData worldData) {
        installTestWorldGrid(worldData, 0L);
    }

    /** Same as {@link #installTestWorldGrid(WorldData)} with an explicit world seed. */
    public void installTestWorldGrid(WorldData worldData, long worldSeed) {
        if (worldData == null) {
            return;
        }
        this.data = worldData;
        this.width = Math.max(1, worldData.width);
        this.height = Math.max(1, worldData.height);
        this.seed = worldSeed;
        this.terrainMap = new int[this.width][this.height];
        this.biomeMap = new long[this.width][this.height];
        final int tile = worldData.tileSize > 0 ? worldData.tileSize : 16;
        final int chunk = 16;
        final int chunksX = Math.max(1, this.width / chunk);
        final int chunksY = Math.max(1, this.height / chunk);
        this.mapPoiIds = new PointOfInterestMap(chunk, tile, chunksX, chunksY);
        this.mapObjectIds = new SpritesDataMap(chunk, tile, chunksX);
        this.worldDataLoaded = true;
    }

    /**
     * Tests: pack seed / maps / POIs (the co-op hash inputs + gates) without pixmaps
     * or Config. Pair with {@link #restoreHashableStateFromSave}.
     */
    public SaveFileData saveHashableStateForTest() {
        SaveFileData out = new SaveFileData();
        out.storeObject("biomeMap", biomeMap);
        out.storeObject("terrainMap", terrainMap);
        out.store("width", width);
        out.store("height", height);
        out.store("seed", seed);
        if (mapPoiIds != null) {
            out.store("mapPoiIds", mapPoiIds.save());
        }
        return out;
    }

    /**
     * Tests: restore seed / maps / POIs from {@link #saveHashableStateForTest()} without
     * reloading world.json. Caller must {@link #installTestWorldGrid} first.
     */
    public void restoreHashableStateFromSave(SaveFileData saveFileData) {
        if (saveFileData == null) {
            return;
        }
        biomeMap = (long[][]) saveFileData.readObject("biomeMap");
        terrainMap = (int[][]) saveFileData.readObject("terrainMap");
        width = saveFileData.readInt("width");
        height = saveFileData.readInt("height");
        seed = saveFileData.readLong("seed");
        if (mapPoiIds != null && saveFileData.containsKey("mapPoiIds")) {
            mapPoiIds.load(saveFileData.readSubData("mapPoiIds"));
        }
    }

    public int getChunkSize() {
        return (Math.max(Scene.getIntendedWidth(), Scene.getIntendedHeight())) / data.tileSize;
    }

    public void dispose() {
        drawingInfoCache.clear();
        disposeBiomeTexturesAsync();
        Forge.safeDispose(biomeImage, globalTileDrawing, globalTexture, emptyTile);
        biomeImage = null;
        globalTileDrawing = null;
        globalTexture = null;
        emptyTile = null;
        worldDataLoaded = false;
    }

    /**
     * CO5: clear world data after a guest leaves co-op so StartScene hides Save/Resume
     * and the guest must Load/Continue from disk. Disposes textures then nulls {@link #data}.
     */
    public void unloadData() {
        try {
            dispose();
        } catch (final Exception ignored) {
        }
        data = null;
        worldDataLoaded = false;
        seed = 0;
        width = 0;
        height = 0;
    }

    /** Dispose {@link BiomeTexture} pixmaps on the GL/EDT thread when available. */
    void disposeBiomeTexturesAsync() {
        final BiomeTexture[] old = biomeTexture;
        biomeTexture = null;
        if (old == null) {
            return;
        }
        Runnable release = () -> {
            for (BiomeTexture bt : old) {
                if (bt != null) {
                    bt.dispose();
                }
            }
        };
        try {
            if (com.badlogic.gdx.Gdx.app != null) {
                com.badlogic.gdx.Gdx.app.postRunnable(release);
            } else {
                release.run();
            }
        } catch (Exception e) {
            release.run();
        }
    }

    public void setSeed(long seedOffset) {
        random.setSeed(seedOffset + seed);
    }

    public Texture getGlobalTexture() {
        if (globalTexture == null) {
            globalTexture = Forge.getAssets().getTexture(Config.instance().getFile("ui/sprite_markers.png"), true, true);
            System.out.print("Loading auxiliary sprites.\n");
        }
        return globalTexture;
    }
}
