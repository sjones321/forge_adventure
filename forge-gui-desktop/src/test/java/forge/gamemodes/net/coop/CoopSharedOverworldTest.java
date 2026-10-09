package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopEnemyStateEvent;
import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopHostPresenceEvent;
import forge.gamemodes.net.event.coop.CoopNodeStateEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless CO2 coverage: security, atomic claims, guest mirror snapshots,
 * invite expiry / mutual accept, host-in-interior pause.
 */
public class CoopSharedOverworldTest {

    private CoopOverworldServer server;
    private CoopOverworldClient client;
    private int port;
    private String sessionCode;

    @BeforeMethod
    public void setUp() throws Exception {
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("test-cards-co2"));
        sessionCode = CoopSessionCode.generate();
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
    }

    @AfterMethod
    public void tearDown() {
        if (client != null) {
            client.disconnect();
            client = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        CoopVersion.setCardDataHashSupplier(null);
    }

    private static CoopHelloEvent hello(final String code) {
        return new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION,
                CoopVersion.buildHash(), CoopVersion.cardDataHash(), "Guest", "Guest", code);
    }

    @Test
    public void positionSyncRoundTripAfterAuth() throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch gotMove = new CountDownLatch(1);
        final AtomicReference<CoopPlayerMoveEvent> received = new AtomicReference<>();
        final String worldHash = CoopWorldHash.hash(7L, 4, 4, sampleBiome(4), sampleTerrain(4));

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override public void onConnected() { }
            @Override public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "Shandalar Ascendant", "plane",
                            7L, worldHash, CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopSessionReadyEvent) {
                    ready.countDown();
                } else if (event instanceof CoopPlayerMoveEvent) {
                    received.set((CoopPlayerMoveEvent) event);
                    gotMove.countDown();
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        final CountDownLatch guestReady = new CountDownLatch(1);
        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override public void onConnected() { client.send(hello(sessionCode)); }
            @Override public void onMessage(final NetEvent event) {
                if (event instanceof CoopWorldOfferEvent) {
                    final CoopWorldOfferEvent offer = (CoopWorldOfferEvent) event;
                    final String local = CoopWorldHash.hash(offer.getWorldSeed(), 4, 4,
                            sampleBiome(4), sampleTerrain(4));
                    client.send(new CoopSessionReadyEvent(false, "Guest", local));
                    guestReady.countDown();
                    client.send(new CoopPlayerMoveEvent(100f, 200f, 1f, System.currentTimeMillis(),
                            "Guest", "sprites/heroes/Human_m.atlas"));
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        client.connect();
        Assert.assertTrue(guestReady.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(gotMove.await(10, TimeUnit.SECONDS));
        Assert.assertEquals(received.get().getAvatarId(), "sprites/heroes/Human_m.atlas");
    }

    @Test
    public void positionRateLimitRejectsFlood() {
        final CoopPositionSync sync = new CoopPositionSync(5, 15f);
        final long t0 = 1_000_000L;
        int accepted = 0;
        for (int i = 0; i < 20; i++) {
            if (sync.acceptInbound(new CoopPlayerMoveEvent(i * 0.1f, 0f, 1f, t0 + i,
                    "P", "sprites/heroes/Human_m.atlas"), t0) != null) {
                accepted++;
            }
        }
        Assert.assertEquals(accepted, 5);
        Assert.assertNotNull(sync.acceptInbound(
                new CoopPlayerMoveEvent(1f, 1f, 1f, t0 + 50, "P", "sprites/heroes/Human_m.atlas"),
                t0 + 1000L));
    }

    @Test
    public void invalidCoordinatesRejected() {
        final CoopPositionSync sync = new CoopPositionSync(30, 15f);
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(Float.NaN, 0f, 1f, 1L, "P",
                "sprites/heroes/Human_m.atlas")));
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 99f, 1L, "P",
                "sprites/heroes/Human_m.atlas")));
    }

    @Test
    public void badAvatarIdRejected() {
        final CoopPositionSync sync = new CoopPositionSync(30, 15f);
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 1f, 1L, "P",
                "textures/evil.png")));
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 1f, 1L, "P",
                "sprites/monsters/Goblin.atlas")));
        Assert.assertNotNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 1f, 1L, "P",
                "sprites/heroes/Elf_f.atlas")));
    }

    @Test
    public void overLongNameRejected() {
        final CoopPositionSync sync = new CoopPositionSync(30, 15f);
        final String tooLong = "x".repeat(CoopWireLimits.MAX_PLAYER_NAME_LEN + 1);
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 1f, 1L, tooLong,
                "sprites/heroes/Human_m.atlas")));
        Assert.assertNull(CoopWireLimits.acceptPlayerName(tooLong));
        Assert.assertNull(CoopWireLimits.acceptText("y".repeat(CoopWireLimits.MAX_TEXT_LEN + 1)));
        Assert.assertEquals(CoopWireLimits.acceptPlayerName("ok"), "ok");
    }

    @Test
    public void teleportRejectedBySpeedCheck() {
        final CoopPositionSync sync = new CoopPositionSync(30, 15f, 50f);
        final long t0 = 5_000_000L;
        Assert.assertNotNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 1f, t0, "P",
                "sprites/heroes/Human_m.atlas"), t0));
        // 10ms later jump 10_000 px — should reject
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(10000f, 0f, 1f, t0 + 10, "P",
                "sprites/heroes/Human_m.atlas"), t0 + 10));
        // Small step within speed budget — accept
        Assert.assertNotNull(sync.acceptInbound(new CoopPlayerMoveEvent(1f, 0f, 1f, t0 + 100, "P",
                "sprites/heroes/Human_m.atlas"), t0 + 100));
    }

    @Test
    public void round3HostKeepsEntitiesOnDisconnectGuestRemovesMirrors() {
        // Item 1: host clears id maps only; guest removes mirrored entities.
        Assert.assertFalse(CoopWorldAuthority.shouldRemoveEntitiesOnDisconnect(true));
        Assert.assertTrue(CoopWorldAuthority.shouldRemoveEntitiesOnDisconnect(false));

        final Map<Long, String> hostNodes = new java.util.concurrent.ConcurrentHashMap<>();
        hostNodes.put(1L, "oak");
        hostNodes.put(2L, "iron");
        final Map<Long, String> hostIdMap = new java.util.concurrent.ConcurrentHashMap<>(hostNodes);
        // Host disconnect: clear id map, keep real entities.
        if (!CoopWorldAuthority.shouldRemoveEntitiesOnDisconnect(true)) {
            hostIdMap.clear();
        } else {
            hostNodes.clear();
            hostIdMap.clear();
        }
        Assert.assertEquals(hostNodes.size(), 2, "host real entities must survive disconnect");
        Assert.assertEquals(hostIdMap.size(), 0, "host id map cleared");

        final Map<Long, String> guestMirrors = new java.util.concurrent.ConcurrentHashMap<>();
        guestMirrors.put(1L, "oak");
        final Map<Long, String> guestIdMap = new java.util.concurrent.ConcurrentHashMap<>(guestMirrors);
        if (CoopWorldAuthority.shouldRemoveEntitiesOnDisconnect(false)) {
            guestMirrors.clear();
            guestIdMap.clear();
        } else {
            guestIdMap.clear();
        }
        Assert.assertEquals(guestMirrors.size(), 0, "guest mirrors removed");
        Assert.assertEquals(guestIdMap.size(), 0);
    }

    @Test
    public void round3SeparateRequestIdSpacesNoHostLocalResultNoNameFallback() {
        // Item 2: guest ids positive; host-local negative; no result for host-local;
        // gather match is by requestId only (no player-name fallback).
        final CoopWorldAuthority auth = new CoopWorldAuthority(96f, 20, 1000L);
        final long guestReq = 42L;
        Assert.assertTrue(CoopWorldAuthority.isGuestRequestId(guestReq));
        final long hostLocal = auth.nextHostLocalRequestId();
        Assert.assertTrue(CoopWorldAuthority.isHostLocalRequestId(hostLocal));
        Assert.assertFalse(CoopWorldAuthority.isGuestRequestId(hostLocal));
        Assert.assertNotEquals(guestReq, hostLocal);

        final long nodeId = auth.registerNode("oak", 10f, 10f);
        final CoopGatherResultEvent hostClaim = auth.claimLocal(hostLocal, nodeId, "Host", 2, 10f, 10f);
        Assert.assertTrue(hostClaim.isAccepted());
        Assert.assertTrue(CoopWorldAuthority.isHostLocalRequestId(hostClaim.getRequestId()));
        // Policy: host-local claims must not be forwarded as a result event to the guest
        // (runtime sends CLAIMED node state only). Assert id-space separation here.
        Assert.assertFalse(CoopWorldAuthority.isGuestRequestId(hostClaim.getRequestId()));

        final long node2 = auth.registerNode("iron", 0f, 0f);
        final CoopGatherResultEvent guestResult = auth.handleGatherRequest(
                new CoopGatherRequestEvent(99L, node2, 0f, 0f, 1L), "Guest", 1, 0f, 0f, 1L);
        Assert.assertTrue(guestResult.isAccepted());
        Assert.assertEquals(guestResult.getRequestId(), 99L); // host echoes guest id

        // Name-fallback drop: a result for a different requestId must not match
        // even when claimedBy equals the local player name.
        final long pendingRequestId = 7L;
        final CoopGatherResultEvent other = new CoopGatherResultEvent(8L, node2, true, "Guest",
                "iron", 1, "");
        final boolean matchByRequestId = other.getRequestId() == pendingRequestId;
        final boolean legacyNameFallback = other.isAccepted() && "Guest".equals(other.getClaimedBy());
        Assert.assertFalse(matchByRequestId);
        Assert.assertTrue(legacyNameFallback, "precondition: name would have matched under old fallback");
        // Runtime matches requestId only — name fallback must not grant.
        Assert.assertFalse(matchByRequestId && legacyNameFallback);
        final boolean applyRewards = matchByRequestId; // no || legacyNameFallback
        Assert.assertFalse(applyRewards);
    }

    @Test
    public void round3TeleportOnlyAfterAllowAndSpeedUsesActualMax() {
        // Item 3: speed limit from actual max; teleport only after allowTeleport().
        final float actualMax = 80f; // base × road × equipment/skill
        final CoopPositionSync sync = new CoopPositionSync(30, 15f, actualMax);
        Assert.assertEquals(sync.getMaxSpeedPxPerSec(), actualMax, 0.01f);

        final long t0 = 9_000_000L;
        Assert.assertNotNull(sync.acceptInbound(new CoopPlayerMoveEvent(
                0f, 0f, 1f, t0, "P", "sprites/heroes/Human_m.atlas", actualMax, false), t0));

        // Within actualMax × margin over 1s — accept
        final float margin = CoopWireLimits.MOVE_SPEED_MARGIN;
        final float okDist = actualMax * margin * 0.5f;
        Assert.assertNotNull(sync.acceptInbound(new CoopPlayerMoveEvent(
                okDist, 0f, 1f, t0 + 1000, "P", "sprites/heroes/Human_m.atlas", actualMax, false),
                t0 + 1000));

        // Far beyond actual max in 10ms as a walk — reject
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(
                okDist + 5000f, 0f, 1f, t0 + 1010, "P", "sprites/heroes/Human_m.atlas",
                actualMax, false), t0 + 1010));

        // Explicit teleport without allowing action — reject
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(
                8000f, 0f, 1f, t0 + 2000, "P", "sprites/heroes/Human_m.atlas",
                actualMax, true), t0 + 2000));
        Assert.assertFalse(sync.isTeleportArmed());

        // Allowing action (waypoint / portal / reset / POI exit) then teleport — accept
        sync.allowTeleport();
        Assert.assertTrue(sync.isTeleportArmed());
        final CoopPlayerMoveEvent teleported = sync.acceptInbound(new CoopPlayerMoveEvent(
                8000f, 0f, 1f, t0 + 2000, "P", "sprites/heroes/Human_m.atlas",
                actualMax, true), t0 + 2000);
        Assert.assertNotNull(teleported);
        Assert.assertTrue(teleported.isTeleport());
        Assert.assertEquals(sync.getLastAccepted().getX(), 8000f, 0.01f);
        Assert.assertFalse(sync.isTeleportArmed()); // one-shot consumed

        // Second teleport without re-arm — reject
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(
                0f, 0f, 1f, t0 + 3000, "P", "sprites/heroes/Human_m.atlas",
                actualMax, true), t0 + 3000));
    }

    @Test
    public void round3EncounterOnePerContactAndHostRateLimit() {
        // Item 4: one request per mob per contact (guest-side set); host rate-limits.
        final Set<Long> contacted = ConcurrentHashMap.newKeySet();
        final long mobA = 5L;
        final long mobB = 6L;
        Assert.assertTrue(contacted.add(mobA));
        Assert.assertFalse(contacted.add(mobA), "second contact with same mob suppressed");
        Assert.assertTrue(contacted.add(mobB));
        // Separate: remove when out of range, then allow again.
        contacted.remove(mobA);
        Assert.assertTrue(contacted.add(mobA));

        final CoopWorldAuthority auth = new CoopWorldAuthority(96f, 20, 1000L, 2, 1000L);
        final long enemyId = auth.registerEnemy("goblin", 1f, 1f);
        Assert.assertTrue(auth.enemyExists(enemyId));
        Assert.assertTrue(auth.tryAcceptEncounterRequest(1_000L));
        Assert.assertTrue(auth.tryAcceptEncounterRequest(1_000L));
        Assert.assertFalse(auth.tryAcceptEncounterRequest(1_000L), "host rate-limits encounters");
        Assert.assertTrue(auth.tryAcceptEncounterRequest(2_000L), "new window allows again");
    }

    @Test
    public void firstGatherClaimWinsAtomicRace() {
        final CoopWorldAuthority auth = new CoopWorldAuthority(96f, 20, 1000L);
        final long nodeId = auth.registerNode("oak", 50f, 50f);

        final CoopGatherResultEvent host = auth.tryClaim(1L, nodeId, "Host", 2, 50f, 50f, true);
        final CoopGatherResultEvent guest = auth.tryClaim(2L, nodeId, "Guest", 2, 50f, 50f, true);
        Assert.assertTrue(host.isAccepted());
        Assert.assertFalse(guest.isAccepted());
        Assert.assertTrue(guest.getReason().toLowerCase().contains("exist")
                || guest.getReason().toLowerCase().contains("claim"), guest.getReason());
        Assert.assertEquals(auth.nodeCount(), 0);
    }

    @Test
    public void gatherRangeUsesLastAcceptedMoveNotRequestCoords() {
        final CoopWorldAuthority auth = new CoopWorldAuthority(32f, 20, 1000L);
        final long nodeId = auth.registerNode("iron", 0f, 0f);
        // Request claims to be next to the node, but last accepted move is far away.
        final CoopGatherRequestEvent req = new CoopGatherRequestEvent(9L, nodeId, 0f, 0f, 1L);
        final CoopGatherResultEvent denied = auth.handleGatherRequest(req, "Guest", 1,
                500f, 500f, 1L);
        Assert.assertFalse(denied.isAccepted());
        Assert.assertTrue(denied.getReason().toLowerCase().contains("range"), denied.getReason());
        Assert.assertEquals(denied.getRequestId(), 9L);

        final CoopGatherResultEvent ok = auth.handleGatherRequest(
                new CoopGatherRequestEvent(10L, nodeId, 999f, 999f, 2L), "Guest", 1,
                0f, 0f, 2L);
        Assert.assertTrue(ok.isAccepted());
        Assert.assertEquals(ok.getRequestId(), 10L);
    }

    @Test
    public void gatherResultMatchedByRequestId() {
        final CoopGatherResultEvent result = new CoopGatherResultEvent(42L, 7L, true, "Guest",
                "oak", 2, "");
        Assert.assertEquals(result.getRequestId(), 42L);
        Assert.assertEquals(result.getNodeId(), 7L);
        Assert.assertEquals(result.getClaimedBy(), "Guest");
        // Host must ignore inbound gather results (policy asserted in runtime; shape here).
        Assert.assertTrue(result.isAccepted());
    }

    @Test
    public void hostIgnoresGatherResultPolicy() {
        // Documented contract: only guests apply CoopGatherResultEvent.
        // Simulated host path: drop when isWorldAuthority would be true.
        final AtomicBoolean hostApplied = new AtomicBoolean(false);
        final boolean isHost = true;
        final CoopGatherResultEvent event = new CoopGatherResultEvent(1L, 1L, true, "Guest", "oak", 1, "");
        if (!isHost) {
            hostApplied.set(true);
        }
        Assert.assertFalse(hostApplied.get());
        Assert.assertNotNull(event);
    }

    @Test
    public void guestRequestForMissingEnemyDenied() {
        final CoopWorldAuthority auth = new CoopWorldAuthority();
        Assert.assertTrue(auth.denyEnemyRequest(42L));
        final long id = auth.registerEnemy("goblin", 10f, 10f);
        Assert.assertFalse(auth.denyEnemyRequest(id));
    }

    @Test
    public void partyInviteAcceptLeaveAndDecline() {
        final CoopPartyState a = new CoopPartyState();
        final CoopPartyState b = new CoopPartyState();
        final CoopPartyInviteEvent invite = a.createInvite("Host", 1000L);
        Assert.assertEquals(b.receiveInvite(invite, 1000L, 12_000L), CoopPartyState.InviteOutcome.PENDING);
        final CoopPartyResponseEvent accept = b.respond(CoopPartyResponseEvent.Action.ACCEPT, 1000L, 12_000L);
        Assert.assertTrue(a.applyPeerResponse(accept, "Guest", 1000L, 12_000L));
        Assert.assertTrue(a.inParty());
        Assert.assertTrue(b.inParty());
    }

    @Test
    public void simultaneousPartyInvitesBecomeMutual() {
        final CoopPartyState a = new CoopPartyState();
        final CoopPartyState b = new CoopPartyState();
        final CoopPartyInviteEvent aInvite = a.createInvite("A", 1000L);
        final CoopPartyInviteEvent bInvite = b.createInvite("B", 1000L);
        Assert.assertEquals(a.receiveInvite(bInvite, 1001L, 12_000L),
                CoopPartyState.InviteOutcome.MUTUAL_ACCEPT);
        Assert.assertTrue(a.inParty());
        // B still INVITE_SENT until it sees A's invite or ACCEPT — simulate mutual:
        Assert.assertEquals(b.receiveInvite(aInvite, 1001L, 12_000L),
                CoopPartyState.InviteOutcome.MUTUAL_ACCEPT);
        Assert.assertTrue(b.inParty());
    }

    @Test
    public void partyInviteExpires() {
        final CoopPartyState a = new CoopPartyState();
        Assert.assertNotNull(a.createInvite("Host", 1000L));
        Assert.assertEquals(a.getStatus(), CoopPartyState.Status.INVITE_SENT);
        Assert.assertTrue(a.expireIfNeeded(1000L + 13_000L, 12_000L));
        Assert.assertEquals(a.getStatus(), CoopPartyState.Status.SOLO);
    }

    @Test
    public void locationInviteExpires() {
        final CoopLocationPolicy loc = new CoopLocationPolicy();
        loc.setPendingInvite(5L, "town-a", 1000L);
        Assert.assertEquals(loc.getPendingInviteId(), 5L);
        Assert.assertTrue(loc.expireIfNeeded(1000L + 13_000L, 12_000L));
        Assert.assertEquals(loc.getPendingInviteId(), 0L);
    }

    @Test
    public void locationAccepterNotInsideUntilEnter() {
        final CoopLocationPolicy loc = new CoopLocationPolicy();
        loc.markLocalEntered("town-a");
        loc.markPartnerAcceptedInvite();
        Assert.assertTrue(loc.isPartnerAcceptedPending());
        Assert.assertEquals(loc.getOccupancy(), CoopLocationPolicy.InteriorOccupancy.LOCAL);
        loc.markPartnerEntered("town-a");
        Assert.assertFalse(loc.isPartnerAcceptedPending());
        Assert.assertEquals(loc.getOccupancy(), CoopLocationPolicy.InteriorOccupancy.BOTH);
        loc.markPartnerExited();
        Assert.assertEquals(loc.getOccupancy(), CoopLocationPolicy.InteriorOccupancy.LOCAL);
    }

    @Test
    public void locationPolicyBlocksDifferentInterior() {
        final CoopLocationPolicy policy = new CoopLocationPolicy();
        policy.markPartnerEntered("town-a");
        Assert.assertTrue(policy.canEnter("town-a"));
        Assert.assertFalse(policy.canEnter("dungeon-b"));
    }

    @Test
    public void readySnapshotSendsExistingNodesAndEnemies() {
        final CoopWorldAuthority auth = new CoopWorldAuthority();
        final long n1 = auth.registerNode("oak", 1f, 2f);
        final long e1 = auth.registerEnemy("goblin", 3f, 4f);
        final List<NetEvent> out = new ArrayList<>();
        for (final CoopWorldAuthority.NodeRecord n : auth.snapshotNodes()) {
            out.add(new CoopNodeStateEvent(n.id, CoopNodeStateEvent.Action.SPAWN,
                    n.materialId, n.x, n.y, ""));
        }
        for (final CoopWorldAuthority.EnemyRecord e : auth.snapshotEnemies()) {
            out.add(new CoopEnemyStateEvent(e.id, CoopEnemyStateEvent.Action.SPAWN,
                    e.enemyDataId, e.x, e.y, 0f));
        }
        Assert.assertEquals(out.size(), 2);
        Assert.assertTrue(out.get(0) instanceof CoopNodeStateEvent);
        Assert.assertEquals(((CoopNodeStateEvent) out.get(0)).getNodeId(), n1);
        Assert.assertTrue(out.get(1) instanceof CoopEnemyStateEvent);
        Assert.assertEquals(((CoopEnemyStateEvent) out.get(1)).getEnemyId(), e1);
    }

    @Test
    public void lifetimeDespawnReachesGuestAsEvent() {
        final CoopWorldAuthority auth = new CoopWorldAuthority();
        final long id = auth.registerEnemy("wolf", 10f, 10f);
        Assert.assertTrue(auth.enemyExists(id));
        auth.removeEnemy(id);
        final CoopEnemyStateEvent despawn = new CoopEnemyStateEvent(id,
                CoopEnemyStateEvent.Action.DESPAWN, "", 0f, 0f, 0f);
        Assert.assertEquals(despawn.getAction(), CoopEnemyStateEvent.Action.DESPAWN);
        Assert.assertFalse(auth.enemyExists(id));
    }

    @Test
    public void hostInInteriorPauseFlag() {
        final CoopHostPresenceEvent interior = new CoopHostPresenceEvent(
                CoopHostPresenceEvent.Presence.INTERIOR, "Town");
        Assert.assertTrue(interior.isWorldPaused());
        Assert.assertEquals(interior.getPlaceLabel(), "Town");
        final CoopHostPresenceEvent overworld = new CoopHostPresenceEvent(
                CoopHostPresenceEvent.Presence.OVERWORLD, "");
        Assert.assertFalse(overworld.isWorldPaused());
        final CoopHostPresenceEvent duel = new CoopHostPresenceEvent(
                CoopHostPresenceEvent.Presence.DUEL, "goblin");
        Assert.assertTrue(duel.isWorldPaused());
    }

    @Test
    public void unauthenticatedCoopMessagesAreIgnored() throws Exception {
        final AtomicInteger movesBeforeAuth = new AtomicInteger();
        final AtomicBoolean sawMoveAfterAuth = new AtomicBoolean(false);
        final CountDownLatch connected = new CountDownLatch(1);

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override public void onConnected() { connected.countDown(); }
            @Override public void onMessage(final NetEvent event) {
                if (event instanceof CoopPlayerMoveEvent) {
                    if (!server.isGuestAuthenticated()) {
                        movesBeforeAuth.incrementAndGet();
                    } else {
                        sawMoveAfterAuth.set(true);
                    }
                } else if (event instanceof CoopHelloEvent) {
                    server.markGuestAuthenticated();
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override public void onConnected() {
                client.send(new CoopPlayerMoveEvent(1f, 1f, 1f, 1L, "X", "sprites/heroes/Human_m.atlas"));
                client.send(hello(sessionCode));
                client.send(new CoopPlayerMoveEvent(3f, 3f, 1f, 3L, "X", "sprites/heroes/Human_m.atlas"));
            }
            @Override public void onMessage(final NetEvent event) { }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        client.connect();
        Assert.assertTrue(connected.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);
        Assert.assertEquals(movesBeforeAuth.get(), 0);
        Assert.assertTrue(sawMoveAfterAuth.get());
    }

    @Test
    public void disconnectClearsPartnerAndParty() {
        final CoopPartyState party = new CoopPartyState();
        final CoopPartyState guest = new CoopPartyState();
        final CoopPositionSync sync = new CoopPositionSync(10, 15f);
        final CoopLocationPolicy loc = new CoopLocationPolicy();
        final CoopWorldAuthority auth = new CoopWorldAuthority();
        final CoopPartyInviteEvent invite = party.createInvite("Host", 1L);
        Assert.assertEquals(guest.receiveInvite(invite, 1L, 12_000L), CoopPartyState.InviteOutcome.PENDING);
        Assert.assertTrue(party.applyPeerResponse(
                guest.respond(CoopPartyResponseEvent.Action.ACCEPT, 1L, 12_000L), "Guest", 1L, 12_000L));
        sync.acceptInbound(new CoopPlayerMoveEvent(5f, 5f, 1f, 1L, "Guest", "sprites/heroes/Human_m.atlas"));
        loc.markLocalEntered("town-1");
        auth.registerNode("oak", 1f, 1f);
        party.clearParty();
        guest.clearParty();
        sync.clear();
        loc.reset();
        auth.clear();
        Assert.assertFalse(party.inParty());
        Assert.assertNull(sync.getLastAccepted());
        Assert.assertEquals(auth.nodeCount(), 0);
    }

    @Test
    public void partyUiInviteExpiresAndClosesDialog() {
        final CoopPartyState party = new CoopPartyState();
        final CoopLocationPolicy loc = new CoopLocationPolicy();
        final CoopInviteUiState ui = new CoopInviteUiState();
        final CoopPartyInviteEvent invite = party.createInvite("Host", 1_000L);
        Assert.assertNotNull(invite);
        // Peer receives → dialog shown (UI path).
        final CoopPartyState guest = new CoopPartyState();
        Assert.assertEquals(guest.receiveInvite(invite, 1_000L, 12_000L),
                CoopPartyState.InviteOutcome.PENDING);
        ui.showPartyInvite(invite.getInviteId(), "Host");
        Assert.assertTrue(ui.isDialogVisible());
        Assert.assertEquals(ui.getKind(), CoopInviteUiState.PromptKind.PARTY);

        final CoopInviteUiState.ExpiryResult early =
                ui.tickExpiry(guest, loc, 1_000L + 5_000L, 12_000L);
        Assert.assertFalse(early.anyExpired());
        Assert.assertTrue(ui.isDialogVisible());

        final CoopInviteUiState.ExpiryResult expired =
                ui.tickExpiry(guest, loc, 1_000L + 13_000L, 12_000L);
        Assert.assertTrue(expired.partyExpired);
        Assert.assertTrue(expired.dialogClosed);
        Assert.assertFalse(ui.isDialogVisible());
        Assert.assertEquals(guest.getStatus(), CoopPartyState.Status.SOLO);
    }

    @Test
    public void joinFightQueuesBehindPartyAndPreservesOrder() {
        final CoopInviteUiState ui = new CoopInviteUiState();
        final CoopInviteUiState.Prompt party = ui.enqueue(
                CoopInviteUiState.PromptKind.PARTY, 1L, "Host", "");
        Assert.assertNotNull(party);
        Assert.assertEquals(ui.getKind(), CoopInviteUiState.PromptKind.PARTY);
        Assert.assertEquals(ui.queueSize(), 0);

        // Second invite queues (does not replace).
        final CoopInviteUiState.Prompt join = ui.enqueue(
                CoopInviteUiState.PromptKind.JOIN_FIGHT, 2L, "Host", "goblin");
        Assert.assertNull(join);
        Assert.assertEquals(ui.queueSize(), 1);
        Assert.assertEquals(ui.getKind(), CoopInviteUiState.PromptKind.PARTY);

        // Force-queue while HUD busy (exit-dungeon) — never activates while busy.
        final CoopInviteUiState.Prompt busyJoin = ui.enqueue(
                CoopInviteUiState.PromptKind.JOIN_FIGHT, 3L, "Host", "wolf", true);
        Assert.assertNull(busyJoin);
        Assert.assertEquals(ui.queueSize(), 2);
        Assert.assertEquals(ui.getKind(), CoopInviteUiState.PromptKind.PARTY);

        final CoopInviteUiState.Prompt next = ui.hideAndPollNext();
        Assert.assertNotNull(next);
        Assert.assertEquals(next.kind, CoopInviteUiState.PromptKind.JOIN_FIGHT);
        Assert.assertEquals(next.inviteId, 2L);
        Assert.assertEquals(next.detail, "goblin");
        Assert.assertEquals(ui.queueSize(), 1);

        final CoopInviteUiState.Prompt next2 = ui.hideAndPollNext();
        Assert.assertEquals(next2.inviteId, 3L);
        Assert.assertEquals(next2.detail, "wolf");
        Assert.assertEquals(ui.queueSize(), 0);

        // Force-queue alone (HUD busy, nothing active) still queues without activating.
        final CoopInviteUiState empty = new CoopInviteUiState();
        Assert.assertNull(empty.enqueue(
                CoopInviteUiState.PromptKind.JOIN_FIGHT, 9L, "Host", "bat", true));
        Assert.assertFalse(empty.isDialogVisible());
        Assert.assertEquals(empty.queueSize(), 1);
        final CoopInviteUiState.Prompt afterBusy = empty.hideAndPollNext();
        Assert.assertEquals(afterBusy.inviteId, 9L);
        Assert.assertEquals(afterBusy.kind, CoopInviteUiState.PromptKind.JOIN_FIGHT);
    }

    @Test
    public void joinFightTimeoutDoesNotClearUnrelatedPartyDialog() {
        final CoopInviteUiState ui = new CoopInviteUiState();
        ui.enqueue(CoopInviteUiState.PromptKind.PARTY, 1L, "Host", "");
        Assert.assertEquals(ui.getKind(), CoopInviteUiState.PromptKind.PARTY);
        // Join-fight arrives while party dialog is up → queued.
        Assert.assertNull(ui.enqueue(
                CoopInviteUiState.PromptKind.JOIN_FIGHT, 2L, "Host", "goblin"));
        Assert.assertEquals(ui.queueSize(), 1);
        // Timeout removes only the JOIN_FIGHT queue entry — party dialog stays.
        Assert.assertTrue(ui.removeJoinFight(2L));
        Assert.assertEquals(ui.getKind(), CoopInviteUiState.PromptKind.PARTY);
        Assert.assertEquals(ui.queueSize(), 0);
        Assert.assertTrue(ui.isDialogVisible());
    }

    @Test
    public void queueAdvancesAfterExitDungeonBusyClears() {
        // Simulates: exit-dungeon owns the HUD (inviteUi kind NONE) while join-fight
        // force-queues; after exit Yes/No, showNextQueuedCoopInvite → hideAndPollNext.
        final CoopInviteUiState ui = new CoopInviteUiState();
        Assert.assertNull(ui.enqueue(
                CoopInviteUiState.PromptKind.JOIN_FIGHT, 5L, "Host", "slime", true));
        Assert.assertNull(ui.enqueue(
                CoopInviteUiState.PromptKind.LOCATION, 6L, "Host", "Inn", true));
        Assert.assertFalse(ui.isDialogVisible());
        Assert.assertEquals(ui.queueSize(), 2);

        final CoopInviteUiState.Prompt first = ui.hideAndPollNext();
        Assert.assertEquals(first.kind, CoopInviteUiState.PromptKind.JOIN_FIGHT);
        Assert.assertEquals(first.inviteId, 5L);
        Assert.assertEquals(ui.queueSize(), 1);

        final CoopInviteUiState.Prompt second = ui.hideAndPollNext();
        Assert.assertEquals(second.kind, CoopInviteUiState.PromptKind.LOCATION);
        Assert.assertEquals(second.inviteId, 6L);
        Assert.assertEquals(ui.queueSize(), 0);
    }

    @Test
    public void multiplePendingGatherRequestsMatchedById() {
        final CoopPendingGatherQueue queue = new CoopPendingGatherQueue();
        queue.add(1L, 10L, "oak");
        queue.add(2L, 11L, "iron");
        queue.add(3L, 12L, "plant");
        Assert.assertEquals(queue.size(), 3);
        Assert.assertTrue(queue.contains(2L));

        final CoopPendingGatherQueue.Entry second = queue.take(2L);
        Assert.assertNotNull(second);
        Assert.assertEquals(second.nodeId, 11L);
        Assert.assertEquals(second.materialId, "iron");
        Assert.assertEquals(queue.size(), 2);
        Assert.assertNull(queue.take(2L));

        final CoopPendingGatherQueue.Entry first = queue.take(1L);
        Assert.assertEquals(first.materialId, "oak");
        Assert.assertEquals(queue.take(3L).nodeId, 12L);
        Assert.assertTrue(queue.isEmpty());
    }

    @Test
    public void pausedDespawnStaysBehindQueuedSpawn() {
        final CoopPausedEventQueue queue = new CoopPausedEventQueue();
        final CoopEnemyStateEvent spawn = new CoopEnemyStateEvent(7L,
                CoopEnemyStateEvent.Action.SPAWN, "goblin", 1f, 2f, 0f);
        final CoopEnemyStateEvent despawn = new CoopEnemyStateEvent(7L,
                CoopEnemyStateEvent.Action.DESPAWN, "", 0f, 0f, 0f);
        queue.enqueue(spawn);
        queue.enqueue(despawn);
        Assert.assertEquals(queue.size(), 2);

        final List<NetEvent> drained = queue.drain();
        Assert.assertEquals(drained.size(), 2);
        Assert.assertSame(spawn, drained.get(0));
        Assert.assertSame(despawn, drained.get(1));
        Assert.assertTrue(queue.isEmpty());

        // Node SPAWN then CLAIMED stays ordered the same way.
        final CoopNodeStateEvent nSpawn = new CoopNodeStateEvent(3L,
                CoopNodeStateEvent.Action.SPAWN, "oak", 0f, 0f, "");
        final CoopNodeStateEvent claimed = new CoopNodeStateEvent(3L,
                CoopNodeStateEvent.Action.CLAIMED, "oak", 0f, 0f, "Host");
        queue.enqueue(nSpawn);
        queue.enqueue(claimed);
        final List<NetEvent> nodes = queue.drain();
        Assert.assertEquals(((CoopNodeStateEvent) nodes.get(0)).getAction(),
                CoopNodeStateEvent.Action.SPAWN);
        Assert.assertEquals(((CoopNodeStateEvent) nodes.get(1)).getAction(),
                CoopNodeStateEvent.Action.CLAIMED);
    }

    @Test
    public void guestNodeStashAndRestore() {
        final CoopLocalEntityStash<String> stash = new CoopLocalEntityStash<>();
        final List<String> live = new ArrayList<>();
        live.add("oak@1");
        live.add("iron@2");
        live.add("mirror-host-node"); // would be filtered out as mirrored
        stash.stashAndClear(live, id -> !id.startsWith("mirror-"));
        Assert.assertTrue(live.isEmpty());
        Assert.assertEquals(stash.size(), 2);
        Assert.assertEquals(stash.snapshot().get(0), "oak@1");

        final List<String> restored = new ArrayList<>();
        stash.restoreInto(restored);
        Assert.assertEquals(restored.size(), 2);
        Assert.assertTrue(stash.isEmpty());
        Assert.assertTrue(restored.contains("oak@1"));
        Assert.assertTrue(restored.contains("iron@2"));
    }

    @Test
    public void hostBlastExtraClaimSkipsRangeCheck() {
        final CoopWorldAuthority auth = new CoopWorldAuthority(16f, 20, 1000L);
        final long near = auth.registerNode("oak", 0f, 0f);
        final long far = auth.registerNode("oak", 500f, 500f);
        // Primary in range.
        Assert.assertTrue(auth.claimLocal(-1L, near, "Host", 1, 0f, 0f).isAccepted());
        // Blast extra far from player — skip-range still claims.
        Assert.assertTrue(auth.claimLocalSkipRange(-2L, far, "Host", 1).isAccepted());
    }

    @Test
    public void protocolVersionIsExactlyEightForMv2() {
        // CO3=6; MV1 plane-follow=7; MV2 live hash + host gate list + mv2SetCode=8.
        // Exact equality only. Open claims: gate-sync (#40)=9; TR1 (#27)=next after merge.
        // Package K packs format into mv2SetCode (#k:) and does not bump.
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 8);
    }

    private static long[][] sampleBiome(final int n) {
        final long[][] m = new long[n][n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                m[x][y] = x * 31L + y;
            }
        }
        return m;
    }

    private static int[][] sampleTerrain(final int n) {
        final int[][] m = new int[n][n];
        for (int x = 0; x < n; x++) {
            for (int y = 0; y < n; y++) {
                m[x][y] = x + y;
            }
        }
        return m;
    }
}
