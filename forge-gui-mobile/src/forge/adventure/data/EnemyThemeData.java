package forge.adventure.data;

/**
 * One EN1 enemy theme: tags that match {@link EnemyData#questTags}, colors,
 * creature types, preferred commanders, and a Bellwarden Standard recipe.
 * Fixed Historic / Pauper / Commander lists live under
 * {@code decks/enemy/<id>/<format>_N.dck}.
 */
public class EnemyThemeData {
    public String id;
    /** Quest tags / creature-type labels that may pick this theme (e.g. Merfolk). */
    public String[] tags = new String[0];
    /** Color names for display and deck building ({@code "blue"}, {@code "red"}, …). */
    public String[] colors = new String[0];
    /** Creature types to bias tribal picks toward. */
    public String[] creatureTypes = new String[0];
    /** Preferred legendary commanders for the Commander list (first legal wins). */
    public String[] preferredCommanders = new String[0];
    /** Signature cards for the theme (documentation + Standard recipe seed). */
    public String[] keyCards = new String[0];
    /** Mechanic notes for the theme (documentation + Standard recipe bias). */
    public String[] mechanics = new String[0];
    /** Bellwarden Standard recipe filled from the current window at runtime. */
    public EnemyThemeRecipeData standardRecipe;
}
