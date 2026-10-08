package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Host → guest: co-op duel is starting; guest should connect {@code FGameClient}
 * to the game port ({@link forge.gamemodes.net.coop.CoopPorts#GAME_PORT}) using
 * the already-authenticated session. Includes the bind address hint when set.
 */
public class CoopDuelStartEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final long duelId;
    private final int gamePort;
    private final String bindAddressHint;
    private final String encounterId;
    private final String sessionCode;

    public CoopDuelStartEvent(final long duelId, final int gamePort, final String bindAddressHint,
                              final String encounterId, final String sessionCode) {
        this.duelId = duelId;
        this.gamePort = gamePort;
        this.bindAddressHint = bindAddressHint != null ? bindAddressHint : "";
        this.encounterId = encounterId != null ? encounterId : "";
        this.sessionCode = sessionCode != null ? sessionCode : "";
    }

    public long getDuelId() {
        return duelId;
    }

    public int getGamePort() {
        return gamePort;
    }

    public String getBindAddressHint() {
        return bindAddressHint;
    }

    public String getEncounterId() {
        return encounterId;
    }

    public String getSessionCode() {
        return sessionCode;
    }
}
