package forge.adventure.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.badlogic.gdx.utils.Align;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;

import forge.Forge;
import forge.Graphics;
import forge.adventure.data.MaterialData;
import forge.adventure.data.MaterialListData;
import forge.adventure.data.RecipeData;
import forge.adventure.data.RecipeListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerSkills;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.util.Utils;

/**
 * Ascendant station crafting UI shared by Forge, Workshop, Apothecary, and Jeweler.
 * Recipe list is filtered by station; missing materials show in red; under-level recipes
 * stay visible but disabled.
 */
public class RecipeScreen extends FScreen {
    private static final float PADDING = Utils.scale(5f);
    private static final FSkinColor SEL_COLOR = FSkinColor.get(FSkinColor.Colors.ADV_CLR_ACTIVE);
    private static final FSkinColor MISSING_COLOR = FSkinColor.getStandardColor(200, 60, 60);

    private String station;
    private final FLabel lblHeader = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FLabel lblDetail = add(new FLabel.Builder().text("").font(FSkinFont.get(12)).align(Align.left).build());
    private final FButton btnCraft = add(new FButton("Craft"));
    private final RecipeLister list = add(new RecipeLister());

    public RecipeScreen(String station) {
        super(stationTitle(station));
        this.station = station != null ? station.toLowerCase(Locale.ROOT) : "forge";
        btnCraft.setCommand(e -> craftSelected());
    }

    public void setStation(String station) {
        this.station = station != null ? station.toLowerCase(Locale.ROOT) : "forge";
        setHeaderCaption(stationTitle(this.station));
        reload();
    }

    @Override
    public void onActivate() {
        if (!Config.ascendant()) {
            FOptionPane.showMessageDialog("Crafting stations are only available in Shandalar Ascendant.",
                    "Craft", FOptionPane.INFORMATION_ICON, result -> Forge.back());
            return;
        }
        reload();
        Current.player().onGoldChange(this::refreshSelection);
        Current.player().onMaterialChange(this::reload);
    }

    private void reload() {
        list.setRows(buildRows());
        refreshSelection();
    }

