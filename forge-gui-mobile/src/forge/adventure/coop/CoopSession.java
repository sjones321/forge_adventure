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
import forge.gamemodes.net.event.coop.CoopPartnerCreateEvent;
import forge.gamemodes.net.event.coop.CoopPartnerOfferEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotAckEvent;
import forge.gamemodes.net.event.coop.CoopPartnerSnapshotEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;
import forge.gamemodes.net.event.coop.CoopPoiChangeEvent;
import forge.gamemodes.net.event.coop.CoopPlanarGateEntry;
import forge.gamemodes.net.event.coop.CoopPlaneSwitchEvent;
import forge.gamemodes.net.event.coop.CoopSessionReadyEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import forge.adventure.scene.StartScene;
import forge.gui.FThreads;
import forge.player.GamePlayerUtil;
import forge.screens.TransitionScreen;
import forge.toolbox.FOptionPane;
import forge.util.URLValidator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Ascendant co-op session (CO1 + CO5). Host owns the world and the guest's
 * world-bound partner character. Hard session-code + version check on connect.
 *
 * <p>CO5 partner model: partners live in the host {@link WorldSave} {@code partners}
 * map keyed by the guest's install {@link CoopProfileId}. The guest's solo save is
 * never read, written, stashed or restored by a session. On leave the guest returns
 * to the main menu; a crash loses at most the last snapshot window.
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
    /** CO5: guest install profile id for this session (host and guest). */
    private volatile String guestProfileId = "";
    /** CO5: true after the guest has loaded a partner blob into the local player. */
    private volatile boolean partnerLoaded;
    /**
     * CO5: stays true from guest leave start until unload finishes so autosave cannot
     * write partner data into {@code auto_save.sav} during the leave window.
     */
    private volatile boolean guestLeaveGuard;
    /** CO5: legacy .chr chosen during create prompt; marked imported only after host accepts. */
    private volatile File pendingLegacyImport;
    /** CO5: true while a final-ack worker is finishing disconnect (avoid re-entry). */
    private final AtomicBoolean guestLeaveInFlight = new AtomicBoolean(false);
    /** CO5: snapshot seq/ack, trailing debounce, host world save. */
    private final CoopPartnerSync partnerSync = new CoopPartnerSync(this);
    /** MV1: plane instance id the guest last accepted from the host. */
    private volatile String guestWorldPlaneId = PlaneMeta.HOME_ID;

    private volatile CoopOverworldServer server;
    private volatile CoopOverworldClient client;

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
     * True while this peer is a guest in an active/joining session, or until guest
     * leave unload has finished. Guests must not write host-world or partner state
     * into local WorldSave slots. REJECTED without a loaded partner keeps the solo
     * game playable (unless {@link #guestLeaveGuard} is set for a failed load return).
     */
    public boolean isGuestSession() {
        if (guestLeaveGuard) {
            return true;
        }
        return role == CoopSessionRole.GUEST
                && (state == State.JOINING || state == State.READY
                || (state == State.REJECTED && partnerLoaded));
    }

    /** Host: unsaved partner progress waiting for a world save. */
    public boolean isHostPartnerDirty() {
        return partnerSync.isHostPartnerDirty();
    }

    /** Host: flush partners to the loaded world slot (no header retitle). */
    public boolean saveHostWorldNow() {
        return partnerSync.saveHostWorldNow();
    }

    public String getGuestProfileId() {
        return guestProfileId != null ? guestProfileId : "";
    }

    public boolean isPartnerLoaded() {
        return partnerLoaded;
    }

    CoopPartnerSync partnerSync() {
        return partnerSync;
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

    /** Test hook: restore idle co-op role after {@link #testFollowHostPlane(String)}. */
    public void testClearGuestPlaneFollow() {
        role = CoopSessionRole.NONE;
        state = State.IDLE;
        guestWorldPlaneId = PlaneMeta.HOME_ID;
        guestProfileId = "";
        partnerLoaded = false;
        guestLeaveGuard = false;
        pendingLegacyImport = null;
        guestLeaveInFlight.set(false);
        partnerSync.resetGuest();
        partnerSync.resetHost();
    }

    /** Test hook: host session ready to accept a partner create for {@code profileId}. */
    public void testBeginHostForPartner(final String profileId) {
        role = CoopSessionRole.HOST;
        state = State.HOSTING;
        guestProfileId = CoopProfileId.sanitize(profileId);
        partnerLoaded = false;
        partnerSync.resetHost();
    }

    /** Test hook: guest session with profile id set (partner not yet loaded). */
    public void testBeginGuestForPartner(final String profileId) {
        role = CoopSessionRole.GUEST;
        state = State.READY;
        guestProfileId = CoopProfileId.sanitize(profileId);
        partnerLoaded = false;
        partnerSync.resetGuest();
    }

    /** Test hook: mark partner loaded (after applyGuestPartnerBlob in tests). */
    public void testSetPartnerLoaded(final boolean loaded) {
        partnerLoaded = loaded;
    }

    /**
     * Test hook: arm/clear the leave-window guard that keeps {@link #isGuestSession()}
     * true until unload finishes (autosave must stay blocked).
     */
    public void testSetGuestLeaveGuard(final boolean on) {
        guestLeaveGuard = on;
    }

    /** Test hook: run the real leave path (final ack + unload + menu flags). */
    public void testGuestLeaveToMenu() {
        disconnectInternal("test leave", true);
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
                    cachedGates);
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
                cachedGates);
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

    /** Package-visible so helpers like {@link CoopPartnerSync} can report status. */
    void status(final String msg) {
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
        guestProfileId = "";
        partnerLoaded = false;
        partnerSync.resetHost();

        server = new CoopOverworldServer(overworldPort,
                bindAddress.isEmpty() ? null : bindAddress,
                new HostListener());
        try {
            server.start();
        } catch (final Exception e) {
            // A failed bind must not leave the session stuck in HOSTING with live event loops.
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
        guestProfileId = CoopProfileId.getOrCreate();
        partnerLoaded = false;
        partnerSync.resetGuest();
        try {
            partnerSync.rememberGuiPlayerName(WorldSave.getCurrentSave().getPlayer().getName());
        } catch (final Exception ignored) {
        }
        // CO5: solo save is never stashed/restored; partner arrives from the host.
        sessionWorld = new World();

        client = new CoopOverworldClient(host, port, new GuestListener());
        try {
            client.connect();
        } catch (final Exception e) {
            disconnectInternal("join failed", true);
            throw e;
        }
        status("Connecting to " + host + ':' + port
                + (CoopAddressUtil.isTailscaleAddress(host) ? " (Tailscale, UPnP N/A)" : ""));
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
     * @param returnToMenu when true, guest sends a final snapshot (awaits ack off
     *                     GL/Netty), unloads, shows any timeout warning, then returns
     *                     to the main menu. Wrong-code rejects before partner load
     *                     pass {@code false} so the solo game stays intact.
     */
    private void disconnectInternal(final String reason, final boolean returnToMenu) {
        final CoopSessionRole previousRole = role;
        final State previousState = state;
        final boolean hadPartner = partnerLoaded;

        // Guest leave with progress: final ack on a worker — never block GL/Netty for 8s.
        if (previousRole == CoopSessionRole.GUEST && hadPartner && returnToMenu) {
            guestLeaveGuard = true;
            if (guestLeaveInFlight.compareAndSet(false, true)) {
                final Runnable afterAck = () -> {
                    try {
                        finishDisconnectAfterFinalAck(reason, previousRole, previousState, true);
                    } finally {
                        guestLeaveInFlight.set(false);
                    }
                };
                if (shouldOffloadFinalAckWait()) {
                    final Thread worker = new Thread(() -> {
                        try {
                            partnerSync.sendFinalSnapshotAndAwaitAck(8_000L);
                        } catch (final Exception ignored) {
                        }
                        CoopPartnerSync.runOnGl(afterAck);
                    }, "coop-final-partner-ack");
                    worker.setDaemon(true);
                    worker.start();
                    return;
                }
                try {
                    partnerSync.sendFinalSnapshotAndAwaitAck(8_000L);
                } catch (final Exception ignored) {
                }
                afterAck.run();
                return;
            }
        }

        if (previousRole == CoopSessionRole.GUEST && returnToMenu) {
            guestLeaveGuard = true;
        }
        finishDisconnectAfterFinalAck(reason, previousRole, previousState, returnToMenu);
    }

    /** True on GL or Netty — final-ack await must run on a worker instead. */
    private static boolean shouldOffloadFinalAckWait() {
        if (Gdx.app == null) {
            return false;
        }
        try {
            if (FThreads.isGuiThread()) {
                return true;
            }
        } catch (final Exception ignored) {
        }
        final String name = Thread.currentThread().getName();
        return name != null && (name.contains("nioEventLoop") || name.contains("coop-ow")
                || name.contains("globalEventExecutor"));
    }

    private void finishDisconnectAfterFinalAck(final String reason,
                                               final CoopSessionRole previousRole,
                                               final State previousState,
                                               final boolean returnToMenu) {
        final boolean hadPartner = partnerLoaded;
        if (previousState == State.READY) {
            noteCoopSessionFinished();
        }

        // Host: flush partner dirty to disk before tearing down.
        if (previousRole == CoopSessionRole.HOST && partnerSync.isHostPartnerDirty()) {
            try {
                partnerSync.saveHostWorldNow();
            } catch (final Exception ignored) {
            }
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

        // CO2: drop partner sprite / party on the GL thread without leaking listeners.
        try {
            CoopOverworldRuntime.get().onSessionEnded(reason);
        } catch (final Exception ignored) {
        }

        disposeSessionWorld();
        try {
            CoopDuelRuntime.get().onSessionPeerDisconnected();
            CoopDuelRuntime.get().detach();
        } catch (final Exception ignored) {
        }

        // Unload partner BEFORE clearing guest session flags so autosave cannot write
        // partner data into auto_save.sav during the leave window.
        if (previousRole == CoopSessionRole.GUEST && (hadPartner || returnToMenu)) {
            unloadGuestPartnerState();
        }

        role = CoopSessionRole.NONE;
        if (previousState == State.REJECTED) {
            state = State.REJECTED;
        } else {
            state = State.DISCONNECTED;
        }
        peerName = "";
        joinHostAddress = "";
        guestProfileId = "";
        partnerLoaded = false;
        if (previousRole == CoopSessionRole.HOST) {
            sessionCode = "";
            bindAddress = "";
            if (partnerSync.isHostPartnerDirty()) {
                status("Warning: partner progress may be unsaved — save your world");
            }
            partnerSync.resetHost();
        }
        status("Disconnected: " + reason);

        if (previousRole == CoopSessionRole.GUEST && returnToMenu) {
            returnGuestToMainMenu();
        } else {
            guestLeaveGuard = false;
        }
    }

    /** Frees the session world's textures on the GL thread, after any queued guest-save restore has run. */
    private void disposeSessionWorld() {
        final World w = sessionWorld;
        sessionWorld = null;
        if (w != null && Gdx.app != null)
            Gdx.app.postRunnable(w::dispose);
    }

    /**
     * CO5: return to the main menu after unload. Shows final-ack timeout warning
     * before switching scenes. Restores the pre-join GUI name and refreshes StartScene.
     */
    private void returnGuestToMainMenu() {
        guestWorldPlaneId = PlaneMeta.HOME_ID;
        final String restoreName = partnerSync.getLastGuiPlayerName();
        final String warning = partnerSync.consumeFinalAckWarning();
        final Runnable switchMenu = () -> {
            try {
                try {
                    if (restoreName != null && !restoreName.isEmpty()) {
                        GamePlayerUtil.getGuiPlayer().setName(restoreName);
                    }
                } catch (final Exception ignored) {
                }
                Forge.switchScene(StartScene.instance());
                try {
                    StartScene.instance().enter();
                } catch (final Exception ignored) {
                }
            } catch (final Exception e) {
                status("Could not return to menu: " + e.getMessage());
            } finally {
                guestLeaveGuard = false;
            }
        };
        final Runnable go = () -> {
            if (warning != null && !warning.isEmpty() && Gdx.app != null) {
                try {
                    FOptionPane.showMessageDialog(warning, "Co-op", null, result -> switchMenu.run());
                    return;
                } catch (final Exception ignored) {
                }
            }
            switchMenu.run();
        };
        CoopPartnerSync.runOnGl(go);
    }

    private void unloadGuestPartnerState() {
        try {
            WorldSave.getCurrentSave().unloadAfterGuestSession();
        } catch (final Exception ignored) {
        }
        partnerLoaded = false;
        partnerSync.resetGuest();
    }

    /** Guest → host: immediate snapshot (duel / craft / leave path). */
    public void sendPartnerSnapshotNow() {
        partnerSync.sendSnapshot(false);
    }

    /** Guest → host: trailing debounced snapshot (gather/craft batches). */
    public void sendPartnerSnapshotDebounced() {
        partnerSync.requestDebouncedSnapshot();
    }

    /**
     * Host: apply create on the GL thread. Package-visible for tests.
     */
    boolean applyHostPartnerCreate(final CoopPartnerCreateEvent event) {
        final boolean[] ok = {false};
        final CountDownLatch done = new CountDownLatch(1);
        CoopPartnerSync.runOnGl(() -> {
            try {
                ok[0] = applyHostPartnerCreateOnGl(event);
            } finally {
                done.countDown();
            }
        });
        try {
            done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok[0];
    }

    private boolean applyHostPartnerCreateOnGl(final CoopPartnerCreateEvent event) {
        if (event == null) {
            return false;
        }
        final String id = CoopProfileId.sanitize(event.getProfileId());
        if (id.isEmpty() || !id.equals(guestProfileId)) {
            status("Rejected partner create: profile mismatch");
            return false;
        }
        if (WorldSave.getCurrentSave().getPartners().has(id)) {
            status("Partner already exists — sending stored copy");
            sendPartnerOffer(id, false);
            return true;
        }
        try {
            final AdventurePlayer partner;
            final byte[] legacy = event.getLegacyChrBlob();
            if (legacy != null && legacy.length > 0) {
                if (!CoopPartnerValidator.blobSizeOk(legacy)) {
                    status("Rejected legacy import: size");
                    return false;
                }
                final SaveFileData legacyData = CoopPartnerCodec.decodeSafe(legacy);
                if (legacyData == null) {
                    status("Rejected legacy import: decode");
                    return false;
                }
                // Keep imported name and look (roadmap).
                partner = CoopPartnerStarter.fromLegacy(legacyData);
            } else {
                final String name = CoopPartnerValidator.capName(event.getCharacterName());
                if (name.isEmpty()) {
                    status("Rejected partner create: name");
                    return false;
                }
                final boolean allowCopy = Config.instance().getConfigData().coopPartnerAllowCopySoloDeck;
                final String deckText = allowCopy ? event.getSoloDecklistText() : "";
                partner = CoopPartnerStarter.createNew(name, event.isMale(),
                        event.getRace(), event.getAvatarIndex(), deckText);
            }
            WorldSave.getCurrentSave().getPartners().putPlayer(id, partner);
            partnerSync.markHostPartnerDirty();
            sendPartnerOffer(id, false);
            status("Created partner \"" + partner.getName() + "\"");
            return true;
        } catch (final Exception e) {
            status("Partner create failed: " + e.getMessage());
            return false;
        }
    }

    private void sendPartnerOffer(final String profileId, final boolean needCreate) {
        try {
            boolean create = needCreate;
            byte[] blob = new byte[0];
            if (!create) {
                final SaveFileData data = WorldSave.getCurrentSave().getPartners().get(profileId);
                if (data != null) {
                    blob = CoopPartnerCodec.encode(data);
                } else {
                    create = true;
                }
            }
            final boolean allowCopy = Config.instance().getConfigData().coopPartnerAllowCopySoloDeck;
            final List<String> sets = CoopPartnerStarter.hostStandardSets(WorldSave.getCurrentSave().getPlayer());
            send(new CoopPartnerOfferEvent(profileId, create, allowCopy, blob,
                    sets.toArray(new String[0])));
        } catch (final Exception e) {
            status("Partner offer failed: " + e.getMessage());
        }
    }

    /**
     * Guest: load a partner blob into the in-memory player (never written to solo slots).
     * On failure, fully unload so Save/Resume cannot persist partner data.
     */
    void applyGuestPartnerBlob(final byte[] blob, final String[] hostStandardSets) throws Exception {
        if (!CoopPartnerValidator.blobSizeOk(blob)) {
            unloadGuestPartnerState();
            throw new IOException("Partner blob size rejected");
        }
        final SaveFileData data = CoopPartnerCodec.decodeSafe(blob);
        final String problem = CoopPartnerValidator.validateDecoded(data);
        if (problem != null) {
            unloadGuestPartnerState();
            throw new IOException(problem);
        }
        try {
            SaveFileData.beginWireFilteredReads();
            try {
                WorldSave.getCurrentSave().getPlayer().load(data);
            } finally {
                SaveFileData.endWireFilteredReads();
            }
        } catch (final Throwable t) {
            unloadGuestPartnerState();
            throw new IOException("Partner load failed: " + t.getMessage(), t);
        }
        CoopPartnerStarter.applyHostStandardWindow(WorldSave.getCurrentSave().getPlayer(), hostStandardSets);
        partnerLoaded = true;
        GamePlayerUtil.getGuiPlayer().setName(WorldSave.getCurrentSave().getPlayer().getName());
        // Host accepted the create/import — only now mark the legacy .chr as imported.
        final File imported = pendingLegacyImport;
        pendingLegacyImport = null;
        if (imported != null) {
            try {
                CoopLegacyChrImport.markImported(imported);
            } catch (final Exception ignored) {
            }
        }
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
            throw new IllegalStateException("Co-op is Ascendant-only");
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
                guestProfileId = "";
                status("Guest disconnected: " + ((CoopDisconnectEvent) event).getReason());
            } else if (event instanceof CoopPartnerCreateEvent) {
                if (s != null && s.isGuestAuthenticated()) {
                    final CoopPartnerCreateEvent create = (CoopPartnerCreateEvent) event;
                    // Never block Netty waiting on GL.
                    CoopPartnerSync.runOnGl(() -> applyHostPartnerCreateOnGl(create));
                }
            } else if (event instanceof CoopPartnerSnapshotEvent) {
                if (s != null && s.isGuestAuthenticated()) {
                    final CoopPartnerSnapshotEvent snap = (CoopPartnerSnapshotEvent) event;
                    CoopPartnerSync.runOnGl(() -> partnerSync.applySnapshotOnGl(snap));
                }
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
            final String profileId = CoopProfileId.sanitize(hello.getProfileId());
            if (profileId.isEmpty()) {
                rejectGuest("CO5: guest profile id missing or invalid", false);
                return;
            }
            guestProfileId = profileId;
            partnerSync.resetHost();
            // H1: live-world hash + gate collect must run on the GL thread (no regenerate).
            // On failure: refuse the guest and keep hosting — never delayedSwitchBack to menu.
            final String loadingMsg = Forge.getLocalizer() != null
                    ? Forge.getLocalizer().getMessage("lblLoadingWorld")
                    : "Preparing world…";
            runWorldOpOnGl(loadingMsg, () -> {
                final CoopWorldOfferEvent offer = buildWorldOffer();
                final boolean hasPartner = WorldSave.getCurrentSave().getPartners().has(profileId);
                runOffNetty(() -> {
                    send(offer);
                    sendPartnerOffer(profileId, !hasPartner);
                    status("Authenticated — offered plane " + offer.getWorldPlaneId()
                            + " seed " + offer.getWorldSeed()
                            + " hash " + worldHash.substring(0, Math.min(8, worldHash.length()))
                            + (hasPartner ? " + partner" : " + partner create") + "…");
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
            final String display = player != null && player.getName() != null ? player.getName() : "Guest";
            final CoopHelloEvent hello = new CoopHelloEvent(
                    CoopPorts.PROTOCOL_VERSION,
                    CoopVersion.buildHash(),
                    CoopVersion.cardDataHash(),
                    display,
                    display,
                    joinSessionCode,
                    guestProfileId);
            send(hello);
            status("Sent hello (session code + build/card hash + profile id)");
        }

        @Override
        public void onMessage(final NetEvent event) {
            if (event instanceof CoopHelloRejectEvent) {
                lastError = ((CoopHelloRejectEvent) event).getReason();
                state = State.REJECTED;
                status("Rejected: " + lastError);
                // Wrong code / version: do not kick the guest out of their solo game.
                endGuestSession(lastError, partnerLoaded);
            } else if (event instanceof CoopWorldOfferEvent) {
                onWorldOffer((CoopWorldOfferEvent) event);
            } else if (event instanceof CoopPartnerOfferEvent) {
                onPartnerOffer((CoopPartnerOfferEvent) event);
            } else if (event instanceof CoopPartnerSnapshotAckEvent) {
                partnerSync.onSnapshotAck((CoopPartnerSnapshotAckEvent) event);
            } else if (event instanceof CoopPlaneSwitchEvent) {
                onPlaneSwitch((CoopPlaneSwitchEvent) event);
            } else if (event instanceof CoopDisconnectEvent) {
                endGuestSession(((CoopDisconnectEvent) event).getReason(), true);
            } else {
                handleHookMessage(event);
            }
        }

        private void onPartnerOffer(final CoopPartnerOfferEvent offer) {
            if (offer.isNeedCreate()) {
                status("Host needs a new partner character for this world");
                if (Gdx.app == null) {
                    send(new CoopPartnerCreateEvent(guestProfileId, "Partner",
                            true, 0, 0, new byte[0], ""));
                } else {
                    Gdx.app.postRunnable(() -> promptPartnerCreateAsync(offer.isAllowCopySoloDeck()));
                }
                return;
            }
            final byte[] blob = offer.getPartnerBlob();
            final String[] hostSets = offer.getHostStandardSets();
            runWorldOpOnGl(Forge.getLocalizer() != null
                            ? Forge.getLocalizer().getMessage("lblLoadingWorld")
                            : "Loading partner…",
                    () -> {
                        try {
                            applyGuestPartnerBlob(blob, hostSets);
                            status("Loaded partner \""
                                    + WorldSave.getCurrentSave().getPlayer().getName() + "\"");
                            if (state == State.JOINING && sessionWorld != null
                                    && sessionWorld.getData() != null) {
                                finishReady(peerName);
                            }
                        } catch (final Throwable e) {
                            lastError = "Partner load failed: " + e.getMessage();
                            state = State.REJECTED;
                            status(lastError);
                            pendingLegacyImport = null;
                            // Return to main menu with StartScene refreshed + GUI name restored.
                            endGuestSession(lastError, true);
                        }
                    });
        }

        /**
         * Async FOptionPane chain on the GL thread (never blocking WaitCallback /
         * assertExecutedByEdt). Marks legacy import only after host accepts.
         */
        private void promptPartnerCreateAsync(final boolean allowCopySoloDeck) {
            try {
                final String soloName = WorldSave.getCurrentSave().getPlayer().getName();
                final List<File> legacy = CoopLegacyChrImport.listImportableChrFiles();
                final List<File> preferred = new ArrayList<>();
                final List<File> hostExports = new ArrayList<>();
                for (final File f : legacy) {
                    if (CoopLegacyChrImport.isLikelySoloHostExport(f, soloName)) {
                        hostExports.add(f);
                    } else {
                        preferred.add(f);
                    }
                }
                if (!preferred.isEmpty() || !hostExports.isEmpty()) {
                    final List<String> labels = new ArrayList<>();
                    final List<File> options = new ArrayList<>();
                    labels.add("Start fresh");
                    options.add(null);
                    for (final File f : preferred) {
                        labels.add(f.getName());
                        options.add(f);
                    }
                    final boolean hasHostExports = !hostExports.isEmpty();
                    if (hasHostExports) {
                        labels.add("Show host-export matches…");
                        options.add(null);
                    }
                    FOptionPane.showOptionDialog(
                            "Bring an existing co-op character into this world?",
                            "Co-op partner", null, labels,
                            idx -> {
                                int choice = idx == null || idx < 0 ? 0 : idx;
                                if (hasHostExports && choice == options.size() - 1
                                        && labels.get(choice).startsWith("Show host-export")) {
                                    promptHostExportThenCreate(hostExports, allowCopySoloDeck);
                                } else if (choice > 0 && choice < options.size()
                                        && options.get(choice) != null) {
                                    sendCreateWithLegacy(options.get(choice), allowCopySoloDeck);
                                } else {
                                    promptFreshPartnerLook(allowCopySoloDeck);
                                }
                            });
                    return;
                }
                promptFreshPartnerLook(allowCopySoloDeck);
            } catch (final Exception e) {
                send(new CoopPartnerCreateEvent(guestProfileId, "Partner", true, 0, 0, new byte[0], ""));
            }
        }

        private void promptHostExportThenCreate(final List<File> hostExports,
                                                final boolean allowCopySoloDeck) {
            final List<String> hostLabels = new ArrayList<>();
            hostLabels.add("Start fresh");
            for (final File f : hostExports) {
                hostLabels.add(f.getName() + " (matches solo name)");
            }
            FOptionPane.showOptionDialog(
                    "These look like old host exports of your solo character.",
                    "Co-op partner", null, hostLabels,
                    hostIdx -> {
                        if (hostIdx != null && hostIdx > 0 && hostIdx <= hostExports.size()) {
                            sendCreateWithLegacy(hostExports.get(hostIdx - 1), allowCopySoloDeck);
                        } else {
                            promptFreshPartnerLook(allowCopySoloDeck);
                        }
                    });
        }

        private void sendCreateWithLegacy(final File chosenLegacy, final boolean allowCopySoloDeck) {
            byte[] legacyBlob = new byte[0];
            File keep = null;
            try {
                legacyBlob = CoopLegacyChrImport.encodeChrFile(chosenLegacy);
                keep = chosenLegacy;
            } catch (final Exception e) {
                legacyBlob = new byte[0];
                keep = null;
            }
            if (keep != null && legacyBlob.length > 0) {
                pendingLegacyImport = keep; // markImported only after host accepts + load
                send(new CoopPartnerCreateEvent(guestProfileId, "Partner", true, 0, 0, legacyBlob, ""));
            } else {
                promptFreshPartnerLook(allowCopySoloDeck);
            }
        }

        private void promptFreshPartnerLook(final boolean allowCopySoloDeck) {
            FOptionPane.showInputDialog("Partner name (max 32 chars)", "Co-op partner", "Partner", null,
                    typed -> {
                        String name = "Partner";
                        if (typed != null && !typed.trim().isEmpty()) {
                            name = CoopPartnerValidator.capName(typed);
                        }
                        final String finalName = name;
                        FOptionPane.showOptionDialog("Choose gender", "Co-op partner look", null,
                                Arrays.asList("Male", "Female"),
                                genderIdx -> {
                                    final boolean male = genderIdx == null || genderIdx != 1;
                                    promptRaceThenAvatar(finalName, male, allowCopySoloDeck);
                                });
                    }, false);
        }

        private void promptRaceThenAvatar(final String name, final boolean male,
                                          final boolean allowCopySoloDeck) {
            final List<String> raceLabels = new ArrayList<>();
            try {
                final com.badlogic.gdx.utils.Array<String> races =
                        forge.adventure.data.HeroListData.instance().getRaces();
                if (races != null) {
                    for (int i = 0; i < races.size; i++) {
                        raceLabels.add(races.get(i));
                    }
                }
            } catch (final Exception ignored) {
            }
            if (raceLabels.isEmpty()) {
                promptAvatarThenSend(name, male, 0, allowCopySoloDeck);
                return;
            }
            FOptionPane.showOptionDialog("Choose race", "Co-op partner look", null, raceLabels,
                    raceIdx -> {
                        final int race = raceIdx != null && raceIdx >= 0 && raceIdx < raceLabels.size()
                                ? raceIdx : 0;
                        promptAvatarThenSend(name, male, race, allowCopySoloDeck);
                    });
        }

        private void promptAvatarThenSend(final String name, final boolean male, final int race,
                                          final boolean allowCopySoloDeck) {
            final List<String> avatarLabels = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                avatarLabels.add("Avatar " + (i + 1));
            }
            FOptionPane.showOptionDialog("Choose look", "Co-op partner look", null, avatarLabels,
                    avatarIdx -> {
                        final int avatar = avatarIdx != null && avatarIdx >= 0 ? avatarIdx : 0;
                        String soloDeck = "";
                        if (allowCopySoloDeck) {
                            try {
                                final forge.deck.Deck d = WorldSave.getCurrentSave().getPlayer().getSelectedDeck();
                                if (d != null) {
                                    soloDeck = forge.deck.io.DeckSerializer.toDecklistText(d);
                                }
                            } catch (final Exception ignored) {
                            }
                        }
                        pendingLegacyImport = null;
                        send(new CoopPartnerCreateEvent(guestProfileId, name, male, race, avatar,
                                new byte[0], soloDeck));
                    });
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
                    final String localHash = CoopWorldSync.rebuildFromSeed(
                            staging, offer.getWorldSeed(), worldPath, mv2SetCode,
                            offer.getGates());
                    if (CoopWorldHash.matches(localHash, offer.getWorldHash())) {
                        final World previous = sessionWorld;
                        sessionWorld = staging;
                        worldHash = localHash;
                        guestWorldPlaneId = offerPlaneId;
                        if (previous != null && previous != staging) {
                            try {
                                previous.dispose();
                            } catch (final Exception ignored) {
                            }
                        }
                        // CO5: wait for partner blob before READY (finishReady from onPartnerOffer).
                        if (partnerLoaded) {
                            finishReady(offer.getHostPlayerName());
                        } else {
                            peerName = offer.getHostPlayerName();
                            status("World hash matched — waiting for partner");
                        }
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
            if (!partnerLoaded) {
                status("Partner not loaded — not ready yet");
                return;
            }
            if (sessionWorld == null || sessionWorld.getData() == null) {
                status("World not ready — waiting for world offer");
                return;
            }
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
