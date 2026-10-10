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
 * MV2 runtime rules for set planes: active set code (host + co-op guest),
 * enemy {@code $generate} decks restricted to that set, shop/reward filters,
 * and portal reachability / gold costs.
 */
public final class SetPlaneRules {
    public static final String GENERATE = GymUtil.GENERATE;
    /** Minimum non-land cards before a set pool is considered usable. */
    public static final int MIN_SET_POOL_SIZE = 12;

    private SetPlaneRules() {
    }

    /**
     * Set code for the live overworld plane (guest-aware via {@link Current#planeId()}).
     * Empty on home, non-Ascendant, CORE / non-edition codes, or debug planes.
     */
    public static String activeSetCode() {
        try {
            if (!Config.ascendant()) {
                return "";
            }
            // Guest follows the host's plane id (Current.planeId → CoopSession).
            String planeId = Current.planeId();
            if (planeId == null || planeId.isEmpty() || PlaneMeta.HOME_ID.equalsIgnoreCase(planeId)) {
                return "";
            }
            String code = "";
            try {
                WorldSave save = WorldSave.getCurrentSave();
                if (save != null) {
                    PlaneMeta meta = save.getMultiverse().getMeta(planeId);
                    if (meta != null && meta.getSetCode() != null && !meta.getSetCode().isEmpty()) {
                        code = meta.getSetCode();
                    }
                }
            } catch (Throwable ignored) {
                // fall through to plane-id parse
            }
            if (code.isEmpty()) {
                code = SetPlaneGenerator.setCodeFromPlaneId(planeId);
            }
            return restrictableSetCode(code);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Returns {@code setCode} only when it is a real CardEdition that shops/decks
     * can draw from. CORE, empty, and unknown/debug codes → empty (no restriction).
     */
    public static String restrictableSetCode(String setCode) {
        if (setCode == null || setCode.isEmpty()) {
            return "";
        }
        if (StandardWindow.CORE_COLLECTION.equalsIgnoreCase(setCode)) {
            return "";
        }
        if (!isKnownEdition(setCode)) {
            return "";
        }
        return setCode;
    }

    public static boolean isKnownEdition(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        try {
            return FModel.getMagicDb() != null
                    && FModel.getMagicDb().getEditions().get(code) != null;
        } catch (Throwable t) {
            return false;
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

    /** Portal gold cost for {@code planeId} (0 when free / home). -1 when locked. */
    public static int portalGoldCost(String planeId, AdventurePlayer player) {
        StandardWindow window = player != null ? player.getStandardWindow() : null;
        return portalGoldCost(planeId, window);
    }

    /** Portal gold cost using an explicit Standard window (host or guest). */
    public static int portalGoldCost(String planeId, StandardWindow window) {
        if (planeId == null || planeId.isEmpty() || PlaneMeta.HOME_ID.equalsIgnoreCase(planeId)) {
            return 0;
        }
        PlaneAlignment align = alignmentForPlane(planeId, window);
        if (!align.isReachable()) {
            return -1;
        }
        ConfigData cfg;
        try {
            cfg = Config.instance().getConfigData();
        } catch (Throwable ignored) {
            cfg = new ConfigData();
        }
        return align.portalGoldCost(cfg);
    }

    /**
     * Preflight for travel. When {@code charge} is true, deducts gold.
     * Returns null when allowed, or an error message. Does not switch planes.
     */
    public static String checkTravel(String planeId, AdventurePlayer player, boolean charge) {
        try {
            if (!Config.ascendant()) {
                return "Multi-plane requires Shandalar Ascendant";
            }
        } catch (Throwable t) {
            // Headless tests: still evaluate alignment.
        }
        if (planeId == null || planeId.isEmpty()) {
            return "Missing plane id";
        }
        if (PlaneMeta.HOME_ID.equalsIgnoreCase(planeId)) {
            return null;
        }
        int cost = portalGoldCost(planeId, player);
        if (cost < 0) {
            return "That plane is out of alignment — master sets to unlock new planes.";
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

    /**
     * Charge portal gold for {@code planeId}. Returns the amount charged (0 if free),
     * or -1 if payment failed (insufficient gold / locked). Caller must not persist
     * a plane switch when this returns -1.
     */
    public static int chargePortalGold(String planeId, AdventurePlayer player) {
        if (player == null) {
            int cost = portalGoldCost(planeId, (StandardWindow) null);
            return cost == 0 ? 0 : -1;
        }
        return chargePortalGold(planeId, player.getStandardWindow(), player.getGold(), player::takeGold);
    }

    /**
     * Charge portal gold against an explicit wallet. Returns charged amount or -1.
     * Used by portals and headless tests — never persists a plane switch on failure.
     */
    public static int chargePortalGold(String planeId, StandardWindow window, int gold,
                                       java.util.function.IntConsumer takeGold) {
        int cost = portalGoldCost(planeId, window);
        if (cost < 0) {
            return -1;
        }
        if (cost == 0) {
            return 0;
        }
        if (gold < cost || takeGold == null) {
            return -1;
        }
        takeGold.accept(cost);
        return cost;
    }

    public static void refundPortalGold(AdventurePlayer player, int charged) {
        if (player != null && charged > 0) {
            player.giveGold(charged);
        }
    }

    public static void refundPortalGold(java.util.function.IntConsumer giveGold, int charged) {
        if (giveGold != null && charged > 0) {
            giveGold.accept(charged);
        }
    }

    /**
     * User-facing message when {@link #chargePortalGold} fails. Never null/empty —
     * callers can pass the result straight to HUD / console.
     */
    public static String paymentFailureMessage(String planeId, AdventurePlayer player) {
        String err = checkTravel(planeId, player, false);
        if (err != null && !err.isEmpty()) {
            return err;
        }
        return "Cannot planeswalk — payment failed.";
    }

    /**
     * Build a {@code $generate} enemy deck restricted to {@code setCode}.
     * Uses a local {@link Random} and temporarily swaps {@link MyRandom} only —
     * never reseeds the shared world RNG.
     */
    public static Deck generateEnemyDeck(EnemyData enemy, String setCode) {
        if (enemy == null) {
            return new Deck("Empty");
        }
        String requested = setCode != null ? setCode : activeSetCode();
        String code = restrictableSetCode(requested);
        String deckName = (enemy.getName() != null ? enemy.getName() : "Enemy")
                + (code.isEmpty() ? "" : " (" + code + ")");
        CardEdition edition = null;
        try {
            if (!code.isEmpty() && FModel.getMagicDb() != null) {
                edition = FModel.getMagicDb().getEditions().get(code);
            }
        } catch (Throwable ignored) {
            // headless
        }

        long seed = 0L;
        try {
            if (Current.world() != null) {
                seed = Current.world().getSeed();
            }
        } catch (Throwable ignored) {
            seed = 0L;
        }
        seed ^= (enemy.getName() != null ? enemy.getName().hashCode() : 0)
                ^ (code != null ? code.hashCode() : 0);

        Random previous = MyRandom.getRandom();
        try {
            // Local RNG via MyRandom only — do not touch World.getRandom().
            MyRandom.setRandom(new Random(seed));

            GeneratedDeckData data = new GeneratedDeckData();
            data.name = deckName;
            data.template = new GeneratedDeckTemplateData();
            data.template.count = 40;
            data.template.rares = Math.min(0.35f, 0.08f + enemy.difficulty * 0.02f);
            data.template.colors = GymUtil.resolveTemplateColors(
                    enemy.colors != null ? enemy.colors : "", seed);

            Deck deck;
            try {
                deck = CardUtil.generateDeck(data, edition, true);
            } catch (Throwable e) {
                deck = new Deck(data.name);
            }
            if (deck == null) {
                deck = new Deck(data.name);
            }
            if (edition != null) {
                sanitizeToSet(deck, code);
            }
            // Tiny / empty set → fall back to unrestricted generate so decks stay playable.
            // Count excludes basic lands (basics alone must not satisfy the pool floor).
            if (countNonBasicCards(deck) < MIN_SET_POOL_SIZE && edition != null) {
                try {
                    deck = CardUtil.generateDeck(data, null, true);
                } catch (Throwable e) {
                    // keep sanitized / empty
                }
            }
            return deck != null ? deck : new Deck(deckName);
        } catch (Throwable t) {
            return new Deck(deckName);
        } finally {
            MyRandom.setRandom(previous);
        }
    }

    /**
     * True only when the enemy uses an explicit {@code $generate} deck marker
     * on a set plane. Bosses and hand-built {@code .dck} lists are left alone.
     */
    public static boolean shouldGenerateSetDeck(EnemyData enemy) {
        try {
            if (!Config.ascendant() || !isOnSetPlane()) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
        if (enemy == null || enemy.boss) {
            return false;
        }
        if (enemy.deck == null || enemy.deck.length == 0) {
            return false;
        }
        for (String path : enemy.deck) {
            if (path != null && (GENERATE.equals(path) || path.contains("$generate"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Apply set-plane edition restriction when the active set has a usable pool.
     * Returns {@code filter} unchanged when not on a restrictable set plane.
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

    /** True when the set has enough non-land cards for shops / rewards. */
    public static boolean setPoolIsUsable(Iterable<PaperCard> pool, String setCode) {
        if (pool == null || setCode == null || setCode.isEmpty()) {
            return false;
        }
        int n = 0;
        for (PaperCard pc : pool) {
            if (pc == null) {
                continue;
            }
            if (!setCode.equalsIgnoreCase(pc.getEdition())) {
                continue;
            }
            if (isBasicLand(pc)) {
                continue;
            }
            n++;
            if (n >= MIN_SET_POOL_SIZE) {
                return true;
            }
        }
        return false;
    }

    /** Non-basic card count in a deck's mainboard (basics do not count toward the small-set floor). */
    public static int countNonBasicCards(Deck deck) {
        if (deck == null || deck.getMain() == null) {
            return 0;
        }
        int n = 0;
        for (PaperCard pc : deck.getMain().toFlatList()) {
            if (pc == null || isBasicLand(pc)) {
                continue;
            }
            n++;
        }
        return n;
    }

    public static boolean isBasicLand(PaperCard pc) {
        try {
            return pc != null && pc.getRules() != null && pc.getRules().getType().isBasicLand();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Filter a card pool to printings of {@code setCode}. */
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
