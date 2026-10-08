package forge.gamemodes.net.coop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Clamp and validate a guest {@link CoopFightLoadout}. Starting life is capped at
 * base life plus the largest real item/perk/blessing bonus the host knows about.
 * Hand-size change is restricted to [-2, +2]. Start-of-battle and command-zone
 * card names must appear on an allowlist built from items, skill perks, and
 * blessing recipes — an empty or failed allowlist rejects all effect card names
 * (fail closed). Mana shards, extra shards, and free mulligans are clamped to
 * host-computed maxima from the same sources.
 *
 * <p><b>Trust model:</b> card ownership on the guest cannot be verified — the
 * partner is trusted to only offer cards they own. The host still bounds and
 * allowlists effect card names and rejects oversized / restricted decklists.
 */
public final class CoopFightLoadoutValidator {
    public static final int MIN_HAND_DELTA = -2;
    public static final int MAX_HAND_DELTA = 2;

    public static final class Result {
        public final boolean ok;
        public final CoopFightLoadout loadout;
        public final String reason;

        Result(final boolean ok, final CoopFightLoadout loadout, final String reason) {
            this.ok = ok;
            this.loadout = loadout;
            this.reason = reason != null ? reason : "";
        }
    }

    /** Optional caps for shards / mulligans; zero or negative means use wire MAX_STAT. */
    public static final class StatCaps {
        public final int maxManaShards;
        public final int maxExtraShards;
        public final int maxFreeMulligans;

        public StatCaps(final int maxManaShards, final int maxExtraShards, final int maxFreeMulligans) {
            this.maxManaShards = maxManaShards;
            this.maxExtraShards = maxExtraShards;
            this.maxFreeMulligans = maxFreeMulligans;
        }

        public static StatCaps unbounded() {
            return new StatCaps(CoopDuelWireLimits.MAX_STAT, CoopDuelWireLimits.MAX_STAT,
                    CoopDuelWireLimits.MAX_STAT);
        }
    }

    private CoopFightLoadoutValidator() {
    }

    /**
     * @param maxLifeBonus largest lifeModifier any known item/perk/blessing can grant
     * @param allowedEffectCards card names grantable by items / skill perks / blessings;
     *                           {@code null} or empty → fail closed on any effect card names
     * @param baseLifeSupplier guest's real base life (bounded); not a hard-coded 20
     */
    public static Result validate(final CoopFightLoadout raw,
                                  final int maxLifeBonus,
                                  final Set<String> allowedEffectCards,
                                  final IntSupplier baseLifeSupplier) {
        return validate(raw, maxLifeBonus, allowedEffectCards, baseLifeSupplier, StatCaps.unbounded());
    }

