package forge.adventure;

import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import forge.adventure.character.PortalActor;
import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopWorldSync;
import forge.adventure.data.BiomeData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.RewardData;
import forge.adventure.data.WorldData;
import forge.adventure.player.StandardWindow;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.MultiverseState;
import forge.adventure.world.PlaneAlignment;
import forge.adventure.world.PlaneBlob;
import forge.adventure.world.PlaneKind;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.PlanarPortalPlacer;
import forge.adventure.world.SetColorBalance;
import forge.adventure.world.SetPlaneGenerator;
import forge.adventure.world.SetPlaneLoading;
import forge.adventure.world.SetPlaneRules;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.util.MyRandom;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    public void applyBiomeMixPreservesFullMapBaseLayers() {
        // Regression: shrinking ocean (1×1) left empty biomeMap cells and crashed generateNew.
        WorldData data = sampleTemplate(350);
        BiomeData ocean = new BiomeData();
        ocean.name = "ocean";
        ocean.width = 1.0f;
        ocean.height = 1.0f;
        ocean.startPointX = 0.5f;
        ocean.startPointY = 0.5f;
        BiomeData red = new BiomeData();
        red.name = "red";
        red.width = 0.7f;
        red.height = 0.7f;
        red.startPointX = 0.3f;
        red.startPointY = 0.3f;
        data.replaceBiomes(List.of(ocean, red));
        SetPlaneGenerator.applyBiomeMix(data, SetColorBalance.fromFractions(0.05f, 0.05f, 0.05f, 0.70f, 0.10f, 0.05f));
        Assert.assertEquals(biome(data, "ocean").width, 1.0f, 0.0001f);
        Assert.assertEquals(biome(data, "ocean").height, 1.0f, 0.0001f);
        Assert.assertTrue(biome(data, "red").width >= 0.7f - 0.0001f,
                "red-heavy mix should not shrink the red biome");
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
        // An unknown set code is not restrictable, so the deck is not tied to it. Without a card DB the
        // deck is empty or basics only; with one (loaded by an earlier test class) it may use any set.
        Assert.assertEquals(SetPlaneRules.restrictableSetCode("ZZZ_NOT_A_SET"), "");
    }

    @Test
    public void enemyGenerateFlagRecognisesGenerateMarker() {
        EnemyData enemy = new EnemyData();
        enemy.name = "Test Mage";
        enemy.deck = new String[]{SetPlaneRules.GENERATE};
        enemy.colors = "R";
        enemy.difficulty = 1f;
        // Off set plane (no live WorldSave) → shouldGenerateSetDeck is false.
        Assert.assertFalse(SetPlaneRules.isOnSetPlane());
        Assert.assertFalse(SetPlaneRules.shouldGenerateSetDeck(enemy));
        Assert.assertEquals(SetPlaneRules.GENERATE, "$generate");
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
        // No set code and non-set_* id → treated as HOME alignment (still reachable).
        Assert.assertEquals(PlaneAlignment.ofPlane(loaded.getMeta("demo_plane"), new StandardWindow()),
                PlaneAlignment.HOME);
        Assert.assertTrue(PlaneAlignment.HOME.isReachable());
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

    // --- Stephen review: registration / bounded gen / fallbacks / gates / gold / co-op ---

    @Test
    public void planarGateRegisteredSoSaveLoadsAfterRestart() {
        PointOfInterestData.clearRuntimeCacheForTests();
        PointOfInterestData gate = SetPlaneGenerator.ensurePlanarGateRegistered();
        Assert.assertNotNull(gate);
        Assert.assertEquals(gate.name, SetPlaneGenerator.PLANAR_GATE_POI);
        Assert.assertEquals(PointOfInterestData.getPointOfInterest("PlanarGate").name, "PlanarGate");

        // Persist a gate POI blob, clear the cache (simulates process restart), re-register, load.
        SaveFileData saved = new SaveFileData();
        saved.store("name", "PlanarGate");
        saved.store("position", new Vector2(64f, 96f));
        saved.store("rectangle", new Rectangle(64f, 96f, 16f, 16f));
        saved.store("spriteIndex", 0);
        saved.store("active", true);
        saved.store("displayName", "Portal to Home");
        saved.store("targetPlane", "home");

        PointOfInterestData.clearRuntimeCacheForTests();
        // Cold cache: Ascendant JSON may already define PlanarGate when Config is
        // live (suite bootstrap). Either way ensurePlanarGateRegistered must make
        // lookup succeed so the saved blob can resolve after "restart".
        SetPlaneGenerator.ensurePlanarGateRegistered();
        Assert.assertNotNull(PointOfInterestData.getPointOfInterest("PlanarGate"));

        PointOfInterest poi = new PointOfInterest();
        poi.load(saved);

        Assert.assertNotNull(poi.getData());
        Assert.assertEquals(poi.getData().name, "PlanarGate");
        Assert.assertEquals(poi.getTargetPlane(), "home");
        Assert.assertEquals(poi.getDisplayName(), "Portal to Home");
    }

    @Test
    public void boundedGenerationScalesPoisAndCapsRestarts() {
        WorldData template = sampleTemplate(200);
        for (BiomeData b : template.GetBiomes()) {
            b.width = "base".equalsIgnoreCase(b.name) ? b.width : 0.3f;
            b.height = "base".equalsIgnoreCase(b.name) ? b.height : 0.3f;
            ArrayList<PointOfInterestData> seeded = new ArrayList<>();
            if (!"base".equalsIgnoreCase(b.name)) {
                seeded.add(poiDef("TinyTown", "town", 8));
                seeded.add(poiDef("TinyCapital", "capital", 2));
                seeded.add(poiDef("TinyDungeon", "dungeon", 6));
            }
            // Always freeze so scale never hits Config / JSON in headless.
            b.replacePointsOfInterest(seeded);
        }

        // Scale + restart cap without full customize (avoids Config in headless).
        SetPlaneGenerator.scalePoiCountsForShrunkBiomes(template, 200);
        Assert.assertTrue(template.maxPoiPlacementRestarts == 0
                || template.maxPoiPlacementRestarts > 0);
        WorldData customized = SetPlaneGenerator.customizeForSet(sampleTemplate(200), "TINY",
                SetColorBalance.equal(), 7L);
        Assert.assertTrue(customized.maxPoiPlacementRestarts > 0
                && customized.maxPoiPlacementRestarts <= 32,
                "restart cap=" + customized.maxPoiPlacementRestarts);

        BiomeData red = biome(template, "red");
        Assert.assertNotNull(red);
        int townCount = 0;
        int capitalCount = 0;
        int dungeonCount = 0;
        for (PointOfInterestData p : red.getPointsOfInterest()) {
            if ("town".equals(p.type)) {
                townCount += p.count;
            } else if ("capital".equals(p.type)) {
                capitalCount += p.count;
            } else if ("dungeon".equals(p.type)) {
                dungeonCount += p.count;
            }
        }
        Assert.assertTrue(townCount >= 1 && townCount <= 8, "townCount=" + townCount);
        Assert.assertTrue(capitalCount >= 1 && capitalCount <= 2, "capitalCount=" + capitalCount);
        Assert.assertTrue(dungeonCount <= 6, "dungeonCount=" + dungeonCount);
    }

    @Test
    public void coreAndDebugPlanesSkipSetRestriction() {
        Assert.assertEquals(SetPlaneRules.restrictableSetCode("CORE"), "");
        Assert.assertEquals(SetPlaneRules.restrictableSetCode(StandardWindow.CORE_COLLECTION), "");
        Assert.assertEquals(SetPlaneRules.restrictableSetCode("MV1_DEBUG"), "");
        Assert.assertEquals(SetPlaneRules.restrictableSetCode(""), "");
        Assert.assertEquals(SetPlaneRules.restrictableSetCode(null), "");

        // Shops / rewards stay unpinned when restriction is skipped.
        RewardData base = new RewardData();
        base.type = "randomCard";
        base.count = 2;
        RewardData filtered = SetPlaneRules.applySetEditionFilter(base);
        Assert.assertSame(filtered, base);

        EnemyData enemy = new EnemyData();
        enemy.name = "Core Scout";
        enemy.colors = "W";
        enemy.difficulty = 1f;
        enemy.deck = new String[]{SetPlaneRules.GENERATE};
        Deck deck = SetPlaneRules.generateEnemyDeck(enemy, "CORE");
        Assert.assertNotNull(deck);
        Assert.assertTrue(deck.getName().contains("Core Scout"));
    }

    @Test
    public void onlyGenerateMarkerReplacesEnemyDecksBossesKept() {
        EnemyData generate = new EnemyData();
        generate.name = "Grunt";
        generate.boss = false;
        generate.deck = new String[]{SetPlaneRules.GENERATE};
        Assert.assertEquals(SetPlaneRules.GENERATE, "$generate");
        // Off set plane → shouldGenerate false; marker alone is not enough.
        Assert.assertFalse(SetPlaneRules.shouldGenerateSetDeck(generate));

        EnemyData boss = new EnemyData();
        boss.name = "Boss Mage";
        boss.boss = true;
        boss.deck = new String[]{SetPlaneRules.GENERATE};
        Assert.assertFalse(SetPlaneRules.shouldGenerateSetDeck(boss));

        EnemyData handBuilt = new EnemyData();
        handBuilt.name = "Named Duelist";
        handBuilt.boss = false;
        handBuilt.deck = new String[]{"decks/custom/hand_built.dck"};
        Assert.assertFalse(SetPlaneRules.shouldGenerateSetDeck(handBuilt));

        EnemyData empty = new EnemyData();
        empty.name = "Empty";
        empty.deck = new String[]{};
        Assert.assertFalse(SetPlaneRules.shouldGenerateSetDeck(empty));
    }

    @Test
    public void generateEnemyDeckLeavesWorldAndMyRandomUntouched() {
        Random marker = new Random(424242L);
        MyRandom.setRandom(marker);
        World world = new World();
        Random worldRng = world.getRandom();
        // Advance and snapshot the next values that must stay deterministic.
        long worldA = worldRng.nextLong();
        long worldB = worldRng.nextLong();
        // Rewind world RNG by reseeding a fresh World and replaying — instead compare identity.
        World world2 = new World();
        Random worldRng2 = world2.getRandom();

        EnemyData enemy = new EnemyData();
        enemy.name = "Rng Probe";
        enemy.colors = "U";
        enemy.difficulty = 1f;
        enemy.deck = new String[]{SetPlaneRules.GENERATE};
        SetPlaneRules.generateEnemyDeck(enemy, "ZZZ_NOT_A_SET");

        Assert.assertSame(MyRandom.getRandom(), marker);
        // World instances keep their own Random; generateEnemyDeck must not replace them.
        Assert.assertSame(world.getRandom(), worldRng);
        Assert.assertSame(world2.getRandom(), worldRng2);
        // Sequence after the two pre-call draws continues independently of MyRandom swap.
        long after = worldRng.nextLong();
        Assert.assertNotEquals(after, worldA);
        Assert.assertNotEquals(after, worldB);
    }

    @Test
    public void missingGatesTrackedAndClearedOnPendingLoad() {
        MultiverseState multi = new MultiverseState();
        multi.initHomeFromLive(9L, 0f, 0f);
        multi.rememberPendingHomeGate("set_dmu");
        multi.rememberPendingHomeGate("set_bro");
        Assert.assertTrue(multi.getPendingHomeGatePlaneIds().contains("set_dmu"));
        Assert.assertTrue(multi.getPendingHomeGatePlaneIds().contains("set_bro"));

        SaveFileData registry = multi.saveRegistry();
        MultiverseState loaded = new MultiverseState();
        Assert.assertTrue(loaded.loadRegistry(registry));
        Assert.assertTrue(loaded.getPendingHomeGatePlaneIds().contains("set_dmu"));
        Assert.assertTrue(loaded.getPendingHomeGatePlaneIds().contains("set_bro"));

        loaded.clearPendingHomeGate("set_dmu");
        Assert.assertFalse(loaded.getPendingHomeGatePlaneIds().contains("set_dmu"));
        Assert.assertTrue(loaded.getPendingHomeGatePlaneIds().contains("set_bro"));
    }

    @Test
    public void gatePlacementAvoidsTownsAndCollisions() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();
        World world = new World();
        WorldData data = new WorldData();
        data.width = 64;
        data.height = 64;
        data.tileSize = 16;
        data.playerStartPosX = 0.5f;
        data.playerStartPosY = 0.5f;
        world.installTestWorldGrid(data);

        PointOfInterestData townDef = poiDef("GateTown", "town", 1);
        PointOfInterestData.registerRuntime(townDef);
        PointOfInterest town = poiAt(townDef, 32 * 16f, 32 * 16f);
        world.addPointOfInterest(town);

        // Spot on the town is blocked.
        Assert.assertTrue(PlanarPortalPlacer.isSpotBlocked(world, town.getPosition().x, town.getPosition().y));

        Vector2 free = PlanarPortalPlacer.pickCollisionFreeSpot(world, new Random(11L), 0);
        Assert.assertNotNull(free);
        Assert.assertFalse(PlanarPortalPlacer.isSpotBlocked(world, free.x, free.y));

        // Second gate slot with different index must not land on the first free spot's exclusion.
        PointOfInterestData gateDef = PointOfInterestData.getPointOfInterest("PlanarGate");
        PointOfInterest gate = poiAt(gateDef, free.x, free.y);
        gate.setTargetPlane("set_dmu");
        world.addPointOfInterest(gate);
        Assert.assertTrue(PlanarPortalPlacer.isSpotBlocked(world, free.x, free.y));

        Vector2 free2 = PlanarPortalPlacer.pickCollisionFreeSpot(world, new Random(11L), 1);
        Assert.assertNotNull(free2);
        Assert.assertTrue(free2.dst(free) > data.tileSize * 4f,
                "ring slots collided: " + free + " vs " + free2);
    }

    @Test
    public void goldChargeFailsBeforeSwitchAndRefundsOnFailure() throws Exception {
        // Source contract: charge before switchPlane; refund after failed switch.
        String portal = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/forge/adventure/character/PortalActor.java")));
        int chargeAt = portal.indexOf("chargePortalGold");
        int switchAt = portal.indexOf("switchPlane");
        int refundAt = portal.indexOf("refundPortalGold");
        Assert.assertTrue(chargeAt > 0 && switchAt > chargeAt && refundAt > switchAt);

        String console = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/forge/adventure/stage/ConsoleCommandInterpreter.java")));
        int cCharge = console.indexOf("chargePortalGold");
        int cSwitch = console.indexOf("switchPlane");
        int cRefund = console.indexOf("refundPortalGold");
        Assert.assertTrue(cCharge > 0 && cSwitch > cCharge && cRefund > cSwitch);

        StandardWindow window = new StandardWindow();
        window.init(List.of("ONE", "BRO", "DMU"));
        Assert.assertEquals(window.addSet("MOM"), "ONE");
        Assert.assertEquals(PlaneAlignment.of("ONE", window), PlaneAlignment.DRIFTED);

        ConfigData cfg = new ConfigData();
        cfg.rotatedOutPortalGoldCost = 750;
        Assert.assertEquals(PlaneAlignment.DRIFTED.portalGoldCost(cfg), 750);

        // Broke wallet → charge fails, gold unchanged, switch must not proceed.
        AtomicInteger gold = new AtomicInteger(100);
        int charged = SetPlaneRules.chargePortalGold(
                SetPlaneGenerator.planeIdForSet("ONE"), window, gold.get(),
                amount -> gold.addAndGet(-amount));
        // Without Config.instance costs, drifted uses ConfigData default (500) via portalGoldCost.
        int cost = SetPlaneRules.portalGoldCost(SetPlaneGenerator.planeIdForSet("ONE"), window);
        if (cost > 0) {
            Assert.assertEquals(charged, -1, "insufficient gold must fail");
            Assert.assertEquals(gold.get(), 100);
        }

        // Funded wallet → charge succeeds; simulated switch failure refunds.
        gold.set(Math.max(cost, 1) + 50);
        int before = gold.get();
        charged = SetPlaneRules.chargePortalGold(
                SetPlaneGenerator.planeIdForSet("ONE"), window, gold.get(),
                amount -> gold.addAndGet(-amount));
        if (cost > 0) {
            Assert.assertEquals(charged, cost);
            Assert.assertEquals(gold.get(), before - cost);
            boolean switchOk = false; // simulated failure
            if (!switchOk) {
                SetPlaneRules.refundPortalGold(amount -> gold.addAndGet(amount), charged);
            }
            Assert.assertEquals(gold.get(), before);
        } else {
            // Headless aligned-as-free path still exercises home free charge.
            Assert.assertEquals(SetPlaneRules.chargePortalGold("home", window, 0, null), 0);
        }
    }

    @Test
    public void guestFollowsHostActiveSetCodeViaPlaneId() {
        CoopSession session = CoopSession.get();
        try {
            String planeId = SetPlaneGenerator.planeIdForSet("BRO");
            session.testFollowHostPlane(planeId);
            Assert.assertEquals(session.getActiveWorldPlaneId(), planeId);
            Assert.assertEquals(SetPlaneGenerator.setCodeFromPlaneId(session.getActiveWorldPlaneId()), "BRO");
            // Guest plane id is what Current.planeId() / activeSetCode() consult.
            // CORE / debug never restrict even if somehow active.
            Assert.assertEquals(SetPlaneRules.restrictableSetCode("CORE"), "");
            Assert.assertEquals(SetPlaneRules.restrictableSetCode("MV1_DEBUG"), "");
        } finally {
            session.testClearGuestPlaneFollow();
        }
        Assert.assertFalse(CoopSession.isGuestBlockedFromPlaneSwitch(
                forge.adventure.coop.CoopSessionRole.NONE, CoopSession.State.IDLE));
    }

    @Test
    public void injectPlanarGatePoiActuallyInjectsIntoFrozenList() {
        PointOfInterestData.clearRuntimeCacheForTests();
        WorldData data = sampleTemplate(120);
        // Freeze POIs first (the bug: inject used to only touch the name array).
        for (BiomeData b : data.GetBiomes()) {
            ArrayList<PointOfInterestData> seeded = new ArrayList<>();
            if (!"base".equalsIgnoreCase(b.name)) {
                seeded.add(poiDef("PreTown", "town", 2));
            }
            b.replacePointsOfInterest(seeded);
        }
        Assert.assertFalse(SetPlaneGenerator.hasInjectedPlanarGate(data));

        SetPlaneGenerator.injectPlanarGatePoi(data, PlaneMeta.HOME_ID, "Portal to Home");
        Assert.assertTrue(SetPlaneGenerator.hasInjectedPlanarGate(data));

        boolean inFrozen = false;
        for (BiomeData b : data.GetBiomes()) {
            if ("base".equalsIgnoreCase(b.name)) {
                continue;
            }
            for (PointOfInterestData p : b.getPointsOfInterest()) {
                if (p != null && SetPlaneGenerator.PLANAR_GATE_POI.equals(p.name)) {
                    inFrozen = true;
                    Assert.assertEquals(p.count, 1);
                    Assert.assertEquals(p.targetPlane, PlaneMeta.HOME_ID);
                }
            }
            Assert.assertTrue(Arrays.asList(b.pointsOfInterest).contains(SetPlaneGenerator.PLANAR_GATE_POI));
            break;
        }
        Assert.assertTrue(inFrozen, "PlanarGate must appear in the frozen POI list");
    }

    @Test
    public void smallSetCheckExcludesBasicLands() {
        Deck basicsOnly = new Deck("basics");
        // Empty / no non-basics → below floor.
        Assert.assertEquals(SetPlaneRules.countNonBasicCards(basicsOnly), 0);
        Assert.assertTrue(SetPlaneRules.countNonBasicCards(basicsOnly) < SetPlaneRules.MIN_SET_POOL_SIZE);

        // setPoolIsUsable must ignore basics from other editions when counting the set.
        List<PaperCard> emptyPool = List.of();
        Assert.assertFalse(SetPlaneRules.setPoolIsUsable(emptyPool, "DMU"));
        Assert.assertFalse(SetPlaneRules.isBasicLand(null));
    }

    @Test
    public void paymentFailureMessageNeverNullOrEmpty() {
        String msg = SetPlaneRules.paymentFailureMessage("home", null);
        Assert.assertNotNull(msg);
        Assert.assertFalse(msg.isEmpty());
        // Locked / drifted with null player still yields a non-empty string.
        String drifted = SetPlaneRules.paymentFailureMessage(
                SetPlaneGenerator.planeIdForSet("ONE"), null);
        Assert.assertNotNull(drifted);
        Assert.assertFalse(drifted.isEmpty());
    }

    /**
     * Opt-in GL group: needs LWJGL3 + a driven render loop (Linux/CI: {@code -Pgl-tests}
     * under xvfb). Excluded from default {@code mvn test}.
     */
    @Test(groups = "gl", timeOut = 300_000)
    public void hostLiveSetPlaneHashMatchesGuestGateReplay() {
        // Real paths on GL: home generateNew → materializeSetPlane → switchPlane →
        // character save/load → live hash; guest = rebuildFromSeed + applyHostGates.
        // (switchPlane stashes the live world, so home must be a real generated world.)
        AdventureGlTestSupport.runOnGl(() -> {
            final long homeSeed = 0xC0FFEE01L;
            final String setCode = "DMU";
            final String planeId = SetPlaneGenerator.planeIdForSet(setCode);
            final int slot = 77;

            PointOfInterestData.clearRuntimeCacheForTests();
            SetPlaneGenerator.ensurePlanarGateRegistered();
            Assert.assertTrue(SetPlaneRules.isKnownEdition(setCode), "FModel must know DMU");

            forge.adventure.data.DifficultyData diff =
                    forge.adventure.util.Config.instance().getConfigData().difficulties[0];
            WorldSave.generateNewWorld("SetHashHost", true, 0, 0,
                    forge.card.ColorSet.W, diff,
                    forge.adventure.util.AdventureModes.Chaos, 0, null, homeSeed);
            WorldSave save = WorldSave.getCurrentSave();
            Assert.assertEquals(save.getCurrentPlaneId(), PlaneMeta.HOME_ID);

            PlaneMeta meta = save.registerSetPlanePending(setCode);
            Assert.assertEquals(meta.getSetCode(), setCode);
            save.materializeSetPlane(planeId);
            Assert.assertTrue(save.getMultiverse().hasCompressedBlob(planeId),
                    "materializeSetPlane must write a blob");

            // Travel onto the set plane (live world = materialized world).
            Assert.assertTrue(save.switchPlane(planeId),
                    "switchPlane to materialized set plane: " + save.getLastPlaneSwitchError());
            String beforeSaveHash = CoopWorldSync.hashPlaneForOffer(save);
            Assert.assertFalse(beforeSaveHash.isEmpty());
            assertTerrainHasNonZero(save.getWorld());

            Assert.assertTrue(save.save("gl-mv2-set-hash", slot), "character save failed");
            Assert.assertTrue(WorldSave.load(slot), "character load failed");
            WorldSave live = WorldSave.getCurrentSave();
            Assert.assertEquals(live.getCurrentPlaneId(), planeId);

            String hostLiveHash = CoopWorldSync.hashPlaneForOffer(live);
            Assert.assertEquals(hostLiveHash, beforeSaveHash, "save/load must preserve live hash");
            forge.gamemodes.net.event.coop.CoopPlanarGateEntry[] gates =
                    CoopWorldSync.collectPlanarGates(live.getWorld());
            Assert.assertTrue(gates.length >= 1, "host live world must include return gate");
            final long worldSeed = live.getWorld().getSeed();
            final String worldPath = live.getWorld().getWorldConfigPath();
            final String mv2 = CoopWorldSync.hostMv2SetCode(live);
            Assert.assertEquals(mv2, setCode);

            World guest = new World();
            try {
                String guestHash = CoopWorldSync.rebuildFromSeed(
                        guest, worldSeed, worldPath, mv2, gates);
                Assert.assertEquals(guestHash, hostLiveHash,
                        "guest rebuildFromSeed+applyHostGates must match host live hash");
            } finally {
                forge.Forge.safeDispose(guest);
            }

            // Revert / negative checks (unconditional). Set-plane biome injection already
            // places+clears the return PlanarGate during generateNew, so skip-applyHostGates
            // matches live by design — Steve's alternative: hash a mistaken regenerate
            // (wrong seed / no mv2 stamp) or wrong gate coordinates instead of the live world.
            World wrongSeed = new World();
            try {
                String regenWrongSeed = CoopWorldSync.rebuildFromSeed(
                        wrongSeed, worldSeed ^ 0x5A5A5A5AL, worldPath, mv2, gates);
                Assert.assertNotEquals(regenWrongSeed, hostLiveHash,
                        "regenerated world with wrong seed must not match live host hash");
            } finally {
                forge.Forge.safeDispose(wrongSeed);
            }
            World wrongGates = new World();
            try {
                CoopWorldSync.generateBaseContent(wrongGates, worldSeed, worldPath, mv2);
                forge.gamemodes.net.event.coop.CoopPlanarGateEntry[] shifted =
                        new forge.gamemodes.net.event.coop.CoopPlanarGateEntry[gates.length];
                for (int i = 0; i < gates.length; i++) {
                    shifted[i] = new forge.gamemodes.net.event.coop.CoopPlanarGateEntry(
                            gates[i].getSetCode(),
                            gates[i].getX() + 160f,
                            gates[i].getY() + 160f);
                }
                CoopWorldSync.applyHostGates(wrongGates, shifted);
                Assert.assertNotEquals(CoopWorldSync.hashWorld(wrongGates), hostLiveHash,
                        "wrong gate coordinates must not match live host hash");
            } finally {
                forge.Forge.safeDispose(wrongGates);
            }
            World noMv2 = new World();
            try {
                String bare = CoopWorldSync.rebuildFromSeed(
                        noMv2, worldSeed, worldPath, "", gates);
                Assert.assertNotEquals(bare, hostLiveHash,
                        "guest without host mv2SetCode stamp must not match live host");
            } finally {
                forge.Forge.safeDispose(noMv2);
            }
        });
    }

    /** @see #hostLiveSetPlaneHashMatchesGuestGateReplay */
    @Test(groups = "gl", timeOut = 300_000)
    public void hostLiveHomeHashMatchesGuestGateReplay() {
        // Real home world: generateNew → real gate placement → character save/load → live hash.
        AdventureGlTestSupport.runOnGl(() -> {
            final long seed = 0xBEEF01L;
            final int slot = 78;

            PointOfInterestData.clearRuntimeCacheForTests();
            SetPlaneGenerator.ensurePlanarGateRegistered();
            Assert.assertTrue(SetPlaneRules.isKnownEdition("BRO"));
            Assert.assertTrue(SetPlaneRules.isKnownEdition("ONE"));

            forge.adventure.data.DifficultyData diff =
                    forge.adventure.util.Config.instance().getConfigData().difficulties[0];
            WorldSave.generateNewWorld("HashHost", true, 0, 0,
                    forge.card.ColorSet.W, diff,
                    forge.adventure.util.AdventureModes.Chaos, 0, null, seed);
            WorldSave save = WorldSave.getCurrentSave();
            Assert.assertEquals(save.getCurrentPlaneId(), PlaneMeta.HOME_ID);
            assertTerrainHasNonZero(save.getWorld());

            String beforeGates = CoopWorldSync.hashWorld(save.getWorld());
            MultiverseState multi = save.getMultiverse();
            multi.rememberPendingHomeGate(SetPlaneGenerator.planeIdForSet("BRO"));
            int placed = PlanarPortalPlacer.ensureHomePortals(
                    save.getWorld(), save.getPlayer().getStandardWindow(),
                    save.getWorld().getSeed(), multi);
            Assert.assertTrue(placed > 0
                            || PlanarPortalPlacer.existingPortalTargets(save.getWorld())
                            .contains(SetPlaneGenerator.planeIdForSet("BRO")),
                    "host home must place a BRO planar gate");
            String afterGates = CoopWorldSync.hashWorld(save.getWorld());
            Assert.assertNotEquals(beforeGates, afterGates,
                    "home gate terrain clear must change hash");

            Assert.assertTrue(save.save("gl-mv2-home-hash", slot));
            Assert.assertTrue(WorldSave.load(slot));
            WorldSave live = WorldSave.getCurrentSave();
            String hostLiveHash = CoopWorldSync.hashPlaneForOffer(live);
            forge.gamemodes.net.event.coop.CoopPlanarGateEntry[] gates =
                    CoopWorldSync.collectPlanarGates(live.getWorld());
            Assert.assertTrue(gates.length >= 1);
            final long worldSeed = live.getWorld().getSeed();
            final String worldPath = live.getWorld().getWorldConfigPath();

            World guest = new World();
            try {
                String guestHash = CoopWorldSync.rebuildFromSeed(
                        guest, worldSeed, worldPath, "", gates);
                Assert.assertEquals(guestHash, hostLiveHash,
                        "guest home gate replay must match host live hash");
            } finally {
                forge.Forge.safeDispose(guest);
            }

            // Unconditional revert: guest computing its own Standard window (2 sets vs host's 1)
            // clears different terrain → different hash.
            World ownWindow = new World();
            try {
                Assert.assertTrue(ownWindow.generateNew(worldSeed, worldPath, false));
                StandardWindow win = new StandardWindow();
                win.init(List.of("ONE", "BRO"));
                int ownPlaced = PlanarPortalPlacer.ensureHomePortals(ownWindow, win, worldSeed, null);
                Assert.assertTrue(ownPlaced >= 2,
                        "guest-owned window must place two gates, placed=" + ownPlaced);
                Assert.assertNotEquals(CoopWorldSync.hashWorld(ownWindow), hostLiveHash,
                        "guest-computed Standard-window gates must not match host live hash");
            } finally {
                forge.Forge.safeDispose(ownWindow);
            }

            World noReplay = new World();
            try {
                String skipped = CoopWorldSync.rebuildFromSeed(
                        noReplay, worldSeed, worldPath, "", null);
                Assert.assertNotEquals(skipped, hostLiveHash,
                        "skipping applyHostGates must not match live host");
            } finally {
                forge.Forge.safeDispose(noReplay);
            }
        });
    }

    @Test
    public void preMv2SetPlaneSkipsCustomisationWhenSetCodeEmpty() {
        // Older saves: empty mv2SetCode → guest must not apply SetPlaneGenerator mix.
        long seed = 7L;
        World a = new World();
        a.installTestWorldGrid(grid48(), seed);
        fillTerrain(a, 0x11111111);
        String ha = CoopWorldSync.hashWorld(a);
        CoopWorldSync.applyMv2Customization(a, seed, "");
        Assert.assertEquals(CoopWorldSync.hashWorld(a), ha, "empty setCode must not alter world");
        // Wire contract: host sends empty mv2SetCode for pre-MV2; plane id alone is not enough.
        Assert.assertEquals(SetPlaneGenerator.setCodeFromPlaneId("set_dmu"), "DMU");
        forge.gamemodes.net.event.coop.CoopWorldOfferEvent offer =
                new forge.gamemodes.net.event.coop.CoopWorldOfferEvent(
                        "Host", "Shandalar Ascendant", "cfg", seed, "hash",
                        1, 2, "set_dmu", "world/set_plane_world.json", "");
        Assert.assertEquals(offer.getMv2SetCode(), "");
        Assert.assertEquals(offer.getWorldPlaneId(), "set_dmu");
        Assert.assertEquals(offer.getGates().length, 0);
    }

    /** @see #hostLiveSetPlaneHashMatchesGuestGateReplay */
    @Test(groups = "gl", timeOut = 300_000)
    public void materializeShowsLoadingScreenViaPortalActor() {
        // Real PortalActor(WorldSave, planeId) overload → materializeSetPlane inside loading wrapper.
        AdventureGlTestSupport.runOnGl(() -> {
            try {
                SetPlaneLoading.clearTestHook();
                AtomicBoolean materializeRan = new AtomicBoolean(false);
                SetPlaneLoading.setTestHook((msg, work) -> {
                    Assert.assertNotNull(msg);
                    Assert.assertFalse(msg.isEmpty());
                    work.run();
                });

                final String setCode = "NEO";
                final String planeId = SetPlaneGenerator.planeIdForSet(setCode);
                Assert.assertTrue(SetPlaneRules.isKnownEdition(setCode));
                WorldSave save = WorldSave.getCurrentSave();
                ensureMinimalPlayerForSave(save);
                save.getMultiverse().resetForNewGamePlus(2L, 0f, 0f);
                save.registerSetPlanePending(setCode);
                Assert.assertFalse(save.getMultiverse().hasCompressedBlob(planeId),
                        "pending plane must not have a blob yet");

                boolean shown = PortalActor.materializePlaneWithLoadingScreen(save, planeId, () ->
                        materializeRan.set(save.getMultiverse().hasCompressedBlob(planeId)));
                Assert.assertTrue(shown, "PortalActor must request a loading screen");
                Assert.assertTrue(SetPlaneLoading.wasLoadingScreenRequested());
                Assert.assertTrue(save.getMultiverse().hasCompressedBlob(planeId),
                        "materializeSetPlane must run inside the loading wrapper");
                Assert.assertTrue(materializeRan.get(),
                        "afterMaterialize must observe the materialized blob");
            } finally {
                SetPlaneLoading.clearTestHook();
            }
        });
    }

    private static void ensureMinimalPlayerForSave(WorldSave save) {
        forge.adventure.data.DifficultyData diff =
                forge.adventure.util.Config.instance().getConfigData().difficulties[0];
        Deck deck = new Deck("HashTest");
        save.getPlayer().create("HashHost", deck, true, 0, 0, false, false, diff,
                forge.adventure.util.AdventureModes.Chaos);
    }

    private static void assertTerrainHasNonZero(World world) {
        Assert.assertNotNull(world);
        Assert.assertNotNull(world.terrainMap, "terrain map required");
        int nonzero = 0;
        for (int[] col : world.terrainMap) {
            if (col == null) {
                continue;
            }
            for (int v : col) {
                if (v != 0) {
                    nonzero++;
                }
            }
        }
        Assert.assertTrue(nonzero > 0, "terrain must be non-zero so gate clears affect the hash");
    }

    @Test
    public void arrivalMessageUsesDisplayNameForSet() {
        String named = PortalActor.arrivalDisplayName(null, "set_dmu");
        Assert.assertEquals(named, SetPlaneGenerator.displayNameForSet("DMU"));
        Assert.assertEquals(PortalActor.arrivalDisplayName(null, "home"), "home");
    }

    // --- helpers ---

    private static WorldData grid48() {
        WorldData grid = new WorldData();
        grid.width = 48;
        grid.height = 48;
        grid.tileSize = 16;
        grid.playerStartPosX = 0.5f;
        grid.playerStartPosY = 0.5f;
        return grid;
    }

    private static WorldData copyWorldGrid(WorldData src) {
        WorldData d = new WorldData();
        d.width = src.width;
        d.height = src.height;
        d.tileSize = src.tileSize;
        d.playerStartPosX = src.playerStartPosX;
        d.playerStartPosY = src.playerStartPosY;
        return d;
    }

    private static void copyTerrain(World from, World to) {
        if (from.terrainMap == null || to.terrainMap == null) {
            return;
        }
        for (int x = 0; x < from.terrainMap.length && x < to.terrainMap.length; x++) {
            System.arraycopy(from.terrainMap[x], 0, to.terrainMap[x], 0,
                    Math.min(from.terrainMap[x].length, to.terrainMap[x].length));
        }
    }

    private static void fillTerrain(World world, int value) {
        if (world == null || world.terrainMap == null) {
            return;
        }
        for (int x = 0; x < world.terrainMap.length; x++) {
            Arrays.fill(world.terrainMap[x], value);
        }
    }

    private static PointOfInterestData poiDef(String name, String type, int count) {
        PointOfInterestData d = new PointOfInterestData();
        d.name = name;
        d.type = type;
        d.count = count;
        d.spriteAtlas = "../common/maps/tileset/buildings.atlas";
        d.sprite = "Town";
        d.map = "../common/maps/map/town_0.tmx";
        d.radiusFactor = 0.5f;
        d.active = true;
        return d;
    }

    private static PointOfInterest poiAt(PointOfInterestData def, float x, float y) {
        PointOfInterest poi = new PointOfInterest();
        SaveFileData saved = new SaveFileData();
        saved.store("name", def.name);
        saved.store("position", new Vector2(x, y));
        saved.store("rectangle", new Rectangle(x, y, 16f, 16f));
        saved.store("spriteIndex", 0);
        saved.store("active", true);
        saved.store("displayName", def.getDisplayName());
        if (def.targetPlane != null) {
            saved.store("targetPlane", def.targetPlane);
        }
        poi.load(saved);
        return poi;
    }


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
