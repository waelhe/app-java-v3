package com.marketplace.payments;

import com.marketplace.shared.api.AdWindowBilledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * W5 (yelp-level plan §5 — the ads & billing wave, G24): the payments
 * half of the billing event — the plan's «وظيفة خصم دورية تُصدر نية دفع».
 *
 * <p>The catalog's billing run freezes the immutable charge row and
 * publishes {@link AdWindowBilledEvent} AFTER_COMMIT; this listener issues
 * the ad bill's payment intent in its own transaction. The idempotency
 * key is the DETERMINISTIC window key
 * {@code ad-debit-{campaignId}-{windowStart}} — a redelivery of the same
 * event (or two overlapping runs producing the same frozen window)
 * replays into {@link PaymentsService#createAdIntent}'s existing-intent
 * return: one window, one payable, however many times the event travels.</p>
 */
@Component
public class AdBillingEventListener {

    private static final Logger log = LoggerFactory.getLogger(AdBillingEventListener.class);

    /** The deterministic key prefix — shared contract with every test that predicts the intent. */
    static final String AD_INTENT_KEY_PREFIX = "ad-debit-";

    private final PaymentsService paymentsService;

    public AdBillingEventListener(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    @ApplicationModuleListener
    public void onWindowBilled(AdWindowBilledEvent event) {
        String idempotencyKey = AD_INTENT_KEY_PREFIX + event.campaignId() + "-" + event.windowStart();
        paymentsService.createAdIntent(event.providerId(), event.campaignId(), event.windowStart(),
                event.amountCents(), event.currency(), idempotencyKey);
        log.info("Ad bill intent issued: provider {} owes {} {} for campaign {} window [{}, {}) — key {}",
                event.providerId(), event.amountCents(), event.currency(), event.campaignId(),
                event.windowStart(), event.windowEnd(), idempotencyKey);
    }
}
