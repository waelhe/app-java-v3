package com.marketplace.ledger;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.LoanOwnerPort;
import com.marketplace.shared.api.OrderSellerPort;
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
    private final OrderSellerPort orderSellerPort;
    private final LoanOwnerPort loanOwnerPort;
    private final double commissionRate;

    public LedgerPaymentEventListener(LedgerService ledgerService,
                                       PaymentIntentLookupPort paymentIntentLookupPort,
                                       BookingParticipantProvider bookingParticipantProvider,
                                       OrderSellerPort orderSellerPort,
                                       LoanOwnerPort loanOwnerPort,
                                       @Value("${app.commission.rate:0.10}") double commissionRate) {
        this.ledgerService = ledgerService;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.orderSellerPort = orderSellerPort;
        this.loanOwnerPort = loanOwnerPort;
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
        // Stage 6 (ADR-0002): the ORDER settlement — the seller credit plus
        // the announced commission debit (the same announced rate the
        // booking path books; the plan's «عمولة معلنة» rule honored by the
        // EXISTING configuration, never a second rate). The seller rides
        // the OrderSellerPort seam — the ledger never imports the orders
        // module (the BookingParticipantProvider twin verbatim).
        if (intent.isOrderOrigin()) {
            java.util.UUID sellerId = orderSellerPort.sellerOf(intent.orderId());
            ledgerService.creditFromPayment(sellerId, intent.paymentIntentId(),
                    intent.amountCents(), intent.currency());
            long commissionCents = BigDecimal.valueOf(intent.amountCents())
                    .multiply(BigDecimal.valueOf(commissionRate))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            ledgerService.debitFromCommission(sellerId, intent.paymentIntentId(), commissionCents, intent.currency());
            log.info("Ledger processed: credited {} {} to seller {}, debited {} {} as commission — "
                            + "the order settlement (intent {})",
                    intent.amountCents(), intent.currency(), sellerId, commissionCents,
                    intent.currency(), intent.paymentIntentId());
            return;
        }
        // Stage 8 (ADR-0004): the LOAN settlement — the owner's fee credit
        // plus the SAME announced commission debit (the order branch's twin
        // one port hop away; no second rate, no escrow vocabulary).
        if (intent.isLoanOrigin()) {
            java.util.UUID ownerId = loanOwnerPort.ownerOf(intent.loanId());
            ledgerService.creditFromPayment(ownerId, intent.paymentIntentId(),
                    intent.amountCents(), intent.currency());
            long commissionCents = BigDecimal.valueOf(intent.amountCents())
                    .multiply(BigDecimal.valueOf(commissionRate))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValue();
            ledgerService.debitFromCommission(ownerId, intent.paymentIntentId(), commissionCents, intent.currency());
            log.info("Ledger processed: credited {} {} to owner {}, debited {} {} as commission — "
                            + "the loan-fee settlement (intent {})",
                    intent.amountCents(), intent.currency(), ownerId, commissionCents,
                    intent.currency(), intent.paymentIntentId());
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
        // Stage 6 (ADR-0002): the ORDER refund mirrors its settlement credit
        // exactly — same seller, same amount, same currency — so the debit
        // lands on the balance the credit moved (the booking branch's R9
        // rule, one port hop away).
        if (intent.isOrderOrigin()) {
            java.util.UUID sellerId = orderSellerPort.sellerOf(intent.orderId());
            ledgerService.debitFromRefund(sellerId, intent.paymentIntentId(),
                    intent.amountCents(), intent.currency());
            log.info("Ledger processed: debited {} {} from seller {} — the order refund "
                            + "mirrors its settlement credit (intent {})",
                    intent.amountCents(), intent.currency(), sellerId, intent.paymentIntentId());
            return;
        }
        // Stage 8 (ADR-0004): the LOAN refund mirrors its settlement credit
        // exactly (the order branch's twin).
        if (intent.isLoanOrigin()) {
            java.util.UUID ownerId = loanOwnerPort.ownerOf(intent.loanId());
            ledgerService.debitFromRefund(ownerId, intent.paymentIntentId(),
                    intent.amountCents(), intent.currency());
            log.info("Ledger processed: debited {} {} from owner {} — the loan-fee refund "
                            + "mirrors its settlement credit (intent {})",
                    intent.amountCents(), intent.currency(), ownerId, intent.paymentIntentId());
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
