package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Adventure;
import forge.Forge;
import forge.adventure.player.PlayerSkills;
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
        performTouch(scrollPaneOfActor(scrollContainer)); //mouse wheel scrolling
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
