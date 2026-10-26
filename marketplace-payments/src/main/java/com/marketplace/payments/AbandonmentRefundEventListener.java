package com.marketplace.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Row 15 (agent findings 2026-10-26T11:43 export) — the abandonment's durable
 * recovery edge: a capture that won the race against a cancellation-pending
 * abandonment settled the books (the provider is the money truth), and the
 * cancellation's own decision — "this money returns to the buyer" — executes
 * here, late but exactly once. The house pattern verbatim
 * ({@link BookingCancelledEventListener}): {@code @ApplicationModuleListener}
 * rides the Modulith Event Publication Registry, so a crash between the
 * settlement's commit and this refund resubmits the publication instead of
 * losing the buyer's money; the abandonment-scoped PSP idempotency key keeps
 * every resubmission a replay of the SAME remote refund.
 */
@Component
public class AbandonmentRefundEventListener {

    private static final Logger log = LoggerFactory.getLogger(AbandonmentRefundEventListener.class);

    private final PaymentsService paymentsService;

    public AbandonmentRefundEventListener(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    @ApplicationModuleListener
    public void onPaymentAbandonmentCaptured(PaymentAbandonmentCapturedEvent event) {
        paymentsService.refundAbandonedCapture(event.paymentIntentId());
        log.info("Abandonment refund executed for captured intent: {}", event.paymentIntentId());
    }
}
