package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.player.PlayerSkills;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

/**
 * Loads and indexes {@code world/skill_trees.json}.
 * Skills with no entry get an empty tree so later packages can add skills before their trees ship.
 */
public final class SkillTreeListData {
    private static final ObjectMap<String, SkillTreeData> bySkill = new ObjectMap<>();
    private static final SkillTreeData EMPTY = new SkillTreeData();

    static {
        reload();
    }

    private SkillTreeListData() {
    }

    /** Reload from disk. Safe if the file is missing. */
    public static void reload() {
        bySkill.clear();
        FileHandle handle = Config.instance().getFile(Paths.SKILL_TREES);
        if (handle == null || !handle.exists())
            return;
        Json json = new Json();
        Array<SkillTreeData> loaded = json.fromJson(Array.class, SkillTreeData.class, handle);
        if (loaded == null)
            return;
        for (SkillTreeData tree : new Array.ArrayIterator<>(loaded)) {
            if (tree == null || tree.skill == null || tree.skill.isEmpty())
                continue;
            bySkill.put(tree.skill, tree);
        }
    }

    public static SkillTreeData get(PlayerSkills.Skill skill) {
        if (skill == null)
            return EMPTY;
        return get(skill.name());
    }

    public static SkillTreeData get(String skillName) {
        if (skillName == null)
            return EMPTY;
        SkillTreeData tree = bySkill.get(skillName);
        return tree != null ? tree : EMPTY;
    }

    public static SkillTreeNodeData findNode(String nodeId) {
        if (nodeId == null)
            return null;
        for (SkillTreeData tree : bySkill.values()) {
            SkillTreeNodeData n = tree.findNode(nodeId);
            if (n != null)
                return n;
        }
        return null;
    }

    /** Skill enum name owning this node, or null. */
    public static String skillForNode(String nodeId) {
        if (nodeId == null)
            return null;
        for (ObjectMap.Entry<String, SkillTreeData> e : bySkill.entries()) {
            if (e.value != null && e.value.findNode(nodeId) != null)
                return e.key;
        }
        return null;
    }
}
