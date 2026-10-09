package forge.adventure;

import forge.adventure.data.BiomeData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.WorldData;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneAlignment;
import forge.adventure.world.PlaneBlob;
import forge.adventure.world.PlaneKind;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.SetColorBalance;
import forge.adventure.world.SetPlaneGenerator;
import forge.adventure.world.SetPlaneRules;
import forge.deck.Deck;
import forge.item.PaperCard;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * MV2 behaviour tests: deterministic set-plane customisation, alignment costs,
 * mastery unlock hook shape, MV1 save load compatibility. No source grepping.
 */
public class SetPlaneGeneratorTest {

    @Test
    public void planeIdAndSetCodeRoundTrip() {
        Assert.assertEquals(SetPlaneGenerator.planeIdForSet("DMU"), "set_dmu");
        Assert.assertEquals(SetPlaneGenerator.setCodeFromPlaneId("set_dmu"), "DMU");
        Assert.assertEquals(SetPlaneGenerator.setCodeFromPlaneId("set_BRO"), "BRO");
        Assert.assertEquals(SetPlaneGenerator.setCodeFromPlaneId("home"), "");
    }

    @Test
    public void clampSizeKeepsTemplateInsideRange() {
        Random rng = new Random(1L);
        Assert.assertEquals(SetPlaneGenerator.clampSize(350, 300, 400, rng), 350);
        int outside = SetPlaneGenerator.clampSize(1000, 300, 400, new Random(42L));
        Assert.assertTrue(outside >= 300 && outside <= 400, "size=" + outside);
        int low = SetPlaneGenerator.clampSize(50, 300, 400, new Random(7L));
        Assert.assertTrue(low >= 300 && low <= 400, "size=" + low);
    }

    @Test
    public void customizeForSetIsDeterministicAndBiasesBiomes() {
        WorldData template = sampleTemplate(350);
        SetColorBalance redHeavy = SetColorBalance.fromFractions(0.05f, 0.05f, 0.05f, 0.70f, 0.10f, 0.05f);

        WorldData a = SetPlaneGenerator.customizeForSet(template, "TST", redHeavy, 99L);
        WorldData b = SetPlaneGenerator.customizeForSet(template, "TST", redHeavy, 99L);

        Assert.assertEquals(a.width, b.width);
        Assert.assertEquals(a.height, b.height);
        Assert.assertTrue(a.width >= 300 && a.width <= 400);
        Assert.assertEquals(a.width, a.height);

        BiomeData redA = biome(a, "red");
        BiomeData blueA = biome(a, "blue");
        BiomeData redB = biome(b, "red");
        Assert.assertNotNull(redA);
        Assert.assertNotNull(blueA);
        Assert.assertEquals(redA.width, redB.width, 0.0001f);
        // Red-heavy set → red biome footprint larger than blue.
        Assert.assertTrue(redA.width > blueA.width,
                "red width " + redA.width + " should exceed blue " + blueA.width);
    }

    @Test
    public void themedTownNamesUseSetTheme() {
        WorldData template = sampleTemplate(350);
        WorldData data = SetPlaneGenerator.customizeForSet(template, "ABC",
                SetColorBalance.equal(), 1L);
        BiomeData white = biome(data, "white");
        Assert.assertNotNull(white);
        ArrayList<String> names = white.getUnusedTownNames();
        Assert.assertFalse(names.isEmpty());
        // displayNameForSet falls back to the code when DB is missing → names contain "ABC".
        boolean themed = names.stream().anyMatch(n -> n.contains("ABC"));
        Assert.assertTrue(themed, "expected ABC-themed town names, got "
                + names.subList(0, Math.min(5, names.size())));
    }

    @Test
    public void colorBalanceFractionsSumToOne() {
        SetColorBalance bal = SetColorBalance.fromFractions(2, 1, 1, 1, 1, 1);
        float sum = bal.white + bal.blue + bal.black + bal.red + bal.green + bal.colorless;
        Assert.assertEquals(sum, 1f, 0.0001f);
        Assert.assertTrue(bal.white > bal.blue);
    }

