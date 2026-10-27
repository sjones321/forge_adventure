package forge.app;

import forge.ai.ComputerUtilMana;
import forge.card.mana.ManaAtom;
import forge.card.mana.ManaCost;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.cost.Cost;
import forge.game.cost.CostPartMana;
import forge.game.mana.Mana;
import forge.game.player.Player;
import forge.game.spellability.AbilityManaPart;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.trigger.Trigger;
import forge.game.GameEntity;
import forge.game.GameEntityView;
import forge.game.GameLog;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.combat.CombatView;
import forge.StaticData;
import forge.card.CardType;
import forge.card.CardTypeView;
import forge.card.ICardFace;
import forge.game.card.CardFaceView;
import forge.game.card.CardView;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordCollectionView;
import forge.game.keyword.KeywordView;
import forge.game.phase.PhaseType;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.game.player.DelayedReveal;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.trackable.TrackableTypes;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.util.ITriggerEvent;
import forge.gamemodes.net.NetworkGuiGame;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.match.YieldUpdate;
import forge.gamemodes.net.NetworkLogConfig;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.client.NetGameController;
import forge.gamemodes.net.event.MessageEvent;
import forge.gamemodes.net.event.UpdateLobbyPlayerEvent;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.util.URLValidator;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputAttack;
import forge.gamemodes.match.input.InputBlock;
import forge.gamemodes.match.input.InputLockUI;
import forge.gamemodes.match.input.InputPassPriority;
import forge.gamemodes.match.input.InputSelectEntitiesFromList;
import forge.gamemodes.match.input.InputSelectManyBase;
import forge.gamemodes.match.input.InputSelectTargets;
import forge.gui.GuiBase;
import forge.gui.download.GuiDownloadService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.sound.IAudioClip;
import forge.sound.IAudioMusic;
import forge.util.FSerializableFunction;
import forge.util.ImageFetcher;
import forge.util.collect.FCollectionView;
import org.jupnp.UpnpServiceConfiguration;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * One real Forge duel drawn by the battlefield window.
 * The game thread blocks on choices. The window answers on the render thread.
 */
public final class BattlefieldLive {
    private static final ISkinImage BLANK_SKIN = new ISkinImage() { };

    volatile String status = "Loading Forge cards. This takes a minute.";
    volatile boolean failed;
    private volatile boolean open;
    private volatile boolean stopping;
    private volatile HostedMatch match;
    private volatile Thread gameThread;
    private final Object matchGate = new Object();
    private volatile MatchGui gui = new MatchGui();
    /** The host's match GUI. Forge asks GuiBase for it when the local seat is created. */
    private static volatile IGuiGame netGui;
    /** Forge's stock duel port. No join password on this protocol. */
    private static final int DUEL_PORT = 36743;
    private volatile boolean weHosted;
    private volatile boolean netSession;
    private volatile FGameClient netClient;
    private volatile ServerGameLobby serverLobby;
    private volatile ClientGameLobby clientLobby;
    private volatile int clientSlot = -1;
    private volatile String lobbyLan = "";
    private volatile String loggedRules;
    private volatile LobbyCard lobbyCard;
    private final AtomicBoolean netLaunched = new AtomicBoolean();
    /** Bumped on Host, Join, and Back. A stale lobby thread must not bind or paint over the new one. */
    private final AtomicInteger netGen = new AtomicInteger();
    /** Session that currently owns port 36743. Touched only while holding {@link #matchGate}. */
    private int boundGen;

    /**
     * What the lobby paints. The paint thread only reads this.
     * Listen, accept, deck sync, and match build write it from other threads.
     */
    static final class LobbyCard {
        final boolean hostSide;
        final boolean canStart;
        final boolean starting;
        final String title;
        final String address;
        final String lan;
        final String wait;
        final String youName;
        final String youDeck;
        final String themName;
        final String themDeck;
        final String problem;

        LobbyCard(boolean hostSide, boolean canStart, boolean starting, String title, String address,
                String lan, String wait, String youName, String youDeck, String themName, String themDeck,
                String problem) {
            this.hostSide = hostSide;
            this.canStart = canStart;
            this.starting = starting;
            this.title = title == null ? "" : title;
            this.address = address == null ? "" : address;
            this.lan = lan == null ? "" : lan;
            this.wait = wait == null ? "" : wait;
            this.youName = youName == null ? "" : youName;
            this.youDeck = youDeck == null ? "" : youDeck;
            this.themName = themName == null ? "" : themName;
            this.themDeck = themDeck == null ? "" : themDeck;
            this.problem = problem == null ? "" : problem;
        }

        LobbyCard withStarting() {
            return new LobbyCard(hostSide, false, true, title, address, lan,
                    "Starting the match.", youName, youDeck, themName, themDeck, problem);
        }

        LobbyCard withLan(String ip) {
            return new LobbyCard(hostSide, canStart, starting, title, address, ip,
                    wait, youName, youDeck, themName, themDeck, problem);
        }
    }
    /** Name typed on this window's picker. Applied before Host bind / Join connect. */
    private volatile String chosenName = "";
    private volatile BattlefieldDecks.Choice youChoice = new BattlefieldDecks.Choice(
            BattlefieldDecks.Kind.GREEN_TEST, BattlefieldDecks.GREEN_LABEL, null);
    private volatile BattlefieldDecks.Choice ashChoice = new BattlefieldDecks.Choice(
            BattlefieldDecks.Kind.ASH_TEST, BattlefieldDecks.ASH_LABEL, null);
    private volatile Choice choice;
    /** Last picture-list reveal. Cascade / discover may-cast reuses this pile. */
    private volatile List<CardView> lastReveal = List.of();
    /** Pass priority until the stack is empty, then stop at the next choice. */
    private volatile boolean resolveAll;
    /**
     * After a fetch is paid, Forge asks "you cannot respond" before the search.
     * 0 = not passed, 1 = yield accepted, 2 = that priority was passed.
     * The search itself is a card sheet and is never answered here.
     */
    private int stackPassForId;
    private int stackPassStep;
    private long stackVisibleAt;
    private final Object stackPassLock = new Object();
    /**
     * Distinct priority/prompt already auto-passed. Paint may check every frame;
     * a second fire is a no-op until the key changes.
     */
    private String emptyPassLatch = "";
    private boolean emptyPassSent;
    /** Full-state resync landed; wait for the next prompt before empty auto-pass. */
    private volatile boolean resyncAwaitPrompt;
    /** Last picture() was still toss / keep / mulligan. Leaving that window resets the latch. */
    private boolean lastPregame = true;
    /** Toss Play/Draw already sent. A second OK must not flip it to Draw or Keep. */
    private boolean playOrDrawSent;
    /** Last GameView hand id set per seat. Any change (mulligan, draw, land, discard) wipes leftovers. */
    private String lastYourHandKey = "";
    private String lastTheirHandKey = "";
    private boolean pendingHandWipe;
    /** Full setGameView after a checksum resync. Next picture rebuilds boards from that view. */
    private volatile boolean pendingBoardRebuild;
    private final Object deathBeatLock = new Object();
    private volatile boolean deathBeatSeen;
    /** One card click at a time. The click must not run on the thread that paints the window. */
    private final AtomicBoolean cardSelectBusy = new AtomicBoolean();
    private final AtomicBoolean stackPassBusy = new AtomicBoolean();
    /** X announced through Forge before mana is paid. Null unless this spell has an X to choose. */
    private volatile XAsk xAsk;
    /**
     * Spells Forge already resolved still stay on screen this long, so an auto-pass is visible.
     * A spell that became a battlefield permanent is dropped immediately so the huge card
     * and the new tile do not overlap.
     */
    private static final long STACK_HOLD_MS = 1200L;
    /** Readable beat before auto-pass when the stack is up and he cannot respond. */
    private static final long STACK_READ_MS = 500L;
    /** Left-window line after a search took the only legal printed name. */
    private volatile String searchAutoNote;
    private volatile long searchAutoNoteUntil;
    /** One auto-take per Forge list-select input. */
    private volatile String listAutoSig;
    /** Cause on screen, then smoke, then the commander yes/no. */
    private static final long DEATH_BEAT_MS = 520L;
    /** After Yes/No, the next picture() writes where Forge actually put the commander. */
    private volatile String commanderChoiceNote;
    private final List<HeldSpell> heldSpells = new ArrayList<>();
    private final List<Strike> combatStrikes = new ArrayList<>();
    /**
     * True yields: red, the game does not wait. False stops: green, the game holds.
     * Your opening stops match the steps the duel already held. A save file replaces both.
     */
    private final boolean[] yourYield = BattlefieldLayout.openingYields(BattlefieldLayout.StripOwner.YOU);
    private final boolean[] enemyYield = BattlefieldLayout.openingYields(BattlefieldLayout.StripOwner.ENEMY);
    private final Object yieldLock = new Object();
    /** Join already sent this seat's strip to the host. Enemy / Ash prefs stay local. */
    private volatile boolean seatStopsSeeded;
    /** Same turn and step already paused once, so the second skip check does not pause again. */
    private int stepDwellKey = Integer.MIN_VALUE;
    /** Original Forge log line to a short UTF-8 line that keeps the real target name. */
    private final Map<String, String> cleanLog = new LinkedHashMap<>();
    /** Opening seven already sorted, keyed by player id and the set of card ids. */
    private final Map<Integer, String> openingLandSort = new HashMap<>();
    /**
     * Order to paint until the joiner's own hand view catches up.
     * A drag reorder clears it. Later draws are a different set of cards, so this does not apply.
     */
    private int paintedPlayer = Integer.MIN_VALUE;
    private String paintedKey = "";
    private int[] paintedIds = new int[0];
    /** True while the opening sort is sending reorder calls, so those do not count as a drag. */
    private boolean sortingHand;
    /**
     * Free mulligans beyond the Commander/Brawl one.
     * Adventure rewards call {@link #grantFreeMulligans} before the duel starts.
     */
    private int extraFreeMulligans;
    /** Mulligans this seat already took. The host reads Forge's count; the joiner has no game object. */
    private int seenMulligans;
    /** Format of the deck this window sat down with. Commander and Brawl grant one free mulligan. */
    private volatile GameType seatedType;
    /** First life total seen for this match, used when the rules object is on the host only. */
    private int cachedYourStart;
    private int cachedTheirStart;
    /**
     * Ability he just confirmed from a permanent. Forge asks once more for extra costs.
     * That second ask must not open the confirm again.
     */
    private volatile SpellAbilityView confirmedAbility;
    private static final Set<String> ICON_WORDS = Set.of(
            "deathtouch", "defender", "double strike", "first strike", "flying",
            "hexproof", "shroud", "indestructible", "lifelink", "menace", "trample",
            "haste", "vigilance", "reach", "flash");
    private static final Pattern TARGET_ID = Pattern.compile("\\s*\\(\\d+\\)");
    private static final Pattern TARGET_ZONE = Pattern.compile(
            "\\b(?:Battlefield|Graveyard|Exile|Library|Command|Sideboard|Ante|Stack|Hand|Flashback)\\s+");
    private static final long ASH_STEP_DWELL_MS = 340L;
    /**
     * Forge's actionable cards for the priority window that is on screen.
     * A gap (no priority, or mulligan) retires the previous window so it cannot flash back on.
     */
    private volatile int playableEpoch;
    private volatile int playableGap = -1;
    private volatile Set<Integer> playableIds = Set.of();
    private volatile List<Face> playableGhosts = List.of();

    private void publishPlayable(Set<Integer> ids, List<Face> ghosts) {
        playableIds = ids;
        playableGhosts = ghosts;
        playableEpoch++;
    }

    /**
     * Snapshot the cards Forge already marked actionable for this priority window.
     * Mulligan and every other prompt publish an empty set. Enemy battlefield cards are never included.
     */
    private void publishPlayableFromPrompt(String message) {
        GameView view = gui.getGameView();
        boolean mulligan = view != null && view.isMulligan();
        String label = forge.util.Localizer.getInstance().getMessage("lblPriority");
        PlayerView you = gui.human;
        String name = you == null || you.getName() == null ? "" : you.getName();
        boolean yours = message != null && !name.isEmpty() && message.contains(label + ": " + name);
        if (!yours || mulligan) {
            publishPlayable(Set.of(), List.of());
            return;
        }
        Set<Integer> ids = new HashSet<>();
        List<Face> ghosts = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        markZone(you.getHand(), false, ids, ghosts, seen);
        markZone(you.getBattlefield(), false, ids, ghosts, seen);
        markZone(you.getFlashback(), true, ids, ghosts, seen);
        markZone(you.getGraveyard(), true, ids, ghosts, seen);
        markZone(you.getExile(), true, ids, ghosts, seen);
        markZone(you.getLibrary(), true, ids, ghosts, seen);
        markZone(you.getCommand(), true, ids, ghosts, seen);
        markZone(you.getSideboard(), true, ids, ghosts, seen);
        PlayerView them = gui.opponent;
        if (them != null) {
            markZone(them.getGraveyard(), true, ids, ghosts, seen);
            markZone(them.getExile(), true, ids, ghosts, seen);
            markZone(them.getLibrary(), true, ids, ghosts, seen);
            markZone(them.getFlashback(), true, ids, ghosts, seen);
            markZone(them.getCommand(), true, ids, ghosts, seen);
        }
        publishPlayable(Set.copyOf(ids), List.copyOf(ghosts));
    }

    /** {@code ghost} cards are playable from somewhere other than the hand. Battlefield stays on the board. */
    private void markZone(FCollectionView<CardView> cards, boolean ghost,
            Set<Integer> ids, List<Face> ghosts, Set<Integer> seen) {
        if (cards == null) return;
        for (CardView card : cards) {
            if (card == null || !gui.isWeaklySelectable(card)) continue;
            int id = card.getId();
            if (!seen.add(id)) continue;
            ids.add(id);
            if (!ghost) continue;
            ZoneType zone = card.getZone();
            if (zone == ZoneType.Hand || zone == ZoneType.Battlefield) continue;
            List<Face> one = faces(List.of(card), false);
            if (!one.isEmpty()) ghosts.add(one.get(0));
        }
    }

    /** Starts a match with the two chosen decks. A previous match is closed first. */
    void begin(BattlefieldDecks.Choice yours, BattlefieldDecks.Choice ash) {
        if (yours != null) youChoice = yours;
        if (ash != null) ashChoice = ash;
        Thread worker = new Thread(() -> {
            synchronized (matchGate) {
                stopping = false;
                failed = false;
                open = false;
                netSession = false;
                lobbyCard = null;
                cachedYourStart = 0;
                cachedTheirStart = 0;
                seenMulligans = 0;
                clearOpeningSort();
                resetPregamePass();
                gui = new MatchGui();
                boot();
            }
        }, "Game boot");
        worker.setDaemon(true);
        gameThread = worker;
        worker.start();
    }

