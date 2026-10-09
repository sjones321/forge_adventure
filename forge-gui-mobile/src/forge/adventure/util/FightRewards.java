package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.EnemyThemeData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.adventure.world.SetPlaneRules;
import forge.deck.CardPool;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
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
     * Build themed-fight rewards for {@code enemy}: signature card(s), then each
     * {@link RewardData} entry (card rows rewritten to the current set / share;
     * gold/items/etc. unchanged). {@code extraRewards} is the sprite-level bonus
     * array from {@code EnemySprite.rewards}.
     */
    public static Array<Reward> generate(EnemyData enemy, RewardData[] extraRewards,
            Iterable<PaperCard> deckCards, boolean useSeedlessRandom) {
        Array<Reward> out = new Array<>();
        if (enemy == null) {
            return out;
        }
        Random rng = useSeedlessRandom
                ? new Random()
                : forge.adventure.world.WorldSave.getCurrentSave().getWorld().getRandom();
        List<PaperCard> deckList = flatList(deckCards);
        String setCode = currentSetCode();

        // 1) Guaranteed signature(s) from theme core ∩ played deck.
        int sigWanted = signatureCount();
        if (sigWanted > 0) {
            List<PaperCard> signatures = pickSignatures(enemy.themeId, deckList, sigWanted, rng);
            for (PaperCard pc : signatures) {
                if (pc != null) {
                    out.add(new Reward(pc));
                }
            }
        }

        // 2) Enemy JSON rewards + sprite extras.
        if (enemy.rewards != null) {
            for (RewardData rdata : enemy.rewards) {
                appendRewardRow(out, rdata, deckList, setCode, rng, useSeedlessRandom);
            }
        }
        if (extraRewards != null) {
            for (RewardData rdata : extraRewards) {
                appendRewardRow(out, rdata, deckList, setCode, rng, useSeedlessRandom);
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
            String setCode, Random rng, boolean useSeedlessRandom) {
        if (rdata == null) {
            return;
        }
        String type = rdata.type == null || rdata.type.isEmpty() ? "randomCard" : rdata.type;
        if (isCardRewardType(type)) {
            appendCardRewards(out, rdata, deckList, setCode, rng);
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
            String setCode, Random rng) {
        if (rdata.probability != 0 && rng.nextFloat() > rdata.probability) {
            return;
        }
        // Named / source-deck rows keep RewardData.generate (quest pins etc.).
        if ((rdata.cardName != null && !rdata.cardName.isEmpty())
                || (rdata.sourceDeck != null && !rdata.sourceDeck.isEmpty())
                || rdata.cardUnion != null) {
            out.addAll(rdata.generate(false, deckList, true));
            return;
        }

        int total = rolledCardCount(rdata, rng);
        if (total <= 0) {
            return;
        }
        float share = currentSetShare();
        int fromSet = setCode == null || setCode.isEmpty()
                ? 0
                : Math.min(total, Math.round(total * share));
        int fromDeck = total - fromSet;

        if (fromSet > 0) {
            List<PaperCard> setPicks = generateFromCurrentSet(rdata, setCode, fromSet, rng);
            for (PaperCard pc : setPicks) {
                if (pc != null) {
                    out.add(new Reward(pc));
                }
            }
            // If the set pool was thin, top up from the deck so the player still gets cards.
            int granted = setPicks.size();
            if (granted < fromSet) {
                fromDeck += fromSet - granted;
            }
        }
        if (fromDeck > 0 && deckList != null && !deckList.isEmpty()) {
            List<PaperCard> deckPicks = CardUtil.generateCards(deckList, rdata, fromDeck, rng);
            for (PaperCard pc : deckPicks) {
                if (pc != null) {
                    out.add(new Reward(pc));
                }
            }
        }
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
     * Pool is every printing of {@code setCode} (not unique preferred arts):
     * {@link CardUtil.CardPredicate} treats {@code editions} as "has a printing in
     * this set", so a unique-card pool would accept off-set preferred printings.
     */
    static List<PaperCard> generateFromCurrentSet(RewardData filter, String setCode, int count, Random rng) {
        List<PaperCard> out = new ArrayList<>();
        if (filter == null || setCode == null || setCode.isEmpty() || count <= 0) {
            return out;
        }
        List<PaperCard> setPool = printingsInSet(setCode);
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
            } else {
                // Keep the set-pool printing when the interim hook cannot pin a match.
                out.add(pc);
            }
        }
        return out;
    }

    /** Every common-card printing from {@code setCode} (basics included). */
    static List<PaperCard> printingsInSet(String setCode) {
        List<PaperCard> out = new ArrayList<>();
        if (setCode == null || setCode.isEmpty()) {
            return out;
        }
        try {
            if (FModel.getMagicDb() == null || FModel.getMagicDb().getCommonCards() == null) {
                return out;
            }
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards()) {
                if (pc != null && setCode.equalsIgnoreCase(pc.getEdition())) {
                    out.add(pc);
                }
            }
        } catch (Throwable ignored) {
            // empty pool
        }
        return out;
    }

    /**
     * Pick up to {@code count} signature cards: theme core names that appear in
     * {@code deckCards}, weighted toward names the player does not own yet.
     */
    public static List<PaperCard> pickSignatures(String themeId, List<PaperCard> deckCards,
            int count, Random rng) {
        List<PaperCard> out = new ArrayList<>();
        if (themeId == null || themeId.isEmpty() || count <= 0 || rng == null) {
            return out;
        }
        EnemyThemeData theme = EnemyThemeDecks.getTheme(themeId);
        if (theme == null) {
            return out;
        }
        EnemyThemeDecks.ensureCoreLoaded(theme);
        if (theme.core == null || theme.core.length == 0) {
            return out;
        }

        Set<String> inDeck = new HashSet<>();
        if (deckCards != null) {
            for (PaperCard pc : deckCards) {
                if (pc != null && pc.getName() != null) {
                    inDeck.add(pc.getName());
                }
            }
        }

        List<String> candidates = new ArrayList<>();
        for (String name : theme.core) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            if (inDeck.isEmpty() || inDeck.contains(name)) {
                candidates.add(name);
            }
        }
        // If the played deck missed every core name (thin fallback deck), still
        // grant from the full core so the signature guarantee holds.
        if (candidates.isEmpty()) {
            for (String name : theme.core) {
                if (name != null && !name.isEmpty()) {
                    candidates.add(name);
                }
            }
        }
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
