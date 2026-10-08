package forge.adventure.coop;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDecklistEvent;
import forge.gamemodes.net.event.coop.CoopDuelInviteEvent;
import forge.gamemodes.net.event.coop.CoopDuelResponseEvent;
import forge.gamemodes.net.event.coop.CoopEnemyStateEvent;
import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;
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
 * <p>CO1 owns the session, version check, character ownership and world
 * handshake. CO2 registers {@link OverworldListener} and sends the reserved
 * NetEvent types — it must not invent a second connection.
 *
 * <h2>World-state ownership</h2>
 * The host is authoritative for enemies, nodes, POI changes and loot. Guests
 * send requests; the host confirms. Character state (collection, decks, skills,
 * materials, items) stays on each peer's disk.
 *
 * <h2>CO2 — Shared overworld</h2>
 * <ul>
 *   <li>Send {@link CoopPlayerMoveEvent} at 10–20 Hz; each peer draws a partner
 *       sprite with name tag (avatar id, never textures).</li>
 *   <li>Party: {@link CoopPartyInviteEvent} / {@link CoopPartyResponseEvent}
 *       (opt-in; never move or pull a player without ACCEPT).</li>
 *   <li>World mutations: {@link CoopGatherRequestEvent} /
 *       {@link CoopGatherResultEvent}, {@link CoopNodeStateEvent},
 *       {@link CoopEnemyStateEvent}, {@link CoopPoiChangeEvent}.</li>
 *   <li>Locations: {@link CoopLocationInviteEvent} /
 *       {@link CoopLocationResponseEvent}.</li>
 * </ul>
 *
 * <h2>CO3 — Co-op duels</h2>
 * <ul>
 *   <li>Opt-in fight join via {@link CoopDuelInviteEvent} /
 *       {@link CoopDuelResponseEvent} when party partners are nearby.</li>
 *   <li>Exchange decks only as text ({@link CoopDecklistEvent} /
 *       {@link forge.deck.io.DeckSerializer}).</li>
 *   <li>Host builds the match with both humans on team 0 using the existing
 *       game port ({@link forge.gamemodes.net.coop.CoopPorts#GAME_PORT}) and
 *       {@code FServerManager} / {@code RemoteClientGuiGame}.</li>
 * </ul>
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

        default void onPoiChange(CoopPoiChangeEvent event) {
        }

        default void onLocationInvite(CoopLocationInviteEvent event) {
        }

        default void onLocationResponse(CoopLocationResponseEvent event) {
        }

        /** Catch-all for future CO2 world-authority events. */
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

        default void onDuelMessage(NetEvent event) {
        }
    }

    /**
     * Whether the local peer may mutate shared world state. Always true for the
     * host; guests must send requests instead.
     */
    public static boolean isWorldAuthority() {
        return CoopSession.get().getRole() == CoopSessionRole.HOST;
    }

    /** True when an authenticated co-op session is ready for overworld traffic. */
    public static boolean isOverworldReady() {
        return forge.adventure.util.Config.ascendant()
                && CoopSession.get().getState() == CoopSession.State.READY;
    }

    /**
     * World the session should render/simulate. Guests use a dedicated session
     * world so the host map never overwrites their local WorldSave.
     */
    public static forge.adventure.world.World activeWorld() {
        return CoopSession.get().getActiveWorld();
    }

    /** Game-port handle reserved for CO3 — not started in CO1/CO2. */
    public static int getGamePort() {
        return CoopSession.get().getGamePort();
    }

    public static int getOverworldPort() {
        return CoopSession.get().getOverworldPort();
    }

    /**
     * Clean hook for CO3: called when a local overworld fight is about to start.
     * CO2 leaves this as a no-op registration point so duel code stays untouched.
     */
    public interface FightStartHook {
        /**
         * @return true if the fight start was deferred (e.g. waiting on a join
         *         prompt); false to proceed with a normal solo fight.
         */
        boolean onFightAboutToStart(String encounterId);
    }

    private static volatile FightStartHook fightStartHook;

    public static void setFightStartHook(final FightStartHook hook) {
        fightStartHook = hook;
    }

    public static FightStartHook getFightStartHook() {
        return fightStartHook;
    }

    /** @return true if CO3 (or a stub) deferred the fight */
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
}
