package com.marketplace.payments;

import java.util.UUID;

/**
 * Row 15 (agent findings 2026-10-26T11:43 export) — the capture that WON the
 * race against an abandonment: the provider confirmed success for an intent
 * a domain cancellation had already abandoned (CANCELLATION_PENDING). The
 * settlement settled the books to the remote truth (SUCCEEDED + payment
 * COMPLETED); this event carries the abandonment's own decision — the money
 * must return to the buyer — to the ONE refund contract through the
 * abandonment-scoped durable listener.
 *
 * <p>An internal payments-module event on purpose: no other module owns the
 * abandonment's recovery. Published INSIDE the settlement transaction, listened
 * AFTER COMMIT ({@code @ApplicationModuleListener}) — the Modulith Event
 * Publication Registry owns the durability: a crashed worker resubmits the
 * publication, and the abandonment-scoped PSP idempotency key keeps the retry
 * a replay, never a second payment.</p>
 */
public record PaymentAbandonmentCapturedEvent(UUID paymentIntentId) {
}
