package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ObjectMap;
import forge.adventure.data.ConfigData;
import forge.adventure.data.EnemyData;
import forge.adventure.data.GeneratedDeckData;
import forge.adventure.data.GeneratedDeckTemplateData;
import forge.adventure.data.GymFighterData;
import forge.adventure.data.GymRewardData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.BanLists;
import forge.adventure.player.StandardWindow;
import forge.adventure.world.WorldSave;
import forge.card.CardRarity;
import forge.card.MagicColor;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.Aggregates;
import forge.util.MyRandom;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Shared helpers for gyms and the League: format deck resolution, EnemyData build,
 * reward grant, and run-format legality checks (package K will expand formats).
 *
 * Gym decks are built with Adventure's {@link CardUtil#generateDeck} ($generate path),
 * never DeckgenUtil/precons. Seeded per gym + badge count; padded with basics / colorless
 * staples when the legal pool is thin. Catches {@link StackOverflowError} from builders.
 */
public final class GymUtil {
    public static final String FORMAT_STANDARD = "Standard";
    public static final String FORMAT_PAUPER = "Pauper";
    public static final String FORMAT_HISTORIC = "Historic";
    public static final String FORMAT_COMMANDER = "Commander";
    /** Sentinel in gyms.json deck maps: generate a format-legal deck at challenge time. */
    public static final String GENERATE = "$generate";

    private static final String[][] GUILD_PAIRS = {
            {"white", "blue"}, {"white", "black"}, {"white", "red"}, {"white", "green"},
            {"blue", "black"}, {"blue", "red"}, {"blue", "green"},
            {"black", "red"}, {"black", "green"}, {"red", "green"}
    };
    private static final String[] COLOR_NAMES = {"white", "blue", "black", "red", "green"};
    private static final String[] COLORLESS_STAPLES = {
            "Solemn Simulacrum", "Burnished Hart", "Hedron Archive", "Mind Stone",
            "Coldsteel Heart", "Guardian Idol", "Sky Diamond", "Charcoal Diamond",
            "Fire Diamond", "Marble Diamond", "Moss Diamond"
    };

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

    public static Deck generateLegalDeck(String colors, boolean rematch) {
        return generateLegalDeck(colors, rematch, Current.player().getBadgeCount(), null);
    }

    /**
     * Builds a run-legal gym/League deck via {@link CardUtil#generateDeck}.
     * Deterministic for the same colors + rematch + badges + run format + seedKey.
     */
    public static Deck generateLegalDeck(String colors, boolean rematch, int badgesHeld, String seedKey) {
        AdventurePlayer player = Current.player();
        String format = player.getRunFormat();
        long seed = deckSeed(colors, rematch, badgesHeld, format, seedKey);
        Random previous = MyRandom.getRandom();
        try {
            MyRandom.setRandom(new Random(seed));
            if (WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getWorld() != null) {
                // Pin Adventure's reward RNG (used inside CardUtil.generateDeck) to the same seed.
                WorldSave.getCurrentSave().getWorld().getRandom().setSeed(seed);
            }

            int target = targetDeckSize(format, player);
            String[] templateColors = resolveTemplateColors(colors, seed);
            boolean colorlessOnly = isColorlessTheme(colors);

            GeneratedDeckData data = new GeneratedDeckData();
            data.name = "Gym " + (seedKey != null ? seedKey : colors) + " (" + format + ")";
            if (colorlessOnly) {
                data.mainDeck = colorlessRewards(target, badgesHeld, rematch, format);
            } else {
                data.template = new GeneratedDeckTemplateData();
                data.template.count = target;
                data.template.rares = Math.min(0.35f, 0.1f + badgesHeld * 0.03f + (rematch ? 0.05f : 0f));
                data.template.colors = templateColors;
            }

            Deck deck;
            try {
                deck = CardUtil.generateDeck(data, null, true);
            } catch (StackOverflowError | Exception e) {
                deck = new Deck(data.name);
            }
            if (deck == null)
                deck = new Deck(data.name);

            deck = sanitizeAndPad(deck, target, templateColors, colorlessOnly, format, player);
            return deck;
        } finally {
            MyRandom.setRandom(previous);
            // Un-pin the world RNG so rewards and drops after a gym fight aren't predictable.
            if (WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getWorld() != null)
                WorldSave.getCurrentSave().getWorld().getRandom().setSeed(System.nanoTime());
        }
    }

