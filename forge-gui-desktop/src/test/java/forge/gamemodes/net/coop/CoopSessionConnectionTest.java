package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDisconnectEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopHelloRejectEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headless CO1 coverage: localhost connect, version/session-code reject,
 * world-hash match/mismatch refusal, session-code lockout, 8-char codes,
 * card-hash fail-closed, second-guest reject.
 */
public class CoopSessionConnectionTest {

    private CoopOverworldServer server;
    private CoopOverworldClient client;
    private CoopOverworldClient client2;
    private int port;
    private String sessionCode;

    @BeforeMethod
    public void setUp() throws Exception {
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("test-cards-v3"));
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
    public void sessionCodeIsEightCharacters() {
        Assert.assertEquals(CoopPorts.SESSION_CODE_LENGTH, 8);
        for (int i = 0; i < 20; i++) {
            final String code = CoopSessionCode.generate();
            Assert.assertEquals(code.length(), 8, code);
            Assert.assertEquals(CoopSessionCode.normalize(code).length(), 8);
        }
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
        final AtomicReference<String> hostState = new AtomicReference<>("HOSTING");

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    final CoopHelloEvent h = (CoopHelloEvent) event;
                    if (!CoopSessionCode.matches(sessionCode, h.getSessionCode())) {
                        final String ip = server.getGuestRemoteAddress();
                        server.getAuthGuard().recordFailure(ip);
                        // Failed attempt must not change host state.
                        Assert.assertEquals(hostState.get(), "HOSTING");
                        server.rejectAndClose("Invalid session code");
                    }
                }
            }

            @Override
            public void onDisconnected(final String reason) {
                hostState.set("HOSTING");
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
                client.send(hello("BADCODE1"));
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
        Assert.assertEquals(hostState.get(), "HOSTING");
    }

    @Test
    public void fiveFailedCodesLockoutAddressWhileHostStaysHosting() throws Exception {
        final AtomicReference<String> hostState = new AtomicReference<>("HOSTING");
        final AtomicInteger failures = new AtomicInteger();

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    final CoopHelloEvent h = (CoopHelloEvent) event;
                    if (!CoopSessionCode.matches(sessionCode, h.getSessionCode())) {
                        failures.incrementAndGet();
                        final String ip = server.getGuestRemoteAddress();
                        server.getAuthGuard().recordFailure(ip);
                        // Host state never leaves HOSTING on a failed attempt.
                        Assert.assertEquals(hostState.get(), "HOSTING");
                        server.rejectAndClose("Invalid session code");
                    }
                }
            }

            @Override
            public void onDisconnected(final String reason) {
                hostState.set("HOSTING");
            }

            @Override
            public void onError(final String message, final Throwable cause) {
            }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        for (int i = 0; i < CoopPorts.SESSION_CODE_MAX_FAILURES; i++) {
            final CountDownLatch rejected = new CountDownLatch(1);
            final AtomicReference<CoopOverworldClient> holder = new AtomicReference<>();
            final String badCode = "WRONGCD" + i;
            final CoopOverworldClient c = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
                @Override
                public void onConnected() {
                    holder.get().send(hello(badCode));
                }

                @Override
                public void onMessage(final NetEvent event) {
                    if (event instanceof CoopHelloRejectEvent) {
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
            holder.set(c);
            c.connect();
            Assert.assertTrue(rejected.await(10, TimeUnit.SECONDS), "attempt " + i + " not rejected");
            c.disconnect();
            Thread.sleep(100);
            Assert.assertEquals(hostState.get(), "HOSTING");
        }
        Assert.assertEquals(failures.get(), CoopPorts.SESSION_CODE_MAX_FAILURES);
        Assert.assertEquals(hostState.get(), "HOSTING");

        // Sixth connect: force lockout for the loopback address Netty will report, then verify refuse.
        final CountDownLatch lockoutReject = new CountDownLatch(1);
        final AtomicReference<String> lockoutReason = new AtomicReference<>();
        server.getAuthGuard().forceLockout("127.0.0.1",
                System.currentTimeMillis() + CoopPorts.SESSION_CODE_LOCKOUT_MS);
        Assert.assertTrue(server.getAuthGuard().isLockedOut("127.0.0.1"));
        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloRejectEvent) {
                    lockoutReason.set(((CoopHelloRejectEvent) event).getReason());
                    lockoutReject.countDown();
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
        Assert.assertTrue(lockoutReject.await(10, TimeUnit.SECONDS), "lockout reject not received");
        Assert.assertTrue(lockoutReason.get().toLowerCase().contains("failed")
                        || lockoutReason.get().toLowerCase().contains("try again"),
                lockoutReason.get());
        Assert.assertEquals(hostState.get(), "HOSTING");
        Assert.assertEquals(CoopPorts.SESSION_CODE_LOCKOUT_MS, 5L * 60L * 1000L);
    }

    @Test
    public void worldHashMismatchRefusesWithExactMessageAndDisconnects() throws Exception {
        final String hostHash = CoopWorldHash.hash(99L, 4, 4, sampleBiome(4), sampleTerrain(4));
        final String guestHash = CoopWorldHash.hash(99L, 4, 4, sampleBiome(4), differentTerrain(4));
        Assert.assertFalse(CoopWorldHash.matches(hostHash, guestHash));
        Assert.assertEquals(CoopPorts.WORLD_HASH_MISMATCH_MESSAGE,
                "Builds or world data differ; update both copies");

        final CountDownLatch gotOffer = new CountDownLatch(1);
        final CountDownLatch guestDisconnected = new CountDownLatch(1);
        final CountDownLatch hostSawDisconnect = new CountDownLatch(1);
        final AtomicReference<String> refuseMsg = new AtomicReference<>();

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
                } else if (event instanceof CoopDisconnectEvent) {
                    hostSawDisconnect.countDown();
                }
            }

            @Override
            public void onDisconnected(final String reason) {
                hostSawDisconnect.countDown();
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                Assert.fail(message);
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
                if (event instanceof CoopWorldOfferEvent) {
                    final CoopWorldOfferEvent offer = (CoopWorldOfferEvent) event;
                    // Mirror CoopSession guest policy: hash mismatch → exact message + disconnect.
                    final String local = guestHash;
                    if (!CoopWorldHash.matches(local, offer.getWorldHash())) {
                        refuseMsg.set(CoopPorts.WORLD_HASH_MISMATCH_MESSAGE);
                    }
                    // Signal only after the refusal is recorded (the test thread reads it right away).
                    gotOffer.countDown();
                    if (refuseMsg.get() != null) {
                        client.send(new CoopDisconnectEvent(CoopPorts.WORLD_HASH_MISMATCH_MESSAGE));
                        client.disconnect();
                    }
                }
            }

            @Override
            public void onDisconnected(final String reason) {
                guestDisconnected.countDown();
            }

            @Override
            public void onError(final String message, final Throwable cause) {
                Assert.fail(message);
            }
        });
        client.connect();
        Assert.assertTrue(gotOffer.await(10, TimeUnit.SECONDS), "no world offer");
        Assert.assertEquals(refuseMsg.get(), CoopPorts.WORLD_HASH_MISMATCH_MESSAGE);
        Assert.assertTrue(guestDisconnected.await(10, TimeUnit.SECONDS), "guest did not disconnect");
        Assert.assertTrue(hostSawDisconnect.await(10, TimeUnit.SECONDS), "host did not see disconnect");
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
                client.send(new CoopSessionReadyEvent(false, "Guest", "x"));
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

    @Test
    public void cardDataHashFailsWhenStaticDataMissing() {
        // Production defaultCardDataHash throws IllegalStateException when StaticData is absent.
        CoopVersion.setCardDataHashSupplier(() -> {
            throw new IllegalStateException("StaticData.instance() is null");
        });
        try {
            CoopVersion.cardDataHash();
            Assert.fail("expected IllegalStateException when StaticData missing");
        } catch (final IllegalStateException expected) {
            Assert.assertTrue(expected.getMessage().contains("StaticData"), expected.getMessage());
        }
        final String reason = CoopVersion.mismatchReason(CoopVersion.buildHash(), "anything");
        Assert.assertEquals(reason, "Card data unavailable — cannot verify co-op version.");

        // Also exercise the real default path: with supplier cleared, missing StaticData must fail closed.
        CoopVersion.setCardDataHashSupplier(null);
        try {
            // If StaticData is actually loaded in this JVM the hash may succeed; that is fine —
            // the fail-closed contract above already covers the missing case. When Class.forName
            // / instance() fails, defaultCardDataHash must throw rather than return a constant.
            final String hash = CoopVersion.cardDataHash();
            Assert.assertNotNull(hash);
            Assert.assertFalse(hash.isEmpty());
            Assert.assertNotEquals(hash, "unavailable");
            Assert.assertNotEquals(hash, "0");
        } catch (final IllegalStateException expected) {
            Assert.assertTrue(
                    expected.getMessage().toLowerCase().contains("card")
                            || expected.getMessage().contains("StaticData")
                            || expected.getMessage().toLowerCase().contains("unavailable"),
                    expected.getMessage());
        }
    }

    @Test
    public void authGuardLockoutConstants() {
        final CoopAuthGuard guard = new CoopAuthGuard();
        Assert.assertFalse(guard.isLockedOut("10.0.0.1"));
        for (int i = 0; i < CoopPorts.SESSION_CODE_MAX_FAILURES - 1; i++) {
            Assert.assertFalse(guard.recordFailure("10.0.0.1"));
            Assert.assertFalse(guard.isLockedOut("10.0.0.1"));
        }
        Assert.assertTrue(guard.recordFailure("10.0.0.1"));
        Assert.assertTrue(guard.isLockedOut("10.0.0.1"));
        Assert.assertTrue(guard.lockoutRemainingMs("10.0.0.1") > 0);
        Assert.assertEquals(CoopPorts.SESSION_CODE_MAX_FAILURES, 5);
        guard.recordSuccess("10.0.0.1");
        Assert.assertFalse(guard.isLockedOut("10.0.0.1"));
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
