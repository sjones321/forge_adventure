package forge.ascendant.world;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight TMX reader for stamping towns into the plane.
 * <p>
 * Collision: non-zero gids on the Foreground layer become {@link Terrain#HOUSE}
 * (blocked). AboveSprites (roofs, sails, canopies, flowers) do not block so you
 * can walk under overhangs. Ground/Background non-zero becomes {@link Terrain#TOWN}.
 */
public final class TmxStamp {
    public final int width;
    public final int height;
    public final byte[] terrain; // row-major, TMX file order (row 0 = top)

    private TmxStamp(int width, int height, byte[] terrain) {
        this.width = width;
        this.height = height;
        this.terrain = terrain;
    }

    public static TmxStamp load(Path tmxPath, int skipRowsTop) throws IOException {
        Document doc;
        try (InputStream in = Files.newInputStream(tmxPath)) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setValidating(false);
            // Town maps are local assets; disable external DTD/schema resolution.
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            doc = factory.newDocumentBuilder().parse(in);
        } catch (Exception e) {
            throw new IOException("Failed to parse TMX " + tmxPath + ": " + e.getMessage(), e);
        }

        Element map = doc.getDocumentElement();
        int width = Integer.parseInt(map.getAttribute("width"));
        int height = Integer.parseInt(map.getAttribute("height"));
        byte[] out = new byte[width * height];

        List<Layer> layers = parseLayers(map, width, height);
        for (Layer layer : layers) {
            boolean blocking = "Foreground".equals(layer.name);
            boolean floor = "Ground".equals(layer.name) || "Background".equals(layer.name);
            if (!blocking && !floor) {
                // AboveSprites and unknown layers: visual only for collision stamp
                continue;
            }
            for (int i = 0; i < layer.gids.length; i++) {
                if (layer.gids[i] == 0) {
                    continue;
                }
                if (blocking) {
                    out[i] = Terrain.HOUSE;
                } else if (out[i] != Terrain.HOUSE) {
                    out[i] = Terrain.TOWN;
                }
            }
        }

        if (skipRowsTop > 0) {
            int newH = height - skipRowsTop;
            byte[] trimmed = new byte[width * newH];
            System.arraycopy(out, skipRowsTop * width, trimmed, 0, trimmed.length);
            return new TmxStamp(width, newH, trimmed);
        }
        return new TmxStamp(width, height, out);
    }

    private static List<Layer> parseLayers(Element map, int width, int height) {
        List<Layer> layers = new ArrayList<>();
        NodeList layerNodes = map.getElementsByTagName("layer");
        for (int i = 0; i < layerNodes.getLength(); i++) {
            Element layerEl = (Element) layerNodes.item(i);
            String name = layerEl.getAttribute("name");
            NodeList dataNodes = layerEl.getElementsByTagName("data");
            if (dataNodes.getLength() == 0) {
                continue;
            }
            Element data = (Element) dataNodes.item(0);
            String encoding = data.getAttribute("encoding");
            if (!"csv".equals(encoding)) {
                throw new IllegalArgumentException("Only CSV TMX layers supported, got: " + encoding);
            }
            String csv = data.getTextContent().trim();
            int[] gids = new int[width * height];
            int idx = 0;
            for (String part : csv.split("[,\\s]+")) {
                if (part.isEmpty()) {
                    continue;
                }
                if (idx < gids.length) {
                    gids[idx++] = (int) (Long.parseLong(part) & 0x1FFFFFFFL);
                }
            }
            layers.add(new Layer(name, gids));
        }
        return layers;
    }

    private static final class Layer {
        final String name;
        final int[] gids;

        Layer(String name, int[] gids) {
            this.name = name;
            this.gids = gids;
        }
    }
}
