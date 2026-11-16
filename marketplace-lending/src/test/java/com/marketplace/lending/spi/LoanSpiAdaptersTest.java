package com.marketplace.lending.spi;

import com.marketplace.lending.Loan;
import com.marketplace.lending.LoanRepository;
import com.marketplace.shared.api.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Stage 8 (ADR-0004) + ADR-0009 — the cross-module seams' own contracts:
 * the ledger's LOAN settlement reads the owner through LoanOwnerPort, and
 * the disputes module's loan-dispute surfaces gate on the party facts
 * through LoanPartyProvider. The unknown id answers the honest 404 —
 * existence itself is not public information across the module boundary.
 */
@ExtendWith(MockitoExtension.class)
class LoanSpiAdaptersTest {

    @Mock
    private LoanRepository loanRepository;

    private Loan loan() {
        return Loan.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-04T10:00:00Z"),
                1500L, "SAR", 0L);
    }

    @Test
    void theOwnerAdapterAnswersTheLoansOwnOwner() {
        LoanOwnerAdapter adapter = new LoanOwnerAdapter(loanRepository);
        Loan loan = loan();
        UUID loanId = loan.getId();
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan));

        assertThat(adapter.ownerOf(loanId)).isEqualTo(loan.getOwnerId());
    }

    @Test
    void theOwnerAdapterAnswersTheHonest404OnAnUnknownLoan() {
        LoanOwnerAdapter adapter = new LoanOwnerAdapter(loanRepository);
        UUID unknown = UUID.randomUUID();
        when(loanRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.ownerOf(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void thePartyProviderCarriesTheBorrowerAndTheOwner() {
        LoanPartyProviderAdapter adapter = new LoanPartyProviderAdapter(loanRepository);
        Loan loan = loan();
        UUID loanId = loan.getId();
        when(loanRepository.findById(loanId)).thenReturn(Optional.of(loan));

        var info = adapter.getLoanInfo(loanId);
        assertThat(info.loanId()).isEqualTo(loanId);
        assertThat(info.borrowerId()).isEqualTo(loan.getBorrowerId());
        assertThat(info.ownerId()).isEqualTo(loan.getOwnerId());
    }

    @Test
    void thePartyProviderAnswersTheHonest404OnAnUnknownLoan() {
        LoanPartyProviderAdapter adapter = new LoanPartyProviderAdapter(loanRepository);
        UUID unknown = UUID.randomUUID();
        when(loanRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.getLoanInfo(unknown))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
