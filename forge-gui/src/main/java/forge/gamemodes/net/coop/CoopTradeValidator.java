package forge.gamemodes.net.coop;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Host-side TR1 offer validation: null-safe wire caps, ownership/counts, gold,
 * and eligibility (no quest items; no vaulted / in-deck cards when a bag is supplied).
 */
public final class CoopTradeValidator {

    public enum RejectReason {
        OK,
        NULL_OFFER,
        GOLD,
        GOLD_OVERFLOW,
        NAME_LEN,
        TEXT_LEN,
        COUNT,
        OWNERSHIP,
        QUEST_ITEM,
        VAULTED_OR_DECK,
        TOO_MANY_LINES,
        DUPLICATE,
        NULL_FIELD
    }

    public static final class Result {
        public final RejectReason reason;
        public final String detail;

        public Result(final RejectReason reason, final String detail) {
            this.reason = reason != null ? reason : RejectReason.NULL_OFFER;
            this.detail = detail == null ? "" : detail;
        }

        public boolean ok() {
            return reason == RejectReason.OK;
        }

        public static Result okResult() {
            return new Result(RejectReason.OK, "");
        }

        public static Result reject(final RejectReason reason, final String detail) {
            return new Result(reason, detail);
        }
    }

    private CoopTradeValidator() {
    }

    /**
     * Validate wire shape and self-attested ownership on the offer lines.
     * When {@code bag} is non-null, also checks live ownership and eligibility.
     * Null lists are treated as empty; null line entries / names are rejected.
     */
    public static Result validate(final CoopTradeOffer offer, final CoopTradeBag bag) {
        if (offer == null) {
            return Result.reject(RejectReason.NULL_OFFER, "null");
        }
        final int gold = offer.getGold();
        if (gold < 0 || gold > CoopTradeWireLimits.MAX_GOLD) {
            return Result.reject(RejectReason.GOLD, "gold=" + gold);
        }
        if (bag != null) {
            if (gold > bag.getGold()) {
                return Result.reject(RejectReason.OWNERSHIP, "gold");
            }
            // Gold overflow is checked against the RECEIVER via
            // {@link #validateReceiverGold}, not the giver's bag.
        }

        final List<CoopTradeOffer.Line> materials = offer.getMaterials();
        final List<CoopTradeOffer.Line> items = offer.getItems();
        final List<CoopTradeOffer.CardLine> cards = offer.getCards();
        if (materials == null || items == null || cards == null) {
            return Result.reject(RejectReason.NULL_FIELD, "list");
        }
        if (materials.size() > CoopTradeWireLimits.MAX_MATERIAL_LINES
                || items.size() > CoopTradeWireLimits.MAX_ITEM_LINES
                || cards.size() > CoopTradeWireLimits.MAX_CARD_LINES) {
            return Result.reject(RejectReason.TOO_MANY_LINES, "lines");
        }

        final Set<String> seenMat = new HashSet<>();
        for (final CoopTradeOffer.Line line : materials) {
            final Result r = validateLine(line, seenMat, bag, true);
            if (!r.ok()) {
                return r;
            }
        }
        final Set<String> seenItem = new HashSet<>();
        for (final CoopTradeOffer.Line line : items) {
            final Result r = validateItemLine(line, seenItem, bag);
            if (!r.ok()) {
                return r;
            }
        }
        final Set<String> seenCard = new HashSet<>();
        for (final CoopTradeOffer.CardLine line : cards) {
            final Result r = validateCardLine(line, seenCard, bag);
            if (!r.ok()) {
                return r;
            }
        }
        return Result.okResult();
    }

    /** Wire-only validation (no live bag). */
    public static Result validateWire(final CoopTradeOffer offer) {
        return validate(offer, null);
    }

    /**
     * Whether {@code current + add} would overflow a signed 32-bit gold total.
     */
    public static boolean goldAddWouldOverflow(final int current, final int add) {
        if (add < 0 || current < 0) {
            return true;
        }
        return current > Integer.MAX_VALUE - add;
    }

