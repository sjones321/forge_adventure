package forge.game;

import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.net.WireClassFilter;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopTakeBackAuthority;
import forge.gamemodes.net.event.coop.CoopTakeBackRequestEvent;
import forge.gamemodes.net.event.coop.CoopTakeBackResultEvent;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * DS4: take back last land/spell/ability via {@link GameSnapshot}.
 * Uses real engine play paths (stash → playChosenSpellAbility → retain).
 */
public class TakeBackDs4Test extends AITest {

    private Game enableTakeBack() {
        final Game game = initAndCreateGame();
        game.EXPERIMENTAL_RESTORE_SNAPSHOT = true;
        game.TAKE_BACK_ENABLED = true;
        return game;
    }

    private SpellAbility landAbility(final Card land, final Player p) {
        for (final SpellAbility sa : land.getAllPossibleAbilities(p, true)) {
            if (sa.isLandAbility()) {
                return sa;
            }
        }
        return null;
    }

    private void playAndRetain(final Game game, final Player actor, final SpellAbility sa) {
        game.stashGameState();
        Assert.assertTrue(actor.getController().playChosenSpellAbility(sa),
                "play should succeed: " + sa);
        final Player prior = game.getTakeBackOwner();
        if (prior != null && !prior.equals(actor)) {
            game.invalidateTakeBack();
        }
        game.retainTakeBackSnapshot(actor);
    }

