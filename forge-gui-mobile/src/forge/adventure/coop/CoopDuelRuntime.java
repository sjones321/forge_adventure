package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.adventure.character.EnemySprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerSkills;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.deck.Deck;
import forge.deck.io.DeckSerializer;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.coop.CoopDecklistValidator;
import forge.gamemodes.net.coop.CoopDuelDisconnectPolicy;
import forge.gamemodes.net.coop.CoopDuelInviteState;
import forge.gamemodes.net.coop.CoopDuelMatchPlan;
import forge.gamemodes.net.coop.CoopDuelRateLimiter;
import forge.gamemodes.net.coop.CoopDuelRewards;
import forge.gamemodes.net.coop.CoopDuelWireLimits;
import forge.gamemodes.net.coop.CoopFightLoadout;
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
import forge.gui.FThreads;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.screens.match.MatchController;
import forge.sound.MusicPlaylist;
import forge.toolbox.FOptionPane;
import forge.util.Localizer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Ascendant co-op duels (CO3). Opt-in join prompts, host match building with
 * team-0 humans + team-1 enemies, FServerManager on the game port for joined
 * fights only, per-side rewards, and non-blocking guest disconnect handling.
 *
 * <p>Party / proximity is read through {@link CoopHooks#getPartyProximity()} so
 * this class does not touch CO2 overworld / party / gather code. Default is
 * never-in-party → solo fight.
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
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        final Thread t = new Thread(r, "coop-duel-timer");
        t.setDaemon(true);
        return t;
    });

    private volatile ScheduledFuture<?> inviteTimeoutFuture;
    private volatile long activeDuelId;
    private volatile boolean gameServerStartedByUs;
    private volatile FGameClient guestClient;
    private volatile CoopFightLoadout pendingGuestLoadout;
    private volatile Deck pendingGuestDeck;
    private volatile EnemySprite pendingEnemy;
    private volatile boolean attached;

    private CoopDuelRuntime() {
    }

    public static CoopDuelRuntime get() {
        return INSTANCE;
    }

    /** Idempotent attach for Ascendant co-op sessions. */
    public synchronized void attach() {
        if (attached) {
            return;
        }
        if (!Config.ascendant()) {
            return;
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
        pendingEnemy = null;
        pendingGuestLoadout = null;
        pendingGuestDeck = null;
        activeDuelId = 0L;
        if (attached) {
            CoopHooks.setFightStartHook(null);
            CoopHooks.setGuestEnemyEncounterHandler(null);
            try {
                CoopSession.get().removeDuelListener(this);
            } catch (final Exception ignored) {
            }
            attached = false;
        }
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

    // ---- FightStartHook ----

    @Override
    public boolean onFightAboutToStart(final String encounterId) {
        if (!Config.ascendant() || !CoopHooks.isOverworldReady()) {
            return false;
        }
        // Guests never start a fight against a mirrored enemy locally (host-owned).
        // On feature/set-start without CO2 mirrors, guests still fight local enemies solo.
        if (CoopSession.get().getRole() == CoopSessionRole.GUEST && !CoopHooks.isWorldAuthority()) {
            // Guest local collision with a mirrored enemy should go through
            // CoopEnemyEncounterRequestEvent (CO2), not this host-side join prompt.
            return false;
        }
        final CoopPartyProximity proximity = CoopHooks.getPartyProximity();
        if (!CoopDuelInviteState.shouldOfferJoin(proximity)) {
            inviteState.markSolo();
            inviteState.clear();
            return false; // normal solo fight
        }
        final AdventurePlayer ap = Current.player();
        final String hostName = ap != null ? ap.getName() : "Player";
        final int timeout = Math.max(5, Config.instance().getConfigData().coopDuelInviteTimeoutSeconds);
        final CoopDuelInviteEvent invite = inviteState.beginInvite(hostName, encounterId, timeout, proximity);
        if (invite == null) {
            inviteState.clear();
            return false;
        }
        pendingEnemy = WorldStage.getInstance().getCurrentMob();
        CoopSession.get().send(invite);
        scheduleInviteTimeout(invite.getInviteId(), timeout);
        notifyHud("Waiting for partner to join the fight…");
        return true; // deferred
    }

    /**
     * CO2 calls {@link CoopHooks#notifyGuestEnemyEncounter} when the guest's
     * {@link CoopEnemyEncounterRequestEvent} arrives (after host enemy-exists check).
     * Host decides; guest is a mirror and never starts a local duel.
     *
     * @return true when CO3 consumed the encounter (join prompt and/or host fight start)
     */
    @Override
    public boolean onGuestEnemyEncounter(final long enemyId, final String enemyDataId, final String guestName) {
        if (!Config.ascendant() || !CoopHooks.isWorldAuthority()) {
            return false;
        }
        if (!inboundLimiter.tryAcquire()) {
            return true; // consumed / rate-limited
        }
        if (enemyId <= 0L) {
            return true;
        }
        final String enc = CoopDuelWireLimits.clampString(
                enemyDataId != null ? enemyDataId : "enemy", CoopDuelWireLimits.MAX_NAME_LEN);
        if (enc.isEmpty()) {
            return true;
        }
        notifyHud((guestName != null ? guestName : "Guest") + " engaged " + enc);
        // CO2 already validated enemy existence before calling this. Offer the
        // party partner a join prompt when nearby; otherwise host solos.
        final boolean deferred = onFightAboutToStart(enc);
        if (!deferred) {
            final EnemySprite mob = WorldStage.getInstance().getCurrentMob();
            if (mob != null) {
                postGl(() -> WorldStage.getInstance().beginEncounterDuel(mob));
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
        // Only the non-initiator (partner) should open a prompt.
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
        // Host (invite sender) applies peer response. Guest ignores responses it didn't wait for.
        if (CoopSession.get().getRole() != CoopSessionRole.HOST
                && inviteState.getStatus() != CoopDuelInviteState.Status.WAITING_RESPONSE) {
            // Guest may also be WAITING if they started the fight — allow either role when waiting.
            if (inviteState.getStatus() != CoopDuelInviteState.Status.WAITING_RESPONSE) {
                return;
            }
        }
        if (!inviteState.applyPeerResponse(event)) {
            return;
        }
        cancelInviteTimeout();
        postGl(() -> {
            if (inviteState.isJoined()) {
                final CoopDecklistValidator.Result validated = CoopDecklistValidator.validate(
                        event.getDecklistText(), cardNameOk());
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
        if (event == null || event.getDuelId() != activeDuelId) {
            return;
        }
        final CoopFightLoadout loadout = CoopFightLoadout.validateOrNull(event.getLoadout());
        if (loadout == null) {
            return;
        }
        final CoopDecklistValidator.Result validated =
                CoopDecklistValidator.validate(event.getDecklistText(), cardNameOk());
        if (!validated.ok()) {
            return;
        }
        pendingGuestLoadout = loadout;
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
        // Require matching session code from the authenticated overworld session.
        final String localCode = CoopSession.get().getSessionCode();
        if (event.getSessionCode() != null && !event.getSessionCode().isEmpty()
                && localCode != null && !localCode.isEmpty()
                && !event.getSessionCode().equalsIgnoreCase(localCode)) {
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
        postGl(() -> applyLocalRewards(event));
    }

    /** Session peer disconnected — host continues, guest abandons. */
    public void onSessionPeerDisconnected() {
        final CoopSessionRole role = CoopSession.get().getRole();
        if (role == CoopSessionRole.HOST) {
            final CoopDuelDisconnectPolicy.Outcome out = disconnectPolicy.onGuestDisconnected();
            if (out == CoopDuelDisconnectPolicy.Outcome.CONTINUE_HOST_MATCH) {
                // Guest concedes; match keeps running. Do not hang.
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
        }
        stopGameServerIfOurs();
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
        // FOptionPane supports keyboard, mouse and controller.
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
        final CoopFightLoadout loadout = buildLocalLoadout();
        final CoopDuelResponseEvent resp = inviteState.respond(true, deckText);
        if (resp == null) {
            return;
        }
        cancelInviteTimeout();
        CoopSession.get().send(resp);
        // Also send structured loadout for adventure setup on the host.
        final long duelId = duelSeq.get();
        CoopSession.get().send(new CoopFightLoadoutEvent(duelId, loadout, deckText));
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
        // Generous timeout; never block the host with sendAndWait.
        final int sec = Math.max(5, Math.min(timeoutSeconds, 120));
        inviteTimeoutFuture = timers.schedule(() -> {
            if (inviteState.timeout(inviteId)) {
                postGl(() -> {
                    if (inviteState.getStatus() == CoopDuelInviteState.Status.SOLO
                            || inviteState.getStatus() == CoopDuelInviteState.Status.WAITING_RESPONSE
                            || inviteState.getStatus() == CoopDuelInviteState.Status.PROMPT_OPEN) {
                        // If we were the invitee with an open prompt, auto-decline on the wire.
                        if (CoopSession.get().getRole() != CoopSessionRole.HOST
                                || inviteState.getStatus() == CoopDuelInviteState.Status.PROMPT_OPEN) {
                            final CoopDuelResponseEvent auto =
                                    new CoopDuelResponseEvent(inviteId, false, "");
                            CoopSession.get().send(auto);
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
        final EnemySprite mob = pendingEnemy != null ? pendingEnemy : WorldStage.getInstance().getCurrentMob();
        pendingEnemy = null;
        if (mob != null) {
            WorldStage.getInstance().beginEncounterDuel(mob);
        } else {
            Forge.advFreezePlayerControls = false;
        }
    }

    private void beginCoopDuelAsHost(final Deck guestDeck) {
        final EnemySprite mob = pendingEnemy != null ? pendingEnemy : WorldStage.getInstance().getCurrentMob();
        if (mob == null) {
            proceedSolo();
            return;
        }
        activeDuelId = duelSeq.getAndIncrement();
        pendingGuestDeck = guestDeck;
        if (pendingGuestLoadout == null) {
            pendingGuestLoadout = CoopFightLoadout.builder()
                    .playerName(CoopSession.get().getPeerName())
                    .avatarId("guest")
                    .startingLife(20)
                    .build();
        }
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
        // Build and start the match on the GL thread after a short grace for guest connect.
        final int grace = Math.max(2, Math.min(30, cfg.coopDuelGuestConnectGraceSeconds));
        timers.schedule(() -> postGl(() -> startHostedCoopMatch(mob)), grace, TimeUnit.SECONDS);
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
        final String peer = CoopSession.get().getPeerName();
        final String code = CoopSession.get().getSessionCode();
        server.setCoopSessionGate(peer, code);
        // Skip UPnP for co-op (same as overworld). Stock online play unchanged.
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
            }
        } catch (final Exception ignored) {
        }
    }

    private void startHostedCoopMatch(final EnemySprite mob) {
        try {
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
                    advPlayer.getName(),
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
                    // Host adventure effects (equipment, perks, badges, shards).
                    applyHostAdventureEffects(rp, advPlayer);
                    guiMap.put(rp, MatchController.instance);
                    hostRp = rp;
                } else if (seat.kind == CoopDuelMatchPlan.SeatKind.GUEST_HUMAN) {
                    rp.setPlayer(GamePlayerUtil.getGuiPlayer(seat.displayName, 0, 0, false));
                    applyGuestLoadoutEffects(rp, pendingGuestLoadout);
                    final IGuiGame remote = FServerManager.getInstance() != null
                            ? FServerManager.getInstance().getGui(seat.lobbySlot)
                            : null;
                    if (remote != null) {
                        guiMap.put(rp, remote);
                    } else {
                        // Guest not connected yet — still register; RemoteClientGuiGame attaches on login.
                        guiMap.put(rp, MatchController.instance);
                    }
                } else {
                    rp.setPlayer(GamePlayerUtil.createAiPlayer(seat.displayName, null));
                    // Co-op scaling already applied in the plan's starting life / hand bonus.
                }
                players.add(rp);
            }

            final HostedMatch hostedMatch = MatchController.hostMatch();
            final GameRules rules = new GameRules(GameType.Adventure);
            rules.setGamesPerMatch(mob.getData() != null ? mob.getData().gamesPerMatch : 1);
            rules.setManaBurn(false);
            rules.setWarnAboutAICards(false);
            final RegisteredPlayer hostRpFinal = hostRp;
            final EnemySprite mobFinal = mob;
            hostedMatch.setEndGameHook(() -> onHostMatchEnded(hostedMatch, hostRpFinal, mobFinal));
            hostedMatch.startMatch(rules, variants, players, guiMap,
                    mob.getData() != null && mob.getData().boss ? MusicPlaylist.BOSS : MusicPlaylist.MATCH);
            MatchController.instance.setGameView(hostedMatch.getGameView());
            Forge.advFreezePlayerControls = false;
            notifyHud("Co-op duel started");
        } catch (final Exception e) {
            notifyHud("Co-op match failed — fighting solo");
            stopGameServerIfOurs();
            disconnectPolicy.endDuel();
            proceedSolo();
        }
    }

    private void onHostMatchEnded(final HostedMatch match, final RegisteredPlayer hostRp,
                                  final EnemySprite mob) {
        boolean teamWon = false;
        try {
            if (match != null && match.getGame() != null && match.getGame().getMatch() != null) {
                final forge.game.player.RegisteredPlayer winner =
                        match.getGame().getMatch().getWinner();
                // Team win if winner is on team 0.
                if (winner != null && winner.getTeamNumber() == 0) {
                    teamWon = true;
                } else if (hostRp != null && winner == hostRp) {
                    teamWon = true;
                }
            }
        } catch (final Exception ignored) {
        }
        final boolean boss = mob != null && mob.getData() != null && mob.getData().boss;
        final int gold = teamWon ? 25 : 0;
        final int xp = teamWon ? 50 : 0;
        final int lifePenalty = teamWon ? 0 : 1;
        final CoopDuelResultEvent result = new CoopDuelResultEvent(
                activeDuelId, teamWon, gold, xp, lifePenalty, boss,
                mob != null && mob.getData() != null ? mob.getData().getName() : "");
        CoopSession.get().send(result);
        applyLocalRewards(result);
        disconnectPolicy.endDuel();
        stopGameServerIfOurs();
        pendingGuestDeck = null;
        pendingGuestLoadout = null;
        pendingEnemy = null;
        activeDuelId = 0L;
    }

    private void applyLocalRewards(final CoopDuelResultEvent event) {
        final AdventurePlayer ap = Current.player();
        if (ap == null || event == null) {
            return;
        }
        final boolean isGuest = CoopSession.get().getRole() == CoopSessionRole.GUEST;
        final CoopDuelRewards.CharacterSink sink = new CoopDuelRewards.CharacterSink() {
            @Override
            public void addGold(final int amount) {
                ap.giveGold(amount);
            }

            @Override
            public void addXp(final int amount) {
                ap.getSkills().addXp(PlayerSkills.Skill.DUELING, amount);
            }

            @Override
            public void applyLifePenalty(final int amount) {
                ap.setLife(Math.max(0, ap.getLife() - amount));
            }

            @Override
            public void recordWin(final boolean boss) {
                ap.win(boss);
            }

            @Override
            public void recordLoss() {
                ap.defeated();
            }

            @Override
            public int getGold() {
                return ap.getGold();
            }

            @Override
            public int getXp() {
                return ap.getSkills().getLevel(PlayerSkills.Skill.DUELING);
            }

            @Override
            public int getLife() {
                return ap.getLife();
            }
        };
        final CoopDuelRewards.ResultPayload payload = new CoopDuelRewards.ResultPayload(
                event.getDuelId(), event.isTeamWon(), event.getGold(), event.getXp(),
                event.getLifePenalty(), event.isBoss(), event.getEncounterId());
        // Guest: apply to live character / character file only — never break CO1 restore.
        CoopDuelRewards.applyToOwnCharacter(
                isGuest ? CoopDuelRewards.Side.GUEST : CoopDuelRewards.Side.HOST,
                sink, payload, !isGuest);
        if (isGuest) {
            try {
                CoopCharacterStore.savePlayer(ap);
            } catch (final Exception ignored) {
            }
        }
        disconnectPolicy.endDuel();
        disconnectGuestClient();
    }

    private void connectGuestGameClient(final CoopDuelStartEvent event) {
        disconnectGuestClient();
        try {
            final String host = resolveGameHost(event);
            final AdventurePlayer ap = Current.player();
            final String name = ap != null ? ap.getName() : "Guest";
            final IGuiGame gui = MatchController.instance;
            guestClient = new FGameClient(name, gui, host, event.getGamePort());
            // Session code is enforced by FServerManager.setCoopSessionGate on the host.
            guestClient.connect();
            // Send loadout for adventure setup.
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
        return CoopFightLoadout.builder()
                .playerName(ap.getName())
                .avatarId(ap.getName()) // name/id only — never textures
                .startingLife(ap.getLife())
                .manaShards(ap.getShards())
                .freeMulligans(freeMull)
                .lifeModifier(lifeMod)
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
        // Mirrors DuelScene.addEffects for the local host character.
        final com.badlogic.gdx.utils.Array<EffectData> effects = new com.badlogic.gdx.utils.Array<>();
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
        // Resolve card names against the host's card DB — never trust objects from the wire.
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
        if (loadout.getOpponentLifeModifier() != 0) {
            // Opponent effects applied to enemies separately when building AI seats if needed.
        }
        rp.setEnableETBCountersEffect(true);
    }

    private String currentDecklistText() {
        final AdventurePlayer ap = Current.player();
        if (ap == null || ap.getSelectedDeck() == null) {
            return "";
        }
        return DeckSerializer.toDecklistText(ap.getSelectedDeck());
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
