package forge.adventure;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EffectData;
import forge.adventure.data.ItemData;
import forge.adventure.player.InventoryBagType;
import forge.adventure.player.InventoryBags;
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
 * Headless INV1 coverage: migration classification, equipped exclusion, overflow,
 * stack limits, upgrades, toolbelt families, compare diffs, capacity round-trip.
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
        cfg.materialsSlots = 3;
        cfg.materialsMaxStack = 5;
        bags = new InventoryBags(cfg);
    }

    private static ItemData gear(String name, String slot) {
        ItemData i = new ItemData();
        i.name = name;
        i.equipmentSlot = slot;
        i.effect = new EffectData();
        i.longID = IDS.getAndIncrement();
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
    public void overflowNeverDeletesAndRefusesNewItems() {
        List<ItemData> inv = new ArrayList<>();
        for (int i = 0; i < 6; i++)
            inv.add(gear("Sword" + i, "Right"));
        Assert.assertTrue(bags.isBackpackOverCapacity(inv, Collections.emptySet(), Collections.emptyMap()));
        Assert.assertEquals(inv.size(), 6);

        ItemData extra = gear("Extra", "Left");
        Assert.assertFalse(bags.canAcceptBackpackItem(extra, inv, Collections.emptySet(), Collections.emptyMap()));
        Assert.assertNotNull(bags.getLastRefuseMessage());
        Assert.assertTrue(bags.getLastRefuseMessage().contains("full")
                || bags.getLastRefuseMessage().contains("Fortress"));
        Assert.assertEquals(inv.size(), 6);
    }

    @Test
    public void stackLimitsAndCapacityUpgrades() {
        List<ItemData> inv = new ArrayList<>();
        for (int i = 0; i < 3; i++)
            inv.add(potion("Vial"));
        Assert.assertEquals(bags.usedBackpackSlots(inv, Collections.emptySet(), Collections.emptyMap()), 1);
        Assert.assertTrue(bags.canAcceptBackpackItem(potion("Vial"), inv, Collections.emptySet(), Collections.emptyMap()));

        inv.add(potion("Vial"));
        Assert.assertEquals(bags.usedBackpackSlots(inv, Collections.emptySet(), Collections.emptyMap()), 2);

        ItemData upgrade = new ItemData();
        upgrade.bagUpgrade = "backpack";
        upgrade.bagBonusSlots = 8;
        upgrade.bagBonusStack = 10;
        bags.applyUpgrade(upgrade);
        Assert.assertEquals(bags.getSlots(InventoryBagType.BACKPACK), 12);
        Assert.assertEquals(bags.getMaxStack(InventoryBagType.BACKPACK), 13);
    }

    @Test
    public void toolbeltFamiliesMatchGathering() {
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES.length, 6);
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES[0], "logs");
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES[1], "ore");
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES[2], "plants");
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES[3], "sacred_stone");
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES[4], "waters");
        Assert.assertEquals(InventoryBags.TOOLBELT_FAMILIES[5], "scrap");

        ItemData axe = tool("Copper Hatchet", "logs", 1);
        Map<String, String> belt = new LinkedHashMap<>();
        belt.put("logs", "Copper Hatchet");
        List<ItemData> inv = new ArrayList<>();
        inv.add(axe);
        Assert.assertTrue(InventoryBags.backpackOccupants(inv, Collections.emptySet(), belt).isEmpty());
    }

    @Test
    public void compareMarksBetterAndWorseForGearAndTools() {
        ItemData weak = gear("Weak", "Body");
        weak.effect.lifeModifier = 1;
        weak.effect.moveSpeed = 1.0f;
        ItemData strong = gear("Strong", "Body");
        strong.effect.lifeModifier = 5;
        strong.effect.moveSpeed = 1.2f;
        strong.effect.cardRewardBonus = 1;

        List<ItemCompare.Diff> diffs = ItemCompare.compare(strong, weak);
        boolean sawLifeBetter = false;
        boolean sawSpeedBetter = false;
        for (ItemCompare.Diff d : diffs) {
            if ("Life".equals(d.label) && d.trend == ItemCompare.Trend.BETTER)
                sawLifeBetter = true;
            if ("Move speed".equals(d.label) && d.trend == ItemCompare.Trend.BETTER)
                sawSpeedBetter = true;
        }
        Assert.assertTrue(sawLifeBetter);
        Assert.assertTrue(sawSpeedBetter);

        ItemData t1 = tool("Copper Pickaxe", "ore", 1);
        ItemData t3 = tool("Mithril Pickaxe", "ore", 3);
        List<ItemCompare.Diff> toolDiffs = ItemCompare.compare(t3, t1);
        boolean sawTier = false;
        for (ItemCompare.Diff d : toolDiffs) {
            if ("Tool tier".equals(d.label) && d.trend == ItemCompare.Trend.BETTER)
                sawTier = true;
        }
        Assert.assertTrue(sawTier);

        String markup = ItemCompare.formatBlock(diffs);
        Assert.assertTrue(markup.contains("66ff66") || markup.contains("↑"));
    }

    @Test
    public void saveLoadRoundTripPerBag() {
        bags.setSlots(InventoryBagType.BACKPACK, 30);
        bags.setMaxStack(InventoryBagType.MATERIALS, 150);
        Map<String, Object> stored = new HashMap<>();
        bags.store(stored);

        InventoryBags loaded = new InventoryBags(cfg);
        loaded.load((String[]) stored.get("bagTypes"), (int[]) stored.get("bagSlots"),
                (int[]) stored.get("bagMaxStacks"), cfg);
        Assert.assertEquals(loaded.getSlots(InventoryBagType.BACKPACK), 30);
        Assert.assertEquals(loaded.getMaxStack(InventoryBagType.MATERIALS), 150);
        Assert.assertEquals(loaded.getSlots(InventoryBagType.PACKS), cfg.packsSlots);
    }

    @Test
    public void materialsAndPacksRefuseWhenFull() {
        Map<String, Integer> mats = new LinkedHashMap<>();
        mats.put("oak", 5);
        mats.put("iron", 5);
        mats.put("copper", 5);
        Assert.assertFalse(bags.canAcceptMaterial("willow", 1, mats));
        Assert.assertEquals(mats.size(), 3);

        Assert.assertFalse(bags.canAcceptMaterial("oak", 1, mats));

        List<Deck> packs = new ArrayList<>();
        packs.add(new Deck("A"));
        packs.add(new Deck("B"));
        Assert.assertFalse(bags.canAcceptBooster(packs));
        Assert.assertEquals(packs.size(), 2);
    }

    @Test
    public void fortressHookAcceptsOverflow() {
        final int[] stored = {0};
        bags.setFortressStorage((bag, key, amount) -> {
            stored[0] += amount;
            return true;
        });
        List<ItemData> inv = new ArrayList<>();
        for (int i = 0; i < 4; i++)
            inv.add(gear("S" + i, "Right"));
        Assert.assertTrue(bags.canAcceptBackpackItem(gear("Extra", "Left"), inv,
                Collections.emptySet(), Collections.emptyMap()));
        Assert.assertEquals(stored[0], 1);
    }
}
