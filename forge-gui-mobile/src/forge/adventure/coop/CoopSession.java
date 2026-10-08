package forge.adventure.coop;

import forge.adventure.player.AdventurePlayer;
import forge.adventure.util.Config;
import forge.adventure.world.WorldSave;
import forge.gamemodes.net.coop.CoopAddressUtil;
import forge.gamemodes.net.coop.CoopMessageListener;
import forge.gamemodes.net.coop.CoopOverworldClient;
import forge.gamemodes.net.coop.CoopOverworldServer;
import forge.gamemodes.net.coop.CoopPorts;
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
import java.util.function.Consumer;

/**
 * Ascendant co-op session (CO1). Host owns the world; guest brings their own
 * character. Hard version check on connect. Provides send/listen hooks for CO2/CO3.
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
    private volatile int overworldPort = CoopPorts.OVERWORLD_PORT;
    private volatile int gamePort = CoopPorts.GAME_PORT;
    private volatile boolean skipUPnP = true;

    private CoopOverworldServer server;
    private CoopOverworldClient client;

    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private final List<CoopHooks.OverworldListener> overworldListeners = new CopyOnWriteArrayList<>();
    private final List<CoopHooks.DuelListener> duelListeners = new CopyOnWriteArrayList<>();

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

    public int getOverworldPort() {
        return overworldPort;
    }

    public int getGamePort() {
        return gamePort;
    }

    public boolean isActive() {
        return state == State.HOSTING || state == State.JOINING || state == State.READY;
    }

    public void addStatusListener(final Consumer<String> listener) {
        statusListeners.add(listener);
    }

    public void removeStatusListener(final Consumer<String> listener) {
        statusListeners.remove(listener);
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
     * Start hosting. Requires Ascendant and a loaded world. UPnP is skipped by
     * default (Tailscale / documented manual firewall); pass {@code skipUPnP=false}
     * only for classic LAN without Tailscale if a future UPnP helper is wired.
     */
    public synchronized void host(final boolean skipUPnPFlag) throws Exception {
        ensureAscendant();
        ensureWorldLoaded();
        disconnectInternal("restarting host");
        this.skipUPnP = skipUPnPFlag;
        this.overworldPort = Config.instance().getConfigData().coopOverworldPort;
        this.gamePort = Config.instance().getConfigData().coopGamePort;
        role = CoopSessionRole.HOST;
        state = State.HOSTING;
        CoopCharacterStore.exportCurrentPlayer();

        server = new CoopOverworldServer(overworldPort, new HostListener());
        server.start();
        // Game port is reserved for CO3; CO1 does not start FServerManager so
        // stock online play stays untouched. CO3 will call startGamePort().
        status("Hosting co-op on overworld port " + overworldPort
                + " (game port " + gamePort + " reserved for duels)"
                + (skipUPnP ? "; UPnP skipped" : ""));
    }

    /**
     * Join a host. Address may be {@code host}, {@code host:port}, Tailscale
     * {@code 100.x.y.z}, or LAN. Default port is the overworld port.
     */
    public synchronized void join(final String address) throws Exception {
        ensureAscendant();
        ensureWorldLoaded(); // guest needs a local character/world slot to rebuild into
        disconnectInternal("restarting join");

        final URLValidator.HostPort hp = URLValidator.parseURL(address);
        if (hp == null) {
            throw new IllegalArgumentException("Invalid address: " + address);
        }
        final String host = hp.host();
        int port = hp.port() != null && hp.port() > 0 ? hp.port() : Config.instance().getConfigData().coopOverworldPort;
        this.overworldPort = port;
        this.gamePort = Config.instance().getConfigData().coopGamePort;
        this.skipUPnP = CoopAddressUtil.shouldSkipUPnPForAddress(host);

        role = CoopSessionRole.GUEST;
        state = State.JOINING;
        CoopCharacterStore.exportCurrentPlayer();

        client = new CoopOverworldClient(host, port, new GuestListener());
        client.connect();
        status("Connecting to " + host + ':' + port
                + (CoopAddressUtil.isTailscaleAddress(host) ? " (Tailscale, UPnP N/A)" : ""));
    }

    /** Send a NetEvent to the peer (host→guest or guest→host). */
    public void send(final NetEvent event) {
        if (role == CoopSessionRole.HOST && server != null) {
            server.send(event);
        } else if (role == CoopSessionRole.GUEST && client != null) {
            client.send(event);
        }
    }

    public synchronized void disconnect() {
        disconnectInternal("local disconnect");
    }

    private void disconnectInternal(final String reason) {
        if (client != null) {
            try {
                client.send(new CoopDisconnectEvent(reason));
            } catch (final Exception ignored) {
            }
            client.disconnect();
            client = null;
        }
        if (server != null) {
            try {
                server.send(new CoopDisconnectEvent(reason));
            } catch (final Exception ignored) {
            }
            server.stop();
            server = null;
        }
        if (role == CoopSessionRole.GUEST || role == CoopSessionRole.HOST) {
            try {
                CoopCharacterStore.savePlayer(WorldSave.getCurrentSave().getPlayer());
            } catch (final Exception e) {
                lastError = "Failed to save character: " + e.getMessage();
            }
        }
        role = CoopSessionRole.NONE;
        state = State.DISCONNECTED;
        peerName = "";
        status("Disconnected: " + reason);
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

    private final class HostListener implements CoopMessageListener {
        @Override
        public void onConnected() {
            status("Guest connected — waiting for hello");
        }

        @Override
        public void onMessage(final NetEvent event) {
            if (event instanceof CoopHelloEvent) {
                onHello((CoopHelloEvent) event);
            } else if (event instanceof CoopWorldRequestEvent) {
                onWorldRequest((CoopWorldRequestEvent) event);
            } else if (event instanceof CoopSessionReadyEvent) {
                state = State.READY;
                peerName = ((CoopSessionReadyEvent) event).getPeerName();
                status("Session ready with " + peerName);
            } else if (event instanceof CoopDisconnectEvent) {
                disconnectInternal(((CoopDisconnectEvent) event).getReason());
            } else {
                handleHookMessage(event);
            }
        }

        private void onHello(final CoopHelloEvent hello) {
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
            peerName = hello.getCharacterName() != null ? hello.getCharacterName() : hello.getPlayerName();
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
            status("Version OK — offered world seed " + offer.getWorldSeed() + " hash " + worldHash.substring(0, 8) + "…");
        }

        private void onWorldRequest(final CoopWorldRequestEvent req) {
            try {
                final byte[] bytes = CoopWorldSync.serializeWorld(WorldSave.getCurrentSave().getWorld());
                send(new CoopWorldDataEvent(
                        WorldSave.getCurrentSave().getWorld().getSeed(),
                        worldHash,
                        bytes));
                status("Sent world data fallback (" + bytes.length + " bytes) — guest hash was "
                        + req.getLocalWorldHash());
            } catch (final Exception e) {
                reject("Failed to serialize world: " + e.getMessage());
            }
        }

        private void reject(final String reason) {
            lastError = reason;
            state = State.REJECTED;
            send(new CoopHelloRejectEvent(reason));
            status("Rejected guest: " + reason);
        }

        @Override
        public void onDisconnected(final String reason) {
            if (state != State.DISCONNECTED && state != State.IDLE) {
                status("Guest left: " + reason);
                state = State.HOSTING; // keep listening for a new guest
                peerName = "";
            }
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
                    player.getName());
            send(hello);
            status("Sent hello (build/card hash check)");
        }

        @Override
        public void onMessage(final NetEvent event) {
            if (event instanceof CoopHelloRejectEvent) {
                lastError = ((CoopHelloRejectEvent) event).getReason();
                state = State.REJECTED;
                status("Rejected: " + lastError);
                disconnectInternal(lastError);
            } else if (event instanceof CoopWorldOfferEvent) {
                onWorldOffer((CoopWorldOfferEvent) event);
            } else if (event instanceof CoopWorldDataEvent) {
                onWorldData((CoopWorldDataEvent) event);
            } else if (event instanceof CoopDisconnectEvent) {
                disconnectInternal(((CoopDisconnectEvent) event).getReason());
            } else {
                handleHookMessage(event);
            }
        }

        private void onWorldOffer(final CoopWorldOfferEvent offer) {
            peerName = offer.getHostPlayerName();
            gamePort = offer.getGamePort();
            // Plane must match — guest already loaded Ascendant.
            final String localPlaneHash = CoopWorldSync.planeConfigHash();
            if (!localPlaneHash.equals(offer.getPlaneConfigHash())) {
                // Still try rebuild; hash mismatch on world will trigger fallback.
                status("Plane config hash differs — will verify world hash");
            }
            final String localHash = CoopWorldSync.rebuildFromSeed(offer.getWorldSeed());
            if (CoopWorldHash.matches(localHash, offer.getWorldHash())) {
                worldHash = localHash;
                finishReady(offer.getHostPlayerName());
                status("World hash matched after seed rebuild");
            } else {
                status("World hash mismatch — requesting world data from host");
                send(new CoopWorldRequestEvent(localHash, "rebuild hash mismatch"));
            }
        }

        private void onWorldData(final CoopWorldDataEvent data) {
            try {
                CoopWorldSync.applyWorldBytes(data.getWorldSaveBytes());
                worldHash = CoopWorldSync.hashWorld(WorldSave.getCurrentSave().getWorld());
                finishReady(peerName);
                status("Applied host world data fallback");
            } catch (final Exception e) {
                lastError = "Failed to apply world data: " + e.getMessage();
                state = State.REJECTED;
                status(lastError);
                disconnectInternal(lastError);
            }
        }

        private void finishReady(final String hostName) {
            state = State.READY;
            send(new CoopSessionReadyEvent(false, WorldSave.getCurrentSave().getPlayer().getName(), worldHash));
            status("Session ready with host " + hostName);
        }

        @Override
        public void onDisconnected(final String reason) {
            if (state != State.DISCONNECTED) {
                disconnectInternal(reason);
            }
        }

        @Override
        public void onError(final String message, final Throwable cause) {
            lastError = message;
            status("Join error: " + message);
        }
    }
}
