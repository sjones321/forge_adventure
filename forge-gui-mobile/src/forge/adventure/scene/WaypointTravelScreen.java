package forge.adventure.scene;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Align;

import forge.Forge;
import forge.Graphics;
import forge.adventure.data.ConfigData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerSkills;
import forge.adventure.pointofintrest.PointOfInterest;
import forge.adventure.stage.MapStage;
import forge.adventure.stage.WorldStage;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.Paths;
import forge.adventure.world.WorldSave;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.gui.FThreads;
import forge.screens.CoverScreen;
import forge.screens.FScreen;
import forge.toolbox.FButton;
import forge.toolbox.FLabel;
import forge.toolbox.FList;
import forge.toolbox.FOptionPane;
import forge.util.ScreenUtil;
import forge.util.Utils;

/**
 * Ascendant town-board travel: pay gold to teleport between visited towns/capitals.
 * Unlocked by Exploration ({@link ConfigData#waypointTravelUnlockLevel}).
 */
public class WaypointTravelScreen extends FScreen {
    private static final float PADDING = Utils.scale(5f);
    private static final FSkinColor SEL_COLOR = FSkinColor.get(FSkinColor.Colors.ADV_CLR_ACTIVE);

    private final FLabel lblHeader = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FLabel lblDetail = add(new FLabel.Builder().text("").font(FSkinFont.get(14)).align(Align.left).build());
    private final FButton btnTravel = add(new FButton("Travel"));
    private final TownLister list = add(new TownLister());

    private String currentTownId;
    private PointOfInterest currentTown;

    public WaypointTravelScreen() {
        super("Town Board — Travel");
        btnTravel.setCommand(e -> travelSelected());
    }

    public void prepare(String currentTownId) {
        this.currentTownId = currentTownId;
        this.currentTown = findCurrentTown();
    }

    @Override
    public void onActivate() {
        if (!Config.ascendant()) {
            FOptionPane.showMessageDialog("Waypoint travel is only available in Shandalar Ascendant.",
                    "Travel", FOptionPane.INFORMATION_ICON, result -> Forge.back());
            return;
        }
        AdventurePlayer player = Current.player();
        if (!player.getSkills().canUseWaypointTravel()) {
            int need = Config.instance().getConfigData().waypointTravelUnlockLevel;
            FOptionPane.showMessageDialog(
                    "Reach Exploration level " + need + " to unlock waypoint travel between visited towns.",
                    "Travel locked", FOptionPane.INFORMATION_ICON, result -> Forge.back());
            return;
        }
        if (player.isOverloaded()) {
            FOptionPane.showMessageDialog(
                    "You are overloaded. Clear the Overflow stash before waypoint travel.",
                    "Overloaded", FOptionPane.INFORMATION_ICON, result -> Forge.back());
            return;
        }
        reload();
    }

