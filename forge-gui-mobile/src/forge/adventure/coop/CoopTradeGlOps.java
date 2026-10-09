package forge.adventure.coop;

import forge.adventure.player.AdventurePlayer;
import forge.adventure.world.WorldSave;
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
 *
 * <p>Host persists trade-log + bag into the world save the host actually loads.
 * Guest persists into the co-op {@code .chr} (single source of truth once CO1
 * guest-save C2 lands).
 */
public final class CoopTradeGlOps {
    /** Test hook: when set, replaces host/guest save routing. */
    private static volatile Consumer<AdventurePlayer> saveOverride;

    private CoopTradeGlOps() {
    }

    public static void setSaveOverride(final Consumer<AdventurePlayer> override) {
        saveOverride = override;
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
        CoopTradeBag.Snapshot snap = null;
        if (!log.hasEscrowed(id)) {
            snap = bag.snapshot();
            final CoopTradeApply.Result result = CoopTradeApply.escrowIdempotent(
                    id, log, bag, state.getLocalOffer(), snap);
            if (!result.applied) {
                return false;
            }
        }
        final CoopTradeEscrowedEvent escrowed = state.markEscrowed(System.currentTimeMillis());
        if (escrowed == null) {
            // H2: save/record failed — restore bag, do not send.
            if (snap != null) {
                bag.restore(snap);
            }
            return false;
        }
        send.accept(escrowed);
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
            // M2: stay escrowed / deliver-blocked so the player can free space and retry.
            state.markDeliverBlocked(result.detail);
            return false;
        }
        final CoopTradeDeliveredEvent delivered = state.markDelivered(System.currentTimeMillis());
        if (delivered == null) {
            bag.restore(snap);
            state.markDeliverBlocked("save failed");
            return false;
        }
        send.accept(delivered);
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
        // C1: own offer from the entry's stored role, not from a reset() default.
        final CoopTradeRole role = entry.localRole != null ? entry.localRole : state.getLocalRole();
        final CoopTradeOffer own = role == CoopTradeRole.HOST
                ? entry.hostOffer : entry.guestOffer;
        final CoopTradeApply.Result result = CoopTradeApply.refundEscrow(bag, own);
        if (!result.applied) {
            return false;
        }
        return state.markRefunded(System.currentTimeMillis());
    }

    /**
     * Persist trade log into the save the local player actually loads:
     * host → world save; guest → co-op {@code .chr}.
     */
    public static void syncLogAndSave(final AdventurePlayer player, final CoopTradeLog log) {
        if (player == null || log == null) {
            return;
        }
        player.setTradeLogBlob(log.encode());
        final Consumer<AdventurePlayer> override = saveOverride;
        if (override != null) {
            override.accept(player);
            return;
        }
        try {
            if (isHostSave()) {
                final WorldSave world = WorldSave.getCurrentSave();
                if (world == null || !world.autoSave()) {
                    throw new IllegalStateException("host world autoSave failed for "
                            + player.getName());
                }
            } else {
                CoopCharacterStore.savePlayer(player);
            }
        } catch (final RuntimeException e) {
            throw e;
        } catch (final Exception e) {
            throw new IllegalStateException(
                    "trade save failed for " + player.getName(), e);
        }
    }

    private static boolean isHostSave() {
        try {
            return CoopHooks.isWorldAuthority()
                    || CoopSession.get().getRole() == CoopSessionRole.HOST;
        } catch (final Exception e) {
            return false;
        }
    }

    public static void loadLogFromPlayer(final AdventurePlayer player, final CoopTradeLog log) {
        if (player == null || log == null) {
            return;
        }
        log.decode(player.getTradeLogBlob());
    }
}
