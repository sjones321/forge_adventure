package forge.gamemodes.net.coop;

import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
import forge.gamemodes.net.event.coop.CoopTradeRequestEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Collections;

/**
 * Headless TR1 protocol unit coverage (wire allowlist, id minting, cancel rules,
 * hostile reconcile). Dual-bag Netty+GL+AdventurePlayer E2E lives in
 * {@code forge-gui-mobile/test/.../CoopTradeEscrowE2ETest}.
 */
public class CoopTradeTest {

    @Test
    public void protocolVersionRemainsEightUntilMv2Merges() {
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 8);
        Assert.assertNotNull(new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION,
                "b", "c", "p", "h", "sess"));
    }

    @Test
    public void wireClassFilterAllowsEscrowEvents() {
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTradeRequestEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeLog$Phase"));
    }

    @Test
    public void hostMintsSecureRandomIdGuestRequest() {
        final CoopTradeLog log = new CoopTradeLog();
        final CoopTradeState host = new CoopTradeState(new CoopRateLimiter(100, 1), log);
        final CoopTradeState guest = new CoopTradeState(new CoopRateLimiter(100, 1), new CoopTradeLog());
        final CoopTradeRequestEvent req = guest.beginRequest("Guest");
        Assert.assertNotNull(req);
        final CoopTradeInviteEvent invite = host.acceptRequest(req, "Host", 30, 1L);
        Assert.assertNotNull(invite);
        Assert.assertTrue(invite.getInviteId() != 0L);
        Assert.assertTrue(guest.receiveInvite(invite, false));
        Assert.assertEquals(guest.getTradeId(), invite.getInviteId());
    }

    @Test
    public void duplicateIdInLogRejected() {
        final CoopTradeLog log = new CoopTradeLog();
        final long id = CoopTradeIds.next();
        log.record(id, CoopTradeLog.Phase.COMPLETED, 1L);
        final CoopTradeState guest = new CoopTradeState(new CoopRateLimiter(100, 1), log);
        Assert.assertFalse(guest.receiveInvite(new CoopTradeInviteEvent(id, "Host", 30), false));
    }

    @Test
    public void cancelRefusedAfterBothConfirm() {
        final CoopTradeBag.Simple hostBag = new CoopTradeBag.Simple();
        hostBag.setGold(50);
        final CoopTradeBag.Simple guestBag = new CoopTradeBag.Simple();
        guestBag.setGold(50);
        final CoopTradeState host = new CoopTradeState(new CoopRateLimiter(100, 1), new CoopTradeLog());
        host.setBagLookup(r -> r == CoopTradeRole.HOST ? hostBag : guestBag);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 30);
        host.applyPeerResponse(
                new forge.gamemodes.net.event.coop.CoopTradeResponseEvent(invite.getInviteId(), true),
                true);
        final long id = host.getTradeId();
        host.acceptOffer(new CoopTradeOfferEvent(id, CoopTradeRole.HOST,
                new CoopTradeOffer(5, null, null, null), 1));
        host.acceptOffer(new CoopTradeOfferEvent(id, CoopTradeRole.GUEST,
                new CoopTradeOffer(5, null, null, null), 1));
        host.acceptConfirm(new CoopTradeConfirmEvent(id, CoopTradeRole.HOST, true, 1, 1));
        Assert.assertTrue(host.isCancelAllowed());
        final CoopTradeState.ConfirmResult both = host.acceptConfirm(
                new CoopTradeConfirmEvent(id, CoopTradeRole.GUEST, true, 1, 1));
        Assert.assertEquals(both, CoopTradeState.ConfirmResult.BEGIN_ESCROW);
        Assert.assertFalse(host.isCancelAllowed());
        Assert.assertNull(host.cancel("nope"));
    }

    @Test
    public void hostileCompletedIgnored() {
        final CoopTradeState st = new CoopTradeState();
        Assert.assertEquals(st.applyReconcile(
                new CoopTradeReconcileEvent(999L, CoopTradeRole.GUEST,
                        CoopTradeLog.Phase.COMPLETED, false), 1L),
                CoopTradeLog.ReconcileAction.IGNORE_HOSTILE);
        Assert.assertFalse(st.receivePeerEscrowed(888L, CoopTradeRole.GUEST));
        Assert.assertFalse(st.receivePeerDelivered(888L, CoopTradeRole.GUEST));
    }

    @Test
    public void tradeLogEncodeDecodeRoundTrip() {
        final CoopTradeLog log = new CoopTradeLog();
        final CoopTradeOffer h = new CoopTradeOffer(7,
                Collections.singletonList(new CoopTradeOffer.Line("oak", 2)), null, null);
        log.record(42L, CoopTradeLog.Phase.ESCROWED, 100L, h, CoopTradeOffer.empty());
        log.record(42L, CoopTradeLog.Phase.DELIVERED, 200L);
        final CoopTradeLog loaded = new CoopTradeLog();
        loaded.decode(log.encode());
        Assert.assertTrue(loaded.hasDelivered(42L));
        Assert.assertEquals(loaded.get(42L).hostOffer.getGold(), 7);
    }

    @Test
    public void nullSafeWireFieldsRejected() {
        final CoopTradeState st = new CoopTradeState();
        Assert.assertNull(st.beginRequest(null));
        Assert.assertNull(st.beginInvite(null, 30));
        Assert.assertFalse(st.receiveInvite(null, false));
        Assert.assertNull(st.acceptOffer(null));
        Assert.assertEquals(st.acceptConfirm(null), CoopTradeState.ConfirmResult.IGNORED);
    }

    @Test
    public void reconcileRefundWhenPeerNeverEscrowed() {
        final CoopTradeBag.Simple bag = new CoopTradeBag.Simple();
        bag.setGold(30);
        final CoopTradeLog log = new CoopTradeLog();
        final CoopTradeState host = new CoopTradeState(new CoopRateLimiter(100, 1), log);
        host.setBagLookup(r -> bag);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 30);
        host.applyPeerResponse(
                new forge.gamemodes.net.event.coop.CoopTradeResponseEvent(invite.getInviteId(), true),
                true);
        final long id = host.getTradeId();
        host.acceptOffer(new CoopTradeOfferEvent(id, CoopTradeRole.HOST,
                new CoopTradeOffer(10, null, null, null), 1));
        host.acceptOffer(new CoopTradeOfferEvent(id, CoopTradeRole.GUEST,
                new CoopTradeOffer(5, null, null, null), 1));
        host.acceptConfirm(new CoopTradeConfirmEvent(id, CoopTradeRole.HOST, true, 1, 1));
        Assert.assertEquals(host.acceptConfirm(
                new CoopTradeConfirmEvent(id, CoopTradeRole.GUEST, true, 1, 1)),
                CoopTradeState.ConfirmResult.BEGIN_ESCROW);
        Assert.assertTrue(CoopTradeApply.escrow(bag, host.getLocalOffer(), bag.snapshot()).applied);
        host.markEscrowed(1_000L);
        Assert.assertEquals(bag.getGold(), 20);
        Assert.assertEquals(host.applyReconcile(
                new CoopTradeReconcileEvent(id, CoopTradeRole.GUEST, CoopTradeLog.Phase.NONE, false),
                2_000L), CoopTradeLog.ReconcileAction.REFUND);
        Assert.assertTrue(CoopTradeApply.refundEscrow(bag, host.getLocalOffer()).applied);
        Assert.assertTrue(host.markRefunded(2_000L));
        Assert.assertEquals(bag.getGold(), 30);
    }
}
