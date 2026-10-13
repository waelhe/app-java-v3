package com.marketplace.shared.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stage 6 (plan D-08, ADR-0002): the store's inventory seam — the atomic
 * stock reservation contract the order machine's placement, cancellation
 * and fulfillment ride.
 *
 * <p>The concurrency mechanism is the catalog module's own implementation
 * detail (one conditional modifying UPDATE per line inside the caller's
 * transaction — the PostgreSQL row-lock conditional update, the
 * Spring Data JPA-official mechanism): two competing placements cannot
 * both reserve the last unit, and the loser answers the house 409 through
 * {@link ConflictException}. Every method is a no-op-safe whole-map write:
 * the order machine calls it exactly once per guarded transition, and the
 * machine's own state guard is the idempotency (a repeated call for an
 * already-released order re-answers the guard, never the stock).
 */
public interface ProductStockPort {

    /**
     * Reserves one quantity per product — the placement's write. Fails the
     * WHOLE reservation when any line cannot be reserved (no partial
     * placement): the exception rolls the placement transaction back with
     * it.
     *
     * @param quantities product id → quantity to reserve (all ≥ 1)
     * @throws com.marketplace.shared.api.ConflictException when any product
     *         is not ACTIVE or lacks the free stock for its quantity
     */
    void reserve(Map<UUID, Integer> quantities);

    /**
     * Releases one quantity per product — the cancellation's write. Only
     * legal on orders whose placement reserved (the machine's guard), and
     * only up to the reserved amount.
     */
    void release(Map<UUID, Integer> quantities);

    /**
     * Commits one quantity per product — the fulfillment's write: the
     * reservation becomes a real deduction (stock and reserved both fall
     * by the quantity).
     */
    void commit(Map<UUID, Integer> quantities);

    /**
     * The line quantities of one placement, in port-carrier form — the
     * adapter-friendly shape the machine passes back on release/commit.
     *
     * @param productId the product's id
     * @param quantity  the frozen line quantity
     */
    record StockLine(UUID productId, int quantity) {

        public static Map<UUID, Integer> asMap(List<StockLine> lines) {
            return lines.stream().collect(java.util.stream.Collectors.toMap(
                    StockLine::productId, StockLine::quantity, Integer::sum));
        }
    }
}
