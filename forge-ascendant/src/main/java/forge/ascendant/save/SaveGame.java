package forge.ascendant.save;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SaveGame {
    public static final int VERSION = 1;

    public final int version;
    public final long seed;
    public final String profileId;
    public final float playerX;
    public final float playerY;
    public final byte[] explored; // bitset, optional

    public SaveGame(int version, long seed, String profileId, float playerX, float playerY, byte[] explored) {
        this.version = version;
        this.seed = seed;
        this.profileId = profileId;
        this.playerX = playerX;
        this.playerY = playerY;
        this.explored = explored;
    }

    public static void write(Path path, SaveGame save) throws IOException {
        JsonObject o = new JsonObject();
        o.addProperty("version", save.version);
        o.addProperty("seed", save.seed);
        o.addProperty("profileId", save.profileId);
        o.addProperty("playerX", save.playerX);
        o.addProperty("playerY", save.playerY);
        if (save.explored != null) {
            o.addProperty("exploredB64", java.util.Base64.getEncoder().encodeToString(save.explored));
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(o), StandardCharsets.UTF_8);
    }

    public static SaveGame read(Path path) throws IOException {
        JsonObject o = new Gson().fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
        byte[] explored = null;
        if (o.has("exploredB64")) {
            explored = java.util.Base64.getDecoder().decode(o.get("exploredB64").getAsString());
        }
        return new SaveGame(
                o.get("version").getAsInt(),
                o.get("seed").getAsLong(),
                o.get("profileId").getAsString(),
                o.get("playerX").getAsFloat(),
                o.get("playerY").getAsFloat(),
                explored);
    }
}
