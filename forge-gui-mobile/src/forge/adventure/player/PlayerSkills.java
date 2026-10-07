package forge.adventure.player;

import forge.adventure.character.EnemySprite;
import forge.adventure.character.PlayerSprite;
import forge.adventure.data.EnemyData;
import forge.adventure.stage.GameHUD;
import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;

import forge.adventure.data.EffectData;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * RuneScape-style skills: XP per skill, levels 1-99 on the RuneScape XP curve.
 * XP is earned from normal play (duels, new cards, exploring, Spell Smith) and is
 * saved with the character, so it carries over into New Game+.
 */
public class PlayerSkills {
    public static final int MAX_LEVEL = 99;

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
        JEWELCRAFTING("Jewelcrafting");

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

    private final Map<Skill, Integer> xp = new EnumMap<>(Skill.class);

    public static int xpForLevel(int level) {
        return XP_FOR_LEVEL[Math.max(1, Math.min(level, MAX_LEVEL))];
    }

    public static int levelForXp(int totalXp) {
        int level = 1;
        while (level < MAX_LEVEL && totalXp >= XP_FOR_LEVEL[level + 1])
            level++;
        return level;
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
    }

    /** Adds XP and announces it; returns the number of levels gained. */
    public int addXp(Skill skill, int amount) {
        if (amount <= 0 || !forge.adventure.util.Config.ascendant())
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
        announcePerks(skill, before, after);
        List<String> staples = StandardWindow.newlyUnlockedStaples(skill, before, after);
        if (!staples.isEmpty()) {
            notify("[GOLD]New " + skill.displayName + " staple:[] " + String.join(", ", staples)
                    + " (now in shops and Spell Smith, always Standard-legal)");
            forge.adventure.data.RewardData.invalidateCardPool();
        }
        if (skill == Skill.RED)
            AdventurePlayer.current().refreshSkillEffects(); // Red 15 move speed
        if (skill == Skill.EXPLORATION)
            AdventurePlayer.current().refreshSkillEffects(); // move speed changed
        if (skill == Skill.DUELING) {
            // +1 max life at every 10th Dueling level
            int lifeGained = after / 10 - before / 10;
            if (lifeGained > 0) {
                AdventurePlayer.current().addMaxLife(lifeGained);
                notify("Dueling milestone: +" + lifeGained + " max life");
            }
        }
    }

    /** Floating "+N Skill XP" above the player, stacked by skill so simultaneous drops don't overlap. */
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

    /** Duel against a map enemy finished (overworld or dungeon). */
    public void onDuelFinished(boolean won, EnemySprite enemy) {
        if (enemy == null || enemy.getData() == null)
            return;
        EnemyData data = enemy.getData();
        onDuelFinished(won, data.difficulty, data.life, data.boss, AdventurePlayer.current().getSelectedDeck());
    }

    /** Win XP scales with the enemy's difficulty and life; losses earn a quarter. */
    public void onDuelFinished(boolean won, float enemyDifficulty, int enemyLife, boolean boss, Deck playerDeck) {
        float base = 60 + 40 * Math.max(0f, enemyDifficulty) + 3 * Math.max(0, enemyLife);
        if (boss)
            base *= 3;
        int amount = Math.round(won ? base : base * 0.25f);
        addXp(Skill.DUELING, amount);
        awardColorXp(playerDeck, amount);
    }

    /** Splits XP across the color skills by how much of the deck's nonland cards are each color. */
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

    /** A card entered the collection. First copies of a card name pay by rarity; repeats pay a little. */
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

    /** First visit to a point of interest; type comes from its data (town, capital, castle, cave, dungeon...). */
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

    /** A Spell Smith pull was accepted; XP follows what was paid. */
    public void onSpellSmithPaid(int gold, int shards) {
        addXp(Skill.SPELLSMITHING, gold / 5 + shards * 25);
    }

    /** Shop purchase; XP follows gold spent. */
    public void onShopPurchase(int goldSpent) {
        addXp(Skill.BARTERING, Math.max(1, goldSpent / 4));
    }

    /** Cards sold for gold (single or bulk). XP per card plus a share of the gold, so selling chaff still counts. */
    public void onCardsSold(int cardCount, int goldEarned) {
        if (cardCount > 0)
            addXp(Skill.SALVAGING, cardCount * 3 + goldEarned / 2);
    }

    /** Cards salvaged for dust. Same scale as selling: per card plus a share of the dust earned. */
    public void onCardsSalvaged(int cardCount, int dustEarned) {
        if (cardCount > 0)
            addXp(Skill.SALVAGING, cardCount * 3 + dustEarned / 2);
    }

    /** A card was crafted from dust; XP follows dust spent (similar to Spell Smith shard/gold spend). */
    public void onCardCrafted(int dustSpent) {
        if (dustSpent > 0)
            addXp(Skill.SPELLSMITHING, Math.max(1, dustSpent));
    }

    /** Materials refined into dust at Spell Smith; XP follows dust granted. */
    public void onMaterialRefined(int dustGranted) {
        if (dustGranted > 0)
            addXp(Skill.SPELLSMITHING, Math.max(1, dustGranted));
    }

    /** Materials sold for gold (inventory Materials tab). XP shares Bartering with shop sales. */
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
    /** A station recipe was crafted; XP comes from the recipe's {@code xp} field. */
    public void onRecipeCrafted(Skill skill, int xp) {
        if (skill != null && xp > 0)
            addXp(skill, xp);
    }

    // ---- color perks: unlocked at levels 15, 40 and 75 of each color skill ----

