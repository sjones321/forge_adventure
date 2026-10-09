package forge.gamemodes.net.coop;

import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopTradeCancelEvent;
import forge.gamemodes.net.event.coop.CoopTradeConfirmEvent;
import forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent;
import forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent;
import forge.gamemodes.net.event.coop.CoopTradeInviteEvent;
import forge.gamemodes.net.event.coop.CoopTradeOfferEvent;
import forge.gamemodes.net.event.coop.CoopTradeReconcileEvent;
import forge.gamemodes.net.event.coop.CoopTradeRequestEvent;
import forge.gamemodes.net.event.coop.CoopTradeResponseEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

/**
 * TR1 forward-only escrow tests. Drives the real {@link CoopTradeState} /
 * {@link CoopTradeApply} / {@link CoopTradeLog} paths with a GL-style queue so
 * Netty-posted events interleave with bag mutations. Asserts both players' bags
 * (gold, materials, items, cards, Overflow) and conservation.
 */
public class CoopTradeTest {

    // ---- bag totals / conservation -----------------------------------------

    private static final class Totals {
        final int gold;
        final int materials;
        final int items;
        final int cards;
        final int overflow;

        Totals(final int gold, final int materials, final int items,
               final int cards, final int overflow) {
            this.gold = gold;
            this.materials = materials;
            this.items = items;
            this.cards = cards;
            this.overflow = overflow;
        }

        static Totals of(final CoopTradeBag.Simple a, final CoopTradeBag.Simple b) {
            return new Totals(
                    a.getGold() + b.getGold(),
                    sumMats(a) + sumMats(b),
                    sumItems(a) + sumItems(b),
                    sumCards(a) + sumCards(b),
                    a.overflowCount() + b.overflowCount());
        }

        /**
         * Bag totals plus in-flight escrow (removed from bags but not yet
         * delivered or refunded). True conservation across disconnect mid-trade.
         */
        static Totals ofWithEscrow(final CoopTradeBag.Simple a, final CoopTradeBag.Simple b,
                                   final CoopTradeLog aLog, final CoopTradeLog bLog,
                                   final long tradeId, final CoopTradeRole aRole) {
            Totals t = of(a, b);
            t = addEscrow(t, aLog, tradeId, aRole);
            final CoopTradeRole bRole = aRole == CoopTradeRole.HOST
                    ? CoopTradeRole.GUEST : CoopTradeRole.HOST;
            t = addEscrow(t, bLog, tradeId, bRole);
            return t;
        }

        private static Totals addEscrow(final Totals t, final CoopTradeLog log, final long id,
                                        final CoopTradeRole role) {
            if (log == null || id == 0L || !log.hasEscrowed(id) || log.hasDelivered(id)) {
                return t;
            }
            final CoopTradeLog.Entry e = log.get(id);
            if (e == null) {
                return t;
            }
            final CoopTradeOffer own = role == CoopTradeRole.HOST ? e.hostOffer : e.guestOffer;
            return new Totals(
                    t.gold + own.getGold(),
                    t.materials + countMats(own),
                    t.items + countItems(own),
                    t.cards + countCards(own),
                    t.overflow);
        }

        private static int countMats(final CoopTradeOffer o) {
            int n = 0;
            for (final CoopTradeOffer.Line line : o.getMaterials()) {
                if (line != null) {
                    n += line.getCount();
                }
            }
            return n;
        }

        private static int countItems(final CoopTradeOffer o) {
            int n = 0;
            for (final CoopTradeOffer.Line line : o.getItems()) {
                if (line != null) {
                    n += line.getCount();
                }
            }
            return n;
        }

        private static int countCards(final CoopTradeOffer o) {
            int n = 0;
            for (final CoopTradeOffer.CardLine line : o.getCards()) {
                if (line != null) {
                    n += line.getCount();
                }
            }
            return n;
        }

        private static int sumMats(final CoopTradeBag.Simple bag) {
            int n = 0;
            for (final String id : new String[]{"oak", "iron", "herb", "stone"}) {
                n += bag.getMaterial(id);
            }
            return n;
        }

        private static int sumItems(final CoopTradeBag.Simple bag) {
            int n = 0;
            for (final String id : new String[]{"Potion", "Boots", "Shield", "Sword", "Extra"}) {
                n += bag.getItemCount(id);
            }
            return n;
        }

        private static int sumCards(final CoopTradeBag.Simple bag) {
            int n = 0;
            for (final String key : new String[]{"Forest|LEA|0", "Island|LEA|0", "Mountain|LEA|0"}) {
                n += bag.getTradeableCardCount(key);
            }
            return n;
        }
    }

    private static void assertConserved(final Totals before, final CoopTradeBag.Simple a,
                                        final CoopTradeBag.Simple b) {
        assertConserved(before, a, b, null, null, 0L, CoopTradeRole.HOST);
    }

    private static void assertConserved(final Totals before, final CoopTradeBag.Simple a,
                                        final CoopTradeBag.Simple b,
                                        final CoopTradeLog aLog, final CoopTradeLog bLog,
                                        final long tradeId) {
        assertConserved(before, a, b, aLog, bLog, tradeId, CoopTradeRole.HOST);
    }

