package forge.gamemodes.net.coop;

import forge.ai.AITest;
import forge.game.Game;
import forge.game.TakeBackResult;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.event.coop.CoopTakeBackRequestEvent;
import forge.gamemodes.net.event.coop.CoopTakeBackResultEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

/**
 * DS4: plain-data take-back events round-trip over Java serialization (loopback)
 * and host authority restores + refuses partner-blocked requests.
 */
public class CoopTakeBackLoopbackTest extends AITest {

    @Test
    public void requestAndResultRoundTripOnLoopback() throws Exception {
        final CoopTakeBackRequestEvent req = new CoopTakeBackRequestEvent(42L, 7, 123456L);
        final CoopTakeBackResultEvent res = new CoopTakeBackResultEvent(42L, 7, true, "");

        final CoopTakeBackRequestEvent req2 = roundTrip(req);
        Assert.assertEquals(req2.getRequestId(), 42L);
        Assert.assertEquals(req2.getPlayerId(), 7);
        Assert.assertEquals(req2.getClientTimeMs(), 123456L);

        final CoopTakeBackResultEvent res2 = roundTrip(res);
        Assert.assertEquals(res2.getRequestId(), 42L);
        Assert.assertTrue(res2.isAccepted());
        Assert.assertEquals(res2.getReason(), "");
    }

    @Test
    public void hostRestoreThenResyncShapeAndPartnerBlocks() {
        final Game game = initAndCreateGame();
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = true;
        game.TAKE_BACK_ENABLED = true;
        final Player host = game.getPlayers().get(1);
        final Player partner = game.getPlayers().get(0);
        fillLibrary(host, 8);
        fillLibrary(partner, 8);
        final Card plains = addCardToZone("Plains", host, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, host);

        game.stashGameState();
        SpellAbility landSa = null;
        for (final SpellAbility sa : plains.getAllPossibleAbilities(host, true)) {
            if (sa.isLandAbility()) {
                landSa = sa;
                break;
            }
        }
        Assert.assertNotNull(landSa);
        Assert.assertTrue(host.getController().playChosenSpellAbility(landSa));
        game.retainTakeBackSnapshot(host);

        // Simulate guest→host wire: deserialize request, host restores.
        final CoopTakeBackRequestEvent wireReq = roundTripSilent(
                new CoopTakeBackRequestEvent(1L, host.getId(), System.currentTimeMillis()));
        final CoopTakeBackResultEvent wireRes = CoopTakeBackAuthority.handle(
                game, wireReq, host.getLobbyPlayer().getName());
        final CoopTakeBackResultEvent wireRes2 = roundTripSilent(wireRes);
        Assert.assertTrue(wireRes2.isAccepted());
        Assert.assertTrue(plains.isInZone(ZoneType.Hand));
        Assert.assertEquals(game.takeBack(host), TakeBackResult.NOT_AVAILABLE);

        // Partner acts → host cannot take back.
        final Card forest = addCard("Forest", host);
        SpellAbility mana = null;
        for (final SpellAbility sa : forest.getAllPossibleAbilities(host, true)) {
            if (sa.isManaAbility()) {
                mana = sa;
                break;
            }
        }
        game.stashGameState();
        Assert.assertTrue(host.getController().playChosenSpellAbility(mana));
        game.retainTakeBackSnapshot(host);
        final Card swamp = addCardToZone("Swamp", partner, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, partner);
        SpellAbility oppLand = null;
        for (final SpellAbility sa : swamp.getAllPossibleAbilities(partner, true)) {
            if (sa.isLandAbility()) {
                oppLand = sa;
                break;
            }
        }
        game.stashGameState();
        Assert.assertTrue(partner.getController().playChosenSpellAbility(oppLand));
        final Player prior = game.getTakeBackOwner();
        if (prior != null && !prior.equals(partner)) {
            game.invalidateTakeBack();
        }
        game.retainTakeBackSnapshot(partner);

        final CoopTakeBackResultEvent blocked = CoopTakeBackAuthority.handle(game,
                new CoopTakeBackRequestEvent(2L, host.getId(), 0L),
                host.getLobbyPlayer().getName());
        Assert.assertFalse(blocked.isAccepted());
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(final T obj) throws Exception {
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(obj);
        }
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bos.toByteArray()))) {
            return (T) ois.readObject();
        }
    }

    private static <T> T roundTripSilent(final T obj) {
        try {
            return roundTrip(obj);
        } catch (final Exception e) {
            throw new AssertionError(e);
        }
    }
}
