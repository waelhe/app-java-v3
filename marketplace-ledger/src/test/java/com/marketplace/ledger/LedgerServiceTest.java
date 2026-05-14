package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    @Test
    void creditFromPayment_createsEntryAndCreditsBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();

        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.empty());
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());
        when(balanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 1000);

        assertThat(result.getAvailableCents()).isEqualTo(1000);
        verify(entryRepository).save(any(LedgerEntry.class));
        verify(balanceRepository).save(any(ProviderBalance.class));
    }

    @Test
    void duplicateCreditDoesNotCreateNewEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.of(mock(LedgerEntry.class)));
        ProviderBalance balance = ProviderBalance.empty(providerId);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(balance));

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 1000);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
    }

    @Test
    void getBalanceReturnsEmptyWhenMissing() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);
        UUID providerId = UUID.randomUUID();
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());

        ProviderBalance result = service.getBalance(providerId);

        assertThat(result.getAvailableCents()).isZero();
    }

    @Test
    void getBalanceReturnsExisting() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);
        UUID providerId = UUID.randomUUID();
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(5000);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));

        ProviderBalance result = service.getBalance(providerId);

        assertThat(result.getAvailableCents()).isEqualTo(5000);
    }
}
