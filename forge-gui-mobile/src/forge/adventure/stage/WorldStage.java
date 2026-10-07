package forge.adventure.stage;

import com.badlogic.gdx.math.GridPoint2;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.utils.Timer;
import com.badlogic.gdx.utils.viewport.Viewport;
import forge.Forge;
import forge.OverlayText;
import forge.adventure.character.CharacterSprite;
import forge.adventure.character.EnemySprite;
import forge.adventure.character.ResourceNodeSprite;
import forge.adventure.data.*;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerSkills;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.DuelScene;
import forge.adventure.scene.GameScene;
import forge.adventure.scene.RewardScene;
import forge.adventure.scene.Scene;
import forge.adventure.scene.TileMapScene;
import forge.adventure.util.*;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.card.CardRarity;
import forge.gui.FThreads;
import forge.haptic.HapticEngine;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.screens.TransitionScreen;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.util.MyRandom;
import forge.util.ScreenUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.util.*;


/**
 * Stage for the over world. Will handle monster spawns
 */
public class WorldStage extends GameStage implements SaveFileContent {
    private static WorldStage instance = null;
    protected EnemySprite currentMob;
    protected Random rand = MyRandom.getRandom();
    WorldBackground background;
    private float spawnDelay = 0;
    private static final float spawnInterval = 4;//todo config
    private PointOfInterestMapSprite collidingPoint;
    protected ArrayList<Pair<Float, EnemySprite>> enemies = new ArrayList<>();
    /** Ascendant resource nodes (Package B); parallel to {@link #enemies}. */
    protected ArrayList<Pair<Float, ResourceNodeSprite>> nodes = new ArrayList<>();
    private final static Float dieTimer = 20f;//todo config
    private Float globalTimer = 0f;
    private float nodeSpawnDelay = 0f;
    private transient boolean enterSpawnPOI = false;

    // Gathering channel state (Ascendant only).
    private ResourceNodeSprite channelNode;
    private float channelElapsed;
    private float channelDuration;
    private float gatherFailNotifyCooldown;

    NavArrowActor navArrow;
    final Rectangle tempBoundingRect = new Rectangle();
    final Vector2 enemyMoveVector = new Vector2();
    boolean collided = false;
    private final Vector2 navDirectionVec = new Vector2();
    private final ArrayList<Float> cachedSaveTimeouts = new ArrayList<>(32);
    private final ArrayList<String> cachedSaveNames = new ArrayList<>(32);
    private final ArrayList<Float> cachedSaveXCoords = new ArrayList<>(32);
    private final ArrayList<Float> cachedSaveYCoords = new ArrayList<>(32);
    private final ArrayList<String> cachedSaveQuestIDs = new ArrayList<>(32);
    private final ArrayList<Float> cachedNodeTimeouts = new ArrayList<>(8);
    private final ArrayList<String> cachedNodeMaterialIds = new ArrayList<>(8);
    private final ArrayList<Float> cachedNodeXCoords = new ArrayList<>(8);
    private final ArrayList<Float> cachedNodeYCoords = new ArrayList<>(8);

    public WorldStage() {
        super();
        background = new WorldBackground(this);
        addActor(background);
        background.setZIndex(0);
        navArrow = new NavArrowActor();
        addActor(navArrow);
        navArrow.toFront();
    }

    public static WorldStage getInstance() {
        return instance == null ? instance = new WorldStage() : instance;
    }

