package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO2: host-authoritative enemy spawn / move / despawn. Guest applies; never
 * invents enemies. Enemy data travels as an id string, never a deck or texture.
 */
public class CoopEnemyStateEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    public enum Action { SPAWN, MOVE, DESPAWN }

    private final long enemyId;
    private final Action action;
    private final String enemyDataId;
    private final float x;
    private final float y;
    private final float facing;

    public CoopEnemyStateEvent(final long enemyId, final Action action, final String enemyDataId,
                               final float x, final float y, final float facing) {
        this.enemyId = enemyId;
        this.action = action == null ? Action.DESPAWN : action;
        this.enemyDataId = enemyDataId == null ? "" : enemyDataId;
        this.x = x;
        this.y = y;
        this.facing = facing;
    }

    public long getEnemyId() {
        return enemyId;
    }

    public Action getAction() {
        return action;
    }

    public String getEnemyDataId() {
        return enemyDataId;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getFacing() {
        return facing;
    }
}
