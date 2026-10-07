package forge.adventure.util;

import forge.adventure.player.AdventurePlayer;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writes the Adventure collection and decks to plain files on every save, so they can be loaded
 * into deck-building sites (Moxfield, Archidekt, ManaBox, ...) with "only cards I own" filtering.
 * Decks built there can be pasted back through the deck editor's Import.
 *
 * Output folder: Forge user folder / adventure / exports
 *   collection.csv  - Moxfield collection CSV layout (re-import with "replace" to update)
 *   collection.txt  - "4 Lightning Bolt (M10) 146" lines
 *   decks/NN - name.txt - Arena-style deck lists
 */
public final class CollectionExporter {
    private CollectionExporter() {
    }

    public static File exportDir() {
        return new File(ForgeConstants.USER_DIR, "adventure" + File.separator + "exports");
    }

    public static void export(AdventurePlayer player) {
        try {
            File dir = exportDir();
            File decksDir = new File(dir, "decks");
            decksDir.mkdirs();

            CardPool cards = player.getCards();
            try (PrintWriter csv = new PrintWriter(new File(dir, "collection.csv"), StandardCharsets.UTF_8);
                 PrintWriter txt = new PrintWriter(new File(dir, "collection.txt"), StandardCharsets.UTF_8)) {
                // Moxfield's own collection export layout, which its importer reads directly
                csv.println("\"Count\",\"Tradelist Count\",\"Name\",\"Edition\",\"Condition\",\"Language\",\"Foil\",\"Tags\",\"Last Modified\",\"Collector Number\",\"Alter\",\"Proxy\",\"Purchase Price\"");
                for (Map.Entry<PaperCard, Integer> e : cards) {
                    PaperCard pc = e.getKey();
                    csv.println(String.join(",", q(String.valueOf(e.getValue())), q("0"), q(pc.getName()),
                            q(pc.getEdition().toLowerCase()), q("Near Mint"), q("English"), q(pc.isFoil() ? "foil" : ""),
                            q(""), q(""), q(pc.getCollectorNumber() == null ? "" : pc.getCollectorNumber()),
                            q("False"), q("False"), q("")));
                    txt.println(line(e.getValue(), pc));
                }
            }

            File[] old = decksDir.listFiles((d, n) -> n.endsWith(".txt"));
            if (old != null)
                for (File f : old)
                    f.delete();
            for (int i = 0; i < player.getDeckCount(); i++) {
                Deck deck = player.getDeck(i);
                if (deck == null || deck.getMain().isEmpty())
                    continue;
                String name = deck.getName() == null || deck.getName().isBlank() ? "Deck" : deck.getName();
                File f = new File(decksDir, String.format("%02d - %s.txt", i + 1, name.replaceAll("[\\\\/:*?\"<>|]", "_")));
                try (PrintWriter out = new PrintWriter(f, StandardCharsets.UTF_8)) {
                    if (deck.has(DeckSection.Commander)) {
                        out.println("Commander");
                        for (Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Commander))
                            out.println(line(e.getValue(), e.getKey()));
                        out.println();
                    }
                    out.println("Deck");
                    for (Map.Entry<PaperCard, Integer> e : deck.getMain())
                        out.println(line(e.getValue(), e.getKey()));
                    if (deck.has(DeckSection.Sideboard) && !deck.get(DeckSection.Sideboard).isEmpty()) {
                        out.println();
                        out.println("Sideboard");
                        for (Map.Entry<PaperCard, Integer> e : deck.get(DeckSection.Sideboard))
                            out.println(line(e.getValue(), e.getKey()));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Collection export failed: " + e);
        }
    }

    private static String line(int count, PaperCard pc) {
        String cn = pc.getCollectorNumber();
        return count + " " + pc.getName() + " (" + pc.getEdition() + ")" + (cn == null || cn.isBlank() ? "" : " " + cn);
    }

    private static String q(String s) {
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
