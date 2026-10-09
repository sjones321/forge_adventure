package forge.gamemodes.net.coop;

import java.security.SecureRandom;

/**
 * Host-assigned globally unique TR1 trade / invite ids. Uses
 * {@link SecureRandom} so two processes cannot both mint {@code 1, 2, 3…}
 * and collide on the idempotent-apply check.
 */
public final class CoopTradeIds {
    private static final SecureRandom RANDOM = new SecureRandom();

    private CoopTradeIds() {
    }

    /** Non-zero 64-bit id. */
    public static long next() {
        long id;
        do {
            id = RANDOM.nextLong();
        } while (id == 0L);
        return id;
    }
}
