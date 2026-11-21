package com.marketplace.shared.api;

import java.util.UUID;

/**
 * A1 (adversarial review 2026-11-10, owner-gate batch 6a — the ADR-0013
 * D4 execution): the order's settlement facts the ledger's ORDER-origin
 * money paths read — the seller plus the frozen fulfillment split.
 *
 * <p>The platform-delivery fee is frozen at checkout
 * ({@code shippingAmountMinor}, already inside {@code totalAmountMinor} —
 * the order's ONE payment intent carries it; no second charge). Per
 * ADR-0013 D1/D4 the platform is the merchant of record and the shipping
 * fee is the platform's own fee funding the carrier — so the seller's
 * allocation is the goods (+tax) value net of the fee, and the commission
 * computes on the goods base, never on the fee («رسم واحد لمالك واحد» —
 * the fee has one owner, the platform; the pre-fix settlement inflated
 * the seller's "owed" balance by the fee and computed the commission on
 * it: the ledger's own finding A1).</p>
 *
 * @param sellerId            the order's seller (stored at placement —
 *                            the single-seller cart invariant)
 * @param shippingAmountMinor the frozen platform-delivery fee (0 for
 *                            PICKUP and legacy orders — PICKUP records
 *                            the method with no fee, the V224 shape law)
 * @param platformDelivery    whether the fulfillment method is
 *                            PLATFORM_DELIVERY (the fee debit rides this
 *                            flag — a zero fee with the flag stays honest
 *                            by the pair-guard the placement factory
 *                            already carries)
 */
public record OrderSettlementFacts(UUID sellerId, long shippingAmountMinor,
                                   boolean platformDelivery) {
}
