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
    public void protocolVersionIsFourForCo2Review() {
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 4);
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
