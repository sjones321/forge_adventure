package forge.app;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.item.PaperCard;
import forge.itemmanager.SFilterUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Pure layout and scripted-combat math for {@link BattlefieldPrototype}.
 * No libGDX and no Forge rules. Sizes are fractions of window height unless noted.
 */
public final class BattlefieldLayout {
    /** Tall enough that the name on a title-and-art crop reads at a glance. */
    public static final float CARD_HEIGHT_FRAC = 0.28f;
    public static final float OPPONENT_LAND_Y = 0.86f;
    public static final float OPPONENT_CREATURE_Y = 0.67f;
    public static final float PLAYER_CREATURE_Y = 0.42f;
    public static final float PLAYER_LAND_Y = 0.26f;
    /** Pointer at or above this fraction of H is in the opponent's half. The divider is this line. */
    public static final float ATTACK_LINE_FRAC = 0.5f;
    /** Clear split between the two battlefronts. Top is the enemy. Bottom is you. */
    public static final float DIVIDER_FRAC = 0.5f;
    /** Generous snap radius for tremor-friendly drops, as a fraction of H. */
    public static final float SNAP_RADIUS_FRAC = 0.14f;
    public static final float ATTACK_SLIDE_FRAC = 0.05f;
    public static final float ROW_WIDTH_FRAC = 0.50f;
    /** Small Arena-style tilt. Steve leans one way, Ash the other. Not a sideways 90. */
    public static final float TAP_DEG = 15f;
    /**
     * How far a flying creature rests above the ground row, as a share of the tile.
     * Half the old 0.42 lift: still a clear hover, not a second row.
     */
    public static final float FLY_LIFT_FRAC = 0.21f;
    /**
     * Corner depth of a token's top, as a fraction of the cropped tile height.
     * Half the tile width, so the top is a semicircle rather than a rounded rectangle.
     */
    public static final float TOKEN_ARCH_FRAC = 0.57f;
    public static final int SHOCK_DAMAGE = 2;
    /** Side log, clear of the centred rows. Fractions of window width or height. */
    public static final float STACK_COLUMN_X_FRAC = 0.115f;
    public static final float STACK_COLUMN_W_FRAC = 0.20f;
    public static final float STACK_TOP_FRAC = 0.73f;
    public static final float STACK_ROW_FRAC = 0.155f;
    public static final float STACK_ROW_EXAMINED_FRAC = 0.36f;
    /** Thumbnail height as a fraction of a battlefield card. Small enough that the board stays clickable. */
    public static final float STACK_THUMB_FRAC = 0.42f;
    /**
     * The stack showcase used to be a hover-raised plate (up to 0.56 of the window).
     * This keeps a readable full card at a large hand size, about 58% of that plate.
     */
    public static final float STACK_SHOWCASE_SCALE = 0.58f;
    public static final String TARGET_PROMPT = "Choose a target.";

    public enum Side { YOU, OPPONENT }
    /** Lands pack to the back-left. Noncreature tokens sit above his hand on that same left column. Standalone enchantments sit one row toward that player from the creatures, to the right of that player's life. Loose non-token artifacts sit in a small pocket on that same life-right band. Creatures stay in the middle. Planeswalkers sit in the right-side pocket of each half. Battles sit on the gold midline, right of center, on the defending player's side. */
    public enum Row { LAND, TOKEN, PLANESWALKER, BATTLE, OTHER, ENCHANTMENT, CREATURE }
    public enum Kind { LAND, CREATURE, PLANESWALKER, BATTLE, ARTIFACT, ENCHANTMENT, SPELL }
    /** Instants may be cast onto a nonempty stack. Permanents and sorceries wait. */
    public enum Speed { INSTANT, SORCERY }

    /**
     * Forge's match phase strip ({@code PhaseIndicator} / {@code VPhaseIndicator}).
     * Untap is a real phase and is not a button on that strip.
     * Chronological order is Forge's: UP DR M1 BC DA DB FS CD EC M2 ET CL.
     * The painted edge line is that order without Cleanup: UP DR M1 BC DA DB FS CD EC M2 ET.
     * Combat sits between the two mains. There is no extra CB chip. Cleanup stays in
     * turn order and is not a chip. Scripted only: this is not Forge priority.
     * A step yields or stops. Hover does not invent a reason.
     */
    public enum StripPhase {
        UPKEEP("UP", "Upkeep"),
        DRAW("DR", "Draw"),
        MAIN_1("M1", "Main 1"),
        BEGIN_COMBAT("BC", "Begin Combat"),
        DECLARE_ATTACKERS("DA", "Declare Attackers"),
        DECLARE_BLOCKERS("DB", "Declare Blockers"),
        FIRST_STRIKE("FS", "First Strike"),
        COMBAT_DAMAGE("CD", "Combat Damage"),
        END_COMBAT("EC", "End Combat"),
        MAIN_2("M2", "Main 2"),
        END_STEP("ET", "End Step"),
        CLEANUP("CL", "Cleanup");

        /** Short label Forge paints on the strip. */
        public final String label;
        /** Full step name from Forge's English phase strings. */
        public final String fullName;

        StripPhase(String label, String fullName) {
            this.label = label;
            this.fullName = fullName;
        }
    }

    /** Whose turn a strip row belongs to. The board's top half is the enemy. */
    public enum StripOwner { YOU, ENEMY }

    /**
     * Phase chips sit on the opponent's top edge as two short lines around her
     * life/avatar: his chips to the left, hers to the right. Same order on both.
     * Sizes are pixels so a 13px face stays sharp inside the chip.
     */
    public static final float CLUSTER_CHIP_W = 34f;
    public static final float CLUSTER_CHIP_H = 22f;
    public static final float CLUSTER_CHIP_GAP = 3f;
    public static final float CLUSTER_ROW_GAP = 4f;
    public static final float CLUSTER_LIFE_H = 64f;
    public static final float CLUSTER_ZONE_H = 48f;
    public static final float CLUSTER_PAD = 6f;
    public static final float CLUSTER_MARGIN = 10f;
    /** One line of the action column on the right edge. Not under the phase strip. */
    public static final float ACTION_BTN_H = 28f;
    public static final float ACTION_GAP = 4f;
    /** Right-edge action column. The edge strip stops before this band. */
    public static final float ACTION_COL_W = 156f;
    /** Gap from the life box to the first pip, and from the last pip to right chrome. */
    public static final float STRIP_AFTER_LIFE = 6f;
    public static final float STRIP_CHIP_MIN_W = 20f;
    public static final float STRIP_GAP_MIN = 1f;
    /**
     * Air gap from the window's top pixel edge to both phase lines.
     * Flush-ish: closer than the life-box center, not sitting on the pixel edge.
     */
    public static final float STRIP_EDGE_GAP = 8f;
    /** Air between the resting hand's title tops and the bottom of his life box. */
    public static final float LIFE_ABOVE_HAND_GAP = 16f;
    /** Log-tab / Options column on the top-right. Ash's strip stops before this. */
    public static final float SIDE_TAB_W = 84f;
    /** One continuous edge line: UP DR M1 BC DA DB FS CD EC M2 ET. */
    public static final int EDGE_STRIP_CHIPS = 11;
    /** Stand-in for uploading your own sleeve art. Read locally. No file picker. */
    public static final String CUSTOM_SLEEVE_PATH = "forge-gui-mobile-dev/target/custom-sleeve.png";
    /** Last image picked from the sleeve window. One absolute path. Not the real Forge folder. */
    public static final String CUSTOM_SLEEVE_CHOICE = "forge-gui-mobile-dev/target/custom-sleeve-choice.txt";
    /**
     * Optional icons for the grave and exile buttons. PNG with a transparent background.
     * Drawn about 30 pixels tall above the count. Missing files keep the drawn shapes.
     */
    public static final String GRAVESTONE_ART = "forge-gui-mobile-dev/res/battlefield/gravestone.png";
    public static final String EXILE_ART = "forge-gui-mobile-dev/res/battlefield/exile.png";

    /**
     * A flat battlefield fill. Two colors are enough for a gradient.
     * {@code colorA} and {@code colorB} are packed RGB ({@code 0xRRGGBB}).
     */
    public static final class Theme {
        public final String id;
        public final String displayName;
        public final int colorA;
        public final int colorB;
        public final String label;

        public Theme(String id, String displayName, int colorA, int colorB, String label) {
            this.id = id == null ? "" : id;
            this.displayName = displayName == null ? "" : displayName;
            this.colorA = colorA;
            this.colorB = colorB;
            this.label = label == null ? "" : label;
        }
    }

    /** id, display name, two colors, short label. Adding a theme is one line. */
    private static final Theme[] THEMES = {
            new Theme("green-gold", "Green-gold plain", 0x2E5A28, 0xC6A84A, "Calm field"),
            new Theme("black-white", "Marble dusk", 0x1A1A20, 0xE4DCCE, "Black and white"),
            new Theme("black-red", "Ash marsh", 0x161214, 0x6E1E16, "Red and black"),
            new Theme("black-green", "Dusk wood", 0x141814, 0x2A4A28, "Black and green"),
            new Theme("black-blue", "Deep tide", 0x12141C, 0x1A3058, "Blue and black"),
            new Theme("blue-red", "Storm coast", 0x1A3A6E, 0x8C2A18, "Blue and red"),
            new Theme("blue-green", "Reed water", 0x1A4060, 0x2E6234, "Blue and green"),
            new Theme("blue-white", "Sky marble", 0x2A4E86, 0xE7EEF4, "White and blue"),
            new Theme("green-red", "Wild verge", 0x2E5A22, 0x7A2818, "Red and green"),
            new Theme("green-white", "Sun meadow", 0x3E6B32, 0xE8E4D4, "Green and white"),
            new Theme("red-white", "Banner field", 0x8A2418, 0xE8E0D4, "Red and white"),
    };

    public enum WidgetPart { AVATAR_LIFE, GRAVE, EXILE, LIBRARY }

    /**
     * Slots in your action column. The words on them change with the board.
     * Top and bottom are always placed. Cancel is the slot under the bottom button.
     */
    public enum ActionSlot { TOP, BOTTOM, CANCEL }

    /** One button in the action column. Centers are the hit test. */
    public static final class ActionBox {
        public final ActionSlot slot;
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        public ActionBox(ActionSlot slot, float x, float y, float w, float h) {
            this.slot = slot == null ? ActionSlot.TOP : slot;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(float px, float py) {
            return w > 0f && h > 0f && Math.abs(px - x) <= w / 2f && Math.abs(py - y) <= h / 2f;
        }
    }

    /** Room for the avatar and the life number, to the left of the library pile. */
    public static final float CLUSTER_LIFE_W = 128f;
    /** The library is a deck of sleeved cards, tall enough to read the sleeve. */
    public static final float LIBRARY_PILE_H = 112f;
    /**
     * Floating zone grid. Cards are about a resting hand card so the printed title can be read.
     * Chrome is the title, the search box, and two rows of type chips.
     */
    public static final float ZONE_CARD_H = 168f;
    public static final float ZONE_CARD_MIN_H = 112f;
    public static final float ZONE_GRID_GAP = 8f;
    public static final float ZONE_GRID_PAD = 12f;
    public static final float ZONE_GRID_CHROME = 128f;
    public static final int ZONE_GRID_COLS = 4;
    /** Room left inside the panel for the scroll track. */
    public static final float ZONE_SCROLL_W = 14f;
    /** All, then the seven basic types. Index 0 clears the type chip. */
    public static final String[] ZONE_TYPE_CHIPS = {
            "All", "Creature", "Land", "Instant", "Sorcery", "Artifact", "Enchantment", "Planeswalker"
    };

    /** One clickable piece of a player cluster. Centers are the hit test. */
    public static final class WidgetBox {
        public final StripOwner owner;
        public final WidgetPart part;
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        public WidgetBox(StripOwner owner, WidgetPart part, float x, float y, float w, float h) {
            this.owner = owner == null ? StripOwner.YOU : owner;
            this.part = part == null ? WidgetPart.AVATAR_LIFE : part;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(float px, float py) {
            return w > 0f && h > 0f && Math.abs(px - x) <= w / 2f && Math.abs(py - y) <= h / 2f;
        }
    }

    /**
     * Axis-aligned field pocket. {@code bottom} and {@code top} are in window
     * coordinates with y up.
     */
    public static final class FieldBox {
        public final float left;
        public final float right;
        public final float bottom;
        public final float top;

        public FieldBox(float left, float right, float bottom, float top) {
            this.left = left;
            this.right = right;
            this.bottom = bottom;
            this.top = top;
        }

        public float width() {
            return Math.max(0f, right - left);
        }

        public float height() {
            return Math.max(0f, top - bottom);
        }

        public float cx() {
            return (left + right) * 0.5f;
        }

        public float cy() {
            return (bottom + top) * 0.5f;
        }
    }

    /**
     * Life, grave, exile, library, and the one edge phase line.
     * Bounds are the right-hand chrome, used to keep a drag inside its half.
     */
    public static final class PlayerCluster {
        public final StripOwner owner;
        public final float left;
        public final float bottom;
        public final float right;
        public final float top;
        public final WidgetBox life;
        public final WidgetBox grave;
        public final WidgetBox exile;
        public final WidgetBox library;
        public final PhaseBox[] nonCombat;
        public final PhaseBox[] combat;
        /** Empty on the enemy widget. Your row hangs under the combat chips. */
        public final ActionBox[] actions;

        public PlayerCluster(StripOwner owner, float left, float bottom, float right, float top,
                              WidgetBox life, WidgetBox grave, WidgetBox exile, WidgetBox library,
                              PhaseBox[] nonCombat, PhaseBox[] combat, ActionBox[] actions) {
            this.owner = owner == null ? StripOwner.YOU : owner;
            this.left = left;
            this.bottom = bottom;
            this.right = right;
            this.top = top;
            this.life = life;
            this.grave = grave;
            this.exile = exile;
            this.library = library;
            this.nonCombat = nonCombat == null ? new PhaseBox[0] : nonCombat;
            this.combat = combat == null ? new PhaseBox[0] : combat;
            this.actions = actions == null ? new ActionBox[0] : actions;
        }
    }

    private BattlefieldLayout() {
    }

    public static float cardHeight(float windowH) {
        return CARD_HEIGHT_FRAC * windowH;
    }

    public static float cardWidth(float windowH) {
        return cardHeight(windowH) * HandLayout.CARD_ASPECT;
    }

    /**
     * Top of a full scan through the type line: name, mana cost, art, and type line.
     * Stops before the rules text, so a battlefield tile is not a full card.
     */
    public static final float BOARD_FACE_CROP = 0.63f;
    /**
     * Share of the cropped tile that is the type line, along the bottom.
     * Power and toughness sit on this band. The art above it stays clear.
     */
    public static final float BOARD_TYPE_BAND = 0.135f;

    /** Title and art only, then shrunk so a full set of rows stays on one half. */
    public static float boardTileHeight(float windowH) {
        return cardHeight(windowH) * BOARD_FACE_CROP * BOARD_TILE_SCALE;
    }

    /** Same aspect as the full frame, at the shrunk battlefield size. */
    public static float boardTileWidth(float windowH) {
        return cardWidth(windowH) * BOARD_TILE_SCALE;
    }

    /**
     * Full printed card on the board, not the type-line crop.
     * Same shrink as a creature tile's frame, so a planeswalker stays readable
     * without becoming the old stack-showcase plate.
     */
    public static float planeswalkerCardHeight(float windowH) {
        return cardHeight(windowH) * BOARD_TILE_SCALE;
    }

    public static float planeswalkerCardWidth(float windowH) {
        return cardWidth(windowH) * BOARD_TILE_SCALE;
    }

    /** Battles keep the printed face. Planeswalkers are cropped tiles with loyalty on the type line. */
    public static boolean fullPrintedCard(Kind kind) {
        return kind == Kind.BATTLE;
    }

    /**
     * Type line or Forge type string. Word Battle, or Siege as a battle subtype.
     * Does not match Battlefield, and does not look at card names.
     */
    public static boolean isBattleType(String type) {
        if (type == null || type.isBlank()) return false;
        String text = type.trim();
        if (text.matches("(?i).*\\bBattle\\b.*")) return true;
        return text.matches("(?i).*\\bSiege\\b.*");
    }

    /**
     * Left edge of the siege pocket: screen-right of the vertical middle,
     * clear of the left deck/land column.
     */
    public static float siegePocketLeft(float windowW) {
        return Math.max(windowW, 1f) * 0.5f + 12f;
    }

    /** Right edge of the siege pocket, left of the planeswalker / Pass column. */
    public static float siegePocketRight(float windowW) {
        return planeswalkerPocketLeft(Math.max(windowW, 1f)) - 8f;
    }

    /**
     * Left edge of the reserved right-side planeswalker pocket.
     * Screen-right of the creature/land beds, same x for both halves.
     */
    public static float planeswalkerPocketLeft(float windowW) {
        float safeW = Math.max(windowW, 1f);
        float bed = safeW * (0.5f + ROW_WIDTH_FRAC * 0.5f);
        float right = planeswalkerPocketRight(safeW);
        float left = bed + 8f;
        if (right - left < 80f) left = right - 80f;
        return left;
    }

    /** Right edge of the pocket, clear of the Pass/Confirm column. */
    public static float planeswalkerPocketRight(float windowW) {
        return Math.max(windowW, 1f) - CLUSTER_MARGIN - ACTION_COL_W - 8f;
    }

    /**
     * Inner edge of a half, clear of the gold line by the attack slide.
     * Your rows stay below this. Ash's rows stay above it.
     */
    public static float halfInner(float windowH, Side side) {
        float split = windowH * DIVIDER_FRAC;
        float guard = ATTACK_SLIDE_FRAC * windowH + 6f;
        return side == Side.OPPONENT ? split + guard : split - guard;
    }

    /**
     * Board tiles are this fraction of the hand-card frame.
     * 0.6072 is fifteen percent larger than the 0.528 size, which was twenty percent
     * above the 0.44 shrink. A row that would cross the gold line wraps on that same half.
     */
    public static final float BOARD_TILE_SCALE = 0.6072f;

    /**
     * Low-count land size: twenty percent smaller than a creature tile.
     * The land row shrinks further as count grows so the strip never overflows.
     */
    public static final float LAND_TILE_SCALE = 0.80f;
    /** Smallest land scale. Still a clickable tile, not a pip. */
    public static final float LAND_MIN_SCALE = 0.28f;
    /** Extra air between tilted lands at low count, so a 15-degree lean stays readable. */
    public static final float LAND_TILT_GAP = 10f;
    /**
     * Unique land fronts may share this much of the title bar, not the art crop.
     * More overlap than this buries the middle tile.
     */
    public static final float LAND_TITLE_SLIVER = 10f;

    public static float landTileWidth(float windowH) {
        return boardTileWidth(windowH) * LAND_TILE_SCALE;
    }

    public static float landTileHeight(float windowH) {
        return boardTileHeight(windowH) * LAND_TILE_SCALE;
    }

    /** Title still peeks on a land stack. Scaled with the smaller tile. */
    public static float landNamePeek(float windowH) {
        return Math.max(14f, landTileHeight(windowH) * 0.24f);
    }

    /** Smallest exposed sliver that stays a click target on a feathered pile. */
    public static final float PILE_SLIVER = 18f;

    /** Dark name strip along the bottom of a battlefield tile. Stacks peek by this much. */
    public static float boardNameBand(float windowH) {
        return Math.max(16f, boardTileHeight(windowH) * 0.24f);
    }

    /**
     * How far to slide a centered hand pose so the ghost fan sits to the right of your hand.
     * The gap fits the raised card, so spreading the ghost fan does not cover the real hand.
     */
    public static float ghostFanShift(float windowW, float windowH, int handCount, int handHover) {
        float handRight = windowW / 2f;
        if (handCount > 0) {
            handRight = windowW / 2f + HandLayout.handWidth(windowW, windowH, handCount) / 2f;
            if (handHover >= 0 && handHover < handCount) {
                float openRight = windowW / 2f
                        + HandLayout.hoverHandWidth(windowW, windowH, handCount, handHover) / 2f;
                handRight = Math.max(handRight, openRight);
            }
        }
        float cardW = HandLayout.hoverRaisedWidth(windowH);
        float gap = 20f;
        float ghostCx = handRight + gap + cardW / 2f;
        float maxCx = Math.max(cardW / 2f + 8f, windowW - 8f - cardW / 2f);
        if (ghostCx > maxCx) ghostCx = maxCx;
        return ghostCx - windowW / 2f;
    }

    /**
     * Front line of a row when every band is present.
     * His lands sit in the left column above deck/command, not under creatures.
     * His noncreature tokens sit in that same column, just above the hand.
     * Creatures and other permanents still pack from the life/hand chrome inward.
     * Planeswalkers use the right-side pocket, not this band.
     */
    public static float rowCenterY(float windowH, Side side, Row row) {
        float edge = 8f;
        float gap = 8f;
        float landH = landTileHeight(windowH);
        float tileH = boardTileHeight(windowH);
        boolean you = side != Side.OPPONENT;
        if (you && row == Row.LAND) {
            return yourLandPocket(Math.max(windowH, 1f), Math.max(windowH, 1f), Float.NaN, false).cy();
        }
        if (you && row == Row.TOKEN) {
            return yourTokenPocket(Math.max(windowH, 1f), Math.max(windowH, 1f), Float.NaN, false).cy();
        }
        float landCy;
        if (you) {
            landCy = yourBoardOuterY(windowH) + landH / 2f;
        } else {
            float lifeBottom = Math.max(windowH, 1f) - edge - CLUSTER_LIFE_H;
            landCy = lifeBottom - gap - landH / 2f;
        }
        if (row == Row.LAND || row == Row.TOKEN) return landCy;
        float step = tileH + gap;
        float otherCy = you
                ? landCy + landH / 2f + gap + tileH / 2f
                : landCy - landH / 2f - gap - tileH / 2f;
        if (row == Row.ENCHANTMENT || row == Row.OTHER) {
            return aePocket(Math.max(windowH, 1f), Math.max(windowH, 1f), side).cy();
        }
        if (row == Row.PLANESWALKER) return otherCy;
        if (row == Row.BATTLE) {
            float mid = dividerY(windowH);
            float bias = Math.max(18f, tileH * 0.22f);
            return you ? mid - bias : mid + bias;
        }
        return you ? otherCy + step : otherCy - step;
    }

