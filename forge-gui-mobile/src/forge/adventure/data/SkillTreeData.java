package forge.adventure.data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talent tree for one {@link forge.adventure.player.PlayerSkills.Skill}, from {@code world/skill_trees.json}.
 */
public class SkillTreeData implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /** Skill enum name, e.g. {@code WHITE}, {@code DUELING}. */
    public String skill;
    public SkillTreeNodeData[] nodes;

    public SkillTreeNodeData findNode(String id) {
        if (id == null || nodes == null)
            return null;
        for (SkillTreeNodeData n : nodes) {
            if (n != null && id.equals(n.id))
                return n;
        }
        return null;
    }

    public boolean isEmpty() {
        return nodes == null || nodes.length == 0;
    }

    /** Branch key → display name, in first-seen order. */
    public Map<String, String> branches() {
        Map<String, String> out = new LinkedHashMap<>();
        if (nodes == null)
            return out;
        for (SkillTreeNodeData n : nodes) {
            if (n == null || n.branch == null || n.branch.isEmpty())
                continue;
            out.putIfAbsent(n.branch, n.branchName != null && !n.branchName.isEmpty() ? n.branchName : n.branch);
        }
        return out;
    }

    public List<SkillTreeNodeData> nodesInBranch(String branch) {
        List<SkillTreeNodeData> out = new ArrayList<>();
        if (nodes == null || branch == null)
            return out;
        for (SkillTreeNodeData n : nodes) {
            if (n != null && branch.equals(n.branch))
                out.add(n);
        }
        out.sort((a, b) -> Integer.compare(a.tier, b.tier));
        return out;
    }
}