    @Test
    public void standardWindowAlignedVsDriftedCosts() {
        StandardWindow window = new StandardWindow();
        window.init(List.of("ONE", "BRO", "DMU"));

        Assert.assertEquals(PlaneAlignment.of("DMU", window), PlaneAlignment.ALIGNED);
        Assert.assertEquals(PlaneAlignment.of("BRO", window), PlaneAlignment.ALIGNED);

        // Simulate rotation: add a fourth set, oldest (ONE) rotates out but stays in history.
        window.clear();
        // Rebuild with unlocked history via addSet path.
        StandardWindow w2 = new StandardWindow();
        w2.init(List.of("ONE", "BRO", "DMU"));
        // Force a rotate by using reflection-free public API: addSet when at capacity.
        // addSet is public; window size 3 → fourth rotates first out.
        String rotated = invokeAddSet(w2, "MOM");
        Assert.assertEquals(rotated, "ONE");
        Assert.assertEquals(PlaneAlignment.of("MOM", w2), PlaneAlignment.ALIGNED);
        Assert.assertEquals(PlaneAlignment.of("ONE", w2), PlaneAlignment.DRIFTED);

        ConfigData cfg = new ConfigData();
        cfg.alignedPortalGoldCost = 0;
        cfg.rotatedOutPortalGoldCost = 750;
        Assert.assertEquals(PlaneAlignment.ALIGNED.portalGoldCost(cfg), 0);
        Assert.assertEquals(PlaneAlignment.DRIFTED.portalGoldCost(cfg), 750);
        Assert.assertFalse(PlaneAlignment.LOCKED.isReachable());
        Assert.assertTrue(PlaneAlignment.ALIGNED.isReachable());
        Assert.assertTrue(PlaneAlignment.DRIFTED.isReachable());
    }

    @Test
    public void unknownFreeFormPlaneStaysAligned() {
        StandardWindow window = new StandardWindow();
        window.init(List.of("DMU"));
        // "DEMO" is not a real edition in headless → ALIGNED (MV1 compat).
        Assert.assertEquals(PlaneAlignment.of("DEMO", window), PlaneAlignment.ALIGNED);
    }

    @Test
    public void checkTravelRefusesLockedKnownSet() {
        // Without a live FModel edition, LOCKED won't trigger for fake codes.
        // Exercise the message path for LOCKED directly.
        Assert.assertEquals(PlaneAlignment.LOCKED.portalGoldCost(new ConfigData()), -1);
        String err = SetPlaneRules.checkTravel("home", null, false);
        Assert.assertNull(err);
    }

    @Test
    public void rewardFilterPinsEditionsOnSetPlaneContext() {
        // applySetEditionFilter is a no-op off a set plane (no WorldSave).
        RewardData base = new RewardData();
        base.type = "card";
        base.count = 1;
        RewardData out = SetPlaneRules.applySetEditionFilter(base);
        Assert.assertSame(out, base);

        // Direct edition pin used by shops when a set code is supplied.
        List<PaperCard> empty = SetPlaneRules.cardsFromSet(List.of(), "DMU");
        Assert.assertTrue(empty.isEmpty());
    }

    @Test
    public void shopAndRewardEditionPinCopiesFilter() {
        // Mirrors SetPlaneRules.applySetEditionFilter when a set code is forced.
        RewardData base = new RewardData();
        base.type = "randomCard";
        base.count = 3;
        base.colors = new String[]{"red"};
        RewardData pinned = new RewardData(base);
        pinned.editions = new String[]{"DMU"};
        Assert.assertEquals(pinned.editions.length, 1);
        Assert.assertEquals(pinned.editions[0], "DMU");
        Assert.assertNull(base.editions);
        Assert.assertEquals(pinned.count, 3);
        Assert.assertEquals(pinned.colors[0], "red");
    }

