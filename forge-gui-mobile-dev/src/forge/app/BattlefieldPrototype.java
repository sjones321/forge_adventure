package forge.app;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.Texture.TextureFilter;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.HdpiUtils;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Align;

import forge.game.card.CardView;
import forge.game.card.CounterType;

import java.awt.FileDialog;
import java.awt.Frame;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scripted battlefield window. No Forge rules, stack, mana, or AI.
 *
 * <p>Hover the hand to read it. Hover a battlefield permanent or a card in the grave, exile,
 * or library grid and a large scan opens in the opposite half, with the name, oracle text, and any
 * rulings stored on disk in a panel beside that card. Right-click opens the same panel at full zoom.
 * Esc or another right-click closes that zoom. Inside the hand, a drag reorders. Past the cast
 * line, the card leaves the pointer: a targeted spell becomes a stack-log entry (full text,
 * then one click assigns a target), and a land or creature lands on its row. Another instant
 * can join the stack before the first resolves. Resolve takes only the top spell. Click a
 * stack entry to read it while it is live. Drag a creature into the opponent's half to attack.
 * Drag one of yours onto their attacker to block. Click Activate, read the preview, then Confirm.
 * N starts a new turn. R resets. Esc closes the zoom, then the zone, then cancels a spell,
 * choice, or target. With nothing pending, Esc opens Options. Esc closes Options when it is open.
 * Options sits under the log tabs and saves the match or quits. 1 is your attack, 2 is their attack.
 * The window is two battlefronts. The top half is the enemy, the bottom half is you, with a divider.
 * Each half has its own flat background. Yours is a green-gold plain. Theirs follows the scripted deck colors.
 * His life and avatar sit above the resting hand. Ash's life stays top-center.
 * Both phase strips sit on her top edge: his chips left of her life (green band),
 * hers right of her life (red band). Same order: UP DR M1 BC DA DB FS CD EC M2 ET.
 * Combat sits between the two mains. Cleanup is not a chip.
 * Drag the life block to move that cluster inside its half.
 * Click a step to toggle that stop. Right-click yields every earlier chip on that row and holds this one.
 * Hover a chip for a panel: whose full step name, stop on/off, and the click options.
 * The gold outline is the current step of the current turn.
 */
public final class BattlefieldPrototype extends ApplicationAdapter {
    private static final Color TABLE = new Color(0.09f, 0.11f, 0.12f, 1f);
    private static final Color YOU_TINT = new Color(0.20f, 0.32f, 0.28f, 1f);
    private static final Color THEM_TINT = new Color(0.32f, 0.18f, 0.18f, 1f);
    private static final Color LINE_DIM = new Color(1f, 1f, 1f, 0.22f);
    private static final Color LINE_HOT = new Color(0.55f, 0.85f, 1f, 0.9f);
    private static final Color GREEN = new Color(0.45f, 0.95f, 0.45f, 1f);
    private static final Color RED = new Color(1f, 0.38f, 0.32f, 1f);
    private static final Color GOLD = new Color(1f, 0.86f, 0.45f, 1f);
    /** Below starting life, while the current attack is not lethal. */
    private static final Color LIFE_YELLOW = new Color(0.98f, 0.93f, 0.22f, 1f);
    private static final Color FRAME_WHITE = new Color(214f / 255f, 206f / 255f, 186f / 255f, 1f);
    private static final Color FRAME_BLUE = new Color(70f / 255f, 122f / 255f, 168f / 255f, 1f);
    private static final Color FRAME_BLACK = new Color(62f / 255f, 58f / 255f, 66f / 255f, 1f);
    private static final Color FRAME_RED = new Color(168f / 255f, 72f / 255f, 58f / 255f, 1f);
    private static final Color FRAME_GREEN = new Color(74f / 255f, 122f / 255f, 70f / 255f, 1f);
    private static final Color FRAME_STEEL = new Color(138f / 255f, 142f / 255f, 148f / 255f, 1f);
    private static final Color FRAME_GOLD = new Color(176f / 255f, 138f / 255f, 72f / 255f, 1f);
    private static final Color FRAME_BROWN = new Color(122f / 255f, 86f / 255f, 52f / 255f, 1f);
    /** Generic beveled pips. Not Wizards mana-symbol art. */
    private static final Color PIP_W = new Color(0.93f, 0.90f, 0.82f, 1f);
    private static final Color PIP_U = new Color(0.20f, 0.45f, 0.78f, 1f);
    private static final Color PIP_B = new Color(0.14f, 0.12f, 0.14f, 1f);
    private static final Color PIP_R = new Color(0.78f, 0.18f, 0.16f, 1f);
    private static final Color PIP_G = new Color(0.16f, 0.55f, 0.26f, 1f);
    private static final Color PIP_C = new Color(0.74f, 0.75f, 0.77f, 1f);
    private static final Color PIP_INK = new Color(0.08f, 0.08f, 0.09f, 1f);
    private static final Color PIP_PHY = new Color(0.45f, 0.08f, 0.10f, 1f);
    private static final Color ACTION_DIM = new Color(0.55f, 0.55f, 0.55f, 1f);
    private static final Color ARROW_ATTACK = new Color(0.72f, 0.34f, 0.30f, 0.92f);
    private static final Color ARROW_BLOCK = new Color(0.36f, 0.54f, 0.68f, 0.92f);
    /** Soft bloom around an attacker. Brighter inner edge is the last layer. */
    private static final Color GLOW_ATTACK = new Color(0.95f, 0.28f, 0.05f, 1f);
    private static final Color GLOW_ATTACK_EDGE = new Color(1f, 0.52f, 0.14f, 1f);
    /** Soft bloom around a blocker. */
    private static final Color GLOW_BLOCK = new Color(0.16f, 0.42f, 1f, 1f);
    private static final Color GLOW_BLOCK_EDGE = new Color(0.45f, 0.82f, 1f, 1f);
    /** Gold-white spell aim. Not combat's red-orange attackers or blue blockers. */
    private static final Color ARROW_SPELL = new Color(0.96f, 0.86f, 0.32f, 0.96f);
    private static final float ARROW_SHAFT = 4f;
    /** One zippy hop: start → pay-hold, pay-hold → stack, or stack → tile. */
    private static final float CAST_TRAVEL_SEC = 0.22f;
    /** First hop stops this far along the path. Not the middle of the table. */
    private static final float PAY_HOLD_U = 0.28f;
    /** Smoke puff on his arriving tile. Short, not Ash's 0.8s hold. */
    private static final float IMPACT_SEC = 0.32f;

    private enum Phase { MAIN, ATTACKERS, BLOCKERS }
    private enum Modal { NONE, CHOOSE_TYPE, ABILITY, SHOCK }
    private enum ZoneView { NONE, GRAVE, EXILE, LIBRARY }

    /** Forge match UI face ({@code FSkinFont} / {@code font1.ttf}), generated at the drawn size. */
    private static final String FORGE_UI_FONT = "fallback_skin/font1.ttf";
    /** Phase abbreviations and the You/Enemy row labels. Small, like {@code VPhaseIndicator}'s 11. */
    private static final int STRIP_PX = 13;
    /** Hover, buttons, card tags, and the stack. A readable body size, not a scaled 15px bitmap. */
    private static final int BODY_PX = 16;
    /** Examined stack row. Generated at this size; the body face is not scaled up to reach it. */
    private static final int STACK_PX = 20;
    /** Life totals. Generated at this size. The bitmap is not scaled up from a smaller face. */
    private static final int LIFE_PX = 40;
    private static final int SLEEVE_PLAIN = 0;
    private static final int SLEEVE_RED = 1;
    private static final int SLEEVE_BLUE = 2;
    private static final int SLEEVE_GEOMETRIC = 3;
    private static final int SLEEVE_CUSTOM = 4;

    private SpriteBatch batch;
    private ShapeRenderer shapes;
    private BitmapFont font;
    private BitmapFont stripFont;
    private BitmapFont stackFont;
    private BitmapFont big;
    private FreeTypeFontGenerator fontGenerator;
    private GlyphLayout glyphs;
    private Texture pixel;
    private final Matrix4 savedTransform = new Matrix4();
    private final Matrix4 cardSpin = new Matrix4();
    private final List<Texture> art = new ArrayList<>();
    private final List<Perm> board = new ArrayList<>();
    private final List<HandCard> hand = new ArrayList<>();
    /** Cards you could cast from the grave or exile. Not the enemy's. */
    private final List<HandCard> ghosts = new ArrayList<>();
    private final List<StackItem> stack = new ArrayList<>();
    /** Colored cost pips queued this frame. Shapes paint after the card batch; letters paint on top. */
    private final List<ManaPipDraw> pipQueue = new ArrayList<>();
    private final List<float[]> pipPlates = new ArrayList<>();
    private final List<UiButton> buttons = new ArrayList<>();
    /** Max, Max-N, and Max-counter. Only filled while Forge is announcing X. */
    private final List<UiButton> xControls = new ArrayList<>();
    /** Minus and plus under a damage or divide row. */
    private final List<UiButton> amountControls = new ArrayList<>();
    /** Scry/surveil TOP and BOTTOM drop plates. */
    private final List<UiButton> placeControls = new ArrayList<>();
    private UiButton youBadge = new UiButton("you-life", "", 0, 0, 1, 1);
    private UiButton themBadge = new UiButton("them-life", "", 0, 0, 1, 1);

    private Phase phase = Phase.MAIN;
    private BattlefieldLayout.StripOwner activeOwner = BattlefieldLayout.StripOwner.YOU;
    private BattlefieldLayout.StripPhase shownPhase = BattlefieldLayout.StripPhase.MAIN_1;
    /** First owner of a match is silent. The chime plays only when the turn actually changes hands. */
    private boolean turnKnown;
    private BattlefieldLayout.StripOwner heardOwner;
    /** The choice already announced. A new wait beeps once; the same wait does not beep again. */
    private String heardChoice;
    /** Forge input sitting under a choice sheet, so closing the sheet does not beep that input. */
    private String choiceShield;
    /** Real Forge rules on this board. The scripted picture stays in the code for the layout tests. */
    private final boolean liveMode = true;
    private BattlefieldLive live;
    private int liveSig = Integer.MIN_VALUE;
    private float bashLeft;
    private float bashDur = 2.09f;
    /** Rear, hold, strike, shake, pause, return. Stagger adds time; it does not shrink these. */
    private static final float BASH_REAR = 0.88f;
    private static final float BASH_HOLD = 1.07f;
    private static final float BASH_STRIKE = 1.23f;
    private static final float BASH_SHAKE = 1.47f;
    private static final float BASH_PAUSE = 1.75f;
    private static final float BASH_MOTION = 2.09f;
    private static final float BASH_STAGGER = 0.24f;
    private float youImpactX, youImpactY, themImpactX, themImpactY;
    private BattlefieldLive.Pic heldPic;
    /** Attack targets from the last frame snapshot. Arrow drawing must not rebuild the match. */
    private List<BattlefieldLive.Strike> shownStrikes = List.of();
    private final List<Bash> bashes = new ArrayList<>();
    private final List<String> gameLog = new ArrayList<>();
    private final List<String> devLog = new ArrayList<>();
    /** True yields (red). False stops (green). Loaded from the duel folder when that file exists. */
    private final boolean[] yourYield = BattlefieldLayout.openingYields(BattlefieldLayout.StripOwner.YOU);
    private final boolean[] enemyYield = BattlefieldLayout.openingYields(BattlefieldLayout.StripOwner.ENEMY);
    private int stripHover = -1;
    /** Session drag of each cluster, in pixels from the default right-hand place. Not saved. */
    private float youDx, youDy, enemyDx, enemyDy;
    private boolean pressingCluster, draggingCluster;
    private BattlefieldLayout.StripOwner clusterOwner = BattlefieldLayout.StripOwner.YOU;
    private float clusterOriginDx, clusterOriginDy;
    private ZoneView openZone = ZoneView.NONE;
    private BattlefieldLayout.StripOwner openOwner = BattlefieldLayout.StripOwner.YOU;
    /** Deck-editor query. Blank matches every card. Combined with {@link #zoneType}. */
    private String zoneQuery = "";
    /** Basic type chip, or null for All. */
    private String zoneType;
    /** Pixels down the open grave, exile, or library pile. */
    private float zoneScroll = 0f;
    private boolean draggingZoneScroll = false;
    private final List<ZoneCard> yourGrave = new ArrayList<>();
    private final List<ZoneCard> theirGrave = new ArrayList<>();
    private final List<ZoneCard> yourExile = new ArrayList<>();
    private final List<ZoneCard> theirExile = new ArrayList<>();
    private final List<ZoneCard> yourLibrary = new ArrayList<>();
    private final List<ZoneCard> theirLibrary = new ArrayList<>();
    private final Map<String, forge.item.PaperCard> zonePapers = new HashMap<>();
    private final Map<String, Texture> zoneArt = new HashMap<>();
    private final Set<String> zoneArtMiss = new HashSet<>();
    /** Your library only. Theirs stays the plain dark sleeve. */
    private int yourSleeve = SLEEVE_PLAIN;
    /** Scripted like Oracle of Mul Daya: your top card stays face up, and a land there can be played. */
    private boolean yourTopRevealed = true;
    private boolean grippingTop, draggingTop;
    /** Look at the top of your library. Set only when a spell or ability is waiting for it. */
    private boolean lookTop;
    /** How many cards that spell or ability is asking him to look at. Zero means the deck opens the library. */
    private int pendingTopLook;
    private int sleeveCount = 4;
    private Texture customSleeve;
    private Texture graveIcon;
    private Texture exileIcon;
    private Texture abilitySheet;
    /** Geometric commander badge. Same art on the command-zone slot and the card icon. */
    private Texture commanderMark;
    /** Open silver-cyan hex ward. Drawn when the permanent currently has hexproof. */
    private Texture hexproofMark;
    /** Closed violet-slate ward. Drawn when the permanent currently has shroud. */
    private Texture shroudMark;
    /** Orange chevrons. Drawn when the creature has haste. Smaller than the ward shield. */
    private Texture hasteMark;
    /** Forge's summoning-sickness swirl, IMG_SUMMONSICK on sprite_icons.png. */
    private Texture iconSheet;
    /** Room under the command slot for the badge. Replaces the old COMMANDER word. */
    private static final float COMMAND_LABEL_H = 28f;
    private TextureRegion summonSick;
    private final Map<String, TextureRegion> abilityIcons = new java.util.HashMap<>();
    private boolean choosingSleeve;
    /** Brief hold on combat damage after a scripted combat resolves, then Main 2. */
    private float combatDamageHold;
    private Modal modal = Modal.NONE;
    private int yourLife = 20, theirLife = 20, yourGy, theirGy, artCursor;
    /** Cards in each hand. Opponent count only — faces stay hidden. */
    private int yourHandCount, theirHandCount;
    /** Unspent pool beside each life total. White, blue, black, red, green, colorless. */
    private int[] yourPool = new int[6];
    private int[] theirPool = new int[6];
    /** Life at the last reset. Gains above it are gold. Losses below it are yellow until lethal. */
    private int yourStartingLife = 20, theirStartingLife = 20;
    /** Scripted poison. Zero draws nothing and reserves no space. */
    private int yourPoison = 2, theirPoison = 5;
    /** Commander damage already dealt to each player. Empty until a commander hits. */
    private final List<BattlefieldLive.CommanderPip> yourCommanderDamage = new ArrayList<>();
    private final List<BattlefieldLive.CommanderPip> theirCommanderDamage = new ArrayList<>();
    /** Command-zone slots. Empty in a constructed game. */
    private List<BattlefieldLive.CommanderSeat> yourCommand = List.of();
    private List<BattlefieldLive.CommanderSeat> theirCommand = List.of();
    private final List<CommandSlot> commandSlots = new ArrayList<>();
    private int pressCommand = -1;
    /** Dragging a commander off its slot, same as a hand card past the cast line. */
    private boolean draggingCommand;
    /** Command-zone tile under the pointer. Preview grows off the resting slot. */
    private int hoverCommand = -1;
    /** End turn yields the rest of this turn. A green step still stops. Cleared when the turn ends. */
    private boolean yieldRest;
    /** Ash's arrivals. OK stays hidden until this reaches zero. */
    private float summonHold;
    private boolean summonBlocks;
    private String summonName = "";
    private int arrivalSlot;
    private boolean arrivalsArmed;
    private final Set<Integer> seenPerms = new HashSet<>();
    private final Set<Integer> seenSpells = new HashSet<>();
    /** Soft smoke at his tile when the permanent actually appears. */
    private final List<SummonPuff> summonPuffs = new ArrayList<>();
    private final java.util.Set<String> seenStack = new java.util.HashSet<>();
    /** Last hand, ghost, or command-slot pose. Held through pay until the same picture sits on the stack or tile. */
    private final Map<Integer, CastPose> departedHand = new HashMap<>();
    /** Steve's card traveling from that start pose. One picture, one trip at a time. */
    private CastFlight castFlight;
    /** Later land (or no-stack) arrivals wait so only one card is in the air. */
    private final List<CastFlight> pendingFlights = new ArrayList<>();
    /** The match is over. Esc leaves this up. Play again and Change decks clear it. */
    private boolean matchOver;
    private String resultTitle = "";
    private String resultWhy = "";
    /** Their turn walks itself. Your upkeep is the beat before your Main 1. */
    private enum ScriptWalk { NONE, ENEMY, YOUR_UPKEEP }
    private ScriptWalk scriptWalk = ScriptWalk.NONE;
    private float scriptHold;
    private int hover = -1, pressHand = -1, examining = -1, orderGrip = -1;
    private int ghostHover = -1, pressGhost = -1;
    /** Face-down exile pile on the grave/exile chrome. Resting tiles, not the ghost fan. */
    private int hoverExile = -1, pressExile = -1;
    private boolean hoverExileYou = true, pressExileYou = true;
    /** Magic card back for foretold / face-down exile. Generated if no local file. */
    private Texture cardBack;
    private boolean draggingHand, dragInHand, draggingOrder;
    private float pressX, pressY, pressTime, dragDist;
    private Perm pressedPerm;
    private boolean draggingBoard;
    private Perm selected;
    private HandCard choiceHand;
    private Perm choicePerm;
    private Perm shockHover, shockLock;
    private boolean shockHoverYou, shockHoverThem, shockLockYou, shockLockThem;
    private String abilityLine = "", note = "";
    private float stackBottomY;
    private float mx, my;
    /** Permanent under the pointer. The raised scan stays on that card, not on the cursor. */
    private Perm hoverPerm, shownPerm;
    /** Stack row under the pointer. Its spell arrow is the only one drawn. */
    private StackItem hoverStack;
    private final List<Emblem> yourEmblems = new ArrayList<>();
    private final List<Emblem> theirEmblems = new ArrayList<>();
    private Emblem hoverEmblem;
    /** Side log. Hover a tab to read it. Click pins it so the wheel can scroll. */
    private enum SideLog { GAME, IMPORTANT, AI, DEBUG }
    private static final String[] SIDE_LOG_LABELS = { "Game", "Important", "AI", "Debug" };
    private SideLog logHoverTab;
    private SideLog logPinned;
    private float logScroll;
    /** Mode lines drawn in the left window this frame. Click uses the same boxes. */
    private final List<float[]> modeHits = new ArrayList<>();
    /** Word-choice rows drawn this frame. Click uses these rectangles, not a second layout. */
    private final List<float[]> textChoiceHits = new ArrayList<>();
    /** Visible type-list row → index in the full legal list. */
    private final List<Integer> textChoiceIndex = new ArrayList<>();
    /** Type-list search. Empty shows deck types; typing matches the full legal list. */
    private String typeQuery = "";
    private String typeChoiceSig = "";
    private float typeScroll;
    private float typeMaxScroll;
    private boolean typeShowAll;
    private float[] textChoiceSearch;
    private float[] textChoiceAll;
    private int pressTypeLine = -1;
    private float typePressY;
    private float typePressScroll;
    private boolean draggingTypeList;
    /** Horizontal sweep of a library-search / reveal row. Reset when the sheet's cards change. */
    private float sheetScroll;
    private float sheetPressX;
    private float sheetPressScroll;
    private boolean draggingSheet;
    private int pressSheetHit = -1;
    private String sheetSig = "";
    /** Last painted action word. A leftover Next must not click through a lagged search sheet. */
    private String lastActionWord = "";
    private boolean ignoreStaleAction;
    private boolean sheetWasOpen;
    /** OK on the word-choice panel: x, y, w, h. Null when that panel is closed. */
    private float[] textChoiceOk;
    /** Face-pick row under the pointer. Hover preview uses this index. */
    private int hoverFace = -1;
    /** Decision lines from the newest recorded turn. Debug tab only. */
    private final List<String> debugLog = new ArrayList<>();
    private float permRaise;
    /** Visible zone-grid card under the pointer, or -1. */
    private int hoverZone = -1, shownZone = -1;
    private float zoneRaise;
    private boolean cardZoom;
    /** Menu under the log tabs. Esc stays Cancel while a spell, choice, or target is in progress. */
    private boolean optionsOpen;
    private String optionsNote =
            "Save writes this Forge match into the duel folder. Quit closes the window.";
    /** Deck lists before the duel. Start leaves this and opens the match. */
    private boolean picking = true;
    /** Host or Join. The board replaces this only after the match picture is ready. */
    private boolean lobbying;
    private boolean starting;
    private BattlefieldDecks decks;
    private List<BattlefieldDecks.Choice> youRows = List.of();
    private List<BattlefieldDecks.Choice> ashRows = List.of();
    private int pickYou;
    private int pickAsh;
    private float pickScrollYou;
    private float pickScrollAsh;
    private String pickNote = "Choose a deck for you and a deck for Ash, then Start.";
    private final List<float[]> youHits = new ArrayList<>();
    private final List<float[]> ashHits = new ArrayList<>();
    private float youColX, youColY, youColW, youColH;
    private float ashColX, ashColY, ashColW, ashColH;
    private float startCx, startCy, startW = 200f, startH = 56f;
    private float hostCx, hostCy, hostW = 150f, hostH = 56f;
    private float joinCx, joinCy, joinW = 150f, joinH = 56f;
    private float importCx, importCy, importW = 328f, importH = 44f;
    private float manageCx, manageCy, manageW = 328f, manageH = 44f;
    private boolean managing;
    private BattlefieldDecks.Choice manageChoice;
    private BattlefieldDecks.Sheet manageSheet;
    private float manageScroll;
    private String manageName = "";
    private boolean manageNameFocused;
    private boolean manageSelectAll;
    private int manageCaret;
    private boolean manageDeleteAsk;
    private int manageHover = -1;
    private String lastImportNorm = "";
    private float managePanelCx, managePanelCy, managePanelW, managePanelH;
    private float manageNameCx, manageNameCy, manageNameW, manageNameH;
    private float manageCloseCx, manageCloseCy, manageDelCx, manageDelCy, manageYesCx, manageNoCx;
    private float manageListX, manageListY, manageListW, manageListH;
    private final List<float[]> manageHits = new ArrayList<>();
    /**
     * This PC reaches its own host on loopback. A LAN address from the host note
     * often cannot. The field starts filled so Join uses it without typing.
     */
    private static final String LOCAL_JOIN = "127.0.0.1:36743";
    private boolean joinField = true;
    /** Click the box to type or paste. Starts focused so Ctrl+V works immediately. */
    private boolean addrFocused = true;
    private boolean joinSelectAll = true;
    private int joinCaret;
    private boolean typedJoinThisFrame;
    private String joinAddress = LOCAL_JOIN;
    private float addrCx, addrCy, addrW = 460f, addrH = 40f;
    /** Same text-input family as the Join host box. Persisted per isolated user dir. */
    private String playerName = "";
    private boolean nameFocused = false;
    private boolean nameSelectAll = true;
    private int nameCaret;
    private boolean typedNameThisFrame;
    private float nameCx, nameCy, nameW = 200f, nameH = 40f;
    private String youName = "";
    private String themName = "";
    private float lobbyStartCx, lobbyStartCy, lobbyStartW, lobbyStartH;
    private float lobbyOkCx, lobbyOkCy, lobbyOkW, lobbyOkH;
    private float lobbyNoCx, lobbyNoCy, lobbyNoW, lobbyNoH;
    /** On the lobby itself, under the status line. Options has the same action. */
    private float lobbyBackCx, lobbyBackCy, lobbyBackW, lobbyBackH;
    private static final float OPT_BTN_W = 132f;
    private static final float OPT_BTN_H = 32f;
    private static final float OPT_PANEL_W = 360f;
    private static final float OPT_PANEL_H = 248f;
    /** Right-click adds the oracle panel. Left-click is the picture only. */
    private boolean zoomPanel;
    private Perm zoomPerm;
    private final List<ModLine> modLines = new ArrayList<>();
    private String zoomName = "", zoomOracle = "", zoomRulings = "";
    private Texture zoomTex;
    private final Map<String, String> rulingsCache = new HashMap<>();

    public static void main(String[] args) {
        String preset = System.getProperty("forge.test.userDir");
        Path home = preset == null || preset.isBlank()
                ? Path.of("forge-gui-mobile-dev", "target", "battlefield-user")
                : Path.of(preset);
        home = home.toAbsolutePath().normalize();
        System.setProperty("forge.test.userDir", home.toString());
        BattlefieldLive.installBase();
        Lwjgl3ApplicationConfiguration.useGlfwAsync();
        Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        boolean tiny = home.toString().toLowerCase(java.util.Locale.ROOT).contains("tiny");
        cfg.setTitle(tiny ? "Battlefield — Tiny" : "Battlefield — Steve");
        // The board uses the window size. Two 1400x788 windows fit on one desktop.
        cfg.setWindowedMode(1400, 788);
        cfg.setWindowPosition(tiny ? 460 : 40, tiny ? 48 : 40);
        cfg.setDecorated(true);
        cfg.setMaximized(false);
        cfg.setResizable(true);
        // Vsync was parking the whole loop inside glfwSwapBuffers, so Host never painted a line.
        cfg.useVsync(false);
        cfg.setForegroundFPS(60);
        cfg.setIdleFPS(30);
        cfg.setPauseWhenMinimized(false);
        cfg.setPauseWhenLostFocus(false);
        new Lwjgl3Application(new BattlefieldPrototype(), cfg);
    }

    @Override
    public void create() {
        batch = new SpriteBatch();
        shapes = new ShapeRenderer();
        font = face(BODY_PX);
        stripFont = face(STRIP_PX);
        stackFont = face(STACK_PX);
        big = face(LIFE_PX);
        glyphs = new GlyphLayout();
        loadArt();
        loadCustomSleeve();
        graveIcon = loadZoneIcon(BattlefieldLayout.GRAVESTONE_ART);
        exileIcon = loadZoneIcon(BattlefieldLayout.EXILE_ART);
        cardBack = loadCardBack();
        loadAbilitySheet();
        loadSummonSick();
        commanderMark = bakeCommanderMark();
        hexproofMark = bakeHexproofMark();
        shroudMark = bakeShroudMark();
        hasteMark = bakeHasteMark();
        DuelSounds.warm();
        if (!liveMode) resetAll();
        else {
            decks = BattlefieldDecks.scan();
            youRows = decks.forYou();
            ashRows = decks.forAsh();
            pickNote = decks.note;
            live = new BattlefieldLive();
            loadPhaseStops();
            loadJoinAddress();
            loadPlayerName();
            live.usePlayerName(playerName);
            pushPhaseStops();
            BattlefieldPics.listen(line -> {
                if (line != null && !line.isBlank()) pickNote = line;
            });
        }
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean keyDown(int keycode) {
                if (keycode == Input.Keys.ESCAPE) {
                    if (managing) {
                        if (manageDeleteAsk) manageDeleteAsk = false;
                        else closeManage();
                        return true;
                    }
                    if (lobbying && live != null && live.lobbyPrompt() && live.cancelOn()) {
                        live.pressCancel();
                        return true;
                    }
                    if (liveReady() && live.xOpen()) { live.xCancel(); return true; }
                    if (liveReady() && (live.choiceOpen() || live.cardSheetOpen() || live.modeSheetOpen())) {
                        orderGrip = -1;
                        draggingOrder = false;
                        live.pressCancel();
                        return true;
                    }
                    if (lookTop) { lookTop = false; return true; }
                    if (cardZoom) { closeZoom(); return true; }
                    if (openZone != ZoneView.NONE) { openZone = ZoneView.NONE; return true; }
                    if (liveReady() && live.cancelOn()) { live.pressCancel(); return true; }
                    if (lobbying) leaveLobby();
                    else if (cancelLit()) cancelInProgress();
                    else if (optionsOpen) optionsOpen = false;
                    else optionsOpen = true;
                    return true;
                }
                if (cardZoom) return true;
                if (openZone != ZoneView.NONE) {
                    if (keycode == Input.Keys.BACKSPACE) {
                        if (!zoneQuery.isEmpty()) zoneQuery = zoneQuery.substring(0, zoneQuery.length() - 1);
                        zoneScroll = 0f;
                        return true;
                    }
                    if (keycode == Input.Keys.FORWARD_DEL) {
                        zoneQuery = "";
                        zoneScroll = 0f;
                        return true;
                    }
                    return true;
                }
                if (picking) {
                    if (managing && manageNameKey(keycode)) return true;
                    if (nameFocused) {
                        if (nameKey(keycode)) return true;
                        if (keycode == Input.Keys.ENTER || keycode == Input.Keys.TAB) {
                            focusJoinAddress();
                            return true;
                        }
                        return true;
                    }
                    if (joinField && (addrFocused || !clipboardLooksLikeDeck()) && joinKey(keycode)) return true;
                    if ((ctrlDown() && keycode == Input.Keys.V) || (shiftDown() && keycode == Input.Keys.INSERT)) {
                        importArenaClipboard();
                        return true;
                    }
                    if (keycode == Input.Keys.TAB) {
                        focusPlayerName();
                        return true;
                    }
                    if (keycode == Input.Keys.ENTER) {
                        if (joinField) startJoin();
                        else startChosenMatch();
                    }
                    return true;
                }
                if (lobbying) {
                    if (live != null && live.lobbyPrompt()) {
                        if (keycode == Input.Keys.ENTER || keycode == Input.Keys.SPACE) live.pressOk();
                    } else if (keycode == Input.Keys.ENTER && live != null) {
                        live.requestStart();
                    }
                    return true;
                }
                if (liveReady()) {
                    if (live.xOpen()) {
                        if (keycode == Input.Keys.ENTER || keycode == Input.Keys.SPACE) live.xSubmit();
                        else if (keycode == Input.Keys.MINUS || keycode == Input.Keys.NUMPAD_SUBTRACT) live.xNudge(-1);
                        else if (keycode == Input.Keys.PLUS || keycode == Input.Keys.EQUALS || keycode == Input.Keys.NUMPAD_ADD) live.xNudge(1);
                        return true;
                    }
                    if (live.typeChoiceOpen()) {
                        if (keycode == Input.Keys.BACKSPACE) {
                            if (!typeQuery.isEmpty()) {
                                typeQuery = typeQuery.substring(0, typeQuery.length() - 1);
                                typeScroll = 0f;
                            }
                            return true;
                        }
                        if (keycode == Input.Keys.FORWARD_DEL) {
                            typeQuery = "";
                            typeScroll = 0f;
                            return true;
                        }
                        if (keycode == Input.Keys.SPACE || keycode == Input.Keys.ENTER) {
                            return true;
                        }
                    }
                    if (keycode == Input.Keys.ENTER || keycode == Input.Keys.SPACE) {
                        live.pressOk();
                        return true;
                    }
                    return true;
                }
                if (keycode == Input.Keys.R) { resetAll(); return true; }
                if (keycode == Input.Keys.N) { newTurn(); return true; }
                if (keycode == Input.Keys.NUM_1 || keycode == Input.Keys.NUMPAD_1) { yourAttack(); return true; }
                if (keycode == Input.Keys.NUM_2 || keycode == Input.Keys.NUMPAD_2) { theirAttack(); return true; }
                if (keycode == Input.Keys.ENTER) {
                    if (topShowing() && confirmWord(topCaption())) pressTop();
                    else if (modal == Modal.NONE && examining < 0 && hover >= 0) play(live().get(hover));
                    return true;
                }
                if (keycode == Input.Keys.SPACE) {
                    if (topShowing()) pressTop();
                    return true;
                }
                if (keycode == Input.Keys.E) {
                    if ("End turn".equals(bottomCaption())) endTheTurn();
                    return true;
                }
                if (keycode == Input.Keys.A) {
                    if ("Alpha strike".equals(bottomCaption())) allAttack();
                    return true;
                }
                return false;
            }
            @Override public boolean keyTyped(char character) {
                if (cardZoom) return true;
                if (picking && managing && manageNameFocused && character >= 32 && character < 127) {
                    if (ctrlDown()) return true;
                    typeManageName(character);
                    return true;
                }
                if (picking && nameFocused && character >= 32 && character < 127) {
                    if (ctrlDown()) return true;
                    typePlayerName(character);
                    typedNameThisFrame = true;
                    return true;
                }
                if (picking && joinField && !nameFocused && character >= 32 && character < 127) {
                    if (ctrlDown()) return true;
                    typeJoinChar(character);
                    typedJoinThisFrame = true;
                    return true;
                }
                if (liveReady() && live.typeChoiceOpen() && character >= 32 && character != 127) {
                    if (ctrlDown()) return true;
                    typeQuery += character;
                    typeScroll = 0f;
                    return true;
                }
                if (openZone == ZoneView.NONE) return false;
                if (character >= 32 && character != 127) {
                    zoneQuery += character;
                    zoneScroll = 0f;
                    return true;
                }
                return true;
            }
            @Override public boolean touchDown(int sx, int sy, int pointer, int button) {
                if (picking && button == Input.Buttons.RIGHT) {
                    readPointer(sx, sy);
                    if (managing && boxContains(manageNameCx, manageNameCy, manageNameW, manageNameH)) {
                        manageNameFocused = true;
                        pasteManageName();
                        return true;
                    }
                    if (boxContains(nameCx, nameCy, nameW, nameH)) {
                        focusPlayerName();
                        pastePlayerName();
                    } else if (joinField && boxContains(addrCx, addrCy, addrW, addrH)) {
                        focusJoinAddress();
                        pasteJoinAddress();
                    }
                    return true;
                }
                if (button == Input.Buttons.RIGHT) {
                    readPointer(sx, sy);
                    float rsw = Gdx.graphics.getWidth(), rsh = Gdx.graphics.getHeight();
                    if (passUntilStrip(rsw, rsh, mx, my)) return true;
                    if (revealedFrontAt(mx, my)) {
                        if (cardZoom) closeZoom();
                        else {
                            ZoneCard top = yourLibrary.get(0);
                            openCardZoom(top.name, textureFor(top.name), null, true);
                        }
                        return true;
                    }
                    if (yourLibraryAtPointer()) {
                        chooseSleeveImage();
                        return true;
                    }
                    if (cardZoom) { closeZoom(); return true; }
                    if (openFaceChoiceZoom()) return true;
                    if (openSheetZoom()) return true;
                    Emblem mark = emblemAt(mx, my);
                    if (mark != null) {
                        openCardZoom(mark.name, textureFor(mark.name), null, true);
                        if (zoomOracle == null || zoomOracle.isBlank()) zoomOracle = mark.text;
                        return true;
                    }
                    StackItem stackHit = stackUnder(mx, my);
                    if (stackHit != null) {
                        Texture tex = stackHit.card != null && stackHit.card.tex != null
                                ? stackHit.card.tex : textureFor(stackHit.name);
                        openCardZoom(stackHit.name, tex, null, true);
                        return true;
                    }
                    if (openZoomAtPointer()) {
                        pressHand = -1;
                        pressedPerm = null;
                        draggingHand = false;
                        dragInHand = false;
                        draggingBoard = false;
                        return true;
                    }
                    if (modal != Modal.NONE) cancelModal();
                    else if (!stack.isEmpty()) cancelTop();
                    return true;
                }
                if (button != Input.Buttons.LEFT) {
                    if (modal != Modal.NONE) cancelModal();
                    else if (!stack.isEmpty()) cancelTop();
                    return true;
                }
                readPointer(sx, sy);
                float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
                if (pressOptions(sw, sh)) return true;
                if (picking) {
                    if (managing) pressManage();
                    else pressPicker();
                    return true;
                }
                if (lobbying) {
                    pressLobby();
                    return true;
                }
                if (cardZoom) return true;
                if (liveReady() && live.xOpen()) {
                    pressXControl();
                    return true;
                }
                if (anyChoiceOpen()) {
                    if (inspectOpenZone(sw, sh)) return true;
                    if (live.modeSheetOpen()) {
                        int line = modeLineAt(mx, my);
                        if (line >= 0) {
                            live.pressMode(line);
                            return true;
                        }
                    }
                    if (live.cardSheetOpen()) {
                        if (live.amountOpen()) {
                            if (pressAmount()) return true;
                        }
                        if (live.placingSheet() && pressPlaceZone()) return true;
                        int hit = sheetHit(sw, sh);
                        if (live.orderSheet()) {
                            if (hit >= 0) {
                                orderGrip = hit;
                                draggingOrder = false;
                                pressX = mx;
                                pressY = my;
                                pressTime = 0;
                                dragDist = 0;
                                return true;
                            }
                        } else if (hit >= 0) {
                            // Pictures first. Confirm sits near the land row and must not eat a card click.
                            live.pressSheet(hit);
                            beginSheetSweep(sw, sh, hit);
                            return true;
                        }
                    }
                    if (live.choiceOpen()) {
                        if (textChoiceAllHit(mx, my)) {
                            typeShowAll = !typeShowAll;
                            typeScroll = 0f;
                            return true;
                        }
                        if (textChoiceSearchHit(mx, my)) return true;
                        int line = textChoiceAt(mx, my);
                        if (line >= 0) {
                            if (typeMaxScroll > 0f) {
                                pressTypeLine = line;
                                typePressY = my;
                                typePressScroll = typeScroll;
                                draggingTypeList = false;
                                pressX = mx;
                                pressY = my;
                                pressTime = 0;
                                dragDist = 0;
                                return true;
                            }
                            live.pressTextChoice(textChoiceMapped(line));
                            return true;
                        }
                        if (textChoiceOkHit(mx, my)) {
                            live.submitTextChoice();
                            return true;
                        }
                    }
                    // Grave and exile after the visible choice rows, so a Yes/No
                    // click still answers. Chrome that is not under a row opens the grid.
                    if (inspectZoneChrome(sw, sh)) return true;
                    // Card and mode sheets draw OK on the action row. A click that missed
                    // the cards still has to reach that button. Inspect already returned.
                    if (pressAction(sw, sh)) return true;
                    return true;
                }
                if (lookTop) {
                    int pick = topLookIndex(mx, my, sw, sh);
                    if (pick >= 0) chooseTopLook(pick);
                    return true;
                }
                if (actionHit(sw, sh) && pressAction(sw, sh)) return true;
                if (pressSideLog(sw, sh)) return true;
                if (pressAction(sw, sh)) return true;
                if (pressPlayerCluster()) return true;
                if (emblemAt(mx, my) != null) return true;
                if (pressExileTile()) return true;
                if (onWidget(sw, sh)) return true;
                if (openZone != ZoneView.NONE) {
                    BattlefieldLayout.ZoneGrid grid = openGrid(sw, sh);
                    if (grid != null && grid.trackContains(mx, my)) {
                        draggingZoneScroll = true;
                        zoneScroll = grid.scrollAt(my);
                        return true;
                    }
                    if (handleZoneGrid(sw, sh)) return true;
                }
                if (toggleStripYield(sw, sh, mx, my)) return true;
                UiButton hit = hitButton(mx, my);
                if (hit != null) { onButton(hit.id); return true; }
                if (!targetClickThroughStack(mx, my) && hitStack(mx, my)) return true;
                int commandIndex = commandIndexAt(mx, my);
                if (commandIndex >= 0 && modal == Modal.NONE) {
                    pressCommand = commandIndex;
                    pressGhost = -1;
                    pressHand = -1;
                    pressedPerm = null;
                    pressX = mx;
                    pressY = my;
                    pressTime = 0;
                    dragDist = 0;
                    return true;
                }
                int ghostIndex = ghostIndexAt(mx, my);
                if (ghostIndex >= 0 && modal == Modal.NONE) {
                    if (examining >= 0) examining = -1;
                    pressGhost = ghostIndex;
                    pressHand = -1;
                    pressedPerm = null;
                    pressX = mx; pressY = my; pressTime = 0; dragDist = 0;
                    draggingHand = false;
                    dragInHand = false;
                    ghostHover = ghostIndex;
                    hover = -1;
                    return true;
                }
                int handIndex = pickHand(mx, my);
                if (handIndex >= 0 && modal == Modal.NONE) {
                    if (examining >= 0) examining = -1;
                    pressHand = handIndex;
                    pressedPerm = null;
                    pressX = mx; pressY = my; pressTime = 0; dragDist = 0;
                    draggingHand = false;
                    dragInHand = false;
                    hover = handIndex;
                    return true;
                }
                if (examining >= 0) {
                    examining = -1;
                    note = targeting() ? BattlefieldLayout.TARGET_PROMPT : note;
                    return true;
                }
                if (targeting() && !(liveReady() && live.choosingTarget())) {
                    clickShockTarget();
                    return true;
                }
                if (modal != Modal.NONE) return true;
                if (overBoardPreview(mx, my)) return true;
                Perm perm = hitBoard(mx, my);
                if (perm != null) {
                    pressedPerm = perm;
                    pressHand = -1;
                    pressX = mx; pressY = my; pressTime = 0; dragDist = 0;
                    draggingBoard = false;
                    return true;
                }
                if (phase == Phase.ATTACKERS && selected != null && themBadge.contains(mx, my)) {
                    tryAttack(selected);
                }
                return true;
            }
            @Override public boolean touchDragged(int sx, int sy, int pointer) {
                if (picking || lobbying) return true;
                if (cardZoom) return true;
                readPointer(sx, sy);
                if (draggingZoneScroll) {
                    BattlefieldLayout.ZoneGrid grid = openGrid(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
                    if (grid != null) zoneScroll = grid.scrollAt(my);
                    return true;
                }
                if (pressTypeLine >= 0 || draggingTypeList) {
                    dragDist = Math.max(dragDist, dist(pressX, pressY, mx, my));
                    if (HandLayout.isDrag(dragDist)) {
                        draggingTypeList = true;
                        typeScroll = BattlefieldLayout.clampChoiceScroll(
                                typePressScroll + (my - typePressY), typeMaxScroll);
                    }
                    return true;
                }
                if (pressSheetHit >= 0 || draggingSheet) {
                    dragDist = Math.max(dragDist, dist(pressX, pressY, mx, my));
                    if (HandLayout.isDrag(dragDist)) {
                        draggingSheet = true;
                        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
                        sheetScroll = sheetPressScroll - (mx - sheetPressX);
                        syncSheetScroll(w, h);
                    }
                    return true;
                }
                dragDist = Math.max(dragDist, dist(pressX, pressY, mx, my));
                if (pressingCluster) {
                    if (!HandLayout.isDrag(dragDist)) return true;
                    draggingCluster = true;
                    float dx = clusterOriginDx + (mx - pressX);
                    float dy = clusterOriginDy + (my - pressY);
                    float[] clamped = BattlefieldLayout.clampClusterDelta(
                            Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), clusterOwner, dx, dy);
                    if (clusterOwner == BattlefieldLayout.StripOwner.ENEMY) {
                        enemyDx = clamped[0];
                        enemyDy = clamped[1];
                    } else {
                        youDx = clamped[0];
                        youDy = clamped[1];
                    }
                    return true;
                }
                if (!HandLayout.isDrag(dragDist)) return true;
                if (orderGrip >= 0) {
                    draggingOrder = true;
                    return true;
                }
                if (grippingTop && modal == Modal.NONE) {
                    draggingTop = true;
                } else if (pressHand >= 0 && modal == Modal.NONE && !mulliganHand()) {
                    if (!draggingHand) dragInHand = true;
                    draggingHand = true;
                    List<HandCard> live = live();
                    float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
                    dragInHand = HandLayout.pointerInHand(w, h, live.size(), hover, dragInHand, mx, my);
                    if (pressHand < live.size()) {
                        HandCard c = live.get(pressHand);
                        c.tcx = mx; c.tcy = my;
                        c.tw = HandLayout.hoverRaisedWidth(h);
                        c.th = HandLayout.hoverRaisedHeight(h);
                        c.trot = 0f;
                    }
                } else if (pressCommand >= 0 && modal == Modal.NONE) {
                    if (pressCommand < commandSlots.size()) {
                        CommandSlot slot = commandSlots.get(pressCommand);
                        if (slot.you && slot.seat != null && slot.seat.inCommand) {
                            draggingCommand = true;
                        }
                    }
                } else if (pressedPerm != null && modal == Modal.NONE) {
                    draggingBoard = true;
                }
                return true;
            }
            @Override public boolean touchUp(int sx, int sy, int pointer, int button) {
                ignoreStaleAction = false;
                if (picking || lobbying) return true;
                if (button != Input.Buttons.LEFT) return false;
                if (cardZoom) {
                    closeZoom();
                    pressHand = -1;
                    pressCommand = -1;
                    draggingCommand = false;
                    orderGrip = -1;
                    draggingOrder = false;
                    grippingTop = false;
                    draggingTop = false;
                    pressedPerm = null;
                    draggingHand = false;
                    draggingBoard = false;
                    draggingZoneScroll = false;
                    pressTypeLine = -1;
                    draggingTypeList = false;
                    return true;
                }
                readPointer(sx, sy);
                if (orderGrip >= 0) {
                    if (draggingOrder && liveReady() && live.orderSheet()) {
                        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
                        int drop = BattlefieldLayout.revealedDropIndex(w, h, sheetCount(), orderGrip, mx,
                                sheetScroll, sheetHandBand());
                        live.moveOrder(orderGrip, drop);
                    } else if (liveReady() && live.placingSheet()) {
                        live.pressSheet(orderGrip);
                    }
                    orderGrip = -1;
                    draggingOrder = false;
                    return true;
                }
                if (pressTypeLine >= 0 || draggingTypeList) {
                    int line = pressTypeLine;
                    boolean tap = !draggingTypeList && HandLayout.isTap(dragDist, pressTime);
                    pressTypeLine = -1;
                    draggingTypeList = false;
                    if (tap && liveReady()) live.pressTextChoice(textChoiceMapped(line));
                    return true;
                }
                if (pressSheetHit >= 0 || draggingSheet) {
                    pressSheetHit = -1;
                    draggingSheet = false;
                    return true;
                }
                if (draggingZoneScroll) {
                    draggingZoneScroll = false;
                    return true;
                }
                dragDist = Math.max(dragDist, dist(pressX, pressY, mx, my));
                if (pressingCluster) {
                    if (liveReady() && !draggingCluster && clusterOwner == BattlefieldLayout.StripOwner.YOU
                            && youBadge.contains(mx, my)) {
                        live.selectPlayer(true);
                    } else if (liveReady() && !draggingCluster && clusterOwner == BattlefieldLayout.StripOwner.ENEMY
                            && themBadge.contains(mx, my)) {
                        live.selectPlayer(false);
                    } else if (!draggingCluster && targeting() && clusterOwner == BattlefieldLayout.StripOwner.YOU
                            && youBadge.contains(mx, my)) {
                        clickShockTarget();
                    } else if (!draggingCluster && targeting() && clusterOwner == BattlefieldLayout.StripOwner.ENEMY
                            && themBadge.contains(mx, my)) {
                        clickShockTarget();
                    } else if (!draggingCluster && phase == Phase.ATTACKERS && selected != null
                            && clusterOwner == BattlefieldLayout.StripOwner.ENEMY && themBadge.contains(mx, my)) {
                        tryAttack(selected);
                    }
                    pressingCluster = false;
                    draggingCluster = false;
                    return true;
                }
                if (grippingTop) {
                    if (draggingTop) releaseTopLand(Gdx.graphics.getHeight());
                    grippingTop = false;
                    draggingTop = false;
                } else if (pressCommand >= 0) {
                    if (liveReady() && pressCommand < commandSlots.size()) {
                        CommandSlot slot = commandSlots.get(pressCommand);
                        boolean tap = HandLayout.isTap(dragDist, pressTime);
                        boolean dragCast = draggingCommand && commandCastReady(slot);
                        if ((tap || dragCast) && slot.seat != null && slot.seat.inCommand
                                && slot.seat.card != null) {
                            if (live.choosingTarget()) {
                                live.select(slot.seat.card.view);
                            } else if (slot.you) {
                                parkCommand(slot);
                                commitCommandTravel(slot);
                                live.select(slot.seat.card.view);
                            }
                        }
                    }
                    pressCommand = -1;
                    draggingCommand = false;
                } else if (pressExile >= 0) {
                    releaseExileTile();
                } else if (pressGhost >= 0) {
                    List<HandCard> ghostCards = liveGhosts();
                    if (pressGhost < ghostCards.size()) {
                        HandCard ghost = ghostCards.get(pressGhost);
                        if (liveReady()) {
                            if (HandLayout.isTap(dragDist, pressTime) && ghost.forgeCard != null) {
                                parkDeparted(ghost);
                                commitHandTravel(ghost);
                                live.select(ghost.forgeCard);
                            }
                        } else if (HandLayout.isTap(dragDist, pressTime)) {
                            play(ghost);
                        }
                    }
                    pressGhost = -1;
                } else if (pressHand >= 0) {
                    List<HandCard> cards = live();
                    if (bottomingHand()) {
                        int hit = pickHand(mx, my);
                        if (hit >= 0 && hit < cards.size()) live.select(cards.get(hit).forgeCard);
                    } else if (draggingHand && pressHand < cards.size()) {
                        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
                        boolean inHand = HandLayout.pointerInHand(w, h, cards.size(), hover, dragInHand, mx, my);
                        if (liveReady()) {
                            HandCard c = cards.get(pressHand);
                            if (!inHand && c.cy > HandLayout.castLineY(h)) {
                                parkDeparted(c);
                                commitHandTravel(c);
                                live.select(c.forgeCard);
                            }
                            else if (inHand) {
                                int drop = HandLayout.reorderIndex(w, h, cards.size(), pressHand, mx);
                                if (drop != pressHand) {
                                    CardView gripped = c.forgeCard;
                                    reorderLive(pressHand, drop);
                                    if (gripped != null) live.reorderHand(gripped, drop);
                                }
                                hover = drop;
                            }
                        } else if (inHand) {
                            int drop = HandLayout.reorderIndex(w, h, cards.size(), pressHand, mx);
                            reorderLive(pressHand, drop);
                            hover = drop;
                        } else {
                            HandCard c = cards.get(pressHand);
                            if (c.cy > HandLayout.castLineY(h)) play(c);
                        }
                    } else if (liveReady() && HandLayout.isTap(dragDist, pressTime) && pressHand < cards.size()) {
                        parkDeparted(cards.get(pressHand));
                        live.select(cards.get(pressHand).forgeCard);
                    }
                } else if (pressedPerm != null) {
                    if (HandLayout.isTap(dragDist, pressTime)) clickBoard(pressedPerm);
                    else if (draggingBoard) releaseBoardDrag(pressedPerm);
                }
                draggingHand = false;
                dragInHand = false;
                draggingBoard = false;
                draggingCommand = false;
                pressHand = -1;
                pressedPerm = null;
                return true;
            }
            @Override public boolean mouseMoved(int sx, int sy) {
                readPointer(sx, sy);
                updatePointerHover();
                return false;
            }
            @Override public boolean scrolled(float amountX, float amountY) {
                readPointer(Gdx.input.getX(), Gdx.input.getY());
                float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
                if (openZone != ZoneView.NONE) {
                    BattlefieldLayout.ZoneGrid zoneGrid = openGrid(sw, sh);
                    if (zoneGrid != null && zoneGrid.contains(mx, my) && zoneGrid.maxScroll > 0f) {
                        zoneScroll = Math.max(0f, Math.min(zoneGrid.maxScroll,
                                zoneScroll + amountY * (zoneGrid.cardH + 8f)));
                        return true;
                    }
                }
                if (liveReady() && live.typeChoiceOpen() && typeMaxScroll > 0f) {
                    typeScroll = BattlefieldLayout.clampChoiceScroll(
                            typeScroll + amountY * (BattlefieldLayout.CHOICE_ROW_MIN + BattlefieldLayout.CHOICE_ROW_GAP),
                            typeMaxScroll);
                    return true;
                }
                if (liveReady() && live.cardSheetOpen() && sheetCount() > 0) {
                    sheetScroll += amountY * 56f;
                    syncSheetScroll(sw, sh);
                    return true;
                }
                if (picking) {
                    if (managing) scrollManage(amountY);
                    else scrollPicker(amountY);
                    return true;
                }
                if (lobbying) return true;
                if (sideLogShown() != null && sideLogPanelContains(sw, sh)) {
                    logScroll = clampLogScroll(sw, sh, logScroll + amountY * 22f);
                    return true;
                }
                if (cardZoom || openZone == ZoneView.NONE) return false;
                BattlefieldLayout.ZoneGrid grid = openGrid(sw, sh);
                if (grid == null || !grid.contains(mx, my) || grid.maxScroll <= 0f) return false;
                zoneScroll = Math.max(0f, Math.min(grid.maxScroll, zoneScroll + amountY * (grid.cardH + 8f)));
                return true;
            }
        });
    }

    private void resetAll() {
        board.clear();
        hand.clear();
        ghosts.clear();
        stack.clear();
        phase = Phase.MAIN;
        resetStrip();
        modal = Modal.NONE;
        examining = -1;
        yourLife = 20;
        theirLife = 20;
        yourStartingLife = yourLife;
        theirStartingLife = theirLife;
        yourPoison = 2;
        theirPoison = 5;
        yourGy = 0;
        theirGy = 0;
        fillZone(yourGrave, "Grizzly Bears", "Shock", "Duress", "Giant Growth");
        fillZone(theirGrave, "Ogre", "Shock");
        fillZone(yourExile, "Cavern of Souls", "Veteran", "Forest");
        fillZone(theirExile, "Sprite", "Duress");
        fillZone(yourLibrary, pile(
                "Forest", "Grizzly Bears", "Shock", "Plains", "Island", "Duress", "Giant Growth", "Llanowar Elves"));
        fillZone(theirLibrary, pile(
                "Mountain", "Swamp", "Ogre", "Shock", "Duress", "Sprite", "Goblin", "Dark Ritual"));
        revealLibrary(yourLibrary, true);
        revealLibrary(theirLibrary, false);
        yourTopRevealed = true;
        grippingTop = false;
        draggingTop = false;
        lookTop = false;
        pendingTopLook = 0;
        openZone = ZoneView.NONE;
        zoneQuery = "";
        zoneType = null;
        zoneScroll = 0f;
        draggingZoneScroll = false;
        closeZoom();
        hoverPerm = null;
        shownPerm = null;
        permRaise = 0f;
        hoverZone = -1;
        shownZone = -1;
        zoneRaise = 0f;
        pressingCluster = false;
        draggingCluster = false;
        hover = -1;
        pressHand = -1;
        pressCommand = -1;
        hoverCommand = -1;
        pressGhost = -1;
        ghostHover = -1;
        hoverExile = -1;
        pressExile = -1;
        draggingCommand = false;
        draggingHand = false;
        dragInHand = false;
        draggingBoard = false;
        pressedPerm = null;
        selected = null;
        choiceHand = null;
        choicePerm = null;
        clearShockLock();
        shockHover = null;
        note = "Main. The board stays put until you play, tap, attack, or activate.";
        artCursor = 0;
        addLand(false, "Mountain");
        addLand(false, "Swamp");
        addLand(false, "Mountain");
        addLand(false, "Swamp");
        keys(addCreature(false, "Ogre", 3, 3, false, 0, 0, null, null, false), "trample");
        keys(addCreature(false, "Sprite", 1, 1, false, 0, 0, null, null, false), "flying", "shroud");
        addLand(true, "Plains");
        addLand(true, "Island");
        addLand(true, "Forest");
        addLand(true, "Forest");
        Perm cavern = addLand(true, "Cavern of Souls");
        cavern.choiceCard = true;
        cavern.choice = null;
        addCreature(true, "Bear Cub", 2, 2, true, 0, 0, null, null, false);
        keys(addCreature(true, "Growth Wolf", 2, 2, false, 2, 0, "+2/+2 Giant Growth", null, false), "trample");
        Perm cursed = addCreature(true, "Cursed Knight", 4, 4, false, 0, 0, null, null, false);
        cursed.minusCounters = 2;
        keys(addCreature(true, "Hero", 1, 1, false, 0, 0, null, null, false), "vigilance", "reach", "hexproof").counters = 3;
        keys(addCreature(true, "Veteran", 3, 3, false, 0, 0, null, null, true), "first strike");
        addPermanent(true, "Sol Ring", BattlefieldLayout.Kind.ARTIFACT);
        addPermanent(true, "Glorious Anthem", BattlefieldLayout.Kind.ENCHANTMENT);
        addToken(true, "Clue", BattlefieldLayout.Kind.ARTIFACT);
        addToken(true, "Clue", BattlefieldLayout.Kind.ARTIFACT);
        addToken(true, "Treasure", BattlefieldLayout.Kind.ARTIFACT);
        keys(addToken(true, "Insect", BattlefieldLayout.Kind.CREATURE), "flying");
        yourCommanderDamage.clear();
        theirCommanderDamage.clear();
        yourCommanderDamage.add(demoPip("1:Demo", "Commander", 6));
        theirCommanderDamage.add(demoPip("2:Demo", "Commander", 4));
        Perm liliana = addPermanent(true, "Liliana of the Veil", BattlefieldLayout.Kind.PLANESWALKER);
        liliana.loyalty = 3;
        liliana.printedLoyalty = 3;
        liliana.hasLoyalty = true;
        keys(liliana, "hexproof");
        Perm chandra = addPermanent(true, "Chandra, Torch of Defiance", BattlefieldLayout.Kind.PLANESWALKER);
        chandra.loyalty = 4;
        chandra.printedLoyalty = 4;
        chandra.hasLoyalty = true;
        addPermanent(false, "Worn Powerstone", BattlefieldLayout.Kind.ARTIFACT);
        addPermanent(false, "Bad Moon", BattlefieldLayout.Kind.ENCHANTMENT);
        Perm jace = addPermanent(false, "Jace Beleren", BattlefieldLayout.Kind.PLANESWALKER);
        jace.loyalty = 3;
        jace.printedLoyalty = 3;
        jace.hasLoyalty = true;
        keys(jace, "shroud");
        addHand("Forest", BattlefieldLayout.Kind.LAND, false, 0, 0, false);
        addHand("Grizzly", BattlefieldLayout.Kind.CREATURE, false, 2, 2, false);
        addHand("Cavern of Souls", BattlefieldLayout.Kind.LAND, true, 0, 0, false);
        addHand("Shock", BattlefieldLayout.Kind.SPELL, false, 0, 0, true);
        addHand("Shock", BattlefieldLayout.Kind.SPELL, false, 0, 0, true);
        addHand("Impulse", BattlefieldLayout.Kind.SPELL, false, 0, 0, true);
        addGhost("Grizzly Bears", BattlefieldLayout.Kind.CREATURE, 2, 2);
        layout(0f);
    }

    private Perm keys(Perm p, String... words) {
        p.keywords = java.util.Arrays.asList(words);
        return p;
    }

    private Perm addPermanent(boolean you, String name, BattlefieldLayout.Kind kind) {
        Perm p = new Perm();
        p.you = you;
        p.name = name;
        p.kind = kind;
        p.tex = nextArt();
        board.add(p);
        return p;
    }

    private Perm addToken(boolean you, String name, BattlefieldLayout.Kind kind) {
        Perm p = addPermanent(you, name, kind);
        p.token = true;
        if (kind == BattlefieldLayout.Kind.CREATURE) {
            p.power = 1;
            p.toughness = 1;
        }
        return p;
    }

    private static BattlefieldLive.CommanderPip demoPip(String key, String name, int amount) {
        BattlefieldLive.CommanderPip pip = new BattlefieldLive.CommanderPip();
        pip.key = key;
        pip.name = name;
        pip.amount = amount;
        return pip;
    }

    private Perm addLand(boolean you, String name) {
        Perm p = new Perm();
        p.you = you;
        p.name = name;
        p.kind = BattlefieldLayout.Kind.LAND;
        p.tex = nextArt();
        board.add(p);
        return p;
    }

    private Perm addCreature(boolean you, String name, int power, int toughness, boolean sick,
                              int buff, int debuff, String buffLine, String debuffLine, boolean ability) {
        Perm p = new Perm();
        p.you = you;
        p.name = name;
        p.kind = BattlefieldLayout.Kind.CREATURE;
        p.power = power;
        p.toughness = toughness;
        p.sick = sick;
        p.buff = buff;
        p.debuff = debuff;
        p.buffLine = buffLine;
        p.debuffLine = debuffLine;
        p.hasAbility = ability;
        p.tex = nextArt();
        board.add(p);
        return p;
    }

    private void addHand(String name, BattlefieldLayout.Kind kind, boolean choice, int power, int toughness,
                          boolean instant) {
        HandCard c = new HandCard();
        c.name = name;
        c.kind = kind;
        c.choice = choice;
        c.instant = instant;
        c.power = power;
        c.toughness = toughness;
        c.tex = nextArt();
        hand.add(c);
    }

    private Texture nextArt() {
        if (art.isEmpty()) return null;
        Texture t = art.get(Math.floorMod(artCursor, art.size()));
        artCursor++;
        return t;
    }

    private void loadArt() {
        for (Path path : HandPrototype.findCardImages(14)) {
            try {
                art.add(loadRounded(path));
            } catch (RuntimeException ignored) {
                /* grey card with a name is enough */
            }
        }
    }

    private static Texture loadRounded(Path file) {
        Pixmap src = new Pixmap(new FileHandle(file.toFile()));
        Pixmap pm = src;
        if (src.getFormat() != Pixmap.Format.RGBA8888) {
            pm = new Pixmap(src.getWidth(), src.getHeight(), Pixmap.Format.RGBA8888);
            pm.setBlending(Pixmap.Blending.None);
            pm.drawPixmap(src, 0, 0);
            src.dispose();
        }
        HandPrototype.roundCardCorners(pm);
        Texture tex = new Texture(pm, true);
        tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
        pm.dispose();
        return tex;
    }

    private void newTurn() {
        cancelModal();
        while (!stack.isEmpty()) cancelTop();
        phase = Phase.MAIN;
        for (Perm p : board) {
            if (!p.alive) continue;
            p.tapped = false;
            p.sick = false;
            p.attacking = false;
            clearBlock(p);
            p.damage = 0;
        }
        lightStrip("NEW_TURN");
        yieldRest = false;
        stopScriptWalk();
        combatDamageHold = 0f;
        note = "New turn. Summoning sickness cleared, permanents untapped.";
    }

    private void resetStrip() {
        copyYields(yourYield);
        copyYields(enemyYield);
        lightStrip("OPENING");
        yieldRest = false;
        stopScriptWalk();
        stripHover = -1;
        combatDamageHold = 0f;
    }

    private static void copyYields(boolean[] into) {
        boolean[] defaults = BattlefieldLayout.defaultAutoYields();
        System.arraycopy(defaults, 0, into, 0, Math.min(into.length, defaults.length));
    }

    private void lightStrip(String moment) {
        BattlefieldLayout.StripMoment lit = BattlefieldLayout.scriptedStrip(moment);
        activeOwner = lit.owner;
        shownPhase = lit.phase;
    }

    private boolean[] yieldsFor(BattlefieldLayout.StripOwner owner) {
        return owner == BattlefieldLayout.StripOwner.ENEMY ? enemyYield : yourYield;
    }

    private boolean yieldOn(BattlefieldLayout.PhaseBox box) {
        if (box == null) return true;
        boolean[] yields = yieldsFor(box.owner);
        BattlefieldLayout.StripPhase phase = box.phase;
        if (phase == null && box.combatMarker) phase = BattlefieldLayout.StripPhase.BEGIN_COMBAT;
        if (phase == null) return true;
        int step = phase.ordinal();
        if (step < 0 || step >= yields.length) return true;
        return yields[step];
    }

    /**
     * Right-click: yield every earlier chip on that row and hold this one.
     * A pass-until, not a settings window.
     */
    private boolean passUntilStrip(float sw, float sh, float px, float py) {
        BattlefieldLayout.PhaseBox[] boxes = BattlefieldLayout.phaseStrip(sw, sh, youDx, youDy, enemyDx, enemyDy);
        int stripHit = BattlefieldLayout.phaseIndexAt(sw, sh, px, py, youDx, youDy, enemyDx, enemyDy);
        if (stripHit < 0 || stripHit >= boxes.length) return false;
        BattlefieldLayout.PhaseBox box = boxes[stripHit];
        boolean[] yields = yieldsFor(box.owner);
        BattlefieldLayout.StripPhase hold = box.phase;
        if (hold == null && box.combatMarker) hold = BattlefieldLayout.StripPhase.BEGIN_COMBAT;
        if (hold == null) return true;
        boolean reached = false;
        for (BattlefieldLayout.StripPhase step : BattlefieldLayout.visibleStripPhases()) {
            int i = step.ordinal();
            if (i < 0 || i >= yields.length) continue;
            if (step == hold) {
                yields[i] = false;
                rememberPhaseStop(box.owner, i, false);
                reached = true;
            } else if (!reached) {
                yields[i] = true;
                rememberPhaseStop(box.owner, i, true);
            }
        }
        savePhaseStops();
        return true;
    }

    /** Toggle the clicked step for that row only. A miss, or a short yield array, does not throw. */
    private boolean toggleStripYield(float sw, float sh, float px, float py) {
        BattlefieldLayout.PhaseBox[] boxes = BattlefieldLayout.phaseStrip(sw, sh, youDx, youDy, enemyDx, enemyDy);
        int stripHit = BattlefieldLayout.phaseIndexAt(sw, sh, px, py, youDx, youDy, enemyDx, enemyDy);
        if (stripHit < 0 || stripHit >= boxes.length) return false;
        BattlefieldLayout.PhaseBox box = boxes[stripHit];
        boolean[] yields = yieldsFor(box.owner);
        BattlefieldLayout.StripPhase phase = box.phase;
        if (phase == null && box.combatMarker) phase = BattlefieldLayout.StripPhase.BEGIN_COMBAT;
        if (phase == null) return true;
        int step = phase.ordinal();
        if (step < 0 || step >= yields.length) return true;
        yields[step] = BattlefieldLayout.toggleAutoYield(yields[step]);
        rememberPhaseStop(box.owner, step, yields[step]);
        savePhaseStops();
        return true;
    }

    private void rememberPhaseStop(BattlefieldLayout.StripOwner owner, int step, boolean yield) {
        if (live == null) return;
        live.setPhaseYield(owner != BattlefieldLayout.StripOwner.ENEMY, step, yield);
    }

    private Path phaseStopFile() {
        String home = System.getProperty("forge.test.userDir");
        Path dir = home == null || home.isBlank()
                ? Path.of("forge-gui-mobile-dev", "target", "battlefield-user")
                : Path.of(home);
        return dir.toAbsolutePath().normalize().resolve("phase-stops.txt");
    }

    /** Keep the file when it is missing. A saved dashboard comes back on the next match. */
    private void loadPhaseStops() {
        Path file = phaseStopFile();
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file)) {
                int eq = line.indexOf('=');
                if (eq < 0) continue;
                String key = line.substring(0, eq).trim();
                String bits = line.substring(eq + 1).trim();
                boolean[] into = yieldRowForKey(key);
                if (into == null) continue;
                if (isLegacyCombatMarkerKey(key)) {
                    migrateLegacyCombatMarker(into, bits);
                    continue;
                }
                for (int i = 0; i < into.length && i < bits.length(); i++) {
                    char bit = bits.charAt(i);
                    if (bit == '0' || bit == '1') into[i] = bit == '1';
                }
            }
        } catch (IOException ignored) {
            // The opening pattern stays.
        }
    }

    private boolean[] yieldRowForKey(String key) {
        if (key == null) return null;
        String who = key;
        int dot = key.indexOf('.');
        if (dot >= 0) who = key.substring(0, dot);
        if ("ash".equalsIgnoreCase(who)) return enemyYield;
        if ("you".equalsIgnoreCase(who)) return yourYield;
        return null;
    }

    /** Old extra CB chip, not Forge's begin-combat step. */
    private static boolean isLegacyCombatMarkerKey(String key) {
        if (key == null) return false;
        int dot = key.indexOf('.');
        String tag = dot >= 0 ? key.substring(dot + 1) : "";
        return "CB".equalsIgnoreCase(tag);
    }

    /** A leftover CB stop becomes BC so the green does not vanish. */
    private static void migrateLegacyCombatMarker(boolean[] into, String bits) {
        if (into == null || bits == null || bits.isEmpty()) return;
        char bit = bits.charAt(0);
        if (bit != '0' && bit != '1') return;
        int bc = BattlefieldLayout.StripPhase.BEGIN_COMBAT.ordinal();
        if (bc >= 0 && bc < into.length) into[bc] = bit == '1';
    }

    // Future: loadable stop profiles per deck (counter vs instant-speed) via deck info. Not now.
    private void savePhaseStops() {
        Path file = phaseStopFile();
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, "you=" + phaseBits(yourYield) + "\nash=" + phaseBits(enemyYield) + "\n");
        } catch (IOException ignored) {
            // The stops still hold for this window.
        }
    }

    private static String phaseBits(boolean[] yields) {
        StringBuilder bits = new StringBuilder();
        for (boolean yield : yields) bits.append(yield ? '1' : '0');
        return bits.toString();
    }

    private void pushPhaseStops() {
        if (live == null) return;
        for (int i = 0; i < yourYield.length; i++) live.setPhaseYield(true, i, yourYield[i]);
        for (int i = 0; i < enemyYield.length; i++) live.setPhaseYield(false, i, enemyYield[i]);
    }

    /** Combat has been applied. Light combat damage, then Main 2. */
    private void showCombatDamageThenMain2() {
        phase = Phase.MAIN;
        shownPhase = BattlefieldLayout.currentStripPhase("COMBAT_DAMAGE");
        combatDamageHold = 0.9f;
        if (scriptWalk == ScriptWalk.ENEMY) scriptHold = 0.25f;
    }

    private void yourAttack() {
        if (modal != Modal.NONE) return;
        phase = Phase.ATTACKERS;
        yieldRest = false;
        stopScriptWalk();
        lightStrip("YOUR_ATTACK");
        for (Perm p : board) {
            clearBlock(p);
            if (!p.you) p.attacking = false;
        }
        note = "Your attack. Legal creatures are highlighted.";
    }

    private void theirAttack() {
        if (modal != Modal.NONE) return;
        Perm ogre = find("Ogre");
        if (ogre == null) {
            note = "Ogre is not on the board.";
            return;
        }
        phase = Phase.BLOCKERS;
        yieldRest = false;
        stopScriptWalk();
        lightStrip("THEIR_ATTACK");
        for (Perm p : board) {
            p.attacking = false;
            clearBlock(p);
        }
        ogre.attacking = true;
        note = "Their attack. Drag creatures onto an attacker to block.";
    }

    private void play(HandCard c) {
        if (c == null || !c.alive || modal != Modal.NONE) return;
        draggingHand = false;
        dragInHand = false;
        pressHand = -1;
        hover = -1;
        BattlefieldLayout.Speed speed = c.instant ? BattlefieldLayout.Speed.INSTANT : BattlefieldLayout.Speed.SORCERY;
        if (!BattlefieldLayout.mayCast(speed, stack.size())) {
            note = c.name + " waits until the stack is empty.";
            return;
        }
        if (c.kind == BattlefieldLayout.Kind.SPELL || c.choice) {
            queue(c);
            return;
        }
        spawn(c, null);
    }

    /** The card leaves the pointer and becomes the top line of the stack log. */
    private void queue(HandCard c) {
        boolean needsTarget = c.kind == BattlefieldLayout.Kind.SPELL && !"Impulse".equals(c.name);
        BattlefieldLayout.StackEntry entry = BattlefieldLayout.castSpell(c.name, needsTarget);
        StackItem item = new StackItem();
        item.card = c;
        item.name = entry.name;
        item.fullText = entry.fullText;
        item.needsTarget = entry.needsTarget;
        item.needsChoice = c.choice && !needsTarget;
        c.alive = false;
        c.parked = false;
        stack.add(item);
        examining = -1;
        clearShockLock();
        shockHover = null;
        if (item.needsTarget) note = BattlefieldLayout.TARGET_PROMPT;
        else if (item.needsChoice) note = "Choose a creature type. It stays printed on the card.";
    }

    private void spawn(HandCard c, String type) {
        Perm p = new Perm();
        p.you = true;
        p.name = c.name;
        p.kind = c.kind == BattlefieldLayout.Kind.SPELL ? BattlefieldLayout.Kind.CREATURE : c.kind;
        p.power = c.power;
        p.toughness = c.toughness;
        p.sick = c.kind == BattlefieldLayout.Kind.CREATURE;
        p.choiceCard = c.choice;
        p.choice = type;
        p.tex = c.tex;
        p.cx = c.cx;
        p.cy = c.cy;
        p.w = c.w;
        p.h = c.h;
        p.thunk = 1f;
        board.add(p);
        c.alive = false;
        c.parked = false;
        note = p.sick ? "Played " + p.name + ". Summoning sickness: it cannot attack yet."
                : "Played " + p.name + ".";
    }

    private void choose(String type) {
        StackItem top = top();
        if (top != null && top.needsChoice && top.card != null) {
            String name = top.card.name;
            spawn(top.card, type);
            stack.remove(stack.size() - 1);
            if (examining >= stack.size()) examining = -1;
            note = type + " stays on " + name + ".";
            return;
        }
        if (choiceHand != null) {
            spawn(choiceHand, type);
            note = type + " stays on " + choiceHand.name + ".";
            choiceHand = null;
        } else if (choicePerm != null) {
            choicePerm.choice = type;
            note = type + " stays on " + choicePerm.name + ".";
            choicePerm = null;
        }
        modal = Modal.NONE;
    }

    private void clickBoard(Perm p) {
        if (!p.alive) return;
        if (liveReady()) {
            // A short click during declare blockers must not assign whatever attacker Forge highlighted.
            if (declaringBlocks() && p.kind == BattlefieldLayout.Kind.CREATURE && (p.you || p.attacking)) {
                selected = p;
                return;
            }
            live.select(p.forgeCard);
            selected = p;
            return;
        }
        if (phase == Phase.ATTACKERS && !p.you && selected != null && selected.you) {
            tryAttack(selected);
            return;
        }
        if (phase == Phase.BLOCKERS && !p.you && p.attacking && selected != null && selected.you
                && selected.kind == BattlefieldLayout.Kind.CREATURE) {
            assignBlock(selected, p);
            return;
        }
        selected = p;
        if (phase == Phase.ATTACKERS && p.you) {
            tryAttackToggle(p);
            return;
        }
        if (phase == Phase.BLOCKERS && p.you && p.kind == BattlefieldLayout.Kind.CREATURE) {
            if (p.blocking) {
                clearBlock(p);
                note = "Block removed.";
            } else {
                note = "Now click the attacking creature.";
            }
            return;
        }
        if (p.choiceCard && (p.choice == null || p.choice.isBlank())) {
            choicePerm = p;
            choiceHand = null;
            modal = Modal.CHOOSE_TYPE;
            return;
        }
        p.tapped = !p.tapped;
        note = p.name + (p.tapped ? " tapped." : " untapped.");
    }

    private void releaseBoardDrag(Perm p) {
        if (!p.alive) return;
        selected = p;
        float h = Gdx.graphics.getHeight();
        if (phase == Phase.ATTACKERS && p.you) {
            if (!legalAttack(p)) return;
            if (BattlefieldLayout.declaresAttack(h, mx, my, themBadge.x, themBadge.y)) {
                p.attacking = true;
                note = p.name + " is attacking.";
            } else {
                note = "Release in their half, or near their life, to attack.";
            }
            return;
        }
        if (declaringBlocks() && p.you && p.kind == BattlefieldLayout.Kind.CREATURE) {
            Perm attacker = attackerUnderPointer();
            if (attacker == null) {
                note = "Release on the attacker to block.";
                return;
            }
            if (liveReady()) {
                if (p.blockTarget == attacker) return;
                CardView previous = p.blockTarget == null ? null : p.blockTarget.forgeCard;
                live.assignBlock(p.forgeCard, attacker.forgeCard, previous);
                return;
            }
            assignBlock(p, attacker);
        }
    }

    private void tryAttackToggle(Perm p) {
        if (!legalAttack(p)) return;
        p.attacking = !p.attacking;
        note = p.attacking ? p.name + " is attacking." : p.name + " pulled back.";
    }

    private void tryAttack(Perm p) {
        if (p == null || !p.you) return;
        if (!legalAttack(p)) return;
        p.attacking = true;
        note = p.name + " is attacking.";
    }

    private boolean legalAttack(Perm p) {
        if (BattlefieldLayout.canAttack(p.kind, p.tapped, p.sick)) return true;
        note = p.sick ? p.name + " has summoning sickness and cannot attack yet."
                : p.name + " cannot attack.";
        return false;
    }

    private void assignBlock(Perm blocker, Perm attacker) {
        if (blocker == null || !blocker.you || blocker.kind != BattlefieldLayout.Kind.CREATURE) return;
        if (attacker == null || !attacker.alive || attacker.you || !attacker.attacking) {
            note = "Nothing is attacking.";
            return;
        }
        blocker.blocking = true;
        blocker.blockTarget = attacker;
        note = blocker.name + " blocks " + attacker.name + ".";
    }

    private void clearBlock(Perm p) {
        p.blocking = false;
        p.blockTarget = null;
    }

    private void confirmAttack() {
        int damage = 0;
        int before = theirLife;
        for (Perm p : board) {
            if (!p.alive || !p.you || !p.attacking) continue;
            damage += power(p);
            p.tapped = true;
            p.attacking = false;
        }
        if (damage == 0) {
            note = "No attackers.";
            return;
        }
        theirLife -= damage;
        showCombatDamageThenMain2();
        note = BattlefieldLayout.lifeChange("Them", before, damage);
    }

    private void confirmBlocks() {
        List<Perm> attackers = enemyAttackers();
        if (attackers.isEmpty()) {
            phase = Phase.MAIN;
            shownPhase = BattlefieldLayout.currentStripPhase("MAIN_2");
            note = "No attacker.";
            return;
        }
        int before = yourLife;
        int damage = 0;
        StringBuilder summary = new StringBuilder();
        for (Perm atk : attackers) {
            List<Perm> blockers = blockersOf(atk);
            if (blockers.isEmpty()) {
                damage += power(atk);
                atk.attacking = false;
                atk.tapped = true;
                continue;
            }
            int blkPower = 0;
            for (Perm blk : blockers) blkPower += power(blk);
            if (blkPower >= toughness(atk)) kill(atk);
            else {
                atk.attacking = false;
                atk.tapped = true;
            }
            for (Perm blk : blockers) {
                BattlefieldLayout.CombatPreview preview = BattlefieldLayout.previewBlock(
                        power(atk), toughness(atk), power(blk), toughness(blk));
                if (summary.length() > 0) summary.append(' ');
                summary.append(preview.summary);
                if (preview.blockerDies) kill(blk);
                else clearBlock(blk);
            }
        }
        if (damage > 0) yourLife -= damage;
        showCombatDamageThenMain2();
        if (summary.length() == 0) note = BattlefieldLayout.lifeChange("You", before, damage);
        else if (damage > 0) note = summary + " " + BattlefieldLayout.lifeChange("You", before, damage);
        else note = summary.toString();
    }

    private void activate() {
        Perm veteran = find("Veteran");
        Perm hero = find("Hero");
        if (veteran == null || !BattlefieldLayout.canActivate(veteran.kind, veteran.tapped, veteran.sick)) {
            note = "Veteran cannot activate (tapped or summoning sick).";
            return;
        }
        if (hero == null) {
            note = "Hero is not on the board.";
            return;
        }
        abilityLine = BattlefieldLayout.abilityPreview("Veteran", "Hero", hero.counters + 1);
        modal = Modal.ABILITY;
    }

    private void confirmModal() {
        if (modal == Modal.ABILITY) {
            Perm veteran = find("Veteran");
            Perm hero = find("Hero");
            if (veteran != null && hero != null) {
                veteran.tapped = true;
                hero.counters++;
                note = "Hero now shows " + hero.counters + " +1/+1.";
            }
            modal = Modal.NONE;
            return;
        }
    }

    private void resolveShock() {
        StackItem top = top();
        if (top == null || !top.needsTarget) return;
        if (top.targetPerm != null) {
            Perm target = top.targetPerm;
            int printed = BattlefieldLayout.shownToughness(target.toughness, target.buff,
                    target.debuff, target.counters);
            String line = BattlefieldLayout.shockOnCreature(target.name, printed,
                    target.damage, BattlefieldLayout.SHOCK_DAMAGE);
            target.damage += BattlefieldLayout.SHOCK_DAMAGE;
            if (target.damage >= printed) kill(target);
            note = line;
        } else if (top.targetYou) {
            int before = yourLife;
            yourLife -= BattlefieldLayout.SHOCK_DAMAGE;
            note = BattlefieldLayout.lifeChange("You", before, BattlefieldLayout.SHOCK_DAMAGE);
        } else if (top.targetThem) {
            int before = theirLife;
            theirLife -= BattlefieldLayout.SHOCK_DAMAGE;
            note = BattlefieldLayout.lifeChange("Them", before, BattlefieldLayout.SHOCK_DAMAGE);
        } else {
            return;
        }
        if (top.card != null) {
            top.card.alive = false;
            top.card.parked = false;
        }
        yourGy++;
        addZone(yourGrave, top.name);
        stack.remove(stack.size() - 1);
        if (examining >= stack.size()) examining = -1;
        applyLockFromTop();
        StackItem next = top();
        if (next != null && BattlefieldLayout.waitingForTarget(
                new BattlefieldLayout.StackEntry(next.name, next.fullText, next.needsTarget, next.targetLabel))) {
            note = note + "  " + BattlefieldLayout.TARGET_PROMPT;
        }
    }

    /**
     * Life/avatar is the player. Emblems and commander pips near the badge
     * must not eat this click when the player is a legal target.
     */
    private boolean pressPlayerCluster() {
        boolean you = youBadge.contains(mx, my);
        boolean them = themBadge.contains(mx, my);
        if (!you && !them) return false;
        if (liveReady() && live.choosingTarget()) {
            if (you && live.legalPlayerTarget(true)) {
                live.selectPlayer(true);
                return true;
            }
            if (them && live.legalPlayerTarget(false)) {
                live.selectPlayer(false);
                return true;
            }
            return false;
        }
        pressingCluster = true;
        draggingCluster = false;
        clusterOwner = you ? BattlefieldLayout.StripOwner.YOU : BattlefieldLayout.StripOwner.ENEMY;
        clusterOriginDx = you ? youDx : enemyDx;
        clusterOriginDy = you ? youDy : enemyDy;
        pressX = mx;
        pressY = my;
        pressTime = 0;
        dragDist = 0;
        return true;
    }

    private void clickShockTarget() {
        if (!targeting() || examining >= 0) return;
        updateShockHover();
        StackItem top = top();
        if (top == null) return;
        if (shockHover != null) {
            BattlefieldLayout.TargetAssignment assigned = BattlefieldLayout.clickTarget(
                    BattlefieldLayout.castSpell(top.name, true), shockHover.name);
            top.targetLabel = assigned.entry.target;
            top.targetPerm = shockHover;
            top.targetYou = false;
            top.targetThem = false;
            shockLock = shockHover;
            shockLockYou = false;
            shockLockThem = false;
            top.preview = BattlefieldLayout.stackOutcome(top.name, assigned.entry.target);
        } else if (shockHoverYou) {
            BattlefieldLayout.TargetAssignment assigned = BattlefieldLayout.clickTarget(
                    BattlefieldLayout.castSpell(top.name, true), "You");
            top.targetLabel = assigned.entry.target;
            top.targetPerm = null;
            top.targetYou = true;
            top.targetThem = false;
            shockLock = null;
            shockLockYou = true;
            shockLockThem = false;
            top.preview = BattlefieldLayout.stackOutcome(top.name, assigned.entry.target);
        } else if (shockHoverThem) {
            BattlefieldLayout.TargetAssignment assigned = BattlefieldLayout.clickTarget(
                    BattlefieldLayout.castSpell(top.name, true), "Them");
            top.targetLabel = assigned.entry.target;
            top.targetPerm = null;
            top.targetYou = false;
            top.targetThem = true;
            shockLock = null;
            shockLockYou = false;
            shockLockThem = true;
            top.preview = BattlefieldLayout.stackOutcome(top.name, assigned.entry.target);
        }
    }

    private void updateShockHover() {
        shockHover = null;
        shockHoverYou = false;
        shockHoverThem = false;
        List<Perm> creatures = new ArrayList<>();
        for (Perm p : board) {
            if (p.alive && p.kind == BattlefieldLayout.Kind.CREATURE) creatures.add(p);
        }
        int n = creatures.size();
        float[] cx = new float[n];
        float[] cy = new float[n];
        float[] ww = new float[n];
        float[] hh = new float[n];
        for (int i = 0; i < n; i++) {
            Perm p = creatures.get(i);
            cx[i] = p.cx;
            cy[i] = p.cy;
            ww[i] = p.w;
            hh[i] = p.h;
        }
        int hit = BattlefieldLayout.stickIndexIfInside(mx, my, cx, cy, ww, hh);
        if (hit >= 0) {
            shockHover = creatures.get(hit);
            return;
        }
        if (youBadge.contains(mx, my)) {
            shockHoverYou = true;
            return;
        }
        if (themBadge.contains(mx, my)) {
            shockHoverThem = true;
        }
    }

    private void clearShockLock() {
        shockLock = null;
        shockLockYou = false;
        shockLockThem = false;
    }

    /** The arrow follows the top spell's target after that spell becomes the top. */
    private void applyLockFromTop() {
        clearShockLock();
        shockHover = null;
        StackItem top = top();
        if (top == null) return;
        shockLock = top.targetPerm;
        shockLockYou = top.targetYou;
        shockLockThem = top.targetThem;
    }

    private void cancelModal() {
        if (choiceHand != null) choiceHand.parked = false;
        choiceHand = null;
        choicePerm = null;
        modal = Modal.NONE;
        note = "Cancelled.";
    }

    private void cancelTop() {
        if (stack.isEmpty()) return;
        StackItem item = stack.remove(stack.size() - 1);
        if (item.card != null) {
            item.card.alive = true;
            item.card.parked = false;
        }
        if (examining >= stack.size()) examining = -1;
        applyLockFromTop();
        note = item.name + " is back in your hand.";
        StackItem next = top();
        if (next != null && next.needsTarget && (next.targetLabel == null || next.targetLabel.isBlank())) {
            note = item.name + " is back in your hand. " + BattlefieldLayout.TARGET_PROMPT;
        }
    }

    private void kill(Perm p) {
        if (!p.alive) return;
        p.alive = false;
        p.attacking = false;
        clearBlock(p);
        if (p.you) {
            yourGy++;
            addZone(yourGrave, p.name);
        } else {
            theirGy++;
            addZone(theirGrave, p.name);
        }
    }

    private int power(Perm p) {
        return BattlefieldLayout.shownPower(p.power, p.buff, p.debuff + p.minusCounters, p.counters);
    }

    private int toughness(Perm p) {
        return BattlefieldLayout.shownToughness(p.toughness, p.buff, p.debuff + p.minusCounters, p.counters) - p.damage;
    }

    private Perm find(String name) {
        for (Perm p : board) if (p.alive && name.equals(p.name)) return p;
        return null;
    }

    private Perm opponentAttacker() {
        for (Perm p : board) if (p.alive && !p.you && p.attacking) return p;
        return null;
    }

    private Perm yourBlocker() {
        for (Perm p : board) if (p.alive && p.you && p.blocking) return p;
        return null;
    }

    private List<Perm> enemyAttackers() {
        List<Perm> out = new ArrayList<>();
        for (Perm p : board) if (p.alive && !p.you && p.attacking) out.add(p);
        return out;
    }

    private List<Perm> blockersOf(Perm attacker) {
        List<Perm> out = new ArrayList<>();
        for (Perm p : board) {
            if (p.alive && p.you && p.blocking && p.blockTarget == attacker) out.add(p);
        }
        return out;
    }

    private List<Perm> row(boolean you, BattlefieldLayout.Row which) {
        List<Perm> out = new ArrayList<>();
        for (Perm p : board) {
            if (!p.alive) continue;
            if (p.auraHost != null && p.auraHost.alive) continue;
            BattlefieldLayout.Row band = bandOf(p);
            if (band != which) continue;
            if (which == BattlefieldLayout.Row.BATTLE) {
                if (p.protectYou != you) continue;
            } else if (p.you != you) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    /**
     * Noncreature tokens (Clue, Treasure, Food, and any other) sit in his left
     * token pocket. Detection is token + not a creature, not a name list.
     * Creature tokens stay on the creature bed. Ash's tokens stay in OTHER.
     */
    private static BattlefieldLayout.Row bandOf(Perm p) {
        if (p.kind == BattlefieldLayout.Kind.LAND) return BattlefieldLayout.Row.LAND;
        if (p.kind == BattlefieldLayout.Kind.PLANESWALKER) return BattlefieldLayout.Row.PLANESWALKER;
        if (p.kind == BattlefieldLayout.Kind.BATTLE) return BattlefieldLayout.Row.BATTLE;
        if (p.you && p.token && p.kind != BattlefieldLayout.Kind.CREATURE) {
            return BattlefieldLayout.Row.TOKEN;
        }
        if (p.kind == BattlefieldLayout.Kind.ENCHANTMENT) {
            return BattlefieldLayout.Row.ENCHANTMENT;
        }
        if (p.kind == BattlefieldLayout.Kind.ARTIFACT) {
            return BattlefieldLayout.Row.ENCHANTMENT;
        }
        return BattlefieldLayout.Row.CREATURE;
    }

    private void layout(float dt) {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        if (w < 1f || h < 1f) return;
        placeRow(w, h, false, BattlefieldLayout.Row.LAND);
        placeRow(w, h, false, BattlefieldLayout.Row.TOKEN);
        placeRow(w, h, false, BattlefieldLayout.Row.PLANESWALKER);
        placeRow(w, h, false, BattlefieldLayout.Row.BATTLE);
        placeRow(w, h, false, BattlefieldLayout.Row.ENCHANTMENT);
        placeRow(w, h, false, BattlefieldLayout.Row.OTHER);
        placeRow(w, h, false, BattlefieldLayout.Row.CREATURE);
        placeRow(w, h, true, BattlefieldLayout.Row.LAND);
        placeRow(w, h, true, BattlefieldLayout.Row.TOKEN);
        placeRow(w, h, true, BattlefieldLayout.Row.PLANESWALKER);
        placeRow(w, h, true, BattlefieldLayout.Row.BATTLE);
        placeRow(w, h, true, BattlefieldLayout.Row.ENCHANTMENT);
        placeRow(w, h, true, BattlefieldLayout.Row.OTHER);
        placeRow(w, h, true, BattlefieldLayout.Row.CREATURE);
        tuckAttachedAuras();
        layoutEmblems(w, h);
        for (Perm p : board) {
            if (!p.alive) {
                if (dt > 0f) p.alpha = Math.max(0f, p.alpha - dt / 0.4f);
                continue;
            }
            boolean fresh = p.cx == 0f && p.cy == 0f && (Math.abs(p.tx) > 1f || Math.abs(p.ty) > 1f);
            if (dt <= 0f || fresh) {
                p.cx = p.tx; p.cy = p.ty; p.w = p.tw; p.h = p.th; p.rot = p.trot;
            } else {
                p.cx = HandLayout.easeToward(p.cx, p.tx, dt);
                p.cy = HandLayout.easeToward(p.cy, p.ty, dt);
                p.w = HandLayout.easeToward(p.w, p.tw, dt);
                p.h = HandLayout.easeToward(p.h, p.th, dt);
                float spin = 1f - (float) Math.exp(-dt * HandLayout.EASE_RATE);
                p.rot = lerpAngle(p.rot, p.trot, spin);
                if (p.thunk > 0f) p.thunk = Math.max(0f, p.thunk - dt / 0.22f);
            }
        }
        layoutHand(w, h, dt);
        layoutGhosts(w, h, dt);
        layoutStack(w, h);
        aimCastFlight(w, h);
        layoutChrome(w, h);
    }

    private void placeRow(float w, float h, boolean you, BattlefieldLayout.Row which) {
        List<Perm> cards = row(you, which);
        BattlefieldLayout.Side side = you ? BattlefieldLayout.Side.YOU : BattlefieldLayout.Side.OPPONENT;
        Map<String, List<Perm>> groups = new LinkedHashMap<>();
        for (Perm p : cards) {
            groups.computeIfAbsent(pileKey(p), k -> new ArrayList<>()).add(p);
        }
        List<List<Perm>> piles = new ArrayList<>(groups.values());
        for (Perm p : cards) {
            p.pileCount = 1;
            p.pileFront = false;
        }
        if (which == BattlefieldLayout.Row.PLANESWALKER) {
            BattlefieldLayout.StripOwner owner = you
                    ? BattlefieldLayout.StripOwner.YOU : BattlefieldLayout.StripOwner.ENEMY;
            float dx = you ? youDx : enemyDx;
            float dy = you ? youDy : enemyDy;
            float clusterLeft = BattlefieldLayout.clusterAt(w, h, owner, dx, dy).left;
            float stackLeft = Float.NaN;
            if (!stack.isEmpty()) {
                HandLayout.Pose show = BattlefieldLayout.stackShowcase(w, h);
                stackLeft = show.cx - show.w / 2f - 10f;
            }
            int n = piles.size();
            for (int g = 0; g < n; g++) {
                List<Perm> pile = piles.get(g);
                boolean tapped = pile.get(0).tapped;
                for (int c = 0; c < pile.size(); c++) {
                    Perm p = pile.get(c);
                    p.pileCount = pile.size();
                    p.pileFront = c == 0;
                    p.pileLayer = c;
                    applyPose(p, BattlefieldLayout.planeswalkerInRow(
                            w, h, side, g, n, c, pile.size(), tapped, clusterLeft, stackLeft), h);
                }
            }
            return;
        }
        if (which == BattlefieldLayout.Row.BATTLE) {
            int n = piles.size();
            for (int g = 0; g < n; g++) {
                List<Perm> pile = piles.get(g);
                for (int c = 0; c < pile.size(); c++) {
                    Perm p = pile.get(c);
                    p.pileCount = pile.size();
                    p.pileFront = c == 0;
                    p.pileLayer = c;
                    applyPose(p, BattlefieldLayout.siegeInPocket(
                            w, h, side, g, n, c, pile.size()), h);
                }
            }
            return;
        }
        if (which == BattlefieldLayout.Row.TOKEN) {
            if (!you) return;
            float commandRight = commandRightFor(true);
            boolean hasLands = !row(true, BattlefieldLayout.Row.LAND).isEmpty();
            BattlefieldLayout.FieldBox pocket = BattlefieldLayout.yourTokenPocket(w, h, commandRight, hasLands);
            int n = piles.size();
            for (int g = 0; g < n; g++) {
                List<Perm> pile = piles.get(g);
                boolean tapped = pile.get(0).tapped;
                for (int c = 0; c < pile.size(); c++) {
                    Perm p = pile.get(c);
                    p.pileCount = pile.size();
                    p.pileFront = c == 0;
                    p.pileLayer = c;
                    applyPose(p, BattlefieldLayout.tokenInPocket(
                            w, h, side, g, n, c, pile.size(), tapped, pocket), h);
                }
            }
            return;
        }
        if (which == BattlefieldLayout.Row.ENCHANTMENT || which == BattlefieldLayout.Row.OTHER) {
            BattlefieldLayout.FieldBox pocket = BattlefieldLayout.aePocket(w, h, side);
            int n = piles.size();
            for (int g = 0; g < n; g++) {
                List<Perm> pile = piles.get(g);
                boolean tapped = pile.get(0).tapped;
                for (int c = 0; c < pile.size(); c++) {
                    Perm p = pile.get(c);
                    p.pileCount = pile.size();
                    p.pileFront = c == 0;
                    p.pileLayer = c;
                    applyPose(p, BattlefieldLayout.aeTileInPocket(
                            w, h, side, g, n, c, pile.size(), tapped, pocket), h);
                }
            }
            return;
        }
        if (which == BattlefieldLayout.Row.CREATURE) {
            int n = piles.size();
            BattlefieldLayout.StripOwner owner = you
                    ? BattlefieldLayout.StripOwner.YOU : BattlefieldLayout.StripOwner.ENEMY;
            float dx = you ? youDx : enemyDx;
            float dy = you ? youDy : enemyDy;
            float clusterLeft = BattlefieldLayout.clusterAt(w, h, owner, dx, dy).left;
            float stackLeft = Float.NaN;
            if (!stack.isEmpty()) {
                HandLayout.Pose show = BattlefieldLayout.stackShowcase(w, h);
                stackLeft = show.cx - show.w / 2f - 10f;
            }
            float[] landLane = landLaneEdges(w, h, you);
            int landPiles = landPileCount(you);
            for (int g = 0; g < n; g++) {
                List<Perm> pile = piles.get(g);
                boolean tapped = pile.get(0).tapped;
                for (int c = 0; c < pile.size(); c++) {
                    Perm p = pile.get(c);
                    p.pileCount = pile.size();
                    p.pileFront = c == 0;
                    p.pileLayer = c;
                    applyPose(p, BattlefieldLayout.creaturePile(
                            w, h, side, g, n, c, pile.size(), tapped, clusterLeft, stackLeft,
                            landPiles, landLane[0], landLane[1]), h);
                }
            }
            return;
        }
        if (which == BattlefieldLayout.Row.LAND) {
            piles.sort((a, b) -> {
                Perm left = a.get(0);
                Perm right = b.get(0);
                String na = left.name == null ? "" : left.name;
                String nb = right.name == null ? "" : right.name;
                int byName = na.compareToIgnoreCase(nb);
                if (byName != 0) return byName;
                int byTap = Boolean.compare(left.tapped, right.tapped);
                if (byTap != 0) return byTap;
                String ta = left.choice == null ? "" : left.choice;
                String tb = right.choice == null ? "" : right.choice;
                return ta.compareToIgnoreCase(tb);
            });
            float laneLeft = Float.NaN;
            float laneRight = Float.NaN;
            float laneCy = Float.NaN;
            float laneH = Float.NaN;
            if (you) {
                float commandRight = commandRightFor(true);
                boolean hasTokens = !row(true, BattlefieldLayout.Row.TOKEN).isEmpty();
                BattlefieldLayout.FieldBox pocket = BattlefieldLayout.yourLandPocket(
                        w, h, commandRight, hasTokens, piles.size());
                laneLeft = pocket.left;
                laneRight = pocket.right;
                laneCy = pocket.cy();
                laneH = pocket.height();
            } else {
                BattlefieldLayout.StripOwner owner = BattlefieldLayout.StripOwner.ENEMY;
                float dx = enemyDx;
                float dy = enemyDy;
                float clusterLeft = BattlefieldLayout.clusterAt(w, h, owner, dx, dy).left;
                laneLeft = BattlefieldLayout.deckFamilyRight(w, h, owner, dx) + 8f;
                float commandRight = commandRightFor(false);
                if (!Float.isNaN(commandRight)) laneLeft = Math.max(laneLeft, commandRight + 8f);
                laneRight = clusterLeft - 8f;
                if (!row(false, BattlefieldLayout.Row.PLANESWALKER).isEmpty()) {
                    float pwRight = BattlefieldLayout.planeswalkerPocket(w, h, side).right;
                    if (pwRight + 8f > laneLeft) laneLeft = pwRight + 8f;
                }
                float laneW = Math.max(40f, laneRight - laneLeft);
                laneH = BattlefieldLayout.landBlockHeight(
                        piles.size(), laneW,
                        BattlefieldLayout.landTileWidth(h), BattlefieldLayout.landTileHeight(h));
            }
            int n = piles.size();
            for (int g = 0; g < n; g++) {
                List<Perm> pile = piles.get(g);
                boolean tapped = pile.get(0).tapped;
                for (int c = 0; c < pile.size(); c++) {
                    Perm p = pile.get(c);
                    p.pileCount = pile.size();
                    p.pileFront = c == 0;
                    p.pileLayer = c;
                    applyPose(p, BattlefieldLayout.landPile(
                            w, h, side, g, n, c, pile.size(), tapped,
                            laneLeft, laneRight, laneCy, laneH), h);
                }
            }
            return;
        }
    }

    /**
     * Right edge of emblems or commander pips that already sit on the life-right
     * lane. Enchantments start after that chrome so they do not cover it.
     */
    private float lifeRightOccupied(float windowW, float windowH, boolean you) {
        BattlefieldLayout.StripOwner owner = you
                ? BattlefieldLayout.StripOwner.YOU : BattlefieldLayout.StripOwner.ENEMY;
        float dx = you ? youDx : enemyDx;
        float dy = you ? youDy : enemyDy;
        BattlefieldLayout.PlayerCluster cluster = BattlefieldLayout.clusterAt(windowW, windowH, owner, dx, dy);
        float right = cluster.life.x + cluster.life.w / 2f;
        BattlefieldLayout.Side side = you ? BattlefieldLayout.Side.YOU : BattlefieldLayout.Side.OPPONENT;
        List<Emblem> marks = you ? yourEmblems : theirEmblems;
        if (marks != null && !marks.isEmpty()) {
            HandLayout.Pose[] poses = BattlefieldLayout.emblemRow(
                    windowW, windowH, side, cluster.life.x, cluster.life.y,
                    cluster.life.w, cluster.life.h, marks.size());
            for (HandLayout.Pose pose : poses) {
                if (pose != null) right = Math.max(right, pose.cx + pose.w / 2f);
            }
        }
        List<BattlefieldLive.CommanderPip> pips = you ? yourCommanderDamage : theirCommanderDamage;
        int pipCount = 0;
        if (pips != null) {
            for (BattlefieldLive.CommanderPip pip : pips) {
                if (pip != null && pip.amount > 0) pipCount++;
            }
        }
        if (pipCount > 0) {
            float[] seat = BattlefieldLayout.commanderPipOrigin(
                    windowW, windowH, cluster.life, owner, pipCount);
            if (Math.abs(seat[1] - cluster.life.y) < 20f) {
                float icon = 16f;
                float gap = 4f;
                float pipW = icon + 18f;
                float pipRight = seat[0] + pipCount * pipW + Math.max(0, pipCount - 1) * gap;
                right = Math.max(right, pipRight);
            }
        }
        return right;
    }

    /** Unique land piles on this seat (name + tap + chosen type + sick). */
    private int landPileCount(boolean you) {
        Map<String, Boolean> keys = new LinkedHashMap<>();
        for (Perm p : row(you, BattlefieldLayout.Row.LAND)) {
            if (p == null || !p.alive) continue;
            keys.putIfAbsent(pileKey(p), Boolean.TRUE);
        }
        return keys.size();
    }

    /** Left and right of this seat's land strip. NaN when the strip is unused. */
    private float[] landLaneEdges(float w, float h, boolean you) {
        if (you) {
            BattlefieldLayout.FieldBox pocket = BattlefieldLayout.yourLandPocket(
                    w, h, commandRightFor(true), !row(true, BattlefieldLayout.Row.TOKEN).isEmpty(),
                    landPileCount(true));
            return new float[] { pocket.left, pocket.right };
        }
        BattlefieldLayout.StripOwner owner = BattlefieldLayout.StripOwner.ENEMY;
        float left = BattlefieldLayout.deckFamilyRight(w, h, owner, enemyDx) + 8f;
        float commandRight = commandRightFor(false);
        if (!Float.isNaN(commandRight)) left = Math.max(left, commandRight + 8f);
        float right = BattlefieldLayout.clusterAt(w, h, owner, enemyDx, enemyDy).left - 8f;
        if (!row(false, BattlefieldLayout.Row.PLANESWALKER).isEmpty()) {
            float pwRight = BattlefieldLayout.planeswalkerPocket(w, h, BattlefieldLayout.Side.OPPONENT).right;
            if (pwRight + 8f > left) left = pwRight + 8f;
        }
        return new float[] { left, right };
    }

    /** Right edge of this side's command-zone cards, or NaN when that zone is empty. */
    private float commandRightFor(boolean you) {
        float right = Float.NaN;
        for (CommandSlot slot : commandSlots) {
            if (slot.you != you) continue;
            float edge = slot.cx + slot.cw / 2f;
            right = Float.isNaN(right) ? edge : Math.max(right, edge);
        }
        return right;
    }

    /**
     * Identical permanents share a pile only when the whole state matches.
     * Tap, summoning sickness, power, toughness, damage, a can't-attack-or-block
     * lock, and the chosen type each start their own pile. Two Caverns named
     * Sliver and Human do not stack. Lands use the same rule and spread
     * sideways along the outer edge.
     */
    private static String pileKey(Perm p) {
        String name = p.name == null ? "" : p.name;
        String tap = p.tapped ? "t" : "u";
        String sick = p.sick ? "s" : "-";
        String kind = p.token ? "k" : "-";
        String lock = p.restraint == null || p.restraint.isEmpty() ? "-" : p.restraint;
        String fly = hasKeyword(p, "flying") ? "f" : "-";
        String reach = hasKeyword(p, "reach") ? "r" : "-";
        String ward = hasKeyword(p, "hexproof") ? "x" : "-";
        String shroud = hasKeyword(p, "shroud") ? "u" : "-";
        String haste = hasKeyword(p, "haste") ? "h" : "-";
        String type = p.choice == null || p.choice.isBlank() ? "-" : p.choice;
        return name + "|" + kind + "|" + tap + "|" + sick + "|" + p.power + "/" + p.toughness
                + "|d" + p.damage + "|L" + p.loyalty + "|D" + p.defense + "|" + lock + "|"
                + fly + reach + ward + shroud + haste + "|" + type;
    }

    /** Forge keyword already stored on the tile, including one an effect granted. */
    private static boolean hasKeyword(Perm p, String word) {
        return p != null && hasKeyword(p.keywords, word);
    }

    private static boolean hasKeyword(java.util.List<String> keywords, String word) {
        if (keywords == null || word == null) return false;
        for (String key : keywords) {
            if (word.equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    /** Moves a creature-row pose onto a planeswalker or other row, keeping the line spacing. */
    private static float shiftRow(float cy, float windowH, BattlefieldLayout.Side side, BattlefieldLayout.Row row) {
        float from = BattlefieldLayout.rowCenterY(windowH, side, BattlefieldLayout.Row.CREATURE);
        float to = BattlefieldLayout.rowCenterY(windowH, side, row);
        return cy + (to - from);
    }

    private void applyPose(Perm p, HandLayout.Pose pose, float h) {
        p.tx = pose.cx;
        p.ty = pose.cy;
        p.tw = pose.w;
        p.th = pose.h;
        p.trot = pose.rotationDeg;
        if (p.attacking) {
            float slide = BattlefieldLayout.ATTACK_SLIDE_FRAC * h;
            p.ty += p.you ? slide : -slide;
        }
        p.flyLift = 0f;
        if (p.kind == BattlefieldLayout.Kind.CREATURE && hasKeyword(p, "flying")) {
            BattlefieldLayout.Side side = p.you ? BattlefieldLayout.Side.YOU : BattlefieldLayout.Side.OPPONENT;
            float lifted = BattlefieldLayout.flyRestY(p.ty, p.th, h, side);
            p.flyLift = lifted - p.ty;
            p.ty = lifted;
        }
    }

    private void layoutHand(float w, float h, float dt) {
        List<HandCard> live = live();
        int n = live.size();
        for (int i = 0; i < n; i++) {
            HandCard c = live.get(i);
            if (draggingHand && i == pressHand) {
                if (dt > 0f) easeHand(c, dt);
                continue;
            }
            HandLayout.Pose pose = draggingHand
                    ? HandLayout.dragLayoutPose(w, h, n, i, hover, dragInHand, pressHand, mx, my)
                    : HandLayout.layoutPose(w, h, n, i, hover, false);
            c.tcx = pose.cx; c.tcy = pose.cy; c.tw = pose.w; c.th = pose.h; c.trot = pose.rotationDeg;
            if (dt <= 0f) snapHand(c);
            else easeHand(c, dt);
        }
        if (pressHand >= 0 || pressSheetHit >= 0 || pressTypeLine >= 0) pressTime += dt;
    }

    private void layoutGhosts(float w, float h, float dt) {
        List<HandCard> cards = liveGhosts();
        int n = cards.size();
        for (int i = 0; i < n; i++) {
            HandCard c = cards.get(i);
            HandLayout.Pose pose = ghostPose(w, h, n, i, ghostHover);
            c.tcx = pose.cx;
            c.tcy = pose.cy;
            c.tw = pose.w;
            c.th = pose.h;
            c.trot = pose.rotationDeg;
            if (dt <= 0f) snapHand(c);
            else easeHand(c, dt);
        }
        if (pressGhost >= 0) pressTime += dt;
    }

    private HandLayout.Pose ghostPose(float w, float h, int n, int i, int hoverIndex) {
        HandLayout.Pose pose = HandLayout.layoutPose(w, h, n, i, hoverIndex, false);
        float shift = BattlefieldLayout.ghostFanShift(w, h, live().size(), hover);
        return new HandLayout.Pose(pose.cx + shift, pose.cy, pose.w, pose.h, pose.rotationDeg, pose.scale);
    }

    private int ghostIndexAt(float x, float y) {
        List<HandCard> cards = liveGhosts();
        int n = cards.size();
        if (n == 0) return -1;
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        float pad = HandLayout.OPEN_HIT_PAD_FRAC * h;
        for (int i = n - 1; i >= 0; i--) {
            if (HandLayout.pointHitsPose(ghostRestPose(w, h, n, i), x, y, pad)) return i;
        }
        return -1;
    }

    private HandLayout.Pose ghostRestPose(float w, float h, int n, int i) {
        HandLayout.Pose pose = HandLayout.restPose(w, h, n, i);
        float shift = BattlefieldLayout.ghostFanShift(w, h, live().size(), hover);
        return new HandLayout.Pose(pose.cx + shift, pose.cy, pose.w, pose.h, pose.rotationDeg, pose.scale);
    }

    private int ghostHoverAt(float w, float h) {
        return ghostIndexAt(mx, my);
    }

    /**
     * The top object is a large hand-sized card in the right stack well.
     * Buried objects stay small rows under it.
     */
    private void layoutStack(float w, float h) {
        for (StackItem item : stack) item.showcase = false;
        if (stack.isEmpty()) {
            stackBottomY = h * 0.5f;
            return;
        }
        StackItem top = stack.get(stack.size() - 1);
        HandLayout.Pose show = placeShowcase(w, h, top);
        top.showcase = true;
        top.rowW = show.w;
        top.rowH = show.h;
        top.rowX = show.cx;
        top.rowY = show.cy;
        top.thumbW = show.w;
        top.thumbH = show.h;
        top.thumbX = show.cx;
        top.thumbY = show.cy;
        float colW = Math.min(show.w, 360f);
        float y = show.cy - show.h / 2f - 8f;
        boolean below = true;
        stackBottomY = y;
        for (int i = stack.size() - 2; i >= 0; i--) {
            StackItem item = stack.get(i);
            boolean examined = i == examining || item == hoverStack;
            float rh = Math.min(examined ? 200f : 86f, BattlefieldLayout.stackRowHeight(h, examined));
            if (below && y - rh < 8f) {
                below = false;
                y = show.cy + show.h / 2f + 8f + rh;
            }
            item.rowW = colW;
            item.rowH = rh;
            item.rowX = show.cx;
            item.rowY = below ? y - rh / 2f : y - rh / 2f;
            item.thumbH = Math.min(rh - 16f, 72f);
            item.thumbW = item.thumbH * HandLayout.CARD_ASPECT;
            item.thumbX = item.rowX - item.rowW / 2f + item.thumbW / 2f + 8f;
            item.thumbY = item.rowY;
            y += below ? -(rh + 6f) : (rh + 6f);
            stackBottomY = Math.min(stackBottomY, item.rowY - item.rowH / 2f);
        }
    }

    private void layoutEmblems(float w, float h) {
        placeEmblemRow(yourEmblems, w, h, true);
        placeEmblemRow(theirEmblems, w, h, false);
    }

    private void placeEmblemRow(List<Emblem> marks, float w, float h, boolean you) {
        BattlefieldLayout.StripOwner owner = you
                ? BattlefieldLayout.StripOwner.YOU : BattlefieldLayout.StripOwner.ENEMY;
        float dx = you ? youDx : enemyDx;
        float dy = you ? youDy : enemyDy;
        BattlefieldLayout.PlayerCluster cluster = BattlefieldLayout.clusterAt(w, h, owner, dx, dy);
        BattlefieldLayout.Side side = you ? BattlefieldLayout.Side.YOU : BattlefieldLayout.Side.OPPONENT;
        HandLayout.Pose[] poses = BattlefieldLayout.emblemRow(
                w, h, side, cluster.life.x, cluster.life.y, cluster.life.w, cluster.life.h, marks.size());
        for (int i = 0; i < marks.size(); i++) {
            Emblem mark = marks.get(i);
            HandLayout.Pose pose = i < poses.length ? poses[i] : poses[poses.length - 1];
            mark.x = pose.cx;
            mark.y = pose.cy;
            mark.w = pose.w;
            mark.h = pose.h;
        }
    }

    private void layoutChrome(float w, float h) {
        buttons.clear();
        BattlefieldLayout.PlayerCluster you = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.YOU, youDx, youDy);
        BattlefieldLayout.PlayerCluster them = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.ENEMY, enemyDx, enemyDy);
        youBadge = badge(you.life.x, you.life.y, you.life.w, you.life.h, "you-life", Integer.toString(yourLife));
        themBadge = badge(them.life.x, them.life.y, them.life.w, them.life.h, "them-life", Integer.toString(theirLife));
        if (liveMode) return;
        float bw = 156f;
        float bh = 34f;
        float x = 0.28f * w;
        float y = 0.70f * h;
        y = stack(x, y, bw, bh, "your-attack", "Your attack");
        y = stack(x, y, bw, bh, "their-attack", "Their attack");
        y = stack(x, y, bw, bh, "new-turn", "New turn");
        if (phase == Phase.ATTACKERS && modal == Modal.NONE) {
            y = stack(x, y, bw, bh, "all-attack", "All attack");
            y = stack(x, y, bw, bh, "confirm-attack", "Attack");
        }
        if (phase == Phase.BLOCKERS && modal == Modal.NONE) {
            y = stack(x, y, bw, bh, "no-blocks", "No blocks");
            y = stack(x, y, bw, bh, "confirm-blocks", "Confirm blocks");
        }
        Perm veteran = find("Veteran");
        if (modal == Modal.NONE && veteran != null
                && BattlefieldLayout.canActivate(veteran.kind, veteran.tapped, veteran.sick)) {
            y = stack(x, y, bw, bh, "activate", "Activate");
        }
        if (modal == Modal.NONE && hover >= 0 && hover < live().size()) {
            stack(x, y, bw, bh, "play", "Play");
        }
        if (modal == Modal.ABILITY) {
            stack(0.50f * w - 0.08f * w, 0.57f * h, 0.12f * w, bh, "confirm", "Confirm");
            stack(0.50f * w + 0.08f * w, 0.57f * h, 0.12f * w, bh, "cancel", "Cancel");
        }
        boolean choosing = modal == Modal.CHOOSE_TYPE || (top() != null && top().needsChoice);
        float choiceY = choosing ? Math.min(stackBottomY - bh * 0.2f, 0.50f * h) : 0.50f * h;
        if (choosing && !stack.isEmpty() && top().needsChoice) {
            float bx = BattlefieldLayout.stackColumnCenterX(w);
            stack(bx, choiceY, bw * 0.7f, bh, "type-dragon", "Dragon");
            stack(bx, choiceY - bh * 1.25f, bw * 0.7f, bh, "type-elf", "Elf");
            stack(bx, choiceY - bh * 2.5f, bw * 0.7f, bh, "type-goblin", "Goblin");
            stack(bx, choiceY - bh * 3.75f, bw * 0.7f, bh, "cancel", "Cancel");
        } else if (modal == Modal.CHOOSE_TYPE) {
            stack(0.38f * w, 0.57f * h, 0.12f * w, bh, "type-dragon", "Dragon");
            stack(0.52f * w, 0.57f * h, 0.12f * w, bh, "type-elf", "Elf");
            stack(0.66f * w, 0.57f * h, 0.12f * w, bh, "type-goblin", "Goblin");
        }
        if (modal != Modal.ABILITY && !stack.isEmpty() && (top() == null || !top().needsChoice)) {
            float bx = BattlefieldLayout.stackColumnCenterX(w);
            float by = Math.min(stackBottomY - bh * 0.15f, 0.52f * h);
            if (topHasTarget()) {
                stack(bx, by, bw * 0.75f, bh, "resolve", "Resolve");
                stack(bx, by - bh * 1.3f, bw * 0.75f, bh, "cancel", "Cancel");
            } else {
                stack(bx, by, bw * 0.75f, bh, "cancel", "Cancel");
            }
        }
    }

    private float stack(float x, float y, float w, float h, String id, String label) {
        buttons.add(new UiButton(id, label, x, y, w, h));
        return y - h * 1.35f;
    }

    private static UiButton badge(float x, float y, float w, float h, String id, String label) {
        return new UiButton(id, label, x, y, w, h);
    }

    private void onButton(String id) {
        if ("options".equals(id)) {
            optionsOpen = !optionsOpen;
            return;
        }
        if (liveMode) return;
        switch (id) {
            case "your-attack" -> yourAttack();
            case "their-attack" -> theirAttack();
            case "new-turn" -> newTurn();
            case "all-attack" -> allAttack();
            case "confirm-attack" -> confirmAttack();
            case "no-blocks" -> clearBlocks();
            case "confirm-blocks" -> confirmBlocks();
            case "activate" -> activate();
            case "confirm" -> confirmModal();
            case "resolve" -> { if (topHasTarget()) resolveShock(); }
            case "cancel" -> {
                if (modal != Modal.NONE) cancelModal();
                else cancelTop();
            }
            case "play" -> { if (hover >= 0 && hover < live().size()) play(live().get(hover)); }
            case "type-dragon" -> choose("Dragon");
            case "type-elf" -> choose("Elf");
            case "type-goblin" -> choose("Goblin");
            default -> { }
        }
    }

    private boolean passLit() {
        return modal == Modal.NONE && stack.isEmpty();
    }

    private boolean endTurnLit() {
        return passLit();
    }

    /** Your Declare Attackers step, and a creature can attack. */
    private boolean alphaShowing() {
        return phase == Phase.ATTACKERS
                && activeOwner == BattlefieldLayout.StripOwner.YOU
                && shownPhase == BattlefieldLayout.StripPhase.DECLARE_ATTACKERS
                && someoneCanAttack();
    }

    private boolean someoneCanAttack() {
        for (Perm p : board) {
            if (p.alive && p.you && BattlefieldLayout.canAttack(p.kind, p.tapped, p.sick)) return true;
        }
        return false;
    }

    private enum OkKind { NONE, ATTACK, BLOCKS, RESOLVE }

    private OkKind okKind() {
        if (modal != Modal.NONE) return OkKind.NONE;
        if (topHasTarget() || topAsksForLibrary()) return OkKind.RESOLVE;
        if (phase == Phase.ATTACKERS) {
            for (Perm p : board) if (p.alive && p.you && p.attacking) return OkKind.ATTACK;
        }
        if (phase == Phase.BLOCKERS && opponentAttacker() != null) return OkKind.BLOCKS;
        return OkKind.NONE;
    }

    private boolean confirmPending() {
        return modal == Modal.ABILITY || okKind() != OkKind.NONE;
    }

    /** Attack, Confirm Blocks, Resolve, or OK. Pass is not a confirm word. */
    private static boolean confirmWord(String word) {
        return "Attack".equals(word) || "Confirm Blocks".equals(word) || "Resolve".equals(word) || "OK".equals(word);
    }

    private boolean topShowing() {
        if (confirmPending()) return true;
        return activeOwner == BattlefieldLayout.StripOwner.YOU && !cancelLit();
    }

    private String topCaption() {
        if (modal == Modal.ABILITY) return "OK";
        return switch (okKind()) {
            case ATTACK -> "Attack";
            case BLOCKS -> "Confirm Blocks";
            case RESOLVE -> "Resolve";
            case NONE -> "Pass";
        };
    }

    /** Null when the bottom button is not on screen. */
    private String bottomCaption() {
        if (alphaShowing()) return "Alpha strike";
        if (activeOwner == BattlefieldLayout.StripOwner.YOU && !confirmPending() && !cancelLit()) return "End turn";
        return null;
    }

    private void pressTop() {
        if (!topShowing()) return;
        switch (topCaption()) {
            case "Pass" -> passPriority();
            case "OK" -> confirmModal();
            default -> pressOk();
        }
    }

    private boolean cancelLit() {
        if (modal != Modal.NONE || !stack.isEmpty()) return true;
        if (phase == Phase.ATTACKERS) {
            for (Perm p : board) if (p.alive && p.you && p.attacking) return true;
        }
        return phase == Phase.BLOCKERS && yourBlocker() != null;
    }

    private boolean liveReady() {
        return liveMode && live != null && live.ready();
    }

    private boolean anyChoiceOpen() {
        return liveReady() && (live.choiceOpen() || live.cardSheetOpen() || live.modeSheetOpen());
    }

    /**
     * Open grave/exile grid is inspect-only. Clicks do not pick a mode or submit.
     * Closing the grid (grave icon again) leaves the same choice.
     */
    private boolean inspectOpenZone(float sw, float sh) {
        if (openZone == ZoneView.NONE) return false;
        BattlefieldLayout.ZoneGrid grid = openGrid(sw, sh);
        if (grid != null && grid.trackContains(mx, my)) {
            draggingZoneScroll = true;
            zoneScroll = grid.scrollAt(my);
            return true;
        }
        if (handleZoneGrid(sw, sh)) return true;
        return pointerOverOpenGrid(sw, sh);
    }

    /** Grave and exile chrome. Library stays closed so a search sheet is not confused. */
    private boolean inspectZoneChrome(float sw, float sh) {
        BattlefieldLayout.WidgetBox[] boxes = BattlefieldLayout.widgetBoxes(sw, sh, youDx, youDy, enemyDx, enemyDy);
        int hit = BattlefieldLayout.widgetIndexAt(sw, sh, mx, my, youDx, youDy, enemyDx, enemyDy);
        if (hit < 0 || hit >= boxes.length) return false;
        BattlefieldLayout.WidgetBox box = boxes[hit];
        if (box.part == BattlefieldLayout.WidgetPart.GRAVE) {
            toggleZone(box.owner, ZoneView.GRAVE);
            return true;
        }
        if (box.part == BattlefieldLayout.WidgetPart.EXILE) {
            toggleZone(box.owner, ZoneView.EXILE);
            return true;
        }
        return false;
    }

    /** Pointer is on a choice row, Confirm, mode line, or picker picture. */
    private boolean choiceControlAt(float x, float y) {
        if (!anyChoiceOpen()) return false;
        if (textChoiceAt(x, y) >= 0 || textChoiceOkHit(x, y) || textChoiceSearchHit(x, y)
                || textChoiceAllHit(x, y) || modeLineAt(x, y) >= 0) return true;
        if (live.cardSheetOpen()) {
            float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
            return sheetHit(w, h) >= 0;
        }
        return false;
    }

    private SmartButton[] smartButtons(BattlefieldLayout.PlayerCluster cluster) {
        if (cluster == null || cluster.owner != BattlefieldLayout.StripOwner.YOU || cluster.actions.length < 2) {
            return new SmartButton[0];
        }
        if (liveReady()) {
            if (live.attackingNow()) {
                return new SmartButton[] {
                        new SmartButton(cluster.actions[0], live.actionWord(), true),
                        new SmartButton(cluster.actions[1], "Alpha Strike", true),
                        new SmartButton(BattlefieldLayout.cancelBox(cluster.actions[1]), "End Turn", true)
                };
            }
            if (summonHold > 0f && summonBlocks && !live.choiceOpen() && !live.okOn()
                    && !live.choosingTarget()) {
                return new SmartButton[0];
            }
            if (matchOver && cluster.actions.length >= 2) {
                return new SmartButton[] {
                        new SmartButton(cluster.actions[0], "Play again", true),
                        new SmartButton(cluster.actions[1], "Change decks", true)
                };
            }
            List<SmartButton> shown = new ArrayList<>();
            boolean tuck = "Auto".equals(live.cancelText());
            String okLabel = live.actionWord();
            boolean sheet = live.cardSheetOpen() || live.modeSheetOpen();
            boolean clickPick = (live.choiceOpen() || sheet) && !live.choiceNeedsConfirm();
            BattlefieldLive.Pic pic = live.picture();
            boolean revealYes = pic != null && pic.mayReveal;
            BattlefieldLayout.ActionBox[] slots = cluster.actions;
            if (live.pickSheetOpen()) {
                slots = BattlefieldLayout.searchActionColumn(
                        Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
            }
            if ((!clickPick || revealYes) && (sheet || live.okOn() || tuck || live.choosingTarget()
                    || "Autopay".equals(okLabel) || live.priorityWaiting() || revealYes)) {
                shown.add(new SmartButton(slots[0], okLabel, live.okOn() || live.priorityWaiting() || revealYes));
            }
            if (live.cancelOn()) shown.add(new SmartButton(slots[1], live.cancelText(), true));
            if (live.resolveAllVisible()) {
                BattlefieldLayout.ActionBox box;
                if (shown.isEmpty()) box = cluster.actions[0];
                else if (shown.size() == 1 && shown.get(0).box == cluster.actions[0]) box = cluster.actions[1];
                else box = BattlefieldLayout.cancelBox(shown.get(shown.size() - 1).box);
                shown.add(new SmartButton(box, "Resolve All", true));
            }
            return shown.toArray(new SmartButton[0]);
        }
        BattlefieldLayout.ActionBox[] slots = {
                cluster.actions[0],
                cluster.actions[1],
                BattlefieldLayout.cancelBox(cluster.actions[1])
        };
        SmartButton[] shown = new SmartButton[3];
        int n = 0;
        if (topShowing()) {
            shown[n] = new SmartButton(slots[n], topCaption(), true);
            n++;
        }
        String bottom = bottomCaption();
        if (bottom != null) {
            shown[n] = new SmartButton(slots[n], bottom, true);
            n++;
        }
        if (cancelLit()) {
            shown[n] = new SmartButton(slots[n], "Cancel", true);
            n++;
        }
        return java.util.Arrays.copyOf(shown, n);
    }

    /**
     * Remember the painted action word after the frame is drawn.
     * A leftover Next from the previous priority window must not submit a search
     * that just opened on this click.
     */
    private void tendSheetActionLatch() {
        if (!liveReady()) {
            lastActionWord = "";
            ignoreStaleAction = false;
            sheetWasOpen = false;
            return;
        }
        boolean sheet = live.pickSheetOpen() || live.cardSheetOpen() || live.modeSheetOpen();
        if (sheet && !sheetWasOpen && ("Next".equals(lastActionWord) || "Pass".equals(lastActionWord))
                && (Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Gdx.input.isTouched())) {
            ignoreStaleAction = true;
        }
        if (!sheet) ignoreStaleAction = false;
        sheetWasOpen = sheet;
        lastActionWord = live.actionWord();
    }

    /** True when a late Next/Pass click would skip a search that just opened. */
    private boolean staleNextOnSheet() {
        if (!liveReady() || !(live.pickSheetOpen() || live.cardSheetOpen() || live.modeSheetOpen())) {
            return false;
        }
        if (ignoreStaleAction) return true;
        if ("Next".equals(lastActionWord) || "Pass".equals(lastActionWord)) {
            ignoreStaleAction = true;
            return true;
        }
        return false;
    }

    /** Pass / Resolve / Confirm sit under the log and stack on some windows. Hit them first. */
    private boolean actionHit(float sw, float sh) {
        BattlefieldLayout.PlayerCluster you = BattlefieldLayout.clusterAt(
                sw, sh, BattlefieldLayout.StripOwner.YOU, youDx, youDy);
        for (SmartButton button : smartButtons(you)) {
            if (button.enabled && button.box.contains(mx, my)) return true;
        }
        return false;
    }

    private static boolean seatOkWord(String word) {
        return "Pass".equals(word) || "Resolve".equals(word) || "Next".equals(word)
                || "Confirm".equals(word) || "OK".equals(word)
                || "Confirm attack".equals(word) || "Confirm blocks".equals(word)
                || "Autopay".equals(word) || "Activate".equals(word);
    }

    private boolean pressAction(float sw, float sh) {
        BattlefieldLayout.PlayerCluster you = BattlefieldLayout.clusterAt(
                sw, sh, BattlefieldLayout.StripOwner.YOU, youDx, youDy);
        if (liveReady()) {
            for (SmartButton button : smartButtons(you)) {
                if (!button.enabled || !button.box.contains(mx, my)) continue;
                if ("Play again".equals(button.label)) playAgain();
                else if ("Change decks".equals(button.label)) changeDecks();
                else if ("Resolve All".equals(button.label)) live.pressResolveAll();
                else if ("Alpha Strike".equals(button.label)) live.alphaStrike();
                else if ("End Turn".equals(button.label)) live.endTurn();
                else if (button.box.slot == BattlefieldLayout.ActionSlot.TOP || seatOkWord(button.label)) {
                    if (staleNextOnSheet()) return true;
                    live.pressOk();
                }
                else live.pressCancel();
                return true;
            }
            return false;
        }
        for (SmartButton button : smartButtons(you)) {
            if (!button.box.contains(mx, my)) continue;
            if ("Cancel".equals(button.label)) cancelInProgress();
            else if ("End turn".equals(button.label)) endTheTurn();
            else if ("Alpha strike".equals(button.label)) allAttack();
            else pressTop();
            return true;
        }
        return false;
    }

    /** Hands priority for this stop only. A yield-the-rest flag still skips green steps after it. */
    private void passPriority() {
        if (!passLit()) return;
        if (scriptWalk == ScriptWalk.YOUR_UPKEEP) stopScriptWalk();
        if (yieldRest) skipToNextStop();
        else if (BattlefieldLayout.handsTurnOver(shownPhase)) handTurnOver();
        else {
            arrive(BattlefieldLayout.nextChronological(shownPhase));
            note = "Passed priority.";
        }
    }

    /** Yields the rest of this turn. A step marked green still stops. Main 2 is not the end. */
    private void endTheTurn() {
        if (!endTurnLit()) return;
        if (scriptWalk == ScriptWalk.YOUR_UPKEEP) stopScriptWalk();
        yieldRest = true;
        skipToNextStop();
    }

    private void skipToNextStop() {
        boolean[] yields = yieldsFor(activeOwner);
        BattlefieldLayout.StripPhase cursor = shownPhase;
        for (int n = 0; n < yields.length; n++) {
            if (BattlefieldLayout.handsTurnOver(cursor)) {
                handTurnOver();
                return;
            }
            cursor = BattlefieldLayout.nextChronological(cursor);
            if (!yields[cursor.ordinal()]) {
                arrive(cursor);
                note = cursor.fullName + " stops.";
                return;
            }
        }
        handTurnOver();
    }

    /** After cleanup, the gold outline moves to the other player's upkeep. */
    private void handTurnOver() {
        yieldRest = false;
        if (activeOwner == BattlefieldLayout.StripOwner.ENEMY) startYourUpkeep();
        else beginEnemyTurn();
    }

    private void stopScriptWalk() {
        scriptWalk = ScriptWalk.NONE;
        scriptHold = 0f;
    }

    private void beginEnemyTurn() {
        stopScriptWalk();
        for (Perm p : board) {
            if (p.you) p.attacking = false;
        }
        activeOwner = BattlefieldLayout.StripOwner.ENEMY;
        phase = Phase.MAIN;
        shownPhase = BattlefieldLayout.StripPhase.UPKEEP;
        scriptWalk = ScriptWalk.ENEMY;
        scriptHold = 0.45f;
        note = "Their turn. Upkeep.";
    }

    private void startYourUpkeep() {
        releaseUnconfirmedEnemyAttack();
        activeOwner = BattlefieldLayout.StripOwner.YOU;
        phase = Phase.MAIN;
        shownPhase = BattlefieldLayout.StripPhase.UPKEEP;
        scriptWalk = ScriptWalk.YOUR_UPKEEP;
        scriptHold = 0.7f;
        note = "Your upkeep.";
    }

    private void finishYourUpkeep() {
        stopScriptWalk();
        activeOwner = BattlefieldLayout.StripOwner.YOU;
        phase = Phase.MAIN;
        shownPhase = BattlefieldLayout.StripPhase.MAIN_1;
        note = "Your turn. Main phase, precombat.";
    }

    /** Their steps play themselves. Declare Blockers waits for the Blocks button. No timer confirms combat. */
    private void tickEnemyTurn(float dt) {
        if (scriptWalk == ScriptWalk.NONE) return;
        if (combatDamageHold > 0f) return;
        if (waitingForBlocks()) return;
        scriptHold -= dt;
        if (scriptHold > 0f) return;
        if (scriptWalk == ScriptWalk.YOUR_UPKEEP) {
            finishYourUpkeep();
            return;
        }
        if (shownPhase == BattlefieldLayout.StripPhase.CLEANUP) {
            startYourUpkeep();
            return;
        }
        boolean leavingBlocks = shownPhase == BattlefieldLayout.StripPhase.DECLARE_BLOCKERS
                && phase == Phase.BLOCKERS;
        BattlefieldLayout.StripPhase next = BattlefieldLayout.nextChronological(shownPhase);
        if (leavingBlocks) releaseUnconfirmedEnemyAttack();
        shownPhase = next;
        activeOwner = BattlefieldLayout.StripOwner.ENEMY;
        if (next == BattlefieldLayout.StripPhase.DECLARE_ATTACKERS && ogreCanAttack()) {
            declareOgreAttack();
            scriptHold = 0.9f;
            note = "Their turn. Ogre attacks.";
        } else if (next == BattlefieldLayout.StripPhase.DECLARE_BLOCKERS && opponentAttacker() != null) {
            phase = Phase.BLOCKERS;
            note = "Declare blockers. Press Confirm Blocks to resolve.";
        } else {
            if (phase != Phase.BLOCKERS) phase = Phase.MAIN;
            scriptHold = 0.42f;
            note = "Their turn. " + (next.fullName == null ? next.label : next.fullName);
        }
    }

    /** True only while their scripted turn is sitting on Declare Blockers. Assigning a blocker does not start a timer. */
    private boolean waitingForBlocks() {
        return scriptWalk == ScriptWalk.ENEMY
                && phase == Phase.BLOCKERS
                && shownPhase == BattlefieldLayout.StripPhase.DECLARE_BLOCKERS;
    }

    private boolean ogreCanAttack() {
        Perm ogre = find("Ogre");
        return ogre != null && ogre.alive && BattlefieldLayout.canAttack(ogre.kind, ogre.tapped, ogre.sick);
    }

    private void declareOgreAttack() {
        for (Perm p : board) {
            if (!p.alive) continue;
            if (p.you) clearBlock(p);
            else p.attacking = false;
        }
        Perm ogre = find("Ogre");
        if (ogre != null && ogre.alive) ogre.attacking = true;
        phase = Phase.MAIN;
    }

    private void releaseUnconfirmedEnemyAttack() {
        for (Perm p : board) {
            if (!p.alive) continue;
            if (!p.you) p.attacking = false;
            if (p.you) clearBlock(p);
        }
        if (phase == Phase.BLOCKERS) phase = Phase.MAIN;
    }

    private void arrive(BattlefieldLayout.StripPhase next) {
        if (phase == Phase.ATTACKERS && next != BattlefieldLayout.StripPhase.DECLARE_ATTACKERS) {
            for (Perm p : board) if (p.you) p.attacking = false;
        }
        shownPhase = next == null ? BattlefieldLayout.StripPhase.MAIN_1 : next;
        if (shownPhase == BattlefieldLayout.StripPhase.DECLARE_ATTACKERS
                && activeOwner == BattlefieldLayout.StripOwner.YOU) {
            phase = Phase.ATTACKERS;
        } else if (shownPhase == BattlefieldLayout.StripPhase.DECLARE_BLOCKERS && opponentAttacker() != null) {
            phase = Phase.BLOCKERS;
        } else {
            phase = Phase.MAIN;
        }
    }

    private void pressOk() {
        switch (okKind()) {
            case ATTACK -> confirmAttack();
            case BLOCKS -> confirmBlocks();
            case RESOLVE -> {
                if (topAsksForLibrary()) resolveImpulse();
                else if (topHasTarget()) resolveShock();
            }
            case NONE -> { }
        }
    }

    private void cancelInProgress() {
        if (!cancelLit()) return;
        if (modal != Modal.NONE) {
            cancelModal();
            return;
        }
        if (!stack.isEmpty()) {
            cancelTop();
            return;
        }
        if (phase == Phase.ATTACKERS) {
            boolean any = false;
            for (Perm p : board) {
                if (p.alive && p.you && p.attacking) {
                    p.attacking = false;
                    any = true;
                }
            }
            if (any) {
                note = "Attack pulled back.";
                return;
            }
        }
        if (phase == Phase.BLOCKERS && yourBlocker() != null) clearBlocks();
    }

    private void allAttack() {
        int n = 0;
        for (Perm p : board) {
            if (p.alive && p.you && BattlefieldLayout.canAttack(p.kind, p.tapped, p.sick)) {
                p.attacking = true;
                n++;
            }
        }
        note = n == 0 ? "Nothing can attack." : "All " + n + " legal creatures are attacking.";
    }

    private void clearBlocks() {
        for (Perm p : board) clearBlock(p);
        note = "No blocks.";
    }

    private List<HandCard> live() {
        List<HandCard> out = new ArrayList<>();
        for (HandCard c : hand) {
            if (c.alive && !c.parked && !travelingHand(c)) out.add(c);
        }
        return out;
    }

    private boolean travelingHand(HandCard card) {
        return card != null && card.forgeCard != null && alreadyTraveling(card.forgeCard.getId());
    }

    private List<HandCard> liveGhosts() {
        List<HandCard> out = new ArrayList<>();
        for (HandCard c : ghosts) {
            if (c.alive && !c.parked && !travelingHand(c)) out.add(c);
        }
        return out;
    }

    private void addGhost(String name, BattlefieldLayout.Kind kind, int power, int toughness) {
        HandCard c = new HandCard();
        c.name = name;
        c.kind = kind;
        c.power = power;
        c.toughness = toughness;
        Texture named = textureFor(name);
        c.tex = named != null ? named : nextArt();
        ghosts.add(c);
    }

    /** Hand, then the open grid's resting cells, then resting battlefield rectangles. */
    private void updatePointerHover() {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        logHoverTab = sideLogTabAt(w, h);
        hoverStack = null;
        if (optionsBlocks(w, h)) {
            hover = -1;
            hoverPerm = null;
            hoverZone = -1;
            hoverCommand = -1;
            ghostHover = -1;
            hoverExile = -1;
            return;
        }
        if (stackHoverAllowed() && takeStackHover()) return;
        if (sideLogBlocksBoard(w, h)) {
            hover = -1;
            hoverPerm = null;
            hoverZone = -1;
            hoverCommand = -1;
            ghostHover = -1;
            hoverExile = -1;
            return;
        }
        if (cardZoom) {
            hoverPerm = null;
            hoverZone = -1;
            hoverCommand = -1;
            hoverExile = -1;
            return;
        }
        if (blockingDrag()) {
            hoverPerm = null;
            hoverZone = -1;
            hoverCommand = -1;
            hoverExile = -1;
            return;
        }
        if (draggingBoard || pressedPerm != null) hoverPerm = null;
        if (draggingCommand || pressCommand >= 0) {
            hover = -1;
            hoverPerm = null;
            hoverZone = -1;
            hoverCommand = pressCommand;
            ghostHover = -1;
            hoverExile = -1;
            return;
        }
        if (draggingHand || pressHand >= 0 || pressingCluster || modal == Modal.ABILITY
                || draggingBoard || pressedPerm != null) {
            hoverCommand = -1;
            hoverExile = -1;
            return;
        }
        if (takeExileHover()) return;
        int zoneHit = zoneIndexAt(mx, my);
        if (zoneHit >= 0 || pointerOverOpenGrid(w, h)) {
            hover = -1;
            hoverPerm = null;
            hoverZone = zoneHit;
            hoverCommand = -1;
            hoverExile = -1;
            return;
        }
        hoverZone = -1;
        hoverExile = -1;
        if (choiceControlAt(mx, my)) {
            hover = -1;
            hoverPerm = null;
            hoverCommand = -1;
            ghostHover = -1;
            return;
        }
        ghostHover = ghostHoverAt(w, h);
        if (ghostHover >= 0) {
            hover = -1;
            hoverPerm = null;
            hoverCommand = -1;
            return;
        }
        int commandHit = commandIndexAt(mx, my);
        if (commandHit >= 0) {
            hover = -1;
            hoverPerm = null;
            hoverStack = null;
            hoverEmblem = null;
            hoverCommand = commandHit;
            return;
        }
        hoverCommand = -1;
        List<HandCard> live = live();
        hover = HandLayout.hoverIndexFor(w, h, live.size(), hover, mx, my);
        hoverPerm = hover >= 0 ? null : hitBoard(mx, my);
        hoverEmblem = emblemAt(mx, my);
        if (hoverEmblem != null) {
            hover = -1;
            hoverPerm = null;
            hoverStack = null;
            return;
        }
    }

    /** Gestures keep the pointer; the stack card must not steal a drag or a press. */
    private boolean stackHoverAllowed() {
        return !cardZoom && !blockingDrag() && !draggingCommand && pressCommand < 0
                && !draggingHand && pressHand < 0 && !pressingCluster
                && modal != Modal.ABILITY && !draggingBoard && pressedPerm == null;
    }

    /**
     * Hover follows the painted stack card, not the old giant well.
     * Command slots and the side log sit in the same right-hand band and must lose.
     */
    private boolean takeStackHover() {
        StackItem hit = stackUnder(mx, my);
        hoverStack = hit;
        if (hit == null) return false;
        hover = -1;
        hoverPerm = null;
        hoverZone = -1;
        hoverCommand = -1;
        hoverEmblem = null;
        ghostHover = -1;
        return true;
    }

    private void easeHover(float dt) {
        float permGoal = hoverPerm != null ? 1f : 0f;
        permRaise = HandLayout.easeToward(permRaise, permGoal, dt);
        if (hoverPerm != null) shownPerm = hoverPerm;
        else if (permRaise <= 0.02f) {
            shownPerm = null;
            permRaise = 0f;
        }
        float zoneGoal = hoverZone >= 0 ? 1f : 0f;
        zoneRaise = HandLayout.easeToward(zoneRaise, zoneGoal, dt);
        if (hoverZone >= 0) shownZone = hoverZone;
        else if (zoneRaise <= 0.02f) {
            shownZone = -1;
            zoneRaise = 0f;
        }
    }

    private boolean pointerOverOpenGrid(float w, float h) {
        if (openZone == ZoneView.NONE) return false;
        BattlefieldLayout.ZoneGrid grid = openGrid(w, h);
        return grid != null && grid.contains(mx, my);
    }

    private int zoneIndexAt(float x, float y) {
        if (openZone == ZoneView.NONE) return -1;
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        BattlefieldLayout.ZoneGrid grid = openGrid(sw, sh);
        if (grid == null) return -1;
        List<ZoneCard> cards = visibleZoneCards();
        if (!grid.contains(x, y)) return -1;
        for (int i = cards.size() - 1; i >= 0; i--) {
            if (!grid.cardOnScreen(i)) continue;
            HandLayout.Pose pose = grid.cardAt(i);
            if (Math.abs(x - pose.cx) <= pose.w / 2f && Math.abs(y - pose.cy) <= pose.h / 2f) return i;
        }
        return -1;
    }

    /** Right-click a grid card, a resting hand card, or a permanent. Does not assign a target. */
    private boolean openZoomAtPointer() {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        if (openSheetZoom()) return true;
        if (openZone != ZoneView.NONE) {
            int zoneHit = zoneIndexAt(mx, my);
            if (zoneHit >= 0) {
                List<ZoneCard> cards = visibleZoneCards();
                if (zoneHit < cards.size()) {
                    ZoneCard card = cards.get(zoneHit);
                    if (openZone == ZoneView.LIBRARY && !card.revealed) return true;
                    openCardZoom(card.name, textureFor(card.name), null, true);
                    return true;
                }
            }
            if (pointerOverOpenGrid(w, h)) return false;
        }
        int[] exileHit = exileTileHit(mx, my);
        if (exileHit != null) {
            ZoneCard tile = exileTileAt(exileHit[0] == 1, exileHit[1]);
            if (tile != null && tile.ownerSees) {
                openCardZoom(tile.name, textureFor(tile.name), null, true);
                return true;
            }
            return tile != null;
        }
        int ghostHit = ghostIndexAt(mx, my);
        if (ghostHit >= 0) {
            HandCard ghost = liveGhosts().get(ghostHit);
            openCardZoom(ghost.name, ghost.tex, null, true);
            return true;
        }
        int commandHit = commandIndexAt(mx, my);
        if (commandHit >= 0 && commandHit < commandSlots.size()) {
            CommandSlot slot = commandSlots.get(commandHit);
            BattlefieldLive.CommanderSeat seat = slot.seat;
            if (seat != null && seat.inCommand) {
                openCardZoom(seat.name, textureFor(seat.name), null, true);
                return true;
            }
            return false;
        }
        // Hidden while a library search owns the hand strip. Do not zoom those cards.
        if (!(liveReady() && live.pickSheetOpen())) {
            List<HandCard> handCards = live();
            int handHit = HandLayout.restingVisibleHit(w, h, handCards.size(), mx, my, 0f);
            if (handHit >= 0) {
                HandCard card = handCards.get(handHit);
                openCardZoom(card.name, card.tex, null, true);
                return true;
            }
        }
        Perm perm = hitBoard(mx, my);
        if (perm == null) return false;
        openBoardLook(perm, true);
        return true;
    }

    private void openBoardLook(Perm perm, boolean panel) {
        if (perm == null) return;
        openCardZoom(perm.name, perm.tex, perm, panel);
    }

    private void closeZoom() {
        cardZoom = false;
        zoomPanel = false;
        zoomPerm = null;
        modLines.clear();
    }

    private void openCardZoom(String name, Texture tex, Perm perm, boolean panel) {
        cardZoom = true;
        zoomPanel = panel;
        zoomPerm = perm;
        zoomName = name == null ? "" : name;
        zoomTex = tex;
        zoomOracle = oracleText(zoomName);
        zoomRulings = rulingsText(zoomName);
        hoverPerm = null;
        hoverZone = -1;
        modLines.clear();
    }

    private String oracleText(String name) {
        String fromRules = oracleFromRules(name);
        if (!fromRules.isBlank()) return fromRules;
        return oracleFromView(cardViewNamed(name));
    }

    /**
     * Warmaster's single face stays the oracle paragraph Forge already stores.
     * A double-faced card adds the other face Forge has: name, cost, type, and rules.
     */
    private String oracleFromRules(String name) {
        forge.card.CardRules rules = rulesFor(name);
        if (rules == null) return "";
        forge.card.ICardFace other = rules.getOtherPart();
        if (other == null) {
            String text = rules.getOracleText();
            return tidyOracle(text);
        }
        String front = faceBlock(rules.getMainPart(), false);
        String back = faceBlock(other, true);
        if (front.isBlank()) return back;
        if (back.isBlank()) return front;
        return front + "\n\n" + back;
    }

    private forge.card.CardRules rulesFor(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            if (forge.model.FModel.getMagicDb() != null) {
                forge.card.CardRules rules = forge.model.FModel.getMagicDb().getCommonCards().getRules(name, true);
                if (rules != null) {
                    String oracle = rules.getOracleText();
                    if ((oracle != null && !oracle.isBlank()) || rules.getOtherPart() != null) return rules;
                }
            }
        } catch (RuntimeException ignored) {
            /* the script file is the backup */
        }
        List<String> lines = readScript(name);
        if (lines == null) return null;
        try {
            return forge.card.CardRules.fromScript(lines);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String faceBlock(forge.card.ICardFace face, boolean includeName) {
        if (face == null) return "";
        StringBuilder text = new StringBuilder();
        if (includeName && face.getName() != null && !face.getName().isBlank()) {
            text.append(face.getName().trim()).append('\n');
        }
        forge.card.mana.ManaCost cost = face.getManaCost();
        if (cost != null && !cost.isNoCost()) {
            String shown = cost.toString();
            if (shown != null && !shown.isBlank()) text.append(shown.trim()).append('\n');
        }
        if (face.getType() != null) {
            String type = face.getType().toString();
            if (type != null && !type.isBlank()) text.append(type.trim()).append('\n');
        }
        String oracle = tidyOracle(face.getOracleText());
        if (!oracle.isBlank()) text.append(oracle);
        return text.toString().trim();
    }

    private String oracleFromView(CardView card) {
        if (card == null) return "";
        CardView.CardStateView front = card.getCurrentState();
        if (front == null) return "";
        CardView.CardStateView back = card.getAlternateState();
        if (back == null || back == front) return tidyOracle(front.getOracleText());
        String first = viewFace(front, false);
        String second = viewFace(back, true);
        if (first.isBlank()) return second;
        if (second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    private static String viewFace(CardView.CardStateView face, boolean includeName) {
        if (face == null) return "";
        StringBuilder text = new StringBuilder();
        if (includeName && face.getName() != null && !face.getName().isBlank()) {
            text.append(face.getName().trim()).append('\n');
        }
        if (face.getManaCost() != null && !face.getManaCost().isNoCost()) {
            String shown = face.getManaCost().toString();
            if (shown != null && !shown.isBlank()) text.append(shown.trim()).append('\n');
        }
        if (face.getType() != null) {
            String type = face.getType().toString();
            if (type != null && !type.isBlank()) text.append(type.trim()).append('\n');
        }
        String oracle = tidyOracle(face.getOracleText());
        if (!oracle.isBlank()) text.append(oracle);
        return text.toString().trim();
    }

    private CardView cardViewNamed(String name) {
        if (name == null || name.isBlank()) return null;
        if (zoomPerm != null && name.equals(zoomPerm.name) && zoomPerm.forgeCard != null) return zoomPerm.forgeCard;
        for (Perm perm : board) {
            if (perm.forgeCard != null && name.equals(perm.name)) return perm.forgeCard;
        }
        for (HandCard card : hand) {
            if (card.forgeCard != null && name.equals(card.name)) return card.forgeCard;
        }
        return null;
    }

    private static String tidyOracle(String text) {
        if (text == null) return "";
        return text.replace("\\n", "\n").replace("\r\n", "\n").trim();
    }

    /**
     * Rulings live on the card script ({@code Ruling:} / {@code Rulings:}) or in a local rulings
     * file Forge can already keep on disk. Nothing is fetched.
     */
    private String rulingsText(String name) {
        if (name == null || name.isBlank()) return "No rulings stored locally.";
        String cached = rulingsCache.get(name);
        if (cached != null) return cached;
        List<String> lines = new ArrayList<>();
        List<String> script = readScript(name);
        if (script != null) {
            for (String line : script) {
                if (line.startsWith("Rulings:") || line.startsWith("Ruling:")) {
                    String body = line.substring(line.indexOf(':') + 1).trim();
                    if (!body.isEmpty()) lines.add(body.replace("\\n", "\n"));
                }
            }
        }
        if (lines.isEmpty()) {
            Path file = findRulingsFile(name);
            if (file != null) {
                try {
                    for (String line : Files.readAllLines(file)) {
                        String body = line.trim();
                        if (!body.isEmpty() && body.charAt(0) != '#') lines.add(body);
                    }
                } catch (IOException ignored) {
                    lines.clear();
                }
            }
        }
        String text = lines.isEmpty() ? "No rulings stored locally." : String.join("\n", lines);
        rulingsCache.put(name, text);
        return text;
    }

    private static Path findRulingsFile(String name) {
        String slug = slugOf(name);
        if (slug.isEmpty()) return null;
        String letter = slug.substring(0, 1);
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of("forge-gui", "res", "rulings", letter, slug + ".txt"));
        candidates.add(Path.of("forge-gui", "res", "rulings", slug + ".txt"));
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            candidates.add(Path.of(local, "Forge", "Cache", "rulings", letter, slug + ".txt"));
            candidates.add(Path.of(local, "Forge", "Cache", "rulings", slug + ".txt"));
            candidates.add(Path.of(local, "Forge", "Cache", "gatherer", letter, slug + ".txt"));
            candidates.add(Path.of(local, "Forge", "Cache", "gatherer", slug + ".txt"));
        }
        String home = System.getProperty("user.home");
        if (home != null) {
            candidates.add(Path.of(home, ".forge", "Cache", "rulings", slug + ".txt"));
            candidates.add(Path.of(home, "AppData", "Local", "Forge", "Cache", "rulings", slug + ".txt"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /** Resting fan only. The grown hover is not a hit target. */
    private int pickHand(float x, float y) {
        List<HandCard> cards = live();
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        return HandLayout.restingVisibleHit(w, h, cards.size(), x, y, 0f);
    }

    /** Forge's London tuck: Cancel is Auto, and a hand click marks a bottom card. */
    private boolean bottomingHand() {
        return liveReady() && "Auto".equals(live.cancelText());
    }

    /** Keep-or-mulligan, or the Bottom marks. A grip must not reorder during either. */
    private boolean mulliganHand() {
        return bottomingHand() || keepingHand();
    }

    private boolean keepingHand() {
        return liveReady() && "Mulligan".equals(live.cancelText());
    }

    /**
     * Advisor and the free-mulligan line sit on the seat that is deciding, over that hand.
     * The other seat only hears that a mulligan is in progress.
     */
    private String mulliganAsk(String prompt) {
        if (live != null && live.otherMulligan()) {
            return "The other player is deciding on a mulligan.";
        }
        if (keepingHand() || bottomingHand()) {
            String advice = bottomAsk(liveExtra(prompt));
            String cost = live.mulliganLine();
            if (cost != null && (advice == null || !advice.contains(cost))) {
                return advice == null || advice.isBlank() ? cost : advice + "\n" + cost;
            }
            return advice;
        }
        return bottomAsk(liveExtra(prompt));
    }

    /**
     * Resting tile only. The full-card hover is not a hit target, so a drag cannot start on it.
     * A tucked aura or equipment is only the title sliver behind its host. The host keeps the card body.
     */
    private Perm hitBoard(float x, float y) {
        float sh = Gdx.graphics.getHeight();
        float pad = 0.01f * sh;
        Perm sliver = null;
        int sliverIndex = Integer.MAX_VALUE;
        for (Perm p : board) {
            if (!p.alive || !p.tucked || p.auraHost == null || !p.auraHost.alive) continue;
            HandLayout.Pose title = BattlefieldLayout.auraTitleSliver(restingPose(p), p.tuckPeek);
            if (!HandLayout.pointHitsPose(title, x, y, 0f)) continue;
            if (p.tuckIndex < sliverIndex) {
                sliverIndex = p.tuckIndex;
                sliver = p;
            }
        }
        if (sliver != null) return sliver;
        Perm best = null;
        int bestLayer = Integer.MAX_VALUE;
        int bestIndex = -1;
        for (int i = board.size() - 1; i >= 0; i--) {
            Perm p = board.get(i);
            if (!p.alive || p.tucked || hidePermForTravel(p)) continue;
            boolean hit = p.token
                    ? tokenHits(restingPose(p), x, y, pad)
                    : HandLayout.pointHitsPose(restingPose(p), x, y, pad);
            if (!hit) continue;
            if (best == null || p.pileLayer < bestLayer
                    || (p.pileLayer == bestLayer && i > bestIndex)) {
                best = p;
                bestLayer = p.pileLayer;
                bestIndex = i;
            }
        }
        return best;
    }

    private static HandLayout.Pose restingPose(Perm p) {
        float cx = p.tw > 1f ? p.tx : p.cx;
        float cy = p.th > 1f ? p.ty : p.cy;
        float rw = p.tw > 1f ? p.tw : p.w;
        float rh = p.th > 1f ? p.th : p.h;
        float rot = p.tw > 1f ? p.trot : p.rot;
        return new HandLayout.Pose(cx, cy, rw, rh, rot, 1f);
    }

    /** True when the pointer is on the enlarged hover and not on that card's resting tile. */
    private boolean overBoardPreview(float x, float y) {
        if (hoverPerm == null || !hoverPerm.alive || cardZoom) return false;
        if (hoverPerm.kind == BattlefieldLayout.Kind.BATTLE && !siegeNeedsHover(hoverPerm)) return false;
        float sh = Gdx.graphics.getHeight();
        HandLayout.Pose tile = hoverPerm.tucked
                ? BattlefieldLayout.auraTitleSliver(restingPose(hoverPerm), hoverPerm.tuckPeek)
                : restingPose(hoverPerm);
        if (HandLayout.pointHitsPose(tile, x, y, 0.01f * sh)) return false;
        float w = Gdx.graphics.getWidth();
        HandLayout.Pose full = boardPreviewPose(w, sh, hoverPerm);
        HandLayout.Pose preview = hoverPerm.tex == null ? missingArtAt(full.cx, full.cy, w, sh) : full;
        return HandLayout.pointHitsPose(preview, x, y, 0f);
    }

    /**
     * Full-card hover for a battlefield permanent. A tucked aura uses the host as the
     * obstacle, grown by the fanned titles, so the preview keeps a gap and does not
     * cover the creature. The aura tile itself stays behind the host.
     */
    private HandLayout.Pose boardPreviewPose(float w, float h, Perm perm) {
        if (perm.tucked && perm.auraHost != null && perm.auraHost.alive) {
            Perm host = perm.auraHost;
            float fan = 0f;
            for (Perm aura : board) {
                if (aura.tucked && aura.auraHost == host) {
                    fan = Math.max(fan, (aura.tuckIndex + 1) * aura.tuckPeek);
                }
            }
            double rad = Math.toRadians(host.rot);
            float sin = (float) Math.sin(rad);
            float cos = (float) Math.cos(rad);
            float ox = -fan * sin;
            float oy = fan * cos;
            return BattlefieldLayout.boardHoverPreview(
                    w, h, host.cx + ox / 2f, host.cy + oy / 2f, host.w + fan, host.h + fan, perm.you);
        }
        if (perm.kind == BattlefieldLayout.Kind.BATTLE) {
            return BattlefieldLayout.boardHoverPreview(w, h, perm.cx, perm.cy, perm.w, perm.h, !perm.protectYou);
        }
        return BattlefieldLayout.boardHoverPreview(w, h, perm.cx, perm.cy, perm.w, perm.h, perm.you);
    }

    /** Offset full preview only after the enchantment tile has shrunk in a fan. */
    private boolean enchantmentNeedsHover(Perm p) {
        if (p == null || p.kind != BattlefieldLayout.Kind.ENCHANTMENT || p.tucked) return false;
        float full = BattlefieldLayout.planeswalkerCardHeight(Gdx.graphics.getHeight());
        float tile = p.th > 1f ? p.th : p.h;
        return tile < full * 0.92f;
    }

    /** Offset full preview only after the siege tile has shrunk in a fan. */
    private boolean siegeNeedsHover(Perm p) {
        if (p == null || p.kind != BattlefieldLayout.Kind.BATTLE) return false;
        float full = BattlefieldLayout.planeswalkerCardHeight(Gdx.graphics.getHeight());
        float tile = p.th > 1f ? p.th : p.h;
        return tile < full * 0.92f;
    }

    /** The attacking creature the pointer is inside, or null. Near the card does not count. */
    private Perm attackerUnderPointer() {
        Perm hit = hitBoard(mx, my);
        if (hit == null || !hit.alive || hit.you || !hit.attacking) return null;
        return hit;
    }

    private Perm snapAttacker(float x, float y) {
        List<Perm> attackers = new ArrayList<>();
        for (Perm p : board) if (p.alive && !p.you && p.attacking) attackers.add(p);
        float[] xs = new float[attackers.size()];
        float[] ys = new float[attackers.size()];
        for (int i = 0; i < attackers.size(); i++) {
            xs[i] = attackers.get(i).cx;
            ys[i] = attackers.get(i).cy;
        }
        int idx = BattlefieldLayout.snapBlocker(Gdx.graphics.getHeight(), x, y, xs, ys);
        return idx < 0 ? null : attackers.get(idx);
    }

    private UiButton hitButton(float x, float y) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            if (buttons.get(i).contains(x, y)) return buttons.get(i);
        }
        return null;
    }

    /** Whose turn, on the left at mid-screen. A real ask grows this same window down the left edge. */
    private void drawTurnInfo(float w, float h, String ask) {
        drawTurnInfo(w, h, ask, List.of());
    }

    private void drawTurnInfo(float w, float h, String ask, List<String> lines) {
        String who = bottomingHand() || keepingHand()
                ? mulliganWhose(true)
                : live != null && live.otherMulligan()
                ? mulliganWhose(false)
                : turnWhose();
        modeHits.clear();
        float pad = 10f;
        float left = 16f;
        boolean hasAsk = ask != null && !ask.isBlank();
        boolean hasLines = lines != null && !lines.isEmpty();
        float boxW = (hasAsk || hasLines) ? askPanelWidth(w, h) : 168f;
        float textW = Math.max(40f, boxW - pad * 2f);
        glyphs.setText(font, who);
        float whoH = glyphs.height;
        float baseline = h * 0.5f + whoH / 2f;
        float boxTop = baseline + pad;
        float headerBottom = baseline - whoH - pad;
        String body = hasAsk ? ask : "";
        boolean loudAsk = orderJob(body)
                || body.startsWith("Choose a target")
                || body.startsWith("Summoning");
        BitmapFont bodyFont = loudAsk ? stackFont : font;
        if (body.startsWith("Choose a target") || body.startsWith("Summoning")) bodyFont = big;
        float bodyH = 0f;
        float gap = 8f;
        if (hasAsk) {
            glyphs.setText(bodyFont, body, Color.WHITE, textW, Align.left, true);
            bodyH = glyphs.height;
        }
        float rowGap = 8f;
        float rowPadY = 8f;
        float rowInset = 8f;
        float lineTextW = Math.max(40f, boxW - rowInset * 2f - 16f);
        float[] lineHeights = new float[hasLines ? lines.size() : 0];
        float linesH = 0f;
        if (hasLines) {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i) == null ? "" : lines.get(i);
                glyphs.setText(font, line, Color.WHITE, lineTextW, Align.left, true);
                lineHeights[i] = Math.max(32f, glyphs.height + rowPadY * 2f);
                linesH += lineHeights[i];
                if (i + 1 < lines.size()) linesH += rowGap;
            }
        }
        float linesLead = hasLines ? 10f : 0f;
        float extra = linesLead + linesH;
        float boxBottom = (hasAsk || hasLines)
                ? headerBottom - (hasAsk ? gap + bodyH : 0f) - extra - pad
                : headerBottom;
        if ((hasAsk || hasLines) && boxBottom < 8f) {
            float ceiling = h - 8f;
            float shift = 8f - boxBottom;
            if (boxTop + shift > ceiling) {
                shift = Math.max(0f, ceiling - boxTop);
                float room = (headerBottom + shift - gap) - (8f + pad);
                if (bodyH > room) {
                    body = fitToHeight(bodyFont, body, textW, Math.max(16f, room));
                    glyphs.setText(bodyFont, body, Color.WHITE, textW, Align.left, true);
                    bodyH = glyphs.height;
                    boxBottom = headerBottom - (hasAsk ? gap + bodyH : 0f) - extra - pad;
                    shift = boxBottom < 8f ? 8f - boxBottom : 0f;
                    if (boxTop + shift > ceiling) shift = Math.max(0f, ceiling - boxTop);
                }
            }
            baseline += shift;
            boxTop += shift;
            headerBottom += shift;
            boxBottom += shift;
        }
        float boxH = boxTop - boxBottom;
        batch.setColor(0.07f, 0.08f, 0.09f, 0.92f);
        batch.draw(pixel(), left, boxBottom, boxW, boxH);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.9f);
        float edge = 2f;
        batch.draw(pixel(), left, boxBottom, boxW, edge);
        batch.draw(pixel(), left, boxTop - edge, boxW, edge);
        batch.draw(pixel(), left, boxBottom, edge, boxH);
        batch.draw(pixel(), left + boxW - edge, boxBottom, edge, boxH);
        batch.setColor(Color.WHITE);
        font.setColor(GOLD);
        font.draw(batch, who, left + pad, baseline);
        if (hasAsk) {
            batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.55f);
            batch.draw(pixel(), left + pad, headerBottom - 1f, textW, 1f);
            batch.setColor(Color.WHITE);
            bodyFont.setColor(GOLD);
            bodyFont.draw(batch, body, left + pad, headerBottom - gap, textW, Align.left, true);
            bodyFont.setColor(Color.WHITE);
        }
        if (hasLines) {
            List<Integer> picked = liveReady() ? live.modePicked() : List.of();
            float y = headerBottom - (hasAsk ? gap + bodyH : 0f) - linesLead;
            float rowX = left + rowInset;
            float rowW = boxW - rowInset * 2f;
            for (int i = 0; i < lines.size(); i++) {
                float rowH = lineHeights[i];
                y -= rowH;
                float[] row = new float[] { rowX, y, rowW, rowH };
                modeHits.add(row);
                String line = lines.get(i) == null ? "" : lines.get(i);
                boolean chosen = picked.contains(i);
                boolean hot = pointerIn(row, mx, my);
                drawTextChoiceRow(rowX, y, rowW, rowH, chosen, hot);
                font.setColor(chosen ? GOLD : Color.WHITE);
                font.draw(batch, line, rowX + 8f, y + rowH - rowPadY, lineTextW, Align.left, true);
                if (i + 1 < lines.size()) y -= rowGap;
            }
        }
        font.setColor(Color.WHITE);
    }

    /** The order step's sentence is the thing he has to notice. */
    private static boolean orderJob(String ask) {
        if (ask == null) return false;
        return ask.startsWith("These go on the bottom of your library")
                || ask.startsWith("Order entering the battlefield");
    }

    private int modeLineAt(float x, float y) {
        return rowAt(modeHits, x, y);
    }

    private int textChoiceAt(float x, float y) {
        return rowAt(textChoiceHits, x, y);
    }

    private boolean textChoiceOkHit(float x, float y) {
        return textChoiceOk != null && pointerIn(textChoiceOk, x, y);
    }

    private boolean textChoiceSearchHit(float x, float y) {
        return textChoiceSearch != null && pointerIn(textChoiceSearch, x, y);
    }

    private boolean textChoiceAllHit(float x, float y) {
        return textChoiceAll != null && pointerIn(textChoiceAll, x, y);
    }

    private int textChoiceMapped(int visible) {
        if (visible < 0) return visible;
        if (visible < textChoiceIndex.size()) return textChoiceIndex.get(visible);
        return visible;
    }

    private static int rowAt(List<float[]> rows, float x, float y) {
        for (int i = 0; i < rows.size(); i++) {
            if (pointerIn(rows.get(i), x, y)) return i;
        }
        return -1;
    }

    private static boolean pointerIn(float[] box, float x, float y) {
        return box != null && box.length >= 4
                && x >= box[0] && x <= box[0] + box[2] && y >= box[1] && y <= box[1] + box[3];
    }

    /** One text choice. Fill is lighter than the panel. A selected row takes the gold edge. */
    private void drawTextChoiceRow(float x, float y, float rw, float rh, boolean selected, boolean hot) {
        float r = hot ? 0.30f : 0.16f;
        float g = hot ? 0.28f : 0.17f;
        float b = hot ? 0.22f : 0.18f;
        if (selected) {
            r = hot ? 0.36f : 0.24f;
            g = hot ? 0.30f : 0.20f;
            b = hot ? 0.16f : 0.10f;
        }
        batch.setColor(r, g, b, 0.98f);
        batch.draw(pixel(), x, y, rw, rh);
        float edge = selected ? 3f : 1.5f;
        if (selected) batch.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
        else if (hot) batch.setColor(0.95f, 0.90f, 0.72f, 0.95f);
        else batch.setColor(0.55f, 0.50f, 0.38f, 0.95f);
        batch.draw(pixel(), x, y, rw, edge);
        batch.draw(pixel(), x, y + rh - edge, rw, edge);
        batch.draw(pixel(), x, y, edge, rh);
        batch.draw(pixel(), x + rw - edge, y, edge, rh);
        batch.setColor(Color.WHITE);
    }

    private int sheetCount() {
        if (!liveReady()) return 0;
        BattlefieldLive.Pic pic = live.picture();
        return pic == null ? 0 : pic.sheet.size();
    }

    private boolean sheetHandBand() {
        return liveReady() && live.pickSheetOpen();
    }

    private int sheetHit(float w, float h) {
        if (liveReady()) {
            BattlefieldLive.Pic pic = live.picture();
            if (pic != null && pic.mayCast) {
                return BattlefieldLayout.cascadeIndex(w, h, sheetCount(), pic.hitIndex, mx, my,
                        sheetScroll, sheetHandBand());
            }
        }
        return BattlefieldLayout.revealedIndex(w, h, sheetCount(), mx, my, sheetScroll, sheetHandBand());
    }

    private void beginSheetSweep(float w, float h, int hit) {
        pressSheetHit = hit;
        sheetPressX = mx;
        sheetPressScroll = sheetScroll;
        draggingSheet = false;
        pressX = mx;
        pressY = my;
        pressTime = 0;
        dragDist = 0;
    }

    private void syncSheetScroll(float w, float h) {
        String sig = sheetSignature();
        if (!sig.equals(sheetSig)) {
            sheetSig = sig;
            sheetScroll = 0f;
        }
        float max = BattlefieldLayout.revealedScrollMax(w, h, sheetCount(), sheetHandBand());
        if (sheetScroll < 0f) sheetScroll = 0f;
        if (sheetScroll > max) sheetScroll = max;
    }

    private String sheetSignature() {
        if (!liveReady()) return "";
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null || pic.sheet.isEmpty()) return "";
        StringBuilder text = new StringBuilder();
        text.append(pic.sheet.size()).append('|');
        for (BattlefieldLive.ShownCard tile : pic.sheet) {
            text.append(tile == null || tile.name == null ? "" : tile.name).append(',');
            if (tile != null && tile.legal) text.append('L');
        }
        return text.toString();
    }

    /**
     * Width of the left ask window. Stays left of the resting fan and of the card that slides up.
     */
    private float askPanelWidth(float w, float h) {
        float left = 16f;
        float gap = 20f;
        int n = live().size();
        if (n <= 0) n = HandLayout.CARD_COUNT;
        float clear = w;
        for (int i = 0; i < n; i++) {
            HandLayout.Pose rest = HandLayout.restPose(w, h, n, i);
            double rad = Math.toRadians(rest.rotationDeg);
            float cos = (float) Math.abs(Math.cos(rad));
            float sin = (float) Math.abs(Math.sin(rad));
            clear = Math.min(clear, rest.cx - rest.w * 0.5f * cos - rest.h * 0.5f * sin);
            HandLayout.Pose preview = HandLayout.hoverPose(w, h, n, i, i);
            clear = Math.min(clear, preview.cx - preview.w * 0.5f);
        }
        float room = clear - gap - left;
        float preferred = 400f;
        if (room >= 80f) return Math.min(preferred, room);
        return Math.max(80f, room);
    }

    /**
     * Collapsed tabs along the right edge, above the life widgets.
     * Hover reads a log. Click pins it so the wheel can scroll. Click again, or elsewhere, collapses it.
     */
    private void drawSideLog(float w, float h) {
        for (int i = 0; i < SIDE_LOG_LABELS.length; i++) {
            HandLayout.Pose tab = sideLogTab(w, h, i);
            SideLog which = SideLog.values()[i];
            boolean hot = which == logHoverTab || which == logPinned;
            batch.setColor(hot ? 0.16f : 0.08f, hot ? 0.14f : 0.09f, hot ? 0.08f : 0.1f, 0.94f);
            batch.draw(pixel(), tab.cx - tab.w / 2f, tab.cy - tab.h / 2f, tab.w, tab.h);
            batch.setColor(hot ? GOLD.r : 0.45f, hot ? GOLD.g : 0.42f, hot ? GOLD.b : 0.36f, hot ? 1f : 0.8f);
            float left = tab.cx - tab.w / 2f;
            float bottom = tab.cy - tab.h / 2f;
            batch.draw(pixel(), left, bottom, tab.w, 2f);
            batch.draw(pixel(), left, bottom + tab.h - 2f, tab.w, 2f);
            batch.draw(pixel(), left, bottom, 2f, tab.h);
            batch.draw(pixel(), left + tab.w - 2f, bottom, 2f, tab.h);
            batch.setColor(Color.WHITE);
            drawCenteredOn(stripFont, SIDE_LOG_LABELS[i], tab.cx, tab.cy, hot ? GOLD : Color.WHITE);
        }
        SideLog shown = sideLogShown();
        if (shown == null) return;
        HandLayout.Pose panel = sideLogPanel(w, h);
        float left = panel.cx - panel.w / 2f;
        float bottom = panel.cy - panel.h / 2f;
        batch.setColor(0.06f, 0.07f, 0.08f, 0.94f);
        batch.draw(pixel(), left, bottom, panel.w, panel.h);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.9f);
        batch.draw(pixel(), left, bottom, panel.w, 2f);
        batch.draw(pixel(), left, bottom + panel.h - 2f, panel.w, 2f);
        batch.draw(pixel(), left, bottom, 2f, panel.h);
        batch.draw(pixel(), left + panel.w - 2f, bottom, 2f, panel.h);
        batch.setColor(Color.WHITE);
        List<String> lines = sideLogLines(shown);
        float textW = Math.max(40f, panel.w - 16f);
        float textLeft = left + 8f;
        float viewBottom = bottom + 8f;
        float viewTop = bottom + panel.h - 8f;
        float viewH = Math.max(1f, viewTop - viewBottom);
        float contentH = sideLogContentHeight(lines, textW);
        float maxScroll = Math.max(0f, contentH - viewH);
        if (logScroll > maxScroll) logScroll = maxScroll;
        if (logScroll < 0f) logScroll = 0f;
        float y = viewBottom + contentH - logScroll;
        batch.flush();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor((int) left, (int) bottom, Math.max(1, (int) panel.w), Math.max(1, (int) panel.h));
        if (lines.isEmpty()) {
            stripFont.setColor(new Color(0.75f, 0.72f, 0.64f, 1f));
            stripFont.draw(batch, sideLogEmpty(shown), textLeft, viewTop, textW, Align.left, true);
        } else {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                boolean header = shown == SideLog.GAME && isTurnHeader(line);
                if (header && i > 0) y -= 8f;
                glyphs.setText(stripFont, line, Color.WHITE, textW, Align.left, true);
                float lineH = Math.max(stripFont.getLineHeight(), glyphs.height);
                stripFont.setColor(header ? GOLD : new Color(0.86f, 0.84f, 0.78f, 1f));
                stripFont.draw(batch, line, textLeft, y, textW, Align.left, true);
                y -= lineH;
            }
        }
        stripFont.setColor(Color.WHITE);
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private float sideLogContentHeight(List<String> lines, float textW) {
        float contentH = 0f;
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0 && isTurnHeader(lines.get(i))) contentH += 8f;
            glyphs.setText(stripFont, lines.get(i), Color.WHITE, textW, Align.left, true);
            contentH += Math.max(stripFont.getLineHeight(), glyphs.height);
        }
        return contentH;
    }

    private float clampLogScroll(float w, float h, float scroll) {
        SideLog shown = sideLogShown();
        if (shown == null) return 0f;
        HandLayout.Pose panel = sideLogPanel(w, h);
        float textW = Math.max(40f, panel.w - 16f);
        float viewH = Math.max(1f, panel.h - 16f);
        float contentH = sideLogContentHeight(sideLogLines(shown), textW);
        float maxScroll = Math.max(0f, contentH - viewH);
        return Math.max(0f, Math.min(maxScroll, scroll));
    }

    private List<String> sideLogLines(SideLog tab) {
        List<String> out = new ArrayList<>();
        if (tab == SideLog.GAME) {
            for (String raw : gameLog) out.add(displayGameLine(raw));
        } else if (tab == SideLog.IMPORTANT) {
            for (String raw : gameLog) {
                if (importantLogLine(raw)) out.add(raw);
            }
        } else if (tab == SideLog.AI) {
            out.addAll(devLog);
        } else if (debugLog.isEmpty()) {
            return out;
        } else {
            out.addAll(debugLog);
        }
        return out;
    }

    private static String sideLogEmpty(SideLog tab) {
        return switch (tab) {
            case GAME -> "No log lines yet.";
            case IMPORTANT -> "No deaths or discards in the log.";
            case AI -> "No decision notes yet.";
            case DEBUG -> "No decision lines for the latest turn.";
        };
    }

    /** "Turn: Turn 4 (Ash)" becomes "Turn 4, Ash". Other lines stay as Forge wrote them. */
    private static String displayGameLine(String raw) {
        String header = turnHeader(raw);
        if (header != null) return header;
        return raw == null ? "" : raw;
    }

    private static boolean isTurnHeader(String line) {
        if (line == null || line.length() < 6) return false;
        if (!line.startsWith("Turn ")) return false;
        int i = 5;
        if (i >= line.length() || !Character.isDigit(line.charAt(i))) return false;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        return i < line.length() && line.charAt(i) == ',';
    }

    private static String turnHeader(String raw) {
        if (raw == null) return null;
        String line = raw.trim();
        String lower = line.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("turn:")) return null;
        int turnWord = lower.indexOf("turn", 4);
        if (turnWord < 0) return null;
        int i = turnWord + 4;
        while (i < line.length() && !Character.isDigit(line.charAt(i))) i++;
        int start = i;
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        if (start == i) return null;
        String number = line.substring(start, i);
        int open = line.indexOf('(', i);
        int close = open < 0 ? -1 : line.indexOf(')', open + 1);
        if (open < 0 || close <= open + 1) return "Turn " + number;
        String who = line.substring(open + 1, close).trim();
        if (who.isEmpty()) return "Turn " + number;
        return "Turn " + number + ", " + who;
    }

    /** Deaths, discards, and battlefield removals that are already in the log. */
    private static boolean importantLogLine(String raw) {
        if (raw == null || turnHeader(raw) != null) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.startsWith("discard:") || lower.contains(" discards ") || lower.contains(" discarded")) return true;
        if (lower.contains("was put into graveyard from battlefield")) return true;
        if (lower.contains("was put into exile from battlefield")) return true;
        if (lower.contains(" dies") || lower.contains(" died") || lower.contains(" destroyed")) return true;
        return false;
    }

    private SideLog sideLogShown() {
        if (logHoverTab != null) return logHoverTab;
        return logPinned;
    }

    private HandLayout.Pose sideLogPanel(float w, float h) {
        BattlefieldLayout.PlayerCluster them = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.ENEMY, enemyDx, enemyDy);
        float tabW = 84f;
        float tabLeft = w - 10f - tabW;
        float creatureRight = w * (0.5f + BattlefieldLayout.ROW_WIDTH_FRAC * 0.5f) + 16f;
        float panelRight = tabLeft - 6f;
        float panelLeft = Math.max(creatureRight, panelRight - 380f);
        float panelTop = h - 8f;
        float panelBottom = them.top + 10f;
        if (panelTop - panelBottom < 96f) panelBottom = Math.max(8f, panelTop - 96f);
        float pw = Math.max(120f, panelRight - panelLeft);
        float ph = Math.max(96f, panelTop - panelBottom);
        return new HandLayout.Pose(panelLeft + pw / 2f, panelBottom + ph / 2f, pw, ph, 0f, 1f);
    }

    private HandLayout.Pose sideLogTab(float w, float h, int index) {
        float tabW = 84f;
        float tabH = 22f;
        float gap = 4f;
        float cy = h - 8f - tabH / 2f - index * (tabH + gap);
        return new HandLayout.Pose(w - 10f - tabW / 2f, cy, tabW, tabH, 0f, 1f);
    }

    private SideLog sideLogTabAt(float w, float h) {
        for (int i = 0; i < SIDE_LOG_LABELS.length; i++) {
            if (poseContains(sideLogTab(w, h, i), mx, my)) return SideLog.values()[i];
        }
        return null;
    }

    private boolean sideLogPanelContains(float w, float h) {
        return sideLogShown() != null && poseContains(sideLogPanel(w, h), mx, my);
    }

    /** Tabs always. The open panel only while it is showing, so a collapsed log leaves the board free. */
    private boolean sideLogBlocksBoard(float w, float h) {
        if (draggingHand || draggingBoard || pressedPerm != null || pressHand >= 0) return false;
        if (sideLogTabAt(w, h) != null) return true;
        return sideLogPanelContains(w, h);
    }

    private boolean pressSideLog(float w, float h) {
        SideLog tab = sideLogTabAt(w, h);
        if (tab != null) {
            if (logPinned == tab) logPinned = null;
            else {
                logScroll = 0f;
                logPinned = tab;
            }
            return true;
        }
        if (sideLogShown() != null && sideLogPanelContains(w, h)) {
            if (logPinned == null) {
                logPinned = sideLogShown();
                logScroll = 0f;
            }
            return true;
        }
        if (logPinned != null) logPinned = null;
        return false;
    }

    private static boolean poseContains(HandLayout.Pose pose, float x, float y) {
        return Math.abs(x - pose.cx) <= pose.w / 2f && Math.abs(y - pose.cy) <= pose.h / 2f;
    }

    private String fitLogLine(String line, float width) {
        if (line == null) return "";
        glyphs.setText(stripFont, line);
        if (glyphs.width <= width) return line;
        int end = line.length();
        while (end > 4) {
            int space = line.lastIndexOf(' ', end - 1);
            if (space < 4) break;
            end = space;
            String word = line.substring(0, end).trim();
            glyphs.setText(stripFont, word);
            if (glyphs.width <= width) return word;
        }
        String cut = line;
        while (cut.length() > 4) {
            int next = cut.offsetByCodePoints(cut.length(), -1);
            cut = cut.substring(0, next);
            glyphs.setText(stripFont, cut);
            if (glyphs.width <= width) return cut;
        }
        return cut;
    }

    /** Wrap {@code text} into {@code width}, shortening it until the block is no taller than {@code maxH}. */
    private String fitToHeight(BitmapFont face, String text, float width, float maxH) {
        glyphs.setText(face, text, Color.WHITE, width, Align.left, true);
        if (glyphs.height <= maxH) return text;
        int lo = 0;
        int hi = text.length();
        String best = "…";
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            String cut = text.substring(0, mid).trim();
            if (cut.isEmpty()) cut = "…";
            else cut = cut + "…";
            glyphs.setText(face, cut, Color.WHITE, width, Align.left, true);
            if (glyphs.height <= maxH) {
                best = cut;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    /**
     * London tuck copy for the left window. Forge reports how many are still unchosen;
     * the line keeps the full count (1st mulligan is 1, 2nd is 2).
     */
    private String bottomAsk(String ask) {
        if (!bottomingHand()) return ask;
        int remaining = firstNumber(ask);
        int picked = 0;
        for (HandCard card : hand) {
            if (card.alive && !card.parked && card.bottomPick) picked++;
        }
        int need = remaining < 0 ? picked : remaining + picked;
        if (need < 1) need = Math.max(1, picked);
        return "Put " + need + " on the bottom";
    }

    private static int firstNumber(String text) {
        if (text == null) return -1;
        int value = 0;
        boolean digit = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
                value = value * 10 + (c - '0');
            } else if (digit) {
                return value;
            }
        }
        return digit ? value : -1;
    }

    /**
     * Who is searching, looking, or choosing. Never whose turn, and never the
     * "waiting for" parse — that names the other seat while this one holds the input.
     */
    private String seatWaitLine() {
        if (!liveReady()) return null;
        if (live.thisSeatSearching()) return namedAction(youName, "You", "is searching");
        if (live.otherSeatSearching()) return namedAction(themName, "They", "is searching");
        if (thisSeatLooking()) return namedAction(youName, "You", "is looking at");
        if (otherSeatLooking()) return namedAction(themName, "They", "is looking at");
        if (thisSeatChoosing()) return namedAction(youName, "You", "is choosing");
        String waiting = waitingForOther();
        if (waiting != null) return spaced("waiting for", waiting);
        return null;
    }

    private boolean thisSeatLooking() {
        if (lookTop) return true;
        if (!liveReady()) return false;
        if (live.placingSheet()) return true;
        if (!live.pickSheetOpen() && !live.cardSheetOpen()) return false;
        if (live.thisSeatSearching()) return false;
        String title = live.sheetTitle();
        return BattlefieldLive.scryLikeAsk(title);
    }

    private boolean otherSeatLooking() {
        if (thisSeatLooking() || (liveReady() && live.thisSeatSearching())) return false;
        if (liveReady() && (live.choiceOpen() || live.pickSheetOpen())) return false;
        String prompt = note == null ? "" : note;
        String lower = prompt.toLowerCase(Locale.ROOT);
        return lower.contains("looking at") || lower.contains("look at");
    }

    private boolean thisSeatChoosing() {
        if (!liveReady()) return false;
        if (live.thisSeatSearching() || thisSeatLooking()) return false;
        return live.choiceOpen() || live.modeSheetOpen() || live.cardSheetOpen();
    }

    /**
     * The other seat still has to act. Do not treat this as a searcher name.
     */
    private String waitingForOther() {
        if (liveReady() && (live.choiceOpen() || live.pickSheetOpen() || live.cardSheetOpen()
                || live.thisSeatSearching())) {
            return null;
        }
        String prompt = note == null ? "" : note;
        if (!prompt.toLowerCase(Locale.ROOT).contains("waiting for")) return null;
        return displayName(themName, "them");
    }

    private static String namedAction(String name, String fallback, String verb) {
        return spaced(displayName(name, fallback), verb);
    }

    private static String displayName(String name, String fallback) {
        return name == null || name.isBlank() ? fallback : name;
    }

    /** Name and verb stay two words. The wait-box font used to drop the space. */
    private static String spaced(String left, String right) {
        if (left == null || left.isBlank()) return right == null ? "" : right;
        if (right == null || right.isBlank()) return left;
        return left + " " + right;
    }

    /** A real ask only. Idle priority, the phase name, and "waiting" stay off the board. */
    private static String liveExtra(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        String text = withoutPriorityStatus(prompt);
        if (text.isEmpty()) return null;
        String lower = text.toLowerCase(Locale.ROOT);
        boolean asking = lower.contains("choose") || lower.contains("select") || lower.contains("target")
                || lower.contains("mulligan") || lower.contains("discard") || lower.contains("pay ")
                || lower.contains("pay mana") || lower.contains("return")
                || lower.contains("bottom of your library")
                || lower.contains("sacrifice") || lower.contains("order") || lower.contains("assign")
                || hasWord(lower, "keep");
        if (!asking) return null;
        return text;
    }

    /** Forge's idle priority dump. Those lines are the phase strip and the stack, not a question. */
    private static String withoutPriorityStatus(String prompt) {
        StringBuilder kept = new StringBuilder();
        for (String raw : prompt.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.startsWith("priority:") || lower.startsWith("turn:") || lower.startsWith("phase:")
                    || lower.startsWith("stack:") || lower.startsWith("storm count:")
                    || lower.startsWith("macro recording") || lower.startsWith("macro playback")
                    || lower.contains("priority:") || lower.contains("phase:") || lower.contains("waiting for")
                    || isPhaseName(lower)) {
                continue;
            }
            if (kept.length() > 0) kept.append('\n');
            kept.append(line);
        }
        return kept.toString().trim();
    }

    /** A step name with no question attached. "Upkeep" is not the word "keep". */
    private static boolean isPhaseName(String lower) {
        if (lower.contains("choose") || lower.contains("select") || lower.contains("target")
                || lower.contains("mulligan") || lower.contains("discard") || lower.contains("sacrifice")) {
            return false;
        }
        return lower.equals("upkeep") || lower.equals("upkeep step") || lower.equals("your upkeep.")
                || lower.startsWith("upkeep") || lower.startsWith("draw step") || lower.startsWith("main phase")
                || lower.startsWith("beginning of combat") || lower.startsWith("declare attackers")
                || lower.startsWith("declare blockers") || lower.startsWith("combat")
                || lower.startsWith("first strike") || lower.startsWith("end of combat")
                || lower.startsWith("end step") || lower.startsWith("cleanup")
                || lower.startsWith("your upkeep") || lower.startsWith("their turn");
    }

    private static boolean hasWord(String lower, String word) {
        int from = 0;
        while (from < lower.length()) {
            int at = lower.indexOf(word, from);
            if (at < 0) return false;
            boolean left = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
            int end = at + word.length();
            boolean right = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (left && right) return true;
            from = at + word.length();
        }
        return false;
    }

    /**
     * The unpaid cost on a pay-mana line, such as {@code Pay Mana Cost: {1}{G}}.
     * Null when the prompt is some other question. The priority dump stays off this plate.
     */
    private static String castingCostOnly(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        for (String raw : prompt.split("\\R")) {
            String line = raw.trim();
            String lower = line.toLowerCase(Locale.ROOT);
            int label = lower.indexOf("pay mana cost");
            if (label < 0) continue;
            String after = line.substring(label + "pay mana cost".length()).trim();
            if (after.startsWith(":")) after = after.substring(1).trim();
            int paren = after.indexOf('(');
            if (paren >= 0) after = after.substring(0, paren).trim();
            return after.isEmpty() ? "0" : after;
        }
        return null;
    }

    /**
     * Unpaid cost only, in the middle of the table. No bar behind it.
     * Nudged off the stack so the pips do not sit on the card name.
     */
    private void queueCenteredCost(String cost, float w, float h) {
        List<ManaPip> pips = parseManaPips(cost);
        if (pips.isEmpty()) return;
        float radius = 20f;
        float gap = 8f;
        float row = pips.size() * radius * 2f + (pips.size() - 1) * gap;
        float[] at = clearOfStackNames(w / 2f, h / 2f, row, radius * 2f, w, h);
        float x = at[0] - row / 2f + radius;
        for (ManaPip pip : pips) {
            pipQueue.add(new ManaPipDraw(x, at[1], radius, pip));
            x += radius * 2f + gap;
        }
    }

    /** Shift a centered block until it misses the name band of every stack row. */
    private float[] clearOfStackNames(float cx, float cy, float rowW, float rowH, float w, float h) {
        if (!coversStackName(cx, cy, rowW, rowH)) return new float[] {cx, cy};
        for (float shift = 40f; shift < h * 0.42f; shift += 32f) {
            if (cy + shift < h - rowH && !coversStackName(cx, cy + shift, rowW, rowH)) {
                return new float[] {cx, cy + shift};
            }
            if (cy - shift > rowH && !coversStackName(cx, cy - shift, rowW, rowH)) {
                return new float[] {cx, cy - shift};
            }
        }
        float leftLimit = w;
        for (StackItem item : stack) leftLimit = Math.min(leftLimit, item.rowX - item.rowW / 2f);
        float nx = leftLimit - 16f - rowW / 2f;
        nx = Math.max(rowW / 2f + 8f, Math.min(nx, w - rowW / 2f - 8f));
        return new float[] {nx, cy};
    }

    private boolean coversStackName(float cx, float cy, float rowW, float rowH) {
        float left = cx - rowW / 2f;
        float right = cx + rowW / 2f;
        float bottom = cy - rowH / 2f;
        float top = cy + rowH / 2f;
        for (StackItem item : stack) {
            float nameLeft = item.thumbX + item.thumbW / 2f + 12f;
            float nameRight = item.rowX + item.rowW / 2f - 8f;
            float nameTop = item.rowY + item.rowH / 2f - 8f;
            float nameBottom = nameTop - 32f;
            if (right > nameLeft && left < nameRight && top > nameBottom && bottom < nameTop) return true;
        }
        return false;
    }

    /** True when the click landed on a minus or plus. The action-row Confirm is not one of those. */
    private boolean pressAmount() {
        for (UiButton button : amountControls) {
            if (!button.contains(mx, my)) continue;
            int split = button.id.indexOf(':');
            if (split <= 0) return true;
            int index = Integer.parseInt(button.id.substring(0, split));
            int dir = "plus".equals(button.id.substring(split + 1)) ? 1 : -1;
            live.nudgeAmount(index, dir);
            return true;
        }
        return false;
    }

    private void pressXControl() {
        for (UiButton button : xControls) {
            if (!button.contains(mx, my)) continue;
            switch (button.id) {
                case "x-max" -> live.xModeMax();
                case "x-counter" -> live.xModeCounter();
                case "x-minus" -> live.xNudge(-1);
                case "x-plus" -> live.xNudge(1);
                case "x-ok" -> live.xSubmit();
                default -> { }
            }
            return;
        }
    }

    /**
     * Small X controls in the middle. No bar, and only while Forge is waiting for the announced X.
     * OK sends that number. Esc cancels the cast.
     */
    private void drawXChoice(float w, float h) {
        xControls.clear();
        int mode = live.xMode();
        int hold = live.xHold();
        int amount = live.xAmount();
        String value = "X = " + amount;
        glyphs.setText(font, value);
        float valueW = glyphs.width;
        float[] widths = {78f, 158f, 36f, 48f, 36f, 64f};
        String[] ids = {"x-max", "x-counter", "x-minus", "x-n", "x-plus", "x-ok"};
        String[] labels = {"Max", "Max-counter", "−", Integer.toString(hold), "+", "Confirm"};
        float gap = 8f;
        float btnH = 34f;
        float rowW = 0f;
        for (float width : widths) rowW += width;
        rowW += gap * (widths.length - 1);
        float blockH = 28f + 10f + btnH;
        float[] at = clearOfStackNames(w / 2f, h / 2f, Math.max(rowW, valueW + 8f), blockH, w, h);
        float textY = at[1] + btnH / 2f + 8f;
        float left = at[0] - rowW / 2f;
        float btnY = at[1] - 14f;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        float x = left;
        for (int i = 0; i < ids.length; i++) {
            float bw = widths[i];
            boolean on = switch (ids[i]) {
                case "x-max" -> mode == 0;
                case "x-counter" -> mode == 2;
                case "x-n" -> mode == 1;
                default -> false;
            };
            float bx = x + bw / 2f;
            shapes.setColor(on ? 0.20f : 0.10f, on ? 0.16f : 0.11f, on ? 0.08f : 0.08f, 0.94f);
            shapes.rect(x, btnY - btnH / 2f, bw, btnH);
            shapes.setColor(on ? GOLD.r : 0.55f, on ? GOLD.g : 0.42f, on ? GOLD.b : 0.22f, on ? 1f : 0.8f);
            shapes.rect(x, btnY - btnH / 2f, bw, 2f);
            shapes.rect(x, btnY + btnH / 2f - 2f, bw, 2f);
            shapes.rect(x, btnY - btnH / 2f, 2f, btnH);
            shapes.rect(x + bw - 2f, btnY - btnH / 2f, 2f, btnH);
            if (!"x-n".equals(ids[i])) xControls.add(new UiButton(ids[i], labels[i], bx, btnY, bw, btnH));
            x += bw + gap;
        }
        shapes.end();
        batch.begin();
        drawCenteredOn(font, value, at[0], textY, Color.WHITE);
        x = left;
        for (int i = 0; i < ids.length; i++) {
            float bw = widths[i];
            boolean on = switch (ids[i]) {
                case "x-max" -> mode == 0;
                case "x-counter" -> mode == 2;
                case "x-n" -> mode == 1;
                default -> false;
            };
            drawCenteredOn(stripFont, labels[i], x + bw / 2f, btnY, on ? GOLD : Color.WHITE);
            x += bw + gap;
        }
        stripFont.setColor(Color.WHITE);
        font.setColor(Color.WHITE);
        batch.end();
    }

    /** One cost line. {@code x} is the left edge of the row and {@code cy} is the circle center. */
    private void queueCostLine(List<ManaPip> pips, float x, float cy, float radius) {
        float cursor = x + radius;
        float gap = 6f;
        for (ManaPip pip : pips) {
            pipQueue.add(new ManaPipDraw(cursor, cy, radius, pip));
            cursor += radius * 2f + gap;
        }
    }

    private static List<ManaPip> parseManaPips(String cost) {
        List<ManaPip> pips = new ArrayList<>();
        if (cost == null || cost.isBlank()) return pips;
        String text = cost.trim();
        if (text.toLowerCase(Locale.ROOT).startsWith("no cost")) return pips;
        if (text.indexOf('{') >= 0) {
            int i = 0;
            while (i < text.length()) {
                int open = text.indexOf('{', i);
                if (open < 0) break;
                int close = text.indexOf('}', open + 1);
                if (close < 0) break;
                addManaPip(pips, text.substring(open + 1, close));
                i = close + 1;
            }
            return pips;
        }
        for (String token : text.split("\\s+")) addManaPip(pips, token);
        return pips;
    }

    private static void addManaPip(List<ManaPip> pips, String raw) {
        if (raw == null) return;
        String token = raw.trim().toUpperCase(Locale.ROOT);
        if (token.isEmpty()) return;
        boolean phyrexian = token.endsWith("/P") || token.equals("P");
        String[] halves = token.split("/");
        String leftTok = halves[0];
        String rightTok = halves.length > 1 ? halves[1] : null;
        if (rightTok != null && (rightTok.equals("P") || rightTok.isEmpty())) rightTok = null;
        Color left = pipFill(leftTok);
        Color right = rightTok == null ? null : pipFill(rightTok);
        Color ink = pipNeedsDarkInk(left) && (right == null || pipNeedsDarkInk(right)) ? PIP_INK : Color.WHITE;
        pips.add(new ManaPip(pipLabel(leftTok), left, right, ink, phyrexian));
    }

    private static Color pipFill(String token) {
        if (token.length() == 1) {
            return switch (token.charAt(0)) {
                case 'W' -> PIP_W;
                case 'U' -> PIP_U;
                case 'B' -> PIP_B;
                case 'R' -> PIP_R;
                case 'G' -> PIP_G;
                default -> PIP_C;
            };
        }
        return PIP_C;
    }

    private static String pipLabel(String token) {
        if (token.length() == 1 && "WUBRGCX".indexOf(token.charAt(0)) >= 0) return token;
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c >= '0' && c <= '9') digits.append(c);
        }
        if (digits.length() > 0) return digits.length() > 2 ? digits.substring(0, 2) : digits.toString();
        return token.isEmpty() ? "" : token.substring(0, 1);
    }

    private static boolean pipNeedsDarkInk(Color fill) {
        float lum = 0.3f * fill.r + 0.59f * fill.g + 0.11f * fill.b;
        return lum > 0.62f;
    }

    private void drawQueuedPipShapes() {
        for (float[] plate : pipPlates) {
            float left = plate[0] - plate[2] / 2f;
            float bottom = plate[1] - plate[3] / 2f;
            shapes.setColor(0.07f, 0.08f, 0.09f, 0.94f);
            shapes.rect(left, bottom, plate[2], plate[3]);
            shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 0.9f);
            shapes.rect(left, bottom, plate[2], 2f);
            shapes.rect(left, bottom + plate[3] - 2f, plate[2], 2f);
            shapes.rect(left, bottom, 2f, plate[3]);
            shapes.rect(left + plate[2] - 2f, bottom, 2f, plate[3]);
        }
        for (ManaPipDraw pip : pipQueue) drawManaToken(pip);
    }

    /**
     * A beveled token: dark bezel, bronze ring with a lit top and shaded bottom, colored face, letter later.
     * The ring is the same for the stack line and the pay-cost row. Not a flat disc, and not mana-symbol art.
     */
    private void drawManaToken(ManaPipDraw pip) {
        float x = pip.x;
        float y = pip.y;
        float r = pip.r;
        int seg = 28;
        shapes.setColor(0f, 0f, 0f, 0.45f);
        shapes.circle(x + r * 0.05f, y - r * 0.12f, r * 0.98f, seg);
        shapes.setColor(0.12f, 0.09f, 0.05f, 1f);
        shapes.circle(x, y, r, seg);
        shapes.setColor(0.55f, 0.42f, 0.18f, 1f);
        shapes.circle(x, y, r * 0.88f, seg);
        shapes.setColor(0.28f, 0.18f, 0.07f, 1f);
        shapes.arc(x, y, r * 0.88f, 206f, 128f, 18);
        shapes.setColor(0.96f, 0.88f, 0.58f, 1f);
        shapes.arc(x, y, r * 0.88f, 24f, 132f, 18);
        shapes.setColor(0.06f, 0.05f, 0.04f, 1f);
        shapes.circle(x, y, r * 0.72f, seg);
        float face = r * 0.66f;
        if (pip.split == null) {
            shapes.setColor(pip.fill.r + (1f - pip.fill.r) * 0.45f, pip.fill.g + (1f - pip.fill.g) * 0.45f,
                    pip.fill.b + (1f - pip.fill.b) * 0.45f, 1f);
            shapes.circle(x, y + face * 0.06f, face, seg);
            shapes.setColor(pip.fill);
            shapes.circle(x, y, face * 0.90f, seg);
        } else {
            shapes.setColor(pip.fill);
            shapes.circle(x, y, face, seg);
            shapes.setColor(pip.split);
            int half = 12;
            for (int i = 0; i < half; i++) {
                double a0 = -Math.PI / 2d + Math.PI * i / half;
                double a1 = -Math.PI / 2d + Math.PI * (i + 1) / half;
                shapes.triangle(x, y,
                        x + (float) Math.cos(a0) * face, y + (float) Math.sin(a0) * face,
                        x + (float) Math.cos(a1) * face, y + (float) Math.sin(a1) * face);
            }
        }
        if (pip.phyrexian) {
            shapes.setColor(0.10f, 0.07f, 0.04f, 1f);
            shapes.circle(x + r * 0.58f, y - r * 0.58f, r * 0.18f, 12);
            shapes.setColor(PIP_PHY);
            shapes.circle(x + r * 0.58f, y - r * 0.58f, r * 0.11f, 10);
        }
    }

    private void drawQueuedPipLabels() {
        for (ManaPipDraw pip : pipQueue) {
            if (pip.label.isEmpty()) continue;
            drawCenteredOn(stripFont, pip.label, pip.x, pip.y, pip.ink);
        }
        stripFont.setColor(Color.WHITE);
    }

    private String bannerText() {
        if (examining >= 0 && examining < stack.size()) {
            return "Reading the stack. Click the entry again, or click the board, to go back to targeting.";
        }
        StackItem top = top();
        if (top != null && top.needsChoice) return "Choose a creature type. It stays printed on the card.";
        if (top != null && top.needsTarget) {
            if (top.preview != null && !top.preview.isBlank()) return top.preview;
            if (note != null && !note.isBlank()) return note;
            return BattlefieldLayout.TARGET_PROMPT;
        }
        if (modal == Modal.CHOOSE_TYPE) return "Choose a creature type. It stays printed on the card.";
        if (modal == Modal.ABILITY) return abilityLine + "  Confirm to resolve.";
        if (phase == Phase.BLOCKERS) {
            List<Perm> attackers = enemyAttackers();
            if (attackers.isEmpty()) return "Nothing is attacking.";
            if (yourBlocker() == null) {
                int incoming = 0;
                for (Perm atk : attackers) incoming += power(atk);
                return BattlefieldLayout.previewUnblocked("You", incoming).summary + " Confirm or assign a blocker.";
            }
            StringBuilder line = new StringBuilder();
            for (Perm blk : board) {
                if (!blk.alive || !blk.you || !blk.blocking || blk.blockTarget == null) continue;
                if (line.length() > 0) line.append(' ');
                line.append(blk.name).append(" blocks ").append(blk.blockTarget.name).append('.');
            }
            return line.toString();
        }
        if (phase == Phase.ATTACKERS) return "Drag a creature into their half. The arrow snaps. Then press Attack.";
        return note;
    }

    private boolean markedDead(Perm p) {
        if (phase != Phase.BLOCKERS || modal != Modal.NONE) return false;
        if (p.you && p.blocking && p.blockTarget != null && p.blockTarget.alive && p.blockTarget.attacking) {
            return BattlefieldLayout.previewBlock(
                    power(p.blockTarget), toughness(p.blockTarget), power(p), toughness(p)).blockerDies;
        }
        if (!p.you && p.attacking && !blockersOf(p).isEmpty()) {
            int blkPower = 0;
            for (Perm blk : blockersOf(p)) blkPower += power(blk);
            return blkPower >= toughness(p);
        }
        return false;
    }

    private boolean loggedGlError;
    private String lastRenderError = "";

    @Override
    public void render() {
        try {
            renderFrame();
        } catch (Throwable failed) {
            String msg = failed.toString();
            if (!msg.equals(lastRenderError)) {
                lastRenderError = msg;
                failed.printStackTrace();
                rememberFailure(failed);
            }
        }
        int glError = Gdx.gl.glGetError();
        if (glError != GL20.GL_NO_ERROR && !loggedGlError) {
            loggedGlError = true;
            String line = "GL error " + glError;
            System.err.println(line);
            rememberFailure(new RuntimeException(line));
        }
    }

    private static void rememberFailure(Throwable failed) {
        try {
            StringBuilder text = new StringBuilder();
            text.append('\n').append(failed).append('\n');
            for (StackTraceElement frame : failed.getStackTrace()) {
                text.append("  at ").append(frame).append('\n');
                if (text.length() > 4000) break;
            }
            Files.writeString(Path.of("battlefield-prototype.log"), text.toString(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The window stays up. The next line still goes to the process log.
        }
    }

    private void renderFrame() {
        pipQueue.clear();
        pipPlates.clear();
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.05f);
        if (picking) {
            pollPickerTyping();
            refreshPickerNote();
            if (picking) {
                drawPicker();
                return;
            }
        }
        if (lobbying) {
            refreshLobby();
            if (lobbying) {
                drawLobby();
                return;
            }
        }
        if (liveMode && !liveReady()) {
            drawLoading();
            return;
        }
        if (liveReady()) {
            tickBash(dt);
            applyLiveFrame();
            tickSummons(dt);
            tickCastFlight(dt);
            tickSummonPuffs(dt);
            tendSheetActionLatch();
        }
        if (combatDamageHold > 0f) {
            combatDamageHold -= dt;
            if (combatDamageHold <= 0f) {
                combatDamageHold = 0f;
                if (shownPhase == BattlefieldLayout.StripPhase.COMBAT_DAMAGE) {
                    shownPhase = BattlefieldLayout.currentStripPhase("MAIN_2");
                }
            }
        }
        if (!liveMode) tickEnemyTurn(dt);
        readPointer(Gdx.input.getX(), Gdx.input.getY());
        updatePointerHover();
        if (targeting() && examining < 0) updateShockHover();
        else {
            shockHover = null;
            shockHoverYou = false;
            shockHoverThem = false;
        }
        layout(dt);
        if (stackHoverAllowed() && !optionsBlocks(Gdx.graphics.getWidth(), Gdx.graphics.getHeight())) {
            takeStackHover();
        }
        firePendingImpacts();
        pinBash();
        easeHover(dt);
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(batch.getProjectionMatrix());
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.05f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        List<HandCard> cards = live();
        boolean willCast = draggingHand && !dragInHand && pressHand >= 0 && pressHand < cards.size()
                && cards.get(pressHand).cy > HandLayout.castLineY(h);
        BattlefieldLayout.PlayerCluster youCluster = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.YOU, youDx, youDy);
        BattlefieldLayout.PlayerCluster enemyCluster = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.ENEMY, enemyDx, enemyDy);
        layoutCommandSlots(w, h, youCluster, enemyCluster);

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        float split = BattlefieldLayout.dividerY(h);
        fillHalf(0f, split, BattlefieldLayout.yourBattlefieldTheme());
        fillHalf(split, h - split, BattlefieldLayout.scriptedEnemyTheme());
        shapes.setColor(0.93f, 0.86f, 0.55f, 1f);
        shapes.rect(0f, split - 3f, w, 6f);
        shapes.setColor(willCast ? LINE_HOT : LINE_DIM);
        shapes.rect(0, HandLayout.castLineY(h) - 2f, w, 4f);
        drawCommandCastLine();
        drawClusterShapes(youCluster, shockHoverYou || shockLockYou || playerTargetHot(true));
        drawClusterShapes(enemyCluster, shockHoverThem || shockLockThem || playerTargetHot(false));
        drawCommandShapes();
        drawPoisonDrop(youCluster);
        drawPoisonDrop(enemyCluster);
        drawActionShapes(youCluster);
        for (UiButton b : buttons) drawBadge(b, new Color(0.16f, 0.18f, 0.22f, 0.92f));
        stripHover = BattlefieldLayout.phaseIndexAt(w, h, mx, my, youDx, youDy, enemyDx, enemyDy);
        drawPhaseStripShapes(w, h);
        for (Perm p : board) {
            if (p.alpha <= 0.02f || p.tucked || hidePermForTravel(p)) continue;
            drawFlyShadow(p);
            Color glow = null;
            if (p == shockHover || p == shockLock) glow = ARROW_SPELL;
            else if (markedDead(p)) glow = RED;
            else if (p.attacking || p.blocking) {
                drawCombatGlow(p);
                if (liveReady() && live.legalTarget(p.forgeCard)) drawTargetRim(p);
                else if (showPlayableRim(p)) drawPlayableRim(p);
                continue;
            } else if (phase == Phase.ATTACKERS && p.you && BattlefieldLayout.canAttack(p.kind, p.tapped, p.sick))
                glow = GOLD;
            else if (p == selected) glow = Color.WHITE;
            if (glow != null) {
                shapes.setColor(glow.r, glow.g, glow.b, 0.45f);
                float gy = p.cy + flyBob(p);
                spinShapes(p.cx, gy, p.rot);
                if (p.token) fillToken(p.cx, gy, p.w + 8f, p.h + 8f);
                else shapes.rect(p.cx - p.w / 2f - 4f, gy - p.h / 2f - 4f, p.w + 8f, p.h + 8f);
                shapes.setTransformMatrix(savedTransform);
            }
            if (liveReady() && live.legalTarget(p.forgeCard)) drawTargetRim(p);
            else if (showPlayableRim(p)) drawPlayableRim(p);
        }
        for (StackItem item : stack) {
            if (item.showcase) continue;
            boolean active = item == top();
            boolean examined = stack.indexOf(item) == examining;
            boolean target = liveReady() && live.choosingTarget() && live.legalTarget(item.forgeCard);
            shapes.setColor(examined ? 0.24f : 0.10f, examined ? 0.18f : 0.10f, 0.08f, 0.94f);
            shapes.rect(item.rowX - item.rowW / 2f, item.rowY - item.rowH / 2f, item.rowW, item.rowH);
            if (target) shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
            else shapes.setColor(active ? GOLD.r : 0.45f, active ? GOLD.g : 0.40f, active ? GOLD.b : 0.28f, active ? 1f : 0.75f);
            float left = item.rowX - item.rowW / 2f;
            float bottom = item.rowY - item.rowH / 2f;
            shapes.rect(left, bottom, item.rowW, 3f);
            shapes.rect(left, bottom + item.rowH - 3f, item.rowW, 3f);
            shapes.rect(left, bottom, 3f, item.rowH);
            shapes.rect(left + item.rowW - 3f, bottom, 3f, item.rowH);
        }
        shapes.end();

        batch.begin();
        drawSide(false);
        drawSide(true);
        font.setColor(Color.WHITE);
        big.setColor(Color.WHITE);
        drawActionLabels(youCluster);
        drawZoneIcon(graveIcon, youCluster.grave);
        drawZoneIcon(graveIcon, enemyCluster.grave);
        drawZoneIcon(exileIcon, youCluster.exile);
        drawZoneIcon(exileIcon, enemyCluster.exile);
        drawExileTiles(youCluster, true);
        drawExileTiles(enemyCluster, false);
        drawCustomSleeve(youCluster);
        drawRevealedTop(youCluster);
        drawZoneCount(youCluster);
        drawZoneCount(enemyCluster);
        drawHandCounts(youCluster, enemyCluster);
        drawPhaseStripText(w, h);
        if (!(liveReady() && live.pickSheetOpen())) {
            drawHandFan(cards, willCast);
        }
        List<HandCard> ghostCards = liveGhosts();
        drawGhostFan(ghostCards);
        if (liveMode) drawSideLog(w, h);
        drawExilePreview(w, h);
        drawBoardPreview(w, h);
        drawLife(youCluster, nameInitial(youName, "Y"), yourLife, youName);
        drawLife(enemyCluster, nameInitial(themName, "E"), theirLife, themName);
        drawCommandCards();
        drawDraggedCommand(w, h);
        drawCommandPreview(w, h);
        drawCommanderDamage(youCluster, yourCommanderDamage);
        drawCommanderDamage(enemyCluster, theirCommanderDamage);
        queueFloatingMana(youCluster, yourPool, 13f);
        queueFloatingMana(enemyCluster, theirPool, 9f);
        drawPoisonCount(youCluster);
        drawPoisonCount(enemyCluster);
        for (UiButton b : buttons) drawCentered(b.label, b.x, b.y + 6f, Color.WHITE);
        if (liveMode && matchOver) drawResult(w, h);
        else if (liveMode) {
            String ask = mulliganAsk(note);
            String unpaid = castingCostOnly(note);
            boolean wordList = live.textChoiceOpen();
            String sheetAsk = (live.cardSheetOpen() || live.modeSheetOpen()) && !wordList ? live.sheetTitle() : null;
            String searching = seatWaitLine();
            String sideNote = unpaid != null || live.xOpen() ? null : (sheetAsk != null ? sheetAsk : ask);
            if (searching != null && !wordList) {
                boolean searchLine = searching.endsWith("is searching")
                        || searching.contains("is looking at")
                        || searching.startsWith("waiting for");
                if (searchLine) sideNote = searching;
            }
            String autoTaken = live.searchAutoNote();
            if (autoTaken != null && sheetAsk == null && !wordList) sideNote = autoTaken;
            if (wordList && unpaid == null && !live.xOpen()) {
                BattlefieldLive.Pic wordPic = live.picture();
                if (wordPic != null && wordPic.choiceMessage != null && !wordPic.choiceMessage.isBlank()) {
                    sideNote = wordPic.choiceMessage;
                }
            }
            if (summonHold > 0f && summonBlocks && sheetAsk == null && searching == null
                    && !wordList && !live.choiceOpen()) {
                sideNote = summonName == null || summonName.isBlank()
                        ? "Summoning" : "Summoning\n" + summonName;
            } else if (live.choosingTarget()) {
                String spell = live.targetSpellName();
                sideNote = spell.isEmpty() ? "Choose a target" : "Choose a target\n" + spell;
            }
            List<String> modeLines = live.modeSheetOpen() ? live.modeLines() : List.of();
            drawTurnInfo(w, h, sideNote, modeLines);
            if (unpaid != null && !live.xOpen()) queueCenteredCost(unpaid, w, h);
        } else drawCentered(bannerText(), w / 2f, split + 22f, GOLD);
        drawStackLog();
        drawStackPreview(w, h);
        drawCastFlight();
        drawEmblems();
        drawEmblemHover();
        if (draggingTop) drawDraggedTop();
        drawTopLook(w, h);
        batch.end();

        shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawSummonPuffs();
        drawQueuedPipShapes();
        drawArrows(w, h);
        shapes.end();
        if (!pipQueue.isEmpty()) {
            batch.begin();
            drawQueuedPipLabels();
            batch.end();
        }
        if (liveReady() && live.xOpen()) drawXChoice(w, h);
        else xControls.clear();

        boolean holdAsk = summonHold > 0f && summonBlocks && !(liveReady() && live.cardSheetOpen());
        if (liveReady() && live.textChoiceOpen()) {
            drawLiveChoice(w, h);
        } else {
            textChoiceHits.clear();
            textChoiceIndex.clear();
            textChoiceOk = null;
            textChoiceSearch = null;
            textChoiceAll = null;
            hoverFace = -1;
            typeMaxScroll = 0f;
        }
        if (liveReady() && live.cardSheetOpen() && !holdAsk) drawCardSheet(w, h);
        if (openZone != ZoneView.NONE) {
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            drawZoneGridShapes(w, h, youCluster, enemyCluster);
            shapes.end();
            batch.begin();
            drawZoneGrid(w, h, youCluster, enemyCluster);
            drawBrowsePreview(w, h);
            batch.end();
        } else {
            batch.begin();
            drawBrowsePreview(w, h);
            batch.end();
        }
        if (cardZoom) {
            batch.begin();
            drawCardZoom(w, h);
            batch.end();
        } else if (openZone == ZoneView.NONE && anyChoiceOpen()) {
            batch.begin();
            drawExilePreview(w, h);
            drawBoardPreview(w, h);
            drawCommandPreview(w, h);
            drawStackPreview(w, h);
            drawEmblemHover();
            batch.end();
        }
        if (liveReady() && live.textChoiceOpen() && !cardZoom && openZone == ZoneView.NONE) {
            drawFaceChoicePreview(w, h);
        }
        if (liveReady() && live.cardSheetOpen() && !holdAsk && !cardZoom && openZone == ZoneView.NONE) {
            drawSheetPreview(w, h);
        }
        batch.begin();
        drawHandCounts(youCluster, enemyCluster);
        batch.end();
        drawOptions(w, h);
    }

    /** Centre of the Change decks row, above Save and Quit. */
    private float[] optChange(float w, float h) {
        float[] save = optRow(w, h, false);
        float[] panel = optPanel(w, h);
        float left = panel[0] - panel[2] / 2f;
        float rowW = panel[2] - 32f;
        float rowH = 32f;
        float cx = left + 16f + rowW / 2f;
        float cy = save[1] + save[3] / 2f + 8f + rowH / 2f;
        return new float[] { cx, cy, rowW, rowH };
    }

    private void refreshPickerNote() {
        if (live != null && live.ready()) {
            picking = false;
            starting = false;
            optionsNote = "Save writes this Forge match into the duel folder. "
                    + "Change decks starts a new match. Quit closes the window.";
            return;
        }
        if (!starting || live == null) return;
        if (live.failed) {
            starting = false;
            pickNote = live.status == null || live.status.isBlank()
                    ? "Could not start the duel." : live.status;
            return;
        }
        if (live.status != null && !live.status.isBlank()) pickNote = live.status;
    }

    private void startChosenMatch() {
        if (starting || youRows.isEmpty() || ashRows.isEmpty()) return;
        int you = Math.max(0, Math.min(pickYou, youRows.size() - 1));
        int ash = Math.max(0, Math.min(pickAsh, ashRows.size() - 1));
        starting = true;
        pickNote = "Loading Forge cards. This takes a minute.";
        ensurePlayerName();
        if (live == null) live = new BattlefieldLive();
        live.usePlayerName(playerName);
        live.begin(youRows.get(you), ashRows.get(ash));
    }

    private void playAgain() {
        matchOver = false;
        optionsOpen = false;
        if (live != null) live.begin(null, null);
    }

    private void changeDecks() {
        matchOver = false;
        optionsOpen = false;
        picking = true;
        lobbying = false;
        starting = false;
        if (live != null) live.abandonMatch();
        joinField = true;
        if (joinAddress == null || joinAddress.isBlank()) joinAddress = LOCAL_JOIN;
        pickNote = decks == null
                ? "Pick a deck for you and a deck for Ash, then Start."
                : decks.note;
    }

    private boolean clipboardLooksLikeDeck() {
        return BattlefieldArenaList.looksLikeDeck(clipboardText());
    }

    private void importArenaClipboard() {
        importArenaList(clipboardText());
    }

    private void importArenaList(String text) {
        if (text == null || text.isBlank()) {
            pickNote = "Copy a list from Arena first, then Import.";
            return;
        }
        String norm = text.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (!norm.isEmpty() && norm.equals(lastImportNorm)) {
            pickNote = "That list is already imported. Open Manage to rename or delete it.";
            return;
        }
        try {
            Path already = BattlefieldDecks.alreadyImported(text);
            Path file = BattlefieldDecks.importArena(text);
            lastImportNorm = norm;
            reloadPicker(file);
            String label = youRows.isEmpty() || pickYou < 0 || pickYou >= youRows.size()
                    ? "that list" : youRows.get(pickYou).label;
            List<String> names = new ArrayList<>();
            BattlefieldArenaList.Parsed parsed = BattlefieldArenaList.parse(text);
            if (parsed.error == null) {
                for (BattlefieldArenaList.CardLine line : parsed.main) names.add(line.name);
                for (BattlefieldArenaList.CardLine line : parsed.side) names.add(line.name);
                for (BattlefieldArenaList.CardLine line : parsed.commander) names.add(line.name);
            }
            BattlefieldPics.want(names);
            if (already != null) {
                pickNote = "Already have that list as " + label + ". Manage to rename or delete.";
            } else if (!BattlefieldPics.busy()) {
                pickNote = "Imported " + label + ". Pictures already on disk. Start, Host, or Join.";
            } else {
                pickNote = "Imported " + label + ". Getting pictures…";
            }
        } catch (IllegalArgumentException bad) {
            pickNote = bad.getMessage();
        } catch (Exception failed) {
            pickNote = "Could not save that list: " + failed.getMessage();
        }
    }

    private void reloadPicker(Path imported) {
        decks = BattlefieldDecks.scan();
        youRows = decks.forYou();
        ashRows = decks.forAsh();
        if (imported != null) {
            Path want = imported.toAbsolutePath().normalize();
            for (int i = 0; i < youRows.size(); i++) {
                BattlefieldDecks.Choice row = youRows.get(i);
                if (row != null && row.file != null && row.file.equals(want)) {
                    pickYou = i;
                    break;
                }
            }
        }
        pickScrollYou = clampPickScroll(youRows, youColW, youColH, pickScrollYou);
        pickScrollAsh = clampPickScroll(ashRows, ashColW, ashColH, pickScrollAsh);
    }

    private void openManageSelected() {
        if (youRows.isEmpty()) {
            pickNote = "Pick a list first.";
            return;
        }
        int you = Math.max(0, Math.min(pickYou, youRows.size() - 1));
        openManage(youRows.get(you));
    }

    private void openManage(BattlefieldDecks.Choice choice) {
        if (choice == null) return;
        manageChoice = choice;
        manageSheet = BattlefieldDecks.sheetOf(choice);
        manageName = manageSheet.name == null ? "" : manageSheet.name;
        manageScroll = 0f;
        manageHover = -1;
        manageDeleteAsk = false;
        manageNameFocused = manageSheet.fileDeck;
        manageSelectAll = true;
        manageCaret = manageName.length();
        managing = true;
        nameFocused = false;
        addrFocused = false;
    }

    private void closeManage() {
        managing = false;
        manageDeleteAsk = false;
        manageNameFocused = false;
        manageChoice = null;
        manageSheet = null;
    }

    private void pressManage() {
        float btnW = 150f, btnH = 40f;
        if (manageDeleteAsk) {
            if (boxContains(manageYesCx, manageCloseCy, btnW, btnH)) {
                deleteManaged();
                return;
            }
            if (boxContains(manageNoCx, manageCloseCy, btnW, btnH)) {
                manageDeleteAsk = false;
                return;
            }
            return;
        }
        if (boxContains(manageCloseCx, manageCloseCy, btnW, btnH)) {
            closeManage();
            return;
        }
        if (boxContains(manageDelCx, manageCloseCy, btnW, btnH)) {
            if (manageSheet != null && manageSheet.fileDeck) manageDeleteAsk = true;
            else pickNote = "The test lists stay. They are not files.";
            return;
        }
        if (boxContains(manageNameCx, manageNameCy, manageNameW, manageNameH)) {
            manageNameFocused = true;
            manageSelectAll = true;
            manageCaret = manageName == null ? 0 : manageName.length();
            return;
        }
        manageNameFocused = false;
        for (float[] hit : manageHits) {
            if (boxContains(hit[0], hit[1], hit[2], hit[3])) {
                manageHover = (int) hit[4];
                return;
            }
        }
    }

    private void scrollManage(float amountY) {
        if (manageSheet == null) return;
        float step = amountY * 28f;
        float content = manageSheet.lines.size() * 28f;
        float max = Math.max(0f, content - Math.max(40f, manageListH));
        manageScroll = Math.max(0f, Math.min(max, manageScroll + step));
    }

    private boolean manageNameKey(int keycode) {
        if (ctrlDown() && keycode == Input.Keys.V) {
            manageNameFocused = true;
            pasteManageName();
            return true;
        }
        if (keycode == Input.Keys.ENTER) {
            saveManagedName();
            return true;
        }
        if (!manageNameFocused) return false;
        if (keycode == Input.Keys.BACKSPACE) {
            if (manageSelectAll) {
                manageName = "";
                manageCaret = 0;
                manageSelectAll = false;
            } else if (manageCaret > 0 && manageName != null && !manageName.isEmpty()) {
                int at = Math.min(manageCaret, manageName.length());
                manageName = manageName.substring(0, at - 1) + manageName.substring(at);
                manageCaret = at - 1;
            }
            return true;
        }
        if (keycode == Input.Keys.FORWARD_DEL) {
            if (manageSelectAll) {
                manageName = "";
                manageCaret = 0;
                manageSelectAll = false;
            } else if (manageName != null && manageCaret < manageName.length()) {
                manageName = manageName.substring(0, manageCaret) + manageName.substring(manageCaret + 1);
            }
            return true;
        }
        if (keycode == Input.Keys.LEFT) {
            if (manageSelectAll) {
                manageSelectAll = false;
                manageCaret = 0;
            } else {
                manageCaret = Math.max(0, manageCaret - 1);
            }
            return true;
        }
        if (keycode == Input.Keys.RIGHT) {
            int end = manageName == null ? 0 : manageName.length();
            if (manageSelectAll) {
                manageSelectAll = false;
                manageCaret = end;
            } else {
                manageCaret = Math.min(end, manageCaret + 1);
            }
            return true;
        }
        return keycode == Input.Keys.SHIFT_LEFT || keycode == Input.Keys.SHIFT_RIGHT
                || keycode == Input.Keys.CONTROL_LEFT || keycode == Input.Keys.CONTROL_RIGHT;
    }

    private void typeManageName(char character) {
        if (!manageSheet.fileDeck) return;
        if (manageSelectAll) {
            manageName = "";
            manageCaret = 0;
            manageSelectAll = false;
        }
        if (manageName == null) manageName = "";
        int at = Math.max(0, Math.min(manageCaret, manageName.length()));
        manageName = manageName.substring(0, at) + character + manageName.substring(at);
        if (manageName.length() > 80) manageName = manageName.substring(0, 80);
        manageCaret = Math.min(manageName.length(), at + 1);
    }

    private void pasteManageName() {
        String clip = clipboardText();
        if (clip == null || clip.isBlank() || BattlefieldArenaList.looksLikeDeck(clip)) return;
        String first = clip.replace('\r', ' ').replace('\n', ' ').trim();
        if (first.length() > 80) first = first.substring(0, 80).trim();
        manageName = first;
        manageCaret = manageName.length();
        manageSelectAll = true;
    }

    private void saveManagedName() {
        if (manageChoice == null || manageChoice.kind != BattlefieldDecks.Kind.FILE) {
            pickNote = "The test lists keep their names.";
            return;
        }
        try {
            Path next = BattlefieldDecks.rename(manageChoice.file, manageName);
            reloadPicker(next);
            openManageSelected();
            pickNote = "Renamed to " + manageName + ".";
        } catch (IllegalArgumentException bad) {
            pickNote = bad.getMessage();
        } catch (Exception failed) {
            pickNote = "Could not rename: " + failed.getMessage();
        }
    }

    private void deleteManaged() {
        if (manageChoice == null || manageChoice.kind != BattlefieldDecks.Kind.FILE) {
            manageDeleteAsk = false;
            pickNote = "The test lists stay.";
            return;
        }
        try {
            Path gone = manageChoice.file;
            BattlefieldDecks.delete(gone);
            closeManage();
            reloadPicker(null);
            pickYou = Math.min(pickYou, Math.max(0, youRows.size() - 1));
            pickNote = "Deleted that list.";
        } catch (Exception failed) {
            manageDeleteAsk = false;
            pickNote = "Could not delete: " + failed.getMessage();
        }
    }

    private void drawManage(float w, float h) {
        if (manageSheet == null) return;
        managePanelW = Math.min(960f, w - 48f);
        managePanelH = Math.min(640f, h - 40f);
        managePanelCx = w * 0.5f;
        managePanelCy = h * 0.5f;
        float left = managePanelCx - managePanelW / 2f;
        float bottom = managePanelCy - managePanelH / 2f;
        float top = bottom + managePanelH;
        manageNameW = managePanelW - 40f;
        manageNameH = 40f;
        manageNameCx = managePanelCx;
        manageNameCy = top - 36f - manageNameH / 2f;
        float btnW = 150f, btnH = 40f;
        manageCloseCy = bottom + 28f + btnH / 2f;
        manageDelCx = left + 28f + btnW / 2f;
        manageCloseCx = left + managePanelW - 28f - btnW / 2f;
        manageYesCx = managePanelCx - btnW / 2f - 12f;
        manageNoCx = managePanelCx + btnW / 2f + 12f;
        manageListX = left + 20f;
        manageListY = bottom + 84f;
        manageListW = managePanelW * 0.52f;
        manageListH = manageNameCy - manageNameH / 2f - 16f - manageListY;
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(batch.getProjectionMatrix());
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0f, 0f, 0f, 0.55f);
        shapes.rect(0, 0, w, h);
        shapes.setColor(0.08f, 0.09f, 0.11f, 0.98f);
        shapes.rect(left, bottom, managePanelW, managePanelH);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
        strokeRect(left, bottom, managePanelW, managePanelH, 3f);
        shapes.setColor(0.05f, 0.06f, 0.07f, 1f);
        shapes.rect(manageNameCx - manageNameW / 2f, manageNameCy - manageNameH / 2f, manageNameW, manageNameH);
        if (manageNameFocused && manageSelectAll && manageName != null && !manageName.isEmpty()) {
            shapes.setColor(0.22f, 0.32f, 0.42f, 1f);
            shapes.rect(manageNameCx - manageNameW / 2f + 4f, manageNameCy - manageNameH / 2f + 4f,
                    manageNameW - 8f, manageNameH - 8f);
        }
        shapes.setColor(manageNameFocused ? GOLD.r : 0.78f, manageNameFocused ? GOLD.g : 0.78f,
                manageNameFocused ? GOLD.b : 0.7f, 1f);
        strokeRect(manageNameCx - manageNameW / 2f, manageNameCy - manageNameH / 2f, manageNameW, manageNameH, 2f);
        shapes.setColor(0.06f, 0.07f, 0.08f, 1f);
        shapes.rect(manageListX, manageListY, manageListW, manageListH);
        shapes.setColor(0.32f, 0.16f, 0.14f, 1f);
        if (manageDeleteAsk) {
            shapes.setColor(0.32f, 0.16f, 0.14f, 1f);
            shapes.rect(manageYesCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH);
            shapes.setColor(0.16f, 0.28f, 0.34f, 1f);
            shapes.rect(manageNoCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH);
        } else {
            if (manageSheet.fileDeck) {
                shapes.setColor(0.32f, 0.16f, 0.14f, 1f);
                shapes.rect(manageDelCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH);
            }
            shapes.setColor(0.16f, 0.28f, 0.34f, 1f);
            shapes.rect(manageCloseCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH);
        }
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
        if (manageDeleteAsk) {
            strokeRect(manageYesCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH, 2f);
            strokeRect(manageNoCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH, 2f);
        } else {
            if (manageSheet.fileDeck) {
                strokeRect(manageDelCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH, 2f);
            }
            strokeRect(manageCloseCx - btnW / 2f, manageCloseCy - btnH / 2f, btnW, btnH, 2f);
        }
        shapes.end();

        manageHits.clear();
        manageHover = -1;
        float rowH = 26f;
        float textW = manageListW - 20f;
        float rowTop = manageListY + manageListH - 8f + manageScroll;
        for (int i = 0; i < manageSheet.lines.size(); i++) {
            float cy = rowTop - rowH / 2f;
            rowTop -= rowH;
            if (cy + rowH / 2f < manageListY || cy - rowH / 2f > manageListY + manageListH) continue;
            if (!manageSheet.lines.get(i).heading
                    && boxContains(manageListX + manageListW / 2f, cy, manageListW, rowH)) {
                manageHover = i;
            }
            manageHits.add(new float[] { manageListX + manageListW / 2f, cy, manageListW, rowH, i });
        }

        batch.begin();
        String title = manageName == null || manageName.isEmpty() ? "Name this list" : manageName;
        stackFont.setColor(manageName == null || manageName.isEmpty()
                ? new Color(0.65f, 0.65f, 0.6f, 1f) : Color.WHITE);
        stackFont.draw(batch, title, manageNameCx - manageNameW / 2f + 12f, manageNameCy + 8f,
                manageNameW - 24f, Align.left, false);
        stackFont.setColor(Color.WHITE);
        String hint = manageSheet.fileDeck
                ? (manageSheet.format == null || manageSheet.format.isBlank()
                    ? "Enter saves the name."
                    : manageSheet.format + "  ·  Enter saves the name.")
                : "Built-in test list. Pick a saved list to rename or delete.";
        stripFont.setColor(new Color(0.78f, 0.78f, 0.7f, 1f));
        stripFont.draw(batch, hint, left + 20f, top - 10f);
        stripFont.setColor(Color.WHITE);
        batch.flush();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor((int) manageListX, (int) manageListY,
                (int) Math.max(1f, manageListW), (int) Math.max(1f, manageListH));
        rowTop = manageListY + manageListH - 8f + manageScroll;
        for (BattlefieldDecks.Line line : manageSheet.lines) {
            float cy = rowTop - rowH / 2f;
            rowTop -= rowH;
            if (cy + rowH / 2f < manageListY || cy - rowH / 2f > manageListY + manageListH) continue;
            if (line.heading) stackFont.setColor(GOLD);
            else stackFont.setColor(Color.WHITE);
            stackFont.draw(batch, line.text, manageListX + 10f, cy + 6f, textW, Align.left, false);
        }
        stackFont.setColor(Color.WHITE);
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        if (manageHover >= 0 && manageHover < manageSheet.lines.size()) {
            BattlefieldDecks.Line line = manageSheet.lines.get(manageHover);
            if (!line.heading && line.card != null && !line.card.isBlank()) {
                float wellW = managePanelW * 0.38f;
                float wellH = wellW * 88f / 63f;
                float wellCx = left + managePanelW - 24f - wellW / 2f;
                float wellCy = manageListY + manageListH / 2f;
                if (wellH > manageListH) {
                    wellH = manageListH;
                    wellW = wellH * 63f / 88f;
                    wellCx = left + managePanelW - 24f - wellW / 2f;
                }
                Texture tex = textureFor(line.card);
                if (tex != null) {
                    batch.setColor(Color.WHITE);
                    drawTex(tex, wellCx, wellCy, wellW, wellH, 0f);
                } else {
                    drawOracleFrame(line.card, missingArtAt(wellCx, wellCy, w, h));
                }
            }
        }
        if (manageDeleteAsk) {
            drawCentered(stackFont, "Delete this list?", managePanelCx, manageCloseCy + btnH / 2f + 18f, Color.WHITE);
            drawCentered(stackFont, "Yes, delete", manageYesCx, manageCloseCy + 10f, Color.WHITE);
            drawCentered(stackFont, "Keep it", manageNoCx, manageCloseCy + 10f, Color.WHITE);
        } else {
            if (manageSheet.fileDeck) {
                drawCentered(stackFont, "Delete", manageDelCx, manageCloseCy + 10f, Color.WHITE);
            }
            drawCentered(stackFont, "Close", manageCloseCx, manageCloseCy + 10f, Color.WHITE);
        }
        batch.end();
    }

    private void startHostedMatch() {
        if (youRows.isEmpty()) {
            pickNote = "Pick your deck, then Host.";
            return;
        }
        if (starting) {
            pickNote = live != null && live.status != null && !live.status.isBlank()
                    ? live.status : "Already opening the match.";
            return;
        }
        if (lobbying) return;
        int you = Math.max(0, Math.min(pickYou, youRows.size() - 1));
        picking = false;
        lobbying = true;
        starting = false;
        ensurePlayerName();
        if (live == null) live = new BattlefieldLive();
        optionsNote = "Back returns to the deck picker. Quit closes the window.";
        live.usePlayerName(playerName);
        live.host(youRows.get(you));
    }

    private void startJoin() {
        joinField = true;
        if (youRows.isEmpty()) {
            pickNote = "Pick your deck, then Join.";
            return;
        }
        if (starting) {
            pickNote = live != null && live.status != null && !live.status.isBlank()
                    ? live.status : "Already connecting.";
            return;
        }
        String address = BattlefieldLive.normalizeJoinAddress(joinAddress);
        if (address.isEmpty()) address = LOCAL_JOIN;
        joinAddress = address;
        joinCaret = joinAddress.length();
        joinSelectAll = false;
        saveJoinAddress();
        if (lobbying) return;
        int you = Math.max(0, Math.min(pickYou, youRows.size() - 1));
        picking = false;
        lobbying = true;
        starting = false;
        ensurePlayerName();
        if (live == null) live = new BattlefieldLive();
        optionsNote = "Back returns to the deck picker. Quit closes the window.";
        live.usePlayerName(playerName);
        live.join(address, youRows.get(you));
    }

    /** Lobby or a failed connect. The deck picker comes back, and the host port is released off this thread. */
    private void leaveLobby() {
        optionsOpen = false;
        lobbying = false;
        picking = true;
        starting = false;
        joinField = true;
        if (joinAddress == null || joinAddress.isBlank()) joinAddress = LOCAL_JOIN;
        pickNote = decks == null || decks.note == null || decks.note.isBlank()
                ? "Pick your deck, then Host or Join."
                : decks.note;
        if (live != null) live.leaveLobby();
        focusJoinAddress();
        joinSelectAll = true;
        joinCaret = joinAddress == null ? 0 : joinAddress.length();
    }

    private Path userDir() {
        String home = System.getProperty("forge.test.userDir");
        Path dir = home == null || home.isBlank()
                ? Path.of("forge-gui-mobile-dev", "target", "battlefield-user")
                : Path.of(home);
        return dir.toAbsolutePath().normalize();
    }

    private Path joinHostFile() {
        return userDir().resolve("join-host.txt");
    }

    private Path playerNameFile() {
        return userDir().resolve("player-name.txt");
    }

    private String defaultPlayerName() {
        String os = BattlefieldLive.cleanPlayerName(System.getProperty("user.name"));
        if (os.isBlank() || "human".equalsIgnoreCase(os)) os = "Player";
        return os;
    }

    private void loadPlayerName() {
        Path file = playerNameFile();
        String ready = "";
        if (Files.isRegularFile(file)) {
            try {
                ready = BattlefieldLive.cleanPlayerName(Files.readString(file));
            } catch (IOException ignored) {
                ready = "";
            }
        }
        if (ready.isBlank() || "human".equalsIgnoreCase(ready)) ready = defaultPlayerName();
        playerName = ready;
        nameCaret = playerName.length();
        nameSelectAll = true;
        nameFocused = false;
        addrFocused = true;
        joinSelectAll = true;
    }

    private void savePlayerName() {
        String ready = BattlefieldLive.cleanPlayerName(playerName);
        playerName = ready;
        nameCaret = Math.max(0, Math.min(nameCaret, playerName.length()));
        if (ready.isBlank()) return;
        Path file = playerNameFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ready + "\n");
        } catch (IOException ignored) {
            // Next launch uses the Windows user name.
        }
    }

    private void ensurePlayerName() {
        String ready = BattlefieldLive.cleanPlayerName(playerName);
        if (ready.isBlank()) ready = defaultPlayerName();
        playerName = ready;
        nameCaret = playerName.length();
        savePlayerName();
    }

    private void loadJoinAddress() {
        Path file = joinHostFile();
        if (!Files.isRegularFile(file)) return;
        try {
            String ready = BattlefieldLive.normalizeJoinAddress(Files.readString(file));
            if (!ready.isBlank()) {
                joinAddress = ready;
                joinCaret = joinAddress.length();
                joinSelectAll = true;
                addrFocused = true;
            }
        } catch (IOException ignored) {
            // Default loopback stays.
        }
    }

    private void saveJoinAddress() {
        String ready = BattlefieldLive.normalizeJoinAddress(joinAddress);
        if (ready.isBlank()) ready = joinAddress == null ? "" : joinAddress.trim();
        if (ready.isBlank()) return;
        Path file = joinHostFile();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, ready + "\n");
        } catch (IOException ignored) {
            // Next launch uses the default.
        }
    }

    private void focusPlayerName() {
        nameFocused = true;
        addrFocused = false;
        nameSelectAll = true;
        nameCaret = playerName == null ? 0 : playerName.length();
    }

    private void focusJoinAddress() {
        nameFocused = false;
        addrFocused = true;
        joinSelectAll = true;
        joinCaret = joinAddress == null ? 0 : joinAddress.length();
    }

    private boolean nameKey(int keycode) {
        if (ctrlDown() && keycode == Input.Keys.V) {
            focusPlayerName();
            pastePlayerName();
            return true;
        }
        if (shiftDown() && keycode == Input.Keys.INSERT) {
            focusPlayerName();
            pastePlayerName();
            return true;
        }
        if (ctrlDown() && keycode == Input.Keys.A) {
            focusPlayerName();
            nameSelectAll = true;
            nameCaret = playerName == null ? 0 : playerName.length();
            return true;
        }
        if (!nameFocused) return false;
        if (keycode == Input.Keys.BACKSPACE) {
            if (nameSelectAll) {
                playerName = "";
                nameCaret = 0;
                nameSelectAll = false;
            } else if (nameCaret > 0 && playerName != null && !playerName.isEmpty()) {
                int at = Math.min(nameCaret, playerName.length());
                playerName = playerName.substring(0, at - 1) + playerName.substring(at);
                nameCaret = at - 1;
            }
            savePlayerName();
            return true;
        }
        if (keycode == Input.Keys.FORWARD_DEL) {
            if (nameSelectAll) {
                playerName = "";
                nameCaret = 0;
                nameSelectAll = false;
            } else if (playerName != null && nameCaret < playerName.length()) {
                playerName = playerName.substring(0, nameCaret) + playerName.substring(nameCaret + 1);
            }
            savePlayerName();
            return true;
        }
        if (keycode == Input.Keys.LEFT) {
            if (nameSelectAll) {
                nameSelectAll = false;
                nameCaret = 0;
            } else {
                nameCaret = Math.max(0, nameCaret - 1);
            }
            return true;
        }
        if (keycode == Input.Keys.RIGHT) {
            int end = playerName == null ? 0 : playerName.length();
            if (nameSelectAll) {
                nameSelectAll = false;
                nameCaret = end;
            } else {
                nameCaret = Math.min(end, nameCaret + 1);
            }
            return true;
        }
        if (keycode == Input.Keys.HOME) {
            nameSelectAll = false;
            nameCaret = 0;
            return true;
        }
        if (keycode == Input.Keys.END) {
            nameSelectAll = false;
            nameCaret = playerName == null ? 0 : playerName.length();
            return true;
        }
        return false;
    }

    private void typePlayerName(char ch) {
        nameFocused = true;
        addrFocused = false;
        if (nameSelectAll) {
            playerName = String.valueOf(ch);
            nameCaret = 1;
            nameSelectAll = false;
        } else {
            if (playerName == null) playerName = "";
            int at = Math.max(0, Math.min(nameCaret, playerName.length()));
            playerName = playerName.substring(0, at) + ch + playerName.substring(at);
            nameCaret = at + 1;
        }
        if (playerName.length() > 32) {
            playerName = playerName.substring(0, 32);
            nameCaret = Math.min(nameCaret, playerName.length());
        }
        savePlayerName();
    }

    private void pastePlayerName() {
        String clip = clipboardText();
        if (clip == null || clip.isBlank()) return;
        if (BattlefieldArenaList.looksLikeDeck(clip)) {
            pickNote = "That paste is a deck list. Click Import Arena list.";
            return;
        }
        String ready = BattlefieldLive.cleanPlayerName(clip);
        if (ready.isBlank()) return;
        playerName = ready;
        nameCaret = playerName.length();
        nameSelectAll = true;
        nameFocused = true;
        addrFocused = false;
        savePlayerName();
        pickNote = "This window will sit as " + playerName + ".";
    }

    private boolean joinKey(int keycode) {
        if (ctrlDown() && keycode == Input.Keys.V) {
            addrFocused = true;
            pasteJoinAddress();
            return true;
        }
        if (shiftDown() && keycode == Input.Keys.INSERT) {
            addrFocused = true;
            pasteJoinAddress();
            return true;
        }
        if (ctrlDown() && keycode == Input.Keys.A) {
            addrFocused = true;
            joinSelectAll = true;
            joinCaret = joinAddress == null ? 0 : joinAddress.length();
            return true;
        }
        if (!addrFocused) {
            if (keycode == Input.Keys.BACKSPACE || keycode == Input.Keys.FORWARD_DEL) {
                addrFocused = true;
                joinSelectAll = true;
            } else {
                return false;
            }
        }
        if (keycode == Input.Keys.BACKSPACE) {
            if (joinSelectAll) {
                joinAddress = "";
                joinCaret = 0;
                joinSelectAll = false;
            } else if (joinCaret > 0 && joinAddress != null && !joinAddress.isEmpty()) {
                int at = Math.min(joinCaret, joinAddress.length());
                joinAddress = joinAddress.substring(0, at - 1) + joinAddress.substring(at);
                joinCaret = at - 1;
            }
            saveJoinAddress();
            return true;
        }
        if (keycode == Input.Keys.FORWARD_DEL) {
            if (joinSelectAll) {
                joinAddress = "";
                joinCaret = 0;
                joinSelectAll = false;
            } else if (joinAddress != null && joinCaret < joinAddress.length()) {
                joinAddress = joinAddress.substring(0, joinCaret) + joinAddress.substring(joinCaret + 1);
            }
            saveJoinAddress();
            return true;
        }
        if (keycode == Input.Keys.LEFT) {
            if (joinSelectAll) {
                joinSelectAll = false;
                joinCaret = 0;
            } else {
                joinCaret = Math.max(0, joinCaret - 1);
            }
            return true;
        }
        if (keycode == Input.Keys.RIGHT) {
            int end = joinAddress == null ? 0 : joinAddress.length();
            if (joinSelectAll) {
                joinSelectAll = false;
                joinCaret = end;
            } else {
                joinCaret = Math.min(end, joinCaret + 1);
            }
            return true;
        }
        if (keycode == Input.Keys.HOME) {
            joinSelectAll = false;
            joinCaret = 0;
            return true;
        }
        if (keycode == Input.Keys.END) {
            joinSelectAll = false;
            joinCaret = joinAddress == null ? 0 : joinAddress.length();
            return true;
        }
        return false;
    }

    private void typeJoinChar(char ch) {
        addrFocused = true;
        if (joinSelectAll) {
            joinAddress = String.valueOf(ch);
            joinCaret = 1;
            joinSelectAll = false;
        } else {
            if (joinAddress == null) joinAddress = "";
            int at = Math.max(0, Math.min(joinCaret, joinAddress.length()));
            joinAddress = joinAddress.substring(0, at) + ch + joinAddress.substring(at);
            joinCaret = at + 1;
        }
        saveJoinAddress();
    }

    private void pasteJoinAddress() {
        String clip = clipboardText();
        if (clip == null || clip.isBlank()) return;
        if (BattlefieldArenaList.looksLikeDeck(clip)) {
            pickNote = "That paste is a deck list. Click Import Arena list.";
            return;
        }
        String ready = BattlefieldLive.normalizeJoinAddress(clip);
        if (ready.isBlank()) {
            String first = clip.trim();
            int cut = first.indexOf('\n');
            if (cut < 0) cut = first.indexOf('\r');
            if (cut >= 0) first = first.substring(0, cut).trim();
            if (first.length() >= 2) {
                char a = first.charAt(0);
                char b = first.charAt(first.length() - 1);
                if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                    first = first.substring(1, first.length() - 1).trim();
                }
            }
            ready = first;
        }
        if (ready.isBlank()) return;
        joinAddress = ready;
        joinCaret = joinAddress.length();
        joinSelectAll = true;
        addrFocused = true;
        saveJoinAddress();
        pickNote = "Join will use " + joinAddress + ". Click Join when your deck is picked.";
    }

    private String clipboardText() {
        try {
            if (Gdx.app != null && Gdx.app.getClipboard() != null) {
                String text = Gdx.app.getClipboard().getContents();
                if (text != null && !text.isBlank()) return text;
            }
        } catch (Throwable ignored) {
            // Fall through to AWT.
        }
        try {
            Object data = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (data != null) return data.toString();
        } catch (Throwable ignored) {
            // Empty paste does nothing.
        }
        return "";
    }

    private void pollPickerTyping() {
        if (!picking) {
            typedJoinThisFrame = false;
            typedNameThisFrame = false;
            return;
        }
        if (nameFocused) {
            typedJoinThisFrame = false;
            if (typedNameThisFrame) {
                typedNameThisFrame = false;
                return;
            }
            if (ctrlDown()) return;
            char ch = nameKeyChar();
            if (ch != 0) typePlayerName(ch);
            return;
        }
        typedNameThisFrame = false;
        if (!joinField) {
            typedJoinThisFrame = false;
            return;
        }
        if (typedJoinThisFrame) {
            typedJoinThisFrame = false;
            return;
        }
        if (ctrlDown()) return;
        char ch = joinKeyChar();
        if (ch != 0) typeJoinChar(ch);
    }

    private char nameKeyChar() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE)) return ' ';
        if (Gdx.input.isKeyJustPressed(Input.Keys.MINUS) || Gdx.input.isKeyJustPressed(Input.Keys.NUMPAD_SUBTRACT)) {
            return '-';
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.APOSTROPHE)) return '\'';
        return joinKeyChar();
    }

    private char joinKeyChar() {
        for (int i = 0; i <= 9; i++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.NUM_0 + i)
                    || Gdx.input.isKeyJustPressed(Input.Keys.NUMPAD_0 + i)) {
                return (char) ('0' + i);
            }
        }
        boolean shift = shiftDown();
        for (int i = 0; i < 26; i++) {
            if (Gdx.input.isKeyJustPressed(Input.Keys.A + i)) {
                return (char) ((shift ? 'A' : 'a') + i);
            }
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.PERIOD) || Gdx.input.isKeyJustPressed(Input.Keys.NUMPAD_DOT)) {
            return '.';
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.COLON)
                || (shift && Gdx.input.isKeyJustPressed(Input.Keys.SEMICOLON))) {
            return ':';
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.MINUS) || Gdx.input.isKeyJustPressed(Input.Keys.NUMPAD_SUBTRACT)) {
            return '-';
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.SLASH) || Gdx.input.isKeyJustPressed(Input.Keys.NUMPAD_DIVIDE)) {
            return '/';
        }
        return 0;
    }

    private boolean ctrlDown() {
        return Gdx.input.isKeyPressed(Input.Keys.CONTROL_LEFT) || Gdx.input.isKeyPressed(Input.Keys.CONTROL_RIGHT);
    }

    private boolean shiftDown() {
        return Gdx.input.isKeyPressed(Input.Keys.SHIFT_LEFT) || Gdx.input.isKeyPressed(Input.Keys.SHIFT_RIGHT);
    }

    private String shownJoinAddress() {
        return joinAddress == null || joinAddress.isEmpty() ? LOCAL_JOIN : joinAddress;
    }

    private void fillNameField() {
        float nx = nameCx - nameW / 2f;
        float ny = nameCy - nameH / 2f;
        shapes.setColor(0.05f, 0.06f, 0.07f, 1f);
        shapes.rect(nx, ny, nameW, nameH);
        if (nameFocused && nameSelectAll && playerName != null && !playerName.isEmpty()) {
            shapes.setColor(0.22f, 0.32f, 0.42f, 1f);
            shapes.rect(nx + 4f, ny + 4f, nameW - 8f, nameH - 8f);
        }
    }

    private void drawNameField() {
        String typed = playerName == null || playerName.isEmpty() ? defaultPlayerName() : playerName;
        boolean placeholder = playerName == null || playerName.isEmpty();
        Color ink = placeholder ? new Color(0.65f, 0.65f, 0.6f, 1f) : Color.WHITE;
        float left = nameCx - nameW / 2f + 12f;
        float textW = nameW - 24f;
        glyphs.setText(stackFont, typed);
        float drawX = left;
        if (glyphs.width > textW) drawX = left + textW - glyphs.width;
        stackFont.setColor(ink);
        stackFont.draw(batch, typed, drawX, nameCy + 8f);
        stackFont.setColor(Color.WHITE);
        if (nameFocused && !placeholder && !nameSelectAll && (System.currentTimeMillis() / 400L) % 2L == 0L) {
            int at = Math.max(0, Math.min(nameCaret, typed.length()));
            glyphs.setText(stackFont, typed.substring(0, at));
            float caretX = drawX + glyphs.width;
            batch.setColor(GOLD);
            batch.draw(pixel(), caretX, nameCy - 12f, 2f, 22f);
            batch.setColor(Color.WHITE);
        }
        stripFont.setColor(new Color(0.78f, 0.78f, 0.7f, 1f));
        String hint = "Name — click, type, or Ctrl+V";
        glyphs.setText(stripFont, hint);
        stripFont.draw(batch, hint, nameCx - glyphs.width / 2f, nameCy + nameH / 2f + 16f);
        stripFont.setColor(Color.WHITE);
    }

    private void drawJoinField() {
        String typed = shownJoinAddress();
        boolean placeholder = joinAddress == null || joinAddress.isEmpty();
        Color ink = placeholder ? new Color(0.65f, 0.65f, 0.6f, 1f) : Color.WHITE;
        float left = addrCx - addrW / 2f + 12f;
        float textW = addrW - 24f;
        glyphs.setText(stackFont, typed);
        float drawX = left;
        if (glyphs.width > textW) drawX = left + textW - glyphs.width;
        stackFont.setColor(ink);
        stackFont.draw(batch, typed, drawX, addrCy + 8f);
        stackFont.setColor(Color.WHITE);
        if (addrFocused && !placeholder && !joinSelectAll && (System.currentTimeMillis() / 400L) % 2L == 0L) {
            int at = Math.max(0, Math.min(joinCaret, typed.length()));
            glyphs.setText(stackFont, typed.substring(0, at));
            float caretX = drawX + glyphs.width;
            batch.setColor(GOLD);
            batch.draw(pixel(), caretX, addrCy - 12f, 2f, 22f);
            batch.setColor(Color.WHITE);
        }
        stripFont.setColor(new Color(0.78f, 0.78f, 0.7f, 1f));
        String hint = "Join address — click, type, or Ctrl+V";
        glyphs.setText(stripFont, hint);
        stripFont.draw(batch, hint, addrCx - glyphs.width / 2f, addrCy + addrH / 2f + 16f);
        stripFont.setColor(Color.WHITE);
    }

    private void pressPicker() {
        if (boxContains(startCx, startCy, startW, startH)) {
            startChosenMatch();
            return;
        }
        if (boxContains(hostCx, hostCy, hostW, hostH)) {
            startHostedMatch();
            return;
        }
        if (boxContains(joinCx, joinCy, joinW, joinH)) {
            startJoin();
            return;
        }
        if (boxContains(importCx, importCy, importW, importH)) {
            importArenaClipboard();
            return;
        }
        if (boxContains(manageCx, manageCy, manageW, manageH)) {
            openManageSelected();
            return;
        }
        if (boxContains(nameCx, nameCy, nameW, nameH)) {
            focusPlayerName();
            return;
        }
        if (joinField && boxContains(addrCx, addrCy, addrW, addrH)) {
            focusJoinAddress();
            return;
        }
        nameFocused = false;
        addrFocused = false;
        for (float[] hit : youHits) {
            if (boxContains(hit[0], hit[1], hit[2], hit[3])) {
                int row = (int) hit[4];
                if (row == pickYou) openManageSelected();
                else pickYou = row;
                return;
            }
        }
        for (float[] hit : ashHits) {
            if (boxContains(hit[0], hit[1], hit[2], hit[3])) {
                pickAsh = (int) hit[4];
                return;
            }
        }
    }

    private void scrollPicker(float amountY) {
        float step = amountY * 28f;
        if (boxContains(youColX + youColW / 2f, youColY + youColH / 2f, youColW, youColH)) {
            pickScrollYou = clampPickScroll(youRows, youColW, youColH, pickScrollYou + step);
        } else if (boxContains(ashColX + ashColW / 2f, ashColY + ashColH / 2f, ashColW, ashColH)) {
            pickScrollAsh = clampPickScroll(ashRows, ashColW, ashColH, pickScrollAsh + step);
        }
    }

    private float clampPickScroll(List<BattlefieldDecks.Choice> rows, float colW, float colH, float scroll) {
        float max = Math.max(0f, columnContent(rows, colW) - Math.max(40f, colH - 54f));
        return Math.max(0f, Math.min(max, scroll));
    }

    private float columnContent(List<BattlefieldDecks.Choice> rows, float colW) {
        float textW = Math.max(40f, colW - 28f);
        float height = 0f;
        for (BattlefieldDecks.Choice row : rows) {
            String label = row == null || row.label == null ? "" : row.label;
            glyphs.setText(stackFont, label, Color.WHITE, textW, Align.left, true);
            height += Math.max(52f, glyphs.height + 22f) + 8f;
        }
        return height;
    }

    private void drawPicker() {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        if (w < 1f || h < 1f) return;
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.05f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(batch.getProjectionMatrix());

        float gap = 28f;
        float left = 360f;
        float colW = Math.max(220f, (w - left - 28f - gap) / 2f);
        float listBottom = joinField ? 178f : 108f;
        float listTop = h - 24f;
        float listH = Math.max(80f, listTop - listBottom);
        youColX = left;
        youColY = listBottom;
        youColW = colW;
        youColH = listH;
        ashColX = left + colW + gap;
        ashColY = listBottom;
        ashColW = colW;
        ashColH = listH;
        startW = 200f;
        startH = 56f;
        hostW = 150f;
        hostH = 56f;
        joinW = 150f;
        joinH = 56f;
        float mid = left + colW + gap / 2f;
        startCx = mid;
        startCy = 46f;
        hostCx = mid - startW / 2f - 16f - hostW / 2f;
        hostCy = startCy;
        joinCx = mid + startW / 2f + 16f + joinW / 2f;
        joinCy = startCy;
        float rowW = Math.min(640f, colW * 2f + gap);
        nameW = 200f;
        nameH = 40f;
        addrW = Math.max(220f, rowW - 16f - nameW);
        addrH = 40f;
        float rowLeft = mid - (nameW + 16f + addrW) / 2f;
        nameCx = rowLeft + nameW / 2f;
        nameCy = 126f;
        addrCx = rowLeft + nameW + 16f + addrW / 2f;
        addrCy = 126f;
        pickScrollYou = clampPickScroll(youRows, colW, listH, pickScrollYou);
        pickScrollAsh = clampPickScroll(ashRows, colW, listH, pickScrollAsh);

        youHits.clear();
        ashHits.clear();
        List<float[]> youRowsLaid = layColumn(youRows, pickYou, pickScrollYou, youColX, listBottom, colW, listTop, youHits);
        List<float[]> ashRowsLaid = layColumn(ashRows, pickAsh, pickScrollAsh, ashColX, listBottom, colW, listTop, ashHits);

        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        drawColumnChrome(youColX, listBottom, colW, listH);
        drawColumnChrome(ashColX, listBottom, colW, listH);
        for (float[] row : youRowsLaid) fillPickRow(row);
        for (float[] row : ashRowsLaid) fillPickRow(row);
        shapes.setColor(starting ? 0.22f : 0.15f, starting ? 0.34f : 0.40f, starting ? 0.22f : 0.26f, 1f);
        shapes.rect(startCx - startW / 2f, startCy - startH / 2f, startW, startH);
        shapes.setColor(0.16f, 0.28f, 0.34f, 1f);
        shapes.rect(hostCx - hostW / 2f, hostCy - hostH / 2f, hostW, hostH);
        shapes.setColor(0.28f, 0.22f, 0.12f, 1f);
        shapes.rect(joinCx - joinW / 2f, joinCy - joinH / 2f, joinW, joinH);
        importW = 328f;
        importH = 44f;
        manageW = 328f;
        manageH = 44f;
        importCx = 16f + importW / 2f;
        importCy = 52f;
        manageCx = importCx;
        manageCy = importCy + importH / 2f + 10f + manageH / 2f;
        shapes.setColor(0.18f, 0.22f, 0.30f, 1f);
        shapes.rect(importCx - importW / 2f, importCy - importH / 2f, importW, importH);
        shapes.setColor(0.20f, 0.18f, 0.28f, 1f);
        shapes.rect(manageCx - manageW / 2f, manageCy - manageH / 2f, manageW, manageH);
        fillNameField();
        if (joinField) {
            float ax = addrCx - addrW / 2f;
            float ay = addrCy - addrH / 2f;
            shapes.setColor(0.05f, 0.06f, 0.07f, 1f);
            shapes.rect(ax, ay, addrW, addrH);
            if (addrFocused && joinSelectAll && joinAddress != null && !joinAddress.isEmpty()) {
                shapes.setColor(0.22f, 0.32f, 0.42f, 1f);
                shapes.rect(ax + 4f, ay + 4f, addrW - 8f, addrH - 8f);
            }
        }
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
        strokeRect(startCx - startW / 2f, startCy - startH / 2f, startW, startH, 3f);
        strokeRect(hostCx - hostW / 2f, hostCy - hostH / 2f, hostW, hostH, 3f);
        strokeRect(joinCx - joinW / 2f, joinCy - joinH / 2f, joinW, joinH, 3f);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
        strokeRect(importCx - importW / 2f, importCy - importH / 2f, importW, importH, 3f);
        strokeRect(manageCx - manageW / 2f, manageCy - manageH / 2f, manageW, manageH, 3f);
        shapes.setColor(nameFocused ? GOLD.r : 0.78f, nameFocused ? GOLD.g : 0.78f, nameFocused ? GOLD.b : 0.7f, 1f);
        strokeRect(nameCx - nameW / 2f, nameCy - nameH / 2f, nameW, nameH, nameFocused ? 3f : 2f);
        if (joinField) {
            shapes.setColor(addrFocused ? GOLD.r : 0.78f, addrFocused ? GOLD.g : 0.78f, addrFocused ? GOLD.b : 0.7f, 1f);
            strokeRect(addrCx - addrW / 2f, addrCy - addrH / 2f, addrW, addrH, addrFocused ? 3f : 2f);
        }
        shapes.end();

        batch.begin();
        drawDeckNote(h);
        big.setColor(GOLD);
        glyphs.setText(big, "You");
        big.draw(batch, "You", youColX + colW / 2f - glyphs.width / 2f, listTop - 8f);
        glyphs.setText(big, "Ash");
        big.draw(batch, "Ash", ashColX + colW / 2f - glyphs.width / 2f, listTop - 8f);
        big.setColor(Color.WHITE);
        drawColumnLabels(youRows, pickScrollYou, youColX, listBottom, colW, listTop);
        drawColumnLabels(ashRows, pickScrollAsh, ashColX, listBottom, colW, listTop);
        drawCentered(stackFont, starting ? "Starting" : "Start", startCx, startCy + 10f, Color.WHITE);
        drawCentered(stackFont, "Host", hostCx, hostCy + 10f, Color.WHITE);
        drawCentered(stackFont, "Join", joinCx, joinCy + 10f, Color.WHITE);
        drawCentered(stackFont, "Import Arena list", importCx, importCy + 10f, Color.WHITE);
        drawCentered(stackFont, "Manage list", manageCx, manageCy + 10f, Color.WHITE);
        drawNameField();
        if (joinField) {
            drawJoinField();
        }
        batch.end();
        drawOptions(w, h);
        if (managing) drawManage(w, h);
    }

    /** x, bottom, w, h, selected(1/0), then the label is not stored here. Text uses a parallel list. */
    private List<float[]> layColumn(List<BattlefieldDecks.Choice> rows, int selected, float scroll,
            float x, float listBottom, float colW, float listTop, List<float[]> hits) {
        List<float[]> laid = new ArrayList<>();
        float textW = Math.max(40f, colW - 28f);
        float rowsTop = listTop - 54f;
        float rowTop = rowsTop - scroll;
        for (int i = 0; i < rows.size(); i++) {
            BattlefieldDecks.Choice choice = rows.get(i);
            String label = choice == null || choice.label == null ? "" : choice.label;
            glyphs.setText(stackFont, label, Color.WHITE, textW, Align.left, true);
            float rh = Math.max(52f, glyphs.height + 22f);
            float bottom = rowTop - rh;
            if (rowTop > listBottom && bottom < rowsTop) {
                float drawBottom = Math.max(listBottom, bottom);
                float drawTop = Math.min(rowsTop, rowTop);
                float drawH = Math.max(0f, drawTop - drawBottom);
                if (drawH > 8f) {
                    boolean on = i == selected;
                    laid.add(new float[] { x + 8f, drawBottom, colW - 16f, drawH, on ? 1f : 0f });
                    hits.add(new float[] {
                            x + 8f + (colW - 16f) / 2f, drawBottom + drawH / 2f, colW - 16f, drawH, i
                    });
                }
            }
            rowTop = bottom - 8f;
        }
        return laid;
    }

    private void drawColumnChrome(float x, float y, float colW, float colH) {
        shapes.setColor(0.08f, 0.09f, 0.11f, 0.96f);
        shapes.rect(x, y, colW, colH);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 0.85f);
        strokeRect(x, y, colW, colH, 2f);
    }

    private void fillPickRow(float[] row) {
        boolean on = row[4] > 0.5f;
        shapes.setColor(on ? 0.20f : 0.12f, on ? 0.24f : 0.13f, on ? 0.16f : 0.15f, 1f);
        shapes.rect(row[0], row[1], row[2], row[3]);
        if (on) {
            shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
            strokeRect(row[0], row[1], row[2], row[3], 3f);
        }
    }

    private void drawColumnLabels(List<BattlefieldDecks.Choice> rows, float scroll,
            float x, float listBottom, float colW, float listTop) {
        float textW = Math.max(40f, colW - 28f);
        float rowsTop = listTop - 54f;
        float rowTop = rowsTop - scroll;
        batch.flush();
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor((int) x, (int) listBottom, (int) Math.max(1f, colW), (int) Math.max(1f, rowsTop - listBottom));
        for (BattlefieldDecks.Choice choice : rows) {
            String label = choice == null || choice.label == null ? "" : choice.label;
            glyphs.setText(stackFont, label, Color.WHITE, textW, Align.left, true);
            float rh = Math.max(52f, glyphs.height + 22f);
            float bottom = rowTop - rh;
            if (rowTop > listBottom && bottom < rowsTop) {
                stackFont.setColor(Color.WHITE);
                stackFont.draw(batch, label, x + 14f, rowTop - 8f, textW, Align.left, true);
            }
            rowTop = bottom - 8f;
        }
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    private void drawDeckNote(float h) {
        String title = "Decks";
        String body = pickNote == null ? "" : pickNote;
        float pad = 12f;
        float left = 16f;
        float boxW = 328f;
        float textW = boxW - pad * 2f;
        glyphs.setText(big, title);
        float titleH = glyphs.height;
        glyphs.setText(stackFont, body, Color.WHITE, textW, Align.left, true);
        float bodyH = glyphs.height;
        float boxH = pad + titleH + 12f + bodyH + pad;
        float boxTop = Math.min(h - 72f, h * 0.72f);
        float boxBottom = boxTop - boxH;
        if (boxBottom < 16f) {
            float shift = 16f - boxBottom;
            boxTop += shift;
            boxBottom += shift;
        }
        batch.setColor(0.07f, 0.08f, 0.09f, 0.94f);
        batch.draw(pixel(), left, boxBottom, boxW, boxH);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.9f);
        float edge = 2f;
        batch.draw(pixel(), left, boxBottom, boxW, edge);
        batch.draw(pixel(), left, boxTop - edge, boxW, edge);
        batch.draw(pixel(), left, boxBottom, edge, boxH);
        batch.draw(pixel(), left + boxW - edge, boxBottom, edge, boxH);
        batch.setColor(Color.WHITE);
        big.setColor(GOLD);
        big.draw(batch, title, left + pad, boxTop - pad);
        stackFont.setColor(new Color(0.95f, 0.93f, 0.86f, 1f));
        stackFont.draw(batch, body, left + pad, boxTop - pad - titleH - 8f, textW, Align.left, true);
        stackFont.setColor(Color.WHITE);
        big.setColor(Color.WHITE);
    }

    private void refreshLobby() {
        if (live != null && live.ready()) {
            lobbying = false;
            picking = false;
            starting = false;
            optionsNote = "Save writes this Forge match into the duel folder. "
                    + "Change decks starts a new match. Quit closes the window.";
        }
    }

    private void pressLobby() {
        if (live == null) return;
        if (lobbyBackW >= 8f && boxContains(lobbyBackCx, lobbyBackCy, lobbyBackW, lobbyBackH)) {
            leaveLobby();
            return;
        }
        if (live.lobbyPrompt()) {
            if (lobbyOkW >= 8f && boxContains(lobbyOkCx, lobbyOkCy, lobbyOkW, lobbyOkH)) live.pressOk();
            else if (lobbyNoW >= 8f && boxContains(lobbyNoCx, lobbyNoCy, lobbyNoW, lobbyNoH)) live.pressCancel();
            return;
        }
        if (lobbyStartW < 8f) return;
        if (boxContains(lobbyStartCx, lobbyStartCy, lobbyStartW, lobbyStartH)) live.requestStart();
    }

    private void drawLobby() {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        if (w < 1f || h < 1f) return;
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.05f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        shapes.setProjectionMatrix(batch.getProjectionMatrix());
        BattlefieldLive.LobbyCard card = live == null ? null : live.lobby();
        boolean answering = live != null && live.lobbyPrompt();
        boolean opening = live != null && live.matchOpening();
        lobbyStartW = 0f;
        lobbyOkW = 0f;
        lobbyNoW = 0f;
        lobbyBackW = 0f;
        boolean canStart = card != null && card.canStart && card.hostSide && !answering;
        lobbyStartW = canStart ? 240f : 0f;
        lobbyStartH = 56f;
        lobbyStartCx = w / 2f;
        lobbyStartCy = 72f;
        lobbyOkH = 56f;
        lobbyNoH = 56f;
        lobbyOkCy = 72f;
        lobbyNoCy = 72f;
        if (answering) {
            boolean yes = live.okOn();
            boolean no = live.cancelOn();
            float bw = 200f;
            float gap = 24f;
            if (yes && no) {
                lobbyOkW = bw;
                lobbyNoW = bw;
                lobbyOkCx = w / 2f - gap / 2f - bw / 2f;
                lobbyNoCx = w / 2f + gap / 2f + bw / 2f;
            } else if (yes) {
                lobbyOkW = 240f;
                lobbyOkCx = w / 2f;
            } else if (no) {
                lobbyNoW = 240f;
                lobbyNoCx = w / 2f;
            }
        }
        if (canStart || answering) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            if (canStart) {
                shapes.setColor(0.15f, 0.40f, 0.26f, 1f);
                shapes.rect(lobbyStartCx - lobbyStartW / 2f, lobbyStartCy - lobbyStartH / 2f, lobbyStartW, lobbyStartH);
                shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
                strokeRect(lobbyStartCx - lobbyStartW / 2f, lobbyStartCy - lobbyStartH / 2f, lobbyStartW, lobbyStartH, 3f);
            }
            if (lobbyOkW >= 8f) {
                shapes.setColor(0.15f, 0.40f, 0.26f, 1f);
                shapes.rect(lobbyOkCx - lobbyOkW / 2f, lobbyOkCy - lobbyOkH / 2f, lobbyOkW, lobbyOkH);
                shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
                strokeRect(lobbyOkCx - lobbyOkW / 2f, lobbyOkCy - lobbyOkH / 2f, lobbyOkW, lobbyOkH, 3f);
            }
            if (lobbyNoW >= 8f) {
                shapes.setColor(0.22f, 0.28f, 0.38f, 1f);
                shapes.rect(lobbyNoCx - lobbyNoW / 2f, lobbyNoCy - lobbyNoH / 2f, lobbyNoW, lobbyNoH);
                shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
                strokeRect(lobbyNoCx - lobbyNoW / 2f, lobbyNoCy - lobbyNoH / 2f, lobbyNoW, lobbyNoH, 3f);
            }
            shapes.end();
        }
        batch.begin();
        float y = h * 0.78f;
        String title = card == null || card.title == null || card.title.isBlank() ? "Hosting" : card.title;
        if (answering) {
            String asked = live.lobbyPromptLine();
            if (!asked.isBlank()) title = asked;
        } else if (opening && (card == null || card.problem == null || card.problem.isBlank())) {
            title = "The other player is choosing who goes first.";
        }
        boolean wideTitle = answering || opening || title.length() > 28;
        if (wideTitle) {
            float textW = w - 80f;
            glyphs.setText(stackFont, title, Color.WHITE, textW, Align.center, true);
            stackFont.setColor(GOLD);
            stackFont.draw(batch, title, 40f, y, textW, Align.center, true);
            stackFont.setColor(Color.WHITE);
            y -= glyphs.height + 18f;
        } else {
            drawCentered(big, title, w / 2f, y, GOLD);
            y -= 46f;
        }
        if (card != null && card.hostSide && card.address != null && !card.address.isBlank()
                && (card.problem == null || card.problem.isBlank())) {
            drawCentered(stackFont, card.address, w / 2f, y, Color.WHITE);
            y -= 32f;
            if (card.lan != null && !card.lan.isBlank()) {
                String tag = card.lan.startsWith("100.") ? "Tailscale " : "LAN ";
                drawCentered(stackFont, tag + card.lan + ":36743", w / 2f, y, new Color(0.78f, 0.78f, 0.7f, 1f));
                y -= 32f;
            }
        }
        if (!answering && !opening && card != null && card.wait != null && !card.wait.isBlank()) {
            drawCentered(stackFont, card.wait, w / 2f, y, Color.WHITE);
            y -= 40f;
        }
        if (card != null && card.problem != null && !card.problem.isBlank() && !card.problem.equals(title)) {
            drawCentered(stackFont, card.problem, w / 2f, y, new Color(0.95f, 0.55f, 0.4f, 1f));
            y -= 36f;
        }
        y -= 8f;
        if (!answering) {
            lobbyBackW = 168f;
            lobbyBackH = 48f;
            lobbyBackCx = w / 2f;
            lobbyBackCy = Math.max(150f, y - lobbyBackH / 2f);
            y = lobbyBackCy - lobbyBackH / 2f - 18f;
        }
        y = drawSeat(w, y, card == null ? "" : card.youName, card == null ? "" : card.youDeck);
        y = drawSeat(w, y, card == null ? "" : card.themName, card == null ? "" : card.themDeck);
        if (canStart) drawCentered(stackFont, "Start match", lobbyStartCx, lobbyStartCy + 10f, Color.WHITE);
        if (lobbyOkW >= 8f) drawCentered(stackFont, live.okText(), lobbyOkCx, lobbyOkCy + 10f, Color.WHITE);
        if (lobbyNoW >= 8f) drawCentered(stackFont, live.cancelText(), lobbyNoCx, lobbyNoCy + 10f, Color.WHITE);
        batch.end();
        if (lobbyBackW >= 8f) {
            Gdx.gl.glEnable(GL20.GL_BLEND);
            Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            shapes.begin(ShapeRenderer.ShapeType.Filled);
            shapes.setColor(0.16f, 0.28f, 0.34f, 1f);
            shapes.rect(lobbyBackCx - lobbyBackW / 2f, lobbyBackCy - lobbyBackH / 2f, lobbyBackW, lobbyBackH);
            shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
            strokeRect(lobbyBackCx - lobbyBackW / 2f, lobbyBackCy - lobbyBackH / 2f, lobbyBackW, lobbyBackH, 3f);
            shapes.end();
            batch.begin();
            drawCentered(stackFont, "Back", lobbyBackCx, lobbyBackCy + 10f, Color.WHITE);
            batch.end();
        }
        drawOptions(w, h);
    }

    private float drawSeat(float w, float y, String name, String deck) {
        if ((name == null || name.isBlank()) && (deck == null || deck.isBlank())) return y;
        if (name != null && !name.isBlank()) {
            drawCentered(big, name, w / 2f, y, Color.WHITE);
            y -= 36f;
        }
        if (deck != null && !deck.isBlank()) {
            drawCentered(stackFont, deck, w / 2f, y, new Color(0.95f, 0.93f, 0.86f, 1f));
            y -= 40f;
        }
        return y - 8f;
    }

    private void drawLoading() {
        float w = Gdx.graphics.getWidth(), h = Gdx.graphics.getHeight();
        if (w < 1f || h < 1f) return;
        Gdx.gl.glClearColor(0.05f, 0.06f, 0.05f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        batch.getProjectionMatrix().setToOrtho2D(0, 0, w, h);
        batch.begin();
        String line = live == null ? "Loading Forge cards. This takes a minute." : live.status;
        drawCentered(line, w / 2f, h / 2f, GOLD);
        batch.end();
        drawOptions(w, h);
    }

    /** One cue when the turn passes. His is low then high. Phase steps keep the same owner, so they stay silent. */
    private void noteTurn(BattlefieldLive.Pic pic) {
        if (pic.over) {
            turnKnown = false;
            return;
        }
        if (!turnKnown) {
            turnKnown = true;
            heardOwner = pic.owner;
            return;
        }
        if (pic.owner == heardOwner) return;
        heardOwner = pic.owner;
        DuelSounds.turnPassed(pic.owner == BattlefieldLayout.StripOwner.YOU);
    }

    /**
     * One slight beep when a new choice starts waiting on him.
     * The same sheet, the input under that sheet, Ash, and the victory buttons stay quiet.
     */
    private void noteChoice(BattlefieldLive.Pic pic) {
        if (pic.over) {
            heardChoice = null;
            choiceShield = null;
            return;
        }
        String specific = live.choiceWait();
        String input = live.inputWait();
        if (specific != null) choiceShield = input;
        String key = specific != null ? specific : input;
        if (key == null) {
            heardChoice = null;
            choiceShield = null;
            return;
        }
        if (specific == null && key.equals(choiceShield)) {
            heardChoice = key;
            return;
        }
        if (key.equals(heardChoice)) return;
        heardChoice = key;
        DuelSounds.choice();
    }

    private void applyLiveFrame() {
        if (liveReady() && live.holdLeftoverPaint()) return;
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null) return;
        shownStrikes = pic.strikes == null ? List.of() : new ArrayList<>(pic.strikes);
        matchOver = pic.over;
        if (pic.over) {
            resultTitle = pic.resultTitle == null ? "" : pic.resultTitle;
            resultWhy = pic.resultWhy == null ? "" : pic.resultWhy;
        }
        noteTurn(pic);
        noteChoice(pic);
        yourPool = copyPool(pic.yourPool);
        theirPool = copyPool(pic.theirPool);
        if (bashLeft > 0f) {
            heldPic = pic;
            gameLog.clear();
            gameLog.addAll(pic.log);
            devLog.clear();
            devLog.addAll(pic.decisions);
            debugLog.clear();
            debugLog.addAll(pic.debugDecisions);
            return;
        }
        if (shouldBash(pic) && startBash()) {
            heldPic = pic;
            gameLog.clear();
            gameLog.addAll(pic.log);
            devLog.clear();
            devLog.addAll(pic.decisions);
            debugLog.clear();
            debugLog.addAll(pic.debugDecisions);
            live.clearStrikes();
            return;
        }
        commitPic(pic);
        if (!stillAttacking(pic)) live.clearStrikes();
    }

    /**
     * Cyan edge for cards Forge says you can play or activate right now.
     * A bottom pick keeps the gold edge. Ghosts are only cards playable from outside the hand.
     */
    private void applyPlayable(BattlefieldLive.Pic pic) {
        Map<Integer, Boolean> handPlay = new HashMap<>();
        for (BattlefieldLive.Face face : pic.hand) {
            if (face.view != null) handPlay.put(face.view.getId(), face.playable);
        }
        for (HandCard card : hand) {
            boolean on = card.forgeCard != null && Boolean.TRUE.equals(handPlay.get(card.forgeCard.getId()));
            card.playable = on && !card.bottomPick;
        }
        Map<Integer, Boolean> exilePlay = new HashMap<>();
        for (BattlefieldLive.Face face : pic.yourExile) {
            if (face.view != null) exilePlay.put(face.view.getId(), face.playable);
        }
        for (ZoneCard card : yourExile) {
            card.playable = card.forgeCard != null && Boolean.TRUE.equals(exilePlay.get(card.forgeCard.getId()));
        }
        for (ZoneCard card : theirExile) card.playable = false;
        Map<Integer, Boolean> boardPlay = new HashMap<>();
        for (BattlefieldLive.Face face : pic.yourBoard) {
            if (face.view != null) boardPlay.put(face.view.getId(), face.playable);
        }
        for (BattlefieldLive.Face face : pic.theirBoard) {
            if (face.view != null) boardPlay.put(face.view.getId(), face.playable);
        }
        for (Perm perm : board) {
            boolean on = perm.forgeCard != null && Boolean.TRUE.equals(boardPlay.get(perm.forgeCard.getId()));
            perm.playable = on && (perm.you || perm.kind == BattlefieldLayout.Kind.BATTLE);
        }
        if (pressGhost < 0) syncGhosts(pic);
    }

    /** Keep a ghost that is already on screen. No playable card outside the hand means an empty fan. */
    private void syncGhosts(BattlefieldLive.Pic pic) {
        boolean wipe = pic != null && pic.wipeHand;
        if (wipe) dropFan(ghosts);
        List<BattlefieldLive.Face> faces = pic == null ? List.of() : pic.ghosts;
        Map<Integer, HandCard> kept = new HashMap<>();
        if (!wipe) {
            for (HandCard card : ghosts) {
                if (card.forgeCard != null) kept.put(card.forgeCard.getId(), card);
            }
        }
        List<HandCard> next = new ArrayList<>();
        if (faces != null) {
            for (BattlefieldLive.Face face : faces) {
                HandCard card = face.view == null ? null : kept.remove(face.view.getId());
                if (card == null) card = handFrom(face);
                else copyFace(card, face);
                card.alive = true;
                card.parked = false;
                card.playable = true;
                next.add(card);
            }
        }
        if (!wipe) {
            keepStillHeld(next);
            for (HandCard left : kept.values()) dropOrLiftDeparted(left, pic, true);
        }
        ghosts.clear();
        ghosts.addAll(next);
    }

    /** Mulligan: drop leftover tiles from the last seven. No travel overlay. */
    private void dropFan(List<HandCard> fan) {
        if (fan == null) return;
        for (HandCard card : fan) {
            if (card == null || card.forgeCard == null) continue;
            int id = card.forgeCard.getId();
            abortTravel(id);
            departedHand.remove(id);
            card.alive = false;
            card.parked = true;
        }
        fan.clear();
    }

    /** Cards Forge has marked to go on the bottom. Updated even when the board itself did not change. */
    private void markBottomPicks(BattlefieldLive.Pic pic) {
        java.util.Set<Integer> picked = new java.util.HashSet<>();
        for (BattlefieldLive.Face face : pic.hand) {
            if (face.marked && face.view != null) picked.add(face.view.getId());
        }
        for (HandCard card : hand) {
            card.bottomPick = card.forgeCard != null && picked.contains(card.forgeCard.getId());
        }
    }

    private void commitPic(BattlefieldLive.Pic pic) {
        youName = pic.youName == null ? "" : pic.youName;
        themName = pic.themName == null ? "" : pic.themName;
        yourLife = pic.yourLife;
        theirLife = pic.theirLife;
        yourHandCount = pic.yourHandCount;
        theirHandCount = pic.theirHandCount;
        yourPool = copyPool(pic.yourPool);
        theirPool = copyPool(pic.theirPool);
        yourPoison = 0;
        theirPoison = 0;
        if (pic.yourStartingLife > 0) yourStartingLife = pic.yourStartingLife;
        if (pic.theirStartingLife > 0) theirStartingLife = pic.theirStartingLife;
        yourCommanderDamage.clear();
        theirCommanderDamage.clear();
        yourCommanderDamage.addAll(pic.yourCommanderDamage);
        theirCommanderDamage.addAll(pic.theirCommanderDamage);
        yourCommand = pic.yourCommand;
        theirCommand = pic.theirCommand;
        activeOwner = pic.owner;
        shownPhase = pic.phase;
        yourTopRevealed = false;
        gameLog.clear();
        gameLog.addAll(pic.log);
        devLog.clear();
        devLog.addAll(pic.decisions);
        debugLog.clear();
        debugLog.addAll(pic.debugDecisions);
        markBottomPicks(pic);
        if (pic.prompt != null && !pic.prompt.isBlank()) note = pic.prompt;
        if ((pic.wipeHand || pic.rebuildBoards || pic.signature != liveSig)
                && !draggingHand && !draggingBoard && !grippingTop
                && !draggingCommand) {
            liveSig = pic.signature;
            rebuildLive(pic);
        }
        applyPlayable(pic);
        holdPayingCast(pic);
        markPaySeen();
        reclaimCancelled();
        dropAwayTravels(pic);
        dropLeftStackPaint(pic);
    }

    private void tickBash(float dt) {
        if (bashLeft <= 0f) return;
        bashLeft -= dt;
        if (bashLeft > 0f) return;
        bashLeft = 0f;
        bashes.clear();
        if (heldPic != null) {
            BattlefieldLive.Pic pic = heldPic;
            heldPic = null;
            commitPic(pic);
            if (!stillAttacking(pic)) live.clearStrikes();
        }
    }

    private boolean shouldBash(BattlefieldLive.Pic pic) {
        if (pic.strikes.isEmpty() || draggingHand || draggingBoard || grippingTop) return false;
        if (pic.yourLife != yourLife || pic.theirLife != theirLife) return true;
        Map<Integer, BattlefieldLive.Face> now = new HashMap<>();
        for (BattlefieldLive.Face face : pic.yourBoard) {
            if (face.view != null) now.put(face.view.getId(), face);
        }
        for (BattlefieldLive.Face face : pic.theirBoard) {
            if (face.view != null) now.put(face.view.getId(), face);
        }
        for (Perm perm : board) {
            if (!perm.alive || perm.forgeCard == null) continue;
            boolean walker = perm.kind == BattlefieldLayout.Kind.PLANESWALKER;
            boolean battle = perm.kind == BattlefieldLayout.Kind.BATTLE;
            boolean creature = perm.kind == BattlefieldLayout.Kind.CREATURE;
            if (!walker && !battle && !creature) continue;
            BattlefieldLive.Face face = now.get(perm.forgeCard.getId());
            if (face == null) return true;
            if (walker && perm.loyalty != face.loyalty) return true;
            if (battle && perm.defense != face.defense) return true;
            if (creature && perm.damage != face.damage) return true;
        }
        return false;
    }

    private boolean stillAttacking(BattlefieldLive.Pic pic) {
        for (BattlefieldLive.Face face : pic.yourBoard) if (face.attacking) return true;
        for (BattlefieldLive.Face face : pic.theirBoard) if (face.attacking) return true;
        return false;
    }

    /** Lunge from the current pose. Life steps down on each impact. The tapped pose waits until the last return. */
    private boolean startBash() {
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null || pic.strikes.isEmpty()) return false;
        float w = Math.max(1f, Gdx.graphics.getWidth());
        float h = Math.max(1f, Gdx.graphics.getHeight());
        BattlefieldLayout.PlayerCluster you = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.YOU, youDx, youDy);
        BattlefieldLayout.PlayerCluster them = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.ENEMY, enemyDx, enemyDy);
        placeRow(w, h, false, BattlefieldLayout.Row.LAND);
        placeRow(w, h, false, BattlefieldLayout.Row.TOKEN);
        placeRow(w, h, false, BattlefieldLayout.Row.PLANESWALKER);
        placeRow(w, h, false, BattlefieldLayout.Row.BATTLE);
        placeRow(w, h, false, BattlefieldLayout.Row.ENCHANTMENT);
        placeRow(w, h, false, BattlefieldLayout.Row.OTHER);
        placeRow(w, h, false, BattlefieldLayout.Row.CREATURE);
        placeRow(w, h, true, BattlefieldLayout.Row.LAND);
        placeRow(w, h, true, BattlefieldLayout.Row.TOKEN);
        placeRow(w, h, true, BattlefieldLayout.Row.PLANESWALKER);
        placeRow(w, h, true, BattlefieldLayout.Row.BATTLE);
        placeRow(w, h, true, BattlefieldLayout.Row.ENCHANTMENT);
        placeRow(w, h, true, BattlefieldLayout.Row.OTHER);
        placeRow(w, h, true, BattlefieldLayout.Row.CREATURE);
        tuckAttachedAuras();
        wireBlockTargets();
        bashes.clear();
        for (BattlefieldLive.Strike strike : pic.strikes) {
            Perm attacker = permById(strike.attackerId);
            if (attacker == null || !attacker.alive) continue;
            float homeGap = (float) Math.hypot(attacker.cx - attacker.tx, attacker.cy - attacker.ty);
            if ((attacker.cx < 2f && attacker.cy < 2f) || homeGap > Math.max(48f, attacker.th) * 0.85f) {
                attacker.cx = attacker.tx;
                attacker.cy = attacker.ty;
            }
            List<Perm> blockers = clashBlockers(attacker, strike);
            if (!blockers.isEmpty()) {
                boolean first = true;
                for (Perm blocker : blockers) {
                    snapIfUnplaced(blocker);
                    float x1 = blocker.cx < 2f && blocker.cy < 2f ? blocker.tx : blocker.cx;
                    float y1 = blocker.cx < 2f && blocker.cy < 2f ? blocker.ty : blocker.cy;
                    Bash bash = aimBash(attacker, blocker, x1, y1, false, false);
                    bash.impactOnly = !first;
                    first = false;
                    bashes.add(bash);
                }
                continue;
            }
            Perm planeswalker = strike.planeswalkerId == 0 ? null : permById(strike.planeswalkerId);
            snapIfUnplaced(planeswalker);
            float x1;
            float y1;
            if (planeswalker != null) {
                x1 = planeswalker.cx < 2f && planeswalker.cy < 2f ? planeswalker.tx : planeswalker.cx;
                y1 = planeswalker.cx < 2f && planeswalker.cy < 2f ? planeswalker.ty : planeswalker.cy;
            } else if (strike.defenderIsYou) {
                x1 = you.life.x;
                y1 = you.life.y;
            } else {
                x1 = them.life.x;
                y1 = them.life.y;
            }
            bashes.add(aimBash(attacker, planeswalker, x1, y1,
                    planeswalker == null && strike.defenderIsYou,
                    planeswalker == null && !strike.defenderIsYou));
        }
        if (bashes.isEmpty()) return false;
        separateLifeStrikes();
        for (int i = 0; i < bashes.size(); i++) bashes.get(i).delay = i * BASH_STAGGER;
        assignLifeHits(pic);
        bashDur = BASH_MOTION + Math.max(0, bashes.size() - 1) * BASH_STAGGER;
        bashLeft = bashDur;
        return true;
    }

    /** Split the life change across attackers that hit that player, weighted by power. */
    private void assignLifeHits(BattlefieldLive.Pic pic) {
        splitLife(true, yourLife - pic.yourLife);
        splitLife(false, theirLife - pic.theirLife);
    }

    private void splitLife(boolean you, int loss) {
        if (loss <= 0) return;
        List<Bash> hits = new ArrayList<>();
        for (Bash bash : bashes) {
            if (you ? bash.hitsYou : bash.hitsThem) hits.add(bash);
        }
        int n = hits.size();
        if (n == 0) return;
        int[] weight = new int[n];
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int w = Math.max(1, power(hits.get(i).perm));
            weight[i] = w;
            sum += w;
        }
        int[] share = new int[n];
        double[] frac = new double[n];
        int given = 0;
        for (int i = 0; i < n; i++) {
            double exact = loss * (weight[i] / (double) sum);
            share[i] = (int) Math.floor(exact);
            frac[i] = exact - share[i];
            given += share[i];
        }
        boolean[] used = new boolean[n];
        int left = loss - given;
        while (left > 0) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (used[i]) continue;
                if (best < 0 || frac[i] > frac[best]) best = i;
            }
            if (best < 0) {
                for (int i = 0; i < n; i++) used[i] = false;
                continue;
            }
            used[best] = true;
            share[best]++;
            left--;
        }
        int shown = you ? yourLife : theirLife;
        for (int i = 0; i < n; i++) {
            shown -= share[i];
            hits.get(i).lifeAfter = shown;
            hits.get(i).dropsLife = share[i] > 0;
        }
    }

    private static Bash aimBash(Perm attacker, Perm target, float x1, float y1,
            boolean hitsYou, boolean hitsThem) {
        Bash bash = new Bash();
        bash.perm = attacker;
        bash.target = target;
        bash.hitsYou = hitsYou;
        bash.hitsThem = hitsThem;
        bash.baseRot = attacker.rot;
        bash.x0 = attacker.cx;
        bash.y0 = attacker.cy;
        bash.x1 = x1;
        bash.y1 = y1;
        return bash;
    }

    /** Every assigned blocker, not only the first id on the strike. */
    private List<Perm> clashBlockers(Perm attacker, BattlefieldLive.Strike strike) {
        List<Perm> out = new ArrayList<>();
        HashSet<Integer> seen = new HashSet<>();
        if (strike != null && strike.blockerId != 0) {
            Perm first = permById(strike.blockerId);
            if (first != null && first.alive) {
                out.add(first);
                if (first.forgeCard != null) seen.add(first.forgeCard.getId());
            }
        }
        int attackerId = attacker != null && attacker.forgeCard != null ? attacker.forgeCard.getId() : 0;
        for (Perm perm : board) {
            if (perm == null || !perm.alive || perm == attacker) continue;
            boolean linked = perm.blockTarget == attacker;
            if (!linked && attackerId != 0 && perm.blockAttackerIds != null) {
                for (int id : perm.blockAttackerIds) {
                    if (id == attackerId) {
                        linked = true;
                        break;
                    }
                }
            }
            if (!linked) continue;
            int id = perm.forgeCard == null ? 0 : perm.forgeCard.getId();
            if (id != 0 && !seen.add(id)) continue;
            out.add(perm);
        }
        return out;
    }

    /** Shake, life step, and thud share this beat. */
    private void fireImpact(Bash bash) {
        if (bash == null || bash.shook) return;
        bash.shook = true;
        DuelSounds.clash();
        if (bash.perm != null) bash.perm.thunk = 1f;
        if (bash.target != null) bash.target.thunk = 1f;
        if (bash.dropsLife) {
            if (bash.hitsYou) yourLife = bash.lifeAfter;
            else if (bash.hitsThem) theirLife = bash.lifeAfter;
        }
    }

    /** A new permanent still at the corner takes its slot before the wind-up. */
    private static void snapIfUnplaced(Perm perm) {
        if (perm == null) return;
        if (perm.cx < 2f && perm.cy < 2f && (Math.abs(perm.tx) > 1f || Math.abs(perm.ty) > 1f)) {
            perm.cx = perm.tx;
            perm.cy = perm.ty;
        }
    }

    /** Unblocked attackers share a life block. Offset each contact so the lunges stay apart. */
    private void separateLifeStrikes() {
        int n = bashes.size();
        for (int pass = 0; pass < 2; pass++) {
            boolean you = pass == 0;
            List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Bash bash = bashes.get(i);
                if (you ? bash.hitsYou : bash.hitsThem) idx.add(i);
            }
            int group = idx.size();
            if (group < 2) continue;
            for (int k = 0; k < group; k++) {
                Bash bash = bashes.get(idx.get(k));
                float dx = bash.x1 - bash.x0;
                float dy = bash.y1 - bash.y0;
                float len = (float) Math.hypot(dx, dy);
                if (len < 1f) continue;
                float shift = (k - (group - 1) * 0.5f) * 72f;
                bash.x1 += (-dy / len) * shift;
                bash.y1 += (dx / len) * shift;
            }
        }
    }

    /** Rear back, hold, strike, shake on contact, then return. Starts from the board slot. */
    private void pinBash() {
        youImpactX = 0f;
        youImpactY = 0f;
        themImpactX = 0f;
        themImpactY = 0f;
        if (bashLeft <= 0f || bashes.isEmpty() || bashDur <= 0f) return;
        float elapsed = bashDur - Math.max(0f, bashLeft);
        // 0.88s rear, 0.19s hold, 0.16s strike, 0.24s shake, 0.28s contact, 0.34s return.
        float rearEnd = BASH_REAR / BASH_MOTION;
        float holdEnd = BASH_HOLD / BASH_MOTION;
        float strikeEnd = BASH_STRIKE / BASH_MOTION;
        float shakeEnd = BASH_SHAKE / BASH_MOTION;
        float pauseEnd = BASH_PAUSE / BASH_MOTION;
        for (Bash bash : bashes) {
            if (bash.perm == null && !bash.impactOnly) continue;
            float local = elapsed - bash.delay;
            if (bash.impactOnly) {
                if (local >= BASH_STRIKE) fireImpact(bash);
                continue;
            }
            if (bash.perm == null) continue;
            if (local <= 0f) {
                bash.perm.cx = bash.x0;
                bash.perm.cy = bash.y0;
                bash.perm.rot = bash.baseRot;
                continue;
            }
            float t = Math.min(1f, local / BASH_MOTION);
            float dx = bash.x1 - bash.x0;
            float dy = bash.y1 - bash.y0;
            float len = (float) Math.hypot(dx, dy);
            if (len < 1f) {
                if (t >= strikeEnd) fireImpact(bash);
                continue;
            }
            float ux = dx / len;
            float uy = dy / len;
            float px = -uy;
            float py = ux;
            // Rotation 0 is upright, name-end toward +Y. Point that end along the strike.
            float facing = (float) Math.toDegrees(Math.atan2(uy, ux)) - 90f;
            float card = Math.max(48f, bash.perm.h);
            float rear = Math.min(card * 0.62f, len * 0.22f);
            float strike = len - card * 0.38f;
            if (strike < len * 0.62f) strike = len * 0.78f;
            float along;
            float tilt;
            float shake = 0f;
            float arc = 0f;
            if (t < rearEnd) {
                float u = smoothStep(t / rearEnd);
                along = -rear * u;
                tilt = -18f * u;
            } else if (t < holdEnd) {
                along = -rear;
                tilt = -18f;
            } else if (t < strikeEnd) {
                float u = easeIn((t - holdEnd) / (strikeEnd - holdEnd));
                along = -rear + (strike + rear) * u;
                tilt = -18f + 26f * u;
                arc = (float) Math.sin(u * Math.PI) * 16f;
            } else if (t < shakeEnd) {
                float u = (t - strikeEnd) / (shakeEnd - strikeEnd);
                float decay = 1f - u;
                shake = (float) Math.sin(u * Math.PI * 5.0) * 11f * decay;
                along = strike;
                tilt = 8f * (float) Math.sin(u * Math.PI * 4.0) * decay;
                fireImpact(bash);
                float ix = ux * shake + px * shake * 0.55f;
                float iy = uy * shake + py * shake * 0.55f;
                if (bash.hitsYou) {
                    youImpactX = ix;
                    youImpactY = iy;
                } else if (bash.hitsThem) {
                    themImpactX = ix;
                    themImpactY = iy;
                }
            } else if (t < pauseEnd) {
                along = strike;
                tilt = 0f;
            } else {
                float span = Math.max(0.01f, 1f - pauseEnd);
                float u = smoothStep((t - pauseEnd) / span);
                along = strike * (1f - u);
                tilt = 0f;
                float home = bash.perm.tapped ? bash.perm.trot : bash.baseRot;
                bash.perm.rot = lerpAngle(facing, home, u);
            }
            bash.perm.cx = bash.x0 + ux * along + px * (arc + shake * 0.35f);
            bash.perm.cy = bash.y0 + uy * along + py * (arc + shake * 0.35f);
            if (t < pauseEnd) bash.perm.rot = facing + tilt;
        }
    }

    /** Shortest turn from {@code from} toward {@code to}. */
    private static float lerpAngle(float from, float to, float u) {
        float delta = to - from;
        while (delta > 180f) delta -= 360f;
        while (delta < -180f) delta += 360f;
        return from + delta * u;
    }

    private static float smoothStep(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    /** Accelerates into contact so the lunge does not ease to a stop like a slide. */
    private static float easeIn(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * t;
    }

    private Perm permById(int id) {
        if (id == 0) return null;
        for (Perm perm : board) {
            if (perm.alive && perm.forgeCard != null && perm.forgeCard.getId() == id) return perm;
        }
        return null;
    }

    private void rebuildLive(BattlefieldLive.Pic pic) {
        arrivalSlot = 0;
        syncHand(pic);
        if (pressGhost < 0) syncGhosts(pic);
        syncBoard(pic);
        fillFaces(yourLibrary, pic.yourLibrary);
        fillFaces(theirLibrary, pic.theirLibrary);
        fillFaces(yourGrave, pic.yourGrave);
        fillFaces(theirGrave, pic.theirGrave);
        fillFaces(yourExile, pic.yourExile);
        fillFaces(theirExile, pic.theirExile);
        stack.clear();
        for (BattlefieldLive.Spell spell : pic.stack) {
            StackItem item = new StackItem();
            item.id = spell.id;
            item.name = spell.name == null ? "" : spell.name;
            item.fullText = spell.text == null ? "" : spell.text;
            item.typeLine = spell.typeLine == null ? "" : spell.typeLine;
            item.cost = spell.cost == null ? "" : spell.cost;
            item.pt = spell.pt == null ? "" : spell.pt;
            item.rules = spell.rules == null ? "" : spell.rules;
            item.targetLabel = spell.targetLabel == null ? "" : spell.targetLabel;
            item.targetCardIds = spell.targetCardIds == null ? new int[0] : spell.targetCardIds.clone();
            item.targetYou = spell.targetYou;
            item.targetThem = spell.targetThem;
            item.sourceId = spell.sourceId;
            item.sourceOnBoard = spell.sourceOnBoard;
            item.sourceYours = spell.sourceYours;
            item.forgeCard = spell.source;
            HandCard card = new HandCard();
            card.name = item.name;
            card.tex = item.name.isBlank() ? null : textureFor(item.name);
            card.forgeCard = spell.source;
            item.card = card;
            stack.add(item);
            if (!startHandCastFlight(item, spell)) noteSpell(item, spell);
        }
        startHandBoardFlights();
        pruneDeparted();
        syncEmblems(yourEmblems, pic.yourMarks);
        arrivalsArmed = true;
        syncEmblems(theirEmblems, pic.theirMarks);
    }

    private static void syncEmblems(List<Emblem> into, List<BattlefieldLive.Mark> marks) {
        into.clear();
        if (marks == null) return;
        for (BattlefieldLive.Mark mark : marks) {
            if (mark == null || mark.name == null || mark.name.isBlank()) continue;
            Emblem emblem = new Emblem();
            emblem.name = mark.name;
            emblem.text = mark.text == null ? "" : mark.text;
            into.add(emblem);
        }
    }

    private void fillFaces(List<ZoneCard> zone, List<BattlefieldLive.Face> faces) {
        zone.clear();
        for (BattlefieldLive.Face face : faces) {
            boolean hidden = face.hiddenBack;
            String name = hidden
                    ? (face.name == null || face.name.isBlank() ? "Card" : face.name)
                    : (face.faceUp ? face.name : "Card");
            boolean know = hidden && face.name != null && !face.name.isBlank();
            ZoneCard card = new ZoneCard(name, face.land ? "Land" : "Card",
                    face.faceUp || know ? paperFor(name) : null);
            card.revealed = face.faceUp;
            card.hiddenBack = hidden;
            card.playable = face.playable;
            card.ownerSees = know;
            card.forgeCard = face.view;
            zone.add(card);
        }
    }

    /** Keep a card that is already on screen. A new card is the only one that starts at the corner. */
    private void syncHand(BattlefieldLive.Pic pic) {
        boolean wipe = pic != null && pic.wipeHand;
        if (wipe) dropFan(hand);
        List<BattlefieldLive.Face> faces = pic == null ? List.of() : pic.hand;
        Map<Integer, HandCard> kept = new HashMap<>();
        if (!wipe) {
            for (HandCard card : hand) {
                if (card.forgeCard != null) kept.put(card.forgeCard.getId(), card);
            }
        }
        List<HandCard> next = new ArrayList<>();
        for (BattlefieldLive.Face face : faces) {
            HandCard card = face.view == null ? null : kept.remove(face.view.getId());
            if (card == null) card = handFrom(face);
            else copyFace(card, face);
            next.add(card);
        }
        if (!wipe) {
            keepStillHeld(next);
            for (HandCard left : kept.values()) dropOrLiftDeparted(left, pic, false);
        }
        hand.clear();
        hand.addAll(next);
    }

    /** The same snapshot already has this card on the stack or his battlefield. */
    private static boolean playedFromHand(HandCard card, BattlefieldLive.Pic pic) {
        if (card == null || card.forgeCard == null || pic == null) return false;
        int id = card.forgeCard.getId();
        if (id == 0) return false;
        for (BattlefieldLive.Spell spell : pic.stack) {
            if (spell != null && spell.sourceId == id) return true;
        }
        for (BattlefieldLive.Face face : pic.yourBoard) {
            if (face != null && face.view != null && face.view.getId() == id) return true;
        }
        return false;
    }

    private void syncBoard(BattlefieldLive.Pic pic) {
        Map<Integer, Perm> kept = new HashMap<>();
        boolean rebuild = pic != null && (pic.rebuildBoards || pic.wipeHand);
        if (!rebuild) {
            for (Perm perm : board) {
                if (perm.forgeCard != null) kept.put(perm.forgeCard.getId(), perm);
            }
        } else {
            for (Perm perm : board) {
                if (perm != null && perm.forgeCard != null) abortTravel(perm.forgeCard.getId());
            }
        }
        List<Perm> next = new ArrayList<>();
        for (BattlefieldLive.Face face : pic.yourBoard) {
            if (boardFace(face)) next.add(keepPerm(kept, face, true));
        }
        for (BattlefieldLive.Face face : pic.theirBoard) {
            if (boardFace(face)) next.add(keepPerm(kept, face, false));
        }
        for (Perm gone : kept.values()) {
            if (gone != null && gone.you && gone.alive && gone.kind == BattlefieldLayout.Kind.CREATURE) {
                noteDeath(gone);
            }
        }
        board.clear();
        board.addAll(next);
        wireBlockTargets();
        wireAuraHosts();
    }

    /**
     * An attached aura or equipment leaves the artifact/enchantment pocket and
     * tucks behind its host. Unequipped gear returns to that pocket.
     */
    private void wireAuraHosts() {
        Map<Integer, Perm> byId = new HashMap<>();
        for (Perm perm : board) {
            if (perm.forgeCard != null) byId.put(perm.forgeCard.getId(), perm);
        }
        for (Perm perm : board) {
            Perm host = perm.attachedToId == 0 ? null : byId.get(perm.attachedToId);
            if (host == perm || host == null || !host.alive) host = null;
            boolean ridesHost = host != null && (perm.aura || perm.equipment
                    || perm.kind == BattlefieldLayout.Kind.ENCHANTMENT
                    || perm.kind == BattlefieldLayout.Kind.ARTIFACT);
            perm.auraHost = ridesHost ? host : null;
        }
    }

    /**
     * Park each attached aura or equipment on its host, one title band past the
     * host's up edge. Extra attached cards fan so each name still shows. The host
     * is drawn on top and keeps the card-body hit.
     */
    private void tuckAttachedAuras() {
        for (Perm perm : board) perm.tucked = false;
        boolean progressed = true;
        int guard = 0;
        while (progressed && guard++ < 8) {
            progressed = false;
            Map<Perm, List<Perm>> byHost = new LinkedHashMap<>();
            for (Perm perm : board) {
                if (perm.tucked || !perm.alive || perm.auraHost == null || !perm.auraHost.alive) continue;
                Perm host = perm.auraHost;
                boolean hostReady = host.tw >= 1f && host.th >= 1f
                        && (host.auraHost == null || !host.auraHost.alive || host.tucked);
                if (!hostReady) continue;
                byHost.computeIfAbsent(host, k -> new ArrayList<>()).add(perm);
            }
            for (List<Perm> auras : byHost.values()) {
                auras.sort((a, b) -> Integer.compare(auraOrder(a), auraOrder(b)));
                Perm host = auras.get(0).auraHost;
                float peek = titlePeek(host);
                for (int i = 0; i < auras.size(); i++) {
                    Perm aura = auras.get(i);
                    HandLayout.Pose pose = BattlefieldLayout.auraBehindHost(
                            host.tx, host.ty, host.tw, host.th, host.trot, i, peek);
                    aura.tx = pose.cx;
                    aura.ty = pose.cy;
                    aura.tw = pose.w;
                    aura.th = pose.h;
                    aura.trot = pose.rotationDeg;
                    aura.tucked = true;
                    aura.tuckIndex = i;
                    aura.tuckPeek = peek;
                    aura.flyLift = 0f;
                    aura.pileCount = 1;
                    aura.pileFront = true;
                    aura.pileLayer = 0;
                }
                progressed = true;
            }
        }
    }

    private static int auraOrder(Perm aura) {
        return aura.forgeCard == null ? 0 : aura.forgeCard.getId();
    }

    private static float titlePeek(Perm host) {
        float tile = host.th > 1f ? host.th : host.h;
        float min = host.kind == BattlefieldLayout.Kind.LAND ? 14f : 16f;
        return Math.max(min, tile * 0.24f);
    }

    /** Point each assigned blocker at the attacker Forge recorded. A rejected block has no id. */
    private void wireBlockTargets() {
        Map<Integer, Perm> byId = new HashMap<>();
        for (Perm perm : board) {
            if (perm.forgeCard != null) byId.put(perm.forgeCard.getId(), perm);
        }
        for (Perm perm : board) {
            Perm target = null;
            if (perm.blockAttackerIds != null) {
                for (int id : perm.blockAttackerIds) {
                    Perm atk = byId.get(id);
                    if (atk != null && atk.alive) {
                        target = atk;
                        break;
                    }
                }
            }
            perm.blockTarget = target;
            if (target != null) perm.blocking = true;
        }
    }

    private void copyFace(HandCard card, BattlefieldLive.Face face) {
        card.name = face.faceUp ? face.name : "Card";
        card.kind = kindOf(face);
        card.power = face.power;
        card.toughness = face.toughness;
        card.instant = card.kind == BattlefieldLayout.Kind.SPELL;
        if (face.faceUp && card.tex == null) card.tex = textureFor(face.name);
        card.forgeCard = face.view;
        card.keywords = face.keywords;
        card.commander = face.commander;
        card.commanderKey = face.commanderKey == null ? "" : face.commanderKey;
    }

    private Perm keepPerm(Map<Integer, Perm> kept, BattlefieldLive.Face face, boolean you) {
        Perm perm = face.view == null ? null : kept.remove(face.view.getId());
        if (perm == null) return permFrom(face, you);
        perm.you = you;
        String shown = face.faceUp ? (face.name == null || face.name.isBlank() ? "" : face.name) : "Card";
        if (face.faceUp && shown.isBlank()) shown = "Card";
        boolean nameChanged = perm.name == null || !perm.name.equals(shown);
        perm.name = shown;
        perm.kind = kindOf(face);
        perm.tapped = face.tapped;
        perm.sick = face.sick;
        perm.attacking = face.attacking;
        perm.blocking = face.blocking;
        perm.blockAttackerIds = face.blockAttackerIds;
        perm.power = face.power;
        perm.toughness = face.toughness;
        perm.printedPower = face.printedPower;
        perm.printedToughness = face.printedToughness;
        perm.hasPrinted = face.hasPrinted;
        perm.loyalty = face.loyalty;
        perm.printedLoyalty = face.printedLoyalty;
        perm.hasLoyalty = face.hasLoyalty;
        perm.defense = face.defense;
        perm.printedDefense = face.printedDefense;
        perm.hasDefense = face.hasDefense;
        perm.protectYou = face.protectYou;
        perm.damage = face.damage;
        perm.restraint = face.restraint;
        perm.attachedToId = face.attachedToId;
        perm.aura = face.aura;
        perm.equipment = face.equipment;
        perm.token = face.token;
        perm.typeLine = typeLineOf(face.view);
        perm.lore = loreOf(face.view);
        if (face.faceUp) {
            if (nameChanged) perm.tex = null;
            Texture art = face.token ? tokenTextureFor(face.name) : textureFor(face.name);
            if (art != null) perm.tex = art;
        }
        perm.forgeCard = face.view;
        perm.keywords = face.keywords;
        perm.status = face.status == null ? java.util.List.of() : face.status;
        perm.choice = chosenTypeOn(face);
        perm.commander = face.commander;
        perm.commanderKey = face.commanderKey == null ? "" : face.commanderKey;
        perm.instant = face.instant;
        perm.sorcery = face.sorcery;
        if (perm.frame == null) perm.frame = borderFor(perm.name, perm.kind);
        return perm;
    }

    private HandCard handFrom(BattlefieldLive.Face face) {
        HandCard card = new HandCard();
        card.name = face.faceUp ? face.name : "Card";
        card.kind = kindOf(face);
        card.power = face.power;
        card.toughness = face.toughness;
        card.instant = card.kind == BattlefieldLayout.Kind.SPELL;
        card.tex = face.faceUp ? textureFor(face.name) : null;
        card.forgeCard = face.view;
        card.keywords = face.keywords;
        card.commander = face.commander;
        card.commanderKey = face.commanderKey == null ? "" : face.commanderKey;
        return card;
    }

    private Perm permFrom(BattlefieldLive.Face face, boolean you) {
        Perm perm = new Perm();
        perm.you = you;
        perm.name = face.faceUp ? face.name : "Card";
        perm.kind = kindOf(face);
        perm.tapped = face.tapped;
        perm.sick = face.sick;
        perm.attacking = face.attacking;
        perm.blocking = face.blocking;
        perm.blockAttackerIds = face.blockAttackerIds;
        perm.power = face.power;
        perm.toughness = face.toughness;
        perm.printedPower = face.printedPower;
        perm.printedToughness = face.printedToughness;
        perm.hasPrinted = face.hasPrinted;
        perm.loyalty = face.loyalty;
        perm.printedLoyalty = face.printedLoyalty;
        perm.hasLoyalty = face.hasLoyalty;
        perm.defense = face.defense;
        perm.printedDefense = face.printedDefense;
        perm.hasDefense = face.hasDefense;
        perm.protectYou = face.protectYou;
        perm.damage = face.damage;
        perm.restraint = face.restraint;
        perm.attachedToId = face.attachedToId;
        perm.aura = face.aura;
        perm.equipment = face.equipment;
        perm.token = face.token;
        perm.typeLine = typeLineOf(face.view);
        perm.lore = loreOf(face.view);
        perm.tex = face.faceUp ? (face.token ? tokenTextureFor(face.name) : textureFor(face.name)) : null;
        perm.forgeCard = face.view;
        perm.keywords = face.keywords;
        perm.status = face.status == null ? java.util.List.of() : face.status;
        perm.choice = chosenTypeOn(face);
        perm.commander = face.commander;
        perm.commanderKey = face.commanderKey == null ? "" : face.commanderKey;
        perm.instant = face.instant;
        perm.sorcery = face.sorcery;
        perm.frame = borderFor(perm.name, perm.kind);
        noteArrival(perm);
        return perm;
    }

    /** Instants and sorceries never become a board tile. Zone must be Battlefield. */
    private static boolean boardFace(BattlefieldLive.Face face) {
        if (face == null || face.view == null) return false;
        if (face.instant || face.sorcery) return false;
        return BattlefieldLive.boardPermanent(face.view);
    }

    /** Chosen type from the live face, then CardView. Empty until a type is named. */
    private static String chosenTypeOn(BattlefieldLive.Face face) {
        if (face == null) return "";
        if (face.chosenType != null && !face.chosenType.isBlank()) return face.chosenType;
        return BattlefieldLive.chosenTypeOf(face.view);
    }

    /** Ash's new permanents hold the next OK. Both sides puff when the tile is on the bed. */
    private void noteArrival(Perm perm) {
        int id = perm.forgeCard == null ? 0 : perm.forgeCard.getId();
        if (!arrivalsArmed) {
            if (id != 0) seenPerms.add(id);
            return;
        }
        if (id != 0 && !seenPerms.add(id)) return;
        perm.impactPending = true;
        if (perm.you && (alreadyTraveling(id) || departedHand.containsKey(id))) {
            perm.summonDelay = 0f;
            perm.summonLeft = 0f;
            perm.summonFull = 0f;
            return;
        }
        float full = perm.you ? 0.4f : 0.8f;
        if (!perm.you) {
            perm.summonDelay = arrivalSlot * full;
            arrivalSlot++;
            summonHold = Math.max(summonHold, perm.summonDelay + full);
            summonBlocks = true;
            summonName = perm.name == null ? "" : perm.name;
        }
        perm.summonLeft = full;
        perm.summonFull = full;
    }

    private void noteSpell(StackItem item, BattlefieldLive.Spell spell) {
        int id = spell == null ? 0 : spell.id;
        if (!arrivalsArmed) {
            if (id != 0) seenSpells.add(id);
            return;
        }
        if (id != 0 && !seenSpells.add(id)) return;
        boolean yours = spell != null && spell.sourceYours;
        float full = yours ? 0.4f : 0.8f;
        if (!yours) {
            item.summonDelay = arrivalSlot * full;
            arrivalSlot++;
            summonHold = Math.max(summonHold, item.summonDelay + full);
            summonBlocks = true;
            summonName = item.name == null ? "" : item.name;
        }
        item.summonLeft = full;
        item.summonFull = full;
    }

    private void tickSummons(float dt) {
        if (summonHold > 0f) {
            summonHold = Math.max(0f, summonHold - dt);
            if (summonHold <= 0f) summonBlocks = false;
        }
        for (Perm p : board) {
            if (p.summonDelay > 0f) p.summonDelay = Math.max(0f, p.summonDelay - dt);
            else if (p.summonLeft > 0f) p.summonLeft = Math.max(0f, p.summonLeft - dt);
        }
        for (StackItem item : stack) {
            if (item.summonDelay > 0f) item.summonDelay = Math.max(0f, item.summonDelay - dt);
            else if (item.summonLeft > 0f) item.summonLeft = Math.max(0f, item.summonLeft - dt);
        }
    }

    /**
     * One puff and one sound when a tile is actually on the bed, his or theirs.
     * Hidden travel seats wait; Ash's 0.8s hold still staggers the first paint.
     */
    private void firePendingImpacts() {
        for (Perm perm : board) {
            if (perm == null || !perm.impactPending || !perm.alive) continue;
            if (perm.summonDelay > 0f || hidePermForTravel(perm)) continue;
            float x = perm.tx > 8f ? perm.tx : perm.cx;
            float y = perm.ty > 8f ? perm.ty : perm.cy;
            if (x < 1f && y < 1f) continue;
            perm.impactPending = false;
            spawnSummonPuff(x, y, perm.tw > 8f ? perm.tw : perm.w, perm.th > 8f ? perm.th : perm.h);
            DuelSounds.summon();
        }
    }

    /** Smoke and a death cue at his last tile. The commander yes/no waits for this beat. */
    private void noteDeath(Perm gone) {
        if (gone == null) return;
        float x = gone.tx > 8f ? gone.tx : gone.cx;
        float y = gone.ty > 8f ? gone.ty : gone.cy;
        float tw = gone.tw > 8f ? gone.tw : gone.w;
        float th = gone.th > 8f ? gone.th : gone.h;
        if (x < 1f && y < 1f) {
            x = gone.cx;
            y = gone.cy;
        }
        spawnDeathPuff(x, y, tw, th);
        DuelSounds.death();
        if (live != null) live.noteDeathBeat();
    }

    private void spawnDeathPuff(float x, float y, float tw, float th) {
        SummonPuff puff = spawnPuff(x, y, tw, th, 0.38f);
        puff.death = true;
        summonPuffs.add(puff);
    }

    private void spawnSummonPuff(float x, float y, float tw, float th) {
        summonPuffs.add(spawnPuff(x, y, tw, th, IMPACT_SEC));
    }

    private SummonPuff spawnPuff(float x, float y, float tw, float th, float dur) {
        SummonPuff puff = new SummonPuff();
        puff.x = x;
        puff.y = y;
        puff.dur = dur;
        float span = Math.max(28f, Math.max(tw, th) * 0.55f);
        int n = 6;
        puff.blobs = new float[n][6];
        for (int i = 0; i < n; i++) {
            float ang = (float) (i * Math.PI * 2.0 / n + 0.35);
            puff.blobs[i][0] = (float) Math.cos(ang) * span * 0.18f;
            puff.blobs[i][1] = (float) Math.sin(ang) * span * 0.14f;
            puff.blobs[i][2] = span * 0.22f;
            puff.blobs[i][3] = span * (0.85f + (i % 3) * 0.12f);
            puff.blobs[i][4] = (float) Math.cos(ang) * span * 0.55f;
            puff.blobs[i][5] = (float) Math.sin(ang) * span * 0.40f + span * 0.18f;
        }
        return puff;
    }

    private void tickSummonPuffs(float dt) {
        for (int i = summonPuffs.size() - 1; i >= 0; i--) {
            SummonPuff puff = summonPuffs.get(i);
            puff.t += dt;
            if (puff.t >= puff.dur) summonPuffs.remove(i);
        }
    }

    /** Soft gray/white blobs that swell and vanish. Drawn in code; no art file. */
    private void drawSummonPuffs() {
        for (SummonPuff puff : summonPuffs) {
            float u = Math.max(0f, Math.min(1f, puff.t / Math.max(0.001f, puff.dur)));
            float ease = 1f - (1f - u) * (1f - u);
            float fade = u < 0.12f ? u / 0.12f : 1f - (u - 0.12f) / 0.88f;
            fade = Math.max(0f, fade);
            for (float[] blob : puff.blobs) {
                float r = blob[2] + (blob[3] - blob[2]) * ease;
                float bx = puff.x + blob[0] + blob[4] * ease;
                float by = puff.y + blob[1] + blob[5] * ease;
                if (puff.death) {
                    shapes.setColor(0.10f, 0.09f, 0.10f, 0.50f * fade);
                    shapes.circle(bx, by, r, 18);
                    shapes.setColor(0.28f, 0.26f, 0.27f, 0.28f * fade);
                    shapes.circle(bx - r * 0.12f, by + r * 0.10f, r * 0.55f, 14);
                } else {
                    shapes.setColor(0.78f, 0.80f, 0.82f, 0.42f * fade);
                    shapes.circle(bx, by, r, 18);
                    shapes.setColor(0.92f, 0.93f, 0.94f, 0.22f * fade);
                    shapes.circle(bx - r * 0.12f, by + r * 0.10f, r * 0.55f, 14);
                }
            }
        }
    }

    /** A card still in the fan was not cast. Cancel leaves it there with no flight. */
    private void keepStillHeld(List<HandCard> next) {
        for (HandCard card : next) {
            if (card == null || card.forgeCard == null) continue;
            int id = card.forgeCard.getId();
            if (payingThis(id)) continue;
            abortTravel(id);
            card.alive = true;
            card.parked = false;
        }
    }

    /**
     * Discard, bounce, or a leftover first-seven tile: drop it. No flyer on
     * the fan. A real cast or land drop still travels.
     */
    private void dropOrLiftDeparted(HandCard left, BattlefieldLive.Pic pic, boolean ghost) {
        if (left == null || left.forgeCard == null) return;
        int id = left.forgeCard.getId();
        if (playedFromHand(left, pic) || payingThis(id) || (pic != null && playedFromHandId(id, pic))) {
            liftDeparted(left, pic, ghost);
            return;
        }
        abortTravel(id);
        departedHand.remove(id);
        left.alive = false;
        left.parked = true;
    }

    /**
     * Card left the fan. Keep drawing it from the last pose through pay,
     * targeting, X, or a land drop. A discard into grave/exile/library
     * does not get a flyer.
     */
    private void liftDeparted(HandCard left, BattlefieldLive.Pic pic, boolean ghost) {
        if (left == null || left.forgeCard == null) return;
        int id = left.forgeCard.getId();
        boolean userLifted = departedHand.containsKey(id);
        parkDeparted(left);
        if (alreadyTraveling(id)) return;
        if (userLifted || playedFromHand(left, pic) || payingThis(id)) {
            if (!payingThis(id) && hiddenExileId(pic, id)) {
                departedHand.remove(id);
                abortTravel(id);
                return;
            }
            commitHandTravel(left);
            return;
        }
        if (!ghost && !parkedElsewhere(pic, id)) commitHandTravel(left);
    }

    private void parkDeparted(HandCard card) {
        if (card == null || card.forgeCard == null) return;
        CastPose pose = poseOf(card);
        if (pose.w < 8f || pose.h < 8f) return;
        departedHand.put(pose.id, pose);
    }

    private void parkCommand(CommandSlot slot) {
        CastPose pose = poseOfCommand(slot);
        if (pose == null || pose.w < 8f || pose.h < 8f) return;
        departedHand.put(pose.id, pose);
    }

    private CastPose poseOf(HandCard card) {
        CastPose pose = new CastPose();
        pose.id = card.forgeCard == null ? 0 : card.forgeCard.getId();
        pose.name = card.name == null ? "" : card.name;
        pose.tex = card.tex;
        pose.kind = card.kind;
        pose.origin = ghosts.contains(card) ? CastOrigin.GHOST : CastOrigin.HAND;
        float w = card.w >= 8f ? card.w : card.tw;
        float h = card.h >= 8f ? card.h : card.th;
        float cx = card.w >= 8f ? card.cx : card.tcx;
        float cy = card.h >= 8f ? card.cy : card.tcy;
        if (w < 8f || h < 8f) {
            float sh = Math.max(1f, Gdx.graphics.getHeight());
            h = HandLayout.hoverRaisedHeight(sh);
            w = h * HandLayout.CARD_ASPECT;
        }
        if (cx < 1f && cy < 1f) {
            float sh = Math.max(1f, Gdx.graphics.getHeight());
            float sw = Math.max(1f, Gdx.graphics.getWidth());
            cx = sw * 0.5f;
            cy = HandLayout.hoverRaisedHeight(sh) * 0.55f;
        }
        pose.cx = cx;
        pose.cy = cy;
        pose.w = w;
        pose.h = h;
        pose.rot = card.w >= 8f ? card.rotation : card.trot;
        return pose;
    }

    private CastPose poseOfCommand(CommandSlot slot) {
        if (slot == null || slot.seat == null || slot.seat.card == null || slot.seat.card.view == null) {
            return null;
        }
        CastPose pose = new CastPose();
        pose.id = slot.seat.card.view.getId();
        pose.name = slot.seat.name == null ? "" : slot.seat.name;
        pose.tex = textureFor(pose.name);
        pose.kind = kindOf(slot.seat.card);
        pose.origin = CastOrigin.COMMAND;
        pose.cx = slot.cx;
        pose.cy = slot.cy;
        pose.w = slot.cw;
        pose.h = slot.ch;
        pose.rot = 0f;
        return pose;
    }

    /**
     * The interpolator starts on this UI thread the instant the card leaves
     * the fan (or a drag-cast commits). It stays up through pay. Destination
     * objects stay hidden until this same picture arrives.
     */
    private void commitHandTravel(HandCard card) {
        if (card == null || card.forgeCard == null) return;
        int id = card.forgeCard.getId();
        if (id == 0) return;
        card.alive = false;
        card.parked = true;
        if (alreadyTraveling(id)) return;
        CastPose pose = departedHand.get(id);
        if (pose == null || pose.w < 8f || pose.h < 8f) pose = poseOf(card);
        commitPoseTravel(pose, card.kind, card.tex, card.name);
    }

    private void commitCommandTravel(CommandSlot slot) {
        CastPose pose = departedHand.get(slot != null && slot.seat != null && slot.seat.card != null
                && slot.seat.card.view != null ? slot.seat.card.view.getId() : 0);
        if (pose == null) pose = poseOfCommand(slot);
        if (pose == null) return;
        commitPoseTravel(pose, pose.kind, pose.tex, pose.name);
    }

    private void commitPoseTravel(CastPose pose, BattlefieldLayout.Kind kind, Texture tex, String name) {
        if (pose == null || pose.w < 8f || pose.h < 8f) return;
        int id = pose.id;
        if (id == 0 || alreadyTraveling(id)) return;
        departedHand.put(id, pose);
        CastFlight flight = flightFromPose(pose);
        flight.cardId = id;
        flight.kind = kind == null ? pose.kind : kind;
        flight.toBoard = flight.kind == BattlefieldLayout.Kind.LAND;
        flight.name = name == null || name.isBlank() ? pose.name : name;
        if (flight.tex == null) flight.tex = tex;
        if (flight.tex == null && flight.name != null && !flight.name.isBlank()) {
            flight.tex = textureFor(flight.name);
        }
        beginFlight(flight);
    }

    private void pruneDeparted() {
        if (departedHand.isEmpty()) return;
        pruneFanPoses(hand);
        pruneFanPoses(ghosts);
    }

    private void pruneFanPoses(List<HandCard> fan) {
        for (HandCard card : fan) {
            if (card == null || card.forgeCard == null || !card.alive) continue;
            int id = card.forgeCard.getId();
            if (payingThis(id) || alreadyTraveling(id)) continue;
            departedHand.remove(id);
        }
    }

    /** Pay started after the card left the fan. Keep the same picture up. */
    private void holdPayingCast(BattlefieldLive.Pic pic) {
        if (!liveReady()) return;
        CardView paying = live.payingCard();
        if (paying == null) return;
        int id = paying.getId();
        if (id == 0 || alreadyTraveling(id) || fanHas(hand, id) || fanHas(ghosts, id)) return;
        CastPose pose = departedHand.get(id);
        if (pose == null || pose.w < 8f || pose.h < 8f) pose = poseFromCommand(id);
        if (pose == null || pose.w < 8f || pose.h < 8f) pose = poseFromGhost(id);
        if (pose == null || pose.w < 8f || pose.h < 8f) return;
        commitPoseTravel(pose, pose.kind, pose.tex, pose.name);
    }

    private CastPose poseFromCommand(int id) {
        if (id == 0) return null;
        for (CommandSlot slot : commandSlots) {
            if (slot == null || slot.seat == null || slot.seat.card == null || slot.seat.card.view == null) continue;
            if (slot.seat.card.view.getId() == id) return poseOfCommand(slot);
        }
        return null;
    }

    private CastPose poseFromGhost(int id) {
        if (id == 0) return null;
        for (HandCard card : ghosts) {
            if (card != null && card.forgeCard != null && card.forgeCard.getId() == id) return poseOf(card);
        }
        return null;
    }

    private void markPaySeen() {
        if (castFlight != null && payingThis(castFlight.cardId)) castFlight.sawPay = true;
        for (CastFlight flight : pendingFlights) {
            if (flight != null && payingThis(flight.cardId)) flight.sawPay = true;
        }
    }

    /** Pay cancelled: the same picture goes back to the hand, ghost fan, or command slot. */
    private void reclaimCancelled() {
        reclaimOne(castFlight);
        for (CastFlight flight : new ArrayList<>(pendingFlights)) reclaimOne(flight);
    }

    private void reclaimOne(CastFlight flight) {
        if (flight == null || !flight.sawPay) return;
        int id = flight.cardId;
        if (id == 0 || payingThis(id)) return;
        if (stackItemForCard(id) != null || permById(id) != null) return;
        if (fanHas(hand, id) || fanHas(ghosts, id) || commandHas(id) || exileHas(id)) {
            departedHand.remove(id);
            abortTravel(id);
        }
    }

    private boolean exileHas(int id) {
        return zoneHasId(yourExile, id) || zoneHasId(theirExile, id);
    }

    private static boolean zoneHasId(List<ZoneCard> zone, int id) {
        if (id == 0 || zone == null) return false;
        for (ZoneCard card : zone) {
            if (card != null && card.forgeCard != null && card.forgeCard.getId() == id) return true;
        }
        return false;
    }

    private boolean commandHas(int id) {
        if (id == 0 || yourCommand == null) return false;
        for (BattlefieldLive.CommanderSeat seat : yourCommand) {
            if (seat != null && seat.inCommand && seat.card != null && seat.card.view != null
                    && seat.card.view.getId() == id) return true;
        }
        return false;
    }

    /** A discard or mill is not a cast. Drop any leftover flyer. */
    private void dropAwayTravels(BattlefieldLive.Pic pic) {
        dropHiddenExileCasts(pic);
        if (pic == null || departedHand.isEmpty()) return;
        List<Integer> drop = new ArrayList<>();
        for (CastPose pose : departedHand.values()) {
            if (pose == null || pose.id == 0) continue;
            if (payingThis(pose.id) || alreadyTraveling(pose.id)) continue;
            if (playedFromHandId(pose.id, pic) && !payingThis(pose.id)) {
                drop.add(pose.id);
                continue;
            }
            if (parkedElsewhere(pic, pose.id)) drop.add(pose.id);
        }
        for (int id : drop) {
            departedHand.remove(id);
            abortTravel(id);
        }
    }

    /**
     * Foretell is a special action. The card is already face-down in exile and
     * never takes a stack seat, so the being-cast pose must not wait for one.
     */
    private void dropHiddenExileCasts(BattlefieldLive.Pic pic) {
        if (pic == null) return;
        Set<Integer> hidden = BattlefieldLive.hiddenExileIds(pic);
        if (hidden.isEmpty()) return;
        List<Integer> drop = new ArrayList<>();
        if (castFlight != null && hidden.contains(castFlight.cardId) && !payingThis(castFlight.cardId)) {
            drop.add(castFlight.cardId);
        }
        for (CastFlight flight : pendingFlights) {
            if (flight != null && hidden.contains(flight.cardId) && !payingThis(flight.cardId)) {
                drop.add(flight.cardId);
            }
        }
        for (CastPose pose : departedHand.values()) {
            if (pose == null || pose.id == 0 || !hidden.contains(pose.id)) continue;
            if (payingThis(pose.id) || playedFromHandId(pose.id, pic)) continue;
            drop.add(pose.id);
        }
        for (int id : drop) {
            departedHand.remove(id);
            abortTravel(id);
        }
        if (liveReady()) live.dropHiddenExileHolds(hidden);
    }

    private static boolean hiddenExileId(BattlefieldLive.Pic pic, int id) {
        return id != 0 && BattlefieldLive.hiddenExileIds(pic).contains(id);
    }

    private static boolean fanHas(List<HandCard> fan, int id) {
        if (id == 0) return false;
        for (HandCard card : fan) {
            if (card != null && card.alive && card.forgeCard != null && card.forgeCard.getId() == id) return true;
        }
        return false;
    }

    private boolean payingThis(int id) {
        if (id == 0 || !liveReady()) return false;
        CardView paying = live.payingCard();
        return paying != null && paying.getId() == id;
    }

    private static boolean parkedElsewhere(BattlefieldLive.Pic pic, int id) {
        if (pic == null || id == 0) return false;
        return inFaces(pic.yourGrave, id) || inFaces(pic.yourExile, id) || inFaces(pic.yourLibrary, id)
                || inFaces(pic.theirGrave, id) || inFaces(pic.theirExile, id) || inFaces(pic.theirLibrary, id);
    }

    private static boolean inFaces(List<BattlefieldLive.Face> faces, int id) {
        if (faces == null || id == 0) return false;
        for (BattlefieldLive.Face face : faces) {
            if (face != null && face.view != null && face.view.getId() == id) return true;
        }
        return false;
    }

    private static boolean playedFromHandId(int id, BattlefieldLive.Pic pic) {
        if (id == 0 || pic == null) return false;
        for (BattlefieldLive.Spell spell : pic.stack) {
            if (spell != null && spell.sourceId == id) return true;
        }
        for (BattlefieldLive.Face face : pic.yourBoard) {
            if (face != null && face.view != null && face.view.getId() == id) return true;
        }
        return false;
    }

    /**
     * A spell that uses the stack: attach the existing hand trip to that stack
     * seat, or start one from the last fan pose. Board abilities stay on the
     * old summon hold.
     */
    private boolean startHandCastFlight(StackItem item, BattlefieldLive.Spell spell) {
        if (item == null || spell == null) return false;
        int cardId = spell.sourceId;
        CastFlight open = cardId == 0 ? null : flightForCard(cardId);
        if (open == null && spell.name != null && !spell.name.isBlank()) {
            CastPose named = peekDepartedByName(spell.name);
            if (named != null) open = flightForCard(named.id);
        }
        if (open != null) {
            attachStack(open, item, spell);
            return true;
        }
        if (spell.sourceOnBoard) return false;
        CastPose pose = cardId == 0 ? null : departedHand.get(cardId);
        if (pose == null && spell.name != null && !spell.name.isBlank()) {
            pose = peekDepartedByName(spell.name);
        }
        if (pose == null || pose.w < 8f || pose.h < 8f) return false;
        departedHand.remove(pose.id);
        CastFlight flight = flightFromPose(pose);
        flight.cardId = pose.id == 0 ? cardId : pose.id;
        flight.kind = pose.kind;
        attachStack(flight, item, spell);
        return beginFlight(flight);
    }

    private void attachStack(CastFlight flight, StackItem item, BattlefieldLive.Spell spell) {
        if (flight == null || item == null) return;
        flight.spellId = spell == null ? item.id : spell.id;
        flight.toBoard = false;
        flight.aimed = false;
        flight.done = false;
        if (spell != null && spell.id != 0) seenSpells.add(spell.id);
        if (flight.tex == null && item.card != null) flight.tex = item.card.tex;
        if (flight.tex == null && item.name != null && !item.name.isBlank()) flight.tex = textureFor(item.name);
        if (flight.name == null || flight.name.isBlank()) flight.name = item.name;
        item.summonDelay = 0f;
        item.summonLeft = 0f;
        item.summonFull = 0f;
    }

    /**
     * Lands and other no-stack plays skip the stack path. Aim the same trip at
     * the tile and keep that tile hidden until the flyer arrives.
     */
    private void startHandBoardFlights() {
        for (Perm perm : board) {
            if (perm == null || perm.forgeCard == null) continue;
            if (!perm.you) {
                if (perm.kind == BattlefieldLayout.Kind.LAND) abortTravel(perm.forgeCard.getId());
                continue;
            }
            if (perm.instant || perm.sorcery || perm.kind == BattlefieldLayout.Kind.SPELL) continue;
            int id = perm.forgeCard.getId();
            if (id == 0) continue;
            CastFlight open = flightForCard(id);
            if (open != null) {
                if (open.spellId != 0) landFromStack(open, perm);
                else attachBoard(open, perm);
                continue;
            }
            CastPose pose = departedHand.get(id);
            if (pose == null || pose.w < 8f || pose.h < 8f) continue;
            boolean land = perm.kind == BattlefieldLayout.Kind.LAND
                    || pose.kind == BattlefieldLayout.Kind.LAND;
            if (!land) {
                departedHand.remove(id);
                continue;
            }
            departedHand.remove(id);
            CastFlight flight = flightFromPose(pose);
            flight.cardId = id;
            flight.kind = perm.kind;
            attachBoard(flight, perm);
            beginFlight(flight);
        }
    }

    /**
     * The stack picture and the new tile share one beat: drop the flyer now.
     * No hop from the huge showcase down to the seat.
     */
    private void landFromStack(CastFlight flight, Perm perm) {
        if (flight == null || perm == null) return;
        if (spellFlight(flight, perm)) {
            abortTravel(flight.cardId);
            return;
        }
        attachBoard(flight, perm);
        flight.toBoard = true;
        flight.done = true;
        flight.aimed = true;
        flight.t = flight.dur;
    }

    private void attachBoard(CastFlight flight, Perm perm) {
        if (flight == null || perm == null || perm.forgeCard == null) return;
        if (spellFlight(flight, perm)) return;
        flight.permId = perm.forgeCard.getId();
        boolean land = perm.kind == BattlefieldLayout.Kind.LAND
                || flight.kind == BattlefieldLayout.Kind.LAND;
        if (land || stackItemForCard(flight.cardId) == null) flight.toBoard = true;
        flight.aimed = false;
        flight.done = false;
        if (flight.tex == null) flight.tex = perm.tex;
        if (flight.name == null || flight.name.isBlank()) flight.name = perm.name;
        perm.summonDelay = 0f;
        perm.summonLeft = 0f;
        perm.summonFull = 0f;
    }

    private CastFlight flightFromPose(CastPose pose) {
        CastFlight flight = new CastFlight();
        flight.cardId = pose.id;
        flight.kind = pose.kind;
        flight.origin = pose.origin;
        flight.tex = pose.tex;
        flight.name = pose.name;
        flight.homeX = pose.cx;
        flight.homeY = pose.cy;
        flight.homeW = pose.w;
        flight.homeH = pose.h;
        flight.homeRot = pose.rot;
        flight.x0 = pose.cx;
        flight.y0 = pose.cy;
        flight.w0 = pose.w;
        flight.h0 = pose.h;
        flight.rot0 = pose.rot;
        flight.x1 = pose.cx;
        flight.y1 = pose.cy;
        flight.w1 = pose.w;
        flight.h1 = pose.h;
        flight.rot1 = pose.rot;
        flight.dur = CAST_TRAVEL_SEC;
        return flight;
    }

    private boolean beginFlight(CastFlight flight) {
        if (flight == null) return false;
        if (castFlight == null) {
            castFlight = flight;
            return true;
        }
        if (flight.cardId != 0 && castFlight.cardId == flight.cardId) return true;
        pendingFlights.add(flight);
        return true;
    }

    private void abortTravel(int cardId) {
        if (cardId == 0) return;
        if (castFlight != null && castFlight.cardId == cardId) castFlight = null;
        pendingFlights.removeIf(flight -> flight != null && flight.cardId == cardId);
        departedHand.remove(cardId);
    }

    /**
     * Stack object left (resolve or counter). Drop the well tile, targeting
     * line, cast flyer, and any held-spell ghost. A death puff on the
     * targeted creature is unchanged.
     */
    private void dropLeftStackPaint(BattlefieldLive.Pic pic) {
        Set<Integer> liveStack = new HashSet<>();
        Set<Integer> liveSources = new HashSet<>();
        if (pic != null) {
            for (BattlefieldLive.Spell spell : pic.stack) {
                if (spell == null) continue;
                if (spell.id != 0) liveStack.add(spell.id);
                if (spell.sourceId != 0) liveSources.add(spell.sourceId);
            }
        }
        stack.removeIf(item -> item != null && !stackStillLive(item, liveStack, liveSources));
        dropLeftFlight(castFlight, liveStack, liveSources);
        for (CastFlight flight : new ArrayList<>(pendingFlights)) {
            dropLeftFlight(flight, liveStack, liveSources);
        }
        if (liveReady()) this.live.dropHiddenExileHolds(BattlefieldLive.hiddenExileIds(pic));
    }

    private static boolean stackStillLive(StackItem item, Set<Integer> liveStack, Set<Integer> liveSources) {
        if (item == null) return false;
        if (item.id != 0 && liveStack.contains(item.id)) return true;
        return item.sourceId != 0 && liveSources.contains(item.sourceId);
    }

    private void dropLeftFlight(CastFlight flight, Set<Integer> liveStack, Set<Integer> liveSources) {
        if (flight == null) return;
        if (payingThis(flight.cardId)) return;
        boolean onStack = (flight.spellId != 0 && liveStack.contains(flight.spellId))
                || (flight.cardId != 0 && liveSources.contains(flight.cardId))
                || stackItemForCard(flight.cardId) != null
                || (flight.spellId != 0 && stackItemById(flight.spellId) != null);
        if (onStack) return;
        if (flight.toBoard || flight.kind == BattlefieldLayout.Kind.LAND) return;
        boolean boardPerm = flight.kind != BattlefieldLayout.Kind.SPELL
                && permById(flight.permId != 0 ? flight.permId : flight.cardId) != null;
        if (boardPerm) return;
        abortTravel(flight.cardId);
        if (flight.spellId != 0) abortTravel(flight.spellId);
    }

    private CastFlight flightForCard(int cardId) {
        if (cardId == 0) return null;
        if (castFlight != null && castFlight.cardId == cardId) return castFlight;
        for (CastFlight flight : pendingFlights) {
            if (flight != null && flight.cardId == cardId) return flight;
        }
        return null;
    }

    private StackItem stackItemForCard(int cardId) {
        if (cardId == 0) return null;
        for (int i = stack.size() - 1; i >= 0; i--) {
            StackItem item = stack.get(i);
            if (item != null && item.sourceId == cardId) return item;
        }
        return null;
    }

    private boolean alreadyTraveling(int cardId) {
        return flightForCard(cardId) != null;
    }

    private CastPose peekDepartedByName(String name) {
        for (CastPose pose : departedHand.values()) {
            if (pose != null && name.equals(pose.name)) return pose;
        }
        return null;
    }

    private void tickCastFlight(float dt) {
        if (liveReady()) dropHiddenExileCasts(live.picture());
        if (castFlight == null) {
            if (!pendingFlights.isEmpty()) castFlight = pendingFlights.remove(0);
            return;
        }
        if (castFlight.done) {
            departedHand.remove(castFlight.cardId);
            castFlight = pendingFlights.isEmpty() ? null : pendingFlights.remove(0);
            return;
        }
        if (payingThis(castFlight.cardId)) castFlight.sawPay = true;
        castFlight.t = Math.min(castFlight.dur, castFlight.t + dt);
        if (castFlight.t >= castFlight.dur && !expectsAnotherLeg(castFlight)) {
            castFlight.done = true;
        }
    }

    private boolean expectsAnotherLeg(CastFlight flight) {
        if (flight == null) return false;
        if (payingThis(flight.cardId)) return true;
        if (!flight.aimed && !flightHasSeat(flight)) return true;
        if (aimAtBoard(flight)) return false;
        if (flight.kind == BattlefieldLayout.Kind.SPELL) return false;
        return true;
    }

    private void aimCastFlight(float w, float h) {
        if (castFlight == null) return;
        float cx = flightX(castFlight);
        float cy = flightY(castFlight);
        float cw = flightW(castFlight);
        float ch = flightH(castFlight);
        float rot = flightRot(castFlight);
        if (flightHasSeat(castFlight)) {
            if (aimAtBoard(castFlight)) aimBoardFlight(w, h);
            else aimStackSeat(castFlight);
            finishAim(castFlight, cx, cy, cw, ch, rot);
        } else {
            aimPayHold(castFlight, w, h);
        }
    }

    private void aimStackSeat(CastFlight flight) {
        StackItem item = flight.spellId != 0 ? stackItemById(flight.spellId) : stackItemForCard(flight.cardId);
        if (item != null && item.rowH > 8f) {
            flight.x1 = item.rowX;
            flight.y1 = item.rowY;
            flight.h1 = item.rowH;
            flight.w1 = stackArt(item) ? item.rowW : item.rowH * HandLayout.CARD_ASPECT;
            flight.rot1 = 0f;
            return;
        }
        HandLayout.Pose well = stackWell(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        flight.x1 = well.cx;
        flight.y1 = well.cy;
        flight.h1 = well.h;
        flight.w1 = well.h * HandLayout.CARD_ASPECT;
        flight.rot1 = 0f;
    }

    /**
     * Short hop toward the stack well. Stops partway so pay does not park a
     * full card in the middle of the table. Size stays tile/hand, not the
     * old half-table plate.
     */
    private void aimPayHold(CastFlight flight, float w, float h) {
        HandLayout.Pose dest = eventualDest(flight, w, h);
        float hx = flight.homeW > 1f ? flight.homeX : flight.x0;
        float hy = flight.homeH > 1f ? flight.homeY : flight.y0;
        float hr = flight.homeW > 1f ? flight.homeRot : flight.rot0;
        float u = PAY_HOLD_U;
        flight.x1 = hx + (dest.cx - hx) * u;
        flight.y1 = hy + (dest.cy - hy) * u;
        float holdH = payVisibleHeight(h, flight);
        flight.h1 = holdH;
        flight.w1 = holdH * HandLayout.CARD_ASPECT;
        flight.rot1 = lerpAngle(hr, dest.rotationDeg, u);
    }

    /** Hand-sized for a spell; tile-sized when the flyer is becoming a permanent. */
    private float payVisibleHeight(float h, CastFlight flight) {
        if (aimAtBoard(flight)) {
            float tile = BattlefieldLayout.boardTileHeight(h);
            if (tile > 8f) return tile;
        }
        return HandLayout.fullHeight(h);
    }

    private HandLayout.Pose eventualDest(CastFlight flight, float w, float h) {
        if (aimAtBoard(flight)) {
            Perm perm = permById(flight.permId != 0 ? flight.permId : flight.cardId);
            if (perm != null && perm.tw > 8f) {
                float ch = perm.th > 8f ? perm.th : HandLayout.hoverRaisedHeight(h);
                return new HandLayout.Pose(perm.tx, perm.ty, ch * HandLayout.CARD_ASPECT, ch, perm.trot, 1f);
            }
            return boardRowCenter(w, h, perm);
        }
        StackItem item = flight.spellId != 0 ? stackItemById(flight.spellId) : stackItemForCard(flight.cardId);
        if (item != null && item.rowH > 8f) {
            float cw = stackArt(item) ? item.rowW : item.rowH * HandLayout.CARD_ASPECT;
            return new HandLayout.Pose(item.rowX, item.rowY, cw, item.rowH, 0f, 1f);
        }
        return stackWell(w, h);
    }

    private HandLayout.Pose stackWell(float w, float h) {
        return BattlefieldLayout.stackShowcase(w, h);
    }

    private boolean aimAtBoard(CastFlight flight) {
        if (flight == null) return false;
        if (flight.kind == BattlefieldLayout.Kind.SPELL) return false;
        if (flight.toBoard || flight.kind == BattlefieldLayout.Kind.LAND) return true;
        if (flight.permId != 0 && stackItemForCard(flight.cardId) == null) return true;
        return false;
    }

    private static boolean spellFlight(CastFlight flight, Perm perm) {
        if (flight != null && flight.kind == BattlefieldLayout.Kind.SPELL) return true;
        return perm != null && (perm.instant || perm.sorcery || perm.kind == BattlefieldLayout.Kind.SPELL);
    }

    /** Land tile when layout knows it; otherwise the land-row center on his half. */
    private void aimBoardFlight(float w, float h) {
        Perm perm = permById(castFlight.permId);
        if (perm != null && perm.tw > 8f) {
            float ch = perm.th > 8f ? perm.th : HandLayout.hoverRaisedHeight(h);
            castFlight.x1 = perm.tx;
            castFlight.y1 = perm.ty;
            castFlight.h1 = ch;
            castFlight.w1 = ch * HandLayout.CARD_ASPECT;
            castFlight.rot1 = perm.trot;
            return;
        }
        HandLayout.Pose well = boardRowCenter(w, h, perm);
        castFlight.x1 = well.cx;
        castFlight.y1 = well.cy;
        castFlight.h1 = well.h;
        castFlight.w1 = well.w;
        castFlight.rot1 = 0f;
    }

    private HandLayout.Pose boardRowCenter(float w, float h, Perm perm) {
        if (perm != null && (perm.kind == BattlefieldLayout.Kind.ENCHANTMENT
                || (perm.kind == BattlefieldLayout.Kind.ARTIFACT && !perm.token))) {
            BattlefieldLayout.FieldBox pocket = BattlefieldLayout.aePocket(w, h, BattlefieldLayout.Side.YOU);
            float ch = Math.max(8f, Math.min(BattlefieldLayout.boardTileHeight(h), pocket.height() - 4f));
            return new HandLayout.Pose(pocket.cx(), pocket.cy(), ch * HandLayout.CARD_ASPECT, ch, 0f, 1f);
        }
        BattlefieldLayout.Row row = perm != null && perm.kind != BattlefieldLayout.Kind.LAND
                ? BattlefieldLayout.Row.CREATURE
                : BattlefieldLayout.Row.LAND;
        float ch = HandLayout.hoverRaisedHeight(h);
        float cw = ch * HandLayout.CARD_ASPECT;
        float cy = BattlefieldLayout.rowCenterY(h, BattlefieldLayout.Side.YOU, row);
        float cx = w * 0.5f;
        if (row == BattlefieldLayout.Row.LAND) {
            BattlefieldLayout.FieldBox pocket = BattlefieldLayout.yourLandPocket(
                    w, h, commandRightFor(true), !row(true, BattlefieldLayout.Row.TOKEN).isEmpty());
            cx = pocket.cx();
            cy = pocket.cy();
        }
        return new HandLayout.Pose(cx, cy, cw, ch, 0f, 1f);
    }

    /** Stack well for an untargeted cast: the open felt above the fan, card-sized. */
    private HandLayout.Pose cardWellAboveHand(float w, float h) {
        float[] well = handRegionEnd();
        float ch = HandLayout.hoverRaisedHeight(h);
        float cw = ch * HandLayout.CARD_ASPECT;
        return new HandLayout.Pose(well[0], well[1], cw, ch, 0f, 1f);
    }

    private StackItem stackItemById(int id) {
        if (id == 0) return top();
        for (int i = stack.size() - 1; i >= 0; i--) {
            StackItem item = stack.get(i);
            if (item.id == id) return item;
        }
        return null;
    }

    private boolean hideStackForTravel(StackItem item) {
        if (item == null) return false;
        if (!item.sourceOnBoard && item.sourceId != 0 && permById(item.sourceId) != null) return true;
        if (hidesStack(castFlight, item)) return true;
        for (CastFlight flight : pendingFlights) {
            if (hidesStack(flight, item)) return true;
        }
        return false;
    }

    private static boolean hidesStack(CastFlight flight, StackItem item) {
        if (flight == null || item == null || flight.toBoard) return false;
        if (flight.cardId != 0 && item.sourceId == flight.cardId) return true;
        return flight.spellId != 0 && item.id == flight.spellId;
    }

    /** The land (or other no-stack arrival) stays off the bed until the flyer gets there. */
    private boolean hidePermForTravel(Perm perm) {
        if (perm == null || perm.forgeCard == null) return false;
        if (!perm.you) return false;
        CastFlight flight = flightForCard(perm.forgeCard.getId());
        if (flight == null || flight.done || !aimAtBoard(flight)) return false;
        if (perm.kind == BattlefieldLayout.Kind.LAND
                && flight.kind != BattlefieldLayout.Kind.LAND && !flight.toBoard) {
            return false;
        }
        if (flight != castFlight && flight.t <= 0f) return false;
        if (perm.tw > 8f && flight.t >= flight.dur) return false;
        return true;
    }

    /** Target curve waits until the card is on the stack, or just as it arrives. */
    private boolean stackTravelReady() {
        if (castFlight == null) return true;
        return flightHasSeat(castFlight) && castFlight.t >= castFlight.dur * 0.85f;
    }

    /** The stack or land tile that this flyer becomes. Empty during pay. */
    private boolean flightHasSeat(CastFlight flight) {
        if (flight == null) return false;
        if (aimAtBoard(flight)) {
            int id = flight.permId != 0 ? flight.permId : flight.cardId;
            return permById(id) != null;
        }
        if (flight.spellId != 0 && stackItemById(flight.spellId) != null) return true;
        return stackItemForCard(flight.cardId) != null;
    }

    /**
     * Pay finished (or a land landed). If the picture is not already on the
     * seat, start a short remaining trip from here. No disappear.
     */
    private void finishAim(CastFlight flight, float cx, float cy, float cw, float ch, float rot) {
        if (flight == null || flight.aimed) return;
        flight.aimed = true;
        float dx = flight.x1 - cx;
        float dy = flight.y1 - cy;
        if (dx * dx + dy * dy < 36f && flight.t >= flight.dur * 0.85f) {
            flight.t = flight.dur;
            return;
        }
        flight.x0 = cx;
        flight.y0 = cy;
        flight.w0 = cw;
        flight.h0 = ch;
        flight.rot0 = rot;
        flight.t = 0f;
        flight.dur = CAST_TRAVEL_SEC;
    }

    private static float flightU(CastFlight flight) {
        if (flight == null || flight.dur <= 0f) return 1f;
        return easeOut(flight.t / flight.dur);
    }

    private static float flightX(CastFlight flight) {
        float u = flightU(flight);
        return flight.x0 + (flight.x1 - flight.x0) * u;
    }

    private static float flightY(CastFlight flight) {
        float u = flightU(flight);
        return flight.y0 + (flight.y1 - flight.y0) * u;
    }

    private static float flightW(CastFlight flight) {
        float u = flightU(flight);
        return flight.w0 + (flight.w1 - flight.w0) * u;
    }

    private static float flightH(CastFlight flight) {
        float u = flightU(flight);
        return flight.h0 + (flight.h1 - flight.h0) * u;
    }

    private static float flightRot(CastFlight flight) {
        return lerpAngle(flight.rot0, flight.rot1, flightU(flight));
    }

    /** The same full card picture, easing out from the fan pose to the stack or land tile. */
    private void drawCastFlight() {
        if (castFlight == null || castFlight.done) return;
        float u = easeOut(castFlight.t / Math.max(0.001f, castFlight.dur));
        float cx = castFlight.x0 + (castFlight.x1 - castFlight.x0) * u;
        float cy = castFlight.y0 + (castFlight.y1 - castFlight.y0) * u;
        float cw = castFlight.w0 + (castFlight.w1 - castFlight.w0) * u;
        float ch = castFlight.h0 + (castFlight.h1 - castFlight.h0) * u;
        if (ch > 1f) cw = ch * HandLayout.CARD_ASPECT;
        float rot = lerpAngle(castFlight.rot0, castFlight.rot1, u);
        Texture tex = castFlight.tex;
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, cx, cy, cw, ch, rot);
        } else {
            batch.setColor(0.10f, 0.11f, 0.13f, 1f);
            drawTex(pixel(), cx, cy, cw, ch, rot);
        }
        batch.setColor(Color.WHITE);
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static BattlefieldLayout.Kind kindOf(BattlefieldLive.Face face) {
        if (face.land) return BattlefieldLayout.Kind.LAND;
        if (face.battle) return BattlefieldLayout.Kind.BATTLE;
        if (face.planeswalker) return BattlefieldLayout.Kind.PLANESWALKER;
        if (face.creature) return BattlefieldLayout.Kind.CREATURE;
        if (face.enchantment) return BattlefieldLayout.Kind.ENCHANTMENT;
        if (face.artifact) return BattlefieldLayout.Kind.ARTIFACT;
        return BattlefieldLayout.Kind.SPELL;
    }

    /** Real card pictures for a reveal. The question stays in the left window. */
    private void drawCardSheet(float w, float h) {
        BattlefieldLive.Pic pic = live.picture();
        amountControls.clear();
        placeControls.clear();
        if (pic == null || pic.sheet.isEmpty()) return;
        int n = pic.sheet.size();
        boolean handBand = sheetHandBand();
        syncSheetScroll(w, h);
        boolean cascade = pic.mayCast;
        HandLayout.Pose[] poses = cascade
                ? BattlefieldLayout.cascadeRow(w, h, n, pic.hitIndex, sheetScroll, handBand)
                : BattlefieldLayout.revealedRow(w, h, n, sheetScroll, handBand);
        boolean ordering = pic.ordering;
        boolean placing = pic.placing;
        int grip = ordering && draggingOrder ? orderGrip : -1;
        int drop = grip >= 0 ? BattlefieldLayout.revealedDropIndex(w, h, n, grip, mx, sheetScroll, handBand) : -1;
        batch.begin();
        if (placing && poses.length > 0) {
            float[] plates = BattlefieldLayout.placeZonePlates(w, h, poses);
            placeControls.add(new UiButton("place-top", "TOP", plates[0], plates[1], plates[2], plates[3]));
            placeControls.add(new UiButton("place-bottom", "BOTTOM", plates[4], plates[5], plates[6], plates[7]));
            boolean topHot = pic.placeSplit > 0;
            boolean botHot = pic.placeSplit < n;
            if (n == 1) {
                topHot = pic.placeSplit == 1;
                botHot = pic.placeSplit == 0;
            }
            drawPlaceWord("TOP", plates[0], plates[1], plates[2], plates[3], topHot);
            drawPlaceWord("BOTTOM", plates[4], plates[5], plates[6], plates[7], botHot);
        } else if (ordering && poses.length > 0) {
            float labelY = poses[0].cy + poses[0].h / 2f + 22f;
            drawEndWord("TOP", poses[0].cx, labelY);
            drawEndWord("BOTTOM", poses[poses.length - 1].cx, labelY);
        }
        for (int i = 0; i < n && i < poses.length; i++) {
            if (i == grip) continue;
            int slot = grip >= 0 ? HandLayout.reorderSlot(n, i, grip, drop) : i;
            HandLayout.Pose pose = grip >= 0
                    ? BattlefieldLayout.revealedSlot(w, h, n, slot, sheetScroll, handBand) : poses[i];
            if (cascade && grip < 0 && i < poses.length) pose = poses[i];
            int mark = ordering ? slot + 1 : pic.sheet.get(i).order;
            drawSheetTile(pic.sheet.get(i), pose, mark);
        }
        if (grip >= 0 && grip < n && poses.length > 0) {
            HandLayout.Pose held = poses[Math.max(0, Math.min(poses.length - 1, grip))];
            drawSheetTile(pic.sheet.get(grip), new HandLayout.Pose(mx, my, held.w, held.h, 0f, held.scale), drop + 1);
        }
        batch.end();
        if (pic.amounting) drawAmountControls(pic, poses);
    }

    /** Minus, the assigned number, and plus along the bottom of each picture. */
    private void drawAmountControls(BattlefieldLive.Pic pic, HandLayout.Pose[] poses) {
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        for (int i = 0; i < pic.sheet.size() && i < poses.length; i++) {
            HandLayout.Pose pose = poses[i];
            float btn = Math.min(28f, pose.w * 0.28f);
            float y = pose.cy - pose.h / 2f + btn / 2f + 6f;
            float minusX = pose.cx - btn - 4f;
            float plusX = pose.cx + btn + 4f;
            shapes.setColor(0.08f, 0.08f, 0.09f, 0.92f);
            shapes.rect(minusX - btn / 2f, y - btn / 2f, btn, btn);
            shapes.rect(plusX - btn / 2f, y - btn / 2f, btn, btn);
            shapes.rect(pose.cx - btn / 2f, y - btn / 2f, btn, btn);
            amountControls.add(new UiButton(i + ":minus", "−", minusX, y, btn, btn));
            amountControls.add(new UiButton(i + ":plus", "+", plusX, y, btn, btn));
        }
        shapes.end();
        batch.begin();
        for (int i = 0; i < pic.sheet.size() && i < poses.length; i++) {
            HandLayout.Pose pose = poses[i];
            float btn = Math.min(28f, pose.w * 0.28f);
            float y = pose.cy - pose.h / 2f + btn / 2f + 6f;
            drawCenteredOn(stripFont, "−", pose.cx - btn - 4f, y, Color.WHITE);
            drawCenteredOn(stripFont, Integer.toString(pic.sheet.get(i).amount), pose.cx, y, GOLD);
            drawCenteredOn(stripFont, "+", pose.cx + btn + 4f, y, Color.WHITE);
        }
        batch.end();
    }

    private void drawSheetTile(BattlefieldLive.ShownCard tile, HandLayout.Pose pose, int orderMark) {
        Texture tex = textureFor(tile.name);
        if (tile.dim) batch.setColor(0.45f, 0.45f, 0.45f, 0.55f);
        else batch.setColor(Color.WHITE);
        if (tex != null) drawTex(tex, pose.cx, pose.cy, pose.w, pose.h, 0f);
        else {
            batch.setColor(tile.dim ? 0.2f : 0.34f, tile.dim ? 0.2f : 0.35f, tile.dim ? 0.22f : 0.38f, tile.dim ? 0.55f : 1f);
            drawTex(pixel(), pose.cx, pose.cy, pose.w, pose.h, 0f);
        }
        batch.setColor(Color.WHITE);
        if (tile.dim) {
            batch.setColor(0f, 0f, 0f, 0.45f);
            drawTex(pixel(), pose.cx, pose.cy, pose.w, pose.h, 0f);
            batch.setColor(Color.WHITE);
        }
        if (tile.picked || tile.hit) drawCardBorder(pose.cx, pose.cy, pose.w, pose.h, 1f, GOLD);
        if (tile.caption != null && !tile.caption.isBlank()) {
            float pad = 8f;
            float textW = Math.max(24f, pose.w - pad * 2f);
            float top = pose.cy + pose.h / 2f - pad;
            float floor = pose.cy - pose.h / 2f + pad;
            if (tile.amount >= 0) floor += 36f;
            if (tile.name == null || tile.name.isBlank()) {
                batch.setColor(0.10f, 0.11f, 0.13f, 1f);
                batch.draw(pixel(), pose.cx - pose.w / 2f, pose.cy - pose.h / 2f, pose.w, pose.h);
                batch.setColor(Color.WHITE);
            } else {
                batch.setColor(0.05f, 0.05f, 0.06f, 0.72f);
                batch.draw(pixel(), pose.cx - pose.w / 2f, pose.cy - pose.h / 2f, pose.w, Math.min(pose.h * 0.42f, 72f));
                batch.setColor(Color.WHITE);
                top = pose.cy - pose.h / 2f + Math.min(pose.h * 0.42f, 72f) - 4f;
            }
            drawWrapped(stripFont, tile.caption, pose.cx - textW / 2f, top, textW, floor, Color.WHITE);
            stripFont.setColor(Color.WHITE);
        }
        if (orderMark > 0) {
            String num = Integer.toString(orderMark);
            float nx = pose.cx - pose.w / 2f + 8f;
            float ny = pose.cy + pose.h / 2f - 8f;
            batch.setColor(0.05f, 0.04f, 0.03f, 0.88f);
            drawTex(pixel(), nx + 11f, ny - 14f, 24f, 24f, 0f);
            batch.setColor(Color.WHITE);
            font.setColor(GOLD);
            font.draw(batch, num, nx, ny);
            font.setColor(Color.WHITE);
        }
    }

    /** TOP or BOTTOM over one end of the order row. The word only, not a bar. */
    private void drawEndWord(String word, float cx, float cy) {
        glyphs.setText(stackFont, word);
        float bw = glyphs.width + 18f;
        float bh = glyphs.height + 12f;
        batch.setColor(0.08f, 0.08f, 0.09f, 0.92f);
        drawTex(pixel(), cx, cy, bw, bh, 0f);
        batch.setColor(Color.WHITE);
        drawCenteredOn(stackFont, word, cx, cy, GOLD);
    }

    /** Clickable scry/surveil pile plate. Gold when that side is the current choice. */
    private void drawPlaceWord(String word, float cx, float cy, float bw, float bh, boolean chosen) {
        batch.setColor(chosen ? 0.22f : 0.08f, chosen ? 0.18f : 0.08f, chosen ? 0.08f : 0.09f, 0.94f);
        drawTex(pixel(), cx, cy, bw, bh, 0f);
        batch.setColor(Color.WHITE);
        if (chosen) {
            drawCardBorder(cx, cy, bw, bh, 1f, GOLD);
        }
        drawCenteredOn(stackFont, word, cx, cy, GOLD);
    }

    /** True when the click landed on a scry TOP or BOTTOM plate. */
    private boolean pressPlaceZone() {
        for (UiButton button : placeControls) {
            if (!button.contains(mx, my)) continue;
            if ("place-top".equals(button.id)) live.placeOnTop();
            else live.placeOnBottom();
            return true;
        }
        return false;
    }

    private void drawLiveChoice(float w, float h) {
        textChoiceHits.clear();
        textChoiceIndex.clear();
        textChoiceOk = null;
        textChoiceSearch = null;
        textChoiceAll = null;
        hoverFace = -1;
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null || pic.choiceLabels.isEmpty()) return;
        syncTypeChoice(pic);
        boolean faces = pic.facePick && pic.choiceFaces.size() >= pic.choiceLabels.size();
        boolean typeList = pic.typeChoice && !faces;
        boolean wantSearch = typeList && pic.choiceLabels.size() >= 8;
        List<Integer> visible = wantSearch
                ? BattlefieldLayout.typeChoiceVisible(pic.choiceLabels, pic.deckTypes, typeQuery, typeShowAll)
                : null;
        if (visible == null) {
            visible = new ArrayList<>();
            for (int i = 0; i < pic.choiceLabels.size(); i++) visible.add(i);
        }
        boolean allChip = wantSearch && pic.deckTypes != null && !pic.deckTypes.isEmpty();
        float searchChrome = wantSearch
                ? BattlefieldLayout.CHOICE_SEARCH_H + BattlefieldLayout.CHOICE_SEARCH_GAP : 0f;
        HandLayout.Pose widthBox = BattlefieldLayout.choicePanel(w, h, Math.max(1, visible.size()));
        float textW = Math.max(40f, widthBox.w - 24f - 16f);
        float[] heights = new float[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            int src = visible.get(i);
            if (faces) {
                heights[i] = BattlefieldLayout.FACE_CHOICE_ROW_MIN;
                continue;
            }
            String label = src < pic.choiceLabels.size() && pic.choiceLabels.get(src) != null
                    ? pic.choiceLabels.get(src) : "";
            glyphs.setText(font, label, Color.WHITE, textW, Align.left, true);
            heights[i] = Math.max(BattlefieldLayout.CHOICE_ROW_MIN, glyphs.height + 16f);
        }
        float stackH = BattlefieldLayout.choiceStackHeight(heights) + searchChrome;
        HandLayout.Pose box = BattlefieldLayout.choicePanelForStack(w, h, stackH);
        boolean needOk = live.choiceNeedsConfirm();
        float bottomChrome = needOk ? 48f : 0f;
        typeMaxScroll = BattlefieldLayout.choiceMaxScroll(box.h,
                BattlefieldLayout.choiceStackHeight(heights), searchChrome, bottomChrome);
        typeScroll = BattlefieldLayout.clampChoiceScroll(typeScroll, typeMaxScroll);
        float[][] rects = BattlefieldLayout.choiceRowRects(box, heights, typeScroll, searchChrome);
        float left = box.cx - box.w / 2f;
        float bottom = box.cy - box.h / 2f;
        textChoiceOk = needOk ? new float[] { left + 24f, bottom + 16f, 160f, 32f } : null;
        if (wantSearch) {
            textChoiceSearch = BattlefieldLayout.choiceSearchRect(box, allChip);
            textChoiceAll = allChip ? BattlefieldLayout.choiceAllRect(box) : null;
        }
        float listBottom = bottom + (needOk ? 56f : 12f);
        float listTop = box.cy + box.h / 2f - 12f - searchChrome;
        float listH = Math.max(8f, listTop - listBottom);
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.05f, 0.06f, 0.07f, 0.94f);
        shapes.rect(left, bottom, box.w, box.h);
        if (wantSearch && textChoiceSearch != null) {
            shapes.setColor(0.14f, 0.15f, 0.17f, 1f);
            shapes.rect(textChoiceSearch[0], textChoiceSearch[1], textChoiceSearch[2], textChoiceSearch[3]);
            if (textChoiceAll != null) {
                boolean on = typeShowAll && typeQuery.isBlank();
                shapes.setColor(on ? 0.45f : 0.16f, on ? 0.62f : 0.17f, on ? 0.28f : 0.19f, 1f);
                shapes.rect(textChoiceAll[0], textChoiceAll[1], textChoiceAll[2], textChoiceAll[3]);
            }
        }
        if (needOk) {
            shapes.setColor(0.93f, 0.86f, 0.55f, 1f);
            shapes.rect(textChoiceOk[0], textChoiceOk[1], textChoiceOk[2], textChoiceOk[3]);
        }
        shapes.end();
        if (wantSearch) {
            batch.begin();
            String shown = typeQuery.isEmpty()
                    ? (typeShowAll || pic.deckTypes == null || pic.deckTypes.isEmpty()
                    ? "Search types" : "Deck types — type to search all")
                    : typeQuery;
            Color queryColor = typeQuery.isEmpty() ? new Color(1f, 1f, 1f, 0.45f) : Color.WHITE;
            drawCenteredOn(font, clipToWidth(font, shown, textChoiceSearch[2] - 16f),
                    textChoiceSearch[0] + textChoiceSearch[2] / 2f,
                    textChoiceSearch[1] + textChoiceSearch[3] / 2f, queryColor);
            if (textChoiceAll != null) {
                boolean on = typeShowAll && typeQuery.isBlank();
                drawCenteredOn(font, "All", textChoiceAll[0] + textChoiceAll[2] / 2f,
                        textChoiceAll[1] + textChoiceAll[3] / 2f, on ? Color.WHITE : new Color(0.82f, 0.82f, 0.78f, 1f));
            }
            batch.end();
        }
        batch.begin();
        batch.flush();
        clipChoiceRows(left + 12f, listBottom, Math.max(1f, box.w - 24f), listH);
        for (int i = 0; i < visible.size() && i < rects.length; i++) {
            float[] row = rects[i];
            if (row == null) continue;
            if (row[1] + row[3] < listBottom || row[1] > listTop) continue;
            int src = visible.get(i);
            textChoiceHits.add(row);
            textChoiceIndex.add(src);
            boolean picked = pic.choicePicked.contains(src);
            boolean hot = pointerIn(row, mx, my);
            if (hot && faces) hoverFace = i;
            drawTextChoiceRow(row[0], row[1], row[2], row[3], picked, hot);
            if (faces && src < pic.choiceFaces.size()) {
                drawFaceChoiceTile(pic.choiceFaces.get(src), row, picked);
            } else {
                String label = src < pic.choiceLabels.size() && pic.choiceLabels.get(src) != null
                        ? pic.choiceLabels.get(src) : "";
                glyphs.setText(font, label, Color.WHITE, textW, Align.left, true);
                float textTop = row[1] + (row[3] + glyphs.height) / 2f;
                font.setColor(picked ? GOLD : Color.WHITE);
                font.draw(batch, label, row[0] + 8f, textTop, textW, Align.left, true);
            }
        }
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        font.setColor(Color.WHITE);
        if (needOk) {
            drawCenteredOn(font, "Confirm", textChoiceOk[0] + textChoiceOk[2] / 2f,
                    textChoiceOk[1] + textChoiceOk[3] / 2f, Color.BLACK);
        }
        batch.end();
    }

    private void syncTypeChoice(BattlefieldLive.Pic pic) {
        boolean type = pic != null && pic.typeChoice && !pic.facePick;
        String first = type && pic.choiceLabels != null && !pic.choiceLabels.isEmpty()
                ? pic.choiceLabels.get(0) : "";
        String last = type && pic.choiceLabels != null && pic.choiceLabels.size() > 1
                ? pic.choiceLabels.get(pic.choiceLabels.size() - 1) : "";
        String sig = !type ? "" : (nullToEmpty(pic.choiceMessage) + "\n" + pic.choiceLabels.size()
                + "\n" + first + "\n" + last);
        if (sig.equals(typeChoiceSig)) return;
        typeChoiceSig = sig;
        typeQuery = "";
        typeScroll = 0f;
        typeShowAll = false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void clipChoiceRows(float x, float y, float rw, float rh) {
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor((int) x, (int) y, Math.max(1, (int) rw), Math.max(1, (int) Math.ceil(rh)));
    }

    /** Picture of that face plus a short type/land caption. Name is not the identifier. */
    private void drawFaceChoiceTile(BattlefieldLive.FacePick face, float[] row, boolean picked) {
        if (face == null || row == null) return;
        float pad = 8f;
        float thumbH = Math.min(BattlefieldLayout.FACE_CHOICE_THUMB_H, Math.max(48f, row[3] - 22f));
        float thumbW = thumbH * HandLayout.CARD_ASPECT;
        float cx = row[0] + pad + thumbW / 2f;
        float cy = row[1] + (row[3] - 16f) / 2f + 8f;
        String name = face.name == null ? "" : face.name;
        Texture tex = name.isBlank() ? null : textureFor(name);
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, cx, cy, thumbW, thumbH, 0f);
        } else {
            batch.setColor(0.10f, 0.11f, 0.13f, 1f);
            drawTex(pixel(), cx, cy, thumbW, thumbH, 0f);
            batch.setColor(Color.WHITE);
        }
        drawCardBorder(cx, cy, thumbW, thumbH, 1f, picked ? GOLD : FRAME_STEEL);
        String type = face.typeLine == null || face.typeLine.isBlank() ? landHint(name) : face.typeLine;
        if (type != null && !type.isBlank()) {
            drawCenteredOn(stripFont, type, row[0] + row[2] / 2f, row[1] + 12f, picked ? GOLD : Color.WHITE);
            stripFont.setColor(Color.WHITE);
        }
    }

    /** Hover preview of the face under the pointer. Offset so the row stays sweepable. */
    private void drawFaceChoicePreview(float w, float h) {
        if (cardZoom || hoverFace < 0 || !liveReady()) return;
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null || hoverFace >= pic.choiceFaces.size() || hoverFace >= textChoiceHits.size()) return;
        BattlefieldLive.FacePick face = pic.choiceFaces.get(hoverFace);
        float[] row = textChoiceHits.get(hoverFace);
        if (face == null || row == null) return;
        float tileCx = row[0] + row[2] / 2f;
        float tileCy = row[1] + row[3] / 2f;
        boolean growUp = tileCy < h * 0.55f;
        HandLayout.Pose preview = BattlefieldLayout.boardHoverPreview(w, h, tileCx, tileCy, row[2], row[3], growUp);
        String name = face.name == null ? "" : face.name;
        Texture tex = name.isBlank() ? null : textureFor(name);
        batch.begin();
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, preview.cx, preview.cy, preview.w, preview.h, 0f);
            drawCardBorder(preview.cx, preview.cy, preview.w, preview.h, 1f, FRAME_STEEL);
        } else {
            HandLayout.Pose plate = missingArtAt(preview.cx, preview.cy, w, h);
            String type = face.typeLine == null || face.typeLine.isBlank() ? landHint(name) : face.typeLine;
            drawMissingFacePlate(name, type, plate);
        }
        drawOtherFacePick(w, h, preview, BattlefieldLive.otherChoiceFace(pic.choiceFaces, hoverFace), true);
        batch.end();
    }

    /** 260×156 stand-in when that face has no scan. Type stays readable. */
    private void drawMissingFacePlate(String name, String type, HandLayout.Pose plate) {
        float x = plate.cx - plate.w / 2f;
        float y = plate.cy - plate.h / 2f;
        batch.setColor(0.10f, 0.11f, 0.13f, 1f);
        batch.draw(pixel(), x, y, plate.w, plate.h);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.95f);
        float edge = 2f;
        batch.draw(pixel(), x, y, plate.w, edge);
        batch.draw(pixel(), x, y + plate.h - edge, plate.w, edge);
        batch.draw(pixel(), x, y, edge, plate.h);
        batch.draw(pixel(), x + plate.w - edge, y, edge, plate.h);
        batch.setColor(Color.WHITE);
        float pad = 10f;
        String shown = type != null && !type.isBlank() ? type : (name == null ? "" : name);
        drawWrapped(stripFont, shown, x + pad, y + plate.h - pad, Math.max(40f, plate.w - pad * 2f),
                y + pad, GOLD);
        stripFont.setColor(Color.WHITE);
    }

    private static String landHint(String name) {
        if (name == null || name.isBlank()) return "";
        return name;
    }

    /** Right-click a face row opens the same oracle zoom used on the board. */
    private boolean openFaceChoiceZoom() {
        if (!liveReady() || !live.facePickOpen() || hoverFace < 0) return false;
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null || hoverFace >= pic.choiceFaces.size()) return false;
        BattlefieldLive.FacePick face = pic.choiceFaces.get(hoverFace);
        if (face == null || face.name == null || face.name.isBlank()) return false;
        openCardZoom(face.name, textureFor(face.name), null, true);
        return true;
    }

    /**
     * Right-click a library-search / grave / exile / scry picture. Looks at the
     * resting tile, not the hover preview. Does not pick or submit.
     */
    private boolean openSheetZoom() {
        if (!liveReady() || !live.cardSheetOpen()) return false;
        int hit = sheetHit(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        BattlefieldLive.ShownCard tile = sheetTile(hit);
        if (tile == null || tile.name == null || tile.name.isBlank()) return false;
        openCardZoom(tile.name, textureFor(tile.name), null, true);
        return true;
    }

    private BattlefieldLive.ShownCard sheetTile(int index) {
        if (index < 0 || !liveReady()) return null;
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null || index >= pic.sheet.size()) return null;
        return pic.sheet.get(index);
    }

    /**
     * Full card above the search/reveal row. Neighbours stay in the row. Hit tests
     * stay on the resting tile so sweeping the mouse does not scramble overlap.
     */
    private void drawSheetPreview(float w, float h) {
        if (cardZoom || draggingSheet || draggingOrder || orderGrip >= 0) return;
        if (!liveReady() || !live.cardSheetOpen()) return;
        BattlefieldLive.Pic pic = live.picture();
        if (pic == null || pic.sheet.isEmpty()) return;
        int hit = sheetHit(w, h);
        BattlefieldLive.ShownCard tile = sheetTile(hit);
        if (tile == null || tile.name == null || tile.name.isBlank()) return;
        HandLayout.Pose rest;
        if (pic.mayCast) {
            HandLayout.Pose[] cascade = BattlefieldLayout.cascadeRow(
                    w, h, pic.sheet.size(), pic.hitIndex, sheetScroll, sheetHandBand());
            rest = hit >= 0 && hit < cascade.length ? cascade[hit]
                    : BattlefieldLayout.revealedSlot(w, h, pic.sheet.size(), hit, sheetScroll, sheetHandBand());
        } else {
            rest = BattlefieldLayout.revealedSlot(
                    w, h, pic.sheet.size(), hit, sheetScroll, sheetHandBand());
        }
        HandLayout.Pose preview = BattlefieldLayout.boardHoverPreview(
                w, h, rest.cx, rest.cy, rest.w, rest.h, true);
        Texture tex = textureFor(tile.name);
        batch.begin();
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, preview.cx, preview.cy, preview.w, preview.h, 0f);
            drawCardBorder(preview.cx, preview.cy, preview.w, preview.h, 1f, FRAME_STEEL);
        } else {
            drawOracleFrame(tile.name, missingArtAt(preview.cx, preview.cy, w, h));
        }
        drawOtherFaceBeside(w, h, preview, tile.name, null, null, true);
        batch.end();
    }

    /** Targeting line starts on the command-zone slot when that commander is the source. */
    private float[] commandTargetOrigin() {
        if (!liveReady() || !live.choosingTarget()) return null;
        String spell = live.targetSpellName();
        if (spell == null || spell.isBlank()) return null;
        String lower = spell.toLowerCase(Locale.ROOT);
        for (CommandSlot slot : commandSlots) {
            if (slot == null || slot.seat == null || !slot.seat.inCommand) continue;
            String name = slot.seat.name;
            if (name != null && !name.isBlank() && lower.contains(name.toLowerCase(Locale.ROOT))) {
                return new float[] {slot.cx, slot.cy};
            }
        }
        return null;
    }

    /** Compact name plate. Same spot as a full preview, without the empty card frame. */
    private HandLayout.Pose missingArtAt(float cx, float cy, float windowW, float windowH) {
        float pw = BattlefieldLayout.missingArtWidth(windowH);
        float ph = BattlefieldLayout.missingArtHeight(windowH);
        float x = Math.max(pw / 2f + 8f, Math.min(windowW - pw / 2f - 8f, cx));
        float y = Math.max(ph / 2f + 8f, Math.min(windowH - ph / 2f - 8f, cy));
        return new HandLayout.Pose(x, y, pw, ph, 0f, 1f);
    }

    /** Owner hover on a face-down exile tile. Opponent never sees the face. */
    private void drawExilePreview(float w, float h) {
        if (cardZoom || hoverExile < 0 || !hoverExileYou) return;
        ZoneCard tile = exileTileAt(true, hoverExile);
        if (tile == null || !tile.ownerSees) return;
        BattlefieldLayout.PlayerCluster cluster = BattlefieldLayout.clusterAt(
                w, h, BattlefieldLayout.StripOwner.YOU, youDx, youDy);
        HandLayout.Pose rest = exileTilePose(cluster.exile, hoverExile, hiddenExile(true).size(), true);
        HandLayout.Pose preview = HandLayout.previewBesideFan(h, rest.cx, rest.cy + rest.h / 2f, false);
        float half = preview.w / 2f;
        float cx = Math.max(half + 8f, Math.min(w - half - 8f, preview.cx));
        preview = new HandLayout.Pose(cx, preview.cy, preview.w, preview.h, 0f, preview.scale);
        Texture face = textureFor(tile.name);
        if (face != null) {
            batch.setColor(Color.WHITE);
            drawTex(face, preview.cx, preview.cy, preview.w, preview.h, 0f);
        } else {
            drawOracleFrame(tile.name, missingArtAt(preview.cx, preview.cy, w, h));
        }
        drawOtherFaceBeside(w, h, preview, tile.name, tile.forgeCard, tile.paper, true);
    }

    /** Readable stand-in when the ghost has no scan. Same size as the full-card hover, not a blank plate. */
    private void drawOracleFrame(String name, HandLayout.Pose preview) {
        float x = preview.cx - preview.w / 2f;
        float y = preview.cy - preview.h / 2f;
        batch.setColor(0.10f, 0.11f, 0.13f, 1f);
        batch.draw(pixel(), x, y, preview.w, preview.h);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.95f);
        float edge = 3f;
        batch.draw(pixel(), x, y, preview.w, edge);
        batch.draw(pixel(), x, y + preview.h - edge, preview.w, edge);
        batch.draw(pixel(), x, y, edge, preview.h);
        batch.draw(pixel(), x + preview.w - edge, y, edge, preview.h);
        batch.setColor(Color.WHITE);
        float pad = 14f;
        float textX = x + pad;
        float textW = Math.max(40f, preview.w - pad * 2f);
        float textTop = y + preview.h - pad;
        float floor = y + pad;
        String shown = name == null ? "" : name;
        float next = drawWrapped(stackFont, shown, textX, textTop, textW, floor, GOLD);
        drawWrapped(stripFont, oracleText(shown), textX, next - 8f, textW, floor, Color.WHITE);
        stackFont.setColor(Color.WHITE);
        stripFont.setColor(Color.WHITE);
    }

    /** Who won, on the left. The buttons that leave it sit on the action row. */
    private void drawResult(float w, float h) {
        float boxW = Math.min(460f, Math.max(240f, askPanelWidth(w, h)));
        float boxH = 168f;
        float left = 16f;
        float bottom = Math.max(16f, h * 0.5f - boxH / 2f);
        batch.setColor(0.07f, 0.08f, 0.09f, 0.94f);
        batch.draw(pixel(), left, bottom, boxW, boxH);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.95f);
        float edge = 3f;
        batch.draw(pixel(), left, bottom, boxW, edge);
        batch.draw(pixel(), left, bottom + boxH - edge, boxW, edge);
        batch.draw(pixel(), left, bottom, edge, boxH);
        batch.draw(pixel(), left + boxW - edge, bottom, edge, boxH);
        batch.setColor(Color.WHITE);
        String title = resultTitle == null || resultTitle.isBlank() ? "Game over" : resultTitle;
        drawCentered(big, title, left + boxW / 2f, bottom + boxH - 36f, Color.WHITE);
        if (resultWhy != null && !resultWhy.isBlank()) {
            drawCentered(stackFont, resultWhy, left + boxW / 2f, bottom + 56f, GOLD);
        }
    }

    /** Full card grown off the tile. An empty gap has no permanent, so it draws nothing. */
    private void drawBoardPreview(float w, float h) {
        if (cardZoom || blockingDrag() || draggingBoard || pressedPerm != null
                || hoverPerm == null || !hoverPerm.alive) return;
        if (hoverPerm.kind == BattlefieldLayout.Kind.PLANESWALKER) return;
        if (hoverPerm.kind == BattlefieldLayout.Kind.ENCHANTMENT && !hoverPerm.tucked
                && !enchantmentNeedsHover(hoverPerm)) return;
        if (hoverPerm.kind == BattlefieldLayout.Kind.BATTLE && !siegeNeedsHover(hoverPerm)) return;
        HandLayout.Pose preview = boardPreviewPose(w, h, hoverPerm);
        if (hoverPerm.tex == null) {
            drawOracleFrame(hoverPerm.name, missingArtAt(preview.cx, preview.cy, w, h));
            drawOtherFaceBeside(w, h, preview, hoverPerm.name, hoverPerm.forgeCard, null, true);
            return;
        }
        batch.setColor(Color.WHITE);
        drawTex(hoverPerm.tex, preview.cx, preview.cy, preview.w, preview.h, 0f);
        drawCardBorder(preview.cx, preview.cy, preview.w, preview.h, 1f, frameColor(hoverPerm));
        if (lightsOut(hoverPerm)) drawLightsOut(preview.cx, preview.cy, preview.w, preview.h, 1f);
        drawOtherFaceBeside(w, h, preview, hoverPerm.name, hoverPerm.forgeCard, null, true);
    }

    /**
     * Full-card hover for a commander still in the command zone.
     * Resting slot stays put. Away words get no preview art.
     */
    private void drawCommandPreview(float w, float h) {
        if (cardZoom || draggingCommand || hoverCommand < 0 || hoverCommand >= commandSlots.size()) return;
        CommandSlot slot = commandSlots.get(hoverCommand);
        BattlefieldLive.CommanderSeat seat = slot.seat;
        if (seat == null || !seat.inCommand) return;
        HandLayout.Pose preview = BattlefieldLayout.boardHoverPreview(
                w, h, slot.cx, slot.cy, slot.cw, slot.ch, slot.you);
        Texture tex = textureFor(seat.name);
        CardView view = seat.card == null ? null : seat.card.view;
        if (tex == null) {
            drawOracleFrame(seat.name, missingArtAt(preview.cx, preview.cy, w, h));
            drawOtherFaceBeside(w, h, preview, seat.name, view, null, true);
            return;
        }
        batch.setColor(Color.WHITE);
        drawTex(tex, preview.cx, preview.cy, preview.w, preview.h, 0f);
        drawCardBorder(preview.cx, preview.cy, preview.w, preview.h, 1f, FRAME_STEEL);
        drawOtherFaceBeside(w, h, preview, seat.name, view, null, true);
    }

    /**
     * Same full-card hover as the board. The small side showcase stays put;
     * this plate is draw-only and is not a click target.
     */
    private void drawStackPreview(float w, float h) {
        if (cardZoom || hoverStack == null || hideStackForTravel(hoverStack)) return;
        if (hoverStack.summonDelay > 0f) return;
        HandLayout.Pose tile = stackDrawnPose(hoverStack);
        if (tile == null || tile.w < 2f || tile.h < 2f) return;
        boolean growUp = tile.cy < h * 0.5f;
        HandLayout.Pose preview = BattlefieldLayout.boardHoverPreview(
                w, h, tile.cx, tile.cy, tile.w, tile.h, growUp);
        Texture tex = hoverStack.card != null && hoverStack.card.tex != null
                ? hoverStack.card.tex : textureFor(hoverStack.name);
        if (tex == null) {
            drawOracleFrame(hoverStack.name, missingArtAt(preview.cx, preview.cy, w, h));
            drawOtherFaceBeside(w, h, preview, hoverStack.name, hoverStack.forgeCard, null, true);
            return;
        }
        batch.setColor(Color.WHITE);
        drawTex(tex, preview.cx, preview.cy, preview.w, preview.h, 0f);
        drawCardBorder(preview.cx, preview.cy, preview.w, preview.h, 1f, FRAME_STEEL);
        drawOtherFaceBeside(w, h, preview, hoverStack.name, hoverStack.forgeCard, null, true);
    }

    private void drawPhaseStripShapes(float w, float h) {
        BattlefieldLayout.PhaseBox[] boxes = BattlefieldLayout.phaseStrip(w, h, youDx, youDy, enemyDx, enemyDy);
        for (int i = 0; i < boxes.length; i++) {
            BattlefieldLayout.PhaseBox box = boxes[i];
            boolean current = box.owner == activeOwner && box.phase == shownPhase;
            boolean hot = i == stripHover;
            if (yieldOn(box)) shapes.setColor(0.72f, 0.16f, 0.14f, 0.98f);
            else shapes.setColor(0.16f, 0.55f, 0.24f, 0.98f);
            shapes.rect(box.x - box.w / 2f, box.y - box.h / 2f, box.w, box.h);
            float edge = current ? 2f : 1f;
            if (current) shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
            else if (hot) shapes.setColor(1f, 1f, 0.9f, 0.9f);
            else shapes.setColor(0f, 0f, 0f, 0.35f);
            float left = box.x - box.w / 2f;
            float bottom = box.y - box.h / 2f;
            shapes.rect(left, bottom, box.w, edge);
            shapes.rect(left, bottom + box.h - edge, box.w, edge);
            shapes.rect(left, bottom, edge, box.h);
            shapes.rect(left + box.w - edge, bottom, edge, box.h);
        }
        outlineStripBand(boxes, BattlefieldLayout.StripOwner.YOU, GREEN);
        outlineStripBand(boxes, BattlefieldLayout.StripOwner.ENEMY, RED);
        if (stripHover < 0 || stripHover >= boxes.length) return;
        BattlefieldLayout.PhaseBox hot = boxes[stripHover];
        boolean current = hot.owner == activeOwner && hot.phase == shownPhase;
        String tip = BattlefieldLayout.phaseHoverText(hot.phase, yieldOn(hot), hot.owner, current);
        HandLayout.Pose panel = BattlefieldLayout.hoverPanelNear(w, h, hot, tip);
        shapes.setColor(0.06f, 0.06f, 0.07f, 0.98f);
        shapes.rect(panel.cx - panel.w / 2f, panel.cy - panel.h / 2f, panel.w, panel.h);
        shapes.setColor(0.93f, 0.86f, 0.62f, 1f);
        float left = panel.cx - panel.w / 2f;
        float bottom = panel.cy - panel.h / 2f;
        shapes.rect(left, bottom, panel.w, 2f);
        shapes.rect(left, bottom + panel.h - 2f, panel.w, 2f);
        shapes.rect(left, bottom, 2f, panel.h);
        shapes.rect(left + panel.w - 2f, bottom, 2f, panel.h);
    }

    /** Thin band around every chip on that row. Green is his. Red is theirs. */
    private void outlineStripBand(BattlefieldLayout.PhaseBox[] boxes, BattlefieldLayout.StripOwner owner,
                                   Color color) {
        float[] band = BattlefieldLayout.ownerStripBounds(boxes, owner);
        if (band == null) return;
        float edge = 2f;
        shapes.setColor(color.r, color.g, color.b, 1f);
        shapes.rect(band[0], band[1], band[2], edge);
        shapes.rect(band[0], band[1] + band[3] - edge, band[2], edge);
        shapes.rect(band[0], band[1], edge, band[3]);
        shapes.rect(band[0] + band[2] - edge, band[1], edge, band[3]);
    }

    private void drawPhaseStripText(float w, float h) {
        BattlefieldLayout.PhaseBox[] boxes = BattlefieldLayout.phaseStrip(w, h, youDx, youDy, enemyDx, enemyDy);
        for (int i = 0; i < boxes.length; i++) {
            BattlefieldLayout.PhaseBox box = boxes[i];
            String label = box.label();
            if (label.isEmpty()) continue;
            drawCenteredOn(stripFont, label, box.x, box.y, Color.WHITE);
        }
        if (stripHover >= 0 && stripHover < boxes.length && boxes[stripHover].phase != null) {
            BattlefieldLayout.PhaseBox hot = boxes[stripHover];
            boolean current = hot.owner == activeOwner && hot.phase == shownPhase;
            String text = BattlefieldLayout.phaseHoverText(hot.phase, yieldOn(hot), hot.owner, current);
            if (text == null) text = "";
            HandLayout.Pose panel = BattlefieldLayout.hoverPanelNear(w, h, hot, text);
            font.setColor(Color.WHITE);
            float textW = Math.max(1f, panel.w - 18f);
            glyphs.setText(font, text, Color.WHITE, textW, Align.left, true);
            float textTop = panel.cy + panel.h / 2f - 8f;
            font.draw(batch, text, panel.cx - textW / 2f, textTop, textW, Align.left, true);
        }
        font.setColor(Color.WHITE);
    }

    private void drawArrows(float w, float h) {
        if (liveReady() && live.choosingTarget() && examining < 0) drawTargetFollow(w, h);
        else if (scriptedTargetRubber()) drawScriptedTargetFollow();
        drawSpellTargetLines();
        if (blockingDrag()) {
            Perm stick = attackerUnderPointer();
            float x0 = pressedPerm.cx;
            float y0 = pressedPerm.cy;
            float x1 = stick != null ? stick.cx : mx;
            float y1 = stick != null ? stick.cy : my;
            float[] from = cardRim(pressedPerm, x1, y1);
            if (stick != null) {
                float[] to = cardRim(stick, x0, y0);
                drawCombatBeam(from[0], from[1], to[0], to[1]);
            } else {
                drawCombatBeam(from[0], from[1], x1, y1);
            }
        }
        for (Perm p : board) {
            if (!p.alive || !p.attacking) continue;
            List<Perm> blocks = combatBlocks(p);
            if (!blocks.isEmpty()) {
                for (Perm blk : blocks) {
                    float[] from = cardRim(p, blk.cx, blk.cy);
                    float[] to = cardRim(blk, p.cx, p.cy);
                    drawCombatBeam(from[0], from[1], to[0], to[1]);
                }
                continue;
            }
            attackBadge(p);
            if (draggingBoard && p == pressedPerm && phase == Phase.ATTACKERS) {
                float x1 = mx;
                float y1 = my;
                if (BattlefieldLayout.declaresAttack(h, mx, my, themBadge.x, themBadge.y)) {
                    x1 = themBadge.x;
                    y1 = themBadge.y;
                }
                float[] from = cardRim(p, x1, y1);
                sleekArrow(from[0], from[1], x1, y1, ARROW_ATTACK);
                continue;
            }
            float[] aim = attackAim(p);
            float[] from = cardRim(p, aim[0], aim[1]);
            drawAttackArrow(from[0], from[1], aim[0], aim[1]);
        }
        drawStackSourceCurve();
    }

    /** Assigned blockers on either side, including a block Forge recorded by card id. */
    private List<Perm> combatBlocks(Perm attacker) {
        List<Perm> out = new ArrayList<>();
        if (attacker == null) return out;
        int id = attacker.forgeCard == null ? 0 : attacker.forgeCard.getId();
        for (Perm p : board) {
            if (!p.alive || p == attacker) continue;
            boolean linked = p.blockTarget == attacker;
            if (!linked && id != 0 && p.blockAttackerIds != null) {
                for (int attackerId : p.blockAttackerIds) {
                    if (attackerId == id) linked = true;
                }
            }
            if (linked) out.add(p);
        }
        return out;
    }

    /**
     * Where this unblocked attacker is headed. A planeswalker beats the life total.
     * His attackers aim at Ash. Hers aim at him.
     */
    private float[] attackAim(Perm attacker) {
        Perm walker = attackPlaneswalker(attacker);
        if (walker != null) return cardRim(walker, attacker.cx, attacker.cy);
        UiButton life = attacker.you ? themBadge : youBadge;
        BattlefieldLive.Strike strike = strikeFor(attacker);
        if (strike != null && strike.planeswalkerId == 0) {
            life = strike.defenderIsYou ? youBadge : themBadge;
        }
        return boxRim(life.x, life.y, life.w, life.h, attacker.cx, attacker.cy);
    }

    private Perm attackPlaneswalker(Perm attacker) {
        BattlefieldLive.Strike strike = strikeFor(attacker);
        if (strike == null || strike.planeswalkerId == 0) return null;
        Perm walker = permById(strike.planeswalkerId);
        if (walker == null || !walker.alive) return null;
        return walker;
    }

    private BattlefieldLive.Strike strikeFor(Perm attacker) {
        if (attacker == null || attacker.forgeCard == null) return null;
        int id = attacker.forgeCard.getId();
        for (BattlefieldLive.Strike strike : shownStrikes) {
            if (strike != null && strike.attackerId == id) return strike;
        }
        return null;
    }

    /** Edge of a life widget facing the attacker, so the head stays off the number. */
    private float[] boxRim(float cx, float cy, float bw, float bh, float fromX, float fromY) {
        float dx = cx - fromX;
        float dy = cy - fromY;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return new float[] { cx, cy };
        dx /= len;
        dy /= len;
        float hx = Math.max(1f, bw) / 2f;
        float hy = Math.max(1f, bh) / 2f;
        float sx = Math.abs(dx) < 0.001f ? Float.MAX_VALUE : hx / Math.abs(dx);
        float sy = Math.abs(dy) < 0.001f ? Float.MAX_VALUE : hy / Math.abs(dy);
        float reach = Math.min(sx, sy);
        return new float[] { cx - dx * reach, cy - dy * reach };
    }

    /** Orange beam with a head. The start is already above the attacker's name. */
    private void drawAttackArrow(float x0, float y0, float x1, float y1) {
        drawCombatBeam(x0, y0, x1, y1);
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;
        dx /= len;
        dy /= len;
        float reach = 16f;
        float wing = 9f;
        float bx = x1 - dx * reach;
        float by = y1 - dy * reach;
        shapes.setColor(1f, 0.55f, 0.12f, 0.95f);
        shapes.triangle(x1, y1, bx - dy * wing, by + dx * wing, bx + dy * wing, by - dx * wing);
    }

    /** Glowing orange beam between two combatants. Same stroke for a drag-block. */
    private void drawCombatBeam(float x0, float y0, float x1, float y1) {
        shapes.setColor(1f, 0.42f, 0.06f, 0.28f);
        shapes.rectLine(x0, y0, x1, y1, 16f);
        shapes.setColor(1f, 0.62f, 0.16f, 0.72f);
        shapes.rectLine(x0, y0, x1, y1, 7f);
        shapes.setColor(1f, 0.95f, 0.78f, 0.95f);
        shapes.rectLine(x0, y0, x1, y1, 2.4f);
    }

    /**
     * Stack-well card, shifted further off any legal or assigned target so that
     * permanent stays visible and clickable. Never slides left onto the beds.
     */
    private HandLayout.Pose placeShowcase(float w, float h, StackItem item) {
        HandLayout.Pose pose = stackArt(item)
                ? BattlefieldLayout.stackShowcase(w, h)
                : BattlefieldLayout.missingArtPanel(w, h);
        List<float[]> boxes = showcaseObstacles(item);
        if (boxes.isEmpty()) return pose;
        float cx = pose.cx;
        float cy = pose.cy;
        float cw = pose.w;
        float ch = pose.h;
        if (!coversAny(cx, cy, cw, ch, boxes)) return pose;
        float maxCx = w - cw / 2f - 8f;
        float tryCx = cx;
        while (tryCx < maxCx && coversAny(tryCx, cy, cw, ch, boxes)) {
            tryCx = Math.min(maxCx, tryCx + 12f);
        }
        if (!coversAny(tryCx, cy, cw, ch, boxes)) {
            return new HandLayout.Pose(tryCx, cy, cw, ch, 0f, 1f);
        }
        cx = tryCx;
        float targetY = 0f;
        for (float[] box : boxes) targetY += box[1];
        targetY /= boxes.size();
        float minY = ch / 2f + 8f;
        float maxY = h - ch / 2f - 8f;
        float above = targetY + ch / 2f + 40f;
        float below = targetY - ch / 2f - 40f;
        float tryY = cy;
        if (targetY < h * 0.5f && above <= maxY) tryY = above;
        else if (below >= minY) tryY = below;
        else tryY = Math.max(minY, Math.min(maxY, targetY >= h * 0.5f ? below : above));
        if (!coversAny(cx, tryY, cw, ch, boxes)) cy = tryY;
        else {
            cx = maxCx;
            cy = tryY;
        }
        return new HandLayout.Pose(cx, cy, cw, ch, 0f, 1f);
    }

    /** Assigned targets plus every legal permanent while he must choose one. */
    private List<float[]> showcaseObstacles(StackItem item) {
        List<float[]> boxes = new ArrayList<>();
        if (item != null && item.targetCardIds != null) {
            for (int id : item.targetCardIds) addShowcaseObstacle(boxes, permById(id));
        }
        if (liveReady() && live.choosingTarget()) {
            for (Perm perm : board) {
                if (perm.alive && live.legalTarget(perm.forgeCard)) addShowcaseObstacle(boxes, perm);
            }
        } else if (targeting() && !topHasTarget()) {
            for (Perm perm : board) {
                if (perm.alive && perm.kind == BattlefieldLayout.Kind.CREATURE) {
                    addShowcaseObstacle(boxes, perm);
                }
            }
        }
        return boxes;
    }

    private static void addShowcaseObstacle(List<float[]> boxes, Perm perm) {
        if (perm == null || !perm.alive) return;
        float tw = perm.tw > 8f ? perm.tw : perm.w;
        float th = perm.th > 8f ? perm.th : perm.h;
        boxes.add(new float[] {perm.cx, perm.cy, tw, th});
    }

    private static boolean coversAny(float cx, float cy, float cw, float ch, List<float[]> boxes) {
        for (float[] box : boxes) {
            if (coversBox(cx, cy, cw, ch, box)) return true;
        }
        return false;
    }

    private static boolean coversBox(float cx, float cy, float cw, float ch, float[] box) {
        float tw = box.length > 2 ? box[2] : 40f;
        float th = box.length > 3 ? box[3] : 40f;
        return Math.abs(box[0] - cx) < (cw + tw) / 2f + 16f
                && Math.abs(box[1] - cy) < (ch + th) / 2f + 16f;
    }

    /** A legal target under the pointer keeps the click. The showcase must not eat it. */
    private boolean targetClickThroughStack(float x, float y) {
        if (!liveReady() || !live.choosingTarget()) return false;
        Perm perm = hitBoard(x, y);
        return perm != null && perm.alive && live.legalTarget(perm.forgeCard);
    }

    /** A scan on the card, or one found by name. Missing art uses the compact panel. */
    private boolean stackArt(StackItem item) {
        if (item == null) return false;
        if (item.card != null && item.card.tex != null) return true;
        return item.name != null && !item.name.isBlank() && textureFor(item.name) != null;
    }

    /** Curve starts on the big card's edge and ends on each target. It stays outside the art. */
    private void drawStackSourceCurve() {
        StackItem top = top();
        if (top == null || !top.showcase || examining >= 0 || !stackTravelReady()) return;
        if (hasSpellTargets(top)) return;
        float left = top.rowX - top.rowW / 2f;
        float right = top.rowX + top.rowW / 2f;
        float bottom = top.rowY - top.rowH / 2f;
        float topEdge = top.rowY + top.rowH / 2f;
        List<float[]> ends = curveEnds(top);
        for (float[] end : ends) {
            float[] start = edgeToward(left, right, bottom, topEdge, top.rowX, top.rowY, end[0], end[1]);
            float x1 = end[0];
            float y1 = end[1];
            if (end.length > 2 && end[2] > 0.5f) {
                Perm perm = permById((int) end[3]);
                if (perm != null && perm.alive) {
                    float[] rim = cardRim(perm, start[0], start[1]);
                    x1 = rim[0];
                    y1 = rim[1];
                }
            }
            drawAroundCurve(start[0], start[1], x1, y1, top);
        }
    }

    private List<float[]> curveEnds(StackItem top) {
        List<float[]> ends = new ArrayList<>();
        if (top.targetCardIds != null) {
            for (int id : top.targetCardIds) {
                Perm perm = permById(id);
                if (perm != null && perm.alive) ends.add(new float[] {perm.cx, perm.cy, 1f, id});
            }
        }
        if (!ends.isEmpty()) return ends;
        if (top.targetThem) ends.add(new float[] {themBadge.x, themBadge.y, 0f, 0f});
        else if (top.targetYou) ends.add(new float[] {youBadge.x, youBadge.y, 0f, 0f});
        else if (top.sourceOnBoard) {
            Perm src = permById(top.sourceId);
            if (src != null && src.alive) ends.add(new float[] {src.cx, src.cy, 1f, top.sourceId});
        }         else if (top.sourceYours) {
            ends.add(handRegionEnd());
        }
        return ends;
    }

    /**
     * A cast with no permanent or player target still comes from the hand.
     * The tip sits in the open felt above the fan, centred on the hand, so it
     * does not land on one card as if that card were the target.
     */
    private float[] handRegionEnd() {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        int n = Math.max(1, live().size());
        float fanTop = 0f;
        for (int i = 0; i < n; i++) {
            HandLayout.Pose pose = HandLayout.restPose(w, h, n, i);
            double rad = Math.toRadians(pose.rotationDeg);
            float lift = (float) (Math.abs(pose.w * Math.sin(rad)) + Math.abs(pose.h * Math.cos(rad))) / 2f;
            fanTop = Math.max(fanTop, pose.cy + lift);
        }
        float gap = Math.max(48f, h * 0.07f);
        float y = fanTop + gap;
        float ceiling = BattlefieldLayout.dividerY(h) - 24f;
        if (y > ceiling) y = Math.max(fanTop + 16f, ceiling);
        return new float[] { w / 2f, y, 0f, 0f };
    }

    private static float[] edgeToward(float left, float right, float bottom, float top,
                                      float cx, float cy, float tx, float ty) {
        float dx = tx - cx;
        float dy = ty - cy;
        float hw = Math.max(1f, (right - left) / 2f);
        float hh = Math.max(1f, (top - bottom) / 2f);
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) return new float[] {left, cy};
        float sx = hw / Math.max(Math.abs(dx), 0.001f);
        float sy = hh / Math.max(Math.abs(dy), 0.001f);
        float reach = Math.min(sx, sy);
        return new float[] {cx + dx * reach, cy + dy * reach};
    }

    private void drawAroundCurve(float x0, float y0, float x1, float y1, StackItem card) {
        float cx = card.rowX;
        float cy = card.rowY;
        float hw = Math.max(1f, card.rowW / 2f);
        float hh = Math.max(1f, card.rowH / 2f);
        float mx = (x0 + x1) * 0.5f - cx;
        float my = (y0 + y1) * 0.5f - cy;
        float len = (float) Math.hypot(mx, my);
        float ox = len < 1f ? -80f : mx / len * 80f;
        float oy = len < 1f ? 0f : my / len * 80f;
        float cpx = (x0 + x1) * 0.5f + ox;
        float cpy = (y0 + y1) * 0.5f + oy;
        float px = x0;
        float py = y0;
        int steps = 18;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float u = 1f - t;
            float x = u * u * x0 + 2f * u * t * cpx + t * t * x1;
            float y = u * u * y0 + 2f * u * t * cpy + t * t * y1;
            if (Math.abs(x - cx) < hw && Math.abs(y - cy) < hh) {
                float dx = x - cx;
                float dy = y - cy;
                float sx = Math.abs(dx) / hw;
                float sy = Math.abs(dy) / hh;
                float s = Math.max(0.001f, Math.max(sx, sy));
                x = cx + dx / s + Math.signum(dx == 0f ? -1f : dx) * 10f;
                y = cy + dy / s + Math.signum(dy == 0f ? 1f : dy) * 10f;
            }
            shapes.setColor(0.78f, 0.88f, 0.98f, 0.92f);
            shapes.rectLine(px, py, x, y, 3.2f);
            px = x;
            py = y;
        }
    }

    private void drawTargetFollow(float w, float h) {
        float[] from = targetLineStart();
        float x1 = mx;
        float y1 = my;
        Perm stick = hitBoard(mx, my);
        if (stick != null && stick.alive && live.legalTarget(stick.forgeCard)) {
            float[] rim = cardRim(stick, from[0], from[1]);
            x1 = rim[0];
            y1 = rim[1];
        } else if (playerTargetHot(false) && themBadge.contains(mx, my)) {
            float[] rim = playerTargetEnd(false, from[0], from[1]);
            x1 = rim[0];
            y1 = rim[1];
        } else if (playerTargetHot(true) && youBadge.contains(mx, my)) {
            float[] rim = playerTargetEnd(true, from[0], from[1]);
            x1 = rim[0];
            y1 = rim[1];
        }
        drawSpellArrow(from[0], from[1], x1, y1);
        for (int id : live.assignedTargetCardIds()) {
            Perm perm = permById(id);
            if (perm == null || !perm.alive || perm == stick) continue;
            float[] rim = cardRim(perm, from[0], from[1]);
            drawSpellArrow(from[0], from[1], rim[0], rim[1]);
        }
        if (live.playerAssigned(true) && !youBadge.contains(mx, my)) {
            float[] rim = playerTargetEnd(true, from[0], from[1]);
            drawSpellArrow(from[0], from[1], rim[0], rim[1]);
        }
        if (live.playerAssigned(false) && !themBadge.contains(mx, my)) {
            float[] rim = playerTargetEnd(false, from[0], from[1]);
            drawSpellArrow(from[0], from[1], rim[0], rim[1]);
        }
    }

    /** Scripted Shock still rubber-bands while he has not locked a target. */
    private boolean scriptedTargetRubber() {
        return targeting() && !topHasTarget() && examining < 0
                && !(liveReady() && live.choosingTarget());
    }

    private void drawScriptedTargetFollow() {
        StackItem top = top();
        if (top == null) return;
        float x1 = mx;
        float y1 = my;
        if (shockHover != null && shockHover.alive) {
            float[] rim = cardRim(shockHover, top.rowX, top.rowY);
            x1 = rim[0];
            y1 = rim[1];
        } else if (shockHoverThem) {
            x1 = themBadge.x;
            y1 = themBadge.y;
        } else if (shockHoverYou) {
            x1 = youBadge.x;
            y1 = youBadge.y;
        }
        float[] from = spellLineOriginToward(top, x1, y1);
        if (from == null) return;
        if (shockHoverThem || shockHoverYou) {
            float[] rim = playerTargetEnd(shockHoverYou, from[0], from[1]);
            x1 = rim[0];
            y1 = rim[1];
        }
        drawSpellArrow(from[0], from[1], x1, y1);
    }

    /**
     * After a target is locked, one gold line from each stack object to each
     * chosen target. Stays until that object leaves the stack. His casts and Ash's.
     */
    private void drawSpellTargetLines() {
        for (StackItem item : stack) {
            if (item == null || !hasSpellTargets(item)) continue;
            if (item.targetCardIds != null) {
                for (int id : item.targetCardIds) {
                    Perm perm = permById(id);
                    if (perm == null || !perm.alive) continue;
                    float[] from = spellLineOriginToward(item, perm.cx, perm.cy);
                    if (from == null) continue;
                    float[] to = cardRim(perm, from[0], from[1]);
                    drawSpellArrow(from[0], from[1], to[0], to[1]);
                }
            }
            if (item.targetPerm != null && item.targetPerm.alive
                    && !alreadyAimedAt(item, item.targetPerm)) {
                Perm perm = item.targetPerm;
                float[] from = spellLineOriginToward(item, perm.cx, perm.cy);
                if (from != null) {
                    float[] to = cardRim(perm, from[0], from[1]);
                    drawSpellArrow(from[0], from[1], to[0], to[1]);
                }
            }
            if (item.targetYou) {
                float[] from = spellLineOriginToward(item, youBadge.x, youBadge.y);
                if (from != null) {
                    float[] to = playerTargetEnd(true, from[0], from[1]);
                    drawSpellArrow(from[0], from[1], to[0], to[1]);
                }
            }
            if (item.targetThem) {
                float[] from = spellLineOriginToward(item, themBadge.x, themBadge.y);
                if (from != null) {
                    float[] to = playerTargetEnd(false, from[0], from[1]);
                    drawSpellArrow(from[0], from[1], to[0], to[1]);
                }
            }
        }
    }

    private static boolean alreadyAimedAt(StackItem item, Perm perm) {
        if (item == null || perm == null || perm.forgeCard == null || item.targetCardIds == null) {
            return false;
        }
        int id = perm.forgeCard.getId();
        for (int cardId : item.targetCardIds) {
            if (cardId == id) return true;
        }
        return false;
    }

    private boolean hasSpellTargets(StackItem item) {
        if (item == null) return false;
        if (item.targetPerm != null && item.targetPerm.alive) return true;
        if (item.targetYou || item.targetThem) return true;
        if (item.targetCardIds == null) return false;
        for (int id : item.targetCardIds) {
            Perm perm = permById(id);
            if (perm != null && perm.alive) return true;
        }
        return false;
    }

    /** Stack card, or the flyer still heading there. Edge facing the target. */
    private float[] spellLineOriginToward(StackItem item, float tx, float ty) {
        if (item == null) return null;
        CastFlight flight = flightForStack(item);
        if (flight != null && !flight.done) {
            float cx = flightX(flight);
            float cy = flightY(flight);
            float cw = flightW(flight);
            float ch = flightH(flight);
            if (cw > 2f && ch > 2f) {
                return edgeToward(cx - cw / 2f, cx + cw / 2f, cy - ch / 2f, cy + ch / 2f,
                        cx, cy, tx, ty);
            }
            return new float[] {cx, cy};
        }
        if (hideStackForTravel(item)) return null;
        HandLayout.Pose drawn = stackDrawnPose(item);
        if (drawn != null && drawn.w > 2f && drawn.h > 2f) {
            return edgeToward(drawn.cx - drawn.w / 2f, drawn.cx + drawn.w / 2f,
                    drawn.cy - drawn.h / 2f, drawn.cy + drawn.h / 2f,
                    drawn.cx, drawn.cy, tx, ty);
        }
        if (item.rowW > 2f && item.rowH > 2f) {
            return edgeToward(item.rowX - item.rowW / 2f, item.rowX + item.rowW / 2f,
                    item.rowY - item.rowH / 2f, item.rowY + item.rowH / 2f,
                    item.rowX, item.rowY, tx, ty);
        }
        if (item.thumbW > 2f && item.thumbH > 2f) {
            return new float[] {item.thumbX, item.thumbY};
        }
        return null;
    }

    private CastFlight flightForStack(StackItem item) {
        if (item == null) return null;
        if (castFlight != null && hidesStack(castFlight, item)) return castFlight;
        for (CastFlight flight : pendingFlights) {
            if (hidesStack(flight, item)) return flight;
        }
        return null;
    }

    private float[] playerTargetEnd(boolean you, float fromX, float fromY) {
        UiButton life = you ? youBadge : themBadge;
        return boxRim(life.x, life.y, life.w, life.h, fromX, fromY);
    }

    /** Thin gold shaft. Same head as combat, different color. */
    private void drawSpellArrow(float x0, float y0, float x1, float y1) {
        shapes.setColor(0.98f, 0.93f, 0.62f, 0.26f);
        shapes.rectLine(x0, y0, x1, y1, 9f);
        sleekArrow(x0, y0, x1, y1, ARROW_SPELL);
    }

    /** Small side stack card, or the card still flying there. Follows the pointer from that rect. */
    private float[] targetLineStart() {
        if (liveReady() && live.choosingTarget()) {
            float[] fromSource = sourceTargetOrigin();
            if (fromSource != null) return fromSource;
        }
        if (castFlight != null && !castFlight.done) {
            return new float[] {flightX(castFlight), flightY(castFlight)};
        }
        StackItem top = top();
        HandLayout.Pose drawn = stackDrawnPose(top);
        if (drawn != null && drawn.w > 2f && drawn.h > 2f) {
            return edgeToward(drawn.cx - drawn.w / 2f, drawn.cx + drawn.w / 2f,
                    drawn.cy - drawn.h / 2f, drawn.cy + drawn.h / 2f,
                    drawn.cx, drawn.cy, mx, my);
        }
        if (top != null && top.rowW > 2f && top.rowH > 2f) {
            return edgeToward(top.rowX - top.rowW / 2f, top.rowX + top.rowW / 2f,
                    top.rowY - top.rowH / 2f, top.rowY + top.rowH / 2f,
                    top.rowX, top.rowY, mx, my);
        }
        if (top != null && top.sourceOnBoard) {
            Perm src = permById(top.sourceId);
            if (src != null && src.alive) return cardRim(src, mx, my);
        }
        if (liveReady() && live.choosingTarget()) {
            float[] fromCommand = commandTargetOrigin();
            if (fromCommand != null) return fromCommand;
        }
        HandLayout.Pose well = BattlefieldLayout.stackShowcase(
                Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        return new float[] {well.cx, well.cy};
    }

    /**
     * Arrow starts on the card that is targeting: the stack object when that
     * ability is on the stack, otherwise the permanent or command tile.
     */
    private float[] sourceTargetOrigin() {
        if (!liveReady() || !live.choosingTarget()) return null;
        CardView source = live.targetingSource();
        if (source == null) return null;
        int id = source.getId();
        StackItem onStack = stackItemForSource(id);
        if (onStack != null && !onStack.sourceOnBoard) {
            HandLayout.Pose drawn = stackDrawnPose(onStack);
            if (drawn != null && drawn.w > 2f && drawn.h > 2f) {
                return edgeToward(drawn.cx - drawn.w / 2f, drawn.cx + drawn.w / 2f,
                        drawn.cy - drawn.h / 2f, drawn.cy + drawn.h / 2f,
                        drawn.cx, drawn.cy, mx, my);
            }
            if (onStack.rowW > 2f && onStack.rowH > 2f) {
                return edgeToward(onStack.rowX - onStack.rowW / 2f, onStack.rowX + onStack.rowW / 2f,
                        onStack.rowY - onStack.rowH / 2f, onStack.rowY + onStack.rowH / 2f,
                        onStack.rowX, onStack.rowY, mx, my);
            }
        }
        Perm board = permById(id);
        if (board != null && board.alive) return cardRim(board, mx, my);
        for (CommandSlot slot : commandSlots) {
            if (slot == null || slot.seat == null || slot.seat.card == null) continue;
            if (slot.seat.card.view != null && slot.seat.card.view.getId() == id) {
                return new float[] {slot.cx, slot.cy};
            }
        }
        return null;
    }

    private StackItem stackItemForSource(int sourceId) {
        if (sourceId == 0) return null;
        for (int i = stack.size() - 1; i >= 0; i--) {
            StackItem item = stack.get(i);
            if (item == null) continue;
            if (item.sourceId == sourceId) return item;
            if (item.forgeCard != null && item.forgeCard.getId() == sourceId) return item;
        }
        return null;
    }

    private boolean playerTargetHot(boolean you) {
        return liveReady() && live.choosingTarget()
                && (live.legalPlayerTarget(you) || live.playerAssigned(you));
    }

    private void drawGlowCurve(float x0, float y0, float x1, float y1) {
        float mx = (x0 + x1) * 0.5f;
        float my = (y0 + y1) * 0.5f;
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return;
        float nx = -dy / len;
        float ny = dx / len;
        float bulge = Math.min(180f, len * 0.42f);
        float bx = mx + nx * bulge;
        float by = my + ny * bulge;
        float ax = mx - nx * bulge;
        float ay = my - ny * bulge;
        float midX = Gdx.graphics.getWidth() * 0.45f;
        float midY = Gdx.graphics.getHeight() * 0.5f;
        if (Math.hypot(ax - midX, ay - midY) < Math.hypot(bx - midX, by - midY)) {
            bx = ax;
            by = ay;
        }
        float prevX = x0;
        float prevY = y0;
        int steps = 22;
        for (int i = 1; i <= steps; i++) {
            float t = i / (float) steps;
            float u = 1f - t;
            float x = u * u * x0 + 2f * u * t * bx + t * t * x1;
            float y = u * u * y0 + 2f * u * t * by + t * t * y1;
            shapes.setColor(1f, 0.90f, 0.55f, 0.22f);
            shapes.rectLine(prevX, prevY, x, y, 14f);
            shapes.setColor(1f, 0.97f, 0.82f, 0.9f);
            shapes.rectLine(prevX, prevY, x, y, 3.2f);
            prevX = x;
            prevY = y;
        }
    }

    /** Muted spell arrow from the stack row under the pointer to that spell's target. */
    private void drawHoveredSpellArrow() {
        StackItem hot = hoverStack;
        if (hot == null || hideStackForTravel(hot)) return;
        if (hot.needsTarget && hot == top() && examining < 0) return;
        float x0 = hot.thumbX;
        float y0 = hot.thumbY;
        if (hot.targetCardIds != null) {
            for (int id : hot.targetCardIds) {
                Perm perm = permById(id);
                if (perm == null || !perm.alive) continue;
                sleekArrow(x0, y0, perm.cx, perm.cy, ARROW_SPELL);
            }
        }
        if (hot.targetYou) sleekArrow(x0, y0, youBadge.x, youBadge.y, ARROW_SPELL);
        if (hot.targetThem) sleekArrow(x0, y0, themBadge.x, themBadge.y, ARROW_SPELL);
    }

    /** The long red arrow. Only the attacker under the pointer, or the one being dragged. */
    private boolean attackArrowShown(Perm p) {
        if (draggingBoard && p == pressedPerm && phase == Phase.ATTACKERS) return true;
        return p == hoverPerm;
    }

    /**
     * Blue assignment line from the blocker to each attacker Forge has accepted.
     * It stays after the drag, with no hover, and stops at the card edge above the name.
     */
    private void drawBlockLinks(Perm blk) {
        if (blk == null || !blk.alive) return;
        if (blk.blockAttackerIds != null && blk.blockAttackerIds.length > 0) {
            for (int id : blk.blockAttackerIds) {
                Perm atk = permById(id);
                if (atk == null || !atk.alive) continue;
                drawAssignmentLine(blk, atk);
            }
            return;
        }
        if (assignedBlock(blk)) drawAssignmentLine(blk, blk.blockTarget);
    }

    private void drawAssignmentLine(Perm from, Perm to) {
        float[] a = cardRim(from, to.cx, to.cy);
        float[] b = cardRim(to, from.cx, from.cy);
        sleekArrow(a[0], a[1], b[0], b[1], ARROW_BLOCK);
    }

    /** Point on the tile edge toward (tx, ty), lifted off the bottom name band. */
    private float[] cardRim(Perm card, float tx, float ty) {
        float dx = tx - card.cx;
        float dy = ty - card.cy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1f) return new float[] { card.cx, card.cy };
        dx /= len;
        dy /= len;
        float hx = Math.max(1f, card.w) / 2f;
        float hy = Math.max(1f, card.h) / 2f;
        float sx = Math.abs(dx) < 0.001f ? Float.MAX_VALUE : hx / Math.abs(dx);
        float sy = Math.abs(dy) < 0.001f ? Float.MAX_VALUE : hy / Math.abs(dy);
        float reach = Math.min(sx, sy);
        float x = card.cx + dx * reach;
        float y = card.cy + dy * reach;
        float band = Math.min(card.h * 0.30f, Math.max(16f, card.h * 0.24f));
        float nameTop = card.cy - card.h / 2f + band + 4f;
        if (y < nameTop) y = nameTop;
        return new float[] { x, y };
    }

    private boolean assignedBlock(Perm blk) {
        return blk.alive && blk.blocking && blk.blockTarget != null
                && blk.blockTarget.alive && blk.blockTarget.attacking;
    }

    private Perm blockOn(Perm attacker) {
        for (Perm p : board) {
            if (assignedBlock(p) && p.blockTarget == attacker) return p;
        }
        return null;
    }

    /** Small red chevron on every attacker. Stays up when the long arrow is hidden. */
    private void attackBadge(Perm p) {
        float span = Math.min(p.w, p.h) * 0.20f;
        float dir = p.you ? 1f : -1f;
        cardArrow(p.cx, p.cy - dir * span * 0.45f, p.cx, p.cy + dir * span * 0.55f, ARROW_ATTACK);
    }

    /** Small blue chevron on an assigned blocker, aimed at its attacker. */
    private void blockBadge(Perm blk) {
        float dx = blk.blockTarget.cx - blk.cx;
        float dy = blk.blockTarget.cy - blk.cy;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        float span = Math.min(blk.w, blk.h) * 0.20f;
        dx /= len;
        dy /= len;
        cardArrow(blk.cx - dx * span * 0.45f, blk.cy - dy * span * 0.45f,
                blk.cx + dx * span * 0.55f, blk.cy + dy * span * 0.55f, ARROW_BLOCK);
    }

    /** Shorter than {@link #sleekArrow}, so it sits on the card instead of crossing the table. */
    private void cardArrow(float x0, float y0, float x1, float y1, Color color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        dx /= len;
        dy /= len;
        float reach = Math.min(9f, len * 0.48f);
        float wing = reach * 0.62f;
        float bx = x1 - dx * reach;
        float by = y1 - dy * reach;
        shapes.setColor(color);
        shapes.rectLine(x0, y0, bx, by, 3f);
        shapes.triangle(x1, y1, bx + -dy * wing, by + dx * wing, bx - -dy * wing, by - dx * wing);
    }

    /** One thin shaft and a small filled head. Not a chain of thick rectangles. */
    private void sleekArrow(float x0, float y0, float x1, float y1, Color color) {
        float dx = x1 - x0, dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1f) return;
        dx /= len;
        dy /= len;
        float reach = 13f;
        float wing = 6f;
        float bx = x1 - dx * reach;
        float by = y1 - dy * reach;
        shapes.setColor(color);
        shapes.rectLine(x0, y0, bx, by, ARROW_SHAFT);
        shapes.triangle(x1, y1, bx + -dy * wing, by + dx * wing, bx - -dy * wing, by - dx * wing);
    }

    private void drawStackLog() {
        if (stack.isEmpty()) return;
        for (int i = stack.size() - 1; i >= 0; i--) {
            StackItem item = stack.get(i);
            if (item.showcase) {
                drawStackShowcase(item);
                continue;
            }
            boolean examined = i == examining;
            BitmapFont face = examined ? stackFont : font;
            float textX = item.thumbX + item.thumbW / 2f + 12f;
            float textW = Math.max(40f, item.rowX + item.rowW / 2f - 12f - textX);
            float top = item.rowY + item.rowH / 2f - 8f;
            float span = Math.max(1f, item.rowH - 16f);
            if (item.card != null && item.card.tex != null) {
                batch.setColor(Color.WHITE);
                drawTex(item.card.tex, item.thumbX, item.thumbY, item.thumbW, item.thumbH, 0f);
            } else {
                batch.setColor(0.35f, 0.36f, 0.4f, 1f);
                drawTex(pixel(), item.thumbX, item.thumbY, item.thumbW, item.thumbH, 0f);
                batch.setColor(Color.WHITE);
            }
            String title = item.name == null ? "" : item.name;
            float nameH = 0f;
            if (!title.isEmpty()) {
                glyphs.setText(face, title, Color.WHITE, textW, Align.left, true);
                nameH = glyphs.height;
            }
            List<ManaPip> pips = parseManaPips(item.cost);
            float pipR = 13f;
            float pipH = pips.isEmpty() ? 0f : pipR * 2f;
            String type = item.typeLine == null ? "" : item.typeLine;
            if (item.pt != null && !item.pt.isBlank()) type = type.isBlank() ? item.pt : type + "  " + item.pt;
            float typeH = 0f;
            if (!type.isBlank()) {
                glyphs.setText(stripFont, type, Color.WHITE, textW, Align.left, true);
                typeH = glyphs.height;
            }
            String rules = item.rules == null ? "" : item.rules.trim();
            if (rules.isBlank()) rules = oracleText(item.name);
            if (rules.isBlank() && item.fullText != null && !item.fullText.equals(item.name)) rules = item.fullText;
            float rulesH = 0f;
            if (!rules.isBlank()) {
                glyphs.setText(stripFont, rules, Color.WHITE, textW, Align.left, true);
                rulesH = glyphs.height;
            }
            String targetLine = item.targetLabel == null || item.targetLabel.isBlank()
                    ? "" : "Target: " + item.targetLabel;
            float targetH = 0f;
            if (!targetLine.isEmpty()) {
                glyphs.setText(stripFont, targetLine, Color.WHITE, textW, Align.left, true);
                targetH = glyphs.height;
            }
            String extra = item.needsTarget
                    ? (item.preview == null || item.preview.isBlank() ? BattlefieldLayout.TARGET_PROMPT : item.preview)
                    : (item.needsChoice ? "Choose a creature type." : "");
            float extraH = 0f;
            if (!extra.isEmpty()) {
                glyphs.setText(face, extra, Color.WHITE, textW, Align.left, true);
                extraH = glyphs.height;
            }
            int blocks = (nameH > 0f ? 1 : 0) + (pipH > 0f ? 1 : 0) + (typeH > 0f ? 1 : 0)
                    + (rulesH > 0f ? 1 : 0) + (targetH > 0f ? 1 : 0) + (extraH > 0f ? 1 : 0);
            int between = Math.max(0, blocks - 1);
            float minLead = 6f;
            float minGap = 8f;
            if (rulesH > 0f) {
                float room = span - nameH - pipH - typeH - targetH - extraH - minLead * 2f - between * minGap;
                if (room < rulesH) {
                    rules = fitToHeight(stripFont, rules, textW, Math.max(stripFont.getLineHeight(), room));
                    glyphs.setText(stripFont, rules, Color.WHITE, textW, Align.left, true);
                    rulesH = glyphs.height;
                }
            }
            float content = nameH + pipH + typeH + rulesH + targetH + extraH;
            float free = span - content;
            float lead;
            float gap;
            if (blocks == 0) {
                lead = 0f;
                gap = 0f;
            } else if (free >= minLead * 2f + between * minGap) {
                int slots = between + 2;
                gap = free / slots;
                lead = gap;
            } else if (free >= 4f + between * 3f) {
                lead = 2f;
                gap = between == 0 ? 0f : (free - 4f) / between;
            } else {
                lead = 2f;
                gap = 2f;
            }
            float y = top - lead;
            if (nameH > 0f) {
                face.setColor(GOLD);
                face.draw(batch, title, textX, y, textW, Align.left, true);
                y -= nameH + gap;
            }
            if (pipH > 0f) {
                queueCostLine(pips, textX, y - pipR, pipR);
                y -= pipH + gap;
            }
            stripFont.setColor(Color.WHITE);
            if (typeH > 0f) {
                stripFont.draw(batch, type, textX, y, textW, Align.left, true);
                y -= typeH + gap;
            }
            if (rulesH > 0f) {
                stripFont.draw(batch, rules, textX, y, textW, Align.left, true);
                y -= rulesH + gap;
            }
            if (targetH > 0f) {
                stripFont.setColor(GOLD);
                stripFont.draw(batch, targetLine, textX, y, textW, Align.left, true);
                stripFont.setColor(Color.WHITE);
                y -= targetH + gap;
            }
            if (extraH > 0f) {
                face.setColor(GOLD);
                face.draw(batch, extra, textX, y, textW, Align.left, true);
            }
        }
        font.setColor(Color.WHITE);
        stackFont.setColor(Color.WHITE);
        stripFont.setColor(Color.WHITE);
        batch.setColor(Color.WHITE);
    }

    /** Full card for the top stack object. Text stands in when the scan is missing. */
    private void drawStackShowcase(StackItem item) {
        if (hideStackForTravel(item)) return;
        if (item.summonDelay > 0f) return;
        float appear = stackAppear(item);
        float cx = item.rowX;
        float cy = item.rowY;
        float cw = item.rowW * appear;
        float ch = item.rowH * appear;
        Texture tex = item.card == null ? null : item.card.tex;
        if (tex == null && item.name != null && !item.name.isBlank()) tex = textureFor(item.name);
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, cx, cy, cw, ch, 0f);
            drawCardBorder(cx, cy, cw, ch, 1f, FRAME_STEEL);
            return;
        }
        batch.setColor(0.10f, 0.11f, 0.13f, 0.96f);
        drawTex(pixel(), cx, cy, cw, ch, 0f);
        batch.setColor(Color.WHITE);
        drawCardBorder(cx, cy, cw, ch, 1f, FRAME_STEEL);
        float left = cx - cw / 2f + 16f;
        float textW = Math.max(40f, cw - 32f);
        float y = cy + ch / 2f - 18f;
        String title = item.name == null ? "" : item.name;
        if (!title.isEmpty()) {
            glyphs.setText(stackFont, title, Color.WHITE, textW, Align.left, true);
            stackFont.setColor(Color.WHITE);
            stackFont.draw(batch, title, left, y, textW, Align.left, true);
            y -= glyphs.height + 8f;
        }
        String type = item.typeLine == null ? "" : item.typeLine;
        if (!type.isBlank()) {
            stripFont.setColor(new Color(0.85f, 0.84f, 0.78f, 1f));
            glyphs.setText(stripFont, type, Color.WHITE, textW, Align.left, true);
            stripFont.draw(batch, type, left, y, textW, Align.left, true);
            y -= glyphs.height + 10f;
        }
        String rules = item.rules == null ? "" : item.rules.trim();
        if (rules.isBlank() && item.fullText != null) rules = item.fullText.trim();
        if (!rules.isBlank()) {
            float room = y - (cy - ch / 2f + 16f);
            rules = fitToHeight(stripFont, rules, textW, Math.max(stripFont.getLineHeight(), room));
            stripFont.setColor(Color.WHITE);
            stripFont.draw(batch, rules, left, y, textW, Align.left, true);
        }
        stripFont.setColor(Color.WHITE);
        stackFont.setColor(Color.WHITE);
    }

    private void drawEmblems() {
        for (Emblem mark : theirEmblems) drawEmblemTile(mark);
        for (Emblem mark : yourEmblems) drawEmblemTile(mark);
    }

    private void drawEmblemTile(Emblem mark) {
        if (mark == null || mark.w < 2f) return;
        batch.setColor(0.12f, 0.13f, 0.16f, 0.94f);
        drawTex(pixel(), mark.x, mark.y, mark.w, mark.h, 0f);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.9f);
        drawTex(pixel(), mark.x, mark.y, mark.w + 3f, 2f, 0f);
        batch.setColor(Color.WHITE);
        String name = mark.name == null ? "" : mark.name;
        drawCenteredOn(stripFont, clipToWidth(stripFont, name, mark.w - 10f), mark.x, mark.y, Color.WHITE);
    }

    /** Full text beside the tile, with a gap so the name on the tile stays visible. */
    private void drawEmblemHover() {
        Emblem mark = hoverEmblem;
        if (mark == null) return;
        float gap = 16f;
        float panelW = 280f;
        float textW = panelW - 20f;
        String body = mark.text == null || mark.text.isBlank() ? mark.name : mark.text;
        glyphs.setText(stripFont, body, Color.WHITE, textW, Align.left, true);
        float panelH = Math.max(72f, glyphs.height + 36f);
        float cx = mark.x - mark.w / 2f - gap - panelW / 2f;
        if (cx - panelW / 2f < 8f) cx = mark.x + mark.w / 2f + gap + panelW / 2f;
        float cy = mark.y;
        float h = Gdx.graphics.getHeight();
        if (cy + panelH / 2f > h - 8f) cy = h - 8f - panelH / 2f;
        if (cy - panelH / 2f < 8f) cy = 8f + panelH / 2f;
        batch.setColor(0.08f, 0.09f, 0.11f, 0.96f);
        drawTex(pixel(), cx, cy, panelW, panelH, 0f);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.95f);
        drawTex(pixel(), cx, cy + panelH / 2f - 2f, panelW, 2f, 0f);
        batch.setColor(Color.WHITE);
        float top = cy + panelH / 2f - 12f;
        stackFont.setColor(Color.WHITE);
        stackFont.draw(batch, mark.name == null ? "" : mark.name, cx - textW / 2f, top, textW, Align.left, true);
        glyphs.setText(stackFont, mark.name == null ? "" : mark.name, Color.WHITE, textW, Align.left, true);
        stripFont.setColor(Color.WHITE);
        stripFont.draw(batch, body, cx - textW / 2f, top - glyphs.height - 8f, textW, Align.left, true);
    }

    private Emblem emblemAt(float x, float y) {
        for (int i = theirEmblems.size() - 1; i >= 0; i--) {
            Emblem mark = theirEmblems.get(i);
            if (Math.abs(x - mark.x) <= mark.w / 2f && Math.abs(y - mark.y) <= mark.h / 2f) return mark;
        }
        for (int i = yourEmblems.size() - 1; i >= 0; i--) {
            Emblem mark = yourEmblems.get(i);
            if (Math.abs(x - mark.x) <= mark.w / 2f && Math.abs(y - mark.y) <= mark.h / 2f) return mark;
        }
        return null;
    }

    /**
     * The rectangle {@link #drawStackShowcase} or the buried thumb actually paints.
     * Not {@link BattlefieldLayout#stackShowcase}'s well before it is placed.
     */
    private HandLayout.Pose stackDrawnPose(StackItem item) {
        if (item == null) return null;
        if (item.showcase) {
            float appear = stackAppear(item);
            if (appear <= 0f) return null;
            return new HandLayout.Pose(item.rowX, item.rowY,
                    Math.max(1f, item.rowW * appear), Math.max(1f, item.rowH * appear), 0f, 1f);
        }
        if (item.thumbW > 1f && item.thumbH > 1f) {
            return new HandLayout.Pose(item.thumbX, item.thumbY, item.thumbW, item.thumbH, 0f, 1f);
        }
        if (item.rowW < 2f || item.rowH < 2f) return null;
        return new HandLayout.Pose(item.rowX, item.rowY, item.rowW, item.rowH, 0f, 1f);
    }

    private static float stackAppear(StackItem item) {
        if (item == null || item.summonDelay > 0f) return 0f;
        if (item.summonFull > 0f && item.summonLeft > 0f) {
            return 0.35f + 0.65f * (1f - item.summonLeft / item.summonFull);
        }
        return 1f;
    }

    private boolean stackDrawnContains(StackItem item, float x, float y) {
        HandLayout.Pose pose = stackDrawnPose(item);
        return pose != null && HandLayout.pointHitsPose(pose, x, y, 0f);
    }

    private StackItem stackUnder(float x, float y) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            StackItem item = stack.get(i);
            if (stackDrawnContains(item, x, y)) return item;
            if (!item.showcase && item.rowW > 1f && item.rowH > 1f
                    && Math.abs(x - item.rowX) <= item.rowW / 2f
                    && Math.abs(y - item.rowY) <= item.rowH / 2f) {
                return item;
            }
        }
        return null;
    }

    private boolean hitStack(float x, float y) {
        StackItem hit = stackUnder(x, y);
        if (hit == null) return false;
        if (liveReady() && live.choosingTarget() && hit.forgeCard != null && live.legalTarget(hit.forgeCard)) {
            live.select(hit.forgeCard);
            return true;
        }
        int i = stack.indexOf(hit);
        if (i < 0) return false;
        examining = examining == i ? -1 : i;
        return true;
    }

    private StackItem top() {
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private boolean targeting() {
        if (liveReady() && live.choosingTarget()) return modal == Modal.NONE;
        StackItem top = top();
        return modal == Modal.NONE && top != null && top.needsTarget;
    }

    private boolean topHasTarget() {
        StackItem top = top();
        return top != null && top.needsTarget && top.targetLabel != null && !top.targetLabel.isBlank();
    }

    /** Insert the gripped live card at {@code to}. Cards already on the stack keep their places. */
    private void reorderLive(int from, int to) {
        List<HandCard> cards = live();
        int n = cards.size();
        if (from < 0 || from >= n) return;
        int[] order = HandLayout.movedOrder(n, from, to);
        HandCard[] next = new HandCard[n];
        for (int i = 0; i < n; i++) next[i] = cards.get(order[i]);
        int li = 0;
        for (int i = 0; i < hand.size(); i++) {
            HandCard c = hand.get(i);
            if (c.alive && !c.parked) hand.set(i, next[li++]);
        }
    }

    private void drawBadge(UiButton b, Color color) {
        shapes.setColor(color);
        shapes.rect(b.x - b.w / 2f, b.y - b.h / 2f, b.w, b.h);
    }

    /**
     * Dark oval on the ground row under a flier. The card bobs above it.
     * The oval stays on the resting float's ground spot, so hit testing does not move.
     */
    private void drawFlyShadow(Perm p) {
        if (p.flyLift < 1f || p.kind != BattlefieldLayout.Kind.CREATURE) return;
        float gy = p.cy - p.flyLift;
        float sw = p.w * 0.62f;
        float sh = Math.max(7f, p.h * 0.10f);
        float bottom = gy - p.h / 2f - sh * 0.35f;
        shapes.setColor(0f, 0f, 0f, 0.28f * p.alpha);
        shapes.ellipse(p.cx - sw / 2f, bottom, sw, sh);
        shapes.setColor(0f, 0f, 0f, 0.18f * p.alpha);
        shapes.ellipse(p.cx - sw * 0.72f, bottom + sh * 0.15f, sw * 1.44f, sh * 0.7f);
    }

    /** Idle bob for a flier. Draw only. The resting {@code ty} stays the hit target. */
    private static float flyBob(Perm p) {
        if (p.flyLift < 1f) return 0f;
        float phase = (System.identityHashCode(p) & 1023) / 1023f * 6.28318f;
        float amp = Math.min(4f, Math.max(2.5f, p.h * 0.03f));
        return (float) Math.sin(System.nanoTime() / 520_000_000.0 + phase) * amp;
    }

    /**
     * Always-on combat halo. Attacking wins if both flags are set.
     * Drawn under the card, so the name stays clear. The long arrow stays hover-only.
     */
    private void drawCombatGlow(Perm p) {
        float bob = flyBob(p);
        p.cy += bob;
        try {
        boolean attack = p.attacking;
        Color soft = attack ? GLOW_ATTACK : GLOW_BLOCK;
        Color edge = attack ? GLOW_ATTACK_EDGE : GLOW_BLOCK_EDGE;
        float[] spread = {26f, 16f, 8f, 3f};
        float[] alpha = {0.18f, 0.32f, 0.55f, 0.95f};
        spinShapes(p.cx, p.cy, p.rot);
        for (int i = 0; i < spread.length; i++) {
            float s = spread[i];
            Color hue = i == spread.length - 1 ? edge : soft;
            shapes.setColor(hue.r, hue.g, hue.b, alpha[i] * p.alpha);
            if (p.token) fillToken(p.cx, p.cy, p.w + s * 2f, p.h + s * 2f);
            else shapes.rect(p.cx - p.w / 2f - s, p.cy - p.h / 2f - s, p.w + s * 2f, p.h + s * 2f);
        }
        shapes.setTransformMatrix(savedTransform);
        } finally {
            p.cy -= bob;
        }
    }

    /** Same gold pulse as a legal card, on the life box and the avatar square. */
    private void drawPlayerTargetRim(float lifeX, float lifeY, float lifeW, float lifeH,
                                     float avatarX, float avatarY, float avatarS, boolean assigned) {
        float pulse = 0.62f + 0.38f * (float) Math.sin(System.nanoTime() / 180_000_000.0);
        float boost = assigned ? 1.15f : 1f;
        float[] spread = {10f, 6f, 3f};
        float[] alpha = {0.28f, 0.55f, 1f};
        for (int i = 0; i < spread.length; i++) {
            float s = spread[i];
            shapes.setColor(1f, 0.78f, 0.12f, alpha[i] * pulse * boost);
            outline(lifeX - s, lifeY - s, lifeW + s * 2f, lifeH + s * 2f, i == 2 ? 3f : 2f);
            outline(avatarX - s, avatarY - s, avatarS + s * 2f, avatarS + s * 2f, i == 2 ? 3f : 2f);
        }
    }

    /** Gold pulse on a legal target. Distinct from the cyan playable rim. */
    private void drawTargetRim(Perm p) {
        float bob = flyBob(p);
        p.cy += bob;
        try {
        float pulse = 0.62f + 0.38f * (float) Math.sin(System.nanoTime() / 180_000_000.0);
        float[] spread = {14f, 8f, 3f};
        float[] alpha = {0.28f, 0.55f, 1f};
        spinShapes(p.cx, p.cy, p.rot);
        for (int i = 0; i < spread.length; i++) {
            float s = spread[i];
            shapes.setColor(1f, 0.78f, 0.12f, alpha[i] * pulse * p.alpha);
            if (p.token) fillToken(p.cx, p.cy, p.w + s * 2f, p.h + s * 2f);
            else shapes.rect(p.cx - p.w / 2f - s, p.cy - p.h / 2f - s, p.w + s * 2f, p.h + s * 2f);
        }
        shapes.setTransformMatrix(savedTransform);
        } finally {
            p.cy -= bob;
        }
    }

    /** Cyan for his legal activations, and for a battle he can attack or activate. */
    private static boolean showPlayableRim(Perm p) {
        return p != null && p.playable && (p.you || p.kind == BattlefieldLayout.Kind.BATTLE);
    }

    /** Bright edge under the card. The name and mana cost stay on the face. */
    private void drawPlayableRim(Perm p) {
        float bob = flyBob(p);
        p.cy += bob;
        try {
        float[] spread = {11f, 6f, 3f};
        float[] alpha = {0.22f, 0.5f, 0.95f};
        spinShapes(p.cx, p.cy, p.rot);
        for (int i = 0; i < spread.length; i++) {
            float s = spread[i];
            shapes.setColor(0.45f, 0.93f, 1f, alpha[i] * p.alpha);
            if (p.token) fillToken(p.cx, p.cy, p.w + s * 2f, p.h + s * 2f);
            else shapes.rect(p.cx - p.w / 2f - s, p.cy - p.h / 2f - s, p.w + s * 2f, p.h + s * 2f);
        }
        shapes.setTransformMatrix(savedTransform);
        } finally {
            p.cy -= bob;
        }
    }

    private void drawPerm(Perm p) {
        if (p == null || p.instant || p.sorcery || p.kind == BattlefieldLayout.Kind.SPELL) return;
        float bob = flyBob(p);
        p.cy += bob;
        try {
        if (p.summonDelay > 0f || hidePermForTravel(p)) return;
        float scale = 1f;
        if (p.summonFull > 0f && p.summonLeft > 0f) {
            scale = 0.35f + 0.65f * (1f - p.summonLeft / p.summonFull);
        }
        if (p.thunk > 0f) scale *= 1f + 0.08f * (float) Math.sin(p.thunk * Math.PI);
        float cw = p.w * scale, ch = p.h * scale;
        boolean sick = p.sick && p.alive;
        spinBatch(p.cx, p.cy, p.rot);
        if (p.tex != null) {
            batch.setColor(1f, 1f, 1f, p.alpha);
            if (fullPrintedBoardCard(p)) drawTex(p.tex, p.cx, p.cy, cw, ch, 0f);
            else if (p.token) drawArchedCrop(p.tex, p.cx, p.cy, cw, ch);
            else drawArtCrop(p.tex, p.cx, p.cy, cw, ch);
        } else if (p.token) {
            batch.setColor(0.34f, 0.35f, 0.38f, p.alpha);
            drawArchedSolid(p.cx, p.cy, cw, ch);
        } else {
            batch.setColor(0.34f, 0.35f, 0.38f, p.alpha);
            drawTex(pixel(), p.cx, p.cy, cw, ch, 0f);
        }
        if (p.token) drawTokenBorder(p.cx, p.cy, cw, ch, p.alpha, frameColor(p));
        else drawCardBorder(p.cx, p.cy, cw, ch, p.alpha, frameColor(p));
        if (lightsOut(p) || (p.kind == BattlefieldLayout.Kind.PLANESWALKER && p.loyalty <= 0)
                || (p.kind == BattlefieldLayout.Kind.BATTLE && p.defense <= 0)) {
            if (p.token) {
                batch.setColor(0f, 0f, 0f, 0.76f * p.alpha);
                drawArchedSolid(p.cx, p.cy, cw, ch);
            } else {
                drawLightsOut(p.cx, p.cy, cw, ch, p.alpha);
            }
        }
        if (p.alpha >= 0.25f) {
            if (p.kind == BattlefieldLayout.Kind.CREATURE) {
                drawCreatureMarks(p, p.cx, p.cy, cw, ch, true);
            } else if (p.kind == BattlefieldLayout.Kind.PLANESWALKER) {
                drawLoyaltyMark(p, p.cx, p.cy, cw, ch, true);
            } else if (p.kind == BattlefieldLayout.Kind.BATTLE) {
                drawDefenseMark(p, p.cx, p.cy, cw, ch, false);
            } else if (p.kind == BattlefieldLayout.Kind.ENCHANTMENT && !p.tucked) {
                drawLoreMark(p, p.cx, p.cy, cw, ch);
            }
            drawChosenType(p, p.cx, p.cy, cw, ch);
            if (!fullPrintedBoardCard(p)) {
                drawAbilityIcons(p.keywords, p.cx, p.cy, cw, ch, p.token, p.commander, p.commanderKey, true);
            }
            drawProtectMarks(p.keywords, p.cx, p.cy, cw, ch, p.alpha, p.token);
            if (p.kind == BattlefieldLayout.Kind.CREATURE) {
                drawHasteMark(p, p.cx, p.cy, cw, ch);
            }
            if (sick) drawSummonSick(p.cx, p.cy, cw, ch, p.alpha);
            if (p.pileFront && p.pileCount > 1) drawPileCount(p.cx, p.cy, cw, ch, p.pileCount, p.token);
            if (p.kind == BattlefieldLayout.Kind.CREATURE) drawReachMark(p, p.cx, p.cy, cw, ch);
        }
        batch.setTransformMatrix(savedTransform);
        batch.setColor(Color.WHITE);
        if (p.tex == null && p.alpha >= 0.25f && p.name != null && !p.name.isBlank()) {
            drawFittedName(p.name, p.cx, p.cy, cw * (p.token ? 0.72f : 1f));
        }
        } finally {
            p.cy -= bob;
        }
    }

    /**
     * Hexproof and shroud on the tile itself. Both sides. Forge keywords only —
     * Ward without current hexproof does not draw.
     */
    private void drawProtectMarks(java.util.List<String> keywords, float cx, float cy, float cw, float ch,
            float alpha, boolean token) {
        boolean hex = hasKeyword(keywords, "hexproof");
        boolean shroud = hasKeyword(keywords, "shroud");
        if (!hex && !shroud) return;
        if (hex && shroud) {
            drawProtectWard(true, cx, cy, cw, ch, alpha, token, -0.10f);
            drawProtectWard(false, cx, cy, cw, ch, alpha, token, 0.16f);
        } else {
            drawProtectWard(hex, cx, cy, cw, ch, alpha, token, 0f);
        }
    }

    /**
     * Large upper-left ward plus an on-tile rim. Hexproof is an open silver-cyan
     * hex. Shroud is a closed violet-slate diamond. Not the cyan playable edge
     * and not the gold commander heater. P/T stays on the lower-right band.
     */
    private void drawProtectWard(boolean hexproof, float cx, float cy, float cw, float ch,
            float alpha, boolean token, float xShift) {
        Texture mark = hexproof ? hexproofMark : shroudMark;
        if (mark == null) return;
        drawProtectRim(hexproof, cx, cy, cw, ch, alpha, token);
        float size = Math.max(48f, Math.min(cw, ch) * 0.66f);
        float x = cx - cw * 0.22f + cw * xShift;
        float y = cy + ch * 0.24f;
        if (token) x += cw * BattlefieldLayout.tokenArchInset(0.18f) * 0.45f;
        float half = size / 2f;
        float minX = cx - cw / 2f + half + 3f;
        float maxX = cx + cw / 2f - half - 3f;
        float minY = cy - ch / 2f + ch * BattlefieldLayout.BOARD_TYPE_BAND + half + 4f;
        float maxY = cy + ch / 2f - half - 4f;
        if (minX > maxX) x = cx;
        else x = Math.max(minX, Math.min(maxX, x));
        if (minY > maxY) y = cy + ch * 0.10f;
        else y = Math.max(minY, Math.min(maxY, y));
        if (hexproof) batch.setColor(0.82f, 0.97f, 1f, alpha);
        else batch.setColor(0.92f, 0.82f, 1f, alpha);
        batch.draw(mark, x - half, y - half, size, size);
        batch.setColor(Color.WHITE);
        String label = hexproof ? "HEX" : "SHROUD";
        glyphs.setText(stripFont, label);
        if (!hexproof && glyphs.width > size * 0.86f) {
            label = "SHRD";
            glyphs.setText(stripFont, label);
        }
        stripFont.setColor(hexproof
                ? new Color(0.88f, 0.99f, 1f, 1f)
                : new Color(0.94f, 0.86f, 1f, 1f));
        stripFont.draw(batch, label, x - glyphs.width / 2f, y - size * 0.06f);
        stripFont.setColor(Color.WHITE);
    }

    /** Inner band on the tile face. Status, not the outer playable glow. */
    private void drawProtectRim(boolean hexproof, float cx, float cy, float cw, float ch,
            float alpha, boolean token) {
        float t = Math.max(6f, Math.min(cw, ch) * 0.085f);
        float inset = token ? cw * BattlefieldLayout.tokenArchInset(0.10f) + 2f : 3f;
        float x = cx - cw / 2f + inset;
        float y = cy - ch / 2f + 3f;
        float w = cw - inset * 2f;
        float h = ch - 6f;
        if (w < t * 2f || h < t * 2f) return;
        if (hexproof) batch.setColor(0.42f, 0.86f, 1f, 0.90f * alpha);
        else batch.setColor(0.58f, 0.36f, 0.86f, 0.92f * alpha);
        batch.draw(pixel(), x, y + h - t, w, t);
        batch.draw(pixel(), x, y, w, t);
        batch.draw(pixel(), x, y, t, h);
        batch.draw(pixel(), x + w - t, y, t, h);
        if (hexproof) batch.setColor(0.88f, 0.98f, 1f, 0.55f * alpha);
        else batch.setColor(0.82f, 0.70f, 1f, 0.50f * alpha);
        float inner = Math.max(2f, t * 0.28f);
        batch.draw(pixel(), x + t, y + h - t - inner, w - t * 2f, inner);
        batch.draw(pixel(), x + t, y + t, w - t * 2f, inner);
        batch.setColor(Color.WHITE);
    }

    /**
     * Readable haste chevrons. Smaller than the ward. Both sides, from Forge keywords.
     */
    private void drawHasteMark(Perm p, float cx, float cy, float cw, float ch) {
        if (hasteMark == null || !hasKeyword(p, "haste")) return;
        boolean hot = p.you == (activeOwner != BattlefieldLayout.StripOwner.ENEMY);
        float size = Math.max(18f, Math.min(cw, ch) * 0.28f);
        float x = cx + cw * 0.22f;
        float y = cy - ch * 0.28f;
        batch.setColor(1f, hot ? 0.95f : 0.78f, hot ? 0.55f : 0.38f, (hot ? 1f : 0.9f) * p.alpha);
        batch.draw(hasteMark, x - size / 2f, y - size / 2f, size, size);
        batch.setColor(Color.WHITE);
        String label = "HASTE";
        glyphs.setText(stripFont, label);
        if (glyphs.width > size + 10f) {
            label = "HST";
            glyphs.setText(stripFont, label);
        }
        stripFont.setColor(hot ? new Color(1f, 0.96f, 0.72f, 1f) : new Color(1f, 0.82f, 0.45f, 1f));
        stripFont.draw(batch, label, x - glyphs.width / 2f, y - size * 0.42f);
        stripFont.setColor(Color.WHITE);
    }

    /**
     * Green REACH bar on the tile itself. Always on, for both sides.
     * Brighter during his declare-blockers step when this creature can block a flyer.
     * This is not the cyan playable edge.
     */
    private void drawReachMark(Perm p, float cx, float cy, float cw, float ch) {
        if (!hasKeyword(p, "reach")) return;
        boolean hot = reachCanBlockFlyer(p);
        float barH = hot ? Math.max(14f, ch * 0.13f) : Math.max(11f, ch * 0.10f);
        float reserve = p.pileFront && p.pileCount > 1 ? cw * 0.26f : 6f;
        float leftInset = p.token ? cw * BattlefieldLayout.tokenArchInset(0.18f) + 4f : 5f;
        float barW = cw - leftInset - reserve;
        if (barW < 36f) barW = Math.min(cw * 0.55f, cw - 8f);
        float x = cx - cw / 2f + leftInset;
        if (x + barW > cx + cw / 2f - 4f) barW = cx + cw / 2f - 4f - x;
        float top = cy + ch / 2f;
        float y = p.token ? top - ch * 0.22f - barH : top - 4f - barH;
        if (hot) {
            batch.setColor(0.85f, 1f, 0.45f, 0.98f * p.alpha);
        } else {
            batch.setColor(0.12f, 0.62f, 0.22f, 0.94f * p.alpha);
        }
        batch.draw(pixel(), x, y, barW, barH);
        if (hot) {
            batch.setColor(1f, 1f, 0.92f, 0.95f * p.alpha);
            batch.draw(pixel(), x, y + barH - 3f, barW, 3f);
        }
        batch.setColor(Color.WHITE);
        String label = "REACH";
        glyphs.setText(stripFont, label);
        if (glyphs.width > barW - 6f) label = "R";
        glyphs.setText(stripFont, label);
        stripFont.setColor(hot ? Color.WHITE : new Color(0.86f, 1f, 0.86f, 1f));
        stripFont.draw(batch, label, x + (barW - glyphs.width) / 2f, y + barH - 1f);
        stripFont.setColor(Color.WHITE);
    }

    /** His reach creature, untapped and allowed to block, while a flyer is attacking. */
    private boolean reachCanBlockFlyer(Perm p) {
        if (!declaringBlocks() || p == null || !p.you || !p.alive) return false;
        if (p.kind != BattlefieldLayout.Kind.CREATURE || !hasKeyword(p, "reach")) return false;
        if (p.tapped) return false;
        if ("blk".equals(p.restraint) || "both".equals(p.restraint)) return false;
        for (Perm attacker : board) {
            if (attacker.alive && !attacker.you && attacker.attacking && hasKeyword(attacker, "flying")) return true;
        }
        return false;
    }

    /** Every copy in a feathered pile is drawn and hit-tested. Kept so old call sites compile. */
    private static boolean hiddenPileCopy(Perm p) {
        return false;
    }

    /** Pointer in the arched tile. The cut top corners are not a hit. */
    private static boolean tokenHits(HandLayout.Pose pose, float x, float y, float pad) {
        float dx = x - pose.cx;
        float dy = y - pose.cy;
        double rad = Math.toRadians(-pose.rotationDeg);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float lx = dx * cos - dy * sin;
        float ly = dx * sin + dy * cos;
        float hw = pose.w / 2f + pad;
        float hh = pose.h / 2f + pad;
        if (hw < 1f || hh < 1f) return false;
        float u = (lx + hw) / (hw * 2f);
        float v = (hh - ly) / (hh * 2f);
        return BattlefieldLayout.tokenTileContains(u, v);
    }

    /** Turn the crop, border, and overlays together. Untapped stays upright. */
    private void drawPileCount(float cx, float cy, float cw, float ch, int count, boolean token) {
        String text = "x" + count;
        glyphs.setText(stripFont, text);
        float x;
        float y;
        if (token) {
            x = cx;
            y = cy + ch * 0.22f;
        } else {
            x = cx + cw / 2f - 8f - glyphs.width / 2f;
            y = cy + ch / 2f - 6f;
        }
        drawPlate(stripFont, text, x, y, GOLD);
    }

    private void spinBatch(float cx, float cy, float rot) {
        savedTransform.set(batch.getTransformMatrix());
        cardSpin.set(savedTransform);
        if (Math.abs(rot) > 0.1f) {
            cardSpin.translate(cx, cy, 0f);
            cardSpin.rotate(0f, 0f, 1f, rot);
            cardSpin.translate(-cx, -cy, 0f);
        }
        batch.setTransformMatrix(cardSpin);
    }

    private void spinShapes(float cx, float cy, float rot) {
        savedTransform.set(shapes.getTransformMatrix());
        cardSpin.set(savedTransform);
        if (Math.abs(rot) > 0.1f) {
            cardSpin.translate(cx, cy, 0f);
            cardSpin.rotate(0f, 0f, 1f, rot);
            cardSpin.translate(-cx, -cy, 0f);
        }
        shapes.setTransformMatrix(cardSpin);
    }

    /** Name, art, and type line. Hand, zoom, and stack thumbnails still draw the whole scan. */
    private void drawArtCrop(Texture t, float cx, float cy, float w, float h) {
        int srcH = Math.max(1, Math.round(t.getHeight() * BattlefieldLayout.BOARD_FACE_CROP));
        batch.draw(t, cx - w / 2f, cy - h / 2f, w / 2f, h / 2f, w, h, 1f, 1f, 0f,
                0, 0, t.getWidth(), srcH, false, false);
    }

    /** Solid fill under a semicircular top. The sides and the bottom stay square. */
    private void drawArchedSolid(float cx, float cy, float cw, float ch) {
        float left = cx - cw / 2f;
        float top = cy + ch / 2f;
        float bottom = cy - ch / 2f;
        int steps = 28;
        float depth = BattlefieldLayout.TOKEN_ARCH_FRAC;
        for (int i = 0; i < steps; i++) {
            float v0 = depth * i / steps;
            float v1 = depth * (i + 1) / steps;
            float inset = BattlefieldLayout.tokenArchInset((v0 + v1) * 0.5f);
            float x = left + inset * cw;
            float bw = Math.max(1f, cw * (1f - inset * 2f));
            float band = (v1 - v0) * ch + 0.75f;
            float y = top - v1 * ch;
            batch.draw(pixel(), x, y, bw, band);
        }
        float body = ch * (1f - depth) + 0.75f;
        batch.draw(pixel(), left, bottom, cw, body);
    }

    /** Cropped scan clipped to the token arch, so the top corners are actually gone. */
    private void drawArchedCrop(Texture t, float cx, float cy, float cw, float ch) {
        int srcW = Math.max(1, t.getWidth());
        int srcH = Math.max(1, Math.round(t.getHeight() * BattlefieldLayout.BOARD_FACE_CROP));
        float left = cx - cw / 2f;
        float top = cy + ch / 2f;
        float bottom = cy - ch / 2f;
        int steps = 28;
        float depth = BattlefieldLayout.TOKEN_ARCH_FRAC;
        for (int i = 0; i < steps; i++) {
            float v0 = depth * i / steps;
            float v1 = depth * (i + 1) / steps;
            float inset = BattlefieldLayout.tokenArchInset((v0 + v1) * 0.5f);
            int sx = Math.min(srcW - 1, Math.max(0, Math.round(inset * srcW)));
            int sw = Math.max(1, srcW - sx * 2);
            int sy = Math.min(srcH - 1, Math.max(0, Math.round(v0 * srcH)));
            int sh = Math.max(1, Math.min(srcH - sy, Math.round((v1 - v0) * srcH)));
            float x = left + inset * cw;
            float bw = Math.max(1f, cw * (1f - inset * 2f));
            float y = top - v1 * ch;
            float bh = (v1 - v0) * ch + 0.75f;
            batch.draw(t, x, y, 0f, 0f, bw, bh, 1f, 1f, 0f, sx, sy, sw, sh, false, false);
        }
        int bodyY = Math.min(srcH - 1, Math.max(0, Math.round(depth * srcH)));
        int bodyH = Math.max(1, srcH - bodyY);
        float y = bottom;
        float bh = ch * (1f - depth) + 0.75f;
        batch.draw(t, left, y, 0f, 0f, cw, bh, 1f, 1f, 0f, 0, bodyY, srcW, bodyH, false, false);
    }

    /** Filled token silhouette for glows. Square bottom, semicircular top. */
    private void fillToken(float cx, float cy, float cw, float ch) {
        float left = cx - cw / 2f;
        float bottom = cy - ch / 2f;
        float top = bottom + ch;
        int steps = 18;
        float prevX = left;
        float prevY = top - BattlefieldLayout.tokenArchFraction(0f) * ch;
        for (int i = 1; i <= steps; i++) {
            float u = i / (float) steps;
            float x = left + cw * u;
            float y = top - BattlefieldLayout.tokenArchFraction(u) * ch;
            shapes.triangle(prevX, bottom, prevX, prevY, x, y);
            shapes.triangle(prevX, bottom, x, y, x, bottom);
            prevX = x;
            prevY = y;
        }
    }

    /** Outer edge in the card's hue, with a thin lighter inner edge of that same hue. */
    private void drawCardBorder(float cx, float cy, float cw, float ch, float alpha, Color hue) {
        float dark = 3f;
        float light = 1.25f;
        float left = cx - cw / 2f;
        float bottom = cy - ch / 2f;
        Color edge = hue == null ? FRAME_STEEL : hue;
        float lift = edge == FRAME_BLACK ? 0.22f : 0.12f;
        batch.setColor(edge.r, edge.g, edge.b, 0.96f * alpha);
        batch.draw(pixel(), left - dark, bottom - dark, cw + dark * 2f, dark);
        batch.draw(pixel(), left - dark, bottom + ch, cw + dark * 2f, dark);
        batch.draw(pixel(), left - dark, bottom, dark, ch);
        batch.draw(pixel(), left + cw, bottom, dark, ch);
        batch.setColor(
                edge.r + (1f - edge.r) * lift,
                edge.g + (1f - edge.g) * lift,
                edge.b + (1f - edge.b) * lift,
                0.96f * alpha);
        batch.draw(pixel(), left, bottom, cw, light);
        batch.draw(pixel(), left, bottom + ch - light, cw, light);
        batch.draw(pixel(), left, bottom, light, ch);
        batch.draw(pixel(), left + cw - light, bottom, light, ch);
        batch.setColor(Color.WHITE);
    }

    /** Arched top, square bottom, so a token is obvious next to a real card. */
    private void drawTokenBorder(float cx, float cy, float cw, float ch, float alpha, Color hue) {
        Color edge = hue == null ? FRAME_STEEL : hue;
        batch.setColor(edge.r, edge.g, edge.b, 0.96f * alpha);
        float top = cy + ch / 2f;
        float bottom = cy - ch / 2f;
        float left = cx - cw / 2f;
        float right = cx + cw / 2f;
        int steps = 24;
        float prevX = left;
        float prevY = top - BattlefieldLayout.tokenArchFraction(0f) * ch;
        float leftY = prevY;
        for (int i = 1; i <= steps; i++) {
            float u = i / (float) steps;
            float x = left + cw * u;
            float y = top - BattlefieldLayout.tokenArchFraction(u) * ch;
            drawEdge(prevX, prevY, x, y, 4.5f);
            prevX = x;
            prevY = y;
        }
        drawEdge(left, leftY, left, bottom, 4.5f);
        drawEdge(right, top - BattlefieldLayout.tokenArchFraction(1f) * ch, right, bottom, 4.5f);
        drawEdge(left, bottom, right, bottom, 4.5f);
        batch.setColor(Color.WHITE);
    }

    private void drawEdge(float x0, float y0, float x1, float y1, float thick) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.hypot(dx, dy);
        if (len < 0.5f) return;
        float ang = (float) Math.toDegrees(Math.atan2(dy, dx));
        batch.draw(pixel(), x0, y0 - thick / 2f, 0f, thick / 2f, len, thick, 1f, 1f, ang,
                0, 0, 1, 1, false, false);
    }

    private Color frameColor(Perm p) {
        if (p.frame != null) return p.frame;
        p.frame = borderFor(p.name, p.kind);
        return p.frame;
    }

    /** Lands are brown. No colored pips is steel. One color is that color. Two or more are bronzy gold. */
    private static Color borderFor(String name, BattlefieldLayout.Kind kind) {
        List<String> lines = readScript(name);
        if (lines != null) {
            String types = "";
            String cost = "";
            boolean sawCost = false;
            for (String line : lines) {
                if (line.startsWith("Types:")) types = line.substring("Types:".length());
                else if (line.startsWith("ManaCost:")) {
                    cost = line.substring("ManaCost:".length());
                    sawCost = true;
                }
            }
            if (types.toLowerCase(Locale.ROOT).contains("land")) return FRAME_BROWN;
            if (sawCost) return colorFromPips(colorsInCost(cost));
        }
        Color named = scriptlessBorder(name);
        if (named != null) return named;
        return kind == BattlefieldLayout.Kind.LAND ? FRAME_BROWN : FRAME_STEEL;
    }

    /** Names on this board that have no card script. A real ManaCost still wins above. */
    private static Color scriptlessBorder(String name) {
        if (name == null) return null;
        return switch (name) {
            case "Bear Cub", "Growth Wolf", "Grizzly Bears", "Llanowar Elves" -> FRAME_GREEN;
            case "Hero", "Glorious Anthem" -> FRAME_WHITE;
            case "Cursed Knight", "Bad Moon", "Duress" -> FRAME_BLACK;
            case "Veteran", "Ogre", "Shock" -> FRAME_RED;
            case "Sprite" -> FRAME_BLUE;
            case "Sol Ring", "Worn Powerstone" -> FRAME_STEEL;
            default -> null;
        };
    }

    private static Color colorFromPips(String colors) {
        if (colors.length() >= 2) return FRAME_GOLD;
        return switch (colors) {
            case "W" -> FRAME_WHITE;
            case "U" -> FRAME_BLUE;
            case "B" -> FRAME_BLACK;
            case "R" -> FRAME_RED;
            case "G" -> FRAME_GREEN;
            default -> FRAME_STEEL;
        };
    }

    /** W, U, B, R, and G, including each half of a hybrid pip. Generic numbers add nothing. */
    private static String colorsInCost(String cost) {
        StringBuilder found = new StringBuilder();
        if (cost == null) return "";
        for (String token : cost.trim().split("\\s+")) {
            if (token.isEmpty()) continue;
            for (String part : token.split("/")) {
                if (part.length() != 1) continue;
                char c = Character.toUpperCase(part.charAt(0));
                if ("WUBRG".indexOf(c) >= 0 && found.indexOf(String.valueOf(c)) < 0) found.append(c);
            }
        }
        return found.toString();
    }

    /**
     * Large mipmapped scan in the fixed well, off the row. Drawn with the zone grid
     * so battlefield cards do not cover it. The full zoom still paints later.
     */
    /** His declare-blockers step. Live play follows the strip, which stays current while phase does not. */
    private boolean declaringBlocks() {
        return shownPhase == BattlefieldLayout.StripPhase.DECLARE_BLOCKERS || phase == Phase.BLOCKERS;
    }

    /** A blocker drag keeps the gripped card at battlefield size. The large well would hide their attackers. */
    private boolean blockingDrag() {
        return draggingBoard && pressedPerm != null && pressedPerm.alive && pressedPerm.you
                && declaringBlocks()
                && pressedPerm.kind == BattlefieldLayout.Kind.CREATURE
                && !pressedPerm.attacking;
    }

    private void drawBrowsePreview(float w, float h) {
        if (cardZoom || blockingDrag()) return;
        if (hoverZone >= 0 && openZone != ZoneView.NONE) {
            List<ZoneCard> cards = visibleZoneCards();
            if (hoverZone >= cards.size()) return;
            ZoneCard peeked = cards.get(hoverZone);
            if (openZone == ZoneView.LIBRARY && !peeked.revealed) return;
            if (peeked.hiddenBack && !peeked.ownerSees) return;
            BattlefieldLayout.ZoneGrid grid = openGrid(w, h);
            if (grid == null) return;
            HandLayout.Pose well = BattlefieldLayout.browsePreview(w, h, grid.panel.cy);
            if (peeked.hiddenBack && peeked.ownerSees) {
                Texture face = textureFor(peeked.name);
                if (face != null) {
                    batch.setColor(Color.WHITE);
                    drawTex(face, well.cx, well.cy, well.w, well.h, 0f);
                } else {
                    drawOracleFrame(peeked.name, missingArtAt(well.cx, well.cy, w, h));
                }
            } else {
                drawZoneCard(peeked, well, well.w, well.h);
            }
            HandLayout.Pose info = BattlefieldLayout.browseInfoPanel(w, h, well);
            drawCardInfo(info, peeked.name);
            boolean infoRight = info.cx >= well.cx;
            drawOtherFaceBeside(w, h, well, peeked.name, peeked.forgeCard, peeked.paper, !infoRight);
        }
    }

    /**
     * Resting fan in hand order, then the grown hover last so the text box and P/T
     * sit on top of neighbors. Hit tests stay on resting slots; this draw order is
     * visual only. A gripped drag is the only card taken out of fan order.
     */
    private void drawHandFan(List<HandCard> cards, boolean willCast) {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        int n = cards.size();
        int gripped = draggingHand && pressHand >= 0 && pressHand < n ? pressHand : -1;
        int grown = !draggingHand && hover >= 0 && hover < n ? hover : -1;
        for (int i = 0; i < n; i++) {
            if (i == gripped) continue;
            HandCard card = cards.get(i);
            if (i == grown) drawHandPose(card, HandLayout.restPose(w, h, n, i), false);
            else drawHand(card, false);
        }
        if (grown >= 0) drawHand(cards.get(grown), false);
        if (gripped >= 0) drawHand(cards.get(gripped), willCast);
        for (int i = 0; i < n; i++) {
            if (i == gripped || i == grown || !cards.get(i).bottomPick) continue;
            HandCard card = cards.get(i);
            float top = card.cy + card.h * 0.42f;
            drawPlate(stripFont, "Bottom", card.cx, Math.max(22f, top), GOLD);
        }
    }

    /** Ghost fan in slot order, then the grown hover last. Hits stay on resting tiles. */
    private void drawGhostFan(List<HandCard> cards) {
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        int n = cards.size();
        int grown = ghostHover >= 0 && ghostHover < n ? ghostHover : -1;
        for (int i = 0; i < n; i++) {
            if (i == grown) drawGhostAt(cards.get(i), ghostRestPose(w, h, n, i));
            else drawGhost(cards.get(i));
        }
        if (grown >= 0) drawGhost(cards.get(grown));
    }

    /** The next control under Game, Important, AI, and Debug. Same column, same size. */
    private HandLayout.Pose optionsButton(float w, float h) {
        return sideLogTab(w, h, SIDE_LOG_LABELS.length);
    }

    /** Centre x, centre y, width, height. Opens under the button, flush with the right edge. */
    private float[] optPanel(float w, float h) {
        HandLayout.Pose btn = optionsButton(w, h);
        float right = btn.cx + btn.w / 2f;
        float top = btn.cy - btn.h / 2f - 8f;
        float left = Math.max(8f, right - OPT_PANEL_W);
        float cy = top - OPT_PANEL_H / 2f;
        if (cy - OPT_PANEL_H / 2f < 8f) cy = 8f + OPT_PANEL_H / 2f;
        return new float[] { left + OPT_PANEL_W / 2f, cy, OPT_PANEL_W, OPT_PANEL_H };
    }

    /** Save is the left row. Quit is the right row. */
    private float[] optRow(float w, float h, boolean quit) {
        float[] panel = optPanel(w, h);
        float left = panel[0] - panel[2] / 2f;
        float bottom = panel[1] - panel[3] / 2f;
        float rowW = 148f;
        float rowH = 32f;
        float cy = bottom + 16f + rowH / 2f;
        float cx = quit ? left + panel[2] - 16f - rowW / 2f : left + 16f + rowW / 2f;
        return new float[] { cx, cy, rowW, rowH };
    }

    private boolean boxContains(float cx, float cy, float bw, float bh) {
        return Math.abs(mx - cx) <= bw / 2f && Math.abs(my - cy) <= bh / 2f;
    }

    private boolean optionsBlocks(float w, float h) {
        HandLayout.Pose btn = optionsButton(w, h);
        if (boxContains(btn.cx, btn.cy, btn.w, btn.h)) return true;
        if (!optionsOpen) return false;
        float[] panel = optPanel(w, h);
        return boxContains(panel[0], panel[1], panel[2], panel[3]);
    }

    private boolean pressOptions(float w, float h) {
        HandLayout.Pose btn = optionsButton(w, h);
        if (boxContains(btn.cx, btn.cy, btn.w, btn.h)) {
            optionsOpen = !optionsOpen;
            return true;
        }
        if (!optionsOpen) return false;
        float[] save = optRow(w, h, false);
        float[] quit = optRow(w, h, true);
        if (boxContains(save[0], save[1], save[2], save[3])) {
            saveDuel();
            return true;
        }
        if (liveReady()) {
            float[] change = optChange(w, h);
            if (boxContains(change[0], change[1], change[2], change[3])) {
                changeDecks();
                return true;
            }
        } else if (lobbying) {
            float[] back = optChange(w, h);
            if (boxContains(back[0], back[1], back[2], back[3])) {
                leaveLobby();
                return true;
            }
        }
        if (boxContains(quit[0], quit[1], quit[2], quit[3])) {
            quitDuel();
            return true;
        }
        float[] panel = optPanel(w, h);
        if (boxContains(panel[0], panel[1], panel[2], panel[3])) return true;
        optionsOpen = false;
        return true;
    }

    private void saveDuel() {
        optionsNote = live == null
                ? "This window has no Forge match to save."
                : live.saveMatch();
    }

    private void quitDuel() {
        if (live != null) live.shutdownMatch();
        Gdx.app.exit();
    }

    private void drawOptions(float w, float h) {
        Gdx.gl.glEnable(GL20.GL_BLEND);
        Gdx.gl.glBlendFunc(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        shapes.setProjectionMatrix(batch.getProjectionMatrix());
        HandLayout.Pose btn = optionsButton(w, h);
        float btnX = btn.cx;
        float btnY = btn.cy;
        shapes.begin(ShapeRenderer.ShapeType.Filled);
        shapes.setColor(0.16f, 0.18f, 0.22f, 0.96f);
        shapes.rect(btnX - btn.w / 2f, btnY - btn.h / 2f, btn.w, btn.h);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
        strokeRect(btnX - btn.w / 2f, btnY - btn.h / 2f, btn.w, btn.h, 2f);
        if (optionsOpen) {
            float[] panel = optPanel(w, h);
            float left = panel[0] - panel[2] / 2f;
            float bottom = panel[1] - panel[3] / 2f;
            shapes.setColor(0.08f, 0.09f, 0.11f, 0.97f);
            shapes.rect(left, bottom, panel[2], panel[3]);
            shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
            strokeRect(left, bottom, panel[2], panel[3], 2f);
            float[] save = optRow(w, h, false);
            float[] quit = optRow(w, h, true);
            shapes.setColor(0.18f, 0.28f, 0.22f, 1f);
            shapes.rect(save[0] - save[2] / 2f, save[1] - save[3] / 2f, save[2], save[3]);
            shapes.setColor(0.32f, 0.16f, 0.14f, 1f);
            shapes.rect(quit[0] - quit[2] / 2f, quit[1] - quit[3] / 2f, quit[2], quit[3]);
            if (liveReady()) {
                float[] change = optChange(w, h);
                shapes.setColor(0.20f, 0.24f, 0.34f, 1f);
                shapes.rect(change[0] - change[2] / 2f, change[1] - change[3] / 2f, change[2], change[3]);
            } else if (lobbying) {
                float[] back = optChange(w, h);
                shapes.setColor(0.16f, 0.28f, 0.34f, 1f);
                shapes.rect(back[0] - back[2] / 2f, back[1] - back[3] / 2f, back[2], back[3]);
            }
        }
        shapes.end();
        batch.begin();
        drawCentered("Options", btnX, btnY + 6f, Color.WHITE);
        if (optionsOpen) {
            float[] panel = optPanel(w, h);
            float left = panel[0] - panel[2] / 2f;
            float top = panel[1] + panel[3] / 2f;
            stripFont.setColor(new Color(0.9f, 0.88f, 0.8f, 1f));
            stripFont.draw(batch, optionsNote, left + 14f, top - 14f, panel[2] - 28f, Align.left, true);
            stripFont.setColor(Color.WHITE);
            float[] save = optRow(w, h, false);
            float[] quit = optRow(w, h, true);
            drawCentered("Save", save[0], save[1] + 6f, Color.WHITE);
            drawCentered("Quit", quit[0], quit[1] + 6f, Color.WHITE);
            if (liveReady()) {
                float[] change = optChange(w, h);
                drawCentered("Change decks", change[0], change[1] + 6f, Color.WHITE);
            } else if (lobbying) {
                float[] back = optChange(w, h);
                drawCentered("Back", back[0], back[1] + 6f, Color.WHITE);
            }
        }
        batch.end();
    }

    private void strokeRect(float x, float y, float rw, float rh, float edge) {
        shapes.rect(x, y, rw, edge);
        shapes.rect(x, y + rh - edge, rw, edge);
        shapes.rect(x, y, edge, rh);
        shapes.rect(x + rw - edge, y, edge, rh);
    }

    private void drawGhost(HandCard c) {
        drawGhostAt(c, c.cx, c.cy, c.w, c.h, c.rotation);
    }

    private void drawGhostAt(HandCard c, HandLayout.Pose pose) {
        drawGhostAt(c, pose.cx, pose.cy, pose.w, pose.h, pose.rotationDeg);
    }

    private void drawGhostAt(HandCard c, float cx, float cy, float cw, float ch, float rot) {
        drawPlayableEdge(cx, cy, cw, ch, rot);
        drawHandAt(c, cx, cy, cw, ch, rot, new Color(0.86f, 0.91f, 0.96f, 0.45f));
        batch.setColor(Color.WHITE);
    }

    private void drawHand(HandCard c, boolean glow) {
        drawHandPose(c, c.cx, c.cy, c.w, c.h, c.rotation, glow);
    }

    private void drawHandPose(HandCard c, HandLayout.Pose pose, boolean glow) {
        drawHandPose(c, pose.cx, pose.cy, pose.w, pose.h, pose.rotationDeg, glow);
    }

    private void drawHandPose(HandCard c, float cx, float cy, float cw, float ch, float rot, boolean glow) {
        if (travelingHand(c)) return;
        if (c.bottomPick) {
            batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.95f);
            drawTex(pixel(), cx, cy, cw + 12f, ch + 12f, rot);
        } else if (c.playable || glow) {
            drawPlayableEdge(cx, cy, cw, ch, rot);
        }
        drawHandAt(c, cx, cy, cw, ch, rot, Color.WHITE);
        if (c.bottomPick) drawPlate(stripFont, "Bottom", cx, cy + ch * 0.36f, GOLD);
    }

    /** Cyan glow behind the card, wider than the face, so the name and cost stay readable. */
    private void drawPlayableEdge(float cx, float cy, float cw, float ch, float rot) {
        batch.setColor(0.35f, 0.85f, 1f, 0.28f);
        drawTex(pixel(), cx, cy, cw + 18f, ch + 18f, rot);
        batch.setColor(0.5f, 0.95f, 1f, 0.95f);
        drawTex(pixel(), cx, cy, cw + 8f, ch + 8f, rot);
        batch.setColor(Color.WHITE);
    }

    private void drawHandAt(HandCard c, float cx, float cy, float cw, float ch, float rot) {
        drawHandAt(c, cx, cy, cw, ch, rot, Color.WHITE);
    }

    private void drawHandAt(HandCard c, float cx, float cy, float cw, float ch, float rot, Color tint) {
        if (c.tex != null) {
            batch.setColor(tint);
            drawTex(c.tex, cx, cy, cw, ch, rot);
        } else {
            batch.setColor(tint.r * 0.4f, tint.g * 0.4f, tint.b * 0.45f, tint.a);
            drawTex(pixel(), cx, cy, cw, ch, rot);
        }
        if (Math.abs(rot) < 8f) drawAbilityIcons(c.keywords, cx, cy, cw, ch, false, c.commander, c.commanderKey, false);
        batch.setColor(Color.WHITE);
    }

    /**
     * On a battlefield tile, power and toughness sit on a type-line band in the frame color.
     * A full-card zoom keeps a small plate over the printed box and does not paint that band.
     */
    private void drawCreatureMarks(Perm p, float cx, float cy, float cw, float ch, boolean cropped) {
        BitmapFont face = ch >= 300f ? big : ch >= 190f ? stackFont : stripFont;
        float right = cx + cw / 2f - 8f;
        float bottom = cy - ch / 2f;
        float bandH = cropped ? ch * BattlefieldLayout.BOARD_TYPE_BAND : 0f;
        if (cropped) drawTypeBand(cx, bottom, cw, bandH, frameColor(p), p.alpha);
        int shownPower = power(p);
        int remaining = toughness(p);
        String toughText = p.damage > 0 ? "[" + remaining + "]" : Integer.toString(remaining);
        glyphs.setText(face, toughText);
        float textBottom = cropped
                ? bottom + Math.max(1f, (bandH - glyphs.height) / 2f)
                : bottom + 3f;
        drawSplitPt(face, Integer.toString(shownPower), statColor(shownPower, basePower(p)),
                toughText, statColor(remaining, baseToughness(p)), right, textBottom, !cropped);
        float y = cropped ? bottom + bandH + 2f : bottom + ch * 0.16f + 2f;
        if (p.counters > 0) y = drawRightPlate(face, p.counters + "x +1/+1", right, y, GREEN);
        if (p.buff > 0) y = drawRightPlate(face, "1x +" + p.buff + "/+" + p.buff, right, y, GREEN);
        if (p.minusCounters > 0) y = drawRightPlate(face, p.minusCounters + "x -1/-1", right, y, RED);
        if (p.debuff > 0) drawRightPlate(face, "1x -" + p.debuff + "/-" + p.debuff, right, y, RED);
    }

    /** Current loyalty on the type-line band. Same corner as creature P/T. */
    private void drawLoyaltyMark(Perm p, float cx, float cy, float cw, float ch, boolean cropped) {
        BitmapFont face = ch >= 300f ? big : ch >= 190f ? stackFont : stripFont;
        float right = cx + cw / 2f - 8f;
        float bottom = cy - ch / 2f;
        float bandH = cropped ? ch * BattlefieldLayout.BOARD_TYPE_BAND : 0f;
        if (cropped) drawTypeBand(cx, bottom, cw, bandH, frameColor(p), p.alpha);
        int shown = p.loyalty;
        int printed = p.hasLoyalty ? p.printedLoyalty : shown;
        String text = Integer.toString(shown);
        glyphs.setText(face, text);
        float textBottom = cropped
                ? bottom + Math.max(1f, (bandH - glyphs.height) / 2f)
                : bottom + 3f;
        float width = glyphs.width;
        float left = right - width;
        float y = textBottom + glyphs.height;
        if (!cropped) {
            float pad = 3f;
            batch.setColor(0f, 0f, 0f, 0.72f);
            batch.draw(pixel(), left - pad, y - glyphs.height, width + pad * 2f, glyphs.height + pad * 2f);
            batch.setColor(Color.WHITE);
        }
        face.setColor(statColor(shown, printed));
        face.draw(batch, text, left, y);
        face.setColor(Color.WHITE);
    }

    /** Sieges keep the printed face. Planeswalkers and enchantments are cropped tiles. */
    private static boolean fullPrintedBoardCard(Perm p) {
        if (p == null) return false;
        return BattlefieldLayout.fullPrintedCard(p.kind);
    }

    /** Lore / chapter number when Forge exposes it. Sagas keep a readable chapter. */
    private void drawLoreMark(Perm p, float cx, float cy, float cw, float ch) {
        int lore = liveLore(p);
        if (lore <= 0 && !isSagaPerm(p)) return;
        if (lore <= 0) return;
        BitmapFont face = ch >= 300f ? big : ch >= 190f ? stackFont : stripFont;
        float right = cx + cw / 2f - 8f;
        float bottom = cy - ch / 2f;
        String text = Integer.toString(lore);
        glyphs.setText(face, text);
        float textBottom = bottom + 3f;
        float width = glyphs.width;
        float left = right - width;
        float y = textBottom + glyphs.height;
        float pad = 3f;
        batch.setColor(0f, 0f, 0f, 0.72f);
        batch.draw(pixel(), left - pad, y - glyphs.height, width + pad * 2f, glyphs.height + pad * 2f);
        batch.setColor(Color.WHITE);
        face.setColor(Color.WHITE);
        face.draw(batch, text, left, y);
        face.setColor(Color.WHITE);
    }

    private static int liveLore(Perm p) {
        if (p == null) return 0;
        if (p.forgeCard != null) {
            int n = loreOf(p.forgeCard);
            if (n > 0) return n;
        }
        return p.lore;
    }

    private static boolean isSagaPerm(Perm p) {
        if (p == null) return false;
        if (BattlefieldLayout.isSagaType(p.typeLine)) return true;
        if (p.forgeCard == null) return false;
        try {
            CardView.CardStateView state = p.forgeCard.getCurrentState();
            if (state == null || state.getType() == null) return false;
            return state.getType().hasSubtype("Saga");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int loreOf(CardView card) {
        if (card == null) return 0;
        try {
            CounterType lore = CounterType.getType("LORE");
            if (lore == null) return 0;
            return card.getCounters(lore);
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String typeLineOf(CardView card) {
        if (card == null) return "";
        try {
            CardView.CardStateView state = card.getCurrentState();
            if (state == null || state.getType() == null) return "";
            String type = state.getType().toString();
            return type == null ? "" : type;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** Remaining defense on the printed card. Same corner as planeswalker loyalty. */
    private void drawDefenseMark(Perm p, float cx, float cy, float cw, float ch, boolean cropped) {
        BitmapFont face = ch >= 300f ? big : ch >= 190f ? stackFont : stripFont;
        float right = cx + cw / 2f - 8f;
        float bottom = cy - ch / 2f;
        float bandH = cropped ? ch * BattlefieldLayout.BOARD_TYPE_BAND : 0f;
        if (cropped) drawTypeBand(cx, bottom, cw, bandH, frameColor(p), p.alpha);
        int shown = p.defense;
        int printed = p.hasDefense ? p.printedDefense : shown;
        String text = Integer.toString(shown);
        glyphs.setText(face, text);
        float textBottom = cropped
                ? bottom + Math.max(1f, (bandH - glyphs.height) / 2f)
                : bottom + 3f;
        float width = glyphs.width;
        float left = right - width;
        float y = textBottom + glyphs.height;
        if (!cropped) {
            float pad = 3f;
            batch.setColor(0f, 0f, 0f, 0.72f);
            batch.draw(pixel(), left - pad, y - glyphs.height, width + pad * 2f, glyphs.height + pad * 2f);
            batch.setColor(Color.WHITE);
        }
        face.setColor(statColor(shown, printed));
        face.draw(batch, text, left, y);
        face.setColor(Color.WHITE);
    }

    /**
     * Chosen type on the type-line bar. Any permanent with CardView ChosenType —
     * Cavern, Unclaimed Territory, Herald's Horn, Cover of Darkness, not a name list.
     * Drawn inside the tile spin so a 15° tap still reads.
     */
    private void drawChosenType(Perm p, float cx, float cy, float cw, float ch) {
        String type = p.choice == null ? "" : p.choice.trim();
        if (type.isBlank() && p.forgeCard != null) {
            type = BattlefieldLive.chosenTypeOf(p.forgeCard);
        }
        if (type.isBlank()) {
            if (p.choiceCard) drawCorner("Type?", cx, cy - ch * 0.38f, GOLD);
            return;
        }
        boolean cropped = !fullPrintedBoardCard(p);
        boolean bandAlready = p.kind == BattlefieldLayout.Kind.CREATURE
                || p.kind == BattlefieldLayout.Kind.PLANESWALKER;
        BitmapFont face = ch >= 220f ? stackFont : stripFont;
        float inset = Math.max(4f, cw * 0.06f);
        float maxW = bandAlready ? cw * 0.58f : cw - inset * 2f;
        if (maxW < 12f) maxW = cw * 0.9f;
        String shown = fitTypeName(face, type, maxW);
        glyphs.setText(face, shown);
        float bottom = cy - ch / 2f;
        float bandH = cropped ? ch * BattlefieldLayout.BOARD_TYPE_BAND : 0f;
        if (cropped && !bandAlready) {
            bandH = Math.max(bandH, Math.min(glyphs.height + 6f, ch * 0.22f));
            drawTypeBand(cx, bottom, cw, bandH, frameColor(p), p.alpha);
        }
        float textBottom = cropped
                ? bottom + Math.max(1f, (Math.max(bandH, glyphs.height + 4f) - glyphs.height) / 2f)
                : bottom + 4f;
        float x = cx - cw / 2f + inset;
        float y = textBottom + glyphs.height;
        float pad = 3f;
        batch.setColor(0f, 0f, 0f, 0.62f * p.alpha);
        batch.draw(pixel(), x - pad, y - glyphs.height - 1f, glyphs.width + pad * 2f, glyphs.height + 4f);
        batch.setColor(Color.WHITE);
        face.setColor(GOLD);
        face.draw(batch, shown, x, y);
        face.setColor(Color.WHITE);
    }

    /** Keep the type word on the bar. Long names lose the tail, not the tile. */
    private String fitTypeName(BitmapFont face, String type, float maxW) {
        if (type == null || type.isBlank()) return "";
        glyphs.setText(face, type);
        if (glyphs.width <= maxW || maxW < 8f) return type;
        String cut = type;
        while (cut.length() > 1) {
            cut = cut.substring(0, cut.length() - 1);
            glyphs.setText(face, cut + "…");
            if (glyphs.width <= maxW) return cut + "…";
        }
        return type;
    }

    /** Dark type line in the card's frame hue. The art above this band stays uncovered. */
    private void drawTypeBand(float cx, float bottom, float cw, float bandH, Color hue, float alpha) {
        if (bandH < 1f) return;
        Color edge = hue == null ? FRAME_STEEL : hue;
        float left = cx - cw / 2f;
        batch.setColor(edge.r * 0.42f, edge.g * 0.42f, edge.b * 0.42f, 0.96f * alpha);
        batch.draw(pixel(), left, bottom, cw, bandH);
        batch.setColor(
                Math.min(1f, edge.r + 0.28f),
                Math.min(1f, edge.g + 0.28f),
                Math.min(1f, edge.b + 0.28f),
                0.95f * alpha);
        batch.draw(pixel(), left, bottom + bandH - 1.5f, cw, 1.5f);
        batch.setColor(Color.WHITE);
    }

    /** White matches the printed base. Green is above it. Red is below it, including damage. */
    private static Color statColor(int shown, int printed) {
        if (shown > printed) return GREEN;
        if (shown < printed) return RED;
        return Color.WHITE;
    }

    private int basePower(Perm p) {
        return p.hasPrinted ? p.printedPower : p.power;
    }

    private int baseToughness(Perm p) {
        return p.hasPrinted ? p.printedToughness : p.toughness;
    }

    private boolean lightsOut(Perm p) {
        return p.kind == BattlefieldLayout.Kind.CREATURE && p.damage > 0 && toughness(p) <= 0;
    }

    /** Heavy dim over the crop. The power and toughness plate is drawn after this. */
    private void drawLightsOut(float cx, float cy, float cw, float ch, float alpha) {
        batch.setColor(0f, 0f, 0f, 0.76f * alpha);
        drawTex(pixel(), cx, cy, cw + 8f, ch + 8f, 0f);
        batch.setColor(Color.WHITE);
    }

    /** Power and toughness share one plate. Each number keeps its own color. */
    private void drawSplitPt(BitmapFont face, String powerText, Color powerColor, String toughText,
            Color toughColor, float right, float bottom, boolean plate) {
        String slash = "/";
        glyphs.setText(face, powerText);
        float powerW = glyphs.width;
        float height = glyphs.height;
        glyphs.setText(face, slash);
        float slashW = glyphs.width;
        glyphs.setText(face, toughText);
        float toughW = glyphs.width;
        float width = powerW + slashW + toughW;
        float left = right - width;
        float y = bottom + height;
        if (plate) {
            float pad = 3f;
            batch.setColor(0f, 0f, 0f, 0.72f);
            batch.draw(pixel(), left - pad, y - height, width + pad * 2f, height + pad * 2f);
            batch.setColor(Color.WHITE);
        }
        face.setColor(powerColor);
        face.draw(batch, powerText, left, y);
        face.setColor(Color.WHITE);
        face.draw(batch, slash, left + powerW, y);
        face.setColor(toughColor);
        face.draw(batch, toughText, left + powerW + slashW, y);
        face.setColor(Color.WHITE);
    }

    /**
     * Short upright name on a grey tile when the picture is missing.
     * One layout of a short prefix. The old shrink loop laid the name out again
     * after appending an ellipsis, and that second layout never returned, so the
     * window died on the first permanent without art.
     */
    private void drawFittedName(String text, float cx, float cy, float cw) {
        if (text == null || text.isBlank()) return;
        int room = nameBudget(stripFont, Math.max(8f, cw - 8f));
        String shown = text.length() > room ? text.substring(0, room) : text;
        glyphs.setText(stripFont, shown);
        float pad = 3f;
        float x = cx - glyphs.width / 2f;
        float y = cy;
        batch.setColor(0f, 0f, 0f, 0.72f);
        batch.draw(pixel(), x - pad, y - glyphs.height, glyphs.width + pad * 2f, glyphs.height + pad * 2f);
        batch.setColor(Color.WHITE);
        stripFont.setColor(Color.WHITE);
        stripFont.draw(batch, glyphs, x, y);
    }

    /** How many characters fit, from the font's own advances. Does not lay out the string. */
    private static int nameBudget(BitmapFont face, float limit) {
        BitmapFont.BitmapFontData data = face.getData();
        BitmapFont.Glyph sample = data.getGlyph('n');
        if (sample == null) sample = data.missingGlyph;
        float adv = sample == null ? 7f : Math.max(1f, sample.xadvance * data.scaleX);
        int room = (int) (limit / adv);
        if (room < 3) room = 3;
        if (room > 28) room = 28;
        return room;
    }

    /** Forge's ability sprites, down the left of the art, under the name and mana cost. */
    private void drawAbilityIcons(java.util.List<String> keywords, float cx, float cy, float cw, float ch,
            boolean token, boolean commander, String commanderKey, boolean hideProtect) {
        boolean showMark = commander && commanderMark != null;
        boolean showKeys = !abilityIcons.isEmpty() && keywords != null && !keywords.isEmpty();
        if (!showMark && !showKeys) return;
        float size = Math.max(11f, Math.min(ch * 0.16f, 22f));
        float inset = token ? cw * BattlefieldLayout.tokenArchInset(0.34f) : 0f;
        float x = cx - cw / 2f + 3f + inset;
        float y = cy + ch / 2f - ch * 0.30f - size;
        float floor = cy - ch / 2f + ch * BattlefieldLayout.BOARD_TYPE_BAND + 2f;
        if (showMark) {
            float mark = Math.max(size + 3f, Math.min(ch * 0.24f, 28f));
            if (y + size - mark >= floor) {
                batch.setColor(commanderTint(commanderKey));
                batch.draw(commanderMark, x, y + size - mark, mark, mark);
                batch.setColor(Color.WHITE);
                y -= mark + 2f;
            }
        }
        if (!showKeys) return;
        for (String word : keywords) {
            if ("haste".equals(word)) continue;
            if (hideProtect && ("hexproof".equals(word) || "shroud".equals(word))) continue;
            TextureRegion icon = abilityIcons.get(word);
            if (icon == null || y < floor) continue;
            batch.setColor(Color.WHITE);
            batch.draw(icon, x, y, size, size);
            y -= size + 2f;
        }
    }

    /** {@code bottom} is the lower edge. Returns the lower edge of the next mark above. */
    private float drawRightPlate(BitmapFont face, String text, float right, float bottom, Color color) {
        glyphs.setText(face, text);
        float x = right - glyphs.width / 2f;
        float y = bottom + glyphs.height;
        drawPlate(face, text, x, y, color);
        return y + 4f;
    }

    /** One short line. Used for a missing face, or the chosen type along the bottom edge. */
    private void drawCorner(String text, float x, float y, Color color) {
        drawPlate(stripFont, text, x, y, color);
    }

    private void drawPlate(BitmapFont face, String text, float x, float y, Color color) {
        glyphs.setText(face, text);
        float pad = 3f;
        batch.setColor(0f, 0f, 0f, 0.72f);
        batch.draw(pixel(), x - glyphs.width / 2f - pad, y - glyphs.height, glyphs.width + pad * 2f, glyphs.height + pad * 2f);
        batch.setColor(Color.WHITE);
        face.setColor(color);
        face.draw(batch, text, x - glyphs.width / 2f, y);
    }

    private void drawTag(String text, float x, float y, Color color) {
        glyphs.setText(font, text);
        float pad = 3f;
        batch.setColor(0f, 0f, 0f, 0.66f);
        batch.draw(pixel(), x - glyphs.width / 2f - pad, y - glyphs.height, glyphs.width + pad * 2f, glyphs.height + pad * 2f);
        batch.setColor(Color.WHITE);
        font.setColor(color);
        font.draw(batch, text, x - glyphs.width / 2f, y);
    }

    private void drawCentered(String text, float x, float y, Color color) {
        drawCentered(font, text, x, y, color);
    }

    /** {@code y} is the top of the text, matching {@link BitmapFont#draw}. */
    private void drawCentered(BitmapFont face, String text, float x, float y, Color color) {
        glyphs.setText(face, text);
        batch.setColor(Color.WHITE);
        face.setColor(color);
        face.draw(batch, text, x - glyphs.width / 2f, y);
    }

    /** {@code cy} is the vertical center of the text. */
    private void drawCenteredOn(BitmapFont face, String text, float x, float cy, Color color) {
        glyphs.setText(face, text);
        drawCentered(face, text, x, cy + glyphs.height / 2f, color);
    }

    private void drawBig(String text, float x, float y) {
        glyphs.setText(big, text);
        batch.setColor(Color.WHITE);
        big.setColor(Color.WHITE);
        big.draw(batch, text, x - glyphs.width / 2f, y);
    }

    private void drawTex(Texture t, float cx, float cy, float w, float h, float rot) {
        batch.draw(t, cx - w / 2f, cy - h / 2f, w / 2f, h / 2f, w, h, 1f, 1f, rot,
                0, 0, t.getWidth(), t.getHeight(), false, false);
    }

    /**
     * Forge's match face at {@code px}, nearest-filtered, scale 1.
     * Falls back to the unscaled libGDX bitmap if FreeType or {@code font1.ttf} is missing.
     */
    private BitmapFont face(int px) {
        try {
            if (fontGenerator == null) {
                FileHandle ttf = Gdx.files.classpath(FORGE_UI_FONT);
                if (ttf == null || !ttf.exists()) throw new IllegalStateException(FORGE_UI_FONT + " missing");
                fontGenerator = new FreeTypeFontGenerator(ttf);
                System.err.println("Battlefield UI font: " + FORGE_UI_FONT
                        + " at " + STRIP_PX + "/" + BODY_PX + "/" + STACK_PX + "/" + LIFE_PX + " px");
            }
            FreeTypeFontParameter parameter = new FreeTypeFontParameter();
            parameter.size = px;
            parameter.minFilter = TextureFilter.Nearest;
            parameter.magFilter = TextureFilter.Nearest;
            parameter.genMipMaps = false;
            parameter.hinting = FreeTypeFontGenerator.Hinting.AutoMedium;
            parameter.characters = " " + FreeTypeFontGenerator.DEFAULT_CHARS + "\u2026\u2014\u2013\u00A0";
            BitmapFont generated = fontGenerator.generateFont(parameter);
            generated.setUseIntegerPositions(true);
            generated.getData().setScale(1f);
            return generated;
        } catch (Throwable failed) {
            System.err.println("Battlefield UI font fell back to unscaled bitmap at " + px + " px: " + failed);
            return unscaledBitmap();
        }
    }

    private static BitmapFont unscaledBitmap() {
        BitmapFont fallback = new BitmapFont();
        fallback.setUseIntegerPositions(true);
        fallback.getData().setScale(1f);
        for (int i = 0; i < fallback.getRegions().size; i++) {
            fallback.getRegions().get(i).getTexture().setFilter(TextureFilter.Nearest, TextureFilter.Nearest);
        }
        return fallback;
    }

    private Texture pixel() {
        if (pixel == null) {
            Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
            pm.setColor(Color.WHITE);
            pm.fill();
            pixel = new Texture(pm);
            pm.dispose();
        }
        return pixel;
    }

    private void readPointer(int sx, int sy) {
        mx = sx;
        my = BattlefieldLayout.screenYToLayoutY(Gdx.graphics.getHeight(), sy);
    }

    private static void easeHand(HandCard c, float dt) {
        c.cx = HandLayout.easeToward(c.cx, c.tcx, dt);
        c.cy = HandLayout.easeToward(c.cy, c.tcy, dt);
        c.w = HandLayout.easeToward(c.w, c.tw, dt);
        c.h = HandLayout.easeToward(c.h, c.th, dt);
        c.rotation = HandLayout.easeToward(c.rotation, c.trot, dt);
    }

    private static void snapHand(HandCard c) {
        c.cx = c.tcx; c.cy = c.tcy; c.w = c.tw; c.h = c.th; c.rotation = c.trot;
    }

    private static float dist(float x0, float y0, float x1, float y1) {
        float dx = x1 - x0, dy = y1 - y0;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private boolean onWidget(float sw, float sh) {
        BattlefieldLayout.WidgetBox[] boxes = BattlefieldLayout.widgetBoxes(sw, sh, youDx, youDy, enemyDx, enemyDy);
        int hit = BattlefieldLayout.widgetIndexAt(sw, sh, mx, my, youDx, youDy, enemyDx, enemyDy);
        if (hit < 0 || hit >= boxes.length) return false;
        BattlefieldLayout.WidgetBox box = boxes[hit];
        if (box.part == BattlefieldLayout.WidgetPart.AVATAR_LIFE) {
            return false;
        }
        if (box.part == BattlefieldLayout.WidgetPart.GRAVE) {
            toggleZone(box.owner, ZoneView.GRAVE);
            return true;
        }
        if (box.part == BattlefieldLayout.WidgetPart.EXILE) {
            if (exileTileHit(mx, my) != null) return false;
            toggleZone(box.owner, ZoneView.EXILE);
            return true;
        }
        if (box.part == BattlefieldLayout.WidgetPart.LIBRARY) {
            if (box.owner == BattlefieldLayout.StripOwner.YOU && pendingTopLook > 0) {
                openZone = ZoneView.NONE;
                lookTop = !yourLibrary.isEmpty();
                note = lookTop ? "Look at the top " + topLookCount() + ". Choose one." : "Your library is empty.";
                return true;
            }
            if (box.owner == BattlefieldLayout.StripOwner.YOU && revealedFrontAt(mx, my)) {
                grippingTop = true;
                draggingTop = false;
                pressHand = -1;
                pressedPerm = null;
                pressX = mx;
                pressY = my;
                pressTime = 0f;
                dragDist = 0f;
                return true;
            }
            toggleZone(box.owner, ZoneView.LIBRARY);
            return true;
        }
        return true;
    }

    private boolean handleZoneGrid(float sw, float sh) {
        BattlefieldLayout.ZoneGrid grid = openGrid(sw, sh);
        if (grid == null || !grid.contains(mx, my)) return false;
        int chip = grid.chipAt(mx, my);
        if (chip == 0) zoneType = null;
        else if (chip > 0) {
            String label = BattlefieldLayout.ZONE_TYPE_CHIPS[chip];
            zoneType = label.equals(zoneType) ? null : label;
            zoneScroll = 0f;
        }
        if (chip >= 0) zoneScroll = 0f;
        return true;
    }

    private BattlefieldLayout.ZoneGrid openGrid(float sw, float sh) {
        if (openZone == ZoneView.NONE) return null;
        BattlefieldLayout.PlayerCluster cluster = BattlefieldLayout.clusterAt(
                sw, sh, openOwner, openOwner == BattlefieldLayout.StripOwner.ENEMY ? enemyDx : youDx,
                openOwner == BattlefieldLayout.StripOwner.ENEMY ? enemyDy : youDy);
        BattlefieldLayout.ZoneGrid grid = BattlefieldLayout.zoneCardGrid(
                sw, sh, cluster, visibleZoneCards().size(), zoneScroll);
        zoneScroll = grid.scroll;
        return grid;
    }

    private void fillZone(List<ZoneCard> zone, String... names) {
        zone.clear();
        for (String name : names) addZone(zone, name);
    }

    /** Five copies of the same short list, so a library is a pile you scroll. */
    private static String[] pile(String... names) {
        String[] out = new String[names.length * 5];
        for (int copy = 0; copy < 5; copy++) {
            System.arraycopy(names, 0, out, copy * names.length, names.length);
        }
        return out;
    }

    private void addZone(List<ZoneCard> zone, String name) {
        zone.add(new ZoneCard(name, scriptedType(name), paperFor(name)));
    }

    private void toggleZone(BattlefieldLayout.StripOwner owner, ZoneView zone) {
        if (openZone == zone && openOwner == owner) openZone = ZoneView.NONE;
        else {
            openZone = zone;
            openOwner = owner;
            zoneQuery = "";
            zoneType = null;
            zoneScroll = 0f;
        }
    }

    /** Basic type written on the scripted zone cards, used when a Forge script is missing. */
    private static String scriptedType(String name) {
        if (name == null) return "Creature";
        return switch (name) {
            case "Shock", "Giant Growth", "Dark Ritual" -> "Instant";
            case "Duress" -> "Sorcery";
            case "Forest", "Cavern of Souls", "Plains", "Island", "Mountain", "Swamp" -> "Land";
            default -> "Creature";
        };
    }

    private boolean yourLibraryAtPointer() {
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        BattlefieldLayout.WidgetBox[] boxes = BattlefieldLayout.widgetBoxes(sw, sh, youDx, youDy, enemyDx, enemyDy);
        int hit = BattlefieldLayout.widgetIndexAt(sw, sh, mx, my, youDx, youDy, enemyDx, enemyDy);
        if (hit < 0 || hit >= boxes.length) return false;
        BattlefieldLayout.WidgetBox box = boxes[hit];
        return box.part == BattlefieldLayout.WidgetPart.LIBRARY
                && box.owner == BattlefieldLayout.StripOwner.YOU
                && !revealedFrontAt(mx, my);
    }

    /** The front sleeve of your pile, when the top card is revealed. */
    private boolean revealedFrontAt(float x, float y) {
        if (!yourTopRevealed || yourLibrary.isEmpty()) return false;
        float[] front = yourFrontFace();
        return Math.abs(x - front[0]) <= front[2] / 2f && Math.abs(y - front[1]) <= front[3] / 2f;
    }

    private float[] yourFrontFace() {
        float sw = Gdx.graphics.getWidth(), sh = Gdx.graphics.getHeight();
        BattlefieldLayout.PlayerCluster you = BattlefieldLayout.clusterAt(
                sw, sh, BattlefieldLayout.StripOwner.YOU, youDx, youDy);
        return frontSleeve(you.library);
    }

    /** A revealed land dropped on your half leaves the library and joins the land row. */
    private void releaseTopLand(float h) {
        if (yourLibrary.isEmpty() || !yourTopRevealed) return;
        ZoneCard card = yourLibrary.get(0);
        if (!"Land".equals(card.basicType)) return;
        if (my >= BattlefieldLayout.dividerY(h)) return;
        if (!BattlefieldLayout.mayCast(BattlefieldLayout.Speed.SORCERY, stack.size())) {
            note = card.name + " waits until the stack is empty.";
            return;
        }
        yourLibrary.remove(0);
        if (!yourLibrary.isEmpty()) yourLibrary.get(0).revealed = true;
        float[] front = yourFrontFace();
        Perm p = new Perm();
        p.you = true;
        p.name = card.name;
        p.kind = BattlefieldLayout.Kind.LAND;
        Texture named = textureFor(card.name);
        p.tex = named != null ? named : nextArt();
        p.cx = mx;
        p.cy = my;
        p.w = front[2];
        p.h = front[3];
        p.thunk = 1f;
        board.add(p);
        note = "Played " + p.name + " from the top of your library.";
    }

    private boolean topAsksForLibrary() {
        StackItem top = top();
        return top != null && "Impulse".equals(top.name) && !top.needsTarget;
    }

    /** Impulse resolves into a look. The deck click opens it. The spell does not open the panel itself. */
    private void resolveImpulse() {
        StackItem top = top();
        if (!topAsksForLibrary()) return;
        if (top.card != null) {
            top.card.alive = false;
            top.card.parked = false;
        }
        yourGy++;
        addZone(yourGrave, top.name);
        stack.remove(stack.size() - 1);
        if (examining >= stack.size()) examining = -1;
        pendingTopLook = 4;
        lookTop = false;
        note = "Click your deck to look at the top 4.";
    }

    private int topLookCount() {
        int asked = pendingTopLook > 0 ? pendingTopLook : 0;
        return Math.min(asked, yourLibrary.size());
    }

    /** Card centers for the look row, in the middle of your half. */
    private float[] topLookBox(int index, int n, float w, float h) {
        float cardH = Math.min(0.22f * h, BattlefieldLayout.dividerY(h) * 0.70f);
        float cardW = cardH * HandLayout.CARD_ASPECT;
        float gap = 16f;
        float rowW = n * cardW + Math.max(0, n - 1) * gap;
        float cy = BattlefieldLayout.dividerY(h) * 0.58f;
        float left = w / 2f - rowW / 2f;
        float cx = left + cardW / 2f + index * (cardW + gap);
        return new float[] { cx, cy, cardW, cardH, rowW };
    }

    private int topLookIndex(float x, float y, float w, float h) {
        int n = topLookCount();
        for (int i = 0; i < n; i++) {
            float[] box = topLookBox(i, n, w, h);
            if (Math.abs(x - box[0]) <= box[2] / 2f && Math.abs(y - box[1]) <= box[3] / 2f) return i;
        }
        return -1;
    }

    /** The chosen card joins the hand. The others, in the same order, go to the bottom. */
    private void chooseTopLook(int index) {
        int n = topLookCount();
        if (index < 0 || index >= n) return;
        List<ZoneCard> looked = new ArrayList<>(yourLibrary.subList(0, n));
        ZoneCard chosen = looked.remove(index);
        for (int i = 0; i < n; i++) yourLibrary.remove(0);
        yourLibrary.addAll(looked);
        if (yourTopRevealed && !yourLibrary.isEmpty()) yourLibrary.get(0).revealed = true;
        HandCard c = new HandCard();
        c.name = chosen.name;
        c.kind = handKind(chosen);
        c.instant = "Instant".equals(chosen.basicType);
        c.power = c.kind == BattlefieldLayout.Kind.CREATURE ? ("Llanowar Elves".equals(chosen.name) ? 1 : 2) : 0;
        c.toughness = c.power;
        Texture named = textureFor(chosen.name);
        c.tex = named != null ? named : nextArt();
        hand.add(c);
        lookTop = false;
        pendingTopLook = 0;
        note = chosen.name + " to hand. The rest went to the bottom of the library.";
    }

    private static BattlefieldLayout.Kind handKind(ZoneCard card) {
        if (card == null) return BattlefieldLayout.Kind.CREATURE;
        return switch (card.basicType) {
            case "Land" -> BattlefieldLayout.Kind.LAND;
            case "Instant", "Sorcery" -> BattlefieldLayout.Kind.SPELL;
            default -> BattlefieldLayout.Kind.CREATURE;
        };
    }

    private void drawTopLook(float w, float h) {
        int n = topLookCount();
        if (!lookTop || n == 0) return;
        float[] first = topLookBox(0, n, w, h);
        float rowW = first[4];
        float cy = first[1];
        float cardH = first[3];
        batch.setColor(0.06f, 0.07f, 0.08f, 0.94f);
        drawTex(pixel(), w / 2f, cy + 10f, rowW + 36f, cardH + 64f, 0f);
        batch.setColor(Color.WHITE);
        drawCentered(stackFont, "Look at the top " + n + ". Choose one.", w / 2f, cy + cardH / 2f + 28f, GOLD);
        for (int i = 0; i < n; i++) {
            float[] box = topLookBox(i, n, w, h);
            ZoneCard card = yourLibrary.get(i);
            Texture tex = textureFor(card.name);
            if (tex != null) {
                batch.setColor(Color.WHITE);
                drawTex(tex, box[0], box[1], box[2], box[3], 0f);
            } else {
                batch.setColor(0.20f, 0.22f, 0.26f, 1f);
                drawTex(pixel(), box[0], box[1], box[2], box[3], 0f);
            }
            batch.setColor(Color.WHITE);
            drawTag(card.name, box[0], box[1] + box[3] * 0.28f, Color.WHITE);
        }
    }

    /** Right-click your deck. A window opens so you can pick a png or jpg. */
    private void chooseSleeveImage() {
        if (choosingSleeve) return;
        choosingSleeve = true;
        note = "Pick a png or jpg for the sleeve.";
        Thread picker = new Thread(() -> {
            FileDialog dialog = new FileDialog((Frame) null, "Choose a sleeve image", FileDialog.LOAD);
            dialog.setVisible(true);
            String name = dialog.getFile();
            String dir = dialog.getDirectory();
            dialog.dispose();
            Gdx.app.postRunnable(() -> {
                choosingSleeve = false;
                if (name == null || dir == null) {
                    note = "Sleeve unchanged.";
                    return;
                }
                applyCustomSleeve(Path.of(dir, name));
            });
        }, "sleeve-picker");
        picker.setDaemon(true);
        picker.start();
    }

    private static final Map<String, int[]> ABILITY_ICON = Map.ofEntries(
            Map.entry("deathtouch", new int[] {2, 2, 80, 80}),
            Map.entry("defender", new int[] {84, 2, 80, 80}),
            Map.entry("double strike", new int[] {166, 2, 80, 80}),
            Map.entry("first strike", new int[] {248, 2, 80, 80}),
            Map.entry("flying", new int[] {330, 2, 80, 80}),
            Map.entry("hexproof", new int[] {412, 2, 80, 80}),
            Map.entry("shroud", new int[] {330, 330, 80, 80}),
            Map.entry("indestructible", new int[] {2, 84, 80, 80}),
            Map.entry("lifelink", new int[] {84, 84, 80, 80}),
            Map.entry("menace", new int[] {166, 84, 80, 80}),
            Map.entry("trample", new int[] {412, 330, 80, 80}),
            Map.entry("haste", new int[] {412, 494, 80, 80}),
            Map.entry("vigilance", new int[] {2, 412, 80, 80}),
            Map.entry("reach", new int[] {248, 330, 80, 80}),
            Map.entry("flash", new int[] {166, 576, 80, 80})
    );

    /** Little blue swirl Forge paints on a creature that cannot act yet. */
    private void loadSummonSick() {
        Path file = Path.of("forge-gui", "res", "skins", "default", "sprite_icons.png");
        if (!Files.isRegularFile(file)) return;
        try {
            Pixmap pm = new Pixmap(Gdx.files.absolute(file.toAbsolutePath().toString()));
            iconSheet = new Texture(pm, true);
            iconSheet.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            pm.dispose();
            summonSick = new TextureRegion(iconSheet, 240, 400, 80, 80);
        } catch (RuntimeException failed) {
            System.err.println("Summoning sickness swirl skipped: " + failed);
            iconSheet = null;
            summonSick = null;
        }
    }

    private void drawSummonSick(float cx, float cy, float cw, float ch, float alpha) {
        if (summonSick == null) return;
        float size = Math.max(18f, Math.min(cw, ch) * 0.34f);
        batch.setColor(1f, 1f, 1f, alpha);
        batch.draw(summonSick, cx - size / 2f, cy + ch * 0.05f - size / 2f, size, size);
    }

    /**
     * Gold heater shield with a cream star. Drawn in code so it is not WotC crown art.
     * Same texture for the command-zone badge and the card status icon.
     */
    private Texture bakeCommanderMark() {
        int s = 64;
        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        try {
            pm.setBlending(Pixmap.Blending.SourceOver);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();
            int cx = 32;
            int cy = 32;
            pm.setColor(0.08f, 0.05f, 0.02f, 1f);
            pm.fillCircle(cx, cy, 31);
            pm.setColor(0.42f, 0.26f, 0.05f, 1f);
            fillHeater(pm, cx, cy + 1, 50, 56);
            pm.setColor(1f, 0.78f, 0.18f, 1f);
            fillHeater(pm, cx, cy, 44, 50);
            pm.setColor(1f, 0.90f, 0.38f, 1f);
            fillHeater(pm, cx, cy - 1, 36, 42);
            pm.setColor(0.22f, 0.12f, 0.03f, 1f);
            fillStar(pm, cx, cy - 1, 13, 5);
            pm.setColor(1f, 0.98f, 0.82f, 1f);
            fillStar(pm, cx, cy - 1, 11, 4);
            Texture tex = new Texture(pm, true);
            tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            return tex;
        } catch (RuntimeException failed) {
            System.err.println("Commander mark skipped: " + failed);
            return null;
        } finally {
            pm.dispose();
        }
    }

    /** Open silver-cyan hex ward. No star, no gold heater, so it cannot read as commander. */
    private Texture bakeHexproofMark() {
        int s = 96;
        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        try {
            pm.setBlending(Pixmap.Blending.SourceOver);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();
            int cx = 48;
            int cy = 48;
            pm.setColor(0.02f, 0.08f, 0.14f, 1f);
            pm.fillCircle(cx, cy, 47);
            pm.setColor(0.18f, 0.62f, 0.96f, 1f);
            pm.fillCircle(cx, cy, 42);
            pm.setColor(0.78f, 0.95f, 1f, 1f);
            pm.fillCircle(cx, cy, 34);
            pm.setColor(0.10f, 0.28f, 0.42f, 1f);
            fillHex(pm, cx, cy, 26);
            pm.setColor(0.42f, 0.88f, 1f, 1f);
            fillHex(pm, cx, cy, 20);
            pm.setColor(0.88f, 0.98f, 1f, 1f);
            fillHex(pm, cx, cy, 12);
            pm.setColor(0.06f, 0.16f, 0.26f, 1f);
            pm.fillCircle(cx, cy, 6);
            Texture tex = new Texture(pm, true);
            tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            return tex;
        } catch (RuntimeException failed) {
            System.err.println("Hexproof mark skipped: " + failed);
            return null;
        } finally {
            pm.dispose();
        }
    }

    /** Closed violet-slate diamond ward. Distinct from hexproof's open hex. */
    private Texture bakeShroudMark() {
        int s = 96;
        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        try {
            pm.setBlending(Pixmap.Blending.SourceOver);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();
            int cx = 48;
            int cy = 48;
            pm.setColor(0.06f, 0.04f, 0.12f, 1f);
            pm.fillCircle(cx, cy, 47);
            pm.setColor(0.38f, 0.22f, 0.62f, 1f);
            pm.fillCircle(cx, cy, 42);
            pm.setColor(0.62f, 0.48f, 0.86f, 1f);
            fillOctagon(pm, cx, cy, 34);
            pm.setColor(0.16f, 0.10f, 0.28f, 1f);
            fillDiamond(pm, cx, cy, 26);
            pm.setColor(0.78f, 0.66f, 1f, 1f);
            fillDiamond(pm, cx, cy, 18);
            pm.setColor(0.10f, 0.07f, 0.18f, 1f);
            fillDiamond(pm, cx, cy, 8);
            Texture tex = new Texture(pm, true);
            tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            return tex;
        } catch (RuntimeException failed) {
            System.err.println("Shroud mark skipped: " + failed);
            return null;
        } finally {
            pm.dispose();
        }
    }

    /** Three orange chevrons. Reads as haste, not as the REACH bar. */
    private Texture bakeHasteMark() {
        int s = 64;
        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        try {
            pm.setBlending(Pixmap.Blending.SourceOver);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();
            pm.setColor(0.18f, 0.06f, 0.02f, 1f);
            fillChevron(pm, 32, 18, 28, 14);
            fillChevron(pm, 32, 32, 28, 14);
            fillChevron(pm, 32, 46, 28, 14);
            pm.setColor(1f, 0.55f, 0.12f, 1f);
            fillChevron(pm, 32, 16, 24, 12);
            fillChevron(pm, 32, 30, 24, 12);
            fillChevron(pm, 32, 44, 24, 12);
            pm.setColor(1f, 0.92f, 0.45f, 1f);
            fillChevron(pm, 32, 15, 16, 8);
            fillChevron(pm, 32, 29, 16, 8);
            fillChevron(pm, 32, 43, 16, 8);
            Texture tex = new Texture(pm, true);
            tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            return tex;
        } catch (RuntimeException failed) {
            System.err.println("Haste mark skipped: " + failed);
            return null;
        } finally {
            pm.dispose();
        }
    }

    private static void fillHex(Pixmap pm, int cx, int cy, int radius) {
        fillRegular(pm, cx, cy, radius, 6, -90);
    }

    private static void fillOctagon(Pixmap pm, int cx, int cy, int radius) {
        fillRegular(pm, cx, cy, radius, 8, -22.5);
    }

    private static void fillDiamond(Pixmap pm, int cx, int cy, int radius) {
        fillRegular(pm, cx, cy, radius, 4, -90);
    }

    private static void fillRegular(Pixmap pm, int cx, int cy, int radius, int sides, double startDeg) {
        int[] xs = new int[sides];
        int[] ys = new int[sides];
        for (int i = 0; i < sides; i++) {
            double a = Math.toRadians(startDeg + i * (360.0 / sides));
            xs[i] = cx + (int) Math.round(Math.cos(a) * radius);
            ys[i] = cy + (int) Math.round(Math.sin(a) * radius);
        }
        for (int i = 1; i < sides - 1; i++) {
            pm.fillTriangle(xs[0], ys[0], xs[i], ys[i], xs[i + 1], ys[i + 1]);
        }
    }

    private static void fillChevron(Pixmap pm, int cx, int cy, int w, int h) {
        int left = cx - w / 2;
        int right = cx + w / 2;
        int mid = cy;
        int top = cy - h / 2;
        pm.fillTriangle(left, mid, cx, top, cx - w / 6, mid);
        pm.fillTriangle(right, mid, cx, top, cx + w / 6, mid);
        pm.fillTriangle(left, mid, right, mid, cx, cy + h / 2);
    }

    private static void fillHeater(Pixmap pm, int cx, int cy, int w, int h) {
        int left = cx - w / 2;
        int right = cx + w / 2;
        int top = cy - h / 2;
        int mid = top + Math.max(8, (int) (h * 0.40f));
        int bot = cy + h / 2;
        pm.fillTriangle(left, top, right, top, right, mid);
        pm.fillTriangle(left, top, right, mid, left, mid);
        pm.fillTriangle(left, mid, right, mid, cx, bot);
    }

    private static void fillStar(Pixmap pm, int cx, int cy, int outer, int inner) {
        int[] ox = new int[5];
        int[] oy = new int[5];
        int[] ix = new int[5];
        int[] iy = new int[5];
        for (int i = 0; i < 5; i++) {
            double tip = Math.toRadians(-90 + i * 72);
            double dip = Math.toRadians(-90 + i * 72 + 36);
            ox[i] = cx + (int) Math.round(Math.cos(tip) * outer);
            oy[i] = cy + (int) Math.round(Math.sin(tip) * outer);
            ix[i] = cx + (int) Math.round(Math.cos(dip) * inner);
            iy[i] = cy + (int) Math.round(Math.sin(dip) * inner);
        }
        for (int i = 0; i < 5; i++) {
            int n = (i + 1) % 5;
            pm.fillTriangle(cx, cy, ox[i], oy[i], ix[i], iy[i]);
            pm.fillTriangle(cx, cy, ix[i], iy[i], ox[n], oy[n]);
        }
    }

    private void loadAbilitySheet() {
        Path file = Path.of("forge-gui", "res", "skins", "default", "sprite_ability.png");
        if (!Files.isRegularFile(file)) return;
        try {
            Pixmap pm = new Pixmap(Gdx.files.absolute(file.toAbsolutePath().toString()));
            abilitySheet = new Texture(pm, true);
            abilitySheet.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            pm.dispose();
            abilityIcons.clear();
            for (Map.Entry<String, int[]> entry : ABILITY_ICON.entrySet()) {
                int[] r = entry.getValue();
                abilityIcons.put(entry.getKey(), new TextureRegion(abilitySheet, r[0], r[1], r[2], r[3]));
            }
        } catch (RuntimeException failed) {
            System.err.println("Ability icons skipped: " + failed);
            abilitySheet = null;
        }
    }

    private Texture loadZoneIcon(String path) {
        Path file = Path.of(path);
        if (!Files.isRegularFile(file)) return null;
        try {
            Pixmap pm = new Pixmap(Gdx.files.absolute(file.toAbsolutePath().toString()));
            Texture tex = new Texture(pm, true);
            tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            pm.dispose();
            System.err.println("Zone icon: " + file.toAbsolutePath());
            return tex;
        } catch (RuntimeException failed) {
            System.err.println("Zone icon skipped: " + failed);
            return null;
        }
    }

    /** Square picture in the space above the count strip. */
    private void drawZoneIcon(Texture tex, BattlefieldLayout.WidgetBox box) {
        if (tex == null) return;
        float bottom = box.y - box.h / 2f + 18f;
        float top = box.y + box.h / 2f - 3f;
        float size = Math.min(box.w - 8f, Math.max(8f, top - bottom));
        batch.setColor(Color.WHITE);
        drawTex(tex, box.x, (bottom + top) / 2f, size, size, 0f);
    }

    private Texture cardBack() {
        if (cardBack == null) cardBack = loadCardBack();
        return cardBack;
    }

    /** Local file if present; otherwise a generated Magic-style back. */
    private Texture loadCardBack() {
        String[] paths = {
                "forge-gui-mobile-dev/res/battlefield/cardback.png",
                "forge-gui/res/skins/default/cardback.png",
                "res/skins/default/cardback.png"
        };
        for (String path : paths) {
            Texture loaded = loadZoneIcon(path);
            if (loaded != null) return loaded;
        }
        return bakeCardBack();
    }

    private Texture bakeCardBack() {
        int w = 223;
        int h = 310;
        Pixmap pm = new Pixmap(w, h, Pixmap.Format.RGBA8888);
        try {
            pm.setBlending(Pixmap.Blending.SourceOver);
            pm.setColor(0.08f, 0.07f, 0.12f, 1f);
            pm.fill();
            pm.setColor(0.78f, 0.62f, 0.22f, 1f);
            pm.fillRectangle(0, 0, w, 10);
            pm.fillRectangle(0, h - 10, w, 10);
            pm.fillRectangle(0, 0, 10, h);
            pm.fillRectangle(w - 10, 0, 10, h);
            pm.setColor(0.16f, 0.10f, 0.28f, 1f);
            pm.fillRectangle(16, 16, w - 32, h - 32);
            pm.setColor(0.10f, 0.18f, 0.42f, 1f);
            pm.fillRectangle(26, 26, w - 52, h - 52);
            pm.setColor(0.86f, 0.70f, 0.28f, 1f);
            int cx = w / 2;
            int cy = h / 2;
            pm.fillTriangle(cx, cy - 54, cx - 40, cy, cx + 40, cy);
            pm.fillTriangle(cx, cy + 54, cx - 40, cy, cx + 40, cy);
            pm.setColor(0.95f, 0.88f, 0.55f, 1f);
            pm.fillCircle(cx, cy, 18);
            pm.setColor(0.12f, 0.10f, 0.22f, 1f);
            pm.fillCircle(cx, cy, 10);
            Texture tex = new Texture(pm, true);
            tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            return tex;
        } catch (RuntimeException failed) {
            System.err.println("Card back skipped: " + failed);
            return null;
        } finally {
            pm.dispose();
        }
    }

    private List<ZoneCard> hiddenExile(boolean you) {
        List<ZoneCard> out = new ArrayList<>();
        for (ZoneCard card : you ? yourExile : theirExile) {
            if (card != null && card.hiddenBack) out.add(card);
        }
        return out;
    }

    private HandLayout.Pose exileTilePose(BattlefieldLayout.WidgetBox box, int i, int n, boolean you) {
        float ch = Math.max(box.h + 8f, 56f);
        float cw = ch * HandLayout.CARD_ASPECT;
        float step = 6f;
        float cx = box.x + (i - Math.max(0, n - 1) * 0.5f) * step;
        float cy = box.y + (you ? 5f : -5f);
        return new HandLayout.Pose(cx, cy, cw, ch, 0f, 1f);
    }

    private void drawExileTiles(BattlefieldLayout.PlayerCluster cluster, boolean you) {
        List<ZoneCard> tiles = hiddenExile(you);
        if (tiles.isEmpty() || cluster == null) return;
        Texture back = cardBack();
        for (int i = 0; i < tiles.size(); i++) {
            ZoneCard tile = tiles.get(i);
            HandLayout.Pose pose = exileTilePose(cluster.exile, i, tiles.size(), you);
            if (you && tile.playable) drawPlayableEdge(pose.cx, pose.cy, pose.w, pose.h, 0f);
            batch.setColor(Color.WHITE);
            if (back != null) drawTex(back, pose.cx, pose.cy, pose.w, pose.h, 0f);
            else {
                batch.setColor(0.78f, 0.62f, 0.22f, 1f);
                drawTex(pixel(), pose.cx, pose.cy, pose.w + 6f, pose.h + 6f, 0f);
                batch.setColor(0.10f, 0.12f, 0.28f, 1f);
                drawTex(pixel(), pose.cx, pose.cy, pose.w, pose.h, 0f);
                batch.setColor(Color.WHITE);
            }
        }
    }

    private boolean takeExileHover() {
        int[] hit = exileTileHit(mx, my);
        if (hit == null) {
            hoverExile = -1;
            return false;
        }
        hoverExileYou = hit[0] == 1;
        hoverExile = hit[1];
        hover = -1;
        hoverPerm = null;
        hoverZone = -1;
        hoverCommand = -1;
        ghostHover = -1;
        return true;
    }

    private boolean pressExileTile() {
        int[] hit = exileTileHit(mx, my);
        if (hit == null) return false;
        pressExileYou = hit[0] == 1;
        pressExile = hit[1];
        pressHand = -1;
        pressGhost = -1;
        pressedPerm = null;
        pressX = mx;
        pressY = my;
        pressTime = 0f;
        dragDist = 0f;
        hoverExileYou = pressExileYou;
        hoverExile = pressExile;
        return true;
    }

    private void releaseExileTile() {
        boolean tap = HandLayout.isTap(dragDist, pressTime);
        int[] hit = exileTileHit(mx, my);
        if (tap && hit != null && hit[0] == (pressExileYou ? 1 : 0) && hit[1] == pressExile) {
            ZoneCard tile = exileTileAt(pressExileYou, pressExile);
            if (liveReady() && pressExileYou && tile != null && tile.forgeCard != null) {
                live.select(tile.forgeCard);
            }
        }
        pressExile = -1;
    }

    /** [0] = 1 for you, 0 for opponent. [1] = tile index. */
    private int[] exileTileHit(float x, float y) {
        int youHit = exileIndexAt(true, x, y);
        if (youHit >= 0) return new int[] {1, youHit};
        int themHit = exileIndexAt(false, x, y);
        if (themHit >= 0) return new int[] {0, themHit};
        return null;
    }

    private int exileIndexAt(boolean you, float x, float y) {
        List<ZoneCard> tiles = hiddenExile(you);
        if (tiles.isEmpty()) return -1;
        float w = Gdx.graphics.getWidth();
        float h = Gdx.graphics.getHeight();
        BattlefieldLayout.StripOwner owner = you
                ? BattlefieldLayout.StripOwner.YOU : BattlefieldLayout.StripOwner.ENEMY;
        float dx = you ? youDx : enemyDx;
        float dy = you ? youDy : enemyDy;
        BattlefieldLayout.PlayerCluster cluster = BattlefieldLayout.clusterAt(w, h, owner, dx, dy);
        for (int i = tiles.size() - 1; i >= 0; i--) {
            HandLayout.Pose pose = exileTilePose(cluster.exile, i, tiles.size(), you);
            if (Math.abs(x - pose.cx) <= pose.w / 2f && Math.abs(y - pose.cy) <= pose.h / 2f) return i;
        }
        return -1;
    }

    private ZoneCard exileTileAt(boolean you, int index) {
        List<ZoneCard> tiles = hiddenExile(you);
        if (index < 0 || index >= tiles.size()) return null;
        return tiles.get(index);
    }

    private void loadCustomSleeve() {
        Path chosen = readSleeveChoice();
        if (chosen != null && Files.isRegularFile(chosen)) {
            applyCustomSleeve(chosen);
            return;
        }
        Path standIn = Path.of(BattlefieldLayout.CUSTOM_SLEEVE_PATH).toAbsolutePath();
        if (Files.isRegularFile(standIn)) applyCustomSleeve(standIn);
    }

    private Path readSleeveChoice() {
        Path choice = Path.of(BattlefieldLayout.CUSTOM_SLEEVE_CHOICE);
        if (!Files.isRegularFile(choice)) return null;
        try {
            String line = Files.readString(choice).trim();
            if (line.isEmpty()) return null;
            return Path.of(line);
        } catch (IOException failed) {
            return null;
        }
    }

    private void applyCustomSleeve(Path path) {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            note = "Sleeve images are png or jpg.";
            return;
        }
        try {
            Pixmap pm = new Pixmap(Gdx.files.absolute(path.toAbsolutePath().toString()));
            Texture tex = new Texture(pm, true);
            tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
            pm.dispose();
            if (customSleeve != null) customSleeve.dispose();
            customSleeve = tex;
            sleeveCount = 5;
            yourSleeve = SLEEVE_CUSTOM;
            note = "Sleeve: " + path.getFileName();
            Path choice = Path.of(BattlefieldLayout.CUSTOM_SLEEVE_CHOICE);
            Files.createDirectories(choice.getParent());
            Files.writeString(choice, path.toAbsolutePath().toString());
        } catch (RuntimeException | IOException failed) {
            System.err.println("Custom sleeve skipped: " + failed);
            note = "Could not use that image.";
        }
    }

    private void fillHalf(float y, float height, BattlefieldLayout.Theme theme) {
        int strips = 28;
        float band = height / strips;
        int a = theme == null ? 0x204020 : theme.colorA;
        int b = theme == null ? 0x204020 : theme.colorB;
        for (int i = 0; i < strips; i++) {
            float t = strips == 1 ? 0f : i / (float) (strips - 1);
            float u = 1f - t;
            float r = ((a >> 16) & 255) / 255f * u + ((b >> 16) & 255) / 255f * t;
            float g = ((a >> 8) & 255) / 255f * u + ((b >> 8) & 255) / 255f * t;
            float bl = (a & 255) / 255f * u + (b & 255) / 255f * t;
            shapes.setColor(r, g, bl, 1f);
            shapes.rect(0f, y + i * band, Gdx.graphics.getWidth(), band + 0.6f);
        }
    }

    private void drawClusterShapes(BattlefieldLayout.PlayerCluster cluster, boolean lifeHot) {
        BattlefieldLayout.WidgetBox life = cluster.life;
        float ox = cluster.owner == BattlefieldLayout.StripOwner.YOU ? youImpactX : themImpactX;
        float oy = cluster.owner == BattlefieldLayout.StripOwner.YOU ? youImpactY : themImpactY;
        shapes.setColor(0.08f, 0.08f, 0.09f, 0.92f);
        shapes.rect(life.x + ox - life.w / 2f, life.y + oy - life.h / 2f, life.w, life.h);
        float[] avatar = avatarBox(life);
        avatar[0] += ox;
        avatar[1] += oy;
        if (cluster.owner == BattlefieldLayout.StripOwner.ENEMY) {
            BattlefieldLayout.Theme theme = BattlefieldLayout.scriptedEnemyTheme();
            shapes.setColor(((theme.colorB >> 16) & 255) / 255f, ((theme.colorB >> 8) & 255) / 255f,
                    (theme.colorB & 255) / 255f, 1f);
        } else {
            shapes.setColor(0.24f, 0.48f, 0.22f, 1f);
        }
        shapes.rect(avatar[0], avatar[1], avatar[2], avatar[2]);
        if (lifeHot) {
            boolean you = cluster.owner != BattlefieldLayout.StripOwner.ENEMY;
            boolean assigned = liveReady() && live.playerAssigned(you);
            drawPlayerTargetRim(life.x + ox - life.w / 2f, life.y + oy - life.h / 2f, life.w, life.h,
                    avatar[0], avatar[1], avatar[2], assigned);
        }
        drawGravestone(cluster.grave);
        drawVoid(cluster.exile);
        markZone(cluster.grave, ZoneView.GRAVE);
        markZone(cluster.exile, ZoneView.EXILE);
        markZone(cluster.library, ZoneView.LIBRARY);
        int sleeve = cluster.owner == BattlefieldLayout.StripOwner.YOU ? yourSleeve : SLEEVE_PLAIN;
        if (sleeve != SLEEVE_CUSTOM || customSleeve == null) drawSleeveStack(cluster.library, sleeve);
        else drawSleeveStack(cluster.library, SLEEVE_PLAIN);
        drawCountBar(cluster.grave);
        drawCountBar(cluster.exile);
        drawLibraryCountPlate(cluster.library);
    }

    private void drawGravestone(BattlefieldLayout.WidgetBox box) {
        shapes.setColor(0.14f, 0.15f, 0.16f, 0.94f);
        shapes.rect(box.x - box.w / 2f, box.y - box.h / 2f, box.w, box.h);
        if (graveIcon != null) return;
        float stoneW = Math.min(box.w * 0.46f, 22f);
        float stoneH = Math.min(box.h * 0.48f, 24f);
        float iconCy = box.y + 7f;
        float left = box.x - stoneW / 2f;
        float bottom = iconCy - stoneH / 2f;
        shapes.setColor(0.62f, 0.64f, 0.66f, 1f);
        shapes.rect(left, bottom, stoneW, stoneH * 0.62f);
        shapes.triangle(left, bottom + stoneH * 0.62f, box.x, bottom + stoneH, left + stoneW, bottom + stoneH * 0.62f);
    }

    private void drawVoid(BattlefieldLayout.WidgetBox box) {
        shapes.setColor(0.14f, 0.15f, 0.16f, 0.94f);
        shapes.rect(box.x - box.w / 2f, box.y - box.h / 2f, box.w, box.h);
        if (exileIcon != null) return;
        float vw = box.w * 0.5f;
        float vh = Math.min(box.h * 0.42f, 20f);
        float iconCy = box.y + 7f;
        float left = box.x - vw / 2f;
        float bottom = iconCy - vh / 2f;
        shapes.setColor(0.03f, 0.02f, 0.08f, 1f);
        shapes.rect(left, bottom, vw, vh);
        shapes.setColor(0.92f, 0.94f, 1f, 1f);
        float[] sx = {0.18f, 0.42f, 0.70f, 0.30f, 0.58f, 0.82f};
        float[] sy = {0.72f, 0.40f, 0.66f, 0.28f, 0.82f, 0.34f};
        for (int i = 0; i < sx.length; i++) {
            float size = (i % 2 == 0) ? 2.5f : 1.6f;
            shapes.rect(left + vw * sx[i], bottom + vh * sy[i], size, size);
        }
    }

    /** Dark strip along the bottom of a pile button. The number is drawn later, in the sharp face. */
    private void drawCountBar(BattlefieldLayout.WidgetBox box) {
        float h = 16f;
        float bottom = box.y - box.h / 2f;
        shapes.setColor(0.04f, 0.04f, 0.05f, 0.94f);
        shapes.rect(box.x - box.w / 2f, bottom, box.w, h);
    }

    private void drawZoneCount(BattlefieldLayout.PlayerCluster cluster) {
        paintZoneCount(cluster.grave, zoneCards(cluster.owner, ZoneView.GRAVE).size());
        paintZoneCount(cluster.exile, zoneCards(cluster.owner, ZoneView.EXILE).size());
        float[] plate = libraryCountPlate(cluster.library);
        float cx = plate[0] + plate[2] / 2f;
        float cy = plate[1] + plate[3] / 2f;
        String count = Integer.toString(zoneCards(cluster.owner, ZoneView.LIBRARY).size());
        drawCenteredOn(stripFont, count, cx + 1f, cy - 1f, Color.BLACK);
        drawCenteredOn(stripFont, count, cx, cy, Color.WHITE);
    }

    /** Cards-in-hand number on each library. Opponent faces stay hidden. */
    private void drawHandCounts(BattlefieldLayout.PlayerCluster you, BattlefieldLayout.PlayerCluster enemy) {
        int youN = liveMode ? yourHandCount : live().size();
        int themN = theirHandCount;
        if (you != null) drawHandCountOnLibrary(you, youN);
        if (enemy != null) drawHandCountOnLibrary(enemy, themN);
    }

    private void drawHandCountOnLibrary(BattlefieldLayout.PlayerCluster cluster, int count) {
        float ox = cluster.owner == BattlefieldLayout.StripOwner.YOU ? youImpactX : themImpactX;
        float oy = cluster.owner == BattlefieldLayout.StripOwner.YOU ? youImpactY : themImpactY;
        float[] plate = handCountPlate(cluster.library);
        plate[0] += ox;
        plate[1] += oy;
        batch.setColor(0.04f, 0.04f, 0.05f, 0.92f);
        batch.draw(pixel(), plate[0], plate[1], plate[2], plate[3]);
        batch.setColor(Color.WHITE);
        float cx = plate[0] + plate[2] / 2f;
        float cy = plate[1] + plate[3] / 2f;
        String label = "H" + count;
        drawCenteredOn(stripFont, label, cx + 1f, cy - 1f, Color.BLACK);
        drawCenteredOn(stripFont, label, cx, cy, Color.WHITE);
    }

    /** Top of the library sleeve. The deck count stays on the bottom plate. */
    private float[] handCountPlate(BattlefieldLayout.WidgetBox box) {
        float[] front = frontSleeve(box);
        float w = 36f;
        float h = 16f;
        float left = front[0] - w / 2f;
        float bottom = front[1] + front[3] / 2f - 8f - h;
        return new float[] {left, bottom, w, h};
    }

    private void paintZoneCount(BattlefieldLayout.WidgetBox box, int count) {
        float barH = 16f;
        float cy = box.y - box.h / 2f + barH / 2f;
        drawCenteredOn(stripFont, Integer.toString(count), box.x, cy, Color.WHITE);
    }

    /** Small plate on the front sleeve. The sleeve itself stays visible around it. */
    private void drawLibraryCountPlate(BattlefieldLayout.WidgetBox box) {
        float[] plate = libraryCountPlate(box);
        shapes.setColor(0.04f, 0.04f, 0.05f, 0.88f);
        shapes.rect(plate[0], plate[1], plate[2], plate[3]);
    }

    private float[] libraryCountPlate(BattlefieldLayout.WidgetBox box) {
        float[] front = frontSleeve(box);
        float w = 34f;
        float h = 16f;
        float left = front[0] - w / 2f;
        float bottom = front[1] - front[3] / 2f + 8f;
        return new float[] {left, bottom, w, h};
    }

    private void markZone(BattlefieldLayout.WidgetBox box, ZoneView zone) {
        if (openZone != zone || openOwner != box.owner) return;
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 1f);
        outline(box.x - box.w / 2f, box.y - box.h / 2f, box.w, box.h, 2f);
    }

    /** Six card backs. The front one is the sleeve. Edges below and to the left show the pile. */
    private void drawSleeveStack(BattlefieldLayout.WidgetBox box, int sleeve) {
        int layers = 6;
        float step = 2.4f;
        float[] front = frontSleeve(box);
        for (int i = layers - 1; i >= 0; i--) {
            float cx = front[0] - i * step;
            float cy = front[1] - i * step;
            paintSleeve(cx, cy, front[2], front[3], sleeve, i == 0);
        }
    }

    /** Front card of {@link #drawSleeveStack}. x, y, w, h with x and y at the center. */
    private static float[] frontSleeve(BattlefieldLayout.WidgetBox box) {
        int layers = 6;
        float step = 2.4f;
        float inset = (layers - 1) * step;
        float cw = box.w - inset;
        float ch = box.h - inset;
        return new float[] {box.x + inset / 2f, box.y + inset / 2f, cw, ch};
    }

    private void paintSleeve(float cx, float cy, float cw, float ch, int sleeve, boolean front) {
        float left = cx - cw / 2f;
        float bottom = cy - ch / 2f;
        switch (sleeve) {
            case SLEEVE_RED -> shapes.setColor(0.62f, 0.12f, 0.12f, 1f);
            case SLEEVE_BLUE -> shapes.setColor(0.12f, 0.28f, 0.72f, 1f);
            case SLEEVE_GEOMETRIC -> shapes.setColor(0.10f, 0.12f, 0.16f, 1f);
            default -> shapes.setColor(0.12f, 0.13f, 0.15f, 1f);
        }
        if (!front) {
            Color ink = shapes.getColor();
            shapes.setColor(ink.r * 0.62f, ink.g * 0.62f, ink.b * 0.62f, 1f);
        }
        shapes.rect(left, bottom, cw, ch);
        shapes.setColor(front ? 0.92f : 0.55f, front ? 0.86f : 0.5f, front ? 0.55f : 0.35f, 1f);
        shapes.rect(left, bottom, cw, 2f);
        shapes.rect(left, bottom + ch - 2f, cw, 2f);
        shapes.rect(left, bottom, 2f, ch);
        shapes.rect(left + cw - 2f, bottom, 2f, ch);
        if (!front) return;
        float margin = Math.min(cw, ch) * 0.12f;
        shapes.setColor(1f, 1f, 1f, 0.28f);
        shapes.rect(left + margin, bottom + margin, cw - margin * 2f, 1.5f);
        shapes.rect(left + margin, bottom + ch - margin - 1.5f, cw - margin * 2f, 1.5f);
        shapes.rect(left + margin, bottom + margin, 1.5f, ch - margin * 2f);
        shapes.rect(left + cw - margin - 1.5f, bottom + margin, 1.5f, ch - margin * 2f);
        if (sleeve == SLEEVE_GEOMETRIC) {
            shapes.setColor(0.86f, 0.72f, 0.28f, 1f);
            shapes.triangle(cx, cy + ch * 0.16f, cx - cw * 0.22f, cy - ch * 0.1f, cx + cw * 0.22f, cy - ch * 0.1f);
            shapes.setColor(0.75f, 0.82f, 0.9f, 1f);
            shapes.circle(cx, cy - ch * 0.02f, Math.min(cw, ch) * 0.16f, 16);
        } else if (sleeve == SLEEVE_PLAIN) {
            shapes.setColor(0.22f, 0.24f, 0.28f, 1f);
            shapes.rect(left + margin + 4f, bottom + margin + 4f, cw - (margin + 4f) * 2f, ch - (margin + 4f) * 2f);
        }
    }

    /**
     * Unspent mana beside this life total. Colored pips are one each.
     * Colorless mana is one numbered generic pip. An empty pool draws nothing.
     */
    private void queueFloatingMana(BattlefieldLayout.PlayerCluster cluster, int[] pool, float radius) {
        List<ManaPip> pips = poolPips(pool);
        if (pips.isEmpty() || cluster == null) return;
        float gap = Math.max(3f, radius * 0.35f);
        float step = radius * 2f + gap;
        float right = cluster.life.x - cluster.life.w / 2f - 8f - radius;
        float leftLimit = 12f + radius;
        int perRow = Math.max(1, (int) ((right - leftLimit) / step) + 1);
        boolean you = cluster.owner == BattlefieldLayout.StripOwner.YOU;
        float rowStep = radius * 2f + 4f;
        for (int i = 0; i < pips.size(); i++) {
            int col = i % perRow;
            int row = i / perRow;
            float x = right - col * step;
            float y = cluster.life.y + (you ? -1f : 1f) * row * rowStep;
            pipQueue.add(new ManaPipDraw(x, y, radius, pips.get(i)));
        }
    }

    /** White through green as single pips. Colorless, if any, is one numbered generic pip. */
    private static List<ManaPip> poolPips(int[] pool) {
        List<ManaPip> pips = new ArrayList<>();
        if (pool == null) return pips;
        String[] colors = {"W", "U", "B", "R", "G"};
        for (int i = 0; i < colors.length && i < pool.length; i++) {
            int count = Math.max(0, pool[i]);
            for (int n = 0; n < count; n++) addManaPip(pips, colors[i]);
        }
        if (pool.length > 5 && pool[5] > 0) addManaPip(pips, Integer.toString(pool[5]));
        return pips;
    }

    private static int[] copyPool(int[] pool) {
        int[] copy = new int[6];
        if (pool == null) return copy;
        System.arraycopy(pool, 0, copy, 0, Math.min(pool.length, copy.length));
        return copy;
    }

    /**
     * One card slot per commander, with the deck / grave / exile family on the
     * left chrome. Not on the land strip and not on the planeswalker row.
     * When the commander is on the battlefield the seat is empty and the shield stays.
     */
    private void layoutCommandSlots(float w, float h,
            BattlefieldLayout.PlayerCluster you, BattlefieldLayout.PlayerCluster them) {
        commandSlots.clear();
        placeCommandSide(w, h, you, yourCommand);
        placeCommandSide(w, h, them, theirCommand);
    }

    private void placeCommandSide(float windowW, float windowH, BattlefieldLayout.PlayerCluster cluster,
            List<BattlefieldLive.CommanderSeat> seats) {
        if (cluster == null || seats == null || seats.isEmpty()) return;
        boolean you = cluster.owner != BattlefieldLayout.StripOwner.ENEMY;
        // Same height as creature tiles; width is full-card 63/88, not the wide art-crop box.
        float ch = BattlefieldLayout.boardTileHeight(windowH);
        if (ch < 64f) ch = 64f;
        float cw = ch * HandLayout.CARD_ASPECT;
        float labelH = COMMAND_LABEL_H;
        float afterExile = cluster.exile.x + cluster.exile.w / 2f + 10f + cw / 2f;
        float cx = afterExile;
        float minCx = 8f + cw / 2f;
        float maxCx = windowW - 8f - cw / 2f;
        if (cx < minCx) cx = minCx;
        if (cx > maxCx) cx = maxCx;
        float cy = cluster.library.y;
        float divider = BattlefieldLayout.dividerY(windowH);
        float step = ch + labelH + 8f;
        for (int i = 0; i < seats.size(); i++) {
            BattlefieldLive.CommanderSeat seat = seats.get(i);
            if (seat == null) continue;
            float y = cy + (you ? -1f : 1f) * i * step;
            float half = ch / 2f + labelH + 4f;
            if (you) y = Math.max(half, Math.min(divider - half, y));
            else y = Math.max(divider + half, Math.min(windowH - half, y));
            CommandSlot slot = new CommandSlot();
            slot.seat = seat;
            slot.you = you;
            slot.cx = cx;
            slot.cy = y;
            slot.cw = cw;
            slot.ch = ch;
            commandSlots.add(slot);
        }
    }

    private int commandIndexAt(float x, float y) {
        for (int i = commandSlots.size() - 1; i >= 0; i--) {
            CommandSlot slot = commandSlots.get(i);
            if (Math.abs(x - slot.cx) <= slot.cw / 2f && Math.abs(y - slot.cy) <= slot.ch / 2f) return i;
            float[] badge = commandBadgeBox(slot);
            if (Math.abs(x - badge[0]) <= badge[2] / 2f && Math.abs(y - badge[1]) <= badge[3] / 2f) return i;
        }
        return -1;
    }

    /** Center x, center y, width, height of the command-zone badge under the slot. */
    private static float[] commandBadgeBox(CommandSlot slot) {
        float mark = 22f;
        float plate = mark + 6f;
        float bottom = slot.cy - slot.ch / 2f;
        return new float[] {slot.cx, bottom - 4f - plate / 2f, plate, plate};
    }

    private void drawCommandShapes() {
        for (CommandSlot slot : commandSlots) {
            float left = slot.cx - slot.cw / 2f;
            float bottom = slot.cy - slot.ch / 2f;
            boolean target = liveReady() && live.choosingTarget() && slot.seat != null
                    && slot.seat.card != null && live.legalTarget(slot.seat.card.view);
            if (target) {
                float[] spread = {11f, 6f, 3f};
                float[] alpha = {0.28f, 0.55f, 1f};
                for (int i = 0; i < spread.length; i++) {
                    float s = spread[i];
                    shapes.setColor(1f, 0.78f, 0.12f, alpha[i]);
                    shapes.rect(left - s, bottom - s, slot.cw + s * 2f, slot.ch + s * 2f);
                }
            } else if (slot.seat != null && slot.seat.inCommand && slot.seat.playable) {
                float[] spread = {11f, 6f, 3f};
                float[] alpha = {0.22f, 0.5f, 0.95f};
                for (int i = 0; i < spread.length; i++) {
                    float s = spread[i];
                    shapes.setColor(0.45f, 0.93f, 1f, alpha[i]);
                    shapes.rect(left - s, bottom - s, slot.cw + s * 2f, slot.ch + s * 2f);
                }
            }
            shapes.setColor(0.10f, 0.09f, 0.08f, 0.94f);
            shapes.rect(left, bottom, slot.cw, slot.ch);
            shapes.setColor(0.86f, 0.72f, 0.28f, 1f);
            shapes.rect(left - 2f, bottom - 2f, slot.cw + 4f, 3f);
            shapes.rect(left - 2f, bottom + slot.ch - 1f, slot.cw + 4f, 3f);
            shapes.rect(left - 2f, bottom, 3f, slot.ch);
            shapes.rect(left + slot.cw - 1f, bottom, 3f, slot.ch);
        }
    }

    private void drawCommandCards() {
        for (CommandSlot slot : commandSlots) {
            BattlefieldLive.CommanderSeat seat = slot.seat;
            if (seat == null) continue;
            if (!hideCommandForTravel(slot) && seat.inCommand) {
                Texture tex = textureFor(seat.name);
                if (tex != null) {
                    batch.setColor(Color.WHITE);
                    drawTex(tex, slot.cx, slot.cy, slot.cw, slot.ch, 0f);
                    drawCardBorder(slot.cx, slot.cy, slot.cw, slot.ch, 1f, FRAME_STEEL);
                } else {
                    batch.setColor(0.34f, 0.35f, 0.38f, 1f);
                    drawTex(pixel(), slot.cx, slot.cy, slot.cw, slot.ch, 0f);
                    batch.setColor(Color.WHITE);
                    drawFittedName(seat.name, slot.cx, slot.cy + 8f, slot.cw * 0.86f);
                }
                java.util.List<String> keys = seat.card != null && seat.card.keywords != null
                        ? seat.card.keywords : java.util.List.of();
                drawAbilityIcons(keys, slot.cx, slot.cy, slot.cw, slot.ch, false, true,
                        seat.colorKey, true);
                drawProtectMarks(keys, slot.cx, slot.cy, slot.cw, slot.ch, 1f, false);
            }
            drawCommandZoneMark(slot);
        }
    }

    /** Badge under the slot. Same gold shield-and-star as the card status icon. */
    private void drawCommandZoneMark(CommandSlot slot) {
        float[] badge = commandBadgeBox(slot);
        float cx = badge[0];
        float cy = badge[1];
        float plate = badge[2];
        float mark = 22f;
        batch.setColor(0.08f, 0.07f, 0.05f, 0.92f);
        batch.draw(pixel(), cx - plate / 2f, cy - plate / 2f, plate, plate);
        batch.setColor(Color.WHITE);
        if (commanderMark != null) {
            String key = slot.seat == null ? "" : slot.seat.colorKey;
            batch.setColor(commanderTint(key));
            batch.draw(commanderMark, cx - mark / 2f, cy - mark / 2f, mark, mark);
            batch.setColor(Color.WHITE);
        }
        if (hoverCommand >= 0 && hoverCommand < commandSlots.size() && commandSlots.get(hoverCommand) == slot) {
            drawPlate(stripFont, "Commander", cx + plate / 2f + 36f, cy + 4f, GOLD);
        }
    }

    private boolean hideCommandForTravel(CommandSlot slot) {
        if (slot == null || slot.seat == null || slot.seat.card == null || slot.seat.card.view == null) {
            return false;
        }
        if (draggingCommand && pressCommand >= 0 && pressCommand < commandSlots.size()
                && commandSlots.get(pressCommand) == slot) {
            return true;
        }
        return alreadyTraveling(slot.seat.card.view.getId());
    }

    /**
     * Off the slot and past the board-side cast line, or up onto his half of the table.
     * Same Forge select path as a click, including commander tax.
     */
    private boolean commandCastReady(CommandSlot slot) {
        if (slot == null || !slot.you || slot.seat == null || !slot.seat.inCommand) return false;
        boolean onSlot = Math.abs(mx - slot.cx) <= slot.cw / 2f + 6f
                && Math.abs(my - slot.cy) <= slot.ch / 2f + 6f;
        if (onSlot) return false;
        return mx > commandCastLineX(slot) || my > slot.cy + slot.ch * 0.2f;
    }

    private static float commandCastLineX(CommandSlot slot) {
        return slot.cx + slot.cw / 2f + 10f;
    }

    private void drawCommandCastLine() {
        if (!draggingCommand || pressCommand < 0 || pressCommand >= commandSlots.size()) return;
        CommandSlot slot = commandSlots.get(pressCommand);
        if (slot == null || !slot.you || slot.seat == null || !slot.seat.inCommand) return;
        float lineX = commandCastLineX(slot);
        float top = slot.cy + slot.ch / 2f + 16f;
        float bot = slot.cy - slot.ch / 2f - 16f;
        shapes.setColor(commandCastReady(slot) ? LINE_HOT : LINE_DIM);
        shapes.rect(lineX - 2f, bot, 4f, top - bot);
    }

    /** Full card follows the pointer. The leftover flyer still starts on the command tile. */
    private void drawDraggedCommand(float w, float h) {
        if (!draggingCommand || pressCommand < 0 || pressCommand >= commandSlots.size()) return;
        CommandSlot slot = commandSlots.get(pressCommand);
        if (slot == null || slot.seat == null || !slot.seat.inCommand) return;
        float ch = HandLayout.hoverRaisedHeight(h);
        float cw = ch * HandLayout.CARD_ASPECT;
        Texture tex = textureFor(slot.seat.name);
        if (tex == null) {
            drawOracleFrame(slot.seat.name, missingArtAt(mx, my, w, h));
            return;
        }
        batch.setColor(Color.WHITE);
        drawTex(tex, mx, my, cw, ch, 0f);
        drawCardBorder(mx, my, cw, ch, 1f, FRAME_STEEL);
        java.util.List<String> keys = slot.seat.card != null && slot.seat.card.keywords != null
                ? slot.seat.card.keywords : java.util.List.of();
        drawAbilityIcons(keys, mx, my, cw, ch, false, true,
                slot != null && slot.seat != null ? slot.seat.colorKey : "", true);
        drawProtectMarks(keys, mx, my, cw, ch, 1f, false);
    }

    private static final class CommandSlot {
        BattlefieldLive.CommanderSeat seat;
        boolean you;
        float cx, cy, cw, ch;
    }

    private void drawLife(BattlefieldLayout.PlayerCluster cluster, String initial, int life, String name) {
        float ox = cluster.owner == BattlefieldLayout.StripOwner.YOU ? youImpactX : themImpactX;
        float oy = cluster.owner == BattlefieldLayout.StripOwner.YOU ? youImpactY : themImpactY;
        float[] avatar = avatarBox(cluster.life);
        avatar[0] += ox;
        avatar[1] += oy;
        drawCenteredOn(stackFont, initial, avatar[0] + avatar[2] / 2f, avatar[1] + avatar[2] / 2f, Color.WHITE);
        String lifeText = Integer.toString(life);
        glyphs.setText(big, lifeText);
        float numberX = avatar[0] + avatar[2] + 14f + glyphs.width / 2f;
        float lifeRight = cluster.life.x + cluster.life.w / 2f - 8f;
        float maxX = lifeRight - glyphs.width / 2f;
        if (numberX > maxX) numberX = maxX;
        drawCentered(big, lifeText, numberX, cluster.life.y + oy + glyphs.height / 2f, lifeNumberColor(cluster.owner));
        if (name != null && !name.isBlank()) {
            int room = nameBudget(stripFont, Math.max(8f, cluster.life.w - 12f));
            String shown = name.length() > room ? name.substring(0, room) : name;
            glyphs.setText(stripFont, shown);
            float nameH = Math.max(8f, glyphs.height);
            float nameCy = BattlefieldLayout.lifeNameCenterY(cluster.life.y + oy, cluster.life.h, nameH);
            drawCenteredOn(stripFont, shown, cluster.life.x + ox, nameCy,
                    new Color(0.92f, 0.90f, 0.82f, 1f));
        }
    }

    private String turnWhose() {
        String name = activeOwner == BattlefieldLayout.StripOwner.ENEMY ? themName : youName;
        if (name == null || name.isBlank()) {
            return activeOwner == BattlefieldLayout.StripOwner.ENEMY ? "Their turn" : "Your turn";
        }
        return spaced(name + "'s", "turn");
    }

    private String mulliganWhose(boolean yours) {
        String name = yours ? youName : themName;
        if (name == null || name.isBlank()) return yours ? "Your mulligan" : "Their mulligan";
        return name + "'s mulligan";
    }

    private static String nameInitial(String name, String fallback) {
        if (name == null || name.isBlank()) return fallback;
        int cp = name.codePointAt(0);
        return new String(Character.toChars(Character.toUpperCase(cp)));
    }

    /**
     * Colored shield + damage with the current life box. One pip per commander
     * that has hit. HIS sit with his badge above the hand, right of the face.
     * Ash's sit with her top-center badge, just under it so they stay on screen.
     */
    private void drawCommanderDamage(BattlefieldLayout.PlayerCluster cluster,
            List<BattlefieldLive.CommanderPip> pips) {
        if (pips == null || pips.isEmpty() || cluster == null) return;
        float w = Math.max(1f, Gdx.graphics.getWidth());
        float h = Math.max(1f, Gdx.graphics.getHeight());
        float[] seat = BattlefieldLayout.commanderPipOrigin(w, h, cluster.life, cluster.owner, pips.size());
        float left = seat[0];
        float y = seat[1];
        float icon = 16f;
        float gap = 4f;
        float pipW = icon + 18f;
        boolean you = cluster.owner != BattlefieldLayout.StripOwner.ENEMY;
        for (int i = 0; i < pips.size(); i++) {
            BattlefieldLive.CommanderPip pip = pips.get(i);
            if (pip == null || pip.amount <= 0) continue;
            float x = left + i * (pipW + gap);
            if (commanderMark != null) {
                batch.setColor(commanderTint(pip.key));
                batch.draw(commanderMark, x, y - icon / 2f, icon, icon);
                batch.setColor(Color.WHITE);
            }
            String amount = Integer.toString(pip.amount);
            Color number = pip.amount >= 21 ? RED : Color.WHITE;
            drawCenteredOn(stripFont, amount, x + icon + 8f, y, number);
            if (mx >= x && mx <= x + pipW && Math.abs(my - y) <= 12f) {
                drawPlate(stripFont, pip.name, x + pipW / 2f, y + (you ? -16f : 18f), GOLD);
            }
        }
    }

    /**
     * Hash of the commander id:name. Same color on the command slot, the tile badge,
     * and the damage pip. Not a hardcoded Giada/Glissa pair.
     */
    private static Color commanderTint(String key) {
        int hash = 0;
        if (key != null) {
            for (int i = 0; i < key.length(); i++) hash = 31 * hash + key.charAt(i);
        }
        float hue = Math.floorMod(hash, 360) / 360f;
        Color tint = new Color();
        tint.fromHsv(hue * 360f, 0.70f, 0.96f);
        tint.a = 1f;
        return tint;
    }

    private int poisonOf(BattlefieldLayout.StripOwner owner) {
        return owner == BattlefieldLayout.StripOwner.ENEMY ? theirPoison : yourPoison;
    }

    /** Drop center, then the count center, just outside the life block toward the screen middle. */
    private float[] poisonSpot(BattlefieldLayout.PlayerCluster cluster, int poison) {
        BattlefieldLayout.WidgetBox life = cluster.life;
        glyphs.setText(stripFont, Integer.toString(poison));
        float drop = 18f;
        float gap = 5f;
        float edge = 6f;
        boolean middleIsLeft = life.x >= Gdx.graphics.getWidth() / 2f;
        float dropX;
        float countX;
        if (middleIsLeft) {
            float lifeLeft = life.x - life.w / 2f;
            dropX = lifeLeft - edge - drop / 2f;
            countX = dropX - drop / 2f - gap - glyphs.width / 2f;
        } else {
            float lifeRight = life.x + life.w / 2f;
            dropX = lifeRight + edge + drop / 2f;
            countX = dropX + drop / 2f + gap + glyphs.width / 2f;
        }
        return new float[] { dropX, life.y, countX };
    }

    /** Purple-green teardrop, about 18 pixels, drawn only while poison is at least 1. */
    private void drawPoisonDrop(BattlefieldLayout.PlayerCluster cluster) {
        int poison = poisonOf(cluster.owner);
        if (poison < 1) return;
        float[] spot = poisonSpot(cluster, poison);
        float cx = spot[0];
        float cy = spot[1];
        shapes.setColor(0.36f, 0.62f, 0.28f, 1f);
        shapes.triangle(cx, cy + 9f, cx - 5.5f, cy + 1.5f, cx + 5.5f, cy + 1.5f);
        shapes.setColor(0.46f, 0.20f, 0.62f, 1f);
        shapes.circle(cx, cy - 2f, 7f, 18);
        shapes.setColor(0.62f, 0.82f, 0.42f, 1f);
        shapes.circle(cx - 2f, cy - 0.5f, 2.2f, 12);
    }

    private void drawPoisonCount(BattlefieldLayout.PlayerCluster cluster) {
        int poison = poisonOf(cluster.owner);
        if (poison < 1) return;
        float[] spot = poisonSpot(cluster, poison);
        drawCenteredOn(stripFont, Integer.toString(poison), spot[2], spot[1], Color.WHITE);
    }

    private Color lifeNumberColor(BattlefieldLayout.StripOwner owner) {
        boolean you = owner != BattlefieldLayout.StripOwner.ENEMY;
        int life = you ? yourLife : theirLife;
        int starting = you ? yourStartingLife : theirStartingLife;
        int incoming = 0;
        boolean underAttack = false;
        for (Perm p : board) {
            if (!p.alive || !p.attacking || p.you == you) continue;
            underAttack = true;
            incoming += damageFromAttacker(p);
        }
        return switch (BattlefieldLayout.lifeTint(life, starting, incoming, underAttack)) {
            case RED -> RED;
            case GOLD -> GOLD;
            case YELLOW -> LIFE_YELLOW;
            case GREEN -> GREEN;
        };
    }

    /** Blocked attacks use the scripted preview. Unblocked attacks use shown power. */
    private int damageFromAttacker(Perm attacker) {
        if (!attacker.you) {
            if (!blockersOf(attacker).isEmpty()) {
                return BattlefieldLayout.previewBlock(
                        power(attacker), toughness(attacker), 0, 0).lifeLoss;
            }
        }
        return Math.max(0, power(attacker));
    }

    private void drawActionShapes(BattlefieldLayout.PlayerCluster cluster) {
        for (SmartButton button : smartButtons(cluster)) {
            BattlefieldLayout.ActionBox box = button.box;
            shapes.setColor(0.16f, 0.18f, 0.22f, button.enabled ? 0.96f : 0.45f);
            shapes.rect(box.x - box.w / 2f, box.y - box.h / 2f, box.w, box.h);
            shapes.setColor(GOLD.r, GOLD.g, GOLD.b, button.enabled ? 0.9f : 0.35f);
            outline(box.x - box.w / 2f, box.y - box.h / 2f, box.w, box.h, 2f);
        }
    }

    private void drawActionLabels(BattlefieldLayout.PlayerCluster cluster) {
        for (SmartButton button : smartButtons(cluster)) {
            drawCenteredOn(font, button.label, button.box.x, button.box.y,
                    button.enabled ? Color.WHITE : new Color(0.65f, 0.65f, 0.65f, 0.7f));
        }
        font.setColor(Color.WHITE);
    }

    private void drawCustomSleeve(BattlefieldLayout.PlayerCluster you) {
        if (yourSleeve != SLEEVE_CUSTOM || customSleeve == null) return;
        BattlefieldLayout.WidgetBox box = you.library;
        float[] front = frontSleeve(box);
        batch.setColor(Color.WHITE);
        drawTex(customSleeve, front[0], front[1], front[2], front[3], 0f);
    }

    /** Revealed top sits on the pile. While it is dragged, the sleeve shows until the card is released. */
    private void drawRevealedTop(BattlefieldLayout.PlayerCluster you) {
        if (draggingTop || !yourTopRevealed || yourLibrary.isEmpty()) return;
        if (you.owner != BattlefieldLayout.StripOwner.YOU) return;
        float[] front = frontSleeve(you.library);
        paintTopFace(yourLibrary.get(0), front[0], front[1], front[2], front[3]);
    }

    private void drawDraggedTop() {
        if (!yourTopRevealed || yourLibrary.isEmpty()) return;
        float[] front = yourFrontFace();
        paintTopFace(yourLibrary.get(0), mx, my, front[2], front[3]);
    }

    private void paintTopFace(ZoneCard card, float x, float y, float w, float h) {
        Texture tex = textureFor(card.name);
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, x, y, w, h, 0f);
        } else {
            batch.setColor(0.20f, 0.22f, 0.26f, 1f);
            drawTex(pixel(), x, y, w, h, 0f);
            batch.setColor(Color.WHITE);
            drawTag(card.name, x, y, Color.WHITE);
        }
    }

    /**
     * Other face of a DFC/MDFC/pathway, beside the main hover preview.
     * Missing back art is the compact type plate, not an empty full frame.
     */
    private void drawOtherFaceBeside(float w, float h, HandLayout.Pose main, String shownName,
                                     CardView view, forge.item.PaperCard paper, boolean preferRight) {
        BattlefieldLive.FacePick other = view != null
                ? BattlefieldLive.otherFace(view)
                : BattlefieldLive.otherFace(shownName, paper);
        drawOtherFacePick(w, h, main, other, preferRight);
    }

    private void drawOtherFacePick(float w, float h, HandLayout.Pose main,
                                   BattlefieldLive.FacePick other, boolean preferRight) {
        if (main == null || other == null || other.name == null || other.name.isBlank()) return;
        Texture tex = textureFor(other.name);
        float ow = tex != null ? main.w : BattlefieldLayout.missingArtWidth(h);
        float oh = tex != null ? main.h : BattlefieldLayout.missingArtHeight(h);
        HandLayout.Pose pose = BattlefieldLayout.previewBeside(w, h, main, ow, oh, preferRight);
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, pose.cx, pose.cy, pose.w, pose.h, 0f);
            drawCardBorder(pose.cx, pose.cy, pose.w, pose.h, 1f, FRAME_STEEL);
            return;
        }
        String type = other.typeLine == null || other.typeLine.isBlank() ? other.name : other.typeLine;
        drawMissingFacePlate(other.name, type, pose);
    }

    private void drawZoneGridShapes(float w, float h, BattlefieldLayout.PlayerCluster you,
                                     BattlefieldLayout.PlayerCluster enemy) {
        List<ZoneCard> cards = visibleZoneCards();
        BattlefieldLayout.PlayerCluster cluster = openOwner == BattlefieldLayout.StripOwner.ENEMY ? enemy : you;
        BattlefieldLayout.ZoneGrid grid = openGrid(w, h);
        HandLayout.Pose panel = grid.panel;
        shapes.setColor(0.08f, 0.08f, 0.09f, 1f);
        shapes.rect(panel.cx - panel.w / 2f, panel.cy - panel.h / 2f, panel.w, panel.h);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 0.9f);
        outline(panel.cx - panel.w / 2f, panel.cy - panel.h / 2f, panel.w, panel.h, 2f);
        HandLayout.Pose search = grid.search;
        shapes.setColor(0.14f, 0.15f, 0.17f, 1f);
        shapes.rect(search.cx - search.w / 2f, search.cy - search.h / 2f, search.w, search.h);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 0.85f);
        outline(search.cx - search.w / 2f, search.cy - search.h / 2f, search.w, search.h, 1.5f);
        for (int i = 0; i < grid.chips.length; i++) {
            HandLayout.Pose chip = grid.chips[i];
            boolean on = i == 0 ? zoneType == null : BattlefieldLayout.ZONE_TYPE_CHIPS[i].equals(zoneType);
            if (on) shapes.setColor(0.45f, 0.62f, 0.28f, 1f);
            else shapes.setColor(0.16f, 0.17f, 0.19f, 1f);
            shapes.rect(chip.cx - chip.w / 2f, chip.cy - chip.h / 2f, chip.w, chip.h);
            shapes.setColor(on ? GOLD.r : 0.35f, on ? GOLD.g : 0.35f, on ? GOLD.b : 0.32f, on ? 1f : 0.8f);
            outline(chip.cx - chip.w / 2f, chip.cy - chip.h / 2f, chip.w, chip.h, on ? 2f : 1f);
        }
        shapes.flush();
        clipCards(grid);
        for (int i = 0; i < cards.size(); i++) {
            if (!grid.cardVisible(i)) continue;
            if (hiddenLibraryCard(cards.get(i))) {
                if (librarySleeve() == SLEEVE_CUSTOM) continue;
                HandLayout.Pose pose = grid.cardAt(i);
                paintSleeve(pose.cx, pose.cy, pose.w, pose.h, librarySleeve(), true);
                continue;
            }
            if (textureFor(cards.get(i).name) != null) continue;
            HandLayout.Pose pose = grid.cardAt(i);
            shapes.setColor(0.20f, 0.22f, 0.26f, 1f);
            shapes.rect(pose.cx - pose.w / 2f, pose.cy - pose.h / 2f, pose.w, pose.h);
            shapes.setColor(0.72f, 0.62f, 0.38f, 1f);
            shapes.rect(pose.cx - pose.w / 2f, pose.cy + pose.h / 2f - pose.h * 0.18f, pose.w, pose.h * 0.18f);
            shapes.setColor(0.86f, 0.78f, 0.48f, 1f);
            outline(pose.cx - pose.w / 2f, pose.cy - pose.h / 2f, pose.w, pose.h, 2f);
        }
        shapes.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
        drawScrollTrack(grid);
    }

    private void clipCards(BattlefieldLayout.ZoneGrid grid) {
        Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
        HdpiUtils.glScissor((int) grid.viewLeft, (int) grid.viewBottom,
                Math.max(1, (int) grid.viewWidth), Math.max(1, (int) Math.ceil(grid.viewHeight)));
    }

    private void drawScrollTrack(BattlefieldLayout.ZoneGrid grid) {
        if (grid.scrollTrack == null || grid.maxScroll <= 0f) return;
        HandLayout.Pose track = grid.scrollTrack;
        shapes.setColor(0.22f, 0.23f, 0.26f, 1f);
        shapes.rect(track.cx - track.w / 2f, track.cy - track.h / 2f, track.w, track.h);
        float thumbH = Math.max(18f, track.h * (track.h / (track.h + grid.maxScroll)));
        float t = grid.scroll / grid.maxScroll;
        float thumbY = track.cy + track.h / 2f - thumbH / 2f - t * (track.h - thumbH);
        shapes.setColor(GOLD.r, GOLD.g, GOLD.b, 0.95f);
        shapes.rect(track.cx - track.w / 2f, thumbY, track.w, thumbH);
    }

    private void drawZoneGrid(float w, float h, BattlefieldLayout.PlayerCluster you,
                               BattlefieldLayout.PlayerCluster enemy) {
        if (openZone == ZoneView.NONE) return;
        List<ZoneCard> cards = visibleZoneCards();
        BattlefieldLayout.PlayerCluster cluster = openOwner == BattlefieldLayout.StripOwner.ENEMY ? enemy : you;
        BattlefieldLayout.ZoneGrid grid = openGrid(w, h);
        String title = switch (openZone) {
            case EXILE -> "Exile";
            case LIBRARY -> "Library";
            default -> "Graveyard";
        };
        String who = openOwner == BattlefieldLayout.StripOwner.ENEMY ? "Enemy " : "Your ";
        float textX = grid.panel.cx - grid.panel.w / 2f + 12f;
        float textW = grid.panel.w - 24f;
        float y = grid.panel.cy + grid.panel.h / 2f - 6f;
        font.setColor(GOLD);
        font.draw(batch, who + title, textX, y, textW, Align.left, false);
        String shown = zoneQuery.isEmpty() ? "Search" : zoneQuery;
        Color queryColor = zoneQuery.isEmpty() ? new Color(1f, 1f, 1f, 0.45f) : Color.WHITE;
        drawCenteredOn(font, clipToWidth(font, shown, grid.search.w - 16f), grid.search.cx, grid.search.cy, queryColor);
        for (int i = 0; i < grid.chips.length; i++) {
            HandLayout.Pose chip = grid.chips[i];
            boolean on = i == 0 ? zoneType == null : BattlefieldLayout.ZONE_TYPE_CHIPS[i].equals(zoneType);
            drawCenteredOn(stripFont, BattlefieldLayout.ZONE_TYPE_CHIPS[i], chip.cx, chip.cy,
                    on ? Color.WHITE : new Color(0.82f, 0.82f, 0.78f, 1f));
        }
        if (cards.isEmpty()) {
            String empty = zoneCards(openOwner, openZone).isEmpty() ? "Empty" : "No matches";
            font.setColor(Color.WHITE);
            font.draw(batch, empty, textX, grid.panel.cy, textW, Align.center, false);
            return;
        }
        batch.flush();
        clipCards(grid);
        for (int i = 0; i < cards.size(); i++) {
            if (!grid.cardVisible(i)) continue;
            HandLayout.Pose pose = grid.cardAt(i);
            drawZoneCard(cards.get(i), pose, pose.w, pose.h);
        }
        batch.flush();
        Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
    }

    /** Grid card from its mipmapped scan. A hidden library card is a sleeve back with no name. */
    private void drawZoneCard(ZoneCard card, HandLayout.Pose pose, float cw, float ch) {
        if (card != null && card.hiddenBack) {
            Texture back = cardBack();
            batch.setColor(Color.WHITE);
            if (back != null) drawTex(back, pose.cx, pose.cy, cw, ch, 0f);
            else drawTex(pixel(), pose.cx, pose.cy, cw, ch, 0f);
            return;
        }
        if (hiddenLibraryCard(card)) {
            if (librarySleeve() == SLEEVE_CUSTOM && customSleeve != null) {
                batch.setColor(Color.WHITE);
                drawTex(customSleeve, pose.cx, pose.cy, cw, ch, 0f);
            }
            return;
        }
        Texture tex = textureFor(card.name);
        if (tex != null) {
            batch.setColor(Color.WHITE);
            drawTex(tex, pose.cx, pose.cy, cw, ch, 0f);
            if (ch < HandLayout.hoverRaisedHeight(Gdx.graphics.getHeight()) * 0.72f) {
                drawTag(card.name, pose.cx, pose.cy + ch * 0.28f, Color.WHITE);
            }
        } else {
            batch.setColor(0.20f, 0.22f, 0.26f, 1f);
            drawTex(pixel(), pose.cx, pose.cy, cw, ch, 0f);
            batch.setColor(Color.WHITE);
            drawTag(card.name, pose.cx, pose.cy + ch * 0.28f, Color.WHITE);
            drawCenteredOn(stripFont, card.basicType, pose.cx, pose.cy - ch * 0.06f, Color.WHITE);
        }
    }

    private void drawCardZoom(float w, float h) {
        batch.setColor(Color.WHITE);
        boolean art = zoomTex != null;
        float zh = art ? 0.42f * h : BattlefieldLayout.missingArtHeight(h);
        float zw = art ? zh * HandLayout.CARD_ASPECT : BattlefieldLayout.missingArtWidth(h);
        float gap = 28f;
        float textW = Math.min(520f, Math.max(240f, w * 0.32f));
        float pair = zw + (zoomPanel ? gap + textW : 0f);
        float cx = (w - pair) / 2f + zw / 2f;
        float cy = h / 2f;
        if (zoomTex != null) {
            drawTex(zoomTex, cx, cy, zw, zh, 0f);
        } else {
            batch.setColor(0.35f, 0.36f, 0.4f, 1f);
            drawTex(pixel(), cx, cy, zw, zh, 0f);
            batch.setColor(Color.WHITE);
            drawTag(zoomName, cx, cy + zh * 0.28f, Color.WHITE);
        }
        if (zoomPerm != null && zoomPerm.kind == BattlefieldLayout.Kind.CREATURE) {
            if (lightsOut(zoomPerm)) drawLightsOut(cx, cy, zw, zh, 1f);
            drawCreatureMarks(zoomPerm, cx, cy, zw, zh, false);
        } else if (zoomPerm != null && zoomPerm.kind == BattlefieldLayout.Kind.PLANESWALKER) {
            if (zoomPerm.loyalty <= 0) drawLightsOut(cx, cy, zw, zh, 1f);
            drawLoyaltyMark(zoomPerm, cx, cy, zw, zh, false);
        } else if (zoomPerm != null && zoomPerm.kind == BattlefieldLayout.Kind.BATTLE) {
            if (zoomPerm.defense <= 0) drawLightsOut(cx, cy, zw, zh, 1f);
            drawDefenseMark(zoomPerm, cx, cy, zw, zh, false);
        } else if (zoomPerm != null && zoomPerm.kind == BattlefieldLayout.Kind.ENCHANTMENT && !zoomPerm.tucked) {
            drawLoreMark(zoomPerm, cx, cy, zw, zh);
        }
        if (!zoomPanel) return;
        float panelX = cx + zw / 2f + gap;
        float panelW = textW;
        float panelH = Math.min(h - 32f, Math.max(zh, h * 0.78f));
        float panelY = (h - panelH) / 2f;
        modLines.clear();
        drawCardInfo(panelX, panelY, panelW, panelH, zoomName, zoomPerm);
        ModLine hot = modLineAt(mx, my);
        if (hot != null) drawModHover(hot, w, h);
    }

    /** Name, oracle, and local rulings beside a preview card. Text is the FreeType face at its drawn size. */
    private void drawCardInfo(HandLayout.Pose panel, String name) {
        drawCardInfo(panel.cx - panel.w / 2f, panel.cy - panel.h / 2f, panel.w, panel.h, name);
    }

    private void drawCardInfo(float x, float y, float w, float h, String name) {
        drawCardInfo(x, y, w, h, name, null);
    }

    private void drawCardInfo(float x, float y, float w, float h, String name, Perm perm) {
        batch.setColor(0.07f, 0.07f, 0.08f, 0.94f);
        batch.draw(pixel(), x, y, w, h);
        batch.setColor(GOLD.r, GOLD.g, GOLD.b, 0.92f);
        float edge = 2f;
        batch.draw(pixel(), x, y, w, edge);
        batch.draw(pixel(), x, y + h - edge, w, edge);
        batch.draw(pixel(), x, y, edge, h);
        batch.draw(pixel(), x + w - edge, y, edge, h);
        batch.setColor(Color.WHITE);
        float pad = 16f;
        float textX = Math.round(x + pad);
        float textW = Math.max(40f, w - pad * 2f);
        float top = Math.round(y + h - pad);
        float floor = y + pad;
        float next = drawWrapped(stackFont, name == null ? "" : name, textX, top, textW, floor, GOLD);
        next -= 10f;
        next = drawInfoSection(textX, next, textW, floor, "Oracle", oracleText(name));
        next = drawModifierSection(textX, next, textW, floor, perm, name);
        drawInfoSection(textX, next, textW, floor, "Rulings", rulingsText(name));
        stackFont.setColor(Color.WHITE);
        font.setColor(Color.WHITE);
        batch.setColor(Color.WHITE);
    }

    private float drawInfoSection(float x, float y, float width, float floor, String heading, String body) {
        if (y <= floor) return y;
        stackFont.setColor(GOLD);
        glyphs.setText(stackFont, heading);
        if (y - glyphs.height < floor) return floor;
        stackFont.draw(batch, heading, x, y);
        y -= glyphs.height + 6f;
        y = drawWrapped(stackFont, body == null ? "" : body, x, y, width, floor, Color.WHITE);
        return y - 14f;
    }

    private float drawModifierSection(float x, float y, float width, float floor, Perm perm, String name) {
        List<ModLine> lines = modifierLines(perm, name);
        if (lines.isEmpty() || y <= floor) return y;
        stackFont.setColor(GOLD);
        glyphs.setText(stackFont, "On this card");
        if (y - glyphs.height < floor) return floor;
        stackFont.draw(batch, "On this card", x, y);
        y -= glyphs.height + 8f;
        for (ModLine line : lines) {
            if (y <= floor) break;
            glyphs.setText(stackFont, line.label);
            float titleH = glyphs.height;
            line.x = x;
            line.y = y - titleH;
            line.w = Math.min(width, Math.max(glyphs.width, 8f));
            line.h = titleH + 4f;
            modLines.add(line);
            stackFont.setColor(GOLD);
            stackFont.draw(batch, line.label, x, y);
            y -= titleH + 4f;
            if (line.reminder != null && !line.reminder.isBlank() && y > floor) {
                y = drawWrapped(stripFont, line.reminder, x, y, width, floor, Color.WHITE);
                y -= 8f;
            } else {
                y -= 4f;
            }
        }
        return y - 10f;
    }

    private List<ModLine> modifierLines(Perm perm, String name) {
        List<ModLine> lines = new ArrayList<>();
        java.util.List<BattlefieldLive.Status> status = perm == null ? java.util.List.of() : perm.status;
        if ((status == null || status.isEmpty()) && perm != null && perm.forgeCard != null) {
            status = BattlefieldLive.statusOf(perm.forgeCard);
        }
        if ((status == null || status.isEmpty()) && name != null && !name.isBlank()) {
            status = BattlefieldLive.statusOf(cardViewNamed(name));
        }
        if (status != null) {
            for (BattlefieldLive.Status row : status) {
                if (row == null || row.title == null || row.title.isBlank()) continue;
                lines.add(new ModLine(row.title, row.reminder, true));
            }
        }
        if (perm != null && perm.buff > 0) {
            lines.add(new ModLine("+" + perm.buff + "/+" + perm.buff, sourceOf(perm.buffLine), false));
        }
        if (perm != null && perm.debuff > 0) {
            lines.add(new ModLine("-" + perm.debuff + "/-" + perm.debuff, sourceOf(perm.debuffLine), false));
        }
        return lines;
    }

    /** A pump line may start with the bonus. The rest is the source. A blank line is unknown. */
    private static String sourceOf(String line) {
        if (line == null || line.isBlank()) return "unknown source";
        String trimmed = line.trim();
        int split = trimmed.indexOf(' ');
        if (split > 0 && (trimmed.startsWith("+") || trimmed.startsWith("-"))) {
            String rest = trimmed.substring(split).trim();
            if (!rest.isEmpty()) return rest;
        }
        return trimmed;
    }

    private ModLine modLineAt(float x, float y) {
        for (int i = modLines.size() - 1; i >= 0; i--) {
            ModLine line = modLines.get(i);
            if (x >= line.x && x <= line.x + line.w && y >= line.y && y <= line.y + line.h) return line;
        }
        return null;
    }

    private void drawModHover(ModLine line, float w, float h) {
        String text = line.permanent
                ? line.source + ", permanent"
                : line.source + ", temporary, until end of turn";
        glyphs.setText(font, text);
        float pad = 6f;
        float pw = glyphs.width + pad * 2f;
        float ph = glyphs.height + pad * 2f;
        float x = Math.min(mx + 12f, w - pw - 8f);
        float y = Math.min(my + 12f, h - ph - 8f);
        batch.setColor(0.08f, 0.08f, 0.09f, 0.96f);
        batch.draw(pixel(), x, y, pw, ph);
        batch.setColor(Color.WHITE);
        font.setColor(Color.WHITE);
        font.draw(batch, text, x + pad, y + pad + glyphs.height);
    }

    /**
     * Word-wrapped text in {@code face}, which was generated at the size it is drawn.
     * Stops at {@code floor} so a long oracle stays inside the panel.
     */
    private float drawWrapped(BitmapFont face, String text, float x, float y, float width, float floor, Color color) {
        face.setColor(color);
        face.getData().setScale(1f);
        String[] paragraphs = text.split("\n", -1);
        for (int p = 0; p < paragraphs.length; p++) {
            if (y <= floor) return y;
            String para = paragraphs[p];
            if (para.isEmpty()) {
                glyphs.setText(face, " ");
                y -= glyphs.height;
                continue;
            }
            String[] words = para.split(" ");
            StringBuilder line = new StringBuilder();
            for (String word : words) {
                if (word.isEmpty()) continue;
                String trial = line.length() == 0 ? word : line + " " + word;
                glyphs.setText(face, trial);
                if (glyphs.width <= width) {
                    line.setLength(0);
                    line.append(trial);
                    continue;
                }
                if (line.length() > 0) {
                    y = drawInfoLine(face, line.toString(), x, y, floor, color);
                    if (y <= floor) return y;
                    line.setLength(0);
                }
                glyphs.setText(face, word);
                if (glyphs.width > width) {
                    y = drawBrokenWord(face, word, x, y, width, floor, color);
                    if (y <= floor) return y;
                } else {
                    line.append(word);
                }
            }
            if (line.length() > 0) {
                y = drawInfoLine(face, line.toString(), x, y, floor, color);
                if (y <= floor) return y;
            }
        }
        return y;
    }

    private float drawBrokenWord(BitmapFont face, String word, float x, float y, float width, float floor, Color color) {
        StringBuilder chunk = new StringBuilder();
        for (int i = 0; i < word.length(); i++) {
            chunk.append(word.charAt(i));
            glyphs.setText(face, chunk.toString());
            if (glyphs.width > width && chunk.length() > 1) {
                chunk.setLength(chunk.length() - 1);
                y = drawInfoLine(face, chunk.toString(), x, y, floor, color);
                if (y <= floor) return y;
                chunk.setLength(0);
                chunk.append(word.charAt(i));
            }
        }
        if (chunk.length() > 0) y = drawInfoLine(face, chunk.toString(), x, y, floor, color);
        return y;
    }

    private float drawInfoLine(BitmapFont face, String line, float x, float y, float floor, Color color) {
        glyphs.setText(face, line);
        if (y - glyphs.height < floor) return floor;
        face.setColor(color);
        face.draw(batch, line, x, y);
        return y - glyphs.height - 2f;
    }

    private String clipToWidth(BitmapFont face, String text, float width) {
        if (text == null || text.isEmpty()) return "";
        glyphs.setText(face, text);
        if (glyphs.width <= width) return text;
        String clipped = text;
        while (clipped.length() > 1) {
            clipped = clipped.substring(1);
            glyphs.setText(face, clipped);
            if (glyphs.width <= width) return clipped;
        }
        return clipped;
    }

    private List<ZoneCard> visibleZoneCards() {
        List<ZoneCard> src = zoneCards(openOwner, openZone);
        List<ZoneCard> ordered = new ArrayList<>();
        if (openZone == ZoneView.LIBRARY) ordered.addAll(src);
        else for (int i = src.size() - 1; i >= 0; i--) ordered.add(src.get(i));
        List<ZoneCard> shown = new ArrayList<>();
        for (ZoneCard card : ordered) {
            if (openZone == ZoneView.LIBRARY && !card.revealed) {
                shown.add(card);
                continue;
            }
            if (BattlefieldLayout.zoneQueryMatches(card.paper, zoneQuery, zoneType)) shown.add(card);
        }
        return shown;
    }

    /** Top card, plus one of your Grizzly Bears. The rest of a library stays face down. */
    private void revealLibrary(List<ZoneCard> library, boolean oneBear) {
        if (library.isEmpty()) return;
        library.get(0).revealed = true;
        if (!oneBear) return;
        for (ZoneCard card : library) {
            if ("Grizzly Bears".equals(card.name)) {
                card.revealed = true;
                return;
            }
        }
    }

    private boolean hiddenLibraryCard(ZoneCard card) {
        return openZone == ZoneView.LIBRARY && card != null && !card.revealed;
    }

    /** Your sleeve, or the enemy's plain dark back. A missing custom image uses the plain sleeve. */
    private int librarySleeve() {
        if (openOwner == BattlefieldLayout.StripOwner.ENEMY) return SLEEVE_PLAIN;
        if (yourSleeve == SLEEVE_CUSTOM && customSleeve == null) return SLEEVE_PLAIN;
        return yourSleeve;
    }

    private List<ZoneCard> zoneCards(BattlefieldLayout.StripOwner owner, ZoneView zone) {
        boolean you = owner != BattlefieldLayout.StripOwner.ENEMY;
        if (zone == ZoneView.EXILE) return you ? yourExile : theirExile;
        if (zone == ZoneView.LIBRARY) return you ? yourLibrary : theirLibrary;
        return you ? yourGrave : theirGrave;
    }

    private forge.item.PaperCard paperFor(String name) {
        forge.item.PaperCard cached = zonePapers.get(name);
        if (cached != null) return cached;
        List<String> lines = readScript(name);
        if (lines == null) lines = fallbackScript(name, scriptedType(name));
        else lines = BattlefieldLayout.colorBasicLand(lines);
        forge.item.PaperCard paper = BattlefieldLayout.paperFromScript(lines);
        zonePapers.put(name, paper);
        return paper;
    }

    private static String slugOf(String name) {
        if (name == null || name.isBlank()) return "";
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replace(' ', '_');
    }

    private static Path cardsRoot;

    /** Finds forge-gui/res/cardsfolder from the working directory or its parents. */
    private static Path cardsFolder() {
        if (cardsRoot != null) return cardsRoot;
        Path[] starts = {
                Path.of(System.getProperty("user.dir", ".")),
                Path.of("A:/forge_adventure")
        };
        for (Path start : starts) {
            Path dir = start.toAbsolutePath().normalize();
            for (int i = 0; i < 8 && dir != null; i++) {
                Path folder = dir.resolve(Path.of("forge-gui", "res", "cardsfolder"));
                if (Files.isDirectory(folder)) {
                    cardsRoot = folder;
                    return cardsRoot;
                }
                dir = dir.getParent();
            }
        }
        cardsRoot = Path.of("forge-gui", "res", "cardsfolder");
        return cardsRoot;
    }

    private static List<String> readScript(String name) {
        if (name == null || name.isBlank()) return null;
        String slug = slugOf(name);
        if (slug.isEmpty()) return null;
        Path file = cardsFolder().resolve(slug.substring(0, 1)).resolve(slug + ".txt");
        if (!Files.isRegularFile(file)) file = faceScript(slug);
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank() || line.charAt(0) == '#') continue;
                lines.add(line);
            }
            return lines.isEmpty() ? null : lines;
        } catch (IOException failed) {
            return null;
        }
    }

    /** A double-faced card file starts with the front-face slug, then the back face. */
    private static Path faceScript(String slug) {
        if (slug == null || slug.isEmpty()) return null;
        Path dir = cardsFolder().resolve(slug.substring(0, 1));
        if (!Files.isDirectory(dir)) return null;
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            Path prefix = null;
            for (Path file : files.toList()) {
                String filename = file.getFileName().toString();
                if (!filename.endsWith(".txt")) continue;
                String stem = filename.substring(0, filename.length() - 4);
                if (stem.equals(slug)) return file;
                if (prefix == null && stem.startsWith(slug + "_")) prefix = file;
            }
            return prefix;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static List<String> fallbackScript(String name, String basicType) {
        String type = basicType == null || basicType.isBlank() ? "Creature" : basicType;
        String cost = switch (type) {
            case "Land" -> "no cost";
            case "Instant", "Sorcery" -> "1";
            default -> "2";
        };
        String types = switch (type) {
            case "Land" -> "Land";
            case "Instant" -> "Instant";
            case "Sorcery" -> "Sorcery";
            case "Artifact" -> "Artifact";
            case "Enchantment" -> "Enchantment";
            case "Planeswalker" -> "Planeswalker";
            case "Battle" -> "Battle";
            default -> "Creature";
        };
        List<String> lines = new ArrayList<>();
        lines.add("Name:" + name);
        lines.add("ManaCost:" + cost);
        lines.add("Types:" + types);
        if ("Creature".equals(types)) lines.add("PT:2/2");
        lines.add("Oracle:");
        return lines;
    }

    /** Arched token tile. Token scans live in Cache\\pics\\tokens; a miss queues one fetch. */
    private Texture tokenTextureFor(String name) {
        if (name == null || name.isBlank()) return null;
        String key = name + "\u0000token";
        Texture cached = zoneArt.get(key);
        if (cached != null) return cached;
        Path scan = findNamedTokenScan(name);
        if (scan == null) {
            if (BattlefieldPics.tokenFailed(name)) {
                zoneArtMiss.add(key);
                return textureFor(name);
            }
            BattlefieldPics.wantToken(name);
            return null;
        }
        try {
            Texture tex = loadToken(scan);
            zoneArt.put(key, tex);
            return tex;
        } catch (RuntimeException failed) {
            zoneArtMiss.add(key);
            return textureFor(name);
        }
    }

    private static Texture loadToken(Path file) {
        Pixmap src = new Pixmap(new FileHandle(file.toFile()));
        Pixmap pm = src;
        if (src.getFormat() != Pixmap.Format.RGBA8888) {
            pm = new Pixmap(src.getWidth(), src.getHeight(), Pixmap.Format.RGBA8888);
            pm.setBlending(Pixmap.Blending.None);
            pm.drawPixmap(src, 0, 0);
            src.dispose();
        }
        archTokenTop(pm);
        Texture tex = new Texture(pm, true);
        tex.setFilter(TextureFilter.MipMapLinearLinear, TextureFilter.Linear);
        pm.dispose();
        return tex;
    }

    /** Clears the top corners of the cropped face so the tile reads as an arch. */
    private static void archTokenTop(Pixmap pm) {
        int w = pm.getWidth();
        int h = pm.getHeight();
        if (w < 2 || h < 2 || pm.getFormat() != Pixmap.Format.RGBA8888) return;
        int cropH = Math.max(1, Math.round(h * BattlefieldLayout.BOARD_FACE_CROP));
        ByteBuffer pixels = pm.getPixels();
        int reach = (int) Math.ceil(BattlefieldLayout.TOKEN_ARCH_FRAC * cropH) + 2;
        for (int y = 0; y < reach && y < cropH; y++) {
            float v = (y + 0.5f) / cropH;
            for (int x = 0; x < w; x++) {
                float u = (x + 0.5f) / w;
                if (BattlefieldLayout.tokenTileContains(u, v)) continue;
                pixels.put((y * w + x) * 4 + 3, (byte) 0);
            }
        }
    }

    private Texture textureFor(String name) {
        if (name == null || zoneArtMiss.contains(name)) return null;
        Texture cached = zoneArt.get(name);
        if (cached != null) return cached;
        Path scan = findNamedScan(name);
        if (scan == null) {
            if (BattlefieldPics.failed(name)) {
                zoneArtMiss.add(name);
                return null;
            }
            BattlefieldPics.want(name);
            return null;
        }
        try {
            Texture tex = loadRounded(scan);
            zoneArt.put(name, tex);
            return tex;
        } catch (RuntimeException failed) {
            zoneArtMiss.add(name);
            return null;
        }
    }

    private static Map<String, Path> scanIndex;
    private static Map<String, Path> tokenIndex;
    private static final Object SCAN_LOCK = new Object();

    static boolean hasScan(String name) {
        return findNamedScan(name) != null;
    }

    static boolean hasTokenScan(String name) {
        return findNamedTokenScan(name) != null;
    }

    static void scansChanged(String name) {
        synchronized (SCAN_LOCK) {
            scanIndex = null;
            tokenIndex = null;
        }
        if (Gdx.app != null && Gdx.app.getApplicationListener() instanceof BattlefieldPrototype proto) {
            if (name != null) {
                proto.zoneArtMiss.remove(name);
                proto.zoneArtMiss.remove(name + "\u0000token");
                int dot = name.indexOf('.');
                if (dot > 0) {
                    String stem = name.substring(0, dot);
                    proto.zoneArtMiss.remove(stem);
                    proto.zoneArtMiss.remove(stem + "\u0000token");
                }
            }
        }
    }

    private static Path findNamedScan(String name) {
        return lookupScan(name, false);
    }

    private static Path findNamedTokenScan(String name) {
        Path hit = lookupScan(name, true);
        if (hit != null || name == null) return hit;
        String folded = name.toLowerCase(Locale.ROOT);
        if (folded.endsWith(" token")) {
            return lookupScan(name.substring(0, name.length() - 6).trim(), true);
        }
        return null;
    }

    private static Path lookupScan(String name, boolean token) {
        Map<String, Path> index;
        synchronized (SCAN_LOCK) {
            if (token) {
                if (tokenIndex == null) tokenIndex = indexPicRoots(picRoots("tokens"));
                index = tokenIndex;
            } else {
                if (scanIndex == null) scanIndex = indexPicRoots(picRoots("cards"));
                index = scanIndex;
            }
        }
        for (String key : scanKeys(name)) {
            Path path = index.get(key);
            if (path != null && HandPrototype.isCardScan(path)) return path;
        }
        return null;
    }

    /**
     * Printed name, Forge's picture filename, and the front face of a double-faced card.
     * Forge drops quotes, slashes, colons, and question marks, so
     * {@code Outland Liberator // Frenzied Trapbreaker} is stored without the slashes.
     */
    private static List<String> scanKeys(String name) {
        String key = scanKey(name);
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addScanKey(keys, key);
        addScanKey(keys, forgeFileStem(key));
        int split = key.indexOf(" // ");
        if (split > 0) addScanKey(keys, key.substring(0, split).trim());
        return new ArrayList<>(keys);
    }

    private static void addScanKey(Set<String> keys, String key) {
        if (key == null || key.isEmpty()) return;
        keys.add(key);
        String printed = printedScanName(key);
        if (!printed.equals(key)) keys.add(printed);
    }

    /** Same characters Forge removes in {@code ImageUtil.toMWSFilename}. */
    private static String forgeFileStem(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '"' && c != '/' && c != ':' && c != '?') out.append(c);
        }
        return out.toString();
    }

    private static String scanKey(String name) {
        String folded = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return folded.toLowerCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace('\u2018', '\'');
    }

    /**
     * File stem before the first dot, so {@code Grizzly Bears.fullborder.jpg} matches that name.
     * Forge glues the art index on when a card has more than one picture:
     * {@code Lightning Bolt1.fullborder.jpg} is still Lightning Bolt.
     */
    private static List<Path> picRoots(String kind) {
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize()
                .resolve(Path.of("pics", kind)));
        String testHome = System.getProperty("forge.test.userDir");
        if (testHome != null && !testHome.isBlank()) {
            roots.add(Path.of(testHome).toAbsolutePath().normalize()
                    .resolve(Path.of("cache", "pics", kind)));
        }
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) roots.add(Path.of(local, "Forge", "Cache", "pics", kind));
        String home = System.getProperty("user.home");
        if (home != null) {
            roots.add(Path.of(home, "AppData", "Local", "Forge", "Cache", "pics", kind));
            roots.add(Path.of(home, ".forge", "Cache", "pics", kind));
        }
        if ("tokens".equals(kind)) {
            try {
                String dir = forge.localinstance.properties.ForgeConstants.CACHE_TOKEN_PICS_DIR;
                if (dir != null && !dir.isBlank()) roots.add(Path.of(dir));
            } catch (Throwable ignored) {
                /* prefs not up yet */
            }
        }
        return roots;
    }

    private static Map<String, Path> indexPicRoots(List<Path> roots) {
        Map<String, Path> index = new HashMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile).forEach(file -> rememberScan(index, file));
            } catch (IOException ignored) {
                /* next root */
            }
        }
        return index;
    }

    /** Keep the larger file when several arts share a printed name. Thumbnails lose to a full scan. */
    private static void rememberScan(Map<String, Path> index, Path file) {
        String n = file.getFileName().toString();
        int dot = n.indexOf('.');
        if (dot <= 0) return;
        long size;
        try {
            size = Files.size(file);
        } catch (IOException failed) {
            return;
        }
        if (size < 20_000L) return;
        String stem = scanKey(n.substring(0, dot));
        putLargerScan(index, stem, file, size);
        String printed = printedScanName(stem);
        if (!printed.equals(stem)) putLargerScan(index, printed, file, size);
        int gap = printed.indexOf("  ");
        if (gap > 0) putLargerScan(index, printed.substring(0, gap).trim(), file, size);
    }

    private static void putLargerScan(Map<String, Path> index, String key, Path file, long size) {
        Path existing = index.get(key);
        if (existing == null) {
            index.put(key, file);
            return;
        }
        try {
            if (size > Files.size(existing)) index.put(key, file);
        } catch (IOException ignored) {
            /* keep the scan already stored */
        }
    }

    /** {@code lightning bolt1} is the printed name {@code lightning bolt}. */
    private static String printedScanName(String stem) {
        return stem.replaceFirst("(?<=\\p{L})\\d+$", "");
    }

    private static float[] avatarBox(BattlefieldLayout.WidgetBox life) {
        float size = Math.min(46f, life.h - 14f);
        float x = life.x - life.w / 2f + 8f;
        float y = life.y - size / 2f;
        return new float[] { x, y, size };
    }

    private void outline(float x, float y, float w, float h, float edge) {
        shapes.rect(x, y, w, edge);
        shapes.rect(x, y + h - edge, w, edge);
        shapes.rect(x, y, edge, h);
        shapes.rect(x + w - edge, y, edge, h);
    }

    @Override
    public void dispose() {
        if (live != null) live.shutdownMatch();
        if (batch != null) batch.dispose();
        if (shapes != null) shapes.dispose();
        if (font != null) font.dispose();
        if (stripFont != null) stripFont.dispose();
        if (stackFont != null) stackFont.dispose();
        if (big != null) big.dispose();
        if (fontGenerator != null) fontGenerator.dispose();
        if (pixel != null) pixel.dispose();
        if (customSleeve != null) customSleeve.dispose();
        if (graveIcon != null) graveIcon.dispose();
        if (exileIcon != null) exileIcon.dispose();
        if (cardBack != null) cardBack.dispose();
        if (abilitySheet != null) abilitySheet.dispose();
        if (commanderMark != null) commanderMark.dispose();
        if (hexproofMark != null) hexproofMark.dispose();
        if (shroudMark != null) shroudMark.dispose();
        if (hasteMark != null) hasteMark.dispose();
        if (iconSheet != null) iconSheet.dispose();
        for (Texture t : art) t.dispose();
        for (Texture t : zoneArt.values()) if (t != null) t.dispose();
    }

    /** One scripted zone card. {@code paper} is a real Forge card when a script exists. */
    private static final class ZoneCard {
        final String name;
        final String basicType;
        final forge.item.PaperCard paper;
        CardView forgeCard;
        boolean revealed;
        boolean hiddenBack;
        boolean playable;
        boolean ownerSees;

        ZoneCard(String name, String basicType, forge.item.PaperCard paper) {
            this.name = name;
            this.basicType = basicType;
            this.paper = paper;
        }
    }

    private static final class Perm {
        String name;
        BattlefieldLayout.Kind kind = BattlefieldLayout.Kind.LAND;
        boolean you, tapped, sick, attacking, blocking, choiceCard, hasAbility, alive = true;
        Color frame;
        Perm blockTarget;
        int[] blockAttackerIds = new int[0];
        int power, toughness, printedPower, printedToughness, buff, debuff, counters, minusCounters, damage;
        int loyalty, printedLoyalty;
        int defense, printedDefense;
        /** Empty when the permanent can attack and block. Otherwise atk, blk, or both. */
        String restraint = "";
        boolean hasPrinted;
        boolean hasLoyalty;
        boolean hasDefense;
        /** True when he is the protecting / defending player of this battle. */
        boolean protectYou;
        java.util.List<String> keywords = java.util.List.of();
        java.util.List<BattlefieldLive.Status> status = java.util.List.of();
        /** True when Forge says this permanent is a commander. */
        boolean commander;
        String commanderKey = "";
        String buffLine, debuffLine, choice;
        Texture tex;
        CardView forgeCard;
        /** An activated ability on your permanent that Forge says you can use now. */
        boolean playable;
        float cx, cy, w, h, rot, alpha = 1f, thunk;
        float summonDelay, summonLeft, summonFull;
        float tx, ty, tw, th, trot;
        /** Pixels this flier rests above the ground row. Hit tests use {@code ty}, not the bob. */
        float flyLift;
        int pileCount = 1;
        int pileLayer;
        boolean pileFront;
        int attachedToId;
        boolean aura;
        /** Forge type line when the card view exposes it. Used for Saga / chapter. */
        String typeLine = "";
        /** Lore counters when Forge exposes them. */
        int lore;
        /** Forge Equipment subtype, not a card name. Loose until attachedToId is set. */
        boolean equipment;
        /** Token tile uses an arched top. Real cards stay rectangular. */
        boolean token;
        /** Set when this aura or equipment is attached to a permanent on the board. */
        Perm auraHost;
        boolean tucked;
        int tuckIndex;
        float tuckPeek;
        /** This tile just arrived. Smoke and the summon sound wait until this seat is on screen. */
        boolean impactPending;
        /** Instant / sorcery never paint as a battlefield permanent. */
        boolean instant, sorcery;
    }

    /** Hosts in back-to-front order. Furthest aura titles draw first, then the host, which covers the aura bodies. */
    private void drawSide(boolean you) {
        List<Perm> painted = new ArrayList<>();
        for (Perm p : board) {
            if (p.you != you || p.alpha <= 0.02f || p.tucked) continue;
            painted.add(p);
        }
        painted.sort((a, b) -> Integer.compare(b.pileLayer, a.pileLayer));
        for (Perm p : painted) drawWithAuras(p);
    }

    /** Furthest tucked titles first, then this card, so the card covers the aura bodies under it. */
    private void drawWithAuras(Perm host) {
        List<Perm> auras = new ArrayList<>();
        for (Perm aura : board) {
            if (aura.tucked && aura.auraHost == host && aura.alpha > 0.02f) auras.add(aura);
        }
        auras.sort((a, b) -> Integer.compare(b.tuckIndex, a.tuckIndex));
        for (Perm aura : auras) drawWithAuras(aura);
        if (host.alpha > 0.02f) drawPerm(host);
    }

    private static final class Bash {
        Perm perm;
        Perm target;
        boolean hitsYou, hitsThem, shook, dropsLife;
        /** Extra blocker: thud and thunk only, so the attacker keeps one lunge. */
        boolean impactOnly;
        float x0, y0, x1, y1, baseRot, delay;
        int lifeAfter;
    }

    /** One generic mana circle. A split fill is a hybrid pip. Phyrexian adds a small mark. */
    private static final class ManaPip {
        final String label;
        final Color fill;
        final Color split;
        final Color ink;
        final boolean phyrexian;

        ManaPip(String label, Color fill, Color split, Color ink, boolean phyrexian) {
            this.label = label;
            this.fill = fill;
            this.split = split;
            this.ink = ink;
            this.phyrexian = phyrexian;
        }
    }

    private static final class ManaPipDraw {
        final float x, y, r;
        final String label;
        final Color fill, split, ink;
        final boolean phyrexian;

        ManaPipDraw(float x, float y, float r, ManaPip pip) {
            this.x = x;
            this.y = y;
            this.r = r;
            this.label = pip.label;
            this.fill = pip.fill;
            this.split = pip.split;
            this.ink = pip.ink;
            this.phyrexian = pip.phyrexian;
        }
    }

    /** One impact puff: center plus six expanding blobs. */
    private static final class SummonPuff {
        float x, y, t, dur;
        float[][] blobs;
        boolean death;
    }

    private enum CastOrigin { HAND, COMMAND, GHOST }

    /** On-screen start pose: hand fan, ghost fan, or the command-zone slot. */
    private static final class CastPose {
        int id;
        String name = "";
        Texture tex;
        BattlefieldLayout.Kind kind = BattlefieldLayout.Kind.SPELL;
        CastOrigin origin = CastOrigin.HAND;
        float cx, cy, w, h, rot;
    }

    /** One card picture traveling from that pose to the stack or a board tile. */
    private static final class CastFlight {
        int cardId;
        int spellId;
        int permId;
        Texture tex;
        String name = "";
        BattlefieldLayout.Kind kind = BattlefieldLayout.Kind.SPELL;
        CastOrigin origin = CastOrigin.HAND;
        float homeX, homeY, homeW, homeH, homeRot;
        float x0, y0, w0, h0, rot0;
        float x1, y1, w1, h1, rot1;
        float t, dur = CAST_TRAVEL_SEC;
        boolean untargeted, toBoard, done, aimed, sawPay;
    }

    private static final class StackItem {
        int id;
        HandCard card;
        String name = "";
        String fullText = "";
        String typeLine = "";
        String cost = "";
        String pt = "";
        String rules = "";
        String targetLabel;
        int[] targetCardIds = new int[0];
        String preview = "";
        Perm targetPerm;
        boolean needsTarget, needsChoice, targetYou, targetThem;
        boolean showcase;
        int sourceId;
        CardView forgeCard;
        boolean sourceOnBoard;
        boolean sourceYours;
        float rowX, rowY, rowW, rowH;
        float thumbX, thumbY, thumbW, thumbH;
        float summonDelay, summonLeft, summonFull;
    }

    private static final class HandCard {
        String name;
        BattlefieldLayout.Kind kind;
        boolean choice, instant, alive = true, parked;
        int power, toughness;
        Texture tex;
        CardView forgeCard;
        java.util.List<String> keywords = java.util.List.of();
        boolean commander;
        String commanderKey = "";
        boolean bottomPick;
        /** Forge says this card can be played or cast in the current priority window. */
        boolean playable;
        float cx, cy, w, h, rotation;
        float tcx, tcy, tw, th, trot;
    }

    private static final class SmartButton {
        final BattlefieldLayout.ActionBox box;
        final String label;
        final boolean enabled;

        SmartButton(BattlefieldLayout.ActionBox box, String label, boolean enabled) {
            this.box = box;
            this.label = label;
            this.enabled = enabled;
        }
    }

    private static final class Emblem {
        String name = "";
        String text = "";
        float x, y, w, h;
    }

    private static final class UiButton {
        final String id;
        final String label;
        final float x, y, w, h;

        UiButton(String id, String label, float x, float y, float w, float h) {
            this.id = id;
            this.label = label;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(float px, float py) {
            return Math.abs(px - x) <= w / 2f && Math.abs(py - y) <= h / 2f;
        }
    }

    /** One plus or minus on the oracle panel. Counters are permanent. A pump is temporary. */
    private static final class ModLine {
        final String label;
        final String source;
        final String reminder;
        final boolean permanent;
        float x, y, w, h;

        ModLine(String label, String source, boolean permanent) {
            this.label = label;
            this.reminder = source == null ? "" : source;
            this.source = source == null || source.isBlank() ? "unknown source" : source;
            this.permanent = permanent;
        }
    }
}
