package forge.screens.match;

import forge.Forge;
import forge.adventure.coop.CoopDuelRuntime;
import forge.adventure.coop.CoopSession;
import forge.adventure.coop.CoopSessionRole;
import forge.adventure.util.Config;
import forge.game.player.PlayerView;
import forge.interfaces.IGameController;
import forge.util.Localizer;

/**
 * DS4: Ascendant-gated take-back entry points for both duel screens.
 * Solo / host use {@link IGameController#takeBackLastAction()}; co-op guests
 * send a plain-data {@code CoopTakeBackRequestEvent} for host-authoritative restore.
 */
public final class TakeBackActions {
    private TakeBackActions() {
    }

    /** Whether take-back chrome should be considered for this match. */
    public static boolean featureEnabled() {
        return Forge.isMobileAdventureMode && Config.ascendant()
                && (Config.instance().getConfigData() == null
                || Config.instance().getConfigData().duelTakeBackEnabled);
    }

    /**
     * Whether the local human may take back right now (snapshot retained and eligible).
     */
    public static boolean canTakeBack() {
        if (!featureEnabled()) {
            return false;
        }
        final PlayerView local = MatchController.instance.getCurrentPlayer();
        if (local != null && local.canTakeBack()) {
            return true;
        }
        final IGameController gc = MatchController.instance.getGameController();
        return gc != null && gc.canTakeBackLastAction();
    }

    /**
     * Perform take-back for the local player. Co-op guests request via the host;
     * everyone else calls the local/host game controller.
     */
    public static void takeBack() {
        if (!featureEnabled()) {
            return;
        }
        if (CoopSession.get().getRole() == CoopSessionRole.GUEST
                && CoopDuelRuntime.get().isDuelActive()) {
            final PlayerView local = MatchController.instance.getCurrentPlayer();
            if (local == null) {
                return;
            }
            CoopDuelRuntime.get().requestTakeBack(local.getId());
            return;
        }
        final IGameController gc = MatchController.instance.getGameController();
        if (gc == null) {
            return;
        }
        if (!gc.canTakeBackLastAction()) {
            return;
        }
        gc.takeBackLastAction();
    }

    public static String buttonLabel() {
        return Localizer.getInstance().getMessage("lblTakeBack");
    }
}