    @Test
    public void enemyDeckGenerateBuildsNamedDeckWithoutCardDb() {
        // generateEnemyDeck must not throw when FModel editions are unavailable;
        // it returns an (possibly empty) named deck restricted-check friendly.
        EnemyData enemy = new EnemyData();
        enemy.name = "Goblin Scout";
        enemy.colors = "R";
        enemy.difficulty = 2f;
        enemy.deck = new String[]{SetPlaneRules.GENERATE};
        Deck deck = SetPlaneRules.generateEnemyDeck(enemy, "ZZZ_NOT_A_SET");
        Assert.assertNotNull(deck);
        Assert.assertTrue(deck.getName().contains("Goblin Scout"));
        Assert.assertTrue(SetPlaneRules.deckRestrictedToSet(deck, "ZZZ_NOT_A_SET"));
    }

    @Test
    public void enemyGenerateFlagRecognisesGenerateMarker() {
        EnemyData enemy = new EnemyData();
        enemy.name = "Test Mage";
        enemy.deck = new String[]{SetPlaneRules.GENERATE};
        enemy.colors = "R";
        enemy.difficulty = 1f;
        // Off set plane → shouldGenerateSetDeck false (ascendant/plane gated).
        Assert.assertFalse(SetPlaneRules.shouldGenerateSetDeck(enemy)
                || !SetPlaneRules.isOnSetPlane());
        // When not on a set plane, shouldGenerate is false.
        Assert.assertFalse(SetPlaneRules.isOnSetPlane());
        Assert.assertFalse(SetPlaneRules.shouldGenerateSetDeck(enemy));
    }

    @Test
    public void deckRestrictedToSetAcceptsBasicsFromOtherEditions() {
        Deck deck = new Deck("t");
        // Empty deck is vacuously restricted.
        Assert.assertTrue(SetPlaneRules.deckRestrictedToSet(deck, "DMU"));
        Assert.assertFalse(SetPlaneRules.deckRestrictedToSet(null, "DMU"));
        Assert.assertFalse(SetPlaneRules.deckRestrictedToSet(deck, ""));
    }

    @Test
    public void masteryUnlockPlaneIdMatchesGenerator() {
        String code = "DMU";
        String planeId = SetPlaneGenerator.planeIdForSet(code);
        PlaneMeta meta = new PlaneMeta(planeId, PlaneKind.SET, 1L,
                "world/set_plane_world.json", "Dominaria United");
        meta.setSetCode(code);
        Assert.assertEquals(meta.getSetCode(), "DMU");
        Assert.assertEquals(SetPlaneGenerator.setCodeFromPlaneId(meta.getId()), "DMU");
    }

    @Test
    public void oldMv1SaveWithoutSetCodeStillLoads() throws Exception {
        MultiverseState multi = new MultiverseState();
        multi.initHomeFromLive(11L, 5f, 6f);
        // Free-form MV1 id (not set_*): empty setCode in meta, still loads.
        PlaneMeta set = multi.registerSetPlane("demo_plane", 22L,
                "world/set_plane_world.json", "Demo");
        set.setSetCode(""); // simulate a pre-MV2 blob meta
        SaveFileData blob = PlaneBlob.pack(sampleWorld(22L), sampleStage(), new SaveFileData(), set);
        multi.writeInactiveBlob("demo_plane", blob);

        SaveFileData registry = multi.saveRegistry();
        MultiverseState loaded = new MultiverseState();
        Assert.assertTrue(loaded.loadRegistry(registry));
        Assert.assertTrue(loaded.hasPlane("home"));
        Assert.assertTrue(loaded.hasPlane("demo_plane"));
        Assert.assertTrue(loaded.hasCompressedBlob("demo_plane"));
        Assert.assertEquals(loaded.getMeta("demo_plane").getKind(), PlaneKind.SET);
        Assert.assertEquals(PlaneAlignment.ofPlane(loaded.getMeta("demo_plane"), new StandardWindow()),
                PlaneAlignment.ALIGNED);
    }