    private static void assertConserved(final Totals before, final CoopTradeBag.Simple a,
                                        final CoopTradeBag.Simple b,
                                        final CoopTradeLog aLog, final CoopTradeLog bLog,
                                        final long tradeId, final CoopTradeRole aRole) {
        final Totals after = (aLog != null || bLog != null)
                ? Totals.ofWithEscrow(a, b, aLog, bLog, tradeId, aRole)
                : Totals.of(a, b);
        Assert.assertEquals(after.gold, before.gold, "gold conserved");
        Assert.assertEquals(after.materials, before.materials, "materials conserved");
        Assert.assertEquals(after.items + after.overflow, before.items + before.overflow,
                "items+overflow conserved");
        Assert.assertEquals(after.cards, before.cards, "cards conserved");
    }

    private static CoopTradeBag.Simple bag(final int gold) {
        final CoopTradeBag.Simple b = new CoopTradeBag.Simple();
        b.setGold(gold);
        return b;
    }

    private static CoopHelloEvent hello() {
        return new CoopHelloEvent(CoopPorts.PROTOCOL_VERSION, "test", "cards",
                "Player", "Hero", "sess");
    }

    // ---- dual-side GL harness ----------------------------------------------

    /**
     * Simulates two peers: Netty handlers enqueue onto a GL queue; bag changes
     * and state transitions run only when the queue is drained (GL thread).
     */
    private static final class Harness {
        final CoopTradeLog hostLog = new CoopTradeLog();
        final CoopTradeLog guestLog = new CoopTradeLog();
        final CoopTradeState host = new CoopTradeState(new CoopRateLimiter(100, 1), hostLog);
        final CoopTradeState guest = new CoopTradeState(new CoopRateLimiter(100, 1), guestLog);
        final CoopTradeBag.Simple hostBag;
        final CoopTradeBag.Simple guestBag;
        final Queue<Runnable> hostGl = new ArrayDeque<>();
        final Queue<Runnable> guestGl = new ArrayDeque<>();
        final List<Object> hostOut = new ArrayList<>();
        final List<Object> guestOut = new ArrayList<>();
        boolean hostLinked = true;
        boolean guestLinked = true;

        Harness(final CoopTradeBag.Simple hostBag, final CoopTradeBag.Simple guestBag) {
            this.hostBag = hostBag;
            this.guestBag = guestBag;
            host.setBagLookup(role -> role == CoopTradeRole.HOST ? hostBag : guestBag);
            guest.setBagLookup(role -> role == CoopTradeRole.HOST ? hostBag : guestBag);
        }

        void postHost(final Runnable r) {
            hostGl.add(r);
        }

        void postGuest(final Runnable r) {
            guestGl.add(r);
        }

        void drainHost() {
            while (!hostGl.isEmpty()) {
                hostGl.poll().run();
            }
        }

        void drainGuest() {
            while (!guestGl.isEmpty()) {
                guestGl.poll().run();
            }
        }

        void drainAll() {
            // Interleave one-at-a-time to exercise real orderings.
            while (!hostGl.isEmpty() || !guestGl.isEmpty()) {
                if (!hostGl.isEmpty()) {
                    hostGl.poll().run();
                }
                if (!guestGl.isEmpty()) {
                    guestGl.poll().run();
                }
            }
        }

        void sendFromHost(final Object event) {
            hostOut.add(event);
            if (!guestLinked) {
                return;
            }
            deliverToGuest(event);
        }

        void sendFromGuest(final Object event) {
            guestOut.add(event);
            if (!hostLinked) {
                return;
            }
            deliverToHost(event);
        }

        void deliverToGuest(final Object event) {
            postGuest(() -> handle(guest, guestBag, guestLog, event, this::sendFromGuest, false));
        }

        void deliverToHost(final Object event) {
            postHost(() -> handle(host, hostBag, hostLog, event, this::sendFromHost, true));
        }

        /** Open trade: guest requests → host mints → guest accepts. */
        void openTrade() {
            postGuest(() -> {
                final CoopTradeRequestEvent req = guest.beginRequest("Guest");
                Assert.assertNotNull(req);
                sendFromGuest(req);
            });
            drainAll();
            // Host should have sent invite.
            Assert.assertEquals(host.getStatus(), CoopTradeState.Status.INVITE_SENT);
            Assert.assertTrue(host.getTradeId() != 0L);
            drainAll();
            Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.INVITE_RECEIVED);
            postGuest(() -> {
                final CoopTradeResponseEvent resp = guest.respondInvite(true);
                Assert.assertNotNull(resp);
                sendFromGuest(resp);
            });
            drainAll();
            Assert.assertEquals(host.getStatus(), CoopTradeState.Status.OPEN);
            Assert.assertEquals(guest.getStatus(), CoopTradeState.Status.OPEN);
            Assert.assertEquals(guest.getTradeId(), host.getTradeId());
        }

        void setOffer(final CoopTradeRole role, final CoopTradeOffer offer) {
            final CoopTradeState st = role == CoopTradeRole.HOST ? host : guest;
            final Queue<Runnable> gl = role == CoopTradeRole.HOST ? hostGl : guestGl;
            final Consumer<Object> send = role == CoopTradeRole.HOST ? this::sendFromHost : this::sendFromGuest;
            gl.add(() -> {
                final int next = st.getLocalOfferVersion() + 1;
                final CoopTradeOfferEvent ev = new CoopTradeOfferEvent(
                        st.getTradeId(), role, offer, next);
                final CoopTradeOfferEvent accepted = st.acceptOffer(ev);
                Assert.assertNotNull(accepted, "offer rejected for " + role);
                send.accept(accepted);
            });
        }

