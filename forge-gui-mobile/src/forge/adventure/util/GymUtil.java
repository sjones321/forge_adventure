package forge.adventure.util;

import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.GymFighterData;
import forge.adventure.data.GymRewardData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.card.CardRarity;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.model.FModel;

/**
 * Shared helpers for gyms and the League: format deck resolution, EnemyData build,
 * reward grant, and run-format legality checks (package K will expand formats).
 */
public final class GymUtil {
    public static final String FORMAT_STANDARD = "Standard";
    public static final String FORMAT_PAUPER = "Pauper";
    public static final String FORMAT_HISTORIC = "Historic";
    public static final String FORMAT_COMMANDER = "Commander";

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

    /** Builds a transient EnemyData for a gym / League duel. */
    public static EnemyData toEnemy(GymFighterData fighter, int badgesHeld, boolean rematch) {
        EnemyData e = new EnemyData();
        e.name = fighter.name != null ? fighter.name : "Gym Fighter";
        e.sprite = fighter.sprite;
        e.life = Math.max(1, fighter.life);
        e.colors = fighter.colors != null ? fighter.colors : "";
        int games = fighter.gamesPerMatch;
        if (games <= 0) {
            ConfigData cfg = Config.instance().getConfigData();
            games = fighter.boss ? cfg.leagueGamesPerMatch : 1;
        }
        e.gamesPerMatch = Math.max(1, games);
        e.boss = fighter.boss || e.gamesPerMatch > 1;
        String path = resolveDeckPath(fighter, badgesHeld, rematch);
        if (path != null && !path.isEmpty())
            e.deck = new String[]{path};
        else
            e.deck = new String[]{"decks/standard/adventurer.dck"};
        return e;
    }

    /**
     * True when the selected deck may challenge gyms / League for this run's format.
     * Until package K, every run is Standard: Commander and Historic-tagged decks are refused.
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
            return p.isHistoricDeck(d) || (!p.isCommanderDeck(d) && !p.isHistoricDeck(d));
        if (FORMAT_PAUPER.equals(format))
            return !p.isCommanderDeck(d); // package K will tighten to Pauper legality
        // Standard run: selected deck must be the Adventure Standard format (not Commander / Historic).
        return !p.isCommanderDeck(d) && !p.isHistoricDeck(d);
    }

    public static String legalityMessage() {
        String format = Current.player().getRunFormat();
        return "Your selected deck must be legal for this run's format (" + format + ").";
    }

    /**
     * Applies dust immediately (no Reward.Type.Dust). Returns gold / material / staple
     * for {@link forge.adventure.scene.RewardScene} EventReward, which calls addReward on claim.
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
        if (reward.stapleCard != null && !reward.stapleCard.isEmpty()) {
            PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(reward.stapleCard);
            if (pc == null)
                pc = CardUtil.getCardByName(reward.stapleCard);
            if (pc != null)
                out.add(new Reward(pc));
            else
                System.err.println("Gym staple missing: " + reward.stapleCard);
        }
        return out;
    }
}
