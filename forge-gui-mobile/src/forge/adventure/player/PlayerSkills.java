package forge.adventure.player;

import forge.adventure.character.EnemySprite;
import forge.adventure.character.PlayerSprite;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EffectData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.SkillTreeData;
import forge.adventure.data.SkillTreeListData;
import forge.adventure.data.SkillTreeNodeData;
import forge.adventure.stage.GameHUD;
import forge.adventure.util.Config;
import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RuneScape-style skills: XP per skill, levels 1-99 on the RuneScape XP curve.
 * Ascendant adds talent points and data-driven skill trees (package J).
 */
public class PlayerSkills {
    public static final int MAX_LEVEL = 99;

    /** Legacy color-perk unlock levels (pre-tree). Kept for migration messaging and Unlocks history. */
    public static final int[] COLOR_PERK_LEVELS = {15, 40, 75};

    public enum Skill {
        DUELING("Dueling"),
        WHITE("White Magic"),
        BLUE("Blue Magic"),
        BLACK("Black Magic"),
        RED("Red Magic"),
        GREEN("Green Magic"),
        COLLECTING("Collecting"),
        EXPLORATION("Exploration"),
        SALVAGING("Salvaging"),
        SPELLSMITHING("Spellsmithing"),
        BARTERING("Bartering"),
        // Ascendant gathering (Package B). Crafting skills (E) append after these.
        WOODCUTTING("Woodcutting"),
        MINING("Mining"),
        QUARRYING("Quarrying"),
        FORAGING("Foraging"),
        DELVING("Delving"),
        // Package E crafting skills.
        SMITHING("Smithing"),
        WOODWORKING("Woodworking"),
        ALCHEMY("Alchemy"),
        JEWELCRAFTING("Jewelcrafting"),
        // Package FT1 fortresses.
        CONSTRUCTION("Construction");

        public final String displayName;

        Skill(String displayName) {
            this.displayName = displayName;
        }

        /** Match materials.json {@code skill} strings (display name or enum name). */
        public static Skill fromMaterialSkill(String name) {
            return fromName(name);
        }

        /** Resolve by enum name or display name; unknown → null. */
        public static Skill fromName(String name) {
            if (name == null || name.isEmpty())
                return null;
            try {
                return Skill.valueOf(name.trim().toUpperCase().replace(' ', '_'));
            } catch (IllegalArgumentException ignored) {
                // fall through to display-name match
            }
            for (Skill s : values()) {
                if (s.displayName.equalsIgnoreCase(name.trim()))
                    return s;
            }
            return null;
        }
    }

    /** XP needed to reach each level; index = level. RuneScape formula. */
    private static final int[] XP_FOR_LEVEL = new int[MAX_LEVEL + 2];

    static {
        double points = 0;
        XP_FOR_LEVEL[1] = 0;
        for (int level = 1; level <= MAX_LEVEL; level++) {
            points += Math.floor(level + 300 * Math.pow(2, level / 7.0));
            XP_FOR_LEVEL[level + 1] = (int) Math.floor(points / 4);
        }
    }

    private static final Skill[] COLOR_SKILLS = {Skill.WHITE, Skill.BLUE, Skill.BLACK, Skill.RED, Skill.GREEN};
    private static final byte[] COLORS = {MagicColor.WHITE, MagicColor.BLUE, MagicColor.BLACK, MagicColor.RED, MagicColor.GREEN};

    /** Descriptions of the retired flat color perks (for Unlocks / migration copy). */
    private static final Map<Skill, String[]> LEGACY_COLOR_PERKS = new EnumMap<>(Skill.class);

