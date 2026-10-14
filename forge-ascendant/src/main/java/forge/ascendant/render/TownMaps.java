package forge.ascendant.render;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.maps.tiled.TiledMap;
import com.badlogic.gdx.maps.tiled.TmxMapLoader;
import com.badlogic.gdx.maps.tiled.renderers.OrthogonalTiledMapRenderer;
import com.badlogic.gdx.utils.Disposable;
import forge.ascendant.world.Site;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Loads stamped town TMX maps for overlay rendering. */
public final class TownMaps implements Disposable {
    private final Path mapsRoot;
    private final TmxMapLoader loader = new TmxMapLoader();
    private final Map<String, TiledMap> maps = new HashMap<>();
    private final Map<String, OrthogonalTiledMapRenderer> renderers = new HashMap<>();

    public TownMaps(Path mapsRoot) {
        this.mapsRoot = mapsRoot;
    }

    public OrthogonalTiledMapRenderer rendererFor(Site site) {
        return renderers.computeIfAbsent(site.tmxRelative, key -> {
            TiledMap map = maps.computeIfAbsent(key, this::load);
            return new OrthogonalTiledMapRenderer(map, 1f);
        });
    }

    public TiledMap mapFor(Site site) {
        return maps.computeIfAbsent(site.tmxRelative, this::load);
    }

    private TiledMap load(String relative) {
        FileHandle fh = new FileHandle(mapsRoot.resolve(relative).toFile());
        // Default flipY=true: getCell(x, 0) is the TMX south / bottom row — matches
        // OrthogonalTiledMapRenderer and site.y as the stamp's southern edge.
        return loader.load(fh.path());
    }

    @Override
    public void dispose() {
        for (OrthogonalTiledMapRenderer r : renderers.values()) {
            r.dispose();
        }
        for (TiledMap m : maps.values()) {
            m.dispose();
        }
        renderers.clear();
        maps.clear();
    }
}
