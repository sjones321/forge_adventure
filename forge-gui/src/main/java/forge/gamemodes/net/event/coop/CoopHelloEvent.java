package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * Guest → host: first message after the overworld TCP connect. Host must refuse
 * unless session code, build hash, card-data hash and protocol version all match.
 * Until this passes, the host ignores every other message type.
 *
 * <p>CO5: carries the guest's stable install {@link #profileId} so the host can
 * look up or create a world-bound partner character.
 */
public class CoopHelloEvent implements NetEvent {
    private static final long serialVersionUID = 3L;

    private final int protocolVersion;
    private final String buildHash;
    private final String cardDataHash;
    private final String playerName;
    private final String characterName;
    private final String sessionCode;
    /** CO5: per-install profile id (empty on pre-CO5 peers → reject via protocol). */
    private final String profileId;

    public CoopHelloEvent(final int protocolVersion, final String buildHash, final String cardDataHash,
                          final String playerName, final String characterName, final String sessionCode) {
        this(protocolVersion, buildHash, cardDataHash, playerName, characterName, sessionCode, "");
    }

    public CoopHelloEvent(final int protocolVersion, final String buildHash, final String cardDataHash,
                          final String playerName, final String characterName, final String sessionCode,
                          final String profileId) {
        this.protocolVersion = protocolVersion;
        this.buildHash = buildHash;
        this.cardDataHash = cardDataHash;
        this.playerName = playerName;
        this.characterName = characterName;
        this.sessionCode = sessionCode != null ? sessionCode : "";
        this.profileId = profileId != null ? profileId : "";
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

    public String getProfileId() {
        return profileId;
    }
}
