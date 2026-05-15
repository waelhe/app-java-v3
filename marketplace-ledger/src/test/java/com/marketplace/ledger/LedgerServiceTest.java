package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private final LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
    private final ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
    private final LedgerService service = new LedgerService(entryRepository, balanceRepository);

    @Test
    void duplicateCreditDoesNotCreateNewEntry() {
        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.of(mock(LedgerEntry.class)));
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(ProviderBalance.empty(providerId)));

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 1000);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
    }

    @Test
    void duplicateCreditReturnsEmptyBalanceWhenNoBalanceExists() {
        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.of(mock(LedgerEntry.class)));
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 1000);

        assertThat(result.getAvailableCents()).isZero();
        assertThat(result.getProviderId()).isEqualTo(providerId);
    }

    @Test
    void creditFromPayment_createsEntryAndCreditsBalance() {
        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.empty());
        when(entryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());
        when(balanceRepository.save(any(ProviderBalance.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 5000);

        assertThat(result.getAvailableCents()).isEqualTo(5000);
        verify(entryRepository).save(any(LedgerEntry.class));
        verify(balanceRepository).save(any(ProviderBalance.class));
    }

    @Test
    void creditFromPayment_updatesExistingBalance() {
        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(2000);
        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.empty());
        when(entryRepository.save(any(LedgerEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));
        when(balanceRepository.save(any(ProviderBalance.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 3000);

        assertThat(result.getAvailableCents()).isEqualTo(5000);
    }

    @Test
    void getBalanceReturnsEmptyWhenMissing() {
        UUID providerId = create(UUID.class);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());

        ProviderBalance result = service.getBalance(providerId);

        assertThat(result.getAvailableCents()).isZero();
    }

    @Test
    void getBalanceReturnsExisting() {
        UUID providerId = create(UUID.class);
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(7500);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));

        ProviderBalance result = service.getBalance(providerId);

        assertThat(result.getAvailableCents()).isEqualTo(7500);
    }

    @Test
    void providerBalance_emptyCreatesZeroBalance() {
        UUID providerId = create(UUID.class);
        ProviderBalance balance = ProviderBalance.empty(providerId);
        assertThat(balance.getProviderId()).isEqualTo(providerId);
        assertThat(balance.getAvailableCents()).isZero();
    }

    @Test
    void providerBalance_creditAddsAmount() {
        ProviderBalance balance = ProviderBalance.empty(create(UUID.class));
        balance.credit(1000);
        assertThat(balance.getAvailableCents()).isEqualTo(1000);
        balance.credit(500);
        assertThat(balance.getAvailableCents()).isEqualTo(1500);
    }
}
