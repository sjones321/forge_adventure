package forge.adventure.world;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.BiomeData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.WorldData;
import forge.adventure.util.Config;
import forge.card.CardEdition;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * MV2 plane-per-set generator: customise the Ascendant set-plane template from a set's
 * color balance, then produce themed town names and planar-gate POIs.
 *
 * <p>Full world grids are still built by {@link World#generateNew}; this class only
 * prepares the {@link WorldData} (size, biome mix, town names, portal POIs).
 */
public final class SetPlaneGenerator {
    public static final String PLANE_ID_PREFIX = "set_";
    public static final String PLANAR_GATE_POI = "PlanarGate";

    private SetPlaneGenerator() {
    }

    public static String planeIdForSet(String setCode) {
        if (setCode == null || setCode.isEmpty()) {
            throw new IllegalArgumentException("setCode required");
        }
        return PLANE_ID_PREFIX + setCode.toLowerCase(Locale.ROOT);
    }

    public static String setCodeFromPlaneId(String planeId) {
        if (planeId == null || planeId.isEmpty()) {
            return "";
        }
        if (planeId.regionMatches(true, 0, PLANE_ID_PREFIX, 0, PLANE_ID_PREFIX.length())) {
            return planeId.substring(PLANE_ID_PREFIX.length()).toUpperCase(Locale.ROOT);
        }
        // Accept bare set codes used as plane ids (MV1 console create).
        if (!PlaneMeta.HOME_ID.equalsIgnoreCase(planeId) && planeId.length() <= 8
                && planeId.chars().allMatch(ch -> Character.isLetterOrDigit(ch))) {
            return planeId.toUpperCase(Locale.ROOT);
        }
        return "";
    }

    public static String displayNameForSet(String setCode) {
        if (setCode == null || setCode.isEmpty()) {
            return "Set Plane";
        }
        try {
            if (FModel.getMagicDb() != null) {
                CardEdition ed = FModel.getMagicDb().getEditions().get(setCode);
                if (ed != null && ed.getName() != null && !ed.getName().isEmpty()) {
                    return ed.getName();
                }
            }
        } catch (Throwable ignored) {
            // headless / missing DB
        }
        return setCode;
    }

    public static String templatePath(ConfigData cfg) {
        if (cfg != null && cfg.setPlaneWorldConfig != null && !cfg.setPlaneWorldConfig.isEmpty()) {
            return cfg.setPlaneWorldConfig;
        }
        return "world/set_plane_world.json";
    }

    /** Load the set-plane template JSON into a fresh {@link WorldData}. */
    public static WorldData loadTemplate(ConfigData cfg) {
        String path = templatePath(cfg);
        FileHandle handle = Config.instance().getFile(path);
        if (handle == null || !handle.exists()) {
            throw new IllegalStateException("Missing set-plane template: " + path);
        }
        return new Json().fromJson(WorldData.class, handle.readString());
    }

    /**
     * Customise a template for {@code setCode}. Deterministic for the same
     * template + balance + seed (size clamp uses seed for the 300–400 pick when
     * the template is outside the range).
     */
    public static WorldData customizeForSet(WorldData template, String setCode, SetColorBalance balance, long seed) {
        if (template == null) {
            throw new IllegalArgumentException("template required");
        }
        ConfigData cfg = safeCfg();
        int min = cfg != null ? Math.max(64, cfg.setPlaneMinTiles) : 300;
        int max = cfg != null ? Math.max(min, cfg.setPlaneMaxTiles) : 400;

        WorldData data = shallowCopyWorld(template);
        Random rng = new Random(seed ^ (setCode != null ? setCode.hashCode() : 0) * 0x9E3779B97F4A7C15L);

        int size = clampSize(data.width, min, max, rng);
        data.width = size;
        data.height = size;

        SetColorBalance bal = balance != null ? balance : SetColorBalance.equal();
        applyBiomeMix(data, bal);
        try {
            scalePoiCountsForShrunkBiomes(data, size);
        } catch (Throwable t) {
            // Headless / missing POI JSON — size and biome mix still apply.
        }
        applyThemedTownNames(data, setCode, rng);
        int maxRestarts = cfg != null ? Math.max(1, cfg.setPlaneMaxPlacementRestarts) : 8;
        data.maxPoiPlacementRestarts = maxRestarts;
        try {
            injectPlanarGatePoi(data, PlaneMeta.HOME_ID, "Portal to Home");
        } catch (Throwable t) {
            // Headless / missing Config — biome mix and town names still apply.
        }
        return data;
    }

    /**
     * Scale town/capital/dungeon counts with biome footprint and map size so
     * shrunken set planes do not try to place a full-size POI budget.
     */
    public static void scalePoiCountsForShrunkBiomes(WorldData data, int mapSize) {
        if (data == null) {
            return;
        }
        float mapFactor = (mapSize * (float) mapSize) / (350f * 350f);
        mapFactor = Math.max(0.2f, Math.min(1.2f, mapFactor));
        List<BiomeData> biomes = data.GetBiomes();
        if (biomes == null) {
            return;
        }
        for (BiomeData biome : biomes) {
            if (biome == null) {
                continue;
            }
            float area = Math.max(0.05f, biome.width * biome.height);
            float factor = (float) Math.sqrt(area * mapFactor);
            biome.scaleAndFreezePois(factor);
        }
    }

    /** Size in {@code [min, max]}; keeps template size when already in range. */
    public static int clampSize(int templateSize, int min, int max, Random rng) {
        if (templateSize >= min && templateSize <= max) {
            return templateSize;
        }
        if (rng == null) {
            rng = new Random(templateSize);
        }
        return min + rng.nextInt(max - min + 1);
    }

    /**
     * Bias color biomes by set color share: higher share → larger footprint and
     * slightly lower distance weight (easier to place). Base biome is untouched.
     */
    public static void applyBiomeMix(WorldData data, SetColorBalance balance) {
        if (data == null || balance == null) {
            return;
        }
        List<BiomeData> biomes = data.GetBiomes();
        if (biomes == null) {
            return;
        }
        for (BiomeData biome : biomes) {
            if (biome == null || biome.name == null) {
                continue;
            }
            String name = biome.name.toLowerCase(Locale.ROOT);
            if ("base".equals(name)) {
                continue;
            }
            float share = balance.share(name);
            // Keep a floor so every color biome still appears; scale width/height.
            float factor = 0.45f + share * 1.4f; // share 0 → 0.45, share ~0.33 → ~0.91, share 1 → 1.85
            biome.width = clamp01(biome.width * factor, 0.25f, 1.0f);
            biome.height = clamp01(biome.height * factor, 0.25f, 1.0f);
            // Higher share → lower distWeight (fills more of its ellipse).
            float distScale = 1.35f - share; // 0 share → 1.35, high share → closer to 0.35
            biome.distWeight = Math.max(0.35f, biome.distWeight * distScale);
            biome.noiseWeight = Math.max(0.2f, biome.noiseWeight * (0.85f + share * 0.5f));
        }
    }

    /**
     * Replace biome town-name pools with set-themed names so generated towns
     * read as part of that plane's culture.
     */
    public static void applyThemedTownNames(WorldData data, String setCode, Random rng) {
        if (data == null) {
            return;
        }
        String theme = displayNameForSet(setCode);
        String shortTheme = shortTheme(theme, setCode);
        List<BiomeData> biomes = data.GetBiomes();
        if (biomes == null) {
            return;
        }
        String[] suffixes = {
                "Haven", "Reach", "Crossing", "Hollow", "Spire", "Market", "Gate",
                "Watch", "Landing", "Commons", "Ward", "Harbor", "Rest", "Keep"
        };
        for (BiomeData biome : biomes) {
            if (biome == null) {
                continue;
            }
            ArrayList<String> names = new ArrayList<>();
            String biomeLabel = biome.name != null ? capitalize(biome.name) : "Wilds";
            for (int i = 0; i < 24; i++) {
                String suffix = suffixes[(Math.abs((int) (rng.nextLong() >> 1)) + i) % suffixes.length];
                names.add(shortTheme + " " + suffix);
                names.add(biomeLabel + " of " + shortTheme);
            }
            // Deterministic unique-ish list
            LinkedUnique(names);
            biome.replaceTownNames(names);
        }
    }

    private static void LinkedUnique(ArrayList<String> names) {
        ArrayList<String> unique = new ArrayList<>();
        for (String n : names) {
            if (n != null && !n.isEmpty() && !unique.contains(n)) {
                unique.add(n);
            }
        }
        names.clear();
        names.addAll(unique);
    }

    /** Ensure each color biome lists a PlanarGate POI aimed at {@code targetPlaneId}. */
    public static void injectPlanarGatePoi(WorldData data, String targetPlaneId, String displayName) {
        if (data == null) {
            return;
        }
        List<BiomeData> biomes = data.GetBiomes();
        if (biomes == null || biomes.isEmpty()) {
            return;
        }
        // Attach to the first non-base biome so a gate always lands somewhere walkable.
        for (BiomeData biome : biomes) {
            if (biome == null || "base".equalsIgnoreCase(biome.name)) {
                continue;
            }
            String[] pois = biome.pointsOfInterest;
            ArrayList<String> list = new ArrayList<>();
            if (pois != null) {
                for (String p : pois) {
                    list.add(p);
                }
            }
            if (!list.contains(PLANAR_GATE_POI)) {
                list.add(0, PLANAR_GATE_POI);
            }
            biome.pointsOfInterest = list.toArray(new String[0]);
            // Only need one biome to carry the gate for placement count.
            break;
        }
        ensurePlanarGateData(targetPlaneId, displayName);
    }

    /**
     * Ensure the PlanarGate definition is registered (JSON and/or runtime) so
     * saves that contain gates load after a restart. Call from Ascendant Config
     * startup and before any gate placement.
     */
    public static PointOfInterestData ensurePlanarGateRegistered() {
        return ensurePlanarGateData(PlaneMeta.HOME_ID, "Planar Gate");
    }

    /**
     * Register / refresh the PlanarGate {@link PointOfInterestData} entry used by
     * world gen and save load. Safe to call repeatedly.
     */
    public static PointOfInterestData ensurePlanarGateData(String targetPlaneId, String displayName) {
        PointOfInterestData existing = PointOfInterestData.getPointOfInterest(PLANAR_GATE_POI);
        if (existing != null) {
            // Do not overwrite per-instance targetPlane on the shared definition
            // when only registering; placement copies set their own target.
            if (displayName != null && !displayName.isEmpty()
                    && (existing.displayName == null || existing.displayName.isEmpty())) {
                existing.displayName = displayName;
            }
            if (existing.map == null || existing.map.isEmpty()) {
                existing.map = "../common/maps/map/ascendant/planar_gate.tmx";
            }
            if (existing.type == null || existing.type.isEmpty()) {
                existing.type = "planar_gate";
            }
            return existing;
        }
        PointOfInterestData data = new PointOfInterestData();
        data.name = PLANAR_GATE_POI;
        data.displayName = displayName != null ? displayName : "Planar Gate";
        data.type = "planar_gate";
        data.count = 1;
        data.spriteAtlas = "../common/maps/tileset/buildings.atlas";
        data.sprite = "MageTowerBlack";
        data.map = "../common/maps/map/ascendant/planar_gate.tmx";
        data.radiusFactor = 0.5f;
        data.active = true;
        data.targetPlane = targetPlaneId != null ? targetPlaneId : PlaneMeta.HOME_ID;
        PointOfInterestData.registerRuntime(data);
        return data;
    }

    public static WorldData prepareSetPlaneData(String setCode, long seed) {
        ConfigData cfg = safeCfg();
        WorldData template = loadTemplate(cfg);
        SetColorBalance balance = SetColorBalance.fromSet(setCode);
        return customizeForSet(template, setCode, balance, seed);
    }

    private static WorldData shallowCopyWorld(WorldData src) {
        WorldData d = new WorldData();
        d.width = src.width;
        d.height = src.height;
        d.playerStartPosX = src.playerStartPosX;
        d.playerStartPosY = src.playerStartPosY;
        d.noiseZoomBiome = src.noiseZoomBiome;
        d.tileSize = src.tileSize;
        d.miniMapTileSize = src.miniMapTileSize;
        d.roadTileset = src.roadTileset;
        d.biomesSprites = src.biomesSprites;
        d.maxRoadDistance = src.maxRoadDistance;
        d.minTownSpacing = src.minTownSpacing;
        d.maxPoiPlacementRestarts = src.maxPoiPlacementRestarts;
        d.biomesNames = src.biomesNames != null ? src.biomesNames.clone() : null;
        // Force biome reload from names, then deep-customise copies.
        List<BiomeData> loaded = src.GetBiomes();
        if (loaded != null) {
            d.replaceBiomes(copyBiomes(loaded));
        }
        return d;
    }

    private static List<BiomeData> copyBiomes(List<BiomeData> src) {
        List<BiomeData> out = new ArrayList<>();
        for (BiomeData b : src) {
            out.add(copyBiome(b));
        }
        return out;
    }

    private static BiomeData copyBiome(BiomeData src) {
        BiomeData b = new BiomeData();
        b.startPointX = src.startPointX;
        b.startPointY = src.startPointY;
        b.noiseWeight = src.noiseWeight;
        b.distWeight = src.distWeight;
        b.name = src.name;
        b.tilesetAtlas = src.tilesetAtlas;
        b.tilesetName = src.tilesetName;
        b.terrain = src.terrain;
        b.width = src.width;
        b.height = src.height;
        b.color = src.color;
        b.collision = src.collision;
        b.invertHeight = src.invertHeight;
        b.spriteNames = src.spriteNames != null ? src.spriteNames.clone() : null;
        b.enemies = src.enemies != null ? src.enemies.clone() : null;
        b.pointsOfInterest = src.pointsOfInterest != null ? src.pointsOfInterest.clone() : null;
        b.structures = src.structures;
        return b;
    }

    private static String shortTheme(String theme, String setCode) {
        if (theme == null || theme.isEmpty()) {
            return setCode != null ? setCode : "Plane";
        }
        // Prefer the first meaningful word ("Theros", "Innistrad", "Kamigawa").
        String[] parts = theme.split("[\\s:]+");
        for (String p : parts) {
            if (p.length() >= 3 && !p.equalsIgnoreCase("the") && !p.equalsIgnoreCase("of")) {
                return p;
            }
        }
        return theme.length() > 16 ? theme.substring(0, 16) : theme;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    private static float clamp01(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static ConfigData safeCfg() {
        try {
            return Config.instance().getConfigData();
        } catch (Throwable t) {
            return null;
        }
    }
}
