package forge.adventure.character;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.*;
import com.badlogic.gdx.utils.Array;
import com.google.common.collect.ImmutableList;
import forge.Forge;
import forge.adventure.data.ConfigData;
import forge.adventure.scene.TileMapScene;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.Paths;
import forge.adventure.world.PlaneFormat;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.WorldSave;
import forge.toolbox.FOptionPane;

import java.util.HashMap;
import java.util.List;

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
            PlaneMeta targetMeta = save.getMultiverse().getMeta(id);
            boolean firstOpen = !save.getMultiverse().hasCompressedBlob(id)
                    && !id.equals(save.getMultiverse().getCurrentPlaneId());
            // Package K: first open of a set plane asks for format (default = current plane).
            if (firstOpen && targetMeta != null && PlaneFormat.raw(targetMeta).isEmpty()
                    && !PlaneMeta.HOME_ID.equals(id)) {
                final String planeIdFinal = id;
                promptPlaneFormat(targetMeta, () -> continueTravelAfterFormat(save, planeIdFinal, true));
                return true;
            }
            // Legacy / home: planes without a format get a sensible default (no dialog).
            if (targetMeta != null && PlaneFormat.raw(targetMeta).isEmpty()) {
                PlaneFormat.setPlaneFormat(targetMeta, PlaneFormat.resolveCurrent());
            }
            if (firstOpen) {
                final String planeIdFinal = id;
                try {
                    materializePlaneWithLoadingScreen(save, planeIdFinal,
                            () -> finishPortalTravel(planeIdFinal));
                } catch (Exception e) {
                    notifyPortal("Could not create plane: "
                            + (e.getMessage() != null ? e.getMessage() : "unknown error"));
                }
                return true;
            }
            return finishPortalTravel(id);
        } catch (Exception e) {
            notifyPortal("Portal failed: " + (e.getMessage() != null ? e.getMessage() : "unknown error"));
            return false;
        }
    }

    private void continueTravelAfterFormat(WorldSave save, String planeId, boolean materialize) {
        try {
            if (materialize) {
                materializePlaneWithLoadingScreen(save, planeId, () -> finishPortalTravel(planeId));
            } else {
                finishPortalTravel(planeId);
            }
        } catch (Exception e) {
            notifyPortal("Could not create plane: "
                    + (e.getMessage() != null ? e.getMessage() : "unknown error"));
        }
    }

    /**
     * Package K portal dialog: pick this plane's format (fixed once chosen).
     * Default selection matches the plane you came from.
     */
    private void promptPlaneFormat(PlaneMeta targetMeta, Runnable onChosen) {
        String from = PlaneFormat.resolveCurrent();
        List<String> options = ImmutableList.copyOf(PlaneFormat.CHOICES);
        int defaultIdx = 0;
        for (int i = 0; i < PlaneFormat.CHOICES.length; i++) {
            if (PlaneFormat.fromChoiceLabel(PlaneFormat.CHOICES[i]).equals(from)) {
                defaultIdx = i;
                break;
            }
        }
        String dest = targetMeta.getDisplayName() != null ? targetMeta.getDisplayName() : targetMeta.getId();
        String msg = "Choose the format for " + dest + ".\n"
                + "Enemies, gyms and events on this plane will use it.\n"
                + "Default: " + PlaneFormat.displayName(from) + " (this plane).";
        FOptionPane.showOptionDialog(msg, "Plane format", FOptionPane.QUESTION_ICON,
                options, defaultIdx, result -> {
                    if (result == null || result < 0 || result >= PlaneFormat.CHOICES.length) {
                        notifyPortal("Portal cancelled.");
                        return;
                    }
                    String chosen = PlaneFormat.fromChoiceLabel(PlaneFormat.CHOICES[result]);
                    PlaneFormat.setPlaneFormat(targetMeta, chosen);
                    notifyPortal("Format: " + PlaneFormat.displayName(chosen));
                    if (onChosen != null) {
                        onChosen.run();
                    }
                });
    }

    /**
     * Loading screen + {@link WorldSave#materializeSetPlane} — same path portal travel uses.
     *
     * @param afterMaterialize optional work after a successful materialize (still inside the loading runnable)
     * @return whether a loading screen was requested
     */
    public static boolean materializePlaneWithLoadingScreen(WorldSave save, String planeId,
                                                            Runnable afterMaterialize) {
        if (save == null || planeId == null || planeId.isEmpty()) {
            return false;
        }
        return materializePlaneWithLoadingScreen(() -> {
            save.materializeSetPlane(planeId);
            if (afterMaterialize != null) {
                afterMaterialize.run();
            }
        });
    }

    /**
     * Portal loading-screen wrapper around materialize work. Production passes
     * {@code () -> save.materializeSetPlane(id)}; tests inject a stand-in when
     * {@link WorldSave} cannot initialize headless.
     */
    public static boolean materializePlaneWithLoadingScreen(Runnable materializeWork) {
        if (materializeWork == null) {
            return false;
        }
        String loadingMsg = "Opening a portal…";
        try {
            if (Forge.getLocalizer() != null) {
                final String localized = Forge.getLocalizer().getMessage("lblGeneratingWorld");
                if (localized != null && !localized.isEmpty()) {
                    loadingMsg = localized;
                }
            }
        } catch (Throwable ignored) {
            // Headless / missing bundle
        }
        final String msg = loadingMsg;
        final Exception[] failure = new Exception[1];
        final boolean shown = forge.adventure.world.SetPlaneLoading.runWithLoadingScreen(msg, () -> {
            try {
                materializeWork.run();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw new IllegalStateException(failure[0].getMessage() != null
                    ? failure[0].getMessage() : "materializeSetPlane failed", failure[0]);
        }
        return shown;
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
        String fmt = "";
        try {
            PlaneMeta meta = save.getMultiverse() != null ? save.getMultiverse().getMeta(id) : null;
            if (meta != null) {
                fmt = " (" + PlaneFormat.displayName(PlaneFormat.resolve(meta)) + ")";
            }
        } catch (Exception ignored) {
        }
        notifyPortal("Planeswalked to " + arrivalDisplayName(save, id) + fmt);
        return true;
    }

    /** Prefer set display name (MV2) over raw plane id / meta label. */
    public static String arrivalDisplayName(WorldSave save, String planeId) {
        try {
            if (save != null && save.getMultiverse() != null) {
                PlaneMeta meta = save.getMultiverse().getMeta(planeId);
                if (meta == null) {
                    meta = save.getMultiverse().getCurrentMeta();
                }
                if (meta != null) {
                    String code = meta.getSetCode();
                    if (code == null || code.isEmpty()) {
                        code = forge.adventure.world.SetPlaneGenerator.setCodeFromPlaneId(meta.getId());
                    }
                    if (code != null && !code.isEmpty()) {
                        return forge.adventure.world.SetPlaneGenerator.displayNameForSet(code);
                    }
                    if (meta.getDisplayName() != null && !meta.getDisplayName().isEmpty()) {
                        return meta.getDisplayName();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        String fromId = forge.adventure.world.SetPlaneGenerator.setCodeFromPlaneId(planeId);
        if (!fromId.isEmpty()) {
            return forge.adventure.world.SetPlaneGenerator.displayNameForSet(fromId);
        }
        return planeId != null ? planeId : "plane";
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