    /**
     * Refuse an incoming grant that would overflow the <b>receiver's</b> gold
     * (not the giver's).
     */
    public static Result validateReceiverGold(final CoopTradeOffer incoming, final CoopTradeBag receiver) {
        if (incoming == null || receiver == null) {
            return Result.okResult();
        }
        final int gold = incoming.getGold();
        if (gold <= 0) {
            return Result.okResult();
        }
        if (goldAddWouldOverflow(receiver.getGold(), gold)) {
            return Result.reject(RejectReason.GOLD_OVERFLOW, "receiver");
        }
        return Result.okResult();
    }

    private static Result validateLine(final CoopTradeOffer.Line line, final Set<String> seen,
                                       final CoopTradeBag bag, final boolean material) {
        if (line == null) {
            return Result.reject(RejectReason.NULL_FIELD, "line");
        }
        final String id = line.getId();
        if (id == null) {
            return Result.reject(RejectReason.NULL_FIELD, "id");
        }
        if (id.isEmpty() || id.length() > CoopTradeWireLimits.MAX_TEXT_LEN) {
            return Result.reject(RejectReason.TEXT_LEN, id);
        }
        if (!seen.add(id)) {
            return Result.reject(RejectReason.DUPLICATE, id);
        }
        if (line.getCount() <= 0 || line.getCount() > CoopTradeWireLimits.MAX_STACK_COUNT) {
            return Result.reject(RejectReason.COUNT, id);
        }
        if (line.getAvailable() < 0 || line.getCount() > line.getAvailable()) {
            return Result.reject(RejectReason.OWNERSHIP, id);
        }
        if (bag != null) {
            final int have = material ? bag.getMaterial(id) : bag.getItemCount(id);
            if (line.getCount() > have) {
                return Result.reject(RejectReason.OWNERSHIP, id);
            }
        }
        return Result.okResult();
    }

    private static Result validateItemLine(final CoopTradeOffer.Line line, final Set<String> seen,
                                           final CoopTradeBag bag) {
        final Result base = validateLine(line, seen, bag, false);
        if (!base.ok()) {
            return base;
        }
        if (bag != null && bag.isQuestItem(line.getId())) {
            return Result.reject(RejectReason.QUEST_ITEM, line.getId());
        }
        return Result.okResult();
    }

    private static Result validateCardLine(final CoopTradeOffer.CardLine line, final Set<String> seen,
                                           final CoopTradeBag bag) {
        if (line == null) {
            return Result.reject(RejectReason.NULL_FIELD, "card");
        }
        if (line.getName() == null || line.getSetCode() == null) {
            return Result.reject(RejectReason.NULL_FIELD, "card-name");
        }
        if (line.getName().isEmpty() || line.getName().length() > CoopTradeWireLimits.MAX_TEXT_LEN) {
            return Result.reject(RejectReason.TEXT_LEN, line.getName());
        }
        if (line.getSetCode().length() > CoopTradeWireLimits.MAX_SET_CODE_LEN) {
            return Result.reject(RejectReason.TEXT_LEN, line.getSetCode());
        }
        if (line.getArtIndex() < 0) {
            return Result.reject(RejectReason.COUNT, "art");
        }
        final String key = line.key();
        if (!seen.add(key)) {
            return Result.reject(RejectReason.DUPLICATE, key);
        }
        if (line.getCount() <= 0 || line.getCount() > CoopTradeWireLimits.MAX_STACK_COUNT) {
            return Result.reject(RejectReason.COUNT, key);
        }
        if (line.getAvailable() < 0 || line.getCount() > line.getAvailable()) {
            return Result.reject(RejectReason.OWNERSHIP, key);
        }
        if (bag != null) {
            if (bag.isCardBlocked(key) && bag.getTradeableCardCount(key) < line.getCount()) {
                return Result.reject(RejectReason.VAULTED_OR_DECK, key);
            }
            if (line.getCount() > bag.getTradeableCardCount(key)) {
                if (bag.getTradeableCardCount(key) <= 0 && bag.isCardBlocked(key)) {
                    return Result.reject(RejectReason.VAULTED_OR_DECK, key);
                }
                return Result.reject(RejectReason.OWNERSHIP, key);
            }
        }
        return Result.okResult();
    }
}
