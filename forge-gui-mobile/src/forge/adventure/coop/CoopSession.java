package forge.adventure.coop;

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
import forge.gamemodes.net.event.coop.CoopWorldDataEvent;
import forge.gamemodes.net.event.coop.CoopWorldOfferEvent;
import forge.gamemodes.net.event.coop.CoopWorldRequestEvent;
import forge.util.URLValidator;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Ascendant co-op session (CO1). Host owns the world; guest brings their own
 * character and never overwrites their local WorldSave with host world data.
 * Hard session-code + version check on connect. Provides send/listen hooks for CO2/CO3.
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
    private volatile boolean worldBlobRequested;
    private volatile String expectedWorldHash = "";
    private volatile String joinSessionCode = "";

    /** Host world held separately for the guest — never written into WorldSave slots. */
    private volatile World sessionWorld;
    private SaveFileData guestWorldBackup;
    private SaveFileData guestPlayerBackup;
    private String guestCharacterName;

    private volatile CoopOverworldServer server;
    private volatile CoopOverworldClient client;

    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<CoopHooks.OverworldListener> overworldListeners = new CopyOnWriteArrayList<>();
    private final List<CoopHooks.DuelListener> duelListeners = new CopyOnWriteArrayList<>();
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
     * code the guest must enter. UPnP is skipped by default.
     */
    public synchronized void host(final boolean skipUPnPFlag) throws Exception {
        ensureAscendant();
        ensureWorldLoaded();
        disconnectInternal("restarting host", false);
        this.skipUPnP = skipUPnPFlag;
        this.overworldPort = Config.instance().getConfigData().coopOverworldPort;
        this.gamePort = Config.instance().getConfigData().coopGamePort;
        this.sessionCode = CoopSessionCode.generate();
        role = CoopSessionRole.HOST;
        state = State.HOSTING;
        CoopCharacterStore.exportCurrentPlayer();

        server = new CoopOverworldServer(overworldPort, new HostListener());
        server.start();
        status("Hosting co-op on overworld port " + overworldPort
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
        stashGuestSave();
        CoopCharacterStore.exportCurrentPlayer();
        guestCharacterName = WorldSave.getCurrentSave().getPlayer().getName();
        // Reload character from the character file into the live player (isolation).
        CoopCharacterStore.loadPlayer(WorldSave.getCurrentSave().getPlayer(), guestCharacterName);
        sessionWorld = new World();
        worldBlobRequested = false;
        expectedWorldHash = "";

        client = new CoopOverworldClient(host, port, new GuestListener());
        client.connect();
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
        } else if (previousRole == CoopSessionRole.HOST
                && previousState != State.HOSTING
                && previousState != State.READY) {
            // Host only persists character when intentionally leaving, not on peer churn.
            // (Character export already happened at host().)
        }

        sessionWorld = null;
        worldBlobRequested = false;
        expectedWorldHash = "";
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
        }
        status("Disconnected: " + reason);
    }

    private void stashGuestSave() {
        guestWorldBackup = WorldSave.getCurrentSave().getWorld().save();
        guestPlayerBackup = WorldSave.getCurrentSave().getPlayer().save();
    }

    private void restoreGuestSave() {
        try {
            if (guestWorldBackup != null) {
                WorldSave.getCurrentSave().getWorld().load(guestWorldBackup);
            }
            if (guestPlayerBackup != null) {
                WorldSave.getCurrentSave().getPlayer().load(guestPlayerBackup);
            } else if (guestCharacterName != null) {
                CoopCharacterStore.loadPlayer(WorldSave.getCurrentSave().getPlayer(), guestCharacterName);
            }
        } catch (final Exception e) {
            lastError = "Failed to restore guest save: " + e.getMessage();
            status(lastError);
        } finally {
            guestWorldBackup = null;
            guestPlayerBackup = null;
        }
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

    private final class HostListener implements CoopMessageListener {
        @Override
        public void onConnected() {
            status("Guest connected — waiting for hello + session code");
        }

        @Override
        public void onMessage(final NetEvent event) {
            // Server already drops non-hello before auth; still be defensive.
            final CoopOverworldServer s = server;
            if (s != null && !s.isGuestAuthenticated() && !(event instanceof CoopHelloEvent)) {
                return;
            }
            if (event instanceof CoopHelloEvent) {
                onHello((CoopHelloEvent) event);
            } else if (event instanceof CoopWorldRequestEvent) {
                onWorldRequest((CoopWorldRequestEvent) event);
            } else if (event instanceof CoopSessionReadyEvent) {
                state = State.READY;
                peerName = ((CoopSessionReadyEvent) event).getPeerName();
                status("Session ready with " + peerName);
            } else if (event instanceof CoopDisconnectEvent) {
                // Peer left — keep hosting; do not save host player or stop server.
                peerName = "";
                if (s != null) {
                    // Auth resets when channel closes; nothing else to do.
                }
                status("Guest disconnected: " + ((CoopDisconnectEvent) event).getReason());
            } else {
                handleHookMessage(event);
            }
        }

        private void onHello(final CoopHelloEvent hello) {
            if (!CoopSessionCode.matches(sessionCode, hello.getSessionCode())) {
                reject("Invalid session code");
                return;
            }
            if (hello.getProtocolVersion() != CoopPorts.PROTOCOL_VERSION) {
                reject("Protocol version mismatch (host=" + CoopPorts.PROTOCOL_VERSION
                        + ", guest=" + hello.getProtocolVersion() + ")");
                return;
            }
            final String mismatch = CoopVersion.mismatchReason(hello.getBuildHash(), hello.getCardDataHash());
            if (mismatch != null) {
                reject(mismatch);
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

        private void onWorldRequest(final CoopWorldRequestEvent req) {
            runOffNetty(() -> {
                try {
                    final byte[] bytes = CoopWorldSync.serializeWorld(WorldSave.getCurrentSave().getWorld());
                    if (bytes.length > CoopPorts.MAX_WORLD_BLOB_BYTES) {
                        reject("Local world too large to send");
                        return;
                    }
                    send(new CoopWorldDataEvent(
                            WorldSave.getCurrentSave().getWorld().getSeed(),
                            worldHash,
                            bytes));
                    status("Sent world data fallback (" + bytes.length + " bytes)");
                } catch (final Exception e) {
                    reject("Failed to serialize world: " + e.getMessage());
                }
            });
        }

        private void reject(final String reason) {
            lastError = reason;
            state = State.REJECTED;
            final CoopOverworldServer s = server;
            if (s != null) {
                s.rejectAndClose(reason);
            } else {
                send(new CoopHelloRejectEvent(reason));
            }
            status("Rejected guest: " + reason);
            // Keep REJECTED visible; stay HOSTING for a new guest after channel close.
        }

        @Override
        public void onDisconnected(final String reason) {
            if (state == State.DISCONNECTED || state == State.IDLE) {
                return;
            }
            // Do not save host player or stop the server — wait for another guest.
            peerName = "";
            if (state != State.REJECTED) {
                state = State.HOSTING;
            }
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
                // Close networking but keep REJECTED; restore guest save.
                endGuestSession(lastError, true);
            } else if (event instanceof CoopWorldOfferEvent) {
                onWorldOffer((CoopWorldOfferEvent) event);
            } else if (event instanceof CoopWorldDataEvent) {
                onWorldData((CoopWorldDataEvent) event);
            } else if (event instanceof CoopDisconnectEvent) {
                endGuestSession(((CoopDisconnectEvent) event).getReason(), true);
            } else {
                handleHookMessage(event);
            }
        }

        private void onWorldOffer(final CoopWorldOfferEvent offer) {
            peerName = offer.getHostPlayerName();
            gamePort = offer.getGamePort();
            expectedWorldHash = offer.getWorldHash();
            final String localPlaneHash = CoopWorldSync.planeConfigHash();
            if (!localPlaneHash.equals(offer.getPlaneConfigHash())) {
                status("Plane config hash differs — will verify world hash");
            }
            runOffNetty(() -> {
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
                        status("World hash mismatch — requesting world data from host");
                        worldBlobRequested = true;
                        expectedWorldHash = offer.getWorldHash();
                        send(new CoopWorldRequestEvent(localHash, "rebuild hash mismatch"));
                    }
                } catch (final Exception e) {
                    lastError = "World rebuild failed: " + e.getMessage();
                    state = State.REJECTED;
                    status(lastError);
                    endGuestSession(lastError, true);
                }
            });
        }

        private void onWorldData(final CoopWorldDataEvent data) {
            if (!worldBlobRequested) {
                lastError = "Unexpected world blob (no request sent)";
                state = State.REJECTED;
                status(lastError);
                endGuestSession(lastError, true);
                return;
            }
            worldBlobRequested = false;
            final byte[] bytes = data.getWorldSaveBytes();
            if (bytes.length > CoopPorts.MAX_WORLD_BLOB_BYTES) {
                lastError = "World blob too large";
                state = State.REJECTED;
                status(lastError);
                endGuestSession(lastError, true);
                return;
            }
            final String expected = expectedWorldHash != null && !expectedWorldHash.isEmpty()
                    ? expectedWorldHash
                    : data.getWorldHash();
            runOffNetty(() -> {
                try {
                    World target = sessionWorld;
                    if (target == null) {
                        target = new World();
                        sessionWorld = target;
                    }
                    CoopWorldSync.applyWorldBytesVerified(target, bytes, expected);
                    worldHash = CoopWorldSync.hashWorld(target);
                    finishReady(peerName);
                    status("Applied host world data into session world (hash verified)");
                } catch (final Exception e) {
                    lastError = "Failed to apply world data: " + e.getMessage();
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
            if (state != State.DISCONNECTED && state != State.IDLE) {
                if (state != State.REJECTED) {
                    endGuestSession(reason, true);
                } else {
                    // Already rejected — ensure networking is torn down once.
                    final CoopOverworldClient c = client;
                    client = null;
                    if (c != null) {
                        c.disconnect();
                    }
                    restoreGuestSave();
                    sessionWorld = null;
                    role = CoopSessionRole.NONE;
                }
            }
        }

        @Override
        public void onError(final String message, final Throwable cause) {
            lastError = message;
            status("Join error: " + message);
        }
    }
}
