package com.marketplace.ledger;

import com.marketplace.shared.api.AdWindowBilledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the ledger's
 * half of the billing event — the plan's «وتقيد الدفتر بنوع مُصدَر جديد
 * AD_DEBIT».
 *
 * <p>The catalog's billing run freezes the immutable charge row and
 * publishes {@link AdWindowBilledEvent} AFTER_COMMIT; this listener books
 * the {@code AD_DEBIT} entry in its own transaction. Idempotency is
 * structural, not procedural: the source key is the DETERMINISTIC
 * {@code AD_DEBIT:{campaignId}:{windowStart}} UUID
 * ({@link LedgerService#adDebitSourceKey}) — the V19 {@code source_id
 * UNIQUE} index rejects any replay of the same window, exactly the plan's
 * acceptance («إعادة تشغيل الخصم أو تداخل جدولتين لنافذة واحدة تنتج
 * قيدًا واحدًا ومفتاح مصدر واحد»).</p>
 */
@Component
public class LedgerAdBillingEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerAdBillingEventListener.class);

    private final LedgerService ledgerService;

    public LedgerAdBillingEventListener(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @ApplicationModuleListener
    public void onWindowBilled(AdWindowBilledEvent event) {
        var sourceKey = LedgerService.adDebitSourceKey(event.campaignId(), event.windowStart());
        ledgerService.debitFromAds(event.providerId(), sourceKey, event.amountCents(), event.currency());
        log.info("Ledger processed: debited {} {} from provider {} — the ad window "
                        + "[{}, {}) of campaign {} ({} impressions, {} clicks)",
                event.amountCents(), event.currency(), event.providerId(),
                event.windowStart(), event.windowEnd(), event.campaignId(),
                event.impressions(), event.clicks());
    }
}
