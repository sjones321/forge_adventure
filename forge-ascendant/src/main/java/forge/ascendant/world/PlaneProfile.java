package forge.ascendant.world;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class PlaneProfile {
    public final String id;
    public final String name;
    public final int size;
    public final int chunk;
    public final int tileSize;
    public final JsonObject layout;
    public final JsonObject homeTown;
    public final JsonObject plainsTown;
    public final JsonObject terrain;
    public final JsonObject sea;
    public final Map<String, Float> biomeDensity;
    public final int roadWidth;

    private PlaneProfile(JsonObject root) {
        this.id = root.get("id").getAsString();
        this.name = root.get("name").getAsString();
        this.size = root.get("size").getAsInt();
        this.chunk = root.get("chunk").getAsInt();
        this.tileSize = root.has("tileSize") ? root.get("tileSize").getAsInt() : 16;
        this.layout = root.getAsJsonObject("layout");
        this.homeTown = root.getAsJsonObject("home_town");
        this.plainsTown = root.getAsJsonObject("plains_town");
        this.terrain = root.getAsJsonObject("terrain");
        this.sea = root.getAsJsonObject("sea");
        this.biomeDensity = new HashMap<>();
        JsonObject biomes = root.getAsJsonObject("biomes");
        for (String key : biomes.keySet()) {
            biomeDensity.put(key, biomes.getAsJsonObject(key).get("density").getAsFloat());
        }
        this.roadWidth = root.getAsJsonObject("roads").get("WIDTH").getAsInt();
    }

    public static PlaneProfile loadClasspath(String resourcePath) throws IOException {
        try (InputStream in = PlaneProfile.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing profile resource: " + resourcePath);
            }
            return load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    public static PlaneProfile loadFile(Path path) throws IOException {
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(r);
        }
    }

    private static PlaneProfile load(Reader reader) {
        JsonObject root = new Gson().fromJson(reader, JsonObject.class);
        return new PlaneProfile(root);
    }
}
