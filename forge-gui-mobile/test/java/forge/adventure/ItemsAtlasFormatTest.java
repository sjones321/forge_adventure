package forge.adventure;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validates libGDX-style {@code .atlas} text so region headers are never glued to the
 * previous {@code size:} line (which throws NumberFormatException at load time).
 */
public class ItemsAtlasFormatTest {

    private static final Pattern XY = Pattern.compile("^\\s*xy:\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*$");
    private static final Pattern SIZE = Pattern.compile("^\\s*size:\\s*(-?\\d+)\\s*,\\s*(-?\\d+)\\s*$");

    @Test
    public void stockItemsAtlasParsesCleanly() throws IOException {
        Path atlas = resolveRes("forge-gui/res/adventure/common/sprites/items.atlas");
        Assert.assertTrue(Files.isRegularFile(atlas), "missing " + atlas);
        ParseResult r = parseAtlas(atlas);
        Assert.assertFalse(r.regions.isEmpty(), "expected regions in items.atlas");
        Assert.assertTrue(r.regions.contains("GoldCoin") || r.regions.contains("Gold")
                || r.regions.stream().anyMatch(n -> n.startsWith("Gold")),
                "expected a Gold* region");
        // No glued "size:16,16Mana" style tokens.
        Assert.assertTrue(r.gluedLines.isEmpty(), "glued atlas lines: " + r.gluedLines);
    }

    @Test
    public void ascendantItemsAtlasHasToolAndDustRegions() throws IOException {
        Path atlas = resolveRes("forge-gui/res/adventure/common/sprites/ascendant_items.atlas");
        Assert.assertTrue(Files.isRegularFile(atlas), "missing " + atlas);
        ParseResult r = parseAtlas(atlas);
        Assert.assertTrue(r.gluedLines.isEmpty(), "glued atlas lines: " + r.gluedLines);
        for (String need : new String[] {
                "ToolPickaxe", "ToolAxe", "ToolHammer", "ToolSickle",
                "ToolBucket", "ToolBucketFull",
                "DustCommon", "DustUncommon", "DustRare", "DustMythic"
        }) {
            Assert.assertTrue(r.regions.contains(need), "missing region " + need);
        }
        // Ascendant atlas must not redefine a second Mana that collides with stock.
        Assert.assertFalse(r.regions.contains("Mana"), "ascendant atlas should not add Mana");
    }

    private static Path resolveRes(String relative) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path direct = cwd.resolve(relative);
        if (Files.isRegularFile(direct))
            return direct;
        // Running from a module directory (forge-gui-mobile).
        Path up = cwd.resolve("..").resolve(relative).normalize();
        if (Files.isRegularFile(up))
            return up;
        return direct;
    }

    private static ParseResult parseAtlas(Path atlas) throws IOException {
        ParseResult out = new ParseResult();
        List<String> lines = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(atlas, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null)
                lines.add(line);
        }
        // Skip page header (image name + size/format/filter/repeat).
        int i = 0;
        if (i < lines.size() && !lines.get(i).isEmpty() && !lines.get(i).startsWith(" "))
            i++; // page image
        while (i < lines.size()) {
            String raw = lines.get(i);
            String t = raw.trim();
            if (t.isEmpty()) {
                i++;
                continue;
            }
            if (t.startsWith("size:") || t.startsWith("format:") || t.startsWith("filter:")
                    || t.startsWith("repeat:")) {
                // Page metadata — size: here is page size, not region size.
                if (t.toLowerCase(Locale.ROOT).matches("size:\\s*\\d+\\s*,\\s*\\d+\\S+"))
                    out.gluedLines.add(raw);
                i++;
                continue;
            }
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                // Region property.
                if (t.startsWith("xy:")) {
                    Matcher m = XY.matcher(raw);
                    Assert.assertTrue(m.matches(), "bad xy line in " + atlas + ": " + raw);
                } else if (t.startsWith("size:")) {
                    Matcher m = SIZE.matcher(raw);
                    if (!m.matches()) {
                        // Classic glue bug: "size:16,16Mana"
                        out.gluedLines.add(raw);
                        Assert.fail("region size line must be 'size: W, H' — got: " + raw);
                    }
                }
                i++;
                continue;
            }
            // Region name (must not contain commas / look like a size tail).
            if (t.matches(".*\\dMana$") || t.contains(","))
                out.gluedLines.add(raw);
            out.regions.add(t);
            i++;
        }
        return out;
    }

    private static final class ParseResult {
        final Set<String> regions = new HashSet<>();
        final List<String> gluedLines = new ArrayList<>();
    }
}
