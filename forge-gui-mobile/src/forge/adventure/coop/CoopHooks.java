package forge.adventure.coop;

import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDecklistEvent;
import forge.gamemodes.net.event.coop.CoopDuelInviteEvent;
import forge.gamemodes.net.event.coop.CoopDuelResponseEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;

/**
 * Extension points for CO2 (shared overworld) and CO3 (co-op duels).
 *
 * <p>CO1 owns the session, version check, character ownership and world
 * handshake. Later packages register listeners here and send the reserved
 * NetEvent types — they must not invent a second connection.
 *
 * <h2>World-state ownership</h2>
 * The host is authoritative for enemies, nodes, POI changes and loot. Guests
 * send requests; the host confirms. Character state (collection, decks, skills,
 * materials, items) stays on each peer's disk.
 *
 * <h2>CO2 — Shared overworld</h2>
 * <ul>
 *   <li>Send {@link CoopPlayerMoveEvent} at 10–20 Hz from the guest; host
 *       mirrors the partner sprite.</li>
 *   <li>Party: {@link CoopPartyInviteEvent} / {@link CoopPartyResponseEvent}
 *       (opt-in; never move or pull a player without ACCEPT).</li>
 *   <li>World mutations: add request/confirm events in
 *       {@code forge.gamemodes.net.event.coop} and handle them only on the host.</li>
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

    /** Game-port handle reserved for CO3 — started by the host when a session opens. */
    public static int getGamePort() {
        return CoopSession.get().getGamePort();
    }

    public static int getOverworldPort() {
        return CoopSession.get().getOverworldPort();
    }
}
