package com.marketplace.disputes.spi;

import com.marketplace.disputes.DisputeRepository;
import com.marketplace.disputes.DisputeStatus;
import com.marketplace.shared.api.DisputeLookupPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Row 15 (agent findings 2026-10-25): the disputes module's
 * implementation of the {@link DisputeLookupPort} cross-module contract —
 * the OPEN-state facts the lending module's freeze/release listener gates
 * on. The {@code LoanPartyProviderAdapter} twin in the reverse direction:
 * the data owner answers, the consumer never re-derives (no second
 * dispute-state engine anywhere).
 *
 * <p><b>Query shape:</b> Spring Data JPA derived existence queries
 * (the ADR-0009 governing reference — "derived queries, state-guarded
 * idempotent writes") against the module's own repository; read-only
 * transactions, one statement each, zero projection beyond a boolean.
 */
@Component
public class DisputeLookupAdapter implements DisputeLookupPort {

    private final DisputeRepository disputeRepository;

    public DisputeLookupAdapter(DisputeRepository disputeRepository) {
        this.disputeRepository = disputeRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isDisputeOpen(UUID disputeId) {
        return disputeRepository.existsByIdAndStatus(disputeId, DisputeStatus.OPEN);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasOpenDisputeForLoan(UUID loanId, UUID excludingDisputeId) {
        return disputeRepository.existsByLoanIdAndStatusAndIdNot(loanId, DisputeStatus.OPEN, excludingDisputeId);
    }
}
