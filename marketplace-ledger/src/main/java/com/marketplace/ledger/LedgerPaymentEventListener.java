package com.marketplace.ledger;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentDetails;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class LedgerPaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerPaymentEventListener.class);

    private final LedgerService ledgerService;
    private final PaymentIntentLookupPort paymentIntentLookupPort;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final double commissionRate;

    public LedgerPaymentEventListener(LedgerService ledgerService,
                                       PaymentIntentLookupPort paymentIntentLookupPort,
                                       BookingParticipantProvider bookingParticipantProvider,
                                       @Value("${app.commission.rate:0.10}") double commissionRate) {
        this.ledgerService = ledgerService;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.commissionRate = commissionRate;
    }

    @ApplicationModuleListener
    public void onPaymentCompleted(PaymentStateChangedEvent event) {
        if ("COMPLETED".equals(event.state())) {
            paymentIntentLookupPort.findById(event.paymentIntentId())
                    .ifPresent(this::processLedgerEntry);
        } else if ("REFUNDED".equals(event.state())) {
            // L24 — the full refund's debit mirrors the original credit:
            // the same booking's priceCents the credit used, so the
            // provider's balance reflects the dispute's (or any full
            // refund's) decision. PARTIALLY_REFUNDED stays out — the
            // roadmap's dispute decision is the full-refund shape and
            // partial PSP refunds are the payments side's own bookkeeping.
            paymentIntentLookupPort.findById(event.paymentIntentId())
                    .ifPresent(this::processRefundDebit);
        }
    }

    private void processLedgerEntry(PaymentIntentDetails intent) {
        // W5 (yelp-level plan §5 — G24): the ad bill's settlement — the
        // raw amount credit, no commission, no booking lookup. The payer
        // of an AD-origin intent IS the provider (consumer_id carries the
        // provider's user id), and the money pair rides the widened
        // PaymentIntentDetails so the ledger never needs a second lookup.
        // The credit's source id is the intent id itself — the same
        // BOOKING-path derivation, so a settled ad bill restores exactly
        // the balance the AD_DEBIT consumed (the campaign's currency on
        // both sides).
        if (intent.isAdOrigin()) {
            ledgerService.creditFromPayment(intent.consumerId(), intent.paymentIntentId(),
                    intent.amountCents(), intent.currency());
            log.info("Ledger processed: credited {} {} to provider {} — the ad bill's "
                            + "settlement (intent {}, no commission)",
                    intent.amountCents(), intent.currency(), intent.consumerId(), intent.paymentIntentId());
            return;
        }
        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.bookingId());
        long priceCents = bookingInfo.priceCents();
        // R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's
        // currency): the booking's currency rides EVERY money call — the
        // field was there all along (BookingInfo's own validated member;
        // the pre-fix listener was measured to ignore it), and the balances
        // are keyed (provider, currency) now, so the credit, the commission
        // and the refund all move the payment's own currency.
        String currency = bookingInfo.currency();
        ledgerService.creditFromPayment(bookingInfo.providerId(), intent.paymentIntentId(), priceCents, currency);
        long commissionCents = BigDecimal.valueOf(priceCents)
                .multiply(BigDecimal.valueOf(commissionRate))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
        ledgerService.debitFromCommission(bookingInfo.providerId(), intent.paymentIntentId(), commissionCents, currency);
        log.info("Ledger processed: credited {} {} to provider {}, debited {} {} as commission",
                priceCents, currency, bookingInfo.providerId(), commissionCents, currency);
    }

    private void processRefundDebit(PaymentIntentDetails intent) {
        // W5 (CodeRabbit round 1, adopted): an ad bill's refund MIRRORS its
        // settlement credit exactly the way a booking's refund mirrors its
        // own — the refund surface (admin refundPayment / a future PSP
        // webhook) reaches AD-origin intents as surely as BOOKING ones
        // (processIntent and confirmIntent are origin-blind), so skipping
        // the debit would leave the provider's balance holding a settled
        // credit the refund reversed. The debitFromRefund source key
        // {@code refund-<intentId>} mirrors once, structurally.
        if (intent.isAdOrigin()) {
            ledgerService.debitFromRefund(intent.consumerId(), intent.paymentIntentId(),
                    intent.amountCents(), intent.currency());
            log.info("Ledger processed: debited {} {} from provider {} — the ad bill's "
                            + "refund mirrors its settlement credit (intent {})",
                    intent.amountCents(), intent.currency(), intent.consumerId(), intent.paymentIntentId());
            return;
        }
        BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.bookingId());
        long priceCents = bookingInfo.priceCents();
        // R9: the refund mirrors the ORIGINAL credit — same amount, same
        // currency — so the debit lands on the balance the credit moved.
        String currency = bookingInfo.currency();
        ledgerService.debitFromRefund(bookingInfo.providerId(), intent.paymentIntentId(), priceCents, currency);
        log.info("Ledger processed: debited {} {} from provider {} — the refund mirrors the original credit",
                priceCents, currency, bookingInfo.providerId());
    }
}
