package forge.adventure.scene;

import java.util.ArrayList;
import java.util.List;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.adventure.data.MaterialData;
import forge.adventure.data.MaterialListData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerSkills;
import forge.adventure.util.CardReagentCost;
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
 * Ascendant Spell Smith UI: craft Prismatic reagents from one reagent of each color (same tier).
 */
public class PrismaticScreen extends FScreen {
    private static final float PADDING = Utils.scale(5f);
    private static final FSkinColor SEL_COLOR = FSkinColor.get(FSkinColor.Colors.ADV_CLR_ACTIVE);
    private static final FSkinColor MISSING_COLOR = FSkinColor.getStandardColor(200, 60, 60);

    private final FLabel lblHeader = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FLabel lblDetail = add(new FLabel.Builder().text("").font(FSkinFont.get(12)).align(Align.left).build());
    private final FButton btnCraft = add(new FButton("Craft"));
    private final TierLister list = add(new TierLister());
    private boolean listenersRegistered;

    public PrismaticScreen() {
        super("Prismatic Reagents");
        btnCraft.setCommand(e -> craftSelected());
    }

    @Override
    public void onActivate() {
        if (!Config.ascendant()) {
            FOptionPane.showMessageDialog("Prismatic crafting is only available in Shandalar Ascendant.",
                    "Prismatic", FOptionPane.INFORMATION_ICON, result -> Forge.back());
            return;
        }
        if (!listenersRegistered) {
            Current.player().onMaterialChange(this::reload);
            listenersRegistered = true;
        }
        reload();
    }

    private void reload() {
        list.setRows(buildRows());
        updateHeader();
        updateDetail();
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        AdventurePlayer player = Current.player();
        for (int tier = 1; tier <= 4; tier++) {
            MaterialData result = MaterialListData.prismaticForTier(tier);
            if (result == null)
                continue;
            String problem = player.prismaticCraftProblem(tier);
            rows.add(new Row(tier, result, problem == null, problem));
        }
        return rows;
    }

    private void craftSelected() {
        Row row = list.getSelectedRow();
        if (row == null) {
            FOptionPane.showMessageDialog("Select a Prismatic tier.");
            return;
        }
        if (!row.canCraft) {
            FOptionPane.showMessageDialog(row.blocker != null ? row.blocker : "Cannot craft that tier.");
            return;
        }
        if (Current.player().craftPrismatic(row.tier)) {
            FOptionPane.showMessageDialog("Crafted 1× " + row.result.getDisplayName() + ".");
            reload();
        }
    }

    private void updateHeader() {
        AdventurePlayer player = Current.player();
        lblHeader.setText("Spellsmithing "
                + player.getSkills().getLevel(PlayerSkills.Skill.SPELLSMITHING)
                + "  ·  1 of each color (same tier) → 1 Prismatic");
    }

    private void updateDetail() {
        Row row = list.getSelectedRow();
        if (row == null) {
            lblDetail.setText("Select a tier. Drop alts (feathers, hide, brine, …) count for their color.");
            btnCraft.setEnabled(false);
            return;
        }
        lblDetail.setText(formatInputs(row.tier) + (row.canCraft ? "" : "\n" + row.blocker));
        btnCraft.setEnabled(row.canCraft);
        btnCraft.setText(row.canCraft ? "Craft" : "Locked");
    }

    private static String formatInputs(int tier) {
        AdventurePlayer player = Current.player();
        StringBuilder sb = new StringBuilder("Needs: ");
        String[] colors = {"W", "U", "B", "R", "G"};
        for (int i = 0; i < colors.length; i++) {
            if (i > 0)
                sb.append(", ");
            MaterialData primary = MaterialListData.primaryReagent(colors[i], tier);
            String label = primary != null ? primary.getDisplayName() : colors[i];
            int have = CardReagentCost.ownedForColor(player, colors[i], tier);
            sb.append(label).append(" ").append(have).append("/1");
        }
        MaterialData result = MaterialListData.prismaticForTier(tier);
        sb.append("  →  ").append(result != null ? result.getDisplayName() : ("T" + tier));
        sb.append(" (owned ").append(result != null ? player.getMaterial(result.id) : 0).append(")");
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

    private static final class Row {
        final int tier;
        final MaterialData result;
        final boolean canCraft;
        final String blocker;

        Row(int tier, MaterialData result, boolean canCraft, String blocker) {
            this.tier = tier;
            this.result = result;
            this.canCraft = canCraft;
            this.blocker = blocker;
        }
    }

    private final class TierLister extends FList<Row> {
        private int selectedIndex = 0;

        TierLister() {
            setListItemRenderer(new ListItemRenderer<>() {
                @Override
                public float getItemHeight() {
                    return Utils.AVG_FINGER_HEIGHT * 0.9f;
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
                    String title = value.result.getDisplayName() + "  ·  Tier " + value.tier;
                    FSkinColor titleColor = value.canCraft ? foreColor
                            : FSkinColor.get(FSkinColor.Colors.ADV_CLR_TEXT).alphaColor(0.55f);
                    g.drawText(title, font, titleColor, x + Utils.scale(4), y, w - Utils.scale(8), h * 0.55f,
                            false, Align.left, true);

                    AdventurePlayer player = Current.player();
                    StringBuilder mats = new StringBuilder();
                    boolean missing = false;
                    String[] colors = {"W", "U", "B", "R", "G"};
                    for (int i = 0; i < colors.length; i++) {
                        if (i > 0)
                            mats.append("  ");
                        MaterialData primary = MaterialListData.primaryReagent(colors[i], value.tier);
                        String label = primary != null ? primary.getDisplayName() : colors[i];
                        int have = CardReagentCost.ownedForColor(player, colors[i], value.tier);
                        if (have < 1)
                            missing = true;
                        mats.append(label).append(" ").append(have).append("/1");
                    }
                    FSkinColor matColor = missing ? MISSING_COLOR
                            : FSkinColor.get(FSkinColor.Colors.ADV_CLR_TEXT).alphaColor(0.75f);
                    g.drawText(mats.toString(), FSkinFont.get(11), matColor, x + Utils.scale(4), y + h * 0.5f,
                            w - Utils.scale(8), h * 0.45f, false, Align.left, true);
                }
            });
        }

        @Override
        protected FSkinColor getItemFillColor(int index) {
            if (index == selectedIndex)
                return SEL_COLOR;
            return null;
        }

        void setRows(List<Row> rows) {
            int keepTier = getSelectedRow() != null ? getSelectedRow().tier : -1;
            setListData(rows);
            selectedIndex = 0;
            if (keepTier > 0) {
                for (int i = 0; i < getCount(); i++) {
                    if (getItemAt(i).tier == keepTier) {
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
