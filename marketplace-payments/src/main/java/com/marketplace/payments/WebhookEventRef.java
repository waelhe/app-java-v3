package com.marketplace.payments;

/**
 * R10 (Wave 2): the inbox-row identity handed from the recorder to the
 * dispatch — {@code (provider, event_id)} is the unique key (V42) the dedup
 * gate and every inbox state transition address. A small value type instead
 * of two loose parameters so the atomic settlement contract
 * ({@code PaymentIntentSettlementService.confirm/fail}) can carry it without
 * either side re-deriving the key.
 *
 * <p>Package-private by design: the inbox is the payments module's own
 * machinery — nothing outside {@code com.marketplace.payments} may address
 * webhook rows directly.
 */
record WebhookEventRef(String provider, String eventId) {

    WebhookEventRef {
        if (provider == null || eventId == null) {
            throw new IllegalArgumentException("provider and eventId are required");
        }
    }
}
