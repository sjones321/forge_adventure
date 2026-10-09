package forge.adventure.coop;

import forge.adventure.AdventureTestUserDir;
import forge.adventure.data.DifficultyData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.WorldSave;
import forge.deck.Deck;
import forge.gamemodes.net.coop.CoopMessageListener;
import forge.gamemodes.net.coop.CoopOverworldClient;
import forge.gamemodes.net.coop.CoopOverworldServer;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopSessionCode;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.coop.CoopWorldHash;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotAckEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import forge.localinstance.properties.ForgeConstants;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CO5 loopback: real join handshake + snapshot/ack over overworld TCP,
 * partners map save/load, solo .sav byte-identical after unload, lost-final-snapshot.
 * No reflection; awaits real events with timeouts.
 */
public class CoopPartnerLoopbackTest {

    private static final String PROFILE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private static Path testUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;

    private CoopOverworldServer server;
    private CoopOverworldClient client;
    private int port;
    private String sessionCode;

    @BeforeClass
    public static void init() throws Exception {
        testUserDir = AdventureTestUserDir.configuredTestUserDir();
        realUserDir = AdventureTestUserDir.defaultRealUserDir();
        realUserDirSnapshot = AdventureTestUserDir.snapshot(realUserDir);
        AdventureTestUserDir.requireIsolatedUserDir();
    }

    @AfterClass
    public static void assertRealUntouched() throws Exception {
        AdventureTestUserDir.assertUnchanged(realUserDir, realUserDirSnapshot, "CoopPartnerLoopbackTest");
    }

    @BeforeMethod
    public void setUp() throws Exception {
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("co5-partner-loopback"));
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

    @Test
    public void snapshotAckRoundTripOverLoopback() throws Exception {
        final String worldHash = CoopWorldHash.hash(42L, 4, 4, sampleBiome(4), sampleTerrain(4));
        final CountDownLatch sessionReady = new CountDownLatch(1);
        final CountDownLatch gotSnap = new CountDownLatch(1);
        final CountDownLatch gotAck = new CountDownLatch(1);
        final AtomicReference<CoopPartnerSnapshotAckEvent> ackRef = new AtomicReference<>();

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "Shandalar Ascendant", "planeHash",
                            42L, worldHash, CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopSessionReadyEvent) {
                    sessionReady.countDown();
                } else if (event instanceof CoopPartnerSnapshotEvent) {
                    final CoopPartnerSnapshotEvent snap = (CoopPartnerSnapshotEvent) event;
                    gotSnap.countDown();
                    final SaveFileData data = CoopPartnerCodec.decodeSafe(snap.getPartnerBlob());
                    Assert.assertNotNull(data);
                    WorldSave.getCurrentSave().getPartners().put(PROFILE, data);
                    server.send(new CoopPartnerSnapshotAckEvent(PROFILE, snap.getSequence(), true, ""));
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
                        CoopVersion.buildHash(), CoopVersion.cardDataHash(),
                        "Guest", "Guest", sessionCode, PROFILE));
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopWorldOfferEvent) {
                    final CoopWorldOfferEvent offer = (CoopWorldOfferEvent) event;
                    final String local = CoopWorldHash.hash(offer.getWorldSeed(), 4, 4,
                            sampleBiome(4), sampleTerrain(4));
                    client.send(new CoopSessionReadyEvent(false, "Guest", local));
                    guestReady.countDown();
                } else if (event instanceof CoopPartnerSnapshotAckEvent) {
                    ackRef.set((CoopPartnerSnapshotAckEvent) event);
                    gotAck.countDown();
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
        Assert.assertTrue(guestReady.await(10, TimeUnit.SECONDS), "guest session ready");
        Assert.assertTrue(sessionReady.await(10, TimeUnit.SECONDS), "host session ready");

        final AdventurePlayer p = prepareMinimal("LoopPartner", 77);
        final byte[] blob = CoopPartnerCodec.encode(p.save());
        client.send(new CoopPartnerSnapshotEvent(PROFILE, 1L, false, blob));

        Assert.assertTrue(gotSnap.await(10, TimeUnit.SECONDS), "host did not receive snapshot");
        Assert.assertTrue(gotAck.await(10, TimeUnit.SECONDS), "guest did not receive ack");
        Assert.assertTrue(ackRef.get().isAccepted());
        Assert.assertEquals(ackRef.get().getSequence(), 1L);
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().get(PROFILE).readString("name"), "LoopPartner");

        final SaveFileData stored = WorldSave.getCurrentSave().getPartners().save();
        final WorldPartners loaded = new WorldPartners();
        loaded.load(stored);
        Assert.assertEquals(loaded.get(PROFILE).readInt("gold"), 77);
    }

    @Test
    public void lostFinalSnapshotWarnsGuest() {
        final CoopSession session = CoopSession.get();
        session.testBeginGuestForPartner(PROFILE);
        session.testSetPartnerLoaded(true);
        final boolean acked = session.partnerSync().sendFinalSnapshotAndAwaitAck(200L);
        Assert.assertFalse(acked, "no host → final ack must time out");
        session.testClearGuestPlaneFollow();
    }

    @Test
    public void soloSavUnchangedAfterUnload() throws Exception {
        final Path solo = Path.of(ForgeConstants.USER_ADVENTURE_DIR, "Shandalar Ascendant", "loop_solo.sav");
        Files.createDirectories(solo.getParent());
        final byte[] before = "solo-bytes-v1".getBytes();
        Files.write(solo, before);

        final AdventurePlayer p = WorldSave.getCurrentSave().getPlayer();
        prepareMinimalInto(p, "TempPartner", 9);
        WorldSave.getCurrentSave().unloadAfterGuestSession();
        Assert.assertNull(WorldSave.getCurrentSave().getWorld().getData());
        Assert.assertFalse(WorldSave.getCurrentSave().save("x", 1));
        Assert.assertEquals(Files.readAllBytes(solo), before);
    }

    private static AdventurePlayer prepareMinimal(final String name, final int gold) {
        final AdventurePlayer p = new AdventurePlayer();
        prepareMinimalInto(p, name, gold);
        return p;
    }

    private static void prepareMinimalInto(final AdventurePlayer p, final String name, final int gold) {
        final DifficultyData d = new DifficultyData();
        d.name = "Easy";
        d.startingLife = 20;
        d.startingMoney = gold;
        d.startingShards = 0;
        d.startingDifficulty = true;
        d.spawnRank = 0;
        d.enemyLifeFactor = 1f;
        d.sellFactor = 0.5f;
        d.shardSellRatio = 0.5f;
        d.goldLoss = 0.1f;
        d.lifeLoss = 0.1f;
        d.startItems = new String[0];
        p.create(name, new Deck(name), true, 0, 0, false, false, d, AdventureModes.Standard);
    }

    private static long[][] sampleBiome(final int n) {
        final long[][] m = new long[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                m[y][x] = (x + y) % 3;
            }
        }
        return m;
    }

    private static int[][] sampleTerrain(final int n) {
        final int[][] m = new int[n][n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                m[y][x] = (x * 3 + y) % 5;
            }
        }
        return m;
    }
}