    private static int targetDeckSize(String format, AdventurePlayer player) {
        if (FORMAT_COMMANDER.equals(format) || player.isCommanderMode())
            return 100;
        // Adventure constructed is typically 40; Ascendant Standard window runs use 60.
        if (FORMAT_STANDARD.equals(format) && player.getStandardWindow().isActive())
            return 60;
        if (FORMAT_HISTORIC.equals(format) || FORMAT_PAUPER.equals(format))
            return 60;
        return 40;
    }

    private static boolean isColorlessTheme(String colors) {
        return colors != null && ("C".equals(colors) || "colorless".equalsIgnoreCase(colors));
    }

    /**
     * Mono → that color name; Guild → seeded two-color pair; Rainbow → 3–5 colors;
     * Colorless → empty (handled separately); WUBRG codes map to names.
     */
    public static String[] resolveTemplateColors(String colors, long seed) {
        if (colors == null || colors.isEmpty() || isColorlessTheme(colors))
            return new String[0];
        if ("Guild".equalsIgnoreCase(colors)) {
            // One pair per world, shared by every fighter in the Guild gym and stable as badges change.
            long worldSeed = WorldSave.getCurrentSave() != null && WorldSave.getCurrentSave().getWorld() != null
                    ? WorldSave.getCurrentSave().getWorld().getSeed() : 0L;
            String[] pair = GUILD_PAIRS[Math.floorMod(Long.hashCode(worldSeed), GUILD_PAIRS.length)];
            return pair.clone();
        }
        if ("Rainbow".equalsIgnoreCase(colors)) {
            int n = 3 + Math.floorMod((int) (seed >> 3), 3); // 3..5
            List<String> pick = new ArrayList<>(List.of(COLOR_NAMES));
            Random r = new Random(seed);
            List<String> out = new ArrayList<>();
            while (out.size() < n && !pick.isEmpty())
                out.add(pick.remove(r.nextInt(pick.size())));
            return out.toArray(new String[0]);
        }
        // Already a color-name list? Unlikely from gyms.json — parse WUBRG / WR / etc.
        if (colors.contains("white") || colors.contains("blue"))
            return colors.split("[, ]+");
        List<String> names = new ArrayList<>();
        for (char c : colors.toUpperCase().toCharArray()) {
            switch (c) {
                case 'W': names.add("white"); break;
                case 'U': names.add("blue"); break;
                case 'B': names.add("black"); break;
                case 'R': names.add("red"); break;
                case 'G': names.add("green"); break;
                default: break;
            }
        }
        // Guild-length WUBRG string with exactly 2 letters already covered; Rainbow WUBRG → 5.
        if (names.size() >= 3)
            return names.toArray(new String[0]);
        if (names.size() == 2)
            return names.toArray(new String[0]);
        if (names.size() == 1)
            return names.toArray(new String[0]);
        // Fallback: seeded guild pair rather than an unfiltered 5-color random deck.
        return GUILD_PAIRS[Math.floorMod((int) seed, GUILD_PAIRS.length)].clone();
    }

    private static RewardData[] colorlessRewards(int target, int badges, boolean rematch, String format) {
        int spells = Math.max(1, Math.round(target * 0.6f));
        RewardData colorless = new RewardData();
        colorless.type = "card";
        colorless.count = spells / 2;
        colorless.colorType = "Colorless";
        colorless.probability = 1f;
        if (FORMAT_PAUPER.equals(format))
            colorless.rarity = new String[]{"Common"};
        else
            colorless.rarity = rematch || badges >= 3
                    ? new String[]{"Common", "Uncommon", "Rare", "Mythic Rare"}
                    : new String[]{"Common", "Uncommon", "Rare"};

        RewardData artifacts = new RewardData();
        artifacts.type = "card";
        artifacts.count = spells - colorless.count;
        artifacts.cardTypes = new String[]{"Artifact"};
        artifacts.probability = 1f;
        artifacts.rarity = colorless.rarity;

        RewardData wastes = new RewardData();
        wastes.type = "card";
        wastes.cardName = "Wastes";
        wastes.count = target - spells;
        wastes.probability = 1f;
        return new RewardData[]{colorless, artifacts, wastes};
    }