    /**
     * Inward of his life badge and the resting hand. Creatures pack from here.
     * Lands left this band for the left chrome column.
     */
    static float yourBoardOuterY(float windowH) {
        float handTop = HandLayout.REST_TOP_FRAC * Math.max(windowH, 1f);
        float lifeTop = yourLifeCenterY(windowH) + CLUSTER_LIFE_H / 2f;
        return Math.max(lifeTop, handTop) + 8f;
    }

    /** Creature bed starts here on his half. Same y as before lands moved left. */
    static float yourCreatureOuter(float windowH) {
        return yourBoardOuterY(windowH) + landTileHeight(windowH) + 8f;
    }

    /** Top of library / grave / exile / command on his left chrome. */
    static float yourChromeTop(float windowW, float windowH) {
        PlayerCluster you = clusterAt(Math.max(windowW, 1f), Math.max(windowH, 1f), StripOwner.YOU, 0f, 0f);
        float top = you.library.y + you.library.h / 2f;
        top = Math.max(top, you.grave.y + you.grave.h / 2f);
        top = Math.max(top, you.exile.y + you.exile.h / 2f);
        float cmdH = Math.max(64f, boardTileHeight(windowH));
        top = Math.max(top, you.library.y + cmdH / 2f);
        return top;
    }

    /** Right edge of a command slot sitting after exile, or the deck family if that is wider. */
    public static float defaultYourCommandRight(float windowW, float windowH) {
        PlayerCluster you = clusterAt(Math.max(windowW, 1f), Math.max(windowH, 1f), StripOwner.YOU, 0f, 0f);
        float ch = Math.max(64f, boardTileHeight(windowH));
        float cw = ch * HandLayout.CARD_ASPECT;
        return you.exile.x + you.exile.w / 2f + 10f + cw;
    }

    /**
     * His left chrome column. Above the deck/command piles, left of the hand and
     * the creature bed, below the old creature floor. Lands and noncreature tokens
     * share this x-band and stack inside it.
     */
    public static FieldBox yourLeftColumn(float windowW, float windowH, float commandRight) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float gap = 8f;
        float left = CLUSTER_MARGIN;
        float right = deckFamilyRight(safeW, safeH, StripOwner.YOU, 0f);
        float cmd = commandRight;
        if (Float.isNaN(cmd) || cmd <= 0f) cmd = defaultYourCommandRight(safeW, safeH);
        if (cmd > right) right = cmd;
        float creatureLeft = creatureBedLeft(safeW) - gap;
        if (right > creatureLeft) right = creatureLeft;
        float handLeft = safeW * 0.5f - safeW * HandLayout.MAX_HAND_WIDTH_FRAC * 0.5f - gap;
        if (right > handLeft) right = handLeft;
        if (right < left + 40f) right = left + 40f;
        float handTop = HandLayout.REST_TOP_FRAC * safeH;
        float chromeTop = yourChromeTop(safeW, safeH);
        float bottom = Math.max(chromeTop, handTop) + gap;
        float top = yourCreatureOuter(safeH) - gap;
        float inner = halfInner(safeH, Side.YOU);
        if (top > inner - gap) top = inner - gap;
        if (top < bottom + 24f) top = bottom + 24f;
        return new FieldBox(left, right, bottom, top);
    }

    /**
     * Noncreature tokens: same left column as the deck, just above the hand and
     * the chrome piles. Not on the fan, not on the life digits, not in the creature bed.
     */
    public static FieldBox yourTokenPocket(float windowW, float windowH, float commandRight, boolean hasLands) {
        FieldBox col = yourLeftColumn(windowW, windowH, commandRight);
        float fullH = landTileHeight(windowH);
        float avail = col.height();
        float tokenH = fullH;
        if (hasLands) {
            float share = Math.max(24f, (avail - 8f) * 0.45f);
            if (tokenH > share) tokenH = share;
        }
        if (tokenH > avail) tokenH = avail;
        if (tokenH < 20f) tokenH = 20f;
        return new FieldBox(col.left, col.right, col.bottom, col.bottom + tokenH);
    }

    /**
     * His lands: outer-edge strip along the bottom of his half, spreading
     * sideways. Starts after command / the planeswalker column so tiles stay
     * off those widgets, and stops before the centered life badge.
     */
    public static FieldBox yourLandPocket(float windowW, float windowH, float commandRight, boolean hasTokens) {
        return yourLandPocket(windowW, windowH, commandRight, hasTokens, 1);
    }

    /**
     * Same strip, tall enough for a second land row when {@code landPiles}
     * unique fronts will not fit in one line without covering the middle.
     */
    public static FieldBox yourLandPocket(float windowW, float windowH, float commandRight,
                                          boolean hasTokens, int landPiles) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float cmd = commandRight;
        if (Float.isNaN(cmd) || cmd <= 0f) cmd = defaultYourCommandRight(safeW, safeH);
        float pwRight = planeswalkerPocket(safeW, safeH, Side.YOU).right;
        float left = Math.max(cmd, pwRight) + 8f;
        WidgetBox life = edgeLifeBox(safeW, safeH, StripOwner.YOU);
        float right = life.x - life.w / 2f - 8f;
        float creatureLeft = creatureBedLeft(safeW);
        if (right > creatureLeft - 8f) {
            right = Math.max(left + 64f, creatureLeft - 8f);
        }
        if (right < left + 48f) right = left + 48f;
        float bottom = yourBoardOuterY(safeH);
        float laneW = Math.max(40f, right - left);
        float need = landBlockHeight(Math.max(1, landPiles), laneW, landTileWidth(safeH), landTileHeight(safeH));
        float top = bottom + need + 8f;
        float inner = halfInner(safeH, Side.YOU) - 4f;
        if (top > inner) top = inner;
        if (hasTokens) {
            FieldBox tokens = yourTokenPocket(safeW, safeH, commandRight, true);
            if (tokens.top + 6f > bottom) bottom = Math.min(tokens.top + 6f, top - 24f);
        }
        if (top < bottom + 24f) top = bottom + 24f;
        return new FieldBox(left, right, bottom, top);
    }

    /**
     * Left-x and center-y of the commander-damage pip row.
     * HIS: with his life box, above the hand, right of the avatar, not on the digits.
     * Ash: with her top-center life, just under the badge so the row stays on screen.
     */
    public static float[] commanderPipOrigin(float windowW, float windowH, WidgetBox life,
                                             StripOwner owner, int count) {
        float icon = 16f;
        float gap = 4f;
        float pipW = icon + 18f;
        int n = Math.max(1, count);
        float rowW = n * pipW + Math.max(0, n - 1) * gap;
        float avatarSize = Math.min(46f, life.h - 14f);
        float avatarRight = life.x - life.w / 2f + 8f + avatarSize;
        float left = avatarRight + 6f;
        boolean you = owner != StripOwner.ENEMY;
        float y;
        if (you) {
            float handTop = HandLayout.REST_TOP_FRAC * Math.max(windowH, 1f);
            float underBox = life.y - life.h / 2f - 11f;
            float clearHand = handTop + 8f + icon / 2f;
            y = Math.max(underBox, clearHand);
            float lifeRight = life.x + life.w / 2f + 6f;
            if (y - icon / 2f < handTop + 4f) {
                left = lifeRight;
                y = life.y;
            }
        } else {
            float above = life.y + life.h / 2f + 11f;
            float maxY = Math.max(windowH, 1f) - 8f - icon / 2f;
            y = above <= maxY ? above : life.y - life.h / 2f - 11f;
        }
        float maxRight = Math.max(windowW, 1f) - ACTION_COL_W - 8f;
        if (left + rowW > maxRight) left = Math.max(8f, maxRight - rowW);
        return new float[] { left, y };
    }

