package forge.adventure.coop;

import forge.adventure.player.AdventurePlayer;
import forge.gamemodes.net.coop.CoopTradeApply;
import forge.gamemodes.net.coop.CoopTradeBag;
import forge.gamemodes.net.coop.CoopTradeLog;
import forge.gamemodes.net.coop.CoopTradeOffer;
import forge.gamemodes.net.coop.CoopTradeRole;
import forge.gamemodes.net.coop.CoopTradeState;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopTradeDeliveredEvent;
import forge.gamemodes.net.event.coop.CoopTradeEscrowedEvent;

import java.util.function.Consumer;

/**
 * GL-thread escrow / deliver / refund mutations shared by {@link CoopTradeRuntime}
 * and headless tests. Call only from the GL runnable queue.
 */
public final class CoopTradeGlOps {
    private CoopTradeGlOps() {
    }

    public static boolean performEscrow(final CoopTradeState state, final CoopTradeLog log,
                                        final CoopTradeBag bag, final Consumer<NetEvent> send) {
        if (state == null || bag == null || send == null) {
            return false;
        }
        final long id = state.getTradeId();
        if (id == 0L) {
            return false;
        }
        if (!log.hasEscrowed(id)) {
            final CoopTradeBag.Snapshot snap = bag.snapshot();
            final CoopTradeApply.Result result = CoopTradeApply.escrowIdempotent(
                    id, log, bag, state.getLocalOffer(), snap);
            if (!result.applied) {
                return false;
            }
        }
        final CoopTradeEscrowedEvent escrowed = state.markEscrowed(System.currentTimeMillis());
        if (escrowed != null) {
            send.accept(escrowed);
        }
        if (state.shouldDeliver()) {
            return performDeliver(state, log, bag, send);
        }
        return true;
    }

    public static boolean performDeliver(final CoopTradeState state, final CoopTradeLog log,
                                         final CoopTradeBag bag, final Consumer<NetEvent> send) {
        if (state == null || bag == null || send == null) {
            return false;
        }
        final long id = state.getTradeId();
        if (log.hasDelivered(id)) {
            final CoopTradeDeliveredEvent again = state.markDelivered(System.currentTimeMillis());
            if (again != null) {
                send.accept(again);
            }
            return true;
        }
        // Invariant: never deliver unless peer escrowed and we escrowed.
        if (!state.shouldDeliver()) {
            return false;
        }
        final CoopTradeBag.Snapshot snap = bag.snapshot();
        final CoopTradeApply.Result result = CoopTradeApply.deliverIdempotent(
                id, log, bag, state.getPeerOffer(), snap);
        if (!result.applied) {
            return false;
        }
        final CoopTradeDeliveredEvent delivered = state.markDelivered(System.currentTimeMillis());
        if (delivered != null) {
            send.accept(delivered);
        }
        return true;
    }

    public static boolean performRefund(final CoopTradeState state, final CoopTradeLog log,
                                        final CoopTradeBag bag) {
        if (state == null || log == null || bag == null) {
            return false;
        }
        final long id = state.getTradeId();
        final CoopTradeLog.Entry entry = log.get(id);
        if (entry == null || log.hasDelivered(id)) {
            return false;
        }
        final CoopTradeOffer own = state.getLocalRole() == CoopTradeRole.HOST
                ? entry.hostOffer : entry.guestOffer;
        final CoopTradeApply.Result result = CoopTradeApply.refundEscrow(bag, own);
        if (!result.applied) {
            return false;
        }
        return state.markRefunded(System.currentTimeMillis());
    }

    /** Persist trade log into the character and atomic-save. */
    public static void syncLogAndSave(final AdventurePlayer player, final CoopTradeLog log) {
        if (player == null || log == null) {
            return;
        }
        try {
            player.setTradeLogBlob(log.encode());
            CoopCharacterStore.savePlayer(player);
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "character save failed for " + player.getName(), e);
        }
    }

    public static void loadLogFromPlayer(final AdventurePlayer player, final CoopTradeLog log) {
        if (player == null || log == null) {
            return;
        }
        log.decode(player.getTradeLogBlob());
    }
}