    static {
        LEGACY_COLOR_PERKS.put(Skill.WHITE, new String[]{"+2 starting life in duels", "Start duels with a Food token", "Start duels with a 1/1 Soldier"});
        LEGACY_COLOR_PERKS.put(Skill.BLUE, new String[]{"Spell Smith 15% cheaper", "+1 mana shard each duel", "+1 card in your opening hand"});
        LEGACY_COLOR_PERKS.put(Skill.BLACK, new String[]{"Opponents start with 1 less life", "Start duels with a Clue token", "Opponents start with 1 fewer card"});
        LEGACY_COLOR_PERKS.put(Skill.RED, new String[]{"Walk 5% faster", "Opponents start with 2 less life", "Start duels with a Treasure token"});
        LEGACY_COLOR_PERKS.put(Skill.GREEN, new String[]{"+3 starting life in duels", "+1 bonus card reward after wins", "Start duels with an extra Forest in play"});
    }

    private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);
    /** Purchased talent ranks: node id → ranks. */
    private final Map<String, Integer> nodeRanks = new HashMap<>();
    /** Slotted duel-perk node ids (order preserved). */
    private final List<String> slottedPerks = new ArrayList<>();
    private final Set<Skill> skillCapes = new HashSet<>();
    private int respecCount;
    private boolean freeRespecPending;
    private boolean colorPerksRefunded;

    public static int xpForLevel(int level) {
        return XP_FOR_LEVEL[Math.max(1, Math.min(level, MAX_LEVEL))];
    }

    public static int levelForXp(int totalXp) {
        int level = 1;
        while (level < MAX_LEVEL && totalXp >= XP_FOR_LEVEL[level + 1])
            level++;
        return level;
    }

    /**
     * Talent points earned from reaching {@code level}.
     * 2 points at every multiple of 10; 1 point at other multiples of 5. 28 by level 99.
     */
    public static int talentPointsForLevel(int level) {
        int points = 0;
        int capped = Math.max(1, Math.min(level, MAX_LEVEL));
        for (int lv = 5; lv <= capped; lv += 5) {
            if (lv % 10 == 0)
                points += 2;
            else
                points += 1;
        }
        return points;
    }

    /** Legacy flat color perk lines, or null for non-color skills. */
    public static String[] colorPerks(Skill skill) {
        return LEGACY_COLOR_PERKS.get(skill);
    }

    public int getXp(Skill skill) {
        return xp.getOrDefault(skill, 0);
    }

    public int getLevel(Skill skill) {
        return levelForXp(getXp(skill));
    }

    public int getTotalLevel() {
        int total = 0;
        for (Skill skill : Skill.values())
            total += getLevel(skill);
        return total;
    }

    public void clear() {
        xp.clear();
        nodeRanks.clear();
        slottedPerks.clear();
        skillCapes.clear();
        respecCount = 0;
        freeRespecPending = false;
        colorPerksRefunded = false;
    }

    /** Adds XP and announces it; returns the number of levels gained. */
    public int addXp(Skill skill, int amount) {
        if (amount <= 0 || !Config.ascendant())
            return 0;
        int before = getLevel(skill);
        xp.put(skill, Math.min(getXp(skill) + amount, 200_000_000));
        int after = getLevel(skill);
        showXpDrop(skill, amount);
        if (after > before) {
            notify("[GOLD]" + skill.displayName + " level " + after + "![]");
            onLevelUp(skill, before, after);
        }
        return after - before;
    }

    private void onLevelUp(Skill skill, int before, int after) {
        int gained = talentPointsForLevel(after) - talentPointsForLevel(before);
        if (gained > 0)
            notify("[GOLD]" + skill.displayName + ":[] +" + gained + " talent point" + (gained > 1 ? "s" : "")
                    + " (Skills → Tree)");
        List<String> staples = StandardWindow.newlyUnlockedStaples(skill, before, after);
        if (!staples.isEmpty()) {
            notify("[GOLD]New " + skill.displayName + " staple:[] " + String.join(", ", staples)
                    + " (now in shops and Spell Smith, always Standard-legal)");
            forge.adventure.data.RewardData.invalidateCardPool();
        }
        if (skill == Skill.EXPLORATION || skill == Skill.RED)
            AdventurePlayer.current().refreshSkillEffects();
        if (skill == Skill.DUELING) {
            int lifeGained = after / 10 - before / 10;
            if (lifeGained > 0) {
                AdventurePlayer.current().addMaxLife(lifeGained);
                notify("Dueling milestone: +" + lifeGained + " max life");
            }
        }
        // AC1: skill-level achievements (Ascendant only; account-wide; local in co-op).
        if (Config.ascendant()) {
            try {
                AchievementService.get().evaluatePlayer(AdventurePlayer.current());
            } catch (Throwable ignored) {
            }
        }
    }

    private static void showXpDrop(Skill skill, int amount) {
        try {
            AdventurePlayer player = AdventurePlayer.current();
            PlayerSprite sprite = player.getCurrentGameStage().getPlayerSprite();
            if (sprite == null)
                return;
            player.addStatusMessage(null, skill.displayName + " XP", amount,
                    sprite.getX(), sprite.getY() + sprite.getHeight() + skill.ordinal() * 8);
        } catch (Exception ignored) {
            // no game stage yet (e.g. during world generation)
        }
    }

    private static void notify(String text) {
        try {
            GameHUD.getInstance().addNotification(text);
        } catch (Exception ignored) {
            // HUD not available (e.g. during world generation); the level still counts
        }
    }

    // ---- XP sources ----

    public void onDuelFinished(boolean won, EnemySprite enemy) {
        if (enemy == null || enemy.getData() == null)
            return;
        EnemyData data = enemy.getData();
        onDuelFinished(won, data.difficulty, data.life, data.boss, AdventurePlayer.current().getSelectedDeck());
    }

    public void onDuelFinished(boolean won, float enemyDifficulty, int enemyLife, boolean boss, Deck playerDeck) {
        float base = 60 + 40 * Math.max(0f, enemyDifficulty) + 3 * Math.max(0, enemyLife);
        if (boss)
            base *= 3;
        int amount = Math.round(won ? base : base * 0.25f);
        addXp(Skill.DUELING, amount);
        awardColorXp(playerDeck, amount);
    }

    private void awardColorXp(Deck deck, int amount) {
        if (deck == null)
            return;
        int[] counts = new int[COLORS.length];
        int total = 0;
        for (Map.Entry<PaperCard, Integer> entry : deck.getOrCreate(DeckSection.Main)) {
            if (entry.getKey().getRules().getType().isLand())
                continue;
            ColorSet color = entry.getKey().getRules().getColor();
            for (int i = 0; i < COLORS.length; i++) {
                if (color.hasAnyColor(COLORS[i])) {
                    counts[i] += entry.getValue();
                    total += entry.getValue();
                }
            }
        }
        if (total == 0)
            return;
        for (int i = 0; i < COLORS.length; i++) {
            if (counts[i] > 0)
                addXp(COLOR_SKILLS[i], Math.max(1, Math.round(amount * (float) counts[i] / total)));
        }
    }

    public void onCardCollected(PaperCard card, boolean firstCopy) {
        int amount;
        if (!firstCopy) {
            amount = 2;
        } else {
            CardRarity rarity = card.getRarity();
            if (rarity == CardRarity.MythicRare)
                amount = 400;
            else if (rarity == CardRarity.Rare)
                amount = 200;
            else if (rarity == CardRarity.Uncommon)
                amount = 80;
            else if (rarity == CardRarity.BasicLand)
                amount = 5;
            else
                amount = 40;
        }
        addXp(Skill.COLLECTING, amount);
    }

    public void onPlaceDiscovered(String type) {
        int amount;
        if (type == null)
            amount = 300;
        else if (type.equals("capital") || type.equals("castle"))
            amount = 1000;
        else if (type.startsWith("sideboss"))
            amount = 800;
        else if (type.equals("dungeon") || type.equals("cave"))
            amount = 400;
        else
            amount = 300;
        addXp(Skill.EXPLORATION, amount);
    }

    public void onSpellSmithPaid(int gold, int shards) {
        addXp(Skill.SPELLSMITHING, gold / 5 + shards * 25);
    }

    public void onShopPurchase(int goldSpent) {
        addXp(Skill.BARTERING, Math.max(1, goldSpent / 4));
    }

    public void onCardsSold(int cardCount, int goldEarned) {
        if (cardCount > 0)
            addXp(Skill.SALVAGING, cardCount * 3 + goldEarned / 2);
    }

    public void onCardsSalvaged(int cardCount, int dustEarned) {
        if (cardCount > 0)
            addXp(Skill.SALVAGING, cardCount * 3 + dustEarned / 2);
    }

    public void onCardCrafted(int dustSpent) {
        if (dustSpent > 0)
            addXp(Skill.SPELLSMITHING, Math.max(1, dustSpent));
    }

    public void onMaterialRefined(int dustGranted) {
        if (dustGranted > 0)
            addXp(Skill.SPELLSMITHING, Math.max(1, dustGranted));
    }

    public void onMaterialsSold(int unitCount, int goldEarned) {
        if (unitCount > 0)
            addXp(Skill.BARTERING, unitCount * 2 + Math.max(0, goldEarned) / 4);
    }

    /** Overworld node gather (Package B). XP is materials.json {@code xp} × units gathered. */
    public void onMaterialGathered(Skill skill, int xpAmount) {
        if (skill != null && xpAmount > 0)
            addXp(skill, xpAmount);
    }

    /**
     * Gathering channel duration factor: 1 at level 1, down toward {@code minFactor} at 99.
     * Used by WorldStage; keep logic here so skill-tree work (J) can hook the same call site.
     */
    public float gatherChannelFactor(Skill skill, float minFactor) {
        if (!rulesOn() || skill == null)
            return 1f;
        float t = (getLevel(skill) - 1) / (float) (MAX_LEVEL - 1);
        return 1f - (1f - minFactor) * Math.max(0f, Math.min(1f, t));
    }

    /** A station recipe was crafted; XP comes from the recipe's {@code xp} field. */
    public void onRecipeCrafted(Skill skill, int xp) {
        if (skill != null && xp > 0)
            addXp(skill, xp);
    }

    // ---- talent points / trees ----

    private boolean rulesOn() {
        return Config.ascendant();
    }

    public int talentPointsEarned(Skill skill) {
        return talentPointsForLevel(getLevel(skill));
    }

    public int talentPointsSpent(Skill skill) {
        if (!rulesOn())
            return 0;
        SkillTreeData tree = SkillTreeListData.get(skill);
        if (tree.isEmpty())
            return 0;
        int spent = 0;
        for (SkillTreeNodeData n : tree.nodes) {
            if (n == null || n.id == null)
                continue;
            int ranks = nodeRanks.getOrDefault(n.id, 0);
            if (ranks > 0)
                spent += ranks * Math.max(1, n.cost);
        }
        return spent;
    }

    public int talentPointsAvailable(Skill skill) {
        return Math.max(0, talentPointsEarned(skill) - talentPointsSpent(skill));
    }

    public int getNodeRanks(String nodeId) {
        return nodeRanks.getOrDefault(nodeId, 0);
    }

    public boolean hasSkillCape(Skill skill) {
        return skillCapes.contains(skill);
    }

    public List<String> getSlottedPerks() {
        return new ArrayList<>(slottedPerks);
    }

    public int perkSlotCount() {
        if (!rulesOn())
            return 0;
        ConfigData cfg = Config.instance().getConfigData();
        int base = Math.max(0, cfg.perkSlotsBase);
        int max = Math.max(base, cfg.perkSlotsMax);
        int step = Math.max(1, cfg.perkSlotTotalLevelsPerSlot);
        int bonus = 0;
        try {
            AdventurePlayer ap = AdventurePlayer.current();
            if (ap != null)
                bonus = Math.max(0, ap.getDuelPerkSlotBonus());
        } catch (Exception ignored) {
            // Player may be unavailable in tests
        }
        return Math.min(max + bonus, base + getTotalLevel() / step + bonus);
    }

    public int respecCostGold() {
        if (!rulesOn())
            return 0;
        if (freeRespecPending)
            return 0;
        ConfigData cfg = Config.instance().getConfigData();
        return Math.max(0, cfg.respecBaseGold + respecCount * cfg.respecGoldIncrement);
    }

    public int getRespecCount() {
        return respecCount;
    }

    public boolean isFreeRespecPending() {
        return freeRespecPending;
    }

    public boolean isColorPerksRefunded() {
        return colorPerksRefunded;
    }

    /** Called when New Game+ starts so the next respec is free. */
    public void grantFreeRespec() {
        freeRespecPending = true;
    }

    /**
     * Old saves used automatic color perks at 15/40/75. Those no longer apply; talent points from
     * level are available unspent. Returns true the first time migration runs for this character.
     */
    public boolean migrateLegacyColorPerksIfNeeded(boolean hadLegacySkillXp) {
        if (!rulesOn() || colorPerksRefunded)
            return false;
        colorPerksRefunded = true;
        // Old flat color perks no longer auto-apply; talent points from level are unspent.
        if (!hadLegacySkillXp)
            return false;
        int refundHint = 0;
        for (Skill color : COLOR_SKILLS) {
            int level = getLevel(color);
            for (int perkLevel : COLOR_PERK_LEVELS) {
                if (level >= perkLevel)
                    refundHint++;
            }
        }
        notify("[GOLD]Color perks refunded[] as talent points"
                + (refundHint > 0 ? " (" + refundHint + " former perk" + (refundHint > 1 ? "s" : "") + ")" : "")
                + ". Open Skills → click a skill to spend them.");
        return true;
    }

    public String canBuyRank(Skill skill, String nodeId) {
        if (!rulesOn())
            return "Skill trees are only available in Shandalar Ascendant.";
        SkillTreeData tree = SkillTreeListData.get(skill);
        SkillTreeNodeData node = tree.findNode(nodeId);
        if (node == null)
            return "Unknown talent.";
        if (getLevel(skill) < node.levelRequired)
            return "Requires " + skill.displayName + " level " + node.levelRequired + ".";
        int ranks = getNodeRanks(nodeId);
        if (ranks >= Math.max(1, node.maxRanks))
            return "Already at max ranks.";
        if (talentPointsAvailable(skill) < Math.max(1, node.cost))
            return "Not enough talent points.";
        if (node.requires != null) {
            for (String req : node.requires) {
                if (getNodeRanks(req) < 1)
                    return "Requires another talent first.";
            }
        }
        if (node.exclusiveWith != null) {
            for (String ex : node.exclusiveWith) {
                if (getNodeRanks(ex) > 0)
                    return "Conflicts with another choice talent.";
            }
        }
        return null;
    }

    public boolean buyRank(Skill skill, String nodeId) {
        String err = canBuyRank(skill, nodeId);
        if (err != null)
            return false;
        SkillTreeNodeData node = SkillTreeListData.get(skill).findNode(nodeId);
        nodeRanks.put(nodeId, getNodeRanks(nodeId) + 1);
        if (node.skillCape)
            skillCapes.add(skill);
        if (node.duelPerk && !slottedPerks.contains(nodeId) && slottedPerks.size() < perkSlotCount())
            slottedPerks.add(nodeId);
        AdventurePlayer.current().refreshSkillEffects();
        notify("[GOLD]" + skill.displayName + " talent:[] " + node.displayDescription());
        return true;
    }

    public boolean isPerkSlotted(String nodeId) {
        return slottedPerks.contains(nodeId);
    }

    public String canSlotPerk(String nodeId) {
        if (!rulesOn())
            return "Skill trees are only available in Shandalar Ascendant.";
        SkillTreeNodeData node = SkillTreeListData.findNode(nodeId);
        if (node == null || !node.duelPerk)
            return "Not a duel perk.";
        if (getNodeRanks(nodeId) < 1)
            return "Talent not purchased.";
        if (slottedPerks.contains(nodeId))
            return "Already slotted.";
        if (slottedPerks.size() >= perkSlotCount())
            return "No free perk slots.";
        return null;
    }

    public boolean slotPerk(String nodeId) {
        if (canSlotPerk(nodeId) != null)
            return false;
        slottedPerks.add(nodeId);
        return true;
    }

    public boolean unslotPerk(String nodeId) {
        return slottedPerks.remove(nodeId);
    }

    /**
     * Refunds all spent points in {@code skill}'s tree and clears its slotted duel perks.
     * Charges gold unless a free New Game+ respec is pending.
     */
    public String respec(Skill skill) {
        if (!rulesOn())
            return "Skill trees are only available in Shandalar Ascendant.";
        int spent = talentPointsSpent(skill);
        if (spent <= 0 && !hasSkillCape(skill))
            return "Nothing to respec.";
        int cost = respecCostGold();
        AdventurePlayer player = AdventurePlayer.current();
        if (cost > 0 && player.getGold() < cost)
            return "Need " + cost + " gold.";
        SkillTreeData tree = SkillTreeListData.get(skill);
        Set<String> ids = new HashSet<>();
        if (!tree.isEmpty()) {
            for (SkillTreeNodeData n : tree.nodes) {
                if (n != null && n.id != null)
                    ids.add(n.id);
            }
        }
        nodeRanks.keySet().removeAll(ids);
        slottedPerks.removeIf(ids::contains);
        skillCapes.remove(skill);
        if (freeRespecPending) {
            freeRespecPending = false;
        } else {
            if (cost > 0)
                player.takeGold(cost);
            respecCount++;
        }
        player.refreshSkillEffects();
        notify("[GOLD]" + skill.displayName + " talents reset.[]"
                + (cost > 0 ? " (-" + cost + " gold)" : " (free)"));
        return null;
    }

    // ---- aggregated effects from trees ----

    private void forEachActiveRank(boolean duelOnly, RankConsumer consumer) {
        if (!rulesOn())
            return;
        for (Map.Entry<String, Integer> e : nodeRanks.entrySet()) {
            if (e.getValue() == null || e.getValue() < 1)
                continue;
            SkillTreeNodeData node = SkillTreeListData.findNode(e.getKey());
            if (node == null)
                continue;
            if (node.duelPerk) {
                if (!duelOnly)
                    continue;
                if (!slottedPerks.contains(node.id))
                    continue;
            } else if (duelOnly) {
                continue;
            }
            consumer.accept(node, e.getValue());
        }
    }

    @FunctionalInterface
    private interface RankConsumer {
        void accept(SkillTreeNodeData node, int ranks);
    }

    private static void applyEffectScaled(EffectData dest, EffectData src, int ranks) {
        if (src == null || ranks < 1)
            return;
        dest.lifeModifier += src.lifeModifier * ranks;
        dest.changeStartCards += src.changeStartCards * ranks;
        dest.extraManaShards += src.extraManaShards * ranks;
        dest.freeMulligans += src.freeMulligans * ranks;
        dest.cardRewardBonus += src.cardRewardBonus * ranks;
        if (src.colorView)
            dest.colorView = true;
        if (src.moveSpeed != 0 && src.moveSpeed != 1f) {
            float factor = 1f;
            for (int i = 0; i < ranks; i++)
                factor *= src.moveSpeed;
            dest.moveSpeed = (dest.moveSpeed == 0f ? 1f : dest.moveSpeed) * factor;
        }
        if (src.goldModifier > 0f) {
            float factor = 1f;
            for (int i = 0; i < ranks; i++)
                factor *= src.goldModifier;
            if (dest.goldModifier <= 0f)
                dest.goldModifier = factor;
            else
                dest.goldModifier *= factor;
        }
        if (src.startBattleWithCard != null) {
            List<String> cards = new ArrayList<>();
            if (dest.startBattleWithCard != null)
                cards.addAll(Arrays.asList(dest.startBattleWithCard));
            for (int i = 0; i < ranks; i++)
                cards.addAll(Arrays.asList(src.startBattleWithCard));
            dest.startBattleWithCard = cards.toArray(new String[0]);
        }
        if (src.startBattleWithCardInCommandZone != null) {
            List<String> cards = new ArrayList<>();
            if (dest.startBattleWithCardInCommandZone != null)
                cards.addAll(Arrays.asList(dest.startBattleWithCardInCommandZone));
            for (int i = 0; i < ranks; i++)
                cards.addAll(Arrays.asList(src.startBattleWithCardInCommandZone));
            dest.startBattleWithCardInCommandZone = cards.toArray(new String[0]);
        }
        if (src.opponent != null) {
            if (dest.opponent == null)
                dest.opponent = new EffectData();
            applyEffectScaled(dest.opponent, src.opponent, ranks);
        }
    }

    /** Duel-start effects from slotted duel perks. */
    public EffectData duelPerks() {
        EffectData e = new EffectData();
        e.moveSpeed = 1f;
        e.goldModifier = -1f;
        if (!rulesOn())
            return e;
        forEachActiveRank(true, (node, ranks) -> applyEffectScaled(e, node.effect, ranks));
        if (e.goldModifier <= 0f)
            e.goldModifier = -1f;
        return e;
    }

    /** Non-duel passives always on (move speed, shop discounts, card rewards, Spell Smith). */
    private EffectData passiveTreeEffects() {
        EffectData e = new EffectData();
        e.moveSpeed = 1f;
        e.goldModifier = 1f;
        if (!rulesOn())
            return e;
        forEachActiveRank(false, (node, ranks) -> applyEffectScaled(e, node.effect, ranks));
        return e;
    }

    public int bonusRewardCards() {
        if (!rulesOn())
            return 0;
        int bonus = 0;
        EffectData passive = passiveTreeEffects();
        bonus += Math.max(0, passive.cardRewardBonus);
        // Slotted duel perks can also grant cardRewardBonus
        EffectData duel = duelPerks();
        bonus += Math.max(0, duel.cardRewardBonus);
        return bonus;
    }

    public int bonusFreeMulligans() {
        if (!rulesOn())
            return 0;
        // Base Dueling milestones retained; tree mulligans come through duelPerks().freeMulligans in DuelScene.
        int level = getLevel(Skill.DUELING);
        return level >= 80 ? 2 : level >= 40 ? 1 : 0;
    }

    public float spellSmithPriceFactor() {
        if (!rulesOn())
            return 1f;
        float f = 1f - 0.004f * (getLevel(Skill.SPELLSMITHING) - 1);
        final float[] tree = {1f};
        forEachActiveRank(false, (node, ranks) -> {
            if (node.spellSmithPriceFactor != 0f && node.spellSmithPriceFactor != 1f) {
                for (int i = 0; i < ranks; i++)
                    tree[0] *= node.spellSmithPriceFactor;
            }
        });
        return f * tree[0];
    }

    public float shopPriceFactor() {
        if (!rulesOn())
            return 1f;
        float f = 1f - 0.003f * (getLevel(Skill.BARTERING) - 1);
        EffectData passive = passiveTreeEffects();
        if (passive.goldModifier > 0f)
            f *= passive.goldModifier;
        return f;
    }

    public float sellPriceFactor() {
        if (!rulesOn())
            return 1f;
        return 1f + 0.005f * (getLevel(Skill.SALVAGING) - 1);
    }

    public float moveSpeedFactor() {
        if (!rulesOn())
            return 1f;
        float f = 1f + 0.0025f * (getLevel(Skill.EXPLORATION) - 1);
        EffectData passive = passiveTreeEffects();
        if (passive.moveSpeed > 0f)
            f *= passive.moveSpeed;
        return f;
    }

    /** Purchased duel-perk nodes for a skill (for the tree UI). */
    public List<SkillTreeNodeData> purchasedDuelPerks(Skill skill) {
        List<SkillTreeNodeData> out = new ArrayList<>();
        SkillTreeData tree = SkillTreeListData.get(skill);
        if (tree.isEmpty())
            return out;
        for (SkillTreeNodeData n : tree.nodes) {
            if (n != null && n.duelPerk && getNodeRanks(n.id) > 0)
                out.add(n);
        }
        return out;
    }

    /** True when Exploration is high enough for Ascendant waypoint travel between visited towns. */
    public boolean canUseWaypointTravel() {
        if (!rulesOn()) return false;
        int need = Config.instance().getConfigData().waypointTravelUnlockLevel;
        return getLevel(Skill.EXPLORATION) >= Math.max(1, need);
    }

    /**
     * Gold cost to waypoint-travel {@code distanceTiles} tiles. Applies Exploration discount
     * up to {@code waypointTravelDiscountMax} at level 99.
     */
    public int waypointTravelCost(float distanceTiles) {
        if (!rulesOn()) return Integer.MAX_VALUE;
        ConfigData cfg = Config.instance().getConfigData();
        float raw = cfg.waypointTravelBaseCost + Math.max(0f, distanceTiles) * cfg.waypointTravelCostPerTile;
        float t = (getLevel(Skill.EXPLORATION) - 1) / 98f;
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        float discount = Math.max(0f, Math.min(1f, cfg.waypointTravelDiscountMax)) * t;
        return Math.max(1, Math.round(raw * (1f - discount)));
    }

    // ---- save/load ----

    public String[] saveKeys() {
        String[] keys = new String[xp.size()];
        int i = 0;
        for (Skill skill : xp.keySet())
            keys[i++] = skill.name();
        return keys;
    }

    public Integer[] saveValues() {
        return xp.values().toArray(new Integer[0]);
    }

    public void load(String[] keys, Integer[] values) {
        xp.clear();
        if (keys == null || values == null)
            return;
        for (int i = 0; i < keys.length && i < values.length; i++) {
            try {
                xp.put(Skill.valueOf(keys[i]), values[i]);
            } catch (IllegalArgumentException ignored) {
                // skill from a newer/older version; skip
            }
        }
    }

    public String[] saveTreeNodeIds() {
        return nodeRanks.keySet().toArray(new String[0]);
    }

    public Integer[] saveTreeNodeRanks() {
        String[] ids = saveTreeNodeIds();
        Integer[] ranks = new Integer[ids.length];
        for (int i = 0; i < ids.length; i++)
            ranks[i] = nodeRanks.get(ids[i]);
        return ranks;
    }

    public String[] saveSlottedPerks() {
        return slottedPerks.toArray(new String[0]);
    }

    public String[] saveSkillCapes() {
        String[] out = new String[skillCapes.size()];
        int i = 0;
        for (Skill s : skillCapes)
            out[i++] = s.name();
        return out;
    }

    public void loadTree(String[] nodeIds, Integer[] ranks, String[] slotted, String[] capes,
                         Integer respec, Boolean freeRespec, Boolean refunded) {
        nodeRanks.clear();
        slottedPerks.clear();
        skillCapes.clear();
        if (nodeIds != null && ranks != null) {
            for (int i = 0; i < nodeIds.length && i < ranks.length; i++) {
                if (nodeIds[i] != null && ranks[i] != null && ranks[i] > 0)
                    nodeRanks.put(nodeIds[i], ranks[i]);
            }
        }
        if (slotted != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (String id : slotted) {
                // Drop perks whose node was removed from the data, so they can't hold a slot forever.
                if (id != null && getNodeRanks(id) > 0 && SkillTreeListData.findNode(id) != null && seen.add(id))
                    slottedPerks.add(id);
            }
        }
        if (capes != null) {
            for (String name : capes) {
                try {
                    skillCapes.add(Skill.valueOf(name));
                } catch (Exception ignored) {
                    // unknown skill from future save
                }
            }
        }
        respecCount = respec != null ? Math.max(0, respec) : 0;
        freeRespecPending = freeRespec != null && freeRespec;
        colorPerksRefunded = refunded != null && refunded;
    }
}
