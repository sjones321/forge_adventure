package forge.adventure;

import org.testng.Assert;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Every gathered material has its own 16x16 region in sprites/material_icons.atlas, inside the sheet. */
public class MaterialIconsAtlasTest {
    private static final Set<String> GATHERED_FAMILIES = Set.of("logs", "ore", "ash", "sacred_stone", "waters",
            "dead", "plants", "scrap", "gems", "crystal", "pearls", "feathers", "hide", "brine");

    private static Path res() {
        for (String p : new String[]{"forge-gui/res/adventure", "../forge-gui/res/adventure"}) {
            Path path = Paths.get(p);
            if (Files.isDirectory(path))
                return path;
        }
        throw new IllegalStateException("adventure res folder not found");
    }

    @Test
    public void everyGatheredMaterialHasAnIconInsideTheSheet() throws Exception {
        Path sprites = res().resolve("common/sprites");
        List<String> lines = Files.readAllLines(sprites.resolve("material_icons.atlas"), StandardCharsets.UTF_8);
        Assert.assertEquals(lines.get(0).trim(), "material_icons.png");
        BufferedImage sheet = ImageIO.read(sprites.resolve("material_icons.png").toFile());

        Map<String, int[]> regions = new HashMap<>();
        Pattern xy = Pattern.compile("\\s+xy:\\s*(\\d+),\\s*(\\d+)");
        for (int i = 5; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))) {
                Matcher m = xy.matcher(lines.get(i + 1));
                Assert.assertTrue(m.matches(), "xy line after " + line);
                Assert.assertNull(regions.put(line.trim(),
                        new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))}), "duplicate " + line);
            }
        }

        String json = Files.readString(res().resolve("common/world/materials.json"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"[^{}]*?\"family\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        while (m.find()) {
            if (!GATHERED_FAMILIES.contains(m.group(2)))
                continue;
            checked++;
            int[] p = regions.get(m.group(1));
            if (p == null) {
                missing.add(m.group(1));
                continue;
            }
            Assert.assertTrue(p[0] + 16 <= sheet.getWidth() && p[1] + 16 <= sheet.getHeight(), m.group(1) + " outside sheet");
            boolean drawn = false;
            for (int y = p[1]; y < p[1] + 16 && !drawn; y++)
                for (int x = p[0]; x < p[0] + 16 && !drawn; x++)
                    drawn = (sheet.getRGB(x, y) >>> 24) != 0;
            Assert.assertTrue(drawn, m.group(1) + " icon is empty");
        }
        Assert.assertTrue(checked >= 57, "expected the gathered families in materials.json, saw " + checked);
        Assert.assertTrue(missing.isEmpty(), "materials without an icon: " + missing);
    }
}
