package forge.gamemodes.net.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Callback for overworld-port messages. Adventure {@code CoopSession} implements
 * the handshake; CO2/CO3 register additional handlers via the session.
 */
public interface CoopMessageListener {
    void onConnected();

    void onMessage(NetEvent event);

    void onDisconnected(String reason);

    void onError(String message, Throwable cause);
}
