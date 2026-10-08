package forge.adventure.data;

import java.io.Serializable;

/**
 * One Mastery Surge pick option from {@code mastery_surge.json}.
 */
public class MasterySurgeData implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Stable id: craft_pouch_next, duel_perk_slot, tool_enchant_socket, overflow_cap, … */
    public String id;
    public String name;
    public String description;
    /** Optional atlas icon placeholder. */
    public String iconName;
    /**
     * When true, this option is always offered if still available (e.g. next Craft Pouch tier).
     * When the Craft Pouch is already Bottomless, the option is hidden.
     */
    public boolean alwaysInclude = false;
    /** Effect kind applied when picked. */
    public String effect;

    public String getDisplayName() {
        return name != null ? name : id;
    }
}
