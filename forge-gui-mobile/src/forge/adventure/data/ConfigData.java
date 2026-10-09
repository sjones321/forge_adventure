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

    // ---- Ascendant gathering (Package B): overworld nodes, channel, tools (color reagent lines). ----

    /** Seconds between resource-node spawn attempts on the overworld. */
    public float gatherNodeSpawnInterval = 5f;
    /** Max resource nodes alive near the player at once. */
    public int gatherNodeMaxAlive = 4;
    /** Node lifetime in seconds (longer than enemy ~20s). */
    public float gatherNodeLifetime = 60f;
    /** Channel duration at gathering skill level 1 (seconds). */
    public float gatherChannelMax = 3f;
    /** Channel duration at gathering skill level 99 (seconds). */
    public float gatherChannelMin = 1f;
    /** Base units gathered per successful channel at low skill. */
    public int gatherYieldMin = 1;
    /** Max units gathered per channel at high skill (levels 40 / 70 add +1 each). */
    public int gatherYieldMax = 3;
    /** Skill levels that bump yield by +1 (first / second bump). */
    public int gatherYieldLevel2 = 40;
    public int gatherYieldLevel3 = 70;
    /** Chance (0-1) a Mining node also drops a gem. */
    public float gatherGemChance = 0.08f;
    /** Chance (0-1) any gather also drops a little common dust. */
    public float gatherDustChance = 0.12f;
    /** Common dust amount on a rare dust roll. */
    public int gatherDustAmount = 1;
    /** Chance (0-1) a gather grants a small gold bonus. */
    public float gatherGoldChance = 0.04f;
    /** Gold granted on a rare gold roll. */
    public int gatherGoldAmount = 5;
    /** Chance (0-1) a gather grants a mana shard. */
    public float gatherShardChance = 0.015f;

    // ---- Ascendant reagent card crafting (Package A2). ----

    /**
     * Colorless card reagent cost: ore (or scrap of the same tier) equal to mana value,
     * capped at this value. Minimum is {@link #colorlessOreMin}.
     */
    public int colorlessOreCap = 4;
    /** Minimum ore/scrap units for colorless cards and colorless no-cost cards/lands. */
    public int colorlessOreMin = 1;
    /** Spellsmithing XP granted when crafting one Prismatic reagent (any tier). */
    public int prismaticCraftXp = 40;

    // ---- Ascendant gathering methods (Package B2): method upgrades, tool sockets, outposts. ----

    /** Tool tier at which the first enchantment socket unlocks. */
    public int toolEnchantSocketMinTier = 2;
    /** Tool tier at which a second enchantment socket unlocks. */
    public int toolEnchantSocketTier2 = 4;
    /** Max enchantment sockets on any gathering tool. */
    public int toolEnchantSocketMax = 2;
    /** In-game seconds treated as one camp production hour. */
    public float outpostSecondsPerHour = 60f;
    /** Default storage cap in production-hours when an outpost omits storageCapHours. */
    public float outpostDefaultStorageHours = 72f;

    // ---- Ascendant stations (Package E): recipe crafting at Forge / Workshop / Apothecary / Jeweler. ----

    /**
     * Max gold-cost discount for station recipes at crafting-skill level 99
     * (0.25 = 25% off). Linear from 0 at level 1.
     */
    public float stationCraftGoldDiscountMax = 0.25f;
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

    // ---- Ascendant gyms and League (Package G). ----

    /** Best-of for gym leaders when a fighter omits gamesPerMatch (data usually sets 3). */
    public int gymLeaderGamesPerMatch = 3;
    /** Best-of for Elite Four and Champion when omitted in gyms.json. */
    public int leagueGamesPerMatch = 3;
    /** Badges required to open the League (normally equals the gym count). */
    public int leagueBadgeRequirement = 8;
    // ---- Ascendant world travel (Package C): waypoint travel + road speed. ----

    /** Exploration level required to use waypoint travel between visited towns. */
    public int waypointTravelUnlockLevel = 5;
    /** Flat gold fee added to every waypoint trip before distance cost. */
    public int waypointTravelBaseCost = 25;
    /** Gold charged per overworld tile of distance (pre-discount). */
    public float waypointTravelCostPerTile = 0.5f;
    /** Max Exploration discount on waypoint travel cost at level 99 (0.5 = 50% off). */
    public float waypointTravelDiscountMax = 0.5f;
    /** Overworld move-speed multiplier while standing on a road tile (Ascendant only). */
    public float roadSpeedBonus = 1.25f;

    // ---- Ascendant co-op (Package CO1): session / connection. ----

    /** Forge duel / lobby port (existing). Reserved for CO3; unused in CO1. */
    public int coopGamePort = 36743;
    /** Ascendant co-op overworld session port (handshake, world sync, CO2). */
    public int coopOverworldPort = 36744;
    /**
     * When true (default), hosting skips UPnP. Prefer Tailscale or manual Windows
     * firewall rules; set false only for classic LAN experiments that add UPnP later.
     */
    public boolean coopSkipUPnP = true;
    /**
     * Optional host bind address. Empty / omitted = all interfaces. Set to a
     * Tailscale {@code 100.x} address to listen only on that interface.
     */
    public String coopBindAddress = "";
    /** Seconds the guest waits for a host hello reply before giving up. */
    public int coopHandshakeTimeoutSeconds = 30;
    /** Party / duel-invite / location-invite radius in overworld tiles (CO2/CO3). */
    public float coopPartyRadiusTiles = 8f;
    /** "Join the fight?" prompt timeout in seconds (CO3). */
    public int coopDuelInviteTimeoutSeconds = 15;

    // ---- Ascendant co-op (Package CO2): shared overworld. ----

    /** Outbound position/facing samples per second (10–20). */
    public float coopPositionSendHz = 15f;
    /** Max inbound position samples accepted per peer per second (rate limit). */
    public int coopPositionMaxPerSecond = 24;
    /** Partner sprite interpolation smoothing rate (higher = snappier). */
    public float coopPartnerInterpRate = 12f;
    /** Max gather / world-request messages per peer per second. */
    public int coopRequestMaxPerSecond = 8;
    /** Host gather-range check in world pixels. */
    public float coopInteractRangePx = 96f;
    /** Seconds a party partner has to accept a location-enter invite. */
    public int coopLocationInviteTimeoutSeconds = 12;
    /** Host enemy-position broadcast rate (Hz). Lower than player move rate. */
    public float coopEnemyBroadcastHz = 5f;
    /**
     * Initial / fallback max peer walk speed in world pixels/sec before the peer
     * reports their actual max (base × road × equipment/skill). Hard-capped by
     * {@code CoopWireLimits.HARD_MAX_MOVE_SPEED_PX}. Walk samples above
     * {@code max × MOVE_SPEED_MARGIN} are rejected; explicit teleport samples
     * bypass the check when armed by an allowing action.
     */
    public float coopMaxMoveSpeedPx = 120f;

    // ---- Ascendant co-op (Package CO3): co-op duels. ----

    /**
     * Enemy starting-life multiplier when two humans fight together (team 0).
     * Solo fights ignore this (factor 1.0). Tunable like {@link #enemyLifeEarly}.
     */
    public float coopDuelEnemyLifeFactor = 1.5f;
    /**
     * Extra cards enemies start with / draw when two humans fight together.
     * Solo fights get 0.
     */
    public int coopDuelEnemyExtraCards = 1;
    /**
     * Seconds the host waits for the guest to connect on the game port after
     * sending {@code CoopDuelStartEvent} before starting the match anyway.
     */
    public int coopDuelGuestConnectGraceSeconds = 8;

    // ---- Ascendant inventory bags (INV1): slot counts and stack sizes (no weight). ----

    /** Default backpack slots for gear and usable items. */
    public int backpackSlots = 24;
    /** Max stack size for stackable backpack items. */
    public int backpackMaxStack = 20;
    /** Default unopened-pack bag slots. */
    public int packsSlots = 12;
    /** Max stack size for packs (each booster is unique; kept for upgrade parity). */
    public int packsMaxStack = 1;
    /** Currency pouch slots (built-in currencies + contest coins). */
    public int currencySlots = 16;
    /** Max amount per contest/challenge coin stack in the currency pouch. */
    public int currencyMaxStack = 9999;
    /**
     * Legacy materials bag distinct-type slots (pre–Craft Pouch). Kept for save compat;
     * Ascendant uses Craft Pouch tier tables below.
     */
    public int materialsSlots = 40;
    /** Legacy materials max stack (pre–Craft Pouch). */
    public int materialsMaxStack = 99;
    /** Slots added by a T1 backpack upgrade item. */
    public int bagUpgradeBackpackSlots = 8;
    /** Stack size added by a T1 backpack upgrade item. */
    public int bagUpgradeBackpackStack = 10;
    /** Slots added by a pack satchel upgrade. */
    public int bagUpgradePacksSlots = 6;
    /** Legacy material-sack upgrade slots (Craft Pouch tiers are Mastery Surge only). */
    public int bagUpgradeMaterialsSlots = 10;
    /** Legacy material-sack upgrade stack (unused for Craft Pouch tiers). */
    public int bagUpgradeMaterialsStack = 50;
    /** Slots added by a currency pouch upgrade. */
    public int bagUpgradeCurrencySlots = 4;

    // ---- INV1 Overflow + Craft Pouch + Mastery Surge ----

    /** Overflow stash slot cap. Past this, new grants are auto-sold (never deleted). */
    public int overflowCap = 10;
    /** Movement speed floor while Overflow is full (1.0 = no slow, 0.5 = half speed). */
    public float overflowSlowMinFactor = 0.5f;
    /** Craft Pouch Satchel (start): distinct material types. */
    public int craftPouchSatchelSlots = 20;
    /** Craft Pouch Satchel stack size per material. */
    public int craftPouchSatchelStack = 250;
    /** Craft Pouch Pack: distinct material types. */
    public int craftPouchPackSlots = 30;
    /** Craft Pouch Pack stack size. */
    public int craftPouchPackStack = 500;
    /** Craft Pouch Hauler's Sack: distinct material types. */
    public int craftPouchHaulerSlots = 40;
    /** Craft Pouch Hauler's Sack stack size. */
    public int craftPouchHaulerStack = 1000;
    /** Mastery Surge picks granted when a set is mastered (1–2 typical). */
    public int masterySurgePicks = 2;

    // ---- Ascendant multiverse (Package MV1): multi-plane save + planar portals. ----

    /**
     * Plane instance id for the home overworld inside one save
     * ({@code "home"}). Distinct from {@code settings.json} adventure pack name.
     */
    public String homePlaneId = "home";
    /**
     * Relative world.json used when generating a new set plane (MV1 template;
     * MV2 will specialise per set). Smaller than the home plane.
     */
    public String setPlaneWorldConfig = "world/set_plane_world.json";
    /** Soft cap on planes stored in one save (home + set planes). */
    public int maxPlanesPerSave = 16;
    /**
     * When true (Ascendant default), planar portal objects with {@code targetPlane}
     * may create a missing set plane on first use.
     */
    public boolean planarPortalAutoCreate = true;

    // ---- AI1: LLM opponent (optional). Player settings live in llm_opponent.properties
    // (local only; API key never in the save). These plane tunables are reserved defaults /
    // documentation; the in-game LLM settings screen owns enable/URL/model/key/timeout. ----

    /**
     * Soft documentation default for the LLM HTTP timeout (seconds) when creating a fresh
     * {@code llm_opponent.properties}. The settings screen and properties file are authoritative.
     */
    public int llmOpponentDefaultTimeoutSeconds = 30;
    // ---- Ascendant achievements (Package AC1): account-wide, outside the save. ----

    /** When true, show a HUD toast when an achievement unlocks. */
    public boolean achievementToastEnabled = true;
    /**
     * Max achievement toasts emitted in one evaluation pass (set-completion spam guard).
     * Extra unlocks still persist; only the toast is deferred.
     */
    public int achievementToastMaxPerPass = 5;

    // ---- Ascendant fortresses (Package FT1): claim site + instance build mode. ----

    /**
     * Minimum tile distance from towns and other POIs required to plant a Fortress Banner.
     */
    public float fortressBannerMinDistanceTiles = 8f;
    /** Max fortresses that may exist on one plane (FT1 starts at 1). */
    public int fortressMaxPerPlane = 1;
    /** Percent of structure material cost returned on demolish (0–100). */
    public float fortressDemolishRefundPercent = 50f;
    /** Buildable zone origin X inside the fortress template map (tiles). */
    public int fortressBuildableOriginX = 4;
    /** Buildable zone origin Y inside the fortress template map (tiles). */
    public int fortressBuildableOriginY = 4;
    /** Buildable zone width in tiles. */
    public int fortressBuildableWidth = 16;
    /** Buildable zone height in tiles. */
    public int fortressBuildableHeight = 12;

}
