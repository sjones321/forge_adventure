package forge.gamemodes.net.coop;

/**
 * Atomic inventory mutation helpers for TR1. Every take/grant return value is
 * checked; any failure restores the pre-apply snapshot so items and cards roll
 * back along with gold and materials.
 *
 * <p>Two-phase commit (guest first, host after ack) lives in
 * {@link CoopTradeState}; this class only mutates bags.
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
     * Either both bags change or neither does.
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
            if (!grantOffer(b, left)) {
                a.restore(snapA);
                b.restore(snapB);
                return Result.fail("grant b");
            }
            if (!grantOffer(a, right)) {
                a.restore(snapA);
                b.restore(snapB);
                return Result.fail("grant a");
            }
            return Result.ok();
        } catch (final RuntimeException ex) {
            a.restore(snapA);
            b.restore(snapB);
            return Result.fail("exception:" + ex.getMessage());
        }
    }

    /**
     * Apply one side's give/receive against a single bag (guest or host local apply).
     * On mid-apply failure the optional {@code snap} restores the bag; post-ack
     * rollback uses {@link #reverseLocal} (line-only) instead of a full snapshot.
     */
    public static Result applyLocal(final CoopTradeBag bag, final CoopTradeOffer give,
                                    final CoopTradeOffer receive, final CoopTradeBag.Snapshot snap) {
        if (bag == null) {
            return Result.fail("null bag");
        }
        final CoopTradeOffer out = give != null ? give : CoopTradeOffer.empty();
        final CoopTradeOffer in = receive != null ? receive : CoopTradeOffer.empty();
        final CoopTradeValidator.Result check = CoopTradeValidator.validate(out, bag);
        if (!check.ok()) {
            return Result.fail(check.reason + ":" + check.detail);
        }
        final CoopTradeValidator.Result recvGold = CoopTradeValidator.validateReceiverGold(in, bag);
        if (!recvGold.ok()) {
            return Result.fail(recvGold.reason + ":" + recvGold.detail);
        }
        try {
            if (!removeOffer(bag, out)) {
                if (snap != null) {
                    bag.restore(snap);
                }
                return Result.fail("remove");
            }
            if (!grantOffer(bag, in)) {
                if (snap != null) {
                    bag.restore(snap);
                } else {
                    // Best-effort undo of the remove when no snap.
                    grantOffer(bag, out);
                }
                return Result.fail("grant");
            }
            return Result.ok();
        } catch (final RuntimeException ex) {
            if (snap != null) {
                bag.restore(snap);
            }
            return Result.fail("exception:" + ex.getMessage());
        }
    }

    /**
     * Idempotent local apply keyed by trade id. If {@code log} already records a
     * local apply for {@code role}, returns ok without mutating the bag.
     */
    public static Result applyLocalIdempotent(final long tradeId, final CoopTradeRole role,
                                              final CoopTradeLog log, final CoopTradeBag bag,
                                              final CoopTradeOffer give, final CoopTradeOffer receive,
                                              final CoopTradeBag.Snapshot snap) {
        if (log != null && log.hasLocalApply(tradeId, role)) {
            return Result.ok();
        }
        return applyLocal(bag, give, receive, snap);
    }

    /**
     * Line-only undo of {@link #applyLocal}: take back what was received and
     * return what was given. Unrelated bag changes since the apply survive.
     */
    public static Result reverseLocal(final CoopTradeBag bag, final CoopTradeOffer give,
                                      final CoopTradeOffer receive) {
        if (bag == null) {
            return Result.fail("null bag");
        }
        final CoopTradeOffer out = give != null ? give : CoopTradeOffer.empty();
        final CoopTradeOffer in = receive != null ? receive : CoopTradeOffer.empty();
        try {
            if (!removeOffer(bag, in)) {
                return Result.fail("reverse remove received");
            }
            if (!grantOffer(bag, out)) {
                // Re-grant what we just took so we don't strand the bag.
                grantOffer(bag, in);
                return Result.fail("reverse grant given");
            }
            return Result.ok();
        } catch (final RuntimeException ex) {
            return Result.fail("exception:" + ex.getMessage());
        }
    }

    public static boolean removeOffer(final CoopTradeBag bag, final CoopTradeOffer offer) {
        if (offer.getGold() > 0 && !bag.takeGold(offer.getGold())) {
            return false;
        }
        for (final CoopTradeOffer.Line line : offer.getMaterials()) {
            if (line == null || !bag.takeMaterial(line.getId(), line.getCount())) {
                return false;
            }
        }
        for (final CoopTradeOffer.Line line : offer.getItems()) {
            if (line == null || !bag.takeItem(line.getId(), line.getCount())) {
                return false;
            }
        }
        for (final CoopTradeOffer.CardLine line : offer.getCards()) {
            if (line == null || !bag.takeCard(line.key(), line.getCount())) {
                return false;
            }
        }
        return true;
    }

    public static boolean grantOffer(final CoopTradeBag bag, final CoopTradeOffer offer) {
        if (offer.getGold() > 0 && !bag.addGold(offer.getGold())) {
            return false;
        }
        for (final CoopTradeOffer.Line line : offer.getMaterials()) {
            if (line == null || !bag.addMaterial(line.getId(), line.getCount())) {
                return false;
            }
        }
        for (final CoopTradeOffer.Line line : offer.getItems()) {
            if (line == null || !bag.addItem(line.getId(), line.getCount())) {
                return false;
            }
        }
        for (final CoopTradeOffer.CardLine line : offer.getCards()) {
            if (line == null || !bag.addCard(line.key(), line.getCount())) {
                return false;
            }
        }
        return true;
    }
}
