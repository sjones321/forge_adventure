package forge.gamemodes.net.coop;

import forge.game.Game;
import forge.game.TakeBackResult;
import forge.game.player.Player;
import forge.gamemodes.net.event.coop.CoopTakeBackRequestEvent;
import forge.gamemodes.net.event.coop.CoopTakeBackResultEvent;
import forge.player.PlayerControllerHuman;

/**
 * DS4 host-side validation for co-op take-back. Restores only when the request
 * is from the acting player and a snapshot is still eligible. Never deserializes
 * a game graph — the host uses its local {@link forge.game.GameSnapshot}.
 */
public final class CoopTakeBackAuthority {

    private CoopTakeBackAuthority() {
    }

    /**
     * Validate and perform take-back on the host game.
     *
     * @param game          host game (may be null when no duel is active)
     * @param request       guest or local request
     * @param requesterName lobby/player name of the peer that sent the request (for seat match)
     * @return result event to broadcast
     */
    public static CoopTakeBackResultEvent handle(final Game game, final CoopTakeBackRequestEvent request,
                                                 final String requesterName) {
        if (request == null) {
            return new CoopTakeBackResultEvent(0L, -1, false, "null request");
        }
        final long id = request.getRequestId();
        final int playerId = request.getPlayerId();
        if (game == null || !game.TAKE_BACK_ENABLED) {
            return new CoopTakeBackResultEvent(id, playerId, false, "take-back disabled");
        }
        final Player actor = game.getPlayer(playerId);
        if (actor == null) {
            return new CoopTakeBackResultEvent(id, playerId, false, "unknown player");
        }
        // Request must be from the acting player (name match when provided).
        if (requesterName != null && !requesterName.isEmpty()) {
            final String lobby = actor.getLobbyPlayer() != null ? actor.getLobbyPlayer().getName() : "";
            final String registered = actor.getName();
            if (!requesterName.equals(lobby) && !requesterName.equals(registered)) {
                return new CoopTakeBackResultEvent(id, playerId, false, "not acting player");
            }
        }
        if (!game.canTakeBack(actor)) {
            return new CoopTakeBackResultEvent(id, playerId, false, "not eligible");
        }
        // Prefer the human controller path so prompt/resync side effects run.
        if (actor.getController() instanceof PlayerControllerHuman pch) {
            final boolean ok = pch.tryTakeBackLastAction();
            return new CoopTakeBackResultEvent(id, playerId, ok, ok ? "" : "restore failed");
        }
        final TakeBackResult result = game.takeBack(actor);
        if (result == TakeBackResult.SUCCESS) {
            return new CoopTakeBackResultEvent(id, playerId, true, "");
        }
        if (result == TakeBackResult.RESTORE_FAILED) {
            return new CoopTakeBackResultEvent(id, playerId, false, "restore failed");
        }
        return new CoopTakeBackResultEvent(id, playerId, false, "not eligible");
    }
}
