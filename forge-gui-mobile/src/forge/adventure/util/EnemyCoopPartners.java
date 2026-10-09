package forge.adventure.util;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/**
 * EN2: co-op enemy partners of the same creature type on a different theme.
 * <p>
 * Pairing is deterministic from the encounter seed so host and guest agree.
 * Bosses, gym/League ({@code preparedDeck}), and {@code nextEnemy} chains keep
 * their hand-made setups. When a type has only one theme or the enemy has no
 * theme tag, fall back to a themed enemy from the same biome.
 */
public final class EnemyCoopPartners {
    private static final Logger LOG = Logger.getLogger(EnemyCoopPartners.class.getName());

    public enum PartnerSource {
        NONE,
        SAME_TAG,
        BIOME_FALLBACK
    }

    /** Result of planning a co-op partner for one primary enemy. */
    public static final class PartnerPlan {
        public final boolean partnerBuilt;
        public final EnemyData partner;
        public final String partnerThemeId;
        public final PartnerSource source;
        public final float lifeFactor;
        public final int extraCards;
        public final int lootRollsPerPlayer;

        PartnerPlan(final boolean partnerBuilt, final EnemyData partner, final String partnerThemeId,
                    final PartnerSource source, final float lifeFactor, final int extraCards,
                    final int lootRollsPerPlayer) {
            this.partnerBuilt = partnerBuilt;
            this.partner = partner;
            this.partnerThemeId = partnerThemeId;
            this.source = source != null ? source : PartnerSource.NONE;
            this.lifeFactor = lifeFactor;
            this.extraCards = extraCards;
            this.lootRollsPerPlayer = lootRollsPerPlayer;
        }

        public static PartnerPlan none(final float lifeFactor, final int extraCards) {
            return new PartnerPlan(false, null, null, PartnerSource.NONE, lifeFactor, extraCards, 1);
        }
    }

    private static Boolean forceEnabledForTests;

    private EnemyCoopPartners() {
    }

    /** Test hook: force EN2 on/off. Pass null to clear. */
    public static void setEnabledForTests(final Boolean enabled) {
        forceEnabledForTests = enabled;
    }

