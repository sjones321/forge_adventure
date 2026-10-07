package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.InputEvent;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.SelectBox;
import com.badlogic.gdx.scenes.scene2d.utils.ClickListener;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.TextraButton;
import forge.adventure.data.RewardData;
import forge.card.CardEdition;
import java.util.List;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Adventure;
import forge.Forge;
import forge.adventure.player.PlayerSkills;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;

/**
 * Lists every skill with its level, XP and progress to the next level.
 * Reached from the Status and Quests screens.
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
            addRow("[BLACK]" + skill.displayName, "[BLACK]" + level, "[DARK_GRAY]" + progress);
        }
        addRow("[BLACK]Total level", "[BLACK]" + skills.getTotalLevel(), "");

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
        performTouch(scrollPaneOfActor(scrollContainer)); //mouse wheel scrolling
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
                window.chooseNextSet(choices.get(i).getCode());
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
