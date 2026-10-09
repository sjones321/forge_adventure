package forge.adventure.coop;

import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.ItemListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.AdventureModes;
import forge.adventure.util.Config;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.coop.CoopMessageListener;
import forge.gamemodes.net.coop.CoopOverworldClient;
import forge.gamemodes.net.coop.CoopOverworldServer;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopRateLimiter;
import forge.gamemodes.net.coop.CoopSessionCode;
import forge.gamemodes.net.coop.CoopTradeApply;
import forge.gamemodes.net.coop.CoopTradeBag;
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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * TR1 escrow E2E: real {@link AdventurePlayer} bags via
 * {@link AdventurePlayerTradeBag}, real overworld Netty
 * ({@link CoopOverworldServer}/{@link CoopOverworldClient} — the production
 * trade transport), and a controllable GL queue standing in for
 * {@code Gdx.app.postRunnable}. Crash/reload uses {@link CoopCharacterStore}
 * atomic temp+move with the trade log inside the character save.
 */
public class CoopTradeEscrowE2ETest {

    private static final String ITEM_A = "Chandra's Stone";
    private static final String ITEM_B = "Liliana's Stone";

    private File tempChars;
    private DualNet dual;

    @BeforeClass
    public void installHeadlessGui() {
        // ForgeConstants / SoundSystem need GuiBase before any class init.
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new HeadlessAssetsGui());
        }
        SoundSystem.instance.setIgnorePlayRequests(true);
        // AdventurePlayer.clearDecks() needs Localizer strings.
        final String langDir = GuiBase.getInterface().getAssetsDir() + "res/languages";
        forge.util.Localizer.getInstance().initialize("en-US", langDir);
        // Avoid StaticData for hello handshake.
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("tr1-test-cards"));
    }

    @BeforeMethod
    public void setUp() throws Exception {
        SoundSystem.instance.setIgnorePlayRequests(true);
        // Force Ascendant rules for Overflow / trade eligibility.
        final ConfigData cfg = Config.instance().getConfigData();
        cfg.ascendantRules = true;
        Assert.assertTrue(Config.ascendant());
        // Touch ItemListData so items.json is loaded from common/.
        Assert.assertNotNull(ItemListData.getItem(ITEM_A), "items.json must resolve " + ITEM_A);
        Assert.assertNotNull(ItemListData.getItem(ITEM_B), "items.json must resolve " + ITEM_B);

        tempChars = Files.createTempDirectory("tr1-chars").toFile();
        CoopCharacterStore.setCharactersDirOverride(tempChars);
        dual = DualNet.start();
    }

    @AfterMethod
    public void tearDown() {
        if (dual != null) {
            dual.close();
            dual = null;
        }
        CoopCharacterStore.setCharactersDirOverride(null);
        CoopTradeRuntime.setGlPoster(null);
        if (tempChars != null) {
            deleteTree(tempChars);
        }
    }

    // ---- fixtures ----------------------------------------------------------

    private static AdventurePlayer player(final String name, final int gold) throws Exception {
        final AdventurePlayer p = new AdventurePlayer();
        setField(p, "name", name);
        // AdventurePlayer.save() requires a non-null mode and difficulty name.
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
        // Ascendant bags from current config.
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

    /**
     * Dual peer over real overworld Netty. Each side has its own GL queue;
     * Netty handlers only enqueue — mutations run when the queue is drained.
     */
    private static final class DualNet {
        final Side host;
        final Side guest;
        final CoopOverworldServer server;
        final CoopOverworldClient client;
        final String sessionCode;
        final int port;
        volatile boolean hostLinked = true;
        volatile boolean guestLinked = true;
        /**
         * When true, escrow still removes goods and sends escrowed(id), but does not
         * auto-deliver — lets tests freeze after both ESCROWED before either DELIVERED.
         */
        volatile boolean suppressAutoDeliver = false;

        private DualNet(final Side host, final Side guest, final CoopOverworldServer server,
                        final CoopOverworldClient client, final String sessionCode, final int port) {
            this.host = host;
            this.guest = guest;
            this.server = server;
            this.client = client;
            this.sessionCode = sessionCode;
            this.port = port;
        }

        static DualNet start() throws Exception {
            Exception last = null;
            for (int attempt = 0; attempt < 4; attempt++) {
                DualNet dual = null;
                try {
                    dual = startOnce();
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

        private static DualNet startOnce() throws Exception {
            final int port;
            try (ServerSocket ss = new ServerSocket(0)) {
                port = ss.getLocalPort();
            }
            final String code = CoopSessionCode.generate();
            final Side host = new Side(true, player("HostHero", 100));
            final Side guest = new Side(false, player("GuestHero", 80));
            giveMaterial(host.player, "oak", 5);
            giveMaterial(guest.player, "iron", 4);
            giveItem(host.player, ITEM_A, 2);
            giveItem(guest.player, ITEM_B, 1);

            final AtomicReference<DualNet> self = new AtomicReference<>();
            final AtomicReference<CoopOverworldServer> serverRef = new AtomicReference<>();
            final AtomicReference<CoopOverworldClient> clientRef = new AtomicReference<>();
            final CountDownLatch ready = new CountDownLatch(2);
            final AtomicReference<String> netError = new AtomicReference<>();

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
                    // Netty → GL only.
                    d.host.gl.add(() -> d.host.handle(event, d));
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
                            d.guest.gl.add(() -> d.guest.handle(event, d));
                        }
                    });
            clientRef.set(client);
            client.connect();
            Assert.assertTrue(client.awaitConnected(5000));
            Assert.assertTrue(ready.await(5, TimeUnit.SECONDS),
                    "session ready" + (netError.get() != null ? " (" + netError.get() + ")" : ""));

            final DualNet dual = new DualNet(host, guest, server, client, code, port);
            self.set(dual);
            // Both sides can validate the peer bag at confirm (receiver gold overflow).
            host.state.setBagLookup(r -> r == CoopTradeRole.HOST ? host.bag : guest.bag);
            guest.state.setBagLookup(r -> r == CoopTradeRole.HOST ? host.bag : guest.bag);
            host.send = ev -> {
                if (dual.guestLinked) {
                    server.send(ev);
                }
            };
            guest.send = ev -> {
                if (dual.hostLinked) {
                    client.send(ev);
                }
            };
            // Bind trade-log save into character on phase advance.
            host.log.setListener(e -> CoopTradeGlOps.syncLogAndSave(host.player, host.log));
            guest.log.setListener(e -> CoopTradeGlOps.syncLogAndSave(guest.player, guest.log));
            return dual;
        }

        void drainAll() throws InterruptedException {
            // Interleave GL with brief Netty flush waits.
            for (int i = 0; i < 200; i++) {
                boolean progress = false;
                if (!host.gl.isEmpty()) {
                    host.gl.poll().run();
                    progress = true;
                }
                if (!guest.gl.isEmpty()) {
                    guest.gl.poll().run();
                    progress = true;
                }
                if (!progress) {
                    Thread.sleep(5);
                    if (host.gl.isEmpty() && guest.gl.isEmpty()) {
                        // one more quiet poll for late Netty deliveries
                        Thread.sleep(15);
                        if (host.gl.isEmpty() && guest.gl.isEmpty()) {
                            return;
                        }
                    }
                }
            }
            Assert.fail("GL queues did not drain: host=" + host.gl.size()
                    + " guest=" + guest.gl.size());
        }

        void drainUntil(final java.util.function.BooleanSupplier done) throws InterruptedException {
            for (int i = 0; i < 200; i++) {
                if (done.getAsBoolean()) {
                    return;
                }
                if (!host.gl.isEmpty()) {
                    host.gl.poll().run();
                } else if (!guest.gl.isEmpty()) {
                    guest.gl.poll().run();
                } else {
                    Thread.sleep(5);
                }
            }
            Assert.fail("condition not reached");
        }

        void openTrade() throws InterruptedException {
            guest.gl.add(() -> {
                final CoopTradeRequestEvent req = guest.state.beginRequest("GuestHero");
                Assert.assertNotNull(req);
                guest.send.accept(req);
            });
            drainAll();
            Assert.assertEquals(host.state.getStatus(), CoopTradeState.Status.INVITE_SENT);
            Assert.assertTrue(host.state.getTradeId() != 0L);
            Assert.assertEquals(guest.state.getStatus(), CoopTradeState.Status.INVITE_RECEIVED);
            guest.gl.add(() -> {
                final CoopTradeResponseEvent resp = guest.state.respondInvite(true);
                Assert.assertNotNull(resp);
                guest.send.accept(resp);
            });
            drainAll();
            Assert.assertEquals(host.state.getStatus(), CoopTradeState.Status.OPEN);
            Assert.assertEquals(guest.state.getStatus(), CoopTradeState.Status.OPEN);
            Assert.assertEquals(guest.state.getTradeId(), host.state.getTradeId());
        }

        void setOffer(final Side side, final CoopTradeOffer offer) {
            side.gl.add(() -> {
                final int next = side.state.getLocalOfferVersion() + 1;
                final CoopTradeOfferEvent ev = new CoopTradeOfferEvent(
                        side.state.getTradeId(), side.role, offer, next);
                Assert.assertNotNull(side.state.acceptOffer(ev), "offer rejected");
                side.send.accept(ev);
            });
        }

        void confirm(final Side side) {
            side.gl.add(() -> {
                final CoopTradeConfirmEvent ev = new CoopTradeConfirmEvent(
                        side.state.getTradeId(), side.role, true,
                        side.state.getLocalOfferVersion(), side.state.getPeerOfferVersion());
                final CoopTradeState.ConfirmResult result = side.state.acceptConfirm(ev);
                Assert.assertNotEquals(result, CoopTradeState.ConfirmResult.IGNORED);
                side.send.accept(ev);
                if (result == CoopTradeState.ConfirmResult.BEGIN_ESCROW) {
                    Assert.assertTrue(escrowNow(side));
                }
            });
        }

        /** Escrow; optionally skip the auto-deliver that production runs when peer already escrowed. */
        boolean escrowNow(final Side side) {
            if (!suppressAutoDeliver) {
                return CoopTradeGlOps.performEscrow(
                        side.state, side.log, side.bag, side.send::accept);
            }
            final long id = side.state.getTradeId();
            if (id == 0L) {
                return false;
            }
            if (!side.log.hasEscrowed(id)) {
                final CoopTradeBag.Snapshot snap = side.bag.snapshot();
                final CoopTradeApply.Result result = CoopTradeApply.escrowIdempotent(
                        id, side.log, side.bag, side.state.getLocalOffer(), snap);
                if (!result.applied) {
                    return false;
                }
            }
            final CoopTradeEscrowedEvent escrowed = side.state.markEscrowed(System.currentTimeMillis());
            if (escrowed != null) {
                side.send.accept(escrowed);
            }
            return true;
        }

        /** Drive both confirms with auto-deliver suppressed until both are ESCROWED. */
        void confirmEscrowBothFreeze() throws InterruptedException {
            suppressAutoDeliver = true;
            confirm(host);
            confirm(guest);
            drainUntil(() -> host.log.hasEscrowed(host.state.getTradeId())
                    && guest.log.hasEscrowed(guest.state.getTradeId())
                    && !host.log.hasDelivered(host.state.getTradeId())
                    && !guest.log.hasDelivered(guest.state.getTradeId()));
            freezeAtStep();
            suppressAutoDeliver = false;
        }

        /** Drop pending Netty→GL work without running it (freeze after a step). */
        void discardPendingGl() {
            host.gl.clear();
            guest.gl.clear();
        }

        /** Unlink peers and discard unprocessed GL so the frozen step stays exact. */
        void freezeAtStep() {
            hostLinked = false;
            guestLinked = false;
            discardPendingGl();
        }

        /** Crash one side: atomic character save already done; discard memory; reload. */
        void crashReload(final Side side) throws Exception {
            CoopTradeGlOps.syncLogAndSave(side.player, side.log);
            final String name = side.player.getName();
            final File chr = CoopCharacterStore.characterFile(name);
            Assert.assertTrue(chr.isFile(), "character file missing for " + name
                    + " at " + chr.getAbsolutePath());
            final long id = side.state.getTradeId();
            final CoopTradeRole role = side.role;
            final AdventurePlayer fresh = new AdventurePlayer();
            Assert.assertTrue(CoopCharacterStore.loadPlayer(fresh, name),
                    "loadPlayer failed for " + name);
            side.player = fresh;
            side.bag = new AdventurePlayerTradeBag(fresh);
            side.log.clear();
            CoopTradeGlOps.loadLogFromPlayer(fresh, side.log);
            side.state.reset();
            // Refresh both sides' bag lookups after reload.
            host.state.setBagLookup(r -> r == CoopTradeRole.HOST ? host.bag : guest.bag);
            guest.state.setBagLookup(r -> r == CoopTradeRole.HOST ? host.bag : guest.bag);
            side.state.restoreFromLog(id, role);
            side.log.setListener(e -> CoopTradeGlOps.syncLogAndSave(side.player, side.log));
        }

        void reconcileBoth() throws InterruptedException {
            host.gl.add(() -> {
                for (final CoopTradeReconcileEvent ev : host.state.buildReconcileRequests(true)) {
                    host.send.accept(ev);
                }
            });
            guest.gl.add(() -> {
                for (final CoopTradeReconcileEvent ev : guest.state.buildReconcileRequests(true)) {
                    guest.send.accept(ev);
                }
            });
            drainAll();
        }

        void close() {
            try { client.disconnect(); } catch (final Exception ignored) { }
            try { server.stop(); } catch (final Exception ignored) { }
            try { Thread.sleep(50); } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Side {
        final boolean weAreHost;
        final CoopTradeRole role;
        final CoopTradeLog log = new CoopTradeLog();
        final CoopTradeState state = new CoopTradeState(new CoopRateLimiter(100, 1), log);
        final Queue<Runnable> gl = new ArrayDeque<>();
        AdventurePlayer player;
        AdventurePlayerTradeBag bag;
        ConsumerSend send = ev -> { };

        Side(final boolean weAreHost, final AdventurePlayer player) {
            this.weAreHost = weAreHost;
            this.role = weAreHost ? CoopTradeRole.HOST : CoopTradeRole.GUEST;
            this.player = player;
            this.bag = new AdventurePlayerTradeBag(player);
            state.setBagLookup(r -> bag);
        }

        @FunctionalInterface
        interface ConsumerSend {
            void accept(NetEvent ev);
        }

        void handle(final NetEvent event, final DualNet dual) {
            if (event instanceof CoopTradeRequestEvent) {
                if (!weAreHost) {
                    return;
                }
                final CoopTradeInviteEvent invite = state.acceptRequest(
                        (CoopTradeRequestEvent) event, player.getName(), 30,
                        System.currentTimeMillis());
                Assert.assertNotNull(invite);
                send.accept(invite);
            } else if (event instanceof CoopTradeInviteEvent) {
                Assert.assertTrue(state.receiveInvite((CoopTradeInviteEvent) event, weAreHost));
            } else if (event instanceof CoopTradeResponseEvent) {
                Assert.assertTrue(state.applyPeerResponse(
                        (CoopTradeResponseEvent) event, weAreHost));
            } else if (event instanceof CoopTradeOfferEvent) {
                final CoopTradeOfferEvent ev = (CoopTradeOfferEvent) event;
                if (ev.getFromRole() == role) {
                    return;
                }
                Assert.assertNotNull(state.acceptOffer(ev));
                if (weAreHost) {
                    send.accept(ev);
                }
            } else if (event instanceof CoopTradeConfirmEvent) {
                final CoopTradeConfirmEvent ev = (CoopTradeConfirmEvent) event;
                if (ev.getFromRole() == role) {
                    return;
                }
                final CoopTradeState.ConfirmResult result = state.acceptConfirm(ev);
                if (weAreHost && result != CoopTradeState.ConfirmResult.IGNORED) {
                    send.accept(ev);
                }
                if (result == CoopTradeState.ConfirmResult.BEGIN_ESCROW) {
                    Assert.assertTrue(dual.escrowNow(this));
                }
            } else if (event instanceof CoopTradeEscrowedEvent) {
                final CoopTradeEscrowedEvent ev = (CoopTradeEscrowedEvent) event;
                state.receivePeerEscrowed(ev.getTradeId(), ev.getFromRole());
                if (!dual.suppressAutoDeliver && state.shouldDeliver()) {
                    Assert.assertTrue(CoopTradeGlOps.performDeliver(state, log, bag, send::accept));
                }
            } else if (event instanceof CoopTradeDeliveredEvent) {
                final CoopTradeDeliveredEvent ev = (CoopTradeDeliveredEvent) event;
                state.receivePeerDelivered(ev.getTradeId(), ev.getFromRole());
            } else if (event instanceof CoopTradeCancelEvent) {
                state.receiveCancel((CoopTradeCancelEvent) event);
            } else if (event instanceof CoopTradeReconcileEvent) {
                final CoopTradeReconcileEvent ev = (CoopTradeReconcileEvent) event;
                if (ev.isRequest()) {
                    final CoopTradeLog.Entry local = log.get(ev.getTradeId());
                    final CoopTradeLog.Phase phase = local != null
                            ? local.phase : CoopTradeLog.Phase.NONE;
                    send.accept(new CoopTradeReconcileEvent(ev.getTradeId(), role, phase, false));
                }
                final CoopTradeLog.ReconcileAction action =
                        state.applyReconcile(ev, System.currentTimeMillis());
                if (action == CoopTradeLog.ReconcileAction.DELIVER) {
                    Assert.assertTrue(CoopTradeGlOps.performDeliver(state, log, bag, send::accept));
                } else if (action == CoopTradeLog.ReconcileAction.REFUND) {
                    Assert.assertTrue(CoopTradeGlOps.performRefund(state, log, bag));
                } else if (action == CoopTradeLog.ReconcileAction.RESEND_ESCROWED) {
                    send.accept(new CoopTradeEscrowedEvent(ev.getTradeId(), role));
                } else if (action == CoopTradeLog.ReconcileAction.RESEND_DELIVERED) {
                    send.accept(new CoopTradeDeliveredEvent(ev.getTradeId(), role));
                }
            }
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
        // Host: 100g, oak×5, ITEM_A×2. Guest: 80g, iron×4, ITEM_B×1.
        // Trade: host gives oak×2 + 25g; guest gives iron×1 + 10g.
        dual.setOffer(dual.host, matsOffer("oak", 2, 5, 25));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 10));
        dual.drainAll();
        dual.confirm(dual.host);
        dual.confirm(dual.guest);
        dual.drainAll();

        Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
        Assert.assertTrue(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
        Assert.assertEquals(dual.host.state.getStatus(), CoopTradeState.Status.COMPLETED);
        Assert.assertEquals(dual.guest.state.getStatus(), CoopTradeState.Status.COMPLETED);

        // Exact bags: host has iron×1 (was 0), oak×3, gold 85; guest has oak×2, iron×3, gold 95.
        assertBag(dual.host.player, 85, 3, 1, 2, 0);
        assertBag(dual.guest.player, 95, 2, 3, 0, 1);
    }

    @Test
    public void crashReloadAfterEscrowThenReconcileBothDeliverExact() throws Exception {
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 2, 5, 0));
        dual.setOffer(dual.guest, matsOffer("iron", 2, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        Assert.assertTrue(dual.host.log.hasEscrowed(dual.host.state.getTradeId()));
        Assert.assertTrue(dual.guest.log.hasEscrowed(dual.guest.state.getTradeId()));
        Assert.assertFalse(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
        Assert.assertFalse(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
        // Escrow removed own goods only: host oak×3 (no iron), guest iron×2 (no oak).
        assertBag(dual.host.player, 100, 3, 0, 2, 0);
        assertBag(dual.guest.player, 80, 0, 2, 0, 1);

        dual.crashReload(dual.host);
        dual.hostLinked = true;
        dual.guestLinked = true;
        dual.reconcileBoth();

        Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()),
                "host must be DELIVERED after reconcile");
        Assert.assertTrue(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()),
                "guest must be DELIVERED after reconcile");
        // Host offered oak×2, guest iron×2 → host: oak×3 iron×2; guest: oak×2 iron×2.
        assertBag(dual.host.player, 100, 3, 2, 2, 0);
        assertBag(dual.guest.player, 80, 2, 2, 0, 1);
    }

    @Test
    public void afterOneDeliverDisconnectCrashReloadReconcileExact() throws Exception {
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirm(dual.host);
        dual.confirm(dual.guest);

        // Drain until exactly one side has DELIVERED, then freeze (do not drain the other).
        dual.drainUntil(() -> dual.host.log.hasDelivered(dual.host.state.getTradeId())
                ^ dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
        dual.freezeAtStep();
        Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId())
                ^ dual.guest.log.hasDelivered(dual.guest.state.getTradeId()),
                "must remain exactly-one-delivered after freeze");

        final boolean hostHadDelivered = dual.host.log.hasDelivered(dual.host.state.getTradeId());
        final Side toCrash = hostHadDelivered ? dual.guest : dual.host;
        dual.crashReload(toCrash);

        dual.hostLinked = true;
        dual.guestLinked = true;
        dual.reconcileBoth();

        Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
        Assert.assertTrue(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
        assertBag(dual.host.player, 100, 4, 1, 2, 0);
        assertBag(dual.guest.player, 80, 1, 3, 0, 1);
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
                dual = DualNet.start();
            }
            dual.openTrade();
            if ("after-open".equals(step)) {
                dual.host.state.onDisconnect();
                Assert.assertEquals(dual.host.state.getStatus(), CoopTradeState.Status.CANCELLED);
                assertBag(dual.host.player, 100, 5, 0, 2, 0);
                assertBag(dual.guest.player, 80, 0, 4, 0, 1);
                continue;
            }
            dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
            dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 0));
            dual.drainAll();
            if ("after-offer".equals(step)) {
                dual.guest.state.onDisconnect();
                Assert.assertEquals(dual.guest.state.getStatus(), CoopTradeState.Status.CANCELLED);
                assertBag(dual.host.player, 100, 5, 0, 2, 0);
                assertBag(dual.guest.player, 80, 0, 4, 0, 1);
                continue;
            }
            if ("after-one-confirm".equals(step)) {
                dual.confirm(dual.host);
                dual.drainAll();
                dual.host.state.onDisconnect();
                Assert.assertEquals(dual.host.state.getStatus(), CoopTradeState.Status.CANCELLED);
                assertBag(dual.host.player, 100, 5, 0, 2, 0);
                assertBag(dual.guest.player, 80, 0, 4, 0, 1);
                continue;
            }
            if ("after-escrow".equals(step)) {
                dual.confirmEscrowBothFreeze();
                dual.host.state.onDisconnect();
                dual.guest.state.onDisconnect();
                Assert.assertEquals(dual.host.state.getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
                Assert.assertEquals(dual.guest.state.getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
                Assert.assertNull(dual.host.state.cancel("abandon"));
                dual.crashReload(dual.host);
                dual.crashReload(dual.guest);
                dual.hostLinked = true;
                dual.guestLinked = true;
                dual.reconcileBoth();
                Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
                Assert.assertTrue(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
                assertBag(dual.host.player, 100, 4, 1, 2, 0);
                assertBag(dual.guest.player, 80, 1, 3, 0, 1);
                continue;
            }
            // after-one-deliver
            dual.confirm(dual.host);
            dual.confirm(dual.guest);
            dual.drainUntil(() -> dual.host.log.hasDelivered(dual.host.state.getTradeId())
                    ^ dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
            dual.freezeAtStep();
            dual.host.state.onDisconnect();
            dual.guest.state.onDisconnect();
            dual.crashReload(dual.host);
            dual.crashReload(dual.guest);
            dual.hostLinked = true;
            dual.guestLinked = true;
            dual.reconcileBoth();
            Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
            Assert.assertTrue(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
            assertBag(dual.host.player, 100, 4, 1, 2, 0);
            assertBag(dual.guest.player, 80, 1, 3, 0, 1);
        }
    }

    @Test
    public void crashAndReloadAtEachStepExact() throws Exception {
        // after-open crash: bags unchanged, empty trade log.
        CoopTradeGlOps.syncLogAndSave(dual.host.player, dual.host.log);
        dual.crashReload(dual.host);
        assertBag(dual.host.player, 100, 5, 0, 2, 0);

        dual.close();
        dual = DualNet.start();
        // after-offer crash: no bag change.
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.drainAll();
        CoopTradeGlOps.syncLogAndSave(dual.host.player, dual.host.log);
        dual.crashReload(dual.host);
        assertBag(dual.host.player, 100, 5, 0, 2, 0);

        dual.close();
        dual = DualNet.start();
        // after-one-confirm crash: still no escrow, bags unchanged.
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirm(dual.host);
        dual.drainAll();
        CoopTradeGlOps.syncLogAndSave(dual.host.player, dual.host.log);
        dual.crashReload(dual.host);
        assertBag(dual.host.player, 100, 5, 0, 2, 0);
        assertBag(dual.guest.player, 80, 0, 4, 0, 1);

        dual.close();
        dual = DualNet.start();
        // after-escrow crash+reload both, reconcile → exact swap.
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        dual.crashReload(dual.host);
        dual.crashReload(dual.guest);
        dual.hostLinked = true;
        dual.guestLinked = true;
        dual.reconcileBoth();
        Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
        Assert.assertTrue(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
        assertBag(dual.host.player, 100, 4, 1, 2, 0);
        assertBag(dual.guest.player, 80, 1, 3, 0, 1);

        dual.close();
        dual = DualNet.start();
        // after-one-deliver crash of the non-delivered side → reconcile completes.
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirm(dual.host);
        dual.confirm(dual.guest);
        dual.drainUntil(() -> dual.host.log.hasDelivered(dual.host.state.getTradeId())
                ^ dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
        dual.freezeAtStep();
        final Side lagging = dual.host.log.hasDelivered(dual.host.state.getTradeId())
                ? dual.guest : dual.host;
        dual.crashReload(lagging);
        dual.hostLinked = true;
        dual.guestLinked = true;
        dual.reconcileBoth();
        Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
        Assert.assertTrue(dual.guest.log.hasDelivered(dual.guest.state.getTradeId()));
        assertBag(dual.host.player, 100, 4, 1, 2, 0);
        assertBag(dual.guest.player, 80, 1, 3, 0, 1);
    }

    @Test
    public void staleAndDuplicateIdRejectedBagsUnchanged() throws Exception {
        dual.openTrade();
        final long id = dual.host.state.getTradeId();
        dual.host.log.record(id, CoopTradeLog.Phase.COMPLETED, 1L);
        // Duplicate id invite rejected.
        Assert.assertFalse(dual.guest.state.receiveInvite(
                new CoopTradeInviteEvent(id, "Host", 30), false));
        assertBag(dual.host.player, 100, 5, 0, 2, 0);
        assertBag(dual.guest.player, 80, 0, 4, 0, 1);

        // Stale offer version rejected.
        dual.host.state.reset();
        dual.guest.state.reset();
        dual.host.log.clear();
        dual.guest.log.clear();
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.drainAll();
        final int ver = dual.host.state.getHostOfferVersion();
        Assert.assertNull(dual.host.state.acceptOffer(new CoopTradeOfferEvent(
                dual.host.state.getTradeId(), CoopTradeRole.HOST,
                matsOffer("oak", 2, 5, 0), ver)));
        assertBag(dual.host.player, 100, 5, 0, 2, 0);
    }

    @Test
    public void hostileCompletedEscrowedDeliveredUnknownIdBagsUnchanged() throws Exception {
        dual.openTrade();
        final long realId = dual.host.state.getTradeId();
        // Unknown id claiming COMPLETED.
        Assert.assertEquals(dual.host.state.applyReconcile(
                new CoopTradeReconcileEvent(99999L, CoopTradeRole.GUEST,
                        CoopTradeLog.Phase.COMPLETED, false), 1L),
                CoopTradeLog.ReconcileAction.IGNORE_HOSTILE);
        Assert.assertFalse(dual.host.state.receivePeerEscrowed(88888L, CoopTradeRole.GUEST));
        Assert.assertFalse(dual.host.state.receivePeerDelivered(88888L, CoopTradeRole.GUEST));
        // Peer claims delivered for our open trade before we escrowed.
        Assert.assertFalse(dual.host.state.receivePeerDelivered(realId, CoopTradeRole.GUEST));
        assertBag(dual.host.player, 100, 5, 0, 2, 0);
        assertBag(dual.guest.player, 80, 0, 4, 0, 1);
    }

    @Test
    public void cancelRefusedAfterBothConfirm() throws Exception {
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 5));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 5));
        dual.drainAll();
        dual.confirm(dual.host);
        dual.drainAll();
        Assert.assertTrue(dual.host.state.isCancelAllowed());
        dual.confirm(dual.guest);
        dual.drainAll();
        Assert.assertFalse(dual.host.state.isCancelAllowed());
        Assert.assertNull(dual.host.state.cancel("nope"));
        Assert.assertNull(dual.guest.state.cancel("nope"));
    }

    @Test
    public void overflowOnDeliverExact() throws Exception {
        // Shrink host backpack to 1; already holds ITEM_A×1 → receiving ITEM_B overflows.
        final ConfigData cfg = Config.instance().getConfigData();
        cfg.backpackSlots = 1;
        dual.host.player.getBags().resetToDefaults(cfg);
        // Reset inventory to a single stone.
        while (itemCount(dual.host.player, ITEM_A) > 0) {
            Assert.assertTrue(dual.host.bag.takeItem(ITEM_A, 1));
        }
        while (itemCount(dual.host.player, ITEM_B) > 0) {
            Assert.assertTrue(dual.host.bag.takeItem(ITEM_B, 1));
        }
        giveItem(dual.host.player, ITEM_A, 1);
        Assert.assertEquals(itemCount(dual.host.player, ITEM_A), 1);

        dual.openTrade();
        dual.setOffer(dual.host, new CoopTradeOffer(1, null, null, null));
        dual.setOffer(dual.guest, itemOffer(ITEM_B, 1, 1));
        dual.drainAll();
        dual.confirm(dual.host);
        dual.confirm(dual.guest);
        dual.drainAll();

        Assert.assertTrue(dual.host.log.hasDelivered(dual.host.state.getTradeId()));
        Assert.assertEquals(itemCount(dual.host.player, ITEM_A), 1);
        Assert.assertEquals(itemCount(dual.host.player, ITEM_B), 0);
        Assert.assertTrue(dual.host.player.getBags().getOverflow().size() >= 1,
                "ITEM_B must land in Overflow");
        // Restore bag config for later tests in this JVM.
        cfg.backpackSlots = 20;
    }

    @Test
    public void characterSaveRoundTripsTradeLogAtomically() throws Exception {
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        dual.confirmEscrowBothFreeze();
        final long id = dual.host.state.getTradeId();
        Assert.assertEquals(dual.host.log.get(id).phase, CoopTradeLog.Phase.ESCROWED);
        CoopTradeGlOps.syncLogAndSave(dual.host.player, dual.host.log);

        final File chr = CoopCharacterStore.characterFile(dual.host.player.getName());
        Assert.assertTrue(chr.isFile());
        Assert.assertFalse(new File(chr.getPath() + ".tmp").exists(), "tmp must be gone after atomic move");

        final AdventurePlayer loaded = new AdventurePlayer();
        Assert.assertTrue(CoopCharacterStore.loadPlayer(loaded, dual.host.player.getName()));
        Assert.assertEquals(loaded.getGold(), 100);
        Assert.assertEquals(loaded.getMaterial("oak"), 4, "host escrowed oak×1");
        Assert.assertEquals(loaded.getMaterial("iron"), 0, "host must not hold guest iron yet");
        final CoopTradeLog reloaded = new CoopTradeLog();
        reloaded.decode(loaded.getTradeLogBlob());
        Assert.assertEquals(reloaded.get(id).phase, CoopTradeLog.Phase.ESCROWED);
        Assert.assertTrue(reloaded.hasEscrowed(id));
        Assert.assertFalse(reloaded.hasDelivered(id));
    }

    /**
     * Mutation check: if deliver is allowed before peer escrowed, the happy-path
     * exact-bag test's invariant {@link CoopTradeGlOps#performDeliver} refuses.
     * Documented in the PR report — this method asserts the guard itself.
     */
    @Test
    public void deliverGuardRequiresPeerEscrowed() throws Exception {
        dual.openTrade();
        dual.setOffer(dual.host, matsOffer("oak", 1, 5, 0));
        dual.setOffer(dual.guest, matsOffer("iron", 1, 4, 0));
        dual.drainAll();
        // Escrow host only (local confirm both on host bag lookup, then only host escrows).
        dual.host.gl.add(() -> {
            dual.host.state.acceptConfirm(new CoopTradeConfirmEvent(
                    dual.host.state.getTradeId(), CoopTradeRole.HOST, true, 1, 1));
            dual.host.state.acceptConfirm(new CoopTradeConfirmEvent(
                    dual.host.state.getTradeId(), CoopTradeRole.GUEST, true, 1, 1));
            Assert.assertTrue(CoopTradeGlOps.performEscrow(
                    dual.host.state, dual.host.log, dual.host.bag, dual.host.send::accept));
        });
        dual.drainAll();
        Assert.assertTrue(dual.host.log.hasEscrowed(dual.host.state.getTradeId()));
        Assert.assertFalse(dual.host.state.isPeerEscrowed());
        // Deliver must refuse — peer never escrowed.
        Assert.assertFalse(CoopTradeGlOps.performDeliver(
                dual.host.state, dual.host.log, dual.host.bag, dual.host.send::accept));
        // Host still missing iron (did not receive guest goods).
        Assert.assertEquals(dual.host.player.getMaterial("iron"), 0);
    }

    @Test
    public void duplicateIdGuardOnReceiveInvite() throws Exception {
        dual.openTrade();
        final long id = dual.host.state.getTradeId();
        dual.guest.log.record(id, CoopTradeLog.Phase.ESCROWED, 1L,
                CoopTradeOffer.empty(), CoopTradeOffer.empty());
        // Same id again must be rejected (log contains it).
        Assert.assertFalse(dual.guest.state.receiveInvite(
                new CoopTradeInviteEvent(id, "Host", 30), false));
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