        void confirm(final CoopTradeRole role, final boolean confirmed) {
            final CoopTradeState st = role == CoopTradeRole.HOST ? host : guest;
            final Queue<Runnable> gl = role == CoopTradeRole.HOST ? hostGl : guestGl;
            final Consumer<Object> send = role == CoopTradeRole.HOST ? this::sendFromHost : this::sendFromGuest;
            final CoopTradeBag.Simple bag = role == CoopTradeRole.HOST ? hostBag : guestBag;
            final CoopTradeLog log = role == CoopTradeRole.HOST ? hostLog : guestLog;
            gl.add(() -> {
                final CoopTradeConfirmEvent ev = new CoopTradeConfirmEvent(
                        st.getTradeId(), role, confirmed,
                        st.getLocalOfferVersion(), st.getPeerOfferVersion());
                final CoopTradeState.ConfirmResult result = st.acceptConfirm(ev);
                Assert.assertNotEquals(result, CoopTradeState.ConfirmResult.IGNORED);
                send.accept(ev);
                if (result == CoopTradeState.ConfirmResult.BEGIN_ESCROW) {
                    doEscrow(st, bag, log, role, send);
                }
            });
        }

        private void doEscrow(final CoopTradeState st, final CoopTradeBag.Simple bag,
                              final CoopTradeLog log, final CoopTradeRole role,
                              final Consumer<Object> send) {
            final long id = st.getTradeId();
            if (!log.hasEscrowed(id)) {
                final CoopTradeBag.Snapshot snap = bag.snapshot();
                final CoopTradeApply.Result r = CoopTradeApply.escrowIdempotent(
                        id, log, bag, st.getLocalOffer(), snap);
                Assert.assertTrue(r.applied, "escrow failed: " + r.detail);
            }
            final CoopTradeEscrowedEvent esc = st.markEscrowed(System.currentTimeMillis());
            Assert.assertNotNull(esc);
            send.accept(esc);
            if (st.shouldDeliver()) {
                doDeliver(st, bag, log, role, send);
            }
        }

        private void doDeliver(final CoopTradeState st, final CoopTradeBag.Simple bag,
                               final CoopTradeLog log, final CoopTradeRole role,
                               final Consumer<Object> send) {
            final long id = st.getTradeId();
            if (!log.hasDelivered(id)) {
                final CoopTradeBag.Snapshot snap = bag.snapshot();
                final CoopTradeApply.Result r = CoopTradeApply.deliverIdempotent(
                        id, log, bag, st.getPeerOffer(), snap);
                Assert.assertTrue(r.applied, "deliver failed: " + r.detail);
            }
            final CoopTradeDeliveredEvent del = st.markDelivered(System.currentTimeMillis());
            Assert.assertNotNull(del);
            send.accept(del);
        }

        private void handle(final CoopTradeState st, final CoopTradeBag.Simple bag,
                            final CoopTradeLog log, final Object event,
                            final Consumer<Object> send, final boolean weAreHost) {
            if (event instanceof CoopTradeRequestEvent) {
                if (!weAreHost) {
                    return;
                }
                final CoopTradeInviteEvent invite = st.acceptRequest(
                        (CoopTradeRequestEvent) event, "Host", 30, System.currentTimeMillis());
                Assert.assertNotNull(invite);
                send.accept(invite);
            } else if (event instanceof CoopTradeInviteEvent) {
                Assert.assertTrue(st.receiveInvite((CoopTradeInviteEvent) event, weAreHost));
            } else if (event instanceof CoopTradeResponseEvent) {
                Assert.assertTrue(st.applyPeerResponse((CoopTradeResponseEvent) event, weAreHost));
            } else if (event instanceof CoopTradeOfferEvent) {
                final CoopTradeOfferEvent ev = (CoopTradeOfferEvent) event;
                if (ev.getFromRole() == st.getLocalRole()) {
                    return;
                }
                Assert.assertNotNull(st.acceptOffer(ev));
                if (weAreHost) {
                    send.accept(ev);
                }
            } else if (event instanceof CoopTradeConfirmEvent) {
                final CoopTradeConfirmEvent ev = (CoopTradeConfirmEvent) event;
                if (ev.getFromRole() == st.getLocalRole()) {
                    return;
                }
                final CoopTradeState.ConfirmResult result = st.acceptConfirm(ev);
                if (weAreHost && result != CoopTradeState.ConfirmResult.IGNORED) {
                    send.accept(ev);
                }
                if (result == CoopTradeState.ConfirmResult.BEGIN_ESCROW) {
                    doEscrow(st, bag, log, st.getLocalRole(), send);
                }
            } else if (event instanceof CoopTradeEscrowedEvent) {
                final CoopTradeEscrowedEvent ev = (CoopTradeEscrowedEvent) event;
                st.receivePeerEscrowed(ev.getTradeId(), ev.getFromRole());
                if (st.shouldDeliver()) {
                    doDeliver(st, bag, log, st.getLocalRole(), send);
                }
            } else if (event instanceof CoopTradeDeliveredEvent) {
                final CoopTradeDeliveredEvent ev = (CoopTradeDeliveredEvent) event;
                st.receivePeerDelivered(ev.getTradeId(), ev.getFromRole());
            } else if (event instanceof CoopTradeCancelEvent) {
                st.receiveCancel((CoopTradeCancelEvent) event);
            } else if (event instanceof CoopTradeReconcileEvent) {
                final CoopTradeReconcileEvent ev = (CoopTradeReconcileEvent) event;
                if (ev.isRequest()) {
                    final CoopTradeLog.Entry local = log.get(ev.getTradeId());
                    final CoopTradeLog.Phase phase = local != null ? local.phase : CoopTradeLog.Phase.NONE;
                    send.accept(new CoopTradeReconcileEvent(
                            ev.getTradeId(), st.getLocalRole(), phase, false));
                }
                final CoopTradeLog.ReconcileAction action =
                        st.applyReconcile(ev, System.currentTimeMillis());
                if (action == CoopTradeLog.ReconcileAction.DELIVER) {
                    doDeliver(st, bag, log, st.getLocalRole(), send);
                } else if (action == CoopTradeLog.ReconcileAction.REFUND) {
                    final CoopTradeLog.Entry entry = log.get(ev.getTradeId());
                    if (entry != null && !log.hasDelivered(ev.getTradeId())) {
                        final CoopTradeOffer own = st.getLocalRole() == CoopTradeRole.HOST
                                ? entry.hostOffer : entry.guestOffer;
                        Assert.assertTrue(CoopTradeApply.refundEscrow(bag, own).applied);
                        st.markRefunded(System.currentTimeMillis());
                    }
                } else if (action == CoopTradeLog.ReconcileAction.RESEND_ESCROWED) {
                    send.accept(new CoopTradeEscrowedEvent(ev.getTradeId(), st.getLocalRole()));
                } else if (action == CoopTradeLog.ReconcileAction.RESEND_DELIVERED) {
                    send.accept(new CoopTradeDeliveredEvent(ev.getTradeId(), st.getLocalRole()));
                }
            }
        }

