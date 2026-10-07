package forge.adventure.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.adventure.data.MaterialData;
import forge.adventure.data.MaterialListData;
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
 * Ascendant Spellsmithing UI: refine owned materials into dust.
 * Yield scales with Spellsmithing level ({@code refineDustBase} → {@code refineDustMax}).
 */
public class RefineScreen extends FScreen {
    private static final float PADDING = Utils.scale(5f);
    private static final FSkinColor SEL_COLOR = FSkinColor.get(FSkinColor.Colors.ADV_CLR_ACTIVE);

    private final FLabel lblDust = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FLabel lblDetail = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FButton btnRefineOne = add(new FButton("Refine 1"));
    private final FButton btnRefineAll = add(new FButton("Refine All"));
    private final MaterialLister list = add(new MaterialLister());

    public RefineScreen() {
        super("Refine to Dust");
        btnRefineOne.setCommand(e -> refineSelected(1));
        btnRefineAll.setCommand(e -> refineSelected(Integer.MAX_VALUE));
    }

    @Override
    public void onActivate() {
        if (!Config.ascendant()) {
            FOptionPane.showMessageDialog("Refining is only available in Shandalar Ascendant.",
                    "Refine", FOptionPane.INFORMATION_ICON, result -> Forge.back());
            return;
        }
        reload();
        Current.player().onDustChange(this::updateDust);
        Current.player().onMaterialChange(this::reload);
    }

    private void reload() {
        list.setRows(buildRows());
        updateDust();
        updateDetail();
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        AdventurePlayer player = Current.player();
        for (Map.Entry<String, Integer> e : player.getMaterials().entrySet()) {
            if (e.getValue() == null || e.getValue() <= 0)
                continue;
            MaterialData mat = MaterialListData.get(e.getKey());
            if (mat == null || mat.dustRefine == null || mat.dustRefine.amount <= 0)
                continue;
            if (AdventurePlayer.materialDustIndex(mat.dustRefine.rarity) < 0)
                continue;
            rows.add(new Row(mat, e.getValue(), player.refineDustYield(mat.id)));
        }
        rows.sort(Comparator.comparing((Row r) -> r.mat.family == null ? "" : r.mat.family)
                .thenComparingInt(r -> r.mat.tier)
                .thenComparing(r -> r.mat.getDisplayName()));
        return rows;
    }

    private void refineSelected(int amount) {
        Row row = list.getSelectedRow();
        if (row == null) {
            FOptionPane.showMessageDialog("Select a material to refine.");
            return;
        }
        int granted = Current.player().refineMaterial(row.mat.id, amount);
        if (granted <= 0) {
            FOptionPane.showMessageDialog("Cannot refine that material.");
            return;
        }
        reload();
    }

    private void updateDust() {
        lblDust.setText("Dust  " + Current.player().dustSummary()
                + "   ·   Spellsmithing "
                + Current.player().getSkills().getLevel(PlayerSkills.Skill.SPELLSMITHING));
    }

    private void updateDetail() {
        Row row = list.getSelectedRow();
        if (row == null) {
            lblDetail.setText("Select a material. Spellsmithing raises dust yield.");
            btnRefineOne.setEnabled(false);
            btnRefineAll.setEnabled(false);
            return;
        }
        int owned = Current.player().getMaterial(row.mat.id);
        int yield = Current.player().refineDustYield(row.mat.id);
        String rarity = row.mat.dustRefine.rarity;
        lblDetail.setText(row.mat.getDisplayName()
                + "  ·  owned " + owned
                + "  ·  " + yield + " " + rarity + " dust each");
        boolean ok = owned > 0 && yield > 0;
        btnRefineOne.setEnabled(ok);
        btnRefineAll.setEnabled(ok);
        btnRefineOne.setText("Refine 1 (" + yield + ")");
        btnRefineAll.setText("Refine All (" + (yield * Math.max(0, owned)) + ")");
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float y = startY + PADDING;
        float dustH = lblDust.getAutoSizeBounds().height;
        lblDust.setBounds(PADDING, y, width - 2 * PADDING, dustH);
        y += dustH + PADDING;

        float btnH = Utils.AVG_FINGER_HEIGHT * 0.8f;
        float btnW = width * 0.22f;
        btnRefineAll.setBounds(width - PADDING - btnW, y, btnW, btnH);
        btnRefineOne.setBounds(width - 2 * PADDING - 2 * btnW, y, btnW, btnH);
        lblDetail.setBounds(PADDING, y, width - 2 * btnW - 4 * PADDING, btnH);
        y += btnH + PADDING;

        list.setBounds(PADDING, y, width - 2 * PADDING, height - y - PADDING);
    }

    @Override
    protected void drawBackground(Graphics g) {
        g.fillRect(FSkinColor.get(FSkinColor.Colors.ADV_CLR_THEME), 0, 0, getWidth(), getHeight());
    }

    private static final class Row {
        final MaterialData mat;
        final int owned;
        final int yieldEach;

        Row(MaterialData mat, int owned, int yieldEach) {
            this.mat = mat;
            this.owned = owned;
            this.yieldEach = yieldEach;
        }
    }

    private final class MaterialLister extends FList<Row> {
        private int selectedIndex = 0;

        MaterialLister() {
            setListItemRenderer(new ListItemRenderer<>() {
                @Override
                public float getItemHeight() {
                    return Utils.AVG_FINGER_HEIGHT * 0.75f;
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
                    String text = value.mat.getDisplayName()
                            + "  ×" + value.owned
                            + "  →  " + value.yieldEach + " " + value.mat.dustRefine.rarity;
                    g.drawText(text, font, foreColor, x + Utils.scale(4), y, w - Utils.scale(8), h, false, Align.left, true);
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
            String keepId = getSelectedRow() != null ? getSelectedRow().mat.id : null;
            setListData(rows);
            selectedIndex = 0;
            if (keepId != null) {
                for (int i = 0; i < getCount(); i++) {
                    if (keepId.equals(getItemAt(i).mat.id)) {
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
