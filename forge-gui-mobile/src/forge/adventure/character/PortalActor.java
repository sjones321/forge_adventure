package forge.adventure.character;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.data.ConfigData;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.Paths;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.WorldSave;
import forge.screens.TransitionScreen;

import java.util.HashMap;

/**
 * PortalActor
 * Extension of EntryActor, visible on map, multiple states that change behavior
 */
public class PortalActor extends EntryActor {
    private final HashMap<PortalAnimationTypes, Animation<TextureRegion>> animations = new HashMap<>();
    private Animation<TextureRegion> currentAnimation = null;
    private PortalAnimationTypes currentAnimationType = PortalAnimationTypes.Closed;

    float timer;
    float transitionTimer;

    private static final Color batchColor = new Color();
    private static final HashMap<String, PortalAnimationTypes> animationTypeMap = new HashMap<>(16);

    static {
        for (PortalAnimationTypes type : PortalAnimationTypes.values()) {
            animationTypeMap.put(type.name().toLowerCase(), type);
        }
    }

    public PortalActor(MapStage stage, int id, String targetMap, float x, float y, float w, float h, String direction, String currentMap, int portalTargetObject, String path) {
        super(stage, id, targetMap, x, y, w, h, direction, currentMap, portalTargetObject);
        load(path);
    }

    public MapStage getMapStage() {
        return stage;
    }

    @Override
    public void onPlayerCollide() {
        if (currentAnimationType == PortalAnimationTypes.Inactive) {
            //Activate portal? Launch Dialog?
        }
        if (currentAnimationType == PortalAnimationTypes.Active) {
            if (Config.ascendant() && Current.player() != null && Current.player().isOverloaded()) {
                try {
                    GameHUD.getInstance().addNotification(
                            "Overloaded — clear Overflow before using portals.");
                } catch (Exception ignored) {
                    // HUD may be unavailable
                }
                return;
            }
            if (Config.ascendant() && targetPlane != null && !targetPlane.isEmpty()) {
                if (travelToPlane(targetPlane)) {
                    stage.getPlayerSprite().playEffect(Paths.EFFECT_TELEPORT, 0.5f);
                    stage.startPause(1.5f);
                }
                return;
            }
            if (targetMap == null || targetMap.isEmpty()) {
                stage.exitDungeon(false, false);
            } else {
                if (targetMap.equals(currentMap)) {
                    stage.spawn(entryTargetObject);
                    stage.getPlayerSprite().playEffect(Paths.EFFECT_TELEPORT, 0.5f);
                    stage.startPause(1.5f);
                } else {
                    currentMap = targetMap;
                    TileMapScene.instance().loadNext(targetMap, entryTargetObject);
                    stage.getPlayerSprite().playEffect(Paths.EFFECT_TELEPORT, 0.5f);
                }
            }
        }
    }

