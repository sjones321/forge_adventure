package forge.adventure.scene;

import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.NinePatch;
import com.badlogic.gdx.graphics.g2d.Sprite;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.scenes.scene2d.utils.ChangeListener;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.scenes.scene2d.utils.NinePatchDrawable;
import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TextraLabel;
import forge.Forge;
import forge.adventure.data.GatheringMethodData;
import forge.adventure.data.GatheringMethodListData;
import forge.adventure.data.ItemData;
import forge.adventure.data.MaterialData;
import forge.adventure.data.MaterialListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.InventoryBagType;
import forge.adventure.player.InventoryBags;
import forge.adventure.stage.ConsoleCommandInterpreter;
import forge.adventure.stage.GameHUD;
import forge.adventure.stage.MapStage;
import forge.adventure.util.*;
import forge.deck.Deck;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventory UI. Stock Adventure keeps the classic flat list. Ascendant (INV1) uses
 * tabbed bags, a toolbelt row, compact details, and side-by-side compare.
 */
public class InventoryScene extends UIScene {
    TextraButton leave;
    Button equipButton;
    TextraButton useButton;
    TextraLabel itemDescription;
    private final Table inventory;
    private final Array<Button> inventoryButtons = new Array<>();
    private final HashMap<String, Button> equipmentSlots = new HashMap<>();
    HashMap<Button, Pair<String, ItemData>> itemLocation = new HashMap<>();
    HashMap<Button, Deck> deckLocation = new HashMap<>();
    /** Materials tab selection: button → material id. */
    HashMap<Button, String> materialLocation = new HashMap<>();
    /** Currency tab: button → currency key. */
    HashMap<Button, String> currencyLocation = new HashMap<>();
    Button selected;
    Button deleteButton;
    TextraButton repairButton;
    TextraButton materialsTab;
    TextraButton sellOneButton;
    TextraButton sellAllButton;
    /** Stock Materials toggle (Ascendant uses bag tabs instead). */
    private boolean materialsMode = false;
    Texture equipOverlay, unusableOverlay;
    Dialog useDialog, deleteDialog;
    int columns = 0;
    private String selectedSlot = null;
    private NinePatchDrawable slotBorderDrawable = null;
    private static final String SLOT_BORDER_NAME = "slotBorder";
    private static final String SLOT_ITEM_NAME = "slotItem";

    // ---- Ascendant INV1 ----
    private boolean ascendantChromeBuilt = false;
    private InventoryBagType activeBag = InventoryBagType.BACKPACK;
    private final ArrayList<TextraButton> bagTabs = new ArrayList<>();
    private final HashMap<String, Button> toolbeltSlots = new HashMap<>();
    private TextraLabel capacityLabel;
    private TextraButton compareButton;
    private ItemData comparePinnedA;
    private ItemData comparePinnedB;
    private boolean comparePinMode = false;
    private long yButtonDownMs = 0;
    private ScrollPane inventoryScroll;
    private NinePatchDrawable getSlotBorderDrawable() {
        if (slotBorderDrawable == null) {
            int border = 4;
            int size = border * 2 + 2;
            Pixmap pm = new Pixmap(size, size, Pixmap.Format.RGBA8888);
            pm.setColor(new Color(1f, 0.9f, 0.05f, 1f));
            pm.fill();
            pm.setBlending(Pixmap.Blending.None);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fillRectangle(border, border, size - border * 2, size - border * 2);
            Texture tex = new Texture(pm);
            pm.dispose();
            slotBorderDrawable = new NinePatchDrawable(new NinePatch(tex, border, border, border, border));
        }
        return slotBorderDrawable;
    }

    private void addSlotBorder(Button button) {
        Image border = new Image(getSlotBorderDrawable());
        border.setName(SLOT_BORDER_NAME);
        border.setSize(button.getWidth(), button.getHeight());
        button.addActor(border);
    }

    private void removeSlotBorder(Button button) {
        Actor border = button.findActor(SLOT_BORDER_NAME);
        if (border != null) border.remove();
    }

