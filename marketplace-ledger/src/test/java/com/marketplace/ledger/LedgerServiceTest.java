package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    /**
     * B2: a negative amount on the payment-credit path must fail fast —
     * VALIDATION (400) via BadRequestException — before any entry or
     * balance write happens.
     */
    @Test
    void creditFromPaymentRejectsNegativeAmount() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.creditFromPayment(providerId, paymentIntentId, -1L))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a zero amount on the payment-credit path is a no-op — the current
     * balance is returned and neither an entry nor a balance write occurs.
     */
    @Test
    void creditFromPaymentZeroAmountReturnsBalanceWithoutEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        ProviderBalance balance = ProviderBalance.empty(providerId);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(balance));

        ProviderBalance result = service.creditFromPayment(providerId, paymentIntentId, 0L);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a negative amount on the commission-debit path must fail fast —
     * VALIDATION (400) via BadRequestException — before any entry or
     * balance write happens.
     */
    @Test
    void debitFromCommissionRejectsNegativeAmount() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.debitFromCommission(providerId, paymentIntentId, -1L))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a zero amount on the commission-debit path is a no-op — the
     * current balance is returned and neither an entry nor a balance
     * write occurs.
     */
    @Test
    void debitFromCommissionZeroAmountReturnsBalanceWithoutEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        ProviderBalance balance = ProviderBalance.empty(providerId);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 0L);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a negative amount on the refund-debit path must fail fast —
     * VALIDATION (400) via BadRequestException — before any entry or
     * balance write happens.
     */
    @Test
    void debitFromRefundRejectsNegativeAmount() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.debitFromRefund(providerId, paymentIntentId, -1L))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a zero amount on the refund-debit path is a no-op — the current
     * balance is returned and neither an entry nor a balance write occurs.
     */
    @Test
    void debitFromRefundZeroAmountReturnsBalanceWithoutEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        ProviderBalance balance = ProviderBalance.empty(providerId);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromRefund(providerId, paymentIntentId, 0L);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

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

    @Test
    void debitFromCommissionCreatesEntryAndDebitsBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID expectedSourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        when(entryRepository.findBySourceId(expectedSourceId)).thenReturn(Optional.empty());
        when(balanceRepository.findById(providerId)).thenReturn(Optional.empty());
        ProviderBalance saved = ProviderBalance.empty(providerId);
        saved.credit(5000L);
        saved.debit(1000L);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 1000L);

        assertThat(result.getAvailableCents()).isEqualTo(4000L);
        verify(entryRepository).save(any(LedgerEntry.class));
        verify(balanceRepository).save(any(ProviderBalance.class));
    }

    @Test
    void debitFromCommissionSkipsOnDuplicate() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID expectedSourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        when(entryRepository.findBySourceId(expectedSourceId)).thenReturn(Optional.of(mock(LedgerEntry.class)));
        ProviderBalance balance = ProviderBalance.empty(providerId);
        balance.credit(5000L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 1000L);

        assertThat(result.getAvailableCents()).isEqualTo(5000L);
        verify(entryRepository, never()).save(any());
    }

    @Test
    void debitFromCommissionSubtractsFromPositiveBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID expectedSourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        when(entryRepository.findBySourceId(expectedSourceId)).thenReturn(Optional.empty());
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(3000L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));
        ProviderBalance saved = ProviderBalance.empty(providerId);
        saved.credit(3000L);
        saved.debit(2500L);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 2500L);

        assertThat(result.getAvailableCents()).isEqualTo(500L);
        verify(entryRepository).save(any(LedgerEntry.class));
    }

    @Test
    void debitFromCommissionAllowsNegativeBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID expectedSourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        when(entryRepository.findBySourceId(expectedSourceId)).thenReturn(Optional.empty());
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(1000L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));
        ProviderBalance saved = ProviderBalance.empty(providerId);
        saved.credit(1000L);
        saved.debit(5000L);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 5000L);

        assertThat(result.getAvailableCents()).isEqualTo(-4000L);
    }

    @Test
    void getBalanceForOwnerReturnsSameAsGetBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        ProviderBalance existing = ProviderBalance.empty(providerId);
        existing.credit(4500L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(existing));

        ProviderBalance result = service.getBalanceForOwner(providerId);

        assertThat(result.getAvailableCents()).isEqualTo(4500L);
    }

    @Test
    void getStatementForOwnerDelegatesToRepositoryPage() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        var expected = new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(LedgerEntry.paymentCredit(providerId, UUID.randomUUID(), 5000L)));
        when(entryRepository.findByProviderIdOrderByCreatedAtDescIdDesc(providerId, pageable)).thenReturn(expected);

        var result = service.getStatementForOwner(providerId, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEntryType()).isEqualTo(LedgerEntryType.PAYMENT_CREDIT);
        assertThat(result.getContent().get(0).getAmountCents()).isEqualTo(5000L);
        verify(entryRepository).findByProviderIdOrderByCreatedAtDescIdDesc(providerId, pageable);
    }

    @Test
    void debitFromRefundMirrorsTheOriginalCreditAndDebitsBalance() {
        // L24 acceptance 2: the refund debit is the credit's mirror — the
        // same amount, a derived refund-<intentId> source id, a debit.
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID expectedSourceId = UUID.nameUUIDFromBytes(("refund-" + paymentIntentId.toString()).getBytes());
        when(entryRepository.findBySourceId(expectedSourceId)).thenReturn(Optional.empty());
        when(entryRepository.save(any(LedgerEntry.class))).thenAnswer(i -> i.getArgument(0));
        ProviderBalance credited = ProviderBalance.empty(providerId);
        credited.credit(5000L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(credited));
        when(balanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ProviderBalance result = service.debitFromRefund(providerId, paymentIntentId, 5000L);

        assertThat(result.getAvailableCents()).isZero();
        var captor = org.mockito.ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryType()).isEqualTo(LedgerEntryType.REFUND_DEBIT);
        assertThat(captor.getValue().getSourceId()).isEqualTo(expectedSourceId);
        assertThat(captor.getValue().getAmountCents()).isEqualTo(5000L);
    }

    @Test
    void debitFromRefundSkipsOnDuplicate() {
        // L24 acceptance 1 (the ledger belt): the derived source id makes
        // replays no-ops — no second entry, no second debit.
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID expectedSourceId = UUID.nameUUIDFromBytes(("refund-" + paymentIntentId.toString()).getBytes());
        when(entryRepository.findBySourceId(expectedSourceId)).thenReturn(Optional.of(mock(LedgerEntry.class)));
        ProviderBalance balance = ProviderBalance.empty(providerId);
        balance.credit(5000L);
        balance.debit(5000L);
        when(balanceRepository.findById(providerId)).thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromRefund(providerId, paymentIntentId, 5000L);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }
}
