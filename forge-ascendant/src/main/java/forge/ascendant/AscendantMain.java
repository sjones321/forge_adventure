package forge.ascendant;

import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import forge.ascendant.world.PlaneData;
import forge.ascendant.world.PlaneGenerator;
import forge.ascendant.world.PlaneProfile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Standalone launcher for Ascendant-Next stage 1 (walk the home plane).
 * Usage:
 *   java -jar forge-ascendant-*-jar-with-dependencies.jar
 *     [--res path/to/forge-gui/res/adventure/common/maps]
 *     [--seed 1] [--save dir]
 */
public final class AscendantMain {
    public static void main(String[] args) throws Exception {
        Path res = resolveMapsRoot(args);
        Path saveDir = resolveArgPath(args, "--save",
                Paths.get(System.getProperty("user.home"), ".forge-ascendant-next"));
        long seed = resolveSeed(args);

        if (!Files.isDirectory(res)) {
            System.err.println("Maps root not found: " + res.toAbsolutePath());
            System.err.println("Pass --res <forge-gui/res/adventure/common/maps>");
            System.exit(2);
        }

        System.out.println("Generating home plane seed=" + seed + " …");
        long t0 = System.nanoTime();
        PlaneProfile profile = PlaneProfile.loadClasspath("/profiles/home_shandalar.json");
        PlaneData plane = new PlaneGenerator(profile, res).generate(seed);
        System.out.printf("Generated %dx%d in %.1fs (%d sites)%n",
                plane.size, plane.size, (System.nanoTime() - t0) / 1e9, plane.sites.size());
        for (var s : plane.sites) {
            System.out.printf("  site %s at %d,%d (%dx%d)%n", s.name, s.x, s.y, s.w, s.h);
        }

        AscendantSession session = new AscendantSession(res, saveDir, profile, plane);

        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("Shandalar Ascendant Next — walk the plane");
        cfg.setWindowedMode(1280, 720);
        cfg.useVsync(true);
        cfg.setForegroundFPS(60);
        new Lwjgl3Application(new AscendantGame(session), cfg);
    }

    private static Path resolveMapsRoot(String[] args) {
        Path named = resolveArgPath(args, "--res", null);
        if (named != null) {
            return named;
        }
        Path[] candidates = {
                Paths.get("forge-gui/res/adventure/common/maps"),
                Paths.get("../forge-gui/res/adventure/common/maps"),
                Paths.get("res/adventure/common/maps")
        };
        for (Path c : candidates) {
            if (Files.isDirectory(c)) {
                return c.toAbsolutePath().normalize();
            }
        }
        return candidates[0].toAbsolutePath().normalize();
    }

    private static Path resolveArgPath(String[] args, String flag, Path defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                return Paths.get(args[i + 1]).toAbsolutePath().normalize();
            }
        }
        return defaultValue == null ? null : defaultValue.toAbsolutePath().normalize();
    }

    private static long resolveSeed(String[] args) {
        for (int i = 0; i < args.length - 1; i++) {
            if ("--seed".equals(args[i])) {
                return Long.parseLong(args[i + 1]);
            }
        }
        return 1L;
    }
}
