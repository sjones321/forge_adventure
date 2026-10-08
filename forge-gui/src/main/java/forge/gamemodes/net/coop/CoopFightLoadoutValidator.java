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
 * base life plus the largest real item/perk bonus the host knows about. Hand-size
 * change is restricted to [-2, +2]. Start-of-battle and command-zone card names
 * must appear on an allowlist built from items.json effects and skill perk effects.
 *
 * <p><b>Trust model:</b> card ownership on the guest cannot be verified — the
 * partner is trusted to only offer cards they own. The host still bounds and
 * allowlists effect card names and rejects oversized / banned decklists.
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

    private CoopFightLoadoutValidator() {
    }

    /**
     * @param maxLifeBonus largest lifeModifier any known item/perk can grant (host-computed)
     * @param allowedEffectCards card names grantable by items.json or skill perks
     * @param baseLifeSupplier guest-claimed base life floor (host may use its own default)
     */
    public static Result validate(final CoopFightLoadout raw,
                                  final int maxLifeBonus,
                                  final Set<String> allowedEffectCards,
                                  final IntSupplier baseLifeSupplier) {
        if (raw == null) {
            return new Result(false, null, "null");
        }
        final CoopFightLoadout first = CoopFightLoadout.validateOrNull(raw);
        if (first == null) {
            return new Result(false, null, "bounds");
        }
        final int baseLife = Math.max(1, baseLifeSupplier != null ? baseLifeSupplier.getAsInt() : 20);
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
        final List<String> start = filterNames(first.getStartBattleCardNames(), allow);
        final List<String> command = filterNames(first.getCommandZoneCardNames(), allow);
        // If the guest sent a name not on the allowlist, reject (don't silently drop
        // when they sent something — dropping only applies when allowlist is empty
        // in tests).
        if (allowedEffectCards != null && !allowedEffectCards.isEmpty()) {
            if (start.size() != first.getStartBattleCardNames().size()
                    || command.size() != first.getCommandZoneCardNames().size()) {
                return new Result(false, null, "effect card not allowlisted");
            }
        }
        final CoopFightLoadout clamped = CoopFightLoadout.builder()
                .playerName(first.getPlayerName())
                .avatarId(first.getAvatarId())
                .startingLife(life)
                .manaShards(first.getManaShards())
                .freeMulligans(first.getFreeMulligans())
                .lifeModifier(0) // folded into startingLife
                .changeStartCards(handDelta)
                .extraManaShards(first.getExtraManaShards())
                .startBattleCardNames(start)
                .commandZoneCardNames(command)
                .equippedItemIds(first.getEquippedItemIds())
                .opponentLifeModifier(clampOpp(first.getOpponentLifeModifier()))
                .opponentChangeStartCards(clampHand(first.getOpponentChangeStartCards()))
                .build();
        return new Result(true, clamped, "");
    }

    /** Build an allowlist from effect card-name arrays (items + perks). */
    public static Set<String> allowlistFromEffects(final Supplier<Iterable<String[]>> effectCardArrays) {
        final Set<String> out = new HashSet<>();
        if (effectCardArrays == null) {
            return out;
        }
        final Iterable<String[]> arrays = effectCardArrays.get();
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

    private static List<String> filterNames(final List<String> names, final Set<String> allow) {
        if (names == null || names.isEmpty()) {
            return Collections.emptyList();
        }
        if (allow.isEmpty()) {
            return new ArrayList<>(names);
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
