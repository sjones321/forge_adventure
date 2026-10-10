package forge.adventure.scene;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.CardReagentCost;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.assets.FSkinImage;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRenderer;
import forge.card.CardZoom;
import forge.item.PaperCard;
import forge.itemmanager.CardManager;
import forge.itemmanager.ItemManager;
import forge.itemmanager.ItemManagerConfig;
import forge.model.FModel;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FCheckBox;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.util.ItemPool;
import forge.util.Utils;

/**
 * Ascendant crafting UI: search the craftable pool, preview cards, spend dust + reagents to craft.
 */
public class CraftingScreen extends FScreen {
    private static final float PADDING = Utils.scale(5f);
    private static final FSkinColor MISSING_COLOR = FSkinColor.getStandardColor(200, 60, 60);

    private final CraftCardManager cardManager = add(new CraftCardManager());
    private final FLabel lblDust = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FLabel lblDetail = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FLabel lblReagents = add(new FLabel.Builder().text("").font(FSkinFont.get(12)).align(Align.left).build());
    private final FButton btnCraft = add(new FButton("Craft"));
    private final FCheckBox chkAutoSalvage = add(new FCheckBox("Auto-salvage extras beyond keep limit"));

    /** Cost structure cache (independent of inventory). Cleared when the craftable pool reloads. */
    private final Map<PaperCard, CardReagentCost> reagentCostCache = new HashMap<>();
    /** Affordability cache; cleared on material changes. */
    private final Map<PaperCard, Boolean> reagentAffordCache = new HashMap<>();
    private boolean listenersRegistered;

    public CraftingScreen() {
        super("Crafting");

        cardManager.setup(ItemManagerConfig.ADVENTURE_EDITOR_POOL);
        cardManager.setCaption("Craftable cards");
        cardManager.setSelectionChangedHandler(e -> updateDetail());
        cardManager.setItemActivateHandler(e -> {
            PaperCard card = cardManager.getSelectedItem();
            if (card != null)
                CardZoom.show(card);
        });

        btnCraft.setCommand(e -> craftSelected());
        chkAutoSalvage.setCommand(e -> Current.player().setAutoSalvage(chkAutoSalvage.isSelected()));
    }

    @Override
    public void onActivate() {
        if (!Config.ascendant()) {
            FOptionPane.showMessageDialog("Crafting is only available in Shandalar Ascendant.",
                    "Crafting", FOptionPane.INFORMATION_ICON, result -> Forge.back());
            return;
        }
        if (!listenersRegistered) {
            Current.player().onDustChange(this::updateDust);
            Current.player().onMaterialChange(this::onMaterialsChanged);
            listenersRegistered = true;
        }
        reloadPool();
        chkAutoSalvage.setSelected(Current.player().isAutoSalvage());
        updateDust();
        updateDetail();
    }

    private void onMaterialsChanged() {
        reagentAffordCache.clear();
        updateDetail();
        cardManager.refresh();
    }

    private void reloadPool() {
        reagentCostCache.clear();
        reagentAffordCache.clear();
        cardManager.setPool(buildCraftablePool(), true);
    }

    private CardReagentCost cachedReagentCost(PaperCard card) {
        if (card == null)
            return CardReagentCost.forCard(null);
        return reagentCostCache.computeIfAbsent(card, c -> Current.player().craftReagentCost(c));
    }

    private boolean cachedMissingReagents(PaperCard card) {
        if (card == null)
            return true;
        Boolean affordable = reagentAffordCache.get(card);
        if (affordable == null) {
            affordable = cachedReagentCost(card).canAfford(Current.player());
            reagentAffordCache.put(card, affordable);
        }
        return !affordable;
    }

    /** Newest unlocked printing per craftable card name. */
    public static ItemPool<PaperCard> buildCraftablePool() {
        ItemPool<PaperCard> pool = new ItemPool<>(PaperCard.class);
        if (!Config.ascendant())
            return pool;

        AdventurePlayer player = Current.player();
        Set<String> seen = new HashSet<>();

        for (String code : player.getStandardWindow().expandedHistoryCodes()) {
            CardEdition edition = FModel.getMagicDb().getEditions().get(code);
            if (edition == null)
                continue;
            for (PaperCard pc : FModel.getMagicDb().getCommonCards().getAllCards(edition)) {
                if (pc == null || !seen.add(pc.getName()))
                    continue;
                addIfCraftable(pool, player, pc.getName());
            }
        }

        for (String name : StandardWindow.unlockedColorStaples()) {
            if (!seen.add(name))
                continue;
            addIfCraftable(pool, player, name);
        }

        StandardWindow window = player.getStandardWindow();
        if (window.isActive()) {
            for (String name : window.activeStaples(false)) {
                if (!seen.add(name))
                    continue;
                addIfCraftable(pool, player, name);
            }
            if (player.hasCommanderDeck()) {
                for (String name : window.activeStaples(true)) {
                    if (!seen.add(name))
                        continue;
                    addIfCraftable(pool, player, name);
                }
            }
        }
        return pool;
    }

