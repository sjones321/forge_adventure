package forge.adventure.coop;

import forge.adventure.IsolatedAdventureUserDir;
import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.ItemListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.coop.CoopMessageListener;
import forge.gamemodes.net.coop.CoopOverworldClient;
import forge.gamemodes.net.coop.CoopOverworldServer;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopSessionCode;
import forge.gamemodes.net.coop.CoopTradeLog;
import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.coop.CoopTradeState;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent;
import forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
import forge.gamemodes.net.event.coop.CoopTradeRequestEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import forge.sound.SoundSystem;
import forge.gui.interfaces.IGuiGame;
import forge.gamemodes.match.HostedMatch;
import forge.gui.download.GuiDownloadService;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import org.jupnp.UpnpServiceConfiguration;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * TR1 escrow E2E via production {@link CoopTradeRuntime} (with {@code setGlPoster}),
 * real overworld Netty, and a shared controllable GL queue. Host persists into a
 * simulated world save; guest into co-op {@code .chr}. Crash/reload never calls
 * {@code restoreFromLog} — only {@code recoverPendingFromLog} after loading the
 * durable blob.
 */
public class CoopTradeEscrowE2ETest {

    private static final String ITEM_A = "Chandra's Stone";
    private static final String ITEM_B = "Liliana's Stone";

    private File tempChars;
    private DualNet dual;

