package forge.adventure.character;

import com.badlogic.gdx.math.Vector2;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.scene.Scene;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;

/**
 * Class that will represent the player sprite on the map
 */
public class PlayerSprite extends CharacterSprite {
    private final float playerSpeed;
    private final Vector2 direction = new Vector2();
    private float playerSpeedModifier = 1f;
    private float playerSpeedEquipmentModifier = 1f;
    GameStage gameStage;

    private final Vector2 prevDirection = new Vector2();

    public PlayerSprite(GameStage gameStage) {
        super(AdventurePlayer.current().spriteName());
        this.gameStage = gameStage;
        setOriginX(getWidth() / 2);
        Current.player().onPlayerChanged(PlayerSprite.this::updatePlayer);

        playerSpeed = Config.instance().getConfigData().playerBaseSpeed;

        //Attach signals here.
        Current.player().onBlessing(() -> playerSpeedEquipmentModifier = Current.player().equipmentSpeed());
        Current.player().onEquipmentChanged(() -> playerSpeedEquipmentModifier = Current.player().equipmentSpeed());
    }

    private void updatePlayer() {
        load(AdventurePlayer.current().spriteName());
        playerSpeedEquipmentModifier = AdventurePlayer.current().equipmentSpeed();
    }

    public void LoadPos() {
        setPosition(AdventurePlayer.current().getWorldPosX(), AdventurePlayer.current().getWorldPosY());
    }

    public void storePos() {
        storePos(getX(), getY());
    }

    public void storePos(final float x, final float y) {
        AdventurePlayer.current().setWorldPosX(x);
        AdventurePlayer.current().setWorldPosY(y);
    }

    public Vector2 getMovementDirection() {
        return direction;
    }

    public void setMovementDirection(final Vector2 dir) {
        direction.set(dir);
    }

    public void setMoveModifier(float speed) {
        playerSpeedModifier = speed;
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        if (Forge.advFreezePlayerControls)
            return;

        float roadBonus = roadSpeedBonus();
        direction.setLength(playerSpeed * delta * playerSpeedModifier * playerSpeedEquipmentModifier * roadBonus);
        prevDirection.set(direction);
        Scene previousScene = forge.Forge.getCurrentScene();

        if(!direction.isZero()) {
            gameStage.prepareCollision(pos(), direction, boundingRect);
            direction.set(gameStage.adjustMovement(direction, boundingRect));
            moveBy(direction.x, direction.y);

            // If the player is blocked by an obstacle, and they haven't changed scenes,
            // they will keep trying to move in that direction
            if (previousScene == forge.Forge.getCurrentScene()) {
                direction.set(prevDirection);
            }
        }
    }

    public boolean isMoving() {
        return !direction.isZero();
    }

    public void stop() {
        direction.setZero();
        setAnimation(AnimationTypes.Idle);
    }

    public void setPosition(Vector2 oldPosition) {
        setPosition(oldPosition.x, oldPosition.y);
    }

    /** Ascendant-only road bonus on the overworld; 1f elsewhere / stock worlds. */
    private float roadSpeedBonus() {
        if (!Config.ascendant() || !(gameStage instanceof WorldStage))
            return 1f;
        World world = WorldSave.getCurrentSave().getWorld();
        if (world == null || world.getData() == null)
            return 1f;
        int tileX = (int) ((getX() + getWidth() / 2f) / world.getTileSize());
        int tileY = (int) (getY() / world.getTileSize());
        if (!world.isRoad(tileX, tileY))
            return 1f;
        float bonus = Config.instance().getConfigData().roadSpeedBonus;
        return bonus > 0f ? bonus : 1f;
    }
}
