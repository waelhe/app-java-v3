package com.marketplace.lending.spi;

import com.marketplace.lending.LoanRepository;
import com.marketplace.shared.api.LoanOwnerPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Stage 8 (ADR-0004): the lending module's implementation of the
 * {@link LoanOwnerPort} cross-module contract — the single fact the
 * ledger's LOAN-origin settlement needs (who to credit the fee). The
 * {@code OrderSellerAdapter} twin verbatim.
 */
@Component
public class LoanOwnerAdapter implements LoanOwnerPort {

    private final LoanRepository loanRepository;

    public LoanOwnerAdapter(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UUID ownerOf(UUID loanId) {
        return loanRepository.findById(loanId)
                .map(loan -> loan.getOwnerId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan", loanId));
    }
}
