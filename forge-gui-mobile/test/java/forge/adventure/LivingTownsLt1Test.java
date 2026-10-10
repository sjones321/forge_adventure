package forge.adventure;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.ConfigData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.RewardData;
import forge.adventure.data.ShopData;
import forge.adventure.data.TownsfolkData;
import forge.adventure.data.TownsfolkListData;
import forge.adventure.util.Config;
import forge.adventure.util.LivingTownMapSupport;
import forge.adventure.util.LivingTownMapSupport.TmxObjectInfo;
import forge.adventure.util.TemplateTmxMapLoader;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * LT1 living-town behaviour: Havenbrook POI resolves to {@code starter_town.tmx},
 * entry/fallback, shop + townsfolk data validate, missing objects warn without crash.
 * Uses Surefire {@code forge.test.userDir}; never writes the real user dir.
 */
public class LivingTownsLt1Test {

    private Path adventureRes;
    private Path planeDir;
    private Path commonDir;
    private ConfigData cfg;

    @BeforeMethod
    public void setUp() throws Exception {
        AdventureTestUserDir.requireIsolatedUserDir();
        adventureRes = resolveAdventureRes();
        planeDir = adventureRes.resolve("Shandalar Ascendant");
        commonDir = adventureRes.resolve("common");
        Assert.assertTrue(Files.isDirectory(planeDir), "Ascendant plane missing: " + planeDir);
        Assert.assertTrue(Files.isDirectory(commonDir), "common adventure res missing: " + commonDir);

        cfg = new ConfigData();
        cfg.ascendantRules = true;
        cfg.lt1LivingTowns = true;
        cfg.lt1WarnMissingMapObjects = true;
        cfg.lt1FallbackEntryXFraction = 0.5f;
        cfg.lt1FallbackEntryYFraction = 0.15f;
        cfg.lt1StarterTownPoiName = "StarterTown";
        TownsfolkListData.clearCache();
    }

    private static Path resolveAdventureRes() {
        for (String p : new String[]{"forge-gui/res/adventure", "../forge-gui/res/adventure"}) {
            Path path = Paths.get(p);
            if (Files.isDirectory(path)) {
                return path.toAbsolutePath().normalize();
            }
        }
        throw new IllegalStateException("adventure res folder not found");
    }

    @Test
    public void starterTownPoiResolvesAndMapFileExists() throws Exception {
        Path poiFile = planeDir.resolve("world/points_of_interest.json");
        Array<PointOfInterestData> pois = LivingTownMapSupport.loadPoisFromPath(poiFile);
        PointOfInterestData poi = LivingTownMapSupport.findPoi(pois, cfg.lt1StarterTownPoiName);
        Assert.assertNotNull(poi, "StarterTown POI must exist in Ascendant points_of_interest.json");
        Assert.assertEquals(poi.type, "town");
        Assert.assertEquals(poi.count, 1);
        Assert.assertEquals(poi.displayName, "Havenbrook");
        Assert.assertTrue(poi.map != null && poi.map.contains("starter_town.tmx"),
                "POI map should point at starter_town.tmx: " + poi.map);
        List<String> tags = poi.questTags != null ? Arrays.asList(poi.questTags) : List.of();
        Assert.assertFalse(tags.contains("QuestSource"),
                "Havenbrook must not be QuestSource until it has a quest board");
        Assert.assertFalse(tags.contains("Sidequest"),
                "Havenbrook must not be Sidequest until it has a quest board");

        Path map = LivingTownMapSupport.resolvePoiMapFile(planeDir, commonDir, poi.map);
        Assert.assertTrue(Files.isRegularFile(map), "starter_town.tmx must resolve: " + map);

        String colorless = Files.readString(planeDir.resolve("world/biomes/colorless.json"),
                StandardCharsets.UTF_8);
        Assert.assertTrue(colorless.contains("\"StarterTown\""),
                "colorless biome must list StarterTown for placement");
    }

    @Test
    public void starterTownTilesetsResolveThroughRealLoader() {
        Path map = commonDir.resolve("maps/map/ascendant/starter_town.tmx");
        Assert.assertTrue(Files.isRegularFile(map), map.toString());
        FileHandle tmx = new FileHandle(map.toFile());
        TemplateTmxMapLoader loader = new TemplateTmxMapLoader();
        Array<FileHandle> tsx = loader.resolveExternalTilesets(tmx);
        Assert.assertTrue(tsx.size >= 8, "expected every external tileset, got " + tsx.size);
        boolean foundLocalResourceNodes = false;
        for (FileHandle handle : tsx) {
            Assert.assertTrue(handle.exists(), "tileset missing via real loader: " + handle.path());
            if ("resource_nodes.tsx".equals(handle.name())) {
                foundLocalResourceNodes = true;
            }
        }
        Assert.assertTrue(foundLocalResourceNodes,
                "map-local ascendant/resource_nodes.tsx must resolve through TemplateTmxMapLoader");
    }