    /**
     * Screen-left column of this half, opposite the centered life widget.
     * Above his command/deck chrome, below Ash's top chrome. Does not sit
     * on the life badge or in the artifact/enchantment row.
     */
    public static FieldBox planeswalkerPocket(float windowW, float windowH, Side side) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        boolean you = side != Side.OPPONENT;
        float tileW = boardTileWidth(safeH);
        float left = CLUSTER_MARGIN;
        float right = left + Math.max(tileW + 12f, 80f);
        float chromeRight = deckFamilyRight(safeW, safeH, you ? StripOwner.YOU : StripOwner.ENEMY, 0f);
        if (right < chromeRight + 4f) right = Math.min(chromeRight + 8f, left + tileW + 20f);
        float creatureLeft = creatureBedLeft(safeW);
        if (right > creatureLeft - 8f) right = Math.max(left + 56f, creatureLeft - 8f);
        float inner = halfInner(safeH, side);
        float bottom;
        float top;
        if (you) {
            float chromeTop = yourChromeTop(safeW, safeH);
            float handTop = HandLayout.REST_TOP_FRAC * safeH;
            bottom = Math.max(chromeTop, handTop) + 8f;
            top = inner - 6f;
        } else {
            float stripFloor = safeH - STRIP_EDGE_GAP - CLUSTER_CHIP_H - 8f;
            float libBottom = safeH - 8f - LIBRARY_PILE_H;
            top = Math.min(stripFloor, libBottom) - 6f;
            bottom = inner + 6f;
            if (top < bottom + 40f) top = Math.min(stripFloor, bottom + 80f);
        }
        if (top < bottom + 40f) {
            float mid = (top + bottom) * 0.5f;
            bottom = mid - 20f;
            top = mid + 20f;
        }
        return new FieldBox(left, right, bottom, top);
    }

    /**
     * Cropped tile in the left-side column. 1–2 keep full tile size and do not
     * overlap. 3+ shrinks the column first so each walker still has its own
     * seat. Copies of the same walker peek toward the gold line only after
     * shrink cannot keep every pile unstacked.
     */
    public static HandLayout.Pose planeswalkerInRow(float windowW, float windowH, Side side,
                                                     int pileIndex, int pileCount, int copyIndex, int copies,
                                                     boolean tapped, float clusterLeft, float stackLeft) {
        FieldBox box = planeswalkerPocket(windowW, windowH, side);
        float fullW = boardTileWidth(windowH);
        float fullH = boardTileHeight(windowH);
        int n = Math.max(1, pileCount);
        float availW = Math.max(40f, box.width() - 8f);
        float availH = Math.max(40f, box.height() - 8f);
        float gap = n <= 2 ? 8f : 6f;
        float scale = 1f;
        float need = n * fullH + Math.max(0, n - 1) * gap;
        if (need > availH) {
            float shrinkGap = n <= 2 ? gap : 4f;
            scale = (availH - Math.max(0, n - 1) * shrinkGap) / Math.max(1f, n * fullH);
            gap = shrinkGap;
        }
        if (fullW * scale > availW) scale = Math.min(scale, availW / fullW);
        if (scale > 1f) scale = 1f;
        boolean unstacked = scale >= 0.42f;
        if (scale < 0.42f) scale = 0.42f;
        float cw = fullW * scale;
        float ch = fullH * scale;
        float used = n * ch + Math.max(0, n - 1) * gap;
        if (used > availH + 0.5f) unstacked = false;
        float cx = box.left + 4f + cw / 2f;
        if (cx + cw / 2f > box.right - 4f) cx = box.right - 4f - cw / 2f;
        boolean you = side != Side.OPPONENT;
        if (unstacked) {
            float firstCy = you ? box.bottom + 4f + ch / 2f : box.top - 4f - ch / 2f;
            float step = ch + gap;
            float cy = firstCy + Math.max(0, pileIndex) * step * (you ? 1f : -1f);
            HandLayout.Pose home = new HandLayout.Pose(cx, cy, cw, ch, 0f, 1f);
            if (copyIndex > 0 && copies > 1) {
                return featherTowardMidline(home, copyIndex, copies, windowH, side);
            }
            return home;
        }
        float peek = Math.max(PILE_SLIVER, ch * 0.18f);
        float inward = you ? 1f : -1f;
        float firstCy = you ? box.bottom + 4f + ch / 2f : box.top - 4f - ch / 2f;
        float cy = firstCy + (Math.max(0, pileIndex) * peek + Math.max(0, copyIndex) * peek) * inward;
        if (you) {
            if (cy + ch / 2f > box.top - 4f) cy = box.top - 4f - ch / 2f;
            if (cy - ch / 2f < box.bottom + 4f) cy = box.bottom + 4f + ch / 2f;
        } else {
            if (cy - ch / 2f < box.bottom + 4f) cy = box.bottom + 4f + ch / 2f;
            if (cy + ch / 2f > box.top - 4f) cy = box.top - 4f - ch / 2f;
        }
        return new HandLayout.Pose(cx, cy, cw, ch, 0f, 1f);
    }

    /** True when {@code count} walkers still each get their own seat after shrink. */
    public static boolean planeswalkersFitUnstacked(float windowW, float windowH, Side side, int count) {
        if (count <= 0) return true;
        FieldBox box = planeswalkerPocket(windowW, windowH, side);
        float fullH = boardTileHeight(windowH);
        float availH = Math.max(40f, box.height() - 8f);
        float gap = count <= 2 ? 8f : 6f;
        float scale = 1f;
        float need = count * fullH + Math.max(0, count - 1) * gap;
        if (need > availH) {
            gap = count <= 2 ? gap : 4f;
            scale = (availH - Math.max(0, count - 1) * gap) / Math.max(1f, count * fullH);
        }
        if (scale < 0.42f) return false;
        float used = count * fullH * scale + Math.max(0, count - 1) * gap;
        return used <= availH + 0.5f;
    }

    /**
     * Midline pocket, right of center. His-side box sits mostly below the gold
     * line (a siege played against him). Her-side box sits mostly above it
     * (a siege he played against her). Not the creature bed, land column,
     * token pocket, planeswalker pocket, or command slot.
     */
    public static FieldBox siegePocket(float windowW, float windowH, Side side) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float left = siegePocketLeft(safeW);
        float right = siegePocketRight(safeW);
        if (right < left + 80f) right = left + 80f;
        float pwLeft = planeswalkerPocketLeft(safeW);
        if (right > pwLeft - 4f) right = pwLeft - 4f;
        if (right < left + 40f) left = Math.max(safeW * 0.5f + 4f, right - 80f);
        float mid = dividerY(safeH);
        float fullH = planeswalkerCardHeight(safeH);
        float pocketH = Math.max(80f, fullH + 16f);
        boolean you = side != Side.OPPONENT;
        float bottom;
        float top;
        if (you) {
            top = mid + 10f;
            bottom = top - pocketH;
        } else {
            bottom = mid - 10f;
            top = bottom + pocketH;
        }
        if (top < bottom + 40f) {
            float cy = (top + bottom) * 0.5f;
            bottom = cy - 20f;
            top = cy + 20f;
        }
        return new FieldBox(left, right, bottom, top);
    }

    /**
     * Full printed card on the defending player's side of the gold line.
     * Later sieges peek away from the midline. Dynamic shrink keeps the fan
     * inside the pocket. No tap tilt.
     */
    public static HandLayout.Pose siegeInPocket(float windowW, float windowH, Side side,
                                                int pileIndex, int pileCount, int copyIndex, int copies) {
        FieldBox box = siegePocket(windowW, windowH, side);
        float fullW = planeswalkerCardWidth(windowH);
        float fullH = planeswalkerCardHeight(windowH);
        int n = Math.max(1, pileCount);
        int extras = Math.max(0, copies - 1);
        float peek = Math.max(PILE_SLIVER, fullH * 0.18f);
        float availW = Math.max(40f, box.width() - 8f);
        float availH = Math.max(40f, box.height() - 8f);
        float needH = fullH + Math.max(0, n - 1) * peek + extras * peek;
        float scale = 1f;
        if (needH > availH) scale = availH / needH;
        if (fullW * scale > availW) scale = Math.min(scale, availW / fullW);
        if (scale > 1f) scale = 1f;
        if (scale < 0.42f) scale = 0.42f;
        float cw = fullW * scale;
        float ch = fullH * scale;
        peek = Math.max(PILE_SLIVER, ch * 0.18f);
        float cx = box.cx();
        if (cx - cw / 2f < box.left + 4f) cx = box.left + 4f + cw / 2f;
        if (cx + cw / 2f > box.right - 4f) cx = box.right - 4f - cw / 2f;
        boolean you = side != Side.OPPONENT;
        float away = you ? -1f : 1f;
        float firstCy = you ? box.top - 4f - ch / 2f : box.bottom + 4f + ch / 2f;
        int arrival = Math.max(0, pileIndex);
        float cy = firstCy + (arrival * peek + Math.max(0, copyIndex) * peek) * away;
        if (you) {
            if (cy + ch / 2f > box.top - 4f) cy = box.top - 4f - ch / 2f;
            if (cy - ch / 2f < box.bottom + 4f) cy = box.bottom + 4f + ch / 2f;
        } else {
            if (cy - ch / 2f < box.bottom + 4f) cy = box.bottom + 4f + ch / 2f;
            if (cy + ch / 2f > box.top - 4f) cy = box.top - 4f - ch / 2f;
        }
        return new HandLayout.Pose(cx, cy, cw, ch, 0f, 1f);
    }

    /**
     * Modest artifact / enchantment row for this seat. Near that player's
     * permanents (under the creature bed), not beside the life widget.
     */
    public static FieldBox aePocket(float windowW, float windowH, Side side) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        boolean you = side != Side.OPPONENT;
        float tileH = boardTileHeight(safeH);
        float left = creatureBedLeft(safeW);
        WidgetBox life = edgeLifeBox(safeW, safeH, you ? StripOwner.YOU : StripOwner.ENEMY);
        float right = you
                ? life.x - life.w / 2f - 8f
                : Math.min(life.x - life.w / 2f - 8f, planeswalkerPocketRight(safeW));
        if (right < left + 48f) right = left + 48f;
        float bottom;
        float top;
        if (you) {
            bottom = yourBoardOuterY(safeH);
            top = Math.min(yourCreatureOuter(safeH), bottom + tileH + 8f);
        } else {
            float outer = creatureOuterEdge(safeH, Side.OPPONENT);
            top = outer;
            bottom = Math.max(halfInner(safeH, side) + 8f, top - tileH - 8f);
        }
        if (top < bottom + 28f) {
            float mid = (top + bottom) * 0.5f;
            bottom = mid - 14f;
            top = mid + 14f;
        }
        return new FieldBox(left, right, bottom, top);
    }

    /**
     * Standalone enchantments and loose non-token artifacts share this band:
     * one row toward that player from the creature bed, to the right of that
     * player's life box. Same neighbourhood as the life badge. Not the hand,
     * not under creatures, not the planeswalker pocket, not the siege midline,
     * and not the Pass/Next column.
     * {@code occupiedRight} is the right edge of emblems or commander pips that
     * already sit on this life-right lane, or {@code NaN} when the lane is empty.
     */
    public static FieldBox lifeRightBand(float windowW, float windowH, Side side, float occupiedRight) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        boolean you = side != Side.OPPONENT;
        WidgetBox life = edgeLifeBox(safeW, safeH, you ? StripOwner.YOU : StripOwner.ENEMY);
        float left = life.x + life.w / 2f + 8f;
        if (!Float.isNaN(occupiedRight) && occupiedRight + 6f > left) {
            left = occupiedRight + 6f;
        }
        float right = Math.min(planeswalkerPocketLeft(safeW) - 8f,
                safeW - CLUSTER_MARGIN - ACTION_COL_W - 8f);
        if (right < left + 48f) right = left + 48f;
        float fullH = planeswalkerCardHeight(safeH);
        float bottom;
        float top;
        if (you) {
            float handTop = HandLayout.REST_TOP_FRAC * safeH;
            float creatureFloor = yourCreatureOuter(safeH);
            float need = Math.min(fullH + 8f, Math.max(40f, creatureFloor - handTop - 12f));
            float mid = life.y;
            bottom = mid - need / 2f;
            top = mid + need / 2f;
            if (bottom < handTop + 6f) {
                bottom = handTop + 6f;
                top = bottom + need;
            }
            if (top > creatureFloor - 6f) {
                top = creatureFloor - 6f;
                bottom = top - need;
                if (bottom < handTop + 6f) bottom = handTop + 6f;
            }
        } else {
            float stripFloor = safeH - STRIP_EDGE_GAP - CLUSTER_CHIP_H - 6f;
            float creatureCeil = creatureOuterEdge(safeH, Side.OPPONENT);
            float need = Math.min(fullH + 8f, Math.max(40f, stripFloor - creatureCeil - 12f));
            top = Math.min(stripFloor, life.y + life.h / 2f);
            bottom = top - need;
            if (bottom < creatureCeil + 6f) {
                bottom = creatureCeil + 6f;
                top = bottom + need;
                if (top > stripFloor) top = stripFloor;
            }
        }
        if (top < bottom + 36f) {
            float mid = (top + bottom) * 0.5f;
            bottom = mid - 18f;
            top = mid + 18f;
        }
        return new FieldBox(left, right, bottom, top);
    }

    /**
     * Saga, Class, Case, Room, curse, and any other enchantment that is not an
     * Aura tucked on a host. Shared A/E row near that player's permanents.
     */
    public static FieldBox enchantmentPocket(float windowW, float windowH, Side side) {
        return enchantmentPocket(windowW, windowH, side, false, Float.NaN);
    }

    public static FieldBox enchantmentPocket(float windowW, float windowH, Side side,
                                              boolean shareWithArtifacts, float occupiedRight) {
        return aePocket(windowW, windowH, side);
    }

    /**
     * Loose non-token artifacts. Same A/E row as standalone enchantments.
     */
    public static FieldBox artifactPocket(float windowW, float windowH, Side side) {
        return artifactPocket(windowW, windowH, side, false, Float.NaN);
    }

    public static FieldBox artifactPocket(float windowW, float windowH, Side side,
                                           boolean shareWithEnchantments, float occupiedRight) {
        return aePocket(windowW, windowH, side);
    }

    /**
     * Cropped board tile on the A/E row. Same family as loose artifacts.
     * Packs from the center of the pocket. Tapped tiles lean 15° in place.
     */
    public static HandLayout.Pose enchantmentInPocket(float windowW, float windowH, Side side,
                                                       int pileIndex, int pileCount, int copyIndex, int copies,
                                                       FieldBox box) {
        return aeTileInPocket(windowW, windowH, side, pileIndex, pileCount, copyIndex, copies, false, box);
    }

    /** Cropped A/E tile packed from the center of the seat. */
    public static HandLayout.Pose aeTileInPocket(float windowW, float windowH, Side side,
                                                 int pileIndex, int pileCount, int copyIndex, int copies,
                                                 boolean tapped, FieldBox box) {
        FieldBox seat = box != null ? box : aePocket(windowW, windowH, side);
        int n = Math.max(1, pileCount);
        int i = Math.max(0, Math.min(pileIndex, n - 1));
        float fullW = boardTileWidth(windowH);
        float fullH = boardTileHeight(windowH);
        float availW = Math.max(36f, seat.width() - 8f);
        float availH = Math.max(28f, seat.height() - 4f);
        float scale = 1f;
        if (n * fullW + Math.max(0, n - 1) * 8f > availW) {
            scale = (availW - Math.max(0, n - 1) * PILE_SLIVER) / Math.max(1f, n * fullW);
        }
        if (fullH > 1f && availH / fullH < scale) scale = availH / fullH;
        if (scale > 1f) scale = 1f;
        if (scale < 0.36f) scale = 0.36f;
        float cw = fullW * scale;
        float ch = fullH * scale;
        float step = n <= 1 ? 0f : Math.min(cw + 8f, Math.max(PILE_SLIVER, (availW - cw) / (n - 1)));
        float mid = seat.cx();
        float cx = n <= 1 ? mid : mid + (i - (n - 1) / 2f) * step;
        if (cx - cw / 2f < seat.left + 4f) cx = seat.left + 4f + cw / 2f;
        if (cx + cw / 2f > seat.right - 4f) cx = seat.right - 4f - cw / 2f;
        float cy = seat.cy();
        if (cy + ch / 2f > seat.top - 2f) cy = seat.top - 2f - ch / 2f;
        if (cy - ch / 2f < seat.bottom + 2f) cy = seat.bottom + 2f + ch / 2f;
        float rot = tapped ? tappedRotation(side) : 0f;
        HandLayout.Pose home = new HandLayout.Pose(cx, cy, cw, ch, rot, 1f);
        return featherTowardMidline(home, copyIndex, copies, windowH, side);
    }

    /**
     * Small artifact tile in the life-right artifact sliver. Not a full printed
     * card and not on the creature bed.
     */
    public static HandLayout.Pose artifactInPocket(float windowW, float windowH, Side side,
                                                    int pileIndex, int pileCount, int copyIndex, int copies,
                                                    boolean tapped, FieldBox box) {
        return aeTileInPocket(windowW, windowH, side, pileIndex, pileCount, copyIndex, copies, tapped, box);
    }

    /** Type line or Forge type string. Word Saga as its own type, not a name match. */
    public static boolean isSagaType(String type) {
        if (type == null || type.isBlank()) return false;
        return type.matches("(?i).*\\bSaga\\b.*");
    }

    /**
     * Slot {@code i} of {@code n} in a centred row. A tapped card rotates in place;
     * the row does not reflow when it taps.
     * Loose artifacts pack into the life-right artifact sliver. Standalone
     * enchantments pack into the life-right enchantment pocket.
     */
    public static HandLayout.Pose slot(float windowW, float windowH, Side side, Row row,
                                        int i, int n, boolean tapped) {
        if (row == Row.LAND) {
            return landInRow(windowW, windowH, side, i, n, tapped, Float.NaN, Float.NaN);
        }
        if (row == Row.TOKEN) {
            FieldBox box = side == Side.OPPONENT
                    ? new FieldBox(10f, 80f, rowCenterY(windowH, side, Row.TOKEN) - 20f,
                    rowCenterY(windowH, side, Row.TOKEN) + 20f)
                    : yourTokenPocket(windowW, windowH, Float.NaN, false);
            return tokenInPocket(windowW, windowH, side, i, n, 0, 1, tapped, box);
        }
        if (row == Row.OTHER) return otherPermanent(windowW, windowH, side, i, n, tapped);
        if (row == Row.ENCHANTMENT) {
            return enchantmentInPocket(windowW, windowH, side, i, n, 0, 1, null);
        }
        if (row == Row.PLANESWALKER) {
            return planeswalkerInRow(windowW, windowH, side, i, n, 0, 1, tapped, Float.NaN, Float.NaN);
        }
        if (row == Row.BATTLE) {
            return siegeInPocket(windowW, windowH, side, i, n, 0, 1);
        }
        float ch = boardTileHeight(windowH);
        float cw = boardTileWidth(windowH);
        float step = 0f;
        if (n > 1) {
            float minStep = tiltedStep(cw, ch);
            float span = Math.min(0.72f * windowW, n * minStep);
            step = (span - cw) / (n - 1);
            if (step < minStep) step = minStep;
        }
        float cx = windowW / 2f + (i - (n - 1) / 2f) * step;
        float cy = rowCenterY(windowH, side, row);
        float rot = tapped ? tappedRotation(side) : 0f;
        return new HandLayout.Pose(cx, cy, cw, ch, rot, 1f);
    }

    /**
     * Loose non-token artifacts. Small life-right sliver, not on the creature bed
     * and not in the planeswalker or siege pockets.
     */
    public static HandLayout.Pose otherPermanent(float windowW, float windowH, Side side,
                                                  int index, int count, boolean tapped) {
        return artifactInPocket(windowW, windowH, side, index, count, 0, 1, tapped, null);
    }

    /**
     * One tile in an identical-state creature pile. {@code pileIndex} 0 sits on the
     * middle of this player's creature bed. Later piles fill right, then left, then
     * right again. The group stays centered. A crowded row shrinks until it fits
     * inside the bed and clear of the life widgets. A second line is only for
     * different piles that would make one row too small to read.
     * Extra copies of the same pile peek toward the gold line so each stays
     * clickable without taking a second full row.
     */
    public static HandLayout.Pose creaturePile(float windowW, float windowH, Side side,
                                                int pileIndex, int pileCount, int copyIndex, int copies,
                                                boolean tapped) {
        return creaturePile(windowW, windowH, side, pileIndex, pileCount, copyIndex, copies,
                tapped, Float.NaN, Float.NaN);
    }

    /**
     * Same as {@link #creaturePile(float, float, Side, int, int, int, int, boolean)},
     * with this player's live cluster left edge and the stack card's left edge when
     * that card is actually on screen. {@code NaN} means "use the default cluster"
     * or "stack is empty, do not reserve it".
     */
    public static HandLayout.Pose creaturePile(float windowW, float windowH, Side side,
                                                int pileIndex, int pileCount, int copyIndex, int copies,
                                                boolean tapped, float clusterLeft, float stackLeft) {
        return creaturePile(windowW, windowH, side, pileIndex, pileCount, copyIndex, copies,
                tapped, clusterLeft, stackLeft, 0, Float.NaN, Float.NaN);
    }

    /**
     * Same creature pile, with the live land-block height so a second land row
     * does not sit under the first creature.
     */
    public static HandLayout.Pose creaturePile(float windowW, float windowH, Side side,
                                                int pileIndex, int pileCount, int copyIndex, int copies,
                                                boolean tapped, float clusterLeft, float stackLeft,
                                                int landPiles, float landLaneLeft, float landLaneRight) {
        int n = Math.max(1, pileCount);
        float fullW = boardTileWidth(windowH);
        float fullH = boardTileHeight(windowH);
        float left = creatureBedLeft(windowW);
        float right = creatureBedRight(windowW, windowH, side, clusterLeft, stackLeft);
        if (right - left < fullW) right = left + fullW;
        float laneW = right - left;
        float bedCx = (left + right) * 0.5f;
        boolean you = side != Side.OPPONENT;
        float outer = creatureOuterEdge(windowH, side, landPiles, landLaneLeft, landLaneRight);
        float inner = halfInner(windowH, side);
        float laneH = Math.max(fullH * 0.45f, Math.abs(inner - outer) - 6f);
        int bands = 1;
        int across = n;
        float scale = creatureRowScale(across, 1, fullW, fullH, laneW, laneH);
        if (scale < 0.52f && n > 1) {
            bands = 2;
            across = (n + 1) / 2;
            scale = creatureRowScale(across, 2, fullW, fullH, laneW, laneH);
        }
        if (scale > 1f) scale = 1f;
        if (scale < 0.36f) scale = 0.36f;
        float cw = fullW * scale;
        float ch = fullH * scale;
        int perBand = bands == 1 ? n : across;
        int arrival = Math.max(0, pileIndex);
        int band = bands == 1 ? 0 : Math.min(bands - 1, arrival / Math.max(1, perBand));
        int inBand = arrival - band * perBand;
        int colsThis = Math.min(perBand, n - band * perBand);
        if (colsThis < 1) colsThis = 1;
        if (inBand >= colsThis) inBand = colsThis - 1;
        float step = tiltedStep(cw, ch);
        float halfFoot = Math.max(cw / 2f, (step - 14f) / 2f);
        float rowSpan = colsThis <= 1 ? halfFoot * 2f : (colsThis - 1) * step + halfFoot * 2f;
        if (colsThis > 1 && rowSpan > laneW) {
            step = (laneW - halfFoot * 2f) / (colsThis - 1);
            rowSpan = (colsThis - 1) * step + halfFoot * 2f;
        }
        int offset = centerOutOffset(inBand);
        float groupShift = (colsThis & 1) == 0 ? -step * 0.5f : 0f;
        float cx = bedCx + offset * step + groupShift;
        float gapLine = 8f;
        float bandBlock = bands * ch + (bands - 1) * gapLine;
        float cy;
        if (you) {
            float slack = Math.max(0f, (inner - outer) - bandBlock);
            cy = outer + slack / 2f + ch / 2f + band * (ch + gapLine);
            float maxCy = inner - ch / 2f;
            float minCy = outer + ch / 2f;
            if (cy > maxCy) cy = maxCy;
            if (cy < minCy) cy = minCy;
        } else {
            float slack = Math.max(0f, (outer - inner) - bandBlock);
            cy = outer - slack / 2f - ch / 2f - band * (ch + gapLine);
            float minCy = inner + ch / 2f;
            float maxCy = outer - ch / 2f;
            if (cy < minCy) cy = minCy;
            if (cy > maxCy) cy = maxCy;
        }
        if (cx - cw / 2f < left) cx = left + cw / 2f;
        if (cx + cw / 2f > right) cx = right - cw / 2f;
        float rot = tapped ? tappedRotation(side) : 0f;
        HandLayout.Pose home = new HandLayout.Pose(cx, cy, cw, ch, rot, 1f);
        return featherTowardMidline(home, copyIndex, copies, windowH, side, inner);
    }

    /**
     * Extra copies peek toward the gold line. The home tile stays put; later
     * copies stay a pile of title slivers, not a second row of full cards.
     * Sideways fan is the fallback only when that vertical room is gone.
     */
    public static HandLayout.Pose featherTowardMidline(HandLayout.Pose home, int copyIndex, int copies,
                                                        float windowH, Side side) {
        if (home == null) return null;
        return featherTowardMidline(home, copyIndex, copies, windowH, side, halfInner(windowH, side));
    }

    private static HandLayout.Pose featherTowardMidline(HandLayout.Pose home, int copyIndex, int copies,
                                                         float windowH, Side side, float inner) {
        if (home == null || copyIndex <= 0 || copies <= 1) return home;
        boolean you = side != Side.OPPONENT;
        float peek = Math.max(PILE_SLIVER, boardNameBand(windowH) * 0.9f);
        float room = you
                ? inner - (home.cy + home.h / 2f)
                : (home.cy - home.h / 2f) - inner;
        if (room < 0f) room = 0f;
        int extra = Math.max(1, copies - 1);
        if (extra * peek > room) peek = room / extra;
        float cy = home.cy;
        float cx = home.cx;
        if (peek >= 14f) {
            cy += copyIndex * peek * (you ? 1f : -1f);
        } else {
            float sidePeek = Math.max(PILE_SLIVER, boardNameBand(windowH) * 0.7f);
            cx += copyIndex * sidePeek;
        }
        if (you) {
            float maxCy = inner - home.h / 2f;
            if (cy > maxCy) cy = maxCy;
        } else {
            float minCy = inner + home.h / 2f;
            if (cy < minCy) cy = minCy;
        }
        return new HandLayout.Pose(cx, cy, home.w, home.h, home.rotationDeg, home.scale);
    }

    /**
     * Extra copies peek away from the life widget so a planeswalker pile stays
     * on the left edge and each copy is a hit.
     */
    public static HandLayout.Pose featherAwayFromLife(HandLayout.Pose home, int copyIndex, int copies,
                                                       float windowH) {
        if (home == null || copyIndex <= 0 || copies <= 1) return home;
        float peek = Math.max(PILE_SLIVER, boardNameBand(windowH) * 0.85f);
        float cx = home.cx - copyIndex * peek;
        float half = home.w / 2f;
        if (cx - half < 8f) cx = 8f + half;
        return new HandLayout.Pose(cx, home.cy, home.w, home.h, home.rotationDeg, home.scale);
    }

    /**
     * One unique land front in the pocket. Shrinks before it overlaps a neighbour's
     * art. Wraps to a second row when one line would bury the middle tile.
     * Tapped lands lean 15° right in place.
     */
    public static HandLayout.Pose landInRow(float windowW, float windowH, Side side,
                                            int index, int count, boolean tapped,
                                            float laneLeft, float laneRight) {
        return landInRow(windowW, windowH, side, index, count, tapped, laneLeft, laneRight,
                Float.NaN, Float.NaN);
    }

    /**
     * Identical-state land pile. Column {@code pileIndex} is a unique front
     * (name + tap + chosen type + sick). Copy 0 is that front; later copies peek
     * toward the gold line. The count badge is drawn on the front.
     */
    public static HandLayout.Pose landPile(float windowW, float windowH, Side side,
                                           int pileIndex, int pileCount, int copyIndex, int copies,
                                           boolean tapped, float laneLeft, float laneRight,
                                           float laneCy, float laneH) {
        HandLayout.Pose home = landInRow(windowW, windowH, side, pileIndex, Math.max(1, pileCount),
                tapped, laneLeft, laneRight, laneCy, laneH);
        return featherTowardMidline(home, copyIndex, copies, windowH, side);
    }

    public static HandLayout.Pose landInRow(float windowW, float windowH, Side side,
                                            int index, int count, boolean tapped,
                                            float laneLeft, float laneRight,
                                            float laneCy, float laneH) {
        float[] lane = landLane(windowW, windowH, side, laneLeft, laneRight, laneCy);
        float left = lane[0];
        float right = lane[1];
        float cy0 = lane[2];
        int n = Math.max(1, count);
        int i = Math.max(0, Math.min(index, n - 1));
        float fullW = landTileWidth(windowH);
        float fullH = landTileHeight(windowH);
        float avail = Math.max(40f, right - left);
        LandFit fit = fitLandPiles(n, fullW, fullH, avail, laneH);
        float cw = fullW * fit.scale;
        float ch = fullH * fit.scale;
        int across = Math.max(1, fit.across);
        int band = fit.bands <= 1 ? 0 : Math.min(fit.bands - 1, i / across);
        int col = i - band * across;
        int colsThis = Math.min(across, n - band * across);
        if (colsThis < 1) colsThis = 1;
        if (col >= colsThis) col = colsThis - 1;
        float step = landRowFrontStep(colsThis, cw, ch, avail);
        float foot = landTiltFootprint(cw, ch);
        float used = colsThis <= 1 ? foot : (colsThis - 1) * step + foot;
        float cx = left + Math.max(0f, avail - used) * 0.5f + foot / 2f + col * step;
        if (cx - cw / 2f < left) cx = left + cw / 2f;
        if (cx + cw / 2f > right) cx = right - cw / 2f;
        boolean you = side != Side.OPPONENT;
        float gapLine = 8f;
        float cy = cy0;
        if (fit.bands > 1) {
            if (you && !Float.isNaN(laneH) && laneH > 8f) {
                float pocketBottom = cy0 - laneH / 2f;
                cy = pocketBottom + ch / 2f + band * (ch + gapLine);
            } else {
                cy = cy0 + band * (ch + gapLine) * (you ? 1f : -1f);
            }
        }
        float rot = tapped ? tappedRotation(side) : 0f;
        return new HandLayout.Pose(cx, cy, cw, ch, rot, 1f);
    }

    /**
     * Height of the unique-front land block, including a second row when one
     * line would cover the middle tile.
     */
    public static float landBlockHeight(int piles, float laneW, float fullW, float fullH) {
        LandFit fit = fitLandPiles(Math.max(1, piles), fullW, fullH, Math.max(40f, laneW), Float.NaN);
        float rowH = fullH * fit.scale;
        return fit.bands * rowH + Math.max(0, fit.bands - 1) * 8f;
    }

    /**
     * Centre spacing for unique land fronts. Comfort is the tilted footprint
     * plus air. Never tighter than {@link #landFrontStep(float, float)}.
     */
    public static float landRowFrontStep(int count, float cw, float ch, float laneW) {
        int n = Math.max(1, count);
        if (n <= 1) return 0f;
        float foot = landTiltFootprint(cw, ch);
        float comfort = foot + LAND_TILT_GAP;
        float min = landFrontStep(cw, ch);
        float spread = (Math.max(40f, laneW) - foot) / (n - 1);
        if (spread > comfort) return comfort;
        if (spread < min) return min;
        return spread;
    }

    /**
     * Minimum centre spacing so two unique fronts share at most a title-bar sliver
     * after a 15° tap. Wider than half the tilted box, so the middle tile stays a hit.
     */
    public static float landFrontStep(float cw, float ch) {
        float foot = landTiltFootprint(cw, ch);
        float sliver = Math.min(LAND_TITLE_SLIVER, Math.max(6f, ch * BOARD_TYPE_BAND));
        float step = foot - sliver;
        float halfFace = Math.max(cw, foot) * 0.55f;
        if (step < halfFace) step = halfFace;
        return step;
    }

    /** True when two unique fronts overlap more than a title-bar sliver. */
    public static boolean landFrontsTooTight(float step, float cw, float ch) {
        return step + 0.01f < landFrontStep(cw, ch);
    }

    /**
     * Left, right, and center y of the land strip.
     * His lane is the left chrome column above deck/command. Ash keeps the
     * outer-edge band inward of her life.
     */
    private static float[] landLane(float windowW, float windowH, Side side,
                                    float laneLeft, float laneRight, float laneCy) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        boolean you = side != Side.OPPONENT;
        float left;
        float right;
        float cy;
        if (you) {
            FieldBox box = yourLandPocket(safeW, safeH, laneRight, false);
            left = box.left;
            right = box.right;
            cy = box.cy();
        } else {
            left = 10f;
            right = safeW - CLUSTER_MARGIN - ACTION_COL_W - 8f;
            cy = rowCenterY(safeH, side, Row.LAND);
        }
        if (!Float.isNaN(laneLeft) && laneLeft > 0f) left = you ? laneLeft : Math.max(left, laneLeft);
        if (!Float.isNaN(laneRight) && laneRight > 0f) right = you ? laneRight : Math.min(right, laneRight);
        if (!Float.isNaN(laneCy) && laneCy > 0f) cy = laneCy;
        if (right < left + 40f) right = left + 40f;
        return new float[] { left, right, cy };
    }

    /**
     * One noncreature token pile in his left pocket, just above the hand.
     * Identical copies feather toward the gold line. Each copy stays a click.
     */
    public static HandLayout.Pose tokenInPocket(float windowW, float windowH, Side side,
                                                int pileIndex, int pileCount, int copyIndex, int copies,
                                                boolean tapped, FieldBox box) {
        FieldBox seat = box;
        if (seat == null) {
            seat = side == Side.OPPONENT
                    ? new FieldBox(10f, 80f, 0f, 40f)
                    : yourTokenPocket(windowW, windowH, Float.NaN, false);
        }
        int n = Math.max(1, pileCount);
        int i = Math.max(0, Math.min(pileIndex, n - 1));
        float fullW = landTileWidth(windowH);
        float fullH = landTileHeight(windowH);
        float availW = Math.max(40f, seat.width() - 8f);
        float availH = Math.max(20f, seat.height() - 4f);
        float scale = landRowScale(n, fullW, fullH, availW);
        if (fullH > 1f && availH / fullH < scale) scale = availH / fullH;
        if (scale < LAND_MIN_SCALE) scale = LAND_MIN_SCALE;
        if (scale > 1f) scale = 1f;
        float cw = fullW * scale;
        float ch = fullH * scale;
        float step = landRowStep(n, cw, ch, availW);
        float cx = landSeatX(i, n, cw, step, seat.left + 4f, seat.right - 4f, 0f, 0f);
        float cy = seat.bottom + 2f + ch / 2f;
        if (cy + ch / 2f > seat.top - 2f) cy = seat.top - 2f - ch / 2f;
        if (cy - ch / 2f < seat.bottom + 2f) cy = seat.bottom + 2f + ch / 2f;
        float rot = tapped ? tappedRotation(side) : 0f;
        HandLayout.Pose home = new HandLayout.Pose(cx, cy, cw, ch, rot, 1f);
        return featherTowardMidline(home, copyIndex, copies, windowH, side, seat.top);
    }

    /**
     * 1 is the 80% creature-relative tile. Smaller when N lands would overflow
     * the pocket even after the gap tightens and neighbours start to overlap.
     */
    private static float landRowScale(int count, float fullW, float fullH, float laneW) {
        int n = Math.max(1, count);
        float base = landTiltFootprint(fullW, fullH);
        if (base < 1f) return 1f;
        float need = n * base + Math.max(0, n - 1) * LAND_TILT_GAP;
        if (need <= laneW) return 1f;
        need = n * base + Math.max(0, n - 1) * 4f;
        if (need <= laneW) return 1f;
        float peek = PILE_SLIVER;
        float scale;
        if (n <= 1) {
            scale = Math.min(1f, laneW / base);
        } else {
            scale = (laneW - (n - 1) * peek) / base;
            if (scale < LAND_MIN_SCALE) {
                peek = Math.max(10f, (laneW - LAND_MIN_SCALE * base) / (n - 1));
                scale = (laneW - (n - 1) * peek) / base;
            }
        }
        if (scale > 1f) scale = 1f;
        if (scale < LAND_MIN_SCALE) scale = LAND_MIN_SCALE;
        return scale;
    }

    private static float landTiltFootprint(float cw, float ch) {
        double rad = Math.toRadians(TAP_DEG);
        return (float) (Math.abs(cw * Math.cos(rad)) + Math.abs(ch * Math.sin(rad)));
    }

    /** Unique-front land grid: shrink first, then a second row. */
    private static final class LandFit {
        int bands = 1;
        int across = 1;
        float scale = 1f;
    }

    private static LandFit fitLandPiles(int count, float fullW, float fullH, float laneW, float laneH) {
        int n = Math.max(1, count);
        LandFit fit = new LandFit();
        fit.bands = 1;
        fit.across = n;
        fit.scale = landUniqueScale(n, 1, fullW, fullH, laneW, laneH);
        if (n > 1 && landNeedsSecondRow(n, fullW, fullH, laneW)) {
            fit.bands = 2;
            fit.across = (n + 1) / 2;
            float h2 = laneH;
            if (Float.isNaN(h2) || h2 < 8f) h2 = 2f * fullH + 8f;
            fit.scale = landUniqueScale(fit.across, 2, fullW, fullH, laneW, h2);
        }
        if (fit.scale > 1f) fit.scale = 1f;
        if (fit.scale < LAND_MIN_SCALE) fit.scale = LAND_MIN_SCALE;
        return fit;
    }

    /** One row at minimum scale still covers the middle front. */
    private static boolean landNeedsSecondRow(int count, float fullW, float fullH, float laneW) {
        int n = Math.max(1, count);
        if (n <= 1) return false;
        float cw = fullW * LAND_MIN_SCALE;
        float ch = fullH * LAND_MIN_SCALE;
        float step = landFrontStep(cw, ch);
        float foot = landTiltFootprint(cw, ch);
        return (n - 1) * step + foot > Math.max(40f, laneW) + 0.5f;
    }

    /**
     * Scale so unique fronts fit with at most a title-bar sliver of overlap,
     * including the 15° tap box. Height is a second clamp when two rows share the pocket.
     */
    private static float landUniqueScale(int across, int bands, float fullW, float fullH,
                                          float laneW, float laneH) {
        int n = Math.max(1, across);
        int rows = Math.max(1, bands);
        float base = landTiltFootprint(fullW, fullH);
        if (base < 1f) return 1f;
        float scale = 1f;
        float comfort = n * base + Math.max(0, n - 1) * LAND_TILT_GAP;
        if (comfort > laneW) {
            float room = laneW + Math.max(0, n - 1) * LAND_TITLE_SLIVER;
            scale = room / (n * base);
        }
        if (!Float.isNaN(laneH) && laneH > 8f && fullH > 1f) {
            float gapLine = 8f;
            float byH = (laneH - Math.max(0, rows - 1) * gapLine) / (rows * fullH);
            if (byH < scale) scale = byH;
        }
        if (scale > 1f) scale = 1f;
        if (scale < LAND_MIN_SCALE) scale = LAND_MIN_SCALE;
        return scale;
    }

    /**
     * Centre spacing. Comfort (tilted width plus air) at low count. As N grows,
     * the step drops so tiles overlap, never below a clickable peek.
     */
    private static float landRowStep(int count, float cw, float ch, float laneW) {
        int n = Math.max(1, count);
        if (n <= 1) return 0f;
        float foot = landTiltFootprint(cw, ch);
        float comfort = foot + LAND_TILT_GAP;
        float step = (laneW - foot) / (n - 1);
        if (step > comfort) step = comfort;
        if (step < PILE_SLIVER) step = PILE_SLIVER;
        return step;
    }

    /**
     * Left-to-right seats. When every land fits in the first span, the group
     * centres there. Otherwise the row fills left, jumps the hand, and continues.
     */
    private static float landSeatX(int index, int count, float cw, float step,
                                   float left, float right, float handLeft, float handRight) {
        int n = Math.max(1, count);
        float half = cw / 2f;
        float foot = half * 2f;
        boolean gap = handRight > handLeft + 8f;
        float firstSpan = gap ? Math.max(foot, handLeft - left) : Math.max(foot, right - left);
        float used = n <= 1 ? foot : (n - 1) * step + foot;
        float x = left + half;
        if (used <= firstSpan + 0.5f) {
            x = left + (firstSpan - used) * 0.5f + half;
        }
        for (int i = 0; i < n; i++) {
            if (gap && x + half > handLeft && x - half < handRight) {
                x = handRight + half;
            }
            if (x + half > right) x = right - half;
            if (x - half < left) x = left + half;
            if (i == index) return x;
            x += step;
        }
        return Math.max(left + half, Math.min(right - half, x));
    }

    /**
     * Left edge of the creature rectangle. Middle {@link #ROW_WIDTH_FRAC} of the
     * window, not the whole screen and not the leftover strip left of the stack card.
     */
    public static float creatureBedLeft(float windowW) {
        float wide = Math.max(windowW, 1f);
        return Math.max(10f, wide * (0.5f - ROW_WIDTH_FRAC * 0.5f));
    }

    /**
     * Right edge of this player's creature rectangle: the middle band, cut back
     * only for that side's life/phase cluster and a live stack card.
     */
    public static float creatureBedRight(float windowW, float windowH, Side side,
                                          float clusterLeft, float stackLeft) {
        float wide = Math.max(windowW, 1f);
        float right = wide * (0.5f + ROW_WIDTH_FRAC * 0.5f);
        float cluster = clusterLeft;
        if (Float.isNaN(cluster) || cluster <= 0f) {
            StripOwner owner = side == Side.OPPONENT ? StripOwner.ENEMY : StripOwner.YOU;
            cluster = clusterAt(wide, Math.max(windowH, 1f), owner, 0f, 0f).left;
        }
        if (cluster - 10f < right) right = cluster - 10f;
        if (!Float.isNaN(stackLeft) && stackLeft < right) right = stackLeft;
        float left = creatureBedLeft(wide);
        if (right < left + 8f) right = left + 8f;
        return right;
    }

    /** Horizontal middle of this player's creature bed. Same rule for him and Ash. */
    public static float creatureBedCenterX(float windowW, float windowH, Side side,
                                            float clusterLeft, float stackLeft) {
        return (creatureBedLeft(windowW) + creatureBedRight(windowW, windowH, side, clusterLeft, stackLeft)) * 0.5f;
    }

    /**
     * Arrival 0 is the middle seat. 1 is one step right, 2 is one step left, and so on.
     */
    static int centerOutOffset(int arrival) {
        if (arrival <= 0) return 0;
        int rank = (arrival + 1) / 2;
        return (arrival & 1) == 1 ? rank : -rank;
    }

    /** Inner edge toward the gold line. His creatures ignore the left land pocket. */
    private static float creatureOuterEdge(float windowH, Side side) {
        return creatureOuterEdge(windowH, side, 0, Float.NaN, Float.NaN);
    }

    private static float creatureOuterEdge(float windowH, Side side,
                                           int landPiles, float landLaneLeft, float landLaneRight) {
        boolean you = side != Side.OPPONENT;
        float landH = landTileHeight(windowH);
        float laneW = 0f;
        if (!Float.isNaN(landLaneLeft) && !Float.isNaN(landLaneRight) && landLaneRight > landLaneLeft) {
            laneW = landLaneRight - landLaneLeft;
        }
        if (you) {
            if (landPiles > 0 && laneW > 8f) {
                return yourBoardOuterY(windowH) + landBlockHeight(landPiles, laneW, landTileWidth(windowH), landH) + 8f;
            }
            return yourCreatureOuter(windowH);
        }
        float landCy = rowCenterY(windowH, side, Row.LAND);
        if (landPiles > 1 && laneW > 8f) {
            float block = landBlockHeight(landPiles, laneW, landTileWidth(windowH), landH);
            return landCy + landH / 2f - block - 8f;
        }
        return landCy - landH / 2f - 8f;
    }

    /**
     * 1 keeps the full tile. Smaller means the row was wider or taller than the open board.
     * {@code across} is how many tiles share one line. {@code bands} is 1, or 2 when a second line is needed.
     */
    private static float creatureRowScale(int across, int bands, float fullW, float fullH,
                                           float laneW, float laneH) {
        int n = Math.max(1, across);
        int rows = Math.max(1, bands);
        double rad = Math.toRadians(TAP_DEG);
        float base = (float) (Math.abs(fullW * Math.cos(rad)) + Math.abs(fullH * Math.sin(rad)));
        if (base < 1f) return 1f;
        float gap = 14f;
        float widthNeed = n * base + Math.max(0, n - 1) * gap;
        float scale = 1f;
        if (widthNeed > laneW) {
            float gaps = Math.max(0, n - 1) * gap;
            float room = laneW - gaps;
            if (room < n * 28f) {
                gaps = Math.max(0, n - 1) * 4f;
                room = Math.max(n * 8f, laneW - gaps);
            }
            scale = room / (n * base);
        }
        float gapLine = 8f;
        float heightNeed = rows * fullH + Math.max(0, rows - 1) * gapLine;
        if (heightNeed > laneH) {
            float room = laneH - Math.max(0, rows - 1) * gapLine;
            float byHeight = room / (rows * fullH);
            if (byHeight < scale) scale = byHeight;
        }
        return scale;
    }

    /** How many other noncreatures fit in one corner row before the next row starts. */
    public static int pilesPerRow(float windowW, float windowH) {
        float cw = boardTileWidth(windowH);
        float gap = 12f;
        float margin = 10f;
        float pocket = Math.max(windowW, 1f) * (1f - HandLayout.MAX_HAND_WIDTH_FRAC) / 2f - margin - 8f;
        if (pocket < cw) return 1;
        return Math.max(1, (int) ((pocket + gap) / (cw + gap)));
    }

    /** Height of the title strip left showing when the same card is stacked. */
    public static float pileTitlePeek(float windowH) {
        return boardNameBand(windowH);
    }

    /**
     * One copy in a same-name stack. {@code copyIndex} 0 is the front card, at the bottom of
     * that stack. Each later copy sits one title higher, so the top of every card stays visible.
     * Different names use a different {@code pileColumn} and do not overlap.
     */
    public static HandLayout.Pose namedStack(float windowW, float windowH, Side side, Row row,
                                              int pileColumn, float frontCy, int copyIndex, boolean tapped) {
        if (row == Row.LAND) {
            return landInRow(windowW, windowH, side, pileColumn, Math.max(1, pileColumn + 1),
                    tapped, Float.NaN, Float.NaN);
        }
        float ch = boardTileHeight(windowH);
        float cw = boardTileWidth(windowH);
        float gap = 12f;
        float margin = 10f;
        float cx = row == Row.OTHER
                ? windowW - margin - cw / 2f - pileColumn * (cw + gap)
                : margin + cw / 2f + pileColumn * (cw + gap);
        float peek = pileTitlePeek(windowH);
        float cy = frontCy + Math.max(0, copyIndex) * peek;
        float rot = tapped ? tappedRotation(side) : 0f;
        return new HandLayout.Pose(cx, cy, cw, ch, rot, 1f);
    }

    /**
     * Aura tucked behind its host. {@code index} 0 is the title band nearest the host.
     * Each later aura sits one title further along the card's up edge, so every name
     * stays readable and none of them covers the host's name, mana cost, or power and toughness.
     * Draw the host after these cards. The host covers everything except the title bands.
     */
    public static HandLayout.Pose auraBehindHost(float hostCx, float hostCy, float hostW, float hostH,
                                                  float hostRotDeg, int index, float titlePeek) {
        float peek = Math.max(1f, titlePeek);
        float offset = (Math.max(0, index) + 1) * peek;
        double rad = Math.toRadians(hostRotDeg);
        float sin = (float) Math.sin(rad);
        float cos = (float) Math.cos(rad);
        float ox = -offset * sin;
        float oy = offset * cos;
        return new HandLayout.Pose(hostCx + ox, hostCy + oy, hostW, hostH, hostRotDeg, 1f);
    }

    /**
     * The title band that peeks past the host. Hit-testing uses this sliver.
     * The rest of the aura card is under the host and is not a target.
     */
    public static HandLayout.Pose auraTitleSliver(HandLayout.Pose aura, float titlePeek) {
        float band = Math.max(1f, Math.min(Math.max(1f, titlePeek), Math.max(1f, aura.h)));
        float ly = aura.h / 2f - band / 2f;
        double rad = Math.toRadians(aura.rotationDeg);
        float sin = (float) Math.sin(rad);
        float cos = (float) Math.cos(rad);
        float ox = -ly * sin;
        float oy = ly * cos;
        return new HandLayout.Pose(aura.cx + ox, aura.cy + oy, aura.w, band, aura.rotationDeg, 1f);
    }

    /**
     * Every tapped card leans to Steve's right on the monitor. About 15 degrees.
     * libGDX rotation is counter-clockwise, so that lean is negative.
     * Both players use the same angle. Board cards are drawn upright, not spun to face Ash,
     * so her cards do not need a second flip to keep the top moving toward the right edge.
     */
    public static float tappedRotation(Side side) {
        return -TAP_DEG;
    }

    /**
     * Vertical rest for a creature with flying. {@code groundCy} is the ground-row center
     * after the attack slide. The lift is up the monitor. Clamped so the card stays on its
     * half: under the gold line for him, under the land row for Ash.
     */
    public static float flyRestY(float groundCy, float cardH, float windowH, Side side) {
        float lift = flyLift(windowH);
        float y = groundCy + lift;
        float half = Math.max(1f, cardH) / 2f;
        if (side == Side.OPPONENT) {
            float ceiling = creatureOuterEdge(windowH, side) - 4f;
            if (y + half > ceiling) y = ceiling - half;
            float floor = halfInner(windowH, side) + 4f;
            if (y - half < floor) y = floor + half;
        } else {
            float ceiling = halfInner(windowH, side) - 4f;
            if (y + half > ceiling) y = ceiling - half;
            float floor = creatureOuterEdge(windowH, side) + 4f;
            if (y - half < floor) y = floor + half;
        }
        if (y < groundCy) y = groundCy;
        return y;
    }

    /** Pixels a flier sits above the ground tile. About 21% of the tile, 20–28 px. */
    public static float flyLift(float windowH) {
        float tile = boardTileHeight(Math.max(windowH, 1f));
        float lift = tile * FLY_LIFT_FRAC;
        if (lift < 20f) lift = 20f;
        if (lift > 28f) lift = 28f;
        return lift;
    }

    /**
     * Centre spacing for a row of tiles. A 15-degree tilt widens each card,
     * and a small gap stays between those tilted neighbours.
     */
    public static float tiltedStep(float cw, float ch) {
        double rad = Math.toRadians(TAP_DEG);
        float footprint = (float) (Math.abs(cw * Math.cos(rad)) + Math.abs(ch * Math.sin(rad)));
        return footprint + 14f;
    }

    /**
     * Depth of the token arch at horizontal fraction {@code u} (0 left, 1 right),
     * as a fraction of the cropped tile height. The center stays full height.
     */
    public static float tokenArchFraction(float u) {
        float uClamped = Math.max(0f, Math.min(1f, u));
        float aspect = HandLayout.CARD_ASPECT / BOARD_FACE_CROP;
        float halfW = aspect / 2f;
        float depth = TOKEN_ARCH_FRAC;
        float radius = (depth * depth + halfW * halfW) / (2f * depth);
        float x = (uClamped - 0.5f) * aspect;
        float inside = radius * radius - x * x;
        if (inside <= 0f) return depth;
        return radius - (float) Math.sqrt(inside);
    }

    /** {@code u} and {@code v} are 0..1 across the cropped tile, origin at the top-left. */
    public static boolean tokenTileContains(float u, float v) {
        if (u < 0f || u > 1f || v < 0f || v > 1f) return false;
        return v >= tokenArchFraction(u);
    }

    /**
     * How far the arch cuts in from the left at height fraction {@code v}
     * (0 at the top, 1 at the bottom). The right edge is {@code 1 - inset}.
     * Below the arch the inset is zero, so the sides and the bottom stay square.
     */
    public static float tokenArchInset(float v) {
        float depth = TOKEN_ARCH_FRAC;
        float vClamped = Math.max(0f, Math.min(1f, v));
        if (vClamped >= depth) return 0f;
        float aspect = HandLayout.CARD_ASPECT / BOARD_FACE_CROP;
        float halfW = aspect / 2f;
        float radius = (depth * depth + halfW * halfW) / (2f * depth);
        float rise = radius - vClamped;
        float inside = radius * radius - rise * rise;
        if (inside <= 0f) return 0.5f;
        float x = (float) Math.sqrt(inside);
        float u = 0.5f - x / aspect;
        if (u < 0f) return 0f;
        if (u > 0.5f) return 0.5f;
        return u;
    }

    /**
     * Name-and-type stand-in when a scan is missing. Short enough to read, and far
     * smaller than {@link #stackShowcase}. A card that has art keeps the full frame.
     */
    public static float missingArtWidth(float windowH) {
        return Math.min(320f, Math.max(260f, Math.max(windowH, 1f) * 0.24f));
    }

    public static float missingArtHeight(float windowH) {
        return Math.min(156f, Math.max(104f, Math.max(windowH, 1f) * 0.145f));
    }

    /** Same right edge as the big stack card, at the compact missing-art size. */
    public static HandLayout.Pose missingArtPanel(float windowW, float windowH) {
        HandLayout.Pose full = stackShowcase(windowW, windowH);
        float safeH = Math.max(windowH, 1f);
        float cardW = missingArtWidth(safeH);
        float cardH = missingArtHeight(safeH);
        float room = Math.max(windowW, 1f) - 16f;
        if (cardW > room) cardW = Math.max(120f, room);
        float right = full.cx + full.w / 2f;
        float cx = right - cardW / 2f;
        if (cx - cardW / 2f < 8f) cx = 8f + cardW / 2f;
        float cy = full.cy;
        float minY = cardH / 2f + 8f;
        float maxY = safeH - cardH / 2f - 8f;
        if (cy < minY) cy = minY;
        if (cy > maxY) cy = maxY;
        return new HandLayout.Pose(cx, cy, cardW, cardH, 0f, 1f);
    }

    /**
     * Large hand-sized card for the top of the stack. It sits in the right-hand
     * stack well, clear of both life widgets, the command slots, and the
     * planeswalker pocket — not a half-table plate over the creature beds.
     * Cards with art keep this full frame; missing art uses {@link #missingArtPanel}.
     */
    public static HandLayout.Pose stackShowcase(float windowW, float windowH) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        PlayerCluster you = clusterAt(safeW, safeH, StripOwner.YOU, 0f, 0f);
        PlayerCluster them = clusterAt(safeW, safeH, StripOwner.ENEMY, 0f, 0f);
        float youClear = you.left - 16f;
        float themClear = them.left - 16f;
        float right = Math.min(youClear, themClear);
        float pocketLeft = planeswalkerPocketLeft(safeW);
        if (pocketLeft - 12f < right) right = pocketLeft - 12f;
        float raised = Math.min(HandLayout.hoverRaisedHeight(safeH), safeH * 0.56f);
        float cardH = raised * STACK_SHOWCASE_SCALE;
        float cardW = cardH * HandLayout.CARD_ASPECT;
        float cx = right - cardW / 2f;
        if (cx - cardW / 2f < 8f) {
            cardW = Math.max(160f, right - 8f);
            cardH = cardW / HandLayout.CARD_ASPECT;
            cx = right - cardW / 2f;
        }
        float cy = safeH / 2f;
        float minY = cardH / 2f + 8f;
        float maxY = safeH - cardH / 2f - 8f;
        if (cy < minY) cy = minY;
        if (cy > maxY) cy = maxY;
        return new HandLayout.Pose(cx, cy, cardW, cardH, 0f, 1f);
    }

    /**
     * Small tiles for emblems, City's Blessing, The Ring, and the current dungeon.
     * They sit beside the life number on the edge chrome, not on the land strip.
     * An empty count returns no boxes, so the row takes no space.
     */
    public static HandLayout.Pose[] emblemRow(float windowW, float windowH, Side side,
                                               float lifeX, float lifeY, float lifeW, float lifeH,
                                               int count) {
        if (count <= 0) return new HandLayout.Pose[0];
        float tileH = 32f;
        float tileW = 112f;
        float gap = 6f;
        float numberRight = lifeX - lifeW / 2f + 88f;
        float right = Math.max(windowW, 1f) - 8f;
        float avail = right - (numberRight + 8f);
        if (avail < 72f) {
            tileW = Math.max(72f, avail);
        }
        int per = Math.max(1, (int) ((avail + gap) / (tileW + gap)));
        boolean you = side != Side.OPPONENT;
        HandLayout.Pose[] poses = new HandLayout.Pose[count];
        for (int i = 0; i < count; i++) {
            int band = i / per;
            int col = i % per;
            float left = numberRight + 8f;
            float cx = left + tileW / 2f + col * (tileW + gap);
            if (cx + tileW / 2f > right) cx = right - tileW / 2f;
            float cy = lifeY + (you ? -1f : 1f) * band * (tileH + 4f);
            poses[i] = new HandLayout.Pose(cx, cy, tileW, tileH, 0f, 1f);
        }
        return poses;
    }

    /**
     * libGDX reports pointer Y downward from the top of the window.
     * Layout space grows upward from the bottom, so one subtraction is the conversion
     * for drawing, hit-testing, and the targeting-arrow tip.
     */
    public static float screenYToLayoutY(float windowH, float screenY) {
        return windowH - screenY;
    }

    /**
     * Index of the rectangle that contains the pointer, testing from the last entry
     * (drawn on top) toward the first. A nearby rectangle that does not contain the
     * point is not a hit, so an empty board does not stick to the nearest card.
     */
    public static int stickIndexIfInside(float px, float py, float[] cx, float[] cy, float[] w, float[] h) {
        if (cx == null || cy == null || w == null || h == null) {
            return -1;
        }
        int n = Math.min(Math.min(cx.length, cy.length), Math.min(w.length, h.length));
        for (int i = n - 1; i >= 0; i--) {
            if (Math.abs(px - cx[i]) <= w[i] / 2f && Math.abs(py - cy[i]) <= h[i] / 2f) {
                return i;
            }
        }
        return -1;
    }

    public static boolean inOpponentHalf(float windowH, float pointerY) {
        return pointerY >= ATTACK_LINE_FRAC * windowH;
    }

    /**
     * Attack declaration. The whole opponent half counts, and a release near their
     * life badge still counts when the pointer stops short of the line.
     */
    public static boolean declaresAttack(float windowH, float pointerX, float pointerY,
                                          float badgeX, float badgeY) {
        if (inOpponentHalf(windowH, pointerY)) {
            return true;
        }
        return within(pointerX, pointerY, badgeX, badgeY, SNAP_RADIUS_FRAC * windowH);
    }

    /** Index of the nearest point within {@code radius}, or -1. */
    public static int snapNearest(float px, float py, float[] xs, float[] ys, float radius) {
        if (xs == null || ys == null || xs.length == 0 || xs.length != ys.length || radius < 0f) {
            return -1;
        }
        int best = -1;
        float bestD = radius;
        for (int i = 0; i < xs.length; i++) {
            float d = dist(px, py, xs[i], ys[i]);
            if (d <= bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    public static int snapBlocker(float windowH, float px, float py, float[] attackerX, float[] attackerY) {
        return snapNearest(px, py, attackerX, attackerY, SNAP_RADIUS_FRAC * windowH);
    }

    /** Movement below the hand prototype's drag threshold is tremor, not a drag. */
    public static boolean ignoredJitter(float movePx) {
        return movePx < HandLayout.DRAG_THRESHOLD_PX;
    }

    public static boolean canAttack(Kind kind, boolean tapped, boolean summoningSick) {
        return kind == Kind.CREATURE && !tapped && !summoningSick;
    }

    public static boolean canActivate(Kind kind, boolean tapped, boolean summoningSick) {
        return kind == Kind.CREATURE && !tapped && !summoningSick;
    }

    /** A blank choice still prompts; a chosen type stays printed as that word. */
    public static String printedChoice(String chosenType) {
        if (chosenType == null || chosenType.isBlank()) {
            return "Choose a creature type";
        }
        return chosenType;
    }

    /**
     * Word on the board tile. Blank until a type is chosen.
     * Two names join with a slash. Not a card-name match.
     */
    public static String chosenTypeLabel(String chosenType, String chosenType2) {
        boolean first = chosenType != null && !chosenType.isBlank();
        boolean second = chosenType2 != null && !chosenType2.isBlank();
        if (first && second) return chosenType + "/" + chosenType2;
        if (first) return chosenType;
        if (second) return chosenType2;
        return "";
    }

    public static int shownPower(int base, int buff, int debuff, int plusCounters) {
        return base + buff + plusCounters - debuff;
    }

    public static int shownToughness(int base, int buff, int debuff, int plusCounters) {
        return shownPower(base, buff, debuff, plusCounters);
    }

    public static String abilityPreview(String source, String target, int countersAfter) {
        return source + " taps. " + target + " will show " + countersAfter + " +1/+1.";
    }

    public static String shockOnCreature(String name, int toughness, int damageAlready, int incoming) {
        int total = damageAlready + incoming;
        if (total >= toughness) {
            return name + " takes " + incoming + " and will die.";
        }
        return name + " takes " + incoming + " and survives.";
    }

    public static String lifeChange(String who, int before, int damage) {
        return who + " " + before + " -> " + (before - damage);
    }

    /** Number color only. Red is lethal attackers and wins over the starting-life colors. */
    public enum LifeTint { GREEN, GOLD, YELLOW, RED }

    /**
     * Green at the duel’s starting life, gold above it, yellow below it.
     * Red when creatures are attacking that player and their damage is at least current life.
     */
    public static LifeTint lifeTint(int life, int startingLife, int incomingAttackDamage, boolean underAttack) {
        if (underAttack && incomingAttackDamage >= life) return LifeTint.RED;
        if (life > startingLife) return LifeTint.GOLD;
        if (life < startingLife) return LifeTint.YELLOW;
        return LifeTint.GREEN;
    }

    /** Oracle-style line for a scripted card. Never blank. */
    public static String fullTextFor(String cardName) {
        if ("Shock".equals(cardName)) {
            return "Shock deals 2 damage to any target.";
        }
        if ("Impulse".equals(cardName)) {
            return "Look at the top four cards of your library. Put one of them into your hand and the rest on the bottom of your library in any order.";
        }
        if ("Cavern of Souls".equals(cardName)) {
            return "As Cavern of Souls enters the battlefield, choose a creature type. "
                    + "{T}: Add {C}. {T}: Add one mana of any color. Spend this mana only to cast a creature spell "
                    + "of the chosen type, and that spell can't be countered.";
        }
        if (cardName == null || cardName.isBlank()) {
            return "No rules text.";
        }
        return cardName;
    }

    /** A cast spell is a log entry. It does not take a card-sized pose on the board. */
    public static StackEntry castSpell(String cardName, boolean needsTarget) {
        return new StackEntry(cardName, fullTextFor(cardName), needsTarget, null);
    }

    public static StackEntry chooseTarget(StackEntry entry, String target) {
        String name = entry == null ? "" : entry.name;
        String text = entry == null ? "" : entry.fullText;
        boolean needs = entry != null && entry.needsTarget;
        return new StackEntry(name, text, needs, target);
    }

    /**
     * One click assigns the target. There is no confirm flag to lock it, and the click
     * does not resolve the spell.
     */
    public static TargetAssignment clickTarget(StackEntry entry, String target) {
        return new TargetAssignment(chooseTarget(entry, target), false);
    }

    /** Outcome line once a target is chosen, for example "Shock deals 2 to Grizzly". */
    public static String stackOutcome(String spell, String target) {
        String name = spell == null || spell.isBlank() ? "Spell" : spell;
        String who = target == null || target.isBlank() ? "a target" : target;
        return name + " deals " + SHOCK_DAMAGE + " to " + who;
    }

    /** A permanent or sorcery joins only an empty stack. An instant may always join. */
    public static boolean mayCast(Speed speed, int stackSize) {
        int n = Math.max(0, stackSize);
        return speed == Speed.INSTANT || n == 0;
    }

    /** Push a spell. A refused cast leaves the stack unchanged. The new spell is the top. */
    public static List<StackEntry> castOnto(List<StackEntry> stack, Speed speed,
                                             String cardName, boolean needsTarget) {
        List<StackEntry> base = stack == null ? List.of() : stack;
        if (!mayCast(speed, base.size())) return List.copyOf(base);
        ArrayList<StackEntry> next = new ArrayList<>(base);
        next.add(castSpell(cardName, needsTarget));
        return List.copyOf(next);
    }

    /**
     * One click writes the target onto the top spell and does not resolve it.
     * A later click on another legal target replaces that choice.
     */
    public static List<StackEntry> assignTopTarget(List<StackEntry> stack, String target) {
        if (stack == null || stack.isEmpty()) return List.of();
        StackEntry top = stack.get(stack.size() - 1);
        if (!top.needsTarget) return List.copyOf(stack);
        ArrayList<StackEntry> next = new ArrayList<>(stack.subList(0, stack.size() - 1));
        next.add(clickTarget(top, target).entry);
        return List.copyOf(next);
    }

    /** Resolve only the top object. The spell under it stays on the stack. */
    public static List<StackEntry> resolveTop(List<StackEntry> stack) {
        if (stack == null || stack.isEmpty()) return List.of();
        return List.copyOf(stack.subList(0, stack.size() - 1));
    }

    /** Stack art is the thumbnail. A full card must not sit over the table. */
    public static boolean stackArtCoversBoard(float windowW, float windowH) {
        return occupiesFullCardOverBoard(stackThumbnail(windowW, windowH), windowW, windowH);
    }

    public static boolean waitingForTarget(StackEntry entry) {
        return entry != null && entry.needsTarget && (entry.target == null || entry.target.isBlank());
    }

    public static float stackColumnCenterX(float windowW) {
        return STACK_COLUMN_X_FRAC * windowW;
    }

    public static float stackColumnWidth(float windowW) {
        return STACK_COLUMN_W_FRAC * windowW;
    }

    public static float stackRowHeight(float windowH, boolean examined) {
        return (examined ? STACK_ROW_EXAMINED_FRAC : STACK_ROW_FRAC) * windowH;
    }

    /** Small art beside the log text. Not a full card over the table. */
    public static HandLayout.Pose stackThumbnail(float windowW, float windowH) {
        float ch = cardHeight(windowH) * STACK_THUMB_FRAC;
        float cw = ch * HandLayout.CARD_ASPECT;
        return new HandLayout.Pose(stackColumnCenterX(windowW), STACK_TOP_FRAC * windowH, cw, ch, 0f, 1f);
    }

    /** True when a pose is a full battlefield card sitting over the centred table. */
    public static boolean occupiesFullCardOverBoard(HandLayout.Pose pose, float windowW, float windowH) {
        if (pose == null) {
            return false;
        }
        boolean fullSize = pose.h >= cardHeight(windowH) * 0.90f;
        boolean overTable = pose.cx > windowW * 0.25f && pose.cx < windowW * 0.75f
                && pose.cy > windowH * 0.18f && pose.cy < windowH * 0.90f;
        return fullSize && overTable;
    }

    /** One click's target. {@code confirmRequired} stays false: the click does not lock a confirm step. */
    public static final class TargetAssignment {
        public final StackEntry entry;
        public final boolean confirmRequired;

        public TargetAssignment(StackEntry entry, boolean confirmRequired) {
            this.entry = entry;
            this.confirmRequired = confirmRequired;
        }
    }

    /** One line in the stack log. {@code target} is null until the player chooses. */
    public static final class StackEntry {
        public final String name;
        public final String fullText;
        public final boolean needsTarget;
        public final String target;

        public StackEntry(String name, String fullText, boolean needsTarget, String target) {
            this.name = name == null ? "" : name;
            this.fullText = fullText == null ? "" : fullText;
            this.needsTarget = needsTarget;
            this.target = target;
        }
    }

    public static final class CombatPreview {
        public final boolean attackerDies;
        public final boolean blockerDies;
        public final int lifeLoss;
        public final String summary;

        public CombatPreview(boolean attackerDies, boolean blockerDies, int lifeLoss, String summary) {
            this.attackerDies = attackerDies;
            this.blockerDies = blockerDies;
            this.lifeLoss = lifeLoss;
            this.summary = summary;
        }
    }

    public static CombatPreview previewBlock(int atkPower, int atkToughness, int blkPower, int blkToughness) {
        boolean attackerDies = blkPower >= atkToughness;
        boolean blockerDies = atkPower >= blkToughness;
        String summary = (attackerDies ? "Attacker dies. " : "Attacker lives. ")
                + (blockerDies ? "Blocker dies." : "Blocker lives.");
        return new CombatPreview(attackerDies, blockerDies, 0, summary);
    }

    public static CombatPreview previewUnblocked(String defender, int power) {
        int loss = Math.max(0, power);
        return new CombatPreview(false, false, loss, defender + " loses " + loss + " life.");
    }

    public static StripPhase[] stripPhases() {
        return StripPhase.values();
    }

    /** How many chips one edge line draws: UP DR M1 BC DA DB FS CD EC M2 ET. */
    public static int dashboardChipCount() {
        return EDGE_STRIP_CHIPS;
    }

    /**
     * Painted chips, left to right, both players. Cleanup is a real step and is not a chip.
     * Order is Forge's turn: combat between first main and second main.
     */
    public static StripPhase[] visibleStripPhases() {
        return new StripPhase[] {
                StripPhase.UPKEEP, StripPhase.DRAW, StripPhase.MAIN_1,
                StripPhase.BEGIN_COMBAT, StripPhase.DECLARE_ATTACKERS, StripPhase.DECLARE_BLOCKERS,
                StripPhase.FIRST_STRIKE, StripPhase.COMBAT_DAMAGE, StripPhase.END_COMBAT,
                StripPhase.MAIN_2, StripPhase.END_STEP
        };
    }

    /** Non-combat steps that still have a chip. Cleanup is not on the strip. */
    public static StripPhase[] nonCombatSteps() {
        return new StripPhase[] {
                StripPhase.UPKEEP, StripPhase.DRAW, StripPhase.MAIN_1,
                StripPhase.MAIN_2, StripPhase.END_STEP
        };
    }

    /** True for BC through EC. Each of those is its own chip. */
    public static boolean combatStep(StripPhase phase) {
        if (phase == null) return false;
        return switch (phase) {
            case BEGIN_COMBAT, DECLARE_ATTACKERS, DECLARE_BLOCKERS,
                    FIRST_STRIKE, COMBAT_DAMAGE, END_COMBAT -> true;
            default -> false;
        };
    }

    /** Row B. Combat steps, in Forge's order. */
    public static StripPhase[] combatSteps() {
        return new StripPhase[] {
                StripPhase.BEGIN_COMBAT, StripPhase.DECLARE_ATTACKERS, StripPhase.DECLARE_BLOCKERS,
                StripPhase.FIRST_STRIKE, StripPhase.COMBAT_DAMAGE, StripPhase.END_COMBAT
        };
    }

    public static Theme[] themes() {
        return THEMES.clone();
    }

    public static Theme themeById(String id) {
        if (id == null) return null;
        for (Theme theme : THEMES) {
            if (theme.id.equalsIgnoreCase(id.trim())) return theme;
        }
        return null;
    }

    /** Order does not matter. {@code "white","black"} and {@code "black","white"} are one theme. */
    public static Theme themeForColors(String first, String second) {
        String id = colorPairId(first, second);
        if (id.isEmpty()) return null;
        return themeById(id);
    }

    /** Your half. A calm battlefield, not a deck color. */
    public static Theme yourBattlefieldTheme() {
        Theme theme = themeById("green-gold");
        return theme == null ? THEMES[0] : theme;
    }

    /**
     * Scripted enemy board: Mountain, Swamp, Ogre, Sprite.
     * The pair is red and black, from Mountain and Swamp. Ogre is red. That is the enemy half.
     */
    public static Theme scriptedEnemyTheme() {
        Theme theme = themeForColors("red", "black");
        return theme == null ? THEMES[0] : theme;
    }

    public static float dividerY(float windowH) {
        return DIVIDER_FRAC * Math.max(windowH, 1f);
    }

    public static boolean inYourHalf(float windowH, float layoutY) {
        return layoutY < dividerY(windowH);
    }

    /** Width of one sleeved card in the library pile. */
    public static float libraryPileWidth() {
        return LIBRARY_PILE_H * HandLayout.CARD_ASPECT;
    }

    public static float playerClusterWidth() {
        return CLUSTER_LIFE_W + CLUSTER_CHIP_GAP + libraryPileWidth();
    }

    /** Life and the zone family. The edge strip and action column sit on the chrome. */
    public static float playerClusterHeight(StripOwner owner) {
        return CLUSTER_LIFE_H + CLUSTER_PAD + CLUSTER_ZONE_H;
    }

    public static float playerClusterHeight() {
        return playerClusterHeight(StripOwner.YOU);
    }

    /**
     * His life/avatar sits above the resting hand, centered. Ash's life stays
     * top-center. Both phase lines sit on her top edge: his chips left of her
     * badge, hers right of it. Library, grave, and exile stay a left-side deck
     * family so they do not cover the land row.
     */
    public static PlayerCluster clusterAt(float windowW, float windowH, StripOwner owner, float dx, float dy) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        StripOwner who = owner == null ? StripOwner.YOU : owner;
        float cw = playerClusterWidth();
        float ch = playerClusterHeight(who);
        float right = safeW - CLUSTER_MARGIN + dx;
        float left = right - cw;
        float bottom = defaultClusterBottom(safeH, who, ch) + dy;
        return withEdgeChrome(buildCluster(who, left, bottom, cw), safeW, safeH, dx, dy);
    }

    /** Right edge of the left-side library / grave / exile family. */
    public static float deckFamilyRight(float windowW, float windowH, StripOwner owner, float dx) {
        float pileW = libraryPileWidth();
        float zoneW = (CLUSTER_LIFE_W - CLUSTER_CHIP_GAP) / 2f;
        return CLUSTER_MARGIN + dx + pileW + CLUSTER_CHIP_GAP + zoneW + CLUSTER_CHIP_GAP + zoneW;
    }

    /**
     * His life above the resting fan. Ash's life on her top-center edge.
     * Both phase lines share that top edge and never sit on the bottom.
     * Action buttons stay on the right-edge column, not under a strip.
     */
    private static PlayerCluster withEdgeChrome(PlayerCluster raw, float windowW, float windowH,
                                                float dx, float dy) {
        if (raw == null) return null;
        boolean you = raw.owner != StripOwner.ENEMY;
        WidgetBox life = edgeLifeBox(windowW, windowH, raw.owner);
        float pileW = libraryPileWidth();
        float pileH = LIBRARY_PILE_H;
        float zoneW = (CLUSTER_LIFE_W - CLUSTER_CHIP_GAP) / 2f;
        float libCx = CLUSTER_MARGIN + dx + pileW / 2f;
        float libCy = you ? 8f + pileH / 2f + dy : windowH - 8f - pileH / 2f + dy;
        float split = dividerY(windowH);
        if (you) {
            if (libCy - pileH / 2f < 4f) libCy = 4f + pileH / 2f;
            if (libCy + pileH / 2f > split - 4f) libCy = split - 4f - pileH / 2f;
        } else {
            if (libCy - pileH / 2f < split + 4f) libCy = split + 4f + pileH / 2f;
            if (libCy + pileH / 2f > windowH - 4f) libCy = windowH - 4f - pileH / 2f;
        }
        WidgetBox library = new WidgetBox(raw.owner, WidgetPart.LIBRARY, libCx, libCy, pileW, pileH);
        float graveCx = libCx + pileW / 2f + CLUSTER_CHIP_GAP + zoneW / 2f;
        WidgetBox grave = new WidgetBox(raw.owner, WidgetPart.GRAVE, graveCx, libCy, zoneW, CLUSTER_ZONE_H);
        float exileCx = graveCx + zoneW / 2f + CLUSTER_CHIP_GAP + zoneW / 2f;
        WidgetBox exile = new WidgetBox(raw.owner, WidgetPart.EXILE, exileCx, libCy, zoneW, CLUSTER_ZONE_H);
        PhaseBox[] line = ownerEdgeStrip(raw.owner, windowW, windowH, life);
        ActionBox[] actions = you ? rightActionColumn(windowW) : new ActionBox[0];
        return new PlayerCluster(raw.owner, raw.left, raw.bottom, raw.right, raw.top,
                life, grave, exile, library, line, new PhaseBox[0], actions);
    }

    /** Centered life badge. His sits above the hand; hers stays on the top edge. */
    public static WidgetBox edgeLifeBox(float windowW, float windowH, StripOwner owner) {
        StripOwner who = owner == null ? StripOwner.YOU : owner;
        boolean you = who != StripOwner.ENEMY;
        float lifeX = Math.max(windowW, 1f) * 0.5f;
        float lifeY = you ? yourLifeCenterY(windowH) : enemyLifeCenterY(windowH);
        return new WidgetBox(who, WidgetPart.AVATAR_LIFE, lifeX, lifeY, CLUSTER_LIFE_W, CLUSTER_LIFE_H);
    }

    /**
     * Center Y of his life box. The fan bottoms hang off the window; titles stay
     * readable. The badge sits in the gap above those tops, not on the cards.
     */
    public static float yourLifeCenterY(float windowH) {
        float handTop = HandLayout.REST_TOP_FRAC * Math.max(windowH, 1f);
        return handTop + LIFE_ABOVE_HAND_GAP + CLUSTER_LIFE_H / 2f;
    }

    /** Center Y of Ash's life box. Top-center, same 8px air as before. */
    public static float enemyLifeCenterY(float windowH) {
        return Math.max(windowH, 1f) - 8f - CLUSTER_LIFE_H / 2f;
    }

    /**
     * Vertical center of the player name on the lower interior of that life badge.
     * Below the life digits, still inside the box — never above the top badge or
     * down into the hand.
     */
    public static float lifeNameCenterY(float lifeCenterY, float lifeH, float nameH) {
        float h = Math.max(8f, nameH);
        float boxH = Math.max(h + 6f, lifeH);
        float badgeBottom = lifeCenterY - boxH / 2f;
        return badgeBottom + 5f + h / 2f;
    }

    /**
     * Keep the whole cluster inside its own half and inside the window.
     * Returns the delta that should be stored for the rest of the session.
     */
    public static float[] clampClusterDelta(float windowW, float windowH, StripOwner owner, float dx, float dy) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        StripOwner who = owner == null ? StripOwner.YOU : owner;
        PlayerCluster base = clusterAt(safeW, safeH, who, 0f, 0f);
        float minX = 4f;
        float maxX = safeW - 4f;
        float split = dividerY(safeH);
        float minY = who == StripOwner.YOU ? 4f : split + 4f;
        float maxY = who == StripOwner.YOU ? split - 4f : safeH - 4f;
        float shiftX = 0f;
        float shiftY = 0f;
        float left = base.left + dx;
        float right = base.right + dx;
        float bottom = base.bottom + dy;
        float top = base.top + dy;
        if (left < minX) shiftX = minX - left;
        else if (right > maxX) shiftX = maxX - right;
        if (bottom < minY) shiftY = minY - bottom;
        else if (top > maxY) shiftY = maxY - top;
        return new float[] { dx + shiftX, dy + shiftY };
    }

    public static WidgetBox[] widgetBoxes(float windowW, float windowH,
                                           float youDx, float youDy, float enemyDx, float enemyDy) {
        PlayerCluster enemy = clusterAt(windowW, windowH, StripOwner.ENEMY, enemyDx, enemyDy);
        PlayerCluster you = clusterAt(windowW, windowH, StripOwner.YOU, youDx, youDy);
        return new WidgetBox[] {
                enemy.life, enemy.grave, enemy.exile, enemy.library,
                you.life, you.grave, you.exile, you.library
        };
    }

    /** Index into {@link #widgetBoxes}, or -1. Grave, exile, and library are separate boxes. */
    public static int widgetIndexAt(float windowW, float windowH, float px, float py,
                                     float youDx, float youDy, float enemyDx, float enemyDy) {
        WidgetBox[] boxes = widgetBoxes(windowW, windowH, youDx, youDy, enemyDx, enemyDy);
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i].contains(px, py)) return i;
        }
        return -1;
    }

    /** Index into your action row, or -1. The enemy widget has no row. */
    public static int actionIndexAt(float windowW, float windowH, float px, float py, float youDx, float youDy) {
        PlayerCluster you = clusterAt(windowW, windowH, StripOwner.YOU, youDx, youDy);
        ActionBox[] row = you.actions;
        for (int i = 0; i < row.length; i++) {
            if (row[i].contains(px, py)) return i;
        }
        return -1;
    }

    /**
     * One floating zone viewer: search, type chips, and a card grid.
     * Sits in that player's half, to the left of the cluster, and stays smaller than the window.
     */
    public static final class ZoneGrid {
        public final HandLayout.Pose panel;
        public final HandLayout.Pose search;
        public final HandLayout.Pose[] chips;
        public final float cardW;
        public final float cardH;
        public final int columns;
        public final int rows;

        ZoneGrid(HandLayout.Pose panel, HandLayout.Pose search, HandLayout.Pose[] chips,
                 float cardW, float cardH, int columns, int rows, float scroll, float maxScroll,
                 float viewLeft, float viewBottom, float viewWidth, float viewHeight,
                 HandLayout.Pose scrollTrack) {
            this.panel = panel;
            this.search = search;
            this.chips = chips;
            this.cardW = cardW;
            this.cardH = cardH;
            this.columns = columns;
            this.rows = rows;
            this.scroll = scroll;
            this.maxScroll = maxScroll;
            this.viewLeft = viewLeft;
            this.viewBottom = viewBottom;
            this.viewWidth = viewWidth;
            this.viewHeight = viewHeight;
            this.scrollTrack = scrollTrack;
        }

        public boolean contains(float px, float py) {
            return inside(panel, px, py);
        }

        public boolean searchContains(float px, float py) {
            return inside(search, px, py);
        }

        /** Index into {@link #ZONE_TYPE_CHIPS}, or -1. */
        public int chipAt(float px, float py) {
            if (chips == null) return -1;
            for (int i = 0; i < chips.length; i++) {
                if (inside(chips[i], px, py)) return i;
            }
            return -1;
        }

        /** Pixels already scrolled down the pile. Zero shows the first row. */
        public final float scroll;
        /** How far the pile can move. Zero when every card fits. */
        public final float maxScroll;
        /** Card area under the search and type chips. Scroll keeps cards inside it. */
        public final float viewLeft;
        public final float viewBottom;
        public final float viewWidth;
        public final float viewHeight;
        /** Scroll track. Null when the pile fits. */
        public final HandLayout.Pose scrollTrack;

        public HandLayout.Pose cardAt(int index) {
            int col = index % columns;
            int row = index / columns;
            float left = panel.cx - panel.w / 2f + ZONE_GRID_PAD;
            float cardsTop = panel.cy + panel.h / 2f - ZONE_GRID_CHROME;
            float x = left + cardW / 2f + col * (cardW + ZONE_GRID_GAP);
            float y = cardsTop - cardH / 2f - row * (cardH + ZONE_GRID_GAP) + scroll;
            return new HandLayout.Pose(x, y, cardW, cardH, 0f, 1f);
        }

        /** The card's center is inside the scrolling window, so a clipped edge does not steal the pointer. */
        public boolean cardOnScreen(int index) {
            HandLayout.Pose pose = cardAt(index);
            return pose.cy <= viewBottom + viewHeight && pose.cy >= viewBottom;
        }

        /** Any part of the card is inside the window, including a row sliding in at the edge. */
        public boolean cardVisible(int index) {
            HandLayout.Pose pose = cardAt(index);
            float top = pose.cy + pose.h / 2f;
            float bottom = pose.cy - pose.h / 2f;
            return top >= viewBottom && bottom <= viewBottom + viewHeight;
        }

        public boolean trackContains(float px, float py) {
            return scrollTrack != null && inside(scrollTrack, px, py);
        }

        /** Scroll offset that puts the thumb under {@code py}. */
        public float scrollAt(float py) {
            if (scrollTrack == null || maxScroll <= 0f) return 0f;
            float top = scrollTrack.cy + scrollTrack.h / 2f;
            float span = Math.max(1f, scrollTrack.h);
            float t = (top - py) / span;
            if (t < 0f) t = 0f;
            if (t > 1f) t = 1f;
            return t * maxScroll;
        }

        private static boolean inside(HandLayout.Pose box, float px, float py) {
            return box != null && Math.abs(px - box.cx) <= box.w / 2f && Math.abs(py - box.cy) <= box.h / 2f;
        }
    }

    public static ZoneGrid zoneCardGrid(float windowW, float windowH, PlayerCluster cluster, int cardCount) {
        return zoneCardGrid(windowW, windowH, cluster, cardCount, 0f);
    }

    /**
     * Cards stay large enough to read. A pile taller than the half scrolls inside the panel.
     * {@code scroll} is pixels down the pile and is clamped to what that pile can show.
     */
    public static ZoneGrid zoneCardGrid(float windowW, float windowH, PlayerCluster cluster,
                                         int cardCount, float scroll) {
        int count = Math.max(0, cardCount);
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float split = dividerY(safeH);
        boolean you = cluster == null || cluster.owner != StripOwner.ENEMY;
        float minY = you ? 8f : split + 8f;
        float maxY = you ? split - 8f : safeH - 8f;
        float maxH = Math.max(ZONE_GRID_CHROME + ZONE_CARD_MIN_H, maxY - minY);
        float rightLimit = cluster == null ? safeW - 8f : Math.max(80f, cluster.left - 8f);
        float maxW = Math.max(160f, rightLimit - 8f);
        maxW = Math.min(maxW, safeW * 0.58f);

        int cols = ZONE_GRID_COLS;
        float cardH = ZONE_CARD_H;
        float cardW = cardH * HandLayout.CARD_ASPECT;
        while (cols > 2 && gridWidth(cols, cardW) > maxW) cols--;
        if (gridWidth(cols, cardW) > maxW) {
            cardW = (maxW - ZONE_GRID_PAD * 2f - ZONE_SCROLL_W - (cols - 1) * ZONE_GRID_GAP) / cols;
            cardH = Math.max(ZONE_CARD_MIN_H, cardW / HandLayout.CARD_ASPECT);
            cardW = cardH * HandLayout.CARD_ASPECT;
        }
        int rows = count == 0 ? 1 : (count + cols - 1) / cols;
        float contentH = rows * cardH + Math.max(0, rows - 1) * ZONE_GRID_GAP;
        float roomH = Math.min(maxH, you ? split - 8f : safeH - split - 8f);
        float viewH = Math.min(contentH, Math.max(cardH, roomH - ZONE_GRID_CHROME - ZONE_GRID_PAD));
        float maxScroll = Math.max(0f, contentH - viewH);
        if (maxScroll > 0f && gridWidth(cols, cardW) + ZONE_SCROLL_W > maxW) {
            cardW = (maxW - ZONE_GRID_PAD * 2f - ZONE_SCROLL_W - (cols - 1) * ZONE_GRID_GAP) / cols;
            cardH = Math.max(ZONE_CARD_MIN_H, cardW / HandLayout.CARD_ASPECT);
            cardW = cardH * HandLayout.CARD_ASPECT;
            contentH = rows * cardH + Math.max(0, rows - 1) * ZONE_GRID_GAP;
            viewH = Math.min(contentH, Math.max(cardH, roomH - ZONE_GRID_CHROME - ZONE_GRID_PAD));
            maxScroll = Math.max(0f, contentH - viewH);
        }
        float shown = scroll;
        if (shown < 0f) shown = 0f;
        if (shown > maxScroll) shown = maxScroll;
        float pw = Math.min(maxW, gridWidth(cols, cardW) + (maxScroll > 0f ? ZONE_SCROLL_W : 0f));
        float ph = ZONE_GRID_CHROME + viewH + ZONE_GRID_PAD;
        float cx = rightLimit - pw / 2f;
        if (cx - pw / 2f < 8f) cx = 8f + pw / 2f;
        float cy = cluster == null ? (minY + maxY) / 2f : cluster.top - ph / 2f;
        float halfMin = minY + ph / 2f;
        float halfMax = maxY - ph / 2f;
        if (halfMin > halfMax) cy = (minY + maxY) / 2f;
        else cy = Math.max(halfMin, Math.min(halfMax, cy));
        HandLayout.Pose panel = new HandLayout.Pose(cx, cy, pw, ph, 0f, 1f);
        float top = cy + ph / 2f;
        float left = cx - pw / 2f;
        float searchW = Math.max(40f, pw - ZONE_GRID_PAD * 2f);
        HandLayout.Pose search = new HandLayout.Pose(left + ZONE_GRID_PAD + searchW / 2f, top - 52f, searchW, 30f, 0f, 1f);
        HandLayout.Pose[] chips = new HandLayout.Pose[ZONE_TYPE_CHIPS.length];
        int chipCols = 4;
        float chipGap = 6f;
        float chipH = 24f;
        float chipW = (searchW - (chipCols - 1) * chipGap) / chipCols;
        float chipTop = top - 74f;
        for (int i = 0; i < chips.length; i++) {
            int col = i % chipCols;
            int row = i / chipCols;
            float chipX = left + ZONE_GRID_PAD + chipW / 2f + col * (chipW + chipGap);
            float chipY = chipTop - chipH / 2f - row * (chipH + 4f);
            chips[i] = new HandLayout.Pose(chipX, chipY, chipW, chipH, 0f, 1f);
        }
        float viewLeft = left + ZONE_GRID_PAD;
        float viewBottom = top - ZONE_GRID_CHROME - viewH;
        float viewWidth = Math.max(cardW, pw - ZONE_GRID_PAD * 2f - (maxScroll > 0f ? ZONE_SCROLL_W : 0f));
        HandLayout.Pose track = null;
        if (maxScroll > 0f) {
            float tw = 8f;
            float tx = left + pw - ZONE_GRID_PAD - tw / 2f;
            track = new HandLayout.Pose(tx, viewBottom + viewH / 2f, tw, viewH, 0f, 1f);
        }
        return new ZoneGrid(panel, search, chips, cardW, cardH, cols, rows, shown, maxScroll,
                viewLeft, viewBottom, viewWidth, viewH, track);
    }

    /**
     * Deck-editor search ({@code SFilterUtil} / {@code AdvancedSearchParser}).
     * A blank query matches. A type chip is ANDed on as {@code t:type}.
     * Space is AND. {@code or} is OR. A leading minus excludes.
     */
    public static boolean zoneQueryMatches(PaperCard card, String query, String typeChip) {
        if (card == null) return false;
        String text = query == null ? "" : query.trim();
        if (!SFilterUtil.buildTextFilter(text, false, true, true, true, false).test(card)) return false;
        if (typeChip == null || typeChip.isBlank() || "all".equalsIgnoreCase(typeChip)) return true;
        String token = "t:" + typeChip.toLowerCase(Locale.ROOT);
        return SFilterUtil.buildTextFilter(token, false, true, true, true, false).test(card);
    }

    /** Arena treats a basic land as its color. Forge's {@code c:} reads {@code Colors}, so set that. */
    public static List<String> colorBasicLand(List<String> lines) {
        if (lines == null) return List.of();
        String types = "";
        boolean hasColors = false;
        for (String line : lines) {
            if (line == null) continue;
            if (line.startsWith("Types:")) types = line.substring("Types:".length()).toLowerCase(Locale.ROOT);
            if (line.startsWith("Colors:")) hasColors = true;
        }
        if (hasColors || !types.contains("basic")) return lines;
        String color = null;
        if (types.contains("forest")) color = "green";
        else if (types.contains("island")) color = "blue";
        else if (types.contains("mountain")) color = "red";
        else if (types.contains("plains")) color = "white";
        else if (types.contains("swamp")) color = "black";
        if (color == null) return lines;
        List<String> copy = new ArrayList<>(lines);
        copy.add("Colors:" + color);
        return copy;
    }

    public static PaperCard paperFromScript(Iterable<String> script) {
        return new PaperCard(CardRules.fromScript(script), "PROTO", CardRarity.Common);
    }

    private static float gridWidth(int cols, float cardW) {
        return ZONE_GRID_PAD * 2f + cols * cardW + Math.max(0, cols - 1) * ZONE_GRID_GAP;
    }

    private static float gridHeight(int rows, float cardH) {
        return ZONE_GRID_CHROME + rows * cardH + Math.max(0, rows - 1) * ZONE_GRID_GAP + ZONE_GRID_PAD;
    }

    private static String colorPairId(String first, String second) {
        String a = canonColor(first);
        String b = canonColor(second);
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) return "";
        if (a.compareTo(b) > 0) {
            String swap = a;
            a = b;
            b = swap;
        }
        return a + "-" + b;
    }

    private static String canonColor(String color) {
        if (color == null) return "";
        String c = color.trim().toLowerCase();
        return switch (c) {
            case "w", "white" -> "white";
            case "u", "blue" -> "blue";
            case "b", "black" -> "black";
            case "r", "red" -> "red";
            case "g", "green" -> "green";
            default -> c;
        };
    }

    private static float rowWidth(int n) {
        int count = Math.max(1, n);
        return count * CLUSTER_CHIP_W + Math.max(0, count - 1) * CLUSTER_CHIP_GAP;
    }

    /** Both widgets sit on the gold line. Yours hangs down. Theirs is the reflection and hangs up. */
    private static float defaultClusterBottom(float windowH, StripOwner owner, float clusterH) {
        float split = dividerY(windowH);
        if (owner == StripOwner.YOU) return split - 8f - clusterH;
        return split + 8f;
    }

    private static PlayerCluster buildCluster(StripOwner owner, float left, float bottom, float cw) {
        boolean mirror = owner == StripOwner.ENEMY;
        float pileW = libraryPileWidth();
        float pileH = LIBRARY_PILE_H;
        float lifeW = cw - pileW - CLUSTER_CHIP_GAP;
        float zoneW = (lifeW - CLUSTER_CHIP_GAP) / 2f;
        float lifeCy;
        float pileCy;
        float zoneCy;
        float top = bottom + playerClusterHeight(owner);
        if (!mirror) {
            lifeCy = top - CLUSTER_LIFE_H / 2f;
            pileCy = top - pileH / 2f;
            float zoneTop = top - CLUSTER_LIFE_H - CLUSTER_PAD;
            zoneCy = zoneTop - CLUSTER_ZONE_H / 2f;
        } else {
            lifeCy = bottom + CLUSTER_LIFE_H / 2f;
            pileCy = bottom + pileH / 2f;
            float zoneBottom = bottom + CLUSTER_LIFE_H + CLUSTER_PAD;
            zoneCy = zoneBottom + CLUSTER_ZONE_H / 2f;
        }
        WidgetBox life = new WidgetBox(owner, WidgetPart.AVATAR_LIFE, left + lifeW / 2f, lifeCy, lifeW, CLUSTER_LIFE_H);
        float pileLeft = left + lifeW + CLUSTER_CHIP_GAP;
        WidgetBox library = new WidgetBox(owner, WidgetPart.LIBRARY, pileLeft + pileW / 2f, pileCy, pileW, pileH);
        WidgetBox grave = new WidgetBox(owner, WidgetPart.GRAVE, left + zoneW / 2f, zoneCy, zoneW, CLUSTER_ZONE_H);
        WidgetBox exile = new WidgetBox(owner, WidgetPart.EXILE,
                left + zoneW + CLUSTER_CHIP_GAP + zoneW / 2f, zoneCy, zoneW, CLUSTER_ZONE_H);
        return new PlayerCluster(owner, left, bottom, left + cw, top,
                life, grave, exile, library, new PhaseBox[0], new PhaseBox[0], new ActionBox[0]);
    }

    /** The two standing buttons. Cancel is extra and is not part of this height. */
    public static float actionColumnHeight() {
        return 2 * ACTION_BTN_H + ACTION_GAP;
    }

    /** Cancel sits one step under the bottom button. Drawn only while something is in progress. */
    public static ActionBox cancelBox(ActionBox bottom) {
        float y = bottom.y - (bottom.h + ACTION_GAP);
        return new ActionBox(ActionSlot.CANCEL, bottom.x, y, bottom.w, ACTION_BTN_H);
    }

    /** Right-edge action column. Top sits above bottom so Cancel can hang under it. */
    private static ActionBox[] rightActionColumn(float windowW) {
        float bw = ACTION_COL_W;
        float x = Math.max(windowW, 1f) - CLUSTER_MARGIN - bw / 2f;
        float bottomCy = 8f + ACTION_BTN_H + ACTION_GAP + ACTION_BTN_H / 2f;
        float topCy = bottomCy + ACTION_BTN_H + ACTION_GAP;
        return new ActionBox[] {
                new ActionBox(ActionSlot.TOP, x, topCy, bw, ACTION_BTN_H),
                new ActionBox(ActionSlot.BOTTOM, x, bottomCy, bw, ACTION_BTN_H)
        };
    }

    /**
     * Confirm / Cancel for a library-search picker. Same right-edge x as the
     * standing column, vertically centered so the hand-band row cannot cover them.
     */
    public static ActionBox[] searchActionColumn(float windowW, float windowH) {
        float bw = ACTION_COL_W;
        float x = Math.max(windowW, 1f) - CLUSTER_MARGIN - bw / 2f;
        float mid = Math.max(windowH, 1f) * 0.5f;
        float topCy = mid + ACTION_GAP / 2f + ACTION_BTN_H / 2f;
        float bottomCy = mid - ACTION_GAP / 2f - ACTION_BTN_H / 2f;
        return new ActionBox[] {
                new ActionBox(ActionSlot.TOP, x, topCy, bw, ACTION_BTN_H),
                new ActionBox(ActionSlot.BOTTOM, x, bottomCy, bw, ACTION_BTN_H)
        };
    }

    /**
     * Last x Ash's line may use. Both lines live on her top edge, so this is the
     * log-tab / Options column, not the bottom action buttons.
     */
    public static float stripRightLimit(float windowW, StripOwner owner) {
        float safeW = Math.max(windowW, 1f);
        return safeW - CLUSTER_MARGIN - SIDE_TAB_W - STRIP_AFTER_LIFE;
    }

    /**
     * His chips sit left of Ash's life; hers sit right of it. Same gap from the
     * life box, same pip size, same spacing, same strip width, same y.
     * If her deck family shortens the left pocket, both lines shrink to that
     * width so they stay mirrors.
     */
    private static PhaseBox[] ownerEdgeStrip(StripOwner owner, float windowW, float windowH,
                                              WidgetBox life) {
        float cy = stripEdgeY(windowH, owner);
        float[] fit = mirroredStripFit(windowW, windowH, life);
        float w = fit[0];
        float gap = fit[1];
        float stripW = fit[2];
        float lifeLeft = fit[3];
        float lifeRight = fit[4];
        boolean you = owner != StripOwner.ENEMY;
        float start = you ? lifeLeft - STRIP_AFTER_LIFE - stripW : lifeRight + STRIP_AFTER_LIFE;
        return edgeStripLine(owner, start, w, gap, cy);
    }

    /** Left edge of his top-line chips. Clears her library / grave / exile family. */
    private static float hisStripLeft(float windowW, float windowH) {
        return deckFamilyRight(windowW, windowH, StripOwner.ENEMY, 0f) + STRIP_AFTER_LIFE;
    }

    /**
     * Shared chip width, gap, and strip width so the two lines stay mirrors.
     * Returns {@code {w, gap, stripW, lifeLeft, lifeRight}}.
     */
    private static float[] mirroredStripFit(float windowW, float windowH, WidgetBox life) {
        WidgetBox them = life != null && life.owner == StripOwner.ENEMY
                ? life : edgeLifeBox(windowW, windowH, StripOwner.ENEMY);
        float lifeLeft = them.x - them.w / 2f;
        float lifeRight = them.x + them.w / 2f;
        float leftAvail = lifeLeft - STRIP_AFTER_LIFE - hisStripLeft(windowW, windowH);
        float rightAvail = stripRightLimit(windowW, StripOwner.ENEMY)
                - (lifeRight + STRIP_AFTER_LIFE);
        float avail = Math.min(leftAvail, rightAvail);
        int n = EDGE_STRIP_CHIPS;
        float gap = CLUSTER_CHIP_GAP;
        float w = CLUSTER_CHIP_W;
        float need = n * w + Math.max(0, n - 1) * gap;
        if (need > avail) {
            gap = STRIP_GAP_MIN;
            w = n > 0 ? (avail - Math.max(0, n - 1) * gap) / n : CLUSTER_CHIP_W;
            if (w > CLUSTER_CHIP_W) w = CLUSTER_CHIP_W;
            if (w < STRIP_CHIP_MIN_W) {
                w = STRIP_CHIP_MIN_W;
                if (n > 1) {
                    gap = Math.max(STRIP_GAP_MIN, (avail - n * w) / (n - 1));
                }
            }
            need = n * w + Math.max(0, n - 1) * gap;
            if (need > avail) {
                w = Math.max(16f, (avail - Math.max(0, n - 1) * STRIP_GAP_MIN) / n);
                gap = STRIP_GAP_MIN;
                need = n * w + Math.max(0, n - 1) * gap;
            }
        }
        return new float[] { w, gap, need, lifeLeft, lifeRight };
    }

    /** One long edge line: UP DR M1 BC DA DB FS CD EC M2 ET. */
    private static PhaseBox[] edgeStripLine(StripOwner owner, float start, float w, float gap,
                                            float cy) {
        StripPhase[] steps = visibleStripPhases();
        int n = steps.length;
        PhaseBox[] boxes = new PhaseBox[n];
        for (int i = 0; i < n; i++) {
            float x = start + w / 2f + i * (w + gap);
            boxes[i] = new PhaseBox(owner, steps[i], x, cy, w, CLUSTER_CHIP_H, false);
        }
        return boxes;
    }

    /**
     * Center Y of both phase lines. They share the opponent's top edge.
     * Air gap is {@link #STRIP_EDGE_GAP}. {@code owner} is kept for callers.
     */
    public static float stripEdgeY(float windowH, StripOwner owner) {
        float half = CLUSTER_CHIP_H / 2f;
        return Math.max(windowH, 1f) - STRIP_EDGE_GAP - half;
    }

    /**
     * Padded union of one owner's chips, for the thin ME / THEM band outline.
     * {@code null} when that row has no chips.
     */
    public static float[] ownerStripBounds(PhaseBox[] boxes, StripOwner owner) {
        if (boxes == null) return null;
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        int n = 0;
        for (int i = 0; i < boxes.length; i++) {
            PhaseBox box = boxes[i];
            if (box == null || box.owner != owner) continue;
            minX = Math.min(minX, box.x - box.w / 2f);
            maxX = Math.max(maxX, box.x + box.w / 2f);
            minY = Math.min(minY, box.y - box.h / 2f);
            maxY = Math.max(maxY, box.y + box.h / 2f);
            n++;
        }
        if (n == 0) return null;
        float pad = 3f;
        return new float[] { minX - pad, minY - pad, (maxX - minX) + pad * 2f, (maxY - minY) + pad * 2f };
    }

    /**
     * Which strip step the scripted moment is in. This is not a turn clock.
     * Opening and a fresh turn are Main 1. Declaring attackers is DA.
     * Once attackers are declared, blockers are DB. After blocks confirm, combat damage.
     * After combat resolves, Main 2.
     */
    /** The next step in turn order. Cleanup hands the turn over; the next step is upkeep. */
    public static StripPhase nextChronological(StripPhase phase) {
        StripPhase[] all = stripPhases();
        int i = phase == null ? 0 : phase.ordinal();
        if (i < 0 || i >= all.length) return StripPhase.UPKEEP;
        return all[(i + 1) % all.length];
    }

    /** True when this step is the last one of the turn. Main 2 is not that step. */
    public static boolean handsTurnOver(StripPhase phase) {
        return phase == StripPhase.CLEANUP;
    }

    public static StripPhase currentStripPhase(String scriptStep) {
        if (scriptStep == null) return StripPhase.MAIN_1;
        return switch (scriptStep) {
            case "ATTACKERS" -> StripPhase.DECLARE_ATTACKERS;
            case "BLOCKERS" -> StripPhase.DECLARE_BLOCKERS;
            case "COMBAT_DAMAGE", "DAMAGE" -> StripPhase.COMBAT_DAMAGE;
            case "MAIN_2" -> StripPhase.MAIN_2;
            case "END" -> StripPhase.END_STEP;
            case "CLEANUP" -> StripPhase.CLEANUP;
            default -> StripPhase.MAIN_1;
        };
    }

    /**
     * True means the step yields: red, the game does not wait.
     * False means the step stops: green, the game holds until he passes.
     * Every step starts yielding. Your opening pattern then stops on the steps
     * the duel already held: Main 1, Main 2, Declare Attackers, Declare Blockers.
     */
    public static boolean[] defaultAutoYields() {
        boolean[] yields = new boolean[StripPhase.values().length];
        for (int i = 0; i < yields.length; i++) yields[i] = true;
        return yields;
    }

    /** Missing save. Ash yields every step. Your four held steps start green. */
    public static boolean[] openingYields(StripOwner owner) {
        boolean[] yields = defaultAutoYields();
        if (owner != StripOwner.ENEMY) {
            yields[StripPhase.MAIN_1.ordinal()] = false;
            yields[StripPhase.MAIN_2.ordinal()] = false;
            yields[StripPhase.DECLARE_ATTACKERS.ordinal()] = false;
            yields[StripPhase.DECLARE_BLOCKERS.ordinal()] = false;
        }
        return yields;
    }

    public static boolean toggleAutoYield(boolean autoYield) {
        return !autoYield;
    }

    /** First word of a chip tooltip. His row is Your. Hers is Their. */
    public static String stripOwnerPrefix(StripOwner owner) {
        return owner == StripOwner.ENEMY ? "Their" : "Your";
    }

    /**
     * Hover copy. Whose + full step name only. No abbreviation.
     * Stop state and click options follow so he does not guess.
     */
    public static String phaseHoverText(StripPhase phase, boolean autoYield) {
        return phaseHoverText(phase, autoYield, StripOwner.YOU, false);
    }

    public static String phaseHoverText(StripPhase phase, boolean autoYield, StripOwner owner) {
        return phaseHoverText(phase, autoYield, owner, false);
    }

    public static String phaseHoverText(StripPhase phase, boolean autoYield, StripOwner owner,
                                         boolean current) {
        if (phase == null) return "";
        String name = phase.fullName == null || phase.fullName.isEmpty() ? phase.label : phase.fullName;
        String text = stripOwnerPrefix(owner) + " " + name;
        text += autoYield ? "\nStop: off — you will yield" : "\nStop: on";
        if (current) text += "\nCurrent step";
        text += "\nLeft-click: toggle this step's stop";
        text += "\nRight-click: pass until this step";
        return text;
    }

    /** Leftover hover for a removed CB block chip. Begin-combat uses {@link #phaseHoverText}. */
    public static String combatMarkerHover(boolean autoYield) {
        return phaseHoverText(StripPhase.BEGIN_COMBAT, autoYield);
    }

    /** Which row a scripted moment lights. Opening is your Main 1. Attacks light that player's declare-attackers step. */
    public static final class StripMoment {
        public final StripOwner owner;
        public final StripPhase phase;

        public StripMoment(StripOwner owner, StripPhase phase) {
            this.owner = owner == null ? StripOwner.YOU : owner;
            this.phase = phase == null ? StripPhase.MAIN_1 : phase;
        }
    }

    public static StripMoment scriptedStrip(String moment) {
        if (moment == null) return new StripMoment(StripOwner.YOU, StripPhase.MAIN_1);
        return switch (moment) {
            case "YOUR_ATTACK" -> new StripMoment(StripOwner.YOU, StripPhase.DECLARE_ATTACKERS);
            case "THEIR_ATTACK" -> new StripMoment(StripOwner.ENEMY, StripPhase.DECLARE_ATTACKERS);
            case "OPENING", "MAIN", "NEW_TURN" -> new StripMoment(StripOwner.YOU, StripPhase.MAIN_1);
            default -> new StripMoment(StripOwner.YOU, currentStripPhase(moment));
        };
    }

    public static String stripRowLabel(StripOwner owner) {
        return owner == StripOwner.ENEMY ? "THEM" : "ME";
    }

    /** One tiny abbreviation chip. {@code y} grows upward from the bottom of the window. */
    public static final class PhaseBox {
        public final StripOwner owner;
        public final StripPhase phase;
        /** Old extra CB chip. Always false: begin-combat is the BC step. */
        public final boolean combatMarker;
        public final float x;
        public final float y;
        public final float w;
        public final float h;

        public PhaseBox(StripOwner owner, StripPhase phase, float x, float y, float w, float h,
                         boolean combatMarker) {
            this.owner = owner == null ? StripOwner.YOU : owner;
            this.phase = phase;
            this.combatMarker = combatMarker;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        /** Abbreviation painted on the chip. */
        public String label() {
            if (phase == null || phase.label == null) return "";
            return phase.label;
        }

        public boolean contains(float px, float py) {
            return w > 0f && h > 0f && Math.abs(px - x) <= w / 2f && Math.abs(py - y) <= h / 2f;
        }
    }

    /**
     * Both players, top-edge positions around Ash's life.
     * Index 0 is Ash's line (right of her badge), then Steve's line (left of it).
     * Same order on both: UP DR M1 BC DA DB FS CD EC M2 ET.
     * A yield array is one set of steps long, so a hit index is not a yield index.
     */
    public static PhaseBox[] phaseStrip(float windowW, float windowH) {
        return phaseStrip(windowW, windowH, 0f, 0f, 0f, 0f);
    }

    public static PhaseBox[] phaseStrip(float windowW, float windowH,
                                         float youDx, float youDy, float enemyDx, float enemyDy) {
        PlayerCluster enemy = clusterAt(windowW, windowH, StripOwner.ENEMY, enemyDx, enemyDy);
        PlayerCluster you = clusterAt(windowW, windowH, StripOwner.YOU, youDx, youDy);
        PhaseBox[] boxes = new PhaseBox[enemy.nonCombat.length + enemy.combat.length
                + you.nonCombat.length + you.combat.length];
        int n = 0;
        n = copyBoxes(boxes, n, enemy.nonCombat);
        n = copyBoxes(boxes, n, enemy.combat);
        n = copyBoxes(boxes, n, you.nonCombat);
        copyBoxes(boxes, n, you.combat);
        return boxes;
    }

    /** Center of a short label just left of the enemy non-combat row. */
    public static float stripLabelCenterX(float windowW, float windowH) {
        PhaseBox[] boxes = phaseStrip(windowW, windowH);
        if (boxes.length == 0) return 8f;
        PhaseBox first = boxes[0];
        return first.x - first.w / 2f - 36f;
    }

    /**
     * Fixed well for the large browse scan. The well is the half opposite the browsed line,
     * so a bottom-half grid or row gets the picture above it and a top-half line gets it below.
     * {@code anchorY} is the line or grid center. The well does not follow a card's x.
     * Hit tests stay on the resting cell; this pose is draw-only.
     */
    public static HandLayout.Pose browsePreview(float windowW, float windowH, float anchorY) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float split = dividerY(safeH);
        float gap = 8f;
        boolean bottom = anchorY < split;
        float bandBottom = bottom ? split + gap : gap;
        float bandTop = bottom ? safeH - gap : split - gap;
        float room = Math.max(1f, bandTop - bandBottom);
        float ph = Math.min(HandLayout.hoverRaisedHeight(safeH), room);
        float pw = ph * HandLayout.CARD_ASPECT;
        float cy = (bandBottom + bandTop) / 2f;
        float cx = safeW / 2f;
        float minX = pw / 2f + 8f;
        float maxX = safeW - pw / 2f - 8f;
        if (minX > maxX) cx = safeW / 2f;
        else cx = Math.max(minX, Math.min(maxX, cx));
        return new HandLayout.Pose(cx, cy, pw, ph, 0f, 1f);
    }

    /**
     * Text panel beside {@link #browsePreview}. Same vertical span as the well, so it stays in the
     * opposite half and off the row being browsed. It does not follow a card's x. Draw-only.
     * When both sides have the same room, the panel sits to the right of the card.
     */
    public static HandLayout.Pose browseInfoPanel(float windowW, float windowH, HandLayout.Pose well) {
        float gap = 16f;
        float margin = 12f;
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        if (well == null) return new HandLayout.Pose(safeW / 2f, safeH / 2f, 320f, 200f, 0f, 1f);
        float cardLeft = well.cx - well.w / 2f;
        float cardRight = well.cx + well.w / 2f;
        float leftRoom = cardLeft - gap - margin;
        float rightRoom = safeW - margin - (cardRight + gap);
        boolean right = rightRoom >= leftRoom;
        float room = Math.max(0f, right ? rightRoom : leftRoom);
        float pw = Math.min(480f, room);
        float ph = Math.max(1f, well.h);
        float cy = well.cy;
        float cx;
        if (pw < 1f) {
            pw = 1f;
            cx = right ? safeW - margin - 0.5f : margin + 0.5f;
        } else if (right) {
            cx = cardRight + gap + pw / 2f;
        } else {
            cx = cardLeft - gap - pw / 2f;
        }
        return new HandLayout.Pose(cx, cy, pw, ph, 0f, 1f);
    }

    /**
     * Full-card picture for a battlefield tile, the same size as the hand preview.
     * Your cards grow upward, enemy cards downward, one hit-pad clear of the tile.
     * Draw-only: hit tests stay on the tile, and the tile itself is not pulled forward.
     */
    public static HandLayout.Pose boardHoverPreview(float windowW, float windowH,
                                                     float tileCx, float tileCy, float tileW, float tileH,
                                                     boolean yours) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float gap = Math.max(12f, HandLayout.OPEN_HIT_PAD_FRAC * safeH);
        float ph = Math.max(1f, HandLayout.hoverRaisedHeight(safeH));
        float pw = Math.max(1f, HandLayout.hoverRaisedWidth(safeH));
        float cx = tileCx;
        float cy;
        if (yours) {
            float bottom = tileCy + Math.max(tileH, 1f) / 2f + gap;
            cy = bottom + ph / 2f;
            if (cy + ph / 2f > safeH - 8f) {
                cy = safeH - 8f - ph / 2f;
            }
        } else {
            float top = tileCy - Math.max(tileH, 1f) / 2f - gap;
            cy = top - ph / 2f;
            if (cy - ph / 2f < 8f) {
                cy = 8f + ph / 2f;
            }
        }
        boolean coversTile = Math.abs(cy - tileCy) < (ph + tileH) / 2f
                && Math.abs(cx - tileCx) < (pw + tileW) / 2f;
        if (coversTile) {
            float right = tileCx + tileW / 2f + gap + pw / 2f;
            float left = tileCx - tileW / 2f - gap - pw / 2f;
            if (right + pw / 2f <= safeW - 8f) cx = right;
            else cx = left;
        }
        float halfW = pw / 2f;
        cx = Math.max(halfW + 8f, Math.min(safeW - halfW - 8f, cx));
        return new HandLayout.Pose(cx, cy, pw, ph, 0f, 1f);
    }

    /**
     * Second card or missing-art plate beside an existing hover preview.
     * Prefers the right when {@code preferRight} is true, then flips if that side
     * would leave the window. Draw-only: hit tests stay on the resting card.
     */
    public static HandLayout.Pose previewBeside(float windowW, float windowH, HandLayout.Pose main,
                                                float otherW, float otherH, boolean preferRight) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float pw = Math.max(1f, otherW);
        float ph = Math.max(1f, otherH);
        if (main == null) {
            return new HandLayout.Pose(safeW / 2f, safeH / 2f, pw, ph, 0f, 1f);
        }
        float gap = Math.max(12f, HandLayout.OPEN_HIT_PAD_FRAC * safeH);
        float right = main.cx + main.w / 2f + gap + pw / 2f;
        float left = main.cx - main.w / 2f - gap - pw / 2f;
        boolean useRight = preferRight;
        if (useRight && right + pw / 2f > safeW - 8f) useRight = false;
        if (!useRight && left - pw / 2f < 8f && right + pw / 2f <= safeW - 8f) useRight = true;
        float cx = useRight ? right : left;
        float cy = main.cy;
        cx = Math.max(pw / 2f + 8f, Math.min(safeW - pw / 2f - 8f, cx));
        cy = Math.max(ph / 2f + 8f, Math.min(safeH - ph / 2f - 8f, cy));
        return new HandLayout.Pose(cx, cy, pw, ph, 0f, 1f);
    }

    /** Gap between text-choice rows so the borders do not touch. */
    public static final float CHOICE_ROW_GAP = 8f;
    /** Shortest text-choice row. Taller lines grow past this. */
    public static final float CHOICE_ROW_MIN = 36f;
    /** Face-pick row: picture plus a short type caption. */
    public static final float FACE_CHOICE_THUMB_H = 118f;
    public static final float FACE_CHOICE_ROW_MIN = FACE_CHOICE_THUMB_H + 28f;
    /** Type-list search box under the panel top. */
    public static final float CHOICE_SEARCH_H = 32f;
    public static final float CHOICE_SEARCH_GAP = 8f;
    public static final float CHOICE_ALL_W = 56f;
    /** Library tutor overlay. Focus card is the grown-hand face so name, art, and text read. */
    public static final float BROWSE_SEARCH_H = 36f;
    public static final float BROWSE_SEARCH_W = 420f;
    public static final float BROWSE_SUGGEST_H = 28f;
    public static final int BROWSE_SUGGEST_MAX = 8;
    /** Neighbour step as a fraction of the focus card width. Peek, do not tile. */
    public static final float BROWSE_STEP_FRAC = 0.42f;

    /** Real choice list, sitting just above the hand. Not a bar across the middle of the board. */
    public static HandLayout.Pose choicePanel(float windowW, float windowH, int rows) {
        int n = Math.max(0, rows);
        float[] heights = new float[n];
        for (int i = 0; i < n; i++) heights[i] = CHOICE_ROW_MIN;
        return choicePanelForStack(windowW, windowH, choiceStackHeight(heights));
    }

    /**
     * Panel tall enough for the stacked rows, capped so a 300-type list cannot
     * grow past the window. An uncapped stack pinned at y=8 puts Zebrafish on
     * screen and the first types above the top.
     * {@code stackH} is the rows plus the gaps between them, from {@link #choiceStackHeight},
     * plus any search chrome the caller folded in.
     */
    public static HandLayout.Pose choicePanelForStack(float windowW, float windowH, float stackH) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float boxW = Math.min(520f, Math.max(280f, safeW - 80f));
        float rawH = 12f + Math.max(0f, stackH) + 12f + 32f + 16f;
        float handTop = HandLayout.REST_TOP_FRAC * safeH + 12f;
        float maxH = Math.max(160f, safeH - 8f - handTop);
        float boxH = Math.min(rawH, maxH);
        float bottom = handTop;
        if (bottom + boxH > safeH - 8f) bottom = Math.max(8f, safeH - 8f - boxH);
        return new HandLayout.Pose(safeW / 2f, bottom + boxH / 2f, boxW, boxH, 0f, 1f);
    }

    /** Sum of row heights plus the gap between neighbors. */
    public static float choiceStackHeight(float[] rowHeights) {
        if (rowHeights == null || rowHeights.length == 0) return 0f;
        float stack = 0f;
        for (int i = 0; i < rowHeights.length; i++) {
            stack += Math.max(CHOICE_ROW_MIN, rowHeights[i]);
            if (i + 1 < rowHeights.length) stack += CHOICE_ROW_GAP;
        }
        return stack;
    }

    /**
     * How far the type list can scroll. {@code scrollY} 0 is the first row at
     * the top of the list area. y is up, so a larger scrollY moves content up
     * and later rows into view.
     */
    public static float choiceMaxScroll(float panelH, float stackH, float topChrome, float bottomChrome) {
        float view = Math.max(0f, panelH - 24f - Math.max(0f, topChrome) - Math.max(0f, bottomChrome));
        return Math.max(0f, Math.max(0f, stackH) - view);
    }

    public static float clampChoiceScroll(float scrollY, float maxScroll) {
        if (scrollY < 0f) return 0f;
        if (scrollY > maxScroll) return maxScroll;
        return scrollY;
    }

    /**
     * Search box under the panel top. {@code allChip} leaves a slot on the right
     * for Show all. y is the bottom of the rectangle.
     */
    public static float[] choiceSearchRect(HandLayout.Pose panel, boolean allChip) {
        if (panel == null) return new float[] { 0f, 0f, 0f, 0f };
        float left = panel.cx - panel.w / 2f + 12f;
        float top = panel.cy + panel.h / 2f - 12f;
        float all = allChip ? CHOICE_ALL_W + CHOICE_SEARCH_GAP : 0f;
        float w = Math.max(40f, panel.w - 24f - all);
        return new float[] { left, top - CHOICE_SEARCH_H, w, CHOICE_SEARCH_H };
    }

    /** Show-all chip to the right of the type search box. */
    public static float[] choiceAllRect(HandLayout.Pose panel) {
        if (panel == null) return new float[] { 0f, 0f, 0f, 0f };
        float[] search = choiceSearchRect(panel, true);
        return new float[] { search[0] + search[2] + CHOICE_SEARCH_GAP, search[1], CHOICE_ALL_W, CHOICE_SEARCH_H };
    }

    /**
     * Visible type-list rows. Empty search with deck types and {@code showAll}
     * off is only those deck types. A query matches the full legal list.
     * Deck types stay first in every case. Indices are into {@code labels}.
     */
    public static List<Integer> typeChoiceVisible(List<String> labels, Collection<String> deckTypes,
            String query, boolean showAll) {
        if (labels == null || labels.isEmpty()) return List.of();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Integer> deckHits = new ArrayList<>();
        List<Integer> otherHits = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            if (label == null) continue;
            if (!q.isEmpty() && !label.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (deckTypeHit(deckTypes, label)) deckHits.add(i);
            else otherHits.add(i);
        }
        if (q.isEmpty() && !showAll && !deckHits.isEmpty()) return deckHits;
        if (otherHits.isEmpty()) return deckHits;
        if (deckHits.isEmpty()) return otherHits;
        List<Integer> out = new ArrayList<>(deckHits.size() + otherHits.size());
        out.addAll(deckHits);
        out.addAll(otherHits);
        return out;
    }

    private static boolean deckTypeHit(Collection<String> deckTypes, String label) {
        if (deckTypes == null || deckTypes.isEmpty() || label == null) return false;
        if (deckTypes.contains(label)) return true;
        for (String type : deckTypes) {
            if (type != null && type.equalsIgnoreCase(label)) return true;
        }
        return false;
    }

    /**
     * One rectangle per text choice, top row first. Each entry is x, y, w, h
     * with y at the bottom of the row. The rectangles do not overlap.
     * {@code scrollY} 0 keeps the first row at the top of the list area.
     */
    public static float[][] choiceRowRects(HandLayout.Pose panel, float[] rowHeights) {
        return choiceRowRects(panel, rowHeights, 0f, 0f);
    }

    public static float[][] choiceRowRects(HandLayout.Pose panel, float[] rowHeights,
            float scrollY, float topChrome) {
        int n = rowHeights == null ? 0 : rowHeights.length;
        float[][] out = new float[n][];
        if (panel == null || n == 0) return out;
        float left = panel.cx - panel.w / 2f + 12f;
        float rowW = Math.max(40f, panel.w - 24f);
        float y = panel.cy + panel.h / 2f - 12f - Math.max(0f, topChrome) + scrollY;
        for (int i = 0; i < n; i++) {
            float rh = Math.max(CHOICE_ROW_MIN, rowHeights[i]);
            y -= rh;
            out[i] = new float[] { left, y, rowW, rh };
            y -= CHOICE_ROW_GAP;
        }
        return out;
    }

    /**
     * Revealed cards for a list choice. Cards keep a hand-like size and never shrink
     * to fit a whole library. Extra cards sit off-screen; {@code scrollX} sweeps the
     * row. A short list stays centered. {@code handBand} puts the row in the hand's
     * strip so a library search feels like scrolling a fan.
     */
    public static HandLayout.Pose[] revealedRow(float windowW, float windowH, int count) {
        return revealedRow(windowW, windowH, count, 0f, false);
    }

    /**
     * Cascade / discover pile. The hit is the large card; misses stay smaller
     * so he sees why the look stopped.
     */
    public static HandLayout.Pose[] cascadeRow(float windowW, float windowH, int count, int hitIndex,
                                                float scrollX, boolean handBand) {
        HandLayout.Pose[] base = revealedRow(windowW, windowH, count, scrollX, handBand);
        if (base.length == 0) return base;
        int hit = hitIndex < 0 || hitIndex >= base.length ? 0 : hitIndex;
        HandLayout.Pose[] out = new HandLayout.Pose[base.length];
        for (int i = 0; i < base.length; i++) {
            HandLayout.Pose p = base[i];
            float scale = i == hit ? 1.16f : 0.70f;
            float ch = p.h * scale;
            float cw = ch * HandLayout.CARD_ASPECT;
            out[i] = new HandLayout.Pose(p.cx, p.cy, cw, ch, 0f, p.scale);
        }
        return out;
    }

    public static int cascadeIndex(float windowW, float windowH, int count, int hitIndex,
                                    float px, float py, float scrollX, boolean handBand) {
        HandLayout.Pose[] poses = cascadeRow(windowW, windowH, count, hitIndex, scrollX, handBand);
        for (int i = poses.length - 1; i >= 0; i--) {
            HandLayout.Pose pose = poses[i];
            if (Math.abs(px - pose.cx) <= pose.w / 2f && Math.abs(py - pose.cy) <= pose.h / 2f) return i;
        }
        return -1;
    }

    public static HandLayout.Pose[] revealedRow(float windowW, float windowH, int count,
                                                 float scrollX, boolean handBand) {
        if (count <= 0) return new HandLayout.Pose[0];
        float[] m = revealedMetrics(windowW, windowH, count, handBand);
        float cw = m[0];
        float ch = m[1];
        float step = m[2];
        float leftEdge = m[3];
        float avail = m[4];
        float cy = m[5];
        float rowW = (count - 1) * step + cw;
        float first = leftEdge + cw / 2f - Math.max(0f, scrollX);
        if (rowW <= avail) first = leftEdge + (avail - rowW) / 2f + cw / 2f;
        float safeH = Math.max(windowH, 1f);
        HandLayout.Pose[] poses = new HandLayout.Pose[count];
        for (int i = 0; i < count; i++) {
            float x = first + i * step;
            poses[i] = new HandLayout.Pose(x, cy, cw, ch, 0f, ch / HandLayout.fullHeight(safeH));
        }
        return poses;
    }

    /** How far the row can sweep. Zero when every card already fits. */
    public static float revealedScrollMax(float windowW, float windowH, int count, boolean handBand) {
        if (count <= 1) return 0f;
        float[] m = revealedMetrics(windowW, windowH, count, handBand);
        float rowW = (count - 1) * m[2] + m[0];
        return Math.max(0f, rowW - m[4]);
    }

    /**
     * Card width, height, step, left edge, available width, center y.
     * Step is about 58% of the card so a land's art stays readable while neighbours peek.
     */
    private static float[] revealedMetrics(float windowW, float windowH, int count, boolean handBand) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float reserve = Math.min(430f, safeW * 0.34f);
        float leftEdge = reserve + 12f;
        float rightEdge = safeW - 20f;
        if (handBand) {
            rightEdge = safeW - CLUSTER_MARGIN - ACTION_COL_W - 8f;
        }
        if (rightEdge - leftEdge < 80f) {
            leftEdge = 16f;
            rightEdge = handBand
                    ? safeW - CLUSTER_MARGIN - ACTION_COL_W - 8f
                    : safeW - 16f;
        }
        float avail = Math.max(80f, rightEdge - leftEdge);
        float handTop = HandLayout.REST_TOP_FRAC * safeH;
        float split = dividerY(safeH);
        float ch;
        float cy;
        if (handBand) {
            ch = Math.min(HandLayout.fullHeight(safeH) * 0.92f, Math.max(72f, handTop - 12f));
            cy = 8f + ch / 2f;
        } else {
            float maxCh = Math.max(72f, split - (handTop + 16f) - 10f);
            ch = Math.min(HandLayout.fullHeight(safeH) * 0.70f, maxCh);
            cy = handTop + 16f + ch / 2f;
            if (cy + ch / 2f > split - 6f) cy = split - 6f - ch / 2f;
        }
        float cw = ch * HandLayout.CARD_ASPECT;
        float step = cw * 0.58f;
        if (count <= 6) step = cw + 10f;
        return new float[] { cw, ch, step, leftEdge, avail, cy };
    }

    /**
     * Where a dragged order card would land, from 0 at the left end through {@code n - 1}.
     * The left end is the top of the list the order dialog returns.
     */
    public static int revealedDropIndex(float windowW, float windowH, int n, int gripIndex, float pointerX) {
        return revealedDropIndex(windowW, windowH, n, gripIndex, pointerX, 0f, false);
    }

    public static int revealedDropIndex(float windowW, float windowH, int n, int gripIndex, float pointerX,
                                         float scrollX, boolean handBand) {
        if (n <= 1) return 0;
        HandLayout.Pose[] poses = revealedRow(windowW, windowH, n, scrollX, handBand);
        int drop = 0;
        for (int i = 0; i < n && i < poses.length; i++) {
            if (i == gripIndex) continue;
            if (pointerX > poses[i].cx) drop++;
        }
        return Math.max(0, Math.min(n - 1, drop));
    }

    /** Resting slot in the reveal row. Used while the other cards shift around a drag. */
    public static HandLayout.Pose revealedSlot(float windowW, float windowH, int count, int slot) {
        return revealedSlot(windowW, windowH, count, slot, 0f, false);
    }

    public static HandLayout.Pose revealedSlot(float windowW, float windowH, int count, int slot,
                                                float scrollX, boolean handBand) {
        HandLayout.Pose[] poses = revealedRow(windowW, windowH, Math.max(count, 1), scrollX, handBand);
        if (poses.length == 0) return new HandLayout.Pose(0f, 0f, 1f, 1f, 0f, 1f);
        int i = Math.max(0, Math.min(poses.length - 1, slot));
        return poses[i];
    }

    /**
     * Clickable TOP / BOTTOM plates for scry and surveil. Left is top, right is
     * bottom. One card still gets two plates, not one overlapping pair.
     * Returns {topCx, topCy, topW, topH, botCx, botCy, botW, botH}.
     */
    public static float[] placeZonePlates(float windowW, float windowH, HandLayout.Pose[] poses) {
        float plateW = 100f;
        float plateH = 36f;
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float rightLimit = safeW - ACTION_COL_W - 10f;
        if (poses == null || poses.length == 0) {
            float cx = safeW * 0.5f;
            float cy = Math.max(plateH / 2f + 8f, yourLifeCenterY(safeH) + 72f);
            return new float[] { cx - 90f, cy, plateW, plateH, cx + 90f, cy, plateW, plateH };
        }
        if (poses.length == 1) {
            HandLayout.Pose card = poses[0];
            float gap = 14f;
            float topX = card.cx - card.w / 2f - gap - plateW / 2f;
            float botX = card.cx + card.w / 2f + gap + plateW / 2f;
            if (topX - plateW / 2f < 10f) topX = 10f + plateW / 2f;
            if (botX + plateW / 2f > rightLimit) botX = rightLimit - plateW / 2f;
            if (botX - topX < plateW + 8f) {
                topX = Math.max(10f + plateW / 2f, card.cx - 110f);
                botX = Math.min(rightLimit - plateW / 2f, card.cx + 110f);
            }
            float cy = card.cy;
            float minY = plateH / 2f + 8f;
            float maxY = safeH - plateH / 2f - 8f;
            cy = Math.max(minY, Math.min(maxY, cy));
            return new float[] { topX, cy, plateW, plateH, botX, cy, plateW, plateH };
        }
        HandLayout.Pose left = poses[0];
        HandLayout.Pose right = poses[poses.length - 1];
        float topX = left.cx;
        float botX = right.cx;
        float y = left.cy + left.h / 2f + 16f + plateH / 2f;
        float maxY = safeH - plateH / 2f - 8f;
        if (y > maxY) {
            y = left.cy - left.h / 2f - 16f - plateH / 2f;
            float minY = plateH / 2f + 8f;
            if (y < minY) y = left.cy;
        }
        if (topX - plateW / 2f < 10f) topX = 10f + plateW / 2f;
        if (botX + plateW / 2f > rightLimit) botX = rightLimit - plateW / 2f;
        return new float[] { topX, y, plateW, plateH, botX, y, plateW, plateH };
    }

    /** Index of the revealed card under the pointer. The rightmost overlap wins. */
    public static int revealedIndex(float windowW, float windowH, int count, float px, float py) {
        return revealedIndex(windowW, windowH, count, px, py, 0f, false);
    }

    public static int revealedIndex(float windowW, float windowH, int count, float px, float py,
                                     float scrollX, boolean handBand) {
        HandLayout.Pose[] poses = revealedRow(windowW, windowH, count, scrollX, handBand);
        for (int i = poses.length - 1; i >= 0; i--) {
            HandLayout.Pose pose = poses[i];
            if (Math.abs(px - pose.cx) <= pose.w / 2f && Math.abs(py - pose.cy) <= pose.h / 2f) return i;
        }
        return -1;
    }

    /**
     * Cover-flow for a library search. {@code scrollX} is the focused card index
     * (fractional). The centre card is full-face; neighbours lean and shrink.
     */
    public static HandLayout.Pose[] libraryBrowsePoses(float windowW, float windowH, int count, float scrollX) {
        if (count <= 0) return new HandLayout.Pose[0];
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float[] box = libraryBrowseCardBox(safeW, safeH);
        float focusH = box[2];
        float focusW = focusH * HandLayout.CARD_ASPECT;
        float step = focusW * BROWSE_STEP_FRAC;
        float midX = box[0];
        float midY = box[1];
        HandLayout.Pose[] poses = new HandLayout.Pose[count];
        for (int i = 0; i < count; i++) {
            float d = i - scrollX;
            float ad = Math.abs(d);
            float t = 1f / (1f + ad * 0.85f);
            float scale = 0.56f + 0.44f * t * t;
            float ch = focusH * scale;
            float cw = focusW * scale;
            float rot = Math.max(-16f, Math.min(16f, d * -9f));
            float x = midX + d * step;
            float y = midY - (1f - scale) * focusH * 0.06f;
            poses[i] = new HandLayout.Pose(x, y, cw, ch, rot, scale);
        }
        return poses;
    }

    /**
     * Centre x, centre y, focus height. Leaves the top search box and the
     * right Confirm column clear.
     */
    public static float[] libraryBrowseCardBox(float windowW, float windowH) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float right = CLUSTER_MARGIN + ACTION_COL_W + 8f;
        float midX = (safeW - right) * 0.5f;
        float top = safeH - 16f - BROWSE_SEARCH_H - 12f;
        float bottom = 20f;
        float avail = Math.max(80f, top - bottom);
        float focusH = Math.min(HandLayout.HOVER_RAISED_HEIGHT_FRAC * safeH, avail - 16f);
        float midY = bottom + avail * 0.5f;
        return new float[] { midX, midY, focusH };
    }

    public static float libraryBrowseStep(float windowH) {
        float focusH = HandLayout.HOVER_RAISED_HEIGHT_FRAC * Math.max(windowH, 1f);
        return focusH * HandLayout.CARD_ASPECT * BROWSE_STEP_FRAC;
    }

    public static float libraryBrowseScrollMax(int count) {
        return Math.max(0f, count - 1);
    }

    /** Frontmost overlapping card wins so a neighbour peek is still clickable. */
    public static int libraryBrowseIndex(float windowW, float windowH, int count, float scrollX,
            float px, float py) {
        HandLayout.Pose[] poses = libraryBrowsePoses(windowW, windowH, count, scrollX);
        if (poses.length == 0) return -1;
        Integer[] order = new Integer[poses.length];
        for (int i = 0; i < poses.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Float.compare(Math.abs(a - scrollX), Math.abs(b - scrollX)));
        for (int i : order) {
            HandLayout.Pose pose = poses[i];
            if (Math.abs(px - pose.cx) <= pose.w / 2f && Math.abs(py - pose.cy) <= pose.h / 2f) return i;
        }
        return -1;
    }

    public static float[] libraryBrowseSearchRect(float windowW, float windowH) {
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        float bw = Math.min(BROWSE_SEARCH_W, Math.max(180f, safeW * 0.42f));
        float x = (safeW - CLUSTER_MARGIN - ACTION_COL_W - 8f) * 0.5f - bw / 2f;
        if (x < 16f) x = 16f;
        float y = safeH - 16f - BROWSE_SEARCH_H;
        return new float[] { x, y, bw, BROWSE_SEARCH_H };
    }

    public static float[][] libraryBrowseSuggestRects(float windowW, float windowH, int count) {
        int n = Math.max(0, Math.min(BROWSE_SUGGEST_MAX, count));
        float[] search = libraryBrowseSearchRect(windowW, windowH);
        float[][] out = new float[n][];
        float y = search[1] - 4f;
        for (int i = 0; i < n; i++) {
            y -= BROWSE_SUGGEST_H;
            out[i] = new float[] { search[0], y, search[2], BROWSE_SUGGEST_H };
            y -= 2f;
        }
        return out;
    }

    /** Sheet indices whose printed names contain the query. Blank query keeps every card. */
    public static List<Integer> libraryBrowseVisible(List<String> names, String query) {
        List<Integer> out = new ArrayList<>();
        if (names == null) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null) continue;
            if (q.isEmpty() || name.toLowerCase(Locale.ROOT).contains(q)) out.add(i);
        }
        return out;
    }

    /**
     * Unique printed names from this sheet that match the typed prefix first,
     * then a contains match. Deck / library order, not Scryfall.
     */
    public static List<String> libraryBrowseSuggest(List<String> names, String query, int limit) {
        List<String> out = new ArrayList<>();
        if (names == null || limit <= 0) return out;
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return out;
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            if (name.toLowerCase(Locale.ROOT).startsWith(q)) seen.add(name);
        }
        for (String name : names) {
            if (name == null || name.isBlank() || seen.contains(name)) continue;
            if (name.toLowerCase(Locale.ROOT).contains(q)) seen.add(name);
        }
        int n = 0;
        for (String name : seen) {
            if (n >= limit) break;
            out.add(name);
            n++;
        }
        return out;
    }

    /** Hover line beside a chip. Not a card over the table. */
    public static HandLayout.Pose stopHoverPanel(float windowW, float windowH) {
        PhaseBox[] boxes = phaseStrip(windowW, windowH);
        PhaseBox anchor = boxes.length == 0 ? null : boxes[boxes.length - 1];
        return hoverPanelNear(windowW, windowH, anchor);
    }

    public static HandLayout.Pose hoverPanelNear(float windowW, float windowH, PhaseBox box) {
        return hoverPanelNear(windowW, windowH, box, "");
    }

    /**
     * Tooltip panel under the hovered chip. Both strips live on the top edge, so
     * the panel hangs down onto the field and stays on-screen.
     */
    public static HandLayout.Pose hoverPanelNear(float windowW, float windowH, PhaseBox box,
                                                 String text) {
        int lines = 1;
        int longest = 0;
        if (text != null && !text.isEmpty()) {
            String[] parts = text.split("\n", -1);
            lines = Math.max(1, parts.length);
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].length() > longest) longest = parts[i].length();
            }
        }
        float pw = Math.min(360f, Math.max(188f, 28f + longest * 7.2f));
        float ph = 18f + lines * 18f;
        float safeW = Math.max(windowW, 1f);
        float safeH = Math.max(windowH, 1f);
        if (box == null) return new HandLayout.Pose(safeW / 2f, safeH / 2f, pw, ph, 0f, 1f);
        float x = box.x;
        if (x - pw / 2f < 8f) x = 8f + pw / 2f;
        if (x + pw / 2f > safeW - 8f) x = safeW - 8f - pw / 2f;
        float y = box.y - box.h / 2f - 8f - ph / 2f;
        float minY = ph / 2f + 4f;
        float maxY = safeH - STRIP_EDGE_GAP - CLUSTER_CHIP_H - 4f - ph / 2f;
        if (minY > maxY) y = (minY + maxY) / 2f;
        else y = Math.max(minY, Math.min(maxY, y));
        return new HandLayout.Pose(x, y, pw, ph, 0f, 1f);
    }

    public static int phaseIndexAt(float windowW, float windowH, float px, float py) {
        return phaseIndexAt(windowW, windowH, px, py, 0f, 0f, 0f, 0f);
    }

    public static int phaseIndexAt(float windowW, float windowH, float px, float py,
                                    float youDx, float youDy, float enemyDx, float enemyDy) {
        PhaseBox[] boxes = phaseStrip(windowW, windowH, youDx, youDy, enemyDx, enemyDy);
        for (int i = 0; i < boxes.length; i++) {
            if (boxes[i].contains(px, py)) return i;
        }
        return -1;
    }

    private static int copyBoxes(PhaseBox[] dest, int at, PhaseBox[] src) {
        for (int i = 0; i < src.length; i++) dest[at + i] = src[i];
        return at + src.length;
    }

    private static boolean within(float x0, float y0, float x1, float y1, float radius) {
        return dist(x0, y0, x1, y1) <= radius;
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
