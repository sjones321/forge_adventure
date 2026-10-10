package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.PlaneConfigPaths;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.gamemodes.net.coop.CoopAddressUtil;
import forge.gamemodes.net.coop.CoopMessageListener;
import forge.gamemodes.net.coop.CoopOverworldClient;
import forge.gamemodes.net.coop.CoopOverworldServer;
import forge.gamemodes.net.coop.CoopPorts;
import forge.gamemodes.net.coop.CoopSessionCode;
import forge.gamemodes.net.coop.CoopVersion;
import forge.gamemodes.net.coop.CoopWorldHash;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDecklistEvent;
import forge.gamemodes.net.event.coop.CoopDisconnectEvent;
import forge.gamemodes.net.event.coop.CoopDuelInviteEvent;
import forge.gamemodes.net.event.coop.CoopDuelResponseEvent;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
import forge.gamemodes.net.event.coop.CoopDuelStartEvent;
import forge.gamemodes.net.event.coop.CoopEnemyEncounterRequestEvent;
import forge.gamemodes.net.event.coop.CoopEnemyStateEvent;
import forge.gamemodes.net.event.coop.CoopFightLoadoutEvent;
import forge.gamemodes.net.event.coop.CoopFightRequestResultEvent;
import forge.gamemodes.net.event.coop.CoopGatherRequestEvent;
import forge.gamemodes.net.event.coop.CoopGatherResultEvent;
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopHelloRejectEvent;
import forge.gamemodes.net.event.coop.CoopHostPresenceEvent;
import forge.gamemodes.net.event.coop.CoopLocationExitEvent;
import forge.gamemodes.net.event.coop.CoopLocationInviteEvent;
import forge.gamemodes.net.event.coop.CoopLocationResponseEvent;
import forge.gamemodes.net.event.coop.CoopNodeStateEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;
import forge.gamemodes.net.event.coop.CoopPoiChangeEvent;
import forge.gamemodes.net.event.coop.CoopPlanarGateEntry;
import forge.gamemodes.net.event.coop.CoopPlaneSwitchEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import forge.screens.TransitionScreen;
import forge.util.URLValidator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Ascendant co-op session (CO1). Host owns the world; guest brings their own
 * character and never overwrites their local WorldSave with host world data.
 * Hard session-code + version check on connect. Provides send/listen hooks for CO2/CO3.
 *
 * <p>Guest character model: the co-op {@code .chr} under {@code characters/} is the
 * source of truth across sessions. Join seeds it from the solo player only when
 * missing; later joins load the existing {@code .chr}. Leave persists the
 * {@code .chr} atomically and restores the stashed solo WorldSave without
 * touching the co-op character file.
 *
 * <p>{@link World#generateNew} / {@link World#load} for co-op run only on the GL
 * thread via {@link Gdx#app}{@Runnable} behind a {@link TransitionScreen},
 * matching New Game / Continue. They never run on the session worker or Netty.
 */
public final class CoopSession {
    private static final CoopSession INSTANCE = new CoopSession();

    public enum State {
        IDLE,
        HOSTING,
        JOINING,
        READY,
        REJECTED,
        DISCONNECTED
    }

    private volatile CoopSessionRole role = CoopSessionRole.NONE;
    private volatile State state = State.IDLE;
    private volatile String peerName = "";
    private volatile String lastError = "";
    private volatile String worldHash = "";
    /** Host: last live planar-gate list paired with {@link #worldHash}. */
    private volatile CoopPlanarGateEntry[] cachedGates = new CoopPlanarGateEntry[0];
    private volatile String sessionCode = "";
    private volatile int overworldPort = CoopPorts.OVERWORLD_PORT;
    private volatile int gamePort = CoopPorts.GAME_PORT;
    private volatile boolean skipUPnP = true;
    private volatile String joinSessionCode = "";
    private volatile String bindAddress = "";
    /** Host address the guest connected to (for CO3 game-port reconnect). */
    private volatile String joinHostAddress = "";

    /** Host world held separately for the guest — never written into WorldSave slots. */
    private volatile World sessionWorld;
    private volatile SaveFileData guestWorldBackup;
    private volatile SaveFileData guestPlayerBackup;
    private volatile String guestCharacterName;
    /** MV1: plane instance id the guest last accepted from the host. */
    private volatile String guestWorldPlaneId = PlaneMeta.HOME_ID;
    /** Package K: host plane format last synced to the guest (offer / plane switch). */
    private volatile String guestPlaneFormat = "";
    private volatile SaveFileData guestMultiverseBackup;
    /** Guards against double {@link #restoreGuestSave()} on REJECTED + disconnect. */
    private final AtomicBoolean guestRestoreDone = new AtomicBoolean(false);

    private volatile CoopOverworldServer server;
    private volatile CoopOverworldClient client;
    /** Kept so tests can dispatch hellos through the real {@link HostListener#onHello}. */
    private volatile HostListener activeHostListener;

    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<CoopHooks.OverworldListener> overworldListeners = new CopyOnWriteArrayList<>();
    private final List<CoopHooks.DuelListener> duelListeners = new CopyOnWriteArrayList<>();
    /** Non-world work only (e.g. hashing + sending offers). Never World.generateNew/load. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "coop-session-worker");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<String> consoleStatusListener = msg -> System.out.println("[co-op] " + msg);
    private volatile boolean consoleListenerAttached;

    private CoopSession() {
    }

    public static CoopSession get() {
        return INSTANCE;
    }

    /**
     * AC1: bump the account-wide {@code coopSessions} counter when a READY
     * co-op session finishes (disconnect). Matches “Together” / finished-session
     * wording. Local only — never sent on the wire.
     */
    static void noteCoopSessionFinished() {
        try {
            if (!forge.adventure.util.Config.ascendant()) {
                return;
            }
            forge.adventure.player.AchievementService.get().incrementCounter("coopSessions", 1);
        } catch (final Exception ignored) {
        }
    }

    public CoopSessionRole getRole() {
        return role;
    }

    public State getState() {
        return state;
    }

    public String getPeerName() {
        return peerName;
    }

    public String getLastError() {
        return lastError;
    }

    public String getWorldHash() {
        return worldHash;
    }

    public String getSessionCode() {
        if (role == CoopSessionRole.GUEST && (sessionCode == null || sessionCode.isEmpty())) {
            return joinSessionCode;
        }
        return sessionCode;
    }

    /** Address the guest used to reach the host (empty on host). */
    public String getJoinHostAddress() {
        return joinHostAddress;
    }

    public int getOverworldPort() {
        return overworldPort;
    }

    public int getGamePort() {
        return gamePort;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public boolean isActive() {
        return state == State.HOSTING || state == State.JOINING || state == State.READY;
    }

    /**
     * True while the local peer is a guest in an active/joining session — blocks
     * writing co-op world state into the guest's normal save slots.
     */
    public boolean blocksLocalWorldSave() {
        return role == CoopSessionRole.GUEST
                && (state == State.JOINING || state == State.READY || state == State.REJECTED);
    }

    /**
     * World the co-op session should use. Guest: dedicated session world when set.
     * Host / solo: the normal WorldSave world.
     */
    public World getActiveWorld() {
        final World sw = sessionWorld;
        if (role == CoopSessionRole.GUEST && sw != null) {
            return sw;
        }
        return WorldSave.getCurrentSave().getWorld();
    }

    /** MV1 plane instance id for the shared overworld (host's current plane). */
    public String getActiveWorldPlaneId() {
        if (role == CoopSessionRole.GUEST && guestWorldPlaneId != null && !guestWorldPlaneId.isEmpty()) {
            return guestWorldPlaneId;
        }
        try {
            return WorldSave.getCurrentSave().getCurrentPlaneId();
        } catch (final Exception e) {
            return PlaneMeta.HOME_ID;
        }
    }

    /**
     * Package K: host plane format synced to the guest. Empty when not a guest or
     * not yet offered. {@link forge.adventure.world.PlaneFormat#resolveCurrent()}
     * prefers this over local plane meta.
     */
    public String getGuestPlaneFormat() {
        return guestPlaneFormat != null ? guestPlaneFormat : "";
    }

    /**
     * Guests must follow the host's plane — they cannot start a portal hop or
     * {@code plane go} while a co-op session is active.
     */
    public boolean canInitiatePlaneSwitch() {
        return !isGuestBlockedFromPlaneSwitch(role, state);
    }

    /** Pure helper for tests and {@link #canInitiatePlaneSwitch()}. */
    public static boolean isGuestBlockedFromPlaneSwitch(final CoopSessionRole role, final State state) {
        return role == CoopSessionRole.GUEST
                && (state == State.JOINING || state == State.READY || state == State.HOSTING);
    }

    /**
     * Test hook: simulate a guest that has followed the host onto {@code planeId}.
     * Clears with {@link #testClearGuestPlaneFollow()}.
     */
    public void testFollowHostPlane(final String planeId) {
        role = CoopSessionRole.GUEST;
        state = State.READY;
        guestWorldPlaneId = planeId != null && !planeId.isEmpty() ? planeId : PlaneMeta.HOME_ID;
    }

    /** Test hook: guest plane follow + Package K format. */
    public void testFollowHostPlane(final String planeId, final String planeFormat) {
        testFollowHostPlane(planeId);
        guestPlaneFormat = planeFormat != null ? planeFormat : "";
    }

    /** Test hook: restore idle co-op role after {@link #testFollowHostPlane(String)}. */
    public void testClearGuestPlaneFollow() {
        role = CoopSessionRole.NONE;
        state = State.IDLE;
        guestWorldPlaneId = PlaneMeta.HOME_ID;
        guestPlaneFormat = "";
    }

    /** Test hook: Package K guest wire accept (length + known-token gate). */
    public static String testAcceptGuestPlaneFormat(final String raw) {
        return acceptGuestPlaneFormat(raw);
    }

    /** Test hook: host wire format (Commander mode / plane / default). */
    public static String testHostWirePlaneFormat() {
        return hostWirePlaneFormat();
    }

    /**
     * Test hook: apply a guest-synced format as offer/switch does, and invalidate
     * the reward card pool (same side effect as a real guest plane change).
     */
    public void testApplyGuestPlaneFormat(final String raw) {
        role = CoopSessionRole.GUEST;
        state = State.READY;
        guestPlaneFormat = acceptGuestPlaneFormat(raw);
        forge.adventure.data.RewardData.invalidateCardPool();
    }

    /**
     * Test hook: prepare a host-side listener so {@link #testHostOnHello} runs the
     * real {@code HostListener.onHello} path (no TCP bind required for protocol reject).
     */
    public void testPrepareHostingForHello(final String code) {
        role = CoopSessionRole.HOST;
        state = State.HOSTING;
        sessionCode = code != null && !code.isEmpty() ? code : CoopSessionCode.generate();
        lastError = "";
        activeHostListener = new HostListener();
    }

    /** Test hook: dispatch through the real host hello handler. */
    public void testHostOnHello(final CoopHelloEvent hello) {
        if (activeHostListener == null) {
            testPrepareHostingForHello(sessionCode);
        }
        activeHostListener.onMessage(hello);
    }

    public String testSessionCode() {
        return sessionCode;
    }

    /** Test hook: restore idle after {@link #testPrepareHostingForHello}. */
    public void testClearHostingForHello() {
        role = CoopSessionRole.NONE;
        state = State.IDLE;
        sessionCode = "";
        lastError = "";
        activeHostListener = null;
    }

    /**
     * Host: re-hash the live world and refresh the cached gate list.
     * Must run on the GL thread (or when the live world is not being mutated).
     */
    public void refreshHostLiveWorldHash() {
        if (role != CoopSessionRole.HOST) {
            return;
        }
        try {
            final WorldSave save = WorldSave.getCurrentSave();
            if (save == null || save.getWorld() == null) {
                return;
            }
            worldHash = CoopWorldSync.hashPlaneForOffer(save);
            cachedGates = CoopWorldSync.collectPlanarGates(save.getWorld());
        } catch (final Exception ignored) {
            // Solo / early init
        }
    }

    /**
     * Host MV1: after a local plane switch, tell the guest to follow onto the
     * host's current plane (seed + world config + live hash + gate list).
     * Hash/gates are taken on the GL thread; the wire send runs off Netty afterward.
     */
    public void offerCurrentPlaneToGuest() {
        if (role != CoopSessionRole.HOST || state != State.READY) {
            return;
        }
        final String loadingMsg = Forge.getLocalizer() != null
                ? Forge.getLocalizer().getMessage("lblLoadingWorld")
                : "Preparing plane…";
        runWorldOpOnGl(loadingMsg, () -> {
            final WorldSave save = WorldSave.getCurrentSave();
            final World w = save.getWorld();
            refreshHostLiveWorldHash();
            final String worldPath = w.getWorldConfigPath();
            if (!PlaneConfigPaths.isAllowed(worldPath, save.getMultiverse())) {
                status("Refusing plane offer — disallowed worldConfigPath " + worldPath);
                return;
            }
            // L1: planeConfigHash uses the same path that is sent on the wire.
            final String mv2SetCode = CoopWorldSync.hostMv2SetCode(save);
            final String planeFormat = hostWirePlaneFormat();
            final CoopPlaneSwitchEvent switchEvent = new CoopPlaneSwitchEvent(
                    Config.instance().getPlane(),
                    save.getCurrentPlaneId(),
                    worldPath,
                    CoopWorldSync.planeConfigHash(worldPath),
                    w.getSeed(),
                    worldHash,
                    save.getPlayer().getWorldPosX(),
                    save.getPlayer().getWorldPosY(),
                    mv2SetCode,
                    cachedGates,
                    planeFormat);
            runOffNetty(() -> {
                send(switchEvent);
                status("Offered plane switch → " + save.getCurrentPlaneId()
                        + " seed " + w.getSeed());
            });
        });
    }

    /**
     * Build the world offer from the live world. Caller must be on the GL thread
     * (hashes live terrain; never regenerates).
     */
    private CoopWorldOfferEvent buildWorldOffer() {
        final WorldSave save = WorldSave.getCurrentSave();
        final World w = save.getWorld();
        refreshHostLiveWorldHash();
        final String worldPath = w.getWorldConfigPath();
        final String safePath = PlaneConfigPaths.isAllowed(worldPath, save.getMultiverse())
                ? worldPath : Paths.WORLD;
        // L1: hash/config use the same path that is sent on the wire.
        final String mv2SetCode = CoopWorldSync.hostMv2SetCode(save);
        final String planeFormat = hostWirePlaneFormat();
        return new CoopWorldOfferEvent(
                save.getPlayer().getName(),
                Config.instance().getPlane(),
                CoopWorldSync.planeConfigHash(safePath),
                w.getSeed(),
                worldHash,
                gamePort,
                overworldPort,
                save.getCurrentPlaneId(),
                safePath,
                mv2SetCode,
                cachedGates,
                planeFormat);
    }

    /**
     * Package K: host-authoritative plane format for the wire — always a known
     * canonical token, length-capped via {@link forge.gamemodes.net.coop.CoopWireLimits}.
     */
    private static String hostWirePlaneFormat() {
        final String fmt = forge.adventure.world.PlaneFormat.resolveCurrent();
        final String accepted = forge.gamemodes.net.coop.CoopWireLimits.acceptPlaneFormat(fmt);
        if (accepted == null || accepted.isEmpty()
                || !forge.adventure.world.PlaneFormat.isKnown(accepted)) {
            return forge.adventure.world.PlaneFormat.defaultFormat();
        }
        return forge.adventure.world.PlaneFormat.normalize(accepted);
    }

    /**
     * Package K: guest accepts host {@code planeFormat} from offer / plane-switch.
     * Over-long or unknown tokens → host {@link forge.adventure.world.PlaneFormat#defaultFormat()}
     * (not the guest's local plane); known tokens → canonical form. Never throws.
     */
    private static String acceptGuestPlaneFormat(final String raw) {
        final String accepted = forge.gamemodes.net.coop.CoopWireLimits.acceptPlaneFormat(raw);
        if (accepted == null || accepted.isEmpty()
                || !forge.adventure.world.PlaneFormat.isKnown(accepted)) {
            return forge.adventure.world.PlaneFormat.defaultFormat();
        }
        return forge.adventure.world.PlaneFormat.normalize(accepted);
    }

    public void addStatusListener(final Consumer<String> listener) {
        if (listener != null && !statusListeners.contains(listener)) {
            statusListeners.add(listener);
        }
    }

    public void removeStatusListener(final Consumer<String> listener) {
        statusListeners.remove(listener);
    }

    /** Idempotent console logger used by the Join UI. */
    public void ensureConsoleStatusListener() {
        if (!consoleListenerAttached) {
            addStatusListener(consoleStatusListener);
            consoleListenerAttached = true;
        }
    }

    public void addOverworldListener(final CoopHooks.OverworldListener listener) {
        if (listener != null && !overworldListeners.contains(listener)) {
            overworldListeners.add(listener);
        }
    }

    public void removeOverworldListener(final CoopHooks.OverworldListener listener) {
        overworldListeners.remove(listener);
    }

    public void addDuelListener(final CoopHooks.DuelListener listener) {
        if (listener != null && !duelListeners.contains(listener)) {
            duelListeners.add(listener);
        }
    }

    public void removeDuelListener(final CoopHooks.DuelListener listener) {
        duelListeners.remove(listener);
    }

    private void status(final String msg) {
        for (final Consumer<String> l : statusListeners) {
            try {
                l.accept(msg);
            } catch (final Exception ignored) {
            }
        }
    }

    /**
     * Start hosting. Requires Ascendant and a loaded world. Generates a session
     * code the guest must enter. UPnP is skipped by default. Optional bind
     * address from config (empty = all interfaces).
     */
    private boolean exitHookInstalled;

    /**
     * Netty's network threads are not daemon threads, so an open co-op session would keep Java running after the
     * game window closes. Disconnect when the libGDX app shuts down.
     */
    private void ensureExitHook() {
        if (exitHookInstalled || Gdx.app == null)
            return;
        exitHookInstalled = true;
        Gdx.app.addLifecycleListener(new com.badlogic.gdx.LifecycleListener() {
            @Override
            public void pause() {
            }

            @Override
            public void resume() {
            }

            @Override
            public void dispose() {
                try {
                    disconnect();
                } catch (final Exception ignored) {
                }
            }
        });
    }

    public synchronized void host(final boolean skipUPnPFlag) throws Exception {
        ensureExitHook();
        ensureAscendant();
        ensureWorldLoaded();
        disconnectInternal("restarting host", false);
        this.skipUPnP = skipUPnPFlag;
        this.overworldPort = Config.instance().getConfigData().coopOverworldPort;
        this.gamePort = Config.instance().getConfigData().coopGamePort;
        final String configuredBind = Config.instance().getConfigData().coopBindAddress;
        this.bindAddress = configuredBind == null ? "" : configuredBind.trim();
        this.sessionCode = CoopSessionCode.generate();
        role = CoopSessionRole.HOST;
        state = State.HOSTING;
        CoopCharacterStore.exportCurrentPlayer();

        activeHostListener = new HostListener();
        server = new CoopOverworldServer(overworldPort,
                bindAddress.isEmpty() ? null : bindAddress,
                activeHostListener);
        try {
            server.start();
        } catch (final Exception e) {
            // A failed bind must not leave the session stuck in HOSTING with live event loops.
            activeHostListener = null;
            disconnectInternal("host failed", false);
            throw e;
        }
        status("Hosting co-op on overworld port " + overworldPort
                + (bindAddress.isEmpty() ? " (all interfaces)" : " bound to " + bindAddress)
                + "; session code " + sessionCode
                + (skipUPnP ? "; UPnP skipped" : ""));
    }

    /**
     * Join a host. Address may be {@code host}, {@code host:port}, Tailscale
     * {@code 100.x.y.z}, or LAN. {@code sessionCode} must match the host screen.
     */
    public synchronized void join(final String address, final String sessionCodeInput) throws Exception {
        ensureExitHook();
        ensureAscendant();
        ensureWorldLoaded();
        disconnectInternal("restarting join", false);

        final URLValidator.HostPort hp = URLValidator.parseURL(address);
        if (hp == null) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        final String host = hp.host();
        final int port = hp.port() != null && hp.port() > 0
                ? hp.port()
                : Config.instance().getConfigData().coopOverworldPort;
        this.overworldPort = port;
        this.gamePort = Config.instance().getConfigData().coopGamePort;
        this.skipUPnP = CoopAddressUtil.shouldSkipUPnPForAddress(host);
        this.joinSessionCode = CoopSessionCode.normalize(sessionCodeInput);
        if (this.joinSessionCode.length() != CoopPorts.SESSION_CODE_LENGTH) {
            throw new IllegalArgumentException("Session code must be "
                    + CoopPorts.SESSION_CODE_LENGTH + " characters");
        }

        role = CoopSessionRole.GUEST;
        state = State.JOINING;
        joinHostAddress = host;
        guestRestoreDone.set(false);
        applyGuestJoinSaveModel();
        sessionWorld = new World();

        client = new CoopOverworldClient(host, port, new GuestListener());
        try {
            client.connect();
        } catch (final Exception e) {
            // Restore the guest's own save so autosave and quick save work again.
            disconnectInternal("join failed", true);
            throw e;
        }
        status("Connecting to " + host + ':' + port
                + (CoopAddressUtil.isTailscaleAddress(host) ? " (Tailscale, UPnP N/A)" : ""));
    }

    /** @deprecated use {@link #join(String, String)} */
    public synchronized void join(final String address) throws Exception {
        join(address, "");
    }

    public void send(final NetEvent event) {
        if (role == CoopSessionRole.HOST) {
            final CoopOverworldServer s = server;
            if (s != null) {
                s.send(event);
            }
        } else if (role == CoopSessionRole.GUEST) {
            final CoopOverworldClient c = client;
            if (c != null) {
                c.send(event);
            }
        }
    }

    public synchronized void disconnect() {
        disconnectInternal("local disconnect", true);
    }

    /**
     * @param restoreGuest when true, restore the guest's stashed WorldSave and
     *                     stop networking; used for real disconnect. When false
     *                     (restarting host/join), skip restore of a mid-flight stash.
     */
    private void disconnectInternal(final String reason, final boolean restoreGuest) {
        final CoopSessionRole previousRole = role;
        final State previousState = state;
        if (previousState == State.READY) {
            noteCoopSessionFinished();
        }

        final CoopOverworldClient c = client;
        client = null;
        if (c != null) {
            try {
                c.send(new CoopDisconnectEvent(reason));
            } catch (final Exception ignored) {
            }
            c.disconnect();
        }
        final CoopOverworldServer s = server;
        server = null;
        if (s != null) {
            try {
                s.send(new CoopDisconnectEvent(reason));
            } catch (final Exception ignored) {
            }
            s.stop();
        }

        if (previousRole == CoopSessionRole.GUEST) {
            applyGuestLeaveSaveModel(restoreGuest);
        }

        // CO2: drop partner sprite / party on the GL thread without leaking listeners.
        // Must run while the role is still set: the host only clears id maps, the guest removes mirrors.
        try {
            CoopOverworldRuntime.get().onSessionEnded(reason);
        } catch (final Exception ignored) {
        }

        disposeSessionWorld();
        activeHostListener = null;
        try {
            CoopDuelRuntime.get().onSessionPeerDisconnected();
            CoopDuelRuntime.get().detach();
        } catch (final Exception ignored) {
        }
        role = CoopSessionRole.NONE;
        // Keep REJECTED visible until the next host/join clears it.
        if (previousState == State.REJECTED) {
            state = State.REJECTED;
        } else {
            state = State.DISCONNECTED;
        }
        peerName = "";
        joinHostAddress = "";
        if (previousRole == CoopSessionRole.HOST) {
            sessionCode = "";
            bindAddress = "";
        }
        status("Disconnected: " + reason);
    }

    /** Frees the session world's textures on the GL thread, after any queued guest-save restore has run. */
    private void disposeSessionWorld() {
        final World w = sessionWorld;
        sessionWorld = null;
        if (w != null && Gdx.app != null)
            Gdx.app.postRunnable(w::dispose);
    }

    /**
     * Production guest join save-model: stash the solo WorldSave, then seed the
     * co-op {@code .chr} from solo once or load the existing co-op character.
     * Called from {@link #join} before networking. Package-visible for tests.
     */
    void applyGuestJoinSaveModel() throws Exception {
        stashGuestSave();
        final AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
        guestCharacterName = player.getName();
        CoopCharacterStore.loadOrSeedForJoin(player);
    }

    /**
     * Production guest leave save-model: persist the co-op {@code .chr} (atomic),
     * then optionally restore the stashed solo WorldSave without touching the
     * {@code .chr}. Called from {@link #disconnectInternal}. Package-visible for tests.
     */
    void applyGuestLeaveSaveModel(final boolean restoreSolo) {
        try {
            CoopCharacterStore.savePlayer(WorldSave.getCurrentSave().getPlayer());
        } catch (final Exception e) {
            lastError = "Failed to save character: " + e.getMessage();
        }
        if (restoreSolo) {
            restoreGuestSave();
        }
    }

    private void stashGuestSave() {
        final World world = WorldSave.getCurrentSave().getWorld();
        if (world.getData() != null) {
            try {
                guestWorldBackup = world.save();
            } catch (final Exception e) {
                guestWorldBackup = null;
            }
        } else {
            guestWorldBackup = null;
        }
        guestPlayerBackup = WorldSave.getCurrentSave().getPlayer().save();
        try {
            guestMultiverseBackup = WorldSave.getCurrentSave().getMultiverse().saveRegistry();
        } catch (final Exception e) {
            guestMultiverseBackup = null;
        }
        guestRestoreDone.set(false);
    }

    /**
     * Restore the guest's stashed world/player on the GL thread behind a loading
     * screen (same pattern as Continue). Idempotent — a REJECTED path must not
     * restore twice when the channel later closes.
     */
    private void restoreGuestSave() {
        if (!guestRestoreDone.compareAndSet(false, true)) {
            return;
        }
        final SaveFileData worldBak = guestWorldBackup;
        final SaveFileData playerBak = guestPlayerBackup;
        final SaveFileData multiBak = guestMultiverseBackup;
        final String charName = guestCharacterName;
        guestWorldBackup = null;
        guestPlayerBackup = null;
        guestMultiverseBackup = null;
        // Drop session-world overlay so Current.world() returns the guest save again.
        guestWorldPlaneId = PlaneMeta.HOME_ID;
        guestPlaneFormat = "";

        if (worldBak == null && playerBak == null && multiBak == null && charName == null) {
            return;
        }

        runWorldOpOnGl(Forge.getLocalizer() != null
                        ? Forge.getLocalizer().getMessage("lblLoadingWorld")
                        : "Loading world…",
                () -> {
                    try {
                        if (worldBak != null) {
                            WorldSave.getCurrentSave().getWorld().load(worldBak);
                        }
                        if (playerBak != null) {
                            WorldSave.getCurrentSave().getPlayer().load(playerBak);
                        } else if (charName != null) {
                            CoopCharacterStore.loadPlayer(
                                    WorldSave.getCurrentSave().getPlayer(), charName);
                        }
                        if (multiBak != null) {
                            WorldSave.getCurrentSave().getMultiverse().loadRegistry(multiBak);
                        }
                        try {
                            WorldStage.getInstance().load(WorldSave.emptyWorldStageData());
                            forge.adventure.scene.GameScene.instance().enter();
                        } catch (final Exception ignored) {
                        }
                    } catch (final Exception e) {
                        lastError = "Failed to restore guest save: " + e.getMessage();
                        status(lastError);
                        // Match SaveFileData's delayedSwitchBack error path on the GL/EDT side.
                        try {
                            Forge.delayedSwitchBack("",
                                    "Co-op restore error\nPress Resume on the Main Menu to continue.\n"
                                            + e.getMessage());
                        } catch (final Exception ignored) {
                        }
                    }
                });
    }

    /**
     * Guest follow: {@link forge.adventure.util.Current#world()} already resolves to
     * {@link #sessionWorld}; rebuild the stage, exit any POI, and move to host spawn.
     */
    private void applyGuestSessionWorldRender(final float spawnX, final float spawnY) {
        try {
            if (MapStage.getInstance().isInMap()) {
                MapStage.getInstance().exitDungeon(false, false);
            }
        } catch (final Exception ignored) {
        }
        final AdventurePlayer ap = WorldSave.getCurrentSave().getPlayer();
        ap.setWorldPosX(spawnX);
        ap.setWorldPosY(spawnY);
        WorldStage.getInstance().load(WorldSave.emptyWorldStageData());
        WorldStage.getInstance().getPlayerSprite().setPosition(spawnX, spawnY);
        try {
            CoopOverworldRuntime.get().clearEntityIdMaps();
        } catch (final Exception ignored) {
        }
        forge.adventure.scene.GameScene.instance().enter();
    }

    /** Test helper: guest render overlay is the session world when set. */
    public boolean isGuestRenderingSessionWorld() {
        return role == CoopSessionRole.GUEST && sessionWorld != null
                && getActiveWorld() == sessionWorld;
    }

    private void ensureAscendant() {
        if (!Config.ascendant()) {
            throw new IllegalStateException("Co-op is only available in Shandalar Ascendant");
        }
    }

    private void ensureWorldLoaded() {
        if (WorldSave.getCurrentSave().getWorld().getData() == null) {
            throw new IllegalStateException("Load or continue a game before hosting or joining co-op");
        }
    }

    private void handleHookMessage(final NetEvent event) {
        // CO2/CO3 gameplay traffic only after the session is ready (authenticated).
        if (state != State.READY && state != State.HOSTING && state != State.JOINING) {
            return;
        }
        if (event instanceof CoopPlayerMoveEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onPlayerMove((CoopPlayerMoveEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopPartyInviteEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onPartyInvite((CoopPartyInviteEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopPartyResponseEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onPartyResponse((CoopPartyResponseEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopGatherRequestEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onGatherRequest((CoopGatherRequestEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopGatherResultEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onGatherResult((CoopGatherResultEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopNodeStateEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onNodeState((CoopNodeStateEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopEnemyStateEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onEnemyState((CoopEnemyStateEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopEnemyEncounterRequestEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onEnemyEncounterRequest((CoopEnemyEncounterRequestEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopPoiChangeEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onPoiChange((CoopPoiChangeEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopLocationInviteEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onLocationInvite((CoopLocationInviteEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopLocationResponseEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onLocationResponse((CoopLocationResponseEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopLocationExitEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onLocationExit((CoopLocationExitEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopHostPresenceEvent) {
            for (final CoopHooks.OverworldListener l : overworldListeners) {
                l.onHostPresence((CoopHostPresenceEvent) event);
                l.onOverworldMessage(event);
            }
        } else if (event instanceof CoopDuelInviteEvent) {
            for (final CoopHooks.DuelListener l : duelListeners) {
                l.onDuelInvite((CoopDuelInviteEvent) event);
                l.onDuelMessage(event);
            }
        } else if (event instanceof CoopDuelResponseEvent) {
            for (final CoopHooks.DuelListener l : duelListeners) {
                l.onDuelResponse((CoopDuelResponseEvent) event);
                l.onDuelMessage(event);
            }
        } else if (event instanceof CoopDecklistEvent) {
            for (final CoopHooks.DuelListener l : duelListeners) {
                l.onDecklist((CoopDecklistEvent) event);
                l.onDuelMessage(event);
            }
        } else if (event instanceof CoopFightRequestResultEvent) {
            for (final CoopHooks.DuelListener l : duelListeners) {
                l.onFightRequestResult((CoopFightRequestResultEvent) event);
                l.onDuelMessage(event);
            }
        } else if (event instanceof CoopFightLoadoutEvent) {
            for (final CoopHooks.DuelListener l : duelListeners) {
                l.onFightLoadout((CoopFightLoadoutEvent) event);
                l.onDuelMessage(event);
            }
        } else if (event instanceof CoopDuelStartEvent) {
            for (final CoopHooks.DuelListener l : duelListeners) {
                l.onDuelStart((CoopDuelStartEvent) event);
                l.onDuelMessage(event);
            }
        } else if (event instanceof CoopDuelResultEvent) {
            for (final CoopHooks.DuelListener l : duelListeners) {
                l.onDuelResult((CoopDuelResultEvent) event);
                l.onDuelMessage(event);
            }
        }
    }

    private void attachDuelRuntime() {
        try {
            CoopDuelRuntime.get().attach();
        } catch (final Exception ignored) {
        }
    }

    /** Non-world work off the Netty event loop (hashing, sending). */
    private void runOffNetty(final Runnable task) {
        worker.execute(() -> {
            try {
                task.run();
            } catch (final Exception e) {
                lastError = e.getMessage();
                status("Worker error: " + e.getMessage());
            }
        });
    }

    /**
     * Run World.generateNew / World.load (or restore) on the GL thread behind a
     * TransitionScreen, matching New Game / Continue. Never schedules on the
     * session worker or Netty threads.
     *
     * @param kickHostToMenuOnError when false (host building a guest world offer),
     *        errors refuse the guest and leave the host in-adventure; when true,
     *        guest-side failures may {@link Forge#delayedSwitchBack} to the menu.
     */
    private void runWorldOpOnGl(final String loadingMessage, final Runnable work) {
        runWorldOpOnGl(loadingMessage, work, true);
    }

    /**
     * Host-side: reject the current guest connection and remain {@link State#HOSTING}.
     * Used when {@link #buildWorldOffer()} fails so the host is not sent to the main menu.
     */
    private void refuseGuestKeepHosting(final String reason) {
        lastError = reason != null ? reason : "world offer failed";
        final CoopOverworldServer s = server;
        if (s != null) {
            try {
                s.rejectAndClose(lastError);
            } catch (final Exception ignored) {
            }
        } else {
            try {
                send(new CoopHelloRejectEvent(lastError));
            } catch (final Exception ignored) {
            }
        }
        peerName = "";
        if (state != State.IDLE && state != State.DISCONNECTED) {
            state = State.HOSTING;
        }
        status("Rejected guest (kept hosting): " + lastError
                + (sessionCode != null && !sessionCode.isEmpty() ? " code " + sessionCode : ""));
    }

    private void runWorldOpOnGl(final String loadingMessage, final Runnable work,
                                final boolean kickHostToMenuOnError) {
        final Runnable wrapped = () -> {
            try {
                work.run();
            } catch (final Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : e.toString();
                status("World op error: " + lastError);
                try {
                    Forge.clearTransitionScreen();
                } catch (final Exception ignored) {
                }
                if (!kickHostToMenuOnError && role == CoopSessionRole.HOST) {
                    // Guest join / offer failed — stay HOSTING; do not dump the host to the menu.
                    refuseGuestKeepHosting(lastError);
                    return;
                }
                try {
                    Forge.delayedSwitchBack("",
                            "Co-op world error\nPress Resume on the Main Menu to continue.\n" + lastError);
                } catch (final Exception ignored) {
                }
            }
        };
        final Runnable withScreen = () -> {
            try {
                Forge.setTransitionScreen(new TransitionScreen(() -> {
                    try {
                        wrapped.run();
                    } finally {
                        try {
                            Forge.clearTransitionScreen();
                        } catch (final Exception ignored) {
                        }
                    }
                }, null, false, true, loadingMessage));
            } catch (final Exception e) {
                // TransitionScreen unavailable — still stay on GL if we got here via postRunnable.
                wrapped.run();
            }
        };
        if (Gdx.app != null) {
            Gdx.app.postRunnable(withScreen);
        } else {
            // Headless / no Gdx: run inline (unit tests never hit generateNew/load here).
            wrapped.run();
        }
    }

    private final class HostListener implements CoopMessageListener {
        @Override
        public void onConnected() {
            status("Guest connected — waiting for hello + session code");
        }

        @Override
        public void onMessage(final NetEvent event) {
            final CoopOverworldServer s = server;
            if (s != null && !s.isGuestAuthenticated() && !(event instanceof CoopHelloEvent)) {
                return;
            }
            if (event instanceof CoopHelloEvent) {
                onHello((CoopHelloEvent) event);
            } else if (event instanceof CoopSessionReadyEvent) {
                state = State.READY;
                peerName = ((CoopSessionReadyEvent) event).getPeerName();
                attachDuelRuntime();
                status("Session ready with " + peerName);
                try {
                    CoopOverworldRuntime.get().onSessionReady();
                } catch (final Exception ignored) {
                }
            } else if (event instanceof CoopDisconnectEvent) {
                try {
                    CoopDuelRuntime.get().onSessionPeerDisconnected();
                } catch (final Exception ignored) {
                }
                peerName = "";
                status("Guest disconnected: " + ((CoopDisconnectEvent) event).getReason());
            } else if (s != null && s.isGuestAuthenticated()) {
                handleHookMessage(event);
            }
            // Unauthenticated co-op gameplay messages are ignored (CO1 + CO2).
        }

        private void onHello(final CoopHelloEvent hello) {
            if (!CoopSessionCode.matches(sessionCode, hello.getSessionCode())) {
                rejectGuest("WRONG SESSION CODE (not a version problem): the code doesn't match the host's. "
                        + "Copy it again from the host's Hosting screen; it changes every time the host starts "
                        + "hosting.", true);
                return;
            }
            if (hello.getProtocolVersion() != CoopPorts.PROTOCOL_VERSION) {
                rejectGuest("VERSION MISMATCH (not the code): co-op protocol " + CoopPorts.PROTOCOL_VERSION
                        + " on the host, " + hello.getProtocolVersion() + " on the guest. Both players: git pull, "
                        + "check the commit hashes match, and rebuild.", false);
                return;
            }
            final String mismatch = CoopVersion.mismatchReason(hello.getBuildHash(), hello.getCardDataHash());
            if (mismatch != null) {
                rejectGuest(mismatch, false);
                return;
            }
            final CoopOverworldServer s = server;
            if (s != null) {
                s.markGuestAuthenticated();
            }
            peerName = hello.getCharacterName() != null ? hello.getCharacterName() : hello.getPlayerName();
            // H1: live-world hash + gate collect must run on the GL thread (no regenerate).
            // On failure: refuse the guest and keep hosting — never delayedSwitchBack to menu.
            final String loadingMsg = Forge.getLocalizer() != null
                    ? Forge.getLocalizer().getMessage("lblLoadingWorld")
                    : "Preparing world…";
            runWorldOpOnGl(loadingMsg, () -> {
                final CoopWorldOfferEvent offer = buildWorldOffer();
                runOffNetty(() -> {
                    send(offer);
                    status("Authenticated — offered plane " + offer.getWorldPlaneId()
                            + " seed " + offer.getWorldSeed()
                            + " hash " + worldHash.substring(0, Math.min(8, worldHash.length())) + "…");
                });
            }, false);
        }

        /**
         * Refuse a guest without changing host state — stays {@link State#HOSTING}.
         * @param countFailure when true, counts toward the per-address session-code lockout
         */
        private void rejectGuest(final String reason, final boolean countFailure) {
            lastError = reason;
            // Intentionally do NOT set state = REJECTED; host remains HOSTING.
            final CoopOverworldServer s = server;
            if (s != null) {
                if (countFailure) {
                    final String ip = s.getGuestRemoteAddress();
                    if (ip != null) {
                        s.getAuthGuard().recordFailure(ip);
                    }
                }
                s.rejectAndClose(reason);
            } else {
                send(new CoopHelloRejectEvent(reason));
            }
            status("Rejected guest: " + reason + " (still hosting, code " + sessionCode + ")");
        }

        @Override
        public void onDisconnected(final String reason) {
            if (state == State.DISCONNECTED || state == State.IDLE) {
                return;
            }
            try {
                CoopDuelRuntime.get().onSessionPeerDisconnected();
            } catch (final Exception ignored) {
            }
            // Do not save host player or stop the server — wait for another guest.
            peerName = "";
            state = State.HOSTING;
            status("Guest left: " + reason + " (still hosting, code " + sessionCode + ")");
        }

        @Override
        public void onError(final String message, final Throwable cause) {
            lastError = message;
            status("Host error: " + message);
        }
    }

    private final class GuestListener implements CoopMessageListener {
        @Override
        public void onConnected() {
            final AdventurePlayer player = WorldSave.getCurrentSave().getPlayer();
            final CoopHelloEvent hello = new CoopHelloEvent(
                    CoopPorts.PROTOCOL_VERSION,
                    CoopVersion.buildHash(),
                    CoopVersion.cardDataHash(),
                    player.getName(),
                    player.getName(),
                    joinSessionCode);
            send(hello);
            status("Sent hello (session code + build/card hash check)");
        }

        @Override
        public void onMessage(final NetEvent event) {
            if (event instanceof CoopHelloRejectEvent) {
                lastError = ((CoopHelloRejectEvent) event).getReason();
                state = State.REJECTED;
                status("Rejected: " + lastError);
                // Close networking + restore once; onDisconnected must not restore again.
                endGuestSession(lastError, true);
            } else if (event instanceof CoopWorldOfferEvent) {
                onWorldOffer((CoopWorldOfferEvent) event);
            } else if (event instanceof CoopPlaneSwitchEvent) {
                onPlaneSwitch((CoopPlaneSwitchEvent) event);
            } else if (event instanceof CoopDisconnectEvent) {
                endGuestSession(((CoopDisconnectEvent) event).getReason(), true);
            } else {
                handleHookMessage(event);
            }
        }

        private void onWorldOffer(final CoopWorldOfferEvent offer) {
            peerName = offer.getHostPlayerName();
            gamePort = offer.getGamePort();
            final String worldPath = offer.getWorldConfigPath() != null && !offer.getWorldConfigPath().isEmpty()
                    ? offer.getWorldConfigPath() : Paths.WORLD;
            if (!PlaneConfigPaths.isAllowed(worldPath, WorldSave.getCurrentSave().getMultiverse())) {
                lastError = "Rejected worldConfigPath: " + worldPath;
                state = State.REJECTED;
                status(lastError);
                endGuestSession(lastError, true);
                return;
            }
            final String localPlaneHash = CoopWorldSync.planeConfigHash(worldPath);
            if (!localPlaneHash.equals(offer.getPlaneConfigHash())) {
                status("Plane config hash differs — will verify world hash");
            }
            final String loadingMsg = Forge.getLocalizer() != null
                    ? Forge.getLocalizer().getMessage("lblGeneratingWorld")
                    : "Generating world…";
            runWorldOpOnGl(loadingMsg, () -> {
                World staging = new World();
                try {
                    // Rebuild + replay host gates, then compare to host live hash.
                    final String offerPlaneId = offer.getWorldPlaneId() != null && !offer.getWorldPlaneId().isEmpty()
                            ? offer.getWorldPlaneId() : PlaneMeta.HOME_ID;
                    final String mv2SetCode = offer.getMv2SetCode();
                    final String offeredFormat = acceptGuestPlaneFormat(offer.getPlaneFormat());
                    final String localHash = CoopWorldSync.rebuildFromSeed(
                            staging, offer.getWorldSeed(), worldPath, mv2SetCode,
                            offer.getGates());
                    if (CoopWorldHash.matches(localHash, offer.getWorldHash())) {
                        final World previous = sessionWorld;
                        sessionWorld = staging;
                        worldHash = localHash;
                        guestWorldPlaneId = offerPlaneId;
                        guestPlaneFormat = offeredFormat;
                        forge.adventure.data.RewardData.invalidateCardPool();
                        if (previous != null && previous != staging) {
                            try {
                                previous.dispose();
                            } catch (final Exception ignored) {
                            }
                        }
                        finishReady(offer.getHostPlayerName());
                        status("World hash matched after seed rebuild (plane "
                                + guestWorldPlaneId + ")");
                    } else {
                        try {
                            staging.dispose();
                        } catch (final Exception ignored) {
                        }
                        lastError = CoopPorts.WORLD_HASH_MISMATCH_MESSAGE;
                        state = State.REJECTED;
                        status(lastError);
                        endGuestSession(lastError, true);
                    }
                } catch (final Exception e) {
                    try {
                        staging.dispose();
                    } catch (final Exception ignored) {
                    }
                    lastError = "World rebuild failed: " + e.getMessage();
                    state = State.REJECTED;
                    status(lastError);
                    endGuestSession(lastError, true);
                }
            });
        }

        private void onPlaneSwitch(final CoopPlaneSwitchEvent event) {
            if (state != State.READY) {
                return;
            }
            final String worldPath = event.getWorldConfigPath() != null && !event.getWorldConfigPath().isEmpty()
                    ? event.getWorldConfigPath() : Paths.WORLD;
            if (!PlaneConfigPaths.isAllowed(worldPath, WorldSave.getCurrentSave().getMultiverse())) {
                status("Rejected plane switch path: " + worldPath + " — staying on prior plane");
                return;
            }
            final String loadingMsg = Forge.getLocalizer() != null
                    ? Forge.getLocalizer().getMessage("lblGeneratingWorld")
                    : "Generating world…";
            runWorldOpOnGl(loadingMsg, () -> {
                World staging = new World();
                try {
                    final String switchPlaneId = event.getWorldPlaneId() != null && !event.getWorldPlaneId().isEmpty()
                            ? event.getWorldPlaneId() : PlaneMeta.HOME_ID;
                    final String mv2SetCode = event.getMv2SetCode();
                    final String switchFormat = acceptGuestPlaneFormat(event.getPlaneFormat());
                    final String localHash = CoopWorldSync.rebuildFromSeed(
                            staging, event.getWorldSeed(), worldPath, mv2SetCode,
                            event.getGates());
                    if (!CoopWorldHash.matches(localHash, event.getWorldHash())) {
                        try {
                            staging.dispose();
                        } catch (final Exception ignored) {
                        }
                        status("Plane switch hash mismatch — sessionWorld unchanged");
                        return;
                    }
                    final World previous = sessionWorld;
                    sessionWorld = staging;
                    worldHash = localHash;
                    guestWorldPlaneId = switchPlaneId;
                    guestPlaneFormat = switchFormat;
                    forge.adventure.data.RewardData.invalidateCardPool();
                    if (previous != null && previous != staging) {
                        try {
                            previous.dispose();
                        } catch (final Exception ignored) {
                        }
                    }
                    // Swap rendered world (Current.world → sessionWorld) + stage; exit POI; spawn.
                    try {
                        applyGuestSessionWorldRender(event.getSpawnX(), event.getSpawnY());
                    } catch (final Exception stageEx) {
                        status("Plane applied; stage rebuild partial: " + stageEx.getMessage());
                    }
                    status("Followed host to plane " + guestWorldPlaneId);
                } catch (final Exception e) {
                    try {
                        staging.dispose();
                    } catch (final Exception ignored) {
                    }
                    status("Plane switch failed: " + e.getMessage());
                }
            });
        }

        private void finishReady(final String hostName) {
            state = State.READY;
            attachDuelRuntime();
            send(new CoopSessionReadyEvent(false, WorldSave.getCurrentSave().getPlayer().getName(), worldHash));
            status("Session ready with host " + hostName);
            try {
                CoopOverworldRuntime.get().onSessionReady();
            } catch (final Exception ignored) {
            }
        }

        private void endGuestSession(final String reason, final boolean restore) {
            synchronized (CoopSession.this) {
                if (role != CoopSessionRole.GUEST && state != State.REJECTED) {
                    return;
                }
                disconnectInternal(reason, restore);
            }
        }

        @Override
        public void onDisconnected(final String reason) {
            if (state == State.DISCONNECTED || state == State.IDLE) {
                return;
            }
            if (state == State.REJECTED) {
                // Already rejected and restored via endGuestSession — tear down only.
                final CoopOverworldClient c = client;
                client = null;
                if (c != null) {
                    c.disconnect();
                }
                disposeSessionWorld();
                role = CoopSessionRole.NONE;
                return;
            }
            endGuestSession(reason, true);
        }

        @Override
        public void onError(final String message, final Throwable cause) {
            lastError = message;
            status("Join error: " + message);
        }
    }
}
