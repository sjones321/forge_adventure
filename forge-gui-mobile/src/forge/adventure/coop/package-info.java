/**
 * Ascendant co-op session and connection layer (roadmap package CO1).
 *
 * <p>Host owns the world save; each peer keeps their own {@code AdventurePlayer}
 * locally. Overworld TCP traffic uses port
 * {@link forge.gamemodes.net.coop.CoopPorts#OVERWORLD_PORT}; duel traffic will
 * reuse {@link forge.gamemodes.net.coop.CoopPorts#GAME_PORT} in CO3.
 *
 * <p>CO2 (shared overworld) and CO3 (co-op duels) plug in through
 * {@link forge.adventure.coop.CoopHooks} and the reserved NetEvent types in
 * {@code forge.gamemodes.net.event.coop}.
 */
package forge.adventure.coop;
