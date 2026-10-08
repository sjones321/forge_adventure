package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.utils.Array;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;
import forge.adventure.data.ItemListData;
import forge.adventure.data.SkillTreeData;
import forge.adventure.data.SkillTreeListData;
import forge.adventure.data.SkillTreeNodeData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.BanLists;
import forge.adventure.player.PlayerSkills;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.coop.CoopDecklistValidator;
import forge.gamemodes.net.coop.CoopDuelDisconnectPolicy;
import forge.gamemodes.net.coop.CoopDuelIdentity;
import forge.gamemodes.net.coop.CoopDuelInviteState;
import forge.gamemodes.net.coop.CoopDuelMatchPlan;
import forge.gamemodes.net.coop.CoopDuelRateLimiter;
import forge.gamemodes.net.coop.CoopDuelWireLimits;
import forge.gamemodes.net.coop.CoopFightLoadout;
import forge.gamemodes.net.coop.CoopFightLoadoutValidator;
import forge.gamemodes.net.coop.CoopFightRequestValidator;
import forge.gamemodes.net.coop.CoopPartyProximity;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.event.coop.CoopDuelInviteEvent;
import forge.gamemodes.net.event.coop.CoopDuelResponseEvent;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
import forge.gamemodes.net.event.coop.CoopDuelStartEvent;
import forge.gamemodes.net.event.coop.CoopEnemyEncounterRequestEvent;
import forge.gamemodes.net.event.coop.CoopFightLoadoutEvent;
import forge.gamemodes.net.event.coop.CoopFightRequestResultEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.FThreads;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.PlayerControllerHuman;
import forge.screens.match.MatchController;
import forge.sound.MusicPlaylist;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Ascendant co-op duels (CO3). Opt-in join prompts, host match building with
 * team-0 humans + team-1 enemies, FServerManager on the game port for joined
 * fights only, per-side local result paths, and non-blocking guest disconnect.
 *
 * <p>Party / proximity is read through {@link CoopHooks#getPartyProximity()} so
 * this class does not touch CO2 overworld / party / gather code. Default is
 * never-in-party → solo fight. After #18 merges, wire
 * {@link CoopHooks#setPartyProximity} to {@code CoopPartyState.inParty()} /
 * {@code withinRadius(...)}.
 *
 * <p><b>Trust model:</b> card ownership on the guest cannot be verified — the
 * partner is trusted. The host still clamps loadout stats, allowlists effect
 * card names from items.json / skill perks, and enforces min deck + ban list.
 */
public final class CoopDuelRuntime implements CoopHooks.DuelListener, CoopHooks.FightStartHook,
        CoopHooks.GuestEnemyEncounterHandler {
    private static final CoopDuelRuntime INSTANCE = new CoopDuelRuntime();

    private final CoopDuelInviteState inviteState = new CoopDuelInviteState();
    private final CoopDuelDisconnectPolicy disconnectPolicy = new CoopDuelDisconnectPolicy();
    private final CoopFightRequestValidator fightRequestValidator = new CoopFightRequestValidator();
    private final CoopDuelRateLimiter inboundLimiter =
            CoopDuelRateLimiter.perSecond(CoopDuelWireLimits.MAX_DUEL_REQUESTS_PER_SECOND);
    private final AtomicLong duelSeq = new AtomicLong(1L);
    private final AtomicLong localEnemySeq = new AtomicLong(1L);
    private final Map<Long, EnemySprite> enemyById = new ConcurrentHashMap<>();
    private final Set<Long> processedResultDuelIds = ConcurrentHashMap.newKeySet();
    private volatile ScheduledExecutorService timers = newTimerExecutor();

    private volatile ScheduledFuture<?> inviteTimeoutFuture;
    private volatile long activeDuelId;
    private volatile long pendingEnemyId;
    private volatile boolean gameServerStartedByUs;
    private volatile FGameClient guestClient;
    private volatile CoopFightLoadout pendingGuestLoadout;
    private volatile Deck pendingGuestDeck;
    private volatile EnemySprite pendingEnemy;
    private volatile HostedMatch activeHostedMatch;
    private volatile RegisteredPlayer guestRegisteredPlayer;
    private volatile ServerGameLobby coopLobby;
    private volatile boolean attached;

    private CoopDuelRuntime() {
    }

    public static CoopDuelRuntime get() {
        return INSTANCE;
    }

    private static ScheduledExecutorService newTimerExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "coop-duel-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** Idempotent attach for Ascendant co-op sessions. */
    public synchronized void attach() {
        if (attached) {
            return;
        }
        if (!Config.ascendant()) {
            return;
        }
        if (timers == null || timers.isShutdown()) {
            timers = newTimerExecutor();
        }
        CoopSession.get().addDuelListener(this);
        CoopHooks.setFightStartHook(this);
        CoopHooks.setGuestEnemyEncounterHandler(this);
        // Wire to CO2 party state: inParty() + withinRadius via partnersNearby().
        CoopHooks.setPartyProximity(() -> {
            try {
                return CoopHooks.partyState().inParty()
                        && CoopOverworldRuntime.get().partnersNearby();
            } catch (final Exception e) {
                return false;
            }
        });
        attached = true;
    }

    public synchronized void detach() {
        cancelInviteTimeout();
        stopGameServerIfOurs();
        disconnectGuestClient();
        inviteState.clear();
        disconnectPolicy.endDuel();
        clearPendingEncounter();
        activeDuelId = 0L;
        activeHostedMatch = null;
        guestRegisteredPlayer = null;
        if (attached) {
            CoopHooks.setFightStartHook(null);
            CoopHooks.setGuestEnemyEncounterHandler(null);
            try {
                CoopSession.get().removeDuelListener(this);
            } catch (final Exception ignored) {
            }
            attached = false;
        }
        shutdownTimers();
    }

    public CoopDuelInviteState getInviteState() {
        return inviteState;
    }

    public CoopDuelDisconnectPolicy getDisconnectPolicy() {
        return disconnectPolicy;
    }

    public CoopFightRequestValidator getFightRequestValidator() {
        return fightRequestValidator;
    }

    /** Bind an encounter enemy under a stable id (host-local until CO2 registry lands). */
    public long bindEnemy(final EnemySprite mob) {
        if (mob == null) {
            return 0L;
        }
        final long id = localEnemySeq.getAndIncrement();
        enemyById.put(id, mob);
        return id;
    }

    public EnemySprite resolveEnemy(final long enemyId) {
        // CO2 host registry uses positive enemy ids; 0 is never valid.
        return enemyId != 0L ? enemyById.get(enemyId) : null;
    }

    // ---- FightStartHook ----

    @Override
    public boolean onFightAboutToStart(final String encounterId) {
        if (!Config.ascendant() || !CoopHooks.isOverworldReady()) {
            return false;
        }
        if (CoopSession.get().getRole() == CoopSessionRole.GUEST && !CoopHooks.isWorldAuthority()) {
            return false;
        }
        final CoopPartyProximity proximity = CoopHooks.getPartyProximity();
        if (!CoopDuelInviteState.shouldOfferJoin(proximity)) {
            inviteState.markSolo();
            inviteState.clear();
            return false;
        }
        final AdventurePlayer ap = Current.player();
        final String hostName = CoopDuelIdentity.normalizeUsername(ap != null ? ap.getName() : "Player");
        final int timeout = Math.max(5, Config.instance().getConfigData().coopDuelInviteTimeoutSeconds);
        final CoopDuelInviteEvent invite = inviteState.beginInvite(hostName, encounterId, timeout, proximity);
        if (invite == null) {
            inviteState.clear();
            return false;
        }
        final EnemySprite mob = pendingEnemy != null
                ? pendingEnemy
                : WorldStage.getInstance().getCurrentMob();
        pendingEnemy = mob;
        if (pendingEnemyId <= 0L || resolveEnemy(pendingEnemyId) != mob) {
            pendingEnemyId = bindEnemy(mob);
        }
        CoopSession.get().send(invite);
        scheduleInviteTimeout(invite.getInviteId(), timeout);
        notifyHud("Waiting for partner to join the fight…");
        return true;
    }

    /**
     * CO2 calls {@link CoopHooks#notifyGuestEnemyEncounter} when the guest's
     * {@link CoopEnemyEncounterRequestEvent} arrives (after host enemy-exists check).
     * Uses the encounter's {@code enemyId}, never {@code getCurrentMob()}.
     */
    @Override
    public boolean onGuestEnemyEncounter(final long enemyId, final String enemyDataId, final String guestName) {
        if (!Config.ascendant() || !CoopHooks.isWorldAuthority()) {
            return false;
        }
        if (!inboundLimiter.tryAcquire()) {
            return true;
        }
        if (enemyId == 0L) {
            return true;
        }
        final EnemySprite mob = resolveEnemy(enemyId);
        if (mob == null) {
            return true;
        }
        final String enc = CoopDuelWireLimits.clampString(
                enemyDataId != null ? enemyDataId
                        : (mob.getData() != null ? mob.getData().getName() : "enemy"),
                CoopDuelWireLimits.MAX_NAME_LEN);
        if (enc.isEmpty()) {
            return true;
        }
        pendingEnemy = mob;
        pendingEnemyId = enemyId;
        WorldStage.getInstance().setCurrentMob(mob);
        notifyHud((guestName != null ? guestName : "Guest") + " engaged " + enc);
        final boolean deferred = onFightAboutToStart(enc);
        if (!deferred) {
            final EnemySprite toFight = pendingEnemy;
            clearPendingEncounter();
            if (toFight != null) {
                postGl(() -> WorldStage.getInstance().beginEncounterDuel(toFight));
            }
        }
        return true;
    }

    /**
     * Feature/set-start path (no CO2 OverworldRuntime yet): host validates a raw
     * {@link CoopEnemyEncounterRequestEvent} from the wire, then forwards to
     * {@link #onGuestEnemyEncounter} on ACCEPT.
     */
    public void onEnemyEncounterRequestFromWire(final CoopEnemyEncounterRequestEvent event) {
        if (CoopSession.get().getRole() != CoopSessionRole.HOST || event == null) {
            return;
        }
        // Ensure the enemy is resolvable by id for this provisional path.
        if (event.getEnemyId() > 0L && resolveEnemy(event.getEnemyId()) == null) {
            final EnemySprite current = WorldStage.getInstance().getCurrentMob();
            if (current != null) {
                enemyById.put(event.getEnemyId(), current);
            }
        }
        final CoopFightRequestResultEvent result =
                fightRequestValidator.validateOnHost(event, true);
        CoopSession.get().send(result);
        if (result.getDecision() != CoopFightRequestResultEvent.Decision.ACCEPT) {
            return;
        }
        final String guest = CoopSession.get().getPeerName();
        onGuestEnemyEncounter(event.getEnemyId(), event.getEnemyDataId(), guest);
    }

    // ---- DuelListener ----

    @Override
    public void onDuelInvite(final CoopDuelInviteEvent event) {
        if (!inboundLimiter.tryAcquire()) {
            return;
        }
        if (CoopSession.get().getRole() == CoopSessionRole.NONE) {
            return;
        }
        if (!inviteState.receiveInvite(event)) {
            return;
        }
        postGl(() -> showJoinPrompt(event));
        scheduleInviteTimeout(event.getInviteId(),
                Math.max(5, event.getTimeoutSeconds() > 0
                        ? event.getTimeoutSeconds()
                        : Config.instance().getConfigData().coopDuelInviteTimeoutSeconds));
    }

    @Override
    public void onDuelResponse(final CoopDuelResponseEvent event) {
        if (!inboundLimiter.tryAcquire()) {
            return;
        }
        if (inviteState.getStatus() != CoopDuelInviteState.Status.WAITING_RESPONSE
                && CoopSession.get().getRole() != CoopSessionRole.HOST) {
            return;
        }
        if (!inviteState.applyPeerResponse(event)) {
            return;
        }
        cancelInviteTimeout();
        postGl(() -> {
            if (inviteState.isJoined()) {
                final CoopDecklistValidator.Result validated = validateGuestDeck(event.getDecklistText());
                if (!validated.ok()) {
                    notifyHud("Partner deck rejected — fighting solo");
                    proceedSolo();
                    return;
                }
                pendingGuestDeck = validated.deck;
                beginCoopDuelAsHost(validated.deck);
            } else {
                proceedSolo();
            }
        });
    }

    @Override
    public void onFightRequestResult(final CoopFightRequestResultEvent event) {
        if (CoopSession.get().getRole() != CoopSessionRole.GUEST) {
            return;
        }
        if (!fightRequestValidator.guestMayApplyResult(event, true)) {
            return;
        }
        postGl(() -> {
            if (event.getDecision() == CoopFightRequestResultEvent.Decision.DENY) {
                notifyHud("Fight request denied: " + event.getReason());
            }
        });
    }

    @Override
    public void onFightLoadout(final CoopFightLoadoutEvent event) {
        if (CoopSession.get().getRole() != CoopSessionRole.HOST) {
            return;
        }
        if (!inboundLimiter.tryAcquire()) {
            return;
        }
        if (event == null || event.getDuelId() != activeDuelId || activeDuelId <= 0L) {
            return;
        }
        final CoopFightLoadoutValidator.Result loadoutResult = CoopFightLoadoutValidator.validate(
                event.getLoadout(),
                maxKnownLifeBonus(),
                effectCardAllowlist(),
                () -> 20);
        if (!loadoutResult.ok) {
            return;
        }
        final CoopDecklistValidator.Result validated = validateGuestDeck(event.getDecklistText());
        if (!validated.ok()) {
            return;
        }
        pendingGuestLoadout = loadoutResult.loadout;
        pendingGuestDeck = validated.deck;
    }

    @Override
    public void onDuelStart(final CoopDuelStartEvent event) {
        if (CoopSession.get().getRole() != CoopSessionRole.GUEST) {
            return;
        }
        if (event == null || event.getGamePort() <= 0) {
            return;
        }
        final String localCode = CoopSession.get().getSessionCode();
        if (event.getSessionCode() != null && !event.getSessionCode().isEmpty()
                && localCode != null && !localCode.isEmpty()
                && !CoopDuelIdentity.normalizeSessionCode(event.getSessionCode())
                .equals(CoopDuelIdentity.normalizeSessionCode(localCode))) {
            return;
        }
        activeDuelId = event.getDuelId();
        disconnectPolicy.beginDuel(CoopDuelDisconnectPolicy.GuestDisconnectAction.CONCEDE);
        postGl(() -> connectGuestGameClient(event));
    }

    @Override
    public void onDuelResult(final CoopDuelResultEvent event) {
        if (event == null) {
            return;
        }
        // Host already applied its local DuelScene path; ignore guest-shaped or duplicate results.
        if (CoopSession.get().getRole() != CoopSessionRole.GUEST) {
            return;
        }
        if (!processedResultDuelIds.add(event.getDuelId())) {
            return;
        }
        postGl(() -> applyGuestLocalResult(event));
    }

    /** Session peer disconnected — host continues (guest concedes), guest abandons. */
    public void onSessionPeerDisconnected() {
        final CoopSessionRole role = CoopSession.get().getRole();
        if (role == CoopSessionRole.HOST) {
            final CoopDuelDisconnectPolicy.Outcome out = disconnectPolicy.onGuestDisconnected();
            if (out == CoopDuelDisconnectPolicy.Outcome.CONTINUE_HOST_MATCH) {
                concedeGuestSeat();
                notifyHud("Partner disconnected — continuing");
            }
            cancelInviteTimeout();
            if (inviteState.isWaiting()) {
                inviteState.timeout(inviteState.getPendingInviteId());
                postGl(this::proceedSolo);
            }
        } else if (role == CoopSessionRole.GUEST) {
            disconnectPolicy.onSessionLostAsGuest();
            disconnectGuestClient();
            cancelInviteTimeout();
            inviteState.clear();
            postGl(() -> {
                Forge.advFreezePlayerControls = false;
                notifyHud("Disconnected from co-op duel");
            });
            // Guest does not stop a host-owned game server.
            return;
        }
    }

    // ---- Internals ----

    private void showJoinPrompt(final CoopDuelInviteEvent event) {
        final Localizer loc = Forge.getLocalizer();
        final String title = "Join the fight?";
        final String msg = (event.getHostPlayer() != null ? event.getHostPlayer() : "Partner")
                + " started a fight"
                + (event.getEncounterId() != null && !event.getEncounterId().isEmpty()
                ? " (" + event.getEncounterId() + ")" : "")
                + ". Join?";
        FOptionPane.showConfirmDialog(msg, title,
                loc != null ? loc.getMessage("lblYes") : "Yes",
                loc != null ? loc.getMessage("lblNo") : "No",
                false, result -> {
                    if (Boolean.TRUE.equals(result)) {
                        acceptInvite();
                    } else {
                        declineInvite();
                    }
                });
    }

    private void acceptInvite() {
        final String deckText = currentDecklistText();
        final CoopDuelResponseEvent resp = inviteState.respond(true, deckText);
        if (resp == null) {
            return;
        }
        cancelInviteTimeout();
        CoopSession.get().send(resp);
        // Loadout is sent only after CoopDuelStartEvent assigns the duel id (no race with grace).
        notifyHud("Joining the fight…");
    }

    private void declineInvite() {
        final CoopDuelResponseEvent resp = inviteState.respond(false, "");
        cancelInviteTimeout();
        if (resp != null) {
            CoopSession.get().send(resp);
        }
        inviteState.clear();
        notifyHud("Declined fight invite");
    }

    private void scheduleInviteTimeout(final long inviteId, final int timeoutSeconds) {
        cancelInviteTimeout();
        final ScheduledExecutorService exec = timers;
        if (exec == null || exec.isShutdown()) {
            return;
        }
        final int sec = Math.max(5, Math.min(timeoutSeconds, 120));
        inviteTimeoutFuture = exec.schedule(() -> {
            if (inviteState.timeout(inviteId)) {
                postGl(() -> {
                    if (inviteState.getStatus() == CoopDuelInviteState.Status.SOLO
                            || inviteState.getStatus() == CoopDuelInviteState.Status.WAITING_RESPONSE
                            || inviteState.getStatus() == CoopDuelInviteState.Status.PROMPT_OPEN) {
                        if (CoopSession.get().getRole() != CoopSessionRole.HOST
                                || inviteState.getStatus() == CoopDuelInviteState.Status.PROMPT_OPEN) {
                            CoopSession.get().send(new CoopDuelResponseEvent(inviteId, false, ""));
                        }
                        notifyHud("Fight invite timed out — solo fight");
                        proceedSolo();
                    }
                });
            }
        }, sec, TimeUnit.SECONDS);
    }

    private void cancelInviteTimeout() {
        final ScheduledFuture<?> f = inviteTimeoutFuture;
        inviteTimeoutFuture = null;
        if (f != null) {
            f.cancel(false);
        }
    }

    private void proceedSolo() {
        inviteState.clear();
        final EnemySprite mob = pendingEnemy != null ? pendingEnemy
                : resolveEnemy(pendingEnemyId);
        clearPendingEncounter();
        if (mob != null) {
            WorldStage.getInstance().beginEncounterDuel(mob);
        } else {
            Forge.advFreezePlayerControls = false;
        }
    }

    private void beginCoopDuelAsHost(final Deck guestDeck) {
        final EnemySprite mob = pendingEnemy != null ? pendingEnemy : resolveEnemy(pendingEnemyId);
        if (mob == null) {
            proceedSolo();
            return;
        }
        activeDuelId = duelSeq.getAndIncrement();
        pendingGuestDeck = guestDeck;
        pendingGuestLoadout = null; // wait for post-start loadout from guest
        disconnectPolicy.beginDuel(CoopDuelDisconnectPolicy.GuestDisconnectAction.CONCEDE);
        try {
            startGameServerForDuel();
        } catch (final Exception e) {
            notifyHud("Co-op duel server failed — fighting solo");
            stopGameServerIfOurs();
            proceedSolo();
            return;
        }
        final ConfigData cfg = Config.instance().getConfigData();
        final String bind = CoopSession.get().getBindAddress();
        CoopSession.get().send(new CoopDuelStartEvent(
                activeDuelId,
                CoopSession.get().getGamePort(),
                bind != null ? bind : "",
                inviteState.getEncounterId(),
                CoopSession.get().getSessionCode()));
        final int grace = Math.max(2, Math.min(30, cfg.coopDuelGuestConnectGraceSeconds));
        final long duelIdAtStart = activeDuelId;
        final long enemyIdAtStart = pendingEnemyId;
        final ScheduledExecutorService exec = timers;
        if (exec == null || exec.isShutdown()) {
            postGl(() -> startHostedCoopMatch(mob, enemyIdAtStart));
        } else {
            exec.schedule(() -> postGl(() -> {
                if (activeDuelId != duelIdAtStart) {
                    return;
                }
                startHostedCoopMatch(mob, enemyIdAtStart);
            }), grace, TimeUnit.SECONDS);
        }
        inviteState.clear();
    }

    private void startGameServerForDuel() {
        final FServerManager server = FServerManager.getInstance();
        if (server == null) {
            throw new IllegalStateException("FServerManager unavailable");
        }
        final String bind = CoopSession.get().getBindAddress();
        final int port = CoopSession.get().getGamePort() > 0
                ? CoopSession.get().getGamePort()
                : CoopPorts.GAME_PORT;
        final String peer = CoopDuelIdentity.normalizeUsername(CoopSession.get().getPeerName());
        final String code = CoopDuelIdentity.normalizeSessionCode(CoopSession.get().getSessionCode());
        // Lobby must exist before LoginEvent so getGui(slot) can resolve the guest remote.
        coopLobby = new ServerGameLobby();
        server.setLobby(coopLobby);
        server.setCoopSessionGate(peer, code);
        server.startServer(port, bind, Boolean.FALSE);
        gameServerStartedByUs = true;
    }

    private void stopGameServerIfOurs() {
        if (!gameServerStartedByUs) {
            return;
        }
        gameServerStartedByUs = false;
        try {
            final FServerManager server = FServerManager.getInstance();
            if (server != null) {
                server.clearCoopSessionGate();
                server.stopServer();
                server.setLobby(null);
            }
        } catch (final Exception ignored) {
        }
        coopLobby = null;
    }

    private void startHostedCoopMatch(final EnemySprite mob, final long enemyId) {
        try {
            final FServerManager server = FServerManager.getInstance();
            final IGuiGame remoteGui = server != null ? server.getGui(1) : null;
            if (remoteGui == null) {
                notifyHud("Partner did not connect — fighting solo");
                stopGameServerIfOurs();
                disconnectPolicy.endDuel();
                proceedSolo();
                return;
            }
            if (pendingGuestLoadout == null) {
                pendingGuestLoadout = CoopFightLoadout.builder()
                        .playerName(CoopDuelIdentity.normalizeUsername(CoopSession.get().getPeerName()))
                        .avatarId("guest")
                        .startingLife(20)
                        .build();
            }
            if (pendingGuestDeck == null) {
                notifyHud("Partner deck missing — fighting solo");
                stopGameServerIfOurs();
                disconnectPolicy.endDuel();
                proceedSolo();
                return;
            }

            final AdventurePlayer advPlayer = Current.player();
            final Deck hostDeck = advPlayer.getSelectedDeck() != null
                    ? (Deck) advPlayer.getSelectedDeck().copyTo("HostDeckCopy")
                    : new Deck("Empty");
            final ConfigData cfg = Config.instance().getConfigData();
            final float lifeFactor = cfg.coopDuelEnemyLifeFactor;
            final int extraCards = cfg.coopDuelEnemyExtraCards;
            final int baseFreeMulligans = cfg.adventureFreeMulligans;

            final List<CoopDuelMatchPlan.EnemySpec> enemies = new ArrayList<>();
            forge.adventure.data.EnemyData current = mob.getData();
            for (int i = 0; i < 8 && current != null; i++) {
                final Deck enemyDeck = current.copyPlayerDeck
                        ? hostDeck
                        : current.generateDeck(advPlayer.isFantasyMode(), false);
                enemies.add(new CoopDuelMatchPlan.EnemySpec(
                        current.getName() != null ? current.getName() : "Enemy",
                        "enemy-" + i,
                        enemyDeck != null ? enemyDeck : hostDeck,
                        current.life,
                        baseFreeMulligans));
                current = current.nextEnemy;
            }

            final CoopDuelMatchPlan plan = CoopDuelMatchPlan.build(
                    CoopDuelIdentity.normalizeUsername(advPlayer.getName()),
                    "host",
                    hostDeck,
                    advPlayer.getLife(),
                    advPlayer.getShards(),
                    baseFreeMulligans + advPlayer.getSkills().bonusFreeMulligans(),
                    true,
                    pendingGuestLoadout,
                    pendingGuestDeck,
                    enemies,
                    lifeFactor,
                    extraCards);

            final int playerCount = plan.getSeats().size();
            final Set<GameType> variants = EnumSet.of(GameType.Adventure);
            final List<RegisteredPlayer> players = new ArrayList<>();
            final Map<RegisteredPlayer, IGuiGame> guiMap = new HashMap<>();
            RegisteredPlayer hostRp = null;
            RegisteredPlayer guestRp = null;

            for (final CoopDuelMatchPlan.Seat seat : plan.getSeats()) {
                final RegisteredPlayer rp = RegisteredPlayer.forVariants(
                        playerCount, variants, seat.deck, null, false, null, null);
                rp.setTeamNumber(seat.teamNumber);
                rp.setStartingLife(Math.max(1, seat.startingLife));
                rp.setManaShards(Math.max(0, seat.manaShards));
                rp.setFreeMulligans(Math.max(0, seat.freeMulligans));
                if (seat.startingHandBonus != 0) {
                    rp.setStartingHand(rp.getStartingHand() + seat.startingHandBonus);
                }
                if (seat.kind == CoopDuelMatchPlan.SeatKind.HOST_HUMAN) {
                    rp.setPlayer(GamePlayerUtil.getGuiPlayer());
                    applyHostAdventureEffects(rp, advPlayer);
                    guiMap.put(rp, MatchController.instance);
                    hostRp = rp;
                } else if (seat.kind == CoopDuelMatchPlan.SeatKind.GUEST_HUMAN) {
                    rp.setPlayer(GamePlayerUtil.getGuiPlayer(
                            CoopDuelIdentity.normalizeUsername(seat.displayName), 0, 0, false));
                    applyGuestLoadoutEffects(rp, pendingGuestLoadout);
                    // Never give the guest seat to MatchController — remote or abort.
                    guiMap.put(rp, remoteGui);
                    guestRp = rp;
                } else {
                    rp.setPlayer(GamePlayerUtil.createAiPlayer(seat.displayName, null));
                }
                players.add(rp);
            }

            final HostedMatch hostedMatch = MatchController.hostMatch();
            activeHostedMatch = hostedMatch;
            guestRegisteredPlayer = guestRp;
            final GameRules rules = new GameRules(GameType.Adventure);
            rules.setGamesPerMatch(mob.getData() != null ? mob.getData().gamesPerMatch : 1);
            rules.setManaBurn(false);
            rules.setWarnAboutAICards(false);
            final RegisteredPlayer hostRpFinal = hostRp;
            final EnemySprite mobFinal = mob;
            final long duelIdFinal = activeDuelId;
            final long enemyIdFinal = enemyId > 0L ? enemyId : pendingEnemyId;
            hostedMatch.setEndGameHook(() -> onHostGameEnded(
                    hostedMatch, hostRpFinal, mobFinal, duelIdFinal, enemyIdFinal));
            hostedMatch.startMatch(rules, variants, players, guiMap,
                    mob.getData() != null && mob.getData().boss ? MusicPlaylist.BOSS : MusicPlaylist.MATCH);
            MatchController.instance.setGameView(hostedMatch.getGameView());
            Forge.advFreezePlayerControls = false;
            notifyHud("Co-op duel started");
        } catch (final Exception e) {
            notifyHud("Co-op match failed — fighting solo");
            stopGameServerIfOurs();
            disconnectPolicy.endDuel();
            activeHostedMatch = null;
            guestRegisteredPlayer = null;
            proceedSolo();
        }
    }

    /**
     * Runs after each game. Only emits match outcome when the match is over
     * (gamesPerMatch &gt; 1). Character changes / server stop happen on the GL
     * thread after HostedMatch's flush.
     */
    private void onHostGameEnded(final HostedMatch hostedMatch, final RegisteredPlayer hostRp,
                                 final EnemySprite mob, final long duelId, final long enemyId) {
        final Match match = hostedMatch != null ? hostedMatch.getMatch() : null;
        if (match == null || !match.isMatchOver()) {
            autoContinueHumans(hostedMatch);
            return;
        }
        // Defer past HostedMatch's ProtocolGuiGame flush on this game thread.
        postGl(() -> finishHostMatch(hostedMatch, hostRp, mob, duelId, enemyId));
    }

    private void autoContinueHumans(final HostedMatch hostedMatch) {
        if (hostedMatch == null) {
            return;
        }
        try {
            for (final PlayerControllerHuman hc : hostedMatch.getHumanControllers()) {
                if (hc != null) {
                    hc.nextGameDecision(NextGameDecision.CONTINUE);
                }
            }
        } catch (final Exception ignored) {
        }
    }

    private void finishHostMatch(final HostedMatch hostedMatch, final RegisteredPlayer hostRp,
                                 final EnemySprite mob, final long duelId, final long enemyId) {
        if (duelId <= 0L || !processedResultDuelIds.add(duelId)) {
            return;
        }
        int winningTeam = -1;
        try {
            if (hostedMatch != null && hostedMatch.getMatch() != null) {
                final RegisteredPlayer winner = hostedMatch.getMatch().getWinner();
                if (winner != null) {
                    winningTeam = winner.getTeamNumber();
                } else if (hostRp != null && hostedMatch.getMatch().isWonBy(hostRp.getPlayer())) {
                    winningTeam = 0;
                }
            }
        } catch (final Exception ignored) {
        }
        final boolean teamWon = winningTeam == 0;
        final String encounterId = mob != null && mob.getData() != null ? mob.getData().getName() : "";
        final CoopDuelResultEvent result = new CoopDuelResultEvent(duelId, winningTeam, enemyId, encounterId);
        CoopSession.get().send(result);

        // Local DuelScene / WorldStage result path (loot, removeEnemy, XP, penalties).
        applyHostLocalResult(teamWon, mob);

        disconnectPolicy.endDuel();
        stopGameServerIfOurs();
        disconnectGuestClient();
        clearPendingEncounter();
        activeHostedMatch = null;
        guestRegisteredPlayer = null;
        pendingGuestDeck = null;
        pendingGuestLoadout = null;
        activeDuelId = 0L;
    }

    private void applyHostLocalResult(final boolean teamWon, final EnemySprite mob) {
        if (mob != null) {
            WorldStage.getInstance().setCurrentMob(mob);
            WorldStage.getInstance().setWinner(teamWon, false);
            return;
        }
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return;
        }
        if (teamWon) {
            ap.win(false);
        } else {
            ap.defeated();
        }
    }

    private void applyGuestLocalResult(final CoopDuelResultEvent event) {
        final AdventurePlayer ap = Current.player();
        if (ap == null || event == null) {
            return;
        }
        // Never apply host character changes from wire numbers — outcome only.
        final boolean teamWon = event.isTeamWon();
        final EnemySprite mob = WorldStage.getInstance().getCurrentMob();
        if (mob != null) {
            WorldStage.getInstance().setWinner(teamWon, false);
        } else {
            if (teamWon) {
                ap.win(false);
            } else {
                ap.defeated();
            }
            try {
                CoopCharacterStore.savePlayer(ap);
            } catch (final Exception ignored) {
            }
        }
        disconnectPolicy.endDuel();
        disconnectGuestClient();
        activeDuelId = 0L;
    }

    private void concedeGuestSeat() {
        final HostedMatch match = activeHostedMatch;
        final RegisteredPlayer guestRp = guestRegisteredPlayer;
        if (match == null || guestRp == null) {
            return;
        }
        try {
            for (final PlayerControllerHuman hc : match.getHumanControllers()) {
                if (hc != null && hc.getPlayer() != null
                        && hc.getPlayer().getRegisteredPlayer() == guestRp) {
                    hc.concede();
                    return;
                }
            }
        } catch (final Exception ignored) {
        }
    }

    private void connectGuestGameClient(final CoopDuelStartEvent event) {
        disconnectGuestClient();
        try {
            final String host = resolveGameHost(event);
            final AdventurePlayer ap = Current.player();
            final String name = CoopDuelIdentity.normalizeUsername(ap != null ? ap.getName() : "Guest");
            final String code = CoopDuelIdentity.normalizeSessionCode(CoopSession.get().getSessionCode());
            final IGuiGame gui = MatchController.instance;
            guestClient = new FGameClient(name, gui, host, event.getGamePort(), code);
            guestClient.connect();
            // Loadout AFTER duel id assignment (this event).
            final CoopFightLoadout loadout = buildLocalLoadout();
            CoopSession.get().send(new CoopFightLoadoutEvent(
                    event.getDuelId(), loadout, currentDecklistText()));
            notifyHud("Connected to co-op duel");
        } catch (final Exception e) {
            notifyHud("Failed to connect to co-op duel");
            disconnectGuestClient();
        }
    }

    private String resolveGameHost(final CoopDuelStartEvent event) {
        final String joined = CoopSession.get().getJoinHostAddress();
        if (joined != null && !joined.isEmpty()) {
            return joined;
        }
        final String hint = event.getBindAddressHint();
        if (hint != null && !hint.isEmpty()) {
            return hint;
        }
        return "127.0.0.1";
    }

    private void disconnectGuestClient() {
        final FGameClient c = guestClient;
        guestClient = null;
        if (c != null) {
            try {
                c.close();
            } catch (final Exception ignored) {
            }
        }
    }

    private void clearPendingEncounter() {
        pendingEnemy = null;
        pendingEnemyId = 0L;
    }

    private CoopFightLoadout buildLocalLoadout() {
        final AdventurePlayer ap = Current.player();
        if (ap == null) {
            return CoopFightLoadout.builder().playerName("Player").startingLife(20).build();
        }
        final List<String> startCards = new ArrayList<>();
        final List<String> commandCards = new ArrayList<>();
        final List<String> itemIds = new ArrayList<>();
        int lifeMod = 0;
        int handMod = 0;
        int shardsExtra = 0;
        int oppLife = 0;
        int oppHand = 0;
        final ConfigData cfg = Config.instance().getConfigData();
        int freeMull = cfg.adventureFreeMulligans + ap.getSkills().bonusFreeMulligans();
        for (final Long id : ap.getEquippedItems()) {
            final ItemData item = ap.getEquippedItem(id);
            if (item == null || item.effect == null) {
                continue;
            }
            itemIds.add(String.valueOf(id));
            lifeMod += item.effect.lifeModifier;
            handMod += item.effect.changeStartCards;
            shardsExtra += item.effect.extraManaShards;
            freeMull += item.effect.freeMulligans;
            accumulateCardNames(item.effect, startCards, commandCards);
            if (item.effect.opponent != null) {
                oppLife += item.effect.opponent.lifeModifier;
                oppHand += item.effect.opponent.changeStartCards;
            }
        }
        final EffectData perks = ap.getSkills().duelPerks();
        if (perks != null) {
            lifeMod += perks.lifeModifier;
            handMod += perks.changeStartCards;
            shardsExtra += perks.extraManaShards;
            freeMull += perks.freeMulligans;
            accumulateCardNames(perks, startCards, commandCards);
            if (perks.opponent != null) {
                oppLife += perks.opponent.lifeModifier;
                oppHand += perks.opponent.changeStartCards;
            }
        }
        final EffectData badges = ap.badgePerks();
        if (badges != null) {
            lifeMod += badges.lifeModifier;
            handMod += badges.changeStartCards;
            shardsExtra += badges.extraManaShards;
            accumulateCardNames(badges, startCards, commandCards);
            if (badges.opponent != null) {
                oppLife += badges.opponent.lifeModifier;
                oppHand += badges.opponent.changeStartCards;
            }
        }
        if (ap.getBlessing() != null) {
            lifeMod += ap.getBlessing().lifeModifier;
            handMod += ap.getBlessing().changeStartCards;
            shardsExtra += ap.getBlessing().extraManaShards;
            accumulateCardNames(ap.getBlessing(), startCards, commandCards);
        }
        // Clamp locally before send (host re-validates).
        handMod = Math.max(CoopFightLoadoutValidator.MIN_HAND_DELTA,
                Math.min(handMod, CoopFightLoadoutValidator.MAX_HAND_DELTA));
        final int cappedLife = Math.min(ap.getLife() + lifeMod, ap.getLife() + maxKnownLifeBonus());
        return CoopFightLoadout.builder()
                .playerName(CoopDuelIdentity.normalizeUsername(ap.getName()))
                .avatarId(ap.getName())
                .startingLife(Math.max(1, cappedLife))
                .manaShards(ap.getShards())
                .freeMulligans(freeMull)
                .lifeModifier(0)
                .changeStartCards(handMod)
                .extraManaShards(shardsExtra)
                .startBattleCardNames(startCards)
                .commandZoneCardNames(commandCards)
                .equippedItemIds(itemIds)
                .opponentLifeModifier(oppLife)
                .opponentChangeStartCards(oppHand)
                .build();
    }

    private static void accumulateCardNames(final EffectData effect, final List<String> start,
                                            final List<String> command) {
        if (effect.startBattleWithCard != null) {
            for (final String n : effect.startBattleWithCard) {
                if (n != null && !n.isEmpty() && start.size() < CoopDuelWireLimits.MAX_EFFECT_CARD_NAMES) {
                    start.add(n);
                }
            }
        }
        if (effect.startBattleWithCardInCommandZone != null) {
            for (final String n : effect.startBattleWithCardInCommandZone) {
                if (n != null && !n.isEmpty() && command.size() < CoopDuelWireLimits.MAX_COMMAND_CARDS) {
                    command.add(n);
                }
            }
        }
    }

    private void applyHostAdventureEffects(final RegisteredPlayer rp, final AdventurePlayer ap) {
        final Array<EffectData> effects = new Array<>();
        for (final Long id : ap.getEquippedItems()) {
            final ItemData item = ap.getEquippedItem(id);
            if (item != null && item.effect != null) {
                effects.add(item.effect);
            }
        }
        if (ap.getBlessing() != null) {
            effects.add(ap.getBlessing());
        }
        effects.add(ap.getSkills().duelPerks());
        effects.add(ap.badgePerks());
        int lifeMod = 0;
        int hand = 0;
        int shards = 0;
        int mull = 0;
        for (final EffectData data : effects) {
            if (data == null) {
                continue;
            }
            lifeMod += data.lifeModifier;
            hand += data.changeStartCards;
            shards += data.extraManaShards;
            mull += data.freeMulligans;
            rp.addExtraCardsOnBattlefield(data.startBattleWithCards());
            rp.addExtraCardsInCommandZone(data.startBattleWithCardsInCommandZone());
        }
        if (lifeMod != 0) {
            rp.setStartingLife(Math.max(1, rp.getStartingLife() + lifeMod));
        }
        rp.setStartingHand(rp.getStartingHand() + hand);
        rp.setManaShards(rp.getManaShards() + shards);
        rp.setFreeMulligans(rp.getFreeMulligans() + mull);
        rp.setEnableETBCountersEffect(true);
    }

    private void applyGuestLoadoutEffects(final RegisteredPlayer rp, final CoopFightLoadout loadout) {
        if (loadout == null) {
            return;
        }
        final List<forge.item.IPaperCard> start = new ArrayList<>();
        final List<forge.item.IPaperCard> command = new ArrayList<>();
        for (final String name : loadout.getStartBattleCardNames()) {
            final PaperCard c = FModel.getMagicDb().getCommonCards().getCard(name);
            if (c != null) {
                start.add(c);
            }
        }
        for (final String name : loadout.getCommandZoneCardNames()) {
            final PaperCard c = FModel.getMagicDb().getCommonCards().getCard(name);
            if (c != null) {
                command.add(c);
            }
        }
        rp.addExtraCardsOnBattlefield(start);
        rp.addExtraCardsInCommandZone(command);
        rp.setEnableETBCountersEffect(true);
    }

    private String currentDecklistText() {
        final AdventurePlayer ap = Current.player();
        if (ap == null || ap.getSelectedDeck() == null) {
            return "";
        }
        return DeckSerializer.toDecklistText(ap.getSelectedDeck());
    }

    private CoopDecklistValidator.Result validateGuestDeck(final String decklistText) {
        final ConfigData cfg = Config.instance().getConfigData();
        final int minMain = Math.max(0, cfg.minDeckSize);
        return CoopDecklistValidator.validate(decklistText, cardNameOk(), minMain, adventureBanned());
    }

    private static Predicate<String> cardNameOk() {
        return name -> {
            if (name == null || name.isEmpty()) {
                return false;
            }
            try {
                return FModel.getMagicDb().getCommonCards().getCard(name) != null
                        || FModel.getMagicDb().getAllTokens().getToken(name) != null;
            } catch (final Exception e) {
                return false;
            }
        };
    }

    private static Predicate<String> adventureBanned() {
        return name -> BanLists.isBanned("standard", name)
                || BanLists.isBanned("historic", name)
                || BanLists.isBanned("commander", name);
    }

    /** Largest lifeModifier any known item or skill-perk effect can grant. */
    static int maxKnownLifeBonus() {
        int max = 0;
        try {
            for (final ItemData item : new Array.ArrayIterator<>(ItemListData.getAllItems())) {
                if (item != null && item.effect != null) {
                    max = Math.max(max, item.effect.lifeModifier);
                    if (item.effect.opponent != null) {
                        max = Math.max(max, item.effect.opponent.lifeModifier);
                    }
                }
            }
            for (final SkillTreeData tree : SkillTreeListData.allTrees()) {
                if (tree == null || tree.nodes == null) {
                    continue;
                }
                for (final SkillTreeNodeData node : tree.nodes) {
                    if (node == null || node.effect == null) {
                        continue;
                    }
                    final int ranks = Math.max(1, node.maxRanks);
                    max = Math.max(max, node.effect.lifeModifier * ranks);
                }
            }
        } catch (final Exception ignored) {
        }
        return Math.max(0, max);
    }

    /** Card names grantable by items.json or skill-perk effects. */
    static Set<String> effectCardAllowlist() {
        final Set<String> out = new HashSet<>();
        try {
            final List<String[]> arrays = new ArrayList<>();
            for (final ItemData item : new Array.ArrayIterator<>(ItemListData.getAllItems())) {
                collectEffectCardArrays(item != null ? item.effect : null, arrays);
            }
            for (final SkillTreeData tree : SkillTreeListData.allTrees()) {
                if (tree == null || tree.nodes == null) {
                    continue;
                }
                for (final SkillTreeNodeData node : tree.nodes) {
                    collectEffectCardArrays(node != null ? node.effect : null, arrays);
                }
            }
            out.addAll(CoopFightLoadoutValidator.allowlistFromEffects(() -> arrays));
        } catch (final Exception ignored) {
        }
        return out;
    }

    private static void collectEffectCardArrays(final EffectData effect, final List<String[]> out) {
        if (effect == null) {
            return;
        }
        if (effect.startBattleWithCard != null) {
            out.add(effect.startBattleWithCard);
        }
        if (effect.startBattleWithCardInCommandZone != null) {
            out.add(effect.startBattleWithCardInCommandZone);
        }
        if (effect.opponent != null) {
            collectEffectCardArrays(effect.opponent, out);
        }
    }

    private void shutdownTimers() {
        final ScheduledExecutorService exec = timers;
        if (exec == null) {
            return;
        }
        try {
            exec.shutdownNow();
        } catch (final Exception ignored) {
        }
    }

    private static void postGl(final Runnable r) {
        if (r == null) {
            return;
        }
        if (Gdx.app != null) {
            Gdx.app.postRunnable(() -> {
                try {
                    r.run();
                } catch (final Exception ignored) {
                }
            });
        } else {
            FThreads.invokeInEdtNowOrLater(() -> {
                try {
                    r.run();
                } catch (final Exception ignored) {
                }
            });
        }
    }

    private static void notifyHud(final String msg) {
        try {
            if (GameHUD.getInstance() != null) {
                GameHUD.getInstance().addNotification(msg);
            }
        } catch (final Exception ignored) {
            System.out.println("[co-op duel] " + msg);
        }
    }
}
