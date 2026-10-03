package com.marketplace.payments;

import com.marketplace.shared.api.AdWindowBilledEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * W5 (yelp-level plan §5 — G24): the payments half of the billing event —
 * the plan's «وظيفة خصم دورية تُصدر نية دفع». The listener derives the
 * DETERMINISTIC idempotency key from the event's own window identity, so
 * a redelivery of the same event replays into the existing intent.
 */
class AdBillingEventListenerTest {

    private final PaymentsService paymentsService = mock(PaymentsService.class);
    private final AdBillingEventListener listener = new AdBillingEventListener(paymentsService);

    @Test
    void issuesTheIntentUnderTheDeterministicWindowKey() {
        UUID campaignId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID providerId = UUID.randomUUID();
        LocalDate windowStart = LocalDate.parse("2026-10-01");

        listener.onWindowBilled(new AdWindowBilledEvent(
                campaignId, providerId, windowStart, windowStart.plusDays(2),
                1200L, 40L, 10000L, "SAR"));

        verify(paymentsService).createAdIntent(
                eq(providerId), eq(campaignId), eq(windowStart),
                eq(10000L), eq("SAR"),
                eq("ad-debit-11111111-1111-1111-1111-111111111111-2026-10-01"));
    }

    @Test
    void theKeyFitsTheColumnsOwnLengthLimit() {
        // The idempotency column is VARCHAR(64): the deterministic key's
        // shape (prefix + UUID + '-' + ISO date) must never exceed it —
        // the column's own UNIQUE backstop depends on the key arriving
        // intact.
        String key = AdBillingEventListener.AD_INTENT_KEY_PREFIX
                + UUID.randomUUID() + "-" + LocalDate.parse("2026-09-30");
        org.junit.jupiter.api.Assertions.assertTrue(key.length() <= 64,
                "key length " + key.length() + " exceeds the idempotency_key VARCHAR(64): " + key);
    }
}
