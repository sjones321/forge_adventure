package forge.adventure.player;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.actions.Actions;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Null;
import com.badlogic.gdx.utils.ObjectMap;
import com.github.tommyettinger.textra.TextraLabel;
import com.google.common.collect.Lists;

import forge.Forge;
import forge.adventure.data.*;
import forge.adventure.pointofintrest.PointOfInterestChanges;
import forge.adventure.scene.AdventureDeckEditor;
import forge.adventure.scene.DeckEditScene;
import forge.adventure.stage.GameStage;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.*;
import forge.adventure.world.PlaneMeta;
import forge.adventure.world.WorldSave;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckSection;
import forge.item.InventoryItem;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.sound.SoundEffectType;
import forge.sound.SoundSystem;
import forge.util.ItemPool;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Predicate;

/**
 * Class that represents the player (not the player sprite)
 */
public class AdventurePlayer implements Serializable, SaveFileContent {
    public static final int MIN_DECK_COUNT = 10;
    // this is a purely arbitrary limit, could be higher or lower; just meant as some sort of reasonable limit for the user
    private int maxDeckCount = 20;
    // Player profile data.
    private String name;
    private int heroRace;
    private int avatarIndex;
    private boolean isFemale;
    private ColorSet colorIdentity = ColorSet.WUBRG;

    // Deck data
    private Deck deck;
    private final ArrayList<Deck> decks = new ArrayList<>(MIN_DECK_COUNT);
    private int selectedDeckIndex = 0;
    private final DifficultyData difficultyData = new DifficultyData();

    // Commander mode
    private AdventureModes adventureMode;

    // Game data.
    private float worldPosX;
    private float worldPosY;
    private int gold = 0;
    private int maxLife = 20;
    private int life = 20;
    private int shards = 0;
    /** Ascendant dust currencies: Common, Uncommon, Rare, Mythic. Never convert into each other. */
    public static final int DUST_COMMON = 0;
    public static final int DUST_UNCOMMON = 1;
    public static final int DUST_RARE = 2;
    public static final int DUST_MYTHIC = 3;
    private final int[] dust = new int[4];
    /** When true (Ascendant only), copies beyond {@link ConfigData#autoSalvageKeepCopies} are salvaged on gain. */
    private boolean autoSalvage = false;
    /**
     * Ascendant materials inventory (Package A). Key = material id from materials.json.
     * Stable API for gathering, recipes, town requests.
     */
    private final LinkedHashMap<String, Integer> materials = new LinkedHashMap<>();
    /** Starter T1 gathering tools are given once per character, not on every load. */
    private boolean starterToolsGranted = false;
    /**
     * Ascendant INV1 bags: capacities / stack limits. Contents stay in inventoryItems,
     * boostersOwned, materials, and contestCurrencies. Stock Adventure leaves this unused.
     */
    private final InventoryBags bags = new InventoryBags();

    {
        // Overflow slows the player: refresh the cached move speed whenever Overflow changes.
        bags.setOverflowListener(() -> this.onEquipmentChange.emit());
    }
    /**
     * Ascendant contest currencies (gym / tournament / Grand Prix). Extensible map; optional on load.
     */
    private final LinkedHashMap<String, Integer> contestCurrencies = new LinkedHashMap<>();
    /** Unspent Mastery Surge picks (INV1). Optional on load → 0. */
    private int masterySurgePicksUnspent = 0;
    /** Extra duel perk slots from Mastery Surge. */
    private int duelPerkSlotBonus = 0;
    /** Extra tool enchant sockets from Mastery Surge (all families). */
    private int toolEnchantSocketBonus = 0;
    /**
     * Ascendant toolbelt (Package B): one equipped gathering tool name per material family
     * (logs, ore, stone, herbs, crystal, scrap). Not an equipment slot.
     */
    private final LinkedHashMap<String, String> toolbelt = new LinkedHashMap<>();
    /**
     * Package B2: highest unlocked gathering-method rank per skill display name
     * (Woodcutting, Mining, …). Missing → 0 (base tool only).
     */
    private final LinkedHashMap<String, Integer> gatherMethodRanks = new LinkedHashMap<>();
    /**
     * Package B2: toolbelt family → ordered enchantment ids in sockets.
     * Optional on old saves (including saves that already have a toolbelt).
     */
    private final LinkedHashMap<String, ArrayList<String>> toolEnchantments = new LinkedHashMap<>();
    /**
     * Package B2: claimed outpost camps (outpost id → state). Optional on load.
     */
    private final LinkedHashMap<String, CampState> camps = new LinkedHashMap<>();
    /**
     * In-game play seconds used for outpost production (Ascendant). Optional on load → 0.
     * Double so the timer does not stall after ~146 hours of float precision loss.
     */
    private double adventurePlaySeconds = 0d;
    /**
     * materialSchema value read on the last {@link #load(SaveFileData)} (0 when missing).
     * Used by {@link forge.adventure.world.WorldSave} to gate inactive-plane ore-line migration.
     */
    private int loadedMaterialSchema = 0;
    /** T1 tools granted free at New Game and on old Ascendant saves. Package E crafts higher tiers. */
    public static final String[] STARTER_GATHERING_TOOLS = {
            "Iron Hatchet", "Iron Pickaxe", "Iron Chisel",
            "Iron Sickle", "Iron Probe", "Iron Spanner"
    };
    /**
     * Ascendant gym badges (Package G). Badge ids from gyms.json. Order of earning is preserved.
     * Packages E (recipes) and J (skill trees) should call {@link #hasBadge(String)} / {@link #getBadgeCount()}.
     */
    private final LinkedHashSet<String> badges = new LinkedHashSet<>();
    /** True after beating the League Champion at least once (unlocks rematches). */
    private boolean leagueCleared = false;
    /**
     * Run format for gyms / League / tournaments (Package K). Until K lands every run is Standard.
     * Saved optionally; missing → {@link forge.adventure.util.GymUtil#FORMAT_STANDARD}.
     * Commander-mode saves always report Commander regardless of this field.
     */
    private String runFormat = forge.adventure.util.GymUtil.FORMAT_STANDARD;

    /** Serializable camp progress for Package B2 outposts. */
    public static class CampState implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        public int level = 0;
        /**
         * Stored output keyed by material id so upgrading the camp cannot convert
         * lower-tier stock into the new tier.
         */
        public final LinkedHashMap<String, Float> storedByMaterial = new LinkedHashMap<>();
        public double lastUpdatePlaySeconds = 0d;

        public float totalStored() {
            float sum = 0f;
            for (Float v : storedByMaterial.values()) {
                if (v != null)
                    sum += v;
            }
            return sum;
        }

        public float getStored(String materialId) {
            if (materialId == null)
                return 0f;
            return storedByMaterial.getOrDefault(materialId, 0f);
        }

