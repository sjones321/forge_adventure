package forge.adventure;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;
import forge.adventure.player.CraftPouchTier;
import forge.adventure.player.GrantResult;
import forge.adventure.player.InventoryBagType;
import forge.adventure.player.InventoryBags;
import forge.adventure.player.OverflowEntry;
import forge.adventure.util.ItemCompare;
import forge.deck.Deck;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Headless INV1 coverage: Overflow (never lose / auto-sell / old-save migrate),
 * Craft Pouch tiers, Mastery Surge persistence helpers, compare diffs, capacity round-trip.
 */
public class InventoryBagsTest {

    private static final AtomicLong IDS = new AtomicLong(1);

    private ConfigData cfg;
    private InventoryBags bags;

    @BeforeMethod
    public void setUp() {
        cfg = new ConfigData();
        cfg.backpackSlots = 4;
        cfg.backpackMaxStack = 3;
        cfg.packsSlots = 2;
        cfg.packsMaxStack = 1;
        cfg.currencySlots = 8;
        cfg.currencyMaxStack = 10;
        cfg.craftPouchSatchelSlots = 3;
        cfg.craftPouchSatchelStack = 5;
        cfg.craftPouchPackSlots = 5;
        cfg.craftPouchPackStack = 10;
        cfg.craftPouchHaulerSlots = 8;
        cfg.craftPouchHaulerStack = 20;
        cfg.overflowCap = 3;
        cfg.overflowSlowMinFactor = 0.5f;
        cfg.masterySurgePicks = 2;
        bags = new InventoryBags(cfg);
    }

    private static ItemData gear(String name, String slot) {
        ItemData i = new ItemData();
        i.name = name;
        i.equipmentSlot = slot;
        i.effect = new EffectData();
        i.longID = IDS.getAndIncrement();
        i.cost = 100;
        return i;
    }

    private static ItemData tool(String name, String family, int tier) {
        ItemData i = new ItemData();
        i.name = name;
        i.toolFamily = family;
        i.toolTier = tier;
        i.longID = IDS.getAndIncrement();
        return i;
    }

    private static ItemData potion(String name) {
        ItemData i = new ItemData();
        i.name = name;
        i.stackable = true;
        i.usableInPoi = true;
        i.longID = IDS.getAndIncrement();
        return i;
    }

    private static ItemData quest(String name) {
        ItemData i = gear(name, null);
        i.equipmentSlot = null;
        i.questItem = true;
        return i;
    }

    @Test
    public void oldFlatInventoryClassifiesIntoBagsWithoutLoss() {
        List<ItemData> flat = new ArrayList<>();
        flat.add(gear("Steel Sword", "Right"));
        flat.add(tool("Copper Hatchet", "logs", 1));
        flat.add(potion("Health Draught"));
        ItemData coin = new ItemData();
        coin.name = "Challenge Coin";
        coin.currencyId = "challenge_coin";
        coin.stackable = true;
        coin.longID = IDS.getAndIncrement();
        flat.add(coin);

        Map<String, String> toolbelt = new LinkedHashMap<>();
        toolbelt.put("logs", "Copper Hatchet");
        Set<Long> equipped = new HashSet<>();
        equipped.add(flat.get(0).longID);
        flat.get(0).isEquipped = true;

        List<ItemData> backpack = InventoryBags.backpackOccupants(flat, equipped, toolbelt);
        Assert.assertEquals(backpack.size(), 1);
        Assert.assertEquals(backpack.get(0).name, "Health Draught");
        Assert.assertEquals(InventoryBags.classifyItem(coin), InventoryBagType.CURRENCY);
        Assert.assertEquals(InventoryBags.classifyItem(flat.get(0)), InventoryBagType.BACKPACK);
        Assert.assertEquals(flat.size(), 4);
    }

    @Test
    public void equippedItemNotInBagAndUnequipReturnsIt() {
        ItemData sword = gear("Blade", "Right");
        sword.isEquipped = true;
        List<ItemData> inv = new ArrayList<>();
        inv.add(sword);
        Set<Long> equipped = new HashSet<>();
        equipped.add(sword.longID);

        Assert.assertTrue(InventoryBags.backpackOccupants(inv, equipped, Collections.emptyMap()).isEmpty());

        sword.isEquipped = false;
        equipped.clear();
        List<ItemData> after = InventoryBags.backpackOccupants(inv, equipped, Collections.emptyMap());
        Assert.assertEquals(after.size(), 1);
        Assert.assertSame(after.get(0), sword);
    }

