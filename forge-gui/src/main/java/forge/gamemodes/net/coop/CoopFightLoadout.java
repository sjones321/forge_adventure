package forge.gamemodes.net.coop;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Guest adventure loadout as plain bounded data for the host (CO3). Never Java
 * objects / textures — avatars are names/ids; effects are numeric fields plus
 * card-name strings the host resolves against its own card DB.
 */
public final class CoopFightLoadout implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String playerName;
    private final String avatarId;
    private final int startingLife;
    private final int manaShards;
    private final int freeMulligans;
    private final int lifeModifier;
    private final int changeStartCards;
    private final int extraManaShards;
    private final List<String> startBattleCardNames;
    private final List<String> commandZoneCardNames;
    private final List<String> equippedItemIds;
    private final int opponentLifeModifier;
    private final int opponentChangeStartCards;

    private CoopFightLoadout(final Builder b) {
        this.playerName = CoopDuelWireLimits.clampString(b.playerName, CoopDuelWireLimits.MAX_NAME_LEN);
        this.avatarId = CoopDuelWireLimits.clampString(b.avatarId, CoopDuelWireLimits.MAX_AVATAR_ID_LEN);
        this.startingLife = clampStat(b.startingLife, 1);
        this.manaShards = clampStat(b.manaShards, 0);
        this.freeMulligans = clampStat(b.freeMulligans, 0);
        this.lifeModifier = clampStat(b.lifeModifier, -CoopDuelWireLimits.MAX_STAT);
        this.changeStartCards = clampStat(b.changeStartCards, -20);
        this.extraManaShards = clampStat(b.extraManaShards, 0);
        this.startBattleCardNames = freezeNames(b.startBattleCardNames, CoopDuelWireLimits.MAX_EFFECT_CARD_NAMES);
        this.commandZoneCardNames = freezeNames(b.commandZoneCardNames, CoopDuelWireLimits.MAX_COMMAND_CARDS);
        this.equippedItemIds = freezeNames(b.equippedItemIds, CoopDuelWireLimits.MAX_EQUIPPED_ITEMS);
        this.opponentLifeModifier = clampStat(b.opponentLifeModifier, -CoopDuelWireLimits.MAX_STAT);
        this.opponentChangeStartCards = clampStat(b.opponentChangeStartCards, -20);
    }

    private static int clampStat(final int v, final int min) {
        if (v < min) {
            return min;
        }
        return Math.min(v, CoopDuelWireLimits.MAX_STAT);
    }

    private static List<String> freezeNames(final List<String> in, final int max) {
        if (in == null || in.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>(Math.min(in.size(), max));
        for (final String s : in) {
            if (out.size() >= max) {
                break;
            }
            final String c = CoopDuelWireLimits.clampString(s, CoopDuelWireLimits.MAX_TEXT_LEN);
            if (!c.isEmpty()) {
                out.add(c);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Validate an inbound loadout. Returns null when rejected.
     */
    public static CoopFightLoadout validateOrNull(final CoopFightLoadout raw) {
        if (raw == null) {
            return null;
        }
        if (!CoopDuelWireLimits.nameOk(raw.playerName)) {
            return null;
        }
        if (raw.avatarId != null && raw.avatarId.length() > CoopDuelWireLimits.MAX_AVATAR_ID_LEN) {
            return null;
        }
        if (!CoopDuelWireLimits.countInRange(raw.startingLife, 1, CoopDuelWireLimits.MAX_STAT)) {
            return null;
        }
        // Rebuild through builder so clamps re-apply.
        return builder()
                .playerName(raw.playerName)
                .avatarId(raw.avatarId)
                .startingLife(raw.startingLife)
                .manaShards(raw.manaShards)
                .freeMulligans(raw.freeMulligans)
                .lifeModifier(raw.lifeModifier)
                .changeStartCards(raw.changeStartCards)
                .extraManaShards(raw.extraManaShards)
                .startBattleCardNames(raw.startBattleCardNames)
                .commandZoneCardNames(raw.commandZoneCardNames)
                .equippedItemIds(raw.equippedItemIds)
                .opponentLifeModifier(raw.opponentLifeModifier)
                .opponentChangeStartCards(raw.opponentChangeStartCards)
                .build();
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getAvatarId() {
        return avatarId;
    }

    public int getStartingLife() {
        return startingLife;
    }

    public int getManaShards() {
        return manaShards;
    }

    public int getFreeMulligans() {
        return freeMulligans;
    }

    public int getLifeModifier() {
        return lifeModifier;
    }

    public int getChangeStartCards() {
        return changeStartCards;
    }

    public int getExtraManaShards() {
        return extraManaShards;
    }

    public List<String> getStartBattleCardNames() {
        return startBattleCardNames;
    }

    public List<String> getCommandZoneCardNames() {
        return commandZoneCardNames;
    }

    public List<String> getEquippedItemIds() {
        return equippedItemIds;
    }

    public int getOpponentLifeModifier() {
        return opponentLifeModifier;
    }

    public int getOpponentChangeStartCards() {
        return opponentChangeStartCards;
    }

    public static final class Builder {
        private String playerName = "";
        private String avatarId = "";
        private int startingLife = 20;
        private int manaShards;
        private int freeMulligans;
        private int lifeModifier;
        private int changeStartCards;
        private int extraManaShards;
        private List<String> startBattleCardNames = Collections.emptyList();
        private List<String> commandZoneCardNames = Collections.emptyList();
        private List<String> equippedItemIds = Collections.emptyList();
        private int opponentLifeModifier;
        private int opponentChangeStartCards;

        public Builder playerName(final String v) {
            playerName = v;
            return this;
        }

        public Builder avatarId(final String v) {
            avatarId = v;
            return this;
        }

        public Builder startingLife(final int v) {
            startingLife = v;
            return this;
        }

        public Builder manaShards(final int v) {
            manaShards = v;
            return this;
        }

        public Builder freeMulligans(final int v) {
            freeMulligans = v;
            return this;
        }

        public Builder lifeModifier(final int v) {
            lifeModifier = v;
            return this;
        }

        public Builder changeStartCards(final int v) {
            changeStartCards = v;
            return this;
        }

        public Builder extraManaShards(final int v) {
            extraManaShards = v;
            return this;
        }

        public Builder startBattleCardNames(final List<String> v) {
            startBattleCardNames = v;
            return this;
        }

        public Builder commandZoneCardNames(final List<String> v) {
            commandZoneCardNames = v;
            return this;
        }

        public Builder equippedItemIds(final List<String> v) {
            equippedItemIds = v;
            return this;
        }

        public Builder opponentLifeModifier(final int v) {
            opponentLifeModifier = v;
            return this;
        }

        public Builder opponentChangeStartCards(final int v) {
            opponentChangeStartCards = v;
            return this;
        }

        public CoopFightLoadout build() {
            return new CoopFightLoadout(this);
        }
    }
}