    private static void addIfCraftable(ItemPool<PaperCard> pool, AdventurePlayer player, String name) {
        PaperCard printing = player.craftPrinting(name);
        if (printing != null && player.canCraft(printing))
            pool.add(printing);
    }

    private void craftSelected() {
        PaperCard selected = cardManager.getSelectedItem();
        if (selected == null) {
            FOptionPane.showMessageDialog("Select a card to craft.");
            return;
        }
        AdventurePlayer player = Current.player();
        String problem = player.craftProblem(selected);
        if (problem != null) {
            FOptionPane.showMessageDialog(problem);
            return;
        }
        PaperCard printing = player.craftPrinting(selected);
        int cost = player.craftCost(printing);
        int dustIdx = AdventurePlayer.dustIndex(printing.getRarity());
        if (player.getDust(dustIdx) < cost) {
            FOptionPane.showMessageDialog("Not enough " + AdventurePlayer.dustRarity(dustIdx).getLongName()
                    + " dust (need " + cost + ").");
            return;
        }
        CardReagentCost reagents = cachedReagentCost(printing);
        if (!reagents.canAfford(player)) {
            var missing = reagents.missing(player);
            FOptionPane.showMessageDialog(missing.isEmpty() ? "Missing reagents." : missing.get(0));
            return;
        }
        if (player.craftCard(selected)) {
            reagentAffordCache.clear();
            updateDust();
            updateDetail();
            cardManager.refresh();
        }
    }

    private void updateDust() {
        lblDust.setText("Dust  " + Current.player().dustSummary());
    }

    private void updateDetail() {
        PaperCard selected = cardManager.getSelectedItem();
        if (selected == null) {
            lblDetail.setText("Select a card. Search filters the craftable pool.");
            lblReagents.setText("");
            lblReagents.setTextColor(FSkinColor.get(FSkinColor.Colors.ADV_CLR_TEXT));
            btnCraft.setEnabled(false);
            return;
        }
        AdventurePlayer player = Current.player();
        PaperCard printing = player.craftPrinting(selected);
        if (printing == null) {
            lblDetail.setText(player.craftProblem(selected));
            lblReagents.setText("");
            btnCraft.setEnabled(false);
            return;
        }
        int cost = player.craftCost(printing);
        int owned = countOwnedByName(player, printing.getName());
        CardRarity rarity = printing.getRarity();
        String historic = player.isStandardLegal(printing) ? "Standard cost" : "Historic (2×)";
        lblDetail.setText(printing.getName() + "  ·  " + cost + " " + rarity.getLongName()
                + " dust  ·  owned " + owned + "  ·  " + historic);

        CardReagentCost reagents = cachedReagentCost(printing);
        lblReagents.setText(reagents.detailSummary(player));
        boolean missingReagents = cachedMissingReagents(printing);
        lblReagents.setTextColor(missingReagents ? MISSING_COLOR
                : FSkinColor.get(FSkinColor.Colors.ADV_CLR_TEXT));

        boolean enoughDust = player.getDust(AdventurePlayer.dustIndex(rarity)) >= cost;
        btnCraft.setEnabled(player.canCraft(printing) && enoughDust && !missingReagents);
        btnCraft.setText("Craft (" + cost + " " + shortRarity(rarity) + ")");
    }

    private static int countOwnedByName(AdventurePlayer player, String name) {
        int owned = 0;
        for (Map.Entry<PaperCard, Integer> e : player.getCards()) {
            if (e.getKey().getName().equalsIgnoreCase(name))
                owned += e.getValue();
        }
        return owned;
    }

