package forge.adventure.world;

import forge.adventure.data.ConfigData;
import forge.adventure.util.Config;
import forge.adventure.util.Paths;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Validates MV1 {@code worldConfigPath} values. Free-form / path-traversal
 * paths are rejected; only known plane templates may be used.
 */
public final class PlaneConfigPaths {
    private PlaneConfigPaths() {
    }

    public static boolean isSyntacticallySafe(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        if (path.indexOf('\\') >= 0 || path.contains("..") || path.startsWith("/")
                || path.startsWith(".")) {
            return false;
        }
        return path.startsWith("world/") && path.endsWith(".json");
    }

    /**
     * Allowed templates: home {@link Paths#WORLD}, Ascendant set-plane template,
     * and any path already registered on a plane meta in {@code multi}.
     */
    public static boolean isAllowed(String path, MultiverseState multi) {
        if (!isSyntacticallySafe(path)) {
            return false;
        }
        for (String allowed : allowedPaths(multi)) {
            if (allowed.equals(path)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> allowedPaths(MultiverseState multi) {
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        allowed.add(Paths.WORLD);
        try {
            ConfigData cfg = Config.instance().getConfigData();
            if (cfg != null && cfg.setPlaneWorldConfig != null && !cfg.setPlaneWorldConfig.isEmpty()
                    && isSyntacticallySafe(cfg.setPlaneWorldConfig)) {
                allowed.add(cfg.setPlaneWorldConfig);
            } else {
                allowed.add("world/set_plane_world.json");
            }
        } catch (Throwable ignored) {
            // Headless tests / early init — still allow the Ascendant set-plane template.
            allowed.add("world/set_plane_world.json");
        }
        if (multi != null) {
            for (PlaneMeta meta : multi.listPlanes()) {
                if (meta != null && isSyntacticallySafe(meta.getWorldConfigPath())) {
                    allowed.add(meta.getWorldConfigPath());
                }
            }
        }
        return allowed;
    }

    public static String normalizeOrDefault(String path) {
        if (path == null || path.isEmpty()) {
            return Paths.WORLD;
        }
        return path;
    }
}