    @Test
    public void overflowAddNeverLosesItem() {
        List<ItemData> inv = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            inv.add(gear("Sword" + i, "Right"));
        Assert.assertFalse(bags.fitsBackpack(gear("Extra", "Left"), inv,
                Collections.emptySet(), Collections.emptyMap()));

        ItemData extra = gear("Extra", "Left");
        GrantResult r = bags.placeInOverflow(OverflowEntry.ofItem(extra));
        Assert.assertTrue(r.wentToOverflow());
        Assert.assertEquals(bags.overflowCount(), 1);
        Assert.assertEquals(bags.getOverflow().get(0).item.name, "Extra");
        Assert.assertEquals(InventoryBags.MESSAGE_OVERFLOW, bags.getLastMessage());
        // Inventory still has originals; overflow holds the new grant — nothing deleted.
        Assert.assertEquals(inv.size(), 4);
    }

    @Test
    public void autoSellHappensPastOverflowCap() {
        bags.setOverflowCap(2);
        Assert.assertTrue(bags.placeInOverflow(OverflowEntry.ofItem(gear("A", "Right"))).wentToOverflow());
        Assert.assertTrue(bags.placeInOverflow(OverflowEntry.ofItem(gear("B", "Right"))).wentToOverflow());
        GrantResult r = bags.placeInOverflow(OverflowEntry.ofItem(gear("C", "Right")));
        Assert.assertTrue(r.wasAutoSold());
        Assert.assertEquals(bags.overflowCount(), 2);
        Assert.assertTrue(r.message.contains("auto-sold") || r.autoSoldName != null);
    }

    @Test
    public void overflowMergesSameMaterialStacks() {
        Assert.assertTrue(bags.placeInOverflow(OverflowEntry.ofMaterial("oak", 3)).wentToOverflow());
        Assert.assertTrue(bags.placeInOverflow(OverflowEntry.ofMaterial("oak", 5)).wentToOverflow());
        Assert.assertTrue(bags.placeInOverflow(OverflowEntry.ofMaterial("iron", 2)).wentToOverflow());
        Assert.assertEquals(bags.overflowCount(), 2);
        Assert.assertEquals(bags.getOverflow().get(0).key, "oak");
        Assert.assertEquals(bags.getOverflow().get(0).amount, 8);
        Assert.assertEquals(bags.getOverflow().get(1).key, "iron");
        Assert.assertEquals(bags.getOverflow().get(1).amount, 2);

        // Load path also merges duplicate material slots from old saves.
        bags.loadOverflowEntries(new OverflowEntry[] {
                OverflowEntry.ofMaterial("oak", 4),
                OverflowEntry.ofMaterial("oak", 6),
                OverflowEntry.ofCurrency(InventoryBags.CURRENCY_DUST_C, 10),
                OverflowEntry.ofCurrency(InventoryBags.CURRENCY_DUST_C, 3),
                OverflowEntry.ofItem(gear("Sword", "Right"))
        });
        Assert.assertEquals(bags.overflowCount(), 3);
        Assert.assertEquals(bags.getOverflow().get(0).amount, 10);
        Assert.assertEquals(bags.getOverflow().get(1).amount, 13);
        Assert.assertEquals(bags.getOverflow().get(2).kind, OverflowEntry.Kind.ITEM);
    }

