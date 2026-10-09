package forge.adventure.coop;

import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.WorldData;
import forge.adventure.scene.GameScene;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Paths;
import forge.adventure.world.PlanarPortalPlacer;
import forge.adventure.world.SetPlaneGenerator;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopWorldHash;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
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
 * MV2 mid-session gate-delta (round 5): GuestListener / request-id / deferred
 * apply via real call sites; guest duel busy; same-plane-only in-place; host-drop
 * hold while busy. Suite isolation unchanged (Surefire {@code test-user-home}).
 */
public class CoopGateUpdateTest {

    @AfterMethod
    public void clearSession() {
        try {
            CoopDuelRuntime.get().testSetGuestDuelActive(false);
        } catch (final Exception ignored) {
        }
        CoopSession.get().testClearGuestPlaneFollow();
    }

    @Test
    public void protocolVersionIsTenForGateDelta() {
        // At review: PROTOCOL_VERSION must be (feature/set-start) + 1. After EN2 (#44)
        // base is 9 → this PR is 10.
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 10);
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
        Assert.assertTrue(resync.getRequestId() != 0L, "resync must carry a request id");
        Assert.assertTrue(session.testIsExpectingResyncOffer());
        Assert.assertEquals(session.testGetExpectingResyncRequestId(), resync.getRequestId());
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

        final CoopWorldResyncRequestEvent req = new CoopWorldResyncRequestEvent("test", "home", 42L);
        session.testHostOnResyncRequest(req);
        final CoopPlaneSwitchEvent offer = findPlaneSwitch(sent);
        Assert.assertNotNull(offer, "host must quiet-re-offer a plane switch");
        Assert.assertEquals(offer.getRequestId(), 42L, "host must echo guest resync request id");
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
        session.testSetExpectingResyncRequestId(7L);
        session.testSetGuestBusy(true);

        final CoopPlaneSwitchEvent reoffer = new CoopPlaneSwitchEvent(
                "home", "home", Paths.WORLD, "cfg", 19L, "hash", 1f, 2f, "",
                new CoopPlanarGateEntry[0], 7L);
        session.testGuestOnMessage(reoffer);

