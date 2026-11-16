package com.marketplace.shared.api;

import java.util.UUID;

/**
 * CodeRabbit agent findings (row 16 — the aggregate-fulfillment
 * reconciliation): published by the delivery module when a delivery leg
 * reaches a terminal state WITHOUT physical delivery (CANCELLED or FAILED),
 * committed inside the leg's own transition transaction.
 *
 * <p>The gap this fact closes: the multi-leg law fulfills a
 * platform-delivery order when EVERY leg reaches a terminal state and at
 * least one leg was DELIVERED — but only the DELIVERED edge carried an
 * event. A secondary package delivered first (its event correctly waited
 * behind the still-active primary) followed by the buyer cancelling the
 * uncollected primary left the order CONFIRMED forever with zero active
 * legs — and that stuck state was exactly the opening the delivered-goods
 * cancellation guard now refuses. The orders module consumes this fact to
 * reconcile the aggregate: every leg terminal AND at least one delivered
 * means the delivery completed in fact, and the order fulfills through the
 * same verified edge the DELIVERED event rides.
 *
 * <p>The Modulith house pattern ({@code DeliveryCompletedEvent} /
 * {@code DeliveryRefundRequestedEvent} precedents): identifiers and
 * stored values only, published inside the transition's transaction so the
 * publication registry entry commits atomically with the state write — a
 * consumer failing AFTER_COMMIT leaves the registry entry incomplete for
 * the framework's resubmission instead of losing the fact.
 *
 * <p>{@code status} carries the terminal state's stored name
 * ({@code CANCELLED} or {@code FAILED} — the
 * {@code PaymentStateChangedEvent} String-vocabulary precedent: the event
 * carries the stored name, not the owning module's enum, so shared-api
 * stays free of delivery-domain types). {@code orderId} is null for a
 * standalone delivery (no order aggregate to reconcile) — the consumer's
 * honest no-op.
 */
public record DeliveryLegTerminatedEvent(
        UUID deliveryRequestId,
        String status,
        UUID orderId
) {
}
