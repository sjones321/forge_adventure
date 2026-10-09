package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import forge.adventure.data.MasterySurgeData;
import forge.adventure.data.RewardData;
import forge.adventure.data.SkillTreeListData;
import forge.card.CardEdition;
import java.util.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Adventure;
import forge.Forge;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.PlayerSkills;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;

/**
 * Lists every skill with its level, XP and progress to the next level.
 * Reached from the Status and Quests screens. Ascendant skills open a talent tree.
 */
public class SkillsScene extends UIScene {
    private final Table scrollContainer;
    private Scene lastGameScene;

    private SkillsScene() {
        super(Forge.isLandscapeMode() ? "ui/skills.json" : "ui/skills_portrait.json");
        Window scrollWindow = ui.findActor("scrollWindow");
        scrollWindow.setTouchable(Touchable.disabled);
        Table root = ui.findActor("skillList");
        ui.onButtonPress("return", SkillsScene.this::back);
        ui.onButtonPress("status", () -> Forge.switchScene(PlayerStatisticScene.instance(lastGameScene), true));
        ui.onButtonPress("quests", () -> Forge.switchScene(QuestLogScene.instance(lastGameScene), true));
        ui.onButtonPress("unlocks", () -> Forge.switchScene(UnlocksScene.instance(lastGameScene), true));
        ui.onButtonPress("achievements", () -> Forge.switchScene(AchievementsScene.instance(lastGameScene), true));
        if (ui.findActor("achievements") != null)
            ui.findActor("achievements").setVisible(Config.ascendant());

        scrollContainer = new Table(Controls.getSkin());
        ScrollPane scroller = new ScrollPane(scrollContainer);
        scroller.setScrollingDisabled(true, false);
        root.add(scroller).fill().expand();
    }

    private static SkillsScene object;

    public static SkillsScene instance(Scene lastGameScene) {
        if (object == null)
            object = new SkillsScene();
        if (lastGameScene != null)
            object.lastGameScene = lastGameScene;
        return object;
    }

    @Override
    public void dispose() {
    }

    @Override
    public void enter() {
        super.enter();
        buildList();
    }

    private void buildList() {
        scrollContainer.clear();
        PlayerSkills skills = Current.player().getSkills();

        addRow("[BLACK]Skill", "[BLACK]Level", "[BLACK]XP");
        for (PlayerSkills.Skill skill : PlayerSkills.Skill.values()) {
            int level = skills.getLevel(skill);
            int xp = skills.getXp(skill);
            String progress;
            if (level >= PlayerSkills.MAX_LEVEL) {
                progress = String.format("%,d", xp);
            } else {
                int next = PlayerSkills.xpForLevel(level + 1);
                progress = String.format("%,d / %,d", xp, next);
            }
            if (Config.ascendant()) {
                addSkillRow(skill, level, progress, skills);
            } else {
                addRow("[BLACK]" + skill.displayName, "[BLACK]" + level, "[DARK_GRAY]" + progress);
            }
        }
        addRow("[BLACK]Total level", "[BLACK]" + skills.getTotalLevel(), "");
        if (Config.ascendant()) {
            TypingLabel slots = label("[DARK_GRAY]Perk slots: " + skills.getSlottedPerks().size()
                    + " / " + skills.perkSlotCount() + "  (open a skill tree to manage)");
            slots.setWrap(true);
            scrollContainer.add(slots).colspan(3).align(Align.left).padLeft(10).growX();
            scrollContainer.row().padTop(4);
            String league = Current.player().isLeagueCleared() ? " · League ✓" : "";
            addRow("[BLACK]Gym badges", "[BLACK]" + Current.player().getBadgeCount() + "/8",
                    "[DARK_GRAY]" + Current.player().getRunFormat() + league);
        }

        StandardWindow window = Current.player().getStandardWindow();
        if (window.isActive()) {
            StringBuilder sets = new StringBuilder();
            for (String code : window.getSets())
                sets.append(sets.length() == 0 ? "" : ", ").append(StandardWindow.setName(code));
            addRow("[BLACK]Standard", "", "[DARK_GRAY]" + sets);
            int[] p = window.masteryProgress(Current.player().getCards());
            int pct = p[1] == 0 ? 0 : Math.round(100f * p[0] / p[1]);
            String status = window.isSetUnlockedThisWorld() ? " (next set: New Game+)" : "";
            addRow("[BLACK]Mastery: " + StandardWindow.setName(window.newestSet()), "[BLACK]" + pct + "%",
                    "[DARK_GRAY]" + String.format("%,d / %,d", p[0], p[1]) + status);
            if (window.isChoicePending())
                addSetChoice(window);
        }
        if (Config.ascendant() && Current.player().getMasterySurgePicksUnspent() > 0)
            addMasterySurgeChoice();
        performTouch(scrollPaneOfActor(scrollContainer)); //mouse wheel scrolling
    }