    public static boolean isEnabled() {
        if (forceEnabledForTests != null) {
            return forceEnabledForTests;
        }
        if (!Config.ascendant()) {
            return false;
        }
        try {
            final ConfigData cfg = Config.instance().getConfigData();
            return cfg == null || cfg.en2CoopEnemyPartners;
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * Bosses, gym/League ({@code preparedDeck}), and encounters that already
     * chain enemies keep their hand-made setups — no generated partner.
     */
    public static boolean eligibleForPartner(final EnemyData primary) {
        if (primary == null) {
            return false;
        }
        if (primary.boss) {
            return false;
        }
        if (primary.preparedDeck != null) {
            return false;
        }
        if (primary.nextEnemy != null) {
            return false;
        }
        return true;
    }

    /**
     * Deterministic sibling theme id sharing a creature-type tag with
     * {@code primaryThemeId}, never the same theme. Returns null when fewer than
     * two themes share a tag.
     */
    public static String pickPartnerThemeId(final String primaryThemeId, final long encounterSeed) {
        if (primaryThemeId == null || primaryThemeId.isEmpty()) {
            return null;
        }
        final EnemyThemeData primary = EnemyThemeDecks.getTheme(primaryThemeId);
        if (primary == null || primary.tags == null || primary.tags.length == 0) {
            return null;
        }
        final List<EnemyThemeData> siblings = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        for (final String tag : primary.tags) {
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            for (final EnemyThemeData t : EnemyThemeDecks.themesForTag(tag)) {
                if (t == null || t.id == null || t.id.isEmpty()) {
                    continue;
                }
                if (primaryThemeId.equals(t.id)) {
                    continue;
                }
                if (seen.add(t.id)) {
                    siblings.add(t);
                }
            }
        }
        if (siblings.isEmpty()) {
            return null;
        }
        siblings.sort(Comparator.comparing(t -> t.id));
        final int idx = floorMod(encounterSeed, siblings.size());
        return siblings.get(idx).id;
    }

    /**
     * Deterministic biome fallback: a different catalog enemy from the biome that
     * can carry a theme, preferably not the primary's theme.
     */
    public static EnemyData pickBiomeFallbackPartner(final EnemyData primary,
                                                     final List<EnemyData> biomeEnemies,
                                                     final long encounterSeed) {
        if (primary == null || biomeEnemies == null || biomeEnemies.isEmpty()) {
            return null;
        }
        final List<EnemyData> candidates = new ArrayList<>();
        for (final EnemyData e : biomeEnemies) {
            if (e == null || e.boss || e.preparedDeck != null) {
                continue;
            }
            if (primary.name != null && primary.name.equals(e.name)
                    && Objects.equals(primary.themeId, e.themeId)) {
                continue;
            }
            // Prefer enemies that EN1 can theme (quest tags or name match).
            final String themeGuess = EnemyThemeDecks.pickThemeId(e, new java.util.Random(0));
            if (themeGuess == null || themeGuess.isEmpty()) {
                continue;
            }
            candidates.add(e);
        }
        if (candidates.isEmpty()) {
            return null;
        }
        candidates.sort(Comparator.comparing(e -> e.name != null ? e.name : "", String.CASE_INSENSITIVE_ORDER));
        final int idx = floorMod(encounterSeed ^ 0x9E3779B97F4A7C15L, candidates.size());
        final EnemyData picked = candidates.get(idx);
        final EnemyData copy = new EnemyData(picked);
        copy.nextEnemy = null;
        copy.preparedDeck = null;
        copy.teamNumber = 1;
        // Assign a theme deterministically; prefer one different from the primary.
        final String primaryTheme = primary.themeId;
        final List<String> themeOptions = new ArrayList<>();
        for (final EnemyThemeData t : themesMatchingEnemy(copy)) {
            if (t.id != null && !t.id.equals(primaryTheme)) {
                themeOptions.add(t.id);
            }
        }
        if (themeOptions.isEmpty()) {
            for (final EnemyThemeData t : themesMatchingEnemy(copy)) {
                if (t.id != null) {
                    themeOptions.add(t.id);
                }
            }
        }
        if (themeOptions.isEmpty()) {
            return null;
        }
        Collections.sort(themeOptions);
        copy.themeId = themeOptions.get(floorMod(encounterSeed, themeOptions.size()));
        return copy;
    }

    /**
     * Plan a partner for a co-op duel. When EN2 is off or the encounter is
     * ineligible / no partner can be built, returns {@link PartnerPlan#none} with
     * the no-partner boosts. Scaling and loot tunables come from Config when
     * available; callers may override via the explicit overload.
     */
    public static PartnerPlan planPartner(final EnemyData primary, final List<EnemyData> biomeEnemies,
                                          final long encounterSeed) {
        float noPartnerLife = forge.gamemodes.net.coop.CoopDuelScaling.DEFAULT_LIFE_FACTOR;
        int noPartnerExtra = forge.gamemodes.net.coop.CoopDuelScaling.DEFAULT_EXTRA_CARDS;
        float partnerLife = forge.gamemodes.net.coop.CoopDuelScaling.DEFAULT_PARTNER_LIFE_FACTOR;
        int partnerExtra = forge.gamemodes.net.coop.CoopDuelScaling.DEFAULT_PARTNER_EXTRA_CARDS;
        int lootRolls = forge.gamemodes.net.coop.CoopDuelRewards.DEFAULT_PARTNER_LOOT_ROLLS;
        try {
            final ConfigData cfg = Config.instance().getConfigData();
            if (cfg != null) {
                noPartnerLife = cfg.coopDuelEnemyLifeFactor;
                noPartnerExtra = cfg.coopDuelEnemyExtraCards;
                partnerLife = cfg.coopDuelPartnerLifeFactor;
                partnerExtra = cfg.coopDuelPartnerExtraCards;
                lootRolls = cfg.coopDuelPartnerLootRollsPerPlayer;
            }
        } catch (final Exception ignored) {
        }
        return planPartner(primary, biomeEnemies, encounterSeed,
                noPartnerLife, noPartnerExtra, partnerLife, partnerExtra, lootRolls);
    }

    public static PartnerPlan planPartner(final EnemyData primary, final List<EnemyData> biomeEnemies,
                                          final long encounterSeed,
                                          final float noPartnerLifeFactor, final int noPartnerExtraCards,
                                          final float partnerLifeFactor, final int partnerExtraCards,
                                          final int partnerLootRolls) {
        final float boostLife = noPartnerLifeFactor > 0f ? noPartnerLifeFactor
                : forge.gamemodes.net.coop.CoopDuelScaling.DEFAULT_LIFE_FACTOR;
        final int boostExtra = Math.max(0, noPartnerExtraCards);
        if (!isEnabled() || !eligibleForPartner(primary)) {
            return PartnerPlan.none(boostLife, boostExtra);
        }

        final String partnerTheme = pickPartnerThemeId(primary.themeId, encounterSeed);
        if (partnerTheme != null) {
            final EnemyData partner = buildSameTypePartner(primary, partnerTheme);
            if (partner != null) {
                final float life = forge.gamemodes.net.coop.CoopDuelScaling.effectiveLifeFactor(
                        true, partnerLifeFactor, boostLife);
                final int extra = forge.gamemodes.net.coop.CoopDuelScaling.effectiveExtraCards(
                        true, partnerExtraCards, boostExtra);
                final int rolls = forge.gamemodes.net.coop.CoopDuelRewards.lootRollsPerPlayer(
                        true, partnerLootRolls);
                return new PartnerPlan(true, partner, partnerTheme, PartnerSource.SAME_TAG,
                        life, extra, rolls);
            }
        }

        final EnemyData fallback = pickBiomeFallbackPartner(primary, biomeEnemies, encounterSeed);
        if (fallback != null) {
            final float life = forge.gamemodes.net.coop.CoopDuelScaling.effectiveLifeFactor(
                    true, partnerLifeFactor, boostLife);
            final int extra = forge.gamemodes.net.coop.CoopDuelScaling.effectiveExtraCards(
                    true, partnerExtraCards, boostExtra);
            final int rolls = forge.gamemodes.net.coop.CoopDuelRewards.lootRollsPerPlayer(
                    true, partnerLootRolls);
            return new PartnerPlan(true, fallback, fallback.themeId, PartnerSource.BIOME_FALLBACK,
                    life, extra, rolls);
        }

        LOG.fine("EN2: no partner for " + (primary != null ? primary.getName() : "?")
                + " theme=" + (primary != null ? primary.themeId : null));
        return PartnerPlan.none(boostLife, boostExtra);
    }

    /** Copy the primary with a different theme; leaf (no nextEnemy), team 1. */
    public static EnemyData buildSameTypePartner(final EnemyData primary, final String partnerThemeId) {
        if (primary == null || partnerThemeId == null || partnerThemeId.isEmpty()) {
            return null;
        }
        if (partnerThemeId.equals(primary.themeId)) {
            return null;
        }
        if (EnemyThemeDecks.getTheme(partnerThemeId) == null) {
            return null;
        }
        final EnemyData partner = new EnemyData(primary);
        partner.nextEnemy = null;
        partner.preparedDeck = null;
        partner.themeId = partnerThemeId;
        partner.teamNumber = 1;
        partner.boss = false;
        return partner;
    }

    /**
     * Stable encounter seed so host and guest pick the same partner.
     * Prefers the overworld enemy id; falls back to name + theme.
     */
    public static long encounterSeed(final long enemyId, final EnemyData primary) {
        if (enemyId != 0L) {
            return enemyId;
        }
        final String name = primary != null && primary.name != null ? primary.name : "";
        final String theme = primary != null && primary.themeId != null ? primary.themeId : "";
        return (long) Objects.hash(name, theme) * 0x9E3779B97F4A7C15L;
    }

    private static List<EnemyThemeData> themesMatchingEnemy(final EnemyData data) {
        final List<EnemyThemeData> out = new ArrayList<>();
        final java.util.Set<String> seen = new java.util.HashSet<>();
        if (data == null) {
            return out;
        }
        if (data.questTags != null) {
            for (final String tag : data.questTags) {
                for (final EnemyThemeData t : EnemyThemeDecks.themesForTag(tag)) {
                    if (t.id != null && seen.add(t.id)) {
                        out.add(t);
                    }
                }
            }
        }
        if (data.name != null) {
            for (final EnemyThemeData t : EnemyThemeDecks.themesForTag(data.name)) {
                if (t.id != null && seen.add(t.id)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    private static int floorMod(final long seed, final int modulus) {
        if (modulus <= 0) {
            return 0;
        }
        final int m = (int) (seed % modulus);
        return m < 0 ? m + modulus : m;
    }
}
