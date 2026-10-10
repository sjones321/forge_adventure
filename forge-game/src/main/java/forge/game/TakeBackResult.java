package forge.game;

/**
 * Outcome of {@link Game#takeBack(forge.game.player.Player)} (DS4).
 */
public enum TakeBackResult {
    /** Snapshot restored; take-back eligibility cleared. */
    SUCCESS,
    /** No retained snapshot, wrong player, epoch mismatch, or take-back disabled. */
    NOT_AVAILABLE,
    /** Restore threw; current board was re-applied from a backup. */
    RESTORE_FAILED,
    /**
     * Restore threw and the backup restore also failed — the duel must end cleanly;
     * the board may be half-restored.
     */
    CATASTROPHIC
}
