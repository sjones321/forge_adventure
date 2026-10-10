package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.adventure.stage.WorldStage;
import forge.adventure.world.SetPlaneRules;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.item.PaperCardPredicates;
import forge.model.FModel;
import forge.card.CardRulesPredicates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * RW1: Ascendant themed-fight loot — one signature card from the enemy theme core
 * (weighted toward unowned names that appeared in the deck it played), then the
 * remaining card rewards from the current set (set plane, or newest rotation set
 * on the home plane). Gold, shards, materials and items keep today's tables.
 * Gym / League / boss / quest paths never enter here.
 * <p>
 * Printings: one call site ({@link #resolvePrinting}) — interim until CS0 (#48)
 * merges {@code SourcePrintings}; then route signature → rotation and set cards
 * → that set's printing through SourcePrintings.
 */
public final class FightRewards {
    /** Unowned signature candidates get this many weight units vs 1 for owned. */
    public static final int UNOWNED_SIGNATURE_WEIGHT = 3;

    /** Test override for {@link #currentSetCode()}; null = live resolution. */
    private static String currentSetOverrideForTest;
    /** Test override for ownership counts; null = live collection. */
    private static java.util.function.Function<String, Integer> ownedCountOverrideForTest;

    private FightRewards() {
    }

    /** Test helper: force the current-set code (e.g. {@code ZEN}); null clears. */
    public static void setCurrentSetCodeForTest(String setCode) {
        currentSetOverrideForTest = setCode;
    }

    /** Test helper: override owned-copy lookup by card name; null clears. */
    public static void setOwnedCountOverrideForTest(java.util.function.Function<String, Integer> fn) {
        ownedCountOverrideForTest = fn;
    }

    public static void clearTestOverrides() {
        currentSetOverrideForTest = null;
        ownedCountOverrideForTest = null;
    }

    /**
     * True when RW1 should rewrite this enemy's fight card loot: Ascendant, flag on,
     * themed (EN1 {@code themeId}), not a boss.
     */
    public static boolean applies(EnemyData enemy) {
        if (enemy == null || enemy.boss) {
            return false;
        }
        if (enemy.themeId == null || enemy.themeId.isEmpty()) {
            return false;
        }
        try {
            if (!Config.ascendant()) {
                return false;
            }
            ConfigData cfg = Config.instance().getConfigData();
            return cfg == null || cfg.rw1FightRewards;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * True when RW1 should rewrite the card loot of a single-player fight. Themed enemies always
     * qualify (see {@link #applies}); with {@code rw1AllRegularFights} any other regular enemy does
     * too (dungeon, town and cave enemies, unthemed overworld spawns). Bosses, quest fights and
     * enemies with a prepared deck (gyms, League) keep their own tables.
     */
    public static boolean appliesToFight(EnemyData enemy, boolean questFight) {
        if (applies(enemy)) {
            return true;
        }
        if (enemy == null || enemy.boss || questFight || enemy.preparedDeck != null) {
            return false;
        }
        try {
            if (!Config.ascendant()) {
                return false;
            }
            ConfigData cfg = Config.instance().getConfigData();
            return cfg == null || (cfg.rw1FightRewards && cfg.rw1AllRegularFights);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Set plane → that set's code; home / non-set → newest set in the player's
     * current Standard rotation. Empty when neither is available.
     */
    public static String currentSetCode() {
        if (currentSetOverrideForTest != null) {
            return currentSetOverrideForTest;
        }
        try {
            String plane = SetPlaneRules.activeSetCode();
            if (plane != null && !plane.isEmpty()) {
                return plane;
            }
            AdventurePlayer player = AdventurePlayer.current();
            if (player == null) {
                return "";
            }
            StandardWindow window = player.getStandardWindow();
            if (window == null || !window.isActive()) {
                return "";
            }
            String newest = window.newestSet();
            return newest != null ? newest : "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static int signatureCount() {
        try {
            ConfigData cfg = Config.instance().getConfigData();
            if (cfg == null) {
                return 1;
            }
            return Math.max(0, cfg.rw1SignatureCardCount);
        } catch (Throwable t) {
            return 1;
        }
    }

    public static float currentSetShare() {
        try {
            ConfigData cfg = Config.instance().getConfigData();
            if (cfg == null) {
                return 1f;
            }
            float share = cfg.rw1CurrentSetCardShare;
            if (share < 0f) {
                return 0f;
            }
            if (share > 1f) {
                return 1f;
            }
            return share;
        } catch (Throwable t) {
            return 1f;
        }
    }

    /**
     * EN2 + RW1: which enemy a peer is credited with for loot.
     * Host → primary; guest → partner when one was built; otherwise primary.
     */
    public static EnemyData creditedLootEnemy(EnemyData primary, EnemyData partner, boolean localIsHost) {
        if (localIsHost || partner == null) {
            return primary;
        }
        return partner;
    }

    /**
     * EN2 loot path used by {@link WorldStage} pending rolls: consume {@code rolls}
     * (host-authoritative, 0 allowed) and generate RW1 rewards for the credited enemy.
     * Mirrors {@code WorldStage#setWinner} bookkeeping via {@link WorldStage.PendingLootRolls}.
     */
    public static Array<Reward> rollViaPendingLootRolls(EnemyData credited, Iterable<PaperCard> playedDeck,
            int rolls) {
        Array<Reward> loot = new Array<>();
        WorldStage.PendingLootRolls pending = new WorldStage.PendingLootRolls();
        pending.set(rolls);
        int n = pending.consume();
        // Co-op / credited path: played deck is known (may be empty) — no full-core fallback.
        final boolean allowFullCore = playedDeck == null;
        for (int i = 0; i < n; i++) {
            if (applies(credited)) {
                loot.addAll(generate(credited, null, playedDeck, true, allowFullCore));
            }
        }
        return loot;
    }

    /**
     * Build themed-fight rewards for {@code enemy}: signature card(s), then each
     * {@link RewardData} entry (card rows rewritten to the current set / share;
     * gold/items/etc. unchanged). {@code extraRewards} is the sprite-level bonus
     * array from {@code EnemySprite.rewards}.
     */
    public static Array<Reward> generate(EnemyData enemy, RewardData[] extraRewards,
            Iterable<PaperCard> deckCards, boolean useSeedlessRandom) {
        return generate(enemy, extraRewards, deckCards, useSeedlessRandom, true);
    }

    /**
     * @param allowFullCoreSignatureFallback when false (co-op wire / known empty deck),
     *        an empty core∩deck yields no signature — never the full theme core.
     */
    public static Array<Reward> generate(EnemyData enemy, RewardData[] extraRewards,
            Iterable<PaperCard> deckCards, boolean useSeedlessRandom,
            boolean allowFullCoreSignatureFallback) {
        Array<Reward> out = new Array<>();
        if (enemy == null) {
            return out;
        }
        Random rng = useSeedlessRandom
                ? new Random()
                : forge.adventure.world.WorldSave.getCurrentSave().getWorld().getRandom();
        List<PaperCard> deckList = flatList(deckCards);
        String setCode = currentSetCode();
        // Remaining set cards exclude the theme core so the signature stays the only core card.
        Set<String> coreExclude = new HashSet<>(coreNames(enemy.themeId));

        // 1) Guaranteed signature(s) from theme core ∩ played deck.
        int sigWanted = signatureCount();
        if (sigWanted > 0) {
            List<PaperCard> signatures = pickSignatures(enemy.themeId, deckList, sigWanted, rng,
                    allowFullCoreSignatureFallback);
            // No theme (or no theme card in the deck): the signature comes from the deck it played.
            if (signatures.isEmpty() && allowFullCoreSignatureFallback) {
                signatures = pickDeckSignatures(deckList, sigWanted, rng);
            }
            for (PaperCard pc : signatures) {
                if (pc != null) {
                    out.add(new Reward(pc));
                }
            }
        }

        // 2) Enemy JSON rewards + sprite extras.
        if (enemy.rewards != null) {
            for (RewardData rdata : enemy.rewards) {
                appendRewardRow(out, rdata, deckList, setCode, coreExclude, rng, useSeedlessRandom);
            }
        }
        if (extraRewards != null) {
            for (RewardData rdata : extraRewards) {
                appendRewardRow(out, rdata, deckList, setCode, coreExclude, rng, useSeedlessRandom);
            }
        }
        return out;
    }

    /**
     * Thin RewardData-facing entry used by tests and {@code EnemySprite}: same as
     * {@link #generate(EnemyData, RewardData[], Iterable, boolean)}.
     */
    public static Array<Reward> generateViaRewardPath(EnemyData enemy, RewardData[] extraRewards,
            Iterable<PaperCard> deckCards, boolean useSeedlessRandom) {
        return generate(enemy, extraRewards, deckCards, useSeedlessRandom);
    }

    private static void appendRewardRow(Array<Reward> out, RewardData rdata, List<PaperCard> deckList,
            String setCode, Set<String> coreExclude, Random rng, boolean useSeedlessRandom) {
        if (rdata == null) {
            return;
        }
        String type = rdata.type == null || rdata.type.isEmpty() ? "randomCard" : rdata.type;
        if (isCardRewardType(type)) {
            appendCardRewards(out, rdata, deckList, setCode, coreExclude, rng);
            return;
        }
        // Gold, shards, items, materials, life, packs — unchanged tables.
        out.addAll(rdata.generate(false, deckList, useSeedlessRandom));
    }

    private static boolean isCardRewardType(String type) {
        return "deckCard".equals(type) || "card".equals(type) || "randomCard".equals(type);
    }

    /**
     * Same count / probability / rarity rolls as today, but the rolled cards come
     * from the current set (share) or the enemy deck (remainder).
     */
    private static void appendCardRewards(Array<Reward> out, RewardData rdata, List<PaperCard> deckList,
            String setCode, Set<String> coreExclude, Random rng) {
        // Named / source-deck / union rows: let RewardData.generate own the probability
        // roll (avoid a double roll that halves the intended chance).
        if ((rdata.cardName != null && !rdata.cardName.isEmpty())
                || (rdata.sourceDeck != null && !rdata.sourceDeck.isEmpty())
                || rdata.cardUnion != null) {
            out.addAll(rdata.generate(false, deckList, true));
            return;
        }

        if (rdata.probability != 0 && rng.nextFloat() > rdata.probability) {
            return;
        }

        int total = rolledCardCount(rdata, rng);
        if (total <= 0) {
            return;
        }
        float share = currentSetShare();
        boolean haveSet = setCode != null && !setCode.isEmpty();
        int fromSet = haveSet ? Math.min(total, Math.round(total * share)) : 0;
        int fromDeck = total - fromSet;

        if (fromSet > 0) {
            List<PaperCard> setPicks = generateFromCurrentSet(rdata, setCode, fromSet, coreExclude, rng);
            for (PaperCard pc : setPicks) {
                if (pc != null) {
                    out.add(new Reward(pc));
                }
            }
            // Thin / empty set pool → top up from the deck (core names excluded).
            int granted = setPicks.size();
            if (granted < fromSet) {
                fromDeck += fromSet - granted;
            }
        }
        if (fromDeck > 0 && deckList != null && !deckList.isEmpty()) {
            // Never reintroduce theme-core names here — the signature must stay unique.
            List<PaperCard> deckPool = excludeNames(deckList, coreExclude);
            if (!deckPool.isEmpty()) {
                List<PaperCard> deckPicks = CardUtil.generateCards(deckPool, rdata, fromDeck, rng);
                for (PaperCard pc : deckPicks) {
                    if (pc != null) {
                        out.add(new Reward(pc));
                    }
                }
            }
        }
    }

    private static List<PaperCard> excludeNames(List<PaperCard> pool, Set<String> exclude) {
        if (pool == null || pool.isEmpty() || exclude == null || exclude.isEmpty()) {
            return pool == null ? Collections.emptyList() : pool;
        }
        List<PaperCard> out = new ArrayList<>();
        for (PaperCard pc : pool) {
            if (pc != null && !exclude.contains(pc.getName())) {
                out.add(pc);
            }
        }
        return out;
    }

    /** Mirrors {@link RewardData#generate} count math for deckCard / card rows. */
    static int rolledCardCount(RewardData rdata, Random rng) {
        float factor = 1f;
        try {
            factor = Current.player().getDifficulty().rewardMaxFactor;
        } catch (Throwable ignored) {
            // keep 1f
        }
        int maxCount = Math.round(rdata.addMaxCount * factor);
        int added = maxCount > 0 ? rng.nextInt(maxCount) : 0;
        int bonus = 0;
        if ("deckCard".equals(rdata.type)) {
            try {
                bonus = Current.player().bonusDeckCards();
            } catch (Throwable ignored) {
                // keep 0
            }
        }
        return Math.max(0, rdata.count + added + bonus);
    }

    /**
     * Draw {@code count} cards from the current set using the row's rarity/color
     * filters, then pin printings via {@link #resolvePrinting}.
     * <p>
     * Pool is built from {@link RewardData#getAllCards()} (Package K format-aware:
     * Pauper commons, Commander breadth, Standard window, plus
     * {@link RewardData#adventureRewardFilter}), then restricted to {@code setCode}
     * printings. Basics are excluded. CardPredicate rarity/color filters still apply.
     */
    static List<PaperCard> generateFromCurrentSet(RewardData filter, String setCode, int count,
            Set<String> coreExclude, Random rng) {
        List<PaperCard> out = new ArrayList<>();
        if (filter == null || setCode == null || setCode.isEmpty() || count <= 0) {
            return out;
        }
        List<PaperCard> setPool = formatAwareSetPool(setCode, coreExclude);
        // Thin sets still grant what they can; appendCardRewards tops up from the deck.
        if (setPool.isEmpty()) {
            return out;
        }
        // Pool is already set-scoped; leave editions null so CardPredicate does not
        // soft-accept other printings of the same name.
        RewardData pinned = new RewardData(filter);
        pinned.type = "card";
        pinned.editions = null;
        pinned.cardName = null;
        pinned.sourceDeck = null;

        List<PaperCard> picks = CardUtil.generateCards(setPool, pinned, count, rng);
        for (PaperCard pc : picks) {
            if (pc == null) {
                continue;
            }
            PaperCard resolved = resolvePrinting(pc.getName(), setCode);
            if (resolved != null && setCode.equalsIgnoreCase(resolved.getEdition())) {
                out.add(resolved);
            } else if (setCode.equalsIgnoreCase(pc.getEdition())) {
                out.add(pc);
            }
        }
        return out;
    }

    /**
     * Format-aware, adventure-filtered printings of {@code setCode} (no basics).
     * <p>
     * Applies the same Package K gates as {@link RewardData#getAllCards()}
     * (Pauper commons, Standard window names, Commander/Historic breadth) plus
     * {@link RewardData#adventureRewardFilter} to each <em>set printing</em>.
     * We cannot filter {@code getAllCards()} by edition alone — that pool is
     * unique-by-name preferred printings, so almost no ZEN rows would survive.
     */
    public static List<PaperCard> formatAwareSetPool(String setCode, Set<String> coreExclude) {
        List<PaperCard> out = new ArrayList<>();
        if (setCode == null || setCode.isEmpty()) {
            return out;
        }
        try {
            if (FModel.getMagicDb() == null || FModel.getMagicDb().getCommonCards() == null
                    || FModel.getMagicDb().getEditions() == null) {
                return out;
            }
            forge.card.CardEdition edition = FModel.getMagicDb().getEditions().get(setCode);
            if (edition == null) {
                return out;
            }
            java.util.function.Predicate<PaperCard> filter = RewardData.adventureRewardFilter();
            boolean pauper = forge.adventure.world.PlaneFormat.favorsPauperPool();
            boolean standardWindow = forge.adventure.world.PlaneFormat.favorsStandardWindowPool();
            StandardWindow window = null;
            if (standardWindow) {
                try {
                    AdventurePlayer player = AdventurePlayer.current();
                    window = player != null ? player.getStandardWindow() : null;
                } catch (Throwable ignored) {
                    window = null;
                }
            }
            // Set planes stock from the full set (see RewardData.generate); home-plane
            // Standard still gates names through the rotation window.
            String activePlaneSet = SetPlaneRules.activeSetCode();
            boolean onThisSetPlane = activePlaneSet != null && !activePlaneSet.isEmpty()
                    && activePlaneSet.equalsIgnoreCase(setCode);
            final boolean gateWindow = standardWindow && !onThisSetPlane
                    && window != null && window.isActive();
            final StandardWindow windowGate = window;
            // getAllCards(edition) resolves each set row (works with lazy card scripts).
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards(edition)) {
                if (pc == null || !setCode.equalsIgnoreCase(pc.getEdition())) {
                    continue;
                }
                if (SetPlaneRules.isBasicLand(pc)) {
                    continue;
                }
                // Pauper planes: only Common printings of this set (no set rares/mythics).
                if (pauper && pc.getRarity() != forge.card.CardRarity.Common) {
                    continue;
                }
                // Standard home-plane: same name gate as RewardData.getAllCards / window.allows.
                if (gateWindow && !windowGate.isStandardLegal(pc.getName())) {
                    continue;
                }
                if (coreExclude != null && coreExclude.contains(pc.getName())) {
                    continue;
                }
                if (filter != null && !filter.test(pc)) {
                    continue;
                }
                out.add(pc);
            }
        } catch (Throwable ignored) {
            // empty
        }
        return out;
    }

    /** Non-basic flat list from a played deck (same filter as {@code EnemySprite#getRewards}). */
    public static List<PaperCard> deckCardsForRewards(Deck deck) {
        if (deck == null || deck.getMain() == null) {
            return Collections.emptyList();
        }
        try {
            String[] restricted = Config.instance().getConfigData() != null
                    ? Config.instance().getConfigData().restrictedEditions : null;
            CardPool pool = deck.getMain();
            if (restricted != null && restricted.length > 0) {
                pool = pool.getFilteredPool(
                        PaperCardPredicates.onlyPrintedInEditions(restricted).negate());
            }
            pool = pool.getFilteredPool(PaperCardPredicates.fromRules(CardRulesPredicates.NOT_BASIC_LAND));
            return flatList(pool.toFlatList());
        } catch (Throwable t) {
            return flatList(deck.getMain().toFlatList());
        }
    }

    /**
     * Pick up to {@code count} signature cards: theme core names that appear in
     * {@code deckCards}, weighted toward names the player does not own yet.
     * Full-core fallback is a last resort only when the played deck is unknown/empty.
     */
    public static List<PaperCard> pickSignatures(String themeId, List<PaperCard> deckCards,
            int count, Random rng) {
        return pickSignatures(themeId, deckCards, count, rng, true);
    }

    public static List<PaperCard> pickSignatures(String themeId, List<PaperCard> deckCards,
            int count, Random rng, boolean allowFullCoreFallback) {
        List<PaperCard> out = new ArrayList<>();
        if (themeId == null || themeId.isEmpty() || count <= 0 || rng == null) {
            return out;
        }
        List<String> candidates = signatureCandidateNameList(themeId, deckCards, allowFullCoreFallback);
        if (candidates.isEmpty()) {
            return out;
        }

        List<String> remaining = new ArrayList<>(candidates);
        for (int i = 0; i < count && !remaining.isEmpty(); i++) {
            String picked = weightedPick(remaining, rng);
            if (picked == null) {
                break;
            }
            remaining.remove(picked);
            // Signature: normal printing from the rotation when possible (CS0).
            PaperCard pc = resolvePrinting(picked, null);
            if (pc != null) {
                out.add(pc);
            }
        }
        return out;
    }

    /**
     * Signature for an enemy without a usable theme: a non-basic card from the deck it played,
     * weighted toward higher rarity and toward cards the player doesn't own yet.
     */
    public static List<PaperCard> pickDeckSignatures(List<PaperCard> deckCards, int count, Random rng) {
        List<PaperCard> out = new ArrayList<>();
        if (deckCards == null || deckCards.isEmpty() || count <= 0 || rng == null) {
            return out;
        }
        List<String> weighted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PaperCard pc : deckCards) {
            if (pc == null || pc.getRules() == null || pc.getRules().getType().isBasicLand()
                    || !seen.add(pc.getName())) {
                continue;
            }
            int copies = switch (pc.getRarity()) {
                case MythicRare, Rare -> 3;
                case Uncommon -> 2;
                default -> 1;
            };
            for (int i = 0; i < copies; i++) {
                weighted.add(pc.getName());
            }
        }
        for (int i = 0; i < count && !weighted.isEmpty(); i++) {
            String picked = weightedPick(weighted, rng);
            if (picked == null) {
                break;
            }
            weighted.removeIf(picked::equals);
            PaperCard pc = resolvePrinting(picked, null);
            if (pc != null) {
                out.add(pc);
            }
        }
        return out;
    }

    /**
     * Core ∩ played-deck names. When {@code deckCards} is null/empty and
     * {@code allowFullCoreFallback} is true, last-resort fallback is the full theme core.
     * Co-op wire paths pass {@code allowFullCoreFallback=false} so an empty host list
     * stays empty on the guest.
     */
    static List<String> signatureCandidateNameList(String themeId, Iterable<PaperCard> deckCards) {
        return signatureCandidateNameList(themeId, deckCards, true);
    }

    static List<String> signatureCandidateNameList(String themeId, Iterable<PaperCard> deckCards,
            boolean allowFullCoreFallback) {
        List<String> core = coreNames(themeId);
        if (core.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> inDeck = new HashSet<>();
        boolean sawAny = false;
        if (deckCards != null) {
            for (PaperCard pc : deckCards) {
                if (pc != null && pc.getName() != null) {
                    sawAny = true;
                    inDeck.add(pc.getName());
                }
            }
        }
        List<String> candidates = new ArrayList<>();
        if (!sawAny) {
            if (allowFullCoreFallback) {
                candidates.addAll(core);
            }
            return candidates;
        }
        for (String name : core) {
            if (inDeck.contains(name)) {
                candidates.add(name);
            }
        }
        return candidates;
    }

    /** Wire helper: core ∩ played-deck names only (legacy / tests). */
    public static String[] signatureCandidateNames(String themeId, Deck playedDeck) {
        List<String> names = signatureCandidateNameList(themeId, deckCardsForRewards(playedDeck), false);
        return names.toArray(new String[0]);
    }

    /**
     * Wire helper for {@link forge.gamemodes.net.event.coop.CoopDuelResultEvent}:
     * core ∩ deck names first (signature), then other played-deck names (thin-set
     * fallback), length-capped. Empty when the played deck is null/empty — guest
     * must not full-core-fallback.
     */
    public static String[] creditPlayedDeckNames(String themeId, Deck playedDeck) {
        List<PaperCard> deck = deckCardsForRewards(playedDeck);
        if (deck.isEmpty()) {
            return new String[0];
        }
        Set<String> core = new HashSet<>(coreNames(themeId));
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // Signature candidates first.
        for (PaperCard pc : deck) {
            if (pc == null || pc.getName() == null || pc.getName().isEmpty()) {
                continue;
            }
            if (core.contains(pc.getName()) && seen.add(pc.getName())) {
                out.add(pc.getName());
            }
        }
        // Thin-set deck-fallback pool (same as host exclude-core deck picks).
        for (PaperCard pc : deck) {
            if (out.size() >= forge.gamemodes.net.coop.CoopWireLimits.MAX_SIGNATURE_CANDIDATES) {
                break;
            }
            if (pc == null || pc.getName() == null || pc.getName().isEmpty()) {
                continue;
            }
            if (!core.contains(pc.getName()) && seen.add(pc.getName())) {
                out.add(pc.getName());
            }
        }
        if (out.size() > forge.gamemodes.net.coop.CoopWireLimits.MAX_SIGNATURE_CANDIDATES) {
            return out.subList(0, forge.gamemodes.net.coop.CoopWireLimits.MAX_SIGNATURE_CANDIDATES)
                    .toArray(new String[0]);
        }
        return out.toArray(new String[0]);
    }

    /**
     * Guest credit from host wire fields. Prefers the catalog enemy for
     * {@code creditEnemyDataId} (partner reward table / colours), then stamps
     * {@code themeId}. Falls back to a copy of the mirror when the catalog miss.
     */
    public static EnemyData creditFromWire(EnemyData mirrorPrimary, String creditEnemyDataId,
            String creditThemeId) {
        EnemyData credit = null;
        if (creditEnemyDataId != null && !creditEnemyDataId.isEmpty()) {
            try {
                EnemyData catalog = forge.adventure.data.WorldData.getEnemy(creditEnemyDataId);
                if (catalog != null) {
                    credit = new EnemyData(catalog);
                }
            } catch (Throwable ignored) {
                credit = null;
            }
        }
        if (credit == null && mirrorPrimary != null) {
            credit = new EnemyData(mirrorPrimary);
        }
        if (credit == null) {
            credit = new EnemyData();
        }
        if (creditEnemyDataId != null && !creditEnemyDataId.isEmpty()) {
            credit.name = creditEnemyDataId;
        }
        // Catalog id on the wire — clear display override so getName() matches.
        credit.nameOverride = "";
        credit.themeId = creditThemeId != null ? creditThemeId : "";
        credit.boss = false;
        return credit;
    }

    /** Build a minimal deck whose mainboard is the named signature candidates. */
    public static Deck deckFromCandidateNames(String[] names) {
        Deck d = new Deck("rw1-credit");
        if (names == null) {
            return d;
        }
        for (String name : names) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            PaperCard pc = CardUtil.getCardByName(name);
            if (pc != null) {
                d.getMain().add(pc);
            }
        }
        return d;
    }

    /** Weighted random among {@code names}; unowned names get {@link #UNOWNED_SIGNATURE_WEIGHT}. */
    public static String weightedPick(List<String> names, Random rng) {
        if (names == null || names.isEmpty() || rng == null) {
            return null;
        }
        int total = 0;
        int[] weights = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            int w = ownedCount(names.get(i)) <= 0 ? UNOWNED_SIGNATURE_WEIGHT : 1;
            weights[i] = w;
            total += w;
        }
        if (total <= 0) {
            return names.get(rng.nextInt(names.size()));
        }
        int roll = rng.nextInt(total);
        int acc = 0;
        for (int i = 0; i < names.size(); i++) {
            acc += weights[i];
            if (roll < acc) {
                return names.get(i);
            }
        }
        return names.get(names.size() - 1);
    }

    static int ownedCount(String cardName) {
        if (cardName == null) {
            return 0;
        }
        if (ownedCountOverrideForTest != null) {
            Integer n = ownedCountOverrideForTest.apply(cardName);
            return n == null ? 0 : Math.max(0, n);
        }
        try {
            CardPool pool = AdventurePlayer.current().getCards();
            return pool == null ? 0 : pool.countByName(cardName);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * RW1 printing hook — <strong>CS0-HOOK</strong>: when {@code SourcePrintings}
     * lands on {@code feature/set-start} (#48), replace this body with:
     * <pre>
     *   if (preferredEdition != null &amp;&amp; !preferredEdition.isEmpty())
     *       return SourcePrintings.printingFromSet(cardName, preferredEdition);
     *   return SourcePrintings.printingFromRotation(cardName);
     * </pre>
     * Do not copy SourcePrintings here; #48 owns that class and CardUtil printing edits.
     *
     * @param preferredEdition set code for set-sourced rewards, or {@code null} for
     *                         signature / rotation printings
     */
    public static PaperCard resolvePrinting(String cardName, String preferredEdition) {
        if (cardName == null || cardName.isEmpty()) {
            return null;
        }
        // --- CS0-HOOK begin (interim until #48 merges) ---
        if (preferredEdition != null && !preferredEdition.isEmpty()) {
            PaperCard pinned = printingInEdition(cardName, preferredEdition);
            if (pinned != null) {
                return pinned;
            }
            return null;
        }
        PaperCard fromRotation = printingFromRotationInterim(cardName);
        if (fromRotation != null) {
            return fromRotation;
        }
        return CardUtil.getCardByName(cardName);
        // --- CS0-HOOK end ---
    }

    /**
     * Exact edition match from the card DB. Does not use
     * {@link CardUtil#getCardByNameAndEdition} (that falls back to a random
     * printing when the set has no copy — CS0 will own the real pin).
     */
    private static PaperCard printingInEdition(String cardName, String edition) {
        if (cardName == null || edition == null || edition.isEmpty()) {
            return null;
        }
        try {
            List<PaperCard> all = null;
            if (FModel.getMagicDb() != null && FModel.getMagicDb().getCommonCards() != null) {
                all = FModel.getMagicDb().getCommonCards().getAllCards(cardName);
            } else if (StaticData.instance() != null) {
                all = StaticData.instance().getCommonCards().getAllCards(cardName);
            }
            if (all == null) {
                return null;
            }
            for (PaperCard pc : all) {
                if (pc != null && edition.equalsIgnoreCase(pc.getEdition())) {
                    return pc;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /**
     * Interim rotation printing (no SourcePrintings yet): prefer a printing from
     * the player's current window sets, else any printing of the name.
     */
    private static PaperCard printingFromRotationInterim(String cardName) {
        try {
            List<String> rotation = new ArrayList<>();
            AdventurePlayer player = AdventurePlayer.current();
            if (player != null && player.getStandardWindow() != null) {
                List<String> sets = player.getStandardWindow().getSets();
                if (sets != null) {
                    rotation.addAll(sets);
                }
            }
            for (String code : rotation) {
                PaperCard pc = printingInEdition(cardName, code);
                if (pc != null) {
                    return pc;
                }
            }
            if (StaticData.instance() != null) {
                List<PaperCard> all = StaticData.instance().getCommonCards().getAllCards(cardName);
                if (all != null && !all.isEmpty()) {
                    return all.get(0);
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return null;
    }

    private static List<PaperCard> flatList(Iterable<PaperCard> cards) {
        List<PaperCard> out = new ArrayList<>();
        if (cards == null) {
            return out;
        }
        for (PaperCard pc : cards) {
            if (pc != null) {
                out.add(pc);
            }
        }
        return out;
    }

    /** Package-visible for tests: theme core names (loaded). */
    public static List<String> coreNames(String themeId) {
        List<String> out = new ArrayList<>();
        EnemyThemeData theme = EnemyThemeDecks.getTheme(themeId);
        if (theme == null) {
            return out;
        }
        EnemyThemeDecks.ensureCoreLoaded(theme);
        if (theme.core == null) {
            return out;
        }
        for (String n : theme.core) {
            if (n != null && !n.isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    /** True when {@code edition} equals {@code setCode} ignoring case. */
    public static boolean isEdition(PaperCard pc, String setCode) {
        return pc != null && setCode != null
                && setCode.equalsIgnoreCase(pc.getEdition());
    }
}
