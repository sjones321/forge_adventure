package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopHelloRejectEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import forge.gamemodes.net.event.coop.CoopWorldRequestEvent;
import forge.gamemodes.net.event.coop.CoopWorldDataEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless CO1 coverage: localhost connect, version-mismatch reject, world-hash
 * match and mismatch (fallback request).
 */
public class CoopSessionConnectionTest {

    private CoopOverworldServer server;
    private CoopOverworldClient client;
    private int port;

    @BeforeMethod
    public void setUp() throws Exception {
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("test-cards-v1"));
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
                    final CoopHelloEvent hello = (CoopHelloEvent) event;
                    final String mismatch = CoopVersion.mismatchReason(hello.getBuildHash(), hello.getCardDataHash());
                    if (mismatch != null) {
                        server.send(new CoopHelloRejectEvent(mismatch));
                        return;
                    }
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
                client.send(new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION,
                        CoopVersion.buildHash(), CoopVersion.cardDataHash(), "Guest", "Guest"));
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
                    final CoopHelloEvent hello = (CoopHelloEvent) event;
                    final String mismatch = CoopVersion.mismatchReason(hello.getBuildHash(), hello.getCardDataHash());
                    Assert.assertNotNull(mismatch);
                    server.send(new CoopHelloRejectEvent(mismatch));
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
                // Deliberately wrong card hash — host must refuse.
                client.send(new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION,
                        CoopVersion.buildHash(), CoopVersion.sha256Hex("other-cards"), "Guest", "Guest"));
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
                steps.add("host-connected");
            }

            @Override
            public void onMessage(final NetEvent event) {
                steps.add("host-msg:" + event.getClass().getSimpleName());
                if (event instanceof CoopHelloEvent) {
                    final String mismatch = CoopVersion.mismatchReason(
                            ((CoopHelloEvent) event).getBuildHash(),
                            ((CoopHelloEvent) event).getCardDataHash());
                    if (mismatch != null) {
                        server.send(new CoopHelloRejectEvent(mismatch));
                        return;
                    }
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
                steps.add("host-disc:" + reason);
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                steps.add("host-err:" + message);
            }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                steps.add("guest-connected");
                client.send(new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION,
                        CoopVersion.buildHash(), CoopVersion.cardDataHash(), "G", "G"));
            }

            @Override
            public void onMessage(final NetEvent event) {
                steps.add("guest-msg:" + event.getClass().getSimpleName());
                if (event instanceof CoopHelloRejectEvent) {
                    Assert.fail("unexpected reject: " + ((CoopHelloRejectEvent) event).getReason()
                            + " steps=" + steps);
                } else if (event instanceof CoopWorldOfferEvent) {
                    steps.add("offer");
                    gotOffer.countDown();
                    client.send(new CoopWorldRequestEvent(guestHash, "rebuild hash mismatch"));
                } else if (event instanceof CoopWorldDataEvent) {
                    steps.add("data");
                    Assert.assertEquals(((CoopWorldDataEvent) event).getWorldSaveBytes().length, 4);
                    gotData.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
                steps.add("guest-disc:" + reason);
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                steps.add("guest-err:" + message);
            }
        });
        client.connect();
        Assert.assertTrue(gotOffer.await(10, TimeUnit.SECONDS), "no offer; steps=" + steps);
        Assert.assertTrue(gotRequest.await(10, TimeUnit.SECONDS), "no request; steps=" + steps);
        Assert.assertTrue(gotData.await(10, TimeUnit.SECONDS), "no data; steps=" + steps);
        Assert.assertTrue(steps.contains("offer"));
        Assert.assertTrue(steps.contains("request"));
        Assert.assertTrue(steps.contains("data"));
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
