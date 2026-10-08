package forge.gamemodes.net.coop;

/**
 * Mid-duel guest disconnect handling (CO3). The host's match continues; the
 * guest seat concedes (or can be marked AI-controlled). The game must not hang,
 * and the host's world stays playable.
 */
public final class CoopDuelDisconnectPolicy {
    public enum GuestDisconnectAction {
        /** Guest seat immediately loses / concedes. */
        CONCEDE,
        /** Guest seat is taken over by AI (optional path). */
        AI_TAKEOVER
    }

    public enum Outcome {
        /** No co-op duel active — ignore. */
        IGNORE,
        /** Host continues; apply guest concede / AI. */
        CONTINUE_HOST_MATCH,
        /** Guest side: abandon local match UI; wait for result or restore. */
        GUEST_ABANDON_LOCAL
    }

    private volatile boolean duelActive;
    private volatile boolean guestConnected = true;
    private volatile GuestDisconnectAction action = GuestDisconnectAction.CONCEDE;

    public void beginDuel(final GuestDisconnectAction preferred) {
        duelActive = true;
        guestConnected = true;
        action = preferred != null ? preferred : GuestDisconnectAction.CONCEDE;
    }

    public void endDuel() {
        duelActive = false;
        guestConnected = true;
    }

    public boolean isDuelActive() {
        return duelActive;
    }

    public boolean isGuestConnected() {
        return guestConnected;
    }

    public GuestDisconnectAction getAction() {
        return action;
    }

    /**
     * Host observed guest disconnect (overworld or game-port channel).
     * @return CONTINUE_HOST_MATCH when a duel was active; does not block
     */
    public Outcome onGuestDisconnected() {
        if (!duelActive) {
            return Outcome.IGNORE;
        }
        guestConnected = false;
        return Outcome.CONTINUE_HOST_MATCH;
    }

    /** Guest observed host / session drop mid-duel. */
    public Outcome onSessionLostAsGuest() {
        if (!duelActive) {
            return Outcome.IGNORE;
        }
        duelActive = false;
        guestConnected = false;
        return Outcome.GUEST_ABANDON_LOCAL;
    }

    /**
     * Whether the host should keep the world interactive after guest drop.
     * Always true — never freeze the host waiting on the guest.
     */
    public static boolean hostWorldRemainsPlayable(final Outcome outcome) {
        return outcome == Outcome.CONTINUE_HOST_MATCH || outcome == Outcome.IGNORE;
    }

    /** Whether a blocking wait on the guest is forbidden. Always true. */
    public static boolean mustNotBlockOnGuest() {
        return true;
    }
}
