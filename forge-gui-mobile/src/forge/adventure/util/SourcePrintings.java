package forge.adventure.util;

import forge.StaticData;
import forge.adventure.data.ConfigData;
import forge.adventure.data.RewardData;
import forge.adventure.player.AdventurePlayer;
import forge.adventure.player.StandardWindow;
import forge.adventure.world.SetPlaneRules;
import forge.card.CardEdition;
import forge.item.PaperCard;
import forge.model.FModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CS0 — Ascendant source printings (stable public API for rewards, shops, packs,
 * Spell Smith, gym staples, fantasy loot, craft, and future RW1 fight rewards).
 *
 * <p><b>Public entry points</b> (keep signatures stable):
 * <ul>
 *   <li>{@link #enabled()} — Ascendant + {@code cs0SourcePrintings}</li>
 *   <li>{@link #useAllCardVariants()} — always false when enabled</li>
 *   <li>{@link #isNormalPrinting(PaperCard)} — non-promo / non-special / allowed</li>
 *   <li>{@link #printingFromSet(String, String)} — pin a name to a set code</li>
 *   <li>{@link #printingFromRotation(String)} / {@link #printingFromRotation(String, Collection)}</li>
 *   <li>{@link #resolve(PaperCard, String[])} / {@link #resolve(PaperCard, RewardData)}</li>
 *   <li>{@link #pinnedEditionsUsable(Iterable, String[])} — shop pin keep-or-fallback</li>
 *   <li>{@link #currentRotationSets()}, {@link #isRestrictedEdition(String)}</li>
 *   <li>{@link #clearCaches()} — call on plane / config reload</li>
 * </ul>
 *
 * <p>When no set context exists, picks a normal printing from the player's current
 * rotation, else the most recent normal printing. Stock worlds are unchanged.
 *
 * <p>Note: CS0 rematches <em>printings</em> only. Shop / reward card <em>picks</em>
 * still use the world-seeded {@link java.util.Random} from
 * {@link forge.adventure.data.RewardData#generate}; seeded shop identity is stable,
 * only the edition/art of each pick may change under CS0.
 */
public final class SourcePrintings {
    private static final Set<String> SPECIAL_SECTIONS = Set.of(
            CardEdition.EditionSectionWithCollectorNumbers.SHOWCASE.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.BORDERLESS.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.EXTENDED_ART.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.FULL_ART.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.ALTERNATE_ART.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.RETRO_FRAME.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.ETCHED.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.PROMO.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.PRERELEASE_PROMO.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.BUY_A_BOX.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.BOX_TOPPER.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.BUNDLE.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.SPECIAL_SLOT.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.JUMPSTART.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.CONJURED.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.REBALANCED.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.ETERNAL.getName(),
            CardEdition.EditionSectionWithCollectorNumbers.PRECON_PRODUCT.getName()
    );

    private static final EnumSet<CardEdition.Type> EXCLUDED_EDITION_TYPES = EnumSet.of(
            CardEdition.Type.PROMO,
            CardEdition.Type.ONLINE,
            CardEdition.Type.COLLECTOR_EDITION,
            CardEdition.Type.FUNNY,
            CardEdition.Type.REPRINT
    );

    /** The List / Mystery Booster — never treated as a "normal" source printing. */
    private static final Set<String> EXCLUDED_EDITION_CODES = Set.of("PLST", "MB1");

    /** Cache: edition|collectorNumber → section name (empty string if unknown). */
    private static final Map<String, String> SECTION_CACHE = new ConcurrentHashMap<>();
    /** Cache: edition|collectorNumber → isNormalPrinting. */
    private static final Map<String, Boolean> NORMAL_CACHE = new ConcurrentHashMap<>();

    private SourcePrintings() {
    }

    /** Drop section / normal caches (plane switch, config reload, tests). */
    public static void clearCaches() {
        SECTION_CACHE.clear();
        NORMAL_CACHE.clear();
    }

    /** @deprecated use {@link #clearCaches()} */
    public static void clearCachesForTest() {
        clearCaches();
    }

    /**
     * True when Ascendant CS0 source printings are active
     * ({@link Config#ascendant()} and {@link ConfigData#cs0SourcePrintings}).
     */
    public static boolean enabled() {
        try {
            if (!Config.ascendant()) {
                return false;
            }
            ConfigData data = Config.instance().getConfigData();
            return data != null && data.cs0SourcePrintings;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Effective {@code useAllCardVariants}: always false when enabled so Ascendant ignores
     * the player setting for rewards, shops, packs and Spell Smith. Stock worlds return
     * the real setting.
     */
    public static boolean useAllCardVariants() {
        if (enabled()) {
            return false;
        }
        try {
            return Config.instance().getSettingData() != null
                    && Config.instance().getSettingData().useAllCardVariants;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A normal printing: not promo / online / collector / funny / reprint-set, not PLST/MB1,
     * not from a special sheet (showcase, borderless, conjured, …), not
     * {@code restrictedEditions}, and inside {@code allowedEditions} when that list is set.
     */
    public static boolean isNormalPrinting(PaperCard pc) {
        if (pc == null) {
            return false;
        }
        String editionCode = pc.getEdition();
        String cn = pc.getCollectorNumber();
        if (editionCode == null) {
            return false;
        }
        String cacheKey = editionCode + "|" + (cn == null ? "" : cn);
        Boolean cached = NORMAL_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        boolean normal = computeIsNormalPrinting(editionCode, cn);
        NORMAL_CACHE.put(cacheKey, normal);
        return normal;
    }

    private static boolean computeIsNormalPrinting(String editionCode, String cn) {
        try {
            if (isExcludedEditionCode(editionCode) || isRestrictedEdition(editionCode)
                    || !isAllowedEdition(editionCode)) {
                return false;
            }
            CardEdition edition = editions().get(editionCode);
            if (edition == null) {
                return false;
            }
            if (EXCLUDED_EDITION_TYPES.contains(edition.getType())) {
                return false;
            }
            String section = sectionFor(editionCode, cn, edition);
            if (section == null || section.isEmpty()) {
                return true;
            }
            String cards = CardEdition.EditionSectionWithCollectorNumbers.CARDS.getName();
            if (cards.equalsIgnoreCase(section)) {
                return true;
            }
            String lower = section.toLowerCase(Locale.ROOT);
            return !SPECIAL_SECTIONS.contains(lower) && !SPECIAL_SECTIONS.contains(section);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Whether a shop/reward edition pin is worth keeping against {@code pool}.
     * Counts distinct non-basic pool names that have a non-basic <em>original</em>
     * printing in a pin edition (no earlier printing elsewhere) — same floor as
     * {@link SetPlaneRules#MIN_SET_POOL_SIZE}. Basics and Commander / D&amp;D
     * reprints of window staples alone are not enough, so 40K / AFR / CLB pins
     * over a Standard window fall back to the rotation.
     */
    public static boolean pinnedEditionsUsable(Iterable<PaperCard> pool, String[] pinEditions) {
        if (pool == null || pinEditions == null || pinEditions.length == 0) {
            return false;
        }
        List<String> usablePins = new ArrayList<>();
        for (String ed : pinEditions) {
            if (ed == null || ed.isEmpty() || isRestrictedEdition(ed) || isExcludedEditionCode(ed)) {
                continue;
            }
            if (!isAllowedEdition(ed)) {
                continue;
            }
            try {
                CardEdition edition = editions().get(ed);
                if (edition != null && EXCLUDED_EDITION_TYPES.contains(edition.getType())) {
                    continue;
                }
            } catch (Throwable ignored) {
                continue;
            }
            usablePins.add(ed);
        }
        if (usablePins.isEmpty()) {
            return false;
        }
        int n = 0;
        Set<String> seen = new HashSet<>();
        for (PaperCard pc : pool) {
            if (pc == null || SetPlaneRules.isBasicLand(pc)) {
                continue;
            }
            String name = pc.getName();
            if (name == null || !seen.add(name)) {
                continue;
            }
            for (String ed : usablePins) {
                if (isOriginalNonBasicInEdition(name, ed)) {
                    n++;
                    break;
                }
            }
            if (n >= SetPlaneRules.MIN_SET_POOL_SIZE) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code cardName} has a non-basic printing in {@code editionCode}
     * and no printing in any earlier-dated edition (i.e. not a reprint into the pin).
     */
    private static boolean isOriginalNonBasicInEdition(String cardName, String editionCode) {
        PaperCard pinPrint = printingFromSet(cardName, editionCode);
        if (pinPrint == null || !editionCode.equalsIgnoreCase(pinPrint.getEdition())
                || SetPlaneRules.isBasicLand(pinPrint)) {
            return false;
        }
        Date pinDate = editionDate(pinPrint);
        if (pinDate == null) {
            return false;
        }
        for (PaperCard other : allPrintings(cardName)) {
            if (other == null || editionCode.equalsIgnoreCase(other.getEdition())) {
                continue;
            }
            Date otherDate = editionDate(other);
            if (otherDate != null && otherDate.before(pinDate)) {
                return false;
            }
        }
        return true;
    }

    /** Prefer a main-sheet printing of {@code cardName} from {@code setCode}. */
    public static PaperCard printingFromSet(String cardName, String setCode) {
        if (cardName == null || cardName.isEmpty() || setCode == null || setCode.isEmpty()) {
            return null;
        }
        if (isRestrictedEdition(setCode) || isExcludedEditionCode(setCode) || !isAllowedEdition(setCode)) {
            return null;
        }
        try {
            CardEdition edition = editions().get(setCode);
            if (edition != null && EXCLUDED_EDITION_TYPES.contains(edition.getType())) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        List<PaperCard> inSet = new ArrayList<>();
        for (PaperCard pc : allPrintings(cardName)) {
            if (pc != null && setCode.equalsIgnoreCase(pc.getEdition())) {
                inSet.add(pc);
            }
        }
        if (inSet.isEmpty()) {
            return null;
        }
        PaperCard normal = pickPreferred(inSet);
        return normal != null ? normal : inSet.get(0);
    }

    /**
     * Normal printing from one of {@code rotationSets}, else most recent normal printing
     * anywhere, else any non-restricted printing.
     */
    public static PaperCard printingFromRotation(String cardName, Collection<String> rotationSets) {
        if (cardName == null || cardName.isEmpty()) {
            return null;
        }
        List<PaperCard> all = allPrintings(cardName);
        if (all.isEmpty()) {
            return null;
        }
        Set<String> rotation = normalizeCodes(rotationSets);
        if (!rotation.isEmpty()) {
            List<PaperCard> normalInRotation = new ArrayList<>();
            List<PaperCard> anyInRotation = new ArrayList<>();
            for (PaperCard pc : all) {
                if (pc == null || !rotation.contains(pc.getEdition().toUpperCase(Locale.ROOT))) {
                    continue;
                }
                if (!isAllowedEdition(pc.getEdition())) {
                    continue;
                }
                anyInRotation.add(pc);
                if (isNormalPrinting(pc)) {
                    normalInRotation.add(pc);
                }
            }
            PaperCard pick = pickPreferred(normalInRotation);
            if (pick != null) {
                return pick;
            }
            pick = pickPreferred(anyInRotation);
            if (pick != null) {
                return pick;
            }
        }
        List<PaperCard> normals = new ArrayList<>();
        for (PaperCard pc : all) {
            if (isNormalPrinting(pc)) {
                normals.add(pc);
            }
        }
        PaperCard recent = pickMostRecent(normals);
        if (recent != null) {
            return recent;
        }
        List<PaperCard> usable = new ArrayList<>();
        for (PaperCard pc : all) {
            if (pc != null && !isRestrictedEdition(pc.getEdition())
                    && !isExcludedEditionCode(pc.getEdition())
                    && isAllowedEdition(pc.getEdition())) {
                usable.add(pc);
            }
        }
        recent = pickMostRecent(usable.isEmpty() ? all : usable);
        return recent != null ? recent : all.get(0);
    }

    /** Uses the player's current Standard window when available. */
    public static PaperCard printingFromRotation(String cardName) {
        return printingFromRotation(cardName, currentRotationSets());
    }

    /**
     * Rematch {@code candidate} to its source printing when CS0 is on.
     * Source editions (shop / reward / Name|SET pin) win; else the active set plane;
     * else rotation / most-recent normal.
     */
    public static PaperCard resolve(PaperCard candidate, String[] sourceEditions) {
        if (candidate == null || !enabled()) {
            return candidate;
        }
        if (sourceEditions != null) {
            for (String ed : sourceEditions) {
                if (ed == null || ed.isEmpty() || isRestrictedEdition(ed)
                        || isExcludedEditionCode(ed) || !isAllowedEdition(ed)) {
                    continue;
                }
                PaperCard pinned = printingFromSet(candidate.getName(), ed);
                if (pinned != null && ed.equalsIgnoreCase(pinned.getEdition())) {
                    return pinned;
                }
            }
        }
        try {
            String planeSet = SetPlaneRules.activeSetCode();
            if (planeSet != null && !planeSet.isEmpty() && !isRestrictedEdition(planeSet)
                    && isAllowedEdition(planeSet)) {
                PaperCard pinned = printingFromSet(candidate.getName(), planeSet);
                if (pinned != null && planeSet.equalsIgnoreCase(pinned.getEdition())) {
                    return pinned;
                }
            }
        } catch (Throwable ignored) {
            // Headless / no plane context.
        }
        PaperCard fromRotation = printingFromRotation(candidate.getName());
        return fromRotation != null ? fromRotation : candidate;
    }

    public static PaperCard resolve(PaperCard candidate, RewardData data) {
        return resolve(candidate, data == null ? null : data.editions);
    }

    /** Current Bellwarden window set codes, or empty when rotation is inactive. */
    public static List<String> currentRotationSets() {
        try {
            AdventurePlayer player = AdventurePlayer.current();
            if (player == null) {
                return List.of();
            }
            StandardWindow window = player.getStandardWindow();
            if (window == null || !window.isActive()) {
                return List.of();
            }
            return new ArrayList<>(window.getSets());
        } catch (Throwable t) {
            return List.of();
        }
    }

    public static boolean isRestrictedEdition(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        try {
            ConfigData data = Config.instance().getConfigData();
            if (data == null || data.restrictedEditions == null) {
                return false;
            }
            for (String r : data.restrictedEditions) {
                if (code.equalsIgnoreCase(r)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // Config unavailable.
        }
        return false;
    }

    /** True when {@code allowedEditions} is unset/empty, or {@code code} is listed. */
    public static boolean isAllowedEdition(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        try {
            ConfigData data = Config.instance().getConfigData();
            if (data == null || data.allowedEditions == null || data.allowedEditions.length == 0) {
                return true;
            }
            for (String a : data.allowedEditions) {
                if (code.equalsIgnoreCase(a)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isExcludedEditionCode(String code) {
        return code != null && EXCLUDED_EDITION_CODES.contains(code.toUpperCase(Locale.ROOT));
    }

    private static List<PaperCard> allPrintings(String cardName) {
        try {
            StaticData db = magicDb();
            if (db == null) {
                return List.of();
            }
            List<PaperCard> cards = db.getCommonCards().getAllCardsNoAlt(cardName);
            return cards == null ? List.of() : cards;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static StaticData magicDb() {
        try {
            StaticData fromModel = FModel.getMagicDb();
            if (fromModel != null) {
                return fromModel;
            }
        } catch (Throwable ignored) {
            // Fall through.
        }
        return StaticData.instance();
    }

    private static CardEdition.Collection editions() {
        StaticData db = magicDb();
        return db != null ? db.getEditions() : null;
    }

    private static String sectionFor(String editionCode, String cn, CardEdition edition) {
        String key = editionCode + "|" + (cn == null ? "" : cn);
        return SECTION_CACHE.computeIfAbsent(key, k -> {
            try {
                String section = edition.getSectionForCollectorNumber(cn);
                return section == null ? "" : section;
            } catch (Throwable t) {
                return "";
            }
        });
    }

    private static Set<String> normalizeCodes(Collection<String> codes) {
        Set<String> out = new HashSet<>();
        if (codes == null) {
            return out;
        }
        for (String c : codes) {
            if (c != null && !c.isEmpty()
                    && !StandardWindow.CORE_COLLECTION.equalsIgnoreCase(c)) {
                out.add(c.toUpperCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Prefer normal main-sheet, then lowest collector number (numeric order). */
    private static PaperCard pickPreferred(List<PaperCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        return cards.stream()
                .filter(pc -> pc != null)
                .min(Comparator
                        .comparing((PaperCard pc) -> isNormalPrinting(pc) ? 0 : 1)
                        .thenComparing(SourcePrintings::sortableCn))
                .orElse(null);
    }

    /**
     * Newest edition date, then lowest collector number (same CN direction as
     * {@link #pickPreferred}).
     */
    private static PaperCard pickMostRecent(List<PaperCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        return cards.stream()
                .filter(pc -> pc != null)
                .max(Comparator
                        .comparing(SourcePrintings::editionDate, Comparator.nullsFirst(Date::compareTo))
                        .thenComparing(SourcePrintings::sortableCn, Comparator.reverseOrder()))
                .orElse(null);
    }

    private static String sortableCn(PaperCard pc) {
        return CardEdition.getSortableCollectorNumber(pc.getCollectorNumber());
    }

    private static Date editionDate(PaperCard pc) {
        try {
            CardEdition ed = editions().get(pc.getEdition());
            return ed != null ? ed.getDate() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
