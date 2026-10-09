package forge.adventure.world;

import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.GeneratedDeckData;
import forge.adventure.data.GeneratedDeckTemplateData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.adventure.util.CardUtil;
import forge.adventure.util.Config;
import forge.adventure.util.Current;
import forge.adventure.util.GymUtil;
import forge.card.CardEdition;
import forge.deck.Deck;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * MV2 runtime rules for set planes: active set code, enemy {@code $generate} decks
 * restricted to that set, shop/reward edition filters, and portal reachability.
 */
public final class SetPlaneRules {
    public static final String GENERATE = GymUtil.GENERATE;

    private SetPlaneRules() {
    }

    /** Set code for the live plane, or empty when on home / non-Ascendant / unknown. */
    public static String activeSetCode() {
        if (!Config.ascendant()) {
            return "";
        }
        try {
            WorldSave save = WorldSave.getCurrentSave();
            if (save == null) {
                return "";
            }
            PlaneMeta meta = save.getMultiverse().getCurrentMeta();
            if (meta == null || meta.getKind() != PlaneKind.SET) {
                return "";
            }
            String code = meta.getSetCode();
            if (code != null && !code.isEmpty()) {
                return code;
            }
            return SetPlaneGenerator.setCodeFromPlaneId(meta.getId());
        } catch (Throwable t) {
            return "";
        }
    }

    public static boolean isOnSetPlane() {
        String code = activeSetCode();
        return code != null && !code.isEmpty();
    }

    public static PlaneAlignment alignmentForPlane(String planeId, StandardWindow window) {
        if (planeId == null || planeId.isEmpty() || PlaneMeta.HOME_ID.equalsIgnoreCase(planeId)) {
            return PlaneAlignment.HOME;
        }
        try {
            WorldSave save = WorldSave.getCurrentSave();
            if (save != null) {
                PlaneMeta meta = save.getMultiverse().getMeta(planeId);
                if (meta != null) {
                    return PlaneAlignment.ofPlane(meta, window);
                }
            }
        } catch (Throwable ignored) {
            // fall through
        }
        String code = SetPlaneGenerator.setCodeFromPlaneId(planeId);
        return PlaneAlignment.of(code, window);
    }

    /**
     * Preflight + gold cost for traveling to {@code planeId}. Returns null when allowed
     * (cost already applied if {@code charge} is true), or an error message.
     */
    public static String checkTravel(String planeId, AdventurePlayer player, boolean charge) {
        if (!Config.ascendant()) {
            return "Multi-plane requires Ascendant";
        }
        if (planeId == null || planeId.isEmpty()) {
            return "Missing plane id";
        }
        if (PlaneMeta.HOME_ID.equalsIgnoreCase(planeId)) {
            return null;
        }
        StandardWindow window = player != null ? player.getStandardWindow() : null;
        PlaneAlignment align = alignmentForPlane(planeId, window);
        if (!align.isReachable()) {
            return "That plane is out of alignment — master sets to unlock new planes.";
        }
        ConfigData cfg = Config.instance().getConfigData();
        int cost = align.portalGoldCost(cfg);
        if (cost < 0) {
            return "That plane is out of alignment.";
        }
        if (cost > 0) {
            if (player == null || player.getGold() < cost) {
                return "Need " + cost + " gold to reach a drifted plane (have "
                        + (player != null ? player.getGold() : 0) + ").";
            }
            if (charge) {
                player.takeGold(cost);
            }
        }
        return null;
    }

    /** Build a {@code $generate} enemy deck restricted to {@code setCode}. */
    public static Deck generateEnemyDeck(EnemyData enemy, String setCode) {
        if (enemy == null) {
            return new Deck("Empty");
        }
        String code = setCode != null ? setCode : activeSetCode();
        CardEdition edition = null;
        try {
            if (code != null && !code.isEmpty()) {
                edition = FModel.getMagicDb().getEditions().get(code);
            }
        } catch (Throwable ignored) {
            // headless
        }

        long seed = (Current.world() != null ? Current.world().getSeed() : 0L)
                ^ (enemy.getName() != null ? enemy.getName().hashCode() : 0)
                ^ (code != null ? code.hashCode() : 0);
        Random previous = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(seed));
            if (WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getWorld() != null) {
                WorldSave.getCurrentSave().getWorld().getRandom().setSeed(seed);
            }