    @Override
    protected void onActing(float delta) {
        if (isPaused() || MapStage.getInstance().isDialogOnlyInput() || Forge.advFreezePlayerControls) {
            if (Forge.advFreezePlayerControls)
                cancelGatherChannel("Interrupted!");
            return;
        }
        drawNavigationArrow();
        if (gatherFailNotifyCooldown > 0f)
            gatherFailNotifyCooldown -= delta;

        boolean moving = player.isMoving();
        boolean channeling = channelNode != null;
        // Enemies keep chasing while the player channels so a touch can cancel gathering.
        boolean updateEnemies = moving || channeling;

        if (moving) {
            handleMonsterSpawn(delta);
            handleNodeSpawn(delta);
            collided = collided || handlePointsOfInterestCollision();
        }
        if (moving || channeling)
            globalTimer += delta;

        tickNodeLifetimes();

        if (channeling) {
            if (moving) {
                cancelGatherChannel(null);
            } else {
                tickGatherChannel(delta);
            }
        }

        if (updateEnemies) {
            for (int i = 0; i < enemies.size(); i++) {
                Pair<Float, EnemySprite> pair = enemies.get(i);
                if (globalTimer >= pair.getKey() + pair.getValue().getLifetime()) {
                    AdventureQuestController.instance().updateDespawn(pair.getValue());
                    AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                    foregroundSprites.removeActor(pair.getValue());
                    enemies.remove(i);
                    i--; // index pointer after index reduction step
                    continue;
                }
                EnemySprite mob = pair.getValue();

                if (!currentModifications.containsKey(PlayerModification.Hide)) {
                    enemyMoveVector.set(player.getX(), player.getY()).sub(mob.pos());
                    enemyMoveVector.setLength(mob.speed() * delta);
                    tempBoundingRect.set(mob.getX() + enemyMoveVector.x, mob.getY() + enemyMoveVector.y, mob.getWidth(), mob.getHeight() * mob.getCollisionHeight());

                    if (!mob.getData().flying && WorldSave.getCurrentSave().getWorld().collidingTile(tempBoundingRect)) {
                        tempBoundingRect.set(mob.getX() + enemyMoveVector.x, mob.getY(), mob.getWidth(), mob.getHeight());
                        if (WorldSave.getCurrentSave().getWorld().collidingTile(tempBoundingRect)) {
                            tempBoundingRect.set(mob.getX(), mob.getY() + enemyMoveVector.y, mob.getWidth(), mob.getHeight());
                            if (!WorldSave.getCurrentSave().getWorld().collidingTile(tempBoundingRect)) {
                                mob.moveBy(0, enemyMoveVector.y);
                            }
                        } else {
                            mob.moveBy(enemyMoveVector.x, 0);
                        }
                    } else {
                        mob.moveBy(enemyMoveVector.x, enemyMoveVector.y);
                    }
                }

                if (player.collideWith(mob)) {
                    if (channelNode != null)
                        cancelGatherChannel("An enemy interrupted you!");
                    if (collided)
                        return;
                    collided = true;
                    player.setAnimation(CharacterSprite.AnimationTypes.Attack);
                    player.playEffect(Paths.EFFECT_SPARKS, 0.5f);
                    mob.setAnimation(CharacterSprite.AnimationTypes.Attack);
                    SoundSystem.instance.play(SoundEffectType.Block, false);
                    HapticEngine.vibrate(FPref.UI_VIBRATE_ON_ENEMY_ENCOUNTER, mob.getData().boss ? 400 : 200);
                    Forge.advFreezePlayerControls = true;
                    player.clearCollisionHeight();
                    float attackDuration = Math.max(
                            player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.8f),
                            mob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.8f));
                    startPause(attackDuration, () -> {
                        Forge.setCursor(null, Forge.magnifyToggle ? "1" : "2");
                        SoundSystem.instance.play(SoundEffectType.ManaBurn, false);
                        DuelScene duelScene = DuelScene.instance();
                        FThreads.invokeInEdtNowOrLater(() -> {
                            Forge.setTransitionScreen(new TransitionScreen(() -> {
                                collided = false;
                                duelScene.initDuels(player, mob);
                                Forge.switchScene(duelScene);
                            }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "", Current.player().avatar(), mob.getAtlasPath(), Current.player().getName(), mob.getName()));
                            currentMob = mob;
                            WorldSave.getCurrentSave().autoSave();
                        });
                    });
                    break;
                }
            }
        } else {
            for (int i = 0; i < enemies.size(); i++) {
                enemies.get(i).getValue().setAnimation(CharacterSprite.AnimationTypes.Idle);
            }
        }

        if (moving && channelNode == null && !Forge.advFreezePlayerControls)
            tryStartGatherFromCollision();

        collided = false;
    }

    private void removeEnemy(EnemySprite currentMob) {
        currentMob.removeAfterEffects();
        Iterator<Pair<Float, EnemySprite>> it = enemies.iterator();
        while (it.hasNext()) {
            Pair<Float, EnemySprite> pair = it.next();
            if (pair.getValue() == currentMob) {
                it.remove();
                return;
            }
        }
    }

    @Override
    public void setWinner(boolean playerIsWinner, boolean isArena) {
        Current.player().getSkills().onDuelFinished(playerIsWinner, currentMob);
        if (playerIsWinner) {
            currentMob.clearCollisionHeight();
            boolean boss = currentMob.getData() != null && currentMob.getData().boss;
            Current.player().win(boss);
            player.setAnimation(CharacterSprite.AnimationTypes.Attack);
            float attackDuration = Math.max(1f,
                    player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 1f));
            currentMob.playEffect(Paths.EFFECT_BLOOD, 0.5f);
            Timer.schedule(new Timer.Task() {
                @Override
                public void run() {
                    currentMob.setAnimation(CharacterSprite.AnimationTypes.Death);
                    currentMob.resetCollisionHeight();
                    float deathDuration = currentMob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Death, 0.3f);
                    startPause(deathDuration, () -> {
                        RewardScene.instance().loadRewards(currentMob.getRewards(), RewardScene.Type.Loot, null);
                        WorldStage.this.removeEnemy(currentMob);
                        AdventureQuestController.instance().updateQuestsWin(currentMob);
                        AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                        Forge.switchScene(RewardScene.instance());
                        currentMob = null;
                    });
                }
            }, attackDuration);
        } else {
            currentMob.clearCollisionHeight();
            player.setAnimation(CharacterSprite.AnimationTypes.Hit);
            currentMob.setAnimation(CharacterSprite.AnimationTypes.Attack);
            float resultAnimationDuration = Math.max(
                    player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Hit, 0.5f),
                    currentMob.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.5f));
            startPause(resultAnimationDuration, () -> {
                currentMob.resetCollisionHeight();
                boolean defeated = Current.player().defeated();
                AdventureQuestController.instance().updateQuestsLose(currentMob);
                AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                boolean defeatedFromBoss = currentMob.getData().boss && !isArena;
                WorldStage.this.removeEnemy(currentMob);
                currentMob = null;
                if (defeated) {
                    WorldStage.getInstance().resetPlayerLocation();
                } else if (defeatedFromBoss) {
                    WorldStage.getInstance().defeatedFromBoss();
                }
            });
        }
    }

    public boolean handlePointsOfInterestCollision() {
        for (Actor actor : foregroundSprites.getChildren()) {
            if (actor.getClass() == PointOfInterestMapSprite.class) {
                PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
                if (!point.getPointOfInterest().getActive())
                {
                    continue;
                }
                if (player.collideWith(point.getBoundingRect())) {
                    if (point == collidingPoint) {
                        continue;
                    }
                    // The loadPOI generates booster and other things that may take time to load, so show a little loading text.
                    OverlayText.getInstance().update("[%240]" + GameScene.instance().getLocationColorID() + "{CAROUSEL} A U T O S A V E ");
                    startPause(1f, ()-> {
                        WorldSave.getCurrentSave().autoSave();
                        loadPOI(point.getPointOfInterest());
                        point.getMapSprite().checkOut();
                        PointOfInterestChanges changes = WorldSave.getCurrentSave().getPointOfInterestChanges(point.getPointOfInterest().getID());
                        if (!changes.isVisited())
                            Current.player().getSkills().onPlaceDiscovered(point.getPointOfInterest().getData().type);
                        changes.visit();
                    });
                    return true;
                } else {
                    if (point == collidingPoint) {
                        collidingPoint = null;
                    }
                }
            }
        }
        return false;
    }

    public void loadPOI(PointOfInterest poi) {
        try {
            stop();
            TileMapScene.instance().load(poi);
            TileMapScene.instance().setFromWorldMap(true);
            Forge.switchScene(TileMapScene.instance());
        } catch (Exception e) {
            System.err.println("Error loading map...");
            e.printStackTrace();
        }
    }

    @Override
    public boolean isColliding(Rectangle boundingRect) {
        if (currentModifications.containsKey(PlayerModification.Fly))
            return false;
        return WorldSave.getCurrentSave().getWorld().collidingTile(boundingRect);
    }

    @Override
    public Vector2 adjustMovement(Vector2 direction, Rectangle boundingRect) {
        if (isColliding(boundingRect)) //if player is already colliding (after flying or teleport) allow to move off collision
            return direction;
        return super.adjustMovement(direction, boundingRect);
    }

    public boolean spawn(String enemy) {
        return spawn(WorldData.getEnemy(enemy));
    }

    // ---- Ascendant gathering nodes (Package B) ----

    private ConfigData gatherConfig() {
        return Config.instance().getConfigData();
    }

    private float nodeLifetime() {
        ConfigData cfg = gatherConfig();
        return cfg != null && cfg.gatherNodeLifetime > 0 ? cfg.gatherNodeLifetime : 60f;
    }

    private void tickNodeLifetimes() {
        if (nodes.isEmpty())
            return;
        float life = nodeLifetime();
        for (int i = 0; i < nodes.size(); i++) {
            Pair<Float, ResourceNodeSprite> pair = nodes.get(i);
            if (globalTimer >= pair.getKey() + life) {
                if (channelNode == pair.getValue())
                    cancelGatherChannel(null);
                foregroundSprites.removeActor(pair.getValue());
                nodes.remove(i);
                i--;
            }
        }
    }

    private void handleNodeSpawn(float delta) {
        if (!Config.ascendant())
            return;
        ConfigData cfg = gatherConfig();
        int maxAlive = cfg != null ? cfg.gatherNodeMaxAlive : 4;
        if (nodes.size() >= maxAlive)
            return;

        World world = WorldSave.getCurrentSave().getWorld();
        int currentBiome = World.highestBiome(world.getBiome(
                (int) ((player.getX() + player.getWidth() / 2f) / world.getTileSize()),
                (int) (player.getY() / world.getTileSize())));
        List<BiomeData> biomeData = world.getData().GetBiomes();
        if (biomeData.size() <= currentBiome)
            return;
        BiomeData data = biomeData.get(currentBiome);
        if (data == null)
            return;

        String materialBiome = MaterialListData.materialBiomeForWorldBiome(data.name);
        if (materialBiome == null)
            return;

        nodeSpawnDelay -= delta;
        if (nodeSpawnDelay >= 0)
            return;
        float interval = cfg != null ? cfg.gatherNodeSpawnInterval : 5f;
        nodeSpawnDelay = interval + (rand.nextFloat() * interval);

        MaterialData mat = pickNodeMaterial(materialBiome);
        if (mat == null)
            return;
        spawnNode(mat);
    }

    private MaterialData pickNodeMaterial(String materialBiome) {
        com.badlogic.gdx.utils.Array<MaterialData> candidates = MaterialListData.getGatherablesForBiome(materialBiome);
        if (candidates.size == 0)
            return null;
        AdventurePlayer ap = Current.player();
        PlayerSkills skills = ap.getSkills();
        float total = 0f;
        float[] weights = new float[candidates.size];
        for (int i = 0; i < candidates.size; i++) {
            MaterialData m = candidates.get(i);
            PlayerSkills.Skill skill = PlayerSkills.Skill.fromMaterialSkill(m.skill);
            int level = skill != null ? skills.getLevel(skill) : 1;
            int req = Math.max(1, m.levelRequired);
            // Prefer tiers at or below skill; still allow a little higher so the map feels alive.
            float dist = Math.abs(level - req);
            float w = 1f / (1f + dist / 12f);
            if (req > level)
                w *= 0.35f;
            weights[i] = w;
            total += w;
        }
        if (total <= 0f)
            return candidates.get(rand.nextInt(candidates.size));
        float roll = rand.nextFloat() * total;
        float acc = 0f;
        for (int i = 0; i < candidates.size; i++) {
            acc += weights[i];
            if (roll <= acc)
                return candidates.get(i);
        }
        return candidates.get(candidates.size - 1);
    }

    private boolean spawnNode(MaterialData mat) {
        if (mat == null)
            return false;
        ResourceNodeSprite sprite = new ResourceNodeSprite(mat);
        float unit = Scene.getIntendedHeight() / 6f;
        Vector2 spawnPos = new Vector2(1, 1);
        for (int j = 0; j < 10; j++) {
            spawnPos.setLength(unit + (unit * 3) * rand.nextFloat());
            spawnPos.setAngleDeg(360 * rand.nextFloat());
            for (int i = 0; i < 10; i++) {
                boolean xBigger = spawnPos.x > 0;
                boolean yBigger = spawnPos.y > 0;
                sprite.setX(player.getX() + spawnPos.x + (i * sprite.getWidth() * (xBigger ? 1 : -1)));
                sprite.setY(player.getY() + spawnPos.y + (i * sprite.getHeight() * (yBigger ? 1 : -1)));
                if (!WorldSave.getCurrentSave().getWorld().collidingTile(sprite.boundingRect())) {
                    nodes.add(Pair.of(globalTimer, sprite));
                    foregroundSprites.addActor(sprite);
                    return true;
                }
            }
        }
        return false;
    }

    /** Debug / console: place a pre-built node sprite into the world. */
    public void debugSpawnNode(ResourceNodeSprite sprite) {
        if (sprite == null)
            return;
        nodes.add(Pair.of(globalTimer, sprite));
        foregroundSprites.addActor(sprite);
    }

    private void tryStartGatherFromCollision() {
        if (!Config.ascendant() || channelNode != null || Forge.advFreezePlayerControls)
            return;
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNodeSprite node = nodes.get(i).getValue();
            if (player.collideWith(node)) {
                beginGatherChannel(node);
                return;
            }
        }
    }

    private void beginGatherChannel(ResourceNodeSprite node) {
        if (node == null || node.getMaterial() == null)
            return;
        MaterialData mat = node.getMaterial();
        AdventurePlayer ap = Current.player();
        PlayerSkills.Skill skill = PlayerSkills.Skill.fromMaterialSkill(mat.skill);
        int skillLevel = skill != null ? ap.getSkills().getLevel(skill) : 1;
        if (skillLevel < mat.levelRequired) {
            notifyGatherFail("Need " + mat.skill + " level " + mat.levelRequired
                    + " (have " + skillLevel + ").");
            return;
        }
        int toolTier = ap.getToolTier(mat.family);
        if (toolTier < mat.tier) {
            notifyGatherFail("Need a tier " + mat.tier + " " + mat.family
                    + " tool (have tier " + toolTier + ").");
            return;
        }

        ConfigData cfg = gatherConfig();
        float max = cfg != null ? cfg.gatherChannelMax : 3f;
        float min = cfg != null ? cfg.gatherChannelMin : 1f;
        float factor = ap.getSkills().gatherChannelFactor(skill, min / max);
        channelDuration = Math.max(min, max * factor);
        channelElapsed = 0f;
        channelNode = node;
        player.stop();
        node.setChannelProgress(0f);
    }

    private void tickGatherChannel(float delta) {
        if (channelNode == null)
            return;
        if (Forge.advFreezePlayerControls) {
            cancelGatherChannel("Interrupted!");
            return;
        }
        // Still touching the node?
        if (!player.collideWith(channelNode) && !almostTouching(channelNode)) {
            cancelGatherChannel(null);
            return;
        }
        channelElapsed += delta;
        channelNode.setChannelProgress(channelElapsed / channelDuration);
        if (channelElapsed >= channelDuration)
            completeGatherChannel();
    }

    private boolean almostTouching(ResourceNodeSprite node) {
        // After player.stop(), slight separation can happen; allow a small pad.
        tempBoundingRect.set(player.getX() - 4, player.getY() - 4, player.getWidth() + 8, player.getHeight() + 8);
        return tempBoundingRect.overlaps(node.boundingRect());
    }

    private void completeGatherChannel() {
        ResourceNodeSprite node = channelNode;
        if (node == null)
            return;
        MaterialData mat = node.getMaterial();
        channelNode = null;
        channelElapsed = 0f;
        channelDuration = 0f;
        if (node != null)
            node.clearChannelProgress();
        if (mat == null) {
            removeNode(node);
            return;
        }

        AdventurePlayer ap = Current.player();
        ConfigData cfg = gatherConfig();
        PlayerSkills.Skill skill = PlayerSkills.Skill.fromMaterialSkill(mat.skill);
        int skillLevel = skill != null ? ap.getSkills().getLevel(skill) : 1;

        int yieldMin = cfg != null ? cfg.gatherYieldMin : 1;
        int yieldMax = cfg != null ? cfg.gatherYieldMax : 3;
        int lvl2 = cfg != null ? cfg.gatherYieldLevel2 : 40;
        int lvl3 = cfg != null ? cfg.gatherYieldLevel3 : 70;
        int amount = yieldMin;
        if (skillLevel >= lvl2)
            amount++;
        if (skillLevel >= lvl3)
            amount++;
        amount = Math.min(yieldMax, Math.max(yieldMin, amount));

        ap.addMaterial(mat.id, amount);
        int xp = Math.max(0, mat.xp) * amount;
        ap.getSkills().onMaterialGathered(skill, xp);

        StringBuilder msg = new StringBuilder();
        msg.append("Gathered ").append(amount).append("× ").append(mat.getDisplayName());

        // Rare gem (Mining nodes).
        float gemChance = cfg != null ? cfg.gatherGemChance : 0.08f;
        if ("ore".equalsIgnoreCase(mat.family) && rand.nextFloat() < gemChance) {
            com.badlogic.gdx.utils.Array<MaterialData> gems = MaterialListData.getGems();
            if (gems.size > 0) {
                MaterialData gem = gems.get(rand.nextInt(gems.size));
                ap.addMaterial(gem.id, 1);
                msg.append(", ").append(gem.getDisplayName());
            }
        }
        float dustChance = cfg != null ? cfg.gatherDustChance : 0.12f;
        if (rand.nextFloat() < dustChance) {
            int dustAmt = cfg != null ? cfg.gatherDustAmount : 1;
            ap.addDust(CardRarity.Common, dustAmt);
            msg.append(", ").append(dustAmt).append(" common dust");
        }
        float goldChance = cfg != null ? cfg.gatherGoldChance : 0.04f;
        if (rand.nextFloat() < goldChance) {
            int goldAmt = cfg != null ? cfg.gatherGoldAmount : 5;
            ap.giveGold(goldAmt);
            msg.append(", ").append(goldAmt).append(" gold");
        }
        float shardChance = cfg != null ? cfg.gatherShardChance : 0.015f;
        if (rand.nextFloat() < shardChance) {
            ap.addShards(1);
            msg.append(", 1 shard");
        }

        GameHUD.getInstance().addNotification(msg.toString());
        node.playEffect(Paths.EFFECT_KILL);
        removeNode(node);
    }

    private void cancelGatherChannel(String message) {
        if (channelNode != null) {
            channelNode.clearChannelProgress();
            channelNode = null;
        }
        channelElapsed = 0f;
        channelDuration = 0f;
        if (message != null && !message.isEmpty())
            GameHUD.getInstance().addNotification(message);
    }

    private void notifyGatherFail(String message) {
        if (gatherFailNotifyCooldown > 0f)
            return;
        gatherFailNotifyCooldown = 1.5f;
        GameHUD.getInstance().addNotification(message);
    }

    private void removeNode(ResourceNodeSprite node) {
        if (node == null)
            return;
        Iterator<Pair<Float, ResourceNodeSprite>> it = nodes.iterator();
        while (it.hasNext()) {
            Pair<Float, ResourceNodeSprite> pair = it.next();
            if (pair.getValue() == node) {
                foregroundSprites.removeActor(node);
                it.remove();
                return;
            }
        }
        foregroundSprites.removeActor(node);
    }

    private void handleMonsterSpawn(float delta) {
        for (EnemySprite questSprite : AdventureQuestController.instance().getQuestSprites()) {
            if (!foregroundSprites.getChildren().contains(questSprite, true)) {
                spawnQuestSprite(questSprite,2.5f);
            }
        }

        World world = WorldSave.getCurrentSave().getWorld();
        int currentBiome = World.highestBiome(world.getBiome((int) ((player.getX() + player.getWidth() / 2f) / world.getTileSize()), (int) (player.getY() / world.getTileSize())));
        List<BiomeData> biomeData = WorldSave.getCurrentSave().getWorld().getData().GetBiomes();
        float sprintingMod = currentModifications.containsKey(PlayerModification.Sprint) ? 2 : 1;
        if (biomeData.size() <= currentBiome) {// "if isOnRoad
            player.setMoveModifier(1.5f * sprintingMod);
            return;
        }
        player.setMoveModifier(1.0f * sprintingMod);
        BiomeData data = biomeData.get(currentBiome);
        if (data == null) return;

        spawnDelay -= delta;
        if (spawnDelay >= 0) return;
        spawnDelay = spawnInterval + (rand.nextFloat() * 4.0f);

        ArrayList<EnemyData> list = data.getEnemyList();
        if (list == null)
            return;
        EnemyData enemyData = data.getEnemy(1.0f);
        EnemyData extraSpawnForQuests = data.getExtraSpawnEnemy(1.0f);
        if (extraSpawnForQuests != null) {
            float spawnPicker = rand.nextFloat();

            if (spawnPicker > 0.5f) //todo: make this difficulty dependent, more enemies on harder difficulty
            {
                spawn(enemyData);
                spawn(extraSpawnForQuests);
            }
            else if (spawnPicker > 0.2f) {
                spawn(extraSpawnForQuests);
            }
            else {
                spawn(enemyData);
            }

        }
        else spawn(enemyData);
    }

    private boolean spawn(EnemySprite sprite){
        if (sprite == null)
            return false;
        float unit = Scene.getIntendedHeight() / 6f;
        Vector2 spawnPos = new Vector2(1, 1);
        for (int j = 0; j < 10; j++) {
            spawnPos.setLength(unit + (unit * 3) * rand.nextFloat());
            spawnPos.setAngleDeg(360 * rand.nextFloat());
            for (int i = 0; i < 10; i++) {
                boolean enemyXIsBigger = sprite.getX() > player.getX();
                boolean enemyYIsBigger = sprite.getY() > player.getY();
                sprite.setX(player.getX() + spawnPos.x + (i * sprite.getWidth() * (enemyXIsBigger ? 1 : -1)));//maybe find a better way to get spawn points
                sprite.setY(player.getY() + spawnPos.y + (i * sprite.getHeight() * (enemyYIsBigger ? 1 : -1)));
                if (sprite.getData().flying || !WorldSave.getCurrentSave().getWorld().collidingTile(sprite.boundingRect())) {
                    enemies.add(Pair.of(globalTimer, sprite));
                    foregroundSprites.addActor(sprite);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean spawn(EnemyData enemyData) {
        if (enemyData == null)
            return false;
        EnemySprite sprite = new EnemySprite(enemyData);
        return spawn(sprite);

    }

    private boolean spawnQuestSprite(EnemySprite sprite, float distanceMultiplier){
        if (sprite == null)
            return false;
        float unit = Scene.getIntendedHeight() / 6f;
        Vector2 spawnPos = new Vector2(1, 1);
        for (int j = 0; j < 10; j++) {
            spawnPos.setLength((unit + (unit * 3) * rand.nextFloat()) * distanceMultiplier);
            spawnPos.setAngleDeg(360 * rand.nextFloat());
            for (int i = 0; i < 10; i++) {
                boolean enemyXIsBigger = sprite.getX() > player.getX();
                boolean enemyYIsBigger = sprite.getY() > player.getY();
                sprite.setX(player.getX() + spawnPos.x + (i * sprite.getWidth() * (enemyXIsBigger ? 1 : -1)));//maybe find a better way to get spawn points
                sprite.setY(player.getY() + spawnPos.y + (i * sprite.getHeight() * (enemyYIsBigger ? 1 : -1)));
                if (sprite.getData().flying || !WorldSave.getCurrentSave().getWorld().collidingTile(sprite.boundingRect())) {
                    enemies.add(Pair.of(globalTimer, sprite));
                    foregroundSprites.addActor(sprite);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void draw() {
        background.setPlayerPos(player.getX(), player.getY());
        //spriteGroup.setCullingArea(new Rectangle(player.getX()-getViewport().getWorldHeight()/2,player.getY()-getViewport().getWorldHeight()/2,getViewport().getWorldHeight(),getViewport().getWorldHeight()));
        super.draw();
    }

    public void enterSpawnPOI(){
        enterSpawnPOI = true; //On a new game, we want to automatically enter spawn POI the player overlaps with.
    }

    public PointOfInterestMapSprite getMapSprite(PointOfInterest poi) {
        if (poi == null)
            return null;
        for (Actor actor : foregroundSprites.getChildren()) {
            if (actor.getClass() == PointOfInterestMapSprite.class) {
                PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
                if (poi == point.getPointOfInterest() && poi.getPosition() == point.getPointOfInterest().getPosition())
                    return point;
            }
        }
        return null;
    }

    @Override
    public void enter() {
        getPlayerSprite().LoadPos();
        getPlayerSprite().setMovementDirection(Vector2.Zero);
        if (enterSpawnPOI) {
            enterSpawnPOI = false;
            PointOfInterest poi = Current.world().findPointsOfInterest("Spawn");
            if (poi != null) { //shouldn't be null
                WorldStage.getInstance().loadPOI(poi);
                // adjust player sprite to prevent triggering the poi collision point when leaving the spawn on New Game
                WorldStage.getInstance().getPlayerSprite().storePos(poi.getPosition().x, poi.getPosition().y + 18f);
            }
        }
        else {
            for (Actor actor : foregroundSprites.getChildren()) {
                if (actor.getClass() == PointOfInterestMapSprite.class) {
                    PointOfInterestMapSprite point = (PointOfInterestMapSprite) actor;
                    if (player.collideWith(point.getBoundingRect())) {
                        collidingPoint = point;
                    }
                }
            }
        }
        setBounds(WorldSave.getCurrentSave().getWorld().getWidthInPixels(), WorldSave.getCurrentSave().getWorld().getHeightInPixels());
        GridPoint2 pos = background.translateFromWorldToChunk(player.getX(), player.getY());
        background.loadChunk(pos.x, pos.y);
        super.enter();
    }

    @Override
    public void leave() {
        cancelGatherChannel(null);
        getPlayerSprite().storePos();
        background.dispose();
    }

    @Override
    public void load(SaveFileData data) {
        try {
            clearCache();
            MapStage.getInstance().clearIsInMap();
            GameHUD.getInstance().clearNotifications();
            List<Float> timeouts = (List<Float>) data.readObject("timeouts");
            List<String> names = (List<String>) data.readObject("names");
            List<Float> x = (List<Float>) data.readObject("x");
            List<Float> y = (List<Float>) data.readObject("y");
            List<String> questStageIDs = (List<String>) data.readObject("questStageIDs");
            for (int i = 0; i < timeouts.size(); i++) {
                EnemySprite sprite = new EnemySprite(WorldData.getEnemy(names.get(i)));
                sprite.setX(x.get(i));
                sprite.setY(y.get(i));
                sprite.questStageID = questStageIDs.get(i);
                if (sprite.questStageID != null)
                    AdventureQuestController.instance().rematchQuestSprite(sprite);
                enemies.add(Pair.of(timeouts.get(i), sprite));
                foregroundSprites.addActor(sprite);
            }
            globalTimer = data.readFloat("globalTimer");
            // Optional: resource nodes (Package B). Missing keys → no nodes (old saves).
            if (data.containsKey("nodeTimeouts") && data.containsKey("nodeMaterialIds")
                    && data.containsKey("nodeX") && data.containsKey("nodeY")) {
                List<Float> nTimeouts = (List<Float>) data.readObject("nodeTimeouts");
                List<String> nMats = (List<String>) data.readObject("nodeMaterialIds");
                List<Float> nX = (List<Float>) data.readObject("nodeX");
                List<Float> nY = (List<Float>) data.readObject("nodeY");
                if (nTimeouts != null && nMats != null && nX != null && nY != null) {
                    int n = Math.min(Math.min(nTimeouts.size(), nMats.size()), Math.min(nX.size(), nY.size()));
                    for (int i = 0; i < n; i++) {
                        MaterialData mat = MaterialListData.get(nMats.get(i));
                        if (mat == null)
                            continue;
                        ResourceNodeSprite sprite = new ResourceNodeSprite(mat);
                        sprite.setX(nX.get(i));
                        sprite.setY(nY.get(i));
                        nodes.add(Pair.of(nTimeouts.get(i), sprite));
                        foregroundSprites.addActor(sprite);
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    public void clearCache() {
        cancelGatherChannel(null);
        for (Pair<Float, EnemySprite> enemy : enemies)
            foregroundSprites.removeActor(enemy.getValue());
        enemies.clear();
        for (Pair<Float, ResourceNodeSprite> node : nodes)
            foregroundSprites.removeActor(node.getValue());
        nodes.clear();
        background.clear();
        player = null;
    }

    @Override
    public SaveFileData save() {
        SaveFileData data = new SaveFileData();

        cachedSaveTimeouts.clear();
        cachedSaveNames.clear();
        cachedSaveXCoords.clear();
        cachedSaveYCoords.clear();
        cachedSaveQuestIDs.clear();

        for (int i = 0; i < enemies.size(); i++) {
            Pair<Float, EnemySprite> enemy = enemies.get(i);
            cachedSaveTimeouts.add(enemy.getKey());
            cachedSaveNames.add(enemy.getValue().getData().getName());
            cachedSaveXCoords.add(enemy.getValue().getX());
            cachedSaveYCoords.add(enemy.getValue().getY());
            cachedSaveQuestIDs.add(enemy.getValue().questStageID);
        }

        data.storeObject("timeouts", cachedSaveTimeouts);
        data.storeObject("names", cachedSaveNames);
        data.storeObject("x", cachedSaveXCoords);
        data.storeObject("y", cachedSaveYCoords);
        data.storeObject("questStageIDs", cachedSaveQuestIDs);
        data.store("globalTimer", globalTimer);

        cachedNodeTimeouts.clear();
        cachedNodeMaterialIds.clear();
        cachedNodeXCoords.clear();
        cachedNodeYCoords.clear();
        for (int i = 0; i < nodes.size(); i++) {
            Pair<Float, ResourceNodeSprite> node = nodes.get(i);
            String mid = node.getValue().getMaterialId();
            if (mid == null || mid.isEmpty())
                continue;
            cachedNodeTimeouts.add(node.getKey());
            cachedNodeMaterialIds.add(mid);
            cachedNodeXCoords.add(node.getValue().getX());
            cachedNodeYCoords.add(node.getValue().getY());
        }
        data.storeObject("nodeTimeouts", cachedNodeTimeouts);
        data.storeObject("nodeMaterialIds", cachedNodeMaterialIds);
        data.storeObject("nodeX", cachedNodeXCoords);
        data.storeObject("nodeY", cachedNodeYCoords);
        return data;
    }

    @Override
    public Viewport getViewport() {
        return super.getViewport();
    }


    public void removeNearestEnemy() {
        float shortestDist = Float.MAX_VALUE;
        EnemySprite enemy = null;
        for (Pair<Float, EnemySprite> pair : enemies) {
            float dist = pair.getValue().pos().sub(player.pos()).len();
            if (dist < shortestDist) {
                shortestDist = dist;
                enemy = pair.getValue();
            }
        }
        if (enemy != null) {
            enemy.playEffect(Paths.EFFECT_KILL);
            removeEnemy(enemy);
            player.playEffect(Paths.TRIGGER_KILL);
        }
    }

    private void drawNavigationArrow() {
        Vector2 navDirection = null;
        for (AdventureQuestData adq : Current.player().getQuests()) {
            if (adq.isTracked) {
                PointOfInterest nearestValidPOI = adq.getClosestValidPOI(player.getCenter());
                if (nearestValidPOI != null) {
                    navDirectionVec.set(nearestValidPOI.getCenter()).sub(player.getCenter());
                    navDirection = navDirectionVec;
                    break;
                }

                if (adq.getTargetEnemySprite() == null
                        && adq.getActiveStages().size() > 0
                        && adq.qualifiesForDetachedQuest(adq.getActiveStages().get(0))) {
                    AdventureQuestStage brokenStage = adq.getActiveStages().get(0);
                    adq.fixOrphanedHuntQuest(brokenStage);
                    AdventureQuestController.instance().addQuestSprites(brokenStage);
                    // When we first load, we will not do this in time to actually spawn the sprite
                    // until the next loop, but as soon as the player moves, if the On the Hunt quest
                    // is tracked, we will immediately point to that sprite
                }

                if (adq.getTargetEnemySprite() != null) {
                    EnemySprite target = adq.getTargetEnemySprite();
                    for (int i = 0; i < enemies.size(); i++) {
                        EnemySprite sprite = enemies.get(i).getValue();
                        if (sprite.equals(target)) {
                            navDirectionVec.set(adq.getTargetEnemySprite().getCenter()).sub(player.getCenter());
                            navDirection = navDirectionVec;
                        }
                    }
                }
                break;
            }
        }
        if (navDirection != null) {
            navArrow.navTargetAngle = navDirection.angleDeg();
            navArrow.setVisible(true);
            navArrow.setPosition(getPlayerSprite().getX() + (getPlayerSprite().getWidth() / 2), getPlayerSprite().getY() + (getPlayerSprite().getHeight() / 2));
        } else {
            navArrow.setVisible(false);
        }
    }
}
