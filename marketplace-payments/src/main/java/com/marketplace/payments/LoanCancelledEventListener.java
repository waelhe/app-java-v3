package com.marketplace.payments;

import com.marketplace.shared.api.LoanCancelledEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stage 8 (ADR-0004): the loan cancellation's money edge — the
 * {@code OrderCancelledEventListener} twin verbatim (the ONE refund
 * contract, the listener's own AFTER_COMMIT unit).
 */
@Component
public class LoanCancelledEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoanCancelledEventListener.class);

    private final PaymentsService paymentsService;

    public LoanCancelledEventListener(PaymentsService paymentsService) {
        this.paymentsService = paymentsService;
    }

    @ApplicationModuleListener
    public void onLoanCancelled(LoanCancelledEvent event) {
        paymentsService.autoRefundByLoan(event.loanId());
        log.info("Auto-refund triggered for loan: {}", event.loanId());
    }
}