    private static Deck sanitizeAndPad(Deck deck, int target, String[] templateColors,
                                       boolean colorlessOnly, String format, AdventurePlayer player) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        List<PaperCard> keep = new ArrayList<>();
        for (PaperCard pc : main.toFlatList()) {
            if (pc == null)
                continue;
            if (!cardLegalForRun(pc, format, player))
                continue;
            if (colorlessOnly) {
                if (!pc.getRules().getColorIdentity().isColorless())
                    continue;
            } else if (templateColors != null && templateColors.length > 0
                    && templateColors.length < 5
                    && !pc.getRules().getType().isBasicLand()) {
                byte allowed = 0;
                for (String c : templateColors)
                    allowed |= MagicColor.fromName(c);
                // Color identity, so off-color lands and colored activation costs are filtered too.
                if (!pc.getRules().getColorIdentity().hasNoColorsExcept(allowed))
                    continue;
            }
            if (FORMAT_HISTORIC.equals(format) && BanLists.isBanned("historic", pc.getName()))
                continue;
            if (FORMAT_PAUPER.equals(format) && !pc.getRules().getType().isBasicLand()
                    && pc.getRarity() != CardRarity.Common
                    && pc.getRarity() != CardRarity.BasicLand)
                continue;
            keep.add(pc);
        }
        main.clear();
        for (PaperCard pc : keep)
            main.add(pc);

