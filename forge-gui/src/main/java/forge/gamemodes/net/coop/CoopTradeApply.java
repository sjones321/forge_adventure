package forge.gamemodes.net.coop;

/**
 * Atomic two-bag swap for TR1. Either both inventories change or neither does.
 * Received grants go through {@link CoopTradeBag#addItem}/{@code addMaterial}/
 * {@code addCard}, which may route excess to Overflow — never silent loss.
 */
public final class CoopTradeApply {

    public static final class Result {
        public final boolean applied;
        public final String detail;

        public Result(final boolean applied, final String detail) {
            this.applied = applied;
            this.detail = detail == null ? "" : detail;
        }

        public static Result ok() {
            return new Result(true, "");
        }

        public static Result fail(final String detail) {
            return new Result(false, detail);
        }
    }

    private CoopTradeApply() {
    }

    /**
     * {@code a} gives {@code aOffer} to {@code b}; {@code b} gives {@code bOffer} to {@code a}.
     */
    public static Result applyAtomic(final CoopTradeBag a, final CoopTradeOffer aOffer,
                                     final CoopTradeBag b, final CoopTradeOffer bOffer) {
        if (a == null || b == null) {
            return Result.fail("null bag");
        }
        final CoopTradeOffer left = aOffer != null ? aOffer : CoopTradeOffer.empty();
        final CoopTradeOffer right = bOffer != null ? bOffer : CoopTradeOffer.empty();

        final CoopTradeValidator.Result va = CoopTradeValidator.validate(left, a);
        if (!va.ok()) {
            return Result.fail("a:" + va.reason + ":" + va.detail);
        }
        final CoopTradeValidator.Result vb = CoopTradeValidator.validate(right, b);
        if (!vb.ok()) {
            return Result.fail("b:" + vb.reason + ":" + vb.detail);
        }

        final CoopTradeBag.Snapshot snapA = a.snapshot();
        final CoopTradeBag.Snapshot snapB = b.snapshot();
        try {
            if (!removeOffer(a, left)) {
                a.restore(snapA);
                b.restore(snapB);
                return Result.fail("remove a");
            }
            if (!removeOffer(b, right)) {
                a.restore(snapA);
                b.restore(snapB);
                return Result.fail("remove b");
            }
            grantOffer(b, left);
            grantOffer(a, right);
            return Result.ok();
        } catch (final RuntimeException ex) {
            a.restore(snapA);
            b.restore(snapB);
            return Result.fail("exception:" + ex.getMessage());
        }
    }

    private static boolean removeOffer(final CoopTradeBag bag, final CoopTradeOffer offer) {
        if (offer.getGold() > 0 && !bag.takeGold(offer.getGold())) {
            return false;
        }
        for (final CoopTradeOffer.Line line : offer.getMaterials()) {
            if (!bag.takeMaterial(line.getId(), line.getCount())) {
                return false;
            }
        }
        for (final CoopTradeOffer.Line line : offer.getItems()) {
            if (!bag.takeItem(line.getId(), line.getCount())) {
                return false;
            }
        }
        for (final CoopTradeOffer.CardLine line : offer.getCards()) {
            if (!bag.takeCard(line.key(), line.getCount())) {
                return false;
            }
        }
        return true;
    }

    private static void grantOffer(final CoopTradeBag bag, final CoopTradeOffer offer) {
        if (offer.getGold() > 0) {
            bag.addGold(offer.getGold());
        }
        for (final CoopTradeOffer.Line line : offer.getMaterials()) {
            bag.addMaterial(line.getId(), line.getCount());
        }
        for (final CoopTradeOffer.Line line : offer.getItems()) {
            bag.addItem(line.getId(), line.getCount());
        }
        for (final CoopTradeOffer.CardLine line : offer.getCards()) {
            bag.addCard(line.key(), line.getCount());
        }
    }
}
