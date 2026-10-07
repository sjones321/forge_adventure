package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TextraButton;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Adventure;
import forge.Forge;
import forge.adventure.data.SkillTreeData;
import forge.adventure.data.SkillTreeListData;
import forge.adventure.data.SkillTreeNodeData;
import forge.adventure.player.PlayerSkills;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;

import java.util.List;
import java.util.Map;

/**
 * Ascendant talent tree for one skill: spend points, slot duel perks, respec.
 */
public class SkillTreeScene extends UIScene {
    private final Table scrollContainer;
    private Scene lastGameScene;
    private PlayerSkills.Skill skill = PlayerSkills.Skill.DUELING;
    private boolean slotsMode;

    private SkillTreeScene() {
        super(Forge.isLandscapeMode() ? "ui/skill_tree.json" : "ui/skill_tree_portrait.json");
        Window scrollWindow = ui.findActor("scrollWindow");
        scrollWindow.setTouchable(Touchable.disabled);
        Table root = ui.findActor("treeList");
        ui.onButtonPress("return", this::back);
        ui.onButtonPress("respec", this::confirmRespec);
        ui.onButtonPress("slots", () -> {
            slotsMode = !slotsMode;
            build();
        });
        scrollContainer = new Table(Controls.getSkin());
        ScrollPane scroller = new ScrollPane(scrollContainer);
        scroller.setScrollingDisabled(true, false);
        root.add(scroller).fill().expand();
    }

    private static SkillTreeScene object;

    public static SkillTreeScene instance(Scene lastGameScene, PlayerSkills.Skill skill) {
        if (object == null)
            object = new SkillTreeScene();
        if (lastGameScene != null)
            object.lastGameScene = lastGameScene;
        if (skill != null)
            object.skill = skill;
        object.slotsMode = false;
        return object;
    }

    @Override
    public void dispose() {
    }

    @Override
    public void enter() {
        super.enter();
        if (!Config.ascendant()) {
            Forge.switchScene(SkillsScene.instance(lastGameScene), true);
            return;
        }
        build();
    }

    private void build() {
        scrollContainer.clear();
        PlayerSkills skills = Current.player().getSkills();
        SkillTreeData tree = SkillTreeListData.get(skill);

        header(skill.displayName + " tree");
        note("Level " + skills.getLevel(skill)
                + "  ·  Points " + skills.talentPointsAvailable(skill) + " / " + skills.talentPointsEarned(skill)
                + "  ·  Perk slots " + skills.getSlottedPerks().size() + " / " + skills.perkSlotCount()
                + (skills.hasSkillCape(skill) ? "  ·  Skill cape" : ""));

        if (tree.isEmpty()) {
            note("No talent tree for this skill yet. Points still accrue as you level.");
            performTouch(scrollPaneOfActor(scrollContainer));
            return;
        }

        if (slotsMode) {
            buildSlots(skills, tree);
        } else {
            for (Map.Entry<String, String> branch : tree.branches().entrySet()) {
                header(branch.getValue());
                for (SkillTreeNodeData node : tree.nodesInBranch(branch.getKey()))
                    addNodeRow(skills, node);
            }
        }
        performTouch(scrollPaneOfActor(scrollContainer));
    }

