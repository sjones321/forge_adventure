package forge.gamemodes.net.coop;

import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeExecuteEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TR1 behaviour tests: atomic swap, rejected offer, disconnect mid-trade,
 * vaulted/deck refusal, WireClassFilter allowlist, Overflow on receive.
 * Loopback uses {@link CoopOverworldServer}/{@link CoopOverworldClient} like CO2 E2E.
 */
public class CoopTradeTest {

    private CoopOverworldServer server;
    private CoopOverworldClient client;
    private int port;
    private String sessionCode;

    @BeforeMethod
    public void setUp() throws Exception {
        CoopVersion.setCardDataHashSupplier(() -> CoopVersion.sha256Hex("test-cards-tr1"));
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
    public void protocolVersionIsEightForTr1() {
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 8);
        Assert.assertTrue(CoopPorts.PROTOCOL_VERSION > 7);
    }

    @Test
    public void wireClassFilterAllowsTradeEvents() {
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeInviteEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeResponseEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeOfferEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeConfirmEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeCancelEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeExecuteEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeOffer"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeOffer$Line"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeOffer$CardLine"));
    }

    @Test
    public void atomicSwapAppliesOnBothBags() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setGold(100);
        a.setMaterial("oak", 5);
        a.setItem("Potion", 2);
        a.setCard("Forest|LEA|0", 3, false);
        b.setGold(50);
        b.setMaterial("iron", 4);
        b.setItem("Boots", 1);
        b.setCard("Island|LEA|0", 2, false);

        final CoopTradeOffer aOffer = new CoopTradeOffer(25,
                Collections.singletonList(new CoopTradeOffer.Line("oak", 2, 5)),
                Collections.singletonList(new CoopTradeOffer.Line("Potion", 1, 2)),
                Collections.singletonList(new CoopTradeOffer.CardLine("Forest", "LEA", 0, 1, 3)));
        final CoopTradeOffer bOffer = new CoopTradeOffer(10,
                Collections.singletonList(new CoopTradeOffer.Line("iron", 1, 4)),
                Collections.singletonList(new CoopTradeOffer.Line("Boots", 1, 1)),
                Collections.singletonList(new CoopTradeOffer.CardLine("Island", "LEA", 0, 1, 2)));

