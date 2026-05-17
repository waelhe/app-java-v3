package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.*;
import static org.instancio.Select.field;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    @Test
    void duplicateCreditDoesNotCreateNewEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.of(mock(LedgerEntry.class)));
        ProviderBalance balance = ProviderBalance.empty(providerId);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(balance));

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 1000);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
    }

    @Test
    void creditFromPaymentCreatesEntryAndCreditsBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        long amountCents = 5000L;

        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.empty());
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());
        ProviderBalance saved = ProviderBalance.empty(providerId);
        saved.credit(amountCents);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, amountCents);

        assertThat(result.getAvailableCents()).isEqualTo(amountCents);
        verify(entryRepository).save(any(LedgerEntry.class));
        verify(balanceRepository).save(any(ProviderBalance.class));
    }

    @Test
    void getBalanceReturnsEmptyWhenMissing() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);
        UUID providerId = create(UUID.class);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());

        ProviderBalance result = service.getBalance(providerId);

        assertThat(result.getAvailableCents()).isZero();
    }

    @Test
    void getBalanceReturnsExistingBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(3000L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));

        ProviderBalance result = service.getBalance(providerId);

        assertThat(result.getAvailableCents()).isEqualTo(3000L);
    }
}
