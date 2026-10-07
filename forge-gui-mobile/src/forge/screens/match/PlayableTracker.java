package forge.screens.match;

import forge.ai.ComputerUtilMana;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.HostedMatch;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Arena-style "you can cast this" tracking for the human player's hand: while the human has
 * priority, a card is playable if it's a land drop they can make or a spell they can cast now
 * with the mana available. Refreshed at most a few times a second; read by the hand display
 * (green glow) and the desktop pop-out hand window.
 */
public final class PlayableTracker {
    private static final long REFRESH_MS = 300;
    private static volatile Set<Integer> playable = Collections.emptySet();
    private static volatile long lastRefresh;

    private PlayableTracker() {
    }

    public static boolean isPlayable(int cardId) {
        return playable.contains(cardId);
    }

    public static Set<Integer> playableIds() {
        return playable;
    }

    /** Recomputes if the last refresh is older than {@link #REFRESH_MS}. Safe to call every frame. */
    public static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefresh < REFRESH_MS)
            return;
        lastRefresh = now;
        Set<Integer> next = new HashSet<>();
        try {
            PlayerView me = MatchController.instance.getCurrentPlayer();
            HostedMatch match = MatchController.getHostedMatch();
            Game game = match == null ? null : match.getGame();
            if (game != null && me != null && !game.isGameOver()) {
                Player player = null;
                for (Player p : game.getPlayers())
                    if (p.getId() == me.getId())
                        player = p;
                if (player != null && game.getPhaseHandler().getPriorityPlayer() == player) {
                    for (Card c : player.getCardsIn(ZoneType.Hand))
                        if (canCastNow(c, player))
                            next.add(c.getId());
                }
            }
        } catch (Exception ignored) {
            // game state changing under us; try again next refresh
        }
        playable = next;
    }

    private static boolean canCastNow(Card c, Player player) {
        for (SpellAbility sa : c.getAllPossibleAbilities(player, true)) {
            if (sa.isLandAbility())
                return true;
            if (sa.isSpell() && ComputerUtilMana.canPayManaCost(sa, player, 0, false))
                return true;
        }
        return false;
    }
}
