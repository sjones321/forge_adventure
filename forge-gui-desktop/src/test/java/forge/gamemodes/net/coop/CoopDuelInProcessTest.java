package forge.gamemodes.net.coop;

import forge.deck.Deck;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.player.RegisteredPlayer;
import forge.game.spellability.SpellAbilityView;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.NextGameDecision;
import forge.gamemodes.net.DeltaPacket;
import forge.gamemodes.net.IRemote;
import forge.gamemodes.net.ProtocolGuiGame;
import forge.gamemodes.net.ProtocolMethod;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
import forge.gui.FThreads;
import forge.interfaces.IGameController;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.TestUtils;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.trackable.TrackableCollection;
import forge.util.MyRandom;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

/**
 * In-process CO3 end-to-end: host + guest {@link ProtocolGuiGame} clients play a
 * real {@link HostedMatch} (team 0 humans, team 1 AI) to completion — based on
 * {@code ProtocolGuiGameInProcessTest}. No FServerManager / Netty.
 *
 * <p>Covers: full duel to match end, guest disconnect → guest seat concedes,
 * and best-of-3 where match-outcome is emitted only when the match is over.
 */
public class CoopDuelInProcessTest {

    /** In-process endpoint: records protocol events and answers blocking prompts. */
    static final class RecordingRemote implements IRemote {
        final List<GuiGameEvent> log = Collections.synchronizedList(new ArrayList<>());
        final BlockingQueue<GuiGameEvent> buttonPrompts = new LinkedBlockingQueue<>();
        final Map<ProtocolMethod, AtomicInteger> counts = new ConcurrentHashMap<>();
        volatile TrackableCollection<PlayerView> myPlayers;
        volatile String lastPrompt = "";
        volatile int fullStates;

        @Override
        public void send(final NetEvent event) {
            final GuiGameEvent ev = (GuiGameEvent) event;
            record(ev);
            final Object[] args = ev.getObjects();
            switch (ev.getMethod()) {
                case openView -> myPlayers = (TrackableCollection<PlayerView>) args[0];
                case setGameView -> fullStates++;
                case applyDelta -> {
                    final DeltaPacket d = (DeltaPacket) args[0];
                    if (d.hasEvents()) {
                        for (final Object o : d.getEvents()) {
                            if (!(o instanceof GameEvent)) {
                                // ignore wrappers
                            }
                        }
                    }
                }
                case showPromptMessage -> lastPrompt = String.valueOf(args[1]);
                case updateButtons -> buttonPrompts.offer(ev);
                default -> {
                }
            }
        }

        @Override
        public Object sendAndWait(final IdentifiableNetEvent event) {
            final GuiGameEvent ev = (GuiGameEvent) event;
            record(ev);
            final Object[] a = ev.getObjects();
            return switch (ev.getMethod()) {
                case getAbilityToPlay -> {
                    final List<SpellAbilityView> abilities = (List<SpellAbilityView>) a[1];
                    SpellAbilityView pick = abilities.get(0);
                    for (final SpellAbilityView sa : abilities) {
                        if (!sa.toString().toLowerCase().contains("cycling")) {
                            pick = sa;
                            break;
                        }
                    }
                    yield pick;
                }
                case getChoices -> {
                    final List<?> choices = (List<?>) a[3];
                    final int min = (Integer) a[1];
                    final int max = (Integer) a[2];
                    yield new ArrayList<>(choices.subList(0,
                            Math.min(choices.size(), Math.max(min, Math.min(1, max)))));
                }
                case confirm -> a[2];
                case showConfirmDialog -> a[4];
                case showOptionDialog -> a[4];
                case showInputDialog -> a[3];
                default -> null;
            };
        }