    private static String shortRarity(CardRarity rarity) {
        return switch (rarity) {
            case Common -> "C";
            case Uncommon -> "U";
            case Rare, Special -> "R";
            case MythicRare -> "M";
            default -> rarity.toString();
        };
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float y = startY + PADDING;
        float dustH = lblDust.getAutoSizeBounds().height;
        lblDust.setBounds(PADDING, y, width - 2 * PADDING, dustH);
        y += dustH + PADDING;

        float btnH = Utils.AVG_FINGER_HEIGHT * 0.8f;
        float craftW = width * 0.28f;
        float detailH = Utils.AVG_FINGER_HEIGHT * 1.15f;
        btnCraft.setBounds(width - PADDING - craftW, y, craftW, btnH);
        lblDetail.setBounds(PADDING, y, width - craftW - 3 * PADDING, btnH * 0.55f);
        lblReagents.setBounds(PADDING, y + btnH * 0.55f, width - craftW - 3 * PADDING, detailH - btnH * 0.55f);
        y += detailH + PADDING;

        float checkH = Utils.AVG_FINGER_HEIGHT * 0.65f;
        chkAutoSalvage.setBounds(PADDING, height - PADDING - checkH, width - 2 * PADDING, checkH);

        float listBottom = height - PADDING - checkH - PADDING;
        cardManager.setBounds(PADDING, y, width - 2 * PADDING, listBottom - y);
    }

    @Override
    protected void drawBackground(Graphics g) {
        g.fillRect(FSkinColor.get(FSkinColor.Colors.ADV_CLR_THEME), 0, 0, getWidth(), getHeight());
    }

    private class CraftCardManager extends CardManager {
        CraftCardManager() {
            super(true);
        }

        @Override
        protected void addDefaultFilters() {
            this.addFilter(new forge.itemmanager.filters.CardColorFilter(this));
            this.addFilter(new forge.itemmanager.filters.CardTypeFilter(this));
        }

        @Override
        protected String getItemSuffix(Map.Entry<PaperCard, Integer> item) {
            PaperCard card = item.getKey();
            AdventurePlayer player = Current.player();
            int cost = player.craftCost(card);
            int owned = countOwnedByName(player, card.getName());
            String historic = player.isStandardLegal(card) ? "" : " ×2";
            CardReagentCost reagents = cachedReagentCost(card);
            String reagentBit = reagents.isEmpty() ? "" : " +" + reagents.shortSummary();
            return " [" + cost + shortRarity(card.getRarity()) + historic + reagentBit + "] ×" + owned;
        }

        @Override
        public ItemManager<PaperCard>.ItemRenderer getListItemRenderer(FList.CompactModeHandler compactModeHandler) {
            return new CardListItemRenderer(compactModeHandler) {
                @Override
                public void drawValue(Graphics g, Map.Entry<PaperCard, Integer> value, FSkinFont font, FSkinColor foreColor,
                                      FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
                    super.drawValue(g, value, font, foreColor, backColor, pressed, x, y, w, h);

                    float totalHeight = h + 2 * FList.PADDING;
                    float cardArtWidth = totalHeight * CardRenderer.CARD_ART_RATIO;
                    PaperCard card = value.getKey();
                    AdventurePlayer player = Current.player();
                    int cost = player.craftCost(card);
                    FSkinImage icon = rarityIcon(card.getRarity());
                    float priceHeight = font.getLineHeight();
                    float drawY = y + totalHeight - priceHeight - FList.PADDING;
                    g.fillRect(backColor, x - FList.PADDING, drawY, cardArtWidth, priceHeight);
                    float iconSize = priceHeight;
                    g.drawImage(icon, x, drawY, iconSize, iconSize);
                    FSkinColor costColor = cachedMissingReagents(card) ? MISSING_COLOR : foreColor;
                    g.drawText(String.valueOf(cost), font, costColor, x + iconSize * 1.1f, drawY,
                            cardArtWidth - iconSize * 1.1f - 2 * FList.PADDING, priceHeight, false, Align.left, true);
                }
            };
        }

        private FSkinImage rarityIcon(CardRarity rarity) {
            return switch (rarity) {
                case Common -> FSkinImage.SET_COMMON;
                case Uncommon -> FSkinImage.SET_UNCOMMON;
                case Rare, Special -> FSkinImage.SET_RARE;
                case MythicRare -> FSkinImage.SET_MYTHIC;
                default -> FSkinImage.SET_COMMON;
            };
        }
    }
}
