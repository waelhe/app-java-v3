package com.marketplace.shared.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): one billed
 * window of one ad campaign, published AFTER_COMMIT by the catalog's
 * billing run the instant the immutable charge row is frozen.
 *
 * <p>The event is the ONLY thing that crosses the module boundary on the
 * money side — the Modulith law (R3: «تواصل الوحدات عبر SPI أو أحداث
 * AFTER_COMMIT حصراً»). Two listeners consume it, each in its own
 * transaction, each idempotent by its own structural backstop:
 * <ul>
 *   <li><b>payments</b> — {@code createAdIntent}: the plan's «تُصدر نية
 *       دفع» — a payment intent whose payer is the provider, replayed
 *       safely by the {@code ad-debit-{campaignId}-{windowStart}}
 *       idempotency key (the column's own UNIQUE);</li>
 *   <li><b>ledger</b> — {@code debitFromAds}: the plan's «تقيد الدفتر
 *       بنوع مُصدَر جديد AD_DEBIT» — the deterministic source key
 *       {@code UUID.nameUUIDFromBytes("AD_DEBIT:{campaignId}:{windowStart}")}
 *       the V19 {@code source_id UNIQUE} index rejects on any replay
 *       («إعادة المحاولة أو تداخل الجدولة يستحيلان معًا»).</li>
 * </ul>
 *
 * <p>The amounts are the FROZEN charge — byte-identical to the
 * {@code ad_billing_charges} row the transaction just wrote — so the
 * ledger entry balances the deducted budget penny-for-penny («القيد
 * يوازن الميزانية المخصومة فلسًا بفلس») no matter how many times the
 * event is redelivered.
 *
 * @param campaignId    the billed campaign
 * @param providerId    the advertiser — the payer of the issued intent
 * @param windowStart   the window's inclusive first UTC day (the
 *                      deterministic key's second component)
 * @param windowEnd     the window's exclusive end UTC day
 * @param impressions   the frozen impression count the charge billed
 * @param clicks        the frozen click count the charge billed
 * @param amountCents   the frozen charge amount (capped at the remaining
 *                      budget — the campaign's end-by-exhaustion law)
 * @param currency      the charge's ISO 4217 code (the campaign's own)
 */
public record AdWindowBilledEvent(
        UUID campaignId,
        UUID providerId,
        LocalDate windowStart,
        LocalDate windowEnd,
        long impressions,
        long clicks,
        long amountCents,
        String currency
) {
}
