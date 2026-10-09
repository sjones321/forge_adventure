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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CS0: Ascendant card printings come from their source (pack set, set plane, shop pool),
 * never from random variants. When no set context exists (junk shops, generic loot), pick a
 * normal printing from the player's current rotation, falling back to the most recent normal
 * (non-promo, non-showcase) printing. Stock worlds are unchanged.
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
            CardEdition.EditionSectionWithCollectorNumbers.JUMPSTART.getName()
    );

    private SourcePrintings() {
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
     * Effective {@code useAllCardVariants}: always false under CS0 so Ascendant ignores
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
     * A normal printing: non-promo edition and not from a showcase/borderless/etc. sheet.
     * Main {@code cards} sheet printings (and unknown section) count as normal.
     */
    public static boolean isNormalPrinting(PaperCard pc) {
        if (pc == null) {
            return false;
        }
        try {
            CardEdition edition = editions().get(pc.getEdition());
            if (edition == null) {
                return false;
            }
            if (edition.getType() == CardEdition.Type.PROMO) {
                return false;
            }
            String section = edition.getSectionForCollectorNumber(pc.getCollectorNumber());
            if (section == null || section.isEmpty()) {
                return true;
            }
            String cards = CardEdition.EditionSectionWithCollectorNumbers.CARDS.getName();
            if (cards.equalsIgnoreCase(section)) {
                return true;
            }
            return !SPECIAL_SECTIONS.contains(section.toLowerCase(Locale.ROOT))
                    && !SPECIAL_SECTIONS.contains(section);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Prefer a main-sheet printing of {@code cardName} from {@code setCode}. */
    public static PaperCard printingFromSet(String cardName, String setCode) {
        if (cardName == null || cardName.isEmpty() || setCode == null || setCode.isEmpty()) {
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
     * anywhere, else any printing.
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
        recent = pickMostRecent(all);
        return recent != null ? recent : all.get(0);
    }

    /** Uses the player's current Standard window when available. */
    public static PaperCard printingFromRotation(String cardName) {
        return printingFromRotation(cardName, currentRotationSets());
    }

    /**
     * Rematch {@code candidate} to its source printing when CS0 is on.
     * Source editions (shop / reward pin) win; else the active set plane; else rotation.
     */
    public static PaperCard resolve(PaperCard candidate, String[] sourceEditions) {
        if (candidate == null || !enabled()) {
            return candidate;
        }
        if (sourceEditions != null) {
            for (String ed : sourceEditions) {
                if (ed == null || ed.isEmpty()) {
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
            if (planeSet != null && !planeSet.isEmpty()) {
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

    /** Prefer normal main-sheet, then lowest collector number. */
    private static PaperCard pickPreferred(List<PaperCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        return cards.stream()
                .filter(pc -> pc != null)
                .min(Comparator
                        .comparing((PaperCard pc) -> isNormalPrinting(pc) ? 0 : 1)
                        .thenComparing(PaperCard::getCollectorNumber,
                                Comparator.nullsLast(String::compareTo)))
                .orElse(null);
    }

    private static PaperCard pickMostRecent(List<PaperCard> cards) {
        if (cards == null || cards.isEmpty()) {
            return null;
        }
        return cards.stream()
                .filter(pc -> pc != null)
                .max(Comparator
                        .comparing(SourcePrintings::editionDate, Comparator.nullsFirst(Date::compareTo))
                        .thenComparing(PaperCard::getCollectorNumber,
                                Comparator.nullsLast(String::compareTo)))
                .orElse(null);
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
