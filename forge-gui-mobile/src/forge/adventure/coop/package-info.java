/**
 * Ascendant co-op: CO1 session/connection and CO3 co-op duels.
 *
 * <p>Host owns the world save; each peer keeps their own {@code AdventurePlayer}
 * locally. Overworld TCP traffic uses port
 * {@link forge.gamemodes.net.coop.CoopPorts#OVERWORLD_PORT}; joined duels start
 * {@code FServerManager} on {@link forge.gamemodes.net.coop.CoopPorts#GAME_PORT}
 * only for the fight, bound to {@code coopBindAddress} when set.
 *
 * <p>CO2 (shared overworld) and CO3 plug in through
 * {@link forge.adventure.coop.CoopHooks}. Stable CO3 entry points matching PR #18:
 * {@code notifyFightAboutToStart}, {@code notifyGuestEnemyEncounter} /
 * {@code GuestEnemyEncounterHandler}, and party proximity via
 * {@code CoopPartyProximity} (wired to {@code CoopPartyState} after #18 merges).
 */
package forge.adventure.coop;