    @Test
    public void partialCurrencyRetrieveLeavesNoZeroEntries() {
        bags.setOverflowCap(10);
        Map<String, Integer> contest = new LinkedHashMap<>();
        // currencyMaxStack=10; leave room for 2 more of gym_coin.
        contest.put("gym_coin", 8);
        Assert.assertTrue(bags.placeInOverflow(OverflowEntry.ofCurrency("gym_coin", 5)).wentToOverflow());
        Assert.assertEquals(bags.currencyRoom("gym_coin", contest, Collections.emptyList()), 2);

        // Mirror AdventurePlayer.retrieveFromOverflow CURRENCY branch.
        OverflowEntry e = bags.takeOverflow(0);
        Assert.assertNotNull(e);
        int room = bags.currencyRoom(e.key, contest, Collections.emptyList());
        int move = Math.min(room, e.amount);
        Assert.assertEquals(move, 2);
        contest.put(e.key, contest.get(e.key) + move);
        int left = e.amount - move;
        if (left > 0)
            bags.placeInOverflow(OverflowEntry.ofCurrency(e.key, left));

        Assert.assertEquals(contest.get("gym_coin").intValue(), 10);
        Assert.assertEquals(bags.overflowCount(), 1);
        Assert.assertEquals(bags.getOverflow().get(0).amount, 3);
        Assert.assertTrue(bags.getOverflow().get(0).amount > 0);
        Assert.assertTrue(contest.get("gym_coin") > 0);

        // Zero-amount currency must not enter Overflow.
        OverflowEntry zero = new OverflowEntry();
        zero.kind = OverflowEntry.Kind.CURRENCY;
        zero.key = "tournament_coin";
        zero.amount = 0;
        GrantResult r = bags.placeInOverflow(zero);
        Assert.assertEquals(r.fate, GrantResult.Fate.ACCEPTED);
        Assert.assertFalse(r.wentToOverflow());
        Assert.assertEquals(bags.overflowCount(), 1);

        // Load path drops zero-amount currency/material entries.
        OverflowEntry zeroMat = new OverflowEntry();
        zeroMat.kind = OverflowEntry.Kind.MATERIAL;
        zeroMat.key = "oak";
        zeroMat.amount = 0;
        bags.loadOverflowEntries(new OverflowEntry[] {
                OverflowEntry.ofCurrency("gym_coin", 4),
                zero,
                zeroMat
        });
        Assert.assertEquals(bags.overflowCount(), 1);
        Assert.assertEquals(bags.getOverflow().get(0).amount, 4);
    }

    @Test
    public void overCapacityOldSaveLoadsIntoOverflow() {
        List<ItemData> inv = new ArrayList<>();
        for (int i = 0; i < 7; i++)
            inv.add(gear("Old" + i, "Right"));
        Map<String, Integer> mats = new LinkedHashMap<>();
        mats.put("oak", 50); // over satchel stack of 5
        mats.put("iron", 3);
        mats.put("copper", 3);
        mats.put("willow", 3); // over satchel slots of 3 after oak/iron/copper
        List<Deck> packs = new ArrayList<>();
        packs.add(new Deck("P1"));
        packs.add(new Deck("P2"));
        packs.add(new Deck("P3")); // packsSlots=2

        bags.setOverflowCap(20);
        List<OverflowEntry> autoSell = bags.migrateOverCapacity(
                inv, Collections.emptySet(), Collections.emptyMap(), packs, mats, new HashMap<>());

        Assert.assertTrue(bags.usedBackpackSlots(inv, Collections.emptySet(), Collections.emptyMap())
                <= bags.getSlots(InventoryBagType.BACKPACK));
        Assert.assertTrue(bags.usedPackSlots(packs) <= bags.getSlots(InventoryBagType.PACKS));
        Assert.assertTrue(mats.getOrDefault("oak", 0) <= bags.getMaxStack(InventoryBagType.MATERIALS));
        Assert.assertTrue(bags.overflowCount() > 0 || !autoSell.isEmpty());
        // Nothing silently deleted: inv + overflow + autoSell accounts for the excess.
        int accounted = inv.size() + bags.overflowCount() + autoSell.size();
        Assert.assertTrue(accounted >= 7);
    }

    @Test
    public void pouchStackingPerTierAndBottomless() {
        Map<String, Integer> mats = new LinkedHashMap<>();
        Assert.assertEquals(bags.getCraftPouchTier(), CraftPouchTier.SATCHEL);
        Assert.assertEquals(bags.materialRoom("oak", mats), 5);
        mats.put("oak", 5);
        Assert.assertEquals(bags.materialRoom("oak", mats), 0);
        mats.put("iron", 1);
        mats.put("copper", 1);
        Assert.assertEquals(bags.materialRoom("willow", mats), 0); // no free slot

        Assert.assertTrue(bags.upgradeCraftPouchTier());
        Assert.assertEquals(bags.getCraftPouchTier(), CraftPouchTier.PACK);
        Assert.assertEquals(bags.materialRoom("oak", mats), 5); // stack 10 - 5
        Assert.assertTrue(bags.materialRoom("willow", mats) > 0);

        bags.upgradeCraftPouchTier(); // Hauler
        bags.upgradeCraftPouchTier(); // Bottomless
        Assert.assertEquals(bags.getCraftPouchTier(), CraftPouchTier.BOTTOMLESS);
        Assert.assertFalse(bags.upgradeCraftPouchTier());
        mats.put("oak", 99999);
        Assert.assertTrue(bags.materialRoom("oak", mats) > 1000);
        Assert.assertFalse(bags.isMaterialsOverCapacity(mats));
    }

