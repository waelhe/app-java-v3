package com.marketplace.ledger;

import com.marketplace.shared.api.AdWindowBilledEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * W5 (yelp-level plan §5 — G24): the ledger half of the billing event —
 * the plan's «وتقيد الدفتر بنوع مُصدَر جديد AD_DEBIT». The listener derives
 * the DETERMINISTIC source key from the event's own window identity — the
 * key the V19 {@code source_id UNIQUE} index rejects on any replay.
 */
class LedgerAdBillingEventListenerTest {

    private final LedgerService ledgerService = mock(LedgerService.class);
    private final LedgerAdBillingEventListener listener = new LedgerAdBillingEventListener(ledgerService);

    @Test
    void debitsTheLedgerUnderTheDeterministicWindowKey() {
        UUID campaignId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID providerId = UUID.randomUUID();
        LocalDate windowStart = LocalDate.parse("2026-10-01");

        listener.onWindowBilled(new AdWindowBilledEvent(
                campaignId, providerId, windowStart, windowStart.plusDays(2),
                1200L, 40L, 10000L, "SAR"));

        // The plan's literal key AD_DEBIT:{campaignId}:{windowStart}, derived
        // through the JDK v3 UUID — predictable in the test, structural in
        // the database.
        verify(ledgerService).debitFromAds(eq(providerId),
                eq(UUID.nameUUIDFromBytes(("AD_DEBIT:" + campaignId + ":" + windowStart)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                eq(10000L), eq("SAR"));
    }

    @Test
    void theSourceKeyDerivationIsStableAcrossCalls() {
        // «إعادة تشغيل الخصم أو تداخل جدولتين لنافذة واحدة تنتج قيدًا
        // واحدًا ومفتاح مصدر واحد» — the derivation is a pure function of
        // the window identity: same inputs, same UUID, forever.
        UUID campaignId = UUID.randomUUID();
        LocalDate windowStart = LocalDate.parse("2026-09-30");
        UUID first = LedgerService.adDebitSourceKey(campaignId, windowStart);
        UUID second = LedgerService.adDebitSourceKey(campaignId, windowStart);
        org.junit.jupiter.api.Assertions.assertEquals(first, second);
        // A different window is a different key — the next day's charge can
        // never collide with this one's.
        org.junit.jupiter.api.Assertions.assertNotEquals(first,
                LedgerService.adDebitSourceKey(campaignId, windowStart.plusDays(1)));
        org.junit.jupiter.api.Assertions.assertNotEquals(first,
                LedgerService.adDebitSourceKey(UUID.randomUUID(), windowStart));
    }
}
