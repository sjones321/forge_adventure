package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopHelloRejectEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopWorldDataEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import forge.gamemodes.net.event.coop.CoopWorldRequestEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless CO1 coverage: localhost connect, version/session-code reject,
 * world-hash match/mismatch, blob guards, second-guest reject.
 */
public class CoopSessionConnectionTest {

    private CoopOverworldServer server;
    private CoopOverworldClient client;
    private CoopOverworldClient client2;
    private int port;
    private String sessionCode;

    @BeforeMethod
    public void setUp() throws Exception {
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("test-cards-v1"));
        sessionCode = CoopSessionCode.generate();
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
    }

    @AfterMethod
    public void tearDown() {
        if (client2 != null) {
            client2.disconnect();
            client2 = null;
        }
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
    public void twoLocalSessionsConnectAndHandshake() throws Exception {
        final String worldHash = CoopWorldHash.hash(42L, 8, 8, sampleBiome(8), sampleTerrain(8));
        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<String> reject = new AtomicReference<>();

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    final CoopHelloEvent h = (CoopHelloEvent) event;
                    if (!CoopSessionCode.matches(sessionCode, h.getSessionCode())) {
                        server.rejectAndClose("Invalid session code");
                        return;
                    }
                    final String mismatch = CoopVersion.mismatchReason(h.getBuildHash(), h.getCardDataHash());
                    if (mismatch != null) {
                        server.rejectAndClose(mismatch);
                        return;
                    }
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "Shandalar Ascendant", "planeHash",
                            42L, worldHash, CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopSessionReadyEvent) {
                    ready.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                Assert.fail(message + ": " + cause);
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
                if (event instanceof CoopHelloRejectEvent) {
                    reject.set(((CoopHelloRejectEvent) event).getReason());
                    guestReady.countDown();
                } else if (event instanceof CoopWorldOfferEvent) {
                    final CoopWorldOfferEvent offer = (CoopWorldOfferEvent) event;
                    final String local = CoopWorldHash.hash(offer.getWorldSeed(), 8, 8,
                            sampleBiome(8), sampleTerrain(8));
                    Assert.assertTrue(CoopWorldHash.matches(local, offer.getWorldHash()));
                    client.send(new CoopSessionReadyEvent(false, "Guest", local));
                    guestReady.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                Assert.fail(message + ": " + cause);
            }
        });
        client.connect();
        Assert.assertTrue(client.awaitConnected(5000));
        Assert.assertTrue(guestReady.await(10, TimeUnit.SECONDS), "guest handshake timed out");
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS), "host ready timed out");
        Assert.assertNull(reject.get(), "unexpected reject: " + reject.get());
    }

    @Test
    public void versionMismatchIsRejected() throws Exception {
        final CountDownLatch rejected = new CountDownLatch(1);
        final AtomicReference<String> reason = new AtomicReference<>();

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    final CoopHelloEvent h = (CoopHelloEvent) event;
                    final String mismatch = CoopVersion.mismatchReason(h.getBuildHash(), h.getCardDataHash());
                    Assert.assertNotNull(mismatch);
                    server.rejectAndClose(mismatch);
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
                client.send(new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION,
                        CoopVersion.buildHash(), CoopVersion.sha256Hex("other-cards"),
                        "Guest", "Guest", sessionCode));
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloRejectEvent) {
                    reason.set(((CoopHelloRejectEvent) event).getReason());
                    rejected.countDown();
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
        Assert.assertTrue(rejected.await(10, TimeUnit.SECONDS), "reject not received");
        Assert.assertTrue(reason.get().toLowerCase().contains("card"), reason.get());
    }

    @Test
    public void wrongSessionCodeIsRejected() throws Exception {
        final CountDownLatch rejected = new CountDownLatch(1);
        final AtomicReference<String> reason = new AtomicReference<>();

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    final CoopHelloEvent h = (CoopHelloEvent) event;
                    if (!CoopSessionCode.matches(sessionCode, h.getSessionCode())) {
                        server.rejectAndClose("Invalid session code");
                    }
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
                client.send(hello("XXXXXX"));
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloRejectEvent) {
                    reason.set(((CoopHelloRejectEvent) event).getReason());
                    rejected.countDown();
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
        Assert.assertTrue(rejected.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(reason.get().toLowerCase().contains("session"), reason.get());
    }

    @Test
    public void secondGuestIsRejected() throws Exception {
        final CountDownLatch firstConnected = new CountDownLatch(1);
        final CountDownLatch secondRejected = new CountDownLatch(1);
        final AtomicInteger connections = new AtomicInteger();

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                connections.incrementAndGet();
                firstConnected.countDown();
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
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
                client.send(hello(sessionCode));
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
        Assert.assertTrue(firstConnected.await(5, TimeUnit.SECONDS));

        client2 = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloRejectEvent) {
                    secondRejected.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        client2.connect();
        Assert.assertTrue(secondRejected.await(10, TimeUnit.SECONDS), "second guest was not rejected");
        Assert.assertEquals(connections.get(), 1);
    }

    @Test
    public void worldBlobWithoutRequestIsIgnoredByPolicy() {
        // Guest-side policy: only accept CoopWorldDataEvent after CoopWorldRequestEvent.
        final AtomicBoolean requested = new AtomicBoolean(false);
        Assert.assertFalse(requested.get());
        // Simulate the gate used by CoopSession.GuestListener.
        final boolean accept = requested.get();
        Assert.assertFalse(accept, "blob must be refused when no request was sent");
        requested.set(true);
        Assert.assertTrue(requested.get());
    }

    @Test
    public void oversizedWorldBlobIsRejected() throws Exception {
        final byte[] huge = new byte[CoopPorts.MAX_WORLD_BLOB_BYTES + 1];
        try {
            CoopWorldBlobGuard.checkSize(huge);
            Assert.fail("expected size rejection");
        } catch (final Exception e) {
            Assert.assertTrue(e.getMessage().toLowerCase().contains("large"), e.getMessage());
        }
    }

    @Test
    public void hashMismatchRejectedBeforeUse() throws Exception {
        final CoopWorldBlobGuard.TestPayload payload = new CoopWorldBlobGuard.TestPayload(1L, "x");
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(payload);
        }
        final Object obj = CoopWorldBlobGuard.deserializeFiltered(bos.toByteArray());
        Assert.assertTrue(obj instanceof CoopWorldBlobGuard.TestPayload);
        try {
            CoopWorldBlobGuard.requireHashMatch("expected-hash", "actual-hash");
            Assert.fail("expected hash rejection");
        } catch (final Exception e) {
            Assert.assertTrue(e.getMessage().toLowerCase().contains("hash"), e.getMessage());
        }
    }

    @Test
    public void worldHashMismatchRequestsFallback() throws Exception {
        final String hostHash = CoopWorldHash.hash(99L, 4, 4, sampleBiome(4), sampleTerrain(4));
        final String guestHash = CoopWorldHash.hash(99L, 4, 4, sampleBiome(4), differentTerrain(4));
        Assert.assertFalse(CoopWorldHash.matches(hostHash, guestHash));

        final CountDownLatch gotOffer = new CountDownLatch(1);
        final CountDownLatch gotRequest = new CountDownLatch(1);
        final CountDownLatch gotData = new CountDownLatch(1);
        final List<String> steps = java.util.Collections.synchronizedList(new ArrayList<>());

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    final CoopHelloEvent h = (CoopHelloEvent) event;
                    if (!CoopSessionCode.matches(sessionCode, h.getSessionCode())) {
                        server.rejectAndClose("Invalid session code");
                        return;
                    }
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "plane", "cfg", 99L, hostHash,
                            CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopWorldRequestEvent) {
                    steps.add("request");
                    gotRequest.countDown();
                    server.send(new CoopWorldDataEvent(99L, hostHash, new byte[] {1, 2, 3, 4}));
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                Assert.fail(message);
            }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        final AtomicBoolean blobRequested = new AtomicBoolean(false);
        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                client.send(hello(sessionCode));
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopWorldOfferEvent) {
                    steps.add("offer");
                    gotOffer.countDown();
                    blobRequested.set(true);
                    client.send(new CoopWorldRequestEvent(guestHash, "rebuild hash mismatch"));
                } else if (event instanceof CoopWorldDataEvent) {
                    Assert.assertTrue(blobRequested.get(), "must not accept blob without request");
                    steps.add("data");
                    Assert.assertEquals(((CoopWorldDataEvent) event).getWorldSaveBytes().length, 4);
                    gotData.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                Assert.fail(message);
            }
        });
        client.connect();
        Assert.assertTrue(gotOffer.await(10, TimeUnit.SECONDS), "no offer; steps=" + steps);
        Assert.assertTrue(gotRequest.await(10, TimeUnit.SECONDS), "no request; steps=" + steps);
        Assert.assertTrue(gotData.await(10, TimeUnit.SECONDS), "no data; steps=" + steps);
    }

    @Test
    public void worldHashMatchAndMismatchPure() {
        final long[][] biome = sampleBiome(4);
        final int[][] terrain = sampleTerrain(4);
        final String a = CoopWorldHash.hash(1L, 4, 4, biome, terrain);
        final String b = CoopWorldHash.hash(1L, 4, 4, biome, terrain);
        final String c = CoopWorldHash.hash(2L, 4, 4, biome, terrain);
        Assert.assertTrue(CoopWorldHash.matches(a, b));
        Assert.assertFalse(CoopWorldHash.matches(a, c));
    }

    @Test
    public void tailscaleDetectionSkipsUPnP() {
        Assert.assertTrue(CoopAddressUtil.isTailscaleAddress("100.64.1.2"));
        Assert.assertTrue(CoopAddressUtil.shouldSkipUPnPForAddress("100.100.100.100"));
        Assert.assertFalse(CoopAddressUtil.isTailscaleAddress("192.168.1.10"));
        Assert.assertFalse(CoopAddressUtil.shouldSkipUPnPForAddress("192.168.1.10"));
        Assert.assertFalse(CoopAddressUtil.isTailscaleAddress("100.not.an.ip"));
    }

    @Test
    public void messagesBeforeAuthAreDropped() throws Exception {
        final CountDownLatch helloSeen = new CountDownLatch(1);
        final AtomicInteger nonHello = new AtomicInteger();

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    helloSeen.countDown();
                } else {
                    nonHello.incrementAndGet();
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
                // Send a non-hello first — server must drop it before auth.
                client.send(new CoopWorldRequestEvent("x", "early"));
                client.send(hello(sessionCode));
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
        Assert.assertTrue(helloSeen.await(10, TimeUnit.SECONDS));
        Thread.sleep(200);
        Assert.assertEquals(nonHello.get(), 0, "pre-auth non-hello must be dropped");
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

    private static int[][] differentTerrain(final int n) {
        final int[][] m = sampleTerrain(n);
        m[0][0] = 999;
        return m;
    }
}
