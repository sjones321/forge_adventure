package forge.gamemodes.net.coop;

/**
 * Forward-only inventory mutations for TR1 escrow trading.
 * <ul>
 *   <li>{@link #escrow} — remove only this side's offered goods.</li>
 *   <li>{@link #deliver} — grant the peer's offer (never reversed).</li>
 *   <li>{@link #refundEscrow} — return this side's own escrow when reconcile
 *       shows the peer never escrowed.</li>
 * </ul>
 * Received goods are never taken back.
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
     * Remove {@code ownOffer} from {@code bag}. On failure restores {@code snap}
     * when provided. Does not grant anything.
     */
    public static Result escrow(final CoopTradeBag bag, final CoopTradeOffer ownOffer,
                                final CoopTradeBag.Snapshot snap) {
        if (bag == null) {
            return Result.fail("null bag");
        }
        final CoopTradeOffer out = ownOffer != null ? ownOffer : CoopTradeOffer.empty();
        final CoopTradeValidator.Result check = CoopTradeValidator.validate(out, bag);
        if (!check.ok()) {
            return Result.fail(check.reason + ":" + check.detail);
        }
        try {
            if (!removeOffer(bag, out)) {
                if (snap != null) {
                    bag.restore(snap);
                }
                return Result.fail("remove");
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
     * Idempotent escrow keyed by trade id. If the log already shows ESCROWED
     * (or later, non-refunded), returns ok without mutating.
     */
    public static Result escrowIdempotent(final long tradeId, final CoopTradeLog log,
                                          final CoopTradeBag bag, final CoopTradeOffer ownOffer,
                                          final CoopTradeBag.Snapshot snap) {
        if (log != null && log.hasEscrowed(tradeId)) {
            return Result.ok();
        }
        return escrow(bag, ownOffer, snap);
    }

    /**
     * Grant {@code peerOffer} into {@code bag}. Receiver-side gold overflow is
     * refused; items/materials may route to Overflow. Never reverses a prior
     * deliver — callers must check the log first.
     */
    public static Result deliver(final CoopTradeBag bag, final CoopTradeOffer peerOffer,
                                 final CoopTradeBag.Snapshot snap) {
        if (bag == null) {
            return Result.fail("null bag");
        }
        final CoopTradeOffer in = peerOffer != null ? peerOffer : CoopTradeOffer.empty();
        final CoopTradeValidator.Result recvGold = CoopTradeValidator.validateReceiverGold(in, bag);
        if (!recvGold.ok()) {
            return Result.fail(recvGold.reason + ":" + recvGold.detail);
        }
        try {
            if (!grantOffer(bag, in)) {
                if (snap != null) {
                    bag.restore(snap);
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
     * Idempotent deliver. If the log already shows DELIVERED, returns ok.
     */
    public static Result deliverIdempotent(final long tradeId, final CoopTradeLog log,
                                           final CoopTradeBag bag, final CoopTradeOffer peerOffer,
                                           final CoopTradeBag.Snapshot snap) {
        if (log != null && log.hasDelivered(tradeId)) {
            return Result.ok();
        }
        return deliver(bag, peerOffer, snap);
    }

    /**
     * Refund this side's own escrowed goods back into the bag. Only used when
     * reconcile shows the peer never escrowed. Does not touch received goods.
     */
    public static Result refundEscrow(final CoopTradeBag bag, final CoopTradeOffer ownOffer) {
        if (bag == null) {
            return Result.fail("null bag");
        }
        final CoopTradeOffer out = ownOffer != null ? ownOffer : CoopTradeOffer.empty();
        try {
            if (!grantOffer(bag, out)) {
                return Result.fail("refund grant");
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
