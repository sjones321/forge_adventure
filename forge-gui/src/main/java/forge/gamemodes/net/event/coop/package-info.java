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
 * authoritative for world state; guest sends requests only.
 *
 * <p>CO3 duel coordination (invite / loadout / start / result /
 * {@link CoopEnemyEncounterRequestEvent}) travels the overworld port. The Magic
 * match itself uses {@link forge.gamemodes.net.coop.CoopPorts#GAME_PORT} via
 * {@code FServerManager} / {@code FGameClient}, started only for co-op duels.
 *
 * <p>TR1 player trading (invite / response / offer / confirm / cancel / execute / ack)
 * also travels the overworld port. Offers are plain data
 * ({@link forge.gamemodes.net.coop.CoopTradeOffer}) — never {@code PaperCard} or
 * {@code ItemData} graphs. Two-phase commit: guest applies first and acks; host
 * applies only after a successful ack. Peers are identified by
 * {@link forge.gamemodes.net.coop.CoopTradeRole}.
 *
 * <p>Protocol: CO2 is 5; CO3 is 6; MV1 plane-follow ({@link CoopPlaneSwitchEvent}) is 7;
 * TR1 trade is 8.
 */
package forge.gamemodes.net.event.coop;
