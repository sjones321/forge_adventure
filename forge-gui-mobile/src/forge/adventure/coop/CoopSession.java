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
import forge.player.GamePlayerUtil;
import forge.screens.TransitionScreen;
import forge.util.URLValidator;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    /** CO5: host-side snapshot rate limit for the connected guest. */
    private final CoopPartnerValidator partnerValidator = new CoopPartnerValidator();
    /** CO5: guest last snapshot send time (debounce for gather/craft). */
    private volatile long lastPartnerSnapshotSendMs;
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
     * True while this peer is a guest in an active/joining/rejected session.
     * Guests must not write host-world or partner state into local WorldSave slots.
     */
    public boolean isGuestSession() {
        return role == CoopSessionRole.GUEST
                && (state == State.JOINING || state == State.READY || state == State.REJECTED);
    }

    /** @deprecated CO5: use {@link #isGuestSession()} */
    public boolean blocksLocalWorldSave() {
        return isGuestSession();
    }

    public String getGuestProfileId() {
        return guestProfileId != null ? guestProfileId : "";
    }

    public boolean isPartnerLoaded() {
        return partnerLoaded;
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
        guestProfileId = "";
        partnerLoaded = false;
        partnerValidator.resetRateLimit();

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
        lastPartnerSnapshotSendMs = 0L;
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
     * @param returnToMenu when true (real disconnect), guest sends a final partner
     *                     snapshot then returns to the main menu without touching
     *                     the solo save. When false (restarting host/join), skip menu.
     */
    private void disconnectInternal(final String reason, final boolean returnToMenu) {
        final CoopSessionRole previousRole = role;
        final State previousState = state;

        if (previousRole == CoopSessionRole.GUEST && partnerLoaded && returnToMenu) {
            try {
                sendPartnerSnapshotNow();
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
        // Must run while the role is still set: the host only clears id maps, the guest removes mirrors.
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
        role = CoopSessionRole.NONE;
        // Keep REJECTED visible until the next host/join clears it.
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
            partnerValidator.resetRateLimit();
        }
        status("Disconnected: " + reason);

        if (previousRole == CoopSessionRole.GUEST && returnToMenu) {
            returnGuestToMainMenu();
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
     * CO5: return the guest to the Adventure main menu without writing or reading
     * the solo save. In-memory partner state is abandoned; Continue loads solo from disk.
     */
    private void returnGuestToMainMenu() {
        guestWorldPlaneId = PlaneMeta.HOME_ID;
        final Runnable go = () -> {
            try {
                // Invalidate live world so Resume cannot continue with partner state.
                try {
                    WorldSave.getCurrentSave().getWorld().dispose();
                } catch (final Exception ignored) {
                }
                Forge.switchScene(StartScene.instance());
            } catch (final Exception e) {
                status("Could not return to menu: " + e.getMessage());
            }
        };
        if (Gdx.app != null) {
            Gdx.app.postRunnable(go);
        } else {
            go.run();
        }
    }

    /**
     * Guest → host: send a full partner snapshot immediately (duel / leave).
     * Package-visible for tests and {@link CoopDuelRuntime}.
     */
    public void sendPartnerSnapshotNow() {
        if (role != CoopSessionRole.GUEST || !partnerLoaded) {
            return;
        }
        try {
            final SaveFileData data = WorldSave.getCurrentSave().getPlayer().save();
            final byte[] blob = CoopPartnerCodec.encode(data);
            if (!CoopPartnerValidator.blobSizeOk(blob)) {
                status("Partner snapshot too large — not sent");
                return;
            }
            send(new CoopPartnerSnapshotEvent(guestProfileId, blob));
            lastPartnerSnapshotSendMs = System.currentTimeMillis();
        } catch (final Exception e) {
            status("Partner snapshot failed: " + e.getMessage());
        }
    }

    /**
     * Guest → host: debounced snapshot for gather/craft batches.
     */
    public void sendPartnerSnapshotDebounced() {
        if (role != CoopSessionRole.GUEST || !partnerLoaded) {
            return;
        }
        final int debounceSec;
        try {
            debounceSec = Math.max(1, Config.instance().getConfigData().coopPartnerSnapshotDebounceSeconds);
        } catch (final Exception e) {
            sendPartnerSnapshotNow();
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastPartnerSnapshotSendMs < debounceSec * 1000L) {
            return;
        }
        sendPartnerSnapshotNow();
    }

    /**
     * Host: apply a validated partner snapshot into the world save.
     * Package-visible for tests.
     */
    boolean applyHostPartnerSnapshot(final String profileId, final byte[] blob) {
        final String id = CoopProfileId.sanitize(profileId);
        if (id.isEmpty()) {
            return false;
        }
        if (!CoopPartnerValidator.blobSizeOk(blob)) {
            status("Rejected partner snapshot: size");
            return false;
        }
        if (!partnerValidator.acceptSnapshot()) {
            status("Rejected partner snapshot: rate limit");
            return false;
        }
        try {
            final SaveFileData data = CoopPartnerCodec.decode(blob);
            final String problem = CoopPartnerValidator.validateDecoded(data);
            if (problem != null) {
                status("Rejected partner snapshot: " + problem);
                return false;
            }
            // Cap name in the stored blob.
            final String name = CoopPartnerValidator.capName(data.readString("name"));
            if (!name.isEmpty()) {
                data.store("name", name);
            }
            WorldSave.getCurrentSave().getPartners().put(id, data);
            status("Stored partner snapshot for " + id.substring(0, Math.min(8, id.length())) + "…");
            return true;
        } catch (final Exception e) {
            status("Rejected partner snapshot: " + e.getMessage());
            return false;
        }
    }

    /**
     * Host: create a new partner (or import legacy) and store it.
     * Package-visible for tests.
     */
    boolean applyHostPartnerCreate(final CoopPartnerCreateEvent event) {
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
        final String name = CoopPartnerValidator.capName(event.getCharacterName());
        if (name.isEmpty()) {
            status("Rejected partner create: name");
            return false;
        }
        try {
            final AdventurePlayer partner;
            final byte[] legacy = event.getLegacyChrBlob();
            if (legacy != null && legacy.length > 0) {
                if (!CoopPartnerValidator.blobSizeOk(legacy)) {
                    status("Rejected legacy import: size");
                    return false;
                }
                final SaveFileData legacyData = CoopPartnerCodec.decode(legacy);
                partner = CoopPartnerStarter.fromLegacy(legacyData, name, event.isMale(),
                        event.getRace(), event.getAvatarIndex());
            } else {
                final boolean allowCopy = Config.instance().getConfigData().coopPartnerAllowCopySoloDeck;
                final String deckText = allowCopy ? event.getSoloDecklistText() : "";
                partner = CoopPartnerStarter.createNew(name, event.isMale(),
                        event.getRace(), event.getAvatarIndex(), deckText);
            }
            WorldSave.getCurrentSave().getPartners().putPlayer(id, partner);
            sendPartnerOffer(id, false);
            status("Created partner \"" + name + "\" for " + id.substring(0, Math.min(8, id.length())) + "…");
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
            send(new CoopPartnerOfferEvent(profileId, create, allowCopy, blob));
        } catch (final Exception e) {
            status("Partner offer failed: " + e.getMessage());
        }
    }

    /**
     * Guest: load a partner blob into the in-memory player (never written to solo slots).
     * Package-visible for tests.
     */
    void applyGuestPartnerBlob(final byte[] blob) throws Exception {
        if (!CoopPartnerValidator.blobSizeOk(blob)) {
            throw new IOException("Partner blob size rejected");
        }
        final SaveFileData data = CoopPartnerCodec.decode(blob);
        final String problem = CoopPartnerValidator.validateDecoded(data);
        if (problem != null) {
            throw new IOException(problem);
        }
        WorldSave.getCurrentSave().getPlayer().load(data);
        partnerLoaded = true;
        GamePlayerUtil.getGuiPlayer().setName(WorldSave.getCurrentSave().getPlayer().getName());
    }

    /**
     * Package-visible test helper: host stores a partner and returns the encoded blob.
     */
    byte[] testHostCreatePartner(final String profileId, final String name) throws Exception {
        final AdventurePlayer partner = CoopPartnerStarter.createNew(name, true, 0, 0, "");
        WorldSave.getCurrentSave().getPartners().putPlayer(profileId, partner);
        return CoopPartnerCodec.encode(WorldSave.getCurrentSave().getPartners().get(profileId));
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
                    applyHostPartnerCreate((CoopPartnerCreateEvent) event);
                }
            } else if (event instanceof CoopPartnerSnapshotEvent) {
                if (s != null && s.isGuestAuthenticated()) {
                    final CoopPartnerSnapshotEvent snap = (CoopPartnerSnapshotEvent) event;
                    applyHostPartnerSnapshot(snap.getProfileId(), snap.getPartnerBlob());
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
            partnerValidator.resetRateLimit();
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
                endGuestSession(lastError, true);
            } else if (event instanceof CoopWorldOfferEvent) {
                onWorldOffer((CoopWorldOfferEvent) event);
            } else if (event instanceof CoopPartnerOfferEvent) {
                onPartnerOffer((CoopPartnerOfferEvent) event);
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
                // Auto-create with a default look when no UI is attached (tests / headless).
                // In-game StartScene / dialog path can send a richer CoopPartnerCreateEvent first.
                if (Gdx.app == null) {
                    send(new CoopPartnerCreateEvent(guestProfileId, "Partner",
                            true, 0, 0, new byte[0], ""));
                } else {
                    Gdx.app.postRunnable(() -> promptPartnerCreate(offer.isAllowCopySoloDeck()));
                }
                return;
            }
            final byte[] blob = offer.getPartnerBlob();
            runWorldOpOnGl(Forge.getLocalizer() != null
                            ? Forge.getLocalizer().getMessage("lblLoadingWorld")
                            : "Loading partner…",
                    () -> {
                        try {
                            applyGuestPartnerBlob(blob);
                            status("Loaded partner \""
                                    + WorldSave.getCurrentSave().getPlayer().getName() + "\"");
                            if (state == State.JOINING && sessionWorld != null
                                    && sessionWorld.getData() != null) {
                                finishReady(peerName);
                            }
                        } catch (final Exception e) {
                            lastError = "Partner load failed: " + e.getMessage();
                            state = State.REJECTED;
                            status(lastError);
                            endGuestSession(lastError, true);
                        }
                    });
        }

        private void promptPartnerCreate(final boolean allowCopySoloDeck) {
            try {
                String name = "Partner";
                byte[] legacyBlob = new byte[0];
                String soloDeck = "";
                if (CoopLegacyChrImport.hasLegacyCharacters()) {
                    final java.util.List<java.io.File> legacy = CoopLegacyChrImport.listLegacyChrFiles();
                    if (!legacy.isEmpty()) {
                        try {
                            legacyBlob = CoopLegacyChrImport.encodeChrFile(legacy.get(0));
                            status("Importing legacy co-op character from " + legacy.get(0).getName());
                        } catch (final Exception ignored) {
                            legacyBlob = new byte[0];
                        }
                    }
                }
                if (allowCopySoloDeck) {
                    try {
                        final forge.deck.Deck d = WorldSave.getCurrentSave().getPlayer().getSelectedDeck();
                        if (d != null) {
                            soloDeck = forge.deck.io.DeckSerializer.toDecklistText(d);
                        }
                    } catch (final Exception ignored) {
                    }
                }
                // Prefer a typed name when possible.
                try {
                    final String typed = forge.gui.GuiBase.getInterface() != null
                            ? null : null;
                    if (typed != null && !typed.trim().isEmpty()) {
                        name = CoopPartnerValidator.capName(typed);
                    }
                } catch (final Exception ignored) {
                }
                send(new CoopPartnerCreateEvent(guestProfileId, name, true, 0, 0, legacyBlob, soloDeck));
            } catch (final Exception e) {
                send(new CoopPartnerCreateEvent(guestProfileId, "Partner", true, 0, 0, new byte[0], ""));
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
