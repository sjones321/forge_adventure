package forge.adventure;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every Shandalar Ascendant point of interest must name a sprite that exists in its atlas.
 * A missing one (PlanarGate pointed at MageTowerBlack in buildings.atlas) blacked out the overworld.
 */
public class PoiSpriteAtlasTest {
    private static final Pattern POI = Pattern.compile(
            "\"spriteAtlas\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"sprite\"\\s*:\\s*\"([^\"]+)\"");

    private static Path res() {
        for (String p : new String[]{"forge-gui/res/adventure", "../forge-gui/res/adventure"}) {
            Path path = Paths.get(p);
            if (Files.isDirectory(path))
                return path;
        }
        throw new IllegalStateException("adventure res folder not found");
    }

    private static Set<String> regions(Path atlas) throws Exception {
        Set<String> out = new HashSet<>();
        for (String line : Files.readAllLines(atlas, StandardCharsets.UTF_8)) {
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0)) && !line.contains(":"))
                out.add(line.trim());
        }
        return out;
    }

    @Test
    public void ascendantPoiSpritesExistInTheirAtlases() throws Exception {
        Path plane = res().resolve("Shandalar Ascendant");
        String json = Files.readString(plane.resolve("world/points_of_interest.json"), StandardCharsets.UTF_8);
        Matcher m = POI.matcher(json);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        while (m.find()) {
            // Same lookup as Config.getFile: the plane folder first, then common resources.
            Path atlas = plane.resolve(m.group(1)).normalize();
            if (!Files.exists(atlas))
                atlas = res().resolve("common").resolve(m.group(1)).normalize();
            Assert.assertTrue(Files.exists(atlas), "missing atlas " + m.group(1));
            if (!regions(atlas).contains(m.group(2)))
                missing.add(m.group(2) + " in " + m.group(1));
            checked++;
        }
        Assert.assertTrue(checked > 10, "expected to check many POIs, checked " + checked);
        Assert.assertTrue(missing.isEmpty(), "POI sprites missing from their atlas: " + missing);
    }
}
