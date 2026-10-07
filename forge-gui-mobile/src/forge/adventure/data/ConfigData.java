package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * Data class that will be used to read Json configuration files
 * BiomeData
 * contains general information about the game
 */
public class ConfigData {
    public int screenWidth;
    public int screenHeight;
    public String skin;
    public String font;
    public String fontColor;
    public int minDeckSize;
    public int maxNumberOfDecks;
    public float playerBaseSpeed;
    public String[] colorIds;
    public String[] colorIdNames;
    public String[] starterEditions;
    public String[] starterEditionNames;
    public ObjectMap<String, ObjectMap<String, String>> starterDecksByEdition;
    public DifficultyData[] difficulties;
    public RewardData legalCards;
    public String[] restrictedCards;
    public String[] restrictedEditions;
    public String[] restrictedBlocks;
    public String[] restrictedTokens;
    public String[] allowedEditions;
    public boolean vintageOnlyEditions = false;
    public String[] restrictedEvents;
    public String[] allowedEvents;
    public String[] allowedJumpstart;
    public String defaultBasicLandSet = "JMP";
    public boolean enableGeneticAI = true;
    public String chaosDeckFormat;
    public boolean usePriceListPrices = true;

    /**
     * When false (default), Adventure shops cannot be restocked with shards.
     * Existing saves still load; shops simply stop offering a restock button.
     */
    public boolean enableShopRestock = false;

    /**
     * When false (default), map objects of type "Rotating" keep a stable shop
     * identity for the save instead of rotating daily (mystery shop).
     */
    public boolean enableRotatingShops = false;

    /**
     * AI profile used by Adventure enemies when EnemyData.ai is empty.
     * Constructed / lobby games are unaffected.
     */
    public String defaultAdventureAiProfile = "Adventure";

    /**
     * Sealed start: boosters of the chosen set given at new game. The first
     * sealedStartOpenedPacks are opened to auto-build the starting deck (all of
     * those cards go to the collection); the rest stay unopened in inventory.
     */
    public int sealedStartPacks = 10;
    public int sealedStartOpenedPacks = 5;
    /** Sealed start also opens packs of a core set chosen on the New Game screen (counts above apply per set). */
    public String[] coreSets = {"CORE", "FDN", "M21", "M20", "M19", "ORI", "M15", "M14", "M13", "M12", "M11", "M10"};
    /** "CORE" = Core Set Collection: custom packs drawing from all of these core sets. */
    public String[] coreCollectionSets = {"FDN", "M21", "M20", "M19", "ORI", "M15", "M14", "M13", "M12", "M11", "M10"};
    /**
     * Shandalar Ascendant rules: sealed start, skills and perks, rotating Standard and staples, vaults
     * and per-deck formats. Off for the stock worlds; turned on in the plane's own config.json.
     */
    public boolean ascendantRules = false;

    /**
     * Ascendant enemy tuning. Enemy starting life is multiplied by a factor that ramps with the player's
     * Dueling level: enemyLifeEarly at level 1, 1.0 at enemyLifeNormalLevel, enemyLifeLate at enemyLifeLateLevel
     * (bosses never go below 1.0). Commander duels add commanderEnemyLifeFactor and extra opening cards.
     */
    public float enemyLifeEarly = 0.75f;
    public int enemyLifeNormalLevel = 30;
    public float enemyLifeLate = 1.25f;
    public int enemyLifeLateLevel = 60;
    public float commanderEnemyLifeFactor = 1.5f;
    public int commanderEnemyExtraCards = 1;

    /** Adventure house rule: mulligans that cost no card, for both the player and enemies (Ascendant sets 1). */
    public int adventureFreeMulligans = 0;

    /** Extra gold on top of the difficulty's starting money for a sealed start. */
    public int sealedStartBonusGold = 500;

    // ---- Ascendant crafting (Phase 1): dust salvage / craft. Tunables only; stock worlds leave defaults unused. ----

    /** Dust granted per salvaged copy at Salvaging level 1. */
    public int salvageDust = 10;
    /** Dust granted per salvaged copy at Salvaging level 99 (linear between). */
    public int salvageDustMax = 20;
    /** Base dust cost to craft one copy at Spellsmithing level 1 (Standard-legal). */
    public int craftCost = 50;
    /** Multiplier on craft cost when the card is not Standard-legal right now (rotated / historic). */
    public float historicCraftFactor = 2f;
    /** Max Spellsmithing craft-cost discount at level 99 (0.25 = 25% off). */
    public float craftDiscountMax = 0.25f;
    /** Common dust granted on each duel win (Ascendant only). */
    public int duelWinDustCommon = 2;
    /** Rare dust granted on boss duel wins in addition to common dust (Ascendant only). */
    public int duelWinDustBossRare = 1;
    /** Owned copies kept before auto-salvage destroys the rest (Ascendant toggle; vaulted/deck copies protected). */
    public int autoSalvageKeepCopies = 4;

    // ---- Ascendant materials (Package A): inventory, enemy drops, Spellsmithing refine. ----

    /**
     * Refine-to-dust yield multiplier at Spellsmithing level 1.
     * Final dust = materials.json dustRefine.amount × lerp(refineDustBase, refineDustMax, skill).
     */
    public float refineDustBase = 1f;
    /** Refine-to-dust yield multiplier at Spellsmithing level 99. */
    public float refineDustMax = 2f;
    /** Chance (0-1) a non-boss enemy drops a color-keyed material on win (Ascendant only). */
    public float enemyMaterialDropChance = 0.4f;
    /** Units granted when a non-boss material drop succeeds. */
    public int enemyMaterialDropCount = 1;

    // ---- Ascendant skill trees (Package J): talent points, duel perk slots, respec. ----

    /** Duel perk slots at total skill level 0 (Ascendant only). */
    public int perkSlotsBase = 3;
    /** Hard cap on duel perk slots. */
    public int perkSlotsMax = 10;
    /** Total skill levels needed for each extra duel perk slot above the base. */
    public int perkSlotTotalLevelsPerSlot = 80;
    /** Gold cost of the first paid skill-tree respec. */
    public int respecBaseGold = 500;
    /** Extra gold added to the respec cost for each prior respec. */
    public int respecGoldIncrement = 500;

}
