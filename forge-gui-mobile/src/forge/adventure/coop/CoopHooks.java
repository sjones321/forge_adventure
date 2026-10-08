package forge.adventure.coop;

import forge.gamemodes.net.coop.CoopPartyProximity;
import forge.gamemodes.net.coop.CoopPartyState;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDecklistEvent;
import forge.gamemodes.net.event.coop.CoopDuelInviteEvent;
import forge.gamemodes.net.event.coop.CoopDuelResponseEvent;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
import forge.gamemodes.net.event.coop.CoopDuelStartEvent;
import forge.gamemodes.net.event.coop.CoopEnemyEncounterRequestEvent;
import forge.gamemodes.net.event.coop.CoopEnemyStateEvent;
import forge.gamemodes.net.event.coop.CoopFightLoadoutEvent;
import forge.gamemodes.net.event.coop.CoopFightRequestResultEvent;
import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;
import forge.gamemodes.net.event.coop.CoopHostPresenceEvent;
import forge.gamemodes.net.event.coop.CoopLocationExitEvent;
import forge.gamemodes.net.event.coop.CoopLocationInviteEvent;
import forge.gamemodes.net.event.coop.CoopLocationResponseEvent;
import forge.gamemodes.net.event.coop.CoopNodeStateEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;
import forge.gamemodes.net.event.coop.CoopPoiChangeEvent;

/**
 * Extension points for CO2 (shared overworld) and CO3 (co-op duels).
 *
 * <h2>CO3-stable entry points</h2>
 * Keep these signatures stable:
 * <ul>
 *   <li>{@link #notifyFightAboutToStart(String)} — host about to start a local fight</li>
 *   <li>{@link #notifyGuestEnemyEncounter(long, String, String)} /
 *       {@link GuestEnemyEncounterHandler} — guest collided with a mirrored
 *       host enemy; host/CO3 decides (guest never starts a local fight)</li>
 *   <li>{@link CoopPartyState#inParty()} and
 *       {@link CoopPartyState#withinRadius(float, float, float, float, float, float)}
 *       — whether the partner is in party and nearby; CO3 also reads them through
 *       {@link #getPartyProximity()} after {@link #setPartyProximity} is wired</li>
 * </ul>
 *
 * <p>CO2 sends one encounter request per mob per contact and rate-limits on the
 * host. Guest-local request ids are positive; host-local ids are negative and
 * stay off the wire.
 */
public final class CoopHooks {
    private CoopHooks() {
    }

    /** Listener for CO2 overworld gameplay messages. */
    public interface OverworldListener {
        default void onPlayerMove(CoopPlayerMoveEvent event) {
        }

        default void onPartyInvite(CoopPartyInviteEvent event) {
        }

        default void onPartyResponse(CoopPartyResponseEvent event) {
        }

        default void onGatherRequest(CoopGatherRequestEvent event) {
        }

        default void onGatherResult(CoopGatherResultEvent event) {
        }

        default void onNodeState(CoopNodeStateEvent event) {
        }

        default void onEnemyState(CoopEnemyStateEvent event) {
        }

        default void onEnemyEncounterRequest(CoopEnemyEncounterRequestEvent event) {
        }

        default void onPoiChange(CoopPoiChangeEvent event) {
        }

        default void onLocationInvite(CoopLocationInviteEvent event) {
        }

        default void onLocationResponse(CoopLocationResponseEvent event) {
        }

        default void onLocationExit(CoopLocationExitEvent event) {
        }

        default void onHostPresence(CoopHostPresenceEvent event) {
        }

        default void onOverworldMessage(NetEvent event) {
        }
    }

    /** Listener for CO3 duel session messages. */
    public interface DuelListener {
        default void onDuelInvite(CoopDuelInviteEvent event) {
        }

        default void onDuelResponse(CoopDuelResponseEvent event) {
        }

        default void onDecklist(CoopDecklistEvent event) {
        }

        default void onFightRequestResult(CoopFightRequestResultEvent event) {
        }

        default void onFightLoadout(CoopFightLoadoutEvent event) {
        }