    private void addSkillRow(PlayerSkills.Skill skill, int level, String progress, PlayerSkills skills) {
        TextraButton nameBtn = Controls.newTextButton(skill.displayName);
        nameBtn.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                Forge.switchScene(SkillTreeScene.instance(lastGameScene, skill), true);
            }
        });
        scrollContainer.add(nameBtn).align(Align.left).expandX().padLeft(10);
        scrollContainer.add(label("[BLACK]" + level)).align(Align.center).padRight(15);
        scrollContainer.add(label("[DARK_GRAY]" + progress)).align(Align.right).padRight(10);
        scrollContainer.row().padTop(1);
        addToSelectable(nameBtn);

        int avail = skills.talentPointsAvailable(skill);
        int earned = skills.talentPointsEarned(skill);
        boolean hasTree = !SkillTreeListData.get(skill).isEmpty();
        String treeLine = hasTree
                ? "Tree · " + avail + " / " + earned + " points"
                + (skills.hasSkillCape(skill) ? " · cape" : "")
                : "No tree data yet · " + avail + " / " + earned + " points stored";
        TypingLabel line = label("[DARK_GRAY][%80]" + treeLine);
        line.setWrap(true);
        scrollContainer.add(line).colspan(3).align(Align.left).padLeft(24).growX();
        scrollContainer.row().padTop(3);
    }

    /** After mastering a set: pick the next Standard set from every set with packs. */
    private void addSetChoice(StandardWindow window) {
        List<CardEdition> choices = window.choosableSets();
        if (choices.isEmpty())
            return;
        Array<String> names = new Array<>();
        for (CardEdition e : choices)
            names.add(e.getName() + " (" + e.getCode() + ")");
        SelectBox<String> box = Controls.newComboBox();
        box.setItems(names);
        box.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                box.showScrollPane();
            }
        });
        TextraButton unlock = Controls.newTextButton("Unlock");
        unlock.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                int i = box.getSelectedIndex();
                if (i < 0 || i >= choices.size())
                    return;
                String rotated = window.chooseNextSet(choices.get(i).getCode());
                if (rotated != null) {
                    // Rotation: nothing moves; rotated cards stay yours and remain playable in Historic
                    int out = Current.player().countRotatedOut();
                    if (out > 0)
                        showDialog(createGenericDialog("Rotation", StandardWindow.setName(rotated) + " rotated out of Standard. "
                                + out + " of your cards are no longer Standard-legal, but you keep them all and they're still "
                                + "playable in Historic decks. Standard decks that use them are now Locked; switch them to "
                                + "Historic on the deck screen to keep playing them.",
                                Forge.getLocalizer().getMessage("lblOK"), null, SkillsScene.this::removeDialog, null));
                }
                RewardData.invalidateCardPool();
                buildList();
            }
        });
        scrollContainer.row().padTop(10);
        scrollContainer.add(label("[BLACK]Choose your next set:")).align(Align.left).padLeft(10).colspan(3);
        scrollContainer.row().padTop(4);
        scrollContainer.add(box).colspan(2).fillX().padLeft(10);
        scrollContainer.add(unlock).padRight(10);
        addToSelectable(box);
        addToSelectable(unlock);
    }

    /** Spend unspent Mastery Surge picks (INV1). */
    private void addMasterySurgeChoice() {
        AdventurePlayer ap = Current.player();
        List<MasterySurgeData> options = ap.availableMasterySurgeOptions();
        if (options.isEmpty())
            return;
        Array<String> names = new Array<>();
        for (MasterySurgeData d : options)
            names.add(d.getDisplayName());
        SelectBox<String> box = Controls.newComboBox();
        box.setItems(names);
        box.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                box.showScrollPane();
            }
        });
        TextraButton pick = Controls.newTextButton("Claim");
        pick.addListener(new ClickListener() {
            @Override
            public void clicked(InputEvent event, float x, float y) {
                int i = box.getSelectedIndex();
                if (i < 0 || i >= options.size())
                    return;
                if (ap.spendMasterySurgePick(options.get(i).id))
                    buildList();
            }
        });
        scrollContainer.row().padTop(10);
        scrollContainer.add(label("[GOLD]Mastery Surge picks: " + ap.getMasterySurgePicksUnspent() + "[]"))
                .align(Align.left).padLeft(10).colspan(3);
        scrollContainer.row().padTop(4);
        scrollContainer.add(label("[DARK_GRAY]Craft Pouch: " + ap.getBags().getCraftPouchTier().label))
                .align(Align.left).padLeft(10).colspan(3);
        scrollContainer.row().padTop(4);
        scrollContainer.add(box).colspan(2).fillX().padLeft(10);
        scrollContainer.add(pick).padRight(10);
        addToSelectable(box);
        addToSelectable(pick);
    }

    private void addRow(String name, String level, String xp) {
        scrollContainer.add(label(name)).align(Align.left).expandX().padLeft(10);
        scrollContainer.add(label(level)).align(Align.center).padRight(15);
        scrollContainer.add(label(xp)).align(Align.right).padRight(10);
        scrollContainer.row().padTop(3);
    }

    private static TypingLabel label(String text) {
        TypingLabel label = Controls.newTypingLabel(text);
        label.skipToTheEnd();
        return label;
    }

    @Override
    public boolean back() {
        Adventure.getInstance().renderTransitionScreen = true;
        Forge.switchScene(lastGameScene == null ? GameScene.instance() : lastGameScene);
        return true;
    }
}
