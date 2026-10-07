package forge.adventure.player;

import forge.adventure.util.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Editable per-format ban lists: res/adventure/common/banned_standard.txt, banned_historic.txt,
 * banned_commander.txt. One card name per line, "#" for comments. Banned cards can't be added to
 * decks of that format, and Standard decks containing them show as locked.
 */
public final class BanLists {
    private static final Map<String, Set<String>> LISTS = new HashMap<>();

    private BanLists() {
    }

    public static boolean isBanned(String format, String cardName) {
        return get(format).contains(cardName);
    }

    public static Set<String> get(String format) {
        return LISTS.computeIfAbsent(format, f -> {
            Set<String> names = new HashSet<>();
            File file = new File(Config.instance().getCommonFilePath("banned_" + f + ".txt"));
            if (!file.isFile())
                return names;
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#"))
                        names.add(line);
                }
            } catch (Exception ignored) {
            }
            return names;
        });
    }
}
