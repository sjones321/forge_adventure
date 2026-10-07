package forge.adventure.scene;

import com.badlogic.gdx.scenes.scene2d.Touchable;
import com.badlogic.gdx.scenes.scene2d.ui.ScrollPane;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.Window;
import com.badlogic.gdx.utils.Align;
import com.github.tommyettinger.textra.TypingLabel;
import forge.Adventure;
import forge.Forge;
import forge.adventure.data.SkillTreeData;
import forge.adventure.data.SkillTreeListData;
import forge.adventure.data.SkillTreeNodeData;
import forge.adventure.player.PlayerSkills;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.Controls;
import forge.adventure.util.Current;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A RuneScape-style collection log of everything skills unlock: tree talents, skill staples,
 * multicolor staples and utility lands, each shown as unlocked (green) or with exactly what it needs.
 */
public class UnlocksScene extends UIScene {
    private final Table list;
    private Scene lastGameScene;

    private UnlocksScene() {
        super(Forge.isLandscapeMode() ? "ui/skills.json" : "ui/skills_portrait.json");
        Window scrollWindow = ui.findActor("scrollWindow");
        scrollWindow.setTouchable(Touchable.disabled);
        Table root = ui.findActor("skillList");
        ui.onButtonPress("return", () -> Forge.switchScene(SkillsScene.instance(lastGameScene), true));
        ui.onButtonPress("status", () -> Forge.switchScene(PlayerStatisticScene.instance(lastGameScene), true));
        ui.onButtonPress("quests", () -> Forge.switchScene(QuestLogScene.instance(lastGameScene), true));
        ui.onButtonPress("unlocks", () -> Forge.switchScene(SkillsScene.instance(lastGameScene), true));
        com.github.tommyettinger.textra.TextraButton toSkills = ui.findActor("unlocks");
        if (toSkills != null)
            toSkills.setText("Skills"); // same layout as the Skills screen; this button goes back there
        list = new Table(Controls.getSkin());
        ScrollPane scroller = new ScrollPane(list);
        scroller.setScrollingDisabled(true, false);
        root.add(scroller).fill().expand();
    }

    private static UnlocksScene object;

    public static UnlocksScene instance(Scene lastGameScene) {
        if (object == null)
            object = new UnlocksScene();
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
        build();
    }

    private void build() {
        list.clear();
        PlayerSkills skills = Current.player().getSkills();
        Map<PlayerSkills.Skill, LinkedHashMap<String, Integer>> staples = StandardWindow.colorStapleLists();

        header("Unlocks");
        note("Staples are always Standard-legal for you once unlocked, and show up in shops, loot and Spell Smith.");
        note("Flat color perks were replaced by talent trees (Skills → click a skill).");

        PlayerSkills.Skill[] colors = {PlayerSkills.Skill.WHITE, PlayerSkills.Skill.BLUE, PlayerSkills.Skill.BLACK,
                PlayerSkills.Skill.RED, PlayerSkills.Skill.GREEN};
        for (PlayerSkills.Skill color : colors) {
            int level = skills.getLevel(color);
            header(color.displayName + " (level " + level + ")");
            addTreeSummary(skills, color);
            addLevelList(staples.get(color), level, "Staple");
        }

        int smithing = skills.getLevel(PlayerSkills.Skill.SPELLSMITHING);
        header("Colorless (Spellsmithing level " + smithing + ")");
        addLevelList(staples.get(PlayerSkills.Skill.SPELLSMITHING), smithing, "Staple");

        int exploration = skills.getLevel(PlayerSkills.Skill.EXPLORATION);
        header("Lands (Exploration level " + exploration + ")");
        addLevelList(staples.get(PlayerSkills.Skill.EXPLORATION), exploration, "Land");

        header("Utility lands (Exploration + a color or Spellsmithing)");
        for (StandardWindow.ComboLand l : StandardWindow.comboLands()) {
            int other = skills.getLevel(l.skill());
            boolean done = exploration >= l.explorationLevel() && other >= l.skillLevel();
            line(done, l.name() + (done ? "" : "  [%80]needs Exploration " + l.explorationLevel() + " (" + exploration + ") + "
                    + l.skill().displayName + " " + l.skillLevel() + " (" + other + ")"));
        }

        header("Multicolor (both colors must reach the level)");
        for (StandardWindow.PairStaple p : StandardWindow.pairStaples()) {
            int a = skills.getLevel(p.a()), b = skills.getLevel(p.b());
            boolean done = Math.min(a, b) >= p.level();
            line(done, p.name() + (done ? "" : "  [%80]needs " + p.a().displayName + " and " + p.b().displayName
                    + " " + p.level() + " (" + a + " / " + b + ")"));
        }
        performTouch(scrollPaneOfActor(list)); //mouse wheel scrolling
    }

    private void addTreeSummary(PlayerSkills skills, PlayerSkills.Skill skill) {
        SkillTreeData tree = SkillTreeListData.get(skill);
        int earned = skills.talentPointsEarned(skill);
        int spent = skills.talentPointsSpent(skill);
        line(spent > 0 || earned > 0, "Talents: " + spent + " spent / " + earned + " earned"
                + (skills.hasSkillCape(skill) ? " · skill cape" : ""));
        if (tree.isEmpty())
            return;
        int owned = 0;
        int capstones = 0;
        for (SkillTreeNodeData n : tree.nodes) {
            if (n == null)
                continue;
            if (skills.getNodeRanks(n.id) > 0) {
                owned++;
                if (n.capstone)
                    capstones++;
            }
        }
        line(owned > 0, "Tree nodes owned: " + owned + (capstones > 0 ? " (" + capstones + " capstones)" : ""));
    }

    private void addLevelList(LinkedHashMap<String, Integer> entries, int level, String kind) {
        if (entries == null)
            return;
        // group same-level entries (e.g. a whole land cycle) into one line
        Map<Integer, List<String>> byLevel = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : entries.entrySet())
            byLevel.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        for (Map.Entry<Integer, List<String>> e : byLevel.entrySet())
            entry(level >= e.getKey(), kind, e.getKey(), String.join(", ", e.getValue()));
    }

    private void entry(boolean unlocked, String kind, int at, String text) {
        line(unlocked, (unlocked ? "" : "Lv " + at + "  ") + kind + ": " + text);
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
        Forge.switchScene(SkillsScene.instance(lastGameScene), true);
        return true;
    }
}
