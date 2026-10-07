package forge.adventure.data;

import com.badlogic.gdx.utils.ObjectMap;

/**
 * One gym trainer, gym leader tier, Elite Four member, or Champion entry.
 * Deck paths are keyed by run format (Standard / Pauper / Historic / Commander)
 * so package K can add format decks without code changes.
 */
public class GymFighterData {
    public String name;
    public String sprite;
    public int life = 20;
    public String colors = "";
    public int gamesPerMatch = 1;
    public boolean boss = false;
    /** Format name → deck path. Missing formats fall back to {@code Standard}. */
    public ObjectMap<String, String> decks;
    /**
     * Leader/Champion only: badge-count threshold (as string key) → format deck map.
     * The highest threshold ≤ badges held is used. Falls back to {@link #decks} when absent.
     */
    public ObjectMap<String, ObjectMap<String, String>> decksByBadges;
    /** Harder decks used after the badge / League has been cleared once. Same format-key layout. */
    public ObjectMap<String, String> rematchDecks;
}