    /**
     * Leaves the current match so another pair of decks can start.
     * The window can show the picker immediately. Closing the old match finishes in the background.
     */
    void abandonMatch() {
        stopping = true;
        open = false;
        failed = false;
        Choice pending = choice;
        if (pending != null) pending.forceClose();
        XAsk pendingX = xAsk;
        if (pendingX != null) pendingX.forceClose();
        Thread worker = new Thread(() -> {
            synchronized (matchGate) {
                HostedMatch hosted = match;
                MatchGui old = gui;
                try {
                    forge.game.Game game = hosted == null ? null : hosted.getGame();
                    if (game != null && !game.isGameOver()) {
                        try {
                            IGameController controller = old.getGameController(old.human);
                            if (controller != null) controller.concede();
                        } catch (Throwable ignored) {
                            // The picker is already up. A stuck concede must not block the next Start.
                        }
                    }
                    if (hosted != null) {
                        try {
                            hosted.endCurrentGame();
                        } catch (Throwable ignored) {
                            // Same as concede: the next begin() waits on this lock, then starts clean.
                        }
                    }
                } finally {
                    match = null;
                    releaseNet();
                    if (gui == old) open = false;
                }
            }
        }, "end duel");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Forge game-state file under the isolated duel folder. Refuses any path outside that folder.
     */
    String saveMatch() {
        try {
            HostedMatch hosted = match;
            forge.game.Game game = hosted == null ? null : hosted.getGame();
            if (game == null) return "No Forge match is ready to save yet.";
            String homeProp = System.getProperty("forge.test.userDir");
            if (homeProp == null || homeProp.isBlank()) {
                return "Save refused: the isolated duel folder is not set.";
            }
            Path home = Path.of(homeProp).toAbsolutePath().normalize();
            Path games = Path.of(forge.localinstance.properties.ForgeConstants.USER_GAMES_DIR)
                    .toAbsolutePath().normalize();
            if (!games.startsWith(home)) {
                return "Save refused so it would not write outside the duel folder.";
            }
            java.nio.file.Files.createDirectories(games);
            forge.game.GameState state = new forge.game.GameState();
            state.initFromGame(game);
            Path file = games.resolve("GameSave.txt").toAbsolutePath().normalize();
            if (!file.startsWith(home)) {
                return "Save refused so it would not write outside the duel folder.";
            }
            java.nio.file.Files.writeString(file, state.toString());
            return "Saved the Forge match to games/GameSave.txt in the duel folder.";
        } catch (Throwable failed) {
            String why = failed.getMessage();
            if (why == null || why.isBlank()) why = failed.getClass().getSimpleName();
            return "Could not save the match: " + why;
        }
    }

    /** Unblocks a waiting choice and interrupts the match thread. Does not exit the process. */
    void shutdownMatch() {
        stopping = true;
        Choice pending = choice;
        if (pending != null) pending.forceClose();
        XAsk pendingX = xAsk;
        if (pendingX != null) pendingX.forceClose();
        Thread thread = gameThread;
        if (thread != null && thread.isAlive() && thread != Thread.currentThread()) thread.interrupt();
        releaseNet();
    }

    static void installBase() {
        if (GuiBase.getInterface() == null) {
            GuiBase.setInterface(new Base());
        }
    }

    /**
     * forge-gui next to the unzip folder or the repo root, not next to javac's -d folder.
     * A compile-dir cwd used to make CardReader look in zone-scroll-classes.
     */
    private static Path forgeGuiDir() {
        List<Path> starts = new ArrayList<>();
        addStart(starts, Path.of(System.getProperty("user.dir", ".")));
        String userHome = System.getProperty("forge.test.userDir", "");
        if (userHome != null && !userHome.isBlank()) {
            Path user = Path.of(userHome).toAbsolutePath().normalize();
            addStart(starts, user);
            if (user.getParent() != null) addStart(starts, user.getParent());
        }
        addStart(starts, codeDir());
        addStart(starts, Path.of("A:/forge_adventure"));
        for (Path start : starts) {
            Path dir = start.toAbsolutePath().normalize();
            for (int i = 0; i < 8 && dir != null; i++) {
                Path folder = dir.resolve("forge-gui");
                if (Files.isDirectory(folder.resolve(Path.of("res", "cardsfolder")))) {
                    return folder;
                }
                dir = dir.getParent();
            }
        }
        return Path.of("A:/forge_adventure/forge-gui").toAbsolutePath();
    }

    private static void addStart(List<Path> starts, Path start) {
        if (start != null) starts.add(start);
    }

    /** Folder that holds the running jar, or the compile classes directory. */
    private static Path codeDir() {
        try {
            java.net.URL where = BattlefieldLive.class.getProtectionDomain().getCodeSource().getLocation();
            if (where == null) return Path.of(".");
            Path path = Path.of(where.toURI());
            if (Files.isRegularFile(path)) {
                Path parent = path.getParent();
                return parent == null ? Path.of(".") : parent;
            }
            return path;
        } catch (Exception ignored) {
            return Path.of(".");
        }
    }

    boolean ready() {
        if (!open || failed || gui == null) return false;
        GameView view = gui.getGameView();
        if (view == null) return false;
        if (!netSession) return true;
        // Play or Draw still has no hand, so the lobby stays. The dealt hand is on the
        // game-view player, not the openView copy, so rebind before testing the hand.
        gui.assignPlayers();
        PlayerView you = gui.human;
        if (you == null || you.getHand() == null || you.getHand().isEmpty()) return false;
        return view.getPlayers() != null && view.getPlayers().size() >= 2;
    }

    /**
     * Keep, Mulligan, or the London bottom pick is waiting on this seat.
     * The other window must not treat that as its own question.
     */
    boolean answeringMulligan() {
        if (gui == null || (!gui.okEnabled && !gui.cancelEnabled)) return false;
        String cancel = gui.cancelLabel == null ? "" : gui.cancelLabel;
        return "Mulligan".equals(cancel) || "Auto".equals(cancel);
    }

    /**
     * Hands are dealt and this seat is not the one holding Keep or Mulligan.
     * The duel is up. The line says the other player is still deciding.
     */
    boolean otherMulligan() {
        if (answeringMulligan()) return false;
        GameView view = gui == null ? null : gui.getGameView();
        if (view == null || view.isGameOver() || view.getPhase() != null) return false;
        int turn;
        try {
            turn = view.getTurn();
        } catch (RuntimeException ignored) {
            return false;
        }
        if (turn > 0) return false;
        PlayerView you = gui.human;
        return you != null && you.getHand() != null && !you.getHand().isEmpty();
    }

    LobbyCard lobby() {
        return lobbyCard;
    }

    /**
     * Play or Draw, or any other button prompt, that arrives before the hand is dealt.
     * The lobby is still on screen then. The paint thread reads this and keeps drawing.
     */
    boolean lobbyPrompt() {
        if (gui == null || failed) return false;
        if (gui.getGameView() == null) return false;
        if (ready()) return false;
        return gui.okEnabled || gui.cancelEnabled;
    }

    /** Coin-toss question, with Forge's line breaks flattened for the lobby title. */
    String lobbyPromptLine() {
        String text = gui == null || gui.prompt == null ? "" : gui.prompt;
        text = text.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").trim();
        return text;
    }

    /**
     * The match has opened and the hand is not in the picture yet.
     * The other window may be the one holding Play or Draw.
     */
    boolean matchOpening() {
        if (gui == null || failed || !netSession) return false;
        if (gui.getGameView() == null) return false;
        return !ready();
    }

    boolean choiceOpen() {
        return choice != null;
    }

    /** Declare Attackers is open. A creature click toggles an attack, not an ability. */
    boolean attackingNow() {
        try {
            if (humanInput() instanceof InputAttack) return true;
        } catch (RuntimeException ignored) {
        }
        return gui != null && gui.isCombatDeclareAttackersInput();
    }

    /** Declare Blockers is open. A click must not start an ability. */
    boolean blockingNow() {
        try {
            if (humanInput() instanceof InputBlock) return true;
        } catch (RuntimeException ignored) {
        }
        return gui != null && gui.isCombatDeclareBlockersInput();
    }

    /** Forge's own alpha strike: every legal creature attacks. */
    void alphaStrike() {
        PlayerControllerHuman human = humanController();
        if (human != null) human.alphaStrike();
    }

    /**
     * Adds free mulligans for the human. The format default is unchanged.
     * Call before the match starts so London copies the count when the hand is dealt.
     */
    public void grantFreeMulligans(int more) {
        if (more <= 0) return;
        extraFreeMulligans += more;
    }

    /** Format default. Commander and Brawl are 1. Constructed is 0. */
    public static int formatFreeMulligans(GameType type) {
        return BattlefieldDecks.formatFreeMulligans(type);
    }

    /** Format default plus anything {@link #grantFreeMulligans} added. */
    public int freeMulliganCount(GameType type) {
        return formatFreeMulligans(type) + Math.max(0, extraFreeMulligans);
    }

    /**
     * Pass priority for the rest of this turn.
     * On a priority stop this is Forge's End Turn button, which passes and then yields.
     * Setting the yield first disables OK, so the pass must happen before that rewrite.
     * The joiner has no local InputPassPriority or PlayerControllerHuman — the yield
     * goes through {@link #seatController()} like Resolve / Pass.
     */
    void endTurn() {
        Input input = humanInput();
        if (input instanceof InputPassPriority) {
            pressCancel();
            return;
        }
        pressOk();
        IGameController controller = seatController();
        PlayerView self = gui == null ? null : gui.human;
        if (controller != null && self != null) {
            controller.sendYieldUpdate(new YieldUpdate.SetAutoPassUntilEndOfTurn(self, true));
            return;
        }
        PlayerControllerHuman human = humanController();
        if (human != null) human.autoPassUntilEndOfTurn();
    }

    /**
     * Keep-prompt line. The first free mulligans bottom nothing.
     * After those, London bottoms one more card each time.
     */
    String mulliganLine() {
        forge.game.Game game = match == null ? null : match.getGame();
        if (game != null && gui.human != null) {
            Player you = null;
            for (Player player : game.getPlayers()) {
                if (player != null && player.getId() == gui.human.getId()) you = player;
            }
            if (you != null) {
                int free = you.getFreeMulligans();
                if (formatGrantsFreeMulligan(game)) free += 1;
                int already = you.getStats().getMulliganCount();
                return mulliganNotice(already, free);
            }
        }
        if (!answeringMulligan()) return null;
        GameType type = seatedType == null ? GameType.Constructed : seatedType;
        int free = formatFreeMulligans(type) + Math.max(0, extraFreeMulligans);
        return mulliganNotice(seenMulligans, free);
    }

    /** Same test MulliganService uses for the one format free mulligan. */
    private static boolean formatGrantsFreeMulligan(forge.game.Game game) {
        GameType kind = game.getRules().getGameType();
        return game.getPlayers().size() > 2
                || kind == GameType.Commander
                || kind == GameType.Brawl
                || game.getRules().hasAppliedVariant(GameType.Commander)
                || game.getRules().hasAppliedVariant(GameType.Brawl);
    }

    /** What this mulligan costs, and what the one after it will cost. */
    static String mulliganNotice(int alreadyMulliganed, int free) {
        int cost = Math.max(0, alreadyMulliganed + 1 - Math.max(0, free));
        int next = Math.max(0, alreadyMulliganed + 2 - Math.max(0, free));
        String now = cost == 0
                ? "This mulligan is free."
                : "This mulligan bottoms " + cardCount(cost) + ".";
        String later = next == 0
                ? "The next one is free."
                : "The next one bottoms " + cardCount(next) + ".";
        return now + " " + later;
    }

    private static String cardCount(int n) {
        return n == 1 ? "1 card" : n + " cards";
    }

    /** A battlefield ability he can legally activate. Unpayable ones stay closed. */
    private boolean payableBattlefieldAbility(CardView card) {
        if (card == null || card.getZone() != ZoneType.Battlefield) return true;
        PlayerControllerHuman human = humanController();
        if (human == null) return false;
        Card gameCard = human.getCard(card);
        Player you = human.getPlayer();
        if (gameCard == null || you == null) return false;
        for (SpellAbility ability : gameCard.getAllPossibleAbilities(you, true)) {
            if (ability == null || ability.isSpell() || ability.isTrigger()) continue;
            if (ability.canPlay()) return true;
        }
        return false;
    }

    /**
     * The spell or land he is paying for right now. Null while he is not
     * in InputPayMana. Used so the table can keep that card picture up.
     */
    CardView payingCard() {
        try {
            PlayerControllerHuman human = humanController();
            Player you = human == null ? null : human.getPlayer();
            if (you == null) return null;
            SpellAbility paid = you.getPaidForSA();
            if (paid == null || paid.getHostCard() == null) return null;
            Card host = paid.getHostCard();
            if (host.isInZone(ZoneType.Battlefield) && !paid.isSpell() && !paid.isLandAbility()) {
                return null;
            }
            return host.getView();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Forge is waiting for him to click a target. Not mana, not priority. */
    boolean choosingTarget() {
        try {
            return humanInput() instanceof InputSelectTargets;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** A battlefield card Forge will accept for the current target ask. */
    boolean legalTarget(CardView card) {
        return card != null && gui.isSelectable(card);
    }

    /** A player Forge will accept for the current target ask. */
    boolean legalPlayerTarget(boolean you) {
        if (!choosingTarget()) return false;
        PlayerView view = you ? gui.human : gui.opponent;
        if (view == null) return false;
        try {
            Input input = humanInput();
            if (!(input instanceof InputSelectTargets)) return false;
            PlayerControllerHuman human = humanController();
            if (human == null || human.getGame() == null) return promptAllowsPlayerTarget();
            Player player = null;
            for (Player seat : human.getGame().getPlayers()) {
                if (seat != null && seat.getId() == view.getId()) {
                    player = seat;
                    break;
                }
            }
            if (player == null || player.hasLost()) return false;
            java.lang.reflect.Field saField = InputSelectTargets.class.getDeclaredField("sa");
            saField.setAccessible(true);
            SpellAbility sa = (SpellAbility) saField.get(input);
            if (sa == null || !sa.canTarget(player)) return false;
            java.lang.reflect.Field mustField = InputSelectTargets.class.getDeclaredField("mustTargetFiltered");
            mustField.setAccessible(true);
            if (mustField.getBoolean(input)) return false;
            java.lang.reflect.Field filterField = InputSelectTargets.class.getDeclaredField("filter");
            filterField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.function.Predicate<Object> filter =
                    (java.util.function.Predicate<Object>) filterField.get(input);
            return filter == null || filter.test(player);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return promptAllowsPlayerTarget();
        }
    }

    private boolean promptAllowsPlayerTarget() {
        String prompt = gui.prompt == null ? "" : gui.prompt.toLowerCase(Locale.ROOT);
        return prompt.contains("player") || prompt.contains("opponent") || prompt.contains("any target");
    }

    /** Host name from Forge's target sentence, before the dash. */
    String targetSpellName() {
        String prompt = gui.prompt == null ? "" : gui.prompt.trim();
        int dash = prompt.indexOf(" - ");
        String name = dash > 0 ? prompt.substring(0, dash) : prompt;
        int line = name.indexOf('\n');
        if (line >= 0) name = name.substring(0, line);
        name = name.trim();
        if (name.isEmpty() || name.equalsIgnoreCase("choose a target")) return "";
        return name;
    }

    /** Yes/no and other word lists. Card pictures and mode lines use the left window instead. */
    boolean textChoiceOpen() {
        Choice pending = choice;
        return pending != null && (pending.kind == Choice.Kind.LINES || pending.facePick);
    }

    /**
     * Choose-a-type line list (Cavern, Conspiracy, Cover of Darkness, Changeling
     * tribal). Not Yes/No and not a hardcoded card name.
     */
    boolean typeChoiceOpen() {
        Choice pending = choice;
        return pending != null && pending.typeList && !pending.facePick;
    }

    /** Which face of a DFC/MDFC to play. Pictures, not a name-only list. */
    boolean facePickOpen() {
        Choice pending = choice;
        return pending != null && pending.facePick;
    }

    List<FacePick> choiceFaces() {
        Choice pending = choice;
        if (pending == null || !pending.facePick) return List.of();
        return List.copyOf(pending.faces);
    }

    /** Revealed cards, including a Forge list-select input that is already waiting. */
    boolean cardSheetOpen() {
        Choice pending = choice;
        if (pending != null) return pending.kind == Choice.Kind.CARDS
                || pending.kind == Choice.Kind.ORDER
                || pending.kind == Choice.Kind.AMOUNT
                || (pending.kind == Choice.Kind.MODES && pending.glance != null && !pending.glance.isEmpty());
        return listSheetActive();
    }

    /**
     * A pick from pictures (library search, Genesis Wave, and the same sheet).
     * Not an order or amount row. The hand fan hides so the sweepable row can use that strip.
     */
    boolean pickSheetOpen() {
        Choice pending = choice;
        if (pending != null) return pending.kind == Choice.Kind.CARDS;
        return listSheetActive();
    }

    /** Minus and plus under each recipient. OK stays dim until the numbers add up. */
    boolean amountOpen() {
        Choice pending = choice;
        return pending != null && pending.amounting;
    }

    void nudgeAmount(int index, int dir) {
        Choice pending = choice;
        if (pending != null) pending.nudgeAmount(index, dir);
    }

    /** The order row. Drag changes the list. A click does not send a card to the front. */
    boolean orderSheet() {
        Choice pending = choice;
        return pending != null && pending.ordering;
    }

    /** Scry/surveil picture row. Left of the split stays on top; a click or a zone pick sets bottom. */
    boolean placingSheet() {
        Choice pending = choice;
        return pending != null && pending.placing;
    }

    /** Grow the top pile, or for scry 1 set the card on top. */
    void placeOnTop() {
        Choice pending = choice;
        if (pending == null || !pending.placing) return;
        pending.placeTowardTop();
    }

    /** Grow the bottom pile, or for scry 1 set the card on the bottom. */
    void placeOnBottom() {
        Choice pending = choice;
        if (pending == null || !pending.placing) return;
        pending.placeTowardBottom();
    }

    /**
     * Move the card at visual slot {@code from} to visual slot {@code to}.
     * Slot 0 is the left end, which is the top of the list OK returns to Forge.
     */
    void moveOrder(int from, int to) {
        Choice pending = choice;
        if (pending == null || !pending.ordering) return;
        int n = pending.picked.size();
        if (from < 0 || from >= n || to < 0 || to >= n || from == to) return;
        int[] next = HandLayout.movedOrder(n, from, to);
        List<Integer> rearranged = new ArrayList<>();
        for (int slot : next) rearranged.add(pending.picked.get(slot));
        pending.picked.clear();
        pending.picked.addAll(rearranged);
    }

    boolean modeSheetOpen() {
        Choice pending = choice;
        return pending != null && pending.kind == Choice.Kind.MODES && !pending.facePick;
    }

    /**
     * Card list, mode lines, or a Forge list-select input that owns Confirm / Cancel.
     * Library search, scry/surveil, Genesis Wave, and Company use this, not Next.
     */
    boolean blockingChoiceSheet() {
        return modeSheetOpen() || pickSheetOpen() || facePickOpen()
                || (choice != null && (choice.kind == Choice.Kind.CARDS
                || choice.kind == Choice.Kind.ORDER
                || choice.kind == Choice.Kind.AMOUNT));
    }

    String sheetTitle() {
        Choice pending = choice;
        if (pending != null && pending.message != null && !pending.message.isBlank()) return pending.message;
        return presentChoice(gui.prompt);
    }

    List<String> modeLines() {
        Choice pending = choice;
        if (pending == null || pending.kind != Choice.Kind.MODES) return List.of();
        return List.copyOf(pending.labels);
    }

    List<Integer> modePicked() {
        Choice pending = choice;
        if (pending == null || pending.kind != Choice.Kind.MODES) return List.of();
        return List.copyOf(pending.picked);
    }

    boolean xOpen() {
        return xAsk != null;
    }

    /** The X Forge will be told, from Max, Max-N, or Max-counter. */
    int xAmount() {
        XAsk pending = xAsk;
        return pending == null ? 0 : pending.announced();
    }

    /** Mana held back for Max-N. Zero until he raises it, so Max and Max-0 match. */
    int xHold() {
        XAsk pending = xAsk;
        return pending == null ? 0 : pending.hold;
    }

    /** 0 Max, 1 Max-N, 2 Max-counter. */
    int xMode() {
        XAsk pending = xAsk;
        return pending == null ? 0 : pending.mode;
    }

    void xModeMax() {
        XAsk pending = xAsk;
        if (pending != null) pending.useMax();
    }

    void xModeCounter() {
        XAsk pending = xAsk;
        if (pending != null) pending.useCounter();
    }

    void xNudge(int dir) {
        XAsk pending = xAsk;
        if (pending != null) pending.nudge(dir);
    }

    void xSubmit() {
        XAsk pending = xAsk;
        if (pending != null) pending.finish(false);
    }

    void xCancel() {
        XAsk pending = xAsk;
        if (pending != null) pending.finish(true);
    }

    String okText() {
        if (blockingChoiceSheet()) return "OK";
        String label = gui.okLabel;
        return label == null || label.isBlank() ? "OK" : label;
    }

    /**
     * Confirm is only for a variable pick: up to N, two or more, order,
     * amounts, reveal, scry piles, X, or targeting. A single option submits
     * on the click that chose it.
     */
    boolean choiceNeedsConfirm() {
        if (xAsk != null) return true;
        if (discardAsk(gui == null ? null : gui.prompt)) return true;
        Choice pending = choice;
        if (pending == null) {
            if (choosingTarget()) return true;
            if (listSheetActive()) {
                InputSelectEntitiesFromList<?> list = listInput();
                return list != null && (listMax(list) > 1 || listMin(list) > 1 || discardList(list));
            }
            return pickSheetOpen();
        }
        if (pending.mayCast || pending.mayReveal) return false;
        if (pending.destinationSplit && pending.max <= 1) return false;
        if (pending.revealOnly || pending.ordering || pending.amounting || pending.placing) return true;
        if (pending.holdForOk) return true;
        return pending.max > 1;
    }

    String actionWord() {
        Choice pending = choice;
        if (pending != null && pending.mayReveal) return "Reveal";
        if (pending != null && pending.mayCast) return "Cast";
        if (pending != null && pending.revealOnly) return "OK";
        if (choiceNeedsConfirm()) {
            if (pending != null && abilitySheet(pending) && !pending.facePick) return "Activate";
            if (choosingTarget() || pickSheetOpen() || cardSheetOpen() || modeSheetOpen()
                    || facePickOpen() || pending != null || xAsk != null) {
                return "Confirm";
            }
        }
        if (attackingNow()) return someoneDeclaredAttack() ? "Confirm attack" : "Next";
        if (blockingNow()) return someoneDeclaredBlock() ? "Confirm blocks" : "Next";
        String raw = okText();
        if ("auto".equalsIgnoreCase(raw)) return "Autopay";
        if (plainOk(raw)) {
            GameView view = gui.getGameView();
            if (view != null && view.isMulligan()) return "Confirm";
            if (realStackPending() || !hisTurn(view)) return "Pass";
            return "Next";
        }
        return raw;
    }

    private static boolean plainOk(String label) {
        return label == null || label.isBlank() || "ok".equalsIgnoreCase(label);
    }

    private static boolean abilitySheet(Choice pending) {
        if (pending.kind != Choice.Kind.MODES) return false;
        String msg = pending.message == null ? "" : pending.message.toLowerCase(Locale.ROOT);
        return msg.contains("abilit");
    }

    private boolean hisTurn(GameView view) {
        if (view == null || gui.human == null || view.getPlayerTurn() == null) return false;
        return view.getPlayerTurn().getId() == gui.human.getId();
    }

    private boolean someoneDeclaredAttack() {
        PlayerView you = gui.human;
        if (you == null || you.getBattlefield() == null) return false;
        try {
            for (CardView card : you.getBattlefield()) {
                if (card != null && card.isAttacking()) return true;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private boolean someoneDeclaredBlock() {
        try {
            GameView view = gui.getGameView();
            CombatView combat = view == null ? null : view.getCombat();
            if (combat == null || combat.getAttackers() == null) return false;
            for (CardView attacker : combat.getAttackers()) {
                if (attacker == null) continue;
                if (hasAny(combat.getPlannedBlockers(attacker)) || hasAny(combat.getBlockers(attacker))) return true;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    private static boolean hasAny(Iterable<CardView> cards) {
        if (cards == null) return false;
        for (CardView card : cards) {
            if (card != null) return true;
        }
        return false;
    }

    String cancelText() {
        Choice pending = choice;
        if (pending != null && (pending.mayCast || pending.mayReveal)) return "Don't";
        if (blockingChoiceSheet() || pickSheetOpen()) {
            String label = gui.cancelLabel;
            if (label != null && "decline".equalsIgnoreCase(label.trim())) return "Decline";
            return "Don't".equalsIgnoreCase(label) ? "Don't" : "Cancel";
        }
        String label = gui.cancelLabel;
        return label == null || label.isBlank() ? "Cancel" : label;
    }

    boolean okOn() {
        Choice pending = choice;
        if (pending != null) return pending.okReady();
        if (listSheetActive()) return listHasEnough();
        return gui.okEnabled;
    }

    boolean cancelOn() {
        Choice pending = choice;
        if (pending != null && (pending.mayCast || pending.mayReveal)) return true;
        if (pending != null && pending.cancelBlocked) return false;
        if (pending != null && pending.allowCancel) return true;
        if (pending != null && pending.kind != Choice.Kind.LINES) return pending.min <= 0;
        if (listSheetActive()) return listOptional() || gui.cancelEnabled;
        return gui.cancelEnabled;
    }

    /** Click a revealed card. Legal cards toggle. A list-select input hears Forge's own select. */
    void pressSheet(int index) {
        Choice pending = choice;
        if (pending != null) {
            if (pending.kind == Choice.Kind.CARDS) pending.toggle(index);
            return;
        }
        InputSelectEntitiesFromList<?> list = listInput();
        if (list == null || index < 0) return;
        int at = 0;
        for (GameEntity entity : list.getValidChoices()) {
            if (!(entity instanceof Card card)) continue;
            if (at == index) {
                boolean oneShot = !discardAsk(gui == null ? null : gui.prompt)
                        && !discardList(list)
                        && listMax(list) <= 1 && listMin(list) <= 1;
                if (oneShot) selectThenOk(card.getView());
                else select(card.getView());
                return;
            }
            at++;
        }
    }

    void pressMode(int index) {
        Choice pending = choice;
        if (pending != null && pending.kind == Choice.Kind.MODES) pending.toggle(index);
    }

    /**
     * Stack is waiting and this window can pass. Resolve All sits with OK and Pass.
     * A target, mode, card, mana, or ability sheet hides it.
     */
    boolean resolveAllVisible() {
        if (choice != null || xAsk != null || pickSheetOpen()) return false;
        if (!realStackPending()) return false;
        Input input = humanInput();
        if (blocksResolveAll(input)) return false;
        if (input instanceof InputPassPriority) return true;
        PlayerView you = gui.human;
        return you != null && you.getHasPriority();
    }

    /**
     * One click. Forge passes his priority until the top object resolves, then keeps going.
     * A choice clears it. Triggers that need nothing still resolve on their own.
     */
    void pressResolveAll() {
        if (!resolveAllVisible()) return;
        resolveAll = true;
        PlayerView self = gui.human;
        IGameController controller = seatController();
        if (self == null || controller == null) return;
        controller.sendYieldUpdate(new YieldUpdate.StackYield(self, true, false));
    }

    /**
     * Confirm, Pass, Next, or Resolve. The joiner has no local InputPassPriority,
     * so this always goes through {@link #seatController()} — NetGameController
     * on Join, the local human controller on Host. Do not call passPriority() here.
     */
    void pressOk() {
        Choice pending = choice;
        if (pending != null) {
            pending.submit();
            return;
        }
        if (listSheetActive() && !okOn()) return;
        if (listSheetActive() && !listHasEnough()) return;
        if (answeringMulligan() || playOrDrawPrompt()) noteKeepOrLeavePregame();
        submitSeatOk();
    }

    /** Same OK the empty-UP joiner auto-pass already sends over the match connection. */
    private void submitSeatOk() {
        IGameController controller = seatController();
        if (controller == null) return;
        if (playOrDrawPrompt()) {
            if (playOrDrawSent) return;
            playOrDrawSent = true;
        }
        if (!(gui.okEnabled || listSheetActive() || priorityWaiting())) return;
        controller.selectButtonOk();
    }

    void pressCancel() {
        Choice pending = choice;
        if (pending != null) {
            pending.cancel();
            return;
        }
        IGameController controller = seatController();
        if (controller == null) return;
        if (gui.cancelEnabled || (listSheetActive() && listOptional())) {
            if ("Mulligan".equals(gui.cancelLabel)) {
                seenMulligans++;
                noteMulliganTaken();
            }
            controller.selectButtonCancel();
        }
    }

    /**
     * Declare this block through Forge's blocker input.
     * The attacker is selected first, then the blocker, so a short click cannot
     * land on whatever attacker Forge auto-highlighted.
     * An illegal block comes back false and is not kept. A block already on a
     * different attacker is lifted first and put back if the new one is rejected.
     */
    void assignBlock(CardView blocker, CardView attacker, CardView previous) {
        if (blocker == null || attacker == null || choice != null) return;
        if (previous != null && previous.getId() == attacker.getId()) return;
        IGameController controller = seatController();
        if (controller == null) return;
        if (!cardSelectBusy.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                if (previous != null) controller.selectCard(blocker, null, RIGHT_CLICK);
                boolean aimed = controller.selectCard(attacker, null, LEFT_CLICK);
                boolean assigned = aimed && controller.selectCard(blocker, null, LEFT_CLICK);
                if (!assigned && previous != null && controller.selectCard(previous, null, LEFT_CLICK)) {
                    controller.selectCard(blocker, null, LEFT_CLICK);
                }
            } catch (Throwable failed) {
                System.err.println("Block assign failed: " + failed);
            } finally {
                cardSelectBusy.set(false);
            }
        }, "assign block");
        worker.setDaemon(true);
        worker.start();
    }

    /** Counts from the player's real pool. A missing view is an empty pool, not invented mana. */
    private static int[] poolOf(PlayerView player) {
        int[] pool = new int[ManaAtom.MANATYPES.length];
        if (player == null) return pool;
        try {
            for (int i = 0; i < ManaAtom.MANATYPES.length && i < pool.length; i++) {
                pool[i] = Math.max(0, player.getMana(ManaAtom.MANATYPES[i]));
            }
        } catch (RuntimeException ignored) {
            java.util.Arrays.fill(pool, 0);
        }
        return pool;
    }

    /** Who blocks whom, from Forge's combat view. Planned blocks count before damage locks them. */
    private static void linkBlockers(Pic pic, CombatView combat) {
        if (pic == null || combat == null) return;
        Map<Integer, List<Integer>> links = new HashMap<>();
        for (CardView attacker : combat.getAttackers()) {
            if (attacker == null) continue;
            addBlockLinks(links, combat.getBlockers(attacker), attacker.getId());
            addBlockLinks(links, combat.getPlannedBlockers(attacker), attacker.getId());
        }
        stampBlockLinks(pic.yourBoard, links);
        stampBlockLinks(pic.theirBoard, links);
    }

    private static void addBlockLinks(Map<Integer, List<Integer>> links, Iterable<CardView> blockers, int attackerId) {
        if (blockers == null) return;
        for (CardView blocker : blockers) {
            if (blocker == null) continue;
            List<Integer> ids = links.computeIfAbsent(blocker.getId(), k -> new ArrayList<>());
            if (!ids.contains(attackerId)) ids.add(attackerId);
        }
    }

    private static void stampBlockLinks(List<Face> faces, Map<Integer, List<Integer>> links) {
        if (faces == null) return;
        for (Face face : faces) {
            if (face.view == null) continue;
            List<Integer> ids = links.get(face.view.getId());
            if (ids == null || ids.isEmpty()) continue;
            face.blockAttackerIds = new int[ids.size()];
            for (int i = 0; i < ids.size(); i++) face.blockAttackerIds[i] = ids.get(i);
            face.blocking = true;
        }
    }

    private static final ITriggerEvent LEFT_CLICK = new ClickButton(1);
    private static final ITriggerEvent RIGHT_CLICK = new ClickButton(3);

    /** Button id only. Forge's blocker input uses 3 to lift a block and 1 to assign one. */
    private static final class ClickButton implements ITriggerEvent {
        private final int button;

        ClickButton(int button) {
            this.button = button;
        }

        @Override
        public int getButton() {
            return button;
        }

        @Override
        public int getX() {
            return 0;
        }

        @Override
        public int getY() {
            return 0;
        }
    }

    void select(CardView card) {
        selectCard(card, false);
    }

    /**
     * A one-card list pick. Forge's select runs off the paint thread; OK has to
     * wait until that select has marked the card, or the search never closes.
     */
    private void selectThenOk(CardView card) {
        selectCard(card, true);
    }

    private void selectCard(CardView card, boolean submitWhenReady) {
        if (card == null || choice != null) return;
        IGameController controller = seatController();
        if (controller == null) return;
        // Ability confirm waits until he clicks. That wait has to sit off the render
        // thread, or the confirm never paints and the window stays frozen.
        if (!cardSelectBusy.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                Input input = humanInput();
                if (input instanceof InputAttack || input instanceof InputBlock
                        || input instanceof InputSelectTargets
                        || attackingNow() || blockingNow()) {
                    controller.selectCard(card, null, LEFT_CLICK);
                    return;
                }
                if (input instanceof InputPassPriority && !payableBattlefieldAbility(card)) return;
                if (!castFromOtherZone(card)) controller.selectCard(card, null, LEFT_CLICK);
                if (submitWhenReady && listSheetActive() && listHasEnough()) submitSeatOk();
            } catch (Throwable failed) {
                System.err.println("Card select failed: " + failed);
            } finally {
                cardSelectBusy.set(false);
            }
        }, "card select");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Grave, exile, and the other ghost zones. A click casts through Forge the same way
     * a hand card does: pick the legal spell (warp or the normal cost), pay, stack, enter.
     * It does not move the card onto the battlefield by itself. A target prompt still
     * uses the normal card click.
     */
    private boolean castFromOtherZone(CardView card) {
        ZoneType zone = card.getZone();
        if (zone == null || zone == ZoneType.Hand || zone == ZoneType.Battlefield) return false;
        if (!(humanInput() instanceof InputPassPriority)) return false;
        PlayerControllerHuman human = humanController();
        if (human == null || human.getInputProxy() == null) return false;
        Card gameCard = human.getCard(card);
        if (gameCard == null) return false;
        List<SpellAbility> spells = new ArrayList<>();
        for (SpellAbility sa : gameCard.getAllPossibleAbilities(human.getPlayer(), true)) {
            if (sa == null || !(sa.isSpell() || sa.isLandAbility())) continue;
            if (!sa.canPlay()) continue;
            String cost = sa.getCostDescription();
            String desc = sa.getDescription() == null ? "" : sa.getDescription();
            if (cost != null && !cost.isBlank() && !desc.contains(cost.trim())) {
                sa.setDescription(cost.trim() + " — " + (desc.isBlank() ? "Cast" : desc));
            }
            spells.add(sa);
        }
        if (spells.isEmpty()) return false;
        SpellAbility chosen = spells.size() == 1 ? spells.get(0) : human.getAbilityToPlay(gameCard, spells, null);
        if (chosen == null) return true;
        human.getInputProxy().selectAbility(chosen);
        return true;
    }

    /** Same hand-order call the desktop board uses. Index is the slot after the card is lifted. */
    void reorderHand(CardView card, int index) {
        if (card == null || index < 0 || choice != null) return;
        if (!sortingHand) paintedIds = new int[0];
        IGameController controller = seatController();
        if (controller != null) controller.reorderHand(card, index);
    }

    /**
     * Same worker path as {@link #select}: Forge's target input must not run
     * on the paint thread, or Confirm never enables.
     */
    void selectPlayer(boolean you) {
        if (choice != null || gui == null || match == null) return;
        PlayerView player = you ? gui.human : gui.opponent;
        IGameController controller = seatController();
        if (controller == null || player == null) return;
        if (!cardSelectBusy.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                controller.selectPlayer(player, LEFT_CLICK);
            } catch (Throwable failed) {
                System.err.println("Player select failed: " + failed);
            } finally {
                cardSelectBusy.set(false);
            }
        }, "player select");
        worker.setDaemon(true);
        worker.start();
    }

    /** Host of the open target ask. The arrow starts on this card, or its stack object. */
    CardView targetingSource() {
        if (!choosingTarget()) return null;
        try {
            Input input = humanInput();
            if (!(input instanceof InputSelectTargets)) return null;
            java.lang.reflect.Field saField = InputSelectTargets.class.getDeclaredField("sa");
            saField.setAccessible(true);
            SpellAbility sa = (SpellAbility) saField.get(input);
            if (sa == null || sa.getHostCard() == null) return null;
            return sa.getHostCard().getView();
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** Card ids already locked in the open target ask. Empty until he picks one. */
    int[] assignedTargetCardIds() {
        if (!choosingTarget()) return new int[0];
        try {
            Input input = humanInput();
            if (!(input instanceof InputSelectTargets)) return new int[0];
            java.lang.reflect.Field targetsField = InputSelectTargets.class.getDeclaredField("targets");
            targetsField.setAccessible(true);
            Object raw = targetsField.get(input);
            if (!(raw instanceof java.util.Set<?> set)) return new int[0];
            java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
            for (Object entry : set) {
                if (entry instanceof forge.game.card.Card card) ids.add(card.getId());
            }
            int[] out = new int[ids.size()];
            for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
            return out;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return new int[0];
        }
    }

    /** This player is already in the current target list. */
    boolean playerAssigned(boolean you) {
        if (!choosingTarget()) return false;
        PlayerView view = you ? gui.human : gui.opponent;
        if (view == null) return false;
        try {
            Input input = humanInput();
            if (!(input instanceof InputSelectTargets)) return false;
            PlayerControllerHuman human = humanController();
            if (human == null || human.getGame() == null) return false;
            Player player = null;
            for (Player seat : human.getGame().getPlayers()) {
                if (seat != null && seat.getId() == view.getId()) {
                    player = seat;
                    break;
                }
            }
            if (player == null) return false;
            java.lang.reflect.Field targetsField = InputSelectTargets.class.getDeclaredField("targets");
            targetsField.setAccessible(true);
            Object raw = targetsField.get(input);
            return raw instanceof java.util.Set<?> set && set.contains(player);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    Pic picture() {
        tendResolveAll();
        notePregameEdge();
        if (!pregameChoice()) {
            considerStackPass();
            considerEmptyPass();
        }
        gui.assignPlayers();
        noteHandIdChange();
        clumpOpeningLands();
        GameView view = gui.getGameView();
        if (view == null) return null;
        PlayerView you = gui.human;
        if (you == null) you = gui.getCurrentPlayer();
        PlayerView them = gui.opponent;
        if (you == null) {
            for (PlayerView player : gui.getLocalPlayers()) {
                you = liveSeat(player);
                break;
            }
        }
        you = liveSeat(you);
        if (them == null && view.getPlayers() != null) {
            for (PlayerView player : view.getPlayers()) {
                if (you == null || !sameSeat(player, you)) {
                    them = liveSeat(player);
                    break;
                }
            }
        }
        them = liveSeat(them);
        if (gui.human == null && you != null) gui.human = you;
        if (gui.opponent == null && them != null) gui.opponent = them;
        Pic pic = new Pic();
        pic.youName = you == null || you.getName() == null ? "" : you.getName();
        pic.themName = them == null || them.getName() == null ? "" : them.getName();
        pic.yourLife = you == null ? 20 : you.getLife();
        pic.theirLife = them == null ? 20 : them.getLife();
        pic.yourStartingLife = rememberedStart(you, true);
        pic.theirStartingLife = rememberedStart(them, false);
        commanderDamagePips(view, you, pic.yourCommanderDamage);
        commanderDamagePips(view, them, pic.theirCommanderDamage);
        pic.yourPool = poolOf(you);
        pic.theirPool = poolOf(them);
        pic.yourHandCount = you == null ? 0 : you.getZoneSize(ZoneType.Hand);
        pic.theirHandCount = them == null ? 0 : them.getZoneSize(ZoneType.Hand);
        pic.prompt = gui.prompt == null ? "" : gui.prompt;
        PlayerView turn = view.getPlayerTurn();
        pic.owner = turn != null && you != null && turn.getId() == you.getId()
                ? BattlefieldLayout.StripOwner.YOU
                : BattlefieldLayout.StripOwner.ENEMY;
        pic.phase = strip(view.getPhase());
        if (you != null) {
            pic.hand.addAll(faces(openingHand(you), true));
            pic.yourLibrary.addAll(faces(you.getLibrary(), false));
            pic.yourGrave.addAll(faces(you.getGraveyard(), false));
            pic.yourExile.addAll(faces(you.getExile(), false));
            if (pic.yourHandCount < pic.hand.size()) pic.yourHandCount = pic.hand.size();
        }
        fillBoards(pic, view, you, them);
        if (them != null) {
            pic.theirLibrary.addAll(faces(them.getLibrary(), false));
            pic.theirGrave.addAll(faces(them.getGraveyard(), false));
            pic.theirExile.addAll(faces(them.getExile(), false));
        }
        int playableEpochNow = playableEpoch;
        boolean showPlayable = playableEpochNow != playableGap;
        if (you == null || !you.getHasPriority() || view.isMulligan()) {
            playableGap = playableEpochNow;
            showPlayable = false;
        }
        Set<Integer> playable = showPlayable ? playableIds : Set.of();
        for (Face face : pic.hand) markPlayable(face, playable);
        for (Face face : pic.yourBoard) markPlayable(face, playable);
        for (Face face : pic.yourExile) markPlayable(face, playable);
        fillCommanders(you, pic.yourCommand, playable);
        fillCommanders(them, pic.theirCommand, playable);
        if (commanderChoiceNote != null) {
            logCommanderZones("after " + commanderChoiceNote, you, them);
            commanderChoiceNote = null;
        }
        if (showPlayable) {
            Set<Integer> seated = new HashSet<>();
            for (CommanderSeat seat : pic.yourCommand) {
                if (seat != null && seat.inCommand && seat.card != null && seat.card.view != null) {
                    seated.add(seat.card.view.getId());
                }
            }
            for (Face ghost : playableGhosts) {
                if (ghost != null && ghost.view != null && seated.contains(ghost.view.getId())) continue;
                if (ghost != null && ghost.hiddenBack) continue;
                pic.ghosts.add(ghost);
            }
        }
        markBattlePlayable(pic, playable);
        FCollectionView<StackItemView> stack = view.getStack();
        long now = System.currentTimeMillis();
        Set<Integer> liveIds = new HashSet<>();
        if (stack != null) {
            for (StackItemView item : stack) {
                Spell spell = spellFrom(item);
                pic.stack.add(spell);
                liveIds.add(spell.id);
            }
        }
        nameTargets(pic);
        for (Spell spell : pic.stack) rememberSpell(spell, now);
        Set<Integer> boardIds = new HashSet<>();
        for (Face face : pic.yourBoard) {
            if (face != null && face.view != null) boardIds.add(face.view.getId());
        }
        for (Face face : pic.theirBoard) {
            if (face != null && face.view != null) boardIds.add(face.view.getId());
        }
        Set<Integer> hiddenExile = hiddenExileIds(pic);
        synchronized (heldSpells) {
            // Leave the live GameView stack. Do not ghost a resolved or
            // countered object back onto the well — that leftover tile sat
            // on the grass after Infernal Grasp (same family as leftover
            // mulligan hand tiles).
            heldSpells.removeIf(held -> !liveIds.contains(held.id)
                    || held.until < now || landedFromStack(held, boardIds)
                    || hiddenExile.contains(held.sourceId) || hiddenExile.contains(held.id)
                    || stackSpellGone(held, pic));
        }
        GameLog gameLog = view.getGameLog();
        if (gameLog != null) {
            List<GameLogEntry> entries = gameLog.getAllEntries();
            int from = Math.max(0, entries.size() - 80);
            for (int i = from; i < entries.size(); i++) {
                GameLogEntry entry = entries.get(i);
                if (entry == null || entry.message() == null || entry.message().isBlank()) continue;
                pic.log.add(presentLog(entry.toString(), pic.stack));
            }
        }
        pic.decisions.addAll(forge.ai.AiDecisionLog.recent());
        int decisionTurn = forge.ai.AiDecisionLog.latestTurnNumber();
        if (decisionTurn >= 0) pic.debugDecisions.add("Turn " + decisionTurn);
        pic.debugDecisions.addAll(forge.ai.AiDecisionLog.latestTurn());
        synchronized (combatStrikes) {
            pic.strikes.addAll(combatStrikes);
        }
        linkBlockers(pic, view.getCombat());
        collectMarks(pic.yourMarks, you);
        collectMarks(pic.theirMarks, them);
        if (view.isGameOver()) {
            pic.over = true;
            String winner = view.getWinningPlayerName();
            if (winner != null && winner.equalsIgnoreCase("You")) pic.resultTitle = "You win";
            else if (winner != null && !winner.isBlank()) pic.resultTitle = winner + " wins";
            else pic.resultTitle = "Draw";
            pic.resultWhy = outcomeLine(view);
        }
        if (pendingHandWipe) {
            pic.wipeHand = true;
            pendingHandWipe = false;
        }
        if (pendingBoardRebuild) {
            pic.wipeHand = true;
            pic.rebuildBoards = true;
            pendingBoardRebuild = false;
        }
        pic.signature = signature(pic);
        Choice pending = choice;
        if (pending != null) {
            pic.choiceMessage = pending.message;
            pic.choiceLabels = List.copyOf(pending.labels);
            pic.choicePicked = List.copyOf(pending.picked);
            pic.choiceFaces = pending.facePick ? List.copyOf(pending.faces) : List.of();
            pic.facePick = pending.facePick;
            pic.typeChoice = pending.typeList && !pending.facePick;
            pic.deckTypes = pending.deckTypes == null || pending.deckTypes.isEmpty()
                    ? Set.of() : Set.copyOf(pending.deckTypes);
            pic.ordering = pending.ordering;
            pic.placing = pending.placing;
            pic.placeSplit = pending.placeSplit;
            pic.amounting = pending.amounting;
            pic.mayCast = pending.mayCast;
            pic.mayReveal = pending.mayReveal;
            pic.destinationSplit = pending.destinationSplit;
            pic.hitIndex = pending.hitIndex;
            if (pending.kind == Choice.Kind.CARDS) {
                fillSheet(pic, pending.cards, pending.legal, pending.picked, pending.ordering,
                        pending.placing, pending.placeSplit, pending.hitIndex);
            }
            else if (pending.kind == Choice.Kind.ORDER) fillWordOrder(pic, pending);
            else if (pending.kind == Choice.Kind.AMOUNT) fillAmounts(pic, pending);
            else if (pending.glance != null && !pending.glance.isEmpty()) fillGlance(pic, pending.glance);
        } else {
            fillListSheet(pic);
            maybeAutoTakeList();
        }
        return pic;
    }

    /**
     * Every legal card is the same printed name. Arts do not count as a choice.
     * An unread name is not automatic — show the row.
     */
    private static boolean samePrintedName(List<?> legal) {
        return uniqueLegalNames(legal).size() == 1;
    }

    /** Distinct printed names Forge listed as legal. Empty if any name could not be read. */
    private static List<String> uniqueLegalNames(List<?> legal) {
        LinkedHashMap<String, String> names = new LinkedHashMap<>();
        for (Object item : legal) {
            if (!(item instanceof CardView card)) return List.of();
            String printed = sheetName(card);
            if (printed == null || printed.isBlank() || printed.equals("Card")) return List.of();
            names.putIfAbsent(printed.toLowerCase(Locale.ROOT), printed);
        }
        return List.copyOf(names.values());
    }

    /** One-line leftover after a search skipped the sheet. Null when nothing was automatic. */
    String searchAutoNote() {
        String note = searchAutoNote;
        if (note == null || note.isBlank()) return null;
        if (System.currentTimeMillis() > searchAutoNoteUntil) return null;
        return note;
    }

    /** Printed name for a search tile. A hidden library copy can leave the entity name blank. */
    private static String sheetName(CardView view) {
        if (view == null) return "Card";
        String name = view.getName();
        if (name != null && !name.isBlank()) return name;
        try {
            CardView.CardStateView state = view.getCurrentState();
            if (state != null && state.getName() != null && !state.getName().isBlank()) return state.getName();
        } catch (RuntimeException ignored) {
            return "Card";
        }
        return "Card";
    }

    private static void fillSheet(Pic pic, List<CardView> cards, boolean[] legal, List<Integer> picked,
            boolean ordering, boolean placing, int placeSplit, int hitIndex) {
        if (cards == null) return;
        if (ordering) {
            fillOrderedSheet(pic, cards, picked, placing, placeSplit);
            return;
        }
        for (int i = 0; i < cards.size(); i++) {
            CardView view = cards.get(i);
            if (view == null) continue;
            ShownCard tile = new ShownCard();
            tile.name = revealedName(view);
            if (tile.name == null || tile.name.isBlank()) tile.name = sheetName(view);
            tile.legal = legal != null && i < legal.length && legal[i];
            tile.dim = !tile.legal;
            int at = picked == null ? -1 : picked.indexOf(i);
            // An order step already accepted these cards. Numbers show the order.
            // The gold take-mark stays off, or this looks like a second pick.
            tile.picked = at >= 0 && !ordering;
            tile.order = ordering && at >= 0 ? at + 1 : 0;
            tile.hit = hitIndex == i;
            pic.sheet.add(tile);
        }
    }

    /**
     * Left to right is the list {@code order()} returns. Index 0 is the top of that list:
     * the first destination entry in Forge's order dialog. For a bottom-of-library order
     * whose caption is "Closest to bottom", Forge does not reverse the list, then appends
     * each card to the library, so the left card is the first of this pile he will draw.
     */
    private static void fillOrderedSheet(Pic pic, List<CardView> cards, List<Integer> picked,
            boolean placing, int placeSplit) {
        boolean[] seen = new boolean[cards.size()];
        int place = 1;
        int shown = 0;
        if (picked != null) {
            for (int index : picked) {
                if (index < 0 || index >= cards.size() || seen[index]) continue;
                seen[index] = true;
                pic.sheet.add(orderedTile(cards.get(index), place++, placing && shown >= placeSplit));
                shown++;
            }
        }
        for (int i = 0; i < cards.size(); i++) {
            if (seen[i]) continue;
            pic.sheet.add(orderedTile(cards.get(i), place++, placing && shown >= placeSplit));
            shown++;
        }
    }

    private static ShownCard orderedTile(CardView view, int place, boolean bottom) {
        ShownCard tile = new ShownCard();
        tile.name = sheetName(view);
        tile.legal = true;
        tile.order = place;
        tile.dim = bottom;
        tile.caption = bottom ? "BOTTOM" : "";
        return tile;
    }

    /** Left is first. Host art when Forge has it, and the ability line on the plate. */
    private static void fillWordOrder(Pic pic, Choice pending) {
        List<Integer> picked = pending.picked;
        int n = pending.labels.size();
        boolean[] seen = new boolean[n];
        int place = 1;
        if (picked != null) {
            for (int index : picked) {
                if (index < 0 || index >= n || seen[index]) continue;
                seen[index] = true;
                pic.sheet.add(wordTile(pending, index, place++));
            }
        }
        for (int i = 0; i < n; i++) {
            if (seen[i]) continue;
            pic.sheet.add(wordTile(pending, i, place++));
        }
    }

    private static ShownCard wordTile(Choice pending, int index, int place) {
        ShownCard tile = new ShownCard();
        CardView host = index < pending.cards.size() ? pending.cards.get(index) : null;
        tile.name = host == null || host.getName() == null ? "" : host.getName();
        String line = pending.labels.get(index);
        tile.caption = line == null ? "" : line;
        if (!tile.caption.isEmpty() && tile.caption.equals(tile.name)) tile.caption = "";
        tile.legal = true;
        tile.order = place;
        return tile;
    }

    private static void fillAmounts(Pic pic, Choice pending) {
        int n = pending.amounts.length;
        for (int i = 0; i < n; i++) {
            ShownCard tile = new ShownCard();
            CardView host = i < pending.cards.size() ? pending.cards.get(i) : null;
            tile.name = host == null || host.getName() == null ? "" : host.getName();
            String line = i < pending.labels.size() ? pending.labels.get(i) : "";
            tile.caption = host == null ? (line == null ? "" : line) : "";
            tile.legal = true;
            tile.amount = pending.amounts[i];
            pic.sheet.add(tile);
        }
    }

    private static void fillGlance(Pic pic, List<CardView> cards) {
        for (CardView view : cards) {
            if (view == null) continue;
            ShownCard tile = new ShownCard();
            tile.name = revealedName(view);
            if (tile.name == null || tile.name.isBlank()) tile.name = sheetName(view);
            tile.legal = true;
            tile.dim = false;
            pic.sheet.add(tile);
        }
    }

    private void fillListSheet(Pic pic) {
        InputSelectEntitiesFromList<?> list = listInput();
        if (list == null) return;
        pic.choiceMessage = presentChoice(gui.prompt);
        int index = 0;
        for (GameEntity entity : list.getValidChoices()) {
            if (!(entity instanceof Card card)) continue;
            ShownCard tile = new ShownCard();
            String name = card.getName();
            tile.name = name == null || name.isBlank() ? "Card" : name;
            tile.legal = true;
            tile.dim = false;
            tile.picked = selectedIn(list, card);
            pic.sheet.add(tile);
            index++;
        }
        if (index == 0) pic.sheet.clear();
    }

    /**
     * Same printed name among every legal card. Fetch and a tutor skip the row.
     * A destination split never takes itself.
     */
    private void maybeAutoTakeList() {
        if (choice != null || !listSheetActive()) {
            listAutoSig = null;
            return;
        }
        String prompt = gui == null || gui.prompt == null ? "" : gui.prompt;
        String title = presentChoice(prompt);
        if (discardAsk(prompt) || discardAsk(title)) return;
        if (destinationSplitAsk(prompt) || destinationSplitAsk(title) || scryLikeAsk(prompt)) return;
        InputSelectEntitiesFromList<?> list = listInput();
        if (list == null || listMax(list) > 1) return;
        String sig = waitId(list);
        if (sig.equals(listAutoSig)) return;
        List<CardView> legal = new ArrayList<>();
        for (GameEntity entity : list.getValidChoices()) {
            if (entity instanceof Card card) legal.add(card.getView());
        }
        if (!samePrintedName(legal) || legal.isEmpty()) return;
        if (destinationLikePile(legal, prompt) || destinationLikePile(legal, title)) return;
        listAutoSig = sig;
        List<String> names = uniqueLegalNames(legal);
        if (names.size() == 1) {
            searchAutoNote = "Only " + names.get(0) + ". Taken automatically.";
            searchAutoNoteUntil = System.currentTimeMillis() + 4000L;
        }
        selectThenOk(legal.get(0));
    }

    boolean clickChoice(float mx, float my, float w, float h) {
        Choice pending = choice;
        if (pending == null || pending.kind != Choice.Kind.LINES) return false;
        int rows = pending.labels.size();
        float[] heights = new float[rows];
        for (int i = 0; i < rows; i++) heights[i] = BattlefieldLayout.CHOICE_ROW_MIN;
        HandLayout.Pose box = BattlefieldLayout.choicePanelForStack(w, h, BattlefieldLayout.choiceStackHeight(heights));
        float[][] rects = BattlefieldLayout.choiceRowRects(box, heights);
        float okX = box.cx - box.w / 2f + 24f;
        float okY = box.cy - box.h / 2f + 16f;
        if (mx >= okX && mx <= okX + 160f && my >= okY && my <= okY + 32f) {
            pending.submit();
            return true;
        }
        for (int i = 0; i < rects.length; i++) {
            float[] row = rects[i];
            if (row == null) continue;
            if (mx >= row[0] && mx <= row[0] + row[2] && my >= row[1] && my <= row[1] + row[3]) {
                pending.toggle(i);
                return true;
            }
        }
        return true;
    }

    /** A short click on a word-choice row. Modes use {@link #pressMode}. */
    void pressTextChoice(int index) {
        Choice pending = choice;
        if (pending != null && (pending.kind == Choice.Kind.LINES || pending.facePick)) pending.toggle(index);
    }

    /** OK on the word-choice panel. */
    void submitTextChoice() {
        Choice pending = choice;
        if (pending != null && (pending.kind == Choice.Kind.LINES || pending.facePick)) pending.submit();
    }

    private static void markPlayable(Face face, Set<Integer> playable) {
        face.playable = face.view != null && playable.contains(face.view.getId());
    }

    /**
     * Foretell, plot, and other exile-face-down cards. The tile is a card back even
     * when the owner may look at the hidden face.
     */
    static boolean hiddenExile(CardView card) {
        if (card == null) return false;
        try {
            if (card.getZone() != ZoneType.Exile) return false;
            return card.isFaceDown() || card.isForeTold();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Face-down / foretold exile. Foretell never takes a stack seat. */
    static Set<Integer> hiddenExileIds(Pic pic) {
        Set<Integer> ids = new HashSet<>();
        if (pic == null) return ids;
        collectHiddenExile(pic.yourExile, ids);
        collectHiddenExile(pic.theirExile, ids);
        return ids;
    }

    private static void collectHiddenExile(List<Face> faces, Set<Integer> ids) {
        if (faces == null) return;
        for (Face face : faces) {
            if (face == null || face.view == null) continue;
            if (face.hiddenBack || face.faceDown || face.foretold) ids.add(face.view.getId());
        }
    }

    /** Drop a leftover showcase when the card is already a face-down exile tile. */
    void dropHiddenExileHolds(Set<Integer> ids) {
        if (ids == null || ids.isEmpty()) return;
        synchronized (heldSpells) {
            heldSpells.removeIf(held -> ids.contains(held.sourceId) || ids.contains(held.id));
        }
    }

    /** Printed name the owner already knows. Empty when only the back should speak. */
    static String revealedName(CardView card) {
        if (card == null) return "";
        try {
            if (!card.isFaceDown()) {
                String shown = card.getName();
                if (shown != null && !shown.isBlank()) return shown;
            }
            CardView.CardStateView alt = card.getAlternateState();
            if (alt != null) {
                String shown = alt.getName();
                if (shown != null && !shown.isBlank()) return shown;
                shown = alt.getOracleName();
                if (shown != null && !shown.isBlank()) return shown;
            }
            String oracle = card.getOracleName();
            if (oracle != null && !oracle.isBlank()) return oracle;
        } catch (RuntimeException ignored) {
            /* fall through */
        }
        try {
            String shown = card.getName();
            return shown == null ? "" : shown;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private List<Face> faces(Iterable<CardView> cards, boolean battlefield) {
        List<Face> out = new ArrayList<>();
        if (cards == null) return out;
        for (CardView card : cards) {
            if (card == null) continue;
            Face face = new Face();
            face.view = card;
            boolean see = gui.mayView(card);
            face.hiddenBack = hiddenExile(card);
            try {
                face.faceDown = card.isFaceDown() || face.hiddenBack;
                face.foretold = card.isForeTold();
            } catch (RuntimeException ignored) {
                face.faceDown = face.hiddenBack;
            }
            face.faceUp = see && !face.hiddenBack;
            if (face.hiddenBack) {
                face.name = see ? revealedName(card) : "";
            } else if (see || battlefield) {
                face.name = printedName(card);
                if (face.name == null || face.name.isBlank()) face.name = revealedName(card);
                if (face.name == null || face.name.isBlank()) {
                    String shown = card.getName();
                    face.name = shown == null || shown.isBlank() ? (see ? "" : "Card") : shown;
                }
            } else {
                face.name = "Card";
            }
            face.tapped = card.isTapped();
            face.token = card.isToken();
            face.sick = card.isSick();
            face.attacking = card.isAttacking();
            face.blocking = card.isBlocking();
            face.marked = gui.isHighlighted(card);
            CardView.CardStateView state = card.getCurrentState();
            face.land = landCard(card);
            if (state != null) {
                face.creature = state.isCreature();
                face.artifact = state.getType() != null && state.getType().isArtifact();
                face.enchantment = state.getType() != null && state.getType().isEnchantment();
                face.aura = state.getType() != null && state.getType().hasSubtype("Aura");
                face.equipment = state.getType() != null && state.getType().hasSubtype("Equipment");
                if (state.isPlaneswalker()) {
                    face.planeswalker = true;
                    fillLoyalty(face, state);
                }
                fillBattle(face, card, state);
                if (face.creature) {
                    face.power = state.getPower();
                    face.toughness = state.getToughness();
                    face.damage = Math.max(0, card.getDamage());
                    fillPrinted(face);
                }
                face.keywords = iconWords(state);
            }
            try {
                face.commander = card.isCommander();
            } catch (RuntimeException ignored) {
                face.commander = false;
            }
            if (face.commander) face.commanderKey = commanderKey(card);
            face.restraint = battlefield ? restraintOf(card) : "";
            face.chosenType = battlefield ? chosenTypeOf(card) : "";
            face.status = battlefield ? statusOf(card, state) : java.util.List.of();
            CardView attached = card.getAttachedTo();
            if (attached != null) face.attachedToId = attached.getId();
            face.instant = instantCard(card);
            face.sorcery = sorceryCard(card);
            face.battlefield = onBattlefield(card);
            out.add(face);
        }
        return out;
    }

    /**
     * Every battlefield list, split by who controls the card. Join can have an
     * empty seat.Battlefield while the same cards sit on another player's list.
     */
    private void fillBoards(Pic pic, GameView view, PlayerView you, PlayerView them) {
        LinkedHashMap<Integer, CardView> yours = new LinkedHashMap<>();
        LinkedHashMap<Integer, CardView> theirs = new LinkedHashMap<>();
        takeBattlefield(yours, you);
        takeBattlefield(theirs, them);
        if (view != null && view.getPlayers() != null) {
            for (PlayerView seat : view.getPlayers()) {
                if (seat == null) continue;
                takeStrayBattlefield(yours, theirs, seat, you);
                if (seat.getBattlefield() == null) continue;
                for (CardView card : seat.getBattlefield()) {
                    if (!boardPermanent(card)) continue;
                    PlayerView ctrl = controllerOf(card);
                    boolean mine = you != null && (sameSeat(ctrl, you)
                            || (ctrl == null && sameSeat(seat, you)));
                    if (mine) yours.putIfAbsent(card.getId(), card);
                    else theirs.putIfAbsent(card.getId(), card);
                }
            }
        }
        pic.yourBoard.addAll(faces(yours.values(), true));
        pic.theirBoard.addAll(faces(theirs.values(), true));
        dropOffBoard(pic.yourBoard);
        dropOffBoard(pic.theirBoard);
        settleBattleSides(pic.yourBoard, you, true);
        settleBattleSides(pic.theirBoard, you, false);
        wantBoardArt(pic.yourBoard);
        wantBoardArt(pic.theirBoard);
    }

    /**
     * Fetch missing scans for every face-up tile. A land still draws its name
     * when the jpg is late; this only queues the picture.
     */
    private static void wantBoardArt(List<Face> faces) {
        if (faces == null) return;
        for (Face face : faces) {
            if (face == null || !face.faceUp) continue;
            if (face.name == null || face.name.isBlank()) continue;
            if (face.token) BattlefieldPics.wantToken(face.name, tokenImageKey(face));
            else BattlefieldPics.want(face.name);
        }
    }

    private static String tokenImageKey(Face face) {
        if (face == null || face.view == null) return null;
        try {
            CardView.CardStateView state = face.view.getCurrentState();
            return state == null ? null : state.getImageKey();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static void takeBattlefield(LinkedHashMap<Integer, CardView> into, PlayerView seat) {
        if (into == null || seat == null || seat.getBattlefield() == null) return;
        for (CardView card : seat.getBattlefield()) {
            if (boardPermanent(card)) into.putIfAbsent(card.getId(), card);
        }
    }

    /**
     * Join deltas can leave a battlefield card on another zone list while
     * {@code CardView.getZone()} already says Battlefield. Host GameView is
     * fine; the joiner still has to draw that land.
     */
    private static void takeStrayBattlefield(LinkedHashMap<Integer, CardView> yours,
            LinkedHashMap<Integer, CardView> theirs, PlayerView seat, PlayerView you) {
        if (seat == null) return;
        ZoneType[] zones = {
                ZoneType.Hand, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command,
                ZoneType.Library, ZoneType.Sideboard, ZoneType.Flashback
        };
        for (ZoneType zone : zones) {
            FCollectionView<CardView> cards;
            try {
                cards = seat.getCards(zone);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (cards == null) continue;
            for (CardView card : cards) {
                if (!boardPermanent(card)) continue;
                PlayerView ctrl = controllerOf(card);
                boolean mine = you != null && (sameSeat(ctrl, you)
                        || (ctrl == null && sameSeat(seat, you)));
                if (mine) yours.putIfAbsent(card.getId(), card);
                else theirs.putIfAbsent(card.getId(), card);
            }
        }
    }

    private static boolean onBattlefield(CardView card) {
        try {
            return card != null && card.getZone() == ZoneType.Battlefield;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Instants and sorceries never sit on the grass. A stale battlefield
     * list or a stray zone still saying Battlefield cannot paint them.
     */
    static boolean boardPermanent(CardView card) {
        return onBattlefield(card) && !spellCard(card);
    }

    private static void dropOffBoard(List<Face> faces) {
        if (faces == null || faces.isEmpty()) return;
        faces.removeIf(face -> face == null || !boardPermanent(face.view)
                || face.instant || face.sorcery);
    }

    /** Instant or sorcery with no permanent type. Adventure creatures stay. */
    static boolean spellCard(CardView card) {
        if (card == null) return false;
        try {
            CardView.CardStateView state = card.getCurrentState();
            if (state != null && permanentState(state)) return false;
            if (instantCard(card) || sorceryCard(card)) return true;
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static boolean permanentState(CardView.CardStateView state) {
        if (state == null) return false;
        try {
            if (state.isCreature() || state.isLand() || state.isPlaneswalker() || state.isBattle()) {
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            CardTypeView type = state.getType();
            return type != null && (type.isArtifact() || type.isEnchantment()
                    || type.isLand() || type.isCreature() || type.isPlaneswalker() || type.isBattle());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean instantCard(CardView card) {
        return typeFlag(card, true);
    }

    private static boolean sorceryCard(CardView card) {
        return typeFlag(card, false);
    }

    private static boolean typeFlag(CardView card, boolean instant) {
        if (card == null) return false;
        try {
            CardView.CardStateView state = card.getCurrentState();
            if (state != null) {
                if (instant ? state.isInstant() : state.isSorcery()) return true;
                CardTypeView type = state.getType();
                if (type != null && (instant ? type.isInstant() : type.isSorcery())) return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    private static PlayerView controllerOf(CardView card) {
        if (card == null) return null;
        try {
            PlayerView ctrl = card.getController();
            if (ctrl != null) return liveSeat(ctrl);
        } catch (RuntimeException ignored) {
        }
        try {
            PlayerView owner = card.getOwner();
            if (owner != null) return liveSeat(owner);
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static PlayerView liveSeat(PlayerView seat) {
        if (seat == null) return null;
        try {
            PlayerView live = TrackableTypes.PlayerViewType.lookup(seat);
            return live != null ? live : seat;
        } catch (RuntimeException ignored) {
            return seat;
        }
    }

    private static boolean sameSeat(PlayerView a, PlayerView b) {
        if (a == null || b == null) return false;
        if (a == b) return true;
        try {
            return a.getId() == b.getId();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * True for a land even when the joiner CardView type is only a subtype
     * ("Forest") and the word Land never arrived over the wire.
     */
    private static boolean landType(CardView.CardStateView state) {
        if (state == null) return false;
        try {
            if (state.isLand()) return true;
        } catch (RuntimeException ignored) {
        }
        try {
            CardTypeView type = state.getType();
            if (type != null) {
                if (type.isLand() || type.isBasicLand()) return true;
                Collection<String> subs = type.getSubtypes();
                if (subs != null) {
                    for (String sub : subs) {
                        if (sub != null && CardType.isALandType(sub)) return true;
                    }
                }
                String types = String.valueOf(type);
                if (types.toLowerCase(Locale.ROOT).contains("land")) return true;
                if (namedLand(types)) return true;
            }
        } catch (RuntimeException ignored) {
        }
        try {
            return namedLand(state.getName());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Once per opening seven, and again when a mulligan deals a new seven.
     * Lands stay on the left. Other cards follow by mana value. Later draws and a drag reorder are left alone.
     * The keep prompt is GameStage.Mulligan. Forge's mulligan flag is only the London bottom pick,
     * so waiting on that flag sorted nothing until the seven had already been shown.
     * The host game sorts every seat, including Ash. The joiner has no game object: that window
     * sorts the hand it is about to draw, when those cards arrive.
     */
    private void clumpOpeningLands() {
        if (stopping) return;
        HostedMatch hosted = match;
        forge.game.Game game = hosted == null ? null : hosted.getGame();
        if (openingHandWindow(game)) {
            try {
                for (Player player : game.getPlayers()) {
                    if (player == null) continue;
                    PlayerZone zone = player.getZone(ZoneType.Hand);
                    if (zone == null || zone.getCards() == null) continue;
                    List<Card> hand = new ArrayList<>();
                    for (Card card : zone.getCards()) {
                        if (card != null) hand.add(card);
                    }
                    int seven = player.getStartingHandSize();
                    if (seven <= 0) seven = 7;
                    if (hand.size() != seven) continue;
                    String key = handIdSet(hand);
                    if (key.equals(openingLandSort.get(player.getId()))) continue;
                    List<Card> desired = landsThenRest(hand);
                    for (int i = 0; i < desired.size(); i++) {
                        if (zone.getCards().get(i) != desired.get(i)) zone.reorder(desired.get(i), i);
                    }
                    player.updateZoneForView(zone);
                    openingLandSort.put(player.getId(), key);
                }
            } catch (RuntimeException ignored) {
                /* the hand stays in Forge's deal order if the zone is mid-update */
            }
        }
        sortDisplayedHand();
    }

    /**
     * The cards this window is about to show. The joiner's list arrives on the game-view player
     * after the host has already sorted, and that snapshot does not pick up the host's reorder.
     */
    private void sortDisplayedHand() {
        if (!openingWindowForSeat()) return;
        if (gui == null) return;
        PlayerView you = gui.human;
        if (you == null || you.getHand() == null) return;
        List<CardView> hand = new ArrayList<>();
        for (CardView card : you.getHand()) {
            if (card != null) hand.add(card);
        }
        int seven = startingSeven(you);
        if (hand.size() != seven) return;
        String key = viewIdSet(hand);
        if (key.equals(openingLandSort.get(you.getId()))) return;
        List<CardView> desired = landsThenMana(hand);
        openingLandSort.put(you.getId(), key);
        boolean same = desired.size() == hand.size();
        for (int i = 0; same && i < desired.size(); i++) same = desired.get(i) == hand.get(i);
        if (same) return;
        paintedPlayer = you.getId();
        paintedKey = key;
        paintedIds = new int[desired.size()];
        List<CardView> live = new ArrayList<>(hand);
        sortingHand = true;
        try {
            for (int i = 0; i < desired.size(); i++) {
                paintedIds[i] = desired.get(i).getId();
                if (live.get(i) == desired.get(i)) continue;
                reorderHand(desired.get(i), i);
                live.remove(desired.get(i));
                live.add(i, desired.get(i));
            }
        } finally {
            sortingHand = false;
        }
    }

    /** Hand list this window paints. The painted order holds until the view matches or he drags. */
    private List<CardView> openingHand(PlayerView you) {
        List<CardView> hand = new ArrayList<>();
        if (you == null || you.getHand() == null) return hand;
        for (CardView card : you.getHand()) {
            if (card != null) hand.add(card);
        }
        if (paintedIds.length == 0 || you.getId() != paintedPlayer) return hand;
        String key = viewIdSet(hand);
        if (!key.equals(paintedKey)) return hand;
        boolean match = hand.size() == paintedIds.length;
        for (int i = 0; match && i < hand.size(); i++) match = hand.get(i).getId() == paintedIds[i];
        if (match) {
            paintedIds = new int[0];
            return hand;
        }
        Map<Integer, CardView> byId = new HashMap<>();
        for (CardView card : hand) byId.put(card.getId(), card);
        List<CardView> ordered = new ArrayList<>();
        for (int id : paintedIds) {
            CardView card = byId.remove(id);
            if (card != null) ordered.add(card);
        }
        ordered.addAll(byId.values());
        return ordered;
    }

    private boolean openingWindowForSeat() {
        forge.game.Game game = match == null ? null : match.getGame();
        if (game != null) return openingHandWindow(game);
        if (gui == null) return false;
        GameView view = gui.getGameView();
        if (view == null || view.isGameOver()) return false;
        if (view.isMulligan()) return true;
        if (view.getPhase() != null) return false;
        try {
            return view.getTurn() <= 0;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private int startingSeven(PlayerView you) {
        forge.game.Game game = match == null ? null : match.getGame();
        if (game != null && you != null) {
            for (Player player : game.getPlayers()) {
                if (player != null && player.getId() == you.getId()) {
                    int seven = player.getStartingHandSize();
                    if (seven > 0) return seven;
                }
            }
        }
        return 7;
    }

    private void clearOpeningSort() {
        openingLandSort.clear();
        paintedPlayer = Integer.MIN_VALUE;
        paintedKey = "";
        paintedIds = new int[0];
        lastYourHandKey = "";
        lastTheirHandKey = "";
    }

    /**
     * Any seat's GameView hand ids changed: mulligan, T1 draw, land, bounce, or
     * cleanup discard. Drop leftover first-seven tiles before the discard picker
     * can send those stale ids.
     */
    private void noteHandIdChange() {
        PlayerView you = gui == null ? null : gui.human;
        PlayerView them = gui == null ? null : gui.opponent;
        String yours = handIdKey(you);
        String theirs = handIdKey(them);
        boolean changed = false;
        if (!lastYourHandKey.isEmpty() && !yours.equals(lastYourHandKey)) {
            changed = true;
            if (you != null) openingLandSort.remove(you.getId());
            paintedPlayer = Integer.MIN_VALUE;
            paintedKey = "";
            paintedIds = new int[0];
        }
        if (!lastTheirHandKey.isEmpty() && !theirs.equals(lastTheirHandKey)) {
            changed = true;
            if (them != null) openingLandSort.remove(them.getId());
        }
        if (changed) pendingHandWipe = true;
        lastYourHandKey = yours;
        lastTheirHandKey = theirs;
    }

    private static String handIdKey(PlayerView seat) {
        List<CardView> hand = new ArrayList<>();
        if (seat != null && seat.getHand() != null) {
            for (CardView card : seat.getHand()) {
                if (card != null) hand.add(card);
            }
        }
        if (!hand.isEmpty()) return viewIdSet(hand);
        try {
            int n = seat == null ? 0 : seat.getZoneSize(ZoneType.Hand);
            return "n:" + n;
        } catch (RuntimeException ignored) {
            return "n:0";
        }
    }

    private void noteMulliganTaken() {
        pendingHandWipe = true;
        PlayerView you = gui == null ? null : gui.human;
        if (you != null) openingLandSort.remove(you.getId());
        paintedPlayer = Integer.MIN_VALUE;
        paintedKey = "";
        paintedIds = new int[0];
        lastYourHandKey = "";
        lastTheirHandKey = "";
    }

    /** Keep, mulligan, and the London bottom pick. A started game is left in the order he set. */
    private static boolean openingHandWindow(forge.game.Game game) {
        if (game == null) return false;
        forge.game.GameStage age = game.getAge();
        if (age == forge.game.GameStage.BeforeMulligan || age == forge.game.GameStage.Mulligan) return true;
        GameView view = game.getView();
        return view != null && view.isMulligan();
    }

    /**
     * Lands, basic and nonbasic, keep their relative order and stay on the left.
     * A zero-cost spell stays with the other spells. Same mana value keeps relative order.
     */
    static List<Card> landsThenRest(List<Card> hand) {
        List<Card> lands = new ArrayList<>();
        List<Card> rest = new ArrayList<>();
        if (hand != null) {
            for (Card card : hand) {
                if (card == null) continue;
                if (landCard(card)) lands.add(card);
                else rest.add(card);
            }
        }
        rest.sort(Comparator.comparingInt(BattlefieldLive::manaValue));
        List<Card> desired = new ArrayList<>(lands);
        desired.addAll(rest);
        return desired;
    }

    /** Same order as {@link #landsThenRest}, from the cards this window is showing. */
    static List<CardView> landsThenMana(List<CardView> hand) {
        List<CardView> lands = new ArrayList<>();
        List<CardView> rest = new ArrayList<>();
        if (hand != null) {
            for (CardView card : hand) {
                if (card == null) continue;
                if (landCard(card)) lands.add(card);
                else rest.add(card);
            }
        }
        rest.sort(Comparator.comparingInt(BattlefieldLive::manaValue));
        List<CardView> desired = new ArrayList<>(lands);
        desired.addAll(rest);
        return desired;
    }

    private static boolean landCard(Card card) {
        try {
            return card != null && card.isLand();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean landCard(CardView card) {
        if (card == null) return false;
        CardView.CardStateView state = null;
        try {
            state = card.getCurrentState();
        } catch (RuntimeException ignored) {
        }
        if (landType(state)) return true;
        try {
            String name = card.getName();
            if (name == null || name.isBlank()) {
                name = state == null ? "" : state.getName();
            }
            return namedLand(name);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** Basic land names, land subtypes, or the printed card rules. */
    private static boolean namedLand(String name) {
        if (name == null || name.isBlank()) return false;
        String fold = name.trim();
        if (CardType.isABasicLandType(fold) || CardType.isALandType(fold)) return true;
        String lower = fold.toLowerCase(Locale.ROOT);
        if (lower.startsWith("snow-covered ")) {
            String bare = fold.substring("snow-covered ".length()).trim();
            if (CardType.isABasicLandType(bare) || CardType.isALandType(bare)) return true;
        }
        try {
            if (FModel.getMagicDb() == null) return false;
            PaperCard paper = FModel.getMagicDb().getCommonCards().getUniqueByName(fold);
            if (paper == null || paper.getRules() == null || paper.getRules().getType() == null) return false;
            return paper.getRules().getType().isLand();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static int manaValue(Card card) {
        try {
            if (card == null || card.getManaCost() == null) return 0;
            return Math.max(0, card.getManaCost().getCMC());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static int manaValue(CardView card) {
        try {
            CardView.CardStateView state = card == null ? null : card.getCurrentState();
            ManaCost cost = state == null ? null : state.getManaCost();
            return cost == null ? 0 : Math.max(0, cost.getCMC());
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String viewIdSet(List<CardView> hand) {
        int[] ids = new int[hand.size()];
        for (int i = 0; i < hand.size(); i++) ids[i] = hand.get(i).getId();
        java.util.Arrays.sort(ids);
        StringBuilder text = new StringBuilder();
        for (int id : ids) text.append(id).append(',');
        return text.toString();
    }

    /** Commanders still in the command zone, plus a word for one that has left. Empty in constructed. */
    private void fillCommanders(PlayerView player, List<CommanderSeat> into, Set<Integer> playable) {
        if (player == null || into == null) return;
        List<CardView> commanders;
        try {
            commanders = player.getCommanders();
        } catch (RuntimeException ignored) {
            return;
        }
        if (commanders == null || commanders.isEmpty()) return;
        for (CardView commander : commanders) {
            if (commander == null) continue;
            CommanderSeat seat = new CommanderSeat();
            String name = commander.getName();
            seat.name = name == null || name.isBlank() ? "Commander" : name;
            seat.colorKey = commanderKey(commander);
            ZoneType zone;
            try {
                zone = commander.getZone();
            } catch (RuntimeException ignored) {
                zone = null;
            }
            CardView seated = commanderInCommandZone(commander, player);
            seat.inCommand = seated != null;
            if (seat.inCommand) {
                List<Face> one = faces(List.of(seated), false);
                if (!one.isEmpty()) {
                    seat.card = one.get(0);
                    markPlayable(seat.card, playable);
                    seat.playable = seat.card.playable;
                }
            } else {
                seat.away = commanderAway(zone);
            }
            into.add(seat);
        }
    }

    /**
     * The commander identity, or the same id sitting in the command zone when
     * CardView.getZone() has not caught up after a return.
     */
    private static CardView commanderInCommandZone(CardView commander, PlayerView player) {
        if (commander == null) return null;
        try {
            if (commander.getZone() == ZoneType.Command) return commander;
        } catch (RuntimeException ignored) {
            /* fall through to the zone list */
        }
        if (player == null) return null;
        int id = commander.getId();
        try {
            Iterable<CardView> command = player.getCommand();
            if (command == null) return null;
            for (CardView card : command) {
                if (card != null && card.getId() == id) return card;
            }
        } catch (RuntimeException ignored) {
            return null;
        }
        return null;
    }

    private static String commanderAway(ZoneType zone) {
        if (zone == null) return "away";
        if (zone == ZoneType.Battlefield) return "on battlefield";
        if (zone == ZoneType.Hand) return "hand";
        if (zone == ZoneType.Graveyard) return "graveyard";
        if (zone == ZoneType.Exile) return "exile";
        if (zone == ZoneType.Library) return "library";
        if (zone == ZoneType.Stack) return "stack";
        String name = zone.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return name.isBlank() ? "away" : name;
    }

    private static String handIdSet(List<Card> hand) {
        int[] ids = new int[hand.size()];
        for (int i = 0; i < hand.size(); i++) ids[i] = hand.get(i).getId();
        java.util.Arrays.sort(ids);
        StringBuilder text = new StringBuilder();
        for (int id : ids) text.append(id).append(',');
        return text.toString();
    }

    /**
     * Keywords the board can badge. Hexproof and shroud come from Forge's
     * keyword list or {@code hasKeyword}. Ward is a different keyword and is
     * not treated as hexproof unless HEXPROOF is also present.
     */
    private static List<String> iconWords(CardView.CardStateView state) {
        if (state == null) return List.of();
        List<String> out = new ArrayList<>();
        KeywordCollectionView keywords = state.getKeywords();
        if (keywords != null) {
            for (KeywordView keyword : keywords) {
                if (keyword == null || keyword.keyword() == null) continue;
                String key = keyword.keyword().name().toLowerCase(Locale.ROOT).replace('_', ' ');
                if (!ICON_WORDS.contains(key) || out.contains(key)) continue;
                out.add(key);
            }
        }
        try {
            if (state.hasKeyword(Keyword.HEXPROOF) && !out.contains("hexproof")) out.add("hexproof");
            if (state.hasKeyword(Keyword.SHROUD) && !out.contains("shroud")) out.add("shroud");
        } catch (RuntimeException ignored) {
            /* keyword flags can be missing on a stale view */
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static int signature(Pic pic) {
        StringBuilder text = new StringBuilder();
        append(text, pic.hand);
        append(text, pic.yourBoard);
        append(text, pic.theirBoard);
        text.append('|').append(pic.yourLibrary.size());
        text.append('|').append(pic.theirLibrary.size());
        text.append('|').append(pic.yourHandCount);
        text.append('|').append(pic.theirHandCount);
        text.append('|').append(pic.yourGrave.size());
        text.append('|').append(pic.theirGrave.size());
        text.append('|').append(pic.yourExile.size());
        text.append('|').append(pic.theirExile.size());
        for (Spell spell : pic.stack) {
            text.append('|').append(spell.id).append(':').append(spell.name).append(':').append(spell.text)
                    .append(':').append(spell.targetLabel)
                    .append(':').append(spell.sourceId)
                    .append(spell.sourceOnBoard ? 'b' : '-')
                    .append(spell.sourceYours ? 'y' : '-');
        }
        appendMarks(text, pic.yourMarks);
        appendMarks(text, pic.theirMarks);
        return text.toString().hashCode();
    }

    private static void append(StringBuilder text, List<Face> faces) {
        for (Face face : faces) {
            text.append(face.view.getId()).append(':')
                    .append(face.name == null ? "" : face.name)
                    .append(face.tapped ? 't' : 'u')
                    .append(face.attacking ? 'a' : '-')
                    .append(face.blocking ? 'b' : '-')
                    .append(face.blockAttackerIds == null ? "" : java.util.Arrays.toString(face.blockAttackerIds))
                    .append(face.sick ? 's' : '-')
                    .append(face.power).append('/').append(face.toughness)
                    .append('d').append(face.damage)
                    .append('L').append(face.loyalty)
                    .append('D').append(face.defense)
                    .append(face.battle ? 'B' : '-')
                    .append(face.protectYou ? 'P' : '-')
                    .append(face.restraint == null || face.restraint.isEmpty() ? "-" : face.restraint)
                    .append('e').append(face.attachedToId)
                    .append(face.aura ? 'A' : '-')
                    .append(face.equipment ? 'Q' : '-')
                    .append(face.token ? 'k' : '-')
                    .append(face.hiddenBack ? 'h' : '-')
                    .append(face.faceDown ? 'd' : '-')
                    .append(face.commander ? 'C' : '-')
                    .append(face.keywords == null ? "" : face.keywords)
                    .append(face.status == null ? "" : face.status.stream().map(s -> s.title).toList())
                    .append('|').append(face.chosenType == null ? "" : face.chosenType)
                    .append(';');
        }
        text.append('#');
    }

    /**
     * Command-zone pieces that are not permanents on the battlefield.
     * Emblems, City's Blessing, The Ring, and a dungeon still in progress.
     * Commanders and other command objects stay out of this row.
     */
    private static void collectMarks(List<Mark> out, PlayerView player) {
        if (player == null) return;
        try {
            Iterable<CardView> command = player.getCommand();
            if (command == null) return;
            for (CardView card : command) {
                Mark mark = markFrom(card);
                if (mark != null) out.add(mark);
            }
        } catch (RuntimeException ignored) {
            /* an empty row is safer than a guessed blessing */
        }
    }

    private static Mark markFrom(CardView card) {
        if (card == null) return null;
        String raw = card.getName() == null ? "" : card.getName().trim();
        String name = raw.replace('’', '\'').replace('‘', '\'');
        CardView.CardStateView state = card.getCurrentState();
        boolean dungeon = false;
        try {
            dungeon = state != null && state.getType() != null && state.getType().isDungeon();
        } catch (RuntimeException ignored) {
            dungeon = false;
        }
        boolean emblem = false;
        try {
            emblem = card.isEmblem();
        } catch (RuntimeException ignored) {
            emblem = false;
        }
        if (!emblem && name.regionMatches(true, 0, "Emblem", 0, "Emblem".length())) emblem = true;
        boolean blessing = name.equalsIgnoreCase("City's Blessing");
        boolean ring = name.equalsIgnoreCase("The Ring");
        if (!dungeon && !emblem && !blessing && !ring) return null;
        Mark mark = new Mark();
        mark.name = markLabel(name, emblem);
        mark.text = markBody(card, state, dungeon, ring, name);
        return mark;
    }

    private static String markLabel(String name, boolean emblem) {
        if (emblem) {
            String rest = name.replaceFirst("(?i)^emblem\\s*[—–:\\-]\\s*", "").trim();
            if (!rest.isEmpty() && !rest.equalsIgnoreCase("emblem")) return rest;
        }
        return name.isEmpty() ? "Emblem" : name;
    }

    private static String markBody(CardView card, CardView.CardStateView state,
                                   boolean dungeon, boolean ring, String name) {
        String rules = "";
        if (state != null && state.getRulesText() != null) rules = state.getRulesText().trim();
        if (rules.isEmpty()) {
            try {
                String text = card.getText();
                if (text != null) rules = text.trim();
            } catch (RuntimeException ignored) {
                rules = "";
            }
        }
        if (dungeon) {
            String room = card.getCurrentRoom();
            if (room != null && !room.isBlank()) {
                rules = "Room: " + room.trim() + (rules.isEmpty() ? "" : "\n" + rules);
            }
        }
        if (ring) {
            int level = card.getRingLevel();
            if (level > 0) rules = "Tempted " + level + (rules.isEmpty() ? "" : "\n" + rules);
        }
        if (rules.isEmpty()) rules = name;
        return rules;
    }

    private static void appendMarks(StringBuilder text, List<Mark> marks) {
        text.append("|marks");
        if (marks == null) return;
        for (Mark mark : marks) {
            text.append(':').append(mark.name).append('=').append(mark.text);
        }
    }

    void clearStrikes() {
        synchronized (combatStrikes) {
            combatStrikes.clear();
        }
    }

    /** Called on the game thread while the spell is still on Forge's stack. */
    private void snapshotStack() {
        GameView view = gui.getGameView();
        if (view == null || view.getStack() == null) return;
        long now = System.currentTimeMillis();
        for (StackItemView item : view.getStack()) {
            rememberSpell(spellFrom(item), now);
        }
    }

    /** Called on the game thread while attackers are still declared, before damage resolves. */
    private void snapshotCombat() {
        GameView view = gui.getGameView();
        if (view == null) return;
        CombatView combat = view.getCombat();
        if (combat == null || combat.getNumAttackers() == 0) return;
        List<Strike> next = new ArrayList<>();
        for (CardView attacker : combat.getAttackers()) {
            if (attacker == null) continue;
            Strike strike = new Strike();
            strike.attackerId = attacker.getId();
            CardView blocker = firstBlocker(combat, attacker);
            if (blocker != null) strike.blockerId = blocker.getId();
            GameEntityView defender = combat.getDefender(attacker);
            if (defender instanceof PlayerView player) {
                strike.defenderIsYou = gui.human != null && player.getId() == gui.human.getId();
            } else if (defender instanceof CardView card) {
                strike.planeswalkerId = card.getId();
            }
            next.add(strike);
        }
        synchronized (combatStrikes) {
            combatStrikes.clear();
            combatStrikes.addAll(next);
        }
    }

    /** Assigned blocker, or the planned one if damage has not locked the block yet. */
    private static CardView firstBlocker(CombatView combat, CardView attacker) {
        CardView assigned = firstCard(combat.getBlockers(attacker));
        if (assigned != null) return assigned;
        return firstCard(combat.getPlannedBlockers(attacker));
    }

    private static CardView firstCard(Iterable<CardView> cards) {
        if (cards == null) return null;
        for (CardView card : cards) {
            if (card != null) return card;
        }
        return null;
    }

    /**
     * Creature, land, and other permanent spells leave the stack and sit on the bed
     * in the same snapshot. Drop the held showcase so the huge card is gone that frame.
     * Activated abilities keep {@code sourceOnBoard} and stay held.
     */
    private static boolean landedFromStack(HeldSpell held, Set<Integer> boardIds) {
        if (held == null || held.sourceOnBoard || held.sourceId == 0 || boardIds == null) return false;
        return boardIds.contains(held.sourceId);
    }

    /**
     * Instant, sorcery, or a source that already left Stack/Battlefield.
     * The well tile, targeting line, and held ghost must not linger.
     */
    private static boolean stackSpellGone(HeldSpell held, Pic pic) {
        if (held == null) return true;
        if (held.sourceOnBoard) return false;
        if (typeLineSpell(held.typeLine)) return true;
        if (pic == null) return false;
        if (inFaces(pic.yourGrave, held.sourceId) || inFaces(pic.theirGrave, held.sourceId)
                || inFaces(pic.yourExile, held.sourceId) || inFaces(pic.theirExile, held.sourceId)
                || inFaces(pic.yourLibrary, held.sourceId) || inFaces(pic.theirLibrary, held.sourceId)
                || inFaces(pic.hand, held.sourceId)) {
            return true;
        }
        return false;
    }

    private static boolean typeLineSpell(String typeLine) {
        if (typeLine == null || typeLine.isBlank()) return false;
        String low = typeLine.toLowerCase(Locale.ROOT);
        if (low.contains("creature") || low.contains("artifact") || low.contains("enchantment")
                || low.contains("planeswalker") || low.contains("battle") || low.contains("land")) {
            return false;
        }
        return low.contains("instant") || low.contains("sorcery");
    }

    private static boolean inFaces(List<Face> faces, int id) {
        if (faces == null || id == 0) return false;
        for (Face face : faces) {
            if (face != null && face.view != null && face.view.getId() == id) return true;
        }
        return false;
    }

    private void rememberSpell(Spell spell, long now) {
        if (spell == null || spell.id == 0 && (spell.name == null || spell.name.isBlank())) return;
        synchronized (heldSpells) {
            for (HeldSpell held : heldSpells) {
                if (held.id == spell.id) {
                    if ((held.targetLabel == null || held.targetLabel.isBlank())
                            && spell.targetLabel != null && !spell.targetLabel.isBlank()) {
                        held.targetLabel = spell.targetLabel;
                        held.targetCardIds = spell.targetCardIds;
                        held.targetYou = spell.targetYou;
                        held.targetThem = spell.targetThem;
                    }
                    return;
                }
            }
            HeldSpell held = new HeldSpell();
            held.id = spell.id;
            held.name = spell.name;
            held.text = spell.text;
            held.typeLine = spell.typeLine;
            held.cost = spell.cost;
            held.pt = spell.pt;
            held.rules = spell.rules;
            held.targetLabel = spell.targetLabel;
            held.targetCardIds = spell.targetCardIds;
            held.targetYou = spell.targetYou;
            held.targetThem = spell.targetThem;
            held.sourceId = spell.sourceId;
            held.sourceOnBoard = spell.sourceOnBoard;
            held.sourceYours = spell.sourceYours;
            held.until = now + STACK_HOLD_MS;
            heldSpells.add(held);
        }
    }

    private Spell spellFrom(StackItemView item) {
        Spell spell = new Spell();
        if (item == null) return spell;
        spell.id = item.getId();
        String text = item.getText() == null ? "" : item.getText().trim();
        CardView source = item.getSourceCard();
        String name = printedName(source);
        if (name.isBlank()) name = text;
        spell.name = name;
        spell.text = text.equals(name) ? "" : text;
        PlayerView you = gui.human;
        if (source != null) {
            spell.source = source;
            spell.sourceId = source.getId();
            spell.sourceOnBoard = source.getZone() == forge.game.zone.ZoneType.Battlefield;
            PlayerView controller = source.getController();
            spell.sourceYours = you != null && controller != null && controller.getId() == you.getId();
        }
        if (source != null && source.getCurrentState() != null) {
            CardView.CardStateView state = source.getCurrentState();
            if (state.getType() != null) spell.typeLine = state.getType().toString();
            if (state.getManaCost() != null) spell.cost = state.getManaCost().toString();
            if (state.isCreature()) spell.pt = state.getPower() + "/" + state.getToughness();
            if (state.getRulesText() != null) spell.rules = state.getRulesText().trim();
        }
        addTargets(item, spell, you);
        return spell;
    }

    /** Card and player targets from the stack item, including a nested ability. */
    private static void addTargets(StackItemView item, Spell spell, PlayerView you) {
        if (item == null) return;
        FCollectionView<CardView> cards = item.getTargetCards();
        if (cards != null) {
            for (CardView card : cards) {
                if (card == null || hasCard(spell, card.getId())) continue;
                Aim aim = new Aim();
                aim.cardId = card.getId();
                aim.name = printedName(card);
                if (aim.name.isBlank()) aim.name = "a card";
                CardView.CardStateView state = card.getCurrentState();
                if (state != null && state.isCreature()) {
                    aim.creature = true;
                    aim.power = state.getPower();
                    aim.toughness = state.getToughness();
                }
                aim.tapped = card.isTapped();
                spell.aims.add(aim);
            }
        }
        FCollectionView<PlayerView> players = item.getTargetPlayers();
        if (players != null) {
            for (PlayerView player : players) {
                if (player == null || hasPlayer(spell, player.getId())) continue;
                Aim aim = new Aim();
                aim.player = true;
                aim.playerId = player.getId();
                aim.you = you != null && player.getId() == you.getId();
                String name = player.getName();
                aim.name = name == null || name.isBlank() ? (aim.you ? "You" : "a player") : name;
                spell.aims.add(aim);
            }
        }
        addTargets(item.getSubInstance(), spell, you);
    }

    private static boolean hasCard(Spell spell, int id) {
        for (Aim aim : spell.aims) {
            if (!aim.player && aim.cardId == id) return true;
        }
        return false;
    }

    private static boolean hasPlayer(Spell spell, int id) {
        for (Aim aim : spell.aims) {
            if (aim.player && aim.playerId == id) return true;
        }
        return false;
    }

    /** Real printed names. Two copies of the same card keep power and toughness, or a card id. */
    private static void nameTargets(Pic pic) {
        Map<String, List<Face>> byName = new LinkedHashMap<>();
        for (Face face : pic.yourBoard) noteFace(byName, face);
        for (Face face : pic.theirBoard) noteFace(byName, face);
        for (Spell spell : pic.stack) {
            if (spell.aims.isEmpty()) continue;
            spell.targetLabel = describe(spell.aims, byName);
            int cards = 0;
            for (Aim aim : spell.aims) if (!aim.player) cards++;
            spell.targetCardIds = new int[cards];
            int i = 0;
            boolean you = false;
            boolean them = false;
            for (Aim aim : spell.aims) {
                if (aim.player) {
                    if (aim.you) you = true;
                    else them = true;
                } else {
                    spell.targetCardIds[i++] = aim.cardId;
                }
            }
            spell.targetYou = you;
            spell.targetThem = them;
        }
    }

    private static void noteFace(Map<String, List<Face>> byName, Face face) {
        if (face == null || face.name == null || face.name.isBlank()) return;
        byName.computeIfAbsent(face.name, key -> new ArrayList<>()).add(face);
    }

    private static String describe(List<Aim> aims, Map<String, List<Face>> byName) {
        StringBuilder text = new StringBuilder();
        for (Aim aim : aims) {
            if (text.length() > 0) text.append(", ");
            if (aim.player) text.append(aim.name);
            else text.append(distinctName(aim, byName.get(aim.name)));
        }
        return text.toString();
    }

    private static String distinctName(Aim aim, List<Face> same) {
        if (same == null || same.size() < 2) return aim.name;
        if (aim.creature) {
            int twins = 0;
            int tappedTwins = 0;
            for (Face face : same) {
                if (!face.creature || face.power != aim.power || face.toughness != aim.toughness) continue;
                twins++;
                if (face.tapped) tappedTwins++;
            }
            String pt = aim.power + "/" + aim.toughness;
            if (twins < 2) return aim.name + " (" + pt + ")";
            if (aim.tapped && tappedTwins == 1) return aim.name + " (" + pt + ", tapped)";
            int untapped = twins - tappedTwins;
            if (!aim.tapped && tappedTwins > 0 && untapped == 1) return aim.name + " (" + pt + ")";
            return aim.name + " (" + pt + " #" + aim.cardId + ")";
        }
        if (aim.tapped) return aim.name + " (tapped)";
        return aim.name + " (#" + aim.cardId + ")";
    }

    /**
     * Keeps Forge's caption and "Ash cast Lightning Bolt", and replaces the target tail
     * with the card's printed name. The original line is remembered so it stays clean
     * after the spell leaves the stack.
     */
    private String presentLog(String original, List<Spell> stack) {
        if (original == null) return "";
        String live = rewriteFromStack(original, stack);
        if (live != null) {
            cleanLog.put(original, live);
            return live;
        }
        String cached = cleanLog.get(original);
        if (cached != null) return cached;
        String tidied = tidyTarget(original);
        if (!tidied.equals(original)) cleanLog.put(original, tidied);
        return tidied;
    }

    private static String rewriteFromStack(String line, List<Spell> stack) {
        int at = line.indexOf(" targeting ");
        if (at < 0 || stack == null) return null;
        String head = line.substring(0, at + " targeting ".length());
        Spell named = null;
        for (Spell spell : stack) {
            if (spell.targetLabel == null || spell.targetLabel.isBlank()) continue;
            if (spell.name == null || spell.name.isBlank() || !head.contains(spell.name)) continue;
            if (spell.targetCardIds != null) {
                for (int id : spell.targetCardIds) {
                    if (id != 0 && line.contains("(" + id + ")")) return head + spell.targetLabel;
                }
            }
            named = spell;
        }
        return named == null ? null : head + named.targetLabel;
    }

    /** Zone text and Forge's card id are not the name Steve reads on the card. */
    private static String tidyTarget(String line) {
        int at = line.indexOf(" targeting ");
        if (at < 0) return line;
        String head = line.substring(0, at + " targeting ".length());
        String tail = line.substring(at + " targeting ".length());
        String cleaned = TARGET_ID.matcher(tail).replaceAll("");
        cleaned = TARGET_ZONE.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("[", "").replace("]", "").trim();
        if (cleaned.isEmpty()) return line;
        return head + cleaned;
    }

    /** Printed card name, the same string the image cache uses before the art index. */
    private static String printedName(CardView source) {
        if (source == null) return "";
        String oracle = source.getOracleName();
        if (oracle != null && !oracle.isBlank()) return oracle;
        CardView.CardStateView state = source.getCurrentState();
        if (state != null) {
            String stateOracle = state.getOracleName();
            if (stateOracle != null && !stateOracle.isBlank()) return stateOracle;
            if (state.getName() != null && !state.getName().isBlank()) return state.getName();
        }
        return source.getName() == null ? "" : source.getName();
    }

    private static BattlefieldLayout.StripPhase strip(PhaseType phase) {
        if (phase == null) return BattlefieldLayout.StripPhase.MAIN_1;
        return switch (phase) {
            case UPKEEP -> BattlefieldLayout.StripPhase.UPKEEP;
            case DRAW -> BattlefieldLayout.StripPhase.DRAW;
            case MAIN1 -> BattlefieldLayout.StripPhase.MAIN_1;
            case COMBAT_BEGIN -> BattlefieldLayout.StripPhase.BEGIN_COMBAT;
            case COMBAT_DECLARE_ATTACKERS -> BattlefieldLayout.StripPhase.DECLARE_ATTACKERS;
            case COMBAT_DECLARE_BLOCKERS -> BattlefieldLayout.StripPhase.DECLARE_BLOCKERS;
            case COMBAT_FIRST_STRIKE_DAMAGE -> BattlefieldLayout.StripPhase.FIRST_STRIKE;
            case COMBAT_DAMAGE -> BattlefieldLayout.StripPhase.COMBAT_DAMAGE;
            case COMBAT_END -> BattlefieldLayout.StripPhase.END_COMBAT;
            case MAIN2 -> BattlefieldLayout.StripPhase.MAIN_2;
            case END_OF_TURN -> BattlefieldLayout.StripPhase.END_STEP;
            case CLEANUP -> BattlefieldLayout.StripPhase.CLEANUP;
            default -> BattlefieldLayout.StripPhase.MAIN_1;
        };
    }

    private void boot() {
        try {
            installBase();
            forge.ai.AiDecisionLog.setEnabled(true);
            status = "Loading Forge cards. This takes a minute.";
            ensureForge();
            status = "Reading the decks.";
            BattlefieldDecks.Choice yourPick = youChoice;
            BattlefieldDecks.Choice theirPick = ashChoice;
            Deck yours;
            Deck theirs;
            try {
                yours = BattlefieldDecks.toDeck(yourPick);
            } catch (Throwable failedDeck) {
                failed = true;
                status = deckLoadFailure("Your deck", yourPick, failedDeck);
                return;
            }
            try {
                theirs = BattlefieldDecks.toDeck(theirPick);
            } catch (Throwable failedDeck) {
                failed = true;
                status = deckLoadFailure("Ash's deck", theirPick, failedDeck);
                return;
            }
            String rulesProblem = BattlefieldDecks.rulesProblem(yours, theirs);
            if (rulesProblem != null) {
                failed = true;
                status = rulesProblem;
                return;
            }
            if (stopping) {
                open = false;
                failed = false;
                return;
            }
            BattlefieldPics.wantDeck(yours);
            BattlefieldPics.wantDeck(theirs);
            status = "Starting the duel.";
            rememberFormat(yours);
            applySeatName();
            GameType matchType = seatedType == null ? BattlefieldDecks.gameType(yours) : seatedType;
            Set<GameType> variants = matchVariants(matchType);
            var human = registerSeat(yours, variants)
                    .setPlayer(new LobbyPlayerHuman(seatName()));
            // Format free mulligan comes from MulliganService. This field is only the extra.
            human.setFreeMulligans(extraFreeMulligans);
            var ai = registerSeat(theirs, variants)
                    .setPlayer(GamePlayerUtil.createAiPlayer("Ash"));
            forge.game.GameRules rules = new forge.game.GameRules(GameType.Constructed);
            rules.setGamesPerMatch(1);
            HostedMatch hosted = new HostedMatch();
            match = hosted;
            hosted.startMatch(rules, variants.isEmpty() ? null : variants, List.of(human, ai), human, gui);
            if (stopping) {
                open = false;
                failed = false;
                try {
                    hosted.endCurrentGame();
                } catch (Throwable ignored) {
                    // Change decks already asked this match to close.
                }
                match = null;
                return;
            }
            status = "Duel started.";
        } catch (Throwable failedBoot) {
            failed = true;
            String why = failedBoot.getMessage();
            if (why == null || why.isBlank()) why = failedBoot.getClass().getSimpleName();
            status = "Could not start the duel: " + why;
            failedBoot.printStackTrace();
        }
    }

    /**
     * This seat's controller. The host keeps a local human controller.
     * The joiner's controller sends each click over the match connection.
     */
    private IGameController seatController() {
        if (joinerSeat()) {
            if (gui.human != null) {
                IGameController owned = gui.getGameController(gui.human);
                if (owned instanceof NetGameController) return owned;
            }
            IGameController current = gui.getGameController();
            if (current instanceof NetGameController) return current;
            for (IGameController controller : gui.getOriginalGameControllers()) {
                if (controller instanceof NetGameController) return controller;
            }
        }
        if (gui.human != null) {
            IGameController owned = gui.getGameController(gui.human);
            if (owned != null) return owned;
        }
        IGameController current = gui.getGameController();
        if (current != null) return current;
        for (IGameController controller : gui.getOriginalGameControllers()) {
            if (controller != null) return controller;
        }
        return null;
    }

    /** Tiny's Join window. The host has a local match and must not take this path. */
    private boolean joinerSeat() {
        return netClient != null;
    }

    /** Cards, prefs, and the headless GUI base. Safe to call again. */
    private static void ensureForge() {
        installBase();
        forge.ai.AiDecisionLog.setEnabled(true);
        if (forge.StaticData.instance() == null) {
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                prefs.setPref(FPref.UI_ANTE, false);
                prefs.setPref(FPref.MATCH_AI_TIMEOUT, "5");
                prefs.setPref(FPref.UI_MATCHES_PER_GAME, "1");
                // Same actionable set the desktop board outlines, including cards outside the hand.
                prefs.setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, true);
                prefs.setPref(FPref.YIELD_AVAILABLE_ACTIONS_BUDGET_MS, "3000");
                prefs.setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, true);
                prefs.setPref(FPref.UI_DISABLE_CARD_IMAGES, false);
                return null;
            });
        }
        FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
        FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, "1");
        FModel.getPreferences().setPref(FPref.UI_ANTE, false);
        FModel.getPreferences().setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, true);
        FModel.getPreferences().setPref(FPref.UI_DISABLE_CARD_IMAGES, false);
    }

    /** Picker name for this window. Must run before Host bind / Join connect. */
    void usePlayerName(String name) {
        chosenName = cleanPlayerName(name);
    }

    static String cleanPlayerName(String raw) {
        if (raw == null) return "";
        String text = raw.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').trim();
        text = text.replaceAll(" +", " ");
        if (text.length() > 32) text = text.substring(0, 32).trim();
        return text;
    }

    /**
     * Writes this window's name into Forge before the lobby accepts anyone.
     * Always overwrites Human / blank so two isolated user dirs cannot both sit as Human.
     */
    private void applySeatName() {
        String typed = cleanPlayerName(chosenName);
        String name = typed;
        if (name.isBlank()) {
            try {
                name = cleanPlayerName(FModel.getPreferences().getPref(FPref.PLAYER_NAME));
            } catch (Throwable ignored) {
                name = "";
            }
        }
        if (name.isBlank() || (typed.isBlank() && "human".equalsIgnoreCase(name))) {
            name = cleanPlayerName(System.getProperty("user.name"));
        }
        if (name.isBlank() || (typed.isBlank() && "human".equalsIgnoreCase(name))) name = "Player";
        chosenName = name;
        try {
            FModel.getPreferences().setPref(FPref.PLAYER_NAME, name);
            FModel.getPreferences().save();
        } catch (Throwable ignored) {
            // The in-memory name is enough for this match.
        }
        try {
            GamePlayerUtil.getGuiPlayer().setName(name);
        } catch (Throwable ignored) {
            // LobbyPlayerHuman still carries the seat name below.
        }
    }

    private String seatName() {
        String name = cleanPlayerName(chosenName);
        if (!name.isBlank()) return name;
        try {
            String current = FModel.getPreferences().getPref(FPref.PLAYER_NAME);
            if (current != null && !current.isBlank()) return current.trim();
        } catch (Throwable ignored) {
        }
        return "Player";
    }

    /**
     * Steve hosts on Forge's match port. His deck is the local seat.
     * The other seat stays open until Tiny connects and sends her deck.
     */
    void host(BattlefieldDecks.Choice yours) {
        if (yours != null) youChoice = yours;
        final int gen = netGen.incrementAndGet();
        stopping = false;
        failed = false;
        open = false;
        netSession = true;
        cachedYourStart = 0;
        cachedTheirStart = 0;
        seenMulligans = 0;
        clearOpeningSort();
        resetPregamePass();
        netLaunched.set(false);
        loggedRules = null;
        lobbyLan = "";
        clientSlot = -1;
        publishHosting(gen, "");
        readLanInBackground(gen);
        Thread worker = new Thread(() -> {
            synchronized (matchGate) {
                if (!lobbyLive(gen)) return;
                gui = new MatchGui();
                gui.born = gen;
                netGui = gui;
            }
            hostLobby(gen);
        }, "Duel host");
        worker.setDaemon(true);
        gameThread = worker;
        worker.start();
    }

    /**
     * Tiny joins Steve's match and sends the deck she picked.
     * Her window only answers her own prompts.
     */
    void join(String address, BattlefieldDecks.Choice yours) {
        if (yours != null) youChoice = yours;
        String target = address == null ? "" : address.trim();
        final int gen = netGen.incrementAndGet();
        stopping = false;
        failed = false;
        open = false;
        netSession = true;
        cachedYourStart = 0;
        cachedTheirStart = 0;
        seenMulligans = 0;
        clearOpeningSort();
        resetPregamePass();
        netLaunched.set(false);
        loggedRules = null;
        clientSlot = -1;
        clientLobby = null;
        publishJoining(gen, target, "");
        Thread worker = new Thread(() -> {
            synchronized (matchGate) {
                if (!lobbyLive(gen)) return;
                gui = new MatchGui();
                gui.born = gen;
            }
            joinLobby(gen, target);
        }, "Duel join");
        worker.setDaemon(true);
        gameThread = worker;
        worker.start();
    }

    /** Host clicks Start. The lobby stays up until the dealt hand is in the picture. */
    void requestStart() {
        final int gen = netGen.get();
        if (!lobbyLive(gen)) return;
        LobbyCard card = lobbyCard;
        if (card == null || !card.hostSide || !card.canStart || card.starting || failed || stopping) return;
        if (!netLaunched.compareAndSet(false, true)) return;
        lobbyCard = card.withStarting();
        status = "Starting the match.";
        Thread starter = new Thread(() -> startNetMatch(gen), "Duel start");
        starter.setDaemon(true);
        gameThread = starter;
        starter.start();
    }

    /**
     * Leaves the lobby or a failed connect and returns the window to the deck picker.
     * Closes the guest socket immediately and unbinds 36743 on a background thread.
     */
    void leaveLobby() {
        stopping = true;
        netGen.incrementAndGet();
        failed = false;
        open = false;
        netSession = false;
        netLaunched.set(false);
        lobbyCard = null;
        Choice pending = choice;
        if (pending != null) pending.forceClose();
        XAsk pendingX = xAsk;
        if (pendingX != null) pendingX.forceClose();
        final FGameClient client = netClient;
        netClient = null;
        Thread worker = new Thread(() -> {
            if (client != null) {
                try {
                    client.close();
                } catch (Throwable ignored) {
                    // The picker is already up.
                }
            }
            synchronized (matchGate) {
                clientLobby = null;
                clientSlot = -1;
                netGui = null;
                // boundGen == netGen means a newer Host already took the port. Leave that listen up.
                if (boundGen != 0 && boundGen != netGen.get()) releaseBoundServer();
            }
        }, "leave lobby");
        worker.setDaemon(true);
        worker.start();
    }

    private boolean lobbyLive(int gen) {
        return !stopping && netGen.get() == gen;
    }

    /** Unbinds 36743. Caller holds {@link #matchGate}. */
    private void releaseBoundServer() {
        try {
            FServerManager.getInstance().stopServer();
        } catch (Throwable ignored) {
            // Stopping a server that already closed is fine.
        }
        boundGen = 0;
        weHosted = false;
        serverLobby = null;
    }

    private void releaseNet() {
        netGui = null;
        serverLobby = null;
        clientLobby = null;
        clientSlot = -1;
        FGameClient client = netClient;
        netClient = null;
        if (client != null) {
            try {
                client.close();
            } catch (Throwable ignored) {
                // The window is already leaving the match.
            }
        }
        if (weHosted) {
            weHosted = false;
            boundGen = 0;
            try {
                FServerManager.getInstance().stopServer();
            } catch (Throwable ignored) {
                // Stopping a server that already closed is fine.
            }
        }
    }

    private void hostLobby(int gen) {
        try {
            if (!lobbyLive(gen)) return;
            ensureForge();
            applySeatName();
            publishHosting(gen, "");
            Deck yours;
            try {
                yours = BattlefieldDecks.toDeck(youChoice);
            } catch (Throwable failedDeck) {
                if (!lobbyLive(gen)) return;
                failed = true;
                String line = deckLoadFailure("Your deck", youChoice, failedDeck);
                status = line;
                publishHosting(gen, line);
                return;
            }
            if (!lobbyLive(gen)) return;
            stampDeckName(yours);
            rememberFormat(yours);
            NetworkLogConfig.activateNetworkLogging();
            synchronized (matchGate) {
                if (!lobbyLive(gen)) return;
                if (boundGen != gen) releaseBoundServer();
                FServerManager server = FServerManager.getInstance();
                try {
                    server.startServer(DUEL_PORT, null, Boolean.FALSE);
                } catch (Throwable failedBind) {
                    releaseBoundServer();
                    if (!lobbyLive(gen)) return;
                    failed = true;
                    String line = hostFailure(failedBind);
                    status = line;
                    publishHosting(gen, line);
                    noteFailure("host", failedBind);
                    return;
                }
                if (!lobbyLive(gen)) {
                    releaseBoundServer();
                    return;
                }
                boundGen = gen;
                weHosted = true;
                ServerGameLobby lobby = new ServerGameLobby();
                serverLobby = lobby;
                server.setLobby(lobby);
                lobby.setListener(new IUpdateable() {
                    @Override public void update(boolean fullUpdate) {
                        if (!lobbyLive(gen)) return;
                        try {
                            server.updateLobbyState();
                        } catch (Throwable ignored) {
                            // A lobby refresh must not stop the match from starting.
                        }
                        publishHosting(gen, "");
                    }
                    @Override public void update(int slot, LobbySlotType type) { }
                });
                offerDeck(server, 0, yours);
                server.updateSlot(0, UpdateLobbyPlayerEvent.nameUpdate(seatName()));
                server.updateSlot(0, UpdateLobbyPlayerEvent.isReadyUpdate(true));
                publishHosting(gen, "");
            }
        } catch (Throwable failedHost) {
            synchronized (matchGate) {
                if (boundGen == gen) releaseBoundServer();
            }
            if (!lobbyLive(gen)) return;
            failed = true;
            String line = hostFailure(failedHost);
            status = line;
            publishHosting(gen, line);
            noteFailure("host", failedHost);
        }
    }

    /** Commander, Brawl, or constructed, from the deck this window is sitting with. */
    private void rememberFormat(Deck deck) {
        try {
            seatedType = deck == null ? GameType.Constructed : BattlefieldDecks.gameType(deck);
        } catch (RuntimeException ignored) {
            seatedType = GameType.Constructed;
        }
    }

    /** The picker label is the deck's name when Forge stored a blank one. */
    private void stampDeckName(Deck deck) {
        if (deck == null) return;
        if (deck.getName() != null && !deck.getName().isBlank()) return;
        String label = youChoice == null ? null : youChoice.label;
        if (label != null && !label.isBlank()) deck.setName(label);
    }

    /** Deck object plus the plain name, so a slot update still has a title if the list is heavy. */
    private static void offerDeck(FServerManager server, int slot, Deck deck) {
        server.updateSlot(slot, UpdateLobbyPlayerEvent.deckUpdate(deck));
        String name = deck == null ? null : deck.getName();
        if (name != null && !name.isBlank()) {
            server.updateSlot(slot, UpdateLobbyPlayerEvent.setDeckSchemePlaneVanguard(name, null, null, null));
        }
    }

    private void publishHosting(int gen, String problem) {
        if (!lobbyLive(gen)) return;
        String youName = safeSeatName();
        String youDeck = youChoice == null || youChoice.label == null ? "" : youChoice.label;
        String themName = "";
        String themDeck = "";
        boolean guest = false;
        ServerGameLobby lobby = serverLobby;
        String rules = null;
        if (lobby != null) {
            LobbySlot you = lobby.getSlot(0);
            LobbySlot them = lobby.getSlot(1);
            if (you != null) {
                if (you.getName() != null && !you.getName().isBlank()) youName = you.getName();
                String fromSlot = deckTitle(you);
                if (!fromSlot.isBlank()) youDeck = fromSlot;
            }
            if (them != null && them.getType() == LobbySlotType.REMOTE) {
                guest = true;
                themName = them.getName() == null ? "" : them.getName();
                themDeck = deckTitle(them);
            }
            if (!youDeck.isBlank() && !themDeck.isBlank() && you != null && them != null
                    && you.getDeck() != null && them.getDeck() != null) {
                rules = BattlefieldDecks.rulesProblem(you.getDeck(), them.getDeck());
            }
        }
        if (rules != null) {
            problem = rules;
            if (!rules.equals(loggedRules)) {
                loggedRules = rules;
                noteFailure("format", new IllegalStateException(rules));
                try {
                    FServerManager.getInstance().broadcast(new MessageEvent(rules));
                } catch (Throwable ignored) {
                    // The lobby line is already the rules problem.
                }
            }
        }
        LobbyCard previous = lobbyCard;
        boolean starting = previous != null && previous.starting;
        boolean both = !youDeck.isBlank() && !themDeck.isBlank();
        String shownProblem = problem == null ? "" : problem;
        boolean can = both && shownProblem.isBlank() && !failed && !stopping && !starting && guest;
        String title = !shownProblem.isBlank() ? shownProblem
                : starting ? "Starting the match." : "Hosting";
        String wait = !shownProblem.isBlank() || starting ? ""
                : (!guest || !both) ? "Waiting for the other player." : "";
        if (!lobbyLive(gen)) return;
        status = title;
        lobbyCard = new LobbyCard(true, can, starting, title, "127.0.0.1:" + DUEL_PORT, lobbyLan,
                wait, youName, youDeck, themName, themDeck, shownProblem);
    }

    /**
     * After the hosting line is already on screen. Enumerates interfaces only.
     * Never opens a socket to 8.8.8.8, and never runs on the paint thread.
     */
    private void readLanInBackground(int gen) {
        Thread lan = new Thread(() -> {
            String ip = lanIpv4();
            if (ip == null || ip.isBlank() || !lobbyLive(gen)) return;
            lobbyLan = ip;
            LobbyCard card = lobbyCard;
            if (!lobbyLive(gen)) return;
            if (card != null && card.hostSide && !card.starting) publishHosting(gen, card.problem);
            else if (card != null && card.hostSide) lobbyCard = card.withLan(ip);
        }, "Duel lan");
        lan.setDaemon(true);
        lan.start();
    }

    /**
     * First Tailscale-looking 100.x, else the first other interface IPv4.
     * Interface list only — no 8.8.8.8 UDP, no reverse DNS.
     */
    private static String lanIpv4() {
        String tail = "";
        String other = "";
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return "";
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface == null || !iface.isUp() || iface.isLoopback()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    String ip = addr.getHostAddress();
                    if (ip == null || ip.isBlank() || ip.startsWith("127.")) continue;
                    if (ip.startsWith("100.") && tail.isEmpty()) tail = ip;
                    else if (other.isEmpty()) other = ip;
                }
            }
        } catch (Throwable ignored) {
            // Loopback is already on screen.
        }
        return !tail.isEmpty() ? tail : other;
    }

    /**
     * Turns a sloppy Join paste into {@code host:port}. Accepts MagicDNS names,
     * {@code https://machine.tailxxxx.ts.net}, {@code host:port}, and {@code 100.x}.
     * Strips scheme, path, query, quotes, and whitespace. No DNS. Empty if nothing usable.
     */
    static String normalizeJoinAddress(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isEmpty()) return "";
        int breakAt = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                breakAt = i;
                break;
            }
        }
        if (breakAt >= 0) text = text.substring(0, breakAt).trim();
        if (text.length() >= 2) {
            char a = text.charAt(0);
            char b = text.charAt(text.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'') || (a == '<' && b == '>')) {
                text = text.substring(1, text.length() - 1).trim();
            }
        }
        if (text.isEmpty()) return "";
        int scheme = text.indexOf("://");
        if (scheme > 0 && scheme < 16 && schemeLooksSafe(text.substring(0, scheme))) {
            text = text.substring(scheme + 3);
        }
        int at = text.lastIndexOf('@');
        if (at >= 0) text = text.substring(at + 1);
        int cut = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' || c == '?' || c == '#' || c == '\\') {
                cut = i;
                break;
            }
        }
        if (cut >= 0) text = text.substring(0, cut);
        text = text.trim();
        if (text.endsWith(".") && text.length() > 1) text = text.substring(0, text.length() - 1);
        if (text.endsWith(":") && text.length() > 1 && text.charAt(0) != '[') {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) return "";
        int port = DUEL_PORT;
        int colon = text.lastIndexOf(':');
        if (colon > 0 && colon < text.length() - 1 && text.charAt(0) != '[') {
            String maybePort = text.substring(colon + 1);
            if (!maybePort.isEmpty() && digitsOnly(maybePort)) {
                try {
                    int parsed = Integer.parseInt(maybePort);
                    if (parsed > 0 && parsed <= 65535) {
                        port = parsed;
                        text = text.substring(0, colon);
                    }
                } catch (NumberFormatException ignored) {
                    // Keep the default duel port.
                }
            }
        }
        String host = text.trim();
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.isEmpty()) return "";
        return host + ":" + port;
    }

    private static boolean schemeLooksSafe(String scheme) {
        if (scheme == null || scheme.isEmpty()) return false;
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (c >= 'A' && c <= 'Z') continue;
            if (c >= 'a' && c <= 'z') continue;
            if (i > 0 && ((c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.')) continue;
            return false;
        }
        return true;
    }

    private static boolean digitsOnly(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private String safeSeatName() {
        try {
            return seatName();
        } catch (Throwable ignored) {
            return "Player";
        }
    }

    private static String deckTitle(LobbySlot slot) {
        if (slot == null) return "";
        Deck deck = slot.getDeck();
        if (deck != null) {
            String name = deck.getName();
            if (name != null && !name.isBlank()) return name;
        }
        String named = slot.getDeckName();
        return named == null || named.isBlank() ? "" : named;
    }

    private static String hostFailure(Throwable failed) {
        Throwable root = failed;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String text = why(failed).toLowerCase(Locale.ROOT);
        if (root instanceof java.net.BindException
                || text.contains("already in use")
                || text.contains("address already")) {
            return "Port " + DUEL_PORT + " is already in use. Close the other host window and try Host again.";
        }
        return "Could not host: " + why(failed);
    }

    private void startNetMatch(int gen) {
        if (!lobbyLive(gen) || failed) {
            netLaunched.set(false);
            return;
        }
        ServerGameLobby lobby = serverLobby;
        if (lobby == null) {
            netLaunched.set(false);
            return;
        }
        try {
            LobbySlot you = lobby.getSlot(0);
            LobbySlot them = lobby.getSlot(1);
            if (you == null || them == null || them.getType() != LobbySlotType.REMOTE
                    || you.getDeck() == null || them.getDeck() == null) {
                netLaunched.set(false);
                LobbyCard card = lobbyCard;
                if (card != null) {
                    lobbyCard = new LobbyCard(true, false, false, card.title, card.address, card.lan,
                            "Waiting for the other player.", card.youName, card.youDeck,
                            card.themName, card.themDeck, card.problem);
                }
                publishHosting(gen, "");
                return;
            }
            String problem = BattlefieldDecks.rulesProblem(you.getDeck(), them.getDeck());
            if (problem != null) {
                netLaunched.set(false);
                failed = false;
                status = problem;
                publishHosting(gen, problem);
                return;
            }
            GameType kind = BattlefieldDecks.gameType(you.getDeck());
            if (kind != GameType.Constructed && !lobby.hasVariant(kind)) {
                lobby.applyVariant(kind);
            }
            FModel.getPreferences().setPref(FPref.UI_MATCHES_PER_GAME, "1");
            FModel.getPreferences().setPref(FPref.ENFORCE_DECK_LEGALITY, false);
            status = "Starting the match.";
            Runnable start = lobby.startGame();
            if (start == null) {
                netLaunched.set(false);
                String line = "Could not start the duel.";
                status = line;
                publishHosting(gen, line);
                return;
            }
            start.run();
            match = lobby.getHostedMatch();
            status = "Starting the match.";
        } catch (Throwable failedStart) {
            netLaunched.set(false);
            if (!lobbyLive(gen)) return;
            failed = true;
            String line = "Could not start the duel: " + why(failedStart);
            status = line;
            publishHosting(gen, line);
            noteFailure("host start", failedStart);
        }
    }

    private void joinLobby(int gen, String address) {
        String where = address;
        try {
            if (!lobbyLive(gen)) return;
            ensureForge();
            applySeatName();
            String ready = normalizeJoinAddress(address);
            URLValidator.HostPort parsed = ready.isBlank() ? null : URLValidator.parseURL(ready);
            if (parsed == null || parsed.host() == null || parsed.host().isBlank()) {
                if (!lobbyLive(gen)) return;
                failed = true;
                String line = "Type or paste the address Steve is hosting, like 127.0.0.1:" + DUEL_PORT + ".";
                status = line;
                publishJoining(gen, address, line);
                return;
            }
            Integer rawPort = parsed.port();
            int port = rawPort == null || rawPort < 0 ? DUEL_PORT : rawPort;
            final String seatedAt = parsed.host() + ":" + port;
            where = seatedAt;
            publishJoining(gen, seatedAt, "");
            Deck yours;
            try {
                yours = BattlefieldDecks.toDeck(youChoice);
            } catch (Throwable failedDeck) {
                if (!lobbyLive(gen)) return;
                failed = true;
                String line = deckLoadFailure("Your deck", youChoice, failedDeck);
                status = line;
                publishJoining(gen, where, line);
                return;
            }
            if (!lobbyLive(gen)) return;
            stampDeckName(yours);
            rememberFormat(yours);
            NetworkLogConfig.activateNetworkLogging();
            String name = seatName();
            if (!lobbyLive(gen)) return;
            FGameClient client = new FGameClient(name, gui, parsed.host(), port);
            netClient = client;
            if (!lobbyLive(gen)) {
                try {
                    client.close();
                } catch (Throwable ignored) {
                    // Back already left this connect.
                }
                if (netClient == client) netClient = null;
                return;
            }
            ClientGameLobby lobby = new ClientGameLobby();
            clientLobby = lobby;
            gui.setClientLobby(lobby);
            CountDownLatch seated = new CountDownLatch(1);
            client.addLobbyListener(new ILobbyListener() {
                @Override public void message(String source, String message, forge.gamemodes.net.ChatMessage.MessageType type) {
                    if (!lobbyLive(gen) || message == null || message.isBlank() || open || lobbyChatter(message)) return;
                    status = message;
                    publishJoining(gen, seatedAt, message);
                }
                @Override public void update(forge.gamemodes.match.GameLobby.GameLobbyData state, int slot) {
                    if (!lobbyLive(gen)) {
                        seated.countDown();
                        return;
                    }
                    if (state != null) {
                        lobby.setLocalPlayer(slot);
                        lobby.setData(state);
                    }
                    if (slot >= 0) {
                        clientSlot = slot;
                        seated.countDown();
                    }
                    publishJoined(gen, seatedAt, "");
                }
                @Override public void close() {
                    if (!open && lobbyLive(gen)) {
                        failed = true;
                        String line = "The connection to Steve closed.";
                        status = line;
                        publishJoining(gen, seatedAt, line);
                    }
                    seated.countDown();
                }
                @Override public ClientGameLobby getLobby() {
                    return lobby;
                }
            });
            status = "Connecting to " + where;
            if (!lobbyLive(gen)) return;
            // One socket only. A probe connect-and-close shows up on the host as
            // a player who joined and instantly dropped, then the real login.
            client.connect();
            if (!seated.await(30, TimeUnit.SECONDS)) {
                if (!lobbyLive(gen)) return;
                failed = true;
                String line = "Timed out waiting for " + where + ".";
                status = line;
                publishJoining(gen, where, line);
                noteFailure("join", new java.net.SocketTimeoutException(line));
                client.close();
                if (netClient == client) netClient = null;
                return;
            }
            if (!lobbyLive(gen) || failed) return;
            client.send(UpdateLobbyPlayerEvent.nameUpdate(seatName()));
            client.send(UpdateLobbyPlayerEvent.deckUpdate(yours));
            String deckName = yours.getName();
            if (deckName != null && !deckName.isBlank()) {
                client.send(UpdateLobbyPlayerEvent.setDeckSchemePlaneVanguard(deckName, null, null, null));
            }
            client.send(UpdateLobbyPlayerEvent.isReadyUpdate(true));
            publishJoined(gen, where, "");
        } catch (Throwable failedJoin) {
            if (!lobbyLive(gen)) return;
            failed = true;
            String line = joinFailure(where, failedJoin);
            status = line;
            publishJoining(gen, where, line);
            noteFailure("join", failedJoin);
        }
    }

    private void publishJoining(int gen, String address, String problem) {
        if (!lobbyLive(gen)) return;
        String shown = problem == null ? "" : problem;
        String title = shown.isBlank() ? "Connecting to " + address : shown;
        status = title;
        LobbyCard previous = lobbyCard;
        if (!lobbyLive(gen)) return;
        lobbyCard = new LobbyCard(false, false, false, title, address == null ? "" : address, "",
                "", previous == null ? "" : previous.youName, previous == null ? "" : previous.youDeck,
                previous == null ? "" : previous.themName, previous == null ? "" : previous.themDeck, shown);
    }

    /** Names and deck titles copied off the lobby update Forge already sent. */
    private void publishJoined(int gen, String address, String problem) {
        if (!lobbyLive(gen)) return;
        ClientGameLobby lobby = clientLobby;
        int me = clientSlot;
        String youName = "";
        String youDeck = "";
        String themName = "";
        String themDeck = "";
        String rules = null;
        if (lobby != null && me >= 0) {
            LobbySlot mine = lobby.getSlot(me);
            youName = mine == null || mine.getName() == null ? "" : mine.getName();
            youDeck = deckTitle(mine);
            int slots = lobby.getNumberOfSlots();
            for (int i = 0; i < slots; i++) {
                if (i == me) continue;
                LobbySlot other = lobby.getSlot(i);
                if (other == null || other.getType() == LobbySlotType.OPEN) continue;
                themName = other.getName() == null ? "" : other.getName();
                themDeck = deckTitle(other);
                if (mine != null && mine.getDeck() != null && other.getDeck() != null) {
                    rules = BattlefieldDecks.rulesProblem(mine.getDeck(), other.getDeck());
                }
                break;
            }
        }
        String shown = problem == null || problem.isBlank() ? (rules == null ? "" : rules) : problem;
        if (rules != null && !rules.equals(loggedRules)) {
            loggedRules = rules;
            noteFailure("format", new IllegalStateException(rules));
        }
        boolean seated = me >= 0 && shown.isBlank() && !failed;
        String title = !shown.isBlank() ? shown
                : seated ? "Connected. Waiting for the host to start."
                : "Connecting to " + address;
        if (!lobbyLive(gen)) return;
        status = title;
        lobbyCard = new LobbyCard(false, false, false, title, address == null ? "" : address, "",
                "", youName, youDeck, themName, themDeck, shown);
    }

    private static boolean lobbyChatter(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("joined the lobby")
                || lower.contains("is ready")
                || lower.contains("is not ready")
                || lower.contains("reconnected")
                || lower.contains("forge version")
                || lower.contains("changed their name");
    }

    private static void logLine(String line) {
        try {
            Files.writeString(Path.of("A:/forge_adventure/battlefield-prototype.log"),
                    line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            /* the window is more important than the log */
        }
    }

    private void logCommanderZones(String when, PlayerView you, PlayerView them) {
        StringBuilder text = new StringBuilder();
        text.append("commander-zones ").append(when).append('\n');
        appendCommanderZones(text, "you", you);
        appendCommanderZones(text, "ash", them);
        logLine(text.toString().trim());
    }

    private static void appendCommanderZones(StringBuilder text, String who, PlayerView player) {
        if (player == null) {
            text.append("  ").append(who).append(": none\n");
            return;
        }
        List<CardView> commanders;
        try {
            commanders = player.getCommanders();
        } catch (RuntimeException ignored) {
            commanders = null;
        }
        if (commanders == null || commanders.isEmpty()) {
            text.append("  ").append(who).append(": no commanders\n");
            return;
        }
        for (CardView commander : commanders) {
            if (commander == null) continue;
            ZoneType zone = null;
            try {
                zone = commander.getZone();
            } catch (RuntimeException ignored) {
                /* keep null */
            }
            boolean inCommandList = commanderInCommandZone(commander, player) != null;
            text.append("  ").append(who).append(' ').append(commander.getName());
            text.append(" id=").append(commander.getId());
            text.append(" zone=").append(zone == null ? "null" : zone.name());
            text.append(" inCommandList=").append(inCommandList);
            text.append(" grave=").append(containsCard(player.getGraveyard(), commander.getId()));
            text.append(" exile=").append(containsCard(player.getExile(), commander.getId()));
            text.append(" library=").append(containsCard(player.getLibrary(), commander.getId()));
            text.append(" hand=").append(containsCard(player.getHand(), commander.getId()));
            text.append(" board=").append(containsCard(player.getBattlefield(), commander.getId()));
            text.append(" command=").append(containsCard(player.getCommand(), commander.getId()));
            text.append('\n');
        }
    }

    private static boolean containsCard(Iterable<CardView> cards, int id) {
        if (cards == null) return false;
        try {
            for (CardView card : cards) {
                if (card != null && card.getId() == id) return true;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    /** Appends the stack to battlefield-prototype.log. The picker still shows the short line. */
    private static void noteFailure(String where, Throwable failed) {
        if (failed != null) failed.printStackTrace();
        try {
            StringBuilder text = new StringBuilder();
            text.append('\n').append(where).append(": ");
            text.append(failed == null ? "unknown" : failed).append('\n');
            if (failed != null) {
                for (StackTraceElement frame : failed.getStackTrace()) {
                    text.append("  at ").append(frame).append('\n');
                    if (text.length() > 6000) break;
                }
            }
            Files.writeString(Path.of("A:/forge_adventure/battlefield-prototype.log"), text.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // The picker line is already set.
        }
    }

    /**
     * Forge applies Commander and Brawl as variants on a constructed match.
     * That is what sets 40 or 25 life, the command zone, and whether 21 commander damage loses.
     */
    private static Set<GameType> matchVariants(GameType kind) {
        if (kind == null || kind == GameType.Constructed) return Set.of();
        return EnumSet.of(kind);
    }

    private static forge.game.player.RegisteredPlayer registerSeat(Deck deck, Set<GameType> variants) {
        if (variants == null || variants.isEmpty()) return new forge.game.player.RegisteredPlayer(deck);
        return forge.game.player.RegisteredPlayer.forVariants(
                2, variants, deck, null, false, null, deck.get(DeckSection.Avatar));
    }

    private static String joinFailure(String address, Throwable failed) {
        Throwable root = failed;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String where = address == null || address.isBlank() ? "that address" : address;
        if (root instanceof java.net.ConnectException) {
            return "No host answered at " + where + ". Host the match in the other window first.";
        }
        if (root instanceof java.net.UnknownHostException) {
            return "That address could not be found: " + where + ".";
        }
        if (root instanceof java.net.SocketTimeoutException) {
            return "Timed out waiting for " + where + ".";
        }
        String text = why(failed);
        if (text.toLowerCase(Locale.ROOT).contains("refused")) {
            return "No host answered at " + where + ". Host the match in the other window first.";
        }
        return "Could not join " + where + ": " + text;
    }

    private static String why(Throwable failed) {
        String text = failed.getMessage();
        if (text == null || text.isBlank()) text = failed.getClass().getSimpleName();
        return text;
    }

    private static String deckLoadFailure(String side, BattlefieldDecks.Choice choice, Throwable failedDeck) {
        String why = failedDeck.getMessage();
        if (why == null || why.isBlank()) why = failedDeck.getClass().getSimpleName();
        String name = choice == null ? side : choice.label;
        if (choice != null && choice.file != null) name = choice.file.getFileName().toString();
        String line = "Could not load " + name + ": " + why;
        System.err.println(line);
        return line;
    }

    private void tendResolveAll() {
        if (!resolveAll) return;
        if (choice != null || xAsk != null || blocksResolveAll(humanInput()) || !realStackPending()) {
            haltResolveAll();
        }
    }

    /** Mana, targets, and other prompts are his. Passing priority is not. */
    private static boolean blocksResolveAll(Input input) {
        if (input == null || input instanceof InputPassPriority || input instanceof InputLockUI) return false;
        return true;
    }

    private boolean realStackPending() {
        if (gui == null) return false;
        GameView view = gui.getGameView();
        return view != null && view.getStack() != null && !view.getStack().isEmpty();
    }

    /**
     * The stack object stays on screen. If he has a legal response, he Passes himself.
     * If he cannot respond, wait a readable beat, then auto-pass. Empty priority
     * with nothing he can do is {@link #considerEmptyPass}, not a Pass-button click.
     */
    /** Hosted Ash match, or a Tailscale host/join that already left the picker. */
    private boolean matchRunning() {
        return gui != null && !failed && !stopping && (match != null || netSession);
    }

    /**
     * Host Input, or the stock Join prompt for that Input.
     * Null Input is not Pass — toss, keep, pay, and select stay OTHER.
     */
    private enum SeatPrompt { PASS, ATTACK, BLOCK, OTHER }

    /**
     * This seat is sitting on empty or stack priority and may Pass.
     * Join answers the same updateButtons / setCombatDeclareInput the desktop
     * client gets — not a missing local Input object.
     */
    boolean priorityWaiting() {
        if (!matchRunning() || pregameChoice()) return false;
        SeatPrompt kind = seatPrompt();
        return (kind == SeatPrompt.PASS || kind == SeatPrompt.ATTACK || kind == SeatPrompt.BLOCK)
                && gui.okEnabled;
    }

    /**
     * Local PlayerControllerHuman Input on Host / Ash. On Join the Input lives
     * on the host; stock IGuiGame already delivered the buttons and combat flag.
     */
    private SeatPrompt seatPrompt() {
        try {
            return seatPrompt(humanInput());
        } catch (RuntimeException ignored) {
            return seatPrompt(null);
        }
    }

    private SeatPrompt seatPrompt(Input input) {
        if (input instanceof InputPassPriority) return SeatPrompt.PASS;
        if (input instanceof InputAttack) return SeatPrompt.ATTACK;
        if (input instanceof InputBlock) return SeatPrompt.BLOCK;
        if (input != null) return SeatPrompt.OTHER;
        if (!joinerSeat() || gui == null) return SeatPrompt.OTHER;
        if (gui.isCombatDeclareAttackersInput()) return SeatPrompt.ATTACK;
        if (gui.isCombatDeclareBlockersInput()) return SeatPrompt.BLOCK;
        if (guestPassPriorityPrompt()) return SeatPrompt.PASS;
        return SeatPrompt.OTHER;
    }

    /**
     * Stock InputPassPriority buttons: OK or Pass, plus End Turn or Undo.
     * Keep, Play, Yes, Accept, look-at, and pay do not use that pair.
     */
    private boolean guestPassPriorityPrompt() {
        if (gui == null || !gui.okEnabled) return false;
        if (pregameChoice()) return false;
        if (choice != null || xAsk != null || choosingTarget()) return false;
        if (cardSheetOpen() || pickSheetOpen() || modeSheetOpen() || listSheetActive()) return false;
        if (asksSomething(gui.prompt) || triggerUseAsk(gui.prompt)) return false;
        String ok = gui.okLabel == null ? "" : gui.okLabel.trim();
        String cancel = gui.cancelLabel == null ? "" : gui.cancelLabel.trim();
        if (!plainOk(ok) && !"Pass".equalsIgnoreCase(ok)) return false;
        String cancelLow = cancel.toLowerCase(Locale.ROOT);
        if (!cancelLow.startsWith("end turn") && !cancelLow.startsWith("undo")) return false;
        return thisSeatMayPass();
    }

    /**
     * Toss, play-or-draw, keep, and London bottom. picture() must not Pass here.
     * Forge's mulligan flag is only the bottom pick; Keep uses the button labels.
     */
    private boolean pregameChoice() {
        if (answeringMulligan() || otherMulligan() || lobbyPrompt() || hostPregame()) return true;
        GameView view = gui == null ? null : gui.getGameView();
        if (view != null && view.isMulligan()) return true;
        if (view != null && view.getPhase() == null) return true;
        String cancel = gui == null || gui.cancelLabel == null ? "" : gui.cancelLabel;
        if ("Mulligan".equals(cancel) || "Auto".equals(cancel) || "Draw".equals(cancel)) return true;
        String ok = gui == null || gui.okLabel == null ? "" : gui.okLabel;
        if ("Keep".equals(ok) || "Play".equals(ok)) return true;
        String prompt = gui == null || gui.prompt == null ? "" : gui.prompt.toLowerCase(Locale.ROOT);
        return prompt.contains("mulligan") || prompt.contains("play or draw")
                || prompt.contains("would you like to play")
                || prompt.contains("won the coin")
                || prompt.contains("keep your hand")
                || prompt.contains("do you want to keep")
                || prompt.contains("going first");
    }

    /** Host game object is still in mulligan. The joiner has no Game. */
    private boolean hostPregame() {
        if (match == null) return false;
        forge.game.Game game = match.getGame();
        if (game == null) return false;
        forge.game.GameStage age = game.getAge();
        return age == forge.game.GameStage.BeforeMulligan || age == forge.game.GameStage.Mulligan;
    }

    private boolean playOrDrawPrompt() {
        if (gui == null) return false;
        String ok = gui.okLabel == null ? "" : gui.okLabel;
        String cancel = gui.cancelLabel == null ? "" : gui.cancelLabel;
        if ("Play".equals(ok) && "Draw".equals(cancel)) return true;
        String prompt = gui.prompt == null ? "" : gui.prompt.toLowerCase(Locale.ROOT);
        return prompt.contains("play or draw") || prompt.contains("would you like to play");
    }

    private void notePregameEdge() {
        boolean now = pregameChoice();
        if (lastPregame && !now) noteKeepOrLeavePregame();
        lastPregame = now;
    }

    /** Keep done or the game just left toss/mulligan. Do not carry a Pass into T1. */
    private void noteKeepOrLeavePregame() {
        resetEmptyPassLatch();
        resyncAwaitPrompt = true;
        playOrDrawSent = false;
    }

    private void resetEmptyPassLatch() {
        synchronized (stackPassLock) {
            emptyPassLatch = "";
            emptyPassSent = false;
            stackPassForId = Integer.MIN_VALUE;
            stackPassStep = 0;
            stackVisibleAt = 0L;
        }
    }

    private void resetPregamePass() {
        lastPregame = true;
        playOrDrawSent = false;
        pendingHandWipe = false;
        lastYourHandKey = "";
        lastTheirHandKey = "";
        resetEmptyPassLatch();
        resyncAwaitPrompt = false;
        seatStopsSeeded = false;
    }

    private boolean thisSeatMayPass() {
        if (choice != null || xAsk != null || choosingTarget()) return false;
        if (cardSheetOpen() || pickSheetOpen() || modeSheetOpen() || listSheetActive()) return false;
        if (asksSomething(gui.prompt) || triggerUseAsk(gui.prompt)) return false;
        if (pregameChoice()) return false;
        if (!gui.okEnabled && !joinerSeat()) return false;
        return thisSeatHoldsPriority();
    }

    /**
     * This window's human, not the other seat. Host must not pass Tiny because
     * a prompt says priority. Join may still read the stock priority line.
     */
    private boolean thisSeatHoldsPriority() {
        if (gui == null) return false;
        PlayerView you = gui.human;
        if (you != null) {
            try {
                if (you.getHasPriority()) return true;
            } catch (RuntimeException ignored) {
            }
        }
        if (!joinerSeat()) return false;
        String prompt = gui.prompt == null ? "" : gui.prompt.toLowerCase(Locale.ROOT);
        return prompt.contains("priority:");
    }

    /**
     * Empty-pass and stack auto-OK are this seat only. Host InputAttack /
     * InputBlock still count when the has-priority bit is late. Tiny's
     * InputPassPriority on a shared host GUI does not.
     */
    private boolean thisSeatMayAutoYield() {
        if (discardAsk(gui == null ? null : gui.prompt)) return false;
        Input input;
        try {
            input = humanInput();
        } catch (RuntimeException ignored) {
            input = null;
        }
        if (input instanceof InputSelectEntitiesFromList<?> list && discardList(list)) return false;
        if (thisSeatHoldsPriority()) return true;
        if (input == null) {
            if (!joinerSeat() || gui == null) return false;
            return gui.isCombatDeclareAttackersInput() || gui.isCombatDeclareBlockersInput();
        }
        if (!(input instanceof InputPassPriority || input instanceof InputAttack || input instanceof InputBlock)) {
            return false;
        }
        return isThisSeat(input.getOwner());
    }

    private void considerStackPass() {
        if (!matchRunning() || pregameChoice() || resyncBlocksAutoPass()) return;
        if (!thisSeatMayAutoYield()) return;
        boolean click = false;
        synchronized (stackPassLock) {
            if (choice != null || xAsk != null || choosingTarget()) return;
            if (cardSheetOpen() || pickSheetOpen() || modeSheetOpen() || listSheetActive()) return;
            if (triggerUseAsk(gui.prompt) || discardAsk(gui.prompt)) return;
            if (resolvingWithoutPriority()) return;
            int top = topStackId();
            if (top != stackPassForId) {
                stackPassForId = top;
                stackPassStep = 0;
                stackVisibleAt = top == 0 ? 0L : System.currentTimeMillis();
            }
            if (top == 0) return;
            if (heCanRespondNow() || livePlayableNow()) return;
            if (!readyToAutoPassStack()) return;
            long elapsed = stackVisibleAt <= 0L ? STACK_READ_MS
                    : System.currentTimeMillis() - stackVisibleAt;
            if (elapsed < STACK_READ_MS) return;
            String prompt = gui.prompt == null ? "" : gui.prompt;
            boolean cannot = prompt.toLowerCase(Locale.ROOT).contains("cannot respond to the stack");
            boolean may = gui.okEnabled || priorityWaiting();
            if (cannot && may && stackPassStep == 0) {
                stackPassStep = 1;
                click = true;
            } else if (stackPassStep == 1 && !cannot) {
                stackPassStep = 2;
                click = may && !asksSomething(prompt);
            } else if (stackPassStep == 0 && may && stackSeatMayAutoPass()
                    && !asksSomething(prompt)) {
                stackPassStep = 2;
                click = true;
            }
        }
        if (click) queueSeatOk();
    }

    /**
     * His priority or combat prompt, empty stack, and Forge has no legal play
     * for him. Pass the input itself — do not click the Pass button, which can
     * reopen a hidden sheet. Cyan cards, ghosts, a target ask, or Yes/No wait.
     */
    private void considerEmptyPass() {
        if (!matchRunning() || pregameChoice() || resyncBlocksAutoPass()) return;
        if (!thisSeatMayAutoYield()) return;
        InputPassPriority pass = null;
        boolean clickOk = false;
        synchronized (stackPassLock) {
            if (choice != null || xAsk != null || choosingTarget()) return;
            if (cardSheetOpen() || pickSheetOpen() || modeSheetOpen() || listSheetActive()) return;
            if (triggerUseAsk(gui.prompt) || discardAsk(gui.prompt)) return;
            if (topStackId() != 0) return;
            Input input;
            try {
                input = humanInput();
            } catch (RuntimeException ignored) {
                input = null;
            }
            rememberEmptyPassKey(input);
            SeatPrompt kind = seatPrompt(input);
            if (kind == SeatPrompt.OTHER) return;
            if (heCanRespondNow() || livePlayableNow() || localHasAvailableActions()) return;
            if (kind == SeatPrompt.PASS && mainWithLand()) return;
            String prompt = gui.prompt == null ? "" : gui.prompt;
            if (asksSomething(prompt)) return;
            if (emptyPassSent) return;
            if (!gui.okEnabled) return;
            if (kind == SeatPrompt.ATTACK) {
                if (heCanDeclareAttack()) return;
                emptyPassSent = true;
                clickOk = true;
            } else if (kind == SeatPrompt.BLOCK) {
                if (heCanDeclareBlock()) return;
                emptyPassSent = true;
                clickOk = true;
            } else if (kind == SeatPrompt.PASS) {
                emptyPassSent = true;
                if (input instanceof InputPassPriority current) {
                    pass = current;
                } else {
                    clickOk = true;
                }
            }
        }
        if (pass != null) {
            try {
                if (!matchRunning()) return;
                pass.passPriority();
            } catch (RuntimeException ignored) {
                return;
            }
            return;
        }
        if (clickOk) submitSeatOk();
    }

    /**
     * Checksum mismatch or the full state has not been followed by a prompt yet.
     * Stale empty playable must not keep clicking OK.
     */
    private boolean resyncBlocksAutoPass() {
        return resyncAwaitPrompt || (gui != null && gui.isResyncPending());
    }

    /**
     * Checksum mismatch: keep the last good paint until setGameView lands.
     * Do not bind grey / wrong-id tiles from the half-applied delta.
     */
    boolean holdLeftoverPaint() {
        return gui != null && gui.isResyncPending();
    }

    /**
     * One auto-pass per distinct priority. New Input, stack top, phase, turn,
     * prompt, resync, or a newly playable set opens the latch again.
     */
    private void rememberEmptyPassKey(Input input) {
        String key = emptyPassKey(input);
        if (key.equals(emptyPassLatch)) return;
        emptyPassLatch = key;
        emptyPassSent = false;
    }

    private String emptyPassKey(Input input) {
        GameView view = gui == null ? null : gui.getGameView();
        int turn = view == null ? -1 : view.getTurn();
        PhaseType phase = view == null ? null : view.getPhase();
        String phaseName = phase == null ? "" : phase.name();
        SeatPrompt kind = seatPrompt(input);
        String inputKind = input != null ? input.getClass().getName() : "join:" + kind.name();
        int inputId = input == null ? 0 : System.identityHashCode(input);
        String prompt = gui == null || gui.prompt == null ? "" : gui.prompt;
        boolean mayPlay = heCanRespondNow() || livePlayableNow();
        return turn + "|" + phaseName + "|" + topStackId() + "|" + inputKind + "|"
                + inputId + "|" + prompt + "|" + mayPlay;
    }

    /** Full state arrived. Do not auto-pass until the next real prompt refresh. */
    private void noteResyncCompleted() {
        resyncAwaitPrompt = true;
        synchronized (stackPassLock) {
            emptyPassLatch = "";
            emptyPassSent = false;
            stackPassForId = Integer.MIN_VALUE;
            stackPassStep = 0;
            stackVisibleAt = 0L;
        }
    }

    private void notePromptAfterResync() {
        resyncAwaitPrompt = false;
    }

    /** Instants, flash, a playable ghost, or an activated ability Forge already marked. */
    private boolean heCanRespondNow() {
        if (localHasAvailableActions()) return true;
        Set<Integer> ids = playableIds;
        if (ids != null && !ids.isEmpty()) return true;
        List<Face> ghosts = playableGhosts;
        return ghosts != null && !ghosts.isEmpty();
    }

    private boolean localHasAvailableActions() {
        PlayerView you = gui == null ? null : gui.human;
        if (you == null) return false;
        try {
            return you.hasAvailableActions();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /** A land in hand during a main. Do not Pass that window. */
    private boolean mainWithLand() {
        GameView view = gui == null ? null : gui.getGameView();
        PlayerView you = gui == null ? null : gui.human;
        if (view == null || you == null) return false;
        PhaseType phase = view.getPhase();
        if (phase != PhaseType.MAIN1 && phase != PhaseType.MAIN2) return false;
        if (you.getHand() == null) return joinerSeat();
        try {
            if (joinerSeat() && you.getHand().isEmpty() && view.getTurn() <= openingTurns(view)) {
                return true;
            }
            for (CardView card : you.getHand()) {
                if (card != null && landType(card.getCurrentState())) return true;
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        return false;
    }

    /** First-turn mains on Join may still have an empty hand snapshot. */
    private static int openingTurns(GameView view) {
        try {
            return view.getPlayers() == null ? 2 : Math.max(2, view.getPlayers().size());
        } catch (RuntimeException ignored) {
            return 2;
        }
    }

    /** Live Forge scan so a stale empty playable set cannot skip a real play. */
    private boolean livePlayableNow() {
        PlayerView you = gui.human;
        if (you == null) return true;
        return zoneHasPlayable(you.getHand())
                || zoneHasPlayable(you.getBattlefield())
                || zoneHasPlayable(you.getFlashback())
                || zoneHasPlayable(you.getGraveyard())
                || zoneHasPlayable(you.getExile())
                || zoneHasPlayable(you.getLibrary())
                || zoneHasPlayable(you.getCommand())
                || zoneHasPlayable(you.getSideboard());
    }

    private boolean zoneHasPlayable(FCollectionView<CardView> cards) {
        if (cards == null) return false;
        try {
            for (CardView card : cards) {
                if (card != null && gui.isWeaklySelectable(card)) return true;
            }
        } catch (RuntimeException ignored) {
            return true;
        }
        return false;
    }

    private boolean heCanDeclareAttack() {
        try {
            PlayerControllerHuman human = humanController();
            Player you = human == null ? null : human.getPlayer();
            if (you != null) return CombatUtil.canAttack(you);
            return !joinerSeat() || guestMayDeclareAttack();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private boolean heCanDeclareBlock() {
        try {
            PlayerControllerHuman human = humanController();
            Player you = human == null ? null : human.getPlayer();
            if (you != null && you.getGame() != null) {
                Combat combat = you.getGame().getCombat();
                return combat != null && CombatUtil.canBlock(you, combat);
            }
            return !joinerSeat() || guestMayDeclareBlock();
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** Join has no CombatUtil player. Untapped creatures or a highlight mean she can block. */
    private boolean guestMayDeclareAttack() {
        return guestMayDeclareCombat(true);
    }

    private boolean guestMayDeclareBlock() {
        return guestMayDeclareCombat(false);
    }

    private boolean guestMayDeclareCombat(boolean attack) {
        PlayerView you = gui == null ? null : gui.human;
        if (you == null || you.getBattlefield() == null) return true;
        try {
            for (CardView card : you.getBattlefield()) {
                if (card == null) continue;
                if (gui.isWeaklySelectable(card)) return true;
                CardView.CardStateView state = card.getCurrentState();
                if (state == null || !state.isCreature()) continue;
                if (card.isTapped()) continue;
                if (attack && card.isSick()) continue;
                return true;
            }
            return false;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    private boolean readyToAutoPassStack() {
        String prompt = gui.prompt == null ? "" : gui.prompt;
        if (asksSomething(prompt) || triggerUseAsk(prompt)) return false;
        if (!gui.okEnabled && !priorityWaiting()) return false;
        if (prompt.toLowerCase(Locale.ROOT).contains("cannot respond to the stack")) return true;
        if (stackPassStep == 1) return true;
        return stackSeatMayAutoPass();
    }

    /**
     * Host auto-pass uses the local InputPassPriority. Join sends one
     * NetGameController OK after the visible beat when the stock Pass
     * prompt is up. A resolving spell is not priority.
     */
    private boolean stackSeatMayAutoPass() {
        try {
            return seatPrompt() == SeatPrompt.PASS;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    /**
     * Stack object is still there while the spell is mid-resolution.
     * That is a card sheet or a lock, not a Pass.
     */
    private boolean resolvingWithoutPriority() {
        if (choice != null || xAsk != null) return true;
        if (topStackId() == 0) return false;
        try {
            SeatPrompt kind = seatPrompt();
            if (kind == SeatPrompt.PASS || kind == SeatPrompt.ATTACK || kind == SeatPrompt.BLOCK) {
                return false;
            }
            Input input = humanInput();
            if (input instanceof InputSelectTargets) return false;
            return true;
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    /** Pass from a paint or prompt callback without parking that thread. */
    private void queueSeatOk() {
        if (!stackPassBusy.compareAndSet(false, true)) return;
        Thread worker = new Thread(() -> {
            try {
                submitSeatOk();
            } finally {
                stackPassBusy.set(false);
            }
        }, "stack auto-pass");
        worker.setDaemon(true);
        worker.start();
    }

    /** The window played the death smoke. The commander ask can finish its wait. */
    void noteDeathBeat() {
        synchronized (deathBeatLock) {
            deathBeatSeen = true;
            deathBeatLock.notifyAll();
        }
    }

    private void waitForDeathBeat() {
        long until = System.currentTimeMillis() + DEATH_BEAT_MS;
        synchronized (deathBeatLock) {
            deathBeatSeen = false;
            while (!deathBeatSeen && System.currentTimeMillis() < until) {
                long left = until - System.currentTimeMillis();
                if (left <= 0L) break;
                try {
                    deathBeatLock.wait(Math.min(80L, left));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        long remain = until - System.currentTimeMillis();
        if (remain > 0L) {
            try {
                Thread.sleep(remain);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private int topStackId() {
        GameView view = gui.getGameView();
        if (view == null || view.getStack() == null) return 0;
        int id = 0;
        try {
            for (StackItemView item : view.getStack()) {
                if (item != null) id = item.getId();
            }
        } catch (RuntimeException ignored) {
            return 0;
        }
        return id;
    }

    /** A priority dump is not a question. Search, pay, and choose stay on screen. */
    /**
     * Cleanup discard-to-7, or any other discard pick. Not Pass. Guest and host
     * must wait for the discarding seat's clicks.
     */
    private static boolean discardAsk(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String text = prompt.toLowerCase(Locale.ROOT);
        return text.contains("discard") || text.contains("cleanup")
                || text.contains("hand down") || text.contains("maximum hand");
    }

    private boolean discardList(InputSelectEntitiesFromList<?> list) {
        return list != null && discardAsk(gui == null ? null : gui.prompt);
    }

    private static boolean asksSomething(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        StringBuilder kept = new StringBuilder();
        for (String raw : prompt.split("\\R")) {
            String line = raw.trim().toLowerCase(Locale.ROOT);
            if (line.isEmpty()) continue;
            if (line.startsWith("priority:") || line.startsWith("turn:") || line.startsWith("phase:")
                    || line.startsWith("stack:") || line.startsWith("storm count:")
                    || line.startsWith("macro ") || line.contains("waiting for")) {
                continue;
            }
            kept.append(line).append('\n');
        }
        String text = kept.toString();
        if (text.isBlank()) return false;
        return text.contains("choose") || text.contains("select") || text.contains("target")
                || text.contains("search") || text.contains("pay ") || text.contains("sacrifice")
                || text.contains("discard") || text.contains("cleanup") || text.contains("hand down")
                || text.contains("maximum hand") || text.contains("mulligan") || text.contains("order")
                || text.contains("assign") || text.contains("scry") || text.contains("surveil")
                || text.contains("top") || text.contains("bottom") || text.contains("library")
                || text.contains("graveyard") || text.contains("triggered ability")
                || text.contains("use this triggered")
                || text.contains("play or draw") || text.contains("would you like to play")
                || text.contains("keep your hand") || text.contains("won the coin")
                || text.contains("going first");
    }

    private void haltResolveAll() {
        if (!resolveAll) return;
        resolveAll = false;
        PlayerView self = gui.human;
        IGameController controller = seatController();
        if (self == null || controller == null) return;
        controller.sendYieldUpdate(new YieldUpdate.StackYield(self, false, false));
    }

    private PlayerControllerHuman humanController() {
        if (gui.human == null) return null;
        IGameController controller = gui.getGameController(gui.human);
        if (controller instanceof PlayerControllerHuman human) return human;
        return null;
    }

    private Input humanInput() {
        if (gui == null || gui.human == null) return null;
        IGameController controller = gui.getGameController(gui.human);
        if (!(controller instanceof PlayerControllerHuman human)) return null;
        if (human.getInputProxy() == null) return null;
        return human.getInputProxy().getInput();
    }

    /**
     * A choice sheet or an X announce that is waiting on him.
     * Null while nobody is asked, and while a trigger resolves by itself.
     */
    String choiceWait() {
        if (gameOver()) return null;
        Choice pending = choice;
        if (pending != null) return waitId(pending);
        XAsk pendingX = xAsk;
        return pendingX == null ? null : waitId(pendingX);
    }

    /**
     * A Forge prompt that is his to answer: target, mana, mulligan, blocks.
     * Passing priority and the lock while Ash plays are not a choice.
     */
    String inputWait() {
        if (gameOver()) return null;
        Input input;
        try {
            input = humanInput();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (input == null || input instanceof InputPassPriority || input instanceof InputLockUI) return null;
        return waitId(input);
    }

    private boolean gameOver() {
        GameView view = gui.getGameView();
        return view != null && view.isGameOver();
    }

    private static String waitId(Object wait) {
        return wait.getClass().getName() + "@" + System.identityHashCode(wait);
    }

    private InputSelectEntitiesFromList<?> listInput() {
        Input input = humanInput();
        if (input instanceof InputSelectEntitiesFromList<?> list) return list;
        return null;
    }

    private boolean listSheetActive() {
        if (choice != null) return false;
        InputSelectEntitiesFromList<?> list = listInput();
        if (list == null) return false;
        for (GameEntity entity : list.getValidChoices()) {
            if (entity instanceof Card) return true;
        }
        return false;
    }

    /** min is 0 for "up to N". Cancel then means take nothing. */
    private boolean listOptional() {
        InputSelectEntitiesFromList<?> list = listInput();
        if (list == null) return false;
        return listMin(list) <= 0 || listAllowsCancel(list);
    }

    private boolean listHasEnough() {
        InputSelectEntitiesFromList<?> list = listInput();
        if (list == null) return false;
        int n = 0;
        for (Object ignored : list.getSelected()) n++;
        return n >= listMin(list);
    }

    private static int listMax(InputSelectEntitiesFromList<?> list) {
        return listBound(list, "max", 1);
    }

    private static int listMin(InputSelectEntitiesFromList<?> list) {
        return listBound(list, "min", 1);
    }

    private static int listBound(InputSelectEntitiesFromList<?> list, String name, int fallback) {
        if (list == null) return fallback;
        Class<?> type = list.getClass();
        while (type != null && type != Object.class) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.getInt(list);
            } catch (ReflectiveOperationException ignored) {
                type = type.getSuperclass();
            }
        }
        return fallback;
    }

    private static boolean listAllowsCancel(InputSelectEntitiesFromList<?> list) {
        try {
            java.lang.reflect.Field allow = InputSelectManyBase.class.getDeclaredField("allowCancel");
            allow.setAccessible(true);
            return allow.getBoolean(list);
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean selectedIn(InputSelectEntitiesFromList<?> list, Card card) {
        for (Object chosen : list.getSelected()) {
            if (chosen == card) return true;
        }
        return false;
    }

    /**
     * Short left-window title for the prompts Forge already uses.
     * Scry and surveil keep those words. Other questions keep Forge's own sentence.
     */
    static String presentChoice(String message) {
        if (commanderReturnAsk(message)) return COMMANDER_RETURN_Q;
        if (message == null || message.isBlank()) return "Choose";
        String best = null;
        for (String raw : message.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.equals("-")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("bottom of your library") || lower.contains("top or bottom")) return "Scry";
            if (lower.contains("top of library or graveyard") || lower.contains("put into the graveyard")) return "Surveil";
            if (best == null) best = line;
            if (lower.contains("choose") || lower.contains("select") || lower.contains("scry") || lower.contains("surveil")) {
                best = line;
            }
        }
        return best == null || best.isEmpty() ? "Choose" : best;
    }

    /**
     * Name Forge's follow-up order. The take already finished, so this must not
     * repeat the "choose creatures" line. Scry keeps its own arrange sentence.
     */
    static String orderStepTitle(String title, String top) {
        String head = title == null ? "" : title.trim();
        String tail = top == null ? "" : top.trim();
        String blob = (head + " " + tail).toLowerCase(Locale.ROOT);
        if (blob.contains("battlefield")) return "Order entering the battlefield.";
        boolean bottom = blob.contains("bottom");
        if (blob.contains("library") && bottom && !blob.contains("top") && !blob.contains("graveyard")) {
            return "These go on the bottom of your library";
        }
        String message = head.isEmpty() ? "Order these cards" : head;
        if (!tail.isEmpty()) message = message + " — " + tail;
        return message;
    }

    /** The left-window line when a commander can go home instead of the graveyard or exile. */
    static final String COMMANDER_RETURN_Q =
            "Would you like to return your commander to the command zone?";

    /**
     * State-based 903.9a and the optional commander replacement.
     * Not Command Beacon or other "from the command zone" asks.
     */
    static boolean commanderReturnAsk(String question) {
        if (question == null || question.isBlank()) return false;
        String lower = question.toLowerCase(Locale.ROOT);
        if (!lower.contains("commander") || !lower.contains("command zone")) return false;
        boolean intoCommand = lower.contains("into the command zone") || lower.contains("to the command zone");
        if (!intoCommand) return false;
        return lower.contains("instead")
                || lower.contains("owner may")
                || lower.contains("its owner may")
                || lower.contains("state-based")
                || lower.contains("may put it into the command")
                || lower.contains("would be put")
                || lower.contains("would be exiled")
                || lower.contains("in a graveyard or in exile");
    }

    /**
     * Cultivate, Kodama's Reach, Final Parting: the cards are already taken.
     * This ask is which copy goes to play, hand, or grave. Never auto-submit.
     * A fetch or tutor still in the library is not this — that is pick one and done.
     * "Search" in the Cultivate oracle must not hide the second-step SelectPrompt.
     */
    static boolean destinationSplitAsk(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("select a card to put") || lower.contains("choose a card to put")
                || lower.contains("select a card to put onto")
                || lower.contains("put onto the battlefield") && lower.contains("select")
                || lower.contains("put one") && (lower.contains("battlefield") || lower.contains("the other"))) {
            return true;
        }
        boolean place = lower.contains("battlefield") || lower.contains("into play")
                || lower.contains("into your hand") || lower.contains("to your hand")
                || lower.contains("into hand") || lower.contains("into your graveyard")
                || lower.contains("into the graveyard") || lower.contains("into graveyard");
        if (!place) return false;
        if (lower.contains("search") && !lower.contains("select a card") && !lower.contains("the other")) {
            return false;
        }
        return lower.contains("select") || lower.contains("choose") || lower.contains("put");
    }

    /**
     * Two or three already-taken cards, pick one destination. Both Forests still
     * need a sheet. A fetch of one name from a whole library is not this.
     */
    private static boolean destinationLikePile(List<?> legal, String message) {
        if (legal == null || legal.size() < 2 || legal.size() > 4) return false;
        if (destinationSplitAsk(message)) return true;
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean place = lower.contains("battlefield") || lower.contains("into play")
                || lower.contains("hand") || lower.contains("graveyard");
        if (!place && !allLandViews(legal)) return false;
        return true;
    }

    private static boolean allLandViews(List<?> legal) {
        if (legal == null || legal.isEmpty()) return false;
        for (Object item : legal) {
            if (!(item instanceof CardView card)) return false;
            try {
                CardView.CardStateView state = card.getCurrentState();
                if (state == null || state.getType() == null || !state.getType().isLand()) return false;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return true;
    }

    /**
     * This seat has the library-search input. Whose turn is not a stand-in.
     * Scry, surveil, and a destination split are a look or a place, not a search.
     */
    boolean thisSeatSearching() {
        if (placingSheet() || orderSheet() || amountOpen()) return false;
        if (!pickSheetOpen() && !cardSheetOpen()) return false;
        String title = sheetTitle();
        String prompt = gui == null || gui.prompt == null ? "" : gui.prompt;
        String blob = ((title == null ? "" : title) + "\n" + prompt).toLowerCase(Locale.ROOT);
        if (destinationSplitAsk(title) || destinationSplitAsk(prompt)) return false;
        if (scryLikeAsk(title) || scryLikeAsk(prompt)) return false;
        if (blob.contains("search")) return true;
        if (librarySearchAbility()) return true;
        return pickingFromLibrary();
    }

    private boolean pickingFromLibrary() {
        Choice pending = choice;
        if (pending != null && pending.kind == Choice.Kind.CARDS && pending.cards != null) {
            for (CardView card : pending.cards) {
                if (card != null && card.getZone() == ZoneType.Library) return true;
            }
        }
        InputSelectEntitiesFromList<?> list = listInput();
        if (list == null) return false;
        for (GameEntity entity : list.getValidChoices()) {
            if (entity instanceof Card card && card.isInZone(ZoneType.Library)) return true;
        }
        return false;
    }

    /**
     * A search is on the stack and this window is not the one picking.
     * Do not read "waiting for" as the searcher — that names the wrong seat.
     */
    boolean otherSeatSearching() {
        if (thisSeatSearching()) return false;
        if (choiceOpen() || pickSheetOpen() || cardSheetOpen()) return false;
        return librarySearchAbility();
    }

    private boolean librarySearchAbility() {
        try {
            GameView view = gui == null ? null : gui.getGameView();
            if (view == null || view.getStack() == null) return false;
            for (StackItemView item : view.getStack()) {
                if (item == null) continue;
                String blob = String.valueOf(item).toLowerCase(Locale.ROOT);
                if (blob.contains("search") && blob.contains("library")) return true;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    /** Scry, surveil, and the same top/bottom library ask. The picture row must open. */
    static boolean scryLikeAsk(String message) {
        if (message == null || message.isBlank()) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("scry") || lower.contains("surveil")
                || lower.contains("top or bottom") || lower.contains("top of library or graveyard")
                || (lower.contains("library") && (lower.contains("bottom") || lower.contains("top")))
                || lower.contains("graveyard");
    }

    /**
     * ETB scry 1 used to print "Scry" and skip the pictures. Same row as Genesis Wave:
     * card scans, drag to order, left is TOP, right is BOTTOM. Confirm submits.
     */
    private boolean askScryConfirm(CardView card, String question, List<String> options, boolean defaultYes) {
        List<CardView> cards = new ArrayList<>();
        if (card != null) cards.add(card);
        boolean surveil = surveilAsk(question, options);
        String title = surveil ? "Surveil" : "Scry";
        if (cards.isEmpty()) {
            List<String> picked = ask(title, 1, 1, options, null);
            if (picked.isEmpty()) return defaultYes;
            return options.get(0).equals(picked.get(0));
        }
        List<CardView> toBottom = askPlacement(title, cards, surveil);
        for (CardView view : toBottom) {
            if (view != null && view.getId() == card.getId()) return false;
        }
        return true;
    }

    private static boolean surveilAsk(String question, List<String> options) {
        String blob = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (blob.contains("surveil") || blob.contains("graveyard")) return true;
        if (options == null) return false;
        for (String option : options) {
            if (option != null && option.toLowerCase(Locale.ROOT).contains("graveyard")) return true;
        }
        return false;
    }

    /**
     * Every looked-at card starts on the left (TOP / library). A click sends it
     * to the right (BOTTOM / graveyard). Drag still reorders the row. Confirm
     * returns the bottom pile, left-to-right.
     */
    private <T> List<T> askPlacement(String title, List<T> choices, boolean surveil) {
        haltResolveAll();
        if (choices == null || choices.isEmpty()) return List.of();
        if (stopping || !isCardList(choices)) return List.of();
        List<CardView> shown = new ArrayList<>();
        for (T item : choices) {
            if (item instanceof CardView card) shown.add(card);
        }
        if (shown.isEmpty()) return List.of();
        Choice pending = new Choice();
        pending.kind = Choice.Kind.CARDS;
        pending.ordering = true;
        pending.placing = true;
        pending.placeSplit = shown.size();
        pending.message = title == null || title.isBlank() ? (surveil ? "Surveil" : "Scry") : title;
        pending.min = 0;
        pending.max = shown.size();
        pending.cards = shown;
        pending.legal = new boolean[shown.size()];
        java.util.Arrays.fill(pending.legal, true);
        for (int i = 0; i < shown.size(); i++) pending.picked.add(i);
        pending.cancelBlocked = true;
        pending.refreshPlacementTitle();
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping) return List.of();
        List<T> bottom = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        int split = Math.max(0, Math.min(shown.size(), pending.placeSplit));
        for (int i = split; i < pending.picked.size(); i++) {
            int index = pending.picked.get(i);
            if (index < 0 || index >= choices.size() || used.contains(index)) continue;
            used.add(index);
            bottom.add(choices.get(index));
        }
        return bottom;
    }

    /** Look-at-top, then you may reveal (Herald's Horn, kinship, Vivien's Grizzly). */
    static boolean peekRevealAsk(String question) {
        if (question == null || question.isBlank()) return false;
        String lower = question.toLowerCase(Locale.ROOT);
        if (lower.contains("reveal and put into your hand")) return true;
        if (lower.contains("reveal cards to other players") || lower.contains("reveal card to other players")) {
            return true;
        }
        return lower.contains("reveal this card");
    }

    /** Looked-at miss: the card is not the chosen type / valid reveal. */
    static boolean missLookAsk(String question) {
        if (question == null || question.isBlank()) return false;
        String lower = question.toLowerCase(Locale.ROOT);
        return lower.startsWith("not a ") || lower.startsWith("does not share");
    }

    /** Cascade, discover, or any optional free cast of a revealed card. */
    static boolean mayCastAsk(String question) {
        if (question == null || question.isBlank()) return false;
        String lower = question.toLowerCase(Locale.ROOT);
        if (lower.contains("discover")) return true;
        if (lower.contains("without paying") || lower.contains("for free")) return true;
        if (lower.contains("cascade")) return true;
        boolean play = lower.contains("cast") || lower.contains("play");
        if (!play) return false;
        return lower.contains("do you want") || lower.contains("would you") || lower.contains("you may")
                || lower.contains("?");
    }

    private boolean cascadeFamilyActive() {
        try {
            GameView view = gui == null ? null : gui.getGameView();
            if (view == null || view.getStack() == null) return false;
            for (StackItemView item : view.getStack()) {
                if (item == null) continue;
                String blob = String.valueOf(item).toLowerCase(Locale.ROOT);
                if (blob.contains("cascade") || blob.contains("discover")) return true;
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    /**
     * Picture sheet: revealed pile, hit large, Cast or Don't. One click is the answer.
     */
    private boolean askMayCast(CardView hit, String question, List<String> options, boolean defaultYes) {
        haltResolveAll();
        if (stopping) return false;
        List<CardView> shown = new ArrayList<>();
        List<CardView> pile = lastReveal;
        if (pile != null) {
            for (CardView card : pile) {
                if (card != null) shown.add(card);
            }
        }
        if (hit != null) {
            boolean have = false;
            for (CardView card : shown) {
                if (card.getId() == hit.getId()) {
                    have = true;
                    break;
                }
            }
            if (!have) shown.add(0, hit);
        }
        if (shown.isEmpty()) {
            List<String> choices = options == null || options.isEmpty() ? List.of("Yes", "No") : options;
            List<String> picked = ask(question, 1, 1, choices, null);
            if (picked.isEmpty()) return false;
            return choices.get(0).equals(picked.get(0));
        }
        int hitAt = 0;
        if (hit != null) {
            for (int i = 0; i < shown.size(); i++) {
                if (shown.get(i).getId() == hit.getId()) {
                    hitAt = i;
                    break;
                }
            }
        }
        String name = sheetName(hit);
        Choice pending = new Choice();
        pending.kind = Choice.Kind.CARDS;
        pending.mayCast = true;
        pending.message = question == null || question.isBlank()
                ? "Cast " + (name == null || name.isBlank() || name.equals("Card") ? "this" : name) + " for free?"
                : question;
        pending.min = 0;
        pending.max = 1;
        pending.allowCancel = true;
        pending.holdForOk = false;
        pending.hitIndex = hitAt;
        pending.cards = shown;
        pending.legal = new boolean[shown.size()];
        if (hitAt >= 0 && hitAt < pending.legal.length) pending.legal[hitAt] = true;
        pending.values = options == null || options.isEmpty() ? List.of("Yes", "No") : options;
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping) return false;
        return !pending.picked.isEmpty();
    }

    /**
     * Picture sheet: looked-at card bright, Reveal or Don't. One click is the answer.
     * Grey is illegal-only. A match must not be dim.
     */
    private boolean askOptionalReveal(CardView hit, String question, List<String> options, boolean defaultYes) {
        haltResolveAll();
        if (stopping) return false;
        List<CardView> shown = new ArrayList<>();
        List<CardView> pile = lastReveal;
        if (pile != null && !pile.isEmpty()) {
            for (CardView card : pile) {
                if (card != null) shown.add(card);
            }
        } else if (hit != null) {
            shown.add(hit);
        }
        if (shown.isEmpty()) {
            List<String> choices = options == null || options.isEmpty() ? List.of("Yes", "No") : options;
            List<String> picked = ask(question, 1, 1, choices, null);
            if (picked.isEmpty()) return false;
            return choices.get(0).equals(picked.get(0));
        }
        lastReveal = List.copyOf(shown);
        Choice pending = new Choice();
        pending.kind = Choice.Kind.CARDS;
        pending.mayReveal = true;
        pending.message = question == null || question.isBlank() ? "Reveal and put into your hand?" : question;
        pending.min = 0;
        pending.max = 1;
        pending.allowCancel = true;
        pending.holdForOk = false;
        pending.cards = shown;
        pending.legal = new boolean[shown.size()];
        java.util.Arrays.fill(pending.legal, true);
        pending.values = options == null || options.isEmpty() ? List.of("Yes", "No") : options;
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping) return false;
        return true;
    }

    /** Forge's optional-trigger sentence, with or without "you may" in the oracle. */
    static boolean triggerUseAsk(String question) {
        if (question == null || question.isBlank()) return false;
        String lower = question.toLowerCase(Locale.ROOT);
        return lower.contains("triggered ability") || lower.contains("use this triggered");
    }

    /** Top/bottom and library/graveyard are scry and surveil, not a yes/no box. */
    private static boolean placementChoice(String question, List<String> options) {
        if (commanderReturnAsk(question)) return false;
        if (triggerUseAsk(question)) return false;
        String lower = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (lower.contains("library") || lower.contains("graveyard") || lower.contains("scry") || lower.contains("surveil")) {
            return true;
        }
        if (options == null) return false;
        for (String option : options) {
            if (option == null) continue;
            String word = option.toLowerCase(Locale.ROOT);
            if (word.equals("top") || word.equals("bottom") || word.equals("library") || word.equals("graveyard")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCardList(List<?> choices) {
        if (choices == null || choices.isEmpty()) return false;
        for (Object item : choices) {
            if (!(item instanceof CardView)) return false;
        }
        return true;
    }

    private static boolean isModeList(String message, List<?> choices) {
        if (choices == null || choices.isEmpty()) return false;
        if (choices.get(0) instanceof SpellAbilityView) return true;
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("choose a mode") || lower.contains("choose one");
    }

    /**
     * Forge is asking which face to play: CardState, CardFace, or a land/spell
     * ability for each side of a DFC/MDFC. Names alone are not enough to see
     * Forest vs Plains, so the rows become pictures.
     */
    private static void attachFacePicks(Choice pending) {
        if (pending == null || pending.values == null || pending.values.size() < 2) return;
        List<FacePick> faces = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (int i = 0; i < pending.values.size(); i++) {
            FacePick face = faceOf(pending.values.get(i));
            if (face == null && i < pending.labels.size()) face = faceOfLabel(pending.labels.get(i));
            if (face == null || face.name == null || face.name.isBlank()) return;
            faces.add(face);
            names.add(face.name.toLowerCase(Locale.ROOT));
        }
        if (names.size() < 2) return;
        pending.faces.clear();
        pending.faces.addAll(faces);
        pending.facePick = true;
    }

    private static FacePick faceOf(Object item) {
        if (item instanceof CardView.CardStateView state) return faceOfState(state);
        if (item instanceof CardFaceView face) return faceOfName(face.getName());
        if (item instanceof SpellAbilityView ability) return faceOfAbility(ability);
        if (item instanceof String text) return faceOfLabel(text);
        return null;
    }

    private static FacePick faceOfState(CardView.CardStateView state) {
        if (state == null) return null;
        FacePick face = new FacePick();
        String name = state.getName();
        if (name == null || name.isBlank()) name = state.getOracleName();
        face.name = name == null ? "" : name.trim();
        if (face.name.isBlank()) return null;
        if (state.getType() != null) {
            String type = state.getType().toString();
            if (type != null && !type.isBlank()) face.typeLine = type.trim();
        }
        if (face.typeLine.isBlank()) face.typeLine = typeOfName(face.name);
        return face;
    }

    private static FacePick faceOfAbility(SpellAbilityView ability) {
        if (ability == null) return null;
        CardView host = ability.getHostCard();
        String desc = ability.toString();
        desc = desc == null ? "" : desc.replace('\r', ' ').replace('\n', ' ').trim();
        FacePick fromLabel = faceOfLabel(desc);
        if (fromLabel != null) {
            fillTypeFromHost(fromLabel, host);
            return fromLabel;
        }
        if (host == null) return null;
        CardView.CardStateView current = host.getCurrentState();
        CardView.CardStateView other = host.getAlternateState();
        if (!host.isModalCard() && !host.isDoubleFacedCard() && other == null) return null;
        String hostName = host.getName() == null ? "" : host.getName().trim();
        if (current != null && nameIn(desc, current.getName())) return faceOfState(current);
        if (other != null && nameIn(desc, other.getName())) return faceOfState(other);
        if (ability.isSpell() && current != null) return faceOfState(current);
        if (!ability.isSpell() && other != null && other.getType() != null && other.getType().isLand()) {
            return faceOfState(other);
        }
        if (!hostName.isBlank() && nameIn(desc, hostName) && current != null) return faceOfState(current);
        return null;
    }

    private static FacePick faceOfLabel(String label) {
        if (label == null) return null;
        String text = label.replace('\r', ' ').replace('\n', ' ').trim();
        if (text.isEmpty() || text.equalsIgnoreCase("yes") || text.equalsIgnoreCase("no")) return null;
        int open = text.toLowerCase(Locale.ROOT).indexOf("play land (");
        if (open >= 0) {
            int from = open + "play land (".length();
            int close = text.indexOf(')', from);
            if (close > from) return faceOfName(text.substring(from, close).trim());
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.startsWith("cast ")) {
            String name = text.substring(5).trim();
            if (!name.isEmpty() && !name.equalsIgnoreCase("this card")) return faceOfName(name);
        }
        return null;
    }

    private static FacePick faceOfName(String name) {
        if (name == null || name.isBlank()) return null;
        FacePick face = new FacePick();
        face.name = name.trim();
        face.typeLine = typeOfName(face.name);
        return face;
    }

    private static void fillTypeFromHost(FacePick face, CardView host) {
        if (face == null || face.typeLine != null && !face.typeLine.isBlank() || host == null) return;
        CardView.CardStateView current = host.getCurrentState();
        if (current != null && sameFaceName(face.name, current.getName()) && current.getType() != null) {
            face.typeLine = current.getType().toString();
            return;
        }
        CardView.CardStateView other = host.getAlternateState();
        if (other != null && sameFaceName(face.name, other.getName()) && other.getType() != null) {
            face.typeLine = other.getType().toString();
        }
        if (face.typeLine == null || face.typeLine.isBlank()) face.typeLine = typeOfName(face.name);
    }

    private static String typeOfName(String name) {
        if (name == null || name.isBlank()) return "";
        try {
            StaticData data = StaticData.instance();
            if (data == null || data.getCommonCards() == null) return "";
            ICardFace printed = data.getCommonCards().getFaceByName(name);
            if (printed != null && printed.getType() != null) {
                String type = printed.getType().toString();
                return type == null ? "" : type.trim();
            }
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    private static boolean nameIn(String text, String name) {
        if (text == null || name == null || name.isBlank()) return false;
        return text.toLowerCase(Locale.ROOT).contains(name.trim().toLowerCase(Locale.ROOT));
    }

    private static boolean sameFaceName(String left, String right) {
        if (left == null || right == null) return false;
        return left.trim().equalsIgnoreCase(right.trim());
    }

    /**
     * The face that is not showing. Modal, transform, and pathway cards have one.
     * Split and adventure cards do not.
     */
    static FacePick otherFace(CardView card) {
        if (card == null) return null;
        try {
            if (!card.isSplitCard() || card.isModalCard() || card.isDoubleFacedCard()) {
                CardView.CardStateView current = card.getCurrentState();
                CardView.CardStateView alt = card.getAlternateState();
                if (alt != null && alt != current) {
                    FacePick face = faceOfState(alt);
                    if (face != null && !sameFaceName(face.name, shownFaceName(card))) return face;
                }
            }
        } catch (RuntimeException ignored) {
            /* printed card lookup below */
        }
        return otherFace(card.getName(), null);
    }

    static FacePick otherFace(String name, PaperCard paper) {
        FacePick fromPaper = otherFaceOfPaper(paper, name);
        if (fromPaper != null) return fromPaper;
        return otherFaceFromPrinted(name);
    }

    /** Other row on a face-choice sheet, or the printed back of the hovered name. */
    static FacePick otherChoiceFace(List<FacePick> faces, int hover) {
        if (faces == null || hover < 0 || hover >= faces.size()) return null;
        FacePick shown = faces.get(hover);
        String shownName = shown == null ? "" : shown.name;
        for (int i = 0; i < faces.size(); i++) {
            if (i == hover) continue;
            FacePick other = faces.get(i);
            if (other != null && !sameFaceName(shownName, other.name)) return other;
        }
        return shown == null ? null : otherFaceFromPrinted(shown.name);
    }

    private static FacePick otherFaceFromPrinted(String name) {
        if (name == null || name.isBlank()) return null;
        try {
            StaticData data = StaticData.instance();
            if (data == null || data.getCommonCards() == null) return null;
            return otherFaceOfPaper(data.getCommonCards().getUniqueByName(name.trim()), name);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static FacePick otherFaceOfPaper(PaperCard paper, String shown) {
        if (paper == null) return null;
        try {
            if (!paper.hasBackFace()) return null;
            ICardFace other = paper.getOtherFace();
            ICardFace main = paper.getMainFace();
            if (other == null) return null;
            ICardFace pick = sameFaceName(shown, other.getName()) ? main : other;
            if (pick == null || sameFaceName(shown, pick.getName())) return null;
            FacePick face = new FacePick();
            face.name = pick.getName() == null ? "" : pick.getName().trim();
            if (face.name.isBlank()) return null;
            if (pick.getType() != null) {
                String type = pick.getType().toString();
                face.typeLine = type == null ? "" : type.trim();
            }
            if (face.typeLine == null || face.typeLine.isBlank()) face.typeLine = typeOfName(face.name);
            return face;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String shownFaceName(CardView card) {
        if (card == null) return "";
        CardView.CardStateView current = card.getCurrentState();
        if (current != null && current.getName() != null && !current.getName().isBlank()) {
            return current.getName().trim();
        }
        return card.getName() == null ? "" : card.getName().trim();
    }

    /**
     * Ability or spell lines. One click on a line plays it. Cancel and Esc
     * close the sheet and activate nothing. A single legal line never opens this.
     */
    private List<SpellAbilityView> askAbility(String message, List<SpellAbilityView> abilities, List<String> lines) {
        haltResolveAll();
        if (stopping) return List.of();
        if (abilities == null || abilities.isEmpty() || lines == null || lines.isEmpty()) return List.of();
        Choice pending = new Choice();
        pending.kind = Choice.Kind.MODES;
        pending.message = message == null || message.isBlank() ? "Choose an ability" : message;
        pending.min = 1;
        pending.max = 1;
        pending.values = new ArrayList<>(abilities);
        pending.labels.addAll(lines);
        pending.allowCancel = true;
        pending.holdForOk = false;
        attachFacePicks(pending);
        return finishChoice(pending, abilities);
    }

    /** Real ability text. A creature's type line is not an ability. */
    static String abilityText(SpellAbilityView ability, CardView host) {
        String text = ability == null ? "" : ability.toString();
        if (text == null) text = "";
        text = text.replace('\r', ' ').replace('\n', ' ').trim();
        if (typeLineOnly(text)) {
            if (ability != null && ability.isSpell()) {
                String name = host != null && host.getName() != null && !host.getName().isBlank()
                        ? host.getName() : "this card";
                return "Cast " + name;
            }
            return "";
        }
        return text.isEmpty() ? "Activate" : text;
    }

    /**
     * Oracle text of a triggered ability. An activated ability is a cost, then a colon.
     * "When this enters, draw a card" has nothing to pay and nothing to choose here.
     */
    static boolean triggeredAbilityText(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.replace('\r', ' ').replace('\n', ' ').trim().toLowerCase(Locale.ROOT);
        if (startsTrigger(lower)) return true;
        int colon = lower.indexOf(':');
        if (colon < 0) return false;
        String head = lower.substring(0, colon);
        if (head.indexOf('{') >= 0) return false;
        return startsTrigger(lower.substring(colon + 1).trim());
    }

    private static boolean startsTrigger(String lower) {
        return lower.startsWith("when ") || lower.startsWith("whenever ") || lower.startsWith("at ");
    }

    /** "Llanowar Elves - Creature 1 / 1" is the spell's type line, not an activated ability. */
    static boolean typeLineOnly(String text) {
        if (text == null) return true;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return true;
        if (trimmed.indexOf(':') >= 0) return false;
        return trimmed.matches("(?is).*-\\s*(Creature|Artifact|Enchantment|Planeswalker|Battle|Land|Instant|Sorcery|Tribal|Kindred)\\b.*");
    }

    private <T> List<T> ask(String message, int min, int max, List<T> choices,
            FSerializableFunction<T, String> display) {
        haltResolveAll();
        if (stopping) return List.of();
        if (choices == null || choices.isEmpty()) return List.of();
        if (min < 0) {
            showReveal(message, choices);
            return new ArrayList<>(choices);
        }
        if (isCardList(choices)) {
            int lo = Math.max(0, min);
            int hi = max < 0 ? choices.size() : max;
            return askCards(presentChoice(message), lo, hi, choices, null);
        }
        Choice pending = new Choice();
        int hi = max < 0 ? choices.size() : max;
        boolean roomToStop = hi > Math.max(0, min);
        pending.kind = isModeList(message, choices) || roomToStop ? Choice.Kind.MODES : Choice.Kind.LINES;
        pending.message = commanderReturnAsk(message) ? COMMANDER_RETURN_Q
                : pending.kind == Choice.Kind.MODES ? presentChoice(message)
                : (message == null ? "Choose" : message);
        pending.min = Math.max(0, min);
        pending.max = hi;
        pending.holdForOk = roomToStop;
        pending.values = new ArrayList<>(choices);
        for (T item : choices) {
            String label = display == null ? String.valueOf(item) : display.apply(item);
            pending.labels.add(label == null ? String.valueOf(item) : label);
        }
        markTypeChoice(pending);
        attachFacePicks(pending);
        return finishChoice(pending, choices);
    }

    /**
     * Forge is showing cards, not asking for a subset. The row stays up until OK.
     * The list goes back unchanged.
     */
    private <T> void showReveal(String message, List<T> choices) {
        haltResolveAll();
        if (stopping || choices == null || choices.isEmpty()) return;
        Choice pending = new Choice();
        pending.revealOnly = true;
        pending.cancelBlocked = true;
        pending.min = 0;
        pending.max = 0;
        pending.message = message == null || message.isBlank() ? "Revealed" : message;
        if (isCardList(choices)) {
            pending.kind = Choice.Kind.CARDS;
            List<CardView> shown = new ArrayList<>();
            for (T item : choices) {
                if (item instanceof CardView card) shown.add(card);
            }
            lastReveal = List.copyOf(shown);
            if (mayCastAsk(message) || cascadeFamilyActive()) {
                return;
            }
            pending.cards = shown;
            pending.legal = new boolean[shown.size()];
            java.util.Arrays.fill(pending.legal, true);
        } else {
            pending.kind = Choice.Kind.LINES;
            for (T item : choices) {
                String label = item == null ? "" : String.valueOf(item);
                pending.labels.add(label);
            }
        }
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            choice = null;
        }
    }

    /**
     * Blocks until OK. Marked cards are the ones Forge asked for.
     * Cards in {@code revealed} that are not in {@code choices} stay on screen and cannot be marked.
     */
    private <T> List<T> askCards(String message, int min, int max, List<T> choices, Iterable<CardView> revealed) {
        haltResolveAll();
        if (stopping) return List.of();
        if (choices == null || choices.isEmpty()) return List.of();
        Set<Integer> legalIds = new HashSet<>();
        List<T> legal = new ArrayList<>();
        for (T item : choices) {
            if (item instanceof CardView card) {
                legalIds.add(card.getId());
                legal.add(item);
            }
        }
        if (legal.isEmpty()) return List.of();
        if (!discardAsk(message) && !scryLikeAsk(message) && !destinationSplitAsk(message)
                && !destinationLikePile(legal, message) && samePrintedName(legal)) {
            List<String> names = uniqueLegalNames(legal);
            logLine("search-auto names=" + names + " n=" + legal.size());
            if (names.size() == 1) {
                searchAutoNote = "Only " + names.get(0) + ". Taken automatically.";
                searchAutoNoteUntil = System.currentTimeMillis() + 4000L;
            }
            int take = max < 0 ? legal.size() : Math.min(max, legal.size());
            if (take < min) take = Math.min(min, legal.size());
            return new ArrayList<>(legal.subList(0, take));
        }
        List<CardView> shown = new ArrayList<>();
        if (revealed != null) {
            for (CardView card : revealed) {
                if (card != null) shown.add(card);
            }
        }
        for (T item : legal) {
            CardView card = (CardView) item;
            boolean have = false;
            for (CardView seen : shown) {
                if (seen.getId() == card.getId()) {
                    have = true;
                    break;
                }
            }
            if (!have) shown.add(card);
        }
        shown.sort((a, b) -> Boolean.compare(legalIds.contains(b.getId()), legalIds.contains(a.getId())));
        Choice pending = new Choice();
        pending.kind = Choice.Kind.CARDS;
        pending.message = message == null || message.isBlank() ? "Choose" : message;
        pending.min = Math.max(0, Math.min(min, legal.size()));
        pending.max = max < 0 ? legal.size() : Math.min(max, legal.size());
        pending.allowCancel = pending.min <= 0;
        pending.holdForOk = pending.max > 1;
        pending.destinationSplit = destinationSplitAsk(pending.message) || destinationLikePile(legal, pending.message);
        pending.cards = shown;
        pending.legal = new boolean[shown.size()];
        for (int i = 0; i < shown.size(); i++) pending.legal[i] = legalIds.contains(shown.get(i).getId());
        pending.values = legal;
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping) return List.of();
        List<T> picked = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int index : pending.picked) {
            if (index < 0 || index >= shown.size() || !pending.legal[index]) continue;
            int id = shown.get(index).getId();
            for (int n = 0; n < legal.size(); n++) {
                if (used.contains(n)) continue;
                if (((CardView) legal.get(n)).getId() == id) {
                    used.add(n);
                    picked.add(legal.get(n));
                    break;
                }
            }
        }
        return picked;
    }

    /**
     * Forge's order dialog, after a take has already returned.
     * Every card starts in Forge's list order. Dragging reorders that row.
     * The left end is the top of the list this method returns. OK sends it.
     * Cancel is off, because this input has no cancel.
     */
    private <T> List<T> askOrder(String message, List<T> choices) {
        haltResolveAll();
        if (choices == null || choices.isEmpty()) return List.of();
        if (stopping || !isCardList(choices)) return new ArrayList<>(choices);
        if (choices.size() <= 1 && !scryLikeAsk(message)) return new ArrayList<>(choices);
        List<CardView> shown = new ArrayList<>();
        for (T item : choices) {
            if (item instanceof CardView card) shown.add(card);
        }
        Choice pending = new Choice();
        pending.kind = Choice.Kind.CARDS;
        pending.ordering = true;
        pending.message = message == null || message.isBlank() ? "Order these cards" : message;
        pending.min = 0;
        pending.max = shown.size();
        pending.cards = shown;
        pending.legal = new boolean[shown.size()];
        java.util.Arrays.fill(pending.legal, true);
        for (int i = 0; i < shown.size(); i++) pending.picked.add(i);
        pending.cancelBlocked = true;
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ArrayList<>(choices);
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping) return new ArrayList<>(choices);
        List<T> ordered = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int index : pending.picked) {
            if (index < 0 || index >= choices.size() || used.contains(index)) continue;
            used.add(index);
            ordered.add(choices.get(index));
        }
        for (int i = 0; i < choices.size(); i++) {
            if (!used.contains(i)) ordered.add(choices.get(i));
        }
        return ordered;
    }

    /**
     * Same drag row as cards. Left is index 0, which Forge treats as first to resolve
     * or first to apply. TOP stays on that end.
     */
    private <T> List<T> askWordOrder(String message, List<T> choices) {
        haltResolveAll();
        if (choices == null || choices.isEmpty()) return List.of();
        if (stopping || choices.size() <= 1) return new ArrayList<>(choices);
        Choice pending = new Choice();
        pending.kind = Choice.Kind.ORDER;
        pending.ordering = true;
        pending.message = message == null || message.isBlank() ? "Order these. Left is first." : message;
        pending.min = 0;
        pending.max = choices.size();
        pending.values = new ArrayList<>(choices);
        List<CardView> hosts = new ArrayList<>();
        for (int i = 0; i < choices.size(); i++) {
            T item = choices.get(i);
            pending.labels.add(orderLabel(item));
            hosts.add(hostOf(item));
            pending.picked.add(i);
        }
        pending.cards = hosts;
        pending.cancelBlocked = true;
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new ArrayList<>(choices);
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping) return new ArrayList<>(choices);
        List<T> ordered = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        for (int index : pending.picked) {
            if (index < 0 || index >= choices.size() || used.contains(index)) continue;
            used.add(index);
            ordered.add(choices.get(index));
        }
        for (int i = 0; i < choices.size(); i++) {
            if (!used.contains(i)) ordered.add(choices.get(i));
        }
        return ordered;
    }

    /**
     * One number per recipient. They start at zero, or at one when each must get something.
     * Plus cannot push the total past what Forge said to assign.
     */
    private Map<Object, Integer> askAmounts(String message, List<Object> keys, List<CardView> pictures,
            List<String> labels, int[] caps, int total, boolean atLeastOne) {
        return askAmounts(message, keys, pictures, labels, caps, total, atLeastOne, false, null);
    }

    private Map<Object, Integer> askAmounts(String message, List<Object> keys, List<CardView> pictures,
            List<String> labels, int[] caps, int total, boolean atLeastOne, boolean orderedDamage, int[] lethal) {
        haltResolveAll();
        Map<Object, Integer> assigned = new LinkedHashMap<>();
        if (keys == null || keys.isEmpty()) return assigned;
        if (stopping) {
            assigned.put(keys.get(0), total);
            return assigned;
        }
        int n = keys.size();
        Choice pending = new Choice();
        pending.kind = Choice.Kind.AMOUNT;
        pending.amounting = true;
        pending.stem = message == null || message.isBlank() ? "Assign " + total : message;
        pending.amountNeed = Math.max(0, total);
        pending.amounts = new int[n];
        pending.amountMax = caps == null || caps.length != n ? new int[n] : caps;
        if (caps == null || caps.length != n) java.util.Arrays.fill(pending.amountMax, total);
        int capSum = 0;
        for (int cap : pending.amountMax) capSum += Math.max(0, cap);
        if (capSum < pending.amountNeed) java.util.Arrays.fill(pending.amountMax, pending.amountNeed);
        pending.atLeastOne = atLeastOne && n <= pending.amountNeed;
        pending.orderedDamage = orderedDamage;
        pending.lethal = lethal == null || lethal.length != n ? new int[n] : lethal;
        if (pending.atLeastOne) java.util.Arrays.fill(pending.amounts, 1);
        pending.cards = pictures == null ? List.of() : pictures;
        if (labels != null) pending.labels.addAll(labels);
        pending.cancelBlocked = true;
        pending.min = 0;
        pending.message = pending.stem + " (" + sumOf(pending.amounts) + "/" + pending.amountNeed + ")";
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            assigned.put(keys.get(0), total);
            return assigned;
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping || !pending.amountsMatch()) {
            assigned.put(keys.get(0), total);
            return assigned;
        }
        for (int i = 0; i < n; i++) assigned.put(keys.get(i), pending.amounts[i]);
        return assigned;
    }

    private static int sumOf(int[] values) {
        int sum = 0;
        if (values == null) return 0;
        for (int value : values) sum += value;
        return sum;
    }

    private static String orderLabel(Object item) {
        if (item instanceof CardView card) {
            return card.getName() == null || card.getName().isBlank() ? "Card" : card.getName();
        }
        String text = item == null ? "" : String.valueOf(item);
        text = text.replace('\r', ' ').replace('\n', ' ').trim();
        return text.isEmpty() ? "Choice" : text;
    }

    private static CardView hostOf(Object item) {
        if (item instanceof CardView card) return card;
        if (item instanceof SpellAbilityView ability) return ability.getHostCard();
        if (item instanceof forge.game.replacement.ReplacementEffectView effect) return effect.getHostCard();
        return null;
    }

    private static int lethalOf(CardView attacker, CardView blocker) {
        try {
            int lethal = blocker == null ? 0 : Math.max(0, blocker.getLethalDamage());
            CardView.CardStateView state = attacker == null ? null : attacker.getCurrentState();
            if (state != null && state.hasDeathtouch()) lethal = Math.min(Math.max(lethal, 1), 1);
            return lethal;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static boolean tramples(CardView attacker) {
        try {
            CardView.CardStateView state = attacker == null ? null : attacker.getCurrentState();
            return state != null && state.hasTrample();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String entityName(GameEntityView entity) {
        if (entity == null) return "Defender";
        String name = entity.getName();
        return name == null || name.isBlank() ? "Defender" : name;
    }

    private static String amountName(Object key) {
        if (key instanceof CardView card) return entityName(card);
        if (key instanceof GameEntityView entity) return entityName(entity);
        if (key instanceof forge.card.MagicColor.Color color) {
            String name = color.getName();
            return name == null || name.isBlank() ? "Mana" : name;
        }
        return orderLabel(key);
    }

    /** Left is the first entry Forge's order dialog returns. */
    private static String orderPrompt(String title, String top) {
        String head = title == null || title.isBlank() ? "Order these" : title.trim();
        String tail = top == null ? "" : top.trim();
        if (tail.isEmpty()) return head + ". Left is first.";
        return head + ". " + tail + " is the left end.";
    }

    /**
     * Type-name line list, not a card name. Cavern, Conspiracy, Cover of Darkness,
     * and the rest of chooseSomeType share this. Yes/No and numbers stay out.
     */
    private void markTypeChoice(Choice pending) {
        if (pending == null || pending.facePick) return;
        if (!typeLineChoice(pending.message, pending.labels)) return;
        pending.typeList = true;
        pending.deckTypes = collectDeckTypes();
        pending.kind = Choice.Kind.LINES;
        pending.holdForOk = pending.max > 1;
    }

    static boolean typeLineChoice(String message, List<String> labels) {
        if (labels == null || labels.size() < 2) return false;
        int hits = 0;
        for (String label : labels) {
            if (knownTypeName(label)) hits++;
        }
        String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean prompt = m.contains("creature type") || m.contains("card type")
                || m.contains("land type") || m.contains("basic land")
                || m.contains("choose a type") || m.contains("choose type")
                || m.contains("a type of")
                || (m.contains("choose") && m.contains(" type"));
        if (hits >= 8 && hits * 2 >= labels.size()) return true;
        return prompt && hits >= Math.max(2, labels.size() / 2);
    }

    static boolean knownTypeName(String label) {
        if (label == null || label.isBlank()) return false;
        String t = label.trim();
        if (CardType.isACreatureType(t) || CardType.isALandType(t) || CardType.isABasicLandType(t)) {
            return true;
        }
        if (CardType.isASupertype(t) || CardType.isAnArtifactType(t)) return true;
        try {
            CardType.CoreType.valueOf(t);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** Types printed on his main, side, and command. Changelings do not count as every type. */
    private Set<String> collectDeckTypes() {
        Set<String> types = new LinkedHashSet<>();
        try {
            PlayerControllerHuman human = humanController();
            Player player = human == null ? null : human.getPlayer();
            if (player == null) return types;
            RegisteredPlayer rp = player.getRegisteredPlayer();
            if (rp != null) {
                Deck deck = rp.getDeck();
                if (deck != null) {
                    addPoolTypes(types, deck.getMain());
                    addPoolTypes(types, deck.get(DeckSection.Sideboard));
                    addPoolTypes(types, deck.get(DeckSection.Commander));
                }
                if (rp.getCommanders() != null) {
                    for (PaperCard card : rp.getCommanders()) addPaperTypes(types, card);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return types;
    }

    private static void addPoolTypes(Set<String> types, CardPool pool) {
        if (types == null || pool == null) return;
        for (PaperCard card : pool.toFlatList()) addPaperTypes(types, card);
    }

    private static void addPaperTypes(Set<String> types, PaperCard card) {
        if (types == null || card == null || card.getRules() == null) return;
        if (card.getRules().hasKeyword("Changeling")) return;
        CardType type = card.getRules().getType();
        if (type == null || type.hasAllCreatureTypes()) return;
        if (type.getCreatureTypes() != null) types.addAll(type.getCreatureTypes());
        if (type.getLandTypes() != null) types.addAll(type.getLandTypes());
        if (type.getCoreTypes() != null) {
            for (CardType.CoreType core : type.getCoreTypes()) {
                if (core != null) types.add(core.name());
            }
        }
        try {
            ICardFace other = card.getOtherFace();
            if (other == null || other.getType() == null) return;
            CardTypeView face = other.getType();
            if (face.hasAllCreatureTypes()) return;
            if (face.getCreatureTypes() != null) types.addAll(face.getCreatureTypes());
            if (face.getLandTypes() != null) types.addAll(face.getLandTypes());
        } catch (RuntimeException ignored) {
        }
    }

    private <T> List<T> finishChoice(Choice pending, List<T> choices) {
        choice = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            choice = null;
        }
        if (pending.cancelled || stopping) return List.of();
        List<T> picked = new ArrayList<>();
        for (int index : pending.picked) {
            if (index >= 0 && index < choices.size()) picked.add(choices.get(index));
        }
        return picked;
    }

    /**
     * Yes returns the commander to the command zone. No leaves Forge's other destination.
     * Display order is always Yes then No. The boolean Forge gets is the option that
     * actually moves the card to command, even when Forge listed graveyard first.
     */
    private boolean askCommanderReturn(boolean defaultYes, List<String> options) {
        waitForDeathBeat();
        List<String> shown = List.of("Yes", "No");
        List<String> picked = ask(COMMANDER_RETURN_Q, 1, 1, shown, null);
        boolean yes = picked.isEmpty() ? defaultYes : "Yes".equalsIgnoreCase(picked.get(0));
        boolean submit = commanderConfirmResult(yes, options);
        commanderChoiceNote = (yes ? "Yes" : "No") + " submitTrue=" + submit + " options=" + options;
        logLine("commander-return " + commanderChoiceNote);
        return submit;
    }

    /**
     * Forge {@code confirm} true is the first button / apply.
     * Yes always means command zone. Invert when that option is not the first button.
     */
    static boolean commanderConfirmResult(boolean yes, List<String> options) {
        if (options == null || options.size() < 2) return yes;
        int commandAt = commanderDestIndex(options, true);
        if (commandAt < 0) return yes;
        boolean commandIsTrue = commandAt == 0;
        return yes ? commandIsTrue : !commandIsTrue;
    }

    private static int commanderDestIndex(List<String> options, boolean command) {
        if (options == null) return -1;
        for (int i = 0; i < options.size(); i++) {
            String word = options.get(i) == null ? "" : options.get(i).toLowerCase(Locale.ROOT);
            boolean isCommand = word.contains("command zone")
                    || (word.contains("command") && !word.contains("commander") && !word.equals("yes"));
            boolean isLeave = word.contains("grave") || word.contains("exile") || word.equals("no");
            if (command && isCommand) return i;
            if (!command && isLeave) return i;
        }
        return -1;
    }

    private List<String> askModes(String message, List<String> options, List<CardView> glance) {
        haltResolveAll();
        if (stopping || options == null || options.isEmpty()) return List.of();
        Choice pending = new Choice();
        pending.kind = Choice.Kind.MODES;
        pending.message = message == null || message.isBlank() ? "Choose one" : message;
        pending.min = 1;
        pending.max = 1;
        pending.values = new ArrayList<>(options);
        pending.labels.addAll(options);
        pending.glance = glance == null ? List.of() : glance;
        return finishChoice(pending, options);
    }

    private int rememberedStart(PlayerView view, boolean you) {
        int fromRules = startingLifeFromRules(view);
        if (fromRules > 0) {
            if (you) cachedYourStart = fromRules;
            else cachedTheirStart = fromRules;
            return fromRules;
        }
        int cached = you ? cachedYourStart : cachedTheirStart;
        if (cached > 0) return cached;
        int life = view == null ? 20 : view.getLife();
        if (life <= 0) life = 20;
        if (you) cachedYourStart = life;
        else cachedTheirStart = life;
        return life;
    }

    private int startingLifeFromRules(PlayerView view) {
        if (view == null) return 0;
        forge.game.Game game = match == null ? null : match.getGame();
        if (game == null) {
            PlayerControllerHuman human = humanController();
            game = human == null ? null : human.getGame();
        }
        if (game == null) return 0;
        for (Player player : game.getPlayers()) {
            if (player.getId() == view.getId()) return player.getStartingLife();
        }
        return 0;
    }

    /** Name, color key, and damage for each commander that has already hit this player. */
    private void commanderDamagePips(GameView view, PlayerView damaged, List<CommanderPip> into) {
        if (damaged == null || view == null || view.getPlayers() == null) return;
        if (!tracksCommanderDamage()) return;
        for (PlayerView owner : view.getPlayers()) {
            List<CardView> commanders = owner.getCommanders();
            if (commanders == null) continue;
            for (CardView commander : commanders) {
                if (commander == null) continue;
                int amount = damaged.getCommanderDamage(commander);
                if (amount <= 0) continue;
                CommanderPip pip = new CommanderPip();
                String name = commander.getName();
                pip.name = name == null || name.isBlank() ? "Commander" : name;
                pip.key = commanderKey(commander);
                pip.amount = amount;
                into.add(pip);
            }
        }
    }

    /**
     * Real Commander tracks 21. Brawl is 25 life and has no commander-damage loss.
     * Constructed has no commanders.
     */
    private boolean tracksCommanderDamage() {
        if (seatedType == GameType.Brawl) return false;
        try {
            forge.game.Game game = match == null ? null : match.getGame();
            if (game != null && game.getRules() != null) {
                GameType kind = game.getRules().getGameType();
                if (kind == GameType.Brawl || game.getRules().hasAppliedVariant(GameType.Brawl)) {
                    return false;
                }
                if (kind == GameType.Commander || game.getRules().hasAppliedVariant(GameType.Commander)) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // Use the deck this window sat down with.
        }
        return seatedType == GameType.Commander;
    }

    /** Stable for one commander all game. Two partners hash to two keys. */
    static String commanderKey(CardView card) {
        if (card == null) return "Commander";
        String name = card.getName();
        if (name == null || name.isBlank()) name = "Commander";
        return card.getId() + ":" + name;
    }

    static final class Pic {
        String youName = "";
        String themName = "";
        int yourLife = 20, theirLife = 20;
        int yourStartingLife = 20, theirStartingLife = 20;
        /** Public count only. Opponent faces stay hidden. */
        int yourHandCount, theirHandCount;
        final List<CommanderPip> yourCommanderDamage = new ArrayList<>();
        final List<CommanderPip> theirCommanderDamage = new ArrayList<>();
        /** Empty in a constructed game. One seat per commander, yours and theirs. */
        final List<CommanderSeat> yourCommand = new ArrayList<>();
        final List<CommanderSeat> theirCommand = new ArrayList<>();
        /** Unspent pool in mana-type order: white, blue, black, red, green, colorless. */
        int[] yourPool = new int[6];
        int[] theirPool = new int[6];
        String prompt = "";
        BattlefieldLayout.StripOwner owner = BattlefieldLayout.StripOwner.YOU;
        BattlefieldLayout.StripPhase phase = BattlefieldLayout.StripPhase.MAIN_1;
        final List<Face> hand = new ArrayList<>();
        final List<Face> yourBoard = new ArrayList<>();
        final List<Face> theirBoard = new ArrayList<>();
        final List<Face> yourLibrary = new ArrayList<>();
        final List<Face> theirLibrary = new ArrayList<>();
        final List<Face> yourGrave = new ArrayList<>();
        final List<Face> theirGrave = new ArrayList<>();
        final List<Face> yourExile = new ArrayList<>();
        final List<Face> theirExile = new ArrayList<>();
        /** Cards Forge says you can play from outside your hand. Empty when nothing is. */
        final List<Face> ghosts = new ArrayList<>();
        final List<Spell> stack = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final List<String> decisions = new ArrayList<>();
        /** Decision lines from the newest recorded turn. The debug tab only. */
        final List<String> debugDecisions = new ArrayList<>();
        final List<Strike> strikes = new ArrayList<>();
        final List<Mark> yourMarks = new ArrayList<>();
        final List<Mark> theirMarks = new ArrayList<>();
        boolean over;
        String resultTitle = "";
        String resultWhy = "";
        int signature;
        String choiceMessage;
        List<String> choiceLabels = List.of();
        List<Integer> choicePicked = List.of();
        List<FacePick> choiceFaces = List.of();
        boolean facePick;
        /** Creature-type / choose-a-type line list. Search and deck bias apply. */
        boolean typeChoice;
        /** Types printed on his main, side, and command. Empty when unknown. */
        Set<String> deckTypes = Set.of();
        /** Left-to-right row is the order Forge will be given. */
        boolean ordering;
        /** Scry/surveil: left of {@link #placeSplit} stays on top. */
        boolean placing;
        int placeSplit;
        /** One number per sheet tile. OK waits until they add up. */
        boolean amounting;
        /** Cascade / discover may-cast picture sheet. */
        boolean mayCast;
        /** Herald's Horn look-at optional reveal. */
        boolean mayReveal;
        /** Cultivate destination of already-taken cards. */
        boolean destinationSplit;
        /** Mulligan, draw, discard, or bounce replaced this seat's hand ids. Drop leftover tiles. */
        boolean wipeHand;
        /** Checksum full state landed. Rebuild board Faces from this GameView, not stale Perm ids. */
        boolean rebuildBoards;
        /** Cascade hit index, or -1. */
        int hitIndex = -1;
        /** Revealed pictures for a card-list choice. Empty when the prompt is not one. */
        final List<ShownCard> sheet = new ArrayList<>();
    }

    /** One picture in the reveal row. Illegal cards stay visible and dim. */
    static final class ShownCard {
        String name = "";
        boolean legal;
        boolean dim;
        boolean picked;
        /** 1-based click order. Zero when this sheet is a pick, not an order. */
        int order;
        /** Ability or recipient line drawn on the plate. Empty when the picture is enough. */
        String caption = "";
        /** Assigned amount. Minus one when this sheet is not dividing a number. */
        int amount = -1;
        /** Cascade / discover hit. Drawn larger. */
        boolean hit;
    }

    static final class Spell {
        int id;
        String name = "";
        String text = "";
        String typeLine = "";
        String cost = "";
        String pt = "";
        String rules = "";
        String targetLabel = "";
        int[] targetCardIds = new int[0];
        boolean targetYou, targetThem;
        CardView source;
        int sourceId;
        boolean sourceOnBoard;
        boolean sourceYours;
        final List<Aim> aims = new ArrayList<>();
    }

    /** One face of a DFC/MDFC/pathway. The picture and type line identify it. */
    static final class FacePick {
        String name = "";
        String typeLine = "";
    }

    /** One commander. The picture is only while it is still in the command zone. */
    static final class CommanderSeat {
        String name = "";
        /** Hash input for this commander's shield color. Same key on the damage pip. */
        String colorKey = "";
        /** Where it went after it left the command zone. Empty while the picture is showing. */
        String away = "";
        boolean inCommand;
        boolean playable;
        Face card;
    }

    /** One commander that has already dealt damage to this player. */
    static final class CommanderPip {
        String name = "";
        String key = "";
        int amount;
    }

    /** Emblem, City's Blessing, The Ring, or the dungeon this player is in. */
    static final class Mark {
        String name = "";
        String text = "";
    }

    /** One chosen target on a stack spell. */
    static final class Aim {
        int cardId;
        int playerId;
        boolean player;
        boolean you;
        String name = "";
        boolean creature;
        boolean tapped;
        int power, toughness;
    }

    static final class Strike {
        int attackerId;
        int blockerId;
        int planeswalkerId;
        boolean defenderIsYou;
    }

    private static final class HeldSpell {
        int id;
        String name = "";
        String text = "";
        String typeLine = "";
        String cost = "";
        String pt = "";
        String rules = "";
        String targetLabel = "";
        int[] targetCardIds = new int[0];
        boolean targetYou, targetThem;
        int sourceId;
        boolean sourceOnBoard;
        boolean sourceYours;
        long until;
    }

    static final class Face {
        CardView view;
        String name = "";
        boolean faceUp, faceDown, hiddenBack, foretold, tapped, token, sick, attacking, blocking, battlefield, marked, playable;
        /** Attackers this card is assigned to block. Empty until Forge records the block. */
        int[] blockAttackerIds = new int[0];
        boolean land, creature, planeswalker, battle, artifact, enchantment, aura, equipment;
        /** Printed instant or sorcery. Never a battlefield permanent. */
        boolean instant, sorcery;
        /** True when he is the protecting / defending player of this battle. */
        boolean protectYou;
        int protectorId;
        /** Forge's commander flag, not a card name. Follows the card after it leaves command. */
        boolean commander;
        /** Same key as the command-zone seat and the damage pip. Empty when not a commander. */
        String commanderKey = "";
        /** Permanent this aura or equipment is attached to, or 0 when it is loose. */
        int attachedToId;
        java.util.List<String> keywords = java.util.List.of();
        int power, toughness, printedPower, printedToughness, damage;
        int loyalty, printedLoyalty;
        int defense, printedDefense;
        boolean hasPrinted;
        boolean hasLoyalty;
        boolean hasDefense;
        /** Empty, atk, blk, or both. Can't attack or block is its own pile. */
        String restraint = "";
        /** CardView chosen type on the battlefield. Empty until a type is named. */
        String chosenType = "";
        /** Live keywords, counters, attachments, and the chosen type for oracle zoom. */
        java.util.List<Status> status = java.util.List.of();
    }

    /** One active keyword, counter, or attachment. Reminder is empty when it is just a count. */
    static final class Status {
        final String title;
        final String reminder;

        Status(String title, String reminder) {
            this.title = title == null ? "" : title;
            this.reminder = reminder == null ? "" : reminder;
        }
    }

    /**
     * Pacifism, Defender, and a static "can't attack" or "can't block" are not the default state.
     * A conditional "unless" line stays with the default pile.
     */
    private static String restraintOf(CardView card) {
        if (card == null) return "";
        try {
            if (card.isDetained()) return "both";
            CardView.CardStateView state = card.getCurrentState();
            if (state == null) return "";
            boolean noAtk = state.hasKeyword(Keyword.DEFENDER);
            boolean noBlk = false;
            KeywordCollectionView keywords = state.getKeywords();
            if (keywords != null) {
                for (KeywordView keyword : keywords) {
                    if (keyword == null || keyword.original() == null) continue;
                    String lower = keyword.original().toLowerCase(Locale.ROOT);
                    if (lower.contains("unless")) continue;
                    boolean attack = lower.contains("can't attack") || lower.contains("cant attack");
                    boolean block = lower.contains("can't block") || lower.contains("cant block");
                    if (lower.contains("can't attack or block") || lower.contains("cant attack or block")) {
                        attack = true;
                        block = true;
                    }
                    if (attack) noAtk = true;
                    if (block) noBlk = true;
                }
            }
            String ability = state.getAbilityText();
            if (ability != null) {
                for (String line : ability.toLowerCase(Locale.ROOT).split("\\R")) {
                    if (line.contains("unless")) continue;
                    boolean attack = line.contains("can't attack") || line.contains("cant attack");
                    boolean block = line.contains("can't block") || line.contains("cant block");
                    if (line.contains("can't attack or block") || line.contains("cant attack or block")) {
                        attack = true;
                        block = true;
                    }
                    if (attack) noAtk = true;
                    if (block) noBlk = true;
                }
            }
            if (noAtk && noBlk) return "both";
            if (noAtk) return "atk";
            if (noBlk) return "blk";
        } catch (RuntimeException ignored) {
            return "";
        }
        return "";
    }

    /**
     * Named type from CardView. Empty until chosen. Guest and host both read
     * TrackableProperty.ChosenType — no extra wire field.
     */
    static String chosenTypeOf(CardView card) {
        if (card == null) return "";
        try {
            return BattlefieldLayout.chosenTypeLabel(card.getChosenType(), card.getChosenType2());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * What is actually on the card right now: keywords with reminder text,
     * counters, attachments, and the chosen type. Oracle zoom reads this list.
     */
    static List<Status> statusOf(CardView card) {
        if (card == null) return List.of();
        CardView.CardStateView state = null;
        try {
            state = card.getCurrentState();
        } catch (RuntimeException ignored) {
        }
        return statusOf(card, state);
    }

    private static List<Status> statusOf(CardView card, CardView.CardStateView state) {
        List<Status> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (state != null) {
            try {
                KeywordCollectionView keywords = state.getKeywords();
                if (keywords != null) {
                    for (KeywordView keyword : keywords) {
                        if (keyword == null) continue;
                        String title = keyword.title();
                        if (title == null || title.isBlank()) title = keyword.original();
                        if (title == null || title.isBlank()) continue;
                        title = title.trim();
                        String key = title.toLowerCase(Locale.ROOT);
                        if (!seen.add(key)) continue;
                        String rem = "";
                        try {
                            rem = keyword.reminderText();
                        } catch (RuntimeException ignored) {
                        }
                        out.add(new Status(title, rem == null ? "" : rem.trim()));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (card != null) {
            String chosen = chosenTypeOf(card);
            if (!chosen.isBlank()) {
                String title = "Chosen type: " + chosen;
                if (seen.add(title.toLowerCase(Locale.ROOT))) {
                    out.add(new Status(title, ""));
                }
            }
            try {
                com.google.common.collect.Multiset<CounterType> counters = card.getCounters();
                if (counters != null) {
                    for (CounterType type : counters.elementSet()) {
                        int n = counters.count(type);
                        if (type == null || n <= 0) continue;
                        String name = type.getCounterOnCardDisplayName();
                        if (name == null || name.isBlank()) name = type.getName();
                        if (name == null || name.isBlank()) continue;
                        String title = n + "x " + name;
                        if (!seen.add(title.toLowerCase(Locale.ROOT))) continue;
                        out.add(new Status(title, ""));
                    }
                }
            } catch (RuntimeException ignored) {
            }
            try {
                List<CardView> hung = card.getAttachedCards();
                if (hung != null) {
                    for (CardView extra : hung) {
                        if (extra == null) continue;
                        String name = extra.getName();
                        if (name == null || name.isBlank()) continue;
                        String title = "Attached: " + name.trim();
                        if (!seen.add(title.toLowerCase(Locale.ROOT))) continue;
                        out.add(new Status(title, ""));
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /** Strip toggle. True yields (red). False stops (green). The game thread reads this while he clicks. */
    void setPhaseYield(boolean yours, int step, boolean yield) {
        boolean[] row = yours ? yourYield : enemyYield;
        if (step < 0 || step >= row.length) return;
        synchronized (yieldLock) {
            row[step] = yield;
        }
        if (yours) publishThisSeatStops();
    }

    private boolean phaseYields(boolean yours, int step) {
        boolean[] row = yours ? yourYield : enemyYield;
        synchronized (yieldLock) {
            if (step < 0 || step >= row.length) return true;
            return row[step];
        }
    }

    /**
     * Join sends only this seat's strip. Host enemy / Ash red never rides along.
     * Host Steve reads his arrays locally and does not seed the other human.
     */
    private void publishThisSeatStops() {
        MatchGui view = gui;
        if (view == null) return;
        view.publishSeatStops();
    }

    /** This window's human. Tiny on Join, Steve on Host — not the far seat. */
    private boolean isThisSeat(PlayerView player) {
        if (player == null || gui == null) return false;
        try {
            if (gui.isLocalPlayer(player)) return true;
        } catch (RuntimeException ignored) {
        }
        return sameSeat(player, gui.human);
    }

    /** Enemy strip is Ash / computer only. A remote human never inherits it. */
    private boolean enemyStripAppliesTo(PlayerView player) {
        if (player == null || isThisSeat(player)) return false;
        return playerIsComputer(player);
    }

    private boolean playerIsComputer(PlayerView player) {
        if (player == null) return false;
        try {
            if (player.isAI()) return true;
        } catch (RuntimeException ignored) {
        }
        Player engine = engineSeat(player);
        return engine != null && engine.getController() != null && engine.getController().isAI();
    }

    private Player engineSeat(PlayerView view) {
        HostedMatch hosted = match;
        forge.game.Game game = hosted == null ? null : hosted.getGame();
        if (game == null || view == null) return null;
        for (Player player : game.getPlayers()) {
            if (player != null && player.getId() == view.getId()) return player;
        }
        return null;
    }

    /**
     * Printed power and toughness from the card rules. The current numbers already
     * include buffs, so they cannot be the white baseline.
     */
    private static void fillPrinted(Face face) {
        face.printedPower = face.power;
        face.printedToughness = face.toughness;
        face.hasPrinted = false;
        try {
            if (face.name == null || face.name.isBlank() || FModel.getMagicDb() == null) return;
            PaperCard paper = FModel.getMagicDb().getCommonCards().getUniqueByName(face.name);
            if (paper == null || paper.getRules() == null) return;
            String power = paper.getRules().getPower();
            String toughness = paper.getRules().getToughness();
            if (power == null || toughness == null) return;
            if (!power.matches("-?\\d+") || !toughness.matches("-?\\d+")) return;
            face.printedPower = Integer.parseInt(power);
            face.printedToughness = Integer.parseInt(toughness);
            face.hasPrinted = true;
        } catch (RuntimeException ignored) {
            face.hasPrinted = false;
        }
    }

    /** Forge's current loyalty, and the printed starting loyalty from the card rules. */
    private static void fillLoyalty(Face face, CardView.CardStateView state) {
        face.hasLoyalty = true;
        String raw = state == null ? null : state.getLoyalty();
        boolean haveCurrent = raw != null && raw.trim().matches("-?\\d+");
        face.loyalty = haveCurrent ? parseStat(raw) : 0;
        face.printedLoyalty = face.loyalty;
        try {
            if (face.name == null || face.name.isBlank() || FModel.getMagicDb() == null) return;
            PaperCard paper = FModel.getMagicDb().getCommonCards().getUniqueByName(face.name);
            if (paper == null || paper.getRules() == null) return;
            int printed = parseStat(paper.getRules().getInitialLoyalty());
            if (printed != 0 || printedLoyaltyText(paper)) face.printedLoyalty = printed;
            if (!haveCurrent && face.printedLoyalty > 0) face.loyalty = face.printedLoyalty;
        } catch (RuntimeException ignored) {
            /* keep the number Forge already put on the view */
        }
    }

    private static boolean printedLoyaltyText(PaperCard paper) {
        if (paper == null || paper.getRules() == null) return false;
        String raw = paper.getRules().getInitialLoyalty();
        return raw != null && raw.trim().matches("-?\\d+");
    }

    /**
     * Battlefield Battle (Siege or any later battle type). Uses Forge's
     * {@code isBattle} / type / Siege subtype. Not a card-name list.
     */
    private static void fillBattle(Face face, CardView card, CardView.CardStateView state) {
        boolean battle = false;
        String typeText = "";
        if (state != null) {
            try {
                if (state.isBattle()) battle = true;
            } catch (RuntimeException ignored) {
                /* type string below */
            }
            try {
                if (state.getType() != null) {
                    typeText = state.getType().toString();
                    if (typeText == null) typeText = "";
                    if (!battle && state.getType().isBattle()) battle = true;
                    if (!battle && state.getType().hasSubtype("Siege")) battle = true;
                }
            } catch (RuntimeException ignored) {
                /* type string below */
            }
        }
        if (!battle && BattlefieldLayout.isBattleType(typeText)) battle = true;
        if (!battle) return;
        face.battle = true;
        fillDefense(face, state);
        try {
            PlayerView protector = card == null ? null : card.getProtectingPlayer();
            if (protector != null) face.protectorId = protector.getId();
        } catch (RuntimeException ignored) {
            face.protectorId = 0;
        }
    }

    /**
     * Protector is the defending player. When Forge has no protector field,
     * a Siege is defended by the opponent of its controller.
     */
    private static void settleBattleSides(List<Face> faces, PlayerView you, boolean controllerYou) {
        if (faces == null) return;
        int youId = you == null ? 0 : you.getId();
        for (Face face : faces) {
            if (face == null || !face.battle) continue;
            if (face.protectorId != 0 && youId != 0) {
                face.protectYou = face.protectorId == youId;
            } else {
                face.protectYou = !controllerYou;
            }
        }
    }

    /** Cyan when he can activate it, or when he can legally attack it. */
    private void markBattlePlayable(Pic pic, Set<Integer> playable) {
        if (pic == null) return;
        boolean heAttacks = pic.phase == BattlefieldLayout.StripPhase.DECLARE_ATTACKERS
                && pic.owner == BattlefieldLayout.StripOwner.YOU;
        paintBattlePlayable(pic.yourBoard, playable, heAttacks);
        paintBattlePlayable(pic.theirBoard, playable, heAttacks);
    }

    private void paintBattlePlayable(List<Face> faces, Set<Integer> playable, boolean heAttacks) {
        if (faces == null) return;
        for (Face face : faces) {
            if (face == null || !face.battle) continue;
            if (playable != null && face.view != null && playable.contains(face.view.getId())) {
                face.playable = true;
            }
            if (face.playable) continue;
            if (face.marked) {
                face.playable = true;
                continue;
            }
            if (heAttacks && !face.protectYou) {
                face.playable = true;
                continue;
            }
            if (face.view == null) continue;
            try {
                if (gui != null && gui.isSelectable(face.view)) face.playable = true;
            } catch (RuntimeException ignored) {
                /* leave playable as it is */
            }
        }
    }

    /** Current defense counters, and the printed starting defense from the card rules. */
    private static void fillDefense(Face face, CardView.CardStateView state) {
        face.hasDefense = true;
        String raw = null;
        try {
            raw = state == null ? null : state.getDefense();
        } catch (RuntimeException ignored) {
            raw = null;
        }
        boolean haveCurrent = raw != null && raw.trim().matches("-?\\d+");
        face.defense = haveCurrent ? parseStat(raw) : 0;
        face.printedDefense = face.defense;
        try {
            if (face.name == null || face.name.isBlank() || FModel.getMagicDb() == null) return;
            PaperCard paper = FModel.getMagicDb().getCommonCards().getUniqueByName(face.name);
            if (paper == null || paper.getRules() == null) return;
            int printed = parseStat(paper.getRules().getDefense());
            if (printed != 0 || printedDefenseText(paper)) face.printedDefense = printed;
            if (!haveCurrent && face.printedDefense > 0) face.defense = face.printedDefense;
        } catch (RuntimeException ignored) {
            /* keep the number Forge already put on the view */
        }
    }

    private static boolean printedDefenseText(PaperCard paper) {
        if (paper == null || paper.getRules() == null) return false;
        String raw = paper.getRules().getDefense();
        return raw != null && raw.trim().matches("-?\\d+");
    }

    private static int parseStat(String raw) {
        if (raw == null) return 0;
        String text = raw.trim();
        if (!text.matches("-?\\d+")) return 0;
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /** Steps on the strip. Untap is not a chip, so it has no stop. */
    private static int enemyStripIndex(PhaseType phase) {
        if (phase == null) return -1;
        BattlefieldLayout.StripPhase step = switch (phase) {
            case UPKEEP -> BattlefieldLayout.StripPhase.UPKEEP;
            case DRAW -> BattlefieldLayout.StripPhase.DRAW;
            case MAIN1 -> BattlefieldLayout.StripPhase.MAIN_1;
            case COMBAT_BEGIN -> BattlefieldLayout.StripPhase.BEGIN_COMBAT;
            case COMBAT_DECLARE_ATTACKERS -> BattlefieldLayout.StripPhase.DECLARE_ATTACKERS;
            case COMBAT_DECLARE_BLOCKERS -> BattlefieldLayout.StripPhase.DECLARE_BLOCKERS;
            case COMBAT_FIRST_STRIKE_DAMAGE -> BattlefieldLayout.StripPhase.FIRST_STRIKE;
            case COMBAT_DAMAGE -> BattlefieldLayout.StripPhase.COMBAT_DAMAGE;
            case COMBAT_END -> BattlefieldLayout.StripPhase.END_COMBAT;
            case MAIN2 -> BattlefieldLayout.StripPhase.MAIN_2;
            case END_OF_TURN -> BattlefieldLayout.StripPhase.END_STEP;
            case CLEANUP -> BattlefieldLayout.StripPhase.CLEANUP;
            default -> null;
        };
        return step == null ? -1 : step.ordinal();
    }

    /** Short reason under the win line. Life, poison, or a concession when Forge says so. */
    private static String outcomeLine(GameView view) {
        try {
            GameOutcome outcome = view.getOutcome();
            if (outcome == null) return "";
            String why = "";
            for (String line : outcome.getOutcomeStrings()) {
                if (line == null) continue;
                String lower = line.toLowerCase(Locale.ROOT);
                if (lower.contains("conced")) {
                    return lower.startsWith("you") ? "You conceded." : "Ash conceded.";
                }
                if (why.isEmpty() && lower.contains("poison")) why = "Poison.";
                else if (why.isEmpty() && lower.contains("life")) why = "Life reached 0.";
            }
            return why;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** Hold a yielded step on screen so the gold outline can move before the turn continues. */
    private void dwellStep(PlayerView player, PhaseType phase) {
        GameView view = gui.getGameView();
        int turn = view == null ? 0 : view.getTurn();
        int key = turn * 1000 + phase.ordinal() * 17 + (player == null ? 0 : player.getId());
        if (key == stepDwellKey) return;
        stepDwellKey = key;
        try {
            Thread.sleep(ASH_STEP_DWELL_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Choice {
        enum Kind { LINES, CARDS, MODES, ORDER, AMOUNT }

        Kind kind = Kind.LINES;
        String message = "";
        /** Amount prompt without the running total, so a nudge does not stack the sentence. */
        String stem = "";
        int min, max;
        final List<String> labels = new ArrayList<>();
        List<?> values = List.of();
        List<CardView> cards = List.of();
        boolean[] legal = new boolean[0];
        /** A card to read beside a mode line, such as the one card in a scry. */
        List<CardView> glance = List.of();
        final List<Integer> picked = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        boolean cancelled;
        /** Esc and Cancel close the sheet even when a line is required before OK. */
        boolean allowCancel;
        /** Variable pick (up to N, choose 2+). A single pick submits on the click. */
        boolean holdForOk;
        /** Cards already accepted. The row order is the list OK submits. */
        boolean ordering;
        /** Scry/surveil: left of {@link #placeSplit} stays on top, the rest go to the bottom. */
        boolean placing;
        int placeSplit;
        /** This Forge input has no cancel. Esc does nothing. */
        boolean cancelBlocked;
        /** Look at the cards, then OK. Marks are not a choice. */
        boolean revealOnly;
        /** Cultivate / Kodama / Final Parting: which already-taken card goes where. */
        boolean destinationSplit;
        /** Cascade / discover: cast the hit or don't. */
        boolean mayCast;
        /** Look-at-top optional reveal: take the card or leave it. */
        boolean mayReveal;
        /** Index of the cascade hit in {@link #cards}, or -1. */
        int hitIndex = -1;
        /** Divide one total across the row. */
        boolean amounting;
        /** Which face of a DFC/MDFC/pathway to play. Pictures identify the rows. */
        boolean facePick;
        /** Choose-a-type line list. Search and deck bias apply. Not a card name. */
        boolean typeList;
        Set<String> deckTypes = Set.of();
        final List<FacePick> faces = new ArrayList<>();
        boolean atLeastOne;
        /** Earlier creatures must be assigned lethal before a later one, unless Forge said order can be ignored. */
        boolean orderedDamage;
        int amountNeed;
        int[] amounts = new int[0];
        int[] amountMax = new int[0];
        int[] lethal = new int[0];

        boolean okReady() {
            if (revealOnly || ordering) return true;
            if (amounting) return amountsMatch();
            if (picked.size() < min) return false;
            return max <= 0 || picked.size() <= max;
        }

        private boolean amountsMatch() {
            int sum = 0;
            for (int i = 0; i < amounts.length; i++) {
                int value = amounts[i];
                if (value < 0) return false;
                if (atLeastOne && value < 1) return false;
                if (i < amountMax.length && amountMax[i] >= 0 && value > amountMax[i]) return false;
                sum += value;
            }
            if (sum != amountNeed) return false;
            if (!orderedDamage) return true;
            for (int i = 0; i < amounts.length; i++) {
                int need = i < lethal.length ? lethal[i] : 0;
                if (amounts[i] >= need) continue;
                for (int later = i + 1; later < amounts.length; later++) {
                    if (amounts[later] > 0) return false;
                }
            }
            return true;
        }

        void nudgeAmount(int index, int dir) {
            if (!amounting || index < 0 || index >= amounts.length || dir == 0) return;
            int floor = atLeastOne ? 1 : 0;
            int next = amounts[index] + dir;
            if (next < floor) next = floor;
            if (index < amountMax.length && amountMax[index] >= 0) next = Math.min(next, amountMax[index]);
            int others = 0;
            for (int i = 0; i < amounts.length; i++) {
                if (i != index) others += amounts[i];
            }
            next = Math.min(next, Math.max(floor, amountNeed - others));
            amounts[index] = next;
            int sum = others + next;
            message = stem + " (" + sum + "/" + amountNeed + ")";
        }

        void toggle(int index) {
            if (placing) {
                togglePlacement(index);
                return;
            }
            if (revealOnly || ordering || amounting) return;
            int limit = kind == Kind.CARDS ? cards.size() : labels.size();
            if (index < 0 || index >= limit) return;
            if (kind == Kind.CARDS) {
                if (index >= legal.length || !legal[index]) return;
                if (picked.contains(index)) {
                    picked.remove((Integer) index);
                    return;
                }
                if (max > 0 && picked.size() >= max) return;
                picked.add(index);
                if (holdForOk) return;
                if (max <= 1 && min <= 1) submit();
                return;
            }
            if (picked.contains(index)) {
                picked.remove((Integer) index);
                return;
            }
            if (max > 0 && picked.size() >= max) return;
            if (max == 1) picked.clear();
            picked.add(index);
            if (holdForOk) return;
            if (max == 1 && min <= 1) submit();
        }

        void togglePlacement(int visual) {
            int n = picked.size();
            if (visual < 0 || visual >= n) return;
            if (n == 1) {
                placeSplit = placeSplit == 0 ? 1 : 0;
                refreshPlacementTitle();
                return;
            }
            int card = picked.remove(visual);
            if (visual < placeSplit) {
                placeSplit = Math.max(0, placeSplit - 1);
                picked.add(placeSplit, card);
            } else {
                picked.add(placeSplit, card);
                placeSplit++;
            }
            refreshPlacementTitle();
        }

        void placeTowardTop() {
            int n = picked.size();
            if (n <= 0) return;
            placeSplit = n == 1 ? 1 : Math.min(n, placeSplit + 1);
            refreshPlacementTitle();
        }

        void placeTowardBottom() {
            int n = picked.size();
            if (n <= 0) return;
            placeSplit = n == 1 ? 0 : Math.max(0, placeSplit - 1);
            refreshPlacementTitle();
        }

        void refreshPlacementTitle() {
            if (!placing) return;
            boolean surveil = message != null && message.toLowerCase(Locale.ROOT).contains("surveil");
            String stem = surveil ? "Surveil" : "Scry";
            if (cards != null && cards.size() == 1) {
                message = placeSplit == 0
                        ? stem + " — goes to the " + (surveil ? "graveyard" : "bottom")
                        : stem + " — stays on top";
            } else {
                message = stem;
            }
        }

        void submit() {
            if (!okReady()) return;
            // One OK. A second click must not open this same choice again.
            if (latch.getCount() == 0) return;
            latch.countDown();
        }

        void cancel() {
            if (cancelBlocked) return;
            if (min > 0 && !allowCancel) return;
            cancelled = true;
            latch.countDown();
        }

        void forceClose() {
            cancelled = true;
            latch.countDown();
        }
    }

    /** Forge's "Choose X for Card" announce. Other number prompts stay on the choice list. */
    private static boolean isAnnounceX(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.startsWith("choose x ") || lower.startsWith("choose x for");
    }

    /**
     * Blocks until he picks X. The number goes back to Forge as the announced X.
     * Null cancels the cast the same way declining the announce does.
     */
    private Integer askAnnouncedX(String message, int min, int max) {
        haltResolveAll();
        if (stopping) return null;
        if (max <= min) return min;
        int payable = min;
        int counterX = min;
        int holdCap = 0;
        try {
            SpellAbility spell = spellBeingAnnounced(message);
            Player you = youPlayer();
            ManaCost cost = manaCostOf(spell);
            if (spell != null && you != null && XCostMath.hasManaX(cost)) {
                List<Integer> mana = spendableMana(you);
                payable = XCostMath.maxSpendableOnX(mana, cost);
                counterX = counterChoice(you, spell, mana, cost, payable);
                holdCap = Math.max(payable, mana.size());
            } else {
                payable = payableX(message, min, max);
                counterX = XCostMath.holdBack(payable, counterReserve(payable));
                holdCap = Math.max(payable, 0);
            }
        } catch (RuntimeException failed) {
            payable = fallbackPayable(min, max);
            counterX = payable;
            holdCap = Math.max(payable, 0);
        }
        if (payable < min) return null;
        XAsk pending = new XAsk(min, max, payable, counterX, holdCap);
        xAsk = pending;
        try {
            pending.latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            xAsk = null;
        }
        if (pending.cancelled || stopping) return null;
        return pending.result;
    }

    /**
     * Stand-in when the announced spell has no mana X.
     * Mana X uses {@link XCostMath} on the real pool and untapped sources.
     */
    private int payableX(String message, int min, int engineMax) {
        SpellAbility spell = spellBeingAnnounced(message);
        Player you = youPlayer();
        int payable = fallbackPayable(min, engineMax);
        if (spell != null && you != null) {
            SpellAbility root = spell.getRootAbility() == null ? spell : spell.getRootAbility();
            Integer previous = root.getXManaCostPaid();
            try {
                payable = ComputerUtilMana.determineLeftoverMana(spell, you, false);
            } finally {
                root.setXManaCostPaid(previous);
            }
        }
        if (engineMax != Integer.MAX_VALUE) payable = Math.min(payable, engineMax);
        return Math.max(min, payable);
    }

    /** A finite stand-in when the spell on the stack cannot be read. Never Integer.MAX_VALUE. */
    private int fallbackPayable(int min, int engineMax) {
        if (engineMax != Integer.MAX_VALUE) return Math.max(min, engineMax);
        Player you = youPlayer();
        int estimate = you == null ? min : ComputerUtilMana.getAvailableManaEstimate(you, true);
        return Math.max(min, estimate);
    }

    /**
     * X after also reserving the cheapest counterspell in his hand that he can still cast.
     * No such counter leaves this equal to Max.
     */
    private int counterChoice(Player you, SpellAbility casting, List<Integer> mana, ManaCost spellCost, int maxPay) {
        Card castingCard = casting == null ? null : casting.getHostCard();
        int bestCmc = Integer.MAX_VALUE;
        int chosen = maxPay;
        for (Card card : you.getCardsIn(ZoneType.Hand)) {
            if (card == null || card == castingCard) continue;
            SpellAbility spell = counterSpell(card);
            if (spell == null) continue;
            ManaCost cost = manaCostOf(spell);
            if (cost == null || cost.isNoCost()) continue;
            int cmc = cost.getCMC();
            if (cmc <= 0 || cmc > bestCmc) continue;
            Integer reserved = XCostMath.maxIfAlsoReserved(mana, spellCost, cost);
            if (reserved == null || cmc == bestCmc) continue;
            bestCmc = cmc;
            chosen = reserved;
        }
        return chosen;
    }

    /**
     * Mana to hold so the cheapest counterspell in his hand can still be cast after this spell.
     * No counter he could still cast means reserve nothing, and Max-counter matches Max.
     */
    private int counterReserve(int leftover) {
        Player you = youPlayer();
        if (you == null || leftover <= 0) return 0;
        int cheapest = Integer.MAX_VALUE;
        for (Card card : you.getCardsIn(ZoneType.Hand)) {
            SpellAbility spell = counterSpell(card);
            if (spell == null) continue;
            int cost = card.getManaCost() == null ? card.getCMC() : card.getManaCost().getCMC();
            if (cost <= 0 || cost > leftover || cost >= cheapest) continue;
            try {
                if (!ComputerUtilMana.canPayManaCost(spell, you, 0, false)) continue;
            } catch (RuntimeException skipped) {
                continue;
            }
            cheapest = cost;
        }
        return cheapest == Integer.MAX_VALUE ? 0 : cheapest;
    }

    /** A spell that counters a spell. Putting a counter on a permanent is a different ability. */
    private static SpellAbility counterSpell(Card card) {
        if (card == null) return null;
        for (SpellAbility spell : card.getSpellAbilities()) {
            if (spell != null && spell.isSpell() && chainCountersSpell(spell)) return spell;
        }
        return null;
    }

    private static boolean chainCountersSpell(SpellAbility spell) {
        int guard = 0;
        for (SpellAbility step = spell; step != null && guard < 12; step = step.getSubAbility(), guard++) {
            if (step.getApi() == ApiType.Counter) return true;
        }
        return false;
    }

    private SpellAbility spellBeingAnnounced(String message) {
        HostedMatch hosted = match;
        forge.game.Game game = hosted == null ? null : hosted.getGame();
        if (game == null || message == null) return null;
        String lower = message.toLowerCase(Locale.ROOT);
        for (SpellAbilityStackInstance item : game.getStack()) {
            if (item == null || item.getSpellAbility() == null) continue;
            SpellAbility spell = item.getSpellAbility();
            Card host = spell.getHostCard();
            if (nameInMessage(host, lower)) return xSpell(host, spell);
        }
        Player you = youPlayer();
        if (you == null) return null;
        Card best = null;
        for (Card card : you.getCardsIn(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard,
                ZoneType.Exile, ZoneType.Command, ZoneType.Stack)) {
            if (!nameInMessage(card, lower)) continue;
            if (best == null || card.getName().length() > best.getName().length()) best = card;
        }
        return best == null ? null : xSpell(best, null);
    }

    private static boolean nameInMessage(Card card, String lowerMessage) {
        if (card == null || lowerMessage == null) return false;
        String name = card.getName();
        if (name != null && name.length() > 1 && lowerMessage.contains(name.toLowerCase(Locale.ROOT))) return true;
        String shown = card.getDisplayName();
        return shown != null && shown.length() > 1 && lowerMessage.contains(shown.toLowerCase(Locale.ROOT));
    }

    /** The ability whose mana cost is being announced. Prefer one that actually has X. */
    private static SpellAbility xSpell(Card card, SpellAbility known) {
        if (known != null && known.getPayCosts() != null && known.getPayCosts().hasXInAnyCostPart()) return known;
        SpellAbility fallback = known;
        if (card == null) return fallback;
        for (SpellAbility spell : card.getSpellAbilities()) {
            if (spell == null || spell.isManaAbility()) continue;
            if (fallback == null) fallback = spell;
            Cost cost = spell.getPayCosts();
            if (cost != null && cost.hasXInAnyCostPart()) return spell;
        }
        return fallback;
    }

    private static ManaCost manaCostOf(SpellAbility spell) {
        if (spell == null || spell.getPayCosts() == null || !spell.getPayCosts().hasManaCost()) return ManaCost.ZERO;
        CostPartMana part = spell.getPayCosts().getCostMana();
        if (part == null) return ManaCost.ZERO;
        try {
            ManaCost cost = part.getManaCostFor(spell);
            return cost == null ? ManaCost.ZERO : cost;
        } catch (RuntimeException failed) {
            return part.getMana();
        }
    }

    /**
     * One mask per mana he can spend: floating mana, plus one activation of each untapped source.
     * A source that can produce more than one color still counts as one mana of either color.
     */
    private static List<Integer> spendableMana(Player you) {
        List<Integer> masks = new ArrayList<>();
        if (you == null || you.getManaPool() == null) return masks;
        for (Mana mana : you.getManaPool()) {
            if (mana == null) continue;
            int mask = mana.getColor() & 0xFF;
            masks.add(mask == 0 ? ManaAtom.COLORLESS : mask);
        }
        for (Card card : you.getCardsIn(ZoneType.Battlefield)) {
            masks.addAll(sourceMana(card, you));
        }
        return masks;
    }

    private static List<Integer> sourceMana(Card card, Player you) {
        List<List<Integer>> options = new ArrayList<>();
        if (card == null) return List.of();
        for (SpellAbility ability : card.getManaAbilities()) {
            if (ability == null || ability.getManaPart() == null) continue;
            if (ability.getPayCosts() != null && ability.getPayCosts().hasManaCost()) continue;
            try {
                ability.setActivatingPlayer(you);
                if (!ability.canPlay()) continue;
            } catch (RuntimeException skipped) {
                continue;
            }
            List<Integer> produced = activationMasks(ability);
            if (!produced.isEmpty()) options.add(produced);
        }
        if (options.isEmpty()) return List.of();
        boolean single = true;
        for (List<Integer> option : options) {
            if (option.size() != 1) single = false;
        }
        if (single) {
            int mask = 0;
            for (List<Integer> option : options) mask |= option.get(0);
            return List.of(mask);
        }
        List<Integer> best = List.of();
        for (List<Integer> option : options) {
            if (option.size() > best.size()) best = option;
        }
        return best;
    }

    /** Mana from one activation. Combo and "any" are one mana that can be any of those colors. */
    private static List<Integer> activationMasks(SpellAbility ability) {
        AbilityManaPart part = ability.getManaPart();
        if (part == null) return List.of();
        int amount = 1;
        try {
            amount = Math.max(1, AbilityUtils.calculateAmount(ability.getHostCard(),
                    ability.getParamOrDefault("Amount", "1"), ability));
        } catch (RuntimeException failed) {
            amount = 1;
        }
        if (part.isAnyMana() || part.isSpecialMana()) {
            return copies(ManaAtom.ALL_MANA_COLORS, amount);
        }
        if (part.isComboMana()) {
            int mask = 0;
            String colors = part.getComboColors(ability);
            if (colors != null) {
                for (String token : colors.split(" ")) {
                    if (!token.isEmpty()) mask |= ManaAtom.fromName(token);
                }
            }
            if (mask == 0) mask = ManaAtom.ALL_MANA_COLORS;
            return copies(mask, amount);
        }
        String produced = part.getOrigProduced();
        if (produced == null || produced.isBlank()) return List.of();
        List<Integer> one = new ArrayList<>();
        for (String token : produced.split(" ")) {
            if (token.isEmpty()) continue;
            boolean numeric = true;
            for (int i = 0; i < token.length(); i++) {
                if (!Character.isDigit(token.charAt(i))) numeric = false;
            }
            if (numeric) {
                int n = Integer.parseInt(token);
                for (int i = 0; i < n; i++) one.add(ManaAtom.COLORLESS);
                continue;
            }
            int bit = ManaAtom.fromName(token) & 0xFF;
            one.add(bit == 0 ? ManaAtom.COLORLESS : bit);
        }
        if (one.isEmpty()) return List.of();
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < amount; i++) all.addAll(one);
        return all;
    }

    private static List<Integer> copies(int mask, int count) {
        List<Integer> masks = new ArrayList<>();
        for (int i = 0; i < count; i++) masks.add(mask);
        return masks;
    }

    private Player youPlayer() {
        HostedMatch hosted = match;
        forge.game.Game game = hosted == null ? null : hosted.getGame();
        if (game == null) return null;
        for (Player player : game.getPlayers()) {
            if (player != null && player.getController() != null && !player.getController().isAI()) return player;
        }
        return null;
    }

    /**
     * The player's X, sent back through Forge's announce-X prompt.
     * Max is mana left after the non-X cost. Max-N holds N more back, and never goes below zero.
     * Max-counter is Max after the cheapest counterspell he can still cast is reserved too.
     */
    private static final class XAsk {
        static final int MODE_MAX = 0;
        static final int MODE_HOLD = 1;
        static final int MODE_COUNTER = 2;

        final int min;
        final int engineMax;
        final int maxPay;
        final int counterX;
        final int holdCap;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile int mode = MODE_MAX;
        volatile int hold;
        volatile boolean finished;
        volatile boolean cancelled;
        volatile int result;

        XAsk(int min, int engineMax, int maxPay, int counterX, int holdCap) {
            this.min = min;
            this.engineMax = engineMax;
            this.maxPay = Math.max(0, maxPay);
            this.counterX = Math.max(0, Math.min(this.maxPay, counterX));
            this.holdCap = Math.max(this.maxPay, holdCap);
        }

        synchronized void useMax() {
            if (finished) return;
            mode = MODE_MAX;
            hold = 0;
        }

        synchronized void useCounter() {
            if (finished) return;
            mode = MODE_COUNTER;
        }

        synchronized void nudge(int dir) {
            if (finished) return;
            mode = MODE_HOLD;
            hold = Math.max(0, Math.min(holdCap, hold + dir));
        }

        synchronized int announced() {
            int chosen = switch (mode) {
                case MODE_COUNTER -> counterX;
                case MODE_HOLD -> XCostMath.holdBack(maxPay, hold);
                default -> maxPay;
            };
            if (engineMax != Integer.MAX_VALUE) chosen = Math.min(chosen, engineMax);
            return Math.max(0, chosen);
        }

        synchronized void finish(boolean cancel) {
            if (finished) return;
            finished = true;
            cancelled = cancel;
            if (!cancel) result = announced();
            latch.countDown();
        }

        void forceClose() {
            finish(true);
        }
    }

    private final class MatchGui extends NetworkGuiGame {
        volatile String prompt = "";
        volatile String okLabel = "OK";
        volatile String cancelLabel = "Cancel";
        volatile boolean okEnabled;
        volatile boolean cancelEnabled;
        volatile PlayerView human;
        volatile PlayerView opponent;
        /** Host or Join session that created this window. Zero for a local Ash match. */
        volatile int born;

        private boolean retired() {
            return gui != this || (born != 0 && born != netGen.get());
        }

        @Override
        public boolean isLibgdxPort() {
            return true;
        }

        @Override
        protected void updateCurrentPlayer(PlayerView player) {
        }

        @Override
        public void setGameView(GameView next) {
            boolean resync = isResyncPending();
            if (resync) noteResyncCompleted();
            super.setGameView(next);
            pendingBoardRebuild = true;
            pendingHandWipe = true;
            if (!retired()) {
                assignPlayers();
                if (!seatStopsSeeded) publishSeatStops();
            }
        }

        @Override
        public void openView(forge.trackable.TrackableCollection<PlayerView> myPlayers) {
            if (retired()) return;
            open = true;
            if (myPlayers != null && !myPlayers.isEmpty()) human = myPlayers.iterator().next();
            assignPlayers();
            prompt = "Keep or mulligan. Drag a card up to play it. OK passes.";
            publishSeatStops();
        }

        /**
         * This seat's yourYield only. Never host enemy / Ash red.
         * SkipPhase updates, not isUiSetToSkipPhase — that path dwells.
         */
        void publishSeatStops() {
            if (retired() || !joinerSeat()) return;
            if (getGameView() == null || human == null) return;
            IGameController controller = seatController();
            if (controller == null) return;
            PlayerView you = human;
            for (PhaseType phase : PhaseType.values()) {
                int step = enemyStripIndex(phase);
                if (step < 0) continue;
                controller.sendYieldUpdate(new YieldUpdate.SkipPhase(you, phase, phaseYields(true, step)));
            }
            seatStopsSeeded = true;
        }

        /** Copies a pre-hand button prompt onto the lobby card. The paint thread only reads that card. */
        private void publishLobbyPrompt() {
            if (retired() || stopping || ready()) return;
            if (getGameView() == null) return;
            if (!okEnabled && !cancelEnabled) return;
            String line = prompt == null ? "" : prompt.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").trim();
            if (line.isBlank()) return;
            LobbyCard card = lobbyCard;
            if (card == null) return;
            status = line;
            lobbyCard = new LobbyCard(card.hostSide, false, card.starting, line, card.address, card.lan,
                    "", card.youName, card.youDeck, card.themName, card.themDeck, card.problem);
        }

        /**
         * This window's seat only. Do not follow getCurrentPlayer() onto Tiny
         * while she discards — that painted her hand on Host and treated her
         * cleanup as Steve's yielded step.
         */
        private void assignPlayers() {
            GameView view = getGameView();
            PlayerView local = liveSeat(human);
            if (local == null) {
                for (PlayerView player : getLocalPlayers()) {
                    local = liveSeat(player);
                    if (local != null) break;
                }
            }
            if (local == null) local = liveSeat(getCurrentPlayer());
            if (view != null && view.getPlayers() != null && local != null) {
                for (PlayerView player : view.getPlayers()) {
                    if (sameSeat(player, local)) {
                        local = liveSeat(player);
                        break;
                    }
                }
            }
            if (local != null) human = local;
            if (human == null || view == null || view.getPlayers() == null) return;
            for (PlayerView player : view.getPlayers()) {
                if (player != null && !sameSeat(player, human)) {
                    opponent = liveSeat(player);
                    break;
                }
            }
        }

        @Override
        protected void afterDeltaApplied() {
            if (!retired()) assignPlayers();
        }

        @Override
        public void showCombat() {
            if (retired()) return;
            snapshotCombat();
        }

        @Override
        public void updateStack() {
            if (retired()) return;
            snapshotStack();
        }

        @Override
        public void showPromptMessage(PlayerView playerView, String message, CardView card) {
            if (retired()) return;
            prompt = message == null ? "" : message;
            publishPlayableFromPrompt(prompt);
            publishLobbyPrompt();
            notePregameEdge();
            notePromptAfterResync();
            considerStackPass();
            considerEmptyPass();
        }

        @Override
        public void updateButtons(PlayerView owner, String label1, String label2,
                boolean enable1, boolean enable2, boolean focus1) {
            if (retired()) return;
            if (human == null && owner != null) human = owner;
            okLabel = label1 == null ? "OK" : label1;
            cancelLabel = label2 == null ? "Cancel" : label2;
            okEnabled = enable1;
            cancelEnabled = enable2;
            publishLobbyPrompt();
            notePregameEdge();
            notePromptAfterResync();
            considerStackPass();
            considerEmptyPass();
        }

        @Override
        public void setCombatDeclareInput(boolean attackers, boolean blockers) {
            super.setCombatDeclareInput(attackers, blockers);
            if (retired() || pregameChoice()) return;
            considerStackPass();
            considerEmptyPass();
        }

        @Override
        public void setWeaklySelectable(Iterable<CardView> cards) {
            super.setWeaklySelectable(cards);
            if (retired()) return;
            publishPlayableFromPrompt(prompt);
            if (pregameChoice()) return;
            considerStackPass();
            considerEmptyPass();
        }

        @Override
        public void clearWeaklySelectable() {
            super.clearWeaklySelectable();
            if (retired()) return;
            publishPlayableFromPrompt(prompt);
        }

        @Override
        public void flashIncorrectAction() {
        }

        @Override
        public void alertUser() {
        }

        @Override
        public forge.game.GameState getGamestate() {
            return null;
        }

        @Override
        public void finishGame() {
            if (retired()) return;
            prompt = "Game over.";
            okEnabled = false;
            cancelEnabled = false;
        }

        @Override
        public void setPanelSelection(CardView hostCard) {
        }

        @Override
        public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities, ITriggerEvent triggerEvent) {
            if (retired()) return null;
            if (abilities == null || abilities.isEmpty()) return null;
            SpellAbilityView followUp = confirmedAbility;
            if (followUp != null) {
                confirmedAbility = null;
                for (SpellAbilityView ability : abilities) {
                    if (sameAbility(followUp, ability)) return ability;
                }
            }
            boolean battlefield = hostCard != null && hostCard.getZone() == ZoneType.Battlefield;
            if (!battlefield) {
                List<SpellAbilityView> playable = new ArrayList<>();
                for (SpellAbilityView ability : abilities) {
                    if (ability != null && ability.canPlay()) playable.add(ability);
                }
                if (playable.isEmpty()) {
                    if (abilities.size() == 1) return abilities.get(0);
                    playable.addAll(abilities);
                }
                String title = hostCard == null || hostCard.getName() == null || hostCard.getName().isBlank()
                        ? "Choose a spell" : hostCard.getName();
                if (playable.size() == 1) return playable.get(0);
                return pickAbility(title, playable, hostCard, false);
            }
            List<SpellAbilityView> activated = new ArrayList<>();
            List<SpellAbilityView> triggers = new ArrayList<>();
            for (SpellAbilityView ability : abilities) {
                if (ability == null || ability.isSpell()) continue;
                // Triggers are not "playable" the way a tap ability is. Dropping them
                // here used to return null and swallow an ETB that Forge handed us.
                if (triggeredAbility(ability, hostCard)) {
                    triggers.add(ability);
                    continue;
                }
                if (!ability.canPlay()) continue;
                if (typeLineOnly(abilityText(ability, hostCard))) continue;
                activated.add(ability);
            }
            // A trigger is already happening. Nothing to pay and nothing to choose on this sheet.
            if (activated.isEmpty()) {
                if (triggers.size() == 1) return triggers.get(0);
                if (triggers.isEmpty()) return null;
                String title = hostCard == null || hostCard.getName() == null || hostCard.getName().isBlank()
                        ? "Choose a trigger" : hostCard.getName();
                return pickAbility(title, triggers, hostCard, false);
            }
            SpellAbilityView chosen = pickAbility("Confirm this ability before paying", activated, hostCard, true);
            if (chosen != null) confirmedAbility = chosen;
            return chosen;
        }

        /** Forge's pay step asks again with the ability he already confirmed. */
        private static boolean sameAbility(SpellAbilityView already, SpellAbilityView next) {
            if (already == null || next == null) return false;
            if (already == next) return true;
            String left = already.toString();
            String right = next.toString();
            if (left == null || right == null || !left.equals(right)) return false;
            CardView host = already.getHostCard();
            CardView other = next.getHostCard();
            if (host == null || other == null) return true;
            return host.getId() == other.getId();
        }

        /** The ability Forge is putting on the stack is one of this permanent's triggers. */
        private boolean triggeredAbility(SpellAbilityView ability, CardView host) {
            String text = ability == null || ability.toString() == null ? "" : ability.toString();
            if (triggeredAbilityText(text)) return true;
            if (host == null || match == null || match.getGame() == null) return false;
            try {
                Card card = match.getGame().findById(host.getId());
                if (card == null) return false;
                String shown = text.replace('\r', ' ').replace('\n', ' ').trim();
                for (Trigger trigger : card.getTriggers()) {
                    if (trigger == null) continue;
                    String desc = trigger.toString();
                    if (desc == null || desc.isBlank()) continue;
                    if (shown.equals(desc) || shown.endsWith(desc)) return true;
                }
            } catch (RuntimeException ignored) {
                return false;
            }
            return false;
        }

        private SpellAbilityView pickAbility(String message, List<SpellAbilityView> abilities,
                CardView hostCard, boolean confirm) {
            List<String> lines = new ArrayList<>();
            List<SpellAbilityView> shown = new ArrayList<>();
            for (SpellAbilityView ability : abilities) {
                if (ability == null) continue;
                String line = abilityText(ability, hostCard);
                if (confirm && typeLineOnly(line)) continue;
                if (line.isBlank()) continue;
                shown.add(ability);
                lines.add(line);
            }
            if (shown.isEmpty()) return null;
            if (shown.size() == 1) return shown.get(0);
            List<SpellAbilityView> picked = askAbility(message, shown, lines);
            return picked.isEmpty() ? null : picked.get(0);
        }

        @Override
        public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers,
                int damage, GameEntityView defender, boolean overrideOrder, boolean maySkip) {
            Map<CardView, Integer> assigned = new LinkedHashMap<>();
            if (damage <= 0 || blockers == null || blockers.isEmpty()) return assigned;
            if (retired()) {
                assigned.put(blockers.get(0), damage);
                return assigned;
            }
            boolean defenderToo = defender != null && (overrideOrder || tramples(attacker));
            if (blockers.size() == 1 && !defenderToo) {
                assigned.put(blockers.get(0), damage);
                return assigned;
            }
            List<Object> keys = new ArrayList<>();
            List<CardView> pictures = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (CardView blocker : blockers) {
                keys.add(blocker);
                pictures.add(blocker);
                labels.add(blocker == null || blocker.getName() == null ? "Blocker" : blocker.getName());
            }
            if (defenderToo) {
                keys.add(null);
                pictures.add(defender instanceof CardView card ? card : null);
                labels.add(entityName(defender));
            }
            int[] caps = new int[keys.size()];
            java.util.Arrays.fill(caps, damage);
            int[] lethal = new int[keys.size()];
            for (int i = 0; i < blockers.size() && i < lethal.length; i++) lethal[i] = lethalOf(attacker, blockers.get(i));
            String who = attacker == null || attacker.getName() == null ? "this creature" : attacker.getName();
            Map<Object, Integer> split = askAmounts(
                    "Assign " + damage + " combat damage from " + who, keys, pictures, labels, caps, damage, false,
                    !overrideOrder, lethal);
            for (Map.Entry<Object, Integer> entry : split.entrySet()) {
                if (entry.getKey() instanceof CardView card) assigned.put(card, entry.getValue());
                else assigned.put(null, entry.getValue());
            }
            return assigned;
        }

        @Override
        public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target,
                int amount, boolean atLeastOne, String amountLabel) {
            Map<Object, Integer> assigned = new LinkedHashMap<>();
            if (target == null || target.isEmpty() || amount <= 0) return assigned;
            if (retired() || target.size() == 1) {
                assigned.put(target.keySet().iterator().next(), amount);
                return assigned;
            }
            List<Object> keys = new ArrayList<>();
            List<CardView> pictures = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            int[] caps = new int[target.size()];
            int i = 0;
            for (Map.Entry<Object, Integer> entry : target.entrySet()) {
                Object key = entry.getKey();
                keys.add(key);
                pictures.add(key instanceof CardView card ? card : null);
                labels.add(amountName(key));
                int cap = entry.getValue() == null ? amount : entry.getValue();
                caps[i++] = Math.max(0, cap);
            }
            String what = amountLabel == null || amountLabel.isBlank() ? "this" : amountLabel;
            String source = effectSource == null || effectSource.getName() == null ? "" : effectSource.getName() + ": ";
            return askAmounts(source + "Divide " + amount + " " + what, keys, pictures, labels, caps, amount, atLeastOne);
        }

        @Override
        public void message(String message, String title) {
            if (retired()) return;
            prompt = message == null ? "" : message;
        }

        @Override
        public void showErrorDialog(String message, String title) {
            if (retired()) return;
            prompt = message == null ? "" : message;
            System.err.println(title + ": " + message);
        }

        @Override
        public boolean showConfirmDialog(String message, String title, String yesButtonText,
                String noButtonText, boolean defaultYes) {
            if (retired()) return defaultYes;
            if (commanderReturnAsk(message) || commanderReturnAsk(title)) {
                return askCommanderReturn(defaultYes, List.of(
                        yesButtonText == null ? "Yes" : yesButtonText,
                        noButtonText == null ? "No" : noButtonText));
            }
            List<String> options = List.of(
                    yesButtonText == null ? "Yes" : yesButtonText,
                    noButtonText == null ? "No" : noButtonText);
            List<String> picked = ask(message, 1, 1, options, null);
            if (picked.isEmpty()) return defaultYes;
            return options.get(0).equals(picked.get(0));
        }

        @Override
        public int showOptionDialog(String message, String title, FSkinProp icon,
                List<String> options, int defaultOption) {
            if (retired()) return defaultOption;
            if (options == null || options.isEmpty()) return defaultOption;
            List<String> picked = ask(message, 1, 1, options, null);
            if (picked.isEmpty()) return defaultOption;
            int index = options.indexOf(picked.get(0));
            return index < 0 ? defaultOption : index;
        }

        @Override
        public Integer getInteger(String message, int min, int max, boolean sortDesc) {
            if (retired()) return min;
            if (isAnnounceX(message)) return askAnnouncedX(message, min, max);
            return super.getInteger(message, min, max, sortDesc);
        }

        @Override
        public Integer getInteger(String message, int min, int max, int cutoff) {
            if (retired()) return min;
            if (isAnnounceX(message)) return askAnnouncedX(message, min, max);
            return super.getInteger(message, min, max, cutoff);
        }

        @Override
        public String showInputDialog(String message, String title, FSkinProp icon,
                String initialInput, List<String> inputOptions, boolean isNumeric) {
            if (retired()) return initialInput == null ? "" : initialInput;
            if (inputOptions != null && !inputOptions.isEmpty()) {
                List<String> picked = ask(message, 1, 1, inputOptions, null);
                return picked.isEmpty() ? initialInput : picked.get(0);
            }
            if (isNumeric) {
                List<String> numbers = new ArrayList<>();
                for (int n = 0; n <= 20; n++) numbers.add(Integer.toString(n));
                List<String> picked = ask(message, 1, 1, numbers, null);
                return picked.isEmpty() ? "0" : picked.get(0);
            }
            return initialInput == null ? "" : initialInput;
        }

        @Override
        public boolean confirm(CardView c,                 String question, boolean defaultIsYes, List<String> options) {
            if (retired()) return defaultIsYes;
            // Commander home: Yes is always return-to-command. The boolean Forge
            // gets is the option that actually puts the card in the command zone.
            if (commanderReturnAsk(question)) return askCommanderReturn(defaultIsYes, options);
            List<String> choices = options == null || options.isEmpty() ? List.of("Yes", "No") : options;
            // "Use this triggered ability?" even when the oracle has no "you may".
            // Show Yes/No. Do not auto-yes, and do not answer No without him.
            if (triggerUseAsk(question)) {
                List<String> picked = ask(question, 1, 1, choices, null);
                if (picked.isEmpty()) return false;
                return choices.get(0).equals(picked.get(0));
            }
            if (placementChoice(question, choices)) {
                return askScryConfirm(c, question, choices, defaultIsYes);
            }
            if (mayCastAsk(question)) {
                return askMayCast(c, question, choices, defaultIsYes);
            }
            if (peekRevealAsk(question)) {
                return askOptionalReveal(c, question, choices, defaultIsYes);
            }
            List<String> picked = ask(question, 1, 1, choices, null);
            if (picked.isEmpty()) return defaultIsYes;
            return choices.get(0).equals(picked.get(0));
        }

        @Override
        public <T> List<T> many(String title, String topCaption, int min, int max, List<T> sourceChoices,
                List<T> destChoices, CardView c) {
            if (retired()) return List.of();
            if (sourceChoices == null || sourceChoices.isEmpty()) return List.of();
            int lo = min < 0 ? 0 : min;
            int hi = max < 0 ? sourceChoices.size() : max;
            if (isCardList(sourceChoices) && scryLikeAsk(title)) {
                return askPlacement(presentChoice(title), sourceChoices, surveilAsk(title, null));
            }
            if (isCardList(sourceChoices)) {
                return askCards(presentChoice(title), lo, hi, sourceChoices, null);
            }
            if (sourceChoices.get(0) instanceof Integer) {
                int[] die = {1};
                return ask(title, lo, hi, sourceChoices, roll -> "Roll " + (die[0]++) + ": " + roll);
            }
            return ask(presentChoice(title), lo, hi, sourceChoices, null);
        }

        @Override
        public <T> List<T> getChoices(String message, int min, int max, List<T> choices,
                List<T> selected, FSerializableFunction<T, String> display) {
            if (retired()) return List.of();
            return ask(message, min, max, choices, display);
        }

        @Override
        public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                boolean sideboardingMode, boolean showRememberCheckbox) {
            if (retired()) {
                List<T> ordered = sourceChoices == null ? List.of() : new ArrayList<>(sourceChoices);
                return new OrderResult<>(ordered, false);
            }
            if (sideboardingMode) {
                List<T> kept = sourceChoices == null ? List.of() : new ArrayList<>(sourceChoices);
                return new OrderResult<>(kept, false);
            }
            List<T> source = sourceChoices == null ? List.of() : sourceChoices;
            List<T> dest = destChoices == null ? List.of() : destChoices;
            int sourceN = source.size();
            // DualListBox dest is the chosen subset. remainingObjects* are how many
            // stay in the source column. Cleanup discard-2-of-9 sends remaining 7 —
            // that is a pick of exactly 2, not an order of the whole hand.
            boolean canLeaveAll = remainingObjectsMax >= sourceN;
            boolean mustMoveSome = remainingObjectsMax >= 0 && remainingObjectsMax < sourceN;
            boolean subset = sourceN > 0 && (canLeaveAll || mustMoveSome);
            if (subset) {
                int pickMin = Math.max(0, sourceN - remainingObjectsMax);
                int pickMax = remainingObjectsMin < 0 ? sourceN : Math.max(pickMin, sourceN - remainingObjectsMin);
                List<T> picked = isCardList(source)
                        ? askCards(presentChoice(title), pickMin, pickMax, source, null)
                        : ask(presentChoice(title), pickMin, pickMax, source, null);
                if (!dest.isEmpty()) {
                    List<T> combined = new ArrayList<>(dest);
                    combined.addAll(picked);
                    return new OrderResult<>(combined, false);
                }
                return new OrderResult<>(picked, false);
            }
            List<T> pile = new ArrayList<>(dest);
            pile.addAll(source);
            if (pile.size() <= 1 && !scryLikeAsk(title) && !scryLikeAsk(top)) {
                return new OrderResult<>(pile, false);
            }
            List<T> ordered = isCardList(pile)
                    ? askOrder(orderStepTitle(title, top), pile)
                    : askWordOrder(orderPrompt(title, top), pile);
            return new OrderResult<>(ordered, false);
        }

        @Override
        public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
            if (retired()) return List.of();
            List<PaperCard> kept = new ArrayList<>();
            if (main != null) {
                for (PaperCard card : main.toFlatList()) kept.add(card);
            }
            return kept;
        }

        @Override
        public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList,
                DelayedReveal delayedReveal, boolean isOptional) {
            if (retired()) return null;
            if (optionList == null || optionList.isEmpty()) return null;
            List<GameEntityView> options = new ArrayList<>(optionList);
            if (isCardList(options)) {
                List<GameEntityView> picked = askCards(presentChoice(title), isOptional ? 0 : 1, 1, options,
                        delayedReveal == null ? null : delayedReveal.getCards());
                return picked.isEmpty() ? null : picked.get(0);
            }
            List<GameEntityView> picked = ask(title, isOptional ? 0 : 1, 1, options, GameEntityView::getName);
            return picked.isEmpty() ? null : picked.get(0);
        }

        @Override
        public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList,
                int min, int max, DelayedReveal delayedReveal) {
            if (retired()) return List.of();
            if (optionList == null || optionList.isEmpty()) return List.of();
            List<GameEntityView> options = new ArrayList<>(optionList);
            if (isCardList(options)) {
                return askCards(presentChoice(title), min, max, options,
                        delayedReveal == null ? null : delayedReveal.getCards());
            }
            return ask(title, min, max, options, GameEntityView::getName);
        }

        @Override
        public List<CardView> manipulateCardList(String title, Iterable<CardView> cards,
                Iterable<CardView> manipulable, boolean toTop, boolean toBottom, boolean toAnywhere) {
            if (retired()) return List.of();
            List<CardView> pile = new ArrayList<>();
            if (cards != null) {
                for (CardView card : cards) {
                    if (card != null) pile.add(card);
                }
            }
            if (pile.size() <= 1 || !(toAnywhere || toTop || toBottom)) return pile;
            String where = toAnywhere ? "Put them in any order, including the middle"
                    : "Order this pile";
            String head = title == null || title.isBlank() ? where : title;
            return askOrder(orderPrompt(head, "TOP"), pile);
        }

        @Override
        public void setCard(CardView card) {
        }

        @Override
        public void setPlayerAvatar(forge.LobbyPlayer player, forge.game.player.IHasIcon ihi) {
        }

        @Override
        public boolean isUiSetToSkipPhase(PlayerView playerTurn, PhaseType phase) {
            if (retired()) return true;
            if (phase == null) return true;
            int step = enemyStripIndex(phase);
            if (step < 0) return true;
            boolean yours = playerTurn == null || isThisSeat(playerTurn);
            // Host enemy / Ash red is computer only. Tiny is a human seat.
            if (!yours && !enemyStripAppliesTo(playerTurn)) return false;
            // Green holds. Red yields, after a short dwell so the gold outline is visible.
            if (!phaseYields(yours, step)) return false;
            dwellStep(playerTurn, phase);
            return true;
        }
    }

    /** Headless Forge base. Dialogs that the match GUI does not take land here and do not block. */
    private static final class Base implements IGuiBase {
        private final String assets;

        Base() {
            String dir = forgeGuiDir().toString();
            if (!dir.endsWith(File.separator)) dir = dir + File.separator;
            assets = dir;
        }

        @Override public boolean isRunningOnDesktop() { return true; }
        @Override public boolean isLibgdxPort() { return true; }
        @Override public String getCurrentVersion() { return "battlefield"; }
        @Override public void invokeInEdtNow(Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtLater(Runnable runnable) { runnable.run(); }
        @Override public void invokeInEdtAndWait(Runnable proc) { proc.run(); }
        @Override public void runBackgroundTask(String message, Runnable task) { task.run(); }
        @Override public boolean isGuiThread() { return false; }
        @Override public String getAssetsDir() { return assets; }
        @Override public ImageFetcher getImageFetcher() { return BattlefieldImageFetcher.INSTANCE; }
        @Override public ISkinImage getSkinIcon(FSkinProp skinProp) { return BLANK_SKIN; }
        @Override public ISkinImage getUnskinnedIcon(String path) { return BLANK_SKIN; }
        @Override public ISkinImage getCardArt(PaperCard card, boolean backFace) { return BLANK_SKIN; }
        @Override public ISkinImage createLayeredImage(PaperCard card, FSkinProp background, String overlayFilename, float opacity) { return BLANK_SKIN; }
        @Override public void clearImageCache() { }
        @Override public String encodeSymbols(String str, boolean formatReminderText) { return str == null ? "" : str; }
        @Override public int getAvatarCount() { return 0; }
        @Override public int getSleevesCount() { return 0; }
        @Override public float getScreenScale() { return 1f; }
        @Override public void preventSystemSleep(boolean preventSleep) { }
        @Override public void download(GuiDownloadService service, Consumer<Boolean> callback) { if (callback != null) callback.accept(false); }
        @Override public void copyToClipboard(String text) { }
        @Override public void browseToUrl(String url) { }
        @Override public void showCardList(String title, String message, List<PaperCard> list) { }
        @Override public boolean showBoxedProduct(String title, String message, List<PaperCard> list) { return false; }
        @Override public void showBugReportDialog(String title, String text, boolean showExitAppBtn) { System.err.println(title + ": " + text); }
        @Override public void showImageDialog(ISkinImage image, String message, String title) { }
        @Override public int showOptionDialog(String message, String title, FSkinProp icon, List<String> options, int defaultOption) { return defaultOption; }
        @Override public String showInputDialog(String message, String title, FSkinProp icon, String initialInput, List<String> inputOptions, boolean isNumeric) { return initialInput; }
        @Override public String showFileDialog(String title, String defaultDir) { return null; }
        @Override public File getSaveFile(File defaultFile) { return defaultFile; }
        @Override public <T> List<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax, List<T> sourceChoices, List<T> destChoices) { return sourceChoices == null ? List.of() : new ArrayList<>(sourceChoices); }
        @Override public <T> List<T> getChoices(String message, int min, int max, Collection<T> choices, Collection<T> selected, FSerializableFunction<T, String> display) { return choices == null ? List.of() : new ArrayList<>(choices); }
        @Override public PaperCard chooseCard(String title, String message, List<PaperCard> list) { return list == null || list.isEmpty() ? null : list.get(0); }
        @Override public boolean isSupportedAudioFormat(File file) { return false; }
        @Override public IAudioClip createAudioClip(String filename) { return SILENT; }
        @Override public IAudioMusic createAudioMusic(String filename) { return null; }
        @Override public void startAltSoundSystem(String filename, boolean isSynchronized) { }
        @Override public void showSpellShop() { }
        @Override public void showBazaar() { }
        @Override public IGuiGame getNewGuiGame() {
            IGuiGame ready = netGui;
            if (ready == null) return null;
            ready.setNetGame();
            return ready;
        }
        @Override public HostedMatch hostMatch() { return new HostedMatch(); }
        @Override public UpnpServiceConfiguration getUpnpPlatformService() { return null; }
        @Override public boolean hasNetGame() { return false; }
    }

    private static final IAudioClip SILENT = new IAudioClip() {
        @Override public void play(float value) { }
        @Override public boolean isDone() { return true; }
        @Override public void stop() { }
        @Override public void loop() { }
        @Override public void dispose() { }
    };
}
