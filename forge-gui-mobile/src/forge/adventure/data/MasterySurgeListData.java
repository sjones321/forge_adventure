package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Loads Ascendant Mastery Surge pick options from {@link Paths#MASTERY_SURGE}.
 */
public final class MasterySurgeListData {
    private static List<MasterySurgeData> cached;

    private MasterySurgeListData() {}

    public static void invalidate() {
        cached = null;
    }

    public static List<MasterySurgeData> getAll() {
        if (cached != null)
            return cached;
        List<MasterySurgeData> list = new ArrayList<>();
        try {
            FileHandle handle = Config.instance().getFile(Paths.MASTERY_SURGE);
            if (handle != null && handle.exists()) {
                Json json = new Json();
                json.setOutputType(JsonWriter.OutputType.json);
                MasterySurgeData[] arr = json.fromJson(MasterySurgeData[].class, handle);
                if (arr != null) {
                    for (MasterySurgeData d : arr) {
                        if (d != null && d.id != null && !d.id.isEmpty())
                            list.add(d);
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall through to built-in defaults
        }
        if (list.isEmpty())
            list.addAll(defaults());
        cached = Collections.unmodifiableList(list);
        return cached;
    }

    public static MasterySurgeData get(String id) {
        if (id == null)
            return null;
        for (MasterySurgeData d : getAll()) {
            if (id.equals(d.id))
                return d;
        }
        return null;
    }

    private static List<MasterySurgeData> defaults() {
        List<MasterySurgeData> list = new ArrayList<>();
        list.add(opt("craft_pouch_next", "Next Craft Pouch tier",
                "Upgrade the Craft Pouch (Satchel → Pack → Hauler's Sack → Bottomless).",
                "Bag", true, "craft_pouch_next"));
        list.add(opt("duel_perk_slot", "+1 duel perk slot",
                "Gain one extra duel perk slot.",
                "Medal", false, "duel_perk_slot"));
        list.add(opt("tool_enchant_socket", "+1 tool enchant socket",
                "Gain one extra tool enchantment socket (all families).",
                "Jewel", false, "tool_enchant_socket"));
        list.add(opt("overflow_cap", "+1 Overflow cap",
                "Raise the Overflow stash slot cap by one.",
                "Chest", false, "overflow_cap"));
        return list;
    }

    private static MasterySurgeData opt(String id, String name, String desc, String icon,
                                       boolean always, String effect) {
        MasterySurgeData d = new MasterySurgeData();
        d.id = id;
        d.name = name;
        d.description = desc;
        d.iconName = icon;
        d.alwaysInclude = always;
        d.effect = effect;
        return d;
    }
}
