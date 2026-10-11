package com.marketplace.payments.spi;

import com.marketplace.payments.PaymentIntent;
import com.marketplace.payments.PaymentsService;
import com.marketplace.shared.api.LoanPaymentPort;
import com.marketplace.shared.api.PaymentIntentDetails;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Stage 8 (ADR-0004): the payments module's implementation of the
 * {@link LoanPaymentPort} cross-module contract — the
 * {@code OrderPaymentAdapter} twin verbatim; no payment internals leak
 * across the boundary.
 */
@Component
public class LoanPaymentAdapter implements LoanPaymentPort {

    private final PaymentsService paymentsService;

    public LoanPaymentAdapter(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    @Override
    public PaymentIntentDetails createForLoan(UUID loanId, UUID borrowerId,
                                              long amountMinor, String currency) {
        PaymentIntent intent = paymentsService.createLoanIntent(loanId, borrowerId, amountMinor, currency);
        return new PaymentIntentDetails(intent.getId(), intent.getBookingId(), intent.getConsumerId(),
                intent.getAdCampaignId(), intent.getStatus().name(), intent.getOrigin(),
                intent.getAmountCents(), intent.getCurrency(), intent.getOrderId(), intent.getLoanId());
    }
}