    private void reload() {
        list.setRows(buildRows());
        int exploration = Current.player().getSkills().getLevel(PlayerSkills.Skill.EXPLORATION);
        lblHeader.setText("Exploration " + exploration + "  ·  Gold " + Current.player().getGold()
                + "  ·  Visited towns only");
        updateDetail();
    }

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();
        PointOfInterest from = currentTown;
        Vector2 fromPos = from != null ? from.getPosition() : WorldStage.getInstance().getPlayerSprite().pos();
        float tileSize = WorldSave.getCurrentSave().getWorld().getTileSize();
        PlayerSkills skills = Current.player().getSkills();

        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (poi == null || poi.getData() == null)
                continue;
            String type = poi.getData().type;
            if (type == null || !(type.equals("town") || type.equals("capital")))
                continue;
            if (currentTownId != null && currentTownId.equals(poi.getID()))
                continue;
            if (!WorldSave.getCurrentSave().getPointOfInterestChanges(poi.getID()).isVisited())
                continue;
            float distTiles = fromPos.dst(poi.getPosition()) / tileSize;
            int cost = skills.waypointTravelCost(distTiles);
            rows.add(new Row(poi, distTiles, cost));
        }
        rows.sort(Comparator.comparing((Row r) -> r.poi.getDisplayName(), String.CASE_INSENSITIVE_ORDER));
        return rows;
    }

    private PointOfInterest findCurrentTown() {
        if (currentTownId == null)
            return null;
        for (PointOfInterest poi : WorldSave.getCurrentSave().getWorld().getAllPointOfInterest()) {
            if (poi != null && currentTownId.equals(poi.getID()))
                return poi;
        }
        return null;
    }

    private void updateDetail() {
        Row row = list.getSelectedRow();
        if (row == null) {
            lblDetail.setText(list.getCount() == 0
                    ? "Visit more towns to unlock destinations."
                    : "Select a visited town to travel there.");
            btnTravel.setEnabled(false);
            return;
        }
        boolean canPay = Current.player().getGold() >= row.cost;
        lblDetail.setText(row.poi.getDisplayName()
                + "  ·  " + Math.round(row.distanceTiles) + " tiles"
                + "  ·  " + row.cost + " gold"
                + (canPay ? "" : "  (not enough gold)"));
        btnTravel.setEnabled(canPay);
    }

    private void travelSelected() {
        Row row = list.getSelectedRow();
        if (row == null)
            return;
        AdventurePlayer player = Current.player();
        if (player.getGold() < row.cost) {
            FOptionPane.showMessageDialog("Not enough gold.");
            return;
        }
        final PointOfInterest dest = row.poi;
        final int cost = row.cost;
        FOptionPane.showConfirmDialog(
                "Travel to " + dest.getDisplayName() + " for " + cost + " gold?",
                "Confirm travel",
                "Travel", "Cancel",
                result -> {
                    if (!Boolean.TRUE.equals(result))
                        return;
                    if (player.getGold() < cost) {
                        FOptionPane.showMessageDialog("Not enough gold.");
                        return;
                    }
                    // Charge only after the trip succeeds — see performTravel.
                    performTravel(dest, cost);
                });
    }

    private void performTravel(PointOfInterest dest, int cost) {
        Forge.advFreezePlayerControls = true;
        FThreads.invokeInEdtNowOrLater(() -> Forge.setTransitionScreen(new CoverScreen(() -> {
            boolean success = false;
            try {
                if (MapStage.getInstance().isInMap()) {
                    MapStage.getInstance().exitDungeon(false, false);
                }
                WorldStage.getInstance().setPosition(
                        new Vector2(dest.getPosition().x - 16f, dest.getPosition().y + 16f));
                WorldStage.getInstance().getPlayerSprite().playEffect(Paths.EFFECT_TELEPORT, 10);
                success = WorldStage.getInstance().loadPOI(dest);
            } catch (Exception e) {
                System.err.println("Waypoint travel failed: " + e.getMessage());
                e.printStackTrace();
                success = false;
            } finally {
                if (success && cost > 0 && Current.player().getGold() >= cost) {
                    Current.player().takeGold(cost);
                } else if (!success) {
                    FOptionPane.showMessageDialog("Travel failed. No gold was charged.");
                }
                Forge.advFreezePlayerControls = false;
                Forge.clearTransitionScreen();
            }
        }, ScreenUtil.getInstance().takeScreenshot())));
    }

    @Override
    protected void doLayout(float startY, float width, float height) {
        float y = startY + PADDING;
        float dustH = lblHeader.getAutoSizeBounds().height;
        lblHeader.setBounds(PADDING, y, width - 2 * PADDING, dustH);
        y += dustH + PADDING;

        float btnH = Utils.AVG_FINGER_HEIGHT * 0.8f;
        float btnW = width * 0.22f;
        btnTravel.setBounds(width - PADDING - btnW, y, btnW, btnH);
        lblDetail.setBounds(PADDING, y, width - btnW - 3 * PADDING, btnH);
        y += btnH + PADDING;

        list.setBounds(PADDING, y, width - 2 * PADDING, height - y - PADDING);
    }

    @Override
    protected void drawBackground(Graphics g) {
        g.fillRect(FSkinColor.get(FSkinColor.Colors.ADV_CLR_THEME), 0, 0, getWidth(), getHeight());
    }

    private static final class Row {
        final PointOfInterest poi;
        final float distanceTiles;
        final int cost;

        Row(PointOfInterest poi, float distanceTiles, int cost) {
            this.poi = poi;
            this.distanceTiles = distanceTiles;
            this.cost = cost;
        }
    }

    private final class TownLister extends FList<Row> {
        private int selectedIndex = 0;

        TownLister() {
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
                    String text = value.poi.getDisplayName()
                            + "  ·  " + Math.round(value.distanceTiles) + " tiles"
                            + "  ·  " + value.cost + "g";
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
            setListData(rows);
            selectedIndex = rows.isEmpty() ? -1 : 0;
        }

        Row getSelectedRow() {
            return getItemAt(selectedIndex);
        }
    }
}
