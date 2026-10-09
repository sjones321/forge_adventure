package forge.adventure.data;

/**
 * EN1 Bellwarden Standard recipe for an enemy theme.
 * Filled at runtime from the current Standard window so fixed Standard lists
 * do not go stale on rotation.
 */
public class EnemyThemeRecipeData {
    /** Target main-deck size (Ascendant Standard uses 60). */
    public int count = 60;
    /** Color names for the template ({@code "Blue"}, {@code "Red"}, …). */
    public String[] colors;
    /** Creature type / tribe to bias toward (e.g. {@code "Merfolk"}). */
    public String tribe;
    /** Fraction of rares/mythics in the generated pool (0–1). */
    public float rares = 0.15f;
    /** Preferred card names to include when legal in the current window. */
    public String[] keyCards = new String[0];
    /** Optional mechanic keywords/phrases for card-text bias. */
    public String[] mechanics = new String[0];
}