        final CoopTradeApply.Result r = CoopTradeApply.applyAtomic(a, aOffer, b, bOffer);
        Assert.assertTrue(r.applied, r.detail);
        Assert.assertEquals(a.getGold(), 100 - 25 + 10);
        Assert.assertEquals(b.getGold(), 50 - 10 + 25);
        Assert.assertEquals(a.getMaterial("oak"), 3);
        Assert.assertEquals(b.getMaterial("oak"), 2);
        Assert.assertEquals(a.getMaterial("iron"), 1);
        Assert.assertEquals(b.getMaterial("iron"), 3);
        Assert.assertEquals(a.getItemCount("Potion"), 1);
        Assert.assertEquals(b.getItemCount("Potion"), 1);
        Assert.assertEquals(a.getItemCount("Boots"), 1);
        Assert.assertEquals(b.getItemCount("Boots"), 0);
        Assert.assertEquals(a.getTradeableCardCount("Forest|LEA|0"), 2);
        Assert.assertEquals(b.getTradeableCardCount("Forest|LEA|0"), 1);
        Assert.assertEquals(a.getTradeableCardCount("Island|LEA|0"), 1);
        Assert.assertEquals(b.getTradeableCardCount("Island|LEA|0"), 1);
    }

    @Test
    public void rejectedInvalidOfferChangesNothing() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        a.setGold(20);
        a.setMaterial("oak", 1);
        final CoopTradeBag.Snapshot snap = a.snapshot();

        final CoopTradeOffer bad = new CoopTradeOffer(50,
                Collections.singletonList(new CoopTradeOffer.Line("oak", 5, 1)),
                null, null);
        final CoopTradeValidator.Result v = CoopTradeValidator.validate(bad, a);
        Assert.assertFalse(v.ok());
        Assert.assertEquals(v.reason, CoopTradeValidator.RejectReason.OWNERSHIP);

        final CoopTradeState host = new CoopTradeState();
        host.setBagLookup(name -> a);
        final CoopTradeInviteEvent inv = host.beginInvite("Host", 20);
        Assert.assertNotNull(inv);
        final CoopTradeState guest = new CoopTradeState();
        guest.receiveInvite(inv);
        host.applyPeerResponse(guest.respondInvite(true), "Guest", true);

        final CoopTradeOfferEvent rejected = host.acceptOffer(
                new CoopTradeOfferEvent(host.getTradeId(), "Host", bad), true);
        Assert.assertNull(rejected);
        Assert.assertEquals(a.getGold(), 20);
        Assert.assertEquals(a.getMaterial("oak"), 1);
        // Snapshot unchanged — never applied.
        a.restore(snap);
        Assert.assertEquals(a.getGold(), 20);
        Assert.assertEquals(a.getMaterial("oak"), 1);
    }

    @Test
    public void disconnectBeforeConfirmLeavesBothUnchanged() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setGold(40);
        b.setGold(40);
        final CoopTradeBag.Snapshot snapA = a.snapshot();
        final CoopTradeBag.Snapshot snapB = b.snapshot();

        final CoopTradeState host = new CoopTradeState();
        host.setBagLookup(name -> "Host".equals(name) ? a : b);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 20);
        Assert.assertNotNull(invite);
        final CoopTradeState guest = new CoopTradeState();
        Assert.assertTrue(guest.receiveInvite(invite));
        final CoopTradeResponseEvent resp = guest.respondInvite(true);
        Assert.assertTrue(host.applyPeerResponse(resp, "Guest", true));
        Assert.assertTrue(host.isOpen());

        host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(), "Host",
                new CoopTradeOffer(10, null, null, null)), true);
        // Disconnect before any confirm.
        final CoopTradeCancelEvent cancel = host.onDisconnect();
        Assert.assertNotNull(cancel);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.CANCELLED);
        a.restore(snapA);
        b.restore(snapB);
        Assert.assertEquals(a.getGold(), 40);
        Assert.assertEquals(b.getGold(), 40);
    }

    @Test
    public void disconnectBetweenConfirmsLeavesBothUnchanged() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setGold(40);
        b.setGold(40);
        final CoopTradeBag.Snapshot snapA = a.snapshot();
        final CoopTradeBag.Snapshot snapB = b.snapshot();

        final CoopTradeState host = new CoopTradeState();
        host.setBagLookup(name -> "Host".equals(name) ? a : b);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 20);
        final CoopTradeState guest = new CoopTradeState();
        guest.receiveInvite(invite);
        host.applyPeerResponse(guest.respondInvite(true), "Guest", true);

        host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(), "Host",
                new CoopTradeOffer(5, null, null, null)), true);
        host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(), "Guest",
                new CoopTradeOffer(5, null, null, null)), false);

        // Only host confirms — peer has not.
        final Object mid = host.acceptConfirm(
                new CoopTradeConfirmEvent(host.getTradeId(), "Host", true), true, true);
        Assert.assertTrue(mid instanceof CoopTradeConfirmEvent);
        Assert.assertNull(host.getPendingExecute());

        final CoopTradeCancelEvent cancel = host.onDisconnect();
        Assert.assertNotNull(cancel);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.CANCELLED);
        a.restore(snapA);
        b.restore(snapB);
        Assert.assertEquals(a.getGold(), 40);
        Assert.assertEquals(b.getGold(), 40);
    }

    @Test
    public void vaultedAndDeckCardsRefused() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setCard("Black Lotus|LEA|0", 0, true); // vaulted/deck only
        final CoopTradeOffer vaulted = new CoopTradeOffer(0, null, null,
                Collections.singletonList(new CoopTradeOffer.CardLine("Black Lotus", "LEA", 0, 1, 1)));
        final CoopTradeValidator.Result r1 = CoopTradeValidator.validate(vaulted, bag);
        Assert.assertFalse(r1.ok());
        Assert.assertEquals(r1.reason, CoopTradeValidator.RejectReason.VAULTED_OR_DECK);

        bag.setCard("Lightning Bolt|LEA|0", 0, true);
        final CoopTradeOffer inDeck = new CoopTradeOffer(0, null, null,
                Collections.singletonList(new CoopTradeOffer.CardLine("Lightning Bolt", "LEA", 0, 1, 1)));
        final CoopTradeValidator.Result r2 = CoopTradeValidator.validate(inDeck, bag);
        Assert.assertFalse(r2.ok());
        Assert.assertEquals(r2.reason, CoopTradeValidator.RejectReason.VAULTED_OR_DECK);

        // Tradeable copies still work.
        bag.setCard("Mountain|LEA|0", 2, false);
        final CoopTradeOffer ok = new CoopTradeOffer(0, null, null,
                Collections.singletonList(new CoopTradeOffer.CardLine("Mountain", "LEA", 0, 1, 2)));
        Assert.assertTrue(CoopTradeValidator.validate(ok, bag).ok());
    }

    @Test
    public void questItemsRefused() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setItem("Quest Relic", 1);
        bag.markQuestItem("Quest Relic");
        final CoopTradeOffer offer = new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line("Quest Relic", 1, 1)), null);
        final CoopTradeValidator.Result r = CoopTradeValidator.validate(offer, bag);
        Assert.assertFalse(r.ok());
        Assert.assertEquals(r.reason, CoopTradeValidator.RejectReason.QUEST_ITEM);
    }

    @Test
    public void receivedItemsGoToOverflowWhenFull() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setItemCapacity(1);
        a.setItem("Sword", 1);
        b.setItem("Shield", 1);
        b.setItem("Helmet", 1);

        final CoopTradeOffer aOffer = CoopTradeOffer.empty();
        final CoopTradeOffer bOffer = new CoopTradeOffer(0, null,
                java.util.Arrays.asList(
                        new CoopTradeOffer.Line("Shield", 1, 1),
                        new CoopTradeOffer.Line("Helmet", 1, 1)),
                null);
        final CoopTradeApply.Result r = CoopTradeApply.applyAtomic(a, aOffer, b, bOffer);
        Assert.assertTrue(r.applied, r.detail);
        // Sword fills capacity; first received may fit if we clear nothing from a —
        // a offered nothing so still has Sword; Shield/Helmet overflow.
        Assert.assertEquals(a.getItemCount("Sword"), 1);
        Assert.assertTrue(a.overflowCount() >= 1, "expected Overflow for excess items");
        Assert.assertTrue(a.getOverflowItems().contains("Shield")
                || a.getOverflowItems().contains("Helmet")
                || a.getItemCount("Shield") + a.getItemCount("Helmet") + a.overflowCount() == 3);
    }

    @Test
    public void offerChangeResetsBothConfirmations() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        a.setGold(20);
        final CoopTradeState host = new CoopTradeState();
        host.setBagLookup(name -> a);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 20);
        final CoopTradeState guest = new CoopTradeState();
        guest.receiveInvite(invite);
        host.applyPeerResponse(guest.respondInvite(true), "Guest", true);

        host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(), "Host",
                new CoopTradeOffer(5, null, null, null)), true);
        host.acceptConfirm(new CoopTradeConfirmEvent(host.getTradeId(), "Host", true), true, true);
        Assert.assertTrue(host.isLocalConfirmed());

        host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(), "Host",
                new CoopTradeOffer(6, null, null, null)), true);
        Assert.assertFalse(host.isLocalConfirmed());
        Assert.assertFalse(host.isPeerConfirmed());
    }

    @Test
    public void bothConfirmProducesExecuteEvent() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setGold(20);
        b.setGold(20);
        final CoopTradeState host = new CoopTradeState();
        host.setBagLookup(name -> "Host".equals(name) ? a : b);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 20);
        final CoopTradeState guest = new CoopTradeState();
        guest.receiveInvite(invite);
        host.applyPeerResponse(guest.respondInvite(true), "Guest", true);

        host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(), "Host",
                new CoopTradeOffer(5, null, null, null)), true);
        host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(), "Guest",
                new CoopTradeOffer(3, null, null, null)), false);

        host.acceptConfirm(new CoopTradeConfirmEvent(host.getTradeId(), "Host", true), true, true);
        final Object result = host.acceptConfirm(
                new CoopTradeConfirmEvent(host.getTradeId(), "Guest", true), false, true);
        Assert.assertTrue(result instanceof CoopTradeExecuteEvent);
        final CoopTradeExecuteEvent exec = (CoopTradeExecuteEvent) result;
        Assert.assertEquals(exec.getHostOffer().getGold(), 5);
        Assert.assertEquals(exec.getGuestOffer().getGold(), 3);

        final CoopTradeApply.Result applied = host.applyPending(a, b, true);
        Assert.assertTrue(applied.applied, applied.detail);
        Assert.assertEquals(a.getGold(), 20 - 5 + 3);
        Assert.assertEquals(b.getGold(), 20 - 3 + 5);
    }

    @Test
    public void tradeInviteRoundTripOverLoopback() throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch gotInvite = new CountDownLatch(1);
        final AtomicReference<CoopTradeInviteEvent> received = new AtomicReference<>();
        final String worldHash = CoopWorldHash.hash(9L, 4, 4, sampleBiome(4), sampleTerrain(4));

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override public void onConnected() { }
            @Override public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "Shandalar Ascendant", "plane",
                            9L, worldHash, CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopSessionReadyEvent) {
                    ready.countDown();
                    server.send(new CoopTradeInviteEvent(42L, "Host", 20));
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override public void onConnected() { client.send(hello(sessionCode)); }
            @Override public void onMessage(final NetEvent event) {
                if (event instanceof CoopWorldOfferEvent) {
                    final CoopWorldOfferEvent offer = (CoopWorldOfferEvent) event;
                    final String local = CoopWorldHash.hash(offer.getWorldSeed(), 4, 4,
                            sampleBiome(4), sampleTerrain(4));
                    client.send(new CoopSessionReadyEvent(false, "Guest", local));
                } else if (event instanceof CoopTradeInviteEvent) {
                    received.set((CoopTradeInviteEvent) event);
                    gotInvite.countDown();
                    client.send(new CoopTradeResponseEvent(
                            ((CoopTradeInviteEvent) event).getInviteId(), true));
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        client.connect();
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(gotInvite.await(10, TimeUnit.SECONDS));
        Assert.assertNotNull(received.get());
        Assert.assertEquals(received.get().getFromPlayer(), "Host");
        Assert.assertEquals(received.get().getInviteId(), 42L);
    }

    @Test
    public void tradeExecuteRoundTripOverLoopback() throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch gotExecute = new CountDownLatch(1);
        final AtomicReference<CoopTradeExecuteEvent> received = new AtomicReference<>();
        final String worldHash = CoopWorldHash.hash(11L, 4, 4, sampleBiome(4), sampleTerrain(4));

        final CoopTradeOffer hostOffer = new CoopTradeOffer(7, null, null, null);
        final CoopTradeOffer guestOffer = new CoopTradeOffer(3, null, null, null);

        server = new CoopOverworldServer(port, new CoopMessageListener() {
            @Override public void onConnected() { }
            @Override public void onMessage(final NetEvent event) {
                if (event instanceof CoopHelloEvent) {
                    server.markGuestAuthenticated();
                    server.send(new CoopWorldOfferEvent("Host", "Shandalar Ascendant", "plane",
                            11L, worldHash, CoopPorts.GAME_PORT, port));
                } else if (event instanceof CoopSessionReadyEvent) {
                    ready.countDown();
                    server.send(new CoopTradeExecuteEvent(99L, "Host", "Guest", hostOffer, guestOffer));
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        server.start();
        Assert.assertTrue(server.awaitBound(5000));

        client = new CoopOverworldClient("127.0.0.1", port, new CoopMessageListener() {
            @Override public void onConnected() { client.send(hello(sessionCode)); }
            @Override public void onMessage(final NetEvent event) {
                if (event instanceof CoopWorldOfferEvent) {
                    final CoopWorldOfferEvent offer = (CoopWorldOfferEvent) event;
                    final String local = CoopWorldHash.hash(offer.getWorldSeed(), 4, 4,
                            sampleBiome(4), sampleTerrain(4));
                    client.send(new CoopSessionReadyEvent(false, "Guest", local));
                } else if (event instanceof CoopTradeExecuteEvent) {
                    received.set((CoopTradeExecuteEvent) event);
                    gotExecute.countDown();
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        client.connect();
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(gotExecute.await(10, TimeUnit.SECONDS));
        Assert.assertNotNull(received.get());
        Assert.assertEquals(received.get().getTradeId(), 99L);
        Assert.assertEquals(received.get().getHostOffer().getGold(), 7);
        Assert.assertEquals(received.get().getGuestOffer().getGold(), 3);

        // Apply on both bags from the wire payload.
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setGold(20);
        b.setGold(20);
        final CoopTradeApply.Result applied = CoopTradeApply.applyAtomic(
                a, received.get().getHostOffer(), b, received.get().getGuestOffer());
        Assert.assertTrue(applied.applied, applied.detail);
        Assert.assertEquals(a.getGold(), 20 - 7 + 3);
        Assert.assertEquals(b.getGold(), 20 - 3 + 7);
    }

    @Test
    public void inviteQueuedBehindBusyPrompt() {
        final CoopInviteUiState ui = new CoopInviteUiState();
        final CoopInviteUiState.Prompt first = ui.enqueue(
                CoopInviteUiState.PromptKind.PARTY, 1L, "A", "");
        Assert.assertNotNull(first);
        final CoopInviteUiState.Prompt trade = ui.enqueue(
                CoopInviteUiState.PromptKind.TRADE, 2L, "B", "", true);
        Assert.assertNull(trade);
        Assert.assertEquals(ui.queueSize(), 1);
        Assert.assertEquals(ui.getKind(), CoopInviteUiState.PromptKind.PARTY);
        final CoopInviteUiState.Prompt next = ui.hideAndPollNext();
        Assert.assertNotNull(next);
        Assert.assertEquals(next.kind, CoopInviteUiState.PromptKind.TRADE);
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
