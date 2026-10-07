package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.GymFighterData;
import forge.adventure.data.GymRewardData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.card.CardRarity;
import forge.card.ColorSet;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckgenUtil;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.gamemodes.quest.QuestController;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.Aggregates;
import forge.util.MyRandom;
import forge.util.StreamUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Shared helpers for gyms and the League: format deck resolution, EnemyData build,
 * reward grant, and run-format legality checks (package K will expand formats).
 *
 * Deck generation intentionally avoids {@link DeckgenUtil#getRandomOrPreconOrThemeDeck}'s
 * color switch (missing breaks — brief forbids editing forge-gui) and LDA archetype
 * generation when LDA data is unloaded (NPE). Color decks are built via
 * {@link DeckgenUtil#buildColorDeck} / filtered precon proxies instead.
 */
public final class GymUtil {
    public static final String FORMAT_STANDARD = "Standard";
    public static final String FORMAT_PAUPER = "Pauper";
    public static final String FORMAT_HISTORIC = "Historic";
    public static final String FORMAT_COMMANDER = "Commander";
    /** Sentinel in gyms.json deck maps: generate a format-legal deck at challenge time. */
    public static final String GENERATE = "$generate";

    private GymUtil() {
    }

    /**
     * Resolves the deck path for the current run format.
     * Falls back to Standard when the format key is missing (package K not yet filled).
     */
    public static String resolveDeckPath(GymFighterData fighter, int badgesHeld, boolean rematch) {
        if (fighter == null)
            return null;
        String format = Current.player().getRunFormat();
        if (rematch && fighter.rematchDecks != null) {
            String path = pickFormat(fighter.rematchDecks, format);
            if (path != null && !path.isEmpty())
                return path;
        }
        if (fighter.decksByBadges != null && fighter.decksByBadges.size > 0) {
            ObjectMap<String, String> best = null;
            int bestThreshold = -1;
            for (ObjectMap.Entry<String, ObjectMap<String, String>> e : fighter.decksByBadges.entries()) {
                int threshold;
                try {
                    threshold = Integer.parseInt(e.key);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (threshold <= badgesHeld && threshold >= bestThreshold) {
                    bestThreshold = threshold;
                    best = e.value;
                }
            }
            if (best != null) {
                String path = pickFormat(best, format);
                if (path != null && !path.isEmpty())
                    return path;
            }
        }
        if (fighter.decks != null)
            return pickFormat(fighter.decks, format);
        return null;
    }

    private static String pickFormat(ObjectMap<String, String> decks, String format) {
        if (decks == null)
            return null;
        String path = decks.get(format);
        if (path != null && !path.isEmpty())
            return path;
        return decks.get(FORMAT_STANDARD);
    }

    /** True when the path means "build a legal deck now" rather than load a .dck file. */
    public static boolean shouldGenerateDeck(String path) {
        if (path == null || path.isEmpty() || GENERATE.equals(path))
            return true;
        // Legacy Package G decks were all *_standard.dck with non-window cards — generate instead.
        if (path.contains("/gym/") && path.endsWith("_standard.dck"))
            return true;
        FileHandle handle = Config.instance().getFile(path);
        return handle == null || !handle.exists();
    }

    /**
     * Builds a deck legal for the current run format, themed by fighter colors.
     * Deterministic for the same colors + rematch + badges + run format.
     * Never throws when LDA data is missing — falls back to color generation.
     */
    public static Deck generateLegalDeck(String colors, boolean rematch) {
        return generateLegalDeck(colors, rematch, Current.player().getBadgeCount(), null);
    }

    /**
     * @param seedKey optional stable id (fighter name / gym id) mixed into the RNG seed
     */
    public static Deck generateLegalDeck(String colors, boolean rematch, int badgesHeld, String seedKey) {
        AdventurePlayer player = Current.player();
        String format = player.getRunFormat();
        String cols = normalizeColors(colors);
        long seed = deckSeed(cols, rematch, badgesHeld, format, seedKey);
        Random previous = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(seed));
            if (FORMAT_COMMANDER.equals(format) || player.isCommanderMode()) {
                Deck d = safeGenerate(() -> DeckgenUtil.generateCommanderDeck(true, GameType.Commander));
                return d != null ? d : emptyFallback("Commander");
            }
            if (FORMAT_PAUPER.equals(format)) {
                Deck d = generateColorDeck(cols, FModel.getFormats().getPauper().getFilterPrinted(), true);
                return d != null && !d.isEmpty() ? d : emptyFallback("Pauper");
            }
            if (FORMAT_HISTORIC.equals(format)) {
                Deck d = generateColorDeck(cols, FModel.getFormats().getHistoric().getFilterPrinted(), true);
                return d != null && !d.isEmpty() ? d : emptyFallback("Historic");
            }
            // Standard: window editions when available; color-matched precon / color gen otherwise.
            StandardWindow window = player.getStandardWindow();
            if (window.isActive() && !window.getSets().isEmpty()) {
                String[] editions = window.getSets().toArray(new String[0]);
                Deck deck = generateColorMatchedPreconOrTheme(cols, true, !rematch, editions);
                if (deck != null && !deck.isEmpty())
                    return deck;
                Predicate<PaperCard> windowFilter = pc -> pc != null && (
                        pc.getRules().getType().isBasicLand()
                                || window.isStandardLegal(pc.getName()));
                Deck colored = generateColorDeck(cols, windowFilter, true);
                if (colored != null && !colored.isEmpty())
                    return colored;
            }
            Deck std = generateColorMatchedPreconOrTheme(cols, true, !rematch, null);
            if (std != null && !std.isEmpty())
                return std;
            GameFormat standard = FModel.getFormats().getStandard();
            Deck lda = safeLda(standard);
            if (lda != null && !lda.isEmpty())
                return lda;
            Deck colored = generateColorDeck(cols, standard != null ? standard.getFilterPrinted() : null, true);
            return colored != null && !colored.isEmpty() ? colored : emptyFallback("Standard");
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    /** Maps gym color codes (W/U/B/R/G/C/Guild/Rainbow) to a WUBRG string for generators. */
    static String normalizeColors(String colors) {
        if (colors == null || colors.isEmpty() || "C".equals(colors))
            return "WUBRG";
        if ("Guild".equals(colors))
            return "WR";
        if ("Rainbow".equals(colors))
            return "WUBRG";
        return colors;
    }

    private static long deckSeed(String cols, boolean rematch, int badges, String format, String seedKey) {
        long h = 0xcbf29ce484222325L;
        String key = (seedKey != null ? seedKey : "") + "|" + cols + "|" + format + "|" + badges + "|" + rematch;
        for (int i = 0; i < key.length(); i++) {
            h ^= key.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * Color-matched precon/theme pick that does <em>not</em> use DeckgenUtil's broken
     * color switch. Filters proxies by {@link ColorSet} from the WUBRG string directly.
     */
    private static Deck generateColorMatchedPreconOrTheme(String colors, boolean forAi, boolean isTheme,
                                                         String[] allowedEditions) {
        try {
            List<DeckProxy> source = new ArrayList<>(DeckProxy.getAllPreconstructedDecks(QuestController.getPrecons()));
            if (isTheme)
                source.addAll(DeckProxy.getNonEasyQuestDuelDecks());
            ColorSet want = ColorSet.fromNames(colors.toCharArray());
            Predicate<DeckProxy> predicate = deckProxy -> deckProxy.getMainSize() <= 60;
            if (allowedEditions != null && allowedEditions.length > 0) {
                Set<String> editionSet = Set.of(allowedEditions);
                predicate = predicate.and(dp -> dp.getEdition() != null && editionSet.contains(dp.getEdition().getCode()));
            }
            if (want != null && !want.isColorless() && want.countColors() < 4) {
                final ColorSet filter = want;
                predicate = predicate.and(dp -> dp.getColorIdentity() != null
                        && dp.getColorIdentity().hasAllColors(filter.getColor()));
            }
            return source.stream().filter(predicate).collect(StreamUtil.random()).map(DeckProxy::getDeck).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Deck generateColorDeck(String colors, Predicate<PaperCard> formatFilter, boolean forAi) {
        List<String> selection = colorSelection(colors);
        if (selection.isEmpty())
            selection = List.of("white", "blue", "black", "red", "green");
        try {
            return DeckgenUtil.buildColorDeck(selection, formatFilter, forAi);
        } catch (Exception e) {
            return null;
        }
    }

    /** Converts a WUBRG string into DeckgenUtil color-name selection (no fall-through bugs). */
    private static List<String> colorSelection(String colors) {
        List<String> selection = new ArrayList<>();
        if (colors == null)
            return selection;
        for (char c : colors.toLowerCase().toCharArray()) {
            switch (c) {
                case 'w':
                    selection.add("white");
                    break;
                case 'u':
                    selection.add("blue");
                    break;
                case 'b':
                    selection.add("black");
                    break;
                case 'r':
                    selection.add("red");
                    break;
                case 'g':
                    selection.add("green");
                    break;
                default:
                    break;
            }
        }
        // Cap at 3 for mono/dual/tri generators; 4+ → 5-color path via empty/full list size.
        if (selection.size() > 3)
            return List.of("white", "blue", "black", "red", "green");
        return selection;
    }

    private static Deck safeLda(GameFormat format) {
        if (format == null)
            return null;
        try {
            if (forge.deck.CardArchetypeLDAGenerator.ldaArchetypes.get(format.getName()) == null)
                return null;
            return DeckgenUtil.buildLDACArchetypeDeck(format, true);
        } catch (Exception | Error e) {
            return null;
        }
    }

    private static Deck safeGenerate(java.util.concurrent.Callable<Deck> gen) {
        try {
            return gen.call();
        } catch (Exception | Error e) {
            return null;
        }
    }

    private static Deck emptyFallback(String label) {
        Deck d = new Deck("Gym fallback (" + label + ")");
        // Last-resort non-empty deck so a missing LDA/precon never NPE mid-challenge.
        try {
            Deck random = DeckgenUtil.getRandomColorDeck(true);
            if (random != null && !random.isEmpty())
                return random;
        } catch (Exception ignored) {
        }
        return d;
    }

    /**
     * Builds a transient EnemyData for a gym / League duel with a format-legal prepared deck.
     * @param league when true, bosses use {@link ConfigData#leagueGamesPerMatch}; gym leaders use
     *               {@link ConfigData#gymLeaderGamesPerMatch} when gamesPerMatch is unset (≤ 0).
     */
    public static EnemyData toEnemy(GymFighterData fighter, int badgesHeld, boolean rematch) {
        return toEnemy(fighter, badgesHeld, rematch, false);
    }

    public static EnemyData toEnemy(GymFighterData fighter, int badgesHeld, boolean rematch, boolean league) {
        EnemyData e = new EnemyData();
        e.name = fighter.name != null ? fighter.name : "Gym Fighter";
        e.sprite = fighter.sprite;
        e.life = Math.max(1, fighter.life);
        if (rematch)
            e.life = Math.max(e.life, e.life + 4 + badgesHeld);
        e.colors = fighter.colors != null ? fighter.colors : "";
        int games = fighter.gamesPerMatch;
        if (games <= 0) {
            ConfigData cfg = Config.instance().getConfigData();
            if (fighter.boss)
                games = league ? cfg.leagueGamesPerMatch : cfg.gymLeaderGamesPerMatch;
            else
                games = 1;
        }
        e.gamesPerMatch = Math.max(1, games);
        e.boss = fighter.boss || e.gamesPerMatch > 1;
        String path = resolveDeckPath(fighter, badgesHeld, rematch);
        if (shouldGenerateDeck(path)) {
            e.preparedDeck = generateLegalDeck(e.colors, rematch, badgesHeld, e.name);
            e.deck = new String[]{GENERATE};
        } else {
            e.deck = new String[]{path};
        }
        return e;
    }

    /**
     * True when the selected deck may challenge gyms / League for this run's format.
     */
    public static boolean selectedDeckLegalForRun() {
        AdventurePlayer p = Current.player();
        Deck d = p.getSelectedDeck();
        if (d == null)
            return false;
        String format = p.getRunFormat();
        if (FORMAT_COMMANDER.equals(format))
            return p.isCommanderDeck(d);
        if (FORMAT_HISTORIC.equals(format))
            return p.historicDeckProblem(d) == null;
        if (FORMAT_PAUPER.equals(format))
            return p.pauperDeckProblem(d) == null;
        // Standard: not Commander/Historic-tagged, and every card legal in the window / staples.
        if (p.isCommanderDeck(d) || p.isHistoricDeck(d))
            return false;
        return p.standardDeckProblem(d) == null;
    }

    public static String legalityMessage() {
        AdventurePlayer p = Current.player();
        Deck d = p.getSelectedDeck();
        String format = p.getRunFormat();
        if (FORMAT_STANDARD.equals(format)) {
            String problem = p.standardDeckProblem(d);
            if (problem != null)
                return problem;
            if (p.isCommanderDeckSelected() || p.isHistoricDeckSelected())
                return "Select a Standard deck for this run (not Commander or Historic).";
        } else if (FORMAT_HISTORIC.equals(format)) {
            String problem = p.historicDeckProblem(d);
            if (problem != null)
                return problem;
        } else if (FORMAT_PAUPER.equals(format)) {
            String problem = p.pauperDeckProblem(d);
            if (problem != null)
                return problem;
        }
        return "Your selected deck must be legal for this run's format (" + format + ").";
    }

    /**
     * Applies dust immediately. Returns gold / material / staple for RewardScene EventReward.
     * Staple rewards prefer a rotating Standard staple (or window-legal named card).
     */
    public static Array<Reward> grantRewards(GymRewardData reward) {
        Array<Reward> out = new Array<>();
        if (reward == null)
            return out;
        AdventurePlayer player = Current.player();
        if (reward.dustCommon > 0)
            player.addDust(CardRarity.Common, reward.dustCommon);
        if (reward.dustUncommon > 0)
            player.addDust(CardRarity.Uncommon, reward.dustUncommon);
        if (reward.dustRare > 0)
            player.addDust(CardRarity.Rare, reward.dustRare);
        if (reward.dustMythic > 0)
            player.addDust(CardRarity.MythicRare, reward.dustMythic);
        if (reward.gold > 0) {
            RewardData gold = new RewardData();
            gold.type = "gold";
            gold.probability = 1f;
            gold.count = reward.gold;
            out.addAll(gold.generate(false, null, true));
        }
        if (reward.material != null && !reward.material.isEmpty()) {
            RewardData mat = new RewardData();
            mat.type = "material";
            mat.probability = 1f;
            mat.count = Math.max(1, reward.materialCount);
            mat.materialName = reward.material;
            out.addAll(mat.generate(false, null, true));
        }
        PaperCard staple = pickStapleReward(reward.stapleCard);
        if (staple != null)
            out.add(new Reward(staple));
        return out;
    }

    /**
     * Prefer {@code preferred} when it is Standard-legal for this run; otherwise a random
     * rotating staple (or unlocked color staple). Commander runs use commander staples.
     */
    public static PaperCard pickStapleReward(String preferred) {
        AdventurePlayer player = Current.player();
        StandardWindow window = player.getStandardWindow();
        boolean commander = FORMAT_COMMANDER.equals(player.getRunFormat()) || player.isCommanderMode();
        if (preferred != null && !preferred.isEmpty()) {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(preferred);
            if (pc == null)
                pc = CardUtil.getCardByName(preferred);
            if (pc != null && (commander || !window.isActive() || window.isStandardLegal(pc.getName())))
                return pc;
        }
        List<String> pool = new ArrayList<>();
        if (window.isActive()) {
            Set<String> active = window.activeStaples(commander);
            if (active != null)
                pool.addAll(active);
            pool.addAll(StandardWindow.unlockedColorStaples());
        }
        while (!pool.isEmpty()) {
            String name = Aggregates.removeRandom(pool);
            if (name == null || name.isEmpty())
                continue;
            if (!commander && window.isActive() && !window.isStandardLegal(name))
                continue;
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(name);
            if (pc == null)
                pc = CardUtil.getCardByName(name);
            if (pc != null)
                return pc;
        }
        return null;
    }
}
