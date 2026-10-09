package forge.adventure.util;

/**
 * Defines for the hard coded paths
 */
public class Paths {
    public static final String ENEMIES = "world/enemies.json";
    public static final String SHOPS = "world/shops.json";
    public static final String WORLD = "world/world.json";
    public static final String HEROES = "world/heroes.json";
    public static final String POINTS_OF_INTEREST = "world/points_of_interest.json";
    public static final String ITEMS = "world/items.json";
    public static final String MATERIALS = "world/materials.json";
    public static final String MASTERY_SURGE = "world/mastery_surge.json";
    public static final String ENEMY_MATERIAL_DROPS = "world/enemy_material_drops.json";
    public static final String RECIPES = "world/recipes.json";
    public static final String GATHERING_METHODS = "world/gathering_methods.json";
    public static final String SKILL_TREES = "world/skill_trees.json";
    /** FT1 fortress structure catalog. */
    public static final String STRUCTURES_FORTRESS = "world/structures_fortress.json";
    public static final String GYMS = "world/gyms.json";
    /** EN1 enemy themes (tags → themes → format decks / Standard recipes). */
    public static final String ENEMY_THEMES = "world/enemy_themes.json";
    public static final String QUESTS = "world/quests.json";
    public static final String SKIN = "skin/ui_skin.json";
    public static final String ITEMS_EQUIP = "skin/equip.png";
    public static final String ITEMS_UNUSABLE = "skin/unusable.png";
    public static final String ITEMS_ATLAS = "sprites/items.atlas";
    /**
     * Ascendant-only tool / dust icons (Kenney Tiny Town + tinted Mana).
     * Loaded only when {@link Config#ascendant()} — stock {@link #ITEMS_ATLAS} stays untouched.
     */
    public static final String ASCENDANT_ITEMS_ATLAS = "sprites/ascendant_items.atlas";
    public static final String PIXELMANA_ATLAS = "sprites/pixelmana.atlas";
    public static final String KEYS_ATLAS = "skin/keys.atlas";
    public static final String COLOR_FRAME_ATLAS = "ui/color_frames.atlas";
    public static final String ARENA_ATLAS = "ui/arena.atlas";
    public static final String MAP_MARKER = "sprites/map_marker.atlas";
    
    
    public static final String EFFECT_HEAL = "particle_effects/heal.p";
    public static final String EFFECT_KILL = "particle_effects/killed.p";
    public static final String TRIGGER_KILL = "particle_effects/kill.p";
    public static final String EFFECT_HIDE = "particle_effects/hide.p";
    public static final String EFFECT_SPRINT = "particle_effects/sprint.p";
    public static final String EFFECT_FLY = "particle_effects/fly.p";
    public static final String EFFECT_TELEPORT = "particle_effects/teleport.p";
    public static final String EFFECT_BLOOD = "particle_effects/blood.p";
    public static final String EFFECT_SPARKS = "particle_effects/sparks.p";
    /** Ascendant ash-vent ambient (Particle Park Smoke, relative images, low emission). */
    public static final String EFFECT_ASH_VENT_SMOKE = "particle_effects/ash_vent_smoke.p";
    /** Ascendant ash-vent embers (Particle Park Flame Pixel, scaled small). */
    public static final String EFFECT_ASH_VENT_EMBERS = "particle_effects/ash_vent_embers.p";
    /** Ascendant water-node sparkle (Particle Park Starlight, occasional). */
    public static final String EFFECT_WATER_SPARKLE = "particle_effects/water_sparkle.p";
    /** Gathering node art sheet (Steve's ore row = atlas regions ore_iron…ore_rune). */
    public static final String RESOURCE_NODES_ATLAS = "maps/tileset/resource_nodes.atlas";
    public static final String CARD_PRICES = "world/cardprices.txt";
    public static final String CUSTOM_CARDS = "custom_cards";
    public static final String CUSTOM_CARDS_PICS = "custom_card_pics";
}