    @Test
    public void missingTilesetFailsThroughRealLoader() throws Exception {
        Path tmp = Files.createTempDirectory("lt1-tsx-miss");
        Path tmx = tmp.resolve("broken.tmx");
        Files.writeString(tmx,
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<map version=\"1.10\" width=\"1\" height=\"1\" tilewidth=\"16\" tileheight=\"16\">\n"
                        + " <tileset firstgid=\"1\" source=\"does_not_exist.tsx\"/>\n"
                        + "</map>\n",
                StandardCharsets.UTF_8);
        TemplateTmxMapLoader loader = new TemplateTmxMapLoader();
        try {
            loader.resolveExternalTilesets(new FileHandle(tmx.toFile()));
            Assert.fail("expected missing tileset to fail through real loader");
        } catch (Throwable expected) {
            Assert.assertTrue(String.valueOf(expected.getMessage()).contains("does_not_exist.tsx")
                            || String.valueOf(expected.getMessage()).contains("Missing tileset"),
                    "failure should name the missing tileset: " + expected);
        }
    }

    @Test
    public void committedMapHasEntryInnAndShop_entryFallbackWorksWithoutCrash() throws Exception {
        Path map = commonDir.resolve("maps/map/ascendant/starter_town.tmx");
        Assert.assertTrue(Files.isRegularFile(map), map.toString());
        List<TmxObjectInfo> objects = LivingTownMapSupport.parseTmxObjects(map);

        // Current committed map (Steve may still be editing — do not require townsfolk yet).
        Assert.assertTrue(LivingTownMapSupport.hasObjectType(objects, "entry"),
                "committed starter_town.tmx should already have an entry object");
        Assert.assertTrue(LivingTownMapSupport.hasObjectType(objects, "inn"),
                "committed starter_town.tmx should already have an inn object");
        Assert.assertTrue(LivingTownMapSupport.hasObjectType(objects, "shop"),
                "committed starter_town.tmx should already have a shop object");

        // Fallback math when entry is missing (Steve mid-edit): no crash, deterministic pixels.
        float[] pos = LivingTownMapSupport.fallbackEntryPixels(64 * 16f, 48 * 16f,
                cfg.lt1FallbackEntryXFraction, cfg.lt1FallbackEntryYFraction);
        Assert.assertEquals(pos[0], 512f, 0.01f);
        Assert.assertEquals(pos[1], 115.2f, 0.01f);

        String warn = LivingTownMapSupport.missingEntryWarning(map.toString());
        Assert.assertTrue(warn.contains("LT1"));
        Assert.assertTrue(warn.contains("no way out"));
        Assert.assertTrue(warn.contains("fallback"));
        Assert.assertTrue(warn.contains(map.toString()), "warning must name the real map path");
        Assert.assertTrue(LivingTownMapSupport.shouldWarnMissing(cfg));

        ConfigData stock = new ConfigData();
        stock.ascendantRules = false;
        Assert.assertFalse(LivingTownMapSupport.lt1Active(stock));
        Assert.assertFalse(LivingTownMapSupport.shouldWarnMissing(stock));
    }

