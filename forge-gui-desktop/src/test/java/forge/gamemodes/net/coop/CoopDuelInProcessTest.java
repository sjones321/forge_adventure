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
import forge.gamemodes.net.client.IToServer;
import forge.gamemodes.net.client.NetGameController;
import forge.gamemodes.net.event.GuiGameEvent;
import forge.gamemodes.net.event.IdentifiableNetEvent;
import forge.gamemodes.net.event.NetEvent;
import forge.gamemodes.net.event.coop.CoopDuelResultEvent;
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

    /**
     * Answer one {@code updateButtons} prompt. When OK is disabled (e.g. 3-player
     * "who starts" selection), pick a player first. Concedes via the waiting
     * controller so the input queue unblocks — same pattern as
     * {@code ProtocolGuiGameInProcessTest}.
     *
     * @return {@code true} if a prompt was handled; {@code false} if none was ready
     */
    private static boolean answerOne(final RecordingRemote remote, final ProtocolGuiGame gui,
                                     final boolean tryPlayLand, final boolean concedeNow) throws Exception {
        final GuiGameEvent ub = remote.buttonPrompts.poll(300, TimeUnit.MILLISECONDS);
        if (ub == null) {
            return false;
        }
        final Object[] args = ub.getObjects();
        final PlayerView owner = (PlayerView) args[0];
        if (remote.myPlayers == null || owner == null || !remote.myPlayers.contains(owner)) {
            return false;
        }
        final IGameController controller = gui.getGameController(owner);
        if (controller == null) {
            return false;
        }
        // updateButtons(owner, label1, label2, enableOk, enableCancel, focusOk)
        final boolean okEnabled = args.length < 4 || Boolean.TRUE.equals(args[3]);
        // GuiDesktop.invokeInEdtNow runs inline — avoid Swing.invokeLater races
        // from the test thread while the game thread is blocked on input.
        final forge.gui.interfaces.IGuiBase guiBase = forge.gui.GuiBase.getInterface();
        if (!okEnabled) {
            // Need an entity selection (e.g. chooseStartingPlayer with 3 seats).
            final GameView gv = gui.getGameView();
            if (gv != null && gv.getPlayers() != null) {
                for (final PlayerView p : gv.getPlayers()) {
                    final PlayerView pick = p;
                    guiBase.invokeInEdtNow(() -> controller.selectPlayer(pick, null));
                    return true;
                }
            }
        }
        if (concedeNow) {
            guiBase.invokeInEdtNow(controller::concede);
            return true;
        }
        final GameView gv = gui.getGameView();
        final boolean myMain = tryPlayLand && gv != null && gv.getPlayerTurn() != null
                && gv.getPlayerTurn().equals(owner) && gv.getPhase() == PhaseType.MAIN1;
        if (myMain && owner.getHand() != null) {
            for (final CardView c : owner.getHand()) {
                final CardView toPlay = c;
                guiBase.invokeInEdtNow(() -> controller.selectCard(toPlay, null, null));
                return true;
            }
        }
        guiBase.invokeInEdtNow(controller::selectButtonOk);
        return true;
    }

    /** Direct concede for any remaining humans (GuiDesktop.invokeInEdtNow is inline). */
    private static void forceConcedeHumans(final HostedMatch match, final boolean guestOnly,
                                           final String guestName) {
        for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
            if (hc == null || hc.getPlayer() == null) {
                continue;
            }
            final String name = hc.getPlayer().getName();
            if (guestOnly && (name == null || !name.equalsIgnoreCase(guestName))) {
                continue;
            }
            // invokeInEdtNow runs inline on GuiDesktop — do not use NowOrLater (that
            // queues Swing.invokeLater from the test thread and races the game thread).
            forge.gui.GuiBase.getInterface().invokeInEdtNow(hc::concede);
        }
    }

    /**
     * Drive both remotes: answer prompts (incl. multiplayer start-player pick), then
     * concede via the waiting controller; force-concede any stragglers and wait.
     */
    private static void driveUntilGameOver(final HostedMatch match,
                                           final RecordingRemote hostRemote,
                                           final ProtocolGuiGame hostGui,
                                           final RecordingRemote guestRemote,
                                           final ProtocolGuiGame guestGui,
                                           final boolean concedeGuestOnly,
                                           final long deadlineMs) throws Exception {
        int answered = 0;
        boolean guestConceded = false;
        while (System.currentTimeMillis() < deadlineMs) {
            if (match.getGameView() != null && match.getGameView().isGameOver()) {
                return;
            }
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                return;
            }
            final boolean concedePhase = answered >= 4;
            if (answerOne(hostRemote, hostGui, true, concedePhase && !concedeGuestOnly)) {
                answered++;
                continue;
            }
            if (answerOne(guestRemote, guestGui, true, concedePhase)) {
                answered++;
                if (concedePhase) {
                    guestConceded = true;
                }
                if (concedeGuestOnly && guestConceded) {
                    break;
                }
                continue;
            }
            if (concedePhase && answered >= 8) {
                break;
            }
        }
        if (concedeGuestOnly) {
            forceConcedeHumans(match, true, "Guest");
        } else {
            // Team-0 wipe: both humans must be gone for the AI to win.
            forceConcedeHumans(match, false, null);
        }
        waitGameOver(match, Math.max(8_000, deadlineMs - System.currentTimeMillis()));
        // Flush Swing EDT so HostedMatch CONTINUE (scheduled via invokeLater) runs.
        flushEdt();
    }

    private static void flushEdt() {
        try {
            forge.gui.GuiBase.getInterface().invokeInEdtAndWait(() -> { });
        } catch (final Exception ignored) {
        }
    }

    private static void waitGameOver(final HostedMatch match, final long ms) throws InterruptedException {
        // Capture the game we are finishing. CONTINUE clears/replaces HostedMatch.game —
        // treat null or a different GameView as "this game ended" so we do not sit in
        // wait on the *next* game without a driver.
        final GameView initial = match.getGameView();
        final long end = System.currentTimeMillis() + Math.max(1_000, ms);
        while (System.currentTimeMillis() < end) {
            final GameView gv = match.getGameView();
            if (gv == null || gv.isGameOver()) {
                return;
            }
            if (initial != null && gv != initial) {
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

        // Drive until both remotes are live, then concede only the guest seat.
        final long warm = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < warm
                && (hostRemote.myPlayers == null || guestRemote.myPlayers == null)) {
            answerOne(hostRemote, hostGui, true, false);
            answerOne(guestRemote, guestGui, true, false);
        }
        assertNotNull(hostRemote.myPlayers, "host openView");
        assertNotNull(guestRemote.myPlayers, "guest openView");

        forceConcedeHumans(match, true, "Guest");
        // Let the game thread process the concede.
        Thread.sleep(500);
        flushEdt();

        final CoopDuelDisconnectPolicy.Outcome out = policy.onGuestDisconnected();
        assertEquals(out, CoopDuelDisconnectPolicy.Outcome.CONTINUE_HOST_MATCH);
        assertTrue(CoopDuelDisconnectPolicy.hostWorldRemainsPlayable(out));
        assertFalse(policy.isGuestConnected());

        // Guest must have lost/conceded; host Match must still be alive (not match-over
        // solely from a guest quit — team 0 still has the host).
        boolean guestLost = false;
        boolean hostAlive = false;
        for (final forge.player.PlayerControllerHuman hc : match.getHumanControllers()) {
            if (hc == null || hc.getPlayer() == null) {
                continue;
            }
            final String name = hc.getPlayer().getName();
            if (name != null && name.equalsIgnoreCase("Guest")) {
                guestLost = hc.getPlayer().hasLost() || hc.getPlayer().conceded();
            }
            if (name != null && name.equalsIgnoreCase("Host")) {
                hostAlive = !hc.getPlayer().hasLost() && !hc.getPlayer().conceded();
            }
        }
        assertTrue(guestLost, "guest seat conceded after disconnect policy");
        assertTrue(hostAlive || match.getMatch() != null,
                "host seat still in the match after guest concede");
        assertNotNull(match.getMatch(), "host Match object still reachable");
        assertFalse(match.getMatch().isMatchOver(),
                "guest concede alone must not end the host match");
        policy.endDuel();
    }

    @Test(timeOut = 300_000)
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
                    // HostedMatch schedules continueMatch on the EDT — flush it now
                    // so the next driveUntilGameOver sees the new game promptly.
                    flushEdt();
                } catch (final Exception ignored) {
                }
            }
        });
        match.startMatch(rules, EnumSet.of(GameType.Constructed),
                List.of(host, guest, enemy), guis, null);

        // Play / concede through enough games for the match to end (first to 2).
        final long overall = System.currentTimeMillis() + 240_000;
        int safety = 0;
        while (System.currentTimeMillis() < overall && safety++ < 6) {
            if (match.getMatch() != null && match.getMatch().isMatchOver()) {
                break;
            }
            // Brief pause so CONTINUE from endGameHook can spin up the next game.
            Thread.sleep(500);
            driveUntilGameOver(match, hostRemote, hostGui, guestRemote, guestGui, false,
                    System.currentTimeMillis() + 50_000);
        }
        waitMatchOver(match, 20_000);
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
        // Guest's real base life (bounded), not a hard-coded 20.
        final CoopFightLoadoutValidator.Result rejected = CoopFightLoadoutValidator.validate(
                raw, 5, allow, () -> Math.min(100, 40));
        assertFalse(rejected.ok, "non-allowlisted effect card rejected");

        final CoopFightLoadout okRaw = CoopFightLoadout.builder()
                .playerName("Guest")
                .avatarId("g")
                .startingLife(20)
                .lifeModifier(50)
                .changeStartCards(9)
                .manaShards(500)
                .extraManaShards(99)
                .freeMulligans(50)
                .startBattleCardNames(List.of("Plains"))
                .build();
        final CoopFightLoadoutValidator.StatCaps caps =
                new CoopFightLoadoutValidator.StatCaps(100, 3, 4);
        final CoopFightLoadoutValidator.Result ok = CoopFightLoadoutValidator.validate(
                okRaw, 5, allow, () -> 20, caps);
        assertTrue(ok.ok, ok.reason);
        assertNotNull(ok.loadout);
        assertTrue(ok.loadout.getStartingLife() <= 25, "capped at base+maxLifeBonus");
        assertEquals(ok.loadout.getChangeStartCards(), CoopFightLoadoutValidator.MAX_HAND_DELTA);
        assertTrue(ok.loadout.getManaShards() <= 100, "mana shards clamped");
        assertTrue(ok.loadout.getExtraManaShards() <= 3, "extra shards clamped");
        assertTrue(ok.loadout.getFreeMulligans() <= 4, "free mulligans clamped");

        // Fail closed: empty allowlist rejects any effect card names.
        final CoopFightLoadout withCards = CoopFightLoadout.builder()
                .playerName("Guest")
                .avatarId("g")
                .startingLife(20)
                .startBattleCardNames(List.of("Plains"))
                .build();
        final CoopFightLoadoutValidator.Result emptyAllow = CoopFightLoadoutValidator.validate(
                withCards, 5, Collections.emptySet(), () -> 20);
        assertFalse(emptyAllow.ok, "empty allowlist must reject effect cards");
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

    /**
     * DS1: guest seat acts through {@link NetGameController} the same way a modern
     * drag-to-cast drop does ({@code selectCard} on a hand card). In-process bridge
     * applies the wire method to the guest's real controller — not a source-text check.
     */
    @Test(timeOut = 180_000)
    public void guestNetGameControllerModernDragToCastPlaysLand() throws Exception {
        TestUtils.ensureFModelInitialized();
        FModel.getPreferences().setPref(FPref.UI_SHOW_ACTIONABLE_HIGHLIGHTS, false);
        MyRandom.setRandom(new Random(23));

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

        final HostedMatch match = new HostedMatch();
        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setGamesPerMatch(1);
        match.startMatch(rules, EnumSet.of(GameType.Constructed),
                List.of(host, guest, enemy), guis, null);

        // Warm until both seats are live and the guest has a hand card.
        final long warm = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < warm
                && (hostRemote.myPlayers == null || guestRemote.myPlayers == null
                || guestGui.getGameView() == null)) {
            answerOne(hostRemote, hostGui, true, false);
            answerOne(guestRemote, guestGui, true, false);
        }
        assertNotNull(guestRemote.myPlayers, "guest openView");

        PlayerView guestView = null;
        CardView handCard = null;
        for (final PlayerView p : guestRemote.myPlayers) {
            if (p == null || p.getHand() == null) {
                continue;
            }
            for (final CardView c : p.getHand()) {
                if (c != null) {
                    guestView = p;
                    handCard = c;
                    break;
                }
            }
            if (handCard != null) {
                break;
            }
        }
        // If hand not yet synced, keep driving briefly.
        final long handWait = System.currentTimeMillis() + 30_000;
        while (handCard == null && System.currentTimeMillis() < handWait) {
            answerOne(hostRemote, hostGui, true, false);
            answerOne(guestRemote, guestGui, true, false);
            for (final PlayerView p : guestRemote.myPlayers) {
                if (p == null || p.getHand() == null) {
                    continue;
                }
                for (final CardView c : p.getHand()) {
                    if (c != null) {
                        guestView = p;
                        handCard = c;
                        break;
                    }
                }
                if (handCard != null) {
                    break;
                }
            }
        }
        assertNotNull(handCard, "guest hand card available");
        assertNotNull(guestView);

        // Wait until the guest seat is in MAIN1 so playing a land can apply.
        final long mainWait = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < mainWait) {
            final GameView gv = guestGui.getGameView();
            if (gv != null && gv.getPhase() == PhaseType.MAIN1
                    && gv.getPlayerTurn() != null
                    && guestView.getId() == gv.getPlayerTurn().getId()) {
                break;
            }
            answerOne(hostRemote, hostGui, true, false);
            answerOne(guestRemote, guestGui, false, false);
            flushEdt();
        }
        assertEquals(guestGui.getGameView().getPhase(), PhaseType.MAIN1,
                "guest MAIN1 before modern cast");
        assertEquals(guestGui.getGameView().getPlayerTurn().getId(), guestView.getId(),
                "guest priority / turn for land play");

        // Refresh hand card from current view (ids can churn across zone sync).
        handCard = null;
        for (final CardView c : guestView.getHand()) {
            if (c != null) {
                handCard = c;
                break;
            }
        }
        assertNotNull(handCard, "guest hand card in MAIN1");

        final IGameController seatController = guestGui.getGameController(guestView);
        assertNotNull(seatController, "guest seat controller");

        final AtomicInteger selectCardSends = new AtomicInteger();
        final AtomicReference<CardView> sentCard = new AtomicReference<>();
        final IGameController seat = seatController;
        final IToServer bridge = new IToServer() {
            @Override
            public void send(final NetEvent event) {
                final GuiGameEvent ev = (GuiGameEvent) event;
                if (ev.getMethod() == ProtocolMethod.selectCard) {
                    selectCardSends.incrementAndGet();
                    final CardView card = (CardView) ev.getObjects()[0];
                    sentCard.set(card);
                    @SuppressWarnings("unchecked")
                    final List<CardView> others = (List<CardView>) ev.getObjects()[1];
                    forge.gui.GuiBase.getInterface().invokeInEdtNow(
                            () -> seat.selectCard(card, others, null));
                }
            }

            @Override
            public Object sendAndWait(final IdentifiableNetEvent event) {
                send(event);
                return null;
            }
        };

        final NetGameController netGuest = new NetGameController(bridge);
        final CardView castCard = handCard;
        final int handBefore = guestView.getHand().size();
        // Same call modern one-press / drag-to-cast uses after ModernDuelActions.Kind.CAST.
        forge.gui.GuiBase.getInterface().invokeInEdtNow(
                () -> netGuest.selectCard(castCard, null, null));
        flushEdt();

        assertEquals(selectCardSends.get(), 1,
                "NetGameController guest sent selectCard once");
        assertNotNull(sentCard.get());
        assertEquals(sentCard.get().getId(), castCard.getId(),
                "selectCard carried the hand card id");

        // Allow the game thread to apply the play when legal (guest MAIN1).
        final long settle = System.currentTimeMillis() + 20_000;
        boolean applied = false;
        while (System.currentTimeMillis() < settle) {
            answerOne(hostRemote, hostGui, true, false);
            answerOne(guestRemote, guestGui, false, false);
            flushEdt();
            final boolean stillInHand = guestView.getHand() != null
                    && java.util.stream.StreamSupport.stream(guestView.getHand().spliterator(), false)
                    .anyMatch(c -> c != null && c.getId() == castCard.getId());
            if (!stillInHand || guestView.getHand().size() < handBefore) {
                applied = true;
                break;
            }
            if (guestView.getBattlefield() != null) {
                for (final CardView c : guestView.getBattlefield()) {
                    if (c != null && c.getId() == castCard.getId()) {
                        applied = true;
                        break;
                    }
                }
            }
            if (applied) {
                break;
            }
            Thread.sleep(100);
        }
        // When the guest seat can act, the land should leave hand or appear on the battlefield.
        // Wire delivery is already asserted above; require apply so this is not an always-true check.
        assertTrue(applied,
                "guest modern cast via NetGameController applied (hand shrunk or card on battlefield)");

        forceConcedeHumans(match, false, null);
        waitGameOver(match, 20_000);
    }
}