    public InventoryScene() {
        super(Forge.isLandscapeMode() ? "ui/inventory.json" : "ui/inventory_portrait.json");
        equipOverlay = Forge.getAssets().getTexture(Config.instance().getFile(Paths.ITEMS_EQUIP));
        unusableOverlay = Forge.getAssets().getTexture(Config.instance().getFile(Paths.ITEMS_UNUSABLE));
        ui.onButtonPress("return", this::done);
        leave = ui.findActor("return");
        repairButton = ui.findActor("repair");
        ui.onButtonPress("repair", this::repair);
        ui.onButtonPress("delete", this::showConfirm);
        ui.onButtonPress("equip", this::equip);
        ui.onButtonPress("use", this::use);
        equipButton = ui.findActor("equip");
        useButton = ui.findActor("use");
        useButton.setDisabled(true);
        deleteButton = ui.findActor("delete");
        itemDescription = ui.findActor("item_description");
        itemDescription.setAlignment(Align.topLeft);
        itemDescription.setWrap(true);
        ScrollPane pane = new ScrollPane(itemDescription);
        pane.setBounds(itemDescription.getX(), itemDescription.getY(), itemDescription.getWidth() - 5, itemDescription.getHeight() - 8);
        ui.addActor(pane);

        Array<Actor> children = ui.getChildren();
        for (int i = 0, n = children.size; i < n; i++) {
            if (children.get(i).getName() != null && children.get(i).getName().startsWith("Equipment")) {
                String slotName = children.get(i).getName().split("_")[1];
                equipmentSlots.put(slotName, (Button) children.get(i));
                Actor slot = children.get(i);
                slot.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        Button button = ((Button) actor);
                        if (button.isChecked()) {
                            for (Button otherButton : equipmentSlots.values()) {
                                if (button != otherButton && otherButton.isChecked()) {
                                    otherButton.setChecked(false);
                                }
                            }
                            selectedSlot = slotName;
                            updateInventory();
                            Long id = Current.player().itemInSlot(slotName);
                            if (id != null) {
                                Button changeButton = null;
                                for (Button invButton : inventoryButtons) {
                                    if (itemLocation.get(invButton) == null)
                                        continue;
                                    ItemData data = itemLocation.get(invButton).getRight();
                                    if (data != null && id.equals(data.longID)) {
                                        changeButton = invButton;
                                        break;
                                    }
                                }
                                if (changeButton != null)
                                    changeButton.setChecked(true);
                                else if (Config.ascendant()) {
                                    // Equipped items are not listed in bags — show slot item details.
                                    ItemData eq = Current.player().getEquippedItem(id);
                                    if (eq != null)
                                        showItemDetails(eq, null);
                                }
                            } else {
                                setSelected(null);
                            }
                        } else {
                            removeSlotBorder(button);
                            boolean anyChecked = false;
                            for (Button otherButton : equipmentSlots.values()) {
                                if (otherButton.isChecked()) {
                                    anyChecked = true;
                                    break;
                                }
                            }
                            if (!anyChecked) {
                                selectedSlot = null;
                                updateInventory();
                            }
                        }
                    }
                });
            }
        }
        inventory = new Table(Controls.getSkin());
        inventoryScroll = ui.findActor("inventory");
        inventoryScroll.setScrollingDisabled(true, false);
        inventoryScroll.setActor(inventory);
        columns = (int) (inventoryScroll.getWidth() / createInventorySlot().getWidth());
        columns -= 1;
        if (columns <= 0) columns = 1;

        // Stock Ascendant Materials tab (replaced by bag tabs when INV1 chrome builds).
        if (leave != null) {
            materialsTab = Controls.newTextButton("Materials", this::toggleMaterialsMode);
            float tabW = Math.max(70f, leave.getWidth() * 1.15f);
            materialsTab.setBounds(leave.getX() - tabW - 8f, leave.getY(), tabW, leave.getHeight());
            materialsTab.setVisible(false);
            ui.addActor(materialsTab);

            sellOneButton = Controls.newTextButton("Sell", this::sellSelectedMaterial);
            sellOneButton.setBounds(equipButton.getX(), equipButton.getY(), equipButton.getWidth(), equipButton.getHeight());
            sellOneButton.setVisible(false);
            ui.addActor(sellOneButton);

            sellAllButton = Controls.newTextButton("Sell All", this::sellAllSelectedMaterial);
            sellAllButton.setBounds(useButton.getX(), useButton.getY(), useButton.getWidth(), useButton.getHeight());
            sellAllButton.setVisible(false);
            ui.addActor(sellAllButton);
        }
    }

    private void ensureAscendantChrome() {
        if (ascendantChromeBuilt || !Config.ascendant())
            return;
        ascendantChromeBuilt = true;

        // Enlarge bag area; shrink details to a compact strip.
        if (inventoryScroll != null) {
            inventoryScroll.setBounds(145, 36, 330, 175);
        }
        for (Actor a : ui.getChildren()) {
            if (a instanceof Window && a.getX() == 145 && a.getWidth() == 330) {
                a.setBounds(145, 8, 220, 26);
                break;
            }
        }
        if (itemDescription != null) {
            itemDescription.setBounds(148, 10, 214, 22);
            itemDescription.setAlignment(Align.left);
        }

        // Bag tabs
        float tabX = 145;
        float tabY = 214;
        float tabW = 78;
        float tabH = 20;
        for (InventoryBagType type : InventoryBagType.values()) {
            TextraButton tab = Controls.newTextButton(type.label, () -> setActiveBag(type));
            tab.setBounds(tabX, tabY, tabW, tabH);
            ui.addActor(tab);
            bagTabs.add(tab);
            tabX += tabW + 2;
        }
        if (materialsTab != null)
            materialsTab.setVisible(false);

        capacityLabel = Controls.newTextraLabel("");
        capacityLabel.setBounds(145, 234, 200, 16);
        ui.addActor(capacityLabel);

        compareButton = Controls.newTextButton("Compare", this::toggleComparePinMode);
        compareButton.setBounds(350, 234, 70, 18);
        ui.addActor(compareButton);

        // Toolbelt row under paper doll
        float tx = 14;
        float ty = 175;
        for (int i = 0; i < InventoryBags.TOOLBELT_FAMILIES.length; i++) {
            String family = InventoryBags.TOOLBELT_FAMILIES[i];
            Button slot = createInventorySlot();
            slot.setBounds(tx + (i % 3) * 36, ty + (i / 3) * 28, 24, 24);
            final String fam = family;
            ChangeListener listener = new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (((Button) actor).isChecked()) {
                        for (Button other : toolbeltSlots.values()) {
                            if (other != actor)
                                other.setChecked(false);
                        }
                        String toolName = Current.player().getToolbeltTool(fam);
                        if (toolName != null) {
                            ItemData tool = findOwnedItem(toolName);
                            if (tool != null)
                                showItemDetails(tool, null);
                        } else {
                            itemDescription.setText(InventoryBags.TOOLBELT_LABELS[
                                    indexOfFamily(fam)] + " (empty)");
                        }
                    }
                }
            };
            slot.addListener(listener);
            toolbeltSlots.put(family, slot);
            ui.addActor(slot);
            TextraLabel tip = Controls.newTextraLabel("[%60]" + InventoryBags.TOOLBELT_LABELS[i]);
            tip.setPosition(slot.getX(), slot.getY() - 10);
            ui.addActor(tip);
        }

        // Ascendant bindings: Dispose is keyboard-only (Y is details/compare).
        if (deleteButton instanceof TextraButton)
            ((TextraButton) deleteButton).setText("Del");
        if (equipButton instanceof TextraButton)
            ((TextraButton) equipButton).setText("Equip/A");
        if (useButton != null)
            useButton.setText("Use/X");
    }

    private static int indexOfFamily(String family) {
        for (int i = 0; i < InventoryBags.TOOLBELT_FAMILIES.length; i++) {
            if (InventoryBags.TOOLBELT_FAMILIES[i].equals(family))
                return i;
        }
        return 0;
    }

    private ItemData findOwnedItem(String name) {
        if (name == null)
            return null;
        for (ItemData item : Current.player().getItems()) {
            if (item != null && name.equalsIgnoreCase(item.name))
                return item;
        }
        return null;
    }

    private void setActiveBag(InventoryBagType type) {
        if (type == null)
            return;
        activeBag = type;
        materialsMode = type == InventoryBagType.MATERIALS;
        selectedSlot = null;
        for (Button slot : equipmentSlots.values()) {
            removeSlotBorder(slot);
            slot.setChecked(false);
        }
        setSelected(null);
        updateInventory();
        updateAscendantChrome();
    }

    private void cycleBag(int delta) {
        InventoryBagType[] all = InventoryBagType.values();
        int idx = activeBag.ordinal() + delta;
        if (idx < 0)
            idx = all.length - 1;
        if (idx >= all.length)
            idx = 0;
        setActiveBag(all[idx]);
    }

    private void toggleComparePinMode() {
        comparePinMode = !comparePinMode;
        comparePinnedA = null;
        comparePinnedB = null;
        if (compareButton != null)
            compareButton.setText(comparePinMode ? "Pin…" : "Compare");
        if (comparePinMode)
            itemDescription.setText("Compare: select first item, then second.");
    }

    private void updateAscendantChrome() {
        boolean showMats = activeBag == InventoryBagType.MATERIALS;
        for (int i = 0; i < bagTabs.size(); i++) {
            InventoryBagType t = InventoryBagType.values()[i];
            bagTabs.get(i).setText(t == activeBag ? "[" + t.label + "]" : t.label);
        }
        if (sellOneButton != null)
            sellOneButton.setVisible(showMats);
        if (sellAllButton != null)
            sellAllButton.setVisible(showMats);
        if (equipButton != null)
            equipButton.setVisible(!showMats && activeBag != InventoryBagType.CURRENCY);
        if (useButton != null)
            useButton.setVisible(activeBag == InventoryBagType.BACKPACK || activeBag == InventoryBagType.PACKS);
        if (deleteButton != null)
            deleteButton.setVisible(activeBag == InventoryBagType.BACKPACK);
        if (repairButton != null && showMats)
            repairButton.setVisible(false);
        for (Button slot : equipmentSlots.values())
            slot.setVisible(activeBag != InventoryBagType.MATERIALS);
        for (Button slot : toolbeltSlots.values())
            slot.setVisible(activeBag != InventoryBagType.MATERIALS);
        refreshCapacityLabel();
    }

    private void refreshCapacityLabel() {
        if (capacityLabel == null || !Config.ascendant())
            return;
        AdventurePlayer ap = Current.player();
        InventoryBags bags = ap.getBags();
        int used;
        switch (activeBag) {
            case BACKPACK:
                used = bags.usedBackpackSlots(ap.getItems(), ap.getEquippedItems() instanceof java.util.Collection
                        ? new java.util.HashSet<>(ap.getEquippedItems()) : java.util.Collections.emptySet(), ap.getToolbelt());
                break;
            case PACKS:
                used = bags.usedPackSlots(ap.getBoostersOwned());
                break;
            case CURRENCY:
                used = bags.usedCurrencySlots(ap.getContestCurrencies(), ap.getItems());
                break;
            case MATERIALS:
                used = bags.usedMaterialSlots(ap.getMaterials());
                break;
            default:
                used = 0;
        }
        String label = bags.capacityLabel(activeBag, used);
        if (activeBag == InventoryBagType.BACKPACK && bags.isBackpackOverCapacity(ap.getItems(),
                new java.util.HashSet<>(ap.getEquippedItems()), ap.getToolbelt()))
            label += " [#ff6666](over)[]";
        else if (activeBag == InventoryBagType.PACKS && bags.isPacksOverCapacity(ap.getBoostersOwned()))
            label += " [#ff6666](over)[]";
        else if (activeBag == InventoryBagType.MATERIALS && bags.isMaterialsOverCapacity(ap.getMaterials()))
            label += " [#ff6666](over)[]";
        else if (activeBag == InventoryBagType.CURRENCY
                && bags.isCurrencyOverCapacity(ap.getContestCurrencies(), ap.getItems()))
            label += " [#ff6666](over)[]";
        capacityLabel.setText("[%80]" + activeBag.label + " " + label
                + "  stack≤" + bags.getMaxStack(activeBag));
    }

    private void toggleMaterialsMode() {
        if (!Config.ascendant())
            return;
        if (ascendantChromeBuilt) {
            setActiveBag(activeBag == InventoryBagType.MATERIALS
                    ? InventoryBagType.BACKPACK : InventoryBagType.MATERIALS);
            return;
        }
        materialsMode = !materialsMode;
        if (materialsMode) {
            selectedSlot = null;
            for (Button slot : equipmentSlots.values()) {
                removeSlotBorder(slot);
                slot.setChecked(false);
            }
        }
        setSelected(null);
        updateInventory();
        updateMaterialsChrome();
    }

    private void updateMaterialsChrome() {
        if (Config.ascendant() && ascendantChromeBuilt) {
            updateAscendantChrome();
            return;
        }
        boolean show = Config.ascendant() && materialsMode;
        if (materialsTab != null) {
            materialsTab.setVisible(Config.ascendant() && !ascendantChromeBuilt);
            materialsTab.setText(materialsMode ? "Items" : "Materials");
        }
        if (sellOneButton != null)
            sellOneButton.setVisible(show);
        if (sellAllButton != null)
            sellAllButton.setVisible(show);
        if (equipButton != null)
            equipButton.setVisible(!show);
        if (useButton != null)
            useButton.setVisible(!show);
        if (deleteButton != null)
            deleteButton.setVisible(!show);
        if (repairButton != null && show)
            repairButton.setVisible(false);
        for (Button slot : equipmentSlots.values())
            slot.setVisible(!show);
    }

    private void sellSelectedMaterial() {
        if ((Config.ascendant() ? activeBag != InventoryBagType.MATERIALS : !materialsMode) || selected == null)
            return;
        String id = materialLocation.get(selected);
        if (id == null)
            return;
        int price = Current.player().materialSellPrice(id);
        if (Current.player().sellMaterial(id, 1) > 0) {
            itemDescription.setText("Sold 1 for [+GoldCoin] " + price);
            updateInventory();
        }
    }

    private void sellAllSelectedMaterial() {
        if ((Config.ascendant() ? activeBag != InventoryBagType.MATERIALS : !materialsMode) || selected == null)
            return;
        String id = materialLocation.get(selected);
        if (id == null)
            return;
        int have = Current.player().getMaterial(id);
        int price = Current.player().materialSellPrice(id);
        int sold = Current.player().sellMaterial(id, have);
        if (sold > 0) {
            itemDescription.setText("Sold " + sold + " for [+GoldCoin] " + (price * sold));
            setSelected(null);
            updateInventory();
        }
    }

    private void showConfirm() {
        if (deleteDialog == null) {
            deleteDialog = createGenericDialog("", Forge.getLocalizer().getMessage("lblDelete"),
                Forge.getLocalizer().getMessage("lblYes"),
                Forge.getLocalizer().getMessage("lblNo"), () -> {
                    this.delete();
                    removeDialog();
                }, this::removeDialog);
        }
        showDialog(deleteDialog);
    }

    private void repair() {
        if (selected == null)
            return;
        if (itemLocation.get(selected) == null)
            return;
        ItemData data = itemLocation.get(selected).getRight();
        if (data == null)
            return;
        int initialCost;
        try {
            initialCost = (int) (data.cost * 0.4f);
        } catch (Exception e) {
            initialCost = 500;
        }
        if (Current.player().getGold() < initialCost) {
            showDialog(createGenericDialog("", Forge.getLocalizer().getMessage("lblNotEnoughCredits") + "\n[+GoldCoin] " + initialCost,
                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
            return;
        }
        final int cost = initialCost;
        showDialog(createGenericDialog("", "[+" + data.iconName + "] " + data.getDisplayName() + "\n" +
            Forge.getLocalizer().getMessage("lblRepairCost", "[+GoldCoin] " + cost),
            Forge.getLocalizer().getMessage("lblYes"),
            Forge.getLocalizer().getMessage("lblNo"), () -> {
                if (data.isCracked) {
                    data.isCracked = false;
                    updateInventory();
                    setSelected(selected);
                    Current.player().takeGold(cost);
                }
                removeDialog();
            }, this::removeDialog)
        );
    }

    private static InventoryScene object;

    public static InventoryScene instance() {
        if (object == null)
            object = new InventoryScene();
        return object;
    }

    public void done() {
        materialsMode = false;
        activeBag = InventoryBagType.BACKPACK;
        comparePinMode = false;
        comparePinnedA = null;
        comparePinnedB = null;
        selectedSlot = null;
        for (Button slot : equipmentSlots.values()) {
            removeSlotBorder(slot);
            slot.setChecked(false);
        }
        GameHUD.getInstance().getTouchpad().setVisible(false);
        Forge.switchToLast();
    }

    public void delete() {
        if (selected == null)
            return;
        if (itemLocation.get(selected) == null)
            return;
        ItemData data = itemLocation.get(selected).getRight();
        if (data != null) {
            data.isEquipped = false;
            Current.player().removeItem(data);
        }
        updateInventory();
    }

    public void equip() {
        if (selected == null)
            return;
        if (itemLocation.get(selected) == null)
            return;
        ItemData data = itemLocation.get(selected).getRight();
        if (data == null) return;
        Current.player().equip(data);
        updateInventory();
    }

    @Override
    public void act(float delta) {
        stage.act(delta);
    }

    private void triggerUse() {
        if (selected == null)
            return;
        if (itemLocation.get(selected) == null)
            return;
        ItemData data = itemLocation.get(selected).getRight();
        if (data == null) return;
        Current.player().addShards(-data.shardsNeeded);
        done();
        if (data.commandOnUse != null && !data.commandOnUse.isEmpty())
            ConsoleCommandInterpreter.getInstance().command(data.commandOnUse);
        if (data.dialogOnUse != null && data.dialogOnUse.text != null && !data.dialogOnUse.text.isEmpty()) {
            MapDialog dialog = new MapDialog(data.dialogOnUse, MapStage.getInstance(),0,null);
            MapStage.getInstance().showDialog();
            dialog.activate();
            ChangeListener listen = new ChangeListener() {
                @Override
                public void changed(ChangeEvent changeEvent, Actor actor) {
                    AdventureQuestController.instance().showQuestDialogs(MapStage.getInstance());
                }
            };
            dialog.addDialogCompleteListener(listen);
        }
        AdventureQuestController.instance().updateItemUsed(data);
    }

    private void openBooster() {
        if (selected == null) return;

        Deck data = (deckLocation.get(selected));
        if (data == null) return;

        setSelected(null);
        RewardScene.instance().loadRewards(data, RewardScene.Type.EventReward, null, data.getTags().contains("noSell"));
        Forge.switchScene(RewardScene.instance());
        Current.player().getBoostersOwned().removeValue(data, true);
    }

    private void use() {
        if (itemLocation.containsKey(selected)) {
            ItemData data = itemLocation.get(selected).getRight();
            if (data == null)
                return;
            if (Config.ascendant() && data.bagUpgrade != null && !data.bagUpgrade.isEmpty()) {
                if (Current.player().applyBagUpgrade(data)) {
                    setSelected(null);
                    updateInventory();
                }
                return;
            }
            if (Config.ascendant() && data.isGatheringTool()
                    && Current.player().isToolEquipped(data)
                    && !Current.player().getToolEnchantments(data.toolFamily).isEmpty()) {
                unsocketLastEnchantment(data);
                return;
            }
            if (useDialog == null) {
                useDialog = createGenericDialog("", null, Forge.getLocalizer().getMessage("lblYes"),
                        Forge.getLocalizer().getMessage("lblNo"), () -> {
                            this.triggerUse();
                            removeDialog();
                        }, this::removeDialog);
                useDialog.getContentTable().add(Controls.newTextraLabel(Forge.getLocalizer().getMessage("lblUse") + " " + data.getDisplayName() + "?\n" + data.getDescription()));
            }
            showDialog(useDialog);
        }
        if (deckLocation.containsKey(selected)){
            Deck data = deckLocation.get(selected);
            if (data == null)
                return;
            this.openBooster();
        }
    }

    private void unsocketLastEnchantment(ItemData data) {
        if (data == null || !data.isGatheringTool())
            return;
        List<String> all = Current.player().getToolEnchantments(data.toolFamily);
        if (all.isEmpty())
            return;
        String id = all.get(all.size() - 1);
        GatheringMethodData.ToolEnchantment ench = GatheringMethodListData.getEnchantment(id);
        String label = ench != null ? ench.getDisplayName() : id;
        showDialog(createGenericDialog("Unsocket",
                "Remove " + label + " and refund its gem?",
                Forge.getLocalizer().getMessage("lblYes"),
                Forge.getLocalizer().getMessage("lblNo"),
                () -> {
                    String refunded = Current.player().removeToolEnchantment(data.toolFamily, id);
                    if (refunded != null) {
                        MaterialData mat = MaterialListData.get(refunded);
                        itemDescription.setText("Removed " + label
                                + (mat != null ? " → refunded " + mat.getDisplayName() : "") + ".");
                    }
                    setSelected(selected);
                    removeDialog();
                },
                this::removeDialog));
    }

    public void clearItemDescription() {
        itemDescription.setText("");
    }

    private void showItemDetails(ItemData data, ItemData compareTo) {
        if (data == null) {
            clearItemDescription();
            return;
        }
        String status = data.isCracked ? " (" + Forge.getLocalizer().getMessage("lblCracked") + ")" : "";
        String desc = data.getDescription();
        if (Config.ascendant() && data.isGatheringTool())
            desc += toolSocketSummary(data);
        StringBuilder sb = new StringBuilder();
        sb.append(data.getDisplayName()).append(status).append("\n[%80]").append(desc);
        ItemData other = compareTo;
        if (other == null && Config.ascendant())
            other = equippedCounterpart(data);
        if (other != null && other != data) {
            sb.append("\n[%90]Compare vs ").append(other.getDisplayName()).append(":\n");
            sb.append(ItemCompare.formatBlock(ItemCompare.compare(data, other)));
        }
        if (comparePinnedA != null && comparePinnedB != null) {
            sb.append("\n[%90]Pinned:\n");
            sb.append(ItemCompare.formatBlock(ItemCompare.compare(comparePinnedB, comparePinnedA)));
        }
        itemDescription.setText(sb.toString());
    }

    private ItemData equippedCounterpart(ItemData data) {
        if (data == null)
            return null;
        if (data.isGatheringTool()) {
            String name = Current.player().getToolbeltTool(data.toolFamily);
            if (name == null || name.equalsIgnoreCase(data.name))
                return null;
            return findOwnedItem(name);
        }
        if (data.equipmentSlot == null || data.equipmentSlot.isEmpty())
            return null;
        Long id = Current.player().itemInSlot(data.equipmentSlot);
        if (id == null)
            return null;
        ItemData eq = Current.player().getEquippedItem(id);
        if (eq != null && data.longID != null && data.longID.equals(eq.longID))
            return null;
        return eq;
    }

    private void setSelected(Button actor) {
        selected = actor;
        if (actor == null) {
            clearItemDescription();
            deleteButton.setDisabled(true);
            equipButton.setDisabled(true);
            useButton.setDisabled(true);
            if (sellOneButton != null)
                sellOneButton.setDisabled(true);
            if (sellAllButton != null)
                sellAllButton.setDisabled(true);
            repairButton.setVisible(false);
            for (Button button : inventoryButtons) {
                button.setChecked(false);
            }
            return;
        }
        if ((materialsMode || activeBag == InventoryBagType.MATERIALS) && materialLocation.containsKey(actor)) {
            String id = materialLocation.get(actor);
            MaterialData mat = MaterialListData.get(id);
            int count = Current.player().getMaterial(id);
            int price = Current.player().materialSellPrice(id);
            StringBuilder desc = new StringBuilder();
            if (mat != null) {
                desc.append(mat.getDisplayName()).append(" ×").append(count).append("\n[%98]");
                if (mat.family != null && !mat.family.isEmpty())
                    desc.append("Family: ").append(mat.family).append("  Tier ").append(mat.tier).append("\n");
                desc.append("Sell: [+GoldCoin] ").append(price).append(" each");
                if (mat.dustRefine != null) {
                    int yield = Current.player().refineDustYield(id);
                    desc.append("\nRefine: ").append(yield).append(" ").append(mat.dustRefine.rarity).append(" dust");
                }
            } else {
                desc.append(id).append(" ×").append(count);
            }
            itemDescription.setText(desc.toString());
            if (sellOneButton != null)
                sellOneButton.setDisabled(count < 1);
            if (sellAllButton != null)
                sellAllButton.setDisabled(count < 1);
            deleteButton.setDisabled(true);
            equipButton.setDisabled(true);
            useButton.setDisabled(true);
            repairButton.setVisible(false);
            for (Button button : inventoryButtons) {
                if (actor != button && button.isChecked())
                    button.setChecked(false);
            }
            performTouch(scrollPaneOfActor(itemDescription));
            return;
        }
        if (Config.ascendant() && currencyLocation.containsKey(actor)) {
            String key = currencyLocation.get(actor);
            itemDescription.setText(currencyDescription(key));
            deleteButton.setDisabled(true);
            equipButton.setDisabled(true);
            useButton.setDisabled(true);
            repairButton.setVisible(false);
            for (Button button : inventoryButtons) {
                if (actor != button && button.isChecked())
                    button.setChecked(false);
            }
            return;
        }
        if (itemLocation.containsKey(actor)) {
            ItemData data = itemLocation.get(actor).getRight();
            if (data == null) return;

            if (comparePinMode && Config.ascendant()) {
                if (comparePinnedA == null) {
                    comparePinnedA = data;
                    itemDescription.setText("Pinned A: " + data.getDisplayName() + "\nSelect second item.");
                } else if (comparePinnedB == null) {
                    comparePinnedB = data;
                    comparePinMode = false;
                    if (compareButton != null)
                        compareButton.setText("Compare");
                    showItemDetails(comparePinnedB, comparePinnedA);
                }
            }

            deleteButton.setDisabled(data.questItem);

            boolean isInPoi = MapStage.getInstance().isInMap();
            useButton.setDisabled(!(isInPoi && data.usableInPoi || !isInPoi && data.usableOnWorldMap));
            if (Config.ascendant() && data.bagUpgrade != null && !data.bagUpgrade.isEmpty()) {
                useButton.setDisabled(false);
                useButton.setText("Apply");
                useButton.layout();
            } else if (data.shardsNeeded == 0)
                useButton.setText(Forge.getLocalizer().getMessage("lblUse"));
            else
                useButton.setText(Forge.getLocalizer().getMessage("lblUse") + " " + data.shardsNeeded + "[+Shards]");
            useButton.layout();
            if (Current.player().getShards() < data.shardsNeeded
                    && (data.bagUpgrade == null || data.bagUpgrade.isEmpty()))
                useButton.setDisabled(true);

            if (data.isGatheringTool()) {
                equipButton.setDisabled(false);
                if (equipButton instanceof TextraButton) {
                    TextraButton button = (TextraButton) equipButton;
                    if (Current.player().isToolEquipped(data))
                        button.setText("Unequip");
                    else
                        button.setText(Forge.getLocalizer().getMessage("lblEquip"));
                    button.layout();
                }
                if (Config.ascendant() && Current.player().isToolEquipped(data)
                        && !Current.player().getToolEnchantments(data.toolFamily).isEmpty()) {
                    useButton.setDisabled(false);
                    useButton.setText("Unsocket");
                    useButton.layout();
                }
            } else if (data.equipmentSlot == null || data.equipmentSlot.isEmpty() || data.isCracked) {
                equipButton.setDisabled(true);
            } else {
                equipButton.setDisabled(false);
                if (equipButton instanceof TextraButton) {
                    TextraButton button = (TextraButton) equipButton;
                    Long id = Current.player().itemInSlot(data.equipmentSlot);
                    if (id != null && id.equals(data.longID) && data.isEquipped) {
                        button.setText("Unequip");
                    } else {
                        button.setText(Forge.getLocalizer().getMessage("lblEquip"));
                    }
                    button.layout();
                }
            }
            repairButton.setVisible(data.isCracked);
            if (!comparePinMode || comparePinnedB != null)
                showItemDetails(data, null);
        }
        else if (deckLocation.containsKey(actor)){
            Deck data = (deckLocation.get(actor));
            if (data == null) return;

            deleteButton.setDisabled(true);
            useButton.setDisabled(false);
            useButton.setText(Forge.getLocalizer().getMessage("lblOpen"));
            useButton.layout();
            equipButton.setDisabled(true);
            repairButton.setVisible(false);

            itemDescription.setText(data.getName() + "\n[%98]" + (data.getComment() == null?"":data.getComment()+" - ") + data.getAllCardsInASinglePool(true, true).countAll() + " cards");
        }

        for (Button button : inventoryButtons) {
            if (actor != button && button.isChecked()) {
                button.setChecked(false);
            }
        }

        performTouch(scrollPaneOfActor(itemDescription));
    }

    private String currencyDescription(String key) {
        AdventurePlayer ap = Current.player();
        switch (key) {
            case InventoryBags.CURRENCY_GOLD:
                return "Gold\n[%80][+GoldCoin] " + ap.getGold();
            case InventoryBags.CURRENCY_SHARDS:
                return "Mana Shards\n[%80][+Shards] " + ap.getShards();
            case InventoryBags.CURRENCY_DUST_C:
                return "Common Dust\n[%80]" + ap.getDust(0);
            case InventoryBags.CURRENCY_DUST_U:
                return "Uncommon Dust\n[%80]" + ap.getDust(1);
            case InventoryBags.CURRENCY_DUST_R:
                return "Rare Dust\n[%80]" + ap.getDust(2);
            case InventoryBags.CURRENCY_DUST_M:
                return "Mythic Dust\n[%80]" + ap.getDust(3);
            default:
                if (key != null && key.startsWith("item:")) {
                    String name = key.substring(5);
                    return name + "\n[%80]×" + ap.countItem(name);
                }
                return key + "\n[%80]×" + ap.getContestCurrency(key);
        }
    }

    private void updateInventory() {
        clearSelectable();
        inventoryButtons.clear();
        inventory.clear();
        itemLocation.clear();
        deckLocation.clear();
        materialLocation.clear();
        currencyLocation.clear();
        repairButton.setVisible(false);

        if (Config.ascendant() && ascendantChromeBuilt) {
            switch (activeBag) {
                case MATERIALS:
                    updateMaterialsInventory();
                    break;
                case PACKS:
                    updatePacksInventory();
                    break;
                case CURRENCY:
                    updateCurrencyInventory();
                    break;
                case BACKPACK:
                default:
                    updateBackpackInventory();
                    break;
            }
            updateToolbeltSlots();
            refreshCapacityLabel();
            return;
        }

        if (materialsMode && Config.ascendant()) {
            updateMaterialsInventory();
            return;
        }

        updateLegacyFlatInventory();
    }

    private void updateLegacyFlatInventory() {
        int itemSlotsUsed = 0;
        ArrayList<ItemData> items = new ArrayList<>();
        for (int i = 0; i < Current.player().getItems().size(); i++) {
            ItemData item = Current.player().getItems().get(i);
            if (item == null) {
                continue;
            }
            if (item.sprite() == null) {
                System.err.print("Can not find sprite name " + item.iconName + "\n");
                continue;
            }
            if (selectedSlot != null && !selectedSlot.equals(item.equipmentSlot)) {
                continue;
            }
            items.add(item);
        }
        items.sort((o1, o2) -> {
            if (o1.equipmentSlot == null && o2.equipmentSlot == null) {
                return o1.name.compareTo(o2.name);
            } else if (o1.equipmentSlot == null) {
                return 1;
            } else if (o2.equipmentSlot == null) {
                return -1;
            } else {
                int slotCompare = o1.equipmentSlot.compareTo(o2.equipmentSlot);
                if (slotCompare != 0) {
                    return slotCompare;
                }
                return o1.name.compareTo(o2.name);
            }
        });

        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0)
                inventory.row();
            Button newActor = createInventorySlot();
            inventory.add(newActor).top().left().space(1);
            addToSelectable(new Selectable(newActor) {
                @Override
                public void onSelect(UIScene scene) {
                    setSelected(newActor);
                    super.onSelect(scene);
                }
            });
            inventoryButtons.add(newActor);

            ItemData item = items.get(i);
            Image img = new Image(item.sprite());
            img.setX((newActor.getWidth() - img.getWidth()) / 2);
            img.setY((newActor.getHeight() - img.getHeight()) / 2);
            newActor.addActor(img);
            itemLocation.put(newActor, Pair.of(item.name, item));
            if ((item.isEquipped && item.longID != null && Current.player().getEquippedItems().contains(item.longID))
                    || Current.player().isToolEquipped(item)) {
                Image overlay = new Image(equipOverlay);
                overlay.setX((newActor.getWidth() - img.getWidth()) / 2);
                overlay.setY((newActor.getHeight() - img.getHeight()) / 2);
                newActor.addActor(overlay);
            } else if (item.isCracked) {
                Image overlay = new Image(unusableOverlay);
                overlay.setX((newActor.getWidth() - img.getWidth()) / 2);
                overlay.setY((newActor.getHeight() - img.getHeight()) / 2);
                newActor.addActor(overlay);
            }
            newActor.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (((Button) actor).isChecked()) {
                        setSelected((Button) actor);
                    }
                }
            });
            itemSlotsUsed++;
        }

        if (selectedSlot == null) {
            for (int i = 0; i < Current.player().getBoostersOwned().size; i++) {
                if ((i + itemSlotsUsed) % columns == 0)
                    inventory.row();
                Button newActor = createInventorySlot();
                inventory.add(newActor).top().left().space(1);
                addToSelectable(new Selectable(newActor) {
                    @Override
                    public void onSelect(UIScene scene) {
                        setSelected(newActor);
                        super.onSelect(scene);
                    }
                });
                inventoryButtons.add(newActor);
                Deck deck = Current.player().getBoostersOwned().get(i);
                if (deck == null | deck.isEmpty()) {
                    System.err.print("Can not add null / empty booster " + Current.player().getBoostersOwned().get(i) + "\n");
                    continue;
                }
                Sprite deckSprite = Config.instance().getItemSprite("Deck");

                Image img = new Image(deckSprite);
                img.setX((newActor.getWidth() - img.getWidth()) / 2);
                img.setY((newActor.getHeight() - img.getHeight()) / 2);
                newActor.addActor(img);
                deckLocation.put(newActor, Current.player().getBoostersOwned().get(i));
                newActor.addListener(new ChangeListener() {
                    @Override
                    public void changed(ChangeEvent event, Actor actor) {
                        if (((Button) actor).isChecked()) {
                            setSelected((Button) actor);
                        }
                    }
                });
            }
        }

        refreshEquipmentDoll();
        repairButton.setZIndex(ui.getChildren().size);
    }

    private void updateBackpackInventory() {
        AdventurePlayer ap = Current.player();
        List<ItemData> occupants = ap.getBackpackItems();
        if (selectedSlot != null) {
            List<ItemData> filtered = new ArrayList<>();
            for (ItemData item : occupants) {
                if (selectedSlot.equals(item.equipmentSlot))
                    filtered.add(item);
            }
            occupants = filtered;
        }
        occupants.sort((o1, o2) -> {
            if (o1.equipmentSlot == null && o2.equipmentSlot == null)
                return o1.name.compareTo(o2.name);
            if (o1.equipmentSlot == null) return 1;
            if (o2.equipmentSlot == null) return -1;
            int slotCompare = o1.equipmentSlot.compareTo(o2.equipmentSlot);
            return slotCompare != 0 ? slotCompare : o1.name.compareTo(o2.name);
        });

        List<List<ItemData>> groups = InventoryBags.stackGroups(occupants,
                ap.getBags().getMaxStack(InventoryBagType.BACKPACK));
        int i = 0;
        for (List<ItemData> group : groups) {
            if (group.isEmpty())
                continue;
            ItemData item = group.get(0);
            if (item.sprite() == null) {
                System.err.print("Can not find sprite name " + item.iconName + "\n");
                continue;
            }
            if (i % columns == 0)
                inventory.row();
            Button newActor = createInventorySlot();
            inventory.add(newActor).top().left().space(1);
            addToSelectable(new Selectable(newActor) {
                @Override
                public void onSelect(UIScene scene) {
                    setSelected(newActor);
                    super.onSelect(scene);
                }
            });
            inventoryButtons.add(newActor);
            Image img = new Image(item.sprite());
            img.setX((newActor.getWidth() - img.getWidth()) / 2);
            img.setY((newActor.getHeight() - img.getHeight()) / 2);
            newActor.addActor(img);
            itemLocation.put(newActor, Pair.of(item.name, item));
            if (group.size() > 1) {
                TextraLabel count = Controls.newTextraLabel("[%70]" + group.size());
                count.setPosition(2, 2);
                newActor.addActor(count);
            }
            if (item.isCracked) {
                Image overlay = new Image(unusableOverlay);
                overlay.setX((newActor.getWidth() - img.getWidth()) / 2);
                overlay.setY((newActor.getHeight() - img.getHeight()) / 2);
                newActor.addActor(overlay);
            }
            // Hover/click compare via selection; also mouse-over shows compare text.
            final ItemData hoverItem = item;
            newActor.addListener(new ClickListener() {
                @Override
                public void enter(InputEvent event, float x, float y, int pointer, Actor fromActor) {
                    if (pointer == -1)
                        showItemDetails(hoverItem, null);
                }
            });
            newActor.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (((Button) actor).isChecked())
                        setSelected((Button) actor);
                }
            });
            i++;
        }
        refreshEquipmentDoll();
        repairButton.setZIndex(ui.getChildren().size);
    }

    private void updatePacksInventory() {
        Array<Deck> boosters = Current.player().getBoostersOwned();
        for (int i = 0; i < boosters.size; i++) {
            if (i % columns == 0)
                inventory.row();
            Button newActor = createInventorySlot();
            inventory.add(newActor).top().left().space(1);
            addToSelectable(new Selectable(newActor) {
                @Override
                public void onSelect(UIScene scene) {
                    setSelected(newActor);
                    super.onSelect(scene);
                }
            });
            inventoryButtons.add(newActor);
            Deck deck = boosters.get(i);
            if (deck == null || deck.isEmpty())
                continue;
            Sprite deckSprite = Config.instance().getItemSprite("Deck");
            Image img = new Image(deckSprite);
            img.setX((newActor.getWidth() - img.getWidth()) / 2);
            img.setY((newActor.getHeight() - img.getHeight()) / 2);
            newActor.addActor(img);
            deckLocation.put(newActor, deck);
            newActor.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (((Button) actor).isChecked())
                        setSelected((Button) actor);
                }
            });
        }
        refreshEquipmentDoll();
    }

    private void updateCurrencyInventory() {
        AdventurePlayer ap = Current.player();
        addCurrencySlot(InventoryBags.CURRENCY_GOLD, "Gold", "GoldCoin", ap.getGold());
        addCurrencySlot(InventoryBags.CURRENCY_SHARDS, "Shards", "Shards", ap.getShards());
        addCurrencySlot(InventoryBags.CURRENCY_DUST_C, "C Dust", "Mana", ap.getDust(0));
        addCurrencySlot(InventoryBags.CURRENCY_DUST_U, "U Dust", "Mana", ap.getDust(1));
        addCurrencySlot(InventoryBags.CURRENCY_DUST_R, "R Dust", "Mana", ap.getDust(2));
        addCurrencySlot(InventoryBags.CURRENCY_DUST_M, "M Dust", "Mana", ap.getDust(3));
        for (Map.Entry<String, Integer> e : ap.getContestCurrencies().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0)
                continue;
            addCurrencySlot(e.getKey(), e.getKey(), "ChallengeCoin", e.getValue());
        }
        // Challenge / contest coin ItemData stacks
        Map<String, Integer> coinCounts = new HashMap<>();
        for (ItemData item : ap.getItems()) {
            if (item == null || InventoryBags.classifyItem(item) != InventoryBagType.CURRENCY)
                continue;
            coinCounts.merge(item.name, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : coinCounts.entrySet()) {
            ItemData sample = findOwnedItem(e.getKey());
            String icon = sample != null ? sample.iconName : "ChallengeCoin";
            addCurrencySlot("item:" + e.getKey(), e.getKey(), icon, e.getValue());
            // Also map to itemLocation for the first instance so Use/Equip paths stay quiet.
            // (Currency items are not equipped.)
        }
        refreshEquipmentDoll();
    }

    private void addCurrencySlot(String key, String ignoredLabel, String iconName, int amount) {
        if (inventoryButtons.size % columns == 0)
            inventory.row();
        Button newActor = createInventorySlot();
        inventory.add(newActor).top().left().space(1);
        addToSelectable(new Selectable(newActor) {
            @Override
            public void onSelect(UIScene scene) {
                setSelected(newActor);
                super.onSelect(scene);
            }
        });
        inventoryButtons.add(newActor);
        currencyLocation.put(newActor, key);
        Sprite sprite = Config.instance().getItemSprite(iconName);
        if (sprite != null) {
            Image img = new Image(sprite);
            img.setX((newActor.getWidth() - img.getWidth()) / 2);
            img.setY((newActor.getHeight() - img.getHeight()) / 2);
            newActor.addActor(img);
        }
        TextraLabel count = Controls.newTextraLabel("[%70]" + amount);
        count.setPosition(2, 2);
        newActor.addActor(count);
        newActor.addListener(new ChangeListener() {
            @Override
            public void changed(ChangeEvent event, Actor actor) {
                if (((Button) actor).isChecked())
                    setSelected((Button) actor);
            }
        });
    }

    private void updateToolbeltSlots() {
        AdventurePlayer ap = Current.player();
        for (Map.Entry<String, Button> e : toolbeltSlots.entrySet()) {
            Button slotButton = e.getValue();
            Actor oldItem = slotButton.findActor(SLOT_ITEM_NAME);
            if (oldItem != null)
                oldItem.remove();
            String toolName = ap.getToolbeltTool(e.getKey());
            if (toolName == null)
                continue;
            ItemData item = findOwnedItem(toolName);
            if (item == null || item.sprite() == null)
                continue;
            Image img = new Image(item.sprite());
            img.setName(SLOT_ITEM_NAME);
            img.setX((slotButton.getWidth() - img.getWidth()) / 2);
            img.setY((slotButton.getHeight() - img.getHeight()) / 2);
            slotButton.addActor(img);
        }
    }

    private void refreshEquipmentDoll() {
        for (Map.Entry<String, Button> slot : equipmentSlots.entrySet()) {
            Button slotButton = slot.getValue();
            Actor oldItem = slotButton.findActor(SLOT_ITEM_NAME);
            if (oldItem != null) oldItem.remove();
            removeSlotBorder(slotButton);

            Long id = Current.player().itemInSlot(slot.getKey());
            if (id != null) {
                ItemData item = Current.player().getEquippedItem(id);
                if (item != null) {
                    Image img = new Image(item.sprite());
                    img.setName(SLOT_ITEM_NAME);
                    img.setX((slotButton.getWidth() - img.getWidth()) / 2);
                    img.setY((slotButton.getHeight() - img.getHeight()) / 2);
                    slotButton.addActor(img);
                }
            }
            if (slot.getKey().equals(selectedSlot)) {
                addSlotBorder(slotButton);
            }
        }
    }

    private void updateMaterialsInventory() {
        List<Map.Entry<String, Integer>> owned = new ArrayList<>(Current.player().getMaterials().entrySet());
        owned.sort(Comparator
                .comparing((Map.Entry<String, Integer> e) -> {
                    MaterialData m = MaterialListData.get(e.getKey());
                    return m != null && m.family != null ? m.family : "";
                })
                .thenComparing(e -> {
                    MaterialData m = MaterialListData.get(e.getKey());
                    return m != null ? m.tier : 0;
                })
                .thenComparing(e -> {
                    MaterialData m = MaterialListData.get(e.getKey());
                    return m != null ? m.getDisplayName() : e.getKey();
                }));

        int i = 0;
        for (Map.Entry<String, Integer> entry : owned) {
            if (entry.getValue() == null || entry.getValue() <= 0)
                continue;
            MaterialData mat = MaterialListData.get(entry.getKey());
            if (i % columns == 0)
                inventory.row();
            Button newActor = createInventorySlot();
            inventory.add(newActor).top().left().space(1);
            addToSelectable(new Selectable(newActor) {
                @Override
                public void onSelect(UIScene scene) {
                    setSelected(newActor);
                    super.onSelect(scene);
                }
            });
            inventoryButtons.add(newActor);
            materialLocation.put(newActor, entry.getKey());

            Sprite sprite = mat != null ? mat.sprite() : Config.instance().getItemSprite("Item");
            if (sprite != null) {
                Image img = new Image(sprite);
                img.setX((newActor.getWidth() - img.getWidth()) / 2);
                img.setY((newActor.getHeight() - img.getHeight()) / 2);
                newActor.addActor(img);
            }
            TextraLabel count = Controls.newTextraLabel("[%80]" + entry.getValue());
            count.setPosition(2, 2);
            newActor.addActor(count);

            newActor.addListener(new ChangeListener() {
                @Override
                public void changed(ChangeEvent event, Actor actor) {
                    if (((Button) actor).isChecked())
                        setSelected((Button) actor);
                }
            });
            i++;
        }
        if (sellOneButton != null)
            sellOneButton.setDisabled(selected == null || !materialLocation.containsKey(selected));
        if (sellAllButton != null)
            sellAllButton.setDisabled(selected == null || !materialLocation.containsKey(selected));
    }

    @Override
    public void enter() {
        materialsMode = false;
        activeBag = InventoryBagType.BACKPACK;
        selectedSlot = null;
        comparePinMode = false;
        comparePinnedA = null;
        comparePinnedB = null;
        for (Button slot : equipmentSlots.values()) {
            removeSlotBorder(slot);
            slot.setChecked(false);
        }
        clearItemDescription();
        ensureAscendantChrome();
        updateMaterialsChrome();
        if (Config.ascendant())
            updateAscendantChrome();
        updateInventory();
        super.enter();
    }

    /**
     * Ascendant INV1 controller map (does not change global KeyBinding / Party=P):
     * A select/equip, X use, Y details (hold Y = pin compare), L1/R1 switch bags.
     * Keyboard: Enter equip, E use, Q details, C compare, PgUp/PgDn tabs, Del dispose.
     */
    @Override
    public boolean keyPressed(int keycode) {
        if (Config.ascendant() && ascendantChromeBuilt) {
            if (KeyBinding.ScrollUp.isPressed(keycode)) {
                cycleBag(-1);
                return true;
            }
            if (KeyBinding.ScrollDown.isPressed(keycode)) {
                cycleBag(1);
                return true;
            }
            if (KeyBinding.Status.isPressed(keycode) || keycode == Input.Keys.BUTTON_Y) {
                yButtonDownMs = System.currentTimeMillis();
                return true;
            }
            if (keycode == Input.Keys.C) {
                toggleComparePinMode();
                return true;
            }
            if (keycode == Input.Keys.FORWARD_DEL || keycode == Input.Keys.DEL) {
                if (!deleteButton.isDisabled())
                    showConfirm();
                return true;
            }
            // A = equip (roadmap); X = use — only consume when acting on a selection.
            if (selected != null && (KeyBinding.Use.isPressed(keycode) || keycode == Input.Keys.BUTTON_A)) {
                if (!equipButton.isDisabled() && equipButton.isVisible()) {
                    equip();
                    return true;
                }
            }
            if (selected != null && (KeyBinding.Equip.isPressed(keycode) || keycode == Input.Keys.BUTTON_X)) {
                if (!useButton.isDisabled() && useButton.isVisible()) {
                    use();
                    return true;
                }
            }
        }
        return super.keyPressed(keycode);
    }

    @Override
    public boolean keyReleased(int keycode) {
        if (Config.ascendant() && ascendantChromeBuilt
                && (KeyBinding.Status.isPressed(keycode) || keycode == Input.Keys.BUTTON_Y)) {
            long held = System.currentTimeMillis() - yButtonDownMs;
            if (held >= 400)
                toggleComparePinMode();
            else if (selected != null && itemLocation.containsKey(selected))
                showItemDetails(itemLocation.get(selected).getRight(), null);
            yButtonDownMs = 0;
            return true;
        }
        return super.keyReleased(keycode);
    }

    private static String toolSocketSummary(ItemData data) {
        if (data == null || !data.isGatheringTool())
            return "";
        AdventurePlayer ap = Current.player();
        if (!ap.isToolEquipped(data))
            return "\nSockets: equip on toolbelt to enchant.";
        int slots = ap.toolEnchantSlots(data.toolFamily);
        List<String> all = ap.getToolEnchantments(data.toolFamily);
        List<String> active = ap.getActiveToolEnchantments(data.toolFamily);
        if (slots <= 0)
            return "\nSockets: none (need tier "
                    + Config.instance().getConfigData().toolEnchantSocketMinTier + "+)."
                    + (all.isEmpty() ? "" : "\nStored (inactive): " + all.size()
                    + " — equip a higher-tier tool or Unsocket.");
        StringBuilder sb = new StringBuilder("\nSockets: ").append(active.size())
                .append("/").append(slots).append(" active");
        for (String id : active) {
            GatheringMethodData.ToolEnchantment e = GatheringMethodListData.getEnchantment(id);
            sb.append("\n  · ").append(e != null ? e.getDisplayName() : id);
        }
        if (all.size() > active.size()) {
            sb.append("\nInactive (no slot):");
            for (int i = active.size(); i < all.size(); i++) {
                GatheringMethodData.ToolEnchantment e = GatheringMethodListData.getEnchantment(all.get(i));
                sb.append("\n  · ").append(e != null ? e.getDisplayName() : all.get(i));
            }
        }
        if (!all.isEmpty())
            sb.append("\nUse Unsocket to remove the last gem (refunded).");
        return sb.toString();
    }

    public Button createInventorySlot() {
        return new ImageButton(Controls.getSkin(), "item_frame");
    }
}