    public static Result validate(final CoopFightLoadout raw,
                                  final int maxLifeBonus,
                                  final Set<String> allowedEffectCards,
                                  final IntSupplier baseLifeSupplier,
                                  final StatCaps caps) {
        if (raw == null) {
            return new Result(false, null, "null");
        }
        final CoopFightLoadout first = CoopFightLoadout.validateOrNull(raw);
        if (first == null) {
            return new Result(false, null, "bounds");
        }
        final int claimedBase = Math.max(1, baseLifeSupplier != null ? baseLifeSupplier.getAsInt() : first.getStartingLife());
        final int baseLife = Math.min(claimedBase, CoopDuelWireLimits.MAX_STAT);
        final int maxLife = baseLife + Math.max(0, maxLifeBonus);
        int life = first.getStartingLife() + first.getLifeModifier();
        if (life < 1) {
            life = 1;
        }
        if (life > maxLife) {
            life = maxLife;
        }
        int handDelta = first.getChangeStartCards();
        if (handDelta < MIN_HAND_DELTA) {
            handDelta = MIN_HAND_DELTA;
        }
        if (handDelta > MAX_HAND_DELTA) {
            handDelta = MAX_HAND_DELTA;
        }
        final Set<String> allow = normalizeAllow(allowedEffectCards);
        // Fail closed: empty / failed allowlist rejects any effect card names.
        final boolean allowlistUsable = allowedEffectCards != null && !allowedEffectCards.isEmpty();
        if (!allowlistUsable) {
            if (!first.getStartBattleCardNames().isEmpty()
                    || !first.getCommandZoneCardNames().isEmpty()) {
                return new Result(false, null, "allowlist empty");
            }
        }
        final List<String> start = filterNames(first.getStartBattleCardNames(), allow);
        final List<String> command = filterNames(first.getCommandZoneCardNames(), allow);
        if (allowlistUsable) {
            if (start.size() != first.getStartBattleCardNames().size()
                    || command.size() != first.getCommandZoneCardNames().size()) {
                return new Result(false, null, "effect card not allowlisted");
            }
        }
        final StatCaps c = caps != null ? caps : StatCaps.unbounded();
        final int manaShards = clampTo(first.getManaShards(), 0, positiveCap(c.maxManaShards));
        final int extraShards = clampTo(first.getExtraManaShards(), 0, positiveCap(c.maxExtraShards));
        final int freeMull = clampTo(first.getFreeMulligans(), 0, positiveCap(c.maxFreeMulligans));
        final CoopFightLoadout clamped = CoopFightLoadout.builder()
                .playerName(first.getPlayerName())
                .avatarId(first.getAvatarId())
                .startingLife(life)
                .manaShards(manaShards)
                .freeMulligans(freeMull)
                .lifeModifier(0) // folded into startingLife
                .changeStartCards(handDelta)
                .extraManaShards(extraShards)
                .startBattleCardNames(start)
                .commandZoneCardNames(command)
                .equippedItemIds(first.getEquippedItemIds())
                .opponentLifeModifier(clampOpp(first.getOpponentLifeModifier()))
                .opponentChangeStartCards(clampHand(first.getOpponentChangeStartCards()))
                .build();
        return new Result(true, clamped, "");
    }

    /** Build an allowlist from effect card-name arrays (items + perks + blessings). */
    public static Set<String> allowlistFromEffects(final Supplier<Iterable<String[]>> effectCardArrays) {
        final Set<String> out = new HashSet<>();
        if (effectCardArrays == null) {
            return out;
        }
        final Iterable<String[]> arrays;
        try {
            arrays = effectCardArrays.get();
        } catch (final Exception e) {
            return out; // failed build → empty → fail closed at validate
        }
        if (arrays == null) {
            return out;
        }
        for (final String[] arr : arrays) {
            if (arr == null) {
                continue;
            }
            for (final String n : arr) {
                if (n != null && !n.isEmpty()) {
                    out.add(n.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static int positiveCap(final int cap) {
        return cap > 0 ? cap : CoopDuelWireLimits.MAX_STAT;
    }

    private static int clampTo(final int v, final int min, final int max) {
        if (v < min) {
            return min;
        }
        return Math.min(v, max);
    }

    private static int clampOpp(final int v) {
        return Math.max(-CoopDuelWireLimits.MAX_STAT, Math.min(v, CoopDuelWireLimits.MAX_STAT));
    }

    private static int clampHand(final int v) {
        return Math.max(MIN_HAND_DELTA, Math.min(v, MAX_HAND_DELTA));
    }

    private static Set<String> normalizeAllow(final Set<String> in) {
        if (in == null || in.isEmpty()) {
            return Collections.emptySet();
        }
        final Set<String> out = new HashSet<>();
        for (final String s : in) {
            if (s != null) {
                out.add(s.toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Fail closed: empty allowlist drops every name. */
    private static List<String> filterNames(final List<String> names, final Set<String> allow) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        if (allow.isEmpty()) {
            return Collections.emptyList();
        }
        final List<String> out = new ArrayList<>();
        for (final String n : names) {
            if (n != null && allow.contains(n.toLowerCase(Locale.ROOT))) {
                out.add(n);
            }
        }
        return out;
    }
}
