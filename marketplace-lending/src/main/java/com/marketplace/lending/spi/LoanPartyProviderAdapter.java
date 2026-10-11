package com.marketplace.lending.spi;

import com.marketplace.lending.LoanRepository;
import com.marketplace.shared.api.LoanInfo;
import com.marketplace.shared.api.LoanPartyProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * ADR-0009 (plan D-09 closure): the lending module's implementation of the
 * {@link LoanPartyProvider} cross-module contract — the party facts the
 * disputes module's loan-dispute surfaces gate on (the
 * {@code BookingParticipantProviderAdapter} twin verbatim, and the concrete
 * mechanism ADR-0004 named "the DisputeOpenPort seam").
 */
@Component
public class LoanPartyProviderAdapter implements LoanPartyProvider {

    private final LoanRepository loanRepository;

    public LoanPartyProviderAdapter(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public LoanInfo getLoanInfo(UUID loanId) {
        return loanRepository.findById(loanId)
                .map(loan -> new LoanInfo(loan.getId(), loan.getBorrowerId(), loan.getOwnerId()))
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
    }
}