        default void onDuelStart(CoopDuelStartEvent event) {
        }

        default void onDuelResult(CoopDuelResultEvent event) {
        }

        default void onDuelMessage(NetEvent event) {
        }
    }

    public static boolean isWorldAuthority() {
        return CoopSession.get().getRole() == CoopSessionRole.HOST;
    }

    public static boolean isOverworldReady() {
        return forge.adventure.util.Config.ascendant()
                && CoopSession.get().getState() == CoopSession.State.READY;
    }

    public static forge.adventure.world.World activeWorld() {
        return CoopSession.get().getActiveWorld();
    }

    public static int getGamePort() {
        return CoopSession.get().getGamePort();
    }

    public static int getOverworldPort() {
        return CoopSession.get().getOverworldPort();
    }

    // ---- CO3-stable fight hooks ----

    /**
     * Called when a local overworld fight is about to start (host path).
     * @return true if the fight start was deferred (e.g. waiting on a join prompt)
     */
    public interface FightStartHook {
        boolean onFightAboutToStart(String encounterId);
    }

    private static volatile FightStartHook fightStartHook;
    private static volatile GuestEnemyEncounterHandler guestEnemyEncounterHandler;
    private static volatile CoopPartyProximity partyProximity = CoopPartyProximity.NEVER;

    public static void setFightStartHook(final FightStartHook hook) {
        fightStartHook = hook;
    }

    public static FightStartHook getFightStartHook() {
        return fightStartHook;
    }

    public static boolean notifyFightAboutToStart(final String encounterId) {
        final FightStartHook hook = fightStartHook;
        if (hook == null || !isOverworldReady()) {
            return false;
        }
        try {
            return hook.onFightAboutToStart(encounterId);
        } catch (final Exception ignored) {
            return false;
        }
    }

    /**
     * CO3 hook: guest collided with a host-authoritative enemy. Guest is a pure
     * mirror and never starts a local duel — it sends
     * {@link CoopEnemyEncounterRequestEvent}; the host (or this handler) decides.
     *
     * @return true if CO3 handled / deferred the encounter
     */
    public interface GuestEnemyEncounterHandler {
        boolean onGuestEnemyEncounter(long enemyId, String enemyDataId, String guestName);
    }

    public static void setGuestEnemyEncounterHandler(final GuestEnemyEncounterHandler handler) {
        guestEnemyEncounterHandler = handler;
    }

    public static GuestEnemyEncounterHandler getGuestEnemyEncounterHandler() {
        return guestEnemyEncounterHandler;
    }

    /**
     * @param enemyId host-assigned coop enemy id (CO2 registry; positive)
     * @param enemyDataId enemy data name/id (not a deck or texture)
     * @param guestName claiming guest
     * @return true if a registered CO3 handler consumed the encounter
     */
    public static boolean notifyGuestEnemyEncounter(final long enemyId, final String enemyDataId,
                                                    final String guestName) {
        final GuestEnemyEncounterHandler handler = guestEnemyEncounterHandler;
        if (handler == null || !isOverworldReady() || !isWorldAuthority()) {
            return false;
        }
        try {
            return handler.onGuestEnemyEncounter(enemyId, enemyDataId, guestName);
        } catch (final Exception ignored) {
            return false;
        }
    }

    /** Party state for CO3 nearby / in-party checks (CO2). */
    public static CoopPartyState partyState() {
        return CoopOverworldRuntime.get().getParty();
    }

    /**
     * Wire CO3's proximity reader. Prefer
     * {@code () -> partyState().inParty() && partyState().withinRadius(...)} from
     * {@code CoopDuelRuntime.attach()}. Default {@link CoopPartyProximity#NEVER}.
     */
    public static void setPartyProximity(final CoopPartyProximity proximity) {
        partyProximity = proximity != null ? proximity : CoopPartyProximity.NEVER;
    }

    public static CoopPartyProximity getPartyProximity() {
        final CoopPartyProximity p = partyProximity;
        return p != null ? p : CoopPartyProximity.NEVER;
    }
}
