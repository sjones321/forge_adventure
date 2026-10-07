package forge.adventure.data;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;
import forge.adventure.util.Reward;
import forge.util.MyRandom;

import java.util.Random;

/**
 * Ascendant enemy material drops loaded from {@code world/enemy_material_drops.json}.
 * Color-keyed random rolls for normal enemies; bosses always drop their unique material.
 */
public final class EnemyMaterialDropData {
    private static Table table;

    static {
        reload();
    }

    private EnemyMaterialDropData() {
    }

    public static void reload() {
        table = null;
        FileHandle handle = Config.instance().getFile(Paths.ENEMY_MATERIAL_DROPS);
        if (handle == null || !handle.exists())
            return;
        Json json = new Json();
        table = json.fromJson(Table.class, handle);
    }

    /**
     * Appends Ascendant material rewards for a defeated enemy.
     * No-op when Ascendant rules are off or the table is missing.
     */
    public static void appendDrops(EnemyData enemy, Array<Reward> out) {
        if (!Config.ascendant() || enemy == null || out == null || table == null)
            return;

        if (enemy.boss) {
            String bossMat = resolveBossMaterial(enemy);
            if (bossMat != null && MaterialListData.exists(bossMat))
                out.add(new Reward(bossMat, 1));
            return;
        }

        ConfigData cfg = Config.instance().getConfigData();
        float chance = cfg.enemyMaterialDropChance;
        if (chance <= 0f)
            return;
        Random rng = MyRandom.getRandom();
        if (rng.nextFloat() >= chance)
            return;

        String materialId = pickColorMaterial(enemy.colors, rng);
        if (materialId != null && MaterialListData.exists(materialId)) {
            int count = Math.max(1, cfg.enemyMaterialDropCount);
            out.add(new Reward(materialId, count));
        }
    }

    private static String resolveBossMaterial(EnemyData enemy) {
        if (table.byBoss != null && enemy.name != null) {
            String id = table.byBoss.get(enemy.name);
            if (id != null && !id.isEmpty())
                return id;
        }
        return table.defaultBossMaterial;
    }

    private static String pickColorMaterial(String colors, Random rng) {
        if (table.byColor == null || table.byColor.size == 0)
            return null;
        if (colors == null || colors.isEmpty())
            colors = "C";

        // Prefer letters that have a table entry; fall back to colorless.
        Array<String> keys = new Array<>();
        for (int i = 0; i < colors.length(); i++) {
            String key = String.valueOf(Character.toUpperCase(colors.charAt(i)));
            if (table.byColor.containsKey(key))
                keys.add(key);
        }
        if (keys.isEmpty() && table.byColor.containsKey("C"))
            keys.add("C");
        if (keys.isEmpty())
            return null;

        String color = keys.get(rng.nextInt(keys.size));
        String[] pool = table.byColor.get(color);
        if (pool == null || pool.length == 0)
            return null;
        return pool[rng.nextInt(pool.length)];
    }

    /** LibGDX JSON root for the drop table. */
    public static class Table {
        /** Color letter → material id pool. */
        public ObjectMap<String, String[]> byColor;
        /** Enemy name → unique boss material id. */
        public ObjectMap<String, String> byBoss;
        /** Used when a boss has no named entry. */
        public String defaultBossMaterial = "boss_trophy";
    }
}
