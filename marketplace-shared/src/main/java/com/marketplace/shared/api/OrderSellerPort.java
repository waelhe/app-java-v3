package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 6 (plan D-08, ADR-0002): the order's seller seam — the single fact
 * the ledger's ORDER-origin settlement needs (who to credit).
 *
 * <p>The order stores its seller at placement (the single-seller cart
 * invariant — ADR-0002); the ledger reads it through this contract and
 * never imports the orders module (the house port discipline verbatim —
 * the {@code BookingParticipantProvider} twin that carries the booking's
 * provider today).
 */
public interface OrderSellerPort {

    /**
     * Resolves the order's seller.
     *
     * @param orderId the order's id
     * @return the seller's provider id (stored at placement)
     * @throws com.marketplace.shared.api.ResourceNotFoundException when the
     *         order does not exist
     */
    UUID sellerOf(UUID orderId);
}
