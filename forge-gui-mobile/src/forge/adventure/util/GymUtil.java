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
import forge.deck.Deck;
import forge.deck.DeckgenUtil;
import forge.game.GameFormat;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.Aggregates;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared helpers for gyms and the League: format deck resolution, EnemyData build,
 * reward grant, and run-format legality checks (package K will expand formats).
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
     * Standard uses the player's window editions (and rotating staples via format generators).
     */
    public static Deck generateLegalDeck(String colors, boolean rematch) {
        AdventurePlayer player = Current.player();
        String format = player.getRunFormat();
        String cols = (colors == null || colors.isEmpty() || "Guild".equals(colors) || "Rainbow".equals(colors)
                || "C".equals(colors))
                ? (colors != null && colors.length() >= 2 && !"Guild".equals(colors) && !"Rainbow".equals(colors)
                ? colors : "WUBRG")
                : colors;
        if ("C".equals(colors))
            cols = "";
        if ("Guild".equals(colors))
            cols = "WR";
        if ("Rainbow".equals(colors))
            cols = "WUBRG";

        if (FORMAT_COMMANDER.equals(format) || player.isCommanderMode()) {
            return DeckgenUtil.generateCommanderDeck(true, GameType.Commander);
        }
        if (FORMAT_PAUPER.equals(format)) {
            return DeckgenUtil.buildLDACArchetypeDeck(FModel.getFormats().getPauper(), true);
        }
        if (FORMAT_HISTORIC.equals(format)) {
            return DeckgenUtil.buildLDACArchetypeDeck(FModel.getFormats().getHistoric(), true);
        }
        // Standard: restrict to the run's window editions when available.
        StandardWindow window = player.getStandardWindow();
        if (window.isActive() && !window.getSets().isEmpty()) {
            String[] editions = window.getSets().toArray(new String[0]);
            Deck deck = DeckgenUtil.getRandomOrPreconOrThemeDeck(cols, true, !rematch, false, editions);
            if (deck != null && !deck.isEmpty())
                return deck;
        }
        GameFormat std = FModel.getFormats().getStandard();
        return DeckgenUtil.buildLDACArchetypeDeck(std, true);
    }

    /** Builds a transient EnemyData for a gym / League duel with a format-legal prepared deck. */
    public static EnemyData toEnemy(GymFighterData fighter, int badgesHeld, boolean rematch) {
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
            games = fighter.boss ? cfg.leagueGamesPerMatch : 1;
        }
        e.gamesPerMatch = Math.max(1, games);
        e.boss = fighter.boss || e.gamesPerMatch > 1;
        String path = resolveDeckPath(fighter, badgesHeld, rematch);
        if (shouldGenerateDeck(path)) {
            e.preparedDeck = generateLegalDeck(e.colors, rematch);
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
            return p.isHistoricDeck(d);
        if (FORMAT_PAUPER.equals(format))
            return !p.isCommanderDeck(d); // package K will tighten to Pauper legality
        // Standard: not Commander/Historic-tagged, and every card legal in the window / staples.
        if (p.isCommanderDeck(d) || p.isHistoricDeck(d))
            return false;
        return p.standardDeckProblem(d) == null;
    }

    public static String legalityMessage() {
        AdventurePlayer p = Current.player();
        String format = p.getRunFormat();
        if (FORMAT_STANDARD.equals(format)) {
            String problem = p.standardDeckProblem(p.getSelectedDeck());
            if (problem != null)
                return problem;
            if (p.isCommanderDeckSelected() || p.isHistoricDeckSelected())
                return "Select a Standard deck for this run (not Commander or Historic).";
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