        padToSize(deck, target, templateColors, colorlessOnly, format, player);
        return deck;
    }

    private static boolean cardLegalForRun(PaperCard pc, String format, AdventurePlayer player) {
        if (pc == null)
            return false;
        if (pc.getRules().getType().isBasicLand())
            return true;
        if (FORMAT_STANDARD.equals(format) && player.getStandardWindow().isActive())
            return player.isStandardLegal(pc) && !BanLists.isBanned("standard", pc.getName());
        if (FORMAT_HISTORIC.equals(format))
            return !BanLists.isBanned("historic", pc.getName());
        if (FORMAT_PAUPER.equals(format))
            return !BanLists.isBanned("pauper", pc.getName());
        return true;
    }

    private static void padToSize(Deck deck, int target, String[] templateColors,
                                  boolean colorlessOnly, String format, AdventurePlayer player) {
        CardPool main = deck.getOrCreate(DeckSection.Main);
        int guard = 0;
        while (main.countAll() < target && guard++ < target * 3) {
            PaperCard pad = pickPadCard(templateColors, colorlessOnly, format, player, main.countAll());
            if (pad == null)
                break;
            main.add(pad);
        }
    }

    private static PaperCard pickPadCard(String[] templateColors, boolean colorlessOnly,
                                         String format, AdventurePlayer player, int index) {
        // Prefer basics matching the theme, then colorless staples, then Wastes.
        if (!colorlessOnly && templateColors != null && templateColors.length > 0) {
            String basic = basicForColor(templateColors[index % templateColors.length]);
            PaperCard land = CardUtil.getCardByName(basic);
            if (land != null && cardLegalForRun(land, format, player))
                return land;
        }
        if (colorlessOnly || index % 3 == 0) {
            String staple = COLORLESS_STAPLES[index % COLORLESS_STAPLES.length];
            if (!BanLists.isBanned(format.toLowerCase(), staple)
                    && !(FORMAT_HISTORIC.equals(format) && BanLists.isBanned("historic", staple))) {
                PaperCard pc = FModel.getMagicDb().getCommonCards().getCard(staple);
                if (pc == null)
                    pc = CardUtil.getCardByName(staple);
                if (pc != null && cardLegalForRun(pc, format, player)
                        && !(FORMAT_PAUPER.equals(format) && pc.getRarity() != CardRarity.Common))
                    return pc;
            }
        }
        String basic = colorlessOnly ? "Wastes"
                : (templateColors != null && templateColors.length > 0
                ? basicForColor(templateColors[0]) : "Wastes");
        PaperCard land = CardUtil.getCardByName(basic);
        return land != null ? land : CardUtil.getCardByName("Wastes");
    }

    private static String basicForColor(String colorName) {
        if (colorName == null)
            return "Wastes";
        return switch (colorName.toLowerCase()) {
            case "white", "w" -> "Plains";
            case "blue", "u" -> "Island";
            case "black", "b" -> "Swamp";
            case "red", "r" -> "Mountain";
            case "green", "g" -> "Forest";
            default -> "Wastes";
        };
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
        if (p.isCommanderDeck(d) || p.isHistoricDeck(d))
            return false;
        return p.standardDeckProblem(d) == null;
    }

    public static String legalityMessage() {
        // CO5: list every illegal card before a gym / League / tournament fight
        // instead of dropping the player on the first problem only.
        final java.util.List<String> illegal = listIllegalCardsForRun();
        if (!illegal.isEmpty()) {
            final StringBuilder sb = new StringBuilder("Illegal for this run's format (");
            sb.append(Current.player().getRunFormat()).append("): ");
            for (int i = 0; i < illegal.size(); i++) {
                if (i > 0) {
                    sb.append("; ");
                }
                sb.append(illegal.get(i));
                if (i >= 11) {
                    sb.append("; …");
                    break;
                }
            }
            return sb.toString();
        }
        final AdventurePlayer p = Current.player();
        final String format = p.getRunFormat();
        if (FORMAT_STANDARD.equals(format)
                && (p.isCommanderDeckSelected() || p.isHistoricDeckSelected())) {
            return "Select a Standard deck for this run (not Commander or Historic).";
        }
        return "Your selected deck must be legal for this run's format (" + format + ").";
    }

    /**
     * CO5: every card (and tag) problem for the selected deck vs the run format.
     * Empty when the deck is legal. Used by gyms, League and tournaments to list
     * illegals before the fight.
     */
    public static java.util.List<String> listIllegalCardsForRun() {
        final java.util.List<String> out = new java.util.ArrayList<>();
        final AdventurePlayer p = Current.player();
        final Deck d = p.getSelectedDeck();
        if (d == null) {
            out.add("No deck selected");
            return out;
        }
        final String format = p.getRunFormat();
        if (FORMAT_COMMANDER.equals(format)) {
            if (!p.isCommanderDeck(d)) {
                out.add("Select a Commander deck for this run");
            }
            return out;
        }
        if (FORMAT_HISTORIC.equals(format)) {
            if (!p.isHistoricDeck(d)) {
                out.add("Select a Historic-tagged deck for this run");
            }
            if (p.isCommanderDeck(d)) {
                out.add("Commander decks can't be used in a Historic run");
            }
            for (final java.util.Map.Entry<forge.item.PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
                final forge.item.PaperCard pc = e.getKey();
                if (pc != null && BanLists.isBanned("historic", pc.getName())) {
                    out.add(pc.getName() + " is banned in Historic");
                }
            }
            return out;
        }
        if (FORMAT_PAUPER.equals(format)) {
            if (p.isCommanderDeck(d)) {
                out.add("Commander decks can't be used in a Pauper run");
            }
            final forge.game.GameFormat pauper = forge.model.FModel.getFormats().getPauper();
            for (final java.util.Map.Entry<forge.item.PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
                final forge.item.PaperCard pc = e.getKey();
                if (pc == null || pc.getRules().getType().isBasicLand()) {
                    continue;
                }
                if (BanLists.isBanned("pauper", pc.getName())) {
                    out.add(pc.getName() + " is banned in Pauper");
                } else if (pauper != null && pauper.getFilterRules() != null
                        && !pauper.getFilterRules().test(pc)) {
                    out.add(pc.getName() + " is not Pauper-legal");
                } else if ((pauper == null || pauper.getFilterRules() == null)
                        && pc.getRarity() != forge.card.CardRarity.Common) {
                    out.add(pc.getName() + " is not Pauper-legal (commons only)");
                }
            }
            return out;
        }
        // Standard (default)
        if (p.isCommanderDeck(d) || p.isHistoricDeck(d)) {
            out.add("Select a Standard deck for this run (not Commander or Historic)");
        }
        if (p.getStandardWindow().isActive()) {
            for (final java.util.Map.Entry<forge.item.PaperCard, Integer> e : d.getAllCardsInASinglePool()) {
                final forge.item.PaperCard pc = e.getKey();
                if (pc == null) {
                    continue;
                }
                if (!p.isStandardLegal(pc)) {
                    out.add(pc.getName() + " rotated out of Standard");
                } else if (BanLists.isBanned("standard", pc.getName())) {
                    out.add(pc.getName() + " is banned in Standard");
                }
            }
        }
        return out;
    }

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
