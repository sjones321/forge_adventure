package forge.gamemodes.net.coop;

import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopTradeAckEvent;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TR1 behaviour tests: two-phase commit, offer versions, disconnect/cancel
 * after Execute, item/card rollback, null wire fields, gold overflow.
 * Loopback uses {@link CoopOverworldServer}/{@link CoopOverworldClient}.
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

    /** Open a trade: host invites, guest accepts. Bags keyed by role. */
    private static CoopTradeState openTrade(final CoopTradeBag hostBag, final CoopTradeBag guestBag) {
        final CoopTradeState host = new CoopTradeState();
        host.setBagLookup(role -> role == CoopTradeRole.HOST ? hostBag : guestBag);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 20, true);
        Assert.assertNotNull(invite);
        final CoopTradeState guest = new CoopTradeState();
        guest.setBagLookup(role -> role == CoopTradeRole.HOST ? hostBag : guestBag);
        Assert.assertTrue(guest.receiveInvite(invite, false));
        Assert.assertTrue(host.applyPeerResponse(guest.respondInvite(true), true));
        Assert.assertTrue(host.isOpen());
        return host;
    }

    private static CoopTradeOfferEvent setOffer(final CoopTradeState st, final CoopTradeRole role,
                                                final CoopTradeOffer offer) {
        final CoopTradeOfferEvent accepted = st.acceptOffer(
                new CoopTradeOfferEvent(st.getTradeId(), role, offer, 0));
        Assert.assertNotNull(accepted, "offer rejected for " + role);
        return accepted;
    }

    private static CoopTradeExecuteEvent bothConfirm(final CoopTradeState host) {
        final int hv = host.getHostOfferVersion();
        final int gv = host.getGuestOfferVersion();
        host.acceptConfirm(new CoopTradeConfirmEvent(host.getTradeId(), CoopTradeRole.HOST, true, hv), true);
        final Object result = host.acceptConfirm(
                new CoopTradeConfirmEvent(host.getTradeId(), CoopTradeRole.GUEST, true, gv), true);
        Assert.assertTrue(result instanceof CoopTradeExecuteEvent, String.valueOf(result));
        return (CoopTradeExecuteEvent) result;
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
                "forge.gamemodes.net.event.coop.CoopTradeAckEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeRole"));
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
    }

    @Test
    public void twoPhaseCommitGuestFirstThenHost() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        final CoopTradeState host = openTrade(hostBag, guestBag);

        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.WAITING_GUEST_ACK);
        // Host bag unchanged until guest ack.
        Assert.assertEquals(hostBag.getGold(), 40);
        Assert.assertEquals(guestBag.getGold(), 40);

        // Guest applies.
        final CoopTradeBag.Snapshot guestSnap = guestBag.snapshot();
        final CoopTradeApply.Result guestApply = CoopTradeApply.applyLocal(
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), guestSnap);
        Assert.assertTrue(guestApply.applied, guestApply.detail);
        Assert.assertEquals(guestBag.getGold(), 40 - 3 + 5);
        Assert.assertEquals(hostBag.getGold(), 40);

        // Host receives guest ack while still WAITING_GUEST_ACK (host bag unchanged).
        final Object ackResult = host.receiveGuestAck(
                new CoopTradeAckEvent(exec.getTradeId(), CoopTradeRole.GUEST, true, ""));
        Assert.assertTrue(ackResult instanceof CoopTradeAckEvent);

        final CoopTradeBag.Snapshot hostSnap = hostBag.snapshot();
        final CoopTradeApply.Result hostApply = CoopTradeApply.applyLocal(
                hostBag, exec.getHostOffer(), exec.getGuestOffer(), hostSnap);
        Assert.assertTrue(hostApply.applied, hostApply.detail);
        final CoopTradeAckEvent complete = host.markHostCompleted();
        Assert.assertNotNull(complete);
        Assert.assertEquals(complete.getFromRole(), CoopTradeRole.HOST);
        Assert.assertEquals(hostBag.getGold(), 40 - 5 + 3);
        Assert.assertEquals(guestBag.getGold(), 40 - 3 + 5);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.COMPLETED);
    }

    @Test
    public void cancelAfterExecuteLeavesBothUnchanged() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        guestBag.setItem("Sword", 1);
        hostBag.setItem("Shield", 1);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line("Shield", 1, 1)), null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line("Sword", 1, 1)), null));
        bothConfirm(host);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.WAITING_GUEST_ACK);

        // Cancel before guest applies.
        final CoopTradeCancelEvent cancel = host.cancel("cancel after execute");
        Assert.assertNotNull(cancel);
        Assert.assertEquals(hostBag.getGold(), 40);
        Assert.assertEquals(guestBag.getGold(), 40);
        Assert.assertEquals(hostBag.getItemCount("Shield"), 1);
        Assert.assertEquals(guestBag.getItemCount("Sword"), 1);
    }

    @Test
    public void disconnectAfterExecuteBeforeAckLeavesBothUnchanged() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(5, null, null, null));
        bothConfirm(host);

        final CoopTradeCancelEvent cancel = host.onDisconnect();
        Assert.assertNotNull(cancel);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.CANCELLED);
        Assert.assertEquals(hostBag.getGold(), 40);
        Assert.assertEquals(guestBag.getGold(), 40);
    }

    @Test
    public void disconnectAfterExecuteAfterGuestAckRollsGuestBack() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);

        final CoopTradeState host = new CoopTradeState();
        host.setBagLookup(role -> role == CoopTradeRole.HOST ? hostBag : guestBag);
        final CoopTradeInviteEvent inv = host.beginInvite("Host", 20, true);
        final CoopTradeState guest = new CoopTradeState();
        guest.setBagLookup(role -> role == CoopTradeRole.HOST ? hostBag : guestBag);
        guest.receiveInvite(inv, false);
        host.applyPeerResponse(guest.respondInvite(true), true);
        guest.applyPeerResponse(new CoopTradeResponseEvent(inv.getInviteId(), true), false);

        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        // Mirror offers onto guest state for versioned confirms on host only.
        setOffer(guest, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(guest, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        guest.receiveExecute(exec, false);

        final CoopTradeBag.Snapshot snap = guestBag.snapshot();
        Assert.assertTrue(CoopTradeApply.applyLocal(
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), snap).applied);
        Assert.assertEquals(guestBag.getGold(), 40 - 3 + 5);
        Assert.assertNotNull(guest.markGuestApplied(true, "", snap, 1_000L));
        Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.GUEST_APPLIED);

        // Disconnect before host complete → guest rolls back; host never applied.
        Assert.assertNotNull(guest.onDisconnect());
        Assert.assertTrue(guest.rollbackGuestApply(guestBag));
        Assert.assertEquals(guestBag.getGold(), 40);
        Assert.assertEquals(hostBag.getGold(), 40);
    }

    @Test
    public void guestAckTimeoutRollsBack() {
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        guestBag.setGold(40);
        final CoopTradeState guest = new CoopTradeState();
        final CoopTradeInviteEvent inv = guest.beginInvite("Host", 20, true);
        final CoopTradeState g = new CoopTradeState();
        g.receiveInvite(inv, false);
        guest.applyPeerResponse(g.respondInvite(true), true);
        g.applyPeerResponse(new CoopTradeResponseEvent(inv.getInviteId(), true), false);

        setOffer(guest, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(guest, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        // Use host state for confirm/execute.
        final CoopTradeExecuteEvent exec = bothConfirm(guest);
        g.receiveExecute(exec, false);

        final CoopTradeBag.Snapshot snap = guestBag.snapshot();
        // Guest bag is the "guest" side — apply guest give/host receive.
        // For this test guestBag is the only bag; treat offers as give 3 recv 5.
        Assert.assertTrue(CoopTradeApply.applyLocal(guestBag,
                exec.getGuestOffer(), exec.getHostOffer(), snap).applied);
        g.markGuestApplied(true, "", snap, 1_000L);
        Assert.assertEquals(guestBag.getGold(), 42);

        Assert.assertTrue(g.expireGuestAckIfNeeded(1_000L + 20_000L, 15_000L));
        Assert.assertTrue(g.rollbackGuestApply(guestBag));
        Assert.assertEquals(guestBag.getGold(), 40);
        Assert.assertEquals(g.getStatus(), CoopTradeState.Status.CANCELLED);
    }

    @Test
    public void confirmForStaleOfferVersionIsIgnored() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(20);
        final CoopTradeState host = openTrade(bag, bag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        final int ver = host.getHostOfferVersion();
        host.acceptConfirm(new CoopTradeConfirmEvent(host.getTradeId(), CoopTradeRole.HOST, true, ver), true);
        Assert.assertTrue(host.isLocalConfirmed());

        // Offer change bumps version and clears confirms.
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(6, null, null, null));
        Assert.assertFalse(host.isLocalConfirmed());
        Assert.assertTrue(host.getHostOfferVersion() > ver);

        // Stale confirm ignored.
        final Object stale = host.acceptConfirm(
                new CoopTradeConfirmEvent(host.getTradeId(), CoopTradeRole.HOST, true, ver), true);
        Assert.assertNull(stale);
        Assert.assertFalse(host.isLocalConfirmed());
    }

    @Test
    public void itemAndCardRollbackOnGrantFailure() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setGold(10);
        a.setItem("Axe", 1);
        a.setCard("Mountain|LEA|0", 2, false);
        b.setGold(10);
        b.setItem("Helm", 1);
        b.setCard("Swamp|LEA|0", 1, false);
        b.setFailNextGrant(true); // fail when receiving A's grant

        final CoopTradeOffer aOffer = new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line("Axe", 1, 1)),
                Collections.singletonList(new CoopTradeOffer.CardLine("Mountain", "LEA", 0, 1, 2)));
        final CoopTradeOffer bOffer = new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line("Helm", 1, 1)),
                Collections.singletonList(new CoopTradeOffer.CardLine("Swamp", "LEA", 0, 1, 1)));

        final CoopTradeApply.Result r = CoopTradeApply.applyAtomic(a, aOffer, b, bOffer);
        Assert.assertFalse(r.applied);
        Assert.assertEquals(a.getItemCount("Axe"), 1);
        Assert.assertEquals(b.getItemCount("Helm"), 1);
        Assert.assertEquals(a.getTradeableCardCount("Mountain|LEA|0"), 2);
        Assert.assertEquals(b.getTradeableCardCount("Swamp|LEA|0"), 1);
        Assert.assertEquals(a.getGold(), 10);
        Assert.assertEquals(b.getGold(), 10);
    }

    @Test
    public void guestApplyFailureLeavesBothUnchanged() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        guestBag.setFailNextGrant(true);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);

        final CoopTradeBag.Snapshot snap = guestBag.snapshot();
        final CoopTradeApply.Result fail = CoopTradeApply.applyLocal(
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), snap);
        Assert.assertFalse(fail.applied);
        Assert.assertEquals(guestBag.getGold(), 40);
        Assert.assertEquals(hostBag.getGold(), 40);

        final Object ackResult = host.receiveGuestAck(
                new CoopTradeAckEvent(exec.getTradeId(), CoopTradeRole.GUEST, false, fail.detail));
        Assert.assertTrue(ackResult instanceof CoopTradeCancelEvent);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.CANCELLED);
        Assert.assertEquals(hostBag.getGold(), 40);
    }

    @Test
    public void nullWireFieldsRejected() {
        Assert.assertFalse(CoopTradeValidator.validate(null, null).ok());
        Assert.assertEquals(CoopTradeValidator.validate(null, null).reason,
                CoopTradeValidator.RejectReason.NULL_OFFER);

        // Null id / name via Line constructor becomes "" → TEXT_LEN / empty reject as COUNT or TEXT_LEN.
        final CoopTradeOffer badItem = new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line(null, 1, 1)), null);
        Assert.assertFalse(CoopTradeValidator.validateWire(badItem).ok());

        final CoopTradeOffer badCard = new CoopTradeOffer(0, null, null,
                Collections.singletonList(new CoopTradeOffer.CardLine(null, "LEA", 0, 1, 1)));
        Assert.assertFalse(CoopTradeValidator.validateWire(badCard).ok());

        // Negative count clamped to 0 by Line → COUNT reject.
        final CoopTradeOffer neg = new CoopTradeOffer(0,
                Collections.singletonList(new CoopTradeOffer.Line("oak", -3, 5)), null, null);
        Assert.assertFalse(CoopTradeValidator.validateWire(neg).ok());
        Assert.assertEquals(CoopTradeValidator.validateWire(neg).reason,
                CoopTradeValidator.RejectReason.COUNT);

        // Null lists are frozen to empty — still valid empty offer.
        Assert.assertTrue(CoopTradeValidator.validateWire(new CoopTradeOffer(0, null, null, null)).ok());
    }

    @Test
    public void goldOverflowRefused() {
        Assert.assertTrue(CoopTradeValidator.goldAddWouldOverflow(Integer.MAX_VALUE - 5, 10));
        Assert.assertFalse(CoopTradeValidator.goldAddWouldOverflow(100, 50));

        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(Integer.MAX_VALUE - 10);
        Assert.assertFalse(bag.addGold(20));
        Assert.assertEquals(bag.getGold(), Integer.MAX_VALUE - 10);
        Assert.assertTrue(bag.addGold(5));
        Assert.assertEquals(bag.getGold(), Integer.MAX_VALUE - 5);

        // Offer that would overflow the receiver when bag is supplied.
        final CoopTradeBag.Simple recv = new CoopTradeBag.Simple();
        recv.setGold(Integer.MAX_VALUE - 1);
        final CoopTradeOffer offer = new CoopTradeOffer(5, null, null, null);
        // validate checks overflow against bag gold (as if bag is giving — ownership)
        // For receiver overflow we check in grant; applyLocal must fail and restore.
        final CoopTradeBag.Simple giver = new CoopTradeBag.Simple();
        giver.setGold(10);
        final CoopTradeBag.Snapshot snap = recv.snapshot();
        final CoopTradeApply.Result r = CoopTradeApply.applyLocal(recv, CoopTradeOffer.empty(), offer, snap);
        Assert.assertFalse(r.applied);
        Assert.assertEquals(recv.getGold(), Integer.MAX_VALUE - 1);
    }

    @Test
    public void rejectedInvalidOfferChangesNothing() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        a.setGold(20);
        a.setMaterial("oak", 1);
        final CoopTradeState host = openTrade(a, a);
        final CoopTradeOffer bad = new CoopTradeOffer(50,
                Collections.singletonList(new CoopTradeOffer.Line("oak", 5, 1)), null, null);
        Assert.assertNull(host.acceptOffer(new CoopTradeOfferEvent(host.getTradeId(),
                CoopTradeRole.HOST, bad, 0)));
        Assert.assertEquals(a.getGold(), 20);
        Assert.assertEquals(a.getMaterial("oak"), 1);
    }

    @Test
    public void vaultedAndDeckCardsRefused() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setCard("Black Lotus|LEA|0", 0, true);
        final CoopTradeOffer vaulted = new CoopTradeOffer(0, null, null,
                Collections.singletonList(new CoopTradeOffer.CardLine("Black Lotus", "LEA", 0, 1, 1)));
        Assert.assertEquals(CoopTradeValidator.validate(vaulted, bag).reason,
                CoopTradeValidator.RejectReason.VAULTED_OR_DECK);
    }

    @Test
    public void questItemsRefused() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setItem("Quest Relic", 1);
        bag.markQuestItem("Quest Relic");
        final CoopTradeOffer offer = new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line("Quest Relic", 1, 1)), null);
        Assert.assertEquals(CoopTradeValidator.validate(offer, bag).reason,
                CoopTradeValidator.RejectReason.QUEST_ITEM);
    }

    @Test
    public void receivedItemsGoToOverflowWhenFull() {
        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setItemCapacity(1);
        a.setItem("Sword", 1);
        b.setItem("Shield", 1);
        b.setItem("Helmet", 1);
        final CoopTradeApply.Result r = CoopTradeApply.applyAtomic(a, CoopTradeOffer.empty(), b,
                new CoopTradeOffer(0, null, Arrays.asList(
                        new CoopTradeOffer.Line("Shield", 1, 1),
                        new CoopTradeOffer.Line("Helmet", 1, 1)), null));
        Assert.assertTrue(r.applied, r.detail);
        Assert.assertTrue(a.overflowCount() >= 1);
    }

    @Test
    public void peersIdentifiedByRoleNotName() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(10);
        guestBag.setGold(20);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        Assert.assertEquals(host.getLocalRole(), CoopTradeRole.HOST);
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(2, null, null, null));
        Assert.assertEquals(host.getGuestOffer().getGold(), 2);
        Assert.assertEquals(host.getHostOffer().getGold(), 0);
    }

    @Test
    public void tradeExecuteAndAckRoundTripOverLoopback() throws Exception {
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch gotExecute = new CountDownLatch(1);
        final CountDownLatch gotAck = new CountDownLatch(1);
        final AtomicReference<CoopTradeExecuteEvent> execRef = new AtomicReference<>();
        final AtomicReference<CoopTradeAckEvent> ackRef = new AtomicReference<>();
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
                    server.send(new CoopTradeExecuteEvent(99L, hostOffer, guestOffer, 1, 1));
                } else if (event instanceof CoopTradeAckEvent) {
                    ackRef.set((CoopTradeAckEvent) event);
                    gotAck.countDown();
                    // Host complete ack back.
                    server.send(new CoopTradeAckEvent(99L, CoopTradeRole.HOST, true, "complete"));
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
                    execRef.set((CoopTradeExecuteEvent) event);
                    gotExecute.countDown();
                    // Guest acks success (apply simulated by test bags below).
                    client.send(new CoopTradeAckEvent(
                            ((CoopTradeExecuteEvent) event).getTradeId(),
                            CoopTradeRole.GUEST, true, ""));
                }
            }
            @Override public void onDisconnected(final String reason) { }
            @Override public void onError(final String message, final Throwable cause) { }
        });
        client.connect();
        Assert.assertTrue(ready.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(gotExecute.await(10, TimeUnit.SECONDS));
        Assert.assertTrue(gotAck.await(10, TimeUnit.SECONDS));
        Assert.assertNotNull(execRef.get());
        Assert.assertEquals(execRef.get().getHostOffer().getGold(), 7);
        Assert.assertNotNull(ackRef.get());
        Assert.assertEquals(ackRef.get().getFromRole(), CoopTradeRole.GUEST);
        Assert.assertTrue(ackRef.get().isSuccess());

        final CoopTradeBag.Simple a = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        a.setGold(20);
        b.setGold(20);
        Assert.assertTrue(CoopTradeApply.applyAtomic(
                a, execRef.get().getHostOffer(), b, execRef.get().getGuestOffer()).applied);
        Assert.assertEquals(a.getGold(), 20 - 7 + 3);
        Assert.assertEquals(b.getGold(), 20 - 3 + 7);
    }

    @Test
    public void inviteQueuedBehindBusyPrompt() {
        final CoopInviteUiState ui = new CoopInviteUiState();
        Assert.assertNotNull(ui.enqueue(CoopInviteUiState.PromptKind.PARTY, 1L, "A", ""));
        Assert.assertNull(ui.enqueue(CoopInviteUiState.PromptKind.TRADE, 2L, "B", "", true));
        Assert.assertEquals(ui.hideAndPollNext().kind, CoopInviteUiState.PromptKind.TRADE);
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
