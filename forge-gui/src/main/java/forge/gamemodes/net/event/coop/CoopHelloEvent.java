package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Guest → host: first message after the overworld TCP connect. Host must refuse
 * unless session code, build hash, card-data hash and protocol version all match.
 * Until this passes, the host ignores every other message type.
 */
public class CoopHelloEvent implements NetEvent {
    private static final long serialVersionUID = 2L;

    private final int protocolVersion;
    private final String buildHash;
    private final String cardDataHash;
    private final String playerName;
    private final String characterName;
    private final String sessionCode;

    public CoopHelloEvent(final int protocolVersion, final String buildHash, final String cardDataHash,
                          final String playerName, final String characterName, final String sessionCode) {
        this.protocolVersion = protocolVersion;
        this.buildHash = buildHash;
        this.cardDataHash = cardDataHash;
        this.playerName = playerName;
        this.characterName = characterName;
        this.sessionCode = sessionCode != null ? sessionCode : "";
    }

    public int getProtocolVersion() {
        return protocolVersion;
    }

    public String getBuildHash() {
        return buildHash;
    }

    public String getCardDataHash() {
        return cardDataHash;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getCharacterName() {
        return characterName;
    }

    public String getSessionCode() {
        return sessionCode;
    }
}
