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
 * Single-player only — co-op duels hide the button and show a short note if
 * Take back / Ctrl+Z / Start is pressed (Bellwarden owns real co-op).
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

    /** True while an Ascendant co-op HostedMatch is live (host or guest). */
    public static boolean isCoopDuel() {
        final CoopSessionRole role = CoopSession.get().getRole();
        return (role == CoopSessionRole.HOST || role == CoopSessionRole.GUEST)
                && CoopDuelRuntime.get().isDuelActive();
    }

    /**
     * Whether the local human may take back right now (snapshot retained and eligible).
     * Always false during a co-op duel.
     */
    public static boolean canTakeBack() {
        if (!featureEnabled() || isCoopDuel()) {
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
     * Perform take-back for the local player. In a co-op duel, shows a short note
     * and does nothing (no wire events).
     */
    public static void takeBack() {
        if (!featureEnabled()) {
            return;
        }
        if (isCoopDuel()) {
            notifyUnavailableInCoop();
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

    /**
     * Show "take back failed" on the duel screen. Uses {@link MatchController#showMatchNote}
     * (MatchScreen overlay via Classic.render) — not FOptionPane / GameHUD.
     */
    public static void notifyFailed() {
        final String msg = Localizer.getInstance().getMessage("lblTakeBackFailed");
        MatchController.instance.showMatchNote(msg);
    }

    /** Co-op duel: Take back is disabled — short note via MatchScreen overlay. */
    public static void notifyUnavailableInCoop() {
        final String msg = Localizer.getInstance().getMessage("lblTakeBackUnavailableInCoop");
        MatchController.instance.showMatchNote(msg);
    }
}
