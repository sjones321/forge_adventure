package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import forge.adventure.data.ConfigData;
import forge.adventure.data.PointOfInterestData;
import forge.adventure.data.ShopData;
import forge.adventure.data.TownsfolkData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ascendant LT1 helpers for hand-made towns: dialog file refs, fallback entry spawn,
 * and headless validation of POI / TMX / shop / townsfolk data.
 *
 * <p>Does not depend on specific tile art or layer contents — only documented object types.
 */
public final class LivingTownMapSupport {
    // Prefer self-closing first: a greedy `[^>]*` before `>` would treat `...16"/>`
    // as an open tag and swallow later objects until `</object>`.
    private static final Pattern OBJECT_TAG = Pattern.compile(
            "<object\\b([^>]*?)\\s*/>|<object\\b([^>]*)>(.*?)</object>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR = Pattern.compile("(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern PROP = Pattern.compile(
            "<property\\b[^>]*\\bname\\s*=\\s*\"([^\"]+)\"[^>]*\\bvalue\\s*=\\s*\"([^\"]*)\"[^>]*/?>",
            Pattern.CASE_INSENSITIVE);

    private LivingTownMapSupport() {
    }

    /** True when {@code dialog} / dialogFile value should be read as a plane-relative JSON path. */
    public static boolean isDialogFileRef(String value) {
        if (value == null) {
            return false;
        }
        String s = value.trim();
        if (s.isEmpty() || s.startsWith("[")) {
            return false;
        }
        return s.endsWith(".json") || s.startsWith("world/");
    }

    /**
     * Fallback player position in map pixels when no entry object exists.
     * Fractions come from {@link ConfigData#lt1FallbackEntryXFraction} /
     * {@link ConfigData#lt1FallbackEntryYFraction}.
     */
    public static float[] fallbackEntryPixels(float mapPixelWidth, float mapPixelHeight,
            float xFraction, float yFraction) {
        float w = Math.max(0f, mapPixelWidth);
        float h = Math.max(0f, mapPixelHeight);
        float xf = clamp01(xFraction);
        float yf = clamp01(yFraction);
        return new float[]{w * xf, h * yf};
    }

    public static String missingEntryWarning(String mapPath) {
        return "LT1: map has no entry object (no spawn and no way out); using fallback spawn. Map="
                + (mapPath == null || mapPath.isEmpty() ? "<unknown>" : mapPath);
    }

    public static String missingTownsfolkWarning(String townsfolkId) {
        return "LT1: unknown or missing townsfolkId \""
                + (townsfolkId == null ? "" : townsfolkId)
                + "\" — skipping NPC.";
    }

    public static String emptyShopWarning(int objectId) {
        return "LT1: shop object id=" + objectId + " matched no ShopData — skipping.";
    }

    public static boolean lt1Active(ConfigData cfg) {
        return cfg != null && cfg.ascendantRules && cfg.lt1LivingTowns;
    }

    public static boolean shouldWarnMissing(ConfigData cfg) {
        return lt1Active(cfg) && cfg.lt1WarnMissingMapObjects;
    }

    /** Resolve a POI {@code map} field against the plane folder, then common. */
    public static Path resolvePoiMapFile(Path planeDir, Path commonAdventureDir, String mapField) {
        if (mapField == null || mapField.isEmpty()) {
            return null;
        }
        Path fromPlane = planeDir.resolve(mapField).normalize();
        if (Files.isRegularFile(fromPlane)) {
            return fromPlane;
        }
        // "../common/maps/..." from plane/world → adventure/common/...
        String cleaned = mapField.replace('\\', '/');
        int commonIdx = cleaned.indexOf("/common/");
        if (commonIdx >= 0) {
            Path fromCommon = commonAdventureDir.resolve(cleaned.substring(commonIdx + "/common/".length())).normalize();
            if (Files.isRegularFile(fromCommon)) {
                return fromCommon;
            }
        }
        Path direct = commonAdventureDir.resolve(cleaned).normalize();
        return Files.isRegularFile(direct) ? direct : fromPlane;
    }

    /** Headless load of {@code points_of_interest.json}. */
    public static Array<PointOfInterestData> loadPoisFromPath(Path path) throws Exception {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        Array<PointOfInterestData> loaded = new Json().fromJson(Array.class, PointOfInterestData.class, json);
        return loaded != null ? loaded : new Array<>();
    }

    public static PointOfInterestData findPoi(Array<PointOfInterestData> pois, String poiName) {
        if (pois == null || poiName == null) {
            return null;
        }
        for (PointOfInterestData p : new Array.ArrayIterator<>(pois)) {
            if (p != null && poiName.equals(p.name)) {
                return p;
            }
        }
        return null;
    }

    /** Convenience map of key fields for assertions / logging. */
    public static Map<String, String> poiFields(PointOfInterestData poi) {
        Map<String, String> out = new LinkedHashMap<>();
        if (poi == null) {
            return out;
        }
        out.put("name", poi.name);
        out.put("displayName", poi.displayName);
        out.put("type", poi.type);
        out.put("map", poi.map);
        out.put("sprite", poi.sprite);
        out.put("spriteAtlas", poi.spriteAtlas);
        out.put("count", String.valueOf(poi.count));
        out.put("radiusFactor", String.valueOf(poi.radiusFactor));
        out.put("offsetX", String.valueOf(poi.offsetX));
        out.put("offsetY", String.valueOf(poi.offsetY));
        return out;
    }

    public static List<TmxObjectInfo> parseTmxObjects(Path tmxPath) throws Exception {
        String xml = Files.readString(tmxPath, StandardCharsets.UTF_8);
        return parseTmxObjectsXml(xml);
    }

    public static List<TmxObjectInfo> parseTmxObjectsXml(String xml) {
        List<TmxObjectInfo> list = new ArrayList<>();
        if (xml == null || xml.isEmpty()) {
            return list;
        }
        Matcher m = OBJECT_TAG.matcher(xml);
        while (m.find()) {
            String attrs = m.group(1) != null ? m.group(1) : m.group(2);
            String body = m.group(1) != null ? "" : (m.group(3) != null ? m.group(3) : "");
            Map<String, String> attrMap = attrs(attrs);
            Map<String, String> props = props(body);
            TmxObjectInfo info = new TmxObjectInfo();
            info.id = attrMap.getOrDefault("id", "");
            info.template = attrMap.getOrDefault("template", "");
            info.type = firstNonEmpty(attrMap.get("type"), attrMap.get("class"), props.get("type"),
                    typeFromTemplate(info.template));
            info.x = parseFloat(attrMap.get("x"), 0f);
            info.y = parseFloat(attrMap.get("y"), 0f);
            info.properties.putAll(props);
            list.add(info);
        }
        return list;
    }

    public static boolean hasObjectType(List<TmxObjectInfo> objects, String type) {
        if (objects == null || type == null) {
            return false;
        }
        for (TmxObjectInfo o : objects) {
            if (o != null && type.equalsIgnoreCase(o.type)) {
                return true;
            }
        }
        return false;
    }

    /** Collect shop names referenced by TMX shop-list properties. */
    public static Set<String> shopNamesFromObjects(List<TmxObjectInfo> objects) {
        Set<String> names = new HashSet<>();
        if (objects == null) {
            return names;
        }
        for (TmxObjectInfo o : objects) {
            if (o == null || !"shop".equalsIgnoreCase(o.type)) {
                continue;
            }
            for (String key : new String[]{"commonShopList", "uncommonShopList", "rareShopList",
                    "mythicShopList", "shopList"}) {
                String csv = o.properties.get(key);
                if (csv == null || csv.isBlank()) {
                    continue;
                }
                for (String part : csv.split(",")) {
                    String n = part.trim();
                    if (!n.isEmpty()) {
                        names.add(n);
                    }
                }
            }
        }
        return names;
    }

    public static Set<String> shopDataNames(Iterable<ShopData> shops) {
        Set<String> names = new HashSet<>();
        if (shops == null) {
            return names;
        }
        for (ShopData s : shops) {
            if (s != null && s.name != null) {
                names.add(s.name);
            }
        }
        return names;
    }

    public static List<String> validateTownsfolk(Iterable<TownsfolkData> folk, Path planeDir) {
        List<String> errors = new ArrayList<>();
        if (folk == null) {
            errors.add("townsfolk list is null");
            return errors;
        }
        Set<String> ids = new HashSet<>();
        for (TownsfolkData t : folk) {
            if (t == null) {
                errors.add("null townsfolk entry");
                continue;
            }
            if (t.id == null || t.id.isBlank()) {
                errors.add("townsfolk missing id");
                continue;
            }
            if (!ids.add(t.id)) {
                errors.add("duplicate townsfolk id: " + t.id);
            }
            if (t.name == null || t.name.isBlank()) {
                errors.add(t.id + ": missing name");
            }
            if (t.dialogFile == null || t.dialogFile.isBlank()) {
                errors.add(t.id + ": missing dialogFile");
            } else if (planeDir != null) {
                Path dialog = planeDir.resolve(t.dialogFile).normalize();
                if (!Files.isRegularFile(dialog)) {
                    errors.add(t.id + ": dialogFile not found: " + t.dialogFile);
                } else {
                    try {
                        String body = Files.readString(dialog, StandardCharsets.UTF_8).trim();
                        if (!body.startsWith("[")) {
                            errors.add(t.id + ": dialogFile is not a JSON array: " + t.dialogFile);
                        }
                    } catch (Exception e) {
                        errors.add(t.id + ": cannot read dialogFile: " + e.getMessage());
                    }
                }
            }
            if (t.sprite == null || t.sprite.isBlank()) {
                errors.add(t.id + ": missing sprite");
            }
        }
        return errors;
    }

    private static float clamp01(float v) {
        if (v < 0f) {
            return 0f;
        }
        if (v > 1f) {
            return 1f;
        }
        return v;
    }

    private static Map<String, String> attrs(String attrBlob) {
        Map<String, String> map = new LinkedHashMap<>();
        if (attrBlob == null) {
            return map;
        }
        Matcher m = ATTR.matcher(attrBlob);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    private static Map<String, String> props(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        if (body == null) {
            return map;
        }
        Matcher m = PROP.matcher(body);
        while (m.find()) {
            map.put(m.group(1), m.group(2));
        }
        return map;
    }

    private static String typeFromTemplate(String template) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        String t = template.replace('\\', '/');
        int slash = t.lastIndexOf('/');
        String file = slash >= 0 ? t.substring(slash + 1) : t;
        String base = file.endsWith(".tx") ? file.substring(0, file.length() - 3) : file;
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.startsWith("entry")) {
            return "entry";
        }
        if (lower.startsWith("door")) {
            return "entry";
        }
        if (lower.equals("rotatingshop")) {
            return "Rotating";
        }
        return lower;
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return "";
        }
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private static float parseFloat(String s, float def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static final class TmxObjectInfo {
        public String id = "";
        public String type = "";
        public String template = "";
        public float x;
        public float y;
        public final Map<String, String> properties = new LinkedHashMap<>();
    }
}
