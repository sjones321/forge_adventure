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

import java.util.EnumMap;
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
        BARTERING("Bartering");

        public final String displayName;

        Skill(String displayName) {
            this.displayName = displayName;
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
        if (amount <= 0)
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

    // ---- perks ----

    /** Extra free mulligans from Dueling: +1 at level 40, +2 at level 80. */
    public int bonusFreeMulligans() {
        int level = getLevel(Skill.DUELING);
        return level >= 80 ? 2 : level >= 40 ? 1 : 0;
    }

    /** Price multiplier for Spell Smith pulls: 0.4% cheaper per Spellsmithing level above 1 (about 39% off at 99). */
    public float spellSmithPriceFactor() {
        return 1f - 0.004f * (getLevel(Skill.SPELLSMITHING) - 1);
    }

    /** Price multiplier for shop purchases: 0.3% cheaper per Bartering level above 1 (about 29% off at 99). */
    public float shopPriceFactor() {
        return 1f - 0.003f * (getLevel(Skill.BARTERING) - 1);
    }

    /** Multiplier for card sell prices: 0.5% more per Salvaging level above 1 (about +49% at 99). */
    public float sellPriceFactor() {
        return 1f + 0.005f * (getLevel(Skill.SALVAGING) - 1);
    }

    /** Overworld move speed multiplier: 0.25% faster per Exploration level above 1 (about +25% at 99). */
    public float moveSpeedFactor() {
        return 1f + 0.0025f * (getLevel(Skill.EXPLORATION) - 1);
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