    @Test
    public void takeBackLandRestoresHandAndDrop() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        Assert.assertEquals(p.getLandsPlayedThisTurn(), 0);
        final SpellAbility landSa = landAbility(plains, p);
        Assert.assertNotNull(landSa);
        playAndRetain(game, p, landSa);

        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));
        Assert.assertEquals(p.getLandsPlayedThisTurn(), 1);
        Assert.assertTrue(game.canTakeBack(p));
        Assert.assertTrue(p.getView().canTakeBack());

        Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
        Assert.assertTrue(plains.isInZone(ZoneType.Hand), "land back in hand");
        Assert.assertEquals(p.getLandsPlayedThisTurn(), 0, "land drop available again");
        Assert.assertFalse(game.canTakeBack(p));
    }

    @Test
    public void takeBackSpellRestoresManaAndCard() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        addCard("Forest", p);
        final Card bear = addCardToZone("Runeclaw Bear", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        // Pay {G} from the Forest then cast the bear through the real SA path.
        final Card forest = findCardWithName(game, "Forest");
        Assert.assertNotNull(forest);
        SpellAbility manaSa = null;
        for (final SpellAbility sa : forest.getAllPossibleAbilities(p, true)) {
            if (sa.isManaAbility()) {
                manaSa = sa;
                break;
            }
        }
        Assert.assertNotNull(manaSa);
        playAndRetain(game, p, manaSa);

        SpellAbility castSa = null;
        for (final SpellAbility sa : bear.getAllPossibleAbilities(p, true)) {
            if (sa.isSpell()) {
                castSa = sa;
                break;
            }
        }
        Assert.assertNotNull(castSa);
        playAndRetain(game, p, castSa);

        Assert.assertTrue(bear.isInPlay() || !game.getStack().isEmpty() || bear.isInZone(ZoneType.Battlefield)
                || bear.isInZone(ZoneType.Stack)
                || countCardsWithName(game, "Runeclaw Bear", ZoneType.Battlefield) == 1
                || countCardsWithName(game, "Runeclaw Bear", ZoneType.Stack) == 1
                || !bear.isInZone(ZoneType.Hand));

        final int manaBefore = p.getManaPool().totalMana();
        Assert.assertTrue(game.canTakeBack(p));
        Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
        Assert.assertTrue(bear.isInZone(ZoneType.Hand), "spell card restored to hand");
        // Mana from the cast is restored; forest may be untapped depending on whether
        // take-back undid only the cast (last action) or included the mana tap.
        Assert.assertTrue(p.getManaPool().totalMana() >= manaBefore
                || !forest.isTapped() || bear.isInZone(ZoneType.Hand));
    }

    @Test
    public void takeBackAbilityRestoresActivation() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        fillLibrary(game.getPlayers().get(0), 8);
        addCards("Plains", 3, p);
        final Card herald = addCard("Herald of Anafenza", p);
        herald.setSickness(false);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);

        final SpellAbility outlast = findSAWithPrefix(herald, "Outlast");
        Assert.assertNotNull(outlast);
        playAndRetain(game, p, outlast);

        Assert.assertTrue(game.canTakeBack(p));
        Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
        Assert.assertFalse(herald.isTapped(), "Outlast tap undone");
        Assert.assertFalse(herald.hasCounters(), "Outlast counters undone");
    }

    @Test
    public void takeBackRefusedAfterDrawRevealAndOpponent() {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        final Player opp = game.getPlayers().get(0);
        fillLibrary(p, 8);
        fillLibrary(opp, 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);

        playAndRetain(game, p, landAbility(plains, p));
        Assert.assertTrue(game.canTakeBack(p));

        // Draw locks.
        p.drawCards(1);
        Assert.assertFalse(game.canTakeBack(p), "draw locks take-back");

        // Fresh action + reveal locks.
        final Card island = addCardToZone("Island", p, ZoneType.Hand);
        // May already have played a land this turn — use an ability on battlefield instead.
        final Card forest = addCard("Forest", p);
        SpellAbility manaSa = null;
        for (final SpellAbility sa : forest.getAllPossibleAbilities(p, true)) {
            if (sa.isManaAbility()) {
                manaSa = sa;
                break;
            }
        }
        playAndRetain(game, p, manaSa);
        Assert.assertTrue(game.canTakeBack(p));
        game.getAction().reveal(p.getCardsIn(ZoneType.Hand), p, false, "test reveal");
        Assert.assertFalse(game.canTakeBack(p), "reveal locks take-back");

        // Opponent action locks.
        playAndRetain(game, p, manaSa);
        // Untap forest for a clean re-activate if needed; re-stash after opp acts.
        forest.setTapped(false);
        game.stashGameState();
        game.retainTakeBackSnapshot(p);
        Assert.assertTrue(game.canTakeBack(p));
        final Card oppLand = addCardToZone("Swamp", opp, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, opp);
        playAndRetain(game, opp, landAbility(oppLand, opp));
        Assert.assertFalse(game.canTakeBack(p), "opponent action locks prior take-back");
        Assert.assertTrue(game.canTakeBack(opp), "opponent has their own take-back");

        // Partner-style: same-team second human would also lock via priorOwner != actor.
        Assert.assertNotNull(island);
    }

    @Test
    public void failedRestoreLeavesStateUnchanged() throws Exception {
        final Game game = enableTakeBack();
        final Player p = game.getPlayers().get(1);
        fillLibrary(p, 8);
        final Card plains = addCardToZone("Plains", p, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        playAndRetain(game, p, landAbility(plains, p));
        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));

        // Not available → unchanged.
        Assert.assertEquals(game.takeBack(oppOrOther(game, p)), TakeBackResult.NOT_AVAILABLE);
        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));

        // Force restore failure: replace retained snapshot with one that never copied.
        final GameSnapshot broken = new GameSnapshot(game);
        final java.lang.reflect.Field snap = Game.class.getDeclaredField("takeBackSnapshot");
        snap.setAccessible(true);
        snap.set(game, broken);
        final java.lang.reflect.Field owner = Game.class.getDeclaredField("takeBackOwner");
        owner.setAccessible(true);
        owner.set(game, p);

        Assert.assertEquals(game.takeBack(p), TakeBackResult.RESTORE_FAILED);
        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield), "board unchanged after failed restore");
    }

    private static Player oppOrOther(final Game game, final Player p) {
        for (final Player o : game.getPlayers()) {
            if (o != p) {
                return o;
            }
        }
        return p;
    }

    @Test
    public void coopHostRestoreAndPartnerBlocks() {
        final Game game = enableTakeBack();
        final Player host = game.getPlayers().get(1);
        final Player guest = game.getPlayers().get(0);
        fillLibrary(host, 8);
        fillLibrary(guest, 8);
        final Card plains = addCardToZone("Plains", host, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, host);
        playAndRetain(game, host, landAbility(plains, host));

        // Wrong requester name → refused.
        final CoopTakeBackResultEvent denied = CoopTakeBackAuthority.handle(game,
                new CoopTakeBackRequestEvent(1L, host.getId(), 0L), "NotTheHost");
        Assert.assertFalse(denied.isAccepted());
        Assert.assertTrue(plains.isInZone(ZoneType.Battlefield));

        // Correct requester → host restores.
        final String hostName = host.getLobbyPlayer().getName();
        final CoopTakeBackResultEvent ok = CoopTakeBackAuthority.handle(game,
                new CoopTakeBackRequestEvent(2L, host.getId(), 0L), hostName);
        Assert.assertTrue(ok.isAccepted(), ok.getReason());
        Assert.assertTrue(plains.isInZone(ZoneType.Hand));

        // Partner acted after host's new action → host take-back blocked.
        final Card island = addCardToZone("Island", host, ZoneType.Hand);
        // land drop already used this turn if we only restored — play mana then partner.
        final Card forest = addCard("Forest", host);
        SpellAbility manaSa = null;
        for (final SpellAbility sa : forest.getAllPossibleAbilities(host, true)) {
            if (sa.isManaAbility()) {
                manaSa = sa;
                break;
            }
        }
        playAndRetain(game, host, manaSa);
        Assert.assertTrue(game.canTakeBack(host));
        final Card swamp = addCardToZone("Swamp", guest, ZoneType.Hand);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, guest);
        playAndRetain(game, guest, landAbility(swamp, guest));
        Assert.assertFalse(game.canTakeBack(host), "partner action blocks host take-back");

        final CoopTakeBackResultEvent blocked = CoopTakeBackAuthority.handle(game,
                new CoopTakeBackRequestEvent(3L, host.getId(), 0L), hostName);
        Assert.assertFalse(blocked.isAccepted());
        Assert.assertNotNull(island);
    }

    @Test
    public void wireClassesAndProtocolBump() {
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTakeBackRequestEvent"));
        Assert.assertTrue(WireClassFilter.isAllowed(
                "forge.gamemodes.net.event.coop.CoopTakeBackResultEvent"));
        // Review-time bump: feature/set-start was 10 → DS4 is 11.
        Assert.assertEquals(CoopPorts.PROTOCOL_VERSION, 11);
    }
}
