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

}
