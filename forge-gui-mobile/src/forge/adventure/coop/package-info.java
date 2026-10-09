/**
 * Ascendant co-op: CO1 session/connection, CO3 duels, and CO5 world-bound partners.
 *
 * <p>Host owns the world save and the guest's partner character ({@link WorldPartners}).
 * Guests play a partner keyed by {@link CoopProfileId}; their solo save is never
 * read, written, stashed or restored by a session. Overworld TCP traffic uses port
 * {@link forge.gamemodes.net.coop.CoopPorts#OVERWORLD_PORT}; joined duels start
 * {@code FServerManager} on {@link forge.gamemodes.net.coop.CoopPorts#GAME_PORT}
 * only for the fight, bound to {@code coopBindAddress} when set.
 *
 * <p>CO2 (shared overworld) and CO3 plug in through {@link CoopHooks}.
 */
package forge.adventure.coop;