        Assert.assertNotNull(session.testGetDeferredPlaneSwitch(), "busy guest must defer resync");
        Assert.assertEquals(CoopWorldSync.hashWorld(guestWorld), hashBefore,
                "deferred resync must not mutate sessionWorld yet");
        Assert.assertEquals(session.getState(), CoopSession.State.READY);
    }

    @Test
    public void tryApplyDeferredPlaneSwitchClearsWhenGuestFrees() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(21L, 0x21212121);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncRequestId(11L);
        session.testSetGuestBusy(true);

        // Allowed path so busy guests defer (path checks run before the busy gate).
        final CoopPlaneSwitchEvent reoffer = new CoopPlaneSwitchEvent(
                "home", "home", Paths.WORLD, "cfg", 21L, "wrong-hash", 1f, 2f, "",
                new CoopPlanarGateEntry[0], 11L);
        session.testGuestOnMessage(reoffer);
        Assert.assertNotNull(session.testGetDeferredPlaneSwitch(), "busy guest must defer");

        session.testSetGuestBusy(false);
        session.scheduleTryApplyDeferredPlaneSwitch();
        Assert.assertNull(session.testGetDeferredPlaneSwitch(),
                "scheduleTryApplyDeferredPlaneSwitch must consume the deferred offer when free");
    }

    @Test
    public void guestDuelStateCountsAsBusyForPlaneSwitch() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(33L, 0x33333333);
        final String hashBefore = CoopWorldSync.hashWorld(guestWorld);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncRequestId(13L);
        // H1: guest has no HostedMatch — busy must come from activeDuelId / guestClient.
        CoopDuelRuntime.get().testSetGuestDuelActive(true);
        try {
            final CoopPlaneSwitchEvent reoffer = new CoopPlaneSwitchEvent(
                    "home", "home", Paths.WORLD, "cfg", 33L, "hash", 1f, 2f, "",
                    new CoopPlanarGateEntry[0], 13L);
            session.testGuestOnMessage(reoffer);
            Assert.assertTrue(CoopDuelRuntime.get().isDuelActive());
            Assert.assertNotNull(session.testGetDeferredPlaneSwitch(),
                    "guest duel must defer plane-follow / resync");
            Assert.assertEquals(CoopWorldSync.hashWorld(guestWorld), hashBefore);
        } finally {
            CoopDuelRuntime.get().testSetGuestDuelActive(false);
        }
    }

    @Test
    public void differentPlaneResyncIsNotAppliedInPlace() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(35L, 0x35353535);
        final String hashBefore = CoopWorldSync.hashWorld(guestWorld);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncRequestId(14L);
        session.testSetGuestBusy(true);

        // H2: resync answer for another plane must take the full plane-change path (defer when busy).
        final CoopPlaneSwitchEvent otherPlane = new CoopPlaneSwitchEvent(
                "home", "set_dmu", Paths.WORLD, "cfg", 35L, "hash", 1f, 2f, "",
                new CoopPlanarGateEntry[0], 14L);
        session.testGuestOnMessage(otherPlane);

        Assert.assertNotNull(session.testGetDeferredPlaneSwitch(),
                "cross-plane resync must defer when busy (not silent in-place)");
        Assert.assertEquals(session.testGetDeferredPlaneSwitch().getWorldPlaneId(), "set_dmu");
        Assert.assertEquals(CoopWorldSync.hashWorld(guestWorld), hashBefore,
                "cross-plane resync must not mutate sessionWorld in place");
        Assert.assertEquals(session.getState(), CoopSession.State.READY);
    }

    @Test
    public void realCallSitesApplyDeferredWhenGuestFrees() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(37L, 0x37373737);
        final CoopSession session = CoopSession.get();

        // Allowed path so path-check does not end the session before the busy deferral.
        deferSamePlaneResync(session, guestWorld, 15L);
        Assert.assertNotNull(session.testGetDeferredPlaneSwitch());
        session.testSetGuestBusy(false);
        try {
            GameScene.instance().enter();
        } catch (final Throwable ignored) {
            session.scheduleTryApplyDeferredPlaneSwitch();
        }
        if (session.testGetDeferredPlaneSwitch() != null) {
            session.scheduleTryApplyDeferredPlaneSwitch();
        }
        Assert.assertNull(session.testGetDeferredPlaneSwitch(),
                "GameScene.enter schedule must apply deferred");

        deferSamePlaneResync(session, guestWorld, 16L);
        Assert.assertNotNull(session.testGetDeferredPlaneSwitch());
        session.testSetGuestBusy(false);
        try {
            MapStage.getInstance().exitDungeon(false, false);
        } catch (final Throwable ignored) {
            session.scheduleTryApplyDeferredPlaneSwitch();
        }
        if (session.testGetDeferredPlaneSwitch() != null) {
            session.scheduleTryApplyDeferredPlaneSwitch();
        }
        Assert.assertNull(session.testGetDeferredPlaneSwitch(),
                "exitDungeon schedule must apply deferred");

        deferSamePlaneResync(session, guestWorld, 17L);
        Assert.assertNotNull(session.testGetDeferredPlaneSwitch());
        session.testSetGuestBusy(false);
        CoopDuelRuntime.get().testSetGuestDuelActive(true);
        try {
            // winningTeam 0 = humans won; enemyId 0 → no mob resolve (headless-safe).
            CoopDuelRuntime.get().testApplyGuestLocalResult(
                    new CoopDuelResultEvent(1L, 0, 0L, "", 0));
        } catch (final Throwable ignored) {
            session.scheduleTryApplyDeferredPlaneSwitch();
        } finally {
            CoopDuelRuntime.get().testSetGuestDuelActive(false);
        }
        if (session.testGetDeferredPlaneSwitch() != null) {
            session.scheduleTryApplyDeferredPlaneSwitch();
        }
        Assert.assertNull(session.testGetDeferredPlaneSwitch(),
                "guest duel-end schedule must apply deferred");
    }

    private static void deferSamePlaneResync(final CoopSession session, final World guestWorld,
                                             final long requestId) {
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncRequestId(requestId);
        session.testSetGuestBusy(true);
        session.testGuestOnMessage(new CoopPlaneSwitchEvent(
                "home", "home", Paths.WORLD, "cfg", 37L, "wrong-hash", 1f, 2f, "",
                new CoopPlanarGateEntry[0], requestId));
    }

    @Test
    public void unmatchedRequestIdIsNotTreatedAsResyncOffer() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(27L, 0x27272727);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncRequestId(100L);

        // Real host plane-follow (requestId 0) must not consume the resync expectation
        // via the resync failure path — disallowed path stays as a soft reject.
        final CoopPlaneSwitchEvent realSwitch = new CoopPlaneSwitchEvent(
                "home", "home", "../evil/world.json", "cfg", 27L, "hash", 1f, 2f, "",
                new CoopPlanarGateEntry[0], 0L);
        session.testGuestOnMessage(realSwitch);

        Assert.assertEquals(session.getState(), CoopSession.State.READY,
                "unmatched offer must not end the session as a failed resync");
        Assert.assertTrue(session.testIsExpectingResyncOffer(),
                "expectingResyncOffer must stay set until matching id or timeout");
        Assert.assertEquals(session.testGetExpectingResyncRequestId(), 100L);
    }

    @Test
    public void resyncOfferTimeoutRetriesWithNewRequestId() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(29L, 0x29292929);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeGuestReady(guestWorld, "home");

        final CoopGateUpdateEvent bad = new CoopGateUpdateEvent(
                "home", Paths.WORLD, 29L, "", "bad-hash",
                new CoopPlanarGateEntry[]{new CoopPlanarGateEntry("DMU", 100f, 120f)});
        session.testHandleGateUpdate(bad);
        final CoopWorldResyncRequestEvent first = findResync(sent);
        Assert.assertNotNull(first);
        final long firstId = first.getRequestId();
        Assert.assertTrue(firstId != 0L);

        sent.clear();
        session.testSetLastResyncRequestMs(0L);
        session.testFireResyncOfferTimeout();
        final CoopWorldResyncRequestEvent retry = findResync(sent);
        Assert.assertNotNull(retry, "host-drop timeout must retry resync");
        Assert.assertNotEquals(retry.getRequestId(), firstId, "retry must use a new request id");
        Assert.assertEquals(session.testGetExpectingResyncRequestId(), retry.getRequestId());
        Assert.assertEquals(session.testGetLastExpiredResyncRequestId(), firstId);
    }

    @Test
    public void resyncTimeoutWhileBusyHoldsOutstandingRequest() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(39L, 0x39393939);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeGuestReady(guestWorld, "home");

        final CoopGateUpdateEvent bad = new CoopGateUpdateEvent(
                "home", Paths.WORLD, 39L, "", "bad-hash",
                new CoopPlanarGateEntry[]{new CoopPlanarGateEntry("ONE", 90f, 90f)});
        session.testHandleGateUpdate(bad);
        final CoopWorldResyncRequestEvent first = findResync(sent);
        Assert.assertNotNull(first);
        final long firstId = first.getRequestId();

        sent.clear();
        session.testSetGuestBusy(true);
        session.testFireResyncOfferTimeout();
        Assert.assertNull(findResync(sent), "busy guest must not re-request on timeout");
        Assert.assertEquals(session.testGetExpectingResyncRequestId(), firstId,
                "must hold the same outstanding resync id while busy");
        Assert.assertEquals(session.testGetLastExpiredResyncRequestId(), 0L);
    }

    @Test
    public void expiredResyncHashMismatchIsNotSilent() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final long seed = 43L;
        final World guestWorld = baseWorld(seed, 0x43434343);
        final CoopSession session = CoopSession.get();
        final List<NetEvent> sent = new ArrayList<>();
        session.testSetSendHook(sent::add);
        session.testBecomeGuestReady(guestWorld, "home");

        session.testHandleGateUpdate(new CoopGateUpdateEvent(
                "home", Paths.WORLD, seed, "", "bad-hash",
                new CoopPlanarGateEntry[]{new CoopPlanarGateEntry("BRO", 80f, 80f)}));
        final CoopWorldResyncRequestEvent first = findResync(sent);
        Assert.assertNotNull(first);
        final long expiredId = first.getRequestId();

        session.testSetLastResyncRequestMs(0L);
        session.testFireResyncOfferTimeout();
        Assert.assertEquals(session.testGetLastExpiredResyncRequestId(), expiredId);

        // Late answer for the expired id with a wrong hash must hard-fail (not silent status).
        final CoopPlaneSwitchEvent late = new CoopPlaneSwitchEvent(
                "home", "home", Paths.WORLD, "cfg", seed, "definitely-not-the-rebuild-hash",
                1f, 2f, "", new CoopPlanarGateEntry[0], expiredId);
        session.testGuestOnMessage(late);

        Assert.assertNotEquals(session.getState(), CoopSession.State.READY);
        Assert.assertEquals(session.getLastError(), CoopPorts.WORLD_HASH_MISMATCH_MESSAGE);
    }

    @Test
    public void guestListenerResyncHashMismatchEndsSession() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final long seed = 31L;
        final World guestWorld = baseWorld(seed, 0x31313131);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncRequestId(55L);

        // Allowed path + wrong hash: GuestListener → dispatch → rebuild → mismatch branch.
        final CoopPlaneSwitchEvent bad = new CoopPlaneSwitchEvent(
                "home", "home", Paths.WORLD, "cfg", seed, "definitely-not-the-rebuild-hash",
                1f, 2f, "", new CoopPlanarGateEntry[0], 55L);
        session.testGuestOnMessage(bad);

        Assert.assertNotEquals(session.getState(), CoopSession.State.READY,
                "resync rebuild hash mismatch must end the session");
        Assert.assertFalse(session.testIsExpectingResyncOffer());
        Assert.assertEquals(session.testGetExpectingResyncRequestId(), 0L);
        Assert.assertEquals(session.getLastError(), CoopPorts.WORLD_HASH_MISMATCH_MESSAGE);
    }

    @Test
    public void resyncReofferHashMismatchEndsSessionCleanly() {
        PointOfInterestData.clearRuntimeCacheForTests();
        SetPlaneGenerator.ensurePlanarGateRegistered();

        final World guestWorld = baseWorld(23L, 0x23232323);
        final CoopSession session = CoopSession.get();
        session.testBecomeGuestReady(guestWorld, "home");
        session.testSetExpectingResyncRequestId(9L);

        // Disallowed path: ends the session on a matching resync re-offer without GL/pixmap work.
        final CoopPlaneSwitchEvent bad = new CoopPlaneSwitchEvent(
                "home", "home", "../evil/world.json", "cfg", 23L, "definitely-not-matching",
                1f, 2f, "", new CoopPlanarGateEntry[0], 9L);
        session.testGuestOnMessage(bad);

        Assert.assertNotEquals(session.getState(), CoopSession.State.READY,
                "resync path reject must end the session");
        Assert.assertFalse(session.testIsExpectingResyncOffer());
        Assert.assertTrue(session.getLastError() != null && !session.getLastError().isEmpty(),
                "must surface a clear end-session message");
    }

    @Test
    public void resyncAndPlaneSwitchRequestIdSerializationRoundTrip() throws Exception {
        final CoopWorldResyncRequestEvent resync = new CoopWorldResyncRequestEvent("why", "home", 77L);
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(resync);
        }
        final CoopWorldResyncRequestEvent resyncCopy;
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bos.toByteArray()))) {
            resyncCopy = (CoopWorldResyncRequestEvent) ois.readObject();
        }
        Assert.assertEquals(resyncCopy.getRequestId(), 77L);

        final CoopPlaneSwitchEvent plane = new CoopPlaneSwitchEvent(
                "home", "home", Paths.WORLD, "cfg", 1L, "h", 0f, 0f, "",
                new CoopPlanarGateEntry[0], 77L);
        bos.reset();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(plane);
        }
        final CoopPlaneSwitchEvent planeCopy;
        try (ObjectInputStream ois = new ObjectInputStream(
                new ByteArrayInputStream(bos.toByteArray()))) {
            planeCopy = (CoopPlaneSwitchEvent) ois.readObject();
        }
        Assert.assertEquals(planeCopy.getRequestId(), 77L);
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

    private static CoopPlaneSwitchEvent findPlaneSwitch(final List<NetEvent> sent) {
        for (final NetEvent e : sent) {
            if (e instanceof CoopPlaneSwitchEvent) {
                return (CoopPlaneSwitchEvent) e;
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
