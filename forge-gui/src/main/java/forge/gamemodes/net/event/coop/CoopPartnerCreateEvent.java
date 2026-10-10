package forge.gamemodes.net.event.coop;

import forge.gamemodes.net.event.NetEvent;

/**
 * CO5 guest → host: create a new partner for this profile in the host world.
 * Optional legacy {@code .chr} blob and optional solo decklist text (gift).
 */
public class CoopPartnerCreateEvent implements NetEvent {
    private static final long serialVersionUID = 1L;

    private final String profileId;
    private final String characterName;
    private final boolean male;
    private final int race;
    private final int avatarIndex;
    /** Optional deflated legacy co-op {@code .chr} SaveFileData for one-time import. */
    private final byte[] legacyChrBlob;
    /** Optional decklist text from the guest's solo character (host must allow). */
    private final String soloDecklistText;

    public CoopPartnerCreateEvent(final String profileId, final String characterName,
                                  final boolean male, final int race, final int avatarIndex,
                                  final byte[] legacyChrBlob, final String soloDecklistText) {
        this.profileId = profileId != null ? profileId : "";
        this.characterName = characterName != null ? characterName : "";
        this.male = male;
        this.race = race;
        this.avatarIndex = avatarIndex;
        this.legacyChrBlob = legacyChrBlob != null ? legacyChrBlob.clone() : new byte[0];
        this.soloDecklistText = soloDecklistText != null ? soloDecklistText : "";
    }

    public String getProfileId() {
        return profileId;
    }

    public String getCharacterName() {
        return characterName;
    }

    public boolean isMale() {
        return male;
    }

    public int getRace() {
        return race;
    }

    public int getAvatarIndex() {
        return avatarIndex;
    }

    public byte[] getLegacyChrBlob() {
        return legacyChrBlob.clone();
    }

    public String getSoloDecklistText() {
        return soloDecklistText;
    }
}
