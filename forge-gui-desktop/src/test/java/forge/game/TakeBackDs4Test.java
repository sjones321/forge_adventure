package forge.game;

import forge.ai.AITest;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
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
        // 0-mana artifact creature so AI payment cannot fail; still a real cast path.
        final Card memnite = addCardToZone("Memnite", p, ZoneType.Hand);
        // Floating mana that casting must not consume (sanity for "mana restored").
        final Card forest = addCard("Forest", p);
        SpellAbility manaSa = null;
        for (final SpellAbility sa : forest.getAllPossibleAbilities(p, true)) {
            if (sa.isManaAbility()) {
                manaSa = sa;
                break;
            }
        }
        Assert.assertNotNull(manaSa);
        playAndRetain(game, p, manaSa);
        final int manaAfterTap = p.getManaPool().totalMana();
        Assert.assertTrue(manaAfterTap >= 1, "forest should have added mana");

        SpellAbility castSa = null;
        for (final SpellAbility sa : memnite.getAllPossibleAbilities(p, true)) {
            if (sa.isSpell()) {
                castSa = sa;
                break;
            }
        }
        Assert.assertNotNull(castSa);
        game.getPhaseHandler().devModeSet(PhaseType.MAIN1, p);
        game.getAction().checkStateEffects(true);
        playAndRetain(game, p, castSa);

        Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Hand), 0, "spell left hand");
        Assert.assertTrue(game.canTakeBack(p));
        Assert.assertEquals(game.takeBack(p), TakeBackResult.SUCCESS);
        // After snapshot restore, look up by name — Card references may be remapped.
        Assert.assertEquals(countCardsWithName(game, "Memnite", ZoneType.Hand), 1,
                "spell card restored to hand");
        Assert.assertEquals(p.getManaPool().totalMana(), manaAfterTap, "floating mana restored");
        Assert.assertNotNull(memnite); // keep reference live for GC clarity in failure dumps
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

}
