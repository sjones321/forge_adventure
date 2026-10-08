package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Guest → host: first message after the overworld TCP connect. Host must refuse
 * unless build hash, card-data hash and protocol version all match.
 */
public class CoopHelloEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final int protocolVersion;
    private final String buildHash;
    private final String cardDataHash;
    private final String playerName;
    private final String characterName;

    public CoopHelloEvent(final int protocolVersion, final String buildHash, final String cardDataHash,
                          final String playerName, final String characterName) {
        this.protocolVersion = protocolVersion;
        this.buildHash = buildHash;
        this.cardDataHash = cardDataHash;
        this.playerName = playerName;
        this.characterName = characterName;
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
}