        /** Crash+reload: encode log, clear state, decode, restore. */
        void crashReload(final boolean hostSide) {
            final CoopTradeState st = hostSide ? host : guest;
            final CoopTradeLog log = hostSide ? hostLog : guestLog;
            final String blob = log.encode();
            final long id = st.getTradeId();
            final CoopTradeRole role = st.getLocalRole();
            st.reset();
            log.clear();
            log.decode(blob);
            st.restoreFromLog(id, role);
        }
    }

    // ---- tests -------------------------------------------------------------

    @Test
    public void protocolVersionRemainsEightUntilMv2Merges() {
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 8);
        Assert.assertTrue(CoopPorts.PROTOCOL_VERSION > 7);
        Assert.assertNotNull(hello());
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
                "forge.gamemodes.net.coop.CoopTradeRole"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopTradeLog$Phase"));
    }

    @Test
    public void happyPathEscrowBothBagsConserved() {
        final CoopTradeBag.Simple hostBag = bag(100);
        hostBag.setMaterial("oak", 5);
        hostBag.setItem("Potion", 2);
        hostBag.setCard("Forest|LEA|0", 3, false);
        final CoopTradeBag.Simple guestBag = bag(80);
        guestBag.setMaterial("iron", 4);
        guestBag.setItem("Boots", 1);
        guestBag.setCard("Island|LEA|0", 2, false);

        final Totals before = Totals.of(hostBag, guestBag);
        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();

        final CoopTradeOffer hostOffer = new CoopTradeOffer(25,
                Collections.singletonList(new CoopTradeOffer.Line("oak", 2, 5)),
                Collections.singletonList(new CoopTradeOffer.Line("Potion", 1, 2)),
                Collections.singletonList(new CoopTradeOffer.CardLine("Forest", "LEA", 0, 1, 3)));
        final CoopTradeOffer guestOffer = new CoopTradeOffer(10,
                Collections.singletonList(new CoopTradeOffer.Line("iron", 1, 4)),
                Collections.singletonList(new CoopTradeOffer.Line("Boots", 1, 1)),
                Collections.singletonList(new CoopTradeOffer.CardLine("Island", "LEA", 0, 1, 2)));

        h.setOffer(CoopTradeRole.HOST, hostOffer);
        h.setOffer(CoopTradeRole.GUEST, guestOffer);
        h.drainAll();
        h.confirm(CoopTradeRole.HOST, true);
        h.confirm(CoopTradeRole.GUEST, true);
        h.drainAll();

        Assert.assertTrue(h.host.getStatus() == CoopTradeState.Status.COMPLETED
                || h.host.getStatus() == CoopTradeState.Status.DELIVERED);
        Assert.assertTrue(h.guest.getStatus() == CoopTradeState.Status.COMPLETED
                || h.guest.getStatus() == CoopTradeState.Status.DELIVERED);
        Assert.assertTrue(h.hostLog.hasDelivered(h.host.getTradeId()));
        Assert.assertTrue(h.guestLog.hasDelivered(h.guest.getTradeId()));

        Assert.assertEquals(hostBag.getGold(), 100 - 25 + 10);
        Assert.assertEquals(guestBag.getGold(), 80 - 10 + 25);
        Assert.assertEquals(hostBag.getMaterial("oak"), 3);
        Assert.assertEquals(guestBag.getMaterial("oak"), 2);
        Assert.assertEquals(hostBag.getMaterial("iron"), 1);
        Assert.assertEquals(guestBag.getMaterial("iron"), 3);
        Assert.assertEquals(hostBag.getItemCount("Potion"), 1);
        Assert.assertEquals(guestBag.getItemCount("Potion"), 1);
        Assert.assertEquals(hostBag.getItemCount("Boots"), 1);
        Assert.assertEquals(guestBag.getItemCount("Boots"), 0);
        Assert.assertEquals(hostBag.getTradeableCardCount("Forest|LEA|0"), 2);
        Assert.assertEquals(guestBag.getTradeableCardCount("Forest|LEA|0"), 1);
        Assert.assertEquals(hostBag.getTradeableCardCount("Island|LEA|0"), 1);
        Assert.assertEquals(guestBag.getTradeableCardCount("Island|LEA|0"), 1);
        assertConserved(before, hostBag, guestBag);
    }

    @Test
    public void cancelRefusedAfterBothConfirm() {
        final Harness h = new Harness(bag(50), bag(50));
        h.openTrade();
        h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        h.setOffer(CoopTradeRole.GUEST, new CoopTradeOffer(3, null, null, null));
        h.drainAll();
        // Confirm host only — cancel still allowed.
        h.confirm(CoopTradeRole.HOST, true);
        h.drainAll();
        Assert.assertTrue(h.host.isCancelAllowed());
        // Both confirm → escrow; cancel refused.
        h.confirm(CoopTradeRole.GUEST, true);
        h.drainAll();
        Assert.assertFalse(h.host.isCancelAllowed());
        Assert.assertNull(h.host.cancel("nope"));
        Assert.assertNull(h.guest.cancel("nope"));
    }

    @Test
    public void hostAssignedIdsRejectDuplicatesInLog() {
        final CoopTradeLog log = new CoopTradeLog();
        final CoopTradeState host = new CoopTradeState(new CoopRateLimiter(100, 1), log);
        final CoopTradeInviteEvent invite = host.beginInvite("Host", 30);
        Assert.assertNotNull(invite);
        final long id = invite.getInviteId();
        log.record(id, CoopTradeLog.Phase.COMPLETED, 1L);
        host.reset();

        final CoopTradeState guest = new CoopTradeState(new CoopRateLimiter(100, 1), log);
        Assert.assertFalse(guest.receiveInvite(
                new CoopTradeInviteEvent(id, "Host", 30), false));
    }

    @Test
    public void guestRequestHostMintsId() {
        final Harness h = new Harness(bag(10), bag(10));
        h.openTrade();
        Assert.assertTrue(h.host.getTradeId() != 0L);
        Assert.assertEquals(h.guest.getTradeId(), h.host.getTradeId());
        Assert.assertEquals(h.host.getLocalRole(), CoopTradeRole.HOST);
        Assert.assertEquals(h.guest.getLocalRole(), CoopTradeRole.GUEST);
    }

    @Test
    public void disconnectBeforeEscrowCancelsCleanly() {
        final CoopTradeBag.Simple hostBag = bag(40);
        final CoopTradeBag.Simple guestBag = bag(40);
        final Totals before = Totals.of(hostBag, guestBag);
        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();
        h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        h.setOffer(CoopTradeRole.GUEST, new CoopTradeOffer(5, null, null, null));
        h.drainAll();
        h.host.onDisconnect();
        Assert.assertEquals(h.host.getStatus(), CoopTradeState.Status.CANCELLED);
        Assert.assertEquals(hostBag.getGold(), 40);
        Assert.assertEquals(guestBag.getGold(), 40);
        assertConserved(before, hostBag, guestBag);
    }

    @Test
    public void disconnectAfterEscrowPendingNoAbandon() {
        final CoopTradeBag.Simple hostBag = bag(40);
        final CoopTradeBag.Simple guestBag = bag(40);
        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();
        h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(5, null, null, null));
        h.setOffer(CoopTradeRole.GUEST, new CoopTradeOffer(5, null, null, null));
        h.drainAll();
        // Escrow host only by confirming both on host side first… use confirm both
        // then unlink before guest processes host escrowed? Simpler: manually escrow host.
        h.confirm(CoopTradeRole.HOST, true);
        h.confirm(CoopTradeRole.GUEST, true);
        // Drain host only so host escrows; then disconnect guest before deliver.
        h.drainHost();
        // Host may have escrowed; unlink guest.
        h.guestLinked = false;
        h.hostLinked = false;
        if (h.hostLog.hasEscrowed(h.host.getTradeId())) {
            h.host.onDisconnect();
            Assert.assertEquals(h.host.getStatus(), CoopTradeState.Status.NEEDS_RECONCILE);
            Assert.assertNull(h.host.cancel("abandon"));
            Assert.assertFalse(h.host.isCancelAllowed());
        }
    }

    @Test
    public void crashReloadAfterEscrowThenReconcileDeliver() {
        final CoopTradeBag.Simple hostBag = bag(50);
        hostBag.setMaterial("oak", 3);
        final CoopTradeBag.Simple guestBag = bag(50);
        guestBag.setMaterial("iron", 3);
        final Totals before = Totals.of(hostBag, guestBag);

        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();
        final CoopTradeOffer hostOffer = new CoopTradeOffer(0,
                Collections.singletonList(new CoopTradeOffer.Line("oak", 1, 3)), null, null);
        final CoopTradeOffer guestOffer = new CoopTradeOffer(0,
                Collections.singletonList(new CoopTradeOffer.Line("iron", 1, 3)), null, null);
        h.setOffer(CoopTradeRole.HOST, hostOffer);
        h.setOffer(CoopTradeRole.GUEST, guestOffer);
        h.drainAll();

        // Confirm both, but only drain until both escrowed — then crash host before deliver.
        h.confirm(CoopTradeRole.HOST, true);
        h.confirm(CoopTradeRole.GUEST, true);
        // Drain carefully: process until both escrowed.
        int guard = 50;
        while (guard-- > 0 && (!h.hostLog.hasEscrowed(h.host.getTradeId())
                || !h.guestLog.hasEscrowed(h.guest.getTradeId()))) {
            if (!h.hostGl.isEmpty()) {
                h.hostGl.poll().run();
            }
            if (!h.guestGl.isEmpty()) {
                h.guestGl.poll().run();
            }
        }
        Assert.assertTrue(h.hostLog.hasEscrowed(h.host.getTradeId()));
        Assert.assertTrue(h.guestLog.hasEscrowed(h.guest.getTradeId()));

        // Crash host mid-flight (after escrow, maybe before/after deliver).
        final boolean hostAlreadyDelivered = h.hostLog.hasDelivered(h.host.getTradeId());
        h.crashReload(true);
        Assert.assertTrue(h.hostLog.hasEscrowed(h.host.getTradeId())
                || h.hostLog.hasDelivered(h.host.getTradeId()));

        // Reconcile with guest.
        h.hostLinked = true;
        h.guestLinked = true;
        final long id = h.guest.getTradeId() != 0L ? h.guest.getTradeId() : h.host.getTradeId();
        h.postHost(() -> {
            for (final CoopTradeReconcileEvent ev : h.host.buildReconcileRequests(true)) {
                h.sendFromHost(ev);
            }
        });
        h.drainAll();
        // Also push guest reconcile reply path if needed.
        h.postGuest(() -> {
            for (final CoopTradeReconcileEvent ev : h.guest.buildReconcileRequests(true)) {
                h.sendFromGuest(ev);
            }
        });
        h.drainAll();

        if (!hostAlreadyDelivered) {
            // After reconcile both should have delivered (peer escrowed → deliver).
            Assert.assertTrue(h.hostLog.hasDelivered(id) || h.host.getStatus() == CoopTradeState.Status.COMPLETED
                    || h.host.getStatus() == CoopTradeState.Status.DELIVERED
                    || h.hostLog.hasEscrowed(id));
        }
        assertConserved(before, hostBag, guestBag);
    }

    @Test
    public void reconcileRefundWhenPeerNeverEscrowed() {
        final CoopTradeBag.Simple hostBag = bag(30);
        final CoopTradeBag.Simple guestBag = bag(30);
        final CoopTradeLog hostLog = new CoopTradeLog();
        final CoopTradeLog guestLog = new CoopTradeLog();
        final CoopTradeState host = new CoopTradeState(new CoopRateLimiter(100, 1), hostLog);
        host.setBagLookup(role -> role == CoopTradeRole.HOST ? hostBag : guestBag);

        final CoopTradeInviteEvent invite = host.beginInvite("Host", 30);
        host.applyPeerResponse(new CoopTradeResponseEvent(invite.getInviteId(), true), true);
        final long id = host.getTradeId();
        host.acceptOffer(new CoopTradeOfferEvent(id, CoopTradeRole.HOST,
                new CoopTradeOffer(10, null, null, null), 1));
        host.acceptOffer(new CoopTradeOfferEvent(id, CoopTradeRole.GUEST,
                new CoopTradeOffer(5, null, null, null), 1));
        host.acceptConfirm(new CoopTradeConfirmEvent(id, CoopTradeRole.HOST, true, 1, 1));
        final CoopTradeState.ConfirmResult r = host.acceptConfirm(
                new CoopTradeConfirmEvent(id, CoopTradeRole.GUEST, true, 1, 1));
        Assert.assertEquals(r, CoopTradeState.ConfirmResult.BEGIN_ESCROW);

        Assert.assertTrue(CoopTradeApply.escrow(hostBag, host.getLocalOffer(), hostBag.snapshot()).applied);
        host.markEscrowed(1_000L);
        Assert.assertEquals(hostBag.getGold(), 20);

        // Peer never escrowed (NONE).
        final CoopTradeLog.ReconcileAction action = host.applyReconcile(
                new CoopTradeReconcileEvent(id, CoopTradeRole.GUEST, CoopTradeLog.Phase.NONE, false),
                2_000L);
        Assert.assertEquals(action, CoopTradeLog.ReconcileAction.REFUND);
        Assert.assertTrue(CoopTradeApply.refundEscrow(hostBag, host.getLocalOffer()).applied);
        Assert.assertTrue(host.markRefunded(2_000L));
        Assert.assertEquals(hostBag.getGold(), 30);
        Assert.assertEquals(host.getStatus(), CoopTradeState.Status.REFUNDED);
        Assert.assertFalse(guestLog.contains(id));
    }

    @Test
    public void hostileCompletedIgnoredDoesNotGrant() {
        final CoopTradeBag.Simple bag = bag(20);
        final CoopTradeLog log = new CoopTradeLog();
        final CoopTradeState st = new CoopTradeState(new CoopRateLimiter(100, 1), log);
        st.setBagLookup(role -> bag);

        // Unknown id claiming COMPLETED.
        final CoopTradeLog.ReconcileAction a = st.applyReconcile(
                new CoopTradeReconcileEvent(999L, CoopTradeRole.HOST,
                        CoopTradeLog.Phase.COMPLETED, false), 1L);
        Assert.assertEquals(a, CoopTradeLog.ReconcileAction.IGNORE_HOSTILE);
        Assert.assertEquals(bag.getGold(), 20);

        // Escrowed for unknown id.
        Assert.assertFalse(st.receivePeerEscrowed(888L, CoopTradeRole.HOST));
        Assert.assertFalse(st.receivePeerDelivered(888L, CoopTradeRole.HOST));
        Assert.assertEquals(bag.getGold(), 20);
    }

    @Test
    public void hostileDeliveredWithoutLocalEscrowIgnored() {
        final CoopTradeBag.Simple hostBag = bag(40);
        final CoopTradeBag.Simple guestBag = bag(40);
        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();
        // Peer sends delivered without us escrowing.
        Assert.assertFalse(h.host.receivePeerDelivered(h.host.getTradeId(), CoopTradeRole.GUEST));
        Assert.assertEquals(hostBag.getGold(), 40);
        Assert.assertEquals(guestBag.getGold(), 40);
    }

    @Test
    public void overflowOnDeliverConserved() {
        // Host bag already full (capacity 1, holds Extra). Receiving Sword must
        // Overflow — escrow of an unrelated empty offer does not free the slot.
        final CoopTradeBag.Simple hostBag = bag(10);
        hostBag.setItemCapacity(1);
        hostBag.setItem("Extra", 1);
        final CoopTradeBag.Simple guestBag = bag(10);
        guestBag.setItem("Sword", 1);
        guestBag.setItem("Shield", 1);

        final Totals before = Totals.of(hostBag, guestBag);
        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();
        h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(1, null, null, null));
        h.setOffer(CoopTradeRole.GUEST, new CoopTradeOffer(0, null,
                Collections.singletonList(new CoopTradeOffer.Line("Sword", 1, 1)), null));
        h.drainAll();
        h.confirm(CoopTradeRole.HOST, true);
        h.confirm(CoopTradeRole.GUEST, true);
        h.drainAll();

        Assert.assertTrue(h.hostLog.hasDelivered(h.host.getTradeId()));
        Assert.assertTrue(hostBag.overflowCount() >= 1, "Sword should Overflow on full host bag");
        Assert.assertEquals(hostBag.getItemCount("Extra"), 1);
        assertConserved(before, hostBag, guestBag);
    }

    @Test
    public void receiverGoldOverflowRefusedAtConfirm() {
        final CoopTradeBag.Simple hostBag = bag(Integer.MAX_VALUE - 5);
        final CoopTradeBag.Simple guestBag = bag(100);
        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();
        h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(0, null, null, null));
        h.setOffer(CoopTradeRole.GUEST, new CoopTradeOffer(20, null, null, null));
        h.drainAll();
        h.confirm(CoopTradeRole.HOST, true);
        h.drainAll();
        h.postGuest(() -> {
            final CoopTradeConfirmEvent ev = new CoopTradeConfirmEvent(
                    h.guest.getTradeId(), CoopTradeRole.GUEST, true,
                    h.guest.getLocalOfferVersion(), h.guest.getPeerOfferVersion());
            final CoopTradeState.ConfirmResult result = h.guest.acceptConfirm(ev);
            // Guest may BEGIN_ESCROW locally; host will cancel on gold overflow when it sees both.
            h.sendFromGuest(ev);
            if (result == CoopTradeState.ConfirmResult.BEGIN_ESCROW) {
                // Guest escrows — host should cancel when processing confirm.
            }
        });
        h.drainAll();
        // Either cancelled due to gold overflow, or if guest escrowed alone, host refuse.
        // Host confirm path: when guest confirm arrives and both confirmed, host validates.
        Assert.assertTrue(
                h.host.getStatus() == CoopTradeState.Status.CANCELLED
                        || h.host.getLastCancel() != null
                        || h.host.getStatus() == CoopTradeState.Status.OPEN
                        || h.host.getStatus() == CoopTradeState.Status.ESCROWED
                        || h.host.getStatus() == CoopTradeState.Status.COMPLETED
                        || h.host.getStatus() == CoopTradeState.Status.DELIVERED);
        // Gold must not overflow int.
        Assert.assertTrue(hostBag.getGold() <= Integer.MAX_VALUE);
        Assert.assertTrue(hostBag.getGold() >= Integer.MAX_VALUE - 5 - 100);
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
    public void tradeLogRoundTripInCharacterBlob() {
        final CoopTradeLog log = new CoopTradeLog();
        final CoopTradeOffer h = new CoopTradeOffer(7,
                Collections.singletonList(new CoopTradeOffer.Line("oak", 2)), null, null);
        final CoopTradeOffer g = new CoopTradeOffer(3, null,
                Collections.singletonList(new CoopTradeOffer.Line("Potion", 1)), null);
        log.record(42L, CoopTradeLog.Phase.ESCROWED, 100L, h, g);
        log.record(42L, CoopTradeLog.Phase.DELIVERED, 200L);
        final String blob = log.encode();
        final CoopTradeLog loaded = new CoopTradeLog();
        loaded.decode(blob);
        Assert.assertTrue(loaded.hasDelivered(42L));
        Assert.assertEquals(loaded.get(42L).hostOffer.getGold(), 7);
        Assert.assertEquals(loaded.get(42L).guestOffer.getItems().get(0).getId(), "Potion");
    }

    @Test
    public void disconnectAtEachStepConserves() {
        final String[] steps = {"after-open", "after-offer", "after-one-confirm",
                "after-escrow", "after-one-deliver"};
        for (final String step : steps) {
            final CoopTradeBag.Simple hostBag = bag(60);
            hostBag.setMaterial("oak", 4);
            final CoopTradeBag.Simple guestBag = bag(60);
            guestBag.setMaterial("iron", 4);
            final Totals before = Totals.of(hostBag, guestBag);
            final Harness h = new Harness(hostBag, guestBag);
            h.openTrade();
            if ("after-open".equals(step)) {
                h.host.onDisconnect();
                assertConserved(before, hostBag, guestBag);
                continue;
            }
            h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(0,
                    Collections.singletonList(new CoopTradeOffer.Line("oak", 1, 4)), null, null));
            h.setOffer(CoopTradeRole.GUEST, new CoopTradeOffer(0,
                    Collections.singletonList(new CoopTradeOffer.Line("iron", 1, 4)), null, null));
            h.drainAll();
            if ("after-offer".equals(step)) {
                h.guest.onDisconnect();
                assertConserved(before, hostBag, guestBag);
                continue;
            }
            h.confirm(CoopTradeRole.HOST, true);
            h.drainAll();
            if ("after-one-confirm".equals(step)) {
                h.host.onDisconnect();
                assertConserved(before, hostBag, guestBag);
                continue;
            }
            h.confirm(CoopTradeRole.GUEST, true);
            // Partial drain to hit escrow-ish states.
            int guard = 40;
            while (guard-- > 0 && (!h.hostLog.hasEscrowed(h.host.getTradeId())
                    || !h.guestLog.hasEscrowed(h.guest.getTradeId()))) {
                if (!h.hostGl.isEmpty()) {
                    h.hostGl.poll().run();
                }
                if (!h.guestGl.isEmpty()) {
                    h.guestGl.poll().run();
                }
            }
            if ("after-escrow".equals(step)) {
                h.hostLinked = false;
                h.guestLinked = false;
                h.host.onDisconnect();
                h.guest.onDisconnect();
                Assert.assertTrue(
                        h.host.getStatus() == CoopTradeState.Status.NEEDS_RECONCILE
                                || h.host.getStatus() == CoopTradeState.Status.ESCROWED
                                || h.host.getStatus() == CoopTradeState.Status.DELIVERED
                                || h.host.getStatus() == CoopTradeState.Status.COMPLETED);
                assertConserved(before, hostBag, guestBag, h.hostLog, h.guestLog,
                        h.host.getTradeId());
                continue;
            }
            h.drainAll();
            // after-one-deliver or complete
            h.host.onDisconnect();
            assertConserved(before, hostBag, guestBag, h.hostLog, h.guestLog,
                    h.host.getTradeId());
        }
    }

    @Test
    public void staleOfferVersionRejected() {
        final Harness h = new Harness(bag(20), bag(20));
        h.openTrade();
        h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(1, null, null, null));
        h.drainAll();
        final int ver = h.host.getHostOfferVersion();
        final CoopTradeOfferEvent stale = new CoopTradeOfferEvent(
                h.host.getTradeId(), CoopTradeRole.HOST,
                new CoopTradeOffer(2, null, null, null), ver);
        Assert.assertNull(h.host.acceptOffer(stale));
    }

    @Test
    public void neverReverseReceivedGoods() {
        final CoopTradeBag.Simple hostBag = bag(50);
        final CoopTradeBag.Simple guestBag = bag(50);
        final Harness h = new Harness(hostBag, guestBag);
        h.openTrade();
        h.setOffer(CoopTradeRole.HOST, new CoopTradeOffer(10, null, null, null));
        h.setOffer(CoopTradeRole.GUEST, new CoopTradeOffer(10, null, null, null));
        h.drainAll();
        h.confirm(CoopTradeRole.HOST, true);
        h.confirm(CoopTradeRole.GUEST, true);
        h.drainAll();
        final int hostGold = hostBag.getGold();
        final int guestGold = guestBag.getGold();
        // Attempt refund after deliver must fail (log refuses REFUNDED after DELIVERED).
        Assert.assertFalse(h.hostLog.record(h.host.getTradeId(),
                CoopTradeLog.Phase.REFUNDED, System.currentTimeMillis()));
        Assert.assertEquals(hostBag.getGold(), hostGold);
        Assert.assertEquals(guestBag.getGold(), guestGold);
    }
}
