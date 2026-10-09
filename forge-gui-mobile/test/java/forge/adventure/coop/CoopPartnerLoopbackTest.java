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
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotAckEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotEvent;
import forge.localinstance.properties.ForgeConstants;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CO5 loopback: real snapshot → ack over the overworld TCP path, real partners
 * map save/load, solo .sav byte-identical after guest unload, lost-final-snapshot.
 * No reflection; awaits real events with timeouts.
 */
public class CoopPartnerLoopbackTest {

    private static final String PROFILE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    private static Path testUserDir;
    private static Path realUserDir;
    private static Map<String, AdventureTestUserDir.FileStamp> realUserDirSnapshot;

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

    @Test
    public void snapshotAckRoundTripOverLoopback() throws Exception {
        final int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }

        final CountDownLatch gotSnap = new CountDownLatch(1);
        final CountDownLatch gotAck = new CountDownLatch(1);
        final AtomicReference<CoopPartnerSnapshotEvent> snapRef = new AtomicReference<>();
        final AtomicReference<CoopPartnerSnapshotAckEvent> ackRef = new AtomicReference<>();

        final CoopOverworldServer[] serverHolder = new CoopOverworldServer[1];
        serverHolder[0] = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override
            public void onConnected() {
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopPartnerSnapshotEvent) {
                    snapRef.set((CoopPartnerSnapshotEvent) event);
                    gotSnap.countDown();
                    final CoopPartnerSnapshotEvent snap = (CoopPartnerSnapshotEvent) event;
                    final SaveFileData data = CoopPartnerCodec.decodeSafe(snap.getPartnerBlob());
                    Assert.assertNotNull(data);
                    WorldSave.getCurrentSave().getPartners().put(PROFILE, data);
                    serverHolder[0].send(new CoopPartnerSnapshotAckEvent(PROFILE, snap.getSequence(), true, ""));
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
        final CoopOverworldServer server = serverHolder[0];
        server.start();
        Assert.assertTrue(server.awaitBound(5000));
        server.markGuestAuthenticated();

        final CoopOverworldClient[] clientHolder = new CoopOverworldClient[1];
        clientHolder[0] = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override
            public void onConnected() {
                try {
                    final AdventurePlayer p = prepareMinimal("LoopPartner", 77);
                    final byte[] blob = CoopPartnerCodec.encode(p.save());
                    clientHolder[0].send(new CoopPartnerSnapshotEvent(PROFILE, 1L, false, blob));
                } catch (final Exception e) {
                    Assert.fail(e.getMessage());
                }
            }

            @Override
            public void onMessage(final NetEvent event) {
                if (event instanceof CoopPartnerSnapshotAckEvent) {
                    ackRef.set((CoopPartnerSnapshotAckEvent) event);
                    gotAck.countDown();
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
        final CoopOverworldClient client = clientHolder[0];
        client.connect();

        Assert.assertTrue(gotSnap.await(10, TimeUnit.SECONDS), "host did not receive snapshot");
        Assert.assertTrue(gotAck.await(10, TimeUnit.SECONDS), "guest did not receive ack");
        Assert.assertTrue(ackRef.get().isAccepted());
        Assert.assertEquals(ackRef.get().getSequence(), 1L);
        Assert.assertEquals(WorldSave.getCurrentSave().getPartners().get(PROFILE).readString("name"), "LoopPartner");

        final SaveFileData stored = WorldSave.getCurrentSave().getPartners().save();
        final WorldPartners loaded = new WorldPartners();
        loaded.load(stored);
        Assert.assertEquals(loaded.get(PROFILE).readInt("gold"), 77);

        client.disconnect();
        server.stop();
    }

    @Test
    public void lostFinalSnapshotWarnsGuest() {
        final CoopSession session = CoopSession.get();
        session.testBeginGuestForPartner(PROFILE);
        session.testSetPartnerLoaded(true);
        // No client connected — final snapshot ack times out.
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
}