    private void buildSlots(PlayerSkills skills, SkillTreeData tree) {
        note("Duel perks only apply when slotted. Non-duel talents are always on.");
        header("Slotted");
        List<String> slotted = skills.getSlottedPerks();
        if (slotted.isEmpty())
            note("No duel perks slotted.");
        for (String id : slotted) {
            SkillTreeNodeData node = SkillTreeListData.findNode(id);
            if (node == null)
                continue;
            TextraButton unslot = Controls.newTextButton("Unslot");
            unslot.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    skills.unslotPerk(id);
                    build();
                }
            });
            row(node.displayDescription() + " [" + id + "]", unslot);
        }
        header("Owned duel perks");
        boolean any = false;
        for (SkillTreeNodeData node : tree.nodes) {
            if (node == null || !node.duelPerk || skills.getNodeRanks(node.id) < 1)
                continue;
            any = true;
            if (skills.isPerkSlotted(node.id)) {
                note("☑ " + node.displayDescription());
                continue;
            }
            TextraButton slot = Controls.newTextButton("Slot");
            slot.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    String err = skills.canSlotPerk(node.id);
                    if (err != null)
                        showDialog(createGenericDialog("Perk slots", err,
                                Forge.getLocalizer().getMessage("lblOK"), null, SkillTreeScene.this::removeDialog, null));
                    else {
                        skills.slotPerk(node.id);
                        build();
                    }
                }
            });
            row(node.displayDescription(), slot);
        }
        if (!any)
            note("Buy a duel perk (marked Duel) to slot it.");
    }

    private void addNodeRow(PlayerSkills skills, SkillTreeNodeData node) {
        int ranks = skills.getNodeRanks(node.id);
        int max = Math.max(1, node.maxRanks);
        String tags = "";
        if (node.capstone)
            tags += " [Capstone]";
        if (node.skillCape)
            tags += " [Cape]";
        if (node.duelPerk)
            tags += " [Duel]";
        if (node.exclusiveWith != null && node.exclusiveWith.length > 0)
            tags += " [Choice]";
        String status;
        if (ranks >= max)
            status = "[FOREST]Owned" + (max > 1 ? " " + ranks + "/" + max : "");
        else if (ranks > 0)
            status = "[FOREST]" + ranks + "/" + max;
        else if (skills.getLevel(skill) < node.levelRequired)
            status = "[DARK_GRAY]Lv " + node.levelRequired;
        else
            status = "[DARK_GRAY]" + Math.max(1, node.cost) + " pt";

        TypingLabel label = label("[BLACK]" + node.displayDescription() + "[DARK_GRAY][%80]" + tags);
        label.setWrap(true);
        scrollContainer.add(label).align(Align.left).growX().padLeft(10).padRight(4);
        scrollContainer.add(label(status)).align(Align.right).padRight(4);
        if (ranks < max) {
            TextraButton buy = Controls.newTextButton("Buy");
            buy.addListener(new ClickListener() {
                @Override
                public void clicked(InputEvent event, float x, float y) {
                    String err = skills.canBuyRank(skill, node.id);
                    if (err != null) {
                        showDialog(createGenericDialog("Talent", err,
                                Forge.getLocalizer().getMessage("lblOK"), null, SkillTreeScene.this::removeDialog, null));
                    } else {
                        skills.buyRank(skill, node.id);
                        build();
                    }
                }
            });
            scrollContainer.add(buy).padRight(8);
            addToSelectable(buy);
        } else {
            scrollContainer.add(label("")).padRight(8);
        }
        scrollContainer.row().padTop(2);
    }

    private void confirmRespec() {
        PlayerSkills skills = Current.player().getSkills();
        int cost = skills.respecCostGold();
        String msg = cost == 0
                ? "Reset all talents in " + skill.displayName + " for free?"
                : "Reset all talents in " + skill.displayName + " for " + cost + " gold?";
        showDialog(createGenericDialog("Respec", msg,
                Forge.getLocalizer().getMessage("lblOK"),
                Forge.getLocalizer().getMessage("lblCancel"),
                () -> {
                    removeDialog();
                    String err = skills.respec(skill);
                    if (err != null)
                        showDialog(createGenericDialog("Respec", err,
                                Forge.getLocalizer().getMessage("lblOK"), null, this::removeDialog, null));
                    else
                        build();
                },
                this::removeDialog));
    }

    private void row(String text, TextraButton button) {
        TypingLabel label = label("[BLACK]" + text);
        label.setWrap(true);
        scrollContainer.add(label).align(Align.left).growX().padLeft(10);
        scrollContainer.add(button).padRight(8);
        scrollContainer.row().padTop(2);
        addToSelectable(button);
    }

    private void header(String text) {
        TypingLabel l = label("[BLACK][%110]" + text);
        scrollContainer.add(l).align(Align.left).padLeft(8).padTop(8).colspan(3);
        scrollContainer.row();
    }

    private void note(String text) {
        TypingLabel l = label("[DARK_GRAY][%85]" + text);
        l.setWrap(true);
        scrollContainer.add(l).align(Align.left).growX().padLeft(8).padRight(8).colspan(3);
        scrollContainer.row();
    }

    private static TypingLabel label(String text) {
        TypingLabel label = Controls.newTypingLabel(text);
        label.skipToTheEnd();
        return label;
    }

    @Override
    public boolean back() {
        Adventure.getInstance().renderTransitionScreen = true;
        Forge.switchScene(SkillsScene.instance(lastGameScene), true);
        return true;
    }
}
