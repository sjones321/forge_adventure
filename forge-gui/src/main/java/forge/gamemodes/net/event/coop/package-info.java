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
 * <p>Protocol: CO2 is 5; CO3 is 6; MV1 plane-follow ({@link CoopPlaneSwitchEvent}) is 7;
 * MV2 live-world hash + host {@link CoopPlanarGateEntry} list + {@code mv2SetCode} is 8;
 * EN2 lootRolls on {@link CoopDuelResultEvent} is 9; Package K {@code planeFormat} on
 * {@link CoopWorldOfferEvent} / {@link CoopPlaneSwitchEvent} is 10; RW1 guest loot credit
 * (enemy data id, theme id, signature candidates) on {@link CoopDuelResultEvent} is 11.
 * DS4 take-back is single-player only — no co-op wire events / keep protocol equal to base.
 */
package forge.gamemodes.net.event.coop;
