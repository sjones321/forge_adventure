package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2/CO3 hook: guest collided with a host-authoritative enemy. Guest is a pure
 * mirror — it never starts a local fight. Host (or CO3 via
 * {@code CoopHooks.GuestEnemyEncounterHandler}) decides.
 */
public class CoopEnemyEncounterRequestEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final long enemyId;
    private final String enemyDataId;
    private final float guestX;
    private final float guestY;

    public CoopEnemyEncounterRequestEvent(final long requestId, final long enemyId,
                                          final String enemyDataId, final float guestX, final float guestY) {
        this.requestId = requestId;
        this.enemyId = enemyId;
        this.enemyDataId = enemyDataId == null ? "" : enemyDataId;
        this.guestX = guestX;
        this.guestY = guestY;
    }

    public long getRequestId() {
        return requestId;
    }

    public long getEnemyId() {
        return enemyId;
    }

    public String getEnemyDataId() {
        return enemyDataId;
    }

    public float getGuestX() {
        return guestX;
    }

    public float getGuestY() {
        return guestY;
    }
}
