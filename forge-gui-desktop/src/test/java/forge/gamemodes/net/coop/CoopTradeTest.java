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
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
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
 * TR1 behaviour tests: two-phase commit, idempotent trade ids, reconnect
 * reconcile, dual offer-version confirms, Overflow rollback, receiver gold
 * overflow, cancel/host-apply race, session-end ordering.
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
        // Wire version must strictly advance (taken from the wire, not a local ++).
        final int current = role == CoopTradeRole.HOST ? st.getHostOfferVersion() : st.getGuestOfferVersion();
        final CoopTradeOfferEvent accepted = st.acceptOffer(
                new CoopTradeOfferEvent(st.getTradeId(), role, offer, current + 1));
        Assert.assertNotNull(accepted, "offer rejected for " + role);
        return accepted;
    }

    private static CoopTradeExecuteEvent bothConfirm(final CoopTradeState host) {
        final int hv = host.getHostOfferVersion();
        final int gv = host.getGuestOfferVersion();
        // Confirms carry BOTH offer versions (mine and theirs).
        host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.HOST, true, hv, gv), true);
        final Object result = host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.GUEST, true, gv, hv), true);
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
                "forge.gamemodes.net.event.coop.CoopTradeReconcileEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeRole"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeOffer$CardLine"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeLog$Phase"));
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

        final CoopTradeExecuteEvent claimed = host.beginHostApply(exec.getTradeId());
        Assert.assertNotNull(claimed);
        final CoopTradeBag.Snapshot hostSnap = hostBag.snapshot();
        final CoopTradeApply.Result hostApply = CoopTradeApply.applyLocal(
                hostBag, claimed.getHostOffer(), claimed.getGuestOffer(), hostSnap);
        Assert.assertTrue(hostApply.applied, hostApply.detail);
        final CoopTradeAckEvent complete = host.markHostCompleted();
        Assert.assertNotNull(complete);
        Assert.assertEquals(complete.getFromRole(), CoopTradeRole.HOST);
        Assert.assertEquals(hostBag.getGold(), 40 - 5 + 3);
        Assert.assertEquals(guestBag.getGold(), 40 - 3 + 5);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.COMPLETED);
        Assert.assertTrue(host.getTradeLog().isAtLeast(exec.getTradeId(), CoopTradeLog.Phase.COMPLETED));
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

        // Cancel refused after Execute — bags unchanged.
        Assert.assertNull(host.cancel("cancel after execute"));
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.WAITING_GUEST_ACK);
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

        // After Execute: disconnect abandons without a cancel event / bag mutation.
        Assert.assertNull(host.onDisconnect());
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.CANCELLED);
        Assert.assertEquals(hostBag.getGold(), 40);
        Assert.assertEquals(guestBag.getGold(), 40);
    }

    @Test
    public void noGuestRollbackAfterAckOnDisconnect() {
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
        setOffer(guest, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(guest, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        guest.receiveExecute(exec, false);
        Assert.assertNotNull(guest.claimGuestApply(exec.getTradeId()));

        final CoopTradeBag.Snapshot snap = guestBag.snapshot();
        Assert.assertTrue(CoopTradeApply.applyLocal(
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), snap).applied);
        Assert.assertEquals(guestBag.getGold(), 40 - 3 + 5);
        Assert.assertNotNull(guest.markGuestApplied(true, "", 1_000L));
        Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.GUEST_APPLIED);

        // After ack: disconnect must NOT roll back — reconcile later.
        Assert.assertNull(guest.onDisconnect());
        Assert.assertFalse(guest.isGuestRollbackPermitted());
        Assert.assertFalse(guest.rollbackGuestApply(guestBag));
        Assert.assertEquals(guestBag.getGold(), 42);
        Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
        Assert.assertEquals(hostBag.getGold(), 40);
    }

    @Test
    public void guestAckTimeoutEntersReconcileNotBlindRollback() {
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        guestBag.setGold(40);
        final CoopTradeState host = new CoopTradeState();
        final CoopTradeInviteEvent inv = host.beginInvite("Host", 20, true);
        final CoopTradeState g = new CoopTradeState();
        g.receiveInvite(inv, false);
        host.applyPeerResponse(g.respondInvite(true), true);
        g.applyPeerResponse(new CoopTradeResponseEvent(inv.getInviteId(), true), false);

        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        g.receiveExecute(exec, false);
        Assert.assertNotNull(g.claimGuestApply(exec.getTradeId()));

        final CoopTradeBag.Snapshot snap = guestBag.snapshot();
        Assert.assertTrue(CoopTradeApply.applyLocal(guestBag,
                exec.getGuestOffer(), exec.getHostOffer(), snap).applied);
        g.markGuestApplied(true, "", 1_000L);
        Assert.assertEquals(guestBag.getGold(), 42);

        final CoopTradeState.TimeoutOutcome outcome =
                g.expireGuestAckIfNeeded(1_000L + 20_000L, 15_000L);
        Assert.assertEquals(outcome, CoopTradeState.TimeoutOutcome.RECONCILE);
        Assert.assertEquals(g.getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
        Assert.assertTrue(g.hasGuestRollbackLines());
        Assert.assertFalse(g.isGuestRollbackPermitted());
        Assert.assertEquals(guestBag.getGold(), 42);

        // Only reconcile ABORTED permits line-only rollback.
        final CoopTradeLog.ReconcileAction action = g.applyReconcile(
                new CoopTradeReconcileEvent(exec.getTradeId(), CoopTradeRole.HOST,
                        CoopTradeLog.Phase.ABORTED, false), 2_000L);
        Assert.assertEquals(action, CoopTradeLog.ReconcileAction.ROLLBACK_GUEST);
        Assert.assertTrue(g.isGuestRollbackPermitted());
        Assert.assertTrue(g.rollbackGuestApply(guestBag));
        Assert.assertEquals(guestBag.getGold(), 40);
    }

    @Test
    public void confirmForStaleOfferVersionIsIgnored() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(20);
        final CoopTradeState host = openTrade(bag, bag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        final int hv = host.getHostOfferVersion();
        final int gv = host.getGuestOfferVersion();
        host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.HOST, true, hv, gv), true);
        Assert.assertTrue(host.isLocalConfirmed());

        // Offer change bumps version and clears confirms.
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(6, null, null, null));
        Assert.assertFalse(host.isLocalConfirmed());
        Assert.assertTrue(host.getHostOfferVersion() > hv);

        // Stale confirm ignored.
        final Object stale = host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.HOST, true, hv, gv), true);
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

        final CoopTradeBag.Simple recv = new CoopTradeBag.Simple();
        recv.setGold(Integer.MAX_VALUE - 1);
        final CoopTradeOffer offer = new CoopTradeOffer(5, null, null, null);
        Assert.assertFalse(CoopTradeValidator.validateReceiverGold(offer, recv).ok());
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
                CoopTradeRole.HOST, bad, host.getHostOfferVersion() + 1)));
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

    @Test
    public void duplicateTradeIdAppliedTwiceIsNoOp() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);

        final CoopTradeBag.Snapshot snap1 = guestBag.snapshot();
        Assert.assertTrue(CoopTradeApply.applyLocalIdempotent(
                exec.getTradeId(), CoopTradeRole.GUEST, host.getTradeLog(),
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), snap1).applied);
        host.getTradeLog().record(exec.getTradeId(), CoopTradeLog.Phase.GUEST_APPLIED, 1L);
        Assert.assertEquals(guestBag.getGold(), 42);

        // Second apply with same trade id is a no-op.
        final CoopTradeBag.Snapshot snap2 = guestBag.snapshot();
        Assert.assertTrue(CoopTradeApply.applyLocalIdempotent(
                exec.getTradeId(), CoopTradeRole.GUEST, host.getTradeLog(),
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), snap2).applied);
        Assert.assertEquals(guestBag.getGold(), 42);

        host.receiveGuestAck(new CoopTradeAckEvent(exec.getTradeId(), CoopTradeRole.GUEST, true, ""));
        Assert.assertNotNull(host.beginHostApply(exec.getTradeId()));
        final CoopTradeBag.Snapshot hSnap = hostBag.snapshot();
        Assert.assertTrue(CoopTradeApply.applyLocalIdempotent(
                exec.getTradeId(), CoopTradeRole.HOST, host.getTradeLog(),
                hostBag, exec.getHostOffer(), exec.getGuestOffer(), hSnap).applied);
        host.getTradeLog().record(exec.getTradeId(), CoopTradeLog.Phase.HOST_COMMITTED, 2L);
        Assert.assertEquals(hostBag.getGold(), 38);
        Assert.assertTrue(CoopTradeApply.applyLocalIdempotent(
                exec.getTradeId(), CoopTradeRole.HOST, host.getTradeLog(),
                hostBag, exec.getHostOffer(), exec.getGuestOffer(), hostBag.snapshot()).applied);
        Assert.assertEquals(hostBag.getGold(), 38);
    }

    @Test
    public void reconnectReconcileCompletesAfterHostCommit() {
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        guestBag.setGold(40);
        final CoopTradeLog guestLog = new CoopTradeLog();
        final CoopTradeState guest = new CoopTradeState(new CoopRateLimiter(8, 1000), guestLog);

        final CoopTradeOffer hostOffer = new CoopTradeOffer(5, null, null, null);
        final CoopTradeOffer guestOffer = new CoopTradeOffer(3, null, null, null);
        final CoopTradeInviteEvent inv = new CoopTradeInviteEvent(42L, "Host", 20);
        Assert.assertTrue(guest.receiveInvite(inv, false));
        guest.respondInvite(true);
        final CoopTradeExecuteEvent exec = new CoopTradeExecuteEvent(
                42L, hostOffer, guestOffer, 1, 1);
        guest.receiveExecute(exec, false);
        Assert.assertNotNull(guest.claimGuestApply(42L));
        Assert.assertTrue(CoopTradeApply.applyLocal(
                guestBag, guestOffer, hostOffer, guestBag.snapshot()).applied);
        guest.markGuestApplied(true, "", 1_000L);
        Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.GUEST_APPLIED);
        Assert.assertEquals(guestBag.getGold(), 42);

        final CoopTradeReconcileEvent peer = new CoopTradeReconcileEvent(
                42L, CoopTradeRole.HOST, CoopTradeLog.Phase.HOST_COMMITTED, false);
        final CoopTradeLog.ReconcileAction action = guest.applyReconcile(peer, 2_000L);
        Assert.assertEquals(action, CoopTradeLog.ReconcileAction.COMPLETE_GUEST);
        Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.COMPLETED);
        Assert.assertFalse(guest.isGuestRollbackPermitted());
        Assert.assertEquals(guestBag.getGold(), 42); // kept — no rollback
    }

    @Test
    public void noGuestRollbackAfterHostAck() {
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
        setOffer(guest, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(guest, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        guest.receiveExecute(exec, false);
        Assert.assertNotNull(guest.claimGuestApply(exec.getTradeId()));

        final CoopTradeBag.Snapshot snap = guestBag.snapshot();
        Assert.assertTrue(CoopTradeApply.applyLocal(
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), snap).applied);
        guest.markGuestApplied(true, "", 1_000L);
        Assert.assertEquals(guestBag.getGold(), 42);

        host.receiveGuestAck(new CoopTradeAckEvent(exec.getTradeId(), CoopTradeRole.GUEST, true, ""));
        Assert.assertNotNull(host.beginHostApply(exec.getTradeId()));
        Assert.assertNotNull(host.markHostCompleted());
        guest.getTradeLog().record(exec.getTradeId(), CoopTradeLog.Phase.HOST_COMMITTED, 2_000L);

        final CoopTradeState.TimeoutOutcome outcome =
                guest.expireGuestAckIfNeeded(1_000L + 20_000L, 15_000L);
        Assert.assertEquals(outcome, CoopTradeState.TimeoutOutcome.ALREADY_COMPLETE);
        Assert.assertFalse(guest.rollbackGuestApply(guestBag));
        Assert.assertEquals(guestBag.getGold(), 42);
        Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.COMPLETED);
    }

    @Test
    public void staleConfirmWithMismatchedVersionsIgnored() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(30);
        final CoopTradeState host = openTrade(bag, bag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(4, null, null, null));
        final int hv = host.getHostOfferVersion();
        final int gv = host.getGuestOfferVersion();

        // Peer offer version wrong → ignored.
        Assert.assertNull(host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.HOST, true, hv, gv + 1), true));
        Assert.assertFalse(host.isLocalConfirmed());

        // Mine version wrong → ignored.
        Assert.assertNull(host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.HOST, true, hv + 1, gv), true));

        // Both match → accepted.
        Assert.assertNotNull(host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.HOST, true, hv, gv), true));
        Assert.assertTrue(host.isLocalConfirmed());
    }

    @Test
    public void overflowRestoredByRollback() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setItemCapacity(1);
        bag.setItem("Sword", 1);
        bag.setGold(10);
        // Push something into Overflow.
        bag.addItem("Shield", 1);
        Assert.assertEquals(bag.overflowCount(), 1);
        Assert.assertEquals(bag.getOverflowItems().get(0), "Shield");

        final CoopTradeBag.Snapshot snap = bag.snapshot();
        // Mutate: clear overflow by "receiving" into bag capacity somehow — add gold and another overflow.
        bag.addGold(5);
        bag.addItem("Helm", 1);
        Assert.assertEquals(bag.getGold(), 15);
        Assert.assertEquals(bag.overflowCount(), 2);

        bag.restore(snap);
        Assert.assertEquals(bag.getGold(), 10);
        Assert.assertEquals(bag.overflowCount(), 1);
        Assert.assertEquals(bag.getOverflowItems().get(0), "Shield");
        Assert.assertEquals(bag.getItemCount("Sword"), 1);
    }

    @Test
    public void receiverSideGoldOverflowRefused() {
        final CoopTradeBag.Simple giver = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple receiver = new CoopTradeBag.Simple();
        giver.setGold(100);
        receiver.setGold(Integer.MAX_VALUE - 3);
        final CoopTradeOffer give = new CoopTradeOffer(10, null, null, null);

        // Giver ownership is fine; receiver would overflow.
        Assert.assertTrue(CoopTradeValidator.validate(give, giver).ok());
        Assert.assertFalse(CoopTradeValidator.validateReceiverGold(give, receiver).ok());

        final CoopTradeState host = openTrade(giver, receiver);
        setOffer(host, CoopTradeRole.HOST, give);
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(1, null, null, null));
        final int hv = host.getHostOfferVersion();
        final int gv = host.getGuestOfferVersion();
        host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.HOST, true, hv, gv), true);
        final Object result = host.acceptConfirm(new CoopTradeConfirmEvent(
                host.getTradeId(), CoopTradeRole.GUEST, true, gv, hv), true);
        Assert.assertTrue(result instanceof CoopTradeCancelEvent, String.valueOf(result));
        Assert.assertEquals(giver.getGold(), 100);
        Assert.assertEquals(receiver.getGold(), Integer.MAX_VALUE - 3);
    }

    @Test
    public void cancelDuringHostApplyingIsRejected() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        host.receiveGuestAck(new CoopTradeAckEvent(exec.getTradeId(), CoopTradeRole.GUEST, true, ""));

        final CoopTradeExecuteEvent claimed = host.beginHostApply(exec.getTradeId());
        Assert.assertNotNull(claimed);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.HOST_APPLYING);

        Assert.assertNull(host.cancel("race cancel"));
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.HOST_APPLYING);
        Assert.assertNotNull(host.getPendingExecute());

        Assert.assertTrue(CoopTradeApply.applyLocal(
                hostBag, claimed.getHostOffer(), claimed.getGuestOffer(), hostBag.snapshot()).applied);
        Assert.assertNotNull(host.markHostCompleted());
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.COMPLETED);
        Assert.assertEquals(hostBag.getGold(), 38);
    }

    @Test
    public void abortHostApplyReleasesHostApplying() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        host.receiveGuestAck(new CoopTradeAckEvent(exec.getTradeId(), CoopTradeRole.GUEST, true, ""));
        Assert.assertNotNull(host.beginHostApply(exec.getTradeId()));
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.HOST_APPLYING);

        final CoopTradeCancelEvent abort = host.abortHostApply("host apply failed");
        Assert.assertNotNull(abort);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.CANCELLED);
        Assert.assertNull(host.getPendingExecute());
        Assert.assertNull(host.beginHostApply(exec.getTradeId()));
    }

    @Test
    public void sessionEndTeardownDoesNotRollbackAfterAck() {
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        guestBag.setGold(40);
        final CoopTradeState host = new CoopTradeState();
        final CoopTradeInviteEvent inv = host.beginInvite("Host", 20, true);
        final CoopTradeState g = new CoopTradeState();
        g.receiveInvite(inv, false);
        host.applyPeerResponse(g.respondInvite(true), true);
        g.applyPeerResponse(new CoopTradeResponseEvent(inv.getInviteId(), true), false);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        g.receiveExecute(exec, false);
        Assert.assertNotNull(g.claimGuestApply(exec.getTradeId()));
        Assert.assertTrue(CoopTradeApply.applyLocal(
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), guestBag.snapshot()).applied);
        g.markGuestApplied(true, "", 1L);
        Assert.assertEquals(guestBag.getGold(), 42);

        // Teardown after ack: no rollback permission; apply kept.
        g.onTeardown();
        Assert.assertFalse(g.isGuestRollbackPermitted());
        Assert.assertFalse(g.rollbackGuestApply(guestBag));
        Assert.assertEquals(guestBag.getGold(), 42);
        g.reset();
        Assert.assertEquals(g.getStatus(), CoopTradeState.Status.IDLE);
    }

    @Test
    public void hostAssignedTradeIdsDoNotCollideAcrossProcesses() {
        final java.util.HashSet<Long> ids = new java.util.HashSet<>();
        for (int i = 0; i < 64; i++) {
            final CoopTradeState a = new CoopTradeState();
            final CoopTradeState b = new CoopTradeState();
            final CoopTradeInviteEvent ia = a.beginInvite("A", 20, true);
            final CoopTradeInviteEvent ib = b.beginInvite("B", 20, true);
            Assert.assertNotNull(ia);
            Assert.assertNotNull(ib);
            Assert.assertNotEquals(ia.getInviteId(), 0L);
            Assert.assertNotEquals(ib.getInviteId(), 0L);
            Assert.assertNotEquals(ia.getInviteId(), ib.getInviteId());
            Assert.assertTrue(ids.add(ia.getInviteId()));
            Assert.assertTrue(ids.add(ib.getInviteId()));
        }
    }

    @Test
    public void cancelRefusedAfterExecute() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(20);
        final CoopTradeState host = openTrade(bag, bag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(1, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(1, null, null, null));
        bothConfirm(host);
        Assert.assertFalse(host.isCancelAllowed());
        Assert.assertNull(host.cancel("nope"));
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.WAITING_GUEST_ACK);
    }

    @Test
    public void claimBeforeGlApplyBlocksStaleQueuedApply() {
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        guestBag.setGold(40);
        final CoopTradeState host = new CoopTradeState();
        final CoopTradeInviteEvent inv = host.beginInvite("Host", 20, true);
        final CoopTradeState guest = new CoopTradeState();
        guest.receiveInvite(inv, false);
        host.applyPeerResponse(guest.respondInvite(true), true);
        guest.applyPeerResponse(new CoopTradeResponseEvent(inv.getInviteId(), true), false);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        guest.receiveExecute(exec, false);

        // Disconnect before claim — no claim, queued apply must not mutate.
        guest.onDisconnect();
        Assert.assertNull(guest.claimGuestApply(exec.getTradeId()));
        Assert.assertFalse(guest.isGuestApplyClaimed(exec.getTradeId()));
        Assert.assertEquals(guestBag.getGold(), 40);
    }

    @Test
    public void tradeIdMismatchIgnoredAtEntryPoints() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        guestBag.setGold(40);
        final CoopTradeState host = openTrade(hostBag, guestBag);
        setOffer(host, CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        setOffer(host, CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        final CoopTradeExecuteEvent exec = bothConfirm(host);
        host.receiveGuestAck(new CoopTradeAckEvent(exec.getTradeId(), CoopTradeRole.GUEST, true, ""));

        Assert.assertNull(host.beginHostApply(exec.getTradeId() + 1));
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.WAITING_GUEST_ACK);

        Assert.assertEquals(host.applyReconcile(new CoopTradeReconcileEvent(
                exec.getTradeId() + 99, CoopTradeRole.GUEST, CoopTradeLog.Phase.GUEST_APPLIED, false), 1L),
                CoopTradeLog.ReconcileAction.NONE);

        final CoopTradeState guest = new CoopTradeState();
        guest.receiveInvite(new CoopTradeInviteEvent(exec.getTradeId(), "H", 20), false);
        guest.respondInvite(true);
        guest.receiveExecute(exec, false);
        Assert.assertNotNull(guest.claimGuestApply(exec.getTradeId()));
        Assert.assertTrue(CoopTradeApply.applyLocal(
                guestBag, exec.getGuestOffer(), exec.getHostOffer(), guestBag.snapshot()).applied);
        guest.markGuestApplied(true, "", 1L);
        Assert.assertFalse(guest.receiveHostComplete(
                new CoopTradeAckEvent(exec.getTradeId() + 1, CoopTradeRole.HOST, true, "complete")));
        Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.GUEST_APPLIED);
    }

    @Test
    public void tradeLogTiedToSlotRefusesWrongCharacter() throws Exception {
        final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tr1-log");
        final java.nio.file.Path file = dir.resolve("trade.tradelog");
        final CoopTradeLog logA = new CoopTradeLog();
        logA.bindSlot("Alice");
        logA.setPersistPath(file);
        logA.record(7L, CoopTradeLog.Phase.GUEST_APPLIED, 1L,
                new CoopTradeOffer(5, null, null, null),
                new CoopTradeOffer(3, null, null, null));
        Assert.assertNotNull(logA.get(7L));

        final CoopTradeLog logB = new CoopTradeLog();
        logB.bindSlot("Bob");
        logB.setPersistPath(file);
        logB.load();
        Assert.assertNull(logB.get(7L)); // wrong slot — refused
    }

    @Test
    public void forwardReplayFromPersistedLog() throws Exception {
        final java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("tr1-replay");
        final java.nio.file.Path file = dir.resolve("trade.tradelog");
        final CoopTradeOffer hostOffer = new CoopTradeOffer(5, null, null, null);
        final CoopTradeOffer guestOffer = new CoopTradeOffer(3, null, null, null);
        final CoopTradeLog log = new CoopTradeLog();
        log.bindSlot("Hero");
        log.setPersistPath(file);
        log.record(99L, CoopTradeLog.Phase.EXECUTED, 1L, hostOffer, guestOffer);
        log.record(99L, CoopTradeLog.Phase.GUEST_APPLIED, 2L);

        final CoopTradeLog reloaded = new CoopTradeLog();
        reloaded.bindSlot("Hero");
        reloaded.setPersistPath(file);
        reloaded.load();
        final CoopTradeLog.Entry e = reloaded.get(99L);
        Assert.assertNotNull(e);
        Assert.assertEquals(e.phase, CoopTradeLog.Phase.GUEST_APPLIED);
        Assert.assertEquals(e.hostOffer.getGold(), 5);
        Assert.assertEquals(e.guestOffer.getGold(), 3);

        // Forward replay onto a host bag that never applied.
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        hostBag.setGold(40);
        Assert.assertTrue(CoopTradeApply.applyLocalIdempotent(
                99L, CoopTradeRole.HOST, reloaded, hostBag, hostOffer, guestOffer,
                hostBag.snapshot()).applied);
        Assert.assertEquals(hostBag.getGold(), 40 - 5 + 3);
    }

    @Test
    public void wireOfferVersionsUsedNotLocalCounters() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(20);
        final CoopTradeState host = openTrade(bag, bag);
        // Wire says version 5 even though local is 0.
        Assert.assertNotNull(host.acceptOffer(new CoopTradeOfferEvent(
                host.getTradeId(), CoopTradeRole.HOST, new CoopTradeOffer(2, null, null, null), 5)));
        Assert.assertEquals(host.getHostOfferVersion(), 5);
        // Wire version must strictly advance — equal/older rejected.
        Assert.assertNull(host.acceptOffer(new CoopTradeOfferEvent(
                host.getTradeId(), CoopTradeRole.HOST, new CoopTradeOffer(3, null, null, null), 5)));
        Assert.assertNull(host.acceptOffer(new CoopTradeOfferEvent(
                host.getTradeId(), CoopTradeRole.HOST, new CoopTradeOffer(3, null, null, null), 4)));
        Assert.assertNotNull(host.acceptOffer(new CoopTradeOfferEvent(
                host.getTradeId(), CoopTradeRole.HOST, new CoopTradeOffer(3, null, null, null), 6)));
        Assert.assertEquals(host.getHostOfferVersion(), 6);
    }

    @Test
    public void lineOnlyRollbackPreservesUnrelatedChanges() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(40);
        bag.setItem("Potion", 2);
        bag.setMaterial("oak", 5);
        final CoopTradeOffer give = new CoopTradeOffer(3, null,
                java.util.Collections.singletonList(new CoopTradeOffer.Line("Potion", 1, 2)), null);
        final CoopTradeOffer recv = new CoopTradeOffer(5,
                java.util.Collections.singletonList(new CoopTradeOffer.Line("iron", 2, 2)), null, null);

        Assert.assertTrue(CoopTradeApply.applyLocal(bag, give, recv, bag.snapshot()).applied);
        Assert.assertEquals(bag.getGold(), 42);
        Assert.assertEquals(bag.getItemCount("Potion"), 1);
        Assert.assertEquals(bag.getMaterial("iron"), 2);

        // Unrelated change after the trade apply.
        bag.setMaterial("oak", 9);
        bag.addGold(7); // now 49
        bag.setItem("Boots", 1);

        Assert.assertTrue(CoopTradeApply.reverseLocal(bag, give, recv).applied);
        // Trade lines reversed; unrelated oak/Boots/extra gold kept relative to reverse.
        Assert.assertEquals(bag.getGold(), 49 - 5 + 3); // undo recv 5, restore give 3
        Assert.assertEquals(bag.getItemCount("Potion"), 2);
        Assert.assertEquals(bag.getMaterial("iron"), 0);
        Assert.assertEquals(bag.getMaterial("oak"), 9);
        Assert.assertEquals(bag.getItemCount("Boots"), 1);
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
