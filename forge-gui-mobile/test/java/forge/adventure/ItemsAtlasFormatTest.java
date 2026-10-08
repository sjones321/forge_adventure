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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates libGDX-style {@code .atlas} text so region headers are never glued to the
 * previous {@code size:} line (which throws NumberFormatException at load time).
 */
public class ItemsAtlasFormatTest {

    /** Valid page or region size — digits only after the pair (rejects size:16,16Mana). */
    private static final Pattern SIZE_OK = Pattern.compile("^\\s*size:\\s*\\d+\\s*,\\s*\\d+\\s*$");
    private static final Pattern XY_OK = Pattern.compile("^\\s*xy:\\s*-?\\d+\\s*,\\s*-?\\d+\\s*$");

    @Test
    public void stockItemsAtlasParsesCleanly() throws IOException {
        Path atlas = resolveRes("forge-gui/res/adventure/common/sprites/items.atlas");
        Assert.assertTrue(Files.isRegularFile(atlas), "missing " + atlas);
        ParseResult r = parseAtlas(atlas);
        Assert.assertFalse(r.regions.isEmpty(), "expected regions in items.atlas");
        Assert.assertTrue(r.regions.stream().anyMatch(n -> n.trim().startsWith("Gold")),
                "expected a Gold* region");
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
        Assert.assertFalse(r.regions.contains("Mana"), "ascendant atlas should not add Mana");
    }

    @Test
    public void detectsGluedSizeAndRegionName() {
        // Regression for the NumberFormatException bug: "size:16,16Mana"
        Assert.assertFalse(SIZE_OK.matcher("  size:16,16Mana").matches());
        Assert.assertFalse(SIZE_OK.matcher("size: 16, 16Mana").matches());
        Assert.assertTrue(SIZE_OK.matcher("  size: 16, 16").matches());
        Assert.assertTrue(SIZE_OK.matcher("size:80,32").matches());
    }

    private static Path resolveRes(String relative) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path direct = cwd.resolve(relative);
        if (Files.isRegularFile(direct))
            return direct;
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
        boolean sawPageImage = false;
        for (String raw : lines) {
            String t = raw.trim();
            if (t.isEmpty())
                continue;
            if (!sawPageImage && !raw.startsWith(" ") && !raw.startsWith("\t")
                    && !t.contains(":")) {
                sawPageImage = true; // first bare token is the PNG name
                continue;
            }
            if (t.startsWith("format:") || t.startsWith("filter:") || t.startsWith("repeat:"))
                continue;
            if (t.startsWith("size:")) {
                if (!SIZE_OK.matcher(raw).matches())
                    out.gluedLines.add(raw);
                continue;
            }
            if (t.startsWith("xy:")) {
                if (!XY_OK.matcher(raw).matches())
                    out.gluedLines.add(raw);
                continue;
            }
            if (raw.startsWith(" ") || raw.startsWith("\t"))
                continue; // other region props (rotate, orig, offset, index)
            // Region name on its own line — reject names glued onto prior tokens.
            if (t.contains(",") || t.matches("(?i).*\\dmana$"))
                out.gluedLines.add(raw);
            out.regions.add(t);
        }
        return out;
    }

    private static final class ParseResult {
        final Set<String> regions = new HashSet<>();
        final List<String> gluedLines = new ArrayList<>();
    }
}