    @Test
    public void shopAndTownsfolkDataLoadAndValidate() throws Exception {
        Path shopsFile = planeDir.resolve("world/shops.json");
        String shopsJson = Files.readString(shopsFile, StandardCharsets.UTF_8);
        Array<ShopData> shops = new Json().fromJson(Array.class, ShopData.class, shopsJson);
        Assert.assertNotNull(shops);
        Assert.assertTrue(shops.size > 10, "expected many shops, got " + shops.size);

        Set<String> shopNames = LivingTownMapSupport.shopDataNames(shops);
        Assert.assertTrue(shopNames.contains("GeneralStore"), "LT1 GeneralStore must exist");
        Assert.assertTrue(shopNames.contains("White"));
        Assert.assertTrue(shopNames.contains("Planeswalker"));

        ShopData general = null;
        for (ShopData s : new Array.ArrayIterator<>(shops)) {
            if (s != null && "GeneralStore".equals(s.name)) {
                general = s;
                break;
            }
        }
        Assert.assertNotNull(general);
        Set<String> itemNames = new HashSet<>();
        if (general.rewards != null) {
            for (RewardData r : new Array.ArrayIterator<>(general.rewards)) {
                if (r != null && r.itemName != null) {
                    itemNames.add(r.itemName);
                }
            }
        }
        Assert.assertTrue(itemNames.contains("Iron Pickaxe"), "GeneralStore needs tier-1 Iron Pickaxe");
        Assert.assertTrue(itemNames.contains("Iron Sickle"), "GeneralStore needs tier-1 Iron Sickle");
        Assert.assertFalse(itemNames.contains("Farmer's Tools"),
                "Farmer's Tools is 6000g equipment, not a starter tool");

        Path map = commonDir.resolve("maps/map/ascendant/starter_town.tmx");
        List<TmxObjectInfo> objects = LivingTownMapSupport.parseTmxObjects(map);
        Set<String> referenced = LivingTownMapSupport.shopNamesFromObjects(objects);
        Assert.assertFalse(referenced.isEmpty(), "starter_town shop lists should name shops");
        Set<String> missing = new HashSet<>(referenced);
        missing.removeAll(shopNames);
        Assert.assertTrue(missing.isEmpty(), "shop names on map missing from shops.json: " + missing);

        Path townsfolkFile = planeDir.resolve("world/townsfolk.json");
        Assert.assertTrue(Files.isRegularFile(townsfolkFile));
        Array<TownsfolkData> folk = TownsfolkListData.loadFromPath(townsfolkFile);
        Assert.assertTrue(folk.size >= 3, "expected starter townsfolk, got " + folk.size);
        List<String> errors = LivingTownMapSupport.validateTownsfolk(folk, planeDir);
        Assert.assertTrue(errors.isEmpty(), "townsfolk validation failed: " + errors);

        TownsfolkData mira = null;
        for (TownsfolkData t : new Array.ArrayIterator<>(folk)) {
            if ("havenbrook_mira".equals(t.id)) {
                mira = t;
                break;
            }
        }
        Assert.assertNotNull(mira);
        Assert.assertEquals(mira.town, "StarterTown");
        Assert.assertTrue(LivingTownMapSupport.isDialogFileRef(mira.dialogFile));
        Assert.assertFalse(LivingTownMapSupport.isDialogFileRef("[{ \"text\":\"hi\" }]"));
        Assert.assertFalse(LivingTownMapSupport.isDialogFileRef(""));
    }

    @Test
    public void missingObjectsWarnButDoNotCrash() {
        Assert.assertTrue(LivingTownMapSupport.missingTownsfolkWarning("nope").contains("nope"));
        Assert.assertTrue(LivingTownMapSupport.emptyShopWarning(42).contains("42"));

        // Unknown townsfolk id → null, caller skips (MapStage must not throw).
        TownsfolkListData.clearCache();
        Assert.assertNull(TownsfolkListData.get("does_not_exist"));

        // Empty object list still yields a usable fallback spawn.
        List<TmxObjectInfo> empty = LivingTownMapSupport.parseTmxObjectsXml(
                "<map><objectgroup></objectgroup></map>");
        Assert.assertFalse(LivingTownMapSupport.hasObjectType(empty, "entry"));
        float[] pos = LivingTownMapSupport.fallbackEntryPixels(100f, 200f, 0.25f, 0.5f);
        Assert.assertEquals(pos[0], 25f, 0.001f);
        Assert.assertEquals(pos[1], 100f, 0.001f);
    }

    @Test
    public void townsfolkCacheClearsOnConfigChange() throws Exception {
        java.lang.reflect.Field cached = TownsfolkListData.class.getDeclaredField("cached");
        cached.setAccessible(true);
        cached.set(null, new Array<TownsfolkData>());
        Assert.assertNotNull(cached.get(null));
        ConfigData other = new ConfigData();
        other.ascendantRules = true;
        Config.installConfigDataForTest(other);
        Assert.assertNull(cached.get(null), "installConfigDataForTest must clear townsfolk cache");
        cached.set(null, new Array<TownsfolkData>());
        TownsfolkListData.clearCache();
        Assert.assertNull(cached.get(null));
    }

    @Test
    public void configJsonHasLt1Block() throws Exception {
        String config = Files.readString(planeDir.resolve("config.json"), StandardCharsets.UTF_8);
        Assert.assertTrue(config.contains("\"lt1LivingTowns\""));
        Assert.assertTrue(config.contains("\"lt1WarnMissingMapObjects\""));
        Assert.assertTrue(config.contains("\"lt1StarterTownPoiName\""));
        Assert.assertTrue(config.contains("\"StarterTown\""));
    }

    @Test
    public void townsfolkTemplateExistsForSteve() {
        Path tx = commonDir.resolve("maps/obj/townsfolk.tx");
        Assert.assertTrue(Files.isRegularFile(tx), "townsfolk.tx template missing: " + tx);
    }
}