    /**
     * MV1 planar travel: eligibility + target readiness run before leaving the interior.
     * A missing plane (or missing blob) must fail before {@code exitDungeon}.
     */
    private boolean travelToPlane(String planeId) {
        WorldSave save = WorldSave.getCurrentSave();
        if (save == null) {
            return false;
        }
        String id = planeId.trim();
        if (id.isEmpty()) {
            return false;
        }
        if (PlaneMeta.HOME_ID.equalsIgnoreCase(id)) {
            id = PlaneMeta.HOME_ID;
        }
        // Eligibility BEFORE exitDungeon — overload / guest / missing plane must not eject the player.
        if (Config.ascendant() && Current.player() != null && Current.player().isOverloaded()) {
            notifyPortal("Overloaded — clear Overflow before using portals.");
            return false;
        }
        if (!forge.adventure.coop.CoopSession.get().canInitiatePlaneSwitch()) {
            notifyPortal("Guests cannot planeswalk — follow the host.");
            return false;
        }
        try {
            // MV2: alignment check before any mutation (no charge yet).
            String alignErr = forge.adventure.world.SetPlaneRules.checkTravel(id, Current.player(), false);
            if (alignErr != null) {
                notifyPortal(alignErr);
                return false;
            }
            if (!save.getMultiverse().hasPlane(id)) {
                ConfigData cfg = Config.instance().getConfigData();
                if (cfg != null && cfg.planarPortalAutoCreate && !PlaneMeta.HOME_ID.equals(id)) {
                    // Register only; materialize below on the GL/UI path with loading feel.
                    save.ensureSetPlane(id, id, false);
                } else {
                    notifyPortal("Unknown plane: " + id);
                    return false;
                }
            }
            if (!save.canTravelToPlane(id)) {
                String err = save.getLastPlaneSwitchError();
                notifyPortal(err != null && !err.isEmpty() ? err : "Could not travel to " + id);
                return false;
            }
            // Deferred MV2 gen: loading screen; World/GL work stays on the GL thread.
            if (!save.getMultiverse().hasCompressedBlob(id)
                    && !id.equals(save.getMultiverse().getCurrentPlaneId())) {
                final String planeIdFinal = id;
                final String loadingMsg = Forge.getLocalizer() != null
                        ? Forge.getLocalizer().getMessage("lblGeneratingWorld")
                        : "Opening a portal…";
                try {
                    Forge.setTransitionScreen(new TransitionScreen(() -> {
                        try {
                            save.materializeSetPlane(planeIdFinal);
                            finishPortalTravel(planeIdFinal);
                        } catch (Exception e) {
                            notifyPortal("Could not create plane: "
                                    + (e.getMessage() != null ? e.getMessage() : "unknown error"));
                        } finally {
                            try {
                                Forge.clearTransitionScreen();
                            } catch (Exception ignored) {
                            }
                        }
                    }, null, false, true, loadingMsg));
                    return true;
                } catch (Exception e) {
                    // TransitionScreen unavailable — still materialize on this (GL) thread.
                    try {
                        save.materializeSetPlane(id);
                    } catch (Exception genEx) {
                        notifyPortal("Could not create plane: "
                                + (genEx.getMessage() != null ? genEx.getMessage() : "unknown error"));
                        return false;
                    }
                }
            }
            return finishPortalTravel(id);
        } catch (Exception e) {
            notifyPortal("Portal failed: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
            return false;
        }
    }

    /**
     * Charge gold, leave the POI, and switch planes. Called after any deferred
     * materialize has finished (possibly behind a loading screen).
     */
    private boolean finishPortalTravel(String id) {
        WorldSave save = WorldSave.getCurrentSave();
        if (save == null) {
            return false;
        }
        // Charge before persisting the switch; refund if switch fails.
        int charged = forge.adventure.world.SetPlaneRules.chargePortalGold(id, Current.player());
        if (charged < 0) {
            notifyPortal(forge.adventure.world.SetPlaneRules.paymentFailureMessage(id, Current.player()));
            return false;
        }
        if (stage != null && stage.isInMap()) {
            stage.exitDungeon(false, false);
        }
        if (!save.switchPlane(id)) {
            forge.adventure.world.SetPlaneRules.refundPortalGold(Current.player(), charged);
            String err = save.getLastPlaneSwitchError();
            notifyPortal(err != null && !err.isEmpty() ? err : "Could not travel to " + id);
            return false;
        }
        // GameScene.enter() happens exactly once inside switchPlane.
        String dest = save.getMultiverse().getCurrentMeta() != null
                ? save.getMultiverse().getCurrentMeta().getDisplayName() : id;
        notifyPortal("Planeswalked to " + dest);
        return true;
    }

    private void notifyPortal(String msg) {
        if (msg == null || msg.isEmpty()) {
            return;
        }
        try {
            GameHUD.getInstance().addNotification(msg);
        } catch (Exception ignored) {
            // HUD may be unavailable
        }
    }

    public void spawn() {
        switch (direction) {
            case "up":
                stage.getPlayerSprite().setPosition(x + w / 2 - stage.getPlayerSprite().getWidth() / 2, y + h);
                break;
            case "down":
                stage.getPlayerSprite().setPosition(x + w / 2 - stage.getPlayerSprite().getWidth() / 2, y - stage.getPlayerSprite().getHeight());
                break;
            case "right":
                stage.getPlayerSprite().setPosition(x - stage.getPlayerSprite().getWidth(), y + h / 2 - stage.getPlayerSprite().getHeight() / 2);
                break;
            case "left":
                stage.getPlayerSprite().setPosition(x + w, y + h / 2 - stage.getPlayerSprite().getHeight() / 2);
                break;
        }
    }

    protected void load(String path) {
        if (path == null || path.isEmpty()) return;
        animations.clear();
        for (PortalAnimationTypes stand : PortalAnimationTypes.values()) {
            Array<Sprite> anim = Config.instance().getAnimatedSprites(path, stand.toString());
            if (anim.size != 0) {
                animations.put(stand, new Animation<>(0.2f, anim));
                if (getWidth() == 0.0f)//init size onload
                {
                    setWidth(anim.first().getWidth());
                    setHeight(anim.first().getHeight());
                }
            }
        }

        setAnimation(PortalAnimationTypes.Closed);
        updateAnimation();
    }

    public void setAnimation(PortalAnimationTypes type) {
        if (currentAnimationType != type) {
            currentAnimationType = type;
            updateAnimation();
        }
    }

    public void setAnimation(String typeName) {
        if (typeName == null) return;

        PortalAnimationTypes animationType = animationTypeMap.get(typeName.toLowerCase());
        if (animationType != null) {
            setAnimation(animationType);
        }
    }

    public String getAnimation() {
        return currentAnimationType.toString().toLowerCase();
    }

    private void updateAnimation() {
        PortalAnimationTypes aniType = currentAnimationType;
        if (!animations.containsKey(aniType)) {
            aniType = PortalAnimationTypes.Inactive;
        }
        if (!animations.containsKey(aniType)) {
            return;
        }
        currentAnimation = animations.get(aniType);
    }

    public enum PortalAnimationTypes {
        Closed,
        Active,
        Inactive,
        Opening,
        Closing
    }

    public void act(float delta) {
        timer += delta;
        super.act(delta);
    }

    public void draw(Batch batch, float parentAlpha) {
        if (currentAnimation == null) {
            return;
        }
        super.draw(batch, parentAlpha);
        beforeDraw(batch, parentAlpha);

        TextureRegion currentFrame;
        if (currentAnimationType.equals(PortalAnimationTypes.Opening) || currentAnimationType.equals(PortalAnimationTypes.Closing)) {
            currentFrame = currentAnimation.getKeyFrame(transitionTimer, false);
        } else {
            currentFrame = currentAnimation.getKeyFrame(timer, true);
        }

        setHeight(currentFrame.getRegionHeight());
        setWidth(currentFrame.getRegionWidth());

        Color oldColor = batch.getColor();
        batchColor.set(oldColor.r, oldColor.g, oldColor.b, oldColor.a);

        batch.setColor(getColor());
        batch.draw(currentFrame, getX(), getY(), getWidth(), getHeight());
        batch.setColor(batchColor);

        super.draw(batch, parentAlpha);
    }
}
