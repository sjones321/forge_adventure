package forge.adventure.coop;

import com.badlogic.gdx.Gdx;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.World;
import forge.adventure.world.WorldSave;
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
import forge.gamemodes.net.event.coop.CoopHelloEvent;
import forge.gamemodes.net.event.coop.CoopHelloRejectEvent;
import forge.gamemodes.net.event.coop.CoopPartyInviteEvent;
import forge.gamemodes.net.event.coop.CoopPartyResponseEvent;
import forge.gamemodes.net.event.coop.CoopPlayerMoveEvent;
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
    private volatile String sessionCode = "";
    private volatile int overworldPort = CoopPorts.OVERWORLD_PORT;
    private volatile int gamePort = CoopPorts.GAME_PORT;
    private volatile boolean skipUPnP = true;
    private volatile String joinSessionCode = "";
    private volatile String bindAddress = "";

    /** Host world held separately for the guest — never written into WorldSave slots. */
    private volatile World sessionWorld;
    private volatile SaveFileData guestWorldBackup;
    private volatile SaveFileData guestPlayerBackup;
    private volatile String guestCharacterName;
    /** Guards against double {@link #restoreGuestSave()} on REJECTED + disconnect. */
    private final AtomicBoolean guestRestoreDone = new AtomicBoolean(false);

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
        return sessionCode;
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
        overworldListeners.add(listener);
    }

    public void addDuelListener(final CoopHooks.DuelListener listener) {
        duelListeners.add(listener);
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
    public synchronized void host(final boolean skipUPnPFlag) throws Exception {
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
        guestRestoreDone.set(false);
        stashGuestSave();
        CoopCharacterStore.exportCurrentPlayer();
        guestCharacterName = WorldSave.getCurrentSave().getPlayer().getName();
        CoopCharacterStore.loadPlayer(WorldSave.getCurrentSave().getPlayer(), guestCharacterName);
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
            try {
                CoopCharacterStore.savePlayer(WorldSave.getCurrentSave().getPlayer());
            } catch (final Exception e) {
                lastError = "Failed to save character: " + e.getMessage();
            }
            if (restoreGuest) {
                restoreGuestSave();
            }
        }

        disposeSessionWorld();
        role = CoopSessionRole.NONE;
        // Keep REJECTED visible until the next host/join clears it.
        if (previousState == State.REJECTED) {
            state = State.REJECTED;
        } else {
            state = State.DISCONNECTED;
        }
        peerName = "";
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

    private void stashGuestSave() {
        guestWorldBackup = WorldSave.getCurrentSave().getWorld().save();
        guestPlayerBackup = WorldSave.getCurrentSave().getPlayer().save();
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
        final String charName = guestCharacterName;
        guestWorldBackup = null;
        guestPlayerBackup = null;

        if (worldBak == null && playerBak == null && charName == null) {
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
     */
    private void runWorldOpOnGl(final String loadingMessage, final Runnable work) {
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
                status("Session ready with " + peerName);
            } else if (event instanceof CoopDisconnectEvent) {
                peerName = "";
                status("Guest disconnected: " + ((CoopDisconnectEvent) event).getReason());
            } else {
                handleHookMessage(event);
            }
        }

        private void onHello(final CoopHelloEvent hello) {
            if (!CoopSessionCode.matches(sessionCode, hello.getSessionCode())) {
                rejectGuest("Invalid session code", true);
                return;
            }
            if (hello.getProtocolVersion() != CoopPorts.PROTOCOL_VERSION) {
                rejectGuest("Protocol version mismatch (host=" + CoopPorts.PROTOCOL_VERSION
                        + ", guest=" + hello.getProtocolVersion() + ")", false);
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
            runOffNetty(() -> {
                worldHash = CoopWorldSync.hashWorld(WorldSave.getCurrentSave().getWorld());
                final CoopWorldOfferEvent offer = new CoopWorldOfferEvent(
                        WorldSave.getCurrentSave().getPlayer().getName(),
                        Config.instance().getPlane(),
                        CoopWorldSync.planeConfigHash(),
                        WorldSave.getCurrentSave().getWorld().getSeed(),
                        worldHash,
                        gamePort,
                        overworldPort);
                send(offer);
                status("Authenticated — offered world seed " + offer.getWorldSeed()
                        + " hash " + worldHash.substring(0, Math.min(8, worldHash.length())) + "…");
            });
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
            } else if (event instanceof CoopDisconnectEvent) {
                endGuestSession(((CoopDisconnectEvent) event).getReason(), true);
            } else {
                handleHookMessage(event);
            }
        }

        private void onWorldOffer(final CoopWorldOfferEvent offer) {
            peerName = offer.getHostPlayerName();
            gamePort = offer.getGamePort();
            final String localPlaneHash = CoopWorldSync.planeConfigHash();
            if (!localPlaneHash.equals(offer.getPlaneConfigHash())) {
                status("Plane config hash differs — will verify world hash");
            }
            final String loadingMsg = Forge.getLocalizer() != null
                    ? Forge.getLocalizer().getMessage("lblGeneratingWorld")
                    : "Generating world…";
            runWorldOpOnGl(loadingMsg, () -> {
                try {
                    World target = sessionWorld;
                    if (target == null) {
                        target = new World();
                        sessionWorld = target;
                    }
                    final String localHash = CoopWorldSync.rebuildFromSeed(target, offer.getWorldSeed());
                    if (CoopWorldHash.matches(localHash, offer.getWorldHash())) {
                        worldHash = localHash;
                        finishReady(offer.getHostPlayerName());
                        status("World hash matched after seed rebuild (session world)");
                    } else {
                        lastError = CoopPorts.WORLD_HASH_MISMATCH_MESSAGE;
                        state = State.REJECTED;
                        status(lastError);
                        endGuestSession(lastError, true);
                    }
                } catch (final Exception e) {
                    lastError = "World rebuild failed: " + e.getMessage();
                    state = State.REJECTED;
                    status(lastError);
                    endGuestSession(lastError, true);
                }
            });
        }

        private void finishReady(final String hostName) {
            state = State.READY;
            send(new CoopSessionReadyEvent(false, WorldSave.getCurrentSave().getPlayer().getName(), worldHash));
            status("Session ready with host " + hostName);
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
