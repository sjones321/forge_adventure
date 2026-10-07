package forge;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import forge.adventure.data.SettingData;
import forge.adventure.util.Config;
import forge.adventure.util.SaveFileData;
import forge.adventure.world.SpritesDataMap;
import forge.adventure.world.World;
import forge.assets.Assets;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgeConstants;
import org.apache.commons.lang3.tuple.Pair;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Ascendant world-gen benchmark for Package C review.
 * Usage (from repo root, after mvn install):
 *   xvfb-run -a java -Xmx3g -cp forge-gui-mobile-dev/target/forge-gui-mobile-dev-*-jar-with-dependencies.jar forge.WorldGenBench
 */
public class WorldGenBench {
    private static final long[] SEEDS = {1L, 2L, 3L, 42L, 99L};
    private static int exitCode = 1;

    static {
        // ForgeConstants clinit needs GuiBase.getInterface().getAssetsDir().
        String assets = Files.exists(Paths.get("./forge-gui")) ? "./forge-gui/"
                : Files.exists(Paths.get("./res")) ? "./" : "../forge-gui/";
        GuiBase.setInterface(new GuiMobile(assets));
    }

    public static void main(String[] args) {
        writeAscendantSettings();

        Lwjgl3ApplicationConfiguration config = new Lwjgl3ApplicationConfiguration();
        config.setWindowedMode(64, 64);
        config.setTitle("WorldGenBench");
        config.disableAudio(true);
        new Lwjgl3Application(new ApplicationAdapter() {
            @Override
            public void create() {
                try {
                    ((GuiMobile) GuiBase.getInterface()).captureGlThread();
                    Texture.setAssetManager(Assets.getInstance().manager());
                    runBench();
                    exitCode = 0;
                } catch (Throwable t) {
                    t.printStackTrace();
                    exitCode = 2;
                } finally {
                    Gdx.app.exit();
                }
            }
        }, config);

        System.exit(exitCode);
    }

    private static void writeAscendantSettings() {
        File dir = new File(ForgeConstants.USER_ADVENTURE_DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        SettingData settings = new SettingData();
        settings.plane = "Shandalar Ascendant";
        settings.width = 1280;
        settings.height = 720;
        settings.videomode = "720p";
        FileHandle out = new FileHandle(ForgeConstants.USER_ADVENTURE_DIR + "settings.json");
        Json json = new Json(JsonWriter.OutputType.json);
        out.writeString(json.prettyPrint(settings), false, "UTF-8");
        System.out.println("Wrote settings plane=" + settings.plane + " to " + out.path());
    }

    private static void runBench() {
        Config cfg = Config.instance();
        System.out.println("Config plane=" + cfg.getPlane() + " ascendant=" + Config.ascendant());
        if (!Config.ascendant()) {
            throw new IllegalStateException("Expected Ascendant plane for world-gen bench");
        }

        System.out.println("=== Ascendant generateNew (" + SEEDS.length + " seeds) ===");
        for (long seed : SEEDS) {
            System.gc();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            Runtime rt = Runtime.getRuntime();
            long before = rt.totalMemory() - rt.freeMemory();
            long t0 = System.currentTimeMillis();
            World world = new World();
            boolean ok = world.generateNew(seed);
            long ms = System.currentTimeMillis() - t0;
            long after = rt.totalMemory() - rt.freeMemory();
            int towns = 0;
            try {
                towns = (int) world.getAllPointOfInterest().stream()
                        .filter(p -> p.getData() != null && p.getData().type != null
                                && (p.getData().type.equals("town") || p.getData().type.equals("capital")))
                        .count();
            } catch (Exception e) {
                System.err.println("town count failed: " + e.getMessage());
            }
            System.out.printf("BENCH seed=%d ok=%s time=%.2fs heapDelta~%dMB heapUsed~%dMB towns=%d size=%dx%d miniMapTileSize=%d%n",
                    seed, ok, ms / 1000f, (after - before) / (1024 * 1024),
                    after / (1024 * 1024), towns,
                    world.getWidthInTiles(), world.getHeightInTiles(),
                    world.getData() != null ? world.getData().miniMapTileSize : -1);
            if (!ok) {
                throw new IllegalStateException("generateNew failed for seed " + seed);
            }
            world.dispose();
        }

        System.out.println("=== Old 700-wide SpritesDataMap load smoke ===");
        verifyOldSpritesMapLoad();
        System.out.println("Old-save SpritesDataMap load: OK (chunk 23 bounds safe)");
    }

    /** Simulate a 700-wide save loaded under a 1000-wide world.json constructor arg. */
    private static void verifyOldSpritesMapLoad() {
        int tileSize = 16;
        int chunkSize = 30; // typical: 480/16
        int savedChunks = 700 / chunkSize; // 23
        int wrongChunks = 1000 / chunkSize; // 33 — what the old bug used

        SpritesDataMap map = new SpritesDataMap(chunkSize, tileSize, wrongChunks);
        @SuppressWarnings("unchecked")
        List<Pair<Vector2, Integer>>[][] saved = new List[savedChunks][savedChunks];
        for (int x = 0; x < savedChunks; x++) {
            for (int y = 0; y < savedChunks; y++) {
                saved[x][y] = new ArrayList<>();
            }
        }
        SaveFileData data = new SaveFileData();
        SpritesDataMap.BiomeSpriteDataMap objectData = new SpritesDataMap.BiomeSpriteDataMap();
        data.store("objectData", objectData.save());
        data.storeObject("mapObjects", saved);
        data.storeObject("objectKeys", new java.util.HashMap<String, Integer>());
        data.store("tileSize", tileSize);
        data.store("chunkSize", chunkSize);
        map.load(data);

        List<Pair<Vector2, Integer>> edge = map.positions(23, 0);
        if (edge == null) {
            throw new IllegalStateException("positions returned null");
        }
        List<Pair<Vector2, Integer>> last = map.positions(savedChunks - 1, savedChunks - 1);
        if (last == null) {
            throw new IllegalStateException("last chunk null");
        }
    }
}
