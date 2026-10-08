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
 * <h2>CO2 / CO3 hooks</h2>
 * CO2 (shared overworld) and CO3 (co-op duels) should add further events in this
 * package and handle them through {@code CoopSession}'s message listeners.
 * Host remains authoritative for world state; guest sends requests only.
 */
package forge.gamemodes.net.event.coop;