        public void addStored(String materialId, float amount) {
            if (materialId == null || amount <= 0f)
                return;
            storedByMaterial.merge(materialId, amount, Float::sum);
        }
    }
    private EffectData blessing; //Blessing to apply for next battle.
    private final PlayerStatistic statistic = new PlayerStatistic();
    private final Map<String, Byte> questFlags = new HashMap<>();
    private final Map<String, Byte> characterFlags = new HashMap<>();
    private final Map<String, Byte> tutorialFlags = new HashMap<>();

    private final ArrayList<ItemData> inventoryItems = new ArrayList<>();
    private final Array<Deck> boostersOwned = new Array<>();
    private final HashMap<String, Long> equippedItems = new HashMap<>();
    private final ArrayList<HashMap<String, Long>> deckLoadouts = new ArrayList<>();
    private final List<AdventureQuestData> quests = new ArrayList<>();
    private final List<AdventureEventData> events = new ArrayList<>();
    private final Set<PaperCard> unsupportedCards = new HashSet<>();
    private final Predicate<PaperCard> isUnsupported = pc -> pc != null && pc.getRules() != null && pc.getRules().isUnsupported();
    private final Predicate<PaperCard> isValid = pc -> pc != null && pc.getRules() != null && !pc.getRules().isUnsupported();

    // Fantasy/Chaos mode settings.
    private boolean fantasyMode = false;
    private boolean announceFantasy = false;
    private boolean usingCustomDeck = false;
    private boolean announceCustom = false;

    // Signals
    final SignalList onLifeTotalChangeList = new SignalList();
    final SignalList onShardsChangeList = new SignalList();
    final SignalList onDustChangeList = new SignalList();
    final SignalList onMaterialChangeList = new SignalList();
    final SignalList onGoldChangeList = new SignalList();
    final SignalList onPlayerChangeList = new SignalList();
    final SignalList onEquipmentChange = new SignalList();
    final SignalList onBlessing = new SignalList();
    private PointOfInterestChanges currentLocationChanges;

    public AdventurePlayer() {
        clear();
    }

    public PlayerStatistic getStatistic() {
        return statistic;
    }

    public int getDeckCount() { return decks.size(); }

    public int getMaxDeckCount() { return maxDeckCount; }

    private void clearDecks() {
        decks.clear();
        for (int i = 0; i < MIN_DECK_COUNT; i++)
            decks.add(new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
        deck = decks.get(0);
        selectedDeckIndex = 0;
    }

    private void clear() {
        //Ensure sensitive gameplay data is properly reset between games.
        //Reset all properties HERE.
        fantasyMode = false;
        announceFantasy = false;
        usingCustomDeck = false;
        adventureMode = null;
        blessing = null;
        gold = 0;
        maxLife = 20;
        life = 20;
        shards = 0;
        Arrays.fill(dust, 0);
        autoSalvage = false;
        materials.clear();
        loadedMaterialSchema = 0;
        starterToolsGranted = false;
        bags.resetToDefaults(safeConfigData());
        contestCurrencies.clear();
        masterySurgePicksUnspent = 0;
        duelPerkSlotBonus = 0;
        toolEnchantSocketBonus = 0;
        toolbelt.clear();
        badges.clear();
        leagueCleared = false;
        runFormat = GymUtil.FORMAT_STANDARD;
        gatherMethodRanks.clear();
        toolEnchantments.clear();
        camps.clear();
        adventurePlaySeconds = 0d;
        maxDeckCount = 20;
        clearDecks();
        inventoryItems.clear();
        boostersOwned.clear();
        equippedItems.clear();
        deckLoadouts.clear();
        characterFlags.clear();
        skills.clear();
        standardWindow.clear();
        questFlags.clear();
        quests.clear();
        events.clear();
        cards.clear();
        statistic.clear();
        newCards.clear();
        autoSellCards.clear();
        favoriteCards.clear();
        vaultCards.clear();
        AdventureEventController.clear();
        AdventureQuestController.clear();
        unsupportedCards.clear();
    }

    static public AdventurePlayer current() {
        return WorldSave.getCurrentSave().getPlayer();
    }

    private final CardPool cards = new CardPool();
    private final PlayerSkills skills = new PlayerSkills();
    private final StandardWindow standardWindow = new StandardWindow();

    public PlayerSkills getSkills() {
        return skills;
    }

    public StandardWindow getStandardWindow() {
        return standardWindow;
    }

    /** After cards enter the collection: check set mastery (may unlock and rotate in a new set). */
    private void afterCardsCollected() {
        afterCardsCollected(null);
    }

    private void afterCardsCollected(Iterable<PaperCard> justAdded) {
        List<String> before = new ArrayList<>(standardWindow.getSets());
        standardWindow.checkMastery(cards);
        if (!before.equals(standardWindow.getSets()))
            RewardData.invalidateCardPool();
        // AC1: incremental set-completion check (account-wide; Ascendant only).
        if (Config.ascendant()) {
            try {
                if (justAdded != null) {
                    AchievementService.get().onCardsAdded(cards, justAdded);
                } else {
                    AchievementService.get().evaluateCollection(cards);
                }
            } catch (Throwable ignored) {
                // Achievements must never break collection rewards.
            }
        }
    }

    public final ItemPool<PaperCard> newCards = new ItemPool<>(PaperCard.class);
    public final ItemPool<PaperCard> autoSellCards = new ItemPool<>(PaperCard.class);
    public final Set<PaperCard> favoriteCards = new HashSet<>();
    /**
     * Commander Vault: copies moved here are permanent and Commander-only. They stay part of
     * {@link #cards} (so saves and ownership checks keep working) but are hidden from the normal
     * collection, can't be sold or auto-sold, can't be used in non-Commander decks, and never rotate.
     */
    public final ItemPool<PaperCard> vaultCards = new ItemPool<>(PaperCard.class);

    public ItemPool<PaperCard> getVaultCards() {
        return vaultCards;
    }

    /** Copies held in the Commander Vault (never sellable, never in non-Commander decks). */
    public int vaultedCount(PaperCard card) {
        return vaultCards.count(card);
    }

    /**
     * Historic = everything you own (except the Commander Vault); Standard = only what's legal right
     * now. Nothing moves on rotation: rotated cards just stop being Standard-legal, and become legal
     * again if their set or a reprint comes back. Basic lands are always legal.
     */
    public boolean isStandardLegal(PaperCard pc) {
        return pc.getRules().getType().isBasicLand() || standardWindow.isStandardLegal(pc.getName());
    }

    /** Owned copies (outside the Commander Vault) that are not Standard-legal right now. */
    public int countRotatedOut() {
        int n = 0;
        for (Map.Entry<PaperCard, Integer> e : cards)
            if (!isStandardLegal(e.getKey()))
                n += Math.max(0, e.getValue() - vaultedCount(e.getKey()));
        return n;
    }

    /** Why a Standard deck can't be played right now (rotated or banned cards), or null if it's fine. */
    public String standardDeckProblem(Deck d) {
        if (d == null || !standardWindow.isActive() || isCommanderDeck(d) || isHistoricDeck(d))
            return null;
        for (Map.Entry<PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
            PaperCard pc = e.getKey();
            if (!isStandardLegal(pc))
                return pc.getName() + " rotated out of Standard";
            if (BanLists.isBanned("standard", pc.getName()))
                return pc.getName() + " is banned in Standard";
        }
        return null;
    }

    /**
     * Why a Historic-tagged deck can't challenge a Historic run, or null if fine.
     * Requires the Historic tag and per-card Adventure historic ban-list legality
     * (same gate the deck editor uses when hiding banned cards).
     */
    public String historicDeckProblem(Deck d) {
        if (d == null)
            return "No deck selected";
        if (!isHistoricDeck(d))
            return "Select a Historic-tagged deck for this run";
        if (isCommanderDeck(d))
            return "Commander decks can't be used in a Historic run";
        for (Map.Entry<PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
            PaperCard pc = e.getKey();
            if (BanLists.isBanned("historic", pc.getName()))
                return pc.getName() + " is banned in Historic";
        }
        return null;
    }

    /**
     * Why a deck can't challenge a Pauper run, or null if fine.
     * Every non-basic card must be Common (or legal under Forge's Pauper format filter),
     * and not on the Adventure pauper ban list.
     */
    public String pauperDeckProblem(Deck d) {
        if (d == null)
            return "No deck selected";
        if (isCommanderDeck(d))
            return "Commander decks can't be used in a Pauper run";
        forge.game.GameFormat pauper = FModel.getFormats().getPauper();
        for (Map.Entry<PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
            PaperCard pc = e.getKey();
            if (pc.getRules().getType().isBasicLand())
                continue;
            if (BanLists.isBanned("pauper", pc.getName()))
                return pc.getName() + " is banned in Pauper";
            if (pauper != null && pauper.getFilterRules() != null) {
                if (!pauper.getFilterRules().test(pc))
                    return pc.getName() + " is not Pauper-legal";
            } else if (pc.getRarity() != CardRarity.Common) {
                return pc.getName() + " is not Pauper-legal (commons only)";
            }
        }
        return null;
    }

    // ---- per-deck format: Standard (60-card Adventure), Commander, or Historic ----

    public static final String COMMANDER_DECK_TAG = "AdventureCommanderDeck";
    public static final String HISTORIC_DECK_TAG = "AdventureHistoricDeck";

    public boolean isHistoricDeck(Deck d) {
        return !isCommanderMode() && d != null && d.getTags().contains(HISTORIC_DECK_TAG);
    }

    public boolean isHistoricDeckSelected() {
        return isHistoricDeck(getSelectedDeck());
    }

    /** Cycles a deck slot's format: Standard -> Commander -> Historic -> Standard. Returns the new format name. */
    public String cycleDeckFormat(int slot) {
        if (slot < 0 || slot >= decks.size() || isCommanderMode())
            return "Commander";
        Deck d = decks.get(slot);
        if (isHistoricDeck(d)) {
            d.getTags().remove(HISTORIC_DECK_TAG);
            setDeckCommander(slot, false);
            return "Standard";
        }
        if (isCommanderDeck(d)) {
            setDeckCommander(slot, false);
            d.getTags().add(HISTORIC_DECK_TAG);
            RewardData.invalidateCardPool();
            return "Historic";
        }
        setDeckCommander(slot, true);
        return "Commander";
    }

    /** A deck is Commander if the whole save is a Commander save, or the deck is tagged Commander. */
    public boolean isCommanderDeck(Deck d) {
        return isCommanderMode() || (d != null && d.getTags().contains(COMMANDER_DECK_TAG));
    }

    /** The selected deck decides the editor rules and the duel format. */
    public boolean isCommanderDeckSelected() {
        return isCommanderDeck(getSelectedDeck());
    }

    public boolean hasCommanderDeck() {
        if (isCommanderMode())
            return true;
        for (Deck d : decks)
            if (d != null && d.getTags().contains(COMMANDER_DECK_TAG))
                return true;
        return false;
    }

    /** Switches a deck slot's format. The first Commander deck grants Command Tower and Arcane Signet (vaulted). */
    public void setDeckCommander(int slot, boolean commander) {
        if (slot < 0 || slot >= decks.size() || isCommanderMode())
            return;
        Deck d = decks.get(slot);
        if (commander)
            d.getTags().add(COMMANDER_DECK_TAG);
        else
            d.getTags().remove(COMMANDER_DECK_TAG);
        if (commander && !checkCharacterFlag("commanderStarterGiven")) {
            setCharacterFlag("commanderStarterGiven", 1);
            for (String name : new String[]{"Command Tower", "Arcane Signet"}) {
                PaperCard pc = forge.model.FModel.getMagicDb().getCommonCards().getCard(name);
                if (pc != null) {
                    cards.add(pc);
                    vaultCards.add(pc);
                }
            }
        }
        RewardData.invalidateCardPool();
    }

    /** Copies of a card that are free to vault: owned, not vaulted, not auto-selling, not used in decks. */
    public int copiesAvailableToVault(PaperCard card) {
        return cards.count(card) - vaultCards.count(card) - autoSellCards.count(card) - getCopiesUsedInDecks(card);
    }

    /** One-way move into the Commander Vault. Returns how many copies were vaulted. */
    public int moveToVault(PaperCard card, int amount) {
        int n = Math.min(amount, copiesAvailableToVault(card));
        if (n <= 0)
            return 0;
        vaultCards.add(card, n);
        return n;
    }

    public void create(String n, Deck startingDeck, boolean male, int race, int avatar, boolean isFantasy,
                       boolean isUsingCustomDeck, DifficultyData difficultyData, AdventureModes adventureMode) {
        clear();
        this.adventureMode = adventureMode;
        announceFantasy = fantasyMode = isFantasy; //Set Chaos mode first.
        announceCustom = usingCustomDeck = isUsingCustomDeck;

        this.maxDeckCount = Config.instance().getConfigData().maxNumberOfDecks; // Get the MAX_DECK_COUNT from the config file
        // Sanity Check make sure the number is not insane and make sure it is at least 20
        this.maxDeckCount = Math.max(Math.min(this.maxDeckCount, 99), 20);

        clearDecks(); // Reset the empty decks to now already have the commander in the command zone.
        deck = startingDeck;
        decks.set(0, deck);

        cards.addAllFlat(deck.getAllCardsInASinglePool(true, true).toFlatList());

        this.difficultyData.startingLife = difficultyData.startingLife;
        this.difficultyData.startingMoney = difficultyData.startingMoney;
        this.difficultyData.startingDifficulty = difficultyData.startingDifficulty;
        this.difficultyData.name = difficultyData.name;
        this.difficultyData.spawnRank = difficultyData.spawnRank;
        this.difficultyData.enemyLifeFactor = difficultyData.enemyLifeFactor;
        this.difficultyData.sellFactor = difficultyData.sellFactor;
        this.difficultyData.shardSellRatio = difficultyData.shardSellRatio;
        this.difficultyData.goldLoss = difficultyData.goldLoss;
        this.difficultyData.lifeLoss = difficultyData.lifeLoss;

        gold = difficultyData.startingMoney;
        name = n;
        heroRace = race;
        avatarIndex = avatar;
        isFemale = !male;

        setColorIdentity(DeckProxy.getColorIdentity(deck));

        life = maxLife = difficultyData.startingLife;
        shards = difficultyData.startingShards;

        for (String s : difficultyData.startItems) {
            ItemData i = ItemListData.getItem(s);
            if (i == null)
                continue;
            inventoryItems.add(i);
        }
        ensureStarterGatheringTools();

        onGoldChangeList.emit();
        onLifeTotalChangeList.emit();
        onShardsChangeList.emit();
    }

    public void setSelectedDeckSlot(int slot) {
        setSelectedDeckSlot(slot, true);
    }

    public void setSelectedDeckSlot(int slot, boolean switchLoadout) {
        if (slot >= 0 && slot < getDeckCount()) {
            boolean bindLoadouts = Config.instance().getSettingData().bindEquipmentLoadoutsToDecks;
            if (switchLoadout && bindLoadouts && slot != selectedDeckIndex) {
                // Save current loadout to old deck
                ensureDeckLoadoutsSize();
                deckLoadouts.set(selectedDeckIndex, new HashMap<>(equippedItems));

                // Clear current equipment
                for (ItemData item : inventoryItems) {
                    if (item != null) {
                        item.isEquipped = false;
                    }
                }
                equippedItems.clear();

                // Restore loadout for new deck (if any)
                HashMap<String, Long> newLoadout = deckLoadouts.get(slot);
                if (newLoadout != null) {
                    for (Map.Entry<String, Long> entry : newLoadout.entrySet()) {
                        ItemData item = getItemFromInventory(entry.getValue());
                        if (item != null) {
                            item.isEquipped = true;
                            equippedItems.put(entry.getKey(), entry.getValue());
                        }
                    }
                }

                onEquipmentChange.emit();
            }

            selectedDeckIndex = slot;
            deck = decks.get(selectedDeckIndex);
            setColorIdentity(DeckProxy.getColorIdentity(deck));
        }
    }

    public void updateDifficulty(DifficultyData diff) {
        maxLife = diff.startingLife;
        this.difficultyData.startingShards = diff.startingShards;
        this.difficultyData.startingLife = diff.startingLife;
        this.difficultyData.startingMoney = diff.startingMoney;
        this.difficultyData.startingDifficulty = diff.startingDifficulty;
        this.difficultyData.name = diff.name;
        this.difficultyData.spawnRank = diff.spawnRank;
        this.difficultyData.enemyLifeFactor = diff.enemyLifeFactor;
        this.difficultyData.sellFactor = diff.sellFactor;
        this.difficultyData.shardSellRatio = diff.shardSellRatio;
        this.difficultyData.goldLoss = diff.goldLoss;
        this.difficultyData.lifeLoss = diff.lifeLoss;
        resetToMaxLife();
    }

    //Getters
    public int getSelectedDeckIndex() {
        return selectedDeckIndex;
    }

    public Deck getSelectedDeck() {
        return deck;
    }

    public ArrayList<ItemData> getItems() {
        return inventoryItems;
    }

    public ItemData getItemFromInventory(Long id) {
        if (id == null)
            return null;
        for (ItemData data : inventoryItems) {
            if (data == null)
                continue;
            if (id.equals(data.longID))
                return data;
        }
        return null;
    }

    public ItemData getEquippedItem(Long id) {
        if (id == null)
            return null;
        for (ItemData data : inventoryItems) {
            if (data == null)
                continue;
            if (id.equals(data.longID) && data.isEquipped)
                return data;
        }
        return null;
    }

    public Array<Deck> getBoostersOwned() {
        return boostersOwned;
    }

    public Deck getDeck(int index) {
        return decks.get(index);
    }

    public CardPool getCards() {
        return cards;
    }

    public String getName() {
        return name;
    }

    public Boolean isFemale() {
        return isFemale;
    }
    
    public float getWorldPosX() {
        return worldPosX;
    }

    public float getWorldPosY() {
        return worldPosY;
    }

    public int getGold() {
        return gold;
    }

    public int getLife() {
        return life;
    }

    /**
     * Sets current map life (clamped to ≥ 0). May exceed {@link #getMaxLife()} when the player
     * has false-life potions. League attrition uses damage-based write-back in DuelScene so
     * badge/skill bonuses never absorb map damage and potions above max are not cut off.
     */
    public void setLife(int amount) {
        life = Math.max(0, amount);
        onLifeTotalChangeList.emit();
    }

    public AdventureModes getAdventureMode(){
        return adventureMode;
    }

    public boolean isCommanderMode() {
        return adventureMode != null && adventureMode.isCommanderLike();
    }

    public int getMaxLife() {
        return maxLife;
    }

    public int getShards() {
        return shards;
    }

    /** Dust of one rarity bucket (0=Common … 3=Mythic). Missing/unknown index → 0. */
    public int getDust(int index) {
        return index >= 0 && index < dust.length ? dust[index] : 0;
    }

    public int getDust(CardRarity rarity) {
        return getDust(dustIndex(rarity));
    }

    public int[] getDustAll() {
        return Arrays.copyOf(dust, dust.length);
    }

    /** Compact dust totals for Ascendant UI headers: {@code C:12 U:5 R:2 M:1}. */
    public String dustSummary() {
        return "C:" + dust[DUST_COMMON] + " U:" + dust[DUST_UNCOMMON]
                + " R:" + dust[DUST_RARE] + " M:" + dust[DUST_MYTHIC];
    }

    /** Owned count of a material id; unknown/missing → 0. */
    public int getMaterial(String id) {
        if (id == null)
            return 0;
        Integer n = materials.get(id);
        return n != null ? Math.max(0, n) : 0;
    }

    /**
     * {@code materialSchema} value from the last load (0 when the key was missing).
     * After a successful save the on-disk value is {@link MaterialListData#MATERIAL_SCHEMA_ORE_LINE}.
     */
    public int getLoadedMaterialSchema() {
        return loadedMaterialSchema;
    }

    /** Unmodifiable view of material id → count (zeros omitted). */
    public Map<String, Integer> getMaterials() {
        return Collections.unmodifiableMap(materials);
    }

    /**
     * Adds {@code amount} of a material (no-op if amount ≤ 0 or id empty).
     * Ascendant INV1: always succeeds — excess goes to Overflow (or auto-sell past Overflow cap).
     * Emits {@link #onMaterialChange} when the Craft Pouch changes.
     *
     * @return false only for invalid id/amount
     */
    public boolean addMaterial(String id, int amount) {
        if (id == null || id.isEmpty() || amount <= 0)
            return false;
        if (!Config.ascendant()) {
            materials.put(id, getMaterial(id) + amount);
            onMaterialChangeList.emit();
            return true;
        }
        int remaining = amount;
        int room = bags.materialRoom(id, materials);
        if (room > 0) {
            int fit = Math.min(room, remaining);
            materials.put(id, getMaterial(id) + fit);
            remaining -= fit;
            onMaterialChangeList.emit();
        }
        if (remaining > 0) {
            GrantResult r = bags.placeInOverflow(OverflowEntry.ofMaterial(id, remaining));
            applyGrantResult(r, OverflowEntry.ofMaterial(id, remaining));
        }
        return true;
    }

    /** Ascendant INV1 bag capacities (always non-null; ignored when not Ascendant). */
    public InventoryBags getBags() {
        return bags;
    }

    /** Unmodifiable contest currency id → count (Ascendant). */
    public Map<String, Integer> getContestCurrencies() {
        return Collections.unmodifiableMap(contestCurrencies);
    }

    public int getContestCurrency(String id) {
        if (id == null)
            return 0;
        Integer n = contestCurrencies.get(id);
        return n != null ? Math.max(0, n) : 0;
    }

    /**
     * Grant contest currency (gym / tournament / Grand Prix). Ascendant only; always succeeds
     * (Overflow / auto-sell when the currency pouch is full).
     */
    public boolean addContestCurrency(String id, int amount) {
        if (!Config.ascendant() || id == null || id.isEmpty() || amount <= 0)
            return false;
        if (bags.fitsContestCurrency(id, amount, contestCurrencies, inventoryItems)) {
            contestCurrencies.put(id, getContestCurrency(id) + amount);
            return true;
        }
        GrantResult r = bags.placeInOverflow(OverflowEntry.ofCurrency(id, amount));
        applyGrantResult(r, OverflowEntry.ofCurrency(id, amount));
        return true;
    }

    public boolean takeContestCurrency(String id, int amount) {
        if (id == null || amount <= 0)
            return false;
        int have = getContestCurrency(id);
        if (have < amount)
            return false;
        int left = have - amount;
        if (left <= 0)
            contestCurrencies.remove(id);
        else
            contestCurrencies.put(id, left);
        return true;
    }

    /** Apply a bag-upgrade item: bump capacities, consume one copy. */
    public boolean applyBagUpgrade(ItemData item) {
        if (!Config.ascendant() || item == null || item.bagUpgrade == null || item.bagUpgrade.isEmpty())
            return false;
        if (!hasItem(item.name) && !inventoryItems.contains(item))
            return false;
        InventoryBagType type = InventoryBags.parseBagUpgrade(item.bagUpgrade);
        if (type == null) {
            // Old Material Sacks: Craft Pouch tiers only come from Mastery Surge now. Keep the item (it can be sold).
            notifyInventory("Craft Pouch upgrades now come from Mastery Surge. You can sell this " + item.getDisplayName() + ".");
            return false;
        }
        bags.applyUpgrade(item);
        if (inventoryItems.contains(item))
            removeItem(item);
        else
            removeItem(item.name);
        String bagName = type != null ? type.label : "Bag";
        notifyInventory(item.getDisplayName() + " applied. " + bagName + " expanded.");
        return true;
    }

    private static void notifyInventory(String msg) {
        if (msg == null || msg.isEmpty())
            return;
        try {
            GameHUD.getInstance().addNotification(msg);
        } catch (Exception ignored) {
            // HUD may be unavailable during load/tests
        }
    }

    /** Apply Overflow / auto-sell outcome: pay gold/dust and toast once. */
    private void applyGrantResult(GrantResult r, OverflowEntry soldEntry) {
        if (r == null)
            return;
        if (r.wentToOverflow()) {
            notifyInventory(GrantResult.overflow().message);
            return;
        }
        if (r.wasAutoSold() && soldEntry != null) {
            autoSellOverflowEntry(soldEntry, r);
            notifyInventory(r.message != null ? r.message : "Overflow full — auto-sold " + soldEntry.displayName());
        }
    }

    private void autoSellOverflowEntry(OverflowEntry entry, GrantResult into) {
        if (entry == null)
            return;
        switch (entry.kind) {
            case ITEM: {
                ItemData item = entry.item;
                int gold = itemSellGold(item);
                if (gold > 0)
                    giveGold(gold);
                if (into != null) {
                    into.goldEarned = gold;
                    into.autoSoldName = entry.displayName();
                    into.message = "Overflow full — auto-sold " + entry.displayName()
                            + (gold > 0 ? " for " + gold + " gold" : "");
                }
                break;
            }
            case MATERIAL: {
                int unit = materialSellPrice(entry.key);
                int gold = unit * Math.max(1, entry.amount);
                if (gold > 0)
                    giveGold(gold);
                if (into != null) {
                    into.goldEarned = gold;
                    into.autoSoldName = entry.displayName();
                    into.message = "Overflow full — auto-sold " + entry.displayName()
                            + " for " + gold + " gold";
                }
                break;
            }
            case CURRENCY: {
                // Contest coins: nominal gold from amount (no dust).
                int gold = Math.max(1, entry.amount);
                giveGold(gold);
                if (into != null) {
                    into.goldEarned = gold;
                    into.autoSoldName = entry.displayName();
                    into.message = "Overflow full — auto-sold " + entry.displayName()
                            + " for " + gold + " gold";
                }
                break;
            }
            case BOOSTER: {
                dustBooster(entry.booster, into);
                break;
            }
            default:
                break;
        }
    }

    private int itemSellGold(ItemData item) {
        if (item == null)
            return 1;
        float factor = difficultyData != null ? difficultyData.sellFactor : 0.2f;
        return Math.max(1, Math.round(item.cost * factor));
    }

    private void dustBooster(Deck booster, GrantResult into) {
        if (booster == null)
            return;
        int c = 0, u = 0, r = 0, m = 0;
        try {
            for (PaperCard card : booster.getAllCardsInASinglePool().toFlatList()) {
                if (card == null)
                    continue;
                switch (dustIndex(card.getRarity())) {
                    case DUST_COMMON: c++; break;
                    case DUST_UNCOMMON: u++; break;
                    case DUST_RARE: r++; break;
                    case DUST_MYTHIC: m++; break;
                    default: c++; break;
                }
            }
        } catch (Exception ignored) {
            c = 1;
        }
        if (c > 0) addDust(DUST_COMMON, c);
        if (u > 0) addDust(DUST_UNCOMMON, u);
        if (r > 0) addDust(DUST_RARE, r);
        if (m > 0) addDust(DUST_MYTHIC, m);
        if (into != null) {
            into.dustCommon = c;
            into.dustUncommon = u;
            into.dustRare = r;
            into.dustMythic = m;
            into.autoSoldName = booster.getName() != null ? booster.getName() : "Booster";
            into.message = "Overflow full — auto-sold " + into.autoSoldName
                    + " to dust (C" + c + " U" + u + " R" + r + " M" + m + ")";
        }
    }

    public int getMasterySurgePicksUnspent() {
        return masterySurgePicksUnspent;
    }

    public int getDuelPerkSlotBonus() {
        return duelPerkSlotBonus;
    }

    public int getToolEnchantSocketBonus() {
        return toolEnchantSocketBonus;
    }

    /** Grant Mastery Surge picks when a set is mastered (Ascendant). */
    public void grantMasterySurgePicks() {
        if (!Config.ascendant())
            return;
        ConfigData cfg = safeConfigData();
        int n = Math.max(1, cfg.masterySurgePicks);
        masterySurgePicksUnspent += n;
        notifyInventory("[GOLD]Mastery Surge![] " + n + " upgrade pick"
                + (n == 1 ? "" : "s") + " — open Skills to choose.");
    }

    /**
     * Spend one Mastery Surge pick on {@code optionId} from mastery_surge.json.
     * @return false if no picks / unknown / unavailable option
     */
    public boolean spendMasterySurgePick(String optionId) {
        if (!Config.ascendant() || masterySurgePicksUnspent <= 0 || optionId == null)
            return false;
        MasterySurgeData opt = MasterySurgeListData.get(optionId);
        if (opt == null)
            return false;
        String effect = opt.effect != null ? opt.effect : opt.id;
        switch (effect) {
            case "craft_pouch_next":
                if (bags.getCraftPouchTier().isBottomless())
                    return false;
                if (!bags.upgradeCraftPouchTier())
                    return false;
                break;
            case "duel_perk_slot":
                duelPerkSlotBonus++;
                break;
            case "tool_enchant_socket":
                toolEnchantSocketBonus++;
                break;
            case "overflow_cap":
                bags.addOverflowCapBonus(1);
                break;
            default:
                return false;
        }
        masterySurgePicksUnspent--;
        notifyInventory("Mastery Surge: " + opt.getDisplayName());
        return true;
    }

    /** Options currently offerable for an unspent Mastery Surge pick. */
    public List<MasterySurgeData> availableMasterySurgeOptions() {
        List<MasterySurgeData> out = new ArrayList<>();
        if (!Config.ascendant())
            return out;
        for (MasterySurgeData d : MasterySurgeListData.getAll()) {
            if (d == null)
                continue;
            String effect = d.effect != null ? d.effect : d.id;
            if ("craft_pouch_next".equals(effect)) {
                if (bags.getCraftPouchTier().isBottomless())
                    continue;
                // Always include next pouch tier when available.
                out.add(0, d);
                continue;
            }
            out.add(d);
        }
        // Ensure craft_pouch_next is first when present.
        out.sort((a, b) -> {
            boolean aa = a != null && a.alwaysInclude;
            boolean bb = b != null && b.alwaysInclude;
            if (aa == bb) return 0;
            return aa ? -1 : 1;
        });
        return out;
    }

    /** True while Overflow is non-empty (blocks inn waypoint travel and planar portals). */
    public boolean isOverloaded() {
        return Config.ascendant() && bags.hasOverflow();
    }

    /**
     * Move one Overflow entry back into its bag/pouch when there is room.
     * @return true if retrieved
     */
    public boolean retrieveFromOverflow(int index) {
        if (!Config.ascendant())
            return false;
        OverflowEntry e = (index >= 0 && index < bags.overflowCount())
                ? bags.getOverflow().get(index) : null;
        if (e == null)
            return false;
        boolean fits;
        switch (e.kind) {
            case ITEM:
                if (e.item == null)
                    return false;
                if (InventoryBags.classifyItem(e.item) == InventoryBagType.CURRENCY)
                    fits = bags.fitsCurrencyItem(e.item, contestCurrencies, inventoryItems);
                else
                    fits = bags.fitsBackpack(e.item, inventoryItems, equippedItemIdSet(), toolbelt)
                            || InventoryBags.isCapacityExempt(e.item);
                if (!fits) {
                    notifyInventory("No room in bag — free space first.");
                    return false;
                }
                bags.takeOverflow(index);
                inventoryItems.add(e.item);
                return true;
            case BOOSTER:
                fits = bags.fitsBooster(boostersOwned);
                if (!fits) {
                    notifyInventory("No room in Packs — free space first.");
                    return false;
                }
                bags.takeOverflow(index);
                if (e.booster != null)
                    boostersOwned.add(e.booster);
                return true;
            case MATERIAL: {
                int room = bags.materialRoom(e.key, materials);
                if (room <= 0) {
                    notifyInventory("No room in Craft Pouch — free space or upgrade tier.");
                    return false;
                }
                int move = Math.min(room, e.amount);
                bags.takeOverflow(index);
                if (move > 0) {
                    int next = getMaterial(e.key) + move;
                    if (next > 0)
                        materials.put(e.key, next);
                    else
                        materials.remove(e.key);
                    onMaterialChangeList.emit();
                }
                int left = e.amount - move;
                if (left > 0)
                    bags.placeInOverflow(OverflowEntry.ofMaterial(e.key, left));
                return move > 0;
            }
            case CURRENCY: {
                // Partial retrieve like materials: move only what fits; never leave 0-amount entries.
                int room = bags.currencyRoom(e.key, contestCurrencies, inventoryItems);
                if (room <= 0) {
                    notifyInventory("No room in Currency pouch.");
                    return false;
                }
                int move = Math.min(room, e.amount);
                bags.takeOverflow(index);
                if (move > 0) {
                    int next = getContestCurrency(e.key) + move;
                    if (next > 0)
                        contestCurrencies.put(e.key, next);
                    else
                        contestCurrencies.remove(e.key);
                }
                int left = e.amount - move;
                if (left > 0)
                    bags.placeInOverflow(OverflowEntry.ofCurrency(e.key, left));
                return move > 0;
            }
            default:
                return false;
        }
    }

    /**
     * Removes {@code amount} of a material. Returns false if there is not enough (nothing taken).
     * Emits {@link #onMaterialChange} on success.
     */
    public boolean takeMaterial(String id, int amount) {
        if (id == null || amount <= 0)
            return false;
        int have = getMaterial(id);
        if (have < amount)
            return false;
        int left = have - amount;
        if (left <= 0)
            materials.remove(id);
        else
            materials.put(id, left);
        onMaterialChangeList.emit();
        return true;
    }

    public boolean isAutoSalvage() {
        return autoSalvage;
    }

    public void setAutoSalvage(boolean enabled) {
        autoSalvage = enabled && Config.ascendant();
    }

    public @Null EffectData getBlessing() {
        return blessing;
    }

    public Collection<Long> getEquippedItems() {
        return equippedItems.values();
    }

    public ColorSet getColorIdentity() {
        return colorIdentity;
    }

    public String getColorIdentityLong() {
        return colorIdentity.toString();
    }

    public Collection<PaperCard> getUnsupportedCards() {
        return unsupportedCards;
    }


    //Setters
    public void setWorldPosX(float worldPosX) {
        this.worldPosX = worldPosX;
    }

    public void setWorldPosY(float worldPosY) {
        this.worldPosY = worldPosY;
    }

    public void setColorIdentity(String C) {
        colorIdentity = ColorSet.fromNames(C.toCharArray());
    }

    public void setColorIdentity(ColorSet set) {
        this.colorIdentity = set;
    }

    @Override
    public void load(SaveFileData data) {
        boolean migration = false;
        clear(); // Reset player data.
        this.statistic.load(data.readSubData("statistic"));
        this.difficultyData.startingLife = data.readInt("startingLife");
        // Support for old typo
        if (data.containsKey("staringMoney")) {
            this.difficultyData.startingMoney = data.readInt("staringMoney");
        } else {
            this.difficultyData.startingMoney = data.readInt("startingMoney");
        }
        this.difficultyData.startingDifficulty = data.readBool("startingDifficulty");
        this.difficultyData.name = data.readString("difficultyName");
        this.difficultyData.enemyLifeFactor = data.readFloat("enemyLifeFactor");
        this.difficultyData.sellFactor = data.readFloat("sellFactor");
        if (this.difficultyData.sellFactor == 0)
            this.difficultyData.sellFactor = 0.2f;

        //BEGIN SPECIAL CASES
        //Previously these were not being read from or written to save files, causing defaults to appear after reload
        //Pull from config if appropriate
        DifficultyData configuredDifficulty = null;
        for (DifficultyData candidate : Config.instance().getConfigData().difficulties) {
            if (candidate.name.equals(this.difficultyData.name)) {
                configuredDifficulty = candidate;
                break;
            }
        }

        if (configuredDifficulty != null && (this.difficultyData.shardSellRatio == data.readFloat("shardSellRatio") || data.readFloat("shardSellRatio") == 0))
            this.difficultyData.shardSellRatio = configuredDifficulty.shardSellRatio;
        else
            this.difficultyData.shardSellRatio = data.readFloat("shardSellRatio");
        if (configuredDifficulty != null && !data.containsKey("goldLoss"))
            this.difficultyData.goldLoss = configuredDifficulty.goldLoss;
        else
            this.difficultyData.goldLoss = data.readFloat("goldLoss");
        if (configuredDifficulty != null && !data.containsKey("lifeLoss"))
            this.difficultyData.lifeLoss = configuredDifficulty.lifeLoss;
        else
            this.difficultyData.lifeLoss = data.readFloat("lifeLoss");
        if (configuredDifficulty != null && !data.containsKey("spawnRank"))
            this.difficultyData.spawnRank = configuredDifficulty.spawnRank;
        else
            this.difficultyData.spawnRank = data.readInt("spawnRank");
        // Always refresh rewardMaxFactor from plane config so economy rebalance
        // applies to existing saves without breaking load compatibility.
        if (configuredDifficulty != null)
            this.difficultyData.rewardMaxFactor = configuredDifficulty.rewardMaxFactor;
        else if (data.containsKey("rewardMaxFactor"))
            this.difficultyData.rewardMaxFactor = data.readFloat("rewardMaxFactor");
        // END SPECIAL CASES

        name = data.readString("name");
        heroRace = data.readInt("heroRace");
        avatarIndex = data.readInt("avatarIndex");
        isFemale = data.readBool("isFemale");

        String _mode = data.readString("adventure_mode");
        if (_mode == null)
            adventureMode = AdventureModes.Standard;
        else
            adventureMode = AdventureModes.valueOf(_mode);

        if (data.containsKey("colorIdentity")) {
            String temp = data.readString("colorIdentity");
            if (temp != null)
                setColorIdentity(temp);
            else
                colorIdentity = ColorSet.WUBRG;
        } else
            colorIdentity = ColorSet.WUBRG;

        gold = data.readInt("gold");
        maxLife = data.readInt("maxLife");
        life = data.readInt("life");
        shards = data.containsKey("shards") ? data.readInt("shards") : 0;
        if (data.containsKey("dust")) {
            Object raw = data.readObject("dust");
            if (raw instanceof int[] savedDust) {
                for (int i = 0; i < Math.min(dust.length, savedDust.length); i++)
                    dust[i] = Math.max(0, savedDust[i]);
            }
        }
        autoSalvage = data.containsKey("autoSalvage") && data.readBool("autoSalvage") && Config.ascendant();
        materials.clear();
        if (data.containsKey("materialIds") && data.containsKey("materialCounts")) {
            Object rawIds = data.readObject("materialIds");
            Object rawCounts = data.readObject("materialCounts");
            if (rawIds instanceof String[] ids && rawCounts instanceof int[] counts) {
                int n = Math.min(ids.length, counts.length);
                for (int i = 0; i < n; i++) {
                    if (ids[i] != null && !ids[i].isEmpty() && counts[i] > 0)
                        materials.put(ids[i], counts[i]);
                }
            }
        }
        // Package A → color-line renames (rough_stone→limestone, nightshade→bone_fragments, …).
        // Only once: some old ids (marble) are also new ids, so re-running would shift tiers every load.
        // Schema 3: ore line copper/iron/mithril/adamant → ore_iron/ore_mithral/ore_adamant/ore_rune (single-pass).
        int materialSchema = data.containsKey("materialSchema") ? data.readInt("materialSchema") : 0;
        loadedMaterialSchema = materialSchema;
        if (materialSchema < 2)
            MaterialListData.migrateMaterialCounts(materials);
        if (materialSchema < MaterialListData.MATERIAL_SCHEMA_ORE_LINE)
            MaterialListData.migrateOreLineMaterialCounts(materials);
        starterToolsGranted = data.containsKey("starterToolsGranted") && data.readBool("starterToolsGranted");
        toolbelt.clear();
        if (data.containsKey("toolbeltFamilies") && data.containsKey("toolbeltItems")) {
            Object rawFam = data.readObject("toolbeltFamilies");
            Object rawItems = data.readObject("toolbeltItems");
            if (rawFam instanceof String[] fams && rawItems instanceof String[] tItems) {
                int n = Math.min(fams.length, tItems.length);
                for (int i = 0; i < n; i++) {
                    if (fams[i] != null && !fams[i].isEmpty() && tItems[i] != null && !tItems[i].isEmpty())
                        toolbelt.put(fams[i], tItems[i]);
                }
            }
        }
        MaterialListData.migrateToolbeltFamilies(toolbelt);
        if (materialSchema < MaterialListData.MATERIAL_SCHEMA_ORE_LINE)
            MaterialListData.migrateOreLineToolbeltItems(toolbelt);
        badges.clear();
        if (data.containsKey("badgeIds")) {
            Object rawBadges = data.readObject("badgeIds");
            if (rawBadges instanceof String[] ids) {
                for (String id : ids) {
                    if (id != null && !id.isEmpty())
                        badges.add(id);
                }
            }
        }
        leagueCleared = data.containsKey("leagueCleared") && data.readBool("leagueCleared");
        if (data.containsKey("runFormat")) {
            String savedFormat = data.readString("runFormat");
            runFormat = (savedFormat != null && !savedFormat.isEmpty()) ? savedFormat : GymUtil.FORMAT_STANDARD;
        } else {
            runFormat = GymUtil.FORMAT_STANDARD;
        }
        gatherMethodRanks.clear();
        if (data.containsKey("gatherMethodSkills") && data.containsKey("gatherMethodRanks")) {
            Object rawSkills = data.readObject("gatherMethodSkills");
            Object rawRanks = data.readObject("gatherMethodRanks");
            if (rawSkills instanceof String[] sk && rawRanks instanceof int[] rk) {
                int n = Math.min(sk.length, rk.length);
                for (int i = 0; i < n; i++) {
                    if (sk[i] != null && !sk[i].isEmpty() && rk[i] > 0)
                        gatherMethodRanks.put(sk[i], rk[i]);
                }
            }
        }
        toolEnchantments.clear();
        if (data.containsKey("toolEnchantFamilies") && data.containsKey("toolEnchantIds")) {
            Object rawFam = data.readObject("toolEnchantFamilies");
            Object rawIds = data.readObject("toolEnchantIds");
            if (rawFam instanceof String[] fams && rawIds instanceof String[] ids) {
                int n = Math.min(fams.length, ids.length);
                for (int i = 0; i < n; i++) {
                    if (fams[i] == null || fams[i].isEmpty() || ids[i] == null || ids[i].isEmpty())
                        continue;
                    ArrayList<String> list = new ArrayList<>();
                    for (String part : ids[i].split(",")) {
                        String trimmed = part.trim();
                        if (!trimmed.isEmpty() && GatheringMethodListData.getEnchantment(trimmed) != null)
                            list.add(trimmed);
                    }
                    if (!list.isEmpty())
                        toolEnchantments.put(fams[i], list);
                }
            }
        }
        camps.clear();
        if (data.containsKey("campOutpostIds") && data.containsKey("campLevels")
                && data.containsKey("campLastUpdate")) {
            Object rawIds = data.readObject("campOutpostIds");
            Object rawLevels = data.readObject("campLevels");
            Object rawLast = data.readObject("campLastUpdate");
            if (rawIds instanceof String[] ids && rawLevels instanceof int[] levels) {
                int n = Math.min(ids.length, levels.length);
                double[] last = readCampLastUpdate(rawLast, n);
                // New format: per-material storage blobs "matId:amount,matId:amount".
                String[] storedBlobs = null;
                if (data.containsKey("campStoredByMaterial")) {
                    Object rawBlobs = data.readObject("campStoredByMaterial");
                    if (rawBlobs instanceof String[] blobs)
                        storedBlobs = blobs;
                }
                // Legacy format: single float amount per camp (converted to current tier material).
                float[] legacyStored = null;
                if (storedBlobs == null && data.containsKey("campStored")) {
                    Object rawStored = data.readObject("campStored");
                    if (rawStored instanceof float[] stored)
                        legacyStored = stored;
                }
                for (int i = 0; i < n; i++) {
                    if (ids[i] == null || ids[i].isEmpty() || levels[i] <= 0)
                        continue;
                    // MV1: bare outpost ids from old saves become home::<id>.
                    String key = ids[i].contains("::")
                            ? ids[i]
                            : campStorageKey(PlaneMeta.HOME_ID, ids[i]);
                    String bare = bareOutpostId(key);
                    GatheringMethodData.Outpost def = GatheringMethodListData.getOutpost(bare);
                    if (def == null)
                        continue;
                    CampState st = new CampState();
                    st.level = levels[i];
                    st.lastUpdatePlaySeconds = last != null && i < last.length ? Math.max(0d, last[i]) : 0d;
                    if (storedBlobs != null && i < storedBlobs.length)
                        parseCampStoredBlob(st, storedBlobs[i]);
                    else if (legacyStored != null && i < legacyStored.length && legacyStored[i] > 0f) {
                        GatheringMethodData.OutpostLevel lvl = def.levelData(st.level);
                        String matId = lvl != null ? lvl.materialId : null;
                        if (matId != null)
                            st.addStored(matId, legacyStored[i]);
                    }
                    camps.put(key, st);
                }
            }
        }
        if (materialSchema < MaterialListData.MATERIAL_SCHEMA_ORE_LINE) {
            for (CampState st : camps.values()) {
                if (st != null)
                    MaterialListData.migrateOreLineMaterialFloatCounts(st.storedByMaterial);
            }
        }
        adventurePlaySeconds = 0d;
        // Double key avoids mis-reading old float-encoded adventurePlaySeconds blobs.
        if (data.containsKey("adventurePlaySecondsD"))
            adventurePlaySeconds = Math.max(0d, data.readDouble("adventurePlaySecondsD"));
        else if (data.containsKey("adventurePlaySeconds"))
            adventurePlaySeconds = Math.max(0d, data.readFloat("adventurePlaySeconds"));
        worldPosX = data.readFloat("worldPosX");
        worldPosY = data.readFloat("worldPosY");

        if (data.containsKey("blessing")) {
            EffectData temp = (EffectData) data.readObject("blessing");
            if (temp != null)
                blessing = temp;
        }

        if (data.containsKey("inventory")) {
            try {
                ItemData[] inv = (ItemData[]) data.readObject("inventory");
                for (int i = 0; i < inv.length; i++) {
                    ItemData itemData = inv[i];
                    if (itemData != null) {
                        if (materialSchema < MaterialListData.MATERIAL_SCHEMA_ORE_LINE)
                            MaterialListData.migrateOreLineItemInstance(itemData);
                        inventoryItems.add(itemData);
                    }
                }
            } catch (Exception ignored) {
                migration = true;
                // migrate from string..
                try {
                    String[] inv = (String[]) data.readObject("inventory");
                    // Prevent items with wrong names from getting through. Hell breaks loose if it causes null pointers.
                    // This only needs to be done on load.
                    for (int j = 0; j < inv.length; j++) {
                        String i = inv[j];
                        ItemData itemData = ItemListData.getItem(i);
                        if (itemData != null) {
                            inventoryItems.add(itemData);
                        } else {
                            System.err.printf("Cannot find item name %s\n", i);
                            // Allow official© permission for the player to get a refund. We will allow it this time.
                            // TODO: Divine retribution if the player refunds too much. Use the orbital laser cannon.
                            System.out.println("Developers have blessed you! You are allowed to cheat the cost of the item back!");
                        }
                    }
                } catch (Exception e) {
                    //shouldn't crash if coming from string...
                    e.printStackTrace();
                }
            }
        }
        if (data.containsKey("equippedSlots") && data.containsKey("equippedItems")) {
            try {
                String[] slots = (String[]) data.readObject("equippedSlots");
                Long[] items = (Long[]) data.readObject("equippedItems");

                assert (slots.length == items.length);
                // Prevent items with wrong names. If it triggered in inventory, it'll trigger here as well.
                for (int i = 0; i < slots.length; i++) {
                    ItemData itemData = getItemFromInventory(items[i]);
                    if (itemData != null) {
                        if (itemData.longID == null)
                            itemData = itemData.clone();
                        if (itemData.longID != null) {
                            itemData.isEquipped = true;
                            equippedItems.put(slots[i], itemData.longID);
                        } else {
                            itemData.isEquipped = false;
                            System.err.println("Missing ID: " + itemData.name);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (data.containsKey("boosters")) {
            Deck[] decks = (Deck[]) data.readObject("boosters");
            if (decks != null) {
                for (Deck d : decks) {
                    if (d != null && !d.isEmpty()) {
                        boostersOwned.add(d);
                    } else {
                        System.err.printf("Null or empty booster %s\n", d);
                        System.out.println("You have an empty booster pack in your inventory.");
                    }
                }
            } else {
                System.err.println("Deck[] is null! [boosters]");
            }
        }

        deck = new Deck(data.readString("deckName"));
        CardPool deckCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("deckCards")));
        deck.getMain().addAll(deckCards.getFilteredPool(isValid));
        unsupportedCards.addAll(deckCards.getFilteredPool(isUnsupported).toFlatList());
        if (data.containsKey("sideBoardCards")) {
            CardPool sideBoardCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("sideBoardCards")));
            deck.getOrCreate(DeckSection.Sideboard).addAll(sideBoardCards.getFilteredPool(isValid));
            unsupportedCards.addAll(sideBoardCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("attractionDeckCards")) {
            CardPool attractionDeckCards = CardPool.fromCardList(List.of((String[]) data.readObject("attractionDeckCards")));
            deck.getOrCreate(DeckSection.Attractions).addAll(attractionDeckCards.getFilteredPool(isValid));
            unsupportedCards.addAll(attractionDeckCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("contraptionDeckCards")) {//TODO: Generalize this. Can't we just serialize the whole deck?
            CardPool contraptionDeckCards = CardPool.fromCardList(List.of((String[]) data.readObject("contraptionDeckCards")));
            deck.getOrCreate(DeckSection.Contraptions).addAll(contraptionDeckCards.getFilteredPool(isValid));
            unsupportedCards.addAll(contraptionDeckCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("commanderCards")) {
            CardPool commanderCards = CardPool.fromCardList(List.of((String[]) data.readObject("commanderCards")));
            deck.getOrCreate(DeckSection.Commander).addAll(commanderCards.getFilteredPool(isValid));
            unsupportedCards.addAll(commanderCards.getFilteredPool(isUnsupported).toFlatList());
        }
        if (data.containsKey("characterFlagsKey") && data.containsKey("characterFlagsValue")) {
            String[] keys = (String[]) data.readObject("characterFlagsKey");
            Byte[] values = (Byte[]) data.readObject("characterFlagsValue");
            assert (keys.length == values.length);
            for (int i = 0; i < keys.length; i++) {
                characterFlags.put(keys[i], values[i]);
            }
        }
        boolean hadSkillXp = data.containsKey("skillXpKeys");
        skills.load(hadSkillXp ? (String[]) data.readObject("skillXpKeys") : null,
                data.containsKey("skillXpValues") ? (Integer[]) data.readObject("skillXpValues") : null);
        skills.loadTree(
                data.containsKey("skillTreeNodeIds") ? (String[]) data.readObject("skillTreeNodeIds") : null,
                data.containsKey("skillTreeNodeRanks") ? (Integer[]) data.readObject("skillTreeNodeRanks") : null,
                data.containsKey("skillTreeSlotted") ? (String[]) data.readObject("skillTreeSlotted") : null,
                data.containsKey("skillCapes") ? (String[]) data.readObject("skillCapes") : null,
                data.containsKey("skillTreeRespecCount") ? data.readInt("skillTreeRespecCount") : null,
                data.containsKey("skillTreeFreeRespec") && data.readBool("skillTreeFreeRespec"),
                data.containsKey("colorPerksRefunded") && data.readBool("colorPerksRefunded"));
        if (!data.containsKey("skillTreeNodeIds"))
            skills.migrateLegacyColorPerksIfNeeded(hadSkillXp);
        standardWindow.load(data.containsKey("standardSets") ? (String[]) data.readObject("standardSets") : null,
                data.containsKey("standardSetUnlocked") && data.readBool("standardSetUnlocked"),
                data.containsKey("standardChoicePending") && data.readBool("standardChoicePending"),
                data.containsKey("unlockedHistory") ? (String[]) data.readObject("unlockedHistory") : null);

        if (data.containsKey("questFlagsKey") && data.containsKey("questFlagsValue")) {
            String[] keys = (String[]) data.readObject("questFlagsKey");
            Byte[] values = (Byte[]) data.readObject("questFlagsValue");
            assert (keys.length == values.length);
            for (int i = 0; i < keys.length; i++) {
                questFlags.put(keys[i], values[i]);
            }
        }
        if (data.containsKey("quests")) {
            quests.clear();
            Object[] q = (Object[]) data.readObject("quests");
            if (q != null) {
                for (Object itsReallyAQuest : q)
                    quests.add((AdventureQuestData) itsReallyAQuest);
            }
        }
        if (data.containsKey("events")) {
            events.clear();
            Object[] q = (Object[]) data.readObject("events");
            if (q != null) {
                for (Object itsReallyAnEvent : q) {
                    events.add((AdventureEventData) itsReallyAnEvent);
                }
            }
        }

        // Set max deck count to either the value in the config or the current player deck count and then ensure it is bound by 20 and 99
        this.maxDeckCount = Math.min(Math.max(Math.max(data.containsKey("deckCount") ? data.readInt("deckCount") : 20, Config.instance().getConfigData().maxNumberOfDecks), 20), 99);


        // Load decks
        // Check if this save has dynamic deck count, use set-count load if not
        boolean hasDynamicDeckCount = data.containsKey("deckCount");
        if (hasDynamicDeckCount) {
            int dynamicDeckCount = data.readInt("deckCount");
            // In case the save had previously saved more decks than the current version allows (in case of the max being lowered)
            dynamicDeckCount = Math.min(maxDeckCount, dynamicDeckCount);
            for (int i = 0; i < dynamicDeckCount; i++){
                // The first x elements are pre-created
                if (i < MIN_DECK_COUNT) {
                    decks.set(i, new Deck(data.readString("deck_name_" + i)));
                }
                else {
                    decks.add(new Deck(data.readString("deck_name_" + i)));
                }
                CardPool mainCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("deck_" + i)));
                decks.get(i).getMain().addAll(mainCards.getFilteredPool(isValid));
                unsupportedCards.addAll(mainCards.getFilteredPool(isUnsupported).toFlatList());
                if (data.containsKey("sideBoardCards_" + i)) {
                    CardPool sideBoardCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("sideBoardCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Sideboard).addAll(sideBoardCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(sideBoardCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("attractionDeckCards_" + i)) {
                    CardPool attractionCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("attractionDeckCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Attractions).addAll(attractionCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(attractionCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("contraptionDeckCards_" + i)) {
                    CardPool contraptionCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("contraptionDeckCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Contraptions).addAll(contraptionCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(contraptionCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("commanderCards_" + i)) {
                    CardPool commanderCards = CardPool.fromCardList(List.of((String[]) data.readObject("commanderCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Commander).addAll(commanderCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(commanderCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("deckCommander_" + i) && data.readBool("deckCommander_" + i))
                    decks.get(i).getTags().add(COMMANDER_DECK_TAG);
                if (data.containsKey("deckHistoric_" + i) && data.readBool("deckHistoric_" + i))
                    decks.get(i).getTags().add(HISTORIC_DECK_TAG);
            }
            // In case we allow removing decks from the deck selection GUI, populate up to the minimum
            for (int i = dynamicDeckCount++; i < MIN_DECK_COUNT; i++) {
                decks.set(i, new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
            }
        // Legacy load
        } else {
            for (int i = 0; i < MIN_DECK_COUNT; i++) {
                if (!data.containsKey("deck_name_" + i)) {
                    if (i == 0) decks.set(i, deck);
                    else decks.set(i, new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
                    continue;
                }
                decks.set(i, new Deck(data.readString("deck_name_" + i)));
                CardPool mainCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("deck_" + i)));
                decks.get(i).getMain().addAll(mainCards.getFilteredPool(isValid));
                unsupportedCards.addAll(mainCards.getFilteredPool(isUnsupported).toFlatList());
                if (data.containsKey("sideBoardCards_" + i)) {
                    CardPool sideBoardCards = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("sideBoardCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Sideboard).addAll(sideBoardCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(sideBoardCards.getFilteredPool(isUnsupported).toFlatList());
                }
                if (data.containsKey("commanderCards_" + i)) {
                    CardPool commanderCards = CardPool.fromCardList(List.of((String[]) data.readObject("commanderCards_" + i)));
                    decks.get(i).getOrCreate(DeckSection.Commander).addAll(commanderCards.getFilteredPool(isValid));
                    unsupportedCards.addAll(commanderCards.getFilteredPool(isUnsupported).toFlatList());
                }
            }
        }

        // Load deck loadouts (equipment tied to each deck)
        for (int i = 0; i < getDeckCount(); i++) {
            HashMap<String, Long> loadout = null;
            if (data.containsKey("deckLoadout_slots_" + i) && data.containsKey("deckLoadout_items_" + i)) {
                try {
                    String[] loadoutSlots = (String[]) data.readObject("deckLoadout_slots_" + i);
                    Long[] loadoutItems = (Long[]) data.readObject("deckLoadout_items_" + i);
                    if (loadoutSlots.length == loadoutItems.length) {
                        loadout = new HashMap<>();
                        for (int j = 0; j < loadoutSlots.length; j++) {
                            loadout.put(loadoutSlots[j], loadoutItems[j]);
                        }
                    }
                } catch (Exception ignored) {}
            }
            deckLoadouts.add(loadout);
        }

        // Use false to skip loadout switching during load (equippedItems already loaded correctly above)
        setSelectedDeckSlot(data.readInt("selectedDeckIndex"), false);
        CardPool cardPool = CardPool.fromCardList(Lists.newArrayList((String[]) data.readObject("cards")));
        cards.addAll(cardPool.getFilteredPool(isValid));
        unsupportedCards.addAll(cardPool.getFilteredPool(isUnsupported).toFlatList());

        if (data.containsKey("newCards")) {
            InventoryItem[] items = (InventoryItem[]) data.readObject("newCards");
            for (InventoryItem item : items) {
                if (item instanceof PaperCard pc) {
                    if (isUnsupported.test(pc))
                        unsupportedCards.add(pc);
                    else
                        newCards.add(pc);
                }
            }
        }
        if (data.containsKey("noSellCards")) {
            // Legacy list of unsellable cards. Now done via CardRequest flags. Convert the corresponding cards.
            PaperCard[] items = (PaperCard[]) data.readObject("noSellCards");
            CardPool noSellPool = new CardPool();
            for (PaperCard pc : items) {
                if (isUnsupported.test(pc))
                    unsupportedCards.add(pc);
                else
                    noSellPool.add(pc);
            }
            for (Map.Entry<PaperCard, Integer> noSellEntry : noSellPool) {
                PaperCard item = noSellEntry.getKey();
                if (item == null)
                    continue;
                int totalCopies = cards.count(item);
                int noSellCopies = Math.min(noSellEntry.getValue(), totalCopies);
                if (!cards.remove(item, noSellCopies)) {
                    System.err.printf("Failed to update noSellValue flag - %s%n", item);
                    continue;
                }

                int remainingSellableCopies = totalCopies - noSellCopies;

                PaperCard noSellVersion = item.getNoSellVersion();
                cards.add(noSellVersion, noSellCopies);

                System.out.printf("Converted legacy noSellCards item - %s (%d / %d copies)%n", item, noSellCopies, totalCopies);

                // Also go through their decks and update cards there.
                for (Deck deck : decks) {
                    int inUse = 0;
                    for (Map.Entry<DeckSection, CardPool> section : deck) {
                        CardPool pool = section.getValue();
                        inUse += pool.count(item);
                        if(inUse > remainingSellableCopies) {
                            int toConvert = inUse - remainingSellableCopies;
                            pool.remove(item, toConvert);
                            pool.add(noSellVersion, toConvert);
                            System.out.printf("- Converted %d copies in deck - %s/%s%n", toConvert, deck.getName(), section.getKey());
                        }
                    }
                }

            }
        }
        if (data.containsKey("autoSellCards")) {
            PaperCard[] items = (PaperCard[]) data.readObject("autoSellCards");
            for (PaperCard pc : items) {
                if (isUnsupported.test(pc))
                    unsupportedCards.add(pc);
                else
                    autoSellCards.add(pc);
            }
        }
        if (data.containsKey("vaultCards")) {
            PaperCard[] items = (PaperCard[]) data.readObject("vaultCards");
            for (PaperCard pc : items) {
                if (!isUnsupported.test(pc))
                    vaultCards.add(pc);
            }
        }
        if (data.containsKey("favoriteCards")) {
            PaperCard[] items = (PaperCard[]) data.readObject("favoriteCards");
            for (PaperCard pc : items) {
                if (isUnsupported.test(pc))
                    unsupportedCards.add(pc);
                else
                    favoriteCards.add(pc);
            }
        }

        fantasyMode = data.containsKey("fantasyMode") && data.readBool("fantasyMode");
        announceFantasy = data.containsKey("announceFantasy") && data.readBool("announceFantasy");
        usingCustomDeck = data.containsKey("usingCustomDeck") && data.readBool("usingCustomDeck");
        announceCustom = data.containsKey("announceCustom") && data.readBool("announceCustom");
        if (migration) {
            getCurrentGameStage().setExtraAnnouncement(Forge.getLocalizer().getMessage("lblDataMigrationMsg"));
        }

        // Bags (and Overflow) must be loaded before starter tools are granted, or a tool sent to Overflow is wiped.
        loadInventoryBags(data, materialSchema);
        ensureStarterGatheringTools();

        RewardData.invalidateCardPool();
        onLifeTotalChangeList.emit();
        onShardsChangeList.emit();
        onDustChangeList.emit();
        onMaterialChangeList.emit();
        onGoldChangeList.emit();
        onBlessing.emit();
    }

    /**
     * INV1: load per-bag capacities and contest currencies. Old saves without bag keys
     * keep Java/config defaults; flat inventory is auto-sorted into bags by classification
     * (nothing is deleted; over-capacity is allowed and shown).
     */
    private static ConfigData safeConfigData() {
        try {
            if (Config.instance() != null && Config.instance().getConfigData() != null)
                return Config.instance().getConfigData();
        } catch (Exception ignored) {
            // Config may be unavailable during early clear/tests
        }
        return new ConfigData();
    }

    private void loadInventoryBags(SaveFileData data, int materialSchema) {
        ConfigData cfg = safeConfigData();
        bags.resetToDefaults(cfg);
        if (data != null && data.containsKey("bagTypes") && data.containsKey("bagSlots")) {
            Object rawTypes = data.readObject("bagTypes");
            Object rawSlots = data.readObject("bagSlots");
            Object rawStacks = data.containsKey("bagMaxStacks") ? data.readObject("bagMaxStacks") : null;
            if (rawTypes instanceof String[] types && rawSlots instanceof int[] slots) {
                int[] stacks = rawStacks instanceof int[] s ? s : null;
                bags.load(types, slots, stacks, cfg);
            }
        }
        Integer tier = null;
        Integer oCap = null;
        if (data != null && data.containsKey("craftPouchTier"))
            tier = data.readInt("craftPouchTier");
        if (data != null && data.containsKey("overflowCap"))
            oCap = data.readInt("overflowCap");
        bags.loadCraftPouchAndOverflow(tier, oCap);
        if (data != null && data.containsKey("overflowEntries")) {
            try {
                OverflowEntry[] entries = (OverflowEntry[]) data.readObject("overflowEntries");
                if (materialSchema < MaterialListData.MATERIAL_SCHEMA_ORE_LINE && entries != null) {
                    for (OverflowEntry e : entries) {
                        if (e == null)
                            continue;
                        if (e.kind == OverflowEntry.Kind.MATERIAL && e.key != null)
                            e.key = MaterialListData.migrateOreLineMaterialId(e.key);
                        else if (e.kind == OverflowEntry.Kind.ITEM) {
                            if (e.item != null) {
                                MaterialListData.migrateOreLineItemInstance(e.item);
                                e.key = e.item.name;
                            } else if (e.key != null) {
                                e.key = MaterialListData.migrateOreLineItemName(e.key);
                            }
                        }
                    }
                }
                bags.loadOverflowEntries(entries);
            } catch (Exception ignored) {
                bags.clearOverflow();
            }
        } else {
            bags.clearOverflow();
        }
        masterySurgePicksUnspent = data != null && data.containsKey("masterySurgePicksUnspent")
                ? Math.max(0, data.readInt("masterySurgePicksUnspent")) : 0;
        duelPerkSlotBonus = data != null && data.containsKey("duelPerkSlotBonus")
                ? Math.max(0, data.readInt("duelPerkSlotBonus")) : 0;
        toolEnchantSocketBonus = data != null && data.containsKey("toolEnchantSocketBonus")
                ? Math.max(0, data.readInt("toolEnchantSocketBonus")) : 0;
        contestCurrencies.clear();
        if (data != null && data.containsKey("contestCurrencyIds") && data.containsKey("contestCurrencyCounts")) {
            Object rawIds = data.readObject("contestCurrencyIds");
            Object rawCounts = data.readObject("contestCurrencyCounts");
            if (rawIds instanceof String[] ids && rawCounts instanceof int[] counts) {
                int n = Math.min(ids.length, counts.length);
                for (int i = 0; i < n; i++) {
                    if (ids[i] != null && !ids[i].isEmpty() && counts[i] > 0)
                        contestCurrencies.put(ids[i], counts[i]);
                }
            }
        }
        if (Config.ascendant())
            migrateOverCapacityBags();
    }

    /**
     * Old over-capacity saves: move excess into Overflow (auto-sell past Overflow cap).
     * Materials already in the materials map become Craft Pouch contents; excess → Overflow.
     */
    private void migrateOverCapacityBags() {
        List<Deck> boosterList = new ArrayList<>();
        for (Deck d : boostersOwned)
            boosterList.add(d);
        List<OverflowEntry> autoSell = bags.migrateOverCapacity(
                inventoryItems, equippedItemIdSet(), toolbelt, boosterList, materials, contestCurrencies);
        boostersOwned.clear();
        for (Deck d : boosterList)
            boostersOwned.add(d);
        for (OverflowEntry e : autoSell) {
            GrantResult r = new GrantResult();
            r.fate = GrantResult.Fate.AUTO_SOLD;
            autoSellOverflowEntry(e, r);
            notifyInventory(r.message);
        }
    }

    @Override
    public SaveFileData save() {
        SaveFileData data = new SaveFileData();

        data.store("statistic", this.statistic.save());
        data.store("startingLife", this.difficultyData.startingLife);
        data.store("startingMoney", this.difficultyData.startingMoney);
        data.store("startingDifficulty", this.difficultyData.startingDifficulty);
        data.store("difficultyName", this.difficultyData.name);
        data.store("enemyLifeFactor", this.difficultyData.enemyLifeFactor);
        data.store("sellFactor", this.difficultyData.sellFactor);
        data.store("shardSellRatio", this.difficultyData.shardSellRatio);
        data.store("goldLoss", this.difficultyData.goldLoss);
        data.store("lifeLoss", this.difficultyData.lifeLoss);
        data.store("spawnRank", this.difficultyData.spawnRank);
        data.store("rewardMaxFactor", this.difficultyData.rewardMaxFactor);

        data.store("name", name);
        data.store("heroRace", heroRace);
        data.store("avatarIndex", avatarIndex);
        data.store("isFemale", isFemale);
        data.store("colorIdentity", colorIdentity.getColor());

        data.store("adventure_mode", adventureMode.toString());

        data.store("fantasyMode", fantasyMode);
        data.store("announceFantasy", announceFantasy);
        data.store("usingCustomDeck", usingCustomDeck);
        data.store("announceCustom", announceCustom);

        data.store("worldPosX", worldPosX);
        data.store("worldPosY", worldPosY);
        data.store("gold", gold);
        data.store("life", life);
        data.store("maxLife", maxLife);
        data.store("shards", shards);
        data.storeObject("dust", Arrays.copyOf(dust, dust.length));
        data.store("autoSalvage", autoSalvage);
        {
            String[] materialIds = materials.keySet().toArray(new String[0]);
            int[] materialCounts = new int[materialIds.length];
            for (int i = 0; i < materialIds.length; i++)
                materialCounts[i] = materials.getOrDefault(materialIds[i], 0);
            data.storeObject("materialIds", materialIds);
            data.store("materialSchema", MaterialListData.MATERIAL_SCHEMA_ORE_LINE);
            data.store("starterToolsGranted", starterToolsGranted);
            data.storeObject("materialCounts", materialCounts);
        }
        {
            // INV1 bag capacities (contents remain in inventory / boosters / materials).
            data.storeObject("bagTypes", new String[]{
                    InventoryBagType.BACKPACK.name(), InventoryBagType.PACKS.name(),
                    InventoryBagType.CURRENCY.name(), InventoryBagType.MATERIALS.name()
            });
            data.storeObject("bagSlots", new int[]{
                    bags.getSlots(InventoryBagType.BACKPACK), bags.getSlots(InventoryBagType.PACKS),
                    bags.getSlots(InventoryBagType.CURRENCY), bags.getSlots(InventoryBagType.MATERIALS)
            });
            data.storeObject("bagMaxStacks", new int[]{
                    bags.getMaxStack(InventoryBagType.BACKPACK), bags.getMaxStack(InventoryBagType.PACKS),
                    bags.getMaxStack(InventoryBagType.CURRENCY), bags.getMaxStack(InventoryBagType.MATERIALS)
            });
            data.store("craftPouchTier", bags.getCraftPouchTierIndex());
            data.store("overflowCap", bags.getOverflowCap());
            data.storeObject("overflowEntries", bags.getOverflow().toArray(new OverflowEntry[0]));
            data.store("masterySurgePicksUnspent", masterySurgePicksUnspent);
            data.store("duelPerkSlotBonus", duelPerkSlotBonus);
            data.store("toolEnchantSocketBonus", toolEnchantSocketBonus);
            String[] cIds = contestCurrencies.keySet().toArray(new String[0]);
            int[] cCounts = new int[cIds.length];
            for (int i = 0; i < cIds.length; i++)
                cCounts[i] = contestCurrencies.getOrDefault(cIds[i], 0);
            data.storeObject("contestCurrencyIds", cIds);
            data.storeObject("contestCurrencyCounts", cCounts);
        }
        {
            String[] fams = toolbelt.keySet().toArray(new String[0]);
            String[] tItems = new String[fams.length];
            for (int i = 0; i < fams.length; i++)
                tItems[i] = toolbelt.get(fams[i]);
            data.storeObject("toolbeltFamilies", fams);
            data.storeObject("toolbeltItems", tItems);
        }
        data.storeObject("badgeIds", badges.toArray(new String[0]));
        data.store("leagueCleared", leagueCleared);
        data.store("runFormat", runFormat != null ? runFormat : GymUtil.FORMAT_STANDARD);
        {
            String[] sk = gatherMethodRanks.keySet().toArray(new String[0]);
            int[] rk = new int[sk.length];
            for (int i = 0; i < sk.length; i++)
                rk[i] = gatherMethodRanks.getOrDefault(sk[i], 0);
            data.storeObject("gatherMethodSkills", sk);
            data.storeObject("gatherMethodRanks", rk);
        }
        {
            String[] fams = toolEnchantments.keySet().toArray(new String[0]);
            String[] ids = new String[fams.length];
            for (int i = 0; i < fams.length; i++) {
                ArrayList<String> list = toolEnchantments.get(fams[i]);
                ids[i] = list == null || list.isEmpty() ? "" : String.join(",", list);
            }
            data.storeObject("toolEnchantFamilies", fams);
            data.storeObject("toolEnchantIds", ids);
        }
        {
            String[] ids = camps.keySet().toArray(new String[0]);
            int[] levels = new int[ids.length];
            String[] storedBlobs = new String[ids.length];
            double[] last = new double[ids.length];
            for (int i = 0; i < ids.length; i++) {
                CampState st = camps.get(ids[i]);
                levels[i] = st != null ? st.level : 0;
                storedBlobs[i] = st != null ? formatCampStoredBlob(st) : "";
                last[i] = st != null ? st.lastUpdatePlaySeconds : 0d;
            }
            data.storeObject("campOutpostIds", ids);
            data.storeObject("campLevels", levels);
            data.storeObject("campStoredByMaterial", storedBlobs);
            data.storeObject("campLastUpdate", last);
        }
        data.store("adventurePlaySecondsD", adventurePlaySeconds);
        data.store("deckName", deck.getName());

        data.storeObject("inventory", inventoryItems.toArray(new ItemData[0]));

        ArrayList<String> slots = new ArrayList<>();
        ArrayList<Long> items = new ArrayList<>();
        for (Map.Entry<String, Long> entry : equippedItems.entrySet()) {
            slots.add(entry.getKey());
            items.add(entry.getValue());
        }
        data.storeObject("equippedSlots", slots.toArray(new String[0]));
        data.storeObject("equippedItems", items.toArray(new Long[0]));

        data.storeObject("boosters", boostersOwned.toArray(Deck.class));

        data.storeObject("blessing", blessing);

        // Save character flags.
        ArrayList<String> characterFlagsKey = new ArrayList<>();
        ArrayList<Byte> characterFlagsValue = new ArrayList<>();
        for (Map.Entry<String, Byte> entry : characterFlags.entrySet()) {
            characterFlagsKey.add(entry.getKey());
            characterFlagsValue.add(entry.getValue());
        }
        data.storeObject("characterFlagsKey", characterFlagsKey.toArray(new String[0]));
        data.storeObject("characterFlagsValue", characterFlagsValue.toArray(new Byte[0]));
        data.storeObject("skillXpKeys", skills.saveKeys());
        data.storeObject("skillXpValues", skills.saveValues());
        data.storeObject("skillTreeNodeIds", skills.saveTreeNodeIds());
        data.storeObject("skillTreeNodeRanks", skills.saveTreeNodeRanks());
        data.storeObject("skillTreeSlotted", skills.saveSlottedPerks());
        data.storeObject("skillCapes", skills.saveSkillCapes());
        data.store("skillTreeRespecCount", skills.getRespecCount());
        data.store("skillTreeFreeRespec", skills.isFreeRespecPending());
        data.store("colorPerksRefunded", skills.isColorPerksRefunded());
        data.storeObject("standardSets", standardWindow.saveSets());
        data.store("standardSetUnlocked", standardWindow.isSetUnlockedThisWorld());
        data.store("standardChoicePending", standardWindow.isChoicePending());
        data.storeObject("unlockedHistory", standardWindow.saveHistory());

        // Save quest flags.
        ArrayList<String> questFlagsKey = new ArrayList<>();
        ArrayList<Byte> questFlagsValue = new ArrayList<>();
        for (Map.Entry<String, Byte> entry : questFlags.entrySet()) {
            questFlagsKey.add(entry.getKey());
            questFlagsValue.add(entry.getValue());
        }
        data.storeObject("questFlagsKey", questFlagsKey.toArray(new String[0]));
        data.storeObject("questFlagsValue", questFlagsValue.toArray(new Byte[0]));
        data.storeObject("quests", quests.toArray());
        data.storeObject("events", events.toArray());

        data.storeObject("deckCards", deck.getMain().toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Sideboard) != null)
            data.storeObject("sideBoardCards", deck.get(DeckSection.Sideboard).toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Attractions) != null)
            data.storeObject("attractionDeckCards", deck.get(DeckSection.Attractions).toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Contraptions) != null)
            data.storeObject("contraptionDeckCards", deck.get(DeckSection.Contraptions).toCardList("\n").split("\n"));
        if (deck.get(DeckSection.Commander) != null)
            data.storeObject("commanderCards", deck.get(DeckSection.Commander).toCardList("\n").split("\n"));

        // save decks dynamically
        data.store("deckCount", getDeckCount());
        for (int i = 0; i < getDeckCount(); i++) {
            data.store("deck_name_" + i, decks.get(i).getName());
            data.storeObject("deck_" + i, decks.get(i).getMain().toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Sideboard) != null)
                data.storeObject("sideBoardCards_" + i, decks.get(i).get(DeckSection.Sideboard).toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Attractions) != null)
                data.storeObject("attractionDeckCards_" + i, decks.get(i).get(DeckSection.Attractions).toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Contraptions) != null)
                data.storeObject("contraptionDeckCards_" + i, decks.get(i).get(DeckSection.Contraptions).toCardList("\n").split("\n"));
            if (decks.get(i).get(DeckSection.Commander) != null)
                data.storeObject("commanderCards_" + i, decks.get(i).get(DeckSection.Commander).toCardList("\n").split("\n"));
            data.store("deckCommander_" + i, decks.get(i).getTags().contains(COMMANDER_DECK_TAG));
            data.store("deckHistoric_" + i, decks.get(i).getTags().contains(HISTORIC_DECK_TAG));
        }

        // Save deck loadouts (equipment tied to each deck)
        // First, save current equipment to current deck's loadout
        ensureDeckLoadoutsSize();
        deckLoadouts.set(selectedDeckIndex, new HashMap<>(equippedItems));
        for (int i = 0; i < getDeckCount(); i++) {
            HashMap<String, Long> loadout = i < deckLoadouts.size() ? deckLoadouts.get(i) : null;
            if (loadout != null) {
                ArrayList<String> loadoutSlots = new ArrayList<>();
                ArrayList<Long> loadoutItems = new ArrayList<>();
                for (Map.Entry<String, Long> entry : loadout.entrySet()) {
                    loadoutSlots.add(entry.getKey());
                    loadoutItems.add(entry.getValue());
                }
                data.storeObject("deckLoadout_slots_" + i, loadoutSlots.toArray(new String[0]));
                data.storeObject("deckLoadout_items_" + i, loadoutItems.toArray(new Long[0]));
            }
        }

        data.store("selectedDeckIndex", selectedDeckIndex);
        data.storeObject("cards", cards.toCardList("\n").split("\n"));

        data.storeObject("newCards", newCards.toFlatList().toArray(new PaperCard[0]));
        data.storeObject("autoSellCards", autoSellCards.toFlatList().toArray(new PaperCard[0]));
        data.storeObject("vaultCards", vaultCards.toFlatList().toArray(new PaperCard[0]));
        data.storeObject("favoriteCards", favoriteCards.toArray(new PaperCard[0]));

        return data;
    }

    public String spriteName() {
        return HeroListData.instance().getHero(heroRace, isFemale);
    }

    public FileHandle sprite() {
        return Config.instance().getFile(HeroListData.instance().getHero(heroRace, isFemale));
    }

    public TextureRegion avatar() {
        return HeroListData.instance().getAvatar(heroRace, isFemale, avatarIndex);
    }

    public String raceName() {
        return HeroListData.instance().getRaces().get(Current.player().heroRace);
    }

    public GameStage getCurrentGameStage() {
        if (MapStage.getInstance().isInMap())
            return MapStage.getInstance();
        return WorldStage.getInstance();
    }

    public void addStatusMessage(String iconName, String message, Integer itemCount, float x, float y) {
        String symbol = itemCount == null || itemCount < 0 ? "" : " +";
        String icon = iconName == null ? "" : "[+" + iconName + "]";
        String count = itemCount == null ? "" : String.valueOf(itemCount);
        TextraLabel actor = Controls.newTextraLabel("[%95]" + icon + "[WHITE]" + symbol + count + " " + message);
        actor.setPosition(x, y);
        actor.addAction(Actions.sequence(
                Actions.parallel(Actions.moveBy(0f, 5f, 3f), Actions.fadeIn(2f)),
                Actions.hide(),
                Actions.removeActor())
        );
        getCurrentGameStage().addActor(actor);
    }

    public void addCard(PaperCard card) {
        addCard(card, 1);
    }

    public void addCard(PaperCard card, int amount) {
        addCard(card, amount, true);
    }

    /** autoSalvage=false for crafted cards, so a card made on purpose is never salvaged straight back. */
    private void addCard(PaperCard card, int amount, boolean autoSalvage) {
        awardCollectingXp(card, amount);
        cards.add(card, amount);
        newCards.add(card, amount);
        List<PaperCard> added = new ArrayList<>(Math.max(1, amount));
        for (int i = 0; i < amount; i++)
            added.add(card);
        afterCardsCollected(added);
        if (autoSalvage)
            maybeAutoSalvage(card);
    }

    public void addCards(ItemPool<PaperCard> cardPool) {
        for (Map.Entry<PaperCard, Integer> entry : cardPool)
            awardCollectingXp(entry.getKey(), entry.getValue());
        cards.addAll(cardPool);
        newCards.addAll(cardPool);
        List<PaperCard> added = new ArrayList<>();
        for (Map.Entry<PaperCard, Integer> entry : cardPool) {
            int n = entry.getValue() == null ? 0 : entry.getValue();
            for (int i = 0; i < n; i++)
                added.add(entry.getKey());
        }
        afterCardsCollected(added);
        for (Map.Entry<PaperCard, Integer> entry : cardPool)
            maybeAutoSalvage(entry.getKey());
    }

    /** Collecting XP: call before the card is added so the first copy of a card name is detected. */
    private void awardCollectingXp(PaperCard card, int amount) {
        if (card == null || amount <= 0)
            return;
        boolean firstCopy = cards.countByName(card.getName()) == 0;
        skills.onCardCollected(card, firstCopy);
        for (int i = 1; i < amount; i++)
            skills.onCardCollected(card, false);
    }

    public void addReward(Reward reward) {
        switch (reward.getType()) {
            case Card:
                awardCollectingXp(reward.getCard(), 1);
                cards.add(reward.getCard());
                newCards.add(reward.getCard());
                afterCardsCollected(java.util.Collections.singletonList(reward.getCard()));
                if (reward.isAutoSell()) {
                    autoSellCards.add(reward.getCard());
                    refreshEditor();
                }
                maybeAutoSalvage(reward.getCard());
                break;
            case Gold:
                addGold(reward.getCount());
                break;
            case Item:
                if (reward.getItem() != null) {
                    if (!addItem(reward.getItem().name))
                        notifyInventory("Could not add item: " + reward.getItem().name);
                }
                break;
            case CardPack:
                if (reward.getDeck() != null)
                    addBooster(reward.getDeck());
                break;
            case Life:
                addMaxLife(reward.getCount());
                break;
            case Shards:
                addShards(reward.getCount());
                break;
            case Material:
                if (reward.getMaterialId() != null)
                    addMaterial(reward.getMaterialId(), reward.getCount());
                break;
        }
    }

    private void refreshEditor() {
        AdventureDeckEditor editor = ((AdventureDeckEditor) DeckEditScene.getInstance(null).getScreen());
        if (editor != null)
            editor.refresh();
    }

    private void addGold(int goldCount) {
        gold += goldCount;
        onGoldChangeList.emit();
    }

    public void onShardsChange(Runnable o) {
        onShardsChangeList.add(o);
        o.run();
    }

    public void onDustChange(Runnable o) {
        onDustChangeList.add(o);
        o.run();
    }

    public void onMaterialChange(Runnable o) {
        onMaterialChangeList.add(o);
        o.run();
    }

    // ---- Ascendant gym badges / League / run format (Package G) ----

    /**
     * Simple badge-check API for packages E (recipe gating) and J (skill trees).
     * Stock worlds and missing badges return false.
     */
    public boolean hasBadge(String badgeId) {
        return badgeId != null && badges.contains(badgeId);
    }

    /** Number of gym badges earned (0-8). */
    public int getBadgeCount() {
        return badges.size();
    }

    /** Unmodifiable view of earned badge ids in earn order. */
    public Set<String> getBadges() {
        return Collections.unmodifiableSet(badges);
    }

    /** True when all eight gym badges from gyms.json are held. */
    public boolean hasAllGymBadges() {
        if (!Config.ascendant())
            return false;
        Array<GymData> all = GymListData.getAll();
        if (all == null || all.size == 0)
            return false;
        for (GymData g : all) {
            if (g.badgeId != null && !hasBadge(g.badgeId))
                return false;
        }
        return true;
    }

    /**
     * Awards a gym badge. No-op if already held or Ascendant rules are off.
     * @return true if this was a newly earned badge
     */
    public boolean addBadge(String badgeId) {
        if (!Config.ascendant() || badgeId == null || badgeId.isEmpty())
            return false;
        if (GymListData.getByBadge(badgeId) == null)
            return false;
        boolean added = badges.add(badgeId);
        if (added) {
            try {
                AchievementService.get().evaluatePlayer(this);
            } catch (Throwable ignored) {
            }
        }
        return added;
    }

    public boolean isLeagueCleared() {
        return leagueCleared;
    }

    public void setLeagueCleared(boolean cleared) {
        boolean newly = cleared && !leagueCleared;
        leagueCleared = cleared;
        if (newly && Config.ascendant()) {
            try {
                AchievementService.get().evaluatePlayer(this);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Run format for gyms, League and (later) tournaments.
     * Commander-mode saves always report Commander so gyms stay enterable.
     * Package K persists the New Game+ choice; until then non-Commander runs are Standard.
     */
    public String getRunFormat() {
        if (isCommanderMode())
            return GymUtil.FORMAT_COMMANDER;
        return runFormat != null && !runFormat.isEmpty() ? runFormat : GymUtil.FORMAT_STANDARD;
    }

    /** Package K sets this at New Game+. Missing on old saves → Standard. */
    public void setRunFormat(String format) {
        if (format == null || format.isEmpty())
            runFormat = GymUtil.FORMAT_STANDARD;
        else
            runFormat = format;
    }

    /**
     * Combined EffectData from all earned gym badges. Applied in DuelScene alongside skill perks.
     * Empty when Ascendant is off or no badges are held.
     */
    public EffectData badgePerks() {
        EffectData combined = new EffectData();
        if (!Config.ascendant() || badges.isEmpty())
            return combined;
        EffectData opp = new EffectData();
        List<String> start = new ArrayList<>();
        List<String> startCz = new ArrayList<>();
        for (String id : badges) {
            GymData gym = GymListData.getByBadge(id);
            if (gym == null || gym.badgeEffect == null)
                continue;
            EffectData e = gym.badgeEffect;
            combined.lifeModifier += e.lifeModifier;
            combined.changeStartCards += e.changeStartCards;
            combined.extraManaShards += e.extraManaShards;
            combined.freeMulligans += e.freeMulligans;
            combined.cardRewardBonus += e.cardRewardBonus;
            if (e.moveSpeed > 0 && e.moveSpeed != 1f)
                combined.moveSpeed = (combined.moveSpeed <= 0 ? 1f : combined.moveSpeed) * e.moveSpeed;
            if (e.startBattleWithCard != null)
                Collections.addAll(start, e.startBattleWithCard);
            if (e.startBattleWithCardInCommandZone != null)
                Collections.addAll(startCz, e.startBattleWithCardInCommandZone);
            if (e.opponent != null) {
                opp.lifeModifier += e.opponent.lifeModifier;
                opp.changeStartCards += e.opponent.changeStartCards;
            }
        }
        if (!start.isEmpty())
            combined.startBattleWithCard = start.toArray(new String[0]);
        if (!startCz.isEmpty())
            combined.startBattleWithCardInCommandZone = startCz.toArray(new String[0]);
        if (opp.lifeModifier != 0 || opp.changeStartCards != 0)
            combined.opponent = opp;
        return combined;
    }

    public void onLifeChange(Runnable o) {
        onLifeTotalChangeList.add(o);
        o.run();
    }

    public void onPlayerChanged(Runnable o) {
        onPlayerChangeList.add(o);
        o.run();
    }

    public void onEquipmentChanged(Runnable o) {
        onEquipmentChange.add(o);
        o.run();
    }

    public void onGoldChange(Runnable o) {
        onGoldChangeList.add(o);
        o.run();
    }

    public void onBlessing(Runnable o) {
        onBlessing.add(o);
        o.run();
    }

    public boolean fullHeal() {
        if (life < maxLife) {
            resetToMaxLife();
            return true;
        }
        return false;
    }

    public void resetToMaxLife() {
        life = maxLife;
        onLifeTotalChangeList.emit();
    }

    public boolean potionOfFalseLife() {
        if (gold >= falseLifeCost() && life == maxLife) {
            life = maxLife + 2;
            gold -= falseLifeCost();
            onLifeTotalChangeList.emit();
            onGoldChangeList.emit();
            return true;
        } else {
            System.out.println("Can't afford cost of false life " + falseLifeCost());
            System.out.println("Only has this much gold " + gold);
        }
        return false;
    }

    public int falseLifeCost() {
        int ret = 200 + (int) (50 * getStatistic().winLossRatio());
        return ret < 0 ? 250 : ret;
    }

    public void heal(int amount) {
        life = Math.min(life + amount, maxLife);
        onLifeTotalChangeList.emit();
    }

    public void heal(float percent) {
        life = Math.min(life + (int) (maxLife * percent), maxLife);
        onLifeTotalChangeList.emit();
    }

    public boolean defeated() {
        gold = (int) (gold - (gold * difficultyData.goldLoss));
        life = (int) (life - (maxLife * difficultyData.lifeLoss));
        onLifeTotalChangeList.emit();
        onGoldChangeList.emit();
        return life < 1;
        // If true, the player would have had 0 or less, and thus is actually "defeated" if the caller cares about it
    }

    public void win() {
        win(false);
    }

    /** Duel win: +1 shard always; Ascendant also grants configured dust (bosses add rare dust). */
    public void win(boolean boss) {
        addShards(1);
        if (!Config.ascendant())
            return;
        ConfigData config = Config.instance().getConfigData();
        if (config.duelWinDustCommon > 0)
            addDust(CardRarity.Common, config.duelWinDustCommon);
        if (boss && config.duelWinDustBossRare > 0)
            addDust(CardRarity.Rare, config.duelWinDustBossRare);
    }

    public void addMaxLife(int count) {
        maxLife += count;
        life += count;
        onLifeTotalChangeList.emit();
    }

    public void giveGold(int price) {
        takeGold(-price);
    }

    public void takeGold(int price) {
        gold -= price;
        onGoldChangeList.emit();
        //play sfx
        SoundSystem.instance.play(SoundEffectType.CoinsDrop, false);
    }

    public void addShards(int number) {
        takeShards(-number);
    }

    public void takeShards(int number) {
        shards -= number;
        onShardsChangeList.emit();
        //play sfx
        SoundSystem.instance.play(SoundEffectType.TakeShard, false);
    }

    public void setShards(int number) {
        boolean changed = shards != number;
        if (changed) {
            shards = number;
            onShardsChangeList.emit();
        }
    }

    /**
     * Maps a card rarity to a dust bucket. Special/bonus sheets count as Rare.
     * Basics, tokens and unknown return -1 (not salvageable or craftable as dust).
     */
    public static int dustIndex(CardRarity rarity) {
        if (rarity == null)
            return -1;
        return switch (rarity) {
            case Common -> DUST_COMMON;
            case Uncommon -> DUST_UNCOMMON;
            case Rare, Special -> DUST_RARE;
            case MythicRare -> DUST_MYTHIC;
            default -> -1;
        };
    }

    public static CardRarity dustRarity(int index) {
        return switch (index) {
            case DUST_COMMON -> CardRarity.Common;
            case DUST_UNCOMMON -> CardRarity.Uncommon;
            case DUST_RARE -> CardRarity.Rare;
            case DUST_MYTHIC -> CardRarity.MythicRare;
            default -> CardRarity.Unknown;
        };
    }

    public void addDust(CardRarity rarity, int amount) {
        addDust(dustIndex(rarity), amount);
    }

    public void addDust(int index, int amount) {
        if (amount == 0 || index < 0 || index >= dust.length)
            return;
        takeDust(index, -amount);
    }

    /** Spends dust; returns false if there is not enough (and nothing is taken). Negative amount adds. */
    public boolean takeDust(CardRarity rarity, int amount) {
        return takeDust(dustIndex(rarity), amount);
    }

    public boolean takeDust(int index, int amount) {
        if (index < 0 || index >= dust.length)
            return false;
        if (amount > 0 && dust[index] < amount)
            return false;
        dust[index] -= amount;
        if (dust[index] < 0)
            dust[index] = 0;
        onDustChangeList.emit();
        return true;
    }

    public void addBlessing(EffectData bless) {
        blessing = bless;
        onBlessing.emit();
    }

    public void clearBlessing() {
        blessing = null;
        onBlessing.emit();
    }

    public boolean hasBlessing(String name) { //Checks for a named blessing.
        //It is not necessary to name all blessings, only the ones you'd want to check for.
        if (blessing == null) return false;
        return blessing.name.equals(name);
    }

    public boolean isFantasyMode() {
        return fantasyMode;
    }

    public boolean isUsingCustomDeck() {
        return usingCustomDeck;
    }

    public boolean hasAnnounceFantasy() {
        return announceFantasy;
    }

    public void clearAnnounceFantasy() {
        announceFantasy = false;
    }

    public boolean hasAnnounceCustom() {
        return announceCustom;
    }

    public void clearAnnounceCustom() {
        announceCustom = false;
    }

    public boolean hasColorView() {
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.colorView) return true;
        }
        if (blessing != null) {
            return blessing.colorView;
        }
        return false;
    }

    public ItemData getRandomEquippedItem() {
        Array<ItemData> items = new Array<>();
        for (Long id : equippedItems.values()) {
            ItemData item = getEquippedItem(id);
            if (item == null)
                continue;
            if (isHardorInsaneDifficulty()) {
                items.add(item);
            } else {
                switch (item.equipmentSlot) {
                    // limit to these for easy and normal
                    case "Boots", "Body", "Neck" -> items.add(item);
                }
            }
        }
        return items.random();
    }

    public boolean hasEquippedItem() {
        for (Long id : equippedItems.values()) {
            ItemData item = getEquippedItem(id);
            if (item == null)
                continue;
            if (isHardorInsaneDifficulty()) {
                return true;
            } else {
                switch (item.equipmentSlot) {
                    // limit to these for easy and normal
                    case "Boots", "Body", "Neck" -> {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public ItemData getEquippedAbility1() {
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && "Ability1".equalsIgnoreCase(data.equipmentSlot)) {
                return data;
            }
        }
        return null;
    }

    public ItemData getEquippedAbility2() {
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && "Ability2".equalsIgnoreCase(data.equipmentSlot)) {
                return data;
            }
        }
        return null;
    }

    public int bonusDeckCards() {
        int result = 0;
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.cardRewardBonus > 0)
                result += data.effect.cardRewardBonus;
        }
        if (blessing != null) {
            if (blessing.cardRewardBonus > 0) result += blessing.cardRewardBonus;
        }
        result += skills.bonusRewardCards(); // Green 40 perk
        return Math.min(result, 3);
    }

    public DifficultyData getDifficulty() {
        return difficultyData;
    }

    public boolean isHardorInsaneDifficulty() {
        return "Hard".equalsIgnoreCase(difficultyData.name) || "Insane".equalsIgnoreCase(difficultyData.name);
    }

    public void renameDeck(String text) {
        deck = (Deck) deck.copyTo(text);
        decks.set(selectedDeckIndex, deck);
    }

    public int cardSellPrice(PaperCard card) {
        if (card.hasNoSellValue()) {
            return 0;
        }

        int basePrice = (int) (CardUtil.getCardPrice(card) * difficultyData.sellFactor);

        if (card.isFoil()) {
            basePrice += basePrice * 20 / 100;
        }

        float townPriceModifier = currentLocationChanges == null ? 1f : currentLocationChanges.getTownPriceModifier();
        return (int) (basePrice * (2.0f - townPriceModifier) * skills.sellPriceFactor());
    }

    /**
     * Sells a number of copies of a card.
     * @return the number of copies successfully sold.
     */
    public int sellCard(PaperCard card, Integer amount) {
        if (amount == null || amount < 1)
            return 0;

        int amountToSell = Math.min(amount, cards.count(card) - vaultedCount(card));
        int earned = performSale(card, amountToSell);

        if(earned > 0)
            giveGold(earned);
        skills.onCardsSold(amountToSell, earned);
        return amountToSell;
    }

    /**
     * Gold paid per unit when selling a material to shops (sellPrice × town/skill modifiers).
     * Returns 0 if the id is unknown.
     */
    public int materialSellPrice(String id) {
        MaterialData mat = MaterialListData.get(id);
        if (mat == null)
            return 0;
        float townPriceModifier = currentLocationChanges == null ? 1f : currentLocationChanges.getTownPriceModifier();
        return Math.max(1, Math.round(mat.sellPrice * (2.0f - townPriceModifier) * skills.sellPriceFactor()));
    }

    /**
     * Sells up to {@code amount} of a material for gold. Returns units sold.
     * Shops buy at {@link #materialSellPrice}.
     */
    public int sellMaterial(String id, int amount) {
        if (id == null || amount < 1)
            return 0;
        MaterialData mat = MaterialListData.get(id);
        if (mat == null)
            return 0;
        int toSell = Math.min(amount, getMaterial(id));
        if (toSell <= 0)
            return 0;
        int unitPrice = materialSellPrice(id);
        if (!takeMaterial(id, toSell))
            return 0;
        int earned = unitPrice * toSell;
        if (earned > 0)
            giveGold(earned);
        skills.onMaterialsSold(toSell, earned);
        return toSell;
    }

    /** Maps materials.json dustRefine.rarity strings to a dust bucket; unknown → -1. */
    public static int materialDustIndex(String rarity) {
        if (rarity == null)
            return -1;
        return switch (rarity.trim().toLowerCase(Locale.ROOT)) {
            case "common", "c" -> DUST_COMMON;
            case "uncommon", "u" -> DUST_UNCOMMON;
            case "rare", "r", "special" -> DUST_RARE;
            case "mythic", "mythicrare", "m" -> DUST_MYTHIC;
            default -> -1;
        };
    }

    /**
     * Dust granted for refining one unit of this material at the player's current Spellsmithing level.
     * Uses materials.json {@code dustRefine.amount} × Config refineDustBase→refineDustMax lerp.
     */
    public int refineDustYield(String id) {
        MaterialData mat = MaterialListData.get(id);
        if (mat == null || mat.dustRefine == null || mat.dustRefine.amount <= 0)
            return 0;
        if (materialDustIndex(mat.dustRefine.rarity) < 0)
            return 0;
        ConfigData config = Config.instance().getConfigData();
        int level = Math.max(1, skills.getLevel(PlayerSkills.Skill.SPELLSMITHING));
        float t = (level - 1) / 98f;
        float mult = config.refineDustBase + (config.refineDustMax - config.refineDustBase) * t;
        return Math.max(1, Math.round(mat.dustRefine.amount * mult));
    }

    /**
     * Spellsmithing: convert materials into dust of the mapped rarity.
     * Returns dust granted, or 0 on failure.
     */
    public int refineMaterial(String id, int amount) {
        if (!Config.ascendant() || id == null || amount < 1)
            return 0;
        MaterialData mat = MaterialListData.get(id);
        if (mat == null || mat.dustRefine == null)
            return 0;
        int dustBucket = materialDustIndex(mat.dustRefine.rarity);
        if (dustBucket < 0)
            return 0;
        int toRefine = Math.min(amount, getMaterial(id));
        if (toRefine <= 0)
            return 0;
        int yieldEach = refineDustYield(id);
        if (yieldEach <= 0)
            return 0;
        if (!takeMaterial(id, toRefine))
            return 0;
        int total = yieldEach * toRefine;
        addDust(dustBucket, total);
        skills.onMaterialRefined(total);
        return total;
    }

    // ---- Ascendant stations (Package E): craft recipes at Forge / Workshop / Apothecary / Jeweler. ----

    /** Gold cost after skill-level discount (Config {@code stationCraftGoldDiscountMax}). */
    public int recipeGoldCost(RecipeData recipe) {
        if (recipe == null)
            return 0;
        int base = Math.max(0, recipe.gold);
        if (base == 0 || !Config.ascendant())
            return base;
        PlayerSkills.Skill skill = PlayerSkills.Skill.fromName(recipe.skill);
        if (skill == null)
            return base;
        float max = Config.instance().getConfigData().stationCraftGoldDiscountMax;
        float t = (skills.getLevel(skill) - 1) / 98f;
        float discount = Math.max(0f, Math.min(1f, max)) * Math.max(0f, Math.min(1f, t));
        return Math.max(0, Math.round(base * (1f - discount)));
    }

    /** True when level, gold, materials, and result are all satisfiable. */
    public boolean canCraftRecipe(RecipeData recipe) {
        return craftRecipeBlockers(recipe).isEmpty();
    }

    /**
     * Human-readable blockers for the UI (empty if craftable).
     * Missing materials are listed; under-level and gold shortfalls too.
     */
    public List<String> craftRecipeBlockers(RecipeData recipe) {
        List<String> blockers = new ArrayList<>();
        if (!Config.ascendant() || recipe == null) {
            blockers.add("Unavailable");
            return blockers;
        }
        PlayerSkills.Skill skill = PlayerSkills.Skill.fromName(recipe.skill);
        if (skill == null)
            blockers.add("Unknown skill");
        else if (skills.getLevel(skill) < recipe.levelRequired)
            blockers.add(skill.displayName + " " + recipe.levelRequired + " required (have "
                    + skills.getLevel(skill) + ")");
        int goldCost = recipeGoldCost(recipe);
        if (goldCost > 0 && getGold() < goldCost)
            blockers.add("Need " + goldCost + " gold (have " + getGold() + ")");
        for (ObjectMap.Entry<String, Integer> e : recipe.getMaterials()) {
            if (e.key == null || e.value == null || e.value <= 0)
                continue;
            int have = getMaterial(e.key);
            if (have < e.value) {
                MaterialData mat = MaterialListData.get(e.key);
                String label = mat != null ? mat.getDisplayName() : e.key;
                blockers.add("Need " + e.value + "× " + label + " (have " + have + ")");
            }
        }
        if (recipe.isPotion()) {
            if (recipe.blessing == null)
                blockers.add("Potion has no blessing effect");
        } else if (recipe.isMethod()) {
            GatheringMethodData.MethodUpgrade method = GatheringMethodListData.getMethod(recipe.methodId);
            if (method == null)
                blockers.add("Unknown gathering method");
            else if (getGatherMethodRank(method.skill) >= method.rank)
                blockers.add("Already unlocked " + method.getDisplayName());
            else if (method.rank > 1 && getGatherMethodRank(method.skill) < method.rank - 1)
                blockers.add("Need prior method rank for " + method.skill);
        } else if (recipe.isToolEnchant()) {
            GatheringMethodData.ToolEnchantment ench = GatheringMethodListData.getEnchantment(recipe.enchantmentId);
            if (ench == null)
                blockers.add("Unknown enchantment");
            else if (recipe.toolFamily == null || recipe.toolFamily.isEmpty())
                blockers.add("No tool family for enchantment");
            else if (getToolTier(recipe.toolFamily) <= 0)
                blockers.add("Equip a " + recipe.toolFamily + " tool first");
            else if (toolEnchantSlots(recipe.toolFamily) <= 0)
                blockers.add("Need a higher-tier " + recipe.toolFamily + " tool for sockets");
            else if (getToolEnchantments(recipe.toolFamily).size() >= toolEnchantSlots(recipe.toolFamily))
                blockers.add("No free sockets on " + recipe.toolFamily + " tool");
            else if (getToolEnchantments(recipe.toolFamily).contains(recipe.enchantmentId))
                blockers.add("Enchantment already socketed");
        } else if (recipe.isTool()) {
            if (recipe.result == null || recipe.result.isEmpty() || ItemListData.getItem(recipe.result) == null)
                blockers.add("Tool item not defined yet");
            // INV1: grants never refuse — overflow/auto-sell handles full bags.
        } else {
            if (recipe.result == null || recipe.result.isEmpty() || ItemListData.getItem(recipe.result) == null)
                blockers.add("Result item missing: " + recipe.result);
        }
        return blockers;
    }

    /**
     * Spend gold and materials, grant the recipe result, and award skill XP.
     * Returns false if any requirement fails (nothing spent).
     */
    public boolean craftRecipe(RecipeData recipe) {
        if (!canCraftRecipe(recipe))
            return false;
        int goldCost = recipeGoldCost(recipe);
        // Snapshot materials first so a mid-loop failure cannot partial-spend.
        List<String> matIds = new ArrayList<>();
        List<Integer> matCounts = new ArrayList<>();
        for (ObjectMap.Entry<String, Integer> e : recipe.getMaterials()) {
            if (e.key != null && e.value != null && e.value > 0) {
                matIds.add(e.key);
                matCounts.add(e.value);
            }
        }
        for (int i = 0; i < matIds.size(); i++) {
            if (getMaterial(matIds.get(i)) < matCounts.get(i))
                return false;
        }
        if (goldCost > 0 && getGold() < goldCost)
            return false;

        for (int i = 0; i < matIds.size(); i++) {
            if (!takeMaterial(matIds.get(i), matCounts.get(i)))
                return false; // should not happen after checks
        }
        if (goldCost > 0)
            takeGold(goldCost);

        boolean granted;
        if (recipe.isPotion()) {
            addBlessing(new EffectData(recipe.blessing));
            granted = true;
        } else if (recipe.isMethod()) {
            granted = unlockGatherMethod(recipe.methodId);
        } else if (recipe.isToolEnchant()) {
            granted = socketToolEnchantment(recipe.toolFamily, recipe.enchantmentId);
        } else {
            granted = addItem(recipe.result);
        }
        if (!granted) {
            // Refund on grant failure (should be rare — blockers already checked).
            if (goldCost > 0)
                giveGold(goldCost);
            for (int i = 0; i < matIds.size(); i++)
                addMaterial(matIds.get(i), matCounts.get(i));
            return false;
        }

        PlayerSkills.Skill skill = PlayerSkills.Skill.fromName(recipe.skill);
        skills.onRecipeCrafted(skill, Math.max(0, recipe.xp));
        return true;
    }

    /**
     * Sells all cards in the given card pool and adds the resulting amount of gold.
     * The given card pool will be emptied.
     */
    public void doBulkSell(ItemPool<PaperCard> cards) {
        int profit = 0;
        int sold = 0;
        for (PaperCard cardToSell : cards.toFlatList()) {
            profit += AdventurePlayer.current().performSale(cardToSell, 1);
            cards.remove(cardToSell);
            sold++;
        }
        giveGold(profit); //do this as one transaction so as not to get multiple copies of sound effect
        skills.onCardsSold(sold, profit);
    }

    /**
     * Removes a number of copies of a card from the player's inventory and returns the amount of gold they sold for.
     * Does *not* update the player's gold. Can be used as part of bulk-sell operations that update the amount all at once.
     */
    private int performSale(PaperCard card, int amount) {
        //Vaulted copies can never be sold
        int amountToSell = Math.min(amount, cards.count(card) - vaultedCount(card));
        if (amountToSell <= 0)
            return 0;
        if(!cards.remove(card, amountToSell))
            return 0; //Failed to sell?
        return cardSellPrice(card) * amountToSell;
    }

    // ---- Ascendant dust salvage / craft -----------------------------------------------------------

    /** Dust yielded for salvaging one copy (Salvaging skill scales from salvageDust to salvageDustMax). */
    public int salvageYield(PaperCard card) {
        if (!Config.ascendant() || card == null || dustIndex(card.getRarity()) < 0)
            return 0;
        ConfigData config = Config.instance().getConfigData();
        int level = skills.getLevel(PlayerSkills.Skill.SALVAGING);
        float t = (Math.max(1, level) - 1) / 98f;
        return Math.max(1, Math.round(config.salvageDust + (config.salvageDustMax - config.salvageDust) * t));
    }

    /** Owned copies that can be salvaged: not vaulted, not required by any deck, and have sell value. */
    public int copiesAvailableToSalvage(PaperCard card) {
        if (!Config.ascendant() || card == null || card.hasNoSellValue() || dustIndex(card.getRarity()) < 0)
            return 0;
        return Math.max(0, cards.count(card) - vaultedCount(card) - getCopiesUsedInDecks(card));
    }

    /**
     * Why this card cannot be salvaged right now, or null if it can.
     * Used by UI (and core notifies via HUD when a salvage attempt is refused).
     */
    public String salvageProblem(PaperCard card) {
        if (!Config.ascendant())
            return "Salvage is not available in this world.";
        if (card == null)
            return "No card.";
        if (card.getRarity() == CardRarity.BasicLand || (card.getRules() != null && card.getRules().getType().isBasicLand()))
            return "Basic lands cannot be salvaged.";
        if (card.hasNoSellValue())
            return "This card cannot be salvaged.";
        if (dustIndex(card.getRarity()) < 0)
            return "This card cannot be salvaged.";
        int owned = cards.count(card);
        if (owned <= 0)
            return "You do not own this card.";
        int vaulted = vaultedCount(card);
        int inDecks = getCopiesUsedInDecks(card);
        int free = owned - vaulted - inDecks;
        if (free <= 0) {
            if (vaulted > 0 && vaulted >= owned - inDecks)
                return "Vaulted copies cannot be salvaged.";
            if (inDecks > 0)
                return "Copies used in decks cannot be salvaged.";
            return "No copies available to salvage.";
        }
        return null;
    }

    /**
     * Destroys copies for dust of the card's rarity. Mirrors {@link #sellCard}: vaulted and in-deck copies
     * are protected. Returns how many copies were salvaged.
     */
    public int salvageCard(PaperCard card, int amount) {
        if (amount < 1)
            return 0;
        String problem = salvageProblem(card);
        if (problem != null) {
            notifyCrafting(problem);
            return 0;
        }
        int toSalvage = Math.min(amount, copiesAvailableToSalvage(card));
        if (toSalvage <= 0) {
            notifyCrafting("No copies available to salvage.");
            return 0;
        }
        if (!cards.remove(card, toSalvage))
            return 0;
        int autoMarked = Math.min(toSalvage, autoSellCards.count(card));
        if (autoMarked > 0)
            autoSellCards.remove(card, autoMarked);
        int yield = salvageYield(card) * toSalvage;
        addDust(card.getRarity(), yield);
        skills.onCardsSalvaged(toSalvage, yield);
        return toSalvage;
    }

    /**
     * Newest printing of this card name among ever-unlocked sets (expanded CORE included).
     * For staples with no unlocked-set printing, falls back to the unique common-cards entry.
     */
    public PaperCard craftPrinting(PaperCard card) {
        if (card == null)
            return null;
        return craftPrinting(card.getName());
    }

    public PaperCard craftPrinting(String cardName) {
        if (cardName == null || cardName.isEmpty())
            return null;
        List<String> history = standardWindow.expandedHistoryCodes();
        PaperCard best = null;
        long bestTime = Long.MIN_VALUE;
        for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards(cardName)) {
            if (pc == null || !history.contains(pc.getEdition()))
                continue;
            CardEdition ed = FModel.getMagicDb().getEditions().get(pc.getEdition());
            long time = ed != null && ed.getDate() != null ? ed.getDate().getTime() : 0L;
            if (best == null || time > bestTime) {
                best = pc;
                bestTime = time;
            }
        }
        if (best != null)
            return best;
        if (isUnlockedStapleName(cardName))
            return FModel.getMagicDb().getCommonCards().getUniqueByName(cardName);
        return null;
    }

    private boolean isUnlockedStapleName(String name) {
        if (name == null)
            return false;
        if (StandardWindow.unlockedColorStaples().contains(name))
            return true;
        if (standardWindow.isActive()) {
            if (standardWindow.activeStaples(false).contains(name))
                return true;
            if (hasCommanderDeck() && standardWindow.activeStaples(true).contains(name))
                return true;
        }
        return false;
    }

    /** True if the card name is in the Ascendant craftable pool and not banned in every format. */
    public boolean canCraft(PaperCard card) {
        return craftProblem(card) == null;
    }

    public String craftProblem(PaperCard card) {
        if (!Config.ascendant())
            return "Crafting is not available in this world.";
        if (card == null)
            return "No card.";
        if (card.getRarity() == CardRarity.BasicLand || (card.getRules() != null && card.getRules().getType().isBasicLand()))
            return "Basic lands cannot be crafted.";
        String name = card.getName();
        if (BanLists.isBanned("standard", name) && BanLists.isBanned("historic", name) && BanLists.isBanned("commander", name))
            return name + " is banned in all formats.";
        PaperCard printing = craftPrinting(card);
        if (printing == null || dustIndex(printing.getRarity()) < 0)
            return "This card is not in your unlocked craftable pool.";
        return null;
    }

    /** Dust cost for one copy after Spellsmithing discount and historic × factor. */
    public int craftCost(PaperCard card) {
        if (!Config.ascendant() || card == null)
            return 0;
        PaperCard printing = craftPrinting(card);
        if (printing == null || dustIndex(printing.getRarity()) < 0)
            return 0;
        ConfigData config = Config.instance().getConfigData();
        float base = config.craftCost;
        if (!isStandardLegal(printing))
            base *= config.historicCraftFactor;
        int level = skills.getLevel(PlayerSkills.Skill.SPELLSMITHING);
        float discount = config.craftDiscountMax * (Math.max(1, level) - 1) / 98f;
        return Math.max(1, Math.round(base * (1f - discount)));
    }

    /**
     * Mana-reagent cost for crafting this card (Package A2). Empty when Ascendant is off
     * or the card has no reagent requirement.
     */
    public CardReagentCost craftReagentCost(PaperCard card) {
        if (!Config.ascendant() || card == null)
            return CardReagentCost.forCard(null);
        PaperCard printing = craftPrinting(card);
        if (printing == null)
            return CardReagentCost.forCard(null);
        return CardReagentCost.forCard(printing);
    }

    /**
     * Spends dust of the printing's rarity plus mana reagents (A2), then adds one copy.
     * Returns true on success. Dust is not spent if reagents are missing.
     */
    public boolean craftCard(PaperCard card) {
        String problem = craftProblem(card);
        if (problem != null) {
            notifyCrafting(problem);
            return false;
        }
        PaperCard printing = craftPrinting(card);
        int cost = craftCost(printing);
        int index = dustIndex(printing.getRarity());
        CardReagentCost reagents = CardReagentCost.forCard(printing);
        if (!reagents.canAfford(this)) {
            List<String> missing = reagents.missing(this);
            notifyCrafting(missing.isEmpty() ? "Missing reagents." : missing.get(0));
            return false;
        }
        Map<String, Integer> payment = reagents.resolvePayment(this);
        if (!reagents.isEmpty() && payment.isEmpty()) {
            notifyCrafting("Missing reagents.");
            return false;
        }
        if (getDust(index) < cost) {
            notifyCrafting("Not enough " + dustRarity(index).getLongName() + " dust (need " + cost + ").");
            return false;
        }
        // Snapshot reagent spends first so a mid-loop failure can refund.
        List<String> matIds = new ArrayList<>();
        List<Integer> matCounts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : payment.entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue() > 0) {
                matIds.add(e.getKey());
                matCounts.add(e.getValue());
            }
        }
        for (int i = 0; i < matIds.size(); i++) {
            if (!takeMaterial(matIds.get(i), matCounts.get(i))) {
                for (int j = 0; j < i; j++)
                    addMaterial(matIds.get(j), matCounts.get(j));
                notifyCrafting("Missing reagents.");
                return false;
            }
        }
        if (!takeDust(index, cost)) {
            for (int i = 0; i < matIds.size(); i++)
                addMaterial(matIds.get(i), matCounts.get(i));
            notifyCrafting("Not enough " + dustRarity(index).getLongName() + " dust (need " + cost + ").");
            return false;
        }
        addCard(printing, 1, false);
        skills.onCardCrafted(cost);
        return true;
    }

    /**
     * Spell Smith: craft one Prismatic reagent of the given tier from one reagent of each
     * of W/U/B/R/G at that tier (alts of the same color+tier are accepted).
     */
    public boolean craftPrismatic(int tier) {
        if (!Config.ascendant()) {
            notifyCrafting("Crafting is not available in this world.");
            return false;
        }
        if (tier < 1 || tier > 4) {
            notifyCrafting("Invalid prismatic tier.");
            return false;
        }
        MaterialData result = MaterialListData.prismaticForTier(tier);
        if (result == null) {
            notifyCrafting("Prismatic reagent T" + tier + " is not defined.");
            return false;
        }
        String[] colors = {"W", "U", "B", "R", "G"};
        Map<String, Integer> payment = new LinkedHashMap<>();
        for (String color : colors) {
            if (CardReagentCost.ownedForColor(this, color, tier) < 1) {
                MaterialData primary = MaterialListData.primaryReagent(color, tier);
                String label = primary != null ? primary.getDisplayName() : color;
                notifyCrafting("Need 1× " + label + " (or same-color alt) for Prismatic T" + tier + ".");
                return false;
            }
            // Prefer primary family, then alts (same order as card craft).
            boolean took = false;
            for (MaterialData m : new Array.ArrayIterator<>(MaterialListData.reagentsForColorTier(color, tier))) {
                if (getMaterial(m.id) > 0) {
                    payment.merge(m.id, 1, Integer::sum);
                    took = true;
                    break;
                }
            }
            if (!took) {
                notifyCrafting("Need a " + color + " T" + tier + " reagent.");
                return false;
            }
        }
        List<String> matIds = new ArrayList<>(payment.keySet());
        for (int i = 0; i < matIds.size(); i++) {
            if (!takeMaterial(matIds.get(i), payment.get(matIds.get(i)))) {
                for (int j = 0; j < i; j++)
                    addMaterial(matIds.get(j), payment.get(matIds.get(j)));
                notifyCrafting("Missing reagents for Prismatic.");
                return false;
            }
        }
        addMaterial(result.id, 1);
        int xp = Math.max(0, Config.instance().getConfigData().prismaticCraftXp);
        if (xp > 0)
            skills.addXp(PlayerSkills.Skill.SPELLSMITHING, xp);
        return true;
    }

    /** Why a Prismatic craft would fail, or null if it can proceed. */
    public String prismaticCraftProblem(int tier) {
        if (!Config.ascendant())
            return "Crafting is not available in this world.";
        if (tier < 1 || tier > 4)
            return "Invalid prismatic tier.";
        if (MaterialListData.prismaticForTier(tier) == null)
            return "Prismatic reagent T" + tier + " is not defined.";
        for (String color : new String[]{"W", "U", "B", "R", "G"}) {
            if (CardReagentCost.ownedForColor(this, color, tier) < 1) {
                MaterialData primary = MaterialListData.primaryReagent(color, tier);
                String label = primary != null ? primary.getDisplayName() : color;
                return "Need 1× " + label + " (or same-color alt)";
            }
        }
        return null;
    }

    /** After a card enters the collection: salvage copies beyond the keep limit when auto-salvage is on. */
    private void maybeAutoSalvage(PaperCard card) {
        if (!Config.ascendant() || !autoSalvage || card == null)
            return;
        if (card.getRarity() == CardRarity.BasicLand || (card.getRules() != null && card.getRules().getType().isBasicLand()))
            return;
        if (dustIndex(card.getRarity()) < 0 || card.hasNoSellValue())
            return;
        int keep = Math.max(0, Config.instance().getConfigData().autoSalvageKeepCopies);
        int nonVaulted = cards.count(card) - vaultedCount(card);
        int excess = nonVaulted - keep;
        if (excess <= 0)
            return;
        int available = copiesAvailableToSalvage(card);
        int toSalvage = Math.min(excess, available);
        if (toSalvage > 0)
            salvageCard(card, toSalvage);
    }

    private static void notifyCrafting(String msg) {
        try {
            GameHUD.getInstance().addNotification(msg);
        } catch (Exception ignored) {
            // HUD may be unavailable during load/tests
        }
    }

    public void removeItem(String name) {
        inventoryItems.stream().filter(itemData -> name.equalsIgnoreCase(itemData.name)).findFirst().ifPresent(this::removeItem);
    }

    public void removeItem(ItemData item) {
        if (item == null)
            return;
        inventoryItems.remove(item);
        if (getEquippedItems().contains(item.longID) && !inventoryItems.contains(item)) {
            item.isEquipped = false;
            getEquippedItems().remove(item.longID);
        }
    }

    public void equip(ItemData item) {
        if (item != null && item.isGatheringTool()) {
            equipTool(item);
            return;
        }
        Long itemID = equippedItems.get(item.equipmentSlot);
        if (itemID != null && itemID.equals(item.longID)) {
            item.isEquipped = false;
            equippedItems.remove(item.equipmentSlot);
        } else {
            item.isEquipped = true;
            equippedItems.put(item.equipmentSlot, item.longID);
        }
        onEquipmentChange.emit();
    }

    /** Equip or unequip a gathering tool on the toolbelt (one per family). */
    public void equipTool(ItemData item) {
        if (item == null || !item.isGatheringTool())
            return;
        String family = item.toolFamily;
        String current = toolbelt.get(family);
        if (current != null && current.equalsIgnoreCase(item.name))
            toolbelt.remove(family);
        else
            toolbelt.put(family, item.name);
        onEquipmentChange.emit();
    }

    /** Tool item name equipped for a material family, or null. */
    public String getToolbeltTool(String family) {
        if (family == null)
            return null;
        return toolbelt.get(family);
    }

    /** Unmodifiable family → tool item name. */
    public Map<String, String> getToolbelt() {
        return Collections.unmodifiableMap(toolbelt);
    }

    /** Equipped tool tier for a family (0 if none / not owned). Caps gatherable material tier. */
    public int getToolTier(String family) {
        String name = getToolbeltTool(family);
        if (name == null || !hasItem(name))
            return 0;
        ItemData data = ItemListData.getItem(name);
        return data != null && data.isGatheringTool() ? data.toolTier : 0;
    }

    public boolean isToolEquipped(ItemData item) {
        if (item == null || !item.isGatheringTool())
            return false;
        String cur = toolbelt.get(item.toolFamily);
        return cur != null && cur.equalsIgnoreCase(item.name);
    }

    /**
     * Ascendant only: grant missing T1 tools and equip the best owned tool per family.
     * Safe on old saves (missing toolbelt → empty then filled).
     */
    public void ensureStarterGatheringTools() {
        if (!Config.ascendant())
            return;
        if (!starterToolsGranted) {
            for (String toolName : STARTER_GATHERING_TOOLS) {
                if (!hasItem(toolName))
                    addItem(toolName, false);
            }
            starterToolsGranted = true;
        }
        // Drop toolbelt entries for tools no longer owned.
        toolbelt.entrySet().removeIf(e -> e.getValue() == null || !hasItem(e.getValue()));
        // Equip best owned tool per family (inventory may hold higher tiers from shops/crafting).
        Map<String, ItemData> best = new HashMap<>();
        for (ItemData item : inventoryItems) {
            if (item == null || !item.isGatheringTool())
                continue;
            ItemData prev = best.get(item.toolFamily);
            if (prev == null || item.toolTier > prev.toolTier)
                best.put(item.toolFamily, item);
        }
        for (Map.Entry<String, ItemData> e : best.entrySet()) {
            String equipped = toolbelt.get(e.getKey());
            int equippedTier = 0;
            if (equipped != null && hasItem(equipped)) {
                ItemData equippedData = ItemListData.getItem(equipped);
                equippedTier = equippedData != null ? equippedData.toolTier : 0;
            }
            if (equippedTier < e.getValue().toolTier)
                toolbelt.put(e.getKey(), e.getValue().name);
        }
    }

    // ---- Ascendant gathering methods (Package B2) ----

    /** Advance in-game play time used by outpost production. Ascendant only. */
    public void tickAdventurePlaySeconds(float delta) {
        if (!Config.ascendant() || delta <= 0f)
            return;
        adventurePlaySeconds += delta;
    }

    public double getAdventurePlaySeconds() {
        return adventurePlaySeconds;
    }

    private static double[] readCampLastUpdate(Object rawLast, int n) {
        if (rawLast instanceof double[] d) {
            double[] out = new double[n];
            for (int i = 0; i < n && i < d.length; i++)
                out[i] = d[i];
            return out;
        }
        if (rawLast instanceof float[] f) {
            double[] out = new double[n];
            for (int i = 0; i < n && i < f.length; i++)
                out[i] = f[i];
            return out;
        }
        return new double[n];
    }

    private static void parseCampStoredBlob(CampState st, String blob) {
        if (st == null || blob == null || blob.isEmpty())
            return;
        for (String part : blob.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty())
                continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0)
                continue;
            String matId = trimmed.substring(0, colon).trim();
            try {
                float amt = Float.parseFloat(trimmed.substring(colon + 1).trim());
                if (!matId.isEmpty() && amt > 0f)
                    st.addStored(matId, amt);
            } catch (NumberFormatException ignored) {
                // skip malformed fragment
            }
        }
    }

    private static String formatCampStoredBlob(CampState st) {
        if (st == null || st.storedByMaterial.isEmpty())
            return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Float> e : st.storedByMaterial.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue() <= 0f)
                continue;
            if (sb.length() > 0)
                sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        return sb.toString();
    }

    public int getGatherMethodRank(String skill) {
        if (skill == null)
            return 0;
        return gatherMethodRanks.getOrDefault(skill, 0);
    }

    public Map<String, Integer> getGatherMethodRanks() {
        return Collections.unmodifiableMap(gatherMethodRanks);
    }

    /** Unlock a method upgrade by id. Returns false if unknown or already at/above rank. */
    public boolean unlockGatherMethod(String methodId) {
        GatheringMethodData.MethodUpgrade method = GatheringMethodListData.getMethod(methodId);
        if (method == null || method.skill == null)
            return false;
        int cur = getGatherMethodRank(method.skill);
        if (cur >= method.rank)
            return false;
        if (method.rank > 1 && cur < method.rank - 1)
            return false;
        gatherMethodRanks.put(method.skill, method.rank);
        return true;
    }

    /** All socketed enchantment ids for a family (may exceed current tool's slot count). */
    public List<String> getToolEnchantments(String family) {
        if (family == null)
            return Collections.emptyList();
        ArrayList<String> list = toolEnchantments.get(family);
        if (list == null || list.isEmpty())
            return Collections.emptyList();
        return Collections.unmodifiableList(list);
    }

    /**
     * Enchantments that currently apply: only the first {@link #toolEnchantSlots(String)}
     * sockets on the equipped tool. Extra enchants stay stored until the tool has slots again
     * or the player removes them.
     */
    public List<String> getActiveToolEnchantments(String family) {
        List<String> all = getToolEnchantments(family);
        int slots = toolEnchantSlots(family);
        if (slots <= 0 || all.isEmpty())
            return Collections.emptyList();
        if (all.size() <= slots)
            return all;
        return Collections.unmodifiableList(all.subList(0, slots));
    }

    /** Socket count available for the equipped tool of this family. */
    public int toolEnchantSlots(String family) {
        if (!Config.ascendant())
            return 0;
        int tier = getToolTier(family);
        if (tier <= 0)
            return 0;
        ConfigData cfg = Config.instance().getConfigData();
        int minTier = cfg != null ? cfg.toolEnchantSocketMinTier : 2;
        int tier2 = cfg != null ? cfg.toolEnchantSocketTier2 : 4;
        int max = cfg != null ? cfg.toolEnchantSocketMax : 2;
        if (tier < minTier)
            return 0;
        int slots = 1;
        if (tier >= tier2)
            slots = 2;
        slots += Math.max(0, toolEnchantSocketBonus);
        return Math.min(max + Math.max(0, toolEnchantSocketBonus), slots);
    }

    /** Socket an enchantment onto the equipped tool for {@code family}. */
    public boolean socketToolEnchantment(String family, String enchantmentId) {
        if (!Config.ascendant() || family == null || enchantmentId == null)
            return false;
        if (GatheringMethodListData.getEnchantment(enchantmentId) == null)
            return false;
        if (getToolTier(family) <= 0)
            return false;
        ArrayList<String> list = toolEnchantments.computeIfAbsent(family, k -> new ArrayList<>());
        if (list.contains(enchantmentId))
            return false;
        if (list.size() >= toolEnchantSlots(family))
            return false;
        list.add(enchantmentId);
        return true;
    }

    /**
     * Remove a socketed enchantment and refund its gem/crystal material.
     * Returns the refunded material id, or null on failure.
     */
    public String removeToolEnchantment(String family, String enchantmentId) {
        if (!Config.ascendant() || family == null || enchantmentId == null)
            return null;
        ArrayList<String> list = toolEnchantments.get(family);
        if (list == null || !list.remove(enchantmentId))
            return null;
        if (list.isEmpty())
            toolEnchantments.remove(family);
        GatheringMethodData.ToolEnchantment ench = GatheringMethodListData.getEnchantment(enchantmentId);
        if (ench != null && ench.socketMaterial != null && !ench.socketMaterial.isEmpty()) {
            addMaterial(ench.socketMaterial, 1);
            return ench.socketMaterial;
        }
        return enchantmentId;
    }

    /** Sum of active enchantment {@code value} for a given effect key on a tool family. */
    public float toolEnchantEffect(String family, String effect) {
        if (family == null || effect == null)
            return 0f;
        float sum = 0f;
        for (String id : getActiveToolEnchantments(family)) {
            GatheringMethodData.ToolEnchantment e = GatheringMethodListData.getEnchantment(id);
            if (e != null && effect.equalsIgnoreCase(e.effect))
                sum += e.value;
        }
        return sum;
    }

    public boolean hasToolEnchantEffect(String family, String effect) {
        return toolEnchantEffect(family, effect) > 0f;
    }

    /**
     * MV1 camp storage key: {@code <planeId>::<outpostId>}. Bare outpost ids from
     * old saves are treated as home-plane keys on load.
     */
    public static String campStorageKey(String planeId, String outpostId) {
        if (outpostId == null) {
            return null;
        }
        if (outpostId.contains("::")) {
            return outpostId;
        }
        String plane = planeId != null && !planeId.isEmpty() ? planeId : PlaneMeta.HOME_ID;
        return plane + "::" + outpostId;
    }

    public static String bareOutpostId(String campKey) {
        if (campKey == null) {
            return null;
        }
        int i = campKey.indexOf("::");
        return i < 0 ? campKey : campKey.substring(i + 2);
    }

    private String campKeyForCurrentPlane(String outpostId) {
        if (outpostId == null) {
            return null;
        }
        if (outpostId.contains("::")) {
            return outpostId;
        }
        String plane;
        try {
            plane = WorldSave.getCurrentSave().getCurrentPlaneId();
        } catch (Exception e) {
            plane = PlaneMeta.HOME_ID;
        }
        return campStorageKey(plane, outpostId);
    }

    public CampState getCamp(String outpostId) {
        if (outpostId == null) {
            return null;
        }
        String key = campKeyForCurrentPlane(outpostId);
        CampState st = camps.get(key);
        if (st == null && !outpostId.contains("::")) {
            // Pre-MV1 bare key (migrated on load; keep lookup for mid-session).
            st = camps.get(outpostId);
        }
        return st;
    }

    public Map<String, CampState> getCamps() {
        return Collections.unmodifiableMap(camps);
    }

    /** Accrue production into camp storage based on adventure play time. */
    public void updateCampProduction(String outpostId) {
        if (!Config.ascendant() || outpostId == null)
            return;
        String key = campKeyForCurrentPlane(outpostId);
        String bare = bareOutpostId(outpostId);
        CampState st = camps.get(key);
        if (st == null) {
            st = camps.get(outpostId);
        }
        GatheringMethodData.Outpost def = GatheringMethodListData.getOutpost(bare);
        if (st == null || def == null || st.level <= 0)
            return;
        GatheringMethodData.OutpostLevel level = def.levelData(st.level);
        if (level == null || level.materialId == null)
            return;
        ConfigData cfg = Config.instance().getConfigData();
        float secPerHour = cfg != null && cfg.outpostSecondsPerHour > 0 ? cfg.outpostSecondsPerHour : 60f;
        float capHours = def.storageCapHours > 0 ? def.storageCapHours
                : (cfg != null ? cfg.outpostDefaultStorageHours : 72f);
        // Cap applies to the current tier's bin only; older-tier stock is kept separately.
        float cap = Math.max(0f, level.outputPerHour) * Math.max(1f, capHours);
        double elapsed = Math.max(0d, adventurePlaySeconds - st.lastUpdatePlaySeconds);
        float gained = (float) ((elapsed / secPerHour) * Math.max(0f, level.outputPerHour));
        float cur = st.getStored(level.materialId);
        float room = Math.max(0f, cap - cur);
        if (gained > 0f && room > 0f)
            st.addStored(level.materialId, Math.min(gained, room));
        st.lastUpdatePlaySeconds = adventurePlaySeconds;
    }

    public void updateAllCampProduction() {
        for (String id : new ArrayList<>(camps.keySet()))
            updateCampProduction(id);
    }

    /** Build level 1 or return false if already claimed / cannot afford. */
    public boolean buildCamp(String outpostId) {
        if (!Config.ascendant() || outpostId == null)
            return false;
        String key = campKeyForCurrentPlane(outpostId);
        if (camps.containsKey(key) || camps.containsKey(outpostId))
            return false;
        GatheringMethodData.Outpost def = GatheringMethodListData.getOutpost(bareOutpostId(outpostId));
        if (def == null)
            return false;
        return upgradeCamp(outpostId, 1);
    }

    /** Upgrade camp to {@code targetLevel} (must be current+1, or 1 when unbuilt). */
    public boolean upgradeCamp(String outpostId, int targetLevel) {
        if (!Config.ascendant() || outpostId == null || targetLevel < 1)
            return false;
        String key = campKeyForCurrentPlane(outpostId);
        String bare = bareOutpostId(outpostId);
        GatheringMethodData.Outpost def = GatheringMethodListData.getOutpost(bare);
        if (def == null)
            return false;
        GatheringMethodData.OutpostLevel level = def.levelData(targetLevel);
        if (level == null)
            return false;
        CampState st = camps.get(key);
        if (st == null) {
            st = camps.get(outpostId);
        }
        int cur = st != null ? st.level : 0;
        if (targetLevel != cur + 1)
            return false;
        List<String> blockers = campCostBlockers(level);
        if (!blockers.isEmpty())
            return false;
        if (level.gold > 0)
            takeGold(level.gold);
        for (ObjectMap.Entry<String, Integer> e : level.getMaterials()) {
            if (e.key != null && e.value != null && e.value > 0)
                takeMaterial(e.key, e.value);
        }
        if (st == null) {
            st = new CampState();
            camps.put(key, st);
        } else if (!camps.containsKey(key)) {
            camps.remove(outpostId);
            camps.put(key, st);
        }
        // Accrue into the current tier's material bin before raising the level so
        // existing stock stays under its material id (never converts on upgrade).
        updateCampProduction(outpostId);
        st.level = targetLevel;
        st.lastUpdatePlaySeconds = adventurePlaySeconds;
        return true;
    }

    public List<String> campCostBlockers(GatheringMethodData.OutpostLevel level) {
        List<String> blockers = new ArrayList<>();
        if (level == null) {
            blockers.add("Unknown camp level");
            return blockers;
        }
        if (level.gold > 0 && getGold() < level.gold)
            blockers.add("Need " + level.gold + " gold (have " + getGold() + ")");
        for (ObjectMap.Entry<String, Integer> e : level.getMaterials()) {
            if (e.key == null || e.value == null || e.value <= 0)
                continue;
            int have = getMaterial(e.key);
            if (have < e.value) {
                MaterialData mat = MaterialListData.get(e.key);
                String label = mat != null ? mat.getDisplayName() : e.key;
                blockers.add("Need " + e.value + "× " + label + " (have " + have + ")");
            }
        }
        return blockers;
    }

    /**
     * Collect whole units from every material bin in camp storage.
     * Returns total units granted across all materials.
     */
    public int collectCamp(String outpostId) {
        if (!Config.ascendant() || outpostId == null)
            return 0;
        updateCampProduction(outpostId);
        String key = campKeyForCurrentPlane(outpostId);
        CampState st = camps.get(key);
        if (st == null) {
            st = camps.get(outpostId);
        }
        if (st == null || st.level <= 0 || st.storedByMaterial.isEmpty())
            return 0;
        int total = 0;
        for (String matId : new ArrayList<>(st.storedByMaterial.keySet())) {
            float have = st.getStored(matId);
            int amount = (int) Math.floor(have);
            if (amount <= 0)
                continue;
            float remain = have - amount;
            if (remain > 0f)
                st.storedByMaterial.put(matId, remain);
            else
                st.storedByMaterial.remove(matId);
            addMaterial(matId, amount);
            total += amount;
        }
        return total;
    }

    /** Human-readable stored stock summary for outpost dialogs. */
    public String campStoredSummary(String outpostId) {
        CampState st = getCamp(outpostId);
        if (st == null || st.storedByMaterial.isEmpty())
            return "none";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Float> e : st.storedByMaterial.entrySet()) {
            int whole = (int) Math.floor(e.getValue() != null ? e.getValue() : 0f);
            if (whole <= 0)
                continue;
            if (sb.length() > 0)
                sb.append(", ");
            MaterialData mat = MaterialListData.get(e.getKey());
            sb.append(whole).append("× ").append(mat != null ? mat.getDisplayName() : e.getKey());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    public Long itemInSlot(String key) {
        return equippedItems.get(key);
    }

    public float equipmentSpeed() {
        float factor = 1.0f;
        for (Long id : equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.moveSpeed > 0.0)  //Avoid negative speeds. It would be silly.
                factor *= data.effect.moveSpeed;
        }
        if (blessing != null) { //If a blessing gives speed, take it into account.
            if (blessing.moveSpeed > 0.0)
                factor *= blessing.moveSpeed;
        }
        float overflowSlow = Config.ascendant() ? bags.overflowSpeedFactor() : 1f;
        return factor * skills.moveSpeedFactor() * overflowSlow;
    }

    /** Re-applies stat effects that skills change (e.g. Exploration move speed). */
    void refreshSkillEffects() {
        onEquipmentChange.emit();
    }

    public float goldModifier(boolean sale) {
        float factor = 1.0f;
        for (Long id: equippedItems.values()) {
            ItemData data = getEquippedItem(id);
            if (data != null && data.effect != null && data.effect.goldModifier > 0.0)  //Avoid negative modifiers.
                factor *= data.effect.goldModifier;
        }
        if (blessing != null) { //If a blessing gives speed, take it into account.
            if (blessing.goldModifier > 0.0)
                factor *= blessing.goldModifier;
        }
        if (sale) return Math.max(1.0f + (1.0f - factor), 2.5f);
        return Math.max(factor, 0.25f);
    }

    public float goldModifier() {
        return goldModifier(false);
    }

    public boolean hasItem(String name) {
        return inventoryItems.stream().anyMatch(itemData -> name.equalsIgnoreCase(itemData.name));
    }

    public int countItem(String name) {
        return (int) inventoryItems.stream().filter(Objects::nonNull).filter(i -> i.name.equals(name)).count();
    }

    public boolean addItem(String name) {
        return addItem(name, true);
    }
    public boolean addItem(String name, boolean updateEvent) {
        ItemData item = ItemListData.getItem(name);
        if (item == null)
            return false;
        if (Config.ascendant()) {
            if (InventoryBags.classifyItem(item) == InventoryBagType.CURRENCY) {
                if (!bags.fitsCurrencyItem(item, contestCurrencies, inventoryItems)) {
                    GrantResult r = bags.placeInOverflow(OverflowEntry.ofItem(item));
                    applyGrantResult(r, OverflowEntry.ofItem(item));
                    if (updateEvent)
                        AdventureQuestController.instance().updateItemReceived(item);
                    return true;
                }
            } else if (!InventoryBags.isCapacityExempt(item)
                    && !bags.fitsBackpack(item, inventoryItems, equippedItemIdSet(), toolbelt)) {
                // Toolbelt upgrade still equips; demoted tool may overflow separately below.
                boolean beltOnly = false;
                if (item.isGatheringTool()) {
                    int have = getToolTier(item.toolFamily);
                    if (item.toolTier > have && toolbelt.get(item.toolFamily) == null)
                        beltOnly = true;
                }
                if (!beltOnly) {
                    GrantResult r = bags.placeInOverflow(OverflowEntry.ofItem(item));
                    applyGrantResult(r, OverflowEntry.ofItem(item));
                    if (item.isGatheringTool()) {
                        int have = getToolTier(item.toolFamily);
                        // Still track ownership for quests even if in overflow — item is in overflow stash.
                    }
                    if (updateEvent)
                        AdventureQuestController.instance().updateItemReceived(item);
                    return true;
                }
            }
        }
        inventoryItems.add(item);
        if (item.isGatheringTool()) {
            int have = getToolTier(item.toolFamily);
            if (item.toolTier > have)
                toolbelt.put(item.toolFamily, item.name);
        }
        if (updateEvent)
            AdventureQuestController.instance().updateItemReceived(item);
        return true;
    }

    /**
     * Whether a new item would fit its target bag without Overflow.
     * Crafting no longer blocks on this; kept for UI hints.
     */
    private boolean canAcceptNewItem(ItemData item) {
        if (item == null)
            return false;
        if (InventoryBags.isCapacityExempt(item))
            return true;
        if (InventoryBags.classifyItem(item) == InventoryBagType.CURRENCY)
            return bags.fitsCurrencyItem(item, contestCurrencies, inventoryItems);
        if (item.isGatheringTool()) {
            int have = getToolTier(item.toolFamily);
            if (item.toolTier > have && toolbelt.get(item.toolFamily) == null)
                return true;
        }
        return bags.fitsBackpack(item, inventoryItems, equippedItemIdSet(), toolbelt);
    }

    private Set<Long> equippedItemIdSet() {
        return new HashSet<>(equippedItems.values());
    }

    /** Backpack items visible in the INV1 bag list (excludes equipped gear and toolbelt tools). */
    public List<ItemData> getBackpackItems() {
        return InventoryBags.backpackOccupants(inventoryItems, equippedItemIdSet(), toolbelt);
    }

    public void removeAllQuestItems(){
        inventoryItems.removeIf(data -> data != null && data.questItem);
    }

    public boolean addBooster(Deck booster) {
        if (booster == null || booster.isEmpty())
            return false;
        if (Config.ascendant() && !bags.fitsBooster(boostersOwned)) {
            GrantResult r = bags.placeInOverflow(OverflowEntry.ofBooster(booster));
            applyGrantResult(r, OverflowEntry.ofBooster(booster));
            return true;
        }
        boostersOwned.add(booster);
        return true;
    }

    public void removeBooster(Deck booster) {
        boostersOwned.removeValue(booster, true);
    }

    //Permanent character flags
    public void setCharacterFlag(String key, int value) {
        if (value != 0)
            characterFlags.put(key, (byte) value);
        else
            characterFlags.remove(key);
        AdventureQuestController.instance().updateQuestsCharacterFlag(key, value);
    }

    public void advanceCharacterFlag(String key) {
        if (characterFlags.get(key) != null) {
            characterFlags.put(key, (byte) (characterFlags.get(key) + 1));
        } else {
            characterFlags.put(key, (byte) 1);
        }
    }

    public boolean checkCharacterFlag(String key) {
        return characterFlags.get(key) != null;
    }

    public int getCharacterFlag(String key) {
        return (int) characterFlags.getOrDefault(key, (byte) 0);
    }

    // Quest functions.
    public void setQuestFlag(String key, int value) {
        if (value != 0)
            questFlags.put(key, (byte) value);
        else
            questFlags.remove(key);
        AdventureQuestController.instance().updateQuestsQuestFlag(key, value);
    }

    public void advanceQuestFlag(String key) {
        if (questFlags.get(key) != null) {
            questFlags.put(key, (byte) (questFlags.get(key) + 1));
        } else {
            questFlags.put(key, (byte) 1);
        }
    }

    public boolean checkQuestFlag(String key) {
        return questFlags.get(key) != null;
    }

    public int getQuestFlag(String key) {
        return (int) questFlags.getOrDefault(key, (byte) 0);
    }

    /**
     * Character flags that exist only to stop a quest from being offered twice. Data checks them
     * with checkCharacterFlag, so they survive a quest wipe unless cleared here: "noQuest" is the
     * New Game+ "skip the main quest" choice and hides the intro mage's main quest option, and
     * "dungeonMasterQuestGiven" gates the lair-clearing quest offered in dungeons.
     */
    private static final String[] QUEST_GATE_CHARACTER_FLAGS = {"noQuest", "dungeonMasterQuestGiven"};

    /** Forget all global quest progress so every quest can be offered again (New Game+, resetQuests) */
    public void resetQuestFlags() {
        questFlags.clear();
        for (String flag : QUEST_GATE_CHARACTER_FLAGS) {
            setCharacterFlag(flag, 0);
        }
    }

    public void addQuest(String questID, boolean isNewGame) {
        int id = Integer.parseInt(questID);
        addQuest(id, isNewGame);
    }

    public void addQuest(int questID, boolean isNewGame) {
        AdventureQuestData toAdd = AdventureQuestController.instance().generateQuest(questID);

        if (toAdd != null) {
            addQuest(toAdd, isNewGame);
        }
    }

    public void addQuest(AdventureQuestData q, boolean isNewGame) {
        //TODO: add a config flag for this
        boolean noTrackedQuests = true;
        for (AdventureQuestData existing : quests) {
            if (noTrackedQuests && existing.isTracked) {
                noTrackedQuests = false;
                break;
            }
        }
        quests.add(q);
        if (noTrackedQuests || q.autoTrack)
            AdventureQuestController.trackQuest(q);
        q.activateNextStages();
        if (!isNewGame)
            AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
    }

    public List<AdventureQuestData> getQuests() {
        return quests;
    }

    public void addEvent(AdventureEventData e) {
        events.add(e);
    }

    public List<AdventureEventData> getEvents() {
        return events;
    }

    public int getEnemyDeckNumber(String enemyName, int maxDecks) {
        int deckNumber = 0;
        if (statistic.getWinLossRecord().get(enemyName) != null) {
            int playerWins = statistic.getWinLossRecord().get(enemyName).getKey();
            int enemyWins = statistic.getWinLossRecord().get(enemyName).getValue();
            if (playerWins > enemyWins) {
                int deckNumberAfterAlgorithmOutput = (int) ((playerWins - enemyWins) * (difficultyData.enemyLifeFactor / 3));
                if (deckNumberAfterAlgorithmOutput < maxDecks) {
                    deckNumber = deckNumberAfterAlgorithmOutput;
                } else {
                    deckNumber = maxDecks - 1;
                }
            }
        }
        return deckNumber;
    }

    public void removeQuest(AdventureQuestData quest) {
        quests.remove(quest);
    }

    /**
     * Clears a deck by replacing the current selected deck with a new deck
     */
    public void clearDeck() {
        deck = decks.set(selectedDeckIndex, new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
        ensureDeckLoadoutsSize();
        deckLoadouts.set(selectedDeckIndex, null);
    }

    /**
     * Actually removes the deck from the list of decks.
     */
    public void deleteDeck(){
        int oldIndex = selectedDeckIndex;
        this.setSelectedDeckSlot(0);
        decks.remove(oldIndex);
        if (oldIndex < deckLoadouts.size()) {
            deckLoadouts.remove(oldIndex);
        }
    }

    public void addDeck(){
        decks.add(new Deck(Forge.getLocalizer().getMessage("lblEmptyDeck")));
        deckLoadouts.add(null);
    }

    /**
     * Attempts to copy a deck to an empty slot.
     *
     * @return int - index of new copy slot, or -1 if no slot was available
     */
    public int copyDeck() {
        for (int i = 0; i < maxDeckCount; i++) {
            if (i >= getDeckCount()) addDeck();
            if (isEmptyDeck(i)) {
                decks.set(i, (Deck) deck.copyTo(deck.getName() + " (" + Forge.getLocalizer().getMessage("lblCopy") + ")"));
                // Copy loadout from source deck to new slot
                ensureDeckLoadoutsSize();
                HashMap<String, Long> sourceLoadout = selectedDeckIndex < deckLoadouts.size() ? deckLoadouts.get(selectedDeckIndex) : null;
                deckLoadouts.set(i, sourceLoadout != null ? new HashMap<>(sourceLoadout) : null);
                return i;
            }
        }

        return -1;
    }

    private void ensureDeckLoadoutsSize() {
        while (deckLoadouts.size() < getDeckCount()) {
            deckLoadouts.add(null);
        }
    }

    public boolean isEmptyDeck(int deckIndex) {
        return decks.get(deckIndex).isEmpty() && decks.get(deckIndex).getName().equals(Forge.getLocalizer().getMessage("lblEmptyDeck"));
    }

    public void removeEvent(AdventureEventData completedEvent) {
        events.remove(completedEvent);
    }

    public ItemPool<PaperCard> getAutoSellCards() {
        return autoSellCards;
    }

    /**
     * Gets a list of cards that can be safely sold without taking copies out of the player's decks.
     */
    public ItemPool<PaperCard> getSellableCards() {
        ItemPool<PaperCard> sellableCards = new ItemPool<>(PaperCard.class);
        sellableCards.addAllFlat(cards.toFlatList());

        // Nosell cards used to be filtered out here. Instead we're going to replace their value with 0

        // 1a. Potentially return here if we want to give config option to sell cards from decks
        // but would need to update the decks on sell, not just the catalog

        // 2. Count max cards across all decks in excess of unsellable
        Map<PaperCard, Integer> maxCardCounts = new HashMap<>();

        for (Deck deck : decks) {
            for (final Map.Entry<PaperCard, Integer> cp : deck.getAllCardsInASinglePool(true, true)) {
                int count = cp.getValue();
                if (count > maxCardCounts.getOrDefault(cp.getKey(), 0)) {
                    maxCardCounts.put(cp.getKey(), count);
                }
            }
        }

        // 3. Remove the highest use count of each card, remainder can be sold safely
        for (PaperCard card : maxCardCounts.keySet()) {
            sellableCards.remove(card, maxCardCounts.get(card));
        }

        return sellableCards;
    }

    /**
     * Gets the number of copies of this card that the player needs to keep for their decks to remain valid.
     * Copies are shared between decks, so if one deck uses 1 copy and another deck uses 2, the player needs 2 copies.
     */
    public int getCopiesUsedInDecks(PaperCard card) {
        int copiesUsed = 0;
        for(Deck deck : decks) {
            copiesUsed = Math.max(copiesUsed, deck.count(card));
        }
        return copiesUsed;
    }

    public void removeLostCardFromPools(PaperCard card) {
        if (card.isVeryBasicLand() && !card.isFoil()) {
            return;
        }

        int leftInPool = Current.player().getCards().count(card) - 1;

        for (final Deck deck : decks) {
            int cntInDeck = deck.count(card);
            int nToRemoveFromThisDeck = cntInDeck - leftInPool;
            if (nToRemoveFromThisDeck <= 0) {
                continue;
            }

            for(DeckSection section : DeckSection.values()) {
                if (section == DeckSection.Main || deck.get(section) == null) {
                    continue;
                }
                int cntInSection = deck.get(section).count(card);
                int nToRemoveFromSection = Math.min(cntInSection, nToRemoveFromThisDeck);
                if (nToRemoveFromSection > 0) {
                    deck.get(section).remove(card, nToRemoveFromSection);
                    nToRemoveFromThisDeck -= nToRemoveFromSection;
                    if (nToRemoveFromThisDeck <= 0) {
                        break;
                    }
                }
            }

            if (nToRemoveFromThisDeck <= 0) {
                continue;
            }

            deck.getMain().remove(card, nToRemoveFromThisDeck);
        }
        Current.player().getCards().remove(card, 1);
    }

    public CardPool getCollectionCards(boolean allCards) {
        CardPool collectionCards = new CardPool();
        collectionCards.addAll(cards);
        if (!allCards) {
            collectionCards.removeAll(autoSellCards);
            collectionCards.removeAll(vaultCards);
        }

        return collectionCards;
    }

    public void loadChanges(PointOfInterestChanges changes) {
        this.currentLocationChanges = changes;
    }
}