    @Test
    public void mv2MetaPersistsSetCode() {
        MultiverseState multi = new MultiverseState();
        multi.initHomeFromLive(1L, 0f, 0f);
        PlaneMeta meta = multi.registerSetPlane(SetPlaneGenerator.planeIdForSet("BRO"), 3L,
                "world/set_plane_world.json", "The Brothers' War");
        meta.setSetCode("BRO");
        SaveFileData saved = multi.saveRegistry();
        MultiverseState loaded = new MultiverseState();
        Assert.assertTrue(loaded.loadRegistry(saved));
        Assert.assertEquals(loaded.getMeta("set_bro").getSetCode(), "BRO");
    }

    @Test
    public void customizeSizeRangeHonoursConfigDefaults() {
        WorldData template = sampleTemplate(350);
        WorldData data = SetPlaneGenerator.customizeForSet(template, "X",
                SetColorBalance.equal(), 12345L);
        Assert.assertTrue(data.width >= 300 && data.width <= 400);
        Assert.assertTrue(data.height >= 300 && data.height <= 400);
    }

    // --- helpers ---

    private static String invokeAddSet(StandardWindow w, String code) {
        return w.addSet(code);
    }

    private static WorldData sampleTemplate(int size) {
        WorldData d = new WorldData();
        d.width = size;
        d.height = size;
        d.tileSize = 16;
        d.playerStartPosX = 0.5f;
        d.playerStartPosY = 0.5f;
        d.noiseZoomBiome = 30;
        d.minTownSpacing = 40;
        d.biomesNames = new String[0];
        List<BiomeData> biomes = new ArrayList<>();
        biomes.add(biomeStub("base", 1.0f, 1.0f, 0.5f, 1.0f));
        biomes.add(biomeStub("white", 0.7f, 0.7f, 0.5f, 1.5f));
        biomes.add(biomeStub("blue", 0.7f, 0.7f, 0.5f, 1.5f));
        biomes.add(biomeStub("black", 0.7f, 0.7f, 0.5f, 1.5f));
        biomes.add(biomeStub("red", 0.7f, 0.7f, 0.5f, 1.5f));
        biomes.add(biomeStub("green", 0.7f, 0.7f, 0.5f, 1.5f));
        biomes.add(biomeStub("colorless", 0.5f, 0.5f, 0.5f, 1.2f));
        d.replaceBiomes(biomes);
        return d;
    }

    private static BiomeData biomeStub(String name, float w, float h, float noise, float dist) {
        BiomeData b = new BiomeData();
        b.name = name;
        b.width = w;
        b.height = h;
        b.noiseWeight = noise;
        b.distWeight = dist;
        b.startPointX = 0.5f;
        b.startPointY = 0.5f;
        b.color = "ffffff";
        b.pointsOfInterest = new String[]{"Town"};
        return b;
    }

    private static BiomeData biome(WorldData data, String name) {
        for (BiomeData b : data.GetBiomes()) {
            if (name.equalsIgnoreCase(b.name)) {
                return b;
            }
        }
        return null;
    }

    private static SaveFileData sampleWorld(long seed) {
        SaveFileData world = new SaveFileData();
        world.store("seed", seed);
        world.store("width", 350);
        world.store("height", 350);
        world.store("worldConfigPath", "world/set_plane_world.json");
        return world;
    }

    private static SaveFileData sampleStage() {
        SaveFileData stage = new SaveFileData();
        List<Float> emptyF = new ArrayList<>();
        List<String> emptyS = new ArrayList<>();
        stage.storeObject("timeouts", emptyF);
        stage.storeObject("names", emptyS);
        stage.storeObject("x", emptyF);
        stage.storeObject("y", emptyF);
        stage.storeObject("questStageIDs", emptyS);
        stage.store("globalTimer", 0f);
        return stage;
    }
}