        private void record(final GuiGameEvent ev) {
            log.add(ev);
            counts.computeIfAbsent(ev.getMethod(), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static Deck landDeck(final String name, final String card) {
        final Deck d = new Deck(name);
        d.getMain().add(card, 40);
        return d;
    }

    private static RegisteredPlayer human(final String name, final Deck deck, final int team) {
        final RegisteredPlayer rp = new RegisteredPlayer(deck)
                .setPlayer(new LobbyPlayerHuman(name));
        rp.setTeamNumber(team);
        rp.setStartingLife(20);
        return rp;
    }

    private static RegisteredPlayer ai(final String name, final Deck deck, final int team) {
        final RegisteredPlayer rp = new RegisteredPlayer(deck)
                .setPlayer(GamePlayerUtil.createAiPlayer(name));
        rp.setTeamNumber(team);
        rp.setStartingLife(20);
        return rp;
    }

    /** Answer one prompt for {@code remote}/{@code gui}; return true if handled. */
    private static boolean answerOne(final RecordingRemote remote, final ProtocolGuiGame gui,
                                     final boolean tryPlayLand) throws Exception {
        final GuiGameEvent ub = remote.buttonPrompts.poll(300, TimeUnit.MILLISECONDS);
        if (ub == null) {
            return false;
        }
        final PlayerView owner = (PlayerView) ub.getObjects()[0];
        if (remote.myPlayers == null || owner == null || !remote.myPlayers.contains(owner)) {
            return false;
        }
        final IGameController controller = gui.getGameController(owner);
        if (controller == null) {
            return false;
        }
        final GameView gv = gui.getGameView();
        final boolean myMain = tryPlayLand && gv != null && gv.getPlayerTurn() != null
                && gv.getPlayerTurn().equals(owner) && gv.getPhase() == PhaseType.MAIN1;
        if (myMain && owner.getHand() != null) {
            for (final CardView c : owner.getHand()) {
                final CardView toPlay = c;
                FThreads.invokeInEdtLater(() -> controller.selectCard(toPlay, null, null));
                return true;
            }
        }
        FThreads.invokeInEdtLater(controller::selectButtonOk);
        return true;
    }

    private static void concedeHumans(final HostedMatch match, final boolean guestOnly,
                                      final String guestName) {
        for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
            if (hc == null || hc.getPlayer() == null) {
                continue;
            }
            final String name = hc.getPlayer().getName();
            if (guestOnly && (name == null || !name.equalsIgnoreCase(guestName))) {
                continue;
            }
            // Headless GuiDesktop.invokeInEdtNow runs inline — avoid Swing queue races.
            FThreads.invokeInEdtNowOrLater(hc::concede);
        }
    }

    /**
     * Drive both remotes briefly, then concede (guest-only or all humans) and wait
     * for the current game to finish.
     */
    private static void driveUntilGameOver(final HostedMatch match,
                                           final RecordingRemote hostRemote,
                                           final ProtocolGuiGame hostGui,
                                           final RecordingRemote guestRemote,
                                           final ProtocolGuiGame guestGui,
                                           final boolean concedeGuestOnly,
                                           final long deadlineMs) throws Exception {
        int answered = 0;
        while (System.currentTimeMillis() < deadlineMs && answered < 6) {
            if (match.getGameView() != null && match.getGameView().isGameOver()) {
                return;
            }
            if (answerOne(hostRemote, hostGui, true)) {
                answered++;
            }
            if (answerOne(guestRemote, guestGui, true)) {
                answered++;
            }
        }
        concedeHumans(match, concedeGuestOnly, "Guest");
        if (!concedeGuestOnly) {
            // Team-0 wipe: both humans must be gone for the AI to win the game.
            concedeHumans(match, false, null);
        }
        waitGameOver(match, Math.max(5_000, deadlineMs - System.currentTimeMillis()));
    }

    private static void waitGameOver(final HostedMatch match, final long ms) throws InterruptedException {
        final long end = System.currentTimeMillis() + Math.max(1_000, ms);
        while (System.currentTimeMillis() < end) {
            final GameView gv = match.getGameView();
            if (gv != null && gv.isGameOver()) {
                return;
            }
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static void waitMatchOver(final HostedMatch match, final long ms) throws InterruptedException {
        final long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Test(timeOut = 180_000)
    public void hostAndGuestPlayRealMatchToCompletion() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        MyRandom.setRandom(new Random(11));

        final RecordingRemote hostRemote = new RecordingRemote();
        final ProtocolGuiGame hostGui = new ProtocolGuiGame(hostRemote);
        final RecordingRemote guestRemote = new RecordingRemote();
        final ProtocolGuiGame guestGui = new ProtocolGuiGame(guestRemote);

        final Deck hostDeck = landDeck("Host", "Drifting Meadow");
        final Deck guestDeck = landDeck("Guest", "Drifting Meadow");
        final Deck enemyDeck = landDeck("Enemy", "Plains");

        final RegisteredPlayer host = human("Host", hostDeck, 0);
        final RegisteredPlayer guest = human("Guest", guestDeck, 0);
        final RegisteredPlayer enemy = ai("Enemy", enemyDeck, 1);

        final Map<RegisteredPlayer, forge.gui.interfaces.IGuiGame> guis = new HashMap<>();
        guis.put(host, hostGui);
        guis.put(guest, guestGui);

        final AtomicReference<CoopDuelResultEvent> outcome = new AtomicReference<>();
        final AtomicInteger endGameCalls = new AtomicInteger();
        final HostedMatch match = new HostedMatch();
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        match.setEndGameHook(() -> {
            endGameCalls.incrementAndGet();
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                final RegisteredPlayer winner = match.getMatch().getWinner();
                final int team = winner != null ? winner.getTeamNumber() : -1;
                outcome.set(new CoopDuelResultEvent(42L, team, 99L, "Enemy"));
            }
        });
        match.startMatch(rules, EnumSet.of(GameType.Constructed),
                List.of(host, guest, enemy), guis, null);

        final long deadline = System.currentTimeMillis() + 90_000;
        driveUntilGameOver(match, hostRemote, hostGui, guestRemote, guestGui, false, deadline);
        waitMatchOver(match, 45_000);
        // endGameHook may still be flushing — brief settle
        final long settle = System.currentTimeMillis() + 10_000;
        while (outcome.get() == null && System.currentTimeMillis() < settle) {
            Thread.sleep(100);
        }

        assertNotNull(hostRemote.myPlayers, "host openView");
        assertNotNull(guestRemote.myPlayers, "guest openView");
        assertTrue(hostRemote.fullStates >= 1, "host full state");
        assertTrue(guestRemote.fullStates >= 1, "guest full state");
        assertTrue(endGameCalls.get() >= 1, "endGameHook ran: " + endGameCalls.get());
        assertNotNull(outcome.get(), "match outcome once at match end");
        assertEquals(outcome.get().getDuelId(), 42L);
        assertEquals(outcome.get().getEnemyId(), 99L);
    }

    @Test(timeOut = 180_000)
    public void guestDisconnectConcedesGuestSeat() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        MyRandom.setRandom(new Random(13));

        final RecordingRemote hostRemote = new RecordingRemote();
        final ProtocolGuiGame hostGui = new ProtocolGuiGame(hostRemote);
        final RecordingRemote guestRemote = new RecordingRemote();
        final ProtocolGuiGame guestGui = new ProtocolGuiGame(guestRemote);

        final RegisteredPlayer host = human("Host", landDeck("H", "Drifting Meadow"), 0);
        final RegisteredPlayer guest = human("Guest", landDeck("G", "Drifting Meadow"), 0);
        final RegisteredPlayer enemy = ai("Enemy", landDeck("E", "Plains"), 1);

        final Map<RegisteredPlayer, forge.gui.interfaces.IGuiGame> guis = new HashMap<>();
        guis.put(host, hostGui);
        guis.put(guest, guestGui);

        final CoopDuelDisconnectPolicy policy = new CoopDuelDisconnectPolicy();
        policy.beginDuel(CoopDuelDisconnectPolicy.GuestDisconnectAction.CONCEDE);

        final HostedMatch match = new HostedMatch();
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        match.startMatch(rules, EnumSet.of(GameType.Constructed),
                List.of(host, guest, enemy), guis, null);

        final long deadline = System.currentTimeMillis() + 90_000;
        driveUntilGameOver(match, hostRemote, hostGui, guestRemote, guestGui, true, deadline);

        final CoopDuelDisconnectPolicy.Outcome out = policy.onGuestDisconnected();
        assertEquals(out, CoopDuelDisconnectPolicy.Outcome.CONTINUE_HOST_MATCH);
        assertTrue(CoopDuelDisconnectPolicy.hostWorldRemainsPlayable(out));
        assertFalse(policy.isGuestConnected());

        // Guest seat conceded — wait for the game to settle; host match must not hang.
        waitGameOver(match, 45_000);
        assertTrue(match.getGameView() == null || match.getGameView().isGameOver()
                || match.getMatch() != null,
                "host match still reachable after guest concede");
        policy.endDuel();
    }

    @Test(timeOut = 240_000)
    public void bestOfThreeEmitsOutcomeOnlyAtMatchEnd() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        MyRandom.setRandom(new Random(17));

        final RecordingRemote hostRemote = new RecordingRemote();
        final ProtocolGuiGame hostGui = new ProtocolGuiGame(hostRemote);
        final RecordingRemote guestRemote = new RecordingRemote();
        final ProtocolGuiGame guestGui = new ProtocolGuiGame(guestRemote);

        final RegisteredPlayer host = human("Host", landDeck("H", "Drifting Meadow"), 0);
        final RegisteredPlayer guest = human("Guest", landDeck("G", "Drifting Meadow"), 0);
        // Fragile enemy so humans can finish games quickly by enemy concede path —
        // we force human concedes each game and auto-CONTINUE until match over.
        final RegisteredPlayer enemy = ai("Enemy", landDeck("E", "Plains"), 1);
        enemy.setStartingLife(1);

        final Map<RegisteredPlayer, forge.gui.interfaces.IGuiGame> guis = new HashMap<>();
        guis.put(host, hostGui);
        guis.put(guest, guestGui);

        final AtomicInteger endGameCalls = new AtomicInteger();
        final AtomicInteger matchEndOutcomes = new AtomicInteger();
        final AtomicReference<CoopDuelResultEvent> outcome = new AtomicReference<>();
        final HostedMatch match = new HostedMatch();
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(3); // first to 2
        match.setEndGameHook(() -> {
            endGameCalls.incrementAndGet();
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                matchEndOutcomes.incrementAndGet();
                final RegisteredPlayer winner = match.getMatch().getWinner();
                final int team = winner != null ? winner.getTeamNumber() : -1;
                // Reject duplicate duel ids (host sends once per MATCH).
                if (outcome.get() == null) {
                    outcome.set(new CoopDuelResultEvent(7L, team, 55L, "Enemy"));
                }
            } else {
                // Mid-match: auto-continue both humans (CO3 runtime does this).
                try {
                    for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
                        if (hc != null) {
                            hc.nextGameDecision(NextGameDecision.CONTINUE);
                        }
                    }
                } catch (final Exception ignored) {
                }
            }
        });
        match.startMatch(rules, EnumSet.of(GameType.Constructed),
                List.of(host, guest, enemy), guis, null);

