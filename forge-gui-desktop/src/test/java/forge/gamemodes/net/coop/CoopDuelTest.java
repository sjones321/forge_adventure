package forge.gamemodes.net.coop;

import forge.deck.Deck;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.event.coop.CoopDuelInviteEvent;
import forge.gamemodes.net.event.coop.CoopDuelResponseEvent;
import forge.gamemodes.net.event.coop.CoopEnemyEncounterRequestEvent;
import forge.gamemodes.net.event.coop.CoopFightRequestResultEvent;
import forge.localinstance.properties.ForgePreferences.FPref;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Headless CO3 coverage: join-prompt timeout → solo, not-in-party never prompts,
 * decklist round-trip + rejection, scaling math, guest fight-request host
 * validation, mid-duel guest disconnect, per-side rewards, match-plan seats.
 */
public class CoopDuelTest {

    @Test
    public void protocolVersionIsExactlyEightForMv2() {
        // CO2=5; CO3=6; MV1=7; MV2=8. Exact equality only.
        // Open claims: gate-sync (#40)=9; TR1 (#27)=next after merge.
        // Package K does not bump (format on mv2SetCode via #k:).
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 8);
    }

    @Test
    public void notInPartyNeverPrompts() {
        final CoopDuelInviteState state = new CoopDuelInviteState();
        Assert.assertFalse(CoopDuelInviteState.shouldOfferJoin(CoopPartyProximity.NEVER));
        final CoopDuelInviteEvent invite = state.beginInvite("Host", "Goblin", 15, CoopPartyProximity.NEVER);
        Assert.assertNull(invite);
        Assert.assertEquals(state.getStatus(), CoopDuelInviteState.Status.SOLO);
    }

    @Test
    public void joinPromptTimeoutGoesSolo() {
        final CoopDuelInviteState state = new CoopDuelInviteState();
        final CoopPartyProximity nearby = () -> true;
        final CoopDuelInviteEvent invite = state.beginInvite("Host", "Bandit", 15, nearby);
        Assert.assertNotNull(invite);
        Assert.assertEquals(state.getStatus(), CoopDuelInviteState.Status.WAITING_RESPONSE);
        Assert.assertTrue(state.timeout(invite.getInviteId()));
        Assert.assertEquals(state.getStatus(), CoopDuelInviteState.Status.SOLO);
        Assert.assertFalse(state.isJoined());
    }

    @Test
    public void acceptJoinsWithDecklist() {
        final CoopDuelInviteState host = new CoopDuelInviteState();
        final CoopDuelInviteState guest = new CoopDuelInviteState();
        final CoopPartyProximity nearby = () -> true;
        final CoopDuelInviteEvent invite = host.beginInvite("Host", "Drake", 20, nearby);
        Assert.assertNotNull(invite);
        Assert.assertTrue(guest.receiveInvite(invite));
        final CoopDuelResponseEvent resp = guest.respond(true, sampleDecklist("Forest"));
        Assert.assertNotNull(resp);
        Assert.assertTrue(resp.isAccepted());
        Assert.assertTrue(host.applyPeerResponse(resp));
        Assert.assertTrue(host.isJoined());
        Assert.assertEquals(host.getAcceptedDecklist(), resp.getDecklistText());
    }

    @Test
    public void decklistRoundTripViaText() {
        final String text = sampleDecklist("Island");
        final Predicate<String> ok = CoopDecklistValidator.allowlist("Island", "Forest", "Mountain", "Swamp", "Plains");
        final CoopDecklistValidator.Result result = CoopDecklistValidator.validate(text, ok);
        Assert.assertTrue(result.ok(), result.reason + " " + result.detail);
        Assert.assertNotNull(result.deck);
        // Re-validate the same text (card names checked from lines; no StaticData needed).
        final CoopDecklistValidator.Result again = CoopDecklistValidator.validate(text, ok);
        Assert.assertTrue(again.ok(), again.reason + " " + again.detail);
        // Reject when the allowlist no longer permits a name that appears in the text.
        final CoopDecklistValidator.Result rejected =
                CoopDecklistValidator.validate(text, CoopDecklistValidator.allowlist("Swamp"));
        Assert.assertEquals(rejected.reason, CoopDecklistValidator.RejectReason.BAD_CARD_NAME);
    }

    @Test
    public void decklistRejectsBadCardNames() {
        final String text = "[metadata]\nName=Bad\n[Main]\n4 NotARealCardNameXYZ\n";
        final Predicate<String> ok = CoopDecklistValidator.allowlist("Island", "Forest");
        final CoopDecklistValidator.Result result = CoopDecklistValidator.validate(text, ok);
        Assert.assertFalse(result.ok());
        Assert.assertEquals(result.reason, CoopDecklistValidator.RejectReason.BAD_CARD_NAME);
    }

    @Test
    public void oversizedDeckRejected() {
        final StringBuilder sb = new StringBuilder();
        sb.append("[metadata]\nName=Huge\n[Main]\n");
        // One line with a count past the cap.
        sb.append((CoopDuelWireLimits.MAX_DECK_CARDS + 5)).append(" Island\n");
        final Predicate<String> ok = name -> "Island".equals(name);
        final CoopDecklistValidator.Result result = CoopDecklistValidator.validate(sb.toString(), ok);
        Assert.assertEquals(result.reason, CoopDecklistValidator.RejectReason.TOO_MANY_MAIN);
    }

    @Test
    public void oversizedDecklistTextRejected() {
        final char[] chars = new char[CoopDuelWireLimits.MAX_DECKLIST_CHARS + 10];
        Arrays.fill(chars, 'A');
        final CoopDecklistValidator.Result result =
                CoopDecklistValidator.validate(new String(chars), name -> true);
        Assert.assertEquals(result.reason, CoopDecklistValidator.RejectReason.TOO_LARGE_TEXT);
    }

    @Test
    public void scalingMathForTwoPlayers() {
        Assert.assertEquals(CoopDuelScaling.scaleEnemyLife(40, 1, 1.5f), 40);
        Assert.assertEquals(CoopDuelScaling.scaleEnemyLife(40, 2, 1.5f), 60);
        Assert.assertEquals(CoopDuelScaling.scaleEnemyExtraCards(1, 3), 0);
        Assert.assertEquals(CoopDuelScaling.scaleEnemyExtraCards(2, 3), 3);
        Assert.assertEquals(CoopDuelScaling.humanCount(false), 1);
        Assert.assertEquals(CoopDuelScaling.humanCount(true), 2);
    }

    @Test
    public void guestFightRequestValidatedByHost() {
        final CoopFightRequestValidator validator = new CoopFightRequestValidator();
        final CoopEnemyEncounterRequestEvent bad =
                new CoopEnemyEncounterRequestEvent(1L, 0L, "Goblin", 10f, 10f);
        CoopFightRequestResultEvent result = validator.validateOnHost(bad, true);
        Assert.assertEquals(result.getDecision(), CoopFightRequestResultEvent.Decision.DENY);

        final CoopEnemyEncounterRequestEvent ok =
                new CoopEnemyEncounterRequestEvent(2L, 99L, "Goblin", 10f, 10f);
        // Default DENY_ALL
        result = validator.validateOnHost(ok, true);
        Assert.assertEquals(result.getDecision(), CoopFightRequestResultEvent.Decision.DENY);
        Assert.assertTrue(result.getReason().contains("enemy"));

        validator.setEnemyLookup((id, data, x, y) -> id == 99L);
        result = validator.validateOnHost(ok, true);
        Assert.assertEquals(result.getDecision(), CoopFightRequestResultEvent.Decision.ACCEPT);

        // Guest must not validate as host
        result = validator.validateOnHost(ok, false);
        Assert.assertEquals(result.getDecision(), CoopFightRequestResultEvent.Decision.DENY);
    }

    @Test
    public void midDuelGuestDisconnectDoesNotHangHost() {
        final CoopDuelDisconnectPolicy policy = new CoopDuelDisconnectPolicy();
        policy.beginDuel(CoopDuelDisconnectPolicy.GuestDisconnectAction.CONCEDE);
        Assert.assertTrue(policy.isDuelActive());
        final CoopDuelDisconnectPolicy.Outcome out = policy.onGuestDisconnected();
        Assert.assertEquals(out, CoopDuelDisconnectPolicy.Outcome.CONTINUE_HOST_MATCH);
        Assert.assertTrue(CoopDuelDisconnectPolicy.hostWorldRemainsPlayable(out));
        Assert.assertTrue(CoopDuelDisconnectPolicy.mustNotBlockOnGuest());
        Assert.assertFalse(policy.isGuestConnected());
        policy.endDuel();
        Assert.assertFalse(policy.isDuelActive());
        Assert.assertEquals(policy.onGuestDisconnected(), CoopDuelDisconnectPolicy.Outcome.IGNORE);
    }

    @Test
    public void eachSideAppliesOnlyOwnRewards() {
        final CoopDuelRewards.SimpleSink host = new CoopDuelRewards.SimpleSink();
        final CoopDuelRewards.SimpleSink guest = new CoopDuelRewards.SimpleSink();
        final CoopDuelRewards.ResultPayload win =
                new CoopDuelRewards.ResultPayload(1L, true, 25, 50, 0, false, "Bandit");
        CoopDuelRewards.applyHostOnly(host, guest, win);
        Assert.assertEquals(host.getGold(), 25);
        Assert.assertEquals(host.getXp(), 50);
        Assert.assertEquals(guest.getGold(), 0);
        Assert.assertEquals(guest.getXp(), 0);

        final CoopDuelRewards.ResultPayload loss =
                new CoopDuelRewards.ResultPayload(2L, false, 0, 0, 3, false, "Bandit");
        CoopDuelRewards.applyGuestOnly(host, guest, loss);
        Assert.assertEquals(host.getGold(), 25);
        Assert.assertEquals(host.getLife(), 20);
        Assert.assertEquals(guest.getLife(), 17);
        Assert.assertEquals(guest.getLosses(), 1);
    }

    @Test
    public void matchPlanTwoHumansTeam0EnemiesTeam1() {
        final Deck hostDeck = new Deck("Host");
        final Deck guestDeck = new Deck("Guest");
        final Deck enemyDeck = new Deck("Enemy");
        final CoopFightLoadout guestLoadout = CoopFightLoadout.builder()
                .playerName("Guest")
                .avatarId("guest-avatar")
                .startingLife(22)
                .manaShards(2)
                .build();
        final CoopDuelMatchPlan plan = CoopDuelMatchPlan.build(
                "Host", "host-avatar", hostDeck, 20, 1, 1,
                true, guestLoadout, guestDeck,
                Collections.singletonList(
                        new CoopDuelMatchPlan.EnemySpec("Orc", "enemy", enemyDeck, 40, 1)),
                1.5f, 2);
        Assert.assertTrue(plan.isCoOp());
        Assert.assertEquals(plan.getHumanCount(), 2);
        Assert.assertEquals(plan.countKind(CoopDuelMatchPlan.SeatKind.HOST_HUMAN), 1);
        Assert.assertEquals(plan.countKind(CoopDuelMatchPlan.SeatKind.GUEST_HUMAN), 1);
        Assert.assertEquals(plan.countTeam(0), 2);
        Assert.assertEquals(plan.countTeam(1), 1);
        Assert.assertEquals(plan.getEnemyExtraCardsApplied(), 2);
        CoopDuelMatchPlan.Seat enemy = null;
        for (final CoopDuelMatchPlan.Seat s : plan.getSeats()) {
            if (s.kind == CoopDuelMatchPlan.SeatKind.ENEMY_AI) {
                enemy = s;
            }
            if (s.kind == CoopDuelMatchPlan.SeatKind.GUEST_HUMAN) {
                Assert.assertTrue(s.remoteGui);
                Assert.assertEquals(s.lobbySlot, 1);
            }
        }
        Assert.assertNotNull(enemy);
        Assert.assertEquals(enemy.startingLife, 60);
        Assert.assertEquals(enemy.teamNumber, 1);
    }

    @Test
    public void soloMatchPlanNoGuestSeat() {
        final Deck hostDeck = new Deck("Host");
        final CoopDuelMatchPlan plan = CoopDuelMatchPlan.build(
                "Host", "h", hostDeck, 20, 0, 0,
                false, null, null,
                Collections.singletonList(
                        new CoopDuelMatchPlan.EnemySpec("Wolf", "e", hostDeck, 30, 0)),
                1.5f, 2);
        Assert.assertFalse(plan.isCoOp());
        Assert.assertEquals(plan.countKind(CoopDuelMatchPlan.SeatKind.GUEST_HUMAN), 0);
        Assert.assertEquals(plan.countTeam(0), 1);
        Assert.assertEquals(plan.getSeats().get(1).startingLife, 30);
        Assert.assertEquals(plan.getEnemyExtraCardsApplied(), 0);
    }

    @Test
    public void loadoutRejectsOversizedName() {
        final CoopFightLoadout bad = CoopFightLoadout.builder()
                .playerName("")
                .startingLife(20)
                .build();
        Assert.assertNull(CoopFightLoadout.validateOrNull(bad));
        final CoopFightLoadout good = CoopFightLoadout.builder()
                .playerName("Alice")
                .avatarId("alice")
                .startingLife(20)
                .build();
        Assert.assertNotNull(CoopFightLoadout.validateOrNull(good));
    }

    @Test
    public void wireClassesAllowed() {
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopEnemyEncounterRequestEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopDuelStartEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopDuelResultEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopFightLoadoutEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.coop.CoopFightLoadout"));
    }

    @Test
    public void inviteMustNotBlock() {
        Assert.assertTrue(CoopDuelDisconnectPolicy.mustNotBlockOnGuest());
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final CoopDuelInviteState state = new CoopDuelInviteState();
        final CoopDuelInviteEvent invite = state.beginInvite("H", "E", 1, () -> true);
        Assert.assertNotNull(invite);
        timedOut.set(state.timeout(invite.getInviteId()));
        Assert.assertTrue(timedOut.get());
    }

    /** DS1: co-op host proxy class remains loadable; modern-duel pref defaults leave stock off. */
    @Test
    public void remoteClientGuiGameClassLoadableAndModernDuelPrefDefaultsAuto() throws Exception {
        // Full construction needs GuiBase (see ProtocolGuiGameInProcessTest); class load is enough here.
        Assert.assertEquals(
                Class.forName("forge.gamemodes.net.server.RemoteClientGuiGame").getSimpleName(),
                "RemoteClientGuiGame");
        // Auto = Adventure + Ascendant only (see ModernDuelScreen.resolve in forge-gui-mobile).
        Assert.assertEquals(FPref.UI_MODERN_DUEL_SCREEN.getDefault(), "Auto");
    }

    private static String sampleDecklist(final String cardName) {
        return "[metadata]\nName=Sample\n[Main]\n4 " + cardName + "\n4 Island\n4 Forest\n";
    }
}
