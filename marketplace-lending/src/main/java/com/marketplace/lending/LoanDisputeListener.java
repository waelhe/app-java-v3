package com.marketplace.lending;

import com.marketplace.shared.api.DisputeOpenedEvent;
import com.marketplace.shared.api.DisputeResolvedEvent;
import com.marketplace.shared.api.DisputeSubject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * ADR-0009 (plan D-09 closure — the ADR-0004 dispute deferral): the loan's
 * dispute cycle listener. The disputes module publishes the shared events;
 * this listener is the loan machine's ONLY dispute writer:
 *
 * <ul>
 *   <li>{@code DisputeOpenedEvent} (LOAN subject) — the freeze: the ACTIVE
 *       loan enters DISPUTED (the ADR-0004 gate verbatim) and the freeze
 *       fact is published to both parties;</li>
 *   <li>{@code DisputeResolvedEvent} (LOAN subject) — the release: a
 *       REFUND_CONSUMER decision terminates the loan (whose
 *       {@code LoanCancelledEvent} drives the payments module's listener —
 *       the ONE refund contract), any other decision resumes the ACTIVE
 *       edge.</li>
 * </ul>
 *
 * <p>The AFTER_COMMIT/REQUIRES_NEW module-listener semantics (the Modulith
 * events contract) keep the machine's writes in their own transaction — a
 * consumer failure never rolls the dispute row back (the same discipline
 * the payments/notifications listeners ride).
 */
@Component
public class LoanDisputeListener {

    private static final Logger log = LoggerFactory.getLogger(LoanDisputeListener.class);

    private final LendingService lendingService;

    public LoanDisputeListener(LendingService lendingService) {
        this.lendingService = lendingService;
    }

    @ApplicationModuleListener
    public void onDisputeOpened(DisputeOpenedEvent event) {
        if (event.subjectType() != DisputeSubject.LOAN) {
            return;
        }
        lendingService.markDisputedFromDispute(event.loanId());
        log.info("Loan dispute freeze applied: loan {}", event.loanId());
    }

    @ApplicationModuleListener
    public void onDisputeResolved(DisputeResolvedEvent event) {
        if (event.subjectType() != DisputeSubject.LOAN) {
            return;
        }
        lendingService.resolveDisputeFromDisputes(event.loanId(), event.resolution());
        log.info("Loan dispute resolution applied: loan {} -> {}", event.loanId(), event.resolution());
    }
}