    public static final int[] COLOR_PERK_LEVELS = {15, 40, 75};
    private static final Map<Skill, String[]> COLOR_PERKS = new EnumMap<>(Skill.class);

    static {
        COLOR_PERKS.put(Skill.WHITE, new String[]{"+2 starting life in duels", "Start duels with a Food token", "Start duels with a 1/1 Soldier"});
        COLOR_PERKS.put(Skill.BLUE, new String[]{"Spell Smith 15% cheaper", "+1 mana shard each duel", "+1 card in your opening hand"});
        COLOR_PERKS.put(Skill.BLACK, new String[]{"Opponents start with 1 less life", "Start duels with a Clue token", "Opponents start with 1 fewer card"});
        COLOR_PERKS.put(Skill.RED, new String[]{"Walk 5% faster", "Opponents start with 2 less life", "Start duels with a Treasure token"});
        COLOR_PERKS.put(Skill.GREEN, new String[]{"+3 starting life in duels", "+1 bonus card reward after wins", "Start duels with an extra Forest in play"});
    }

    /** Perk descriptions for a color skill, or null for other skills. */
    public static String[] colorPerks(Skill skill) {
        return COLOR_PERKS.get(skill);
    }

    private boolean hasPerk(Skill skill, int tier) {
        return forge.adventure.util.Config.ascendant() && getLevel(skill) >= COLOR_PERK_LEVELS[tier];
    }

    /** Skill-based perks only exist in the Shandalar Ascendant rules. */
    private boolean rulesOn() {
        return forge.adventure.util.Config.ascendant();
    }

    /** Duel-start effects from color perks (life, tokens, mana shards, opening hand, opponent effects). */
    public EffectData duelPerks() {
        if (!rulesOn()) return new EffectData();
        EffectData e = new EffectData();
        EffectData opp = new EffectData();
        List<String> start = new ArrayList<>();
        if (hasPerk(Skill.WHITE, 0)) e.lifeModifier += 2;
        if (hasPerk(Skill.WHITE, 1)) start.add("c_a_food_sac");
        if (hasPerk(Skill.WHITE, 2)) start.add("w_1_1_soldier");
        if (hasPerk(Skill.BLUE, 1)) e.extraManaShards += 1;
        if (hasPerk(Skill.BLUE, 2)) e.changeStartCards += 1;
        if (hasPerk(Skill.BLACK, 0)) opp.lifeModifier -= 1;
        if (hasPerk(Skill.BLACK, 1)) start.add("c_a_clue_draw");
        if (hasPerk(Skill.BLACK, 2)) opp.changeStartCards -= 1;
        if (hasPerk(Skill.RED, 1)) opp.lifeModifier -= 2;
        if (hasPerk(Skill.RED, 2)) start.add("c_a_treasure_sac");
        if (hasPerk(Skill.GREEN, 0)) e.lifeModifier += 3;
        if (hasPerk(Skill.GREEN, 2)) start.add("Forest");
        if (!start.isEmpty())
            e.startBattleWithCard = start.toArray(new String[0]);
        if (opp.lifeModifier != 0 || opp.changeStartCards != 0)
            e.opponent = opp;
        return e;
    }

    /** Extra "deck card" rewards after wins from perks (Green 40). */
    public int bonusRewardCards() {
        if (!rulesOn()) return 0;
        return hasPerk(Skill.GREEN, 1) ? 1 : 0;
    }

    private void announcePerks(Skill skill, int before, int after) {
        String[] perks = COLOR_PERKS.get(skill);
        if (perks == null)
            return;
        for (int i = 0; i < COLOR_PERK_LEVELS.length; i++)
            if (before < COLOR_PERK_LEVELS[i] && after >= COLOR_PERK_LEVELS[i])
                notify("[GOLD]" + skill.displayName + " perk unlocked:[] " + perks[i]);
    }

    // ---- perks ----

    /** Extra free mulligans from Dueling: +1 at level 40, +2 at level 80. */
    public int bonusFreeMulligans() {
        if (!rulesOn()) return 0;
        int level = getLevel(Skill.DUELING);
        return level >= 80 ? 2 : level >= 40 ? 1 : 0;
    }

    /** Price multiplier for Spell Smith pulls: 0.4% cheaper per Spellsmithing level above 1 (about 39% off at 99). */
    public float spellSmithPriceFactor() {
        if (!rulesOn()) return 1f;
        float f = 1f - 0.004f * (getLevel(Skill.SPELLSMITHING) - 1);
        return hasPerk(Skill.BLUE, 0) ? f * 0.85f : f; // Blue 15
    }

    /** Price multiplier for shop purchases: 0.3% cheaper per Bartering level above 1 (about 29% off at 99). */
    public float shopPriceFactor() {
        if (!rulesOn()) return 1f;
        return 1f - 0.003f * (getLevel(Skill.BARTERING) - 1);
    }

    /** Multiplier for card sell prices: 0.5% more per Salvaging level above 1 (about +49% at 99). */
    public float sellPriceFactor() {
        if (!rulesOn()) return 1f;
        return 1f + 0.005f * (getLevel(Skill.SALVAGING) - 1);
    }

    /** Overworld move speed multiplier: 0.25% faster per Exploration level above 1 (about +25% at 99). */
    public float moveSpeedFactor() {
        if (!rulesOn()) return 1f;
        float f = 1f + 0.0025f * (getLevel(Skill.EXPLORATION) - 1);
        return hasPerk(Skill.RED, 0) ? f * 1.05f : f; // Red 15
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
}
