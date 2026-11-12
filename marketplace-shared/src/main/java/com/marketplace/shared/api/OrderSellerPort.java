package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 6 (plan D-08, ADR-0002): the order's seller seam — the facts the
 * ledger's ORDER-origin settlement needs (who to credit, and — since
 * ADR-0013 D4 — the frozen fulfillment split the money paths follow).
 *
 * <p>The order stores its seller at placement (the single-seller cart
 * invariant — ADR-0002); the ledger reads it through this contract and
 * never imports the orders module (the house port discipline verbatim —
 * the {@code BookingParticipantProvider} twin that carries the booking's
 * provider today).
 *
 * <p><b>A1 (adversarial review 2026-11-10, owner-gate batch 6a — the
 * ADR-0013 D4 execution):</b> the settlement now needs the fulfillment
 * facts beside the seller — the frozen platform-delivery fee (already
 * inside {@code totalAmountMinor}) decides the split the ADR prescribes:
 * the seller is credited the goods (+tax) value net of the platform's own
 * shipping fee, and the commission computes on the goods base, never on
 * the fee («رسم واحد لمالك واحد»). The widened read
 * {@link #settlementFactsOf(UUID)} carries the three facts in ONE call —
 * the {@code PaymentIntentDetails} widening precedent (one carrier, one
 * hop); {@link #sellerOf(UUID)} stays for the refund twin (the mirror
 * needs only the seller).</p>
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

    /**
     * A1 (ADR-0013 D4): the order's settlement facts — the seller plus
     * the frozen fulfillment split the ledger's money paths follow.
     *
     * @param orderId the order's id
     * @return the facts record (seller, frozen shipping fee, whether the
     *         fulfillment is platform delivery)
     * @throws com.marketplace.shared.api.ResourceNotFoundException when the
     *         order does not exist or its seller is not attributable
     *         (the legacy pre-invariant shape — the same honest 404
     *         {@link #sellerOf(UUID)} answers)
     */
    OrderSettlementFacts settlementFactsOf(UUID orderId);
}