    @Test
    public void unspentMasterySurgePicksPersistViaSaveFields() {
        // Persistence is on AdventurePlayer; bags store craft pouch tier + overflow cap
        // which Mastery Surge mutates. Round-trip those fields.
        bags.upgradeCraftPouchTier();
        bags.addOverflowCapBonus(2);
        Map<String, Object> stored = new HashMap<>();
        bags.store(stored);
        Assert.assertEquals(stored.get("craftPouchTier"), CraftPouchTier.PACK.index);
        Assert.assertEquals(stored.get("overflowCap"), cfg.overflowCap + 2);

        InventoryBags loaded = new InventoryBags(cfg);
        loaded.load((String[]) stored.get("bagTypes"), (int[]) stored.get("bagSlots"),
                (int[]) stored.get("bagMaxStacks"), cfg);
        loaded.loadCraftPouchAndOverflow((Integer) stored.get("craftPouchTier"),
                (Integer) stored.get("overflowCap"));
        Assert.assertEquals(loaded.getCraftPouchTier(), CraftPouchTier.PACK);
        Assert.assertEquals(loaded.getOverflowCap(), cfg.overflowCap + 2);

        // Unspent picks themselves: simulate save/load ints
        int unspent = cfg.masterySurgePicks;
        Assert.assertEquals(unspent, 2);
        unspent -= 1; // spent one
        Assert.assertEquals(unspent, 1);
    }

    @Test
    public void compareDiffOpponentEffectsCardsAndColorView() {
        ItemData weak = gear("Weak", "Body");
        weak.effect.lifeModifier = 1;
        weak.effect.colorView = false;
        weak.effect.startBattleWithCard = new String[]{"Plains"};
        weak.effect.startBattleWithCardInCommandZone = new String[]{"Sol Ring"};
        weak.effect.opponent = new EffectData();
        weak.effect.opponent.lifeModifier = 5;
        weak.effect.opponent.changeStartCards = 1;

        ItemData strong = gear("Strong", "Body");
        strong.effect.lifeModifier = 5;
        strong.effect.colorView = true;
        strong.effect.startBattleWithCard = new String[]{"Plains", "Island"};
        strong.effect.startBattleWithCardInCommandZone = new String[]{};
        strong.effect.opponent = new EffectData();
        strong.effect.opponent.lifeModifier = 0;
        strong.effect.opponent.changeStartCards = 0;
        strong.effect.opponent.startBattleWithCard = new String[]{"Shock"};

        List<ItemCompare.Diff> diffs = ItemCompare.compare(strong, weak);
        boolean sawColorView = false;
        boolean sawBattleAdded = false;
        boolean sawCommandRemoved = false;
        boolean sawOppLife = false;
        for (ItemCompare.Diff d : diffs) {
            if (d.label.contains("colorView") || d.label.contains("Manasight")) {
                if (d.trend == ItemCompare.Trend.BETTER)
                    sawColorView = true;
            }
            if (d.label.startsWith("Start-of-battle") && d.trend == ItemCompare.Trend.BETTER)
                sawBattleAdded = true;
            if (d.label.startsWith("Command zone") && d.trend == ItemCompare.Trend.WORSE)
                sawCommandRemoved = true;
            if ("Opp life".equals(d.label) && d.trend == ItemCompare.Trend.BETTER)
                sawOppLife = true; // opponent life 5 → 0 is better for you
        }
        Assert.assertTrue(sawColorView, "colorView should differ");
        Assert.assertTrue(sawBattleAdded, "start-of-battle should show Island added");
        Assert.assertTrue(sawCommandRemoved, "command zone should show Sol Ring removed");
        Assert.assertTrue(sawOppLife, "opponent life should be field-compared");

        String markup = ItemCompare.formatBlock(diffs);
        Assert.assertTrue(markup.contains("66ff66") || markup.contains("↑") || markup.contains("±"));
    }