    @BeforeClass
    public void installHeadlessGui() throws Exception {
        // Before GuiBase / Config / WorldSave touch the real user tree.
        IsolatedAdventureUserDir.install();
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new HeadlessAssetsGui());
        }
        SoundSystem.instance.setIgnorePlayRequests(true);
        final String langDir = GuiBase.getInterface().getAssetsDir() + "res/languages";
        forge.util.Localizer.getInstance().initialize("en-US", langDir);
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("tr1-test-cards"));
    }

    @AfterClass(alwaysRun = true)
    public void restoreIsolatedUserDir() throws Exception {
        IsolatedAdventureUserDir.restoreAndAssertUntouched();
    }

    @BeforeMethod
    public void setUp() throws Exception {
        // Idempotent if suite listener already installed; required when run alone.
        IsolatedAdventureUserDir.install();
        SoundSystem.instance.setIgnorePlayRequests(true);
        final ConfigData cfg = Config.instance().getConfigData();
        cfg.ascendantRules = true;
        Assert.assertTrue(Config.ascendant());
        Assert.assertNotNull(ItemListData.getItem(ITEM_A), "items.json must resolve " + ITEM_A);
        Assert.assertNotNull(ItemListData.getItem(ITEM_B), "items.json must resolve " + ITEM_B);

        tempChars = Files.createTempDirectory("tr1-chars").toFile();
        CoopCharacterStore.setCharactersDirOverride(tempChars);
        dual = DualNet.start(tempChars);
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown() throws Exception {
        if (dual != null) {
            dual.close();
            dual = null;
        }
        CoopCharacterStore.setCharactersDirOverride(null);
        CoopTradeRuntime.setGlPoster(null);
        CoopTradeGlOps.setSaveOverride(null);
        if (tempChars != null) {
            deleteTree(tempChars);
        }
        // Pair the per-method install(); suite listener / @AfterClass still hold the redirect.
        IsolatedAdventureUserDir.restoreAndAssertUntouched();
    }

    // ---- fixtures ----------------------------------------------------------

    private static AdventurePlayer player(final String name, final int gold) throws Exception {
        final AdventurePlayer p = new AdventurePlayer();
        setField(p, "name", name);
        setField(p, "adventureMode", AdventureModes.Standard);
        final DifficultyData[] diffs = Config.instance().getConfigData().difficulties;
        if (diffs != null && diffs.length > 0 && diffs[0] != null && diffs[0].name != null) {
            final Field dd = AdventurePlayer.class.getDeclaredField("difficultyData");
            dd.setAccessible(true);
            final DifficultyData data = (DifficultyData) dd.get(p);
            data.name = diffs[0].name;
            data.startingLife = diffs[0].startingLife;
            data.startingMoney = diffs[0].startingMoney;
        } else {
            final Field dd = AdventurePlayer.class.getDeclaredField("difficultyData");
            dd.setAccessible(true);
            ((DifficultyData) dd.get(p)).name = "Easy";
        }
        SoundSystem.instance.setIgnorePlayRequests(true);
        p.giveGold(gold);
        p.getBags().resetToDefaults(Config.instance().getConfigData());
        return p;
    }

    private static void setField(final Object target, final String name, final Object value)
            throws Exception {
        final Field f = AdventurePlayer.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void giveMaterial(final AdventurePlayer p, final String id, final int n) {
        Assert.assertTrue(p.addMaterial(id, n));
    }

    private static void giveItem(final AdventurePlayer p, final String name, final int n) {
        for (int i = 0; i < n; i++) {
            Assert.assertTrue(p.addItem(name, false), "addItem " + name);
        }
    }

    private static int itemCount(final AdventurePlayer p, final String name) {
        return new AdventurePlayerTradeBag(p).getItemCount(name);
    }

    private static void assertBag(final AdventurePlayer p, final int gold,
                                   final int oak, final int iron,
                                   final int itemA, final int itemB) {
        Assert.assertEquals(p.getGold(), gold, p.getName() + " gold");
        Assert.assertEquals(p.getMaterial("oak"), oak, p.getName() + " oak");
        Assert.assertEquals(p.getMaterial("iron"), iron, p.getName() + " iron");
        Assert.assertEquals(itemCount(p, ITEM_A), itemA, p.getName() + " " + ITEM_A);
        Assert.assertEquals(itemCount(p, ITEM_B), itemB, p.getName() + " " + ITEM_B);
    }

    private static void deleteTree(final File f) {
        if (f == null || !f.exists()) {
            return;
        }
        final File[] kids = f.listFiles();
        if (kids != null) {
            for (final File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    /** Host world-save path (ObjectOutputStream + deflate of player.save()). */
    static void saveHostWorld(final AdventurePlayer p, final File file) throws IOException {
        if (p == null || file == null) {
            return;
        }
        final File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        final SaveFileData data = p.save();
        final File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(def)) {
            oos.writeObject(data);
            oos.flush();
        }
        try {
            Files.move(tmp.toPath(), file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static boolean loadHostWorld(final AdventurePlayer target, final File file)
            throws IOException, ClassNotFoundException {
        if (target == null || file == null || !file.isFile()) {
            return false;
        }
        try (FileInputStream fis = new FileInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(inf)) {
            final SaveFileData data = (SaveFileData) ois.readObject();
            target.load(data);
            return true;
        }
    }

    // ---- DualNet -----------------------------------------------------------

    /**
     * Dual peer over real overworld Netty. One shared GL queue; Netty invokes
     * production {@code onTrade*} which {@code postGl} themselves.
     */
    private static final class DualNet {
        final CoopTradeRuntime hostRt;
        final CoopTradeRuntime guestRt;
        final AtomicReference<AdventurePlayer> hostPlayer;
        final AtomicReference<AdventurePlayer> guestPlayer;
        final AtomicReference<String> hostPeerId;
        final AtomicReference<String> guestPeerId;
        final Queue<Runnable> gl = new ArrayDeque<>();
        final CoopOverworldServer server;
        final CoopOverworldClient client;
        final File hostWorldFile;
        volatile boolean hostLinked = true;
        volatile boolean guestLinked = true;
        /** Drop Escrowed/Delivered on the wire so both sides can escrow without auto-deliver. */
        volatile boolean dropEscrowWire = false;

        private DualNet(final CoopTradeRuntime hostRt, final CoopTradeRuntime guestRt,
                        final AtomicReference<AdventurePlayer> hostPlayer,
                        final AtomicReference<AdventurePlayer> guestPlayer,
                        final AtomicReference<String> hostPeerId,
                        final AtomicReference<String> guestPeerId,
                        final CoopOverworldServer server, final CoopOverworldClient client,
                        final File hostWorldFile) {
            this.hostRt = hostRt;
            this.guestRt = guestRt;
            this.hostPlayer = hostPlayer;
            this.guestPlayer = guestPlayer;
            this.hostPeerId = hostPeerId;
            this.guestPeerId = guestPeerId;
            this.server = server;
            this.client = client;
            this.hostWorldFile = hostWorldFile;
        }

        static DualNet start(final File tempChars) throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                DualNet dual = null;
                try {
                    dual = startOnce(tempChars);
                    return dual;
                } catch (final Exception | AssertionError e) {
                    if (dual != null) {
                        dual.close();
                    }
                    last = e instanceof Exception ? (Exception) e : new Exception(e);
                    Thread.sleep(150L * (attempt + 1));
                }
            }
            throw last != null ? last : new IllegalStateException("DualNet.start failed");
        }

        private static DualNet startOnce(final File tempChars) throws Exception {
            final int port;
            try (ServerSocket ss = new ServerSocket(0)) {
                port = ss.getLocalPort();
            }
            final String code = CoopSessionCode.generate();
            final AtomicReference<AdventurePlayer> hostPlayer =
                    new AtomicReference<>(player("HostHero", 100));
            final AtomicReference<AdventurePlayer> guestPlayer =
                    new AtomicReference<>(player("GuestHero", 80));
            giveMaterial(hostPlayer.get(), "oak", 5);
            giveMaterial(guestPlayer.get(), "iron", 4);
            giveItem(hostPlayer.get(), ITEM_A, 2);
            giveItem(guestPlayer.get(), ITEM_B, 1);

            final File hostWorldFile = new File(tempChars, "host-world.sav");
            // Baseline durable snapshot (trade has not started). Crash reload must
            // not call syncLogAndSave — only listener saves during escrow/deliver.
            saveHostWorld(hostPlayer.get(), hostWorldFile);
            CoopCharacterStore.savePlayer(guestPlayer.get());

            final AtomicReference<DualNet> self = new AtomicReference<>();
            final AtomicReference<CoopOverworldServer> serverRef = new AtomicReference<>();
            final AtomicReference<CoopOverworldClient> clientRef = new AtomicReference<>();
            final CountDownLatch ready = new CountDownLatch(2);
            final AtomicReference<String> netError = new AtomicReference<>();

            final AtomicReference<String> hostPeerId = new AtomicReference<>("GuestHero");
            final AtomicReference<String> guestPeerId = new AtomicReference<>("HostHero");

            final CoopTradeRuntime hostRt = CoopTradeRuntime.createForTest();
            final CoopTradeRuntime guestRt = CoopTradeRuntime.createForTest();

            CoopTradeRuntime.setGlPoster(r -> {
                final DualNet d = self.get();
                if (d != null) {
                    synchronized (d.gl) {
                        d.gl.add(r);
                    }
                } else {
                    r.run();
                }
            });
            CoopTradeGlOps.setSaveOverride(p -> {
                try {
                    if (p != null && "HostHero".equals(p.getName())) {
                        saveHostWorld(p, hostWorldFile);
                    } else {
                        CoopCharacterStore.savePlayer(p);
                    }
                } catch (final IOException e) {
                    throw new IllegalStateException("trade save failed", e);
                }
            });

            final CoopOverworldServer server = new CoopOverworldServer(port, new CoopMessageListener() {
                @Override public void onConnected() { }
                @Override public void onDisconnected(final String reason) { }
                @Override public void onError(final String message, final Throwable cause) {
                    netError.compareAndSet(null, "host netty: " + message);
                }
                @Override
                public void onMessage(final NetEvent event) {
                    final CoopOverworldServer srv = serverRef.get();
                    final DualNet d = self.get();
                    if (event instanceof CoopHelloEvent) {
                        final CoopHelloEvent h = (CoopHelloEvent) event;
                        if (!CoopSessionCode.matches(code, h.getSessionCode())) {
                            srv.rejectAndClose("bad code");
                            return;
                        }
                        srv.markGuestAuthenticated();
                        srv.send(new CoopWorldOfferEvent("Host", "plane", "hash",
                                1L, "wh", CoopPorts.GAME_PORT, port));
                        return;
                    }
                    if (event instanceof CoopSessionReadyEvent) {
                        ready.countDown();
                        return;
                    }
                    if (d == null || !d.hostLinked) {
                        return;
                    }
                    // onTrade* postGl themselves.
                    dispatch(d.hostRt, event);
                }
            });
            serverRef.set(server);
            server.start();
            Assert.assertTrue(server.awaitBound(5000));

            final CoopOverworldClient client = new CoopOverworldClient("127.0.0.1", port,
                    new CoopMessageListener() {
                        @Override public void onConnected() {
                            clientRef.get().send(new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION,
                                    CoopVersion.buildHash(), CoopVersion.cardDataHash(),
                                    "Guest", "GuestHero", code));
                        }
                        @Override public void onDisconnected(final String reason) { }
                        @Override public void onError(final String message, final Throwable cause) {
                            netError.compareAndSet(null, "guest netty: " + message);
                        }
                        @Override
                        public void onMessage(final NetEvent event) {
                            final DualNet d = self.get();
                            if (event instanceof CoopWorldOfferEvent) {
                                clientRef.get().send(new CoopSessionReadyEvent(false, "Guest", "local"));
                                ready.countDown();
                                return;
                            }
                            if (d == null || !d.guestLinked) {
                                return;
                            }
                            dispatch(d.guestRt, event);
                        }
                    });
            clientRef.set(client);
            client.connect();
            Assert.assertTrue(client.awaitConnected(5000));
            Assert.assertTrue(ready.await(5, TimeUnit.SECONDS),
                    "session ready" + (netError.get() != null ? " (" + netError.get() + ")" : ""));

            final DualNet dual = new DualNet(hostRt, guestRt, hostPlayer, guestPlayer,
                    hostPeerId, guestPeerId, server, client, hostWorldFile);
            self.set(dual);

            final Consumer<NetEvent> hostSend = ev -> {
                if (ev == null || !dual.guestLinked) {
                    return;
                }
                if (dual.dropEscrowWire && (ev instanceof CoopTradeEscrowedEvent
                        || ev instanceof CoopTradeDeliveredEvent)) {
                    return;
                }
                server.send(ev);
            };
            final Consumer<NetEvent> guestSend = ev -> {
                if (ev == null || !dual.hostLinked) {
                    return;
                }
                if (dual.dropEscrowWire && (ev instanceof CoopTradeEscrowedEvent
                        || ev instanceof CoopTradeDeliveredEvent)) {
                    return;
                }
                client.send(ev);
            };

            hostRt.setSendOverride(hostSend);
            guestRt.setSendOverride(guestSend);
            hostRt.setPlayerOverride(hostPlayer::get);
            guestRt.setPlayerOverride(guestPlayer::get);
            hostRt.setHostOverride(() -> Boolean.TRUE);
            guestRt.setHostOverride(() -> Boolean.FALSE);
            hostRt.setPeerIdOverride(hostPeerId::get);
            guestRt.setPeerIdOverride(guestPeerId::get);
            hostRt.setSkipSessionListener(true);
            guestRt.setSkipSessionListener(true);
            hostRt.attachForTest();
            guestRt.attachForTest();
            return dual;
        }

        static void dispatch(final CoopTradeRuntime rt, final NetEvent event) {
            if (event instanceof CoopTradeRequestEvent) {
                rt.onTradeRequest((CoopTradeRequestEvent) event);
            } else if (event instanceof CoopTradeInviteEvent) {
                // Bypass invite UI / CoopOverworldRuntime HUD; still use production state path.
                try {
                    rt.onTradeInvite((CoopTradeInviteEvent) event);
                } catch (final Exception ex) {
                    final CoopTradeInviteEvent inv = (CoopTradeInviteEvent) event;
                    rt.getState().receiveInvite(inv, false);
                }
            } else if (event instanceof CoopTradeResponseEvent) {
                rt.onTradeResponse((CoopTradeResponseEvent) event);
            } else if (event instanceof CoopTradeOfferEvent) {
                rt.onTradeOffer((CoopTradeOfferEvent) event);
            } else if (event instanceof CoopTradeConfirmEvent) {
                rt.onTradeConfirm((CoopTradeConfirmEvent) event);
            } else if (event instanceof CoopTradeCancelEvent) {
                rt.onTradeCancel((CoopTradeCancelEvent) event);
            } else if (event instanceof CoopTradeEscrowedEvent) {
                rt.onTradeEscrowed((CoopTradeEscrowedEvent) event);
            } else if (event instanceof CoopTradeDeliveredEvent) {
                rt.onTradeDelivered((CoopTradeDeliveredEvent) event);
            } else if (event instanceof CoopTradeReconcileEvent) {
                rt.onTradeReconcile((CoopTradeReconcileEvent) event);
            }
        }

        void drainAll() throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                final Runnable r;
                synchronized (gl) {
                    r = gl.poll();
                }
                if (r != null) {
                    r.run();
                    continue;
                }
                Thread.sleep(5);
                synchronized (gl) {
                    if (gl.isEmpty()) {
                        Thread.sleep(15);
                        if (gl.isEmpty()) {
                            return;
                        }
                    }
                }
            }
            Assert.fail("GL queue did not drain: size=" + gl.size());
        }

        void drainUntil(final java.util.function.BooleanSupplier done) throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                if (done.getAsBoolean()) {
                    return;
                }
                final Runnable r;
                synchronized (gl) {
                    r = gl.poll();
                }
                if (r != null) {
                    r.run();
                } else {
                    Thread.sleep(5);
                }
            }
            Assert.fail("condition not reached");
        }

        void openTrade() throws InterruptedException {
            synchronized (gl) {
                gl.add(() -> {
                    final CoopTradeRequestEvent req = guestRt.getState().beginRequest("GuestHero");
                    Assert.assertNotNull(req);
                    // sendOverride is private; drive via Netty client directly.
                    if (hostLinked) {
                        client.send(req);
                    }
                });
            }
            drainAll();
            Assert.assertEquals(hostRt.getState().getStatus(), CoopTradeState.Status.INVITE_SENT);
            Assert.assertTrue(hostRt.getState().getTradeId() != 0L);
            Assert.assertEquals(guestRt.getState().getStatus(), CoopTradeState.Status.INVITE_RECEIVED);
            guestRt.acceptTradeInvite();
            drainAll();
            Assert.assertEquals(hostRt.getState().getStatus(), CoopTradeState.Status.OPEN);
            Assert.assertEquals(guestRt.getState().getStatus(), CoopTradeState.Status.OPEN);
            Assert.assertEquals(guestRt.getState().getTradeId(), hostRt.getState().getTradeId());
        }

        void setOffer(final boolean asHost, final CoopTradeOffer offer) {
            (asHost ? hostRt : guestRt).updateLocalOffer(offer);
        }

        void confirm(final boolean asHost) {
            (asHost ? hostRt : guestRt).setLocalConfirmed(true);
        }

        /**
         * Confirm both while dropping Escrowed/Delivered on the wire so each side
         * escrows locally without receiving peer escrowed (no auto-deliver).
         */
        void confirmEscrowBothFreeze() throws InterruptedException {
            dropEscrowWire = true;
            confirm(true);
            confirm(false);
            drainUntil(() -> hostRt.getTradeLog().hasEscrowed(hostRt.getState().getTradeId())
                    && guestRt.getTradeLog().hasEscrowed(guestRt.getState().getTradeId())
                    && !hostRt.getTradeLog().hasDelivered(hostRt.getState().getTradeId())
                    && !guestRt.getTradeLog().hasDelivered(guestRt.getState().getTradeId()));
            freezeAtStep();
            dropEscrowWire = false;
        }

        void discardPendingGl() {
            synchronized (gl) {
                gl.clear();
            }
        }

        void freezeAtStep() {
            hostLinked = false;
            guestLinked = false;
            discardPendingGl();
        }

        /** Local host escrow only (both confirms applied locally; Escrowed not wired). */
        void escrowHostOnly() throws InterruptedException {
            freezeAtStep();
            synchronized (gl) {
                gl.add(() -> {
                    final long id = hostRt.getState().getTradeId();
                    final int hv = hostRt.getState().getHostOfferVersion();
                    final int gv = hostRt.getState().getGuestOfferVersion();
                    hostRt.getState().acceptConfirm(new CoopTradeConfirmEvent(
                            id, CoopTradeRole.HOST, true, hv, gv));
                    hostRt.getState().acceptConfirm(new CoopTradeConfirmEvent(
                            id, CoopTradeRole.GUEST, true, hv, gv));
                    Assert.assertTrue(CoopTradeGlOps.performEscrow(
                            hostRt.getState(), hostRt.getTradeLog(),
                            new AdventurePlayerTradeBag(hostPlayer.get()), ev -> { }));
                });
            }
            drainAll();
        }

        /**
         * Crash host: discard memory, load lasting world save, swap playerOverride,
         * reload log + recoverPendingFromLog. Does NOT call restoreFromLog or
         * syncLogAndSave before discard.
         */
        void crashReloadHost() throws Exception {
            final AdventurePlayer fresh = new AdventurePlayer();
            Assert.assertTrue(loadHostWorld(fresh, hostWorldFile),
                    "host-world.sav missing at " + hostWorldFile.getAbsolutePath());
            hostPlayer.set(fresh);
            hostRt.getTradeLog().clear();
            CoopTradeGlOps.loadLogFromPlayer(fresh, hostRt.getTradeLog());
            hostRt.getState().reset();
            hostRt.getState().recoverPendingFromLog();
        }

        void crashReloadGuest() throws Exception {
            final String name = guestPlayer.get().getName();
            final File chr = CoopCharacterStore.characterFile(name);
            Assert.assertTrue(chr.isFile(), "guest .chr missing at " + chr.getAbsolutePath());
            final AdventurePlayer fresh = new AdventurePlayer();
            Assert.assertTrue(CoopCharacterStore.loadPlayer(fresh, name),
                    "loadPlayer failed for " + name);
            guestPlayer.set(fresh);
            guestRt.getTradeLog().clear();
            CoopTradeGlOps.loadLogFromPlayer(fresh, guestRt.getTradeLog());
            guestRt.getState().reset();
            guestRt.getState().recoverPendingFromLog();
        }

        void reconcileBoth() throws InterruptedException {
            hostLinked = true;
            guestLinked = true;
            hostRt.onSessionReadyReconcile();
            guestRt.onSessionReadyReconcile();
            drainAll();
        }

        void close() {
            try { client.disconnect(); } catch (final Exception ignored) { }
            try { server.stop(); } catch (final Exception ignored) { }
            try { Thread.sleep(50); } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            CoopTradeRuntime.setGlPoster(null);
            CoopTradeGlOps.setSaveOverride(null);
        }
    }

    private static CoopTradeOffer matsOffer(final String mat, final int count, final int have,
                                            final int gold) {
        return new CoopTradeOffer(gold,
                Collections.singletonList(new CoopTradeOffer.Line(mat, count, have)),
                null, null);
    }

    private static CoopTradeOffer itemOffer(final String item, final int count, final int have) {
        return new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line(item, count, have)), null);
    }

    // ---- tests -------------------------------------------------------------

    @Test
    public void protocolVersionRemainsEight() {
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 8);
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent"));
    }

    @Test
    public void happyPathExactBagsViaNettyAndGl() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 2, 5, 25));
        dual.setOffer(false, matsOffer("iron", 1, 4, 10));
        dual.drainAll();
        dual.confirm(true);
        dual.confirm(false);
        dual.drainAll();

        Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId()));
        Assert.assertTrue(dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
        Assert.assertEquals(dual.hostRt.getState().getStatus(), CoopTradeState.Status.COMPLETED);
        Assert.assertEquals(dual.guestRt.getState().getStatus(), CoopTradeState.Status.COMPLETED);

        assertBag(dual.hostPlayer.get(), 85, 3, 1, 2, 0);
        assertBag(dual.guestPlayer.get(), 95, 2, 3, 0, 1);
    }

    @Test
    public void crashReloadAfterEscrowThenReconcileBothDeliverExact() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 2, 5, 0));
        dual.setOffer(false, matsOffer("iron", 2, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        final long id = dual.hostRt.getState().getTradeId();
        Assert.assertTrue(dual.hostRt.getTradeLog().hasEscrowed(id));
        Assert.assertTrue(dual.guestRt.getTradeLog().hasEscrowed(id));
        Assert.assertFalse(dual.hostRt.getTradeLog().hasDelivered(id));
        Assert.assertFalse(dual.guestRt.getTradeLog().hasDelivered(id));
        assertBag(dual.hostPlayer.get(), 100, 3, 0, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 0, 2, 0, 1);

        dual.crashReloadHost();
        dual.reconcileBoth();

        Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(id),
                "host must be DELIVERED after reconcile");
        Assert.assertTrue(dual.guestRt.getTradeLog().hasDelivered(id),
                "guest must be DELIVERED after reconcile");
        assertBag(dual.hostPlayer.get(), 100, 3, 2, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 2, 2, 0, 1);
    }

    @Test
    public void afterOneDeliverDisconnectCrashReloadReconcileExact() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirm(true);
        dual.confirm(false);

        dual.drainUntil(() -> dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId())
                ^ dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
        dual.freezeAtStep();
        Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId())
                ^ dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()),
                "must remain exactly-one-delivered after freeze");

        final boolean hostHadDelivered =
                dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId());
        if (hostHadDelivered) {
            dual.crashReloadGuest();
        } else {
            dual.crashReloadHost();
        }

        dual.reconcileBoth();

        Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId()));
        Assert.assertTrue(dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
        assertBag(dual.hostPlayer.get(), 100, 4, 1, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 1, 3, 0, 1);
    }

    @Test
    public void disconnectAtEachStepThenReconcileExact() throws Exception {
        final String[] steps = {
                "after-open", "after-offer", "after-one-confirm",
                "after-escrow", "after-one-deliver"
        };
        for (int i = 0; i < steps.length; i++) {
            final String step = steps[i];
            if (i > 0) {
                dual.close();
                dual = DualNet.start(tempChars);
            }
            dual.openTrade();
            if ("after-open".equals(step)) {
                dual.hostRt.onSessionPeerDisconnected();
                dual.drainAll();
                Assert.assertEquals(dual.hostRt.getState().getStatus(), CoopTradeState.Status.CANCELLED);
                assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
                assertBag(dual.guestPlayer.get(), 80, 0, 4, 0, 1);
                continue;
            }
            dual.setOffer(true, matsOffer("oak", 1, 5, 0));
            dual.setOffer(false, matsOffer("iron", 1, 4, 0));
            dual.drainAll();
            if ("after-offer".equals(step)) {
                dual.guestRt.onSessionPeerDisconnected();
                dual.drainAll();
                Assert.assertEquals(dual.guestRt.getState().getStatus(), CoopTradeState.Status.CANCELLED);
                assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
                assertBag(dual.guestPlayer.get(), 80, 0, 4, 0, 1);
                continue;
            }
            if ("after-one-confirm".equals(step)) {
                dual.confirm(true);
                dual.drainAll();
                dual.hostRt.onSessionPeerDisconnected();
                dual.drainAll();
                Assert.assertEquals(dual.hostRt.getState().getStatus(), CoopTradeState.Status.CANCELLED);
                assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
                assertBag(dual.guestPlayer.get(), 80, 0, 4, 0, 1);
                continue;
            }
            if ("after-escrow".equals(step)) {
                dual.confirmEscrowBothFreeze();
                dual.hostRt.onSessionPeerDisconnected();
                dual.guestRt.onSessionPeerDisconnected();
                dual.drainAll();
                Assert.assertEquals(dual.hostRt.getState().getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
                Assert.assertEquals(dual.guestRt.getState().getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
                Assert.assertNull(dual.hostRt.getState().cancel("abandon"));
                dual.crashReloadHost();
                dual.crashReloadGuest();
                dual.reconcileBoth();
                Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId()));
                Assert.assertTrue(dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
                assertBag(dual.hostPlayer.get(), 100, 4, 1, 2, 0);
                assertBag(dual.guestPlayer.get(), 80, 1, 3, 0, 1);
                continue;
            }
            // after-one-deliver
            dual.confirm(true);
            dual.confirm(false);
            dual.drainUntil(() -> dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId())
                    ^ dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
            dual.freezeAtStep();
            dual.hostRt.onSessionPeerDisconnected();
            dual.guestRt.onSessionPeerDisconnected();
            dual.drainAll();
            dual.crashReloadHost();
            dual.crashReloadGuest();
            dual.reconcileBoth();
            Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId()));
            Assert.assertTrue(dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
            assertBag(dual.hostPlayer.get(), 100, 4, 1, 2, 0);
            assertBag(dual.guestPlayer.get(), 80, 1, 3, 0, 1);
        }
    }

    @Test
    public void crashAndReloadAtEachStepExact() throws Exception {
        // after-open crash: bags unchanged from baseline world save (no pre-save).
        dual.crashReloadHost();
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);

        dual.close();
        dual = DualNet.start(tempChars);
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.drainAll();
        dual.crashReloadHost();
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);

        dual.close();
        dual = DualNet.start(tempChars);
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirm(true);
        dual.drainAll();
        dual.crashReloadHost();
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 0, 4, 0, 1);

        dual.close();
        dual = DualNet.start(tempChars);
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        dual.crashReloadHost();
        dual.crashReloadGuest();
        dual.reconcileBoth();
        Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId()));
        Assert.assertTrue(dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
        assertBag(dual.hostPlayer.get(), 100, 4, 1, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 1, 3, 0, 1);

        dual.close();
        dual = DualNet.start(tempChars);
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirm(true);
        dual.confirm(false);
        dual.drainUntil(() -> dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId())
                ^ dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
        dual.freezeAtStep();
        final boolean hostDone =
                dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId());
        if (hostDone) {
            dual.crashReloadGuest();
        } else {
            dual.crashReloadHost();
        }
        dual.reconcileBoth();
        Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId()));
        Assert.assertTrue(dual.guestRt.getTradeLog().hasDelivered(dual.guestRt.getState().getTradeId()));
        assertBag(dual.hostPlayer.get(), 100, 4, 1, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 1, 3, 0, 1);
    }

    @Test
    public void staleAndDuplicateIdRejectedBagsUnchanged() throws Exception {
        final long staleId = forge.gamemodes.net.coop.CoopTradeIds.next();
        dual.hostRt.getTradeLog().record(staleId, CoopTradeLog.Phase.COMPLETED, 1L);
        CoopTradeGlOps.syncLogAndSave(dual.hostPlayer.get(), dual.hostRt.getTradeLog());
        dual.crashReloadHost();
        Assert.assertTrue(dual.hostRt.getTradeLog().contains(staleId));
        Assert.assertFalse(dual.hostRt.getState().receiveInvite(
                new CoopTradeInviteEvent(staleId, "Guest", 30), true),
                "stale/duplicate id after reload must be rejected");
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 0, 4, 0, 1);

        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.drainAll();
        final int ver = dual.hostRt.getState().getHostOfferVersion();
        Assert.assertNull(dual.hostRt.getState().acceptOffer(new CoopTradeOfferEvent(
                dual.hostRt.getState().getTradeId(), CoopTradeRole.HOST,
                matsOffer("oak", 2, 5, 0), ver)));
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
    }

    @Test
    public void hostileCompletedEscrowedDeliveredUnknownIdBagsUnchanged() throws Exception {
        dual.openTrade();
        final long realId = dual.hostRt.getState().getTradeId();
        Assert.assertEquals(dual.hostRt.getState().applyReconcile(
                new CoopTradeReconcileEvent(99999L, CoopTradeRole.GUEST,
                        CoopTradeLog.Phase.COMPLETED, false), 1L),
                CoopTradeLog.ReconcileAction.IGNORE_HOSTILE);
        Assert.assertFalse(dual.hostRt.getState().receivePeerEscrowed(88888L, CoopTradeRole.GUEST));
        Assert.assertFalse(dual.hostRt.getState().receivePeerDelivered(88888L, CoopTradeRole.GUEST));
        Assert.assertFalse(dual.hostRt.getState().receivePeerDelivered(realId, CoopTradeRole.GUEST));
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 0, 4, 0, 1);
    }

    @Test
    public void cancelRefusedAfterBothConfirm() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 5));
        dual.setOffer(false, matsOffer("iron", 1, 4, 5));
        dual.drainAll();
        dual.confirm(true);
        dual.drainAll();
        Assert.assertTrue(dual.hostRt.getState().isCancelAllowed());
        dual.confirm(false);
        dual.drainAll();
        Assert.assertFalse(dual.hostRt.getState().isCancelAllowed());
        Assert.assertNull(dual.hostRt.getState().cancel("nope"));
        Assert.assertNull(dual.guestRt.getState().cancel("nope"));
    }

    @Test
    public void overflowOnDeliverExact() throws Exception {
        final ConfigData cfg = Config.instance().getConfigData();
        cfg.backpackSlots = 1;
        dual.hostPlayer.get().getBags().resetToDefaults(cfg);
        final AdventurePlayerTradeBag hostBag = new AdventurePlayerTradeBag(dual.hostPlayer.get());
        while (itemCount(dual.hostPlayer.get(), ITEM_A) > 0) {
            Assert.assertTrue(hostBag.takeItem(ITEM_A, 1));
        }
        while (itemCount(dual.hostPlayer.get(), ITEM_B) > 0) {
            Assert.assertTrue(hostBag.takeItem(ITEM_B, 1));
        }
        giveItem(dual.hostPlayer.get(), ITEM_A, 1);
        Assert.assertEquals(itemCount(dual.hostPlayer.get(), ITEM_A), 1);

        dual.openTrade();
        dual.setOffer(true, new CoopTradeOffer(1, null, null, null));
        dual.setOffer(false, itemOffer(ITEM_B, 1, 1));
        dual.drainAll();
        dual.confirm(true);
        dual.confirm(false);
        dual.drainAll();

        Assert.assertTrue(dual.hostRt.getTradeLog().hasDelivered(dual.hostRt.getState().getTradeId()));
        Assert.assertEquals(itemCount(dual.hostPlayer.get(), ITEM_A), 1);
        Assert.assertEquals(itemCount(dual.hostPlayer.get(), ITEM_B), 0);
        Assert.assertTrue(dual.hostPlayer.get().getBags().getOverflow().size() >= 1,
                "ITEM_B must land in Overflow");
        cfg.backpackSlots = 20;
    }

    @Test
    public void characterSaveRoundTripsTradeLogAtomically() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        final long id = dual.hostRt.getState().getTradeId();
        Assert.assertEquals(dual.hostRt.getTradeLog().get(id).phase, CoopTradeLog.Phase.ESCROWED);
        Assert.assertTrue(dual.hostWorldFile.isFile());
        Assert.assertFalse(new File(dual.hostWorldFile.getPath() + ".tmp").exists(),
                "tmp must be gone after atomic move");

        final AdventurePlayer loaded = new AdventurePlayer();
        Assert.assertTrue(loadHostWorld(loaded, dual.hostWorldFile));
        Assert.assertEquals(loaded.getGold(), 100);
        Assert.assertEquals(loaded.getMaterial("oak"), 4, "host escrowed oak×1");
        Assert.assertEquals(loaded.getMaterial("iron"), 0, "host must not hold guest iron yet");
        final CoopTradeLog reloaded = new CoopTradeLog();
        reloaded.decode(loaded.getTradeLogBlob());
        Assert.assertEquals(reloaded.get(id).phase, CoopTradeLog.Phase.ESCROWED);
        Assert.assertTrue(reloaded.hasEscrowed(id));
        Assert.assertFalse(reloaded.hasDelivered(id));
    }

    @Test
    public void deliverGuardRequiresPeerEscrowed() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.escrowHostOnly();
        Assert.assertTrue(dual.hostRt.getTradeLog().hasEscrowed(dual.hostRt.getState().getTradeId()));
        Assert.assertFalse(dual.hostRt.getState().isPeerEscrowed());
        Assert.assertFalse(CoopTradeGlOps.performDeliver(
                dual.hostRt.getState(), dual.hostRt.getTradeLog(),
                new AdventurePlayerTradeBag(dual.hostPlayer.get()), ev -> { }));
        Assert.assertEquals(dual.hostPlayer.get().getMaterial("iron"), 0);
    }

    @Test
    public void duplicateIdGuardOnReceiveInvite() throws Exception {
        final long id = forge.gamemodes.net.coop.CoopTradeIds.next();
        dual.guestRt.getTradeLog().record(id, CoopTradeLog.Phase.COMPLETED, 1L);
        Assert.assertEquals(dual.guestRt.getState().getStatus(), CoopTradeState.Status.IDLE);
        Assert.assertFalse(dual.guestRt.getState().receiveInvite(
                new CoopTradeInviteEvent(id, "Host", 30), false));
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
        assertBag(dual.guestPlayer.get(), 80, 0, 4, 0, 1);
    }

    @Test
    public void refundWhenPeerNeverEscrowedExact() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 2, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.escrowHostOnly();
        final long id = dual.hostRt.getState().getTradeId();
        Assert.assertTrue(dual.hostRt.getTradeLog().hasEscrowed(id));
        assertBag(dual.hostPlayer.get(), 100, 3, 0, 2, 0);

        dual.hostRt.onSessionPeerDisconnected();
        dual.drainAll();
        dual.crashReloadHost();
        Assert.assertEquals(dual.hostRt.getState().getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);

        dual.hostLinked = true;
        dual.guestLinked = true;
        synchronized (dual.gl) {
            dual.gl.add(() -> {
                final CoopTradeLog.ReconcileAction action = dual.hostRt.getState().applyReconcile(
                        new CoopTradeReconcileEvent(id, CoopTradeRole.GUEST,
                                CoopTradeLog.Phase.NONE, false),
                        System.currentTimeMillis(), dual.hostPeerId.get());
                Assert.assertEquals(action, CoopTradeLog.ReconcileAction.REFUND);
                Assert.assertTrue(CoopTradeGlOps.performRefund(
                        dual.hostRt.getState(), dual.hostRt.getTradeLog(),
                        new AdventurePlayerTradeBag(dual.hostPlayer.get())));
            });
        }
        dual.drainAll();
        assertBag(dual.hostPlayer.get(), 100, 5, 0, 2, 0);
        Assert.assertEquals(dual.hostRt.getTradeLog().get(id).phase, CoopTradeLog.Phase.REFUNDED);
    }

    @Test
    public void wrongPeerReconcileDoesNotRefund() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 2, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.escrowHostOnly();
        final long id = dual.hostRt.getState().getTradeId();
        assertBag(dual.hostPlayer.get(), 100, 3, 0, 2, 0);

        Assert.assertEquals(dual.hostRt.getState().applyReconcile(
                new CoopTradeReconcileEvent(id, CoopTradeRole.GUEST,
                        CoopTradeLog.Phase.NONE, false),
                System.currentTimeMillis(), "OtherGuest"),
                CoopTradeLog.ReconcileAction.IGNORE_HOSTILE);
        assertBag(dual.hostPlayer.get(), 100, 3, 0, 2, 0);
        Assert.assertEquals(dual.hostRt.getTradeLog().get(id).phase, CoopTradeLog.Phase.ESCROWED);
    }

    @Test
    public void hostRestartRestoresRoleFromLog() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        final long id = dual.hostRt.getState().getTradeId();
        Assert.assertEquals(dual.hostRt.getState().getLocalRole(), CoopTradeRole.HOST);
        Assert.assertEquals(dual.hostRt.getTradeLog().get(id).localRole, CoopTradeRole.HOST);

        dual.crashReloadHost();
        // C1: role comes from the durable log entry, not reset() default GUEST.
        Assert.assertEquals(dual.hostRt.getState().getLocalRole(), CoopTradeRole.HOST);
        Assert.assertEquals(dual.hostRt.getState().getTradeId(), id);
        Assert.assertEquals(dual.hostRt.getState().getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
        Assert.assertEquals(dual.hostRt.getTradeLog().get(id).localRole, CoopTradeRole.HOST);
    }

    /**
     * Guest crash/rejoin loads the co-op {@code .chr} (trade log + bags).
     * CO1 C2 (restoreGuestSave wipe) is not merged — this test assumes {@code .chr}
     * persists via {@link CoopCharacterStore} without that wipe; full rejoin waits
     * for the CO1 fix PR.
     */
    @Test
    public void guestRejoinLoadsChr() throws Exception {
        dual.openTrade();
        dual.setOffer(true, matsOffer("oak", 1, 5, 0));
        dual.setOffer(false, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        final long id = dual.guestRt.getState().getTradeId();
        Assert.assertTrue(dual.guestRt.getTradeLog().hasEscrowed(id));
        assertBag(dual.guestPlayer.get(), 80, 0, 3, 0, 1);

        dual.crashReloadGuest();
        Assert.assertEquals(dual.guestRt.getState().getLocalRole(), CoopTradeRole.GUEST);
        Assert.assertEquals(dual.guestRt.getState().getTradeId(), id);
        Assert.assertEquals(dual.guestRt.getState().getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
        assertBag(dual.guestPlayer.get(), 80, 0, 3, 0, 1);
        Assert.assertEquals(dual.guestRt.getTradeLog().get(id).phase, CoopTradeLog.Phase.ESCROWED);
    }

    /** Minimal GuiBase so ForgeConstants can resolve ASSETS_DIR in headless tests. */
    private static final class HeadlessAssetsGui implements IGuiBase {
        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return false; }
        @Override public String getCurrentVersion() { return "test"; }
        @Override public void invokeInEdtNow(final Runnable r) { if (r != null) r.run(); }
        @Override public void invokeInEdtLater(final Runnable r) { if (r != null) r.run(); }
        @Override public void invokeInEdtAndWait(final Runnable r) { if (r != null) r.run(); }
        @Override public void runBackgroundTask(final String message, final Runnable task) {
            if (task != null) {
                task.run();
            }
        }
        @Override public boolean isGuiThread() { return true; }
        @Override public String getAssetsDir() {
            if (Files.isDirectory(java.nio.file.Paths.get("./forge-gui/res"))) {
                return "./forge-gui/";
            }
            if (Files.isDirectory(java.nio.file.Paths.get("../forge-gui/res"))) {
                return "../forge-gui/";
            }
            return "./";
        }
        @Override public ImageFetcher getImageFetcher() { return null; }
        @Override public ISkinImage getSkinIcon(final FSkinProp skinProp) { return null; }
        @Override public ISkinImage getUnskinnedIcon(final String path) { return null; }
        @Override public ISkinImage getCardArt(final PaperCard card, final boolean backFace) { return null; }
        @Override public ISkinImage createLayeredImage(final PaperCard card, final FSkinProp background,
                                                         final String overlayFilename, final float opacity) {
            return null;
        }
        @Override public void clearImageCache() { }
        @Override public String encodeSymbols(final String str, final boolean formatReminderText) {
            return str;
        }
        @Override public int getAvatarCount() { return 0; }
        @Override public int getSleevesCount() { return 0; }
        @Override public float getScreenScale() { return 1f; }
        @Override public void preventSystemSleep(final boolean preventSleep) { }
        @Override public void download(final GuiDownloadService service, final Consumer<Boolean> callback) { }
        @Override public void copyToClipboard(final String text) { }
        @Override public void browseToUrl(final String url) { }
        @Override public void showCardList(final String title, final String message, final List<PaperCard> list) { }
        @Override public boolean showBoxedProduct(final String title, final String message,
                                                    final List<PaperCard> list) {
            return false;
        }
        @Override public void showBugReportDialog(final String title, final String text,
                                                    final boolean showExitAppBtn) { }
        @Override public void showImageDialog(final ISkinImage image, final String message, final String title) { }
        @Override public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                                final List<String> options, final int defaultOption) {
            return -1;
        }
        @Override public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                                  final String initialInput, final List<String> inputOptions,
                                                  final boolean isNumeric) {
            return initialInput;
        }
        @Override public String showFileDialog(final String title, final String defaultDir) { return null; }
        @Override public File getSaveFile(final File defaultFile) { return defaultFile; }
        @Override public <T> List<T> order(final String title, final String top, final int remainingObjectsMin,
                                             final int remainingObjectsMax, final List<T> sourceChoices,
                                             final List<T> destChoices) {
            return destChoices;
        }
        @Override public <T> List<T> getChoices(final String message, final int min, final int max,
                                                  final Collection<T> choices, final Collection<T> selected,
                                                  final FSerializableFunction<T, String> display) {
            return Collections.emptyList();
        }
        @Override public PaperCard chooseCard(final String title, final String message,
                                                final List<PaperCard> list) {
            return null;
        }
        @Override public boolean isSupportedAudioFormat(final File file) { return false; }
        @Override public IAudioClip createAudioClip(final String filename) { return null; }
        @Override public IAudioMusic createAudioMusic(final String filename) { return null; }
        @Override public void startAltSoundSystem(final String filename, final boolean isSynchronized) { }
        @Override public void showSpellShop() { }
        @Override public void showBazaar() { }
        @Override public IGuiGame getNewGuiGame() { return null; }
        @Override public HostedMatch hostMatch() { return null; }
        @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
        @Override public boolean hasNetGame() { return false; }
    }
}
