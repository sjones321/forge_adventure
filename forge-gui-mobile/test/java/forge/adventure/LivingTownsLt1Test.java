package forge.adventure;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.ConfigData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.ShopData;
import forge.adventure.data.TownsfolkData;
import forge.adventure.data.TownsfolkListData;
import forge.adventure.util.LivingTownMapSupport;
import forge.adventure.util.LivingTownMapSupport.TmxObjectInfo;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        TownsfolkListData.clearCacheForTests();
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

        Path map = LivingTownMapSupport.resolvePoiMapFile(planeDir, commonDir, poi.map);
        Assert.assertTrue(Files.isRegularFile(map), "starter_town.tmx must resolve: " + map);

        String colorless = Files.readString(planeDir.resolve("world/biomes/colorless.json"),
                StandardCharsets.UTF_8);
        Assert.assertTrue(colorless.contains("\"StarterTown\""),
                "colorless biome must list StarterTown for placement");
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
        Assert.assertTrue(warn.contains("fallback"));
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
        TownsfolkListData.clearCacheForTests();
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
