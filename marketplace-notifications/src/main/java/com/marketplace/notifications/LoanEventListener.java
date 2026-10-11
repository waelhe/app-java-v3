package com.marketplace.notifications;

import com.marketplace.shared.api.LoanApprovedEvent;
import com.marketplace.shared.api.LoanCancelledEvent;
import com.marketplace.shared.api.LoanRequestedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Stage 8 (ADR-0004): the lending workflow's late-lander — the
 * requested/approved/cancelled legs write the recipient's notification
 * rows (the order legs' listener twin; the payload carries the truth, the
 * listeners derive nothing).
 */
@Component
public class LoanEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoanEventListener.class);

    private final NotificationService notificationService;

    public LoanEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    public void onLoanRequested(LoanRequestedEvent event) {
        notificationService.onLoanRequested(event.loanId(), event.borrowerId(), event.ownerId());
        log.info("Notification sent for loan requested: {}", event.loanId());
    }

    @ApplicationModuleListener
    public void onLoanApproved(LoanApprovedEvent event) {
        notificationService.onLoanApproved(event.loanId(), event.borrowerId(), event.ownerId());
        log.info("Notification sent for loan approved: {}", event.loanId());
    }

    @ApplicationModuleListener
    public void onLoanCancelled(LoanCancelledEvent event) {
        notificationService.onLoanCancelled(event.loanId(), event.borrowerId());
        log.info("Notification sent for loan cancelled: {}", event.loanId());
    }
}
