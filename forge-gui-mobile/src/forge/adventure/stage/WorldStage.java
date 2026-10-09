package forge.adventure.stage;

import forge.adventure.util.Current;
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
import forge.adventure.coop.CoopHooks;
import forge.adventure.coop.CoopOverworldRuntime;
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
    /**
     * EN2: how many times to roll {@link EnemySprite#getRewards()} on a win.
     * Default 1; co-op partner kills use the tunable so a pair is not worth double.
     * {@code 0} is valid. Cleared on win (consume) and on loss.
     */
    private final PendingLootRolls pendingLootRolls = new PendingLootRolls();
    /**
     * RW1 + EN2: enemy the local peer is credited with for loot (host → primary,
     * guest → partner). Null means roll {@link #currentMob} as today.
     */
    private forge.adventure.data.EnemyData pendingLootCredit;
    /** Played deck for {@link #pendingLootCredit} (partner seat), or null. */
    private forge.deck.Deck pendingLootCreditDeck;

    /**
     * EN2 loot-roll bookkeeping used by {@link #setWinner}. Package-visible shape so
     * headless tests can exercise the same win/loss rules without constructing a Stage.
     */
    public static final class PendingLootRolls {
        private int pending = 1;

        public void set(final int rolls) {
            pending = Math.max(0, Math.min(rolls, 8));
        }

        public int get() {
            return pending;
        }

        /** Read and reset to 1. Returns the rolls to apply (0 allowed). */
        public int consume() {
            final int rolls = Math.max(0, Math.min(pending, 8));
            pending = 1;
            return rolls;
        }

        /** Loss path: drop any pending co-op loot rolls. */
        public void clearOnLoss() {
            pending = 1;
        }
    }
    protected Random rand = MyRandom.getRandom();
    WorldBackground background;
    private float spawnDelay = 0;
    private static final float spawnInterval = 4;//todo config
    private PointOfInterestMapSprite collidingPoint;
    protected ArrayList<Pair<Float, EnemySprite>> enemies = new ArrayList<>();
    /** Guest co-op: locally saved enemies held aside while mirroring the host. */
    private final ArrayList<Pair<Float, EnemySprite>> stashedCoopEnemies = new ArrayList<>();
    /** Guest co-op: locally saved nodes held aside while mirroring the host. */
    private final ArrayList<Pair<Float, ResourceNodeSprite>> stashedCoopNodes = new ArrayList<>();
    /** Ascendant resource nodes (Package B); parallel to {@link #enemies}. */
    protected ArrayList<Pair<Float, ResourceNodeSprite>> nodes = new ArrayList<>();
    private final static Float dieTimer = 20f;//todo config
    private Float globalTimer = 0f;
    private float nodeSpawnDelay = 0f;
    private transient boolean enterSpawnPOI = false;

    // Gathering channel state (Ascendant only).
    private ResourceNodeSprite channelNode;
    /** Node the player walked off mid-channel; not restarted until the player stops touching it. */
    private ResourceNodeSprite walkedOffNode;
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
    /** EN1: theme id per enemy (parallel to names); empty string when none. */
    private final ArrayList<String> cachedSaveThemes = new ArrayList<>(32);
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
            // Co-op: still tick partner interpolation / outbound position while frozen for duels.
            if (CoopHooks.isOverworldReady())
                CoopOverworldRuntime.get().tick(delta);
            return;
        }
        drawNavigationArrow();
        if (gatherFailNotifyCooldown > 0f)
            gatherFailNotifyCooldown -= delta;

        // Package B2: outpost production uses active overworld play time.
        Current.player().tickAdventurePlaySeconds(delta);

        boolean moving = player.isMoving();
        boolean channeling = channelNode != null;
        // Enemies keep chasing while the player channels so a touch can cancel gathering.
        boolean updateEnemies = moving || channeling;

        final CoopOverworldRuntime coop = CoopOverworldRuntime.get();
        final boolean guestMirror = coop.guestIsPureMirror();
        final boolean worldPaused = coop.shouldPauseWorldSim();
        if (moving) {
            if (!guestMirror && !worldPaused) {
                handleMonsterSpawn(delta);
                handleNodeSpawn(delta);
            }
            collided = collided || handlePointsOfInterestCollision();
        }
        if (moving || channeling)
            globalTimer += delta;

        if (!guestMirror && !worldPaused)
            tickNodeLifetimes();

        if (channeling) {
            if (moving) {
                // Moving cancels; stopping on the node again restarts it.
                cancelGatherChannel(null);
            } else {
                tickGatherChannel(delta);
            }
        }

        if (guestMirror) {
            // Pure mirror: no AI / lifetime; collision → encounter request (CO3 hook).
            for (int i = 0; i < enemies.size(); i++) {
                EnemySprite mob = enemies.get(i).getValue();
                if (player.collideWith(mob)) {
                    if (channelNode != null)
                        cancelGatherChannel("An enemy interrupted you!");
                    if (collided)
                        break;
                    collided = true;
                    coop.onGuestEnemyCollision(mob);
                    break;
                }
            }
        } else if (updateEnemies && !worldPaused) {
            for (int i = 0; i < enemies.size(); i++) {
                Pair<Float, EnemySprite> pair = enemies.get(i);
                if (globalTimer >= pair.getKey() + pair.getValue().getLifetime()) {
                    AdventureQuestController.instance().updateDespawn(pair.getValue());
                    AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                    foregroundSprites.removeActor(pair.getValue());
                    CoopOverworldRuntime.get().onHostEnemyRemoved(pair.getValue());
                    enemies.remove(i);
                    i--; // index pointer after index reduction step
                    continue;
                }
                EnemySprite mob = pair.getValue();

                if (!currentModifications.containsKey(PlayerModification.Hide)) {
                    enemyMoveVector.set(player.getX(), player.getY()).sub(mob.pos());
                    enemyMoveVector.setLength(mob.speed() * delta);
                    tempBoundingRect.set(mob.getX() + enemyMoveVector.x, mob.getY() + enemyMoveVector.y, mob.getWidth(), mob.getHeight() * mob.getCollisionHeight());

                    if (!mob.getData().flying && Current.world().collidingTile(tempBoundingRect)) {
                        tempBoundingRect.set(mob.getX() + enemyMoveVector.x, mob.getY(), mob.getWidth(), mob.getHeight());
                        if (Current.world().collidingTile(tempBoundingRect)) {
                            tempBoundingRect.set(mob.getX(), mob.getY() + enemyMoveVector.y, mob.getWidth(), mob.getHeight());
                            if (!Current.world().collidingTile(tempBoundingRect)) {
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
                    currentMob = mob;
                    // CO3 hook: party partner may be invited to join; CO2 leaves duel code untouched.
                    final String encounterId = mob.getData() != null ? mob.getData().getName() : "enemy";
                    if (CoopHooks.notifyFightAboutToStart(encounterId)) {
                        // Deferred for co-op duel invite (CO3). Stay frozen until resolved.
                        break;
                    }
                    beginEncounterDuel(mob);
                    break;
                }
            }
        } else {
            for (int i = 0; i < enemies.size(); i++) {
                enemies.get(i).getValue().setAnimation(CharacterSprite.AnimationTypes.Idle);
            }
        }

        // Gather starts once the player stands still on a node, so walking onto it with the key held works.
        if (!moving && channelNode == null && !Forge.advFreezePlayerControls)
            tryStartGatherFromCollision();

        if (CoopHooks.isOverworldReady())
            CoopOverworldRuntime.get().tick(delta);

        collided = false;
    }

    /**
     * CO2: keep enemies / nodes / gather timers advancing while inventory or
     * deck-editor menus are open so pausing UI does not pause the shared world.
     * Never runs during a duel and never despawns {@link #currentMob}.
     */
    public void coopBackgroundTick(float delta) {
        if (!CoopHooks.isOverworldReady())
            return;
        if (Forge.getCurrentScene() instanceof DuelScene)
            return;
        if (CoopOverworldRuntime.get().shouldPauseWorldSim())
            return;
        if (CoopOverworldRuntime.get().guestIsPureMirror())
            return;
        boolean channeling = channelNode != null;
        if (channeling)
            tickGatherChannel(delta);
        tickNodeLifetimes();
        if (CoopHooks.isWorldAuthority()) {
            handleMonsterSpawn(delta);
            handleNodeSpawn(delta);
        }
        globalTimer += delta;
        for (int i = 0; i < enemies.size(); i++) {
            Pair<Float, EnemySprite> pair = enemies.get(i);
            if (pair.getValue() == currentMob)
                continue; // never despawn the mob being fought
            if (globalTimer >= pair.getKey() + pair.getValue().getLifetime()) {
                AdventureQuestController.instance().updateDespawn(pair.getValue());
                foregroundSprites.removeActor(pair.getValue());
                CoopOverworldRuntime.get().onHostEnemyRemoved(pair.getValue());
                enemies.remove(i);
                i--;
            }
        }
    }

    /**
     * Guest session start: stash locally saved enemies so the host mirror is the
     * only enemy set. Restored on {@link #coopRestoreStashedEnemies()}.
     */
    public void coopStashAndClearLocalEnemies() {
        stashedCoopEnemies.clear();
        for (Pair<Float, EnemySprite> pair : enemies) {
            if (pair != null && pair.getValue() != null
                    && !CoopOverworldRuntime.get().isMirroredActor(pair.getValue())) {
                stashedCoopEnemies.add(pair);
                foregroundSprites.removeActor(pair.getValue());
            }
        }
        enemies.clear();
    }

    public void coopRestoreStashedEnemies() {
        for (Pair<Float, EnemySprite> pair : stashedCoopEnemies) {
            if (pair == null || pair.getValue() == null) {
                continue;
            }
            enemies.add(pair);
            foregroundSprites.addActor(pair.getValue());
        }
        stashedCoopEnemies.clear();
    }

    /**
     * Guest session start: stash locally saved nodes so the host mirror is the
     * only node set. Restored on {@link #coopRestoreStashedNodes()}.
     */
    public void coopStashAndClearLocalNodes() {
        stashedCoopNodes.clear();
        for (Pair<Float, ResourceNodeSprite> pair : nodes) {
            if (pair != null && pair.getValue() != null
                    && !CoopOverworldRuntime.get().isMirroredActor(pair.getValue())) {
                stashedCoopNodes.add(pair);
                foregroundSprites.removeActor(pair.getValue());
            }
        }
        nodes.clear();
    }

    public void coopRestoreStashedNodes() {
        for (Pair<Float, ResourceNodeSprite> pair : stashedCoopNodes) {
            if (pair == null || pair.getValue() == null) {
                continue;
            }
            nodes.add(pair);
            foregroundSprites.addActor(pair.getValue());
        }
        stashedCoopNodes.clear();
    }

    /**
     * Guest: apply the normal solo gather reward path after the host confirms
     * the claim (item 6 — guest rolls with local skills, matching solo).
     */
    public void coopApplyGuestGatherRewards(String materialId) {
        if (materialId == null || materialId.isEmpty()) {
            return;
        }
        // Mixed-build hosts may still send pre-schema-3 ore ids on the wire.
        final String wireId = materialId;
        materialId = MaterialListData.migrateOreLineMaterialId(wireId);
        MaterialData mat = MaterialListData.get(materialId);
        if (mat == null) {
            GameHUD.getInstance().addNotification("Unknown node material \"" + wireId
                    + "\" — update Adventure so ore ids match");
            return;
        }
        AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        StringBuilder msg = new StringBuilder();
        floatMaterial = null;
        floatAmount = 0;
        grantGatherRewards(mat, ap, msg, true);
        GameHUD.getInstance().addNotification(msg.toString());
        // The node is already gone on the guest's screen, so the text rises from the player.
        if (floatMaterial != null && floatAmount > 0)
            floatGatherText(player, "+" + floatAmount + " " + floatMaterial.getDisplayName()
                    + " (" + ap.getMaterial(floatMaterial.id) + ")");
        floatMaterial = null;
        floatAmount = 0;
    }

    /** Host READY snapshot: register any already-spawned enemies/nodes. */
    public void coopRegisterExistingForSnapshot() {
        if (!CoopHooks.isWorldAuthority())
            return;
        for (Pair<Float, EnemySprite> pair : enemies) {
            if (pair.getValue() != null && CoopOverworldRuntime.get().getEnemyId(pair.getValue()) < 0L)
                CoopOverworldRuntime.get().onHostEnemySpawned(pair.getValue());
        }
        for (Pair<Float, ResourceNodeSprite> pair : nodes) {
            if (pair.getValue() != null && CoopOverworldRuntime.get().getNodeId(pair.getValue()) < 0L
                    && pair.getValue().getMaterialId() != null)
                CoopOverworldRuntime.get().onHostNodeSpawned(pair.getValue(), pair.getValue().getMaterialId());
        }
    }

    /** CO2: guest applies a host-spawned node. */
    public void coopAddRemoteNode(ResourceNodeSprite node) {
        if (node == null)
            return;
        nodes.add(Pair.of(globalTimer, node));
        foregroundSprites.addActor(node);
    }

    public void coopRemoveRemoteNode(ResourceNodeSprite node) {
        removeNode(node);
        if (channelNode == node)
            cancelGatherChannel(null);
    }

    public void coopAddRemoteEnemy(EnemySprite sprite) {
        if (sprite == null)
            return;
        enemies.add(Pair.of(globalTimer, sprite));
        foregroundSprites.addActor(sprite);
    }

    public void coopRemoveRemoteEnemy(EnemySprite sprite) {
        if (sprite == null)
            return;
        foregroundSprites.removeActor(sprite);
        removeEnemy(sprite);
    }

    /**
     * Start the normal (solo) overworld duel transition for {@code mob}.
     * Also used by CO3 after a join prompt times out or is declined.
     * Notifies CO2 {@code onHostDuelStarted} so the host pauses world sim.
     */
    public void beginEncounterDuel(final EnemySprite mob) {
        if (mob == null) {
            Forge.advFreezePlayerControls = false;
            collided = false;
            return;
        }
        currentMob = mob;
        final String encounterId = mob.getData() != null ? mob.getData().getName() : "enemy";
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
                    CoopOverworldRuntime.get().onHostDuelStarted(encounterId);
                    duelScene.initDuels(player, mob);
                    Forge.switchScene(duelScene);
                }, ScreenUtil.getInstance().takeScreenshot(), true, false, false, false, "", Current.player().avatar(), mob.getAtlasPath(), Current.player().getName(), mob.getName()));
                currentMob = mob;
                WorldSave.getCurrentSave().autoSave();
            });
        });
    }

    /** Enemy currently frozen for an encounter / co-op invite (may be null). */
    public EnemySprite getCurrentMob() {
        return currentMob;
    }

    /**
     * EN2: set loot rolls for the next {@link #setWinner} win path.
     * {@code 0} is valid (no loot). Cleared after win or loss.
     */
    public void setPendingLootRolls(final int rolls) {
        pendingLootRolls.set(rolls);
    }

    public int getPendingLootRolls() {
        return pendingLootRolls.get();
    }

    /**
     * EN2: read and clear pending loot rolls (0 allowed). Used by the win path;
     * also callable from tests that exercise the WorldStage loot path without GL.
     */
    public int consumePendingLootRolls() {
        return pendingLootRolls.consume();
    }

    /**
     * RW1 + EN2: set which enemy the local peer is credited with for the next
     * loot rolls (host → primary, guest → partner). Pass null to use {@link #currentMob}.
     */
    public void setPendingLootCredit(final forge.adventure.data.EnemyData credit,
            final forge.deck.Deck playedDeck) {
        pendingLootCredit = credit;
        pendingLootCreditDeck = playedDeck;
    }

    public forge.adventure.data.EnemyData getPendingLootCredit() {
        return pendingLootCredit;
    }

    /** CO3: pin the encounter enemy before a deferred co-op result path runs. */
    public void setCurrentMob(final EnemySprite mob) {
        currentMob = mob;
    }

    /**
     * One loot roll for the local peer. When EN2 credits a different enemy (guest →
     * partner), roll RW1 against that enemy's theme core and played deck; otherwise
     * use {@link EnemySprite#getRewards()} on the overworld mob (primary).
     */
    public static com.badlogic.gdx.utils.Array<Reward> rollLootForCredit(final EnemySprite mob,
            final forge.adventure.data.EnemyData credit, final forge.deck.Deck creditDeck) {
        if (credit != null && FightRewards.applies(credit)
                && (mob == null || mob.getData() == null
                || credit.themeId != null && !credit.themeId.equals(mob.getData().themeId))) {
            final java.util.List<forge.item.PaperCard> deckCards =
                    FightRewards.deckCardsForRewards(creditDeck);
            final com.badlogic.gdx.utils.Array<Reward> one =
                    FightRewards.generate(credit, null, deckCards, true);
            forge.adventure.data.EnemyMaterialDropData.appendDrops(credit, one);
            return one;
        }
        return mob != null ? mob.getRewards() : new com.badlogic.gdx.utils.Array<>();
    }

    private void removeEnemy(EnemySprite currentMob) {
        currentMob.removeAfterEffects();
        CoopOverworldRuntime.get().onHostEnemyRemoved(currentMob);
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
        CoopOverworldRuntime.get().onHostDuelEnded();
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
                        final int rolls = consumePendingLootRolls();
                        final forge.adventure.data.EnemyData credit = pendingLootCredit;
                        final forge.deck.Deck creditDeck = pendingLootCreditDeck;
                        pendingLootCredit = null;
                        pendingLootCreditDeck = null;
                        final com.badlogic.gdx.utils.Array<Reward> loot = new com.badlogic.gdx.utils.Array<>();
                        for (int r = 0; r < rolls; r++) {
                            final com.badlogic.gdx.utils.Array<Reward> one =
                                    rollLootForCredit(currentMob, credit, creditDeck);
                            if (one != null) {
                                loot.addAll(one);
                            }
                        }
                        RewardScene.instance().loadRewards(loot, RewardScene.Type.Loot, null);
                        WorldStage.this.removeEnemy(currentMob);
                        AdventureQuestController.instance().updateQuestsWin(currentMob);
                        AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                        Forge.switchScene(RewardScene.instance());
                        currentMob = null;
                    });
                }
            }, attackDuration);
        } else {
            // EN2: clear any pending loot rolls on a loss too.
            pendingLootRolls.clearOnLoss();
            pendingLootCredit = null;
            pendingLootCreditDeck = null;
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
                        if (!CoopOverworldRuntime.get().beforeEnterPoi(point.getPointOfInterest())) {
                            collidingPoint = null;
                            return;
                        }
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

    /** @return true if the POI map loaded and became the active scene. */
    public boolean loadPOI(PointOfInterest poi) {
        // FT1 co-op: guests are kept out of the host's fortress (no structure sync /
        // protocol bump). Simpler correct option until CO4.
        if (forge.adventure.fortress.FortressService.isFortressPoi(poi)
                && !forge.adventure.fortress.FortressService.get().guestMayEnterFortress()) {
            GameHUD.getInstance().addNotification(
                    forge.adventure.fortress.FortressService.get().guestFortressDeniedMessage());
            return false;
        }
        try {
            stop();
            TileMapScene.instance().load(poi);
            TileMapScene.instance().setFromWorldMap(true);
            Forge.switchScene(TileMapScene.instance());
            return true;
        } catch (Exception e) {
            System.err.println("Error loading map...");
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean isColliding(Rectangle boundingRect) {
        if (currentModifications.containsKey(PlayerModification.Fly))
            return false;
        return Current.world().collidingTile(boundingRect);
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
                CoopOverworldRuntime.get().onHostNodeRemoved(pair.getValue());
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

        World world = Current.world();
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

    /**
     * Chooses a gather line for biomes that host more than one (forest: logs|plants,
     * mountain: ore veins|ash vents), then a tier weighted toward the player's skill level.
     */
    private MaterialData pickNodeMaterial(String materialBiome) {
        String familyFilter = pickBiomeFamily(materialBiome);
        // Log materials are defined on the forest biome; other biomes borrow them.
        String familyBiome = "logs".equals(familyFilter) ? "forest" : materialBiome;
        com.badlogic.gdx.utils.Array<MaterialData> candidates = familyFilter != null
                ? MaterialListData.getGatherablesForBiomeFamily(familyBiome, familyFilter)
                : MaterialListData.getGatherablesForBiome(materialBiome);
        if (candidates.size == 0)
            candidates = MaterialListData.getGatherablesForBiome(materialBiome);
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

    /** Family split for dual-line biomes; null = use every gatherable in the biome. */
    private String pickBiomeFamily(String materialBiome) {
        if ("forest".equalsIgnoreCase(materialBiome))
            return rand.nextBoolean() ? "logs" : "plants";
        // Trees grow everywhere (each biome has its own look, world/node_variants.json).
        if (rand.nextFloat() < 0.3f)
            return "logs";
        if ("mountain".equalsIgnoreCase(materialBiome))
            return rand.nextBoolean() ? "ore" : "ash";
        return null;
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
                if (!Current.world().collidingTile(sprite.boundingRect())) {
                    nodes.add(Pair.of(globalTimer, sprite));
                    foregroundSprites.addActor(sprite);
                    CoopOverworldRuntime.get().onHostNodeSpawned(sprite, mat.id);
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
        if (sprite.getMaterialId() != null)
            CoopOverworldRuntime.get().onHostNodeSpawned(sprite, sprite.getMaterialId());
    }

    private void tryStartGatherFromCollision() {
        if (!Config.ascendant() || channelNode != null || Forge.advFreezePlayerControls)
            return;
        if (walkedOffNode != null && (!nodes.stream().anyMatch(p -> p.getValue() == walkedOffNode)
                || !player.collideWith(walkedOffNode)))
            walkedOffNode = null;
        for (int i = 0; i < nodes.size(); i++) {
            ResourceNodeSprite node = nodes.get(i).getValue();
            if (node != walkedOffNode && player.collideWith(node)) {
                beginGatherChannel(node);
                // A failed start (tool, level) is not retried until the player steps off, so the message shows once.
                if (channelNode == null)
                    walkedOffNode = node;
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
        String toolFamily = mat.toolFamily();
        int toolTier = ap.getToolTier(toolFamily);
        if (toolTier < mat.tier) {
            notifyGatherFail("Need a tier " + mat.tier + " " + toolFamily
                    + " tool (have tier " + toolTier + ").");
            return;
        }

        ConfigData cfg = gatherConfig();
        float max = cfg != null ? cfg.gatherChannelMax : 3f;
        float min = cfg != null ? cfg.gatherChannelMin : 1f;
        float factor = ap.getSkills().gatherChannelFactor(skill, min / max);
        channelDuration = Math.max(min, max * factor);
        // B2 method upgrades / tool enchantments that shorten channel.
        channelDuration *= gatherChannelMultiplier(ap, skill, toolFamily);
        channelDuration = Math.max(0.35f, channelDuration);
        channelElapsed = 0f;
        channelNode = node;
        player.stop();
        node.setChannelProgress(0f);
        // Hits land evenly through the channel (~every 0.45s), the first one right away.
        int hits = Math.max(2, Math.round(channelDuration / 0.45f));
        gatherHitInterval = channelDuration / hits;
        nextGatherHitAt = 0.08f;
    }

    private float gatherHitInterval = 0.45f;
    private MaterialData floatMaterial;
    private int floatAmount;
    private float nextGatherHitAt = 0f;

    /** Feedback for one gathering hit: node shake, spark puff, a swing and a family-specific sound. */
    private void gatherHit(ResourceNodeSprite node) {
        node.hit();
        node.playEffect(Paths.EFFECT_SPARKS, 0.12f);
        player.setAnimation(CharacterSprite.AnimationTypes.Attack);
        float swing = player.getActionAnimationDuration(CharacterSprite.AnimationTypes.Attack, 0.25f);
        Timer.schedule(new Timer.Task() {
            @Override
            public void run() {
                if (!player.isMoving())
                    player.setAnimation(CharacterSprite.AnimationTypes.Idle);
            }
        }, Math.min(swing, gatherHitInterval * 0.9f));
        playGatherHitSound(node.getMaterial());
    }

    /** Kenney CC0 sounds in res/adventure/common/sound: {set name, number of variants} per material family. */
    private static String[] gatherSoundSet(MaterialData mat) {
        String family = mat != null && mat.family != null ? mat.family.toLowerCase(Locale.ROOT) : "";
        switch (family) {
            case "logs":
                return new String[]{"gather_wood", "5", "gather_break_wood"};
            case "plants":
                return new String[]{"gather_plant", "2", "gather_pickup"};
            case "dead":
            case "herbs":
                return new String[]{"gather_bone", "3", "gather_pickup"};
            case "waters":
            case "crystal":
                return new String[]{"gather_water", "3", "gather_pickup"};
            case "scrap":
                return new String[]{"gather_metal", "3", "gather_break_rock"};
            default: // ore, ash, sacred stone
                return new String[]{"gather_mine", "5", "gather_break_rock"};
        }
    }

    private static void playGatherHitSound(MaterialData mat) {
        String[] set = gatherSoundSet(mat);
        int variant = com.badlogic.gdx.math.MathUtils.random(Integer.parseInt(set[1]) - 1);
        SoundSystem.instance.play(set[0] + "_" + variant, false);
    }

    private static void playGatherFinishSound(MaterialData mat) {
        SoundSystem.instance.play(gatherSoundSet(mat)[2], false);
    }

    /** Floating "+2 Oak" text that rises from a gathered node and fades out. */
    private void floatGatherText(com.badlogic.gdx.scenes.scene2d.Actor node, String text) {
        if (text == null || text.isEmpty())
            return;
        com.github.tommyettinger.textra.TextraLabel label =
                forge.adventure.util.Controls.newTextraLabel("[%60]" + text);
        label.setPosition(node.getX() + node.getWidth() / 2f - label.getPrefWidth() / 2f,
                node.getY() + node.getHeight() + 4f);
        foregroundSprites.addActor(label);
        label.addAction(com.badlogic.gdx.scenes.scene2d.actions.Actions.sequence(
                com.badlogic.gdx.scenes.scene2d.actions.Actions.parallel(
                        com.badlogic.gdx.scenes.scene2d.actions.Actions.moveBy(0f, 18f, 1.2f),
                        com.badlogic.gdx.scenes.scene2d.actions.Actions.fadeOut(1.2f)),
                com.badlogic.gdx.scenes.scene2d.actions.Actions.removeActor()));
    }

    /** Channel duration multiplier from Mining/Quarrying methods and faster_channel enchants. */
    private float gatherChannelMultiplier(AdventurePlayer ap, PlayerSkills.Skill skill, String toolFamily) {
        float mult = 1f;
        if (skill != null) {
            // Ranks stack: every unlocked shorter_channel method applies.
            for (GatheringMethodData.MethodUpgrade method : new com.badlogic.gdx.utils.Array.ArrayIterator<>(
                    GatheringMethodListData.methodsUpToRank(skill.displayName,
                            ap.getGatherMethodRank(skill.displayName)))) {
                if (method != null && "shorter_channel".equalsIgnoreCase(method.effect)
                        && method.effectValue > 0f)
                    mult *= method.effectValue;
            }
        }
        float faster = ap.toolEnchantEffect(toolFamily, "faster_channel");
        if (faster > 0f)
            mult *= Math.max(0.35f, 1f - Math.min(0.65f, faster));
        return mult;
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
            // Walking over a node starts and silently cancels the channel; say how gathering works.
            notifyGatherFail("Stop on the node and stand still to gather.");
            return;
        }
        channelElapsed += delta;
        if (channelElapsed >= nextGatherHitAt && channelElapsed < channelDuration) {
            gatherHit(channelNode);
            nextGatherHitAt += gatherHitInterval;
        }
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

        // CO2: claim first (host authority / guest request), then apply local loot.
        ConfigData cfg = gatherConfig();
        int previewAmount = cfg != null ? cfg.gatherYieldMin : 1;
        if (CoopHooks.isOverworldReady()) {
            if (!CoopOverworldRuntime.get().onGatherComplete(node, previewAmount)) {
                if (!CoopHooks.isWorldAuthority()) {
                    // Guest waiting on host — sprite stays until CLAIMED / deny.
                    return;
                }
                GameHUD.getInstance().addNotification("Node already claimed");
                return;
            }
            if (!CoopHooks.isWorldAuthority()) {
                return; // should not reach; guest returns false above
            }
        }

        AdventurePlayer ap = Current.player();
        StringBuilder msg = new StringBuilder();
        floatMaterial = null;
        floatAmount = 0;
        grantGatherRewards(mat, ap, msg, true);

        // B2: multi-node methods (adjacent trees / blast vein / lumber crew).
        float blastRadius = multiNodeGatherRadius(ap, mat);
        if (blastRadius > 0f) {
            List<ResourceNodeSprite> extras = new ArrayList<>();
            float cx = node.getX() + node.getWidth() / 2f;
            float cy = node.getY() + node.getHeight() / 2f;
            for (Pair<Float, ResourceNodeSprite> pair : nodes) {
                ResourceNodeSprite other = pair.getValue();
                if (other == null || other == node || other.getMaterial() == null)
                    continue;
                MaterialData om = other.getMaterial();
                if (!sameGatherFamily(mat, om))
                    continue;
                float ox = other.getX() + other.getWidth() / 2f;
                float oy = other.getY() + other.getHeight() / 2f;
                float dx = ox - cx;
                float dy = oy - cy;
                if (dx * dx + dy * dy <= blastRadius * blastRadius)
                    extras.add(other);
            }
            for (ResourceNodeSprite extra : extras) {
                if (CoopHooks.isOverworldReady() && CoopHooks.isWorldAuthority()
                        && !CoopOverworldRuntime.get().onGatherComplete(extra, previewAmount, true))
                    continue; // blast extras: quiet, skip per-node range message
                msg.append("; ");
                grantGatherRewards(extra.getMaterial(), ap, msg, false);
                extra.playEffect(Paths.EFFECT_KILL);
                removeNode(extra);
            }
        }

        GameHUD.getInstance().addNotification(msg.toString());
        node.playEffect(Paths.EFFECT_KILL);
        playGatherFinishSound(mat);
        // "+2 Oak (54)": this haul, then the total now in the inventory.
        if (floatMaterial != null && floatAmount > 0)
            floatGatherText(node, "+" + floatAmount + " " + floatMaterial.getDisplayName()
                    + " (" + ap.getMaterial(floatMaterial.id) + ")");
        floatMaterial = null;
        floatAmount = 0;
        removeNode(node);
    }

    private boolean sameGatherFamily(MaterialData a, MaterialData b) {
        if (a == null || b == null || a.family == null || b.family == null)
            return false;
        return a.family.equalsIgnoreCase(b.family);
    }

    /** Largest radius among stacked adjacent/blast/lumber-crew methods; 0 if none. */
    private float multiNodeGatherRadius(AdventurePlayer ap, MaterialData mat) {
        PlayerSkills.Skill skill = PlayerSkills.Skill.fromMaterialSkill(mat.skill);
        if (skill == null)
            return 0f;
        float radius = 0f;
        for (GatheringMethodData.MethodUpgrade method : new com.badlogic.gdx.utils.Array.ArrayIterator<>(
                GatheringMethodListData.methodsUpToRank(skill.displayName,
                        ap.getGatherMethodRank(skill.displayName)))) {
            if (method == null || method.effect == null)
                continue;
            String effect = method.effect.toLowerCase(Locale.ROOT);
            if ("adjacent_same_family".equals(effect) || "lumber_crew".equals(effect)
                    || "blast_vein".equals(effect))
                radius = Math.max(radius, Math.max(16f, method.effectValue));
        }
        return radius;
    }

    /**
     * Apply yield, XP, method/enchant modifiers, and rare extras for one node.
     * {@code primary} controls whether dust/gold/shard rolls run (only once per channel).
     * All unlocked method ranks for the skill stack.
     */
    private void grantGatherRewards(MaterialData mat, AdventurePlayer ap,
                                   StringBuilder msg, boolean primary) {
        if (mat == null)
            return;
        ConfigData cfg = gatherConfig();
        PlayerSkills.Skill skill = PlayerSkills.Skill.fromMaterialSkill(mat.skill);
        int skillLevel = skill != null ? ap.getSkills().getLevel(skill) : 1;
        String toolFamily = mat.toolFamily();
        com.badlogic.gdx.utils.Array<GatheringMethodData.MethodUpgrade> methods = skill != null
                ? GatheringMethodListData.methodsUpToRank(skill.displayName,
                ap.getGatherMethodRank(skill.displayName))
                : new com.badlogic.gdx.utils.Array<>();

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

        // Method yield modifiers (every unlocked rank).
        int purifySteps = 0;
        float graveChance = 0f;
        float upgradeFindChance = 0f;
        boolean elementalBonus = false;
        boolean consecratedBonus = false;
        for (GatheringMethodData.MethodUpgrade method : new com.badlogic.gdx.utils.Array.ArrayIterator<>(methods)) {
            if (method == null || method.effect == null)
                continue;
            String effect = method.effect.toLowerCase(Locale.ROOT);
            if ("double_plant_yield".equals(effect) && "plants".equalsIgnoreCase(mat.family))
                amount *= Math.max(1, Math.round(method.effectValue));
            if ("bonus_yield".equals(effect))
                amount += Math.max(0, Math.round(method.effectValue));
            if ("lumber_crew".equals(effect) && "logs".equalsIgnoreCase(mat.family))
                amount += 1;
            if ("consecrated_quarry".equals(effect) && ("sacred_stone".equalsIgnoreCase(mat.family)
                    || "stone".equalsIgnoreCase(mat.family))) {
                amount += Math.max(0, Math.round(method.effectValue));
                consecratedBonus = true;
            }
            if (("purify_next_tier".equals(effect) || "elemental_condenser".equals(effect))
                    && "waters".equalsIgnoreCase(mat.family))
                purifySteps += Math.max(1, Math.round(method.effectValue));
            if ("elemental_condenser".equals(effect))
                elementalBonus = true;
            if ("grave_lantern".equals(effect))
                graveChance += method.effectValue;
            if ("upgrade_find".equals(effect))
                upgradeFindChance += method.effectValue;
        }
        // Enchant: chance to double yield (only active sockets).
        float doubleChance = ap.toolEnchantEffect(toolFamily, "double_yield");
        if (doubleChance > 0f && rand.nextFloat() < doubleChance)
            amount *= 2;

        MaterialData grantMat = mat;
        if (purifySteps > 0 && "waters".equalsIgnoreCase(mat.family)) {
            MaterialData next = mat;
            for (int i = 0; i < purifySteps; i++) {
                MaterialData up = MaterialListData.nextTierInFamily(next);
                if (up == null)
                    break;
                next = up;
            }
            grantMat = next;
        }
        if (upgradeFindChance > 0f && "scrap".equalsIgnoreCase(mat.family)
                && rand.nextFloat() < upgradeFindChance) {
            MaterialData up = MaterialListData.nextTierInFamily(mat);
            if (up != null)
                grantMat = up;
        }

        boolean autoRefine = ap.hasToolEnchantEffect(toolFamily, "auto_refine");
        if (autoRefine) {
            // Grant then refine so Spellsmithing XP still applies.
            ap.addMaterial(grantMat.id, amount);
            int dust = ap.refineMaterial(grantMat.id, amount);
            msg.append("Refined ").append(amount).append("× ").append(grantMat.getDisplayName())
                    .append(" → ").append(dust).append(" dust");
        } else {
            ap.addMaterial(grantMat.id, amount);
            msg.append("Gathered ").append(amount).append("× ").append(grantMat.getDisplayName());
            // Floating text: the main material plus any extra nodes of the same material.
            if (primary) {
                floatMaterial = grantMat;
                floatAmount = amount;
            } else if (floatMaterial != null && floatMaterial.id.equals(grantMat.id)) {
                floatAmount += amount;
            }
        }
        int xp = Math.max(0, mat.xp) * amount;
        ap.getSkills().onMaterialGathered(skill, xp);

        // Foraging grave lantern: rare dead-thing bonus.
        if (graveChance > 0f
                && ("dead".equalsIgnoreCase(mat.family) || "remains".equalsIgnoreCase(mat.nodeType))
                && rand.nextFloat() < graveChance) {
            MaterialData rareDead = MaterialListData.getFamilyTier("dead", Math.min(4, mat.tier + 1));
            if (rareDead == null)
                rareDead = MaterialListData.getFamilyTier("dead", 4);
            if (rareDead != null) {
                if (autoRefine) {
                    ap.addMaterial(rareDead.id, 1);
                    ap.refineMaterial(rareDead.id, 1);
                } else {
                    ap.addMaterial(rareDead.id, 1);
                }
                msg.append(", ").append(rareDead.getDisplayName());
            }
        }

        float gemChance = cfg != null ? cfg.gatherGemChance : 0.08f;
        gemChance += ap.toolEnchantEffect(toolFamily, "rare_find");
        if (elementalBonus)
            gemChance += 0.15f;
        if (consecratedBonus)
            gemChance += 0.08f;
        if (("ore".equalsIgnoreCase(mat.family) || "vein".equalsIgnoreCase(mat.nodeType))
                && rand.nextFloat() < gemChance) {
            com.badlogic.gdx.utils.Array<MaterialData> gems = MaterialListData.getGems();
            if (gems.size > 0) {
                MaterialData gem = gems.get(rand.nextInt(gems.size));
                ap.addMaterial(gem.id, 1);
                msg.append(", ").append(gem.getDisplayName());
            }
        }
        if (("waters".equalsIgnoreCase(mat.family) || "water".equalsIgnoreCase(mat.nodeType))
                && rand.nextFloat() < gemChance) {
            com.badlogic.gdx.utils.Array<MaterialData> rare = rand.nextBoolean()
                    ? MaterialListData.getCrystals() : MaterialListData.getPearls();
            if (rare.size > 0) {
                MaterialData bonus = rare.get(rand.nextInt(rare.size));
                ap.addMaterial(bonus.id, 1);
                msg.append(", ").append(bonus.getDisplayName());
            }
        }

        if (!primary)
            return;

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

        World world = Current.world();
        int currentBiome = World.highestBiome(world.getBiome((int) ((player.getX() + player.getWidth() / 2f) / world.getTileSize()), (int) (player.getY() / world.getTileSize())));
        List<BiomeData> biomeData = Current.world().getData().GetBiomes();
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
                if (sprite.getData().flying || !Current.world().collidingTile(sprite.boundingRect())) {
                    enemies.add(Pair.of(globalTimer, sprite));
                    foregroundSprites.addActor(sprite);
                    CoopOverworldRuntime.get().onHostEnemySpawned(sprite);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean spawn(EnemyData enemyData) {
        if (enemyData == null)
            return false;
        // EN1: copy + assign theme at spawn so the shared catalog is never mutated
        // and the theme survives save/load. Gyms/League use preparedDeck paths instead.
        EnemyData data = forge.adventure.util.EnemyThemeDecks.assignThemeAtSpawn(enemyData);
        EnemySprite sprite = new EnemySprite(data);
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
                if (sprite.getData().flying || !Current.world().collidingTile(sprite.boundingRect())) {
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
        setBounds(Current.world().getWidthInPixels(), Current.world().getHeightInPixels());
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
            // EN1: optional theme ids (old saves omit the key → no themes).
            List<String> themes = null;
            if (data.containsKey("themes")) {
                try {
                    themes = (List<String>) data.readObject("themes");
                } catch (Exception ignored) {
                    themes = null;
                }
            }
            for (int i = 0; i < timeouts.size(); i++) {
                forge.adventure.data.EnemyData catalog = WorldData.getEnemy(names.get(i));
                forge.adventure.data.EnemyData enemyData = catalog;
                if (Config.ascendant() && catalog != null) {
                    enemyData = new forge.adventure.data.EnemyData(catalog);
                    if (themes != null && i < themes.size()) {
                        String tid = themes.get(i);
                        if (tid != null && !tid.isEmpty())
                            enemyData.themeId = tid;
                    }
                }
                EnemySprite sprite = new EnemySprite(enemyData);
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
                        // Schema 3 ore-line ids (idempotent for already-migrated saves).
                        String matId = MaterialListData.migrateOreLineMaterialId(nMats.get(i));
                        MaterialData mat = MaterialListData.get(matId);
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
        stashedCoopEnemies.clear();
        stashedCoopNodes.clear();
        background.clear();
        player = null;
        CoopOverworldRuntime.get().clearEntityIdMaps();
    }

    @Override
    public SaveFileData save() {
        SaveFileData data = new SaveFileData();

        cachedSaveTimeouts.clear();
        cachedSaveNames.clear();
        cachedSaveXCoords.clear();
        cachedSaveYCoords.clear();
        cachedSaveQuestIDs.clear();
        cachedSaveThemes.clear();

        for (int i = 0; i < enemies.size(); i++) {
            Pair<Float, EnemySprite> enemy = enemies.get(i);
            // Never persist mirrored co-op guest sprites into any save.
            if (CoopOverworldRuntime.get().isMirroredActor(enemy.getValue()))
                continue;
            cachedSaveTimeouts.add(enemy.getKey());
            cachedSaveNames.add(enemy.getValue().getData().getName());
            cachedSaveXCoords.add(enemy.getValue().getX());
            cachedSaveYCoords.add(enemy.getValue().getY());
            cachedSaveQuestIDs.add(enemy.getValue().questStageID);
            String themeId = enemy.getValue().getData().themeId;
            cachedSaveThemes.add(themeId != null ? themeId : "");
        }

        data.storeObject("timeouts", cachedSaveTimeouts);
        data.storeObject("names", cachedSaveNames);
        data.storeObject("x", cachedSaveXCoords);
        data.storeObject("y", cachedSaveYCoords);
        data.storeObject("questStageIDs", cachedSaveQuestIDs);
        data.storeObject("themes", cachedSaveThemes);
        data.store("globalTimer", globalTimer);

        cachedNodeTimeouts.clear();
        cachedNodeMaterialIds.clear();
        cachedNodeXCoords.clear();
        cachedNodeYCoords.clear();
        for (int i = 0; i < nodes.size(); i++) {
            Pair<Float, ResourceNodeSprite> node = nodes.get(i);
            if (CoopOverworldRuntime.get().isMirroredActor(node.getValue()))
                continue;
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