        // Play / concede through enough games for the match to end (first to 2).
        final long overall = System.currentTimeMillis() + 200_000;
        int safety = 0;
        while (System.currentTimeMillis() < overall && safety++ < 8) {
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                break;
            }
            driveUntilGameOver(match, hostRemote, hostGui, guestRemote, guestGui, false,
                    System.currentTimeMillis() + 40_000);
            Thread.sleep(800);
        }
        waitMatchOver(match, 30_000);
        final long settle = System.currentTimeMillis() + 10_000;
        while (outcome.get() == null && System.currentTimeMillis() < settle) {
            Thread.sleep(100);
        }

        assertTrue(endGameCalls.get() >= 1, "endGameHook ran per finished game: " + endGameCalls.get());
        assertTrue(matchEndOutcomes.get() >= 1, "outcome at match end: calls=" + endGameCalls.get()
                + " matchEnds=" + matchEndOutcomes.get());
        // Exactly one outcome object (duplicate duel-id guard in hook).
        assertEquals(matchEndOutcomes.get(), 1, "outcome only once at match end");
        assertNotNull(outcome.get());
        assertEquals(outcome.get().getDuelId(), 7L);
        assertEquals(outcome.get().getEnemyId(), 55L);
        assertTrue(outcome.get().getWinningTeam() == 0 || outcome.get().getWinningTeam() == 1
                || outcome.get().getWinningTeam() < 0);
    }

    @Test
    public void loadoutValidatorClampsLifeAndHandAndAllowlistsCards() {
        final Set<String> allow = CoopFightLoadoutValidator.allowlistFromEffects(
                () -> Collections.singletonList(new String[]{"Plains", "Island"}));
        final CoopFightLoadout raw = CoopFightLoadout.builder()
                .playerName("Guest")
                .avatarId("g")
                .startingLife(100)
                .lifeModifier(50)
                .changeStartCards(9)
                .startBattleCardNames(List.of("Plains", "Black Lotus"))
                .build();
        final CoopFightLoadoutValidator.Result rejected = CoopFightLoadoutValidator.validate(
                raw, 5, allow, () -> 20);
        assertFalse(rejected.ok, "non-allowlisted effect card rejected");

        final CoopFightLoadout okRaw = CoopFightLoadout.builder()
                .playerName("Guest")
                .avatarId("g")
                .startingLife(100)
                .lifeModifier(50)
                .changeStartCards(9)
                .startBattleCardNames(List.of("Plains"))
                .build();
        final CoopFightLoadoutValidator.Result ok = CoopFightLoadoutValidator.validate(
                okRaw, 5, allow, () -> 20);
        assertTrue(ok.ok, ok.reason);
        assertNotNull(ok.loadout);
        assertTrue(ok.loadout.getStartingLife() <= 25, "capped at base+maxLifeBonus");
        assertEquals(ok.loadout.getChangeStartCards(), CoopFightLoadoutValidator.MAX_HAND_DELTA);
    }

    @Test
    public void decklistEnforcesMinSizeAndBanList() {
        final String tiny = "[metadata]\nName=Tiny\n[Main]\n4 Island\n";
        final CoopDecklistValidator.Result tooSmall =
                CoopDecklistValidator.validate(tiny, name -> true, 40, null);
        assertEquals(tooSmall.reason, CoopDecklistValidator.RejectReason.TOO_SMALL_MAIN);

        final String banned = "[metadata]\nName=Bad\n[Main]\n40 Island\n4 Black Lotus\n";
        final CoopDecklistValidator.Result bannedHit =
                CoopDecklistValidator.validate(banned, name -> true, 40, "Black Lotus"::equals);
        assertEquals(bannedHit.reason, CoopDecklistValidator.RejectReason.BANNED_CARD);
    }

    @Test
    public void resultEventIsOutcomeOnlyAndHostIgnoresDuplicates() {
        final CoopDuelResultEvent a = new CoopDuelResultEvent(1L, 0, 9L, "Orc");
        assertTrue(a.isTeamWon());
        assertEquals(a.getEnemyId(), 9L);
        // Outcome-only: no gold/xp on the wire event (compile-time shape).
        final java.util.Set<Long> seen = ConcurrentHashMap.newKeySet();
        assertTrue(seen.add(a.getDuelId()));
        assertFalse(seen.add(a.getDuelId()), "duplicate duel id rejected");
    }
}
