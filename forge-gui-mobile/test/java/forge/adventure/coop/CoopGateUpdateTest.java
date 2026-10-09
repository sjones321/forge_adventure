package forge.adventure.coop;

import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.WorldData;
import forge.adventure.util.Paths;
import forge.adventure.world.PlanarPortalPlacer;
import forge.adventure.world.SetPlaneGenerator;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopWorldHash;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopGateUpdateEvent;
import forge.gamemodes.net.event.coop.CoopPlanarGateEntry;
import forge.gamemodes.net.event.coop.CoopPlaneSwitchEvent;
import forge.gamemodes.net.event.coop.CoopWorldResyncRequestEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MV2 mid-session gate-delta (round 3): production {@code notifyCoopHashRefresh}
 * → host push → guest Netty {@code onGateUpdate}; quiet resync / deferral /
 * pending queue. Suite isolation unchanged (Surefire {@code test-user-home}).
 */
public class CoopGateUpdateTest {

    @AfterMethod
    public void clearSession() {
        CoopSession.get().testClearGuestPlaneFollow();
    }

    @Test
    public void protocolVersionIsNineForGateDelta() {
        // At review: PROTOCOL_VERSION must be (feature/set-start) + 1. Today that is 9.
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 9);
    }

    @Test
    public void wireClassFilterAllowsGateUpdateAndResyncEvents() {
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopGateUpdateEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopWorldResyncRequestEvent"));
    }

    @Test
    public void gateUpdateEventSerializationRoundTrip() throws Exception {
        final CoopPlanarGateEntry[] gates = {
                new CoopPlanarGateEntry("DMU", 12.5f, 34.25f),
                new CoopPlanarGateEntry("", 1f, 2f),
        };
        final CoopGateUpdateEvent original = new CoopGateUpdateEvent(
                "home", Paths.WORLD, 99L, "", "abc123hash", gates);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(original);
        }
        final Object read;
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bos.toByteArray()))) {
            read = ois.readObject();
        }
        Assert.assertTrue(read instanceof CoopGateUpdateEvent);
        final CoopGateUpdateEvent copy = (CoopGateUpdateEvent) read;
        Assert.assertEquals(copy.getWorldPlaneId(), "home");
        Assert.assertEquals(copy.getWorldConfigPath(), Paths.WORLD);
        Assert.assertEquals(copy.getWorldSeed(), 99L);
        Assert.assertEquals(copy.getMv2SetCode(), "");
        Assert.assertEquals(copy.getWorldHash(), "abc123hash");
        Assert.assertEquals(copy.getGates().length, 2);
        Assert.assertEquals(copy.getGates()[0].getSetCode(), "DMU");
        Assert.assertEquals(copy.getGates()[0].getX(), 12.5f, 0.001f);
        Assert.assertEquals(copy.getGates()[1].getSetCode(), "");
    }

    @Test
    public void productionNotifyCoopHashRefreshPushesAndGuestNettyApplies() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final long seed = 42L;
        final World live = WorldSave.getCurrentSave().getWorld();
        // Non-zero terrain so clearTerrainAroundWorld in placeGateAt changes the hash.
        installBase(live, seed, 0x22222222);
        final World guestWorld = baseWorld(seed, 0x22222222);
        final String hashBefore = CoopWorldSync.hashWorld(live);
        Assert.assertEquals(CoopWorldSync.hashWorld(guestWorld), hashBefore);

        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeHostReady();
        session.refreshHostLiveWorldHash();

        // Production path: placeGateAt → notifyCoopHashRefresh → notifyGatesChanged.
        Assert.assertNotNull(PlanarPortalPlacer.placeGateAt(live, "DMU", 200f, 240f),
                "host must place a mid-session gate on the live world");
        final CoopGateUpdateEvent update = findGateUpdate(sent);
        Assert.assertNotNull(update, "notifyCoopHashRefresh must push CoopGateUpdateEvent when READY");
        Assert.assertNotEquals(update.getWorldHash(), hashBefore);
        Assert.assertEquals(update.getGates().length, 1);
        Assert.assertEquals(update.getWorldPlaneId(), WorldSave.getCurrentSave().getCurrentPlaneId());

        // Guest Netty onGateUpdate path (testGuestOnMessage → runOnGlQuiet → handleGateUpdateOnGl).
        session.testBecomeGuestReady(guestWorld, update.getWorldPlaneId());
        final int poisBefore = countPois(guestWorld);
        sent.clear();
        session.testGuestOnMessage(update);
        Assert.assertTrue(CoopWorldHash.matches(CoopWorldSync.hashWorld(guestWorld), update.getWorldHash()),
                "guest live sessionWorld hash must match host after in-place delta");
        Assert.assertTrue(CoopWorldHash.matches(session.getWorldHash(), update.getWorldHash()));
        Assert.assertEquals(countPois(guestWorld), poisBefore + 1, "exactly one new gate POI");
        Assert.assertFalse(sent.stream().anyMatch(e -> e instanceof CoopPlaneSwitchEvent));
    }

    @Test
    public void unchangedHashDoesNotPushGateUpdate() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World live = WorldSave.getCurrentSave().getWorld();
        installBase(live, 5L, 0x55555555);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeHostReady();
        session.refreshHostLiveWorldHash();

        session.notifyGatesChanged(live);
        Assert.assertNull(findGateUpdate(sent), "unchanged hash must not push");
    }

    @Test
    public void suppressGatePushSkipsWireSend() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World live = WorldSave.getCurrentSave().getWorld();
        installBase(live, 6L, 0x66666666);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeHostReady();
        session.refreshHostLiveWorldHash();
        session.beginSuppressGatePush();
        try {
            Assert.assertNotNull(PlanarPortalPlacer.placeGateAt(live, "BRO", 180f, 180f));
            Assert.assertNull(findGateUpdate(sent), "suppressed push must not send");
        } finally {
            session.endSuppressGatePush();
        }
    }

    @Test
    public void coalescedNotifiesSendSingleGateUpdate() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World live = WorldSave.getCurrentSave().getWorld();
        installBase(live, 8L, 0x88888888);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeHostReady();
        session.refreshHostLiveWorldHash();
        session.testSetHoldCoalescedGatePush(true);

        // placeGateAt → notifyCoopHashRefresh (held, not flushed yet).
        Assert.assertNotNull(PlanarPortalPlacer.placeGateAt(live, "NEO", 160f, 160f));
        Assert.assertNull(findGateUpdate(sent), "held coalesce must not send yet");
        // Second gate change while scheduled — must not queue a second push.
        Assert.assertNotNull(PlanarPortalPlacer.placeGateAt(live, "BRO", 200f, 200f));
        Assert.assertNull(findGateUpdate(sent));

        session.testFlushCoalescedGatePush();
        int updates = 0;
        for (final NetEvent e : sent) {
            if (e instanceof CoopGateUpdateEvent) {
                updates++;
            }
        }
        Assert.assertEquals(updates, 1, "coalesced notifies must send exactly one update");
        Assert.assertEquals(((CoopGateUpdateEvent) findGateUpdate(sent)).getGates().length, 2);
    }

    @Test
    public void guestIgnoresGateUpdateForOtherPlane() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(7L, 0x11111111);
        final String hashBefore = CoopWorldSync.hashWorld(guestWorld);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeGuestReady(guestWorld, "set_dmu");

        final CoopGateUpdateEvent homeUpdate = new CoopGateUpdateEvent(
                "home", Paths.WORLD, 7L, "", "deadbeef",
                new CoopPlanarGateEntry[]{new CoopPlanarGateEntry("BRO", 10f, 10f)});
        session.testGuestOnMessage(homeUpdate);

        Assert.assertEquals(CoopWorldSync.hashWorld(guestWorld), hashBefore,
                "wrong-plane gate update must not mutate sessionWorld");
        Assert.assertTrue(sent.isEmpty(), "wrong-plane ignore must not request resync");
    }

    @Test
    public void guestMismatchSendsResyncRequestNotSilent() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(11L, 0x33333333);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeGuestReady(guestWorld, "home");

        final CoopGateUpdateEvent bad = new CoopGateUpdateEvent(
                "home", Paths.WORLD, 11L, "", "not-the-real-hash",
                new CoopPlanarGateEntry[]{new CoopPlanarGateEntry("DMU", 200f, 240f)});
        session.testHandleGateUpdate(bad);

        final CoopWorldResyncRequestEvent resync = findResync(sent);
        Assert.assertNotNull(resync, "hash mismatch must send CoopWorldResyncRequestEvent");
        Assert.assertTrue(resync.getReason().contains("mismatch")
                        || resync.getReason().equals(CoopPorts.GATE_UPDATE_MISMATCH_MESSAGE),
                "resync reason=" + resync.getReason());
        Assert.assertEquals(resync.getWorldPlaneId(), "home");
        Assert.assertTrue(session.testIsExpectingResyncOffer());
    }

    @Test
    public void rateLimitedResyncIsQueuedAndFlushed() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(13L, 0x13131313);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetLastResyncRequestMs(System.currentTimeMillis());

        final CoopGateUpdateEvent bad = new CoopGateUpdateEvent(
                "home", Paths.WORLD, 13L, "", "bad-hash",
                new CoopPlanarGateEntry[]{new CoopPlanarGateEntry("ONE", 120f, 140f)});
        session.testHandleGateUpdate(bad);

        Assert.assertNull(findResync(sent), "rate-limited resync must not send immediately");
        Assert.assertNotNull(session.testGetPendingResyncReason(), "must queue one pending reason");

        session.testFlushPendingResync();
        Assert.assertNotNull(findResync(sent), "flush must send the queued resync");
        Assert.assertNull(session.testGetPendingResyncReason());
        Assert.assertTrue(session.testIsExpectingResyncOffer());
    }

    @Test
    public void hostResyncRequestIsQuietRateLimitedAndDoesNotKick() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World live = WorldSave.getCurrentSave().getWorld();
        installBase(live, 17L, 0x17171717);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeHostReady();
        session.refreshHostLiveWorldHash();

        final CoopWorldResyncRequestEvent req = new CoopWorldResyncRequestEvent("test", "home");
        session.testHostOnResyncRequest(req);
        Assert.assertTrue(sent.stream().anyMatch(e -> e instanceof CoopPlaneSwitchEvent),
                "host must quiet-re-offer a plane switch");
        Assert.assertEquals(session.getState(), CoopSession.State.READY, "host must stay READY");
        Assert.assertEquals(session.getRole(), CoopSessionRole.HOST);

        sent.clear();
        session.testSetLastHostResyncHandledMs(System.currentTimeMillis());
        session.testHostOnResyncRequest(req);
        Assert.assertFalse(sent.stream().anyMatch(e -> e instanceof CoopPlaneSwitchEvent),
                "rate-limited host resync must not re-offer");
        Assert.assertEquals(session.getState(), CoopSession.State.READY);
    }

    @Test
    public void resyncReofferDefersWhenGuestBusy() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(19L, 0x19191919);
        final String hashBefore = CoopWorldSync.hashWorld(guestWorld);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncOffer(true);
        session.testSetGuestBusy(true);

        final CoopPlaneSwitchEvent reoffer = new CoopPlaneSwitchEvent(
                "home", "home", Paths.WORLD, "cfg", 19L, "hash", 1f, 2f, "",
                new CoopPlanarGateEntry[0]);
        session.testGuestOnMessage(reoffer);

        Assert.assertNotNull(session.testGetDeferredPlaneSwitch(), "busy guest must defer resync");
        Assert.assertEquals(CoopWorldSync.hashWorld(guestWorld), hashBefore,
                "deferred resync must not mutate sessionWorld yet");
        Assert.assertEquals(session.getState(), CoopSession.State.READY);
    }

    @Test
    public void resyncReofferHashMismatchEndsSessionCleanly() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(23L, 0x23232323);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncOffer(true);

        // Disallowed path: ends the session on a resync re-offer without GL/pixmap work.
        final CoopPlaneSwitchEvent bad = new CoopPlaneSwitchEvent(
                "home", "home", "../evil/world.json", "cfg", 23L, "definitely-not-matching",
                1f, 2f, "", new CoopPlanarGateEntry[0]);
        session.testGuestOnMessage(bad);

        Assert.assertNotEquals(session.getState(), CoopSession.State.READY,
                "resync hash failure must end the session");
        Assert.assertFalse(session.testIsExpectingResyncOffer());
        Assert.assertTrue(session.getLastError() != null && !session.getLastError().isEmpty(),
                "must surface a clear end-session message");
    }

    @Test
    public void inactiveWorldDoesNotPushGateUpdate() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World live = WorldSave.getCurrentSave().getWorld();
        installBase(live, 3L, 0x44444444);
        final World inactive = baseWorld(3L, 0x44444444);

        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeHostReady();

        Assert.assertNotNull(PlanarPortalPlacer.placeGateAt(inactive, "NEO", 160f, 160f));
        session.notifyGatesChanged(inactive);
        Assert.assertNull(findGateUpdate(sent),
                "inactive/staging world must not push a gate update");
    }

    @Test
    public void guestMissingGateUpdateIsDetectedAsMismatch() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final long seed = 99L;
        final World host = baseWorld(seed, 0x33333333);
        Assert.assertNotNull(PlanarPortalPlacer.placeGateAt(host, "BRO", 180f, 220f));
        final CoopPlanarGateEntry[] gates = CoopWorldSync.collectPlanarGates(host);
        final String hostHash = CoopWorldSync.hashWorld(host);
        final CoopGateUpdateEvent update = new CoopGateUpdateEvent(
                "home", Paths.WORLD, seed, "", hostHash, gates);

        final World guestMissed = baseWorld(seed, 0x33333333);
        Assert.assertFalse(CoopWorldHash.matches(CoopWorldSync.hashWorld(guestMissed), update.getWorldHash()),
                "guest that misses the gate update must not match the host live hash");
    }

    private static CoopGateUpdateEvent findGateUpdate(final List<NetEvent> sent) {
        for (final NetEvent e : sent) {
            if (e instanceof CoopGateUpdateEvent) {
                return (CoopGateUpdateEvent) e;
            }
        }
        return null;
    }

    private static CoopWorldResyncRequestEvent findResync(final List<NetEvent> sent) {
        for (final NetEvent e : sent) {
            if (e instanceof CoopWorldResyncRequestEvent) {
                return (CoopWorldResyncRequestEvent) e;
            }
        }
        return null;
    }

    private static int countPois(final World world) {
        int n = 0;
        for (final forge.adventure.pointofintrest.PointOfInterest poi : world.getAllPointOfInterest()) {
            if (poi != null) {
                n++;
            }
        }
        return n;
    }

    private static void installBase(final World world, final long seed, final int terrainFill) {
        world.installTestWorldGrid(grid48(), seed);
        fillTerrain(world, terrainFill);
    }

    private static World baseWorld(final long seed, final int terrainFill) {
        final World w = new World();
        installBase(w, seed, terrainFill);
        return w;
    }

    private static WorldData grid48() {
        final WorldData grid = new WorldData();
        grid.width = 48;
        grid.height = 48;
        grid.tileSize = 16;
        grid.playerStartPosX = 0.5f;
        grid.playerStartPosY = 0.5f;
        return grid;
    }

    private static void fillTerrain(final World world, final int value) {
        if (world == null || world.terrainMap == null) {
            return;
        }
        for (int x = 0; x < world.terrainMap.length; x++) {
            Arrays.fill(world.terrainMap[x], value);
        }
    }
}
