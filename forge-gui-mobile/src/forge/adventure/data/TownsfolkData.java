package forge.adventure.data;

/**
 * Ascendant LT1 townsfolk record (loaded from {@code world/townsfolk.json}).
 * Map objects of type {@code townsfolk} reference {@link #id} via {@code townsfolkId}.
 */
public class TownsfolkData {
    public String id;
    public String name;
    public String town;
    public String home;
    public String[] dailySpots = new String[0];
    /** Character sprite atlas path (e.g. {@code sprites/heroes/human_f.atlas}). */
    public String sprite;
    public String personality;
    /** Plane-relative dialog JSON path (e.g. {@code world/dialogs/starter_town/mira.json}). */
    public String dialogFile;
    public String deck = "";
    public String signatureCard = "";
    public String[] giftLikes = new String[0];
    public String[] giftDislikes = new String[0];
}