    private void refreshSelection() {
        updateHeader();
        updateDetail();
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        Array<RecipeData> recipes = RecipeListData.forStation(station);
        AdventurePlayer player = Current.player();
        for (RecipeData recipe : new Array.ArrayIterator<>(recipes)) {
            if (recipe == null)
                continue;
            rows.add(new Row(recipe, player.canCraftRecipe(recipe), player.craftRecipeBlockers(recipe)));
        }
        rows.sort(Comparator
                .comparingInt((Row r) -> r.recipe.levelRequired)
                .thenComparing(r -> r.recipe.getDisplayResult(), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private void craftSelected() {
        Row row = list.getSelectedRow();
        if (row == null) {
            FOptionPane.showMessageDialog("Select a recipe to craft.");
            return;
        }
        if (!row.canCraft) {
            FOptionPane.showMessageDialog(String.join("\n", row.blockers), "Cannot craft");
            return;
        }
        if (!Current.player().craftRecipe(row.recipe)) {
            FOptionPane.showMessageDialog("Crafting failed.");
            return;
        }
        String msg = row.recipe.isPotion()
                ? "Brewed " + row.recipe.getDisplayResult() + " (next duel blessing)."
                : "Crafted " + row.recipe.getDisplayResult() + ".";
        FOptionPane.showMessageDialog(msg, stationTitle(station));
        reload();
    }

    private void updateHeader() {
        AdventurePlayer player = Current.player();
        PlayerSkills.Skill skill = skillForStation(station);
        String skillBit = skill != null
                ? skill.displayName + " " + player.getSkills().getLevel(skill)
                : "";
        lblHeader.setText(stationTitle(station)
                + "   ·   Gold " + player.getGold()
                + (skillBit.isEmpty() ? "" : "   ·   " + skillBit));
    }

    private void updateDetail() {
        Row row = list.getSelectedRow();
        if (row == null) {
            lblDetail.setText("Select a recipe. Recipes above your level stay visible but cannot be crafted.");
            btnCraft.setEnabled(false);
            return;
        }
        AdventurePlayer player = Current.player();
        int goldCost = player.recipeGoldCost(row.recipe);
        StringBuilder sb = new StringBuilder();
        sb.append(row.recipe.getDisplayResult());
        if (row.recipe.isPotion())
            sb.append(" (potion)");
        else if (row.recipe.isTool())
            sb.append(" (tool)");
        sb.append("  ·  ").append(row.recipe.skill).append(" ").append(row.recipe.levelRequired);
        sb.append("  ·  ").append(goldCost).append(" gold");
        sb.append("  ·  +").append(row.recipe.xp).append(" XP");
        sb.append("\n");
        sb.append(formatMaterials(row.recipe, player));
        if (!row.canCraft && !row.blockers.isEmpty())
            sb.append("\n").append(row.blockers.get(0));
        lblDetail.setText(sb.toString());
        btnCraft.setEnabled(row.canCraft);
        btnCraft.setText(row.canCraft ? "Craft" : "Locked");
    }

    private static String formatMaterials(RecipeData recipe, AdventurePlayer player) {
        StringBuilder sb = new StringBuilder("Mats: ");
        boolean first = true;
        for (ObjectMap.Entry<String, Integer> e : recipe.getMaterials()) {
            if (e.key == null || e.value == null || e.value <= 0)
                continue;
            if (!first)
                sb.append(", ");
            first = false;
            MaterialData mat = MaterialListData.get(e.key);
            String label = mat != null ? mat.getDisplayName() : e.key;
            int have = player.getMaterial(e.key);
            sb.append(label).append(" ").append(have).append("/").append(e.value);
        }
        if (first)
            sb.append("none");
        return sb.toString();
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float y = startY + PADDING;
        float headerH = lblHeader.getAutoSizeBounds().height;
        lblHeader.setBounds(PADDING, y, width - 2 * PADDING, headerH);
        y += headerH + PADDING;

        float btnH = Utils.AVG_FINGER_HEIGHT * 0.8f;
        float btnW = width * 0.22f;
        float detailH = Utils.AVG_FINGER_HEIGHT * 1.35f;
        btnCraft.setBounds(width - PADDING - btnW, y, btnW, btnH);
        lblDetail.setBounds(PADDING, y, width - btnW - 3 * PADDING, detailH);
        y += detailH + PADDING;

        list.setBounds(PADDING, y, width - 2 * PADDING, height - y - PADDING);
    }

    @Override
    protected void drawBackground(Graphics g) {
        g.fillRect(FSkinColor.get(FSkinColor.Colors.ADV_CLR_THEME), 0, 0, getWidth(), getHeight());
    }

    static String stationTitle(String station) {
        if (station == null)
            return "Craft";
        switch (station.toLowerCase(Locale.ROOT)) {
            case "forge":
                return "Forge";
            case "workshop":
                return "Workshop";
            case "apothecary":
                return "Apothecary";
            case "jeweler":
                return "Jeweler";
            default:
                return "Craft";
        }
    }

    static PlayerSkills.Skill skillForStation(String station) {
        if (station == null)
            return null;
        switch (station.toLowerCase(Locale.ROOT)) {
            case "forge":
                return PlayerSkills.Skill.SMITHING;
            case "workshop":
                return PlayerSkills.Skill.WOODWORKING;
            case "apothecary":
                return PlayerSkills.Skill.ALCHEMY;
            case "jeweler":
                return PlayerSkills.Skill.JEWELCRAFTING;
            default:
                return null;
        }
    }

    private static final class Row {
        final RecipeData recipe;
        final boolean canCraft;
        final List<String> blockers;

        Row(RecipeData recipe, boolean canCraft, List<String> blockers) {
            this.recipe = recipe;
            this.canCraft = canCraft;
            this.blockers = blockers;
        }
    }

    private final class RecipeLister extends FList<Row> {
        private int selectedIndex = 0;

        RecipeLister() {
            setListItemRenderer(new ListItemRenderer<>() {
                @Override
                public float getItemHeight() {
                    return Utils.AVG_FINGER_HEIGHT * 0.85f;
                }

                @Override
                public boolean tap(Integer index, Row value, float x, float y, int count) {
                    selectedIndex = index;
                    updateDetail();
                    return true;
                }

                @Override
                public void drawValue(Graphics g, Integer index, Row value, FSkinFont font, FSkinColor foreColor,
                                     FSkinColor backColor, boolean pressed, float x, float y, float w, float h) {
                    AdventurePlayer player = Current.player();
                    String title = value.recipe.getDisplayResult()
                            + "  ·  " + value.recipe.skill + " " + value.recipe.levelRequired
                            + "  ·  " + player.recipeGoldCost(value.recipe) + "g";
                    FSkinColor titleColor = value.canCraft ? foreColor : FSkinColor.get(FSkinColor.Colors.ADV_CLR_TEXT).alphaColor(0.55f);
                    g.drawText(title, font, titleColor, x + Utils.scale(4), y, w - Utils.scale(8), h * 0.55f,
                            false, Align.left, true);

                    String mats = formatMaterialsLine(value.recipe, player);
                    boolean anyMissing = hasMissingMaterial(value.recipe, player);
                    FSkinColor matColor = anyMissing ? MISSING_COLOR
                            : FSkinColor.get(FSkinColor.Colors.ADV_CLR_TEXT).alphaColor(0.75f);
                    g.drawText(mats, FSkinFont.get(11), matColor, x + Utils.scale(4), y + h * 0.5f,
                            w - Utils.scale(8), h * 0.45f, false, Align.left, true);
                }
            });
        }

        private String formatMaterialsLine(RecipeData recipe, AdventurePlayer player) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (ObjectMap.Entry<String, Integer> e : recipe.getMaterials()) {
                if (e.key == null || e.value == null || e.value <= 0)
                    continue;
                if (!first)
                    sb.append("  ");
                first = false;
                MaterialData mat = MaterialListData.get(e.key);
                String label = mat != null ? mat.getDisplayName() : e.key;
                int have = player.getMaterial(e.key);
                sb.append(label).append(" ").append(have).append("/").append(e.value);
            }
            return first ? "No materials" : sb.toString();
        }

        private boolean hasMissingMaterial(RecipeData recipe, AdventurePlayer player) {
            for (ObjectMap.Entry<String, Integer> e : recipe.getMaterials()) {
                if (e.key == null || e.value == null || e.value <= 0)
                    continue;
                if (player.getMaterial(e.key) < e.value)
                    return true;
            }
            return false;
        }

        @Override
        protected FSkinColor getItemFillColor(int index) {
            if (index == selectedIndex)
                return SEL_COLOR;
            return null;
        }

        void setRows(List<Row> rows) {
            String keepId = getSelectedRow() != null ? getSelectedRow().recipe.id : null;
            setListData(rows);
            selectedIndex = 0;
            if (keepId != null) {
                for (int i = 0; i < getCount(); i++) {
                    if (keepId.equals(getItemAt(i).recipe.id)) {
                        selectedIndex = i;
                        break;
                    }
                }
            }
            if (getCount() == 0)
                selectedIndex = -1;
        }

        Row getSelectedRow() {
            return getItemAt(selectedIndex);
        }
    }
}