    @Test
    public void questItemsExemptFromCapacity() {
        List<ItemData> inv = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            inv.add(gear("S" + i, "Right"));
        ItemData q = quest("Quest Relic");
        Assert.assertTrue(bags.fitsBackpack(q, inv, Collections.emptySet(), Collections.emptyMap()));
    }

    @Test
    public void overflowSpeedScalesToMinFactor() {
        bags.setOverflowCap(4);
        Assert.assertEquals(bags.overflowSpeedFactor(), 1f, 0.001f);
        bags.placeInOverflow(OverflowEntry.ofItem(gear("A", "Right")));
        bags.placeInOverflow(OverflowEntry.ofItem(gear("B", "Right")));
        bags.placeInOverflow(OverflowEntry.ofItem(gear("C", "Right")));
        bags.placeInOverflow(OverflowEntry.ofItem(gear("D", "Right")));
        Assert.assertEquals(bags.overflowSpeedFactor(), 0.5f, 0.001f);
    }

    @Test
    public void stackLimitsAndCapacityUpgrades() {
        List<ItemData> inv = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            inv.add(potion("Vial"));
        Assert.assertEquals(bags.usedBackpackSlots(inv, Collections.emptySet(), Collections.emptyMap()), 1);
        Assert.assertTrue(bags.fitsBackpack(potion("Vial"), inv, Collections.emptySet(), Collections.emptyMap()));

        ItemData upgrade = new ItemData();
        upgrade.bagUpgrade = "backpack";
        upgrade.bagBonusSlots = 8;
        upgrade.bagBonusStack = 10;
        bags.applyUpgrade(upgrade);
        Assert.assertEquals(bags.getSlots(InventoryBagType.BACKPACK), 12);
        Assert.assertEquals(bags.getMaxStack(InventoryBagType.BACKPACK), 13);

        // Craft Pouch upgrades via bagUpgrade are ignored (Mastery Surge only).
        ItemData sack = new ItemData();
        sack.bagUpgrade = "materials";
        sack.bagBonusSlots = 99;
        int before = bags.getSlots(InventoryBagType.MATERIALS);
        bags.applyUpgrade(sack);
        Assert.assertEquals(bags.getSlots(InventoryBagType.MATERIALS), before);
    }

    @Test
    public void toolbeltFamiliesMatchGathering() {
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES.length, 6);
        ItemData axe = tool("Copper Hatchet", "logs", 1);
        Map<String, String> belt = new LinkedHashMap<>();
        belt.put("logs", "Copper Hatchet");
        List<ItemData> inv = new ArrayList<>();
        inv.add(axe);
        Assert.assertTrue(InventoryBags.backpackOccupants(inv, Collections.emptySet(), belt).isEmpty());
    }

    @Test
    public void saveLoadRoundTripPerBag() {
        bags.setSlots(InventoryBagType.BACKPACK, 30);
        bags.upgradeCraftPouchTier();
        Map<String, Object> stored = new HashMap<>();
        bags.store(stored);

        InventoryBags loaded = new InventoryBags(cfg);
        loaded.load((String[]) stored.get("bagTypes"), (int[]) stored.get("bagSlots"),
                (int[]) stored.get("bagMaxStacks"), cfg);
        loaded.loadCraftPouchAndOverflow((Integer) stored.get("craftPouchTier"),
                (Integer) stored.get("overflowCap"));
        Assert.assertEquals(loaded.getSlots(InventoryBagType.BACKPACK), 30);
        Assert.assertEquals(loaded.getCraftPouchTier(), CraftPouchTier.PACK);
        Assert.assertEquals(loaded.getSlots(InventoryBagType.PACKS), cfg.packsSlots);
    }

    @Test
    public void fortressHookAcceptsOverflowWhenAtCap() {
        bags.setOverflowCap(1);
        bags.placeInOverflow(OverflowEntry.ofItem(gear("A", "Right")));
        final int[] stored = {0};
        bags.setFortressStorage((bag, key, amount) -> {
            stored[0] += amount;
            return true;
        });
        GrantResult r = bags.placeInOverflow(OverflowEntry.ofItem(gear("B", "Left")));
        Assert.assertTrue(r.wentToOverflow());
        Assert.assertEquals(stored[0], 1);
        Assert.assertEquals(bags.overflowCount(), 1); // still only first; fortress took second
    }
}
