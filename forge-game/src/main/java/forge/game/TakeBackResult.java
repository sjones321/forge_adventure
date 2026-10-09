package forge.game;

/**
 * Outcome of {@link Game#takeBack(forge.game.player.Player)} (DS4).
 */
public enum TakeBackResult {
    /** Snapshot restored; take-back eligibility cleared. */
    SUCCESS,
    /** No retained snapshot, wrong player, or take-back disabled. */
    NOT_AVAILABLE,
    /** Restore threw; current board was re-applied from a backup. */
    RESTORE_FAILED
}
