/**
 * Ascendant co-op wire events (CO1 session/connection).
 *
 * <p>These travel the existing Netty + {@code CompatibleObjectEncoder} pipeline
 * on the overworld port ({@link forge.gamemodes.net.coop.CoopPorts#OVERWORLD_PORT}).
 * Never put {@code PaperCard} or {@code Deck} graphs on the wire — send decklists
 * as text via {@link forge.deck.io.DeckSerializer} ({@link CoopDecklistEvent}).
 *
 * <p>Registered for the multiplayer class filter in {@code WireClassFilter}
 * (package {@code forge.gamemodes.net.event.coop} is under the {@code forge.}
 * allowlist prefix; exact names are also listed there for documentation).
 *
 * <h2>CO2 / CO3</h2>
 * CO2 events in this package: player move, party invite/response, gather
 * request/result, node/enemy/POI state, location invite/response. Handled by
 * {@code CoopOverworldRuntime} via {@code CoopSession} listeners. Host remains
 * authoritative for world state; guest sends requests only. CO3 duel events
 * stay stubs until the duel package lands.
 */
package forge.gamemodes.net.event.coop;