            GeneratedDeckData data = new GeneratedDeckData();
            data.name = (enemy.getName() != null ? enemy.getName() : "Enemy") + " (" + code + ")";
            data.template = new GeneratedDeckTemplateData();
            data.template.count = 40;
            data.template.rares = Math.min(0.35f, 0.08f + enemy.difficulty * 0.02f);
            data.template.colors = GymUtil.resolveTemplateColors(
                    enemy.colors != null ? enemy.colors : "", seed);

            Deck deck;
            try {
                deck = CardUtil.generateDeck(data, edition, true);
            } catch (StackOverflowError | Exception e) {
                deck = new Deck(data.name);
            }
            if (deck == null) {
                deck = new Deck(data.name);
            }
            // Drop any non-set cards that slipped through (basics may be from other sets — OK).
            if (edition != null) {
                sanitizeToSet(deck, code);
            }
            return deck;
        } finally {
            MyRandom.setRandom(previous);
            if (WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getWorld() != null) {
                WorldSave.getCurrentSave().getWorld().getRandom().setSeed(System.nanoTime());
            }
        }
    }

    /** True when this enemy should use set-restricted $generate on the current plane. */
    public static boolean shouldGenerateSetDeck(EnemyData enemy) {
        if (!Config.ascendant() || !isOnSetPlane()) {
            return false;
        }
        if (enemy == null) {
            return false;
        }
        // Honour explicit $generate markers; also override stock .dck lists on set planes
        // so every overworld fight draws from the plane's set.
        if (enemy.deck == null || enemy.deck.length == 0) {
            return true;
        }
        for (String path : enemy.deck) {
            if (path != null && (GENERATE.equals(path) || path.contains("$generate"))) {
                return true;
            }
        }
        return true;
    }

    /**
     * Apply set-plane edition restriction to a reward filter copy.
     * Returns {@code filter} unchanged when not on a set plane.
     */
    public static RewardData applySetEditionFilter(RewardData filter) {
        if (filter == null || !isOnSetPlane()) {
            return filter;
        }
        String code = activeSetCode();
        if (code == null || code.isEmpty()) {
            return filter;
        }
        RewardData copy = new RewardData(filter);
        copy.editions = new String[]{code};
        return copy;
    }

    /** Filter a card pool to printings of the active set (or {@code setCode}). */
    public static List<PaperCard> cardsFromSet(Iterable<PaperCard> pool, String setCode) {
        List<PaperCard> out = new ArrayList<>();
        if (pool == null || setCode == null || setCode.isEmpty()) {
            return out;
        }
        for (PaperCard pc : pool) {
            if (pc == null) {
                continue;
            }
            if (setCode.equalsIgnoreCase(pc.getEdition())) {
                out.add(pc);
                continue;
            }
            // Accept any printing whose name exists in the set (basics / reprints).
            if (pc.getRules() != null && pc.getRules().getType().isBasicLand()) {
                out.add(pc);
            }
        }
        return out;
    }

    /** Verify every non-land card in the deck is from {@code setCode}. */
    public static boolean deckRestrictedToSet(Deck deck, String setCode) {
        if (deck == null || setCode == null || setCode.isEmpty()) {
            return false;
        }
        for (PaperCard pc : deck.getAllCardsInASinglePool(true, true).toFlatList()) {
            if (pc == null) {
                continue;
            }
            if (pc.getRules() != null && pc.getRules().getType().isBasicLand()) {
                continue;
            }
            if (!setCode.equalsIgnoreCase(pc.getEdition())) {
                return false;
            }
        }
        return true;
    }

    private static void sanitizeToSet(Deck deck, String setCode) {
        if (deck == null || setCode == null) {
            return;
        }
        var main = deck.getMain();
        List<PaperCard> keep = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null) {
                continue;
            }
            if (pc.getRules() != null && pc.getRules().getType().isBasicLand()) {
                keep.add(pc);
            } else if (setCode.equalsIgnoreCase(pc.getEdition())) {
                keep.add(pc);
            }
        }
        main.clear();
        for (PaperCard pc : keep) {
            main.add(pc);
        }
    }
}
