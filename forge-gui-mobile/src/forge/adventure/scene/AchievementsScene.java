package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Adventure;
import forge.Forge;
import forge.adventure.data.AchievementData;
import forge.adventure.data.AchievementListData;
import forge.adventure.player.AchievementService;
import forge.adventure.util.Config;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ascendant AC1: controller-friendly achievements list (keyboard, mouse, gamepad
 * via {@link UIScene} selectables). Reached from the status menu.
 */
public class AchievementsScene extends UIScene {
    private final Table list;
    private Scene lastGameScene;

    private AchievementsScene() {
        super(Forge.isLandscapeMode() ? "ui/achievements.json" : "ui/achievements_portrait.json");
        Window scrollWindow = ui.findActor("scrollWindow");
        if (scrollWindow != null) {
            scrollWindow.setTouchable(Touchable.disabled);
        }
        Table root = ui.findActor("achievementList");
        ui.onButtonPress("return", this::back);
        ui.onButtonPress("status", () -> Forge.switchScene(PlayerStatisticScene.instance(lastGameScene), true));
        ui.onButtonPress("skills", () -> Forge.switchScene(SkillsScene.instance(lastGameScene), true));
        ui.onButtonPress("quests", () -> Forge.switchScene(QuestLogScene.instance(lastGameScene), true));
        list = new Table(Controls.getSkin());
        ScrollPane scroller = new ScrollPane(list);
        scroller.setScrollingDisabled(true, false);
        if (root != null) {
            root.add(scroller).fill().expand();
        }
        Actor backBtn = ui.findActor("return");
        Actor statusBtn = ui.findActor("status");
        Actor skillsBtn = ui.findActor("skills");
        Actor questsBtn = ui.findActor("quests");
        if (backBtn != null) {
            addToSelectable(backBtn);
        }
        if (statusBtn != null) {
            addToSelectable(statusBtn);
        }
        if (skillsBtn != null) {
            addToSelectable(skillsBtn);
        }
        if (questsBtn != null) {
            addToSelectable(questsBtn);
        }
    }

    private static AchievementsScene object;

    public static AchievementsScene instance(Scene lastGameScene) {
        if (object == null) {
            object = new AchievementsScene();
        }
        if (lastGameScene != null) {
            object.lastGameScene = lastGameScene;
        }
        return object;
    }

    @Override
    public void dispose() {
    }

    @Override
    public void enter() {
        super.enter();
        if (Config.ascendant() && Current.player() != null) {
            AchievementService.get().evaluateAll(Current.player());
        }
        build();
    }

    private void build() {
        list.clear();
        if (!Config.ascendant()) {
            note("Achievements are part of Shandalar Ascendant.");
            return;
        }
        AchievementService svc = AchievementService.get();
        header("Achievements");
        note("Account-wide. Kept through prestige and New Game+.");

        List<AchievementData> all = AchievementListData.getAll();
        Map<String, List<AchievementData>> byCategory = new LinkedHashMap<>();
        for (AchievementData a : all) {
            if (a == null) {
                continue;
            }
            boolean unlocked = svc.getProgress().isUnlocked(a.id);
            if (a.hidden && !unlocked) {
                continue;
            }
            String cat = a.category == null || a.category.isEmpty() ? "general" : a.category;
            byCategory.computeIfAbsent(cat, k -> new java.util.ArrayList<>()).add(a);
        }

        if (byCategory.isEmpty()) {
            note("No achievements loaded.");
            performTouch(scrollPaneOfActor(list));
            return;
        }

        for (Map.Entry<String, List<AchievementData>> e : byCategory.entrySet()) {
            header(prettyCategory(e.getKey()));
            for (AchievementData a : e.getValue()) {
                AchievementService.ProgressView pv = svc.progressView(a);
                boolean unlocked = pv.unlocked;
                String title = a.hidden && !unlocked ? "???" : (a.name == null ? a.id : a.name);
                String desc = a.hidden && !unlocked ? "Hidden achievement."
                        : (a.description == null ? "" : a.description);
                String progress = pv.label == null || pv.label.isEmpty() ? "" : "  [%80](" + pv.label + ")";
                line(unlocked, title + progress);
                if (desc != null && !desc.isEmpty()) {
                    note("  " + desc);
                }
            }
        }
        performTouch(scrollPaneOfActor(list));
    }

    private static String prettyCategory(String cat) {
        if (cat == null || cat.isEmpty()) {
            return "General";
        }
        String[] parts = cat.replace('_', ' ').split(" ");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1));
            }
        }
        return sb.toString();
    }

    private void line(boolean unlocked, String text) {
        TypingLabel l = Controls.newTypingLabel((unlocked ? "[FOREST]☑ " : "[DARK_GRAY]☐ ") + text);
        l.skipToTheEnd();
        l.setWrap(true);
        list.add(l).align(Align.left).growX().padLeft(18).padRight(8);
        list.row().padTop(1);
    }

    private void header(String text) {
        TypingLabel l = Controls.newTypingLabel("[BLACK][%110]" + text);
        l.skipToTheEnd();
        list.add(l).align(Align.left).padLeft(8).padTop(8);
        list.row();
    }

    private void note(String text) {
        TypingLabel l = Controls.newTypingLabel("[DARK_GRAY][%85]" + text);
        l.skipToTheEnd();
        l.setWrap(true);
        list.add(l).align(Align.left).growX().padLeft(8).padRight(8);
        list.row();
    }

    @Override
    public boolean back() {
        Adventure.getInstance().renderTransitionScreen = true;
        Forge.switchScene(lastGameScene == null ? PlayerStatisticScene.instance(GameScene.instance())
                : PlayerStatisticScene.instance(lastGameScene), true);
        return true;
    }
}
