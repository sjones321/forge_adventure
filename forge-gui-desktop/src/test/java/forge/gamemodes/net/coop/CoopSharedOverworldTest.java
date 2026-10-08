package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless CO2 coverage next to {@link CoopSessionConnectionTest}: position
 * sync, rate limits, coordinate rejection, node claim races, enemy/POI/node
 * denies, party invite/accept/leave/decline, unauthenticated drops, disconnect
 * cleanup of partner/party state.
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

    private void handshake(final CountDownLatch ready) throws Exception {
        final String worldHash = CoopWorldHash.hash(7L, 4, 4, sampleBiome(4), sampleTerrain(4));
        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "Shandalar Ascendant", "plane",
                            7L, worldHash, CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopSessionReadyEvent) {
                    ready.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        final CountDownLatch guestReady = new CountDownLatch(1);
        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                client.send(hello(sessionCode));
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopWorldOfferEvent) {
                    final CoopWorldOfferEvent offer = (CoopWorldOfferEvent) event;
                    final String local = CoopWorldHash.hash(offer.getWorldSeed(), 4, 4,
                            sampleBiome(4), sampleTerrain(4));
                    client.send(new CoopSessionReadyEvent(false, "Guest", local));
                    guestReady.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        client.connect();
        Assert.assertTrue(client.awaitConnected(5000));
        Assert.assertTrue(guestReady.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(server.isGuestAuthenticated());
    }

    @Test
    public void positionSyncRoundTripAfterAuth() throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch gotMove = new CountDownLatch(1);
        final AtomicReference<CoopPlayerMoveEvent> received = new AtomicReference<>();

        final String worldHash = CoopWorldHash.hash(7L, 4, 4, sampleBiome(4), sampleTerrain(4));
        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "Shandalar Ascendant", "plane",
                            7L, worldHash, CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopSessionReadyEvent) {
                    ready.countDown();
                } else if (event instanceof CoopPlayerMoveEvent) {
                    if (!server.isGuestAuthenticated()) {
                        Assert.fail("move before auth");
                    }
                    received.set((CoopPlayerMoveEvent) event);
                    gotMove.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        final CountDownLatch guestReady = new CountDownLatch(1);
        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                client.send(hello(sessionCode));
            }

            @Override
            public void onMessage(final NetEvent event) {
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

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        client.connect();
        Assert.assertTrue(guestReady.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(gotMove.await(10, TimeUnit.SECONDS));
        Assert.assertEquals(received.get().getX(), 100f, 0.01f);
        Assert.assertEquals(received.get().getY(), 200f, 0.01f);
        Assert.assertEquals(received.get().getPlayerName(), "Guest");
        Assert.assertEquals(received.get().getAvatarId(), "sprites/heroes/Human_m.atlas");
    }

    @Test
    public void positionRateLimitRejectsFlood() {
        final CoopPositionSync sync = new CoopPositionSync(5, 15f);
        final long t0 = 1_000_000L;
        int accepted = 0;
        for (int i = 0; i < 20; i++) {
            final CoopPlayerMoveEvent ev = new CoopPlayerMoveEvent(i, i, 1f, t0 + i, "P", "avat");
            if (sync.acceptInbound(ev, t0) != null) {
                accepted++;
            }
        }
        Assert.assertEquals(accepted, 5);
        // New window
        Assert.assertNotNull(sync.acceptInbound(
                new CoopPlayerMoveEvent(1f, 1f, 1f, t0 + 50, "P", "avat"), t0 + 1000L));
    }

    @Test
    public void invalidCoordinatesRejected() {
        final CoopPositionSync sync = new CoopPositionSync(30, 15f);
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(Float.NaN, 0f, 1f, 1L, "P", "a")));
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, Float.POSITIVE_INFINITY, 1f, 1L, "P", "a")));
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(CoopWireLimits.MAX_COORD_ABS + 1f, 0f, 1f, 1L, "P", "a")));
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 99f, 1L, "P", "a")));
        Assert.assertNull(sync.acceptInbound(new CoopPlayerMoveEvent(0f, 0f, 1f, 1L,
                "x".repeat(CoopWireLimits.MAX_PLAYER_NAME_LEN + 1), "a")));
        Assert.assertNotNull(sync.acceptInbound(new CoopPlayerMoveEvent(10f, 20f, 2f, 1L, "P", "avat")));
    }

    @Test
    public void firstGatherClaimWinsSecondDenied() {
        final CoopWorldAuthority auth = new CoopWorldAuthority(96f, 20, 1000L);
        final long nodeId = auth.registerNode("oak", 50f, 50f);
        Assert.assertTrue(nodeId > 0L);

        final CoopGatherResultEvent first = auth.handleGatherRequest(
                new CoopGatherRequestEvent(nodeId, 50f, 50f, 1L), "Alice", 2, 1000L);
        Assert.assertTrue(first.isAccepted());
        Assert.assertEquals(first.getClaimedBy(), "Alice");
        Assert.assertEquals(first.getMaterialId(), "oak");
        Assert.assertEquals(first.getAmount(), 2);

        final CoopGatherResultEvent second = auth.handleGatherRequest(
                new CoopGatherRequestEvent(nodeId, 50f, 50f, 2L), "Bob", 2, 1001L);
        Assert.assertFalse(second.isAccepted());
        Assert.assertTrue(second.getReason().toLowerCase().contains("exist")
                || second.getReason().toLowerCase().contains("claim"), second.getReason());
    }

    @Test
    public void gatherOutOfRangeAndMissingNodeDenied() {
        final CoopWorldAuthority auth = new CoopWorldAuthority(32f, 20, 1000L);
        final long nodeId = auth.registerNode("iron", 0f, 0f);

        final CoopGatherResultEvent far = auth.handleGatherRequest(
                new CoopGatherRequestEvent(nodeId, 500f, 500f, 1L), "Guest", 1, 1L);
        Assert.assertFalse(far.isAccepted());
        Assert.assertTrue(far.getReason().toLowerCase().contains("range"), far.getReason());

        final CoopGatherResultEvent missing = auth.handleGatherRequest(
                new CoopGatherRequestEvent(99999L, 0f, 0f, 1L), "Guest", 1, 2L);
        Assert.assertFalse(missing.isAccepted());
        Assert.assertTrue(missing.getReason().toLowerCase().contains("exist"), missing.getReason());
    }

    @Test
    public void guestRequestForMissingEnemyDenied() {
        final CoopWorldAuthority auth = new CoopWorldAuthority();
        Assert.assertTrue(auth.denyEnemyRequest(42L));
        final long id = auth.registerEnemy("goblin", 10f, 10f);
        Assert.assertFalse(auth.denyEnemyRequest(id));
        auth.removeEnemy(id);
        Assert.assertTrue(auth.denyEnemyRequest(id));
    }

    @Test
    public void partyInviteAcceptLeaveAndDecline() {
        final CoopPartyState a = new CoopPartyState();
        final CoopPartyState b = new CoopPartyState();

        final CoopPartyInviteEvent invite = a.createInvite("Host");
        Assert.assertNotNull(invite);
        Assert.assertEquals(a.getStatus(), CoopPartyState.Status.INVITE_SENT);

        Assert.assertTrue(b.receiveInvite(invite));
        Assert.assertEquals(b.getStatus(), CoopPartyState.Status.INVITE_RECEIVED);

        final CoopPartyResponseEvent accept = b.respond(CoopPartyResponseEvent.Action.ACCEPT);
        Assert.assertNotNull(accept);
        Assert.assertTrue(a.applyPeerResponse(accept, "Guest"));
        Assert.assertTrue(a.inParty());
        Assert.assertTrue(b.inParty());

        final CoopPartyResponseEvent leave = a.respond(CoopPartyResponseEvent.Action.LEAVE);
        Assert.assertNotNull(leave);
        Assert.assertTrue(b.applyPeerResponse(leave, "Host"));
        Assert.assertFalse(a.inParty());
        Assert.assertFalse(b.inParty());

        // Decline path
        final CoopPartyInviteEvent invite2 = a.createInvite("Host");
        Assert.assertTrue(b.receiveInvite(invite2));
        final CoopPartyResponseEvent decline = b.respond(CoopPartyResponseEvent.Action.DECLINE);
        Assert.assertTrue(a.applyPeerResponse(decline, "Guest"));
        Assert.assertEquals(a.getStatus(), CoopPartyState.Status.SOLO);
        Assert.assertEquals(b.getStatus(), CoopPartyState.Status.SOLO);
    }

    @Test
    public void unauthenticatedCoopMessagesAreIgnored() throws Exception {
        final AtomicInteger movesBeforeAuth = new AtomicInteger();
        final AtomicBoolean sawMoveAfterAuth = new AtomicBoolean(false);
        final CountDownLatch connected = new CountDownLatch(1);

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                connected.countDown();
            }

            @Override
            public void onMessage(final NetEvent event) {
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

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                // Flood moves before hello — server must drop them.
                client.send(new CoopPlayerMoveEvent(1f, 1f, 1f, 1L, "X", "a"));
                client.send(new CoopPlayerMoveEvent(2f, 2f, 1f, 2L, "X", "a"));
                client.send(hello(sessionCode));
                client.send(new CoopPlayerMoveEvent(3f, 3f, 1f, 3L, "X", "a"));
            }

            @Override
            public void onMessage(final NetEvent event) {
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        client.connect();
        Assert.assertTrue(connected.await(5, TimeUnit.SECONDS));
        Thread.sleep(500);
        Assert.assertEquals(movesBeforeAuth.get(), 0, "pre-auth moves must be dropped by the server");
        Assert.assertTrue(sawMoveAfterAuth.get(), "post-auth move should arrive");
    }

    @Test
    public void disconnectClearsPartnerAndParty() {
        final CoopPartyState party = new CoopPartyState();
        final CoopPartyState guest = new CoopPartyState();
        final CoopPositionSync sync = new CoopPositionSync(10, 15f);
        final CoopLocationPolicy loc = new CoopLocationPolicy();
        final CoopWorldAuthority auth = new CoopWorldAuthority();

        final CoopPartyInviteEvent invite = party.createInvite("Host");
        Assert.assertTrue(guest.receiveInvite(invite));
        final CoopPartyResponseEvent accept = guest.respond(CoopPartyResponseEvent.Action.ACCEPT);
        Assert.assertTrue(party.applyPeerResponse(accept, "Guest"));
        Assert.assertTrue(party.inParty());

        sync.acceptInbound(new CoopPlayerMoveEvent(5f, 5f, 1f, 1L, "Guest", "avat"));
        Assert.assertNotNull(sync.getLastAccepted());
        loc.markLocalEntered("town-1");
        auth.registerNode("oak", 1f, 1f);

        // Simulate disconnect cleanup (mirrors CoopOverworldRuntime.onSessionEnded)
        party.clearParty();
        guest.clearParty();
        sync.clear();
        loc.reset();
        auth.clear();

        Assert.assertFalse(party.inParty());
        Assert.assertFalse(guest.inParty());
        Assert.assertNull(sync.getLastAccepted());
        Assert.assertEquals(loc.getOccupancy(), CoopLocationPolicy.InteriorOccupancy.NONE);
        Assert.assertEquals(auth.nodeCount(), 0);
    }

    @Test
    public void locationPolicyBlocksDifferentInterior() {
        final CoopLocationPolicy policy = new CoopLocationPolicy();
        Assert.assertTrue(policy.canEnter("town-a"));
        policy.markPartnerEntered("town-a");
        Assert.assertTrue(policy.canEnter("town-a"));
        Assert.assertFalse(policy.canEnter("dungeon-b"));
        policy.markPartnerExited();
        Assert.assertTrue(policy.canEnter("dungeon-b"));
    }

    @Test
    public void protocolVersionIsThreeForCo2() {
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 3);
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
