package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.instancio.Instancio.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LedgerServiceTest {

    private static final String SAR = "SAR";
    private static final String USD = "USD";

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

        assertThatThrownBy(() -> service.creditFromPayment(providerId, paymentIntentId, -1L, SAR))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a zero amount on the payment-credit path is a no-op — the current
     * balance of the TOUCHED currency is returned and neither an entry nor a
     * balance write occurs.
     */
    @Test
    void creditFromPaymentZeroAmountReturnsBalanceWithoutEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        ProviderBalance balance = ProviderBalance.empty(providerId, USD);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, USD)))
                .thenReturn(Optional.of(balance));

        ProviderBalanceResponse result = service.creditFromPayment(providerId, paymentIntentId, 0L, USD);

        assertThat(result.availableCents()).isZero();
        assertThat(result.currency()).isEqualTo(USD);
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

        assertThatThrownBy(() -> service.debitFromCommission(providerId, paymentIntentId, -1L, SAR))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a zero amount on the commission-debit path is a no-op — the
     * current balance of the touched currency is returned and neither an
     * entry nor a balance write occurs.
     */
    @Test
    void debitFromCommissionZeroAmountReturnsBalanceWithoutEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        ProviderBalance balance = ProviderBalance.empty(providerId, USD);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, USD)))
                .thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 0L, USD);

        assertThat(result.getAvailableCents()).isZero();
        assertThat(result.getCurrency()).isEqualTo(USD);
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

        assertThatThrownBy(() -> service.debitFromRefund(providerId, paymentIntentId, -1L, SAR))
                .isInstanceOf(com.marketplace.shared.api.BadRequestException.class);
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * B2: a zero amount on the refund-debit path is a no-op — the current
     * balance of the touched currency is returned and neither an entry nor a
     * balance write occurs.
     */
    @Test
    void debitFromRefundZeroAmountReturnsBalanceWithoutEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        ProviderBalance balance = ProviderBalance.empty(providerId, SAR);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromRefund(providerId, paymentIntentId, 0L, SAR);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }

    /**
     * A repeated payment-intent credit is a no-op: the source-id lookup
     * short-circuits so the balance is credited exactly once.
     */
    @Test
    void duplicateCreditDoesNotCreateNewEntry() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = create(UUID.class);
        UUID paymentIntentId = create(UUID.class);
        when(entryRepository.findBySourceId(paymentIntentId)).thenReturn(Optional.of(mock(LedgerEntry.class)));
        ProviderBalance balance = ProviderBalance.empty(providerId, SAR);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.of(balance));

        ProviderBalanceResponse result = service.creditFromPayment(providerId, paymentIntentId, 1000, SAR);

        assertThat(result.availableCents()).isZero();
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
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.empty());
        ProviderBalance saved = ProviderBalance.empty(providerId, SAR);
        saved.credit(amountCents);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalanceResponse result = service.creditFromPayment(providerId, paymentIntentId, amountCents, SAR);

        assertThat(result.availableCents()).isEqualTo(amountCents);
        assertThat(result.currency()).isEqualTo(SAR);
        verify(entryRepository).save(any(LedgerEntry.class));
        verify(balanceRepository).save(any(ProviderBalance.class));
    }

    /**
     * R9 — the review document's own scenario, at the service layer: two
     * payments of DIFFERENT currencies credit two SEPARATE balances. The
     * pre-fix single-key balance summed them into one number (the measured
     * defect); the (provider, currency) key keeps each currency's money its
     * own row.
     */
    @Test
    void r9_creditsOfDifferentCurrenciesLandOnSeparateBalances() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID sarIntent = UUID.randomUUID();
        UUID usdIntent = UUID.randomUUID();

        when(entryRepository.findBySourceId(any(UUID.class))).thenReturn(Optional.empty());
        when(balanceRepository.findById(any(ProviderBalance.ProviderBalanceId.class)))
                .thenReturn(Optional.empty());
        when(balanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.creditFromPayment(providerId, sarIntent, 10_000, SAR);
        service.creditFromPayment(providerId, usdIntent, 10_000, USD);

        // two distinct balance rows were persisted — one per currency
        var savedCaptor = org.mockito.ArgumentCaptor.forClass(ProviderBalance.class);
        verify(balanceRepository, times(2)).save(savedCaptor.capture());
        var byCurrency = new java.util.HashMap<String, Long>();
        for (ProviderBalance saved : savedCaptor.getAllValues()) {
            byCurrency.merge(saved.getCurrency(), saved.getAvailableCents(), Long::sum);
        }
        assertThat(byCurrency)
                .as("each currency's credit lands on its own balance, never a mixed sum")
                .containsOnly(java.util.Map.entry(SAR, 10_000L), java.util.Map.entry(USD, 10_000L));
    }

    @Test
    void getBalancesReturnsEmptyListWhenNothingHeld() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);
        UUID providerId = create(UUID.class);
        when(balanceRepository.findByProviderIdOrderByCurrencyAsc(providerId)).thenReturn(List.of());

        List<ProviderBalanceResponse> result = service.getBalances(providerId);

        assertThat(result).isEmpty();
    }

    @Test
    void getBalancesReturnsOneRowPerCurrencyHeld() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        ProviderBalance sar = ProviderBalance.empty(providerId, SAR);
        sar.credit(3000L);
        ProviderBalance usd = ProviderBalance.empty(providerId, USD);
        usd.credit(2500L);
        when(balanceRepository.findByProviderIdOrderByCurrencyAsc(providerId))
                .thenReturn(List.of(sar, usd));

        List<ProviderBalanceResponse> result = service.getBalances(providerId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).currency()).isEqualTo(SAR);
        assertThat(result.get(0).availableCents()).isEqualTo(3000L);
        assertThat(result.get(1).currency()).isEqualTo(USD);
        assertThat(result.get(1).availableCents()).isEqualTo(2500L);
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
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.empty());
        ProviderBalance saved = ProviderBalance.empty(providerId, SAR);
        saved.credit(5000L);
        saved.debit(1000L);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 1000L, SAR);

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
        ProviderBalance balance = ProviderBalance.empty(providerId, SAR);
        balance.credit(5000L);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 1000L, SAR);

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
        ProviderBalance existing = ProviderBalance.empty(providerId, SAR);
        existing.credit(3000L);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.of(existing));
        ProviderBalance saved = ProviderBalance.empty(providerId, SAR);
        saved.credit(3000L);
        saved.debit(2500L);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 2500L, SAR);

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
        ProviderBalance existing = ProviderBalance.empty(providerId, SAR);
        existing.credit(1000L);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.of(existing));
        ProviderBalance saved = ProviderBalance.empty(providerId, SAR);
        saved.credit(1000L);
        saved.debit(5000L);
        when(balanceRepository.save(any())).thenReturn(saved);

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 5000L, SAR);

        assertThat(result.getAvailableCents()).isEqualTo(-4000L);
    }

    /**
     * R9: the commission of a USD payment moves the USD balance — never the
     * SAR row of the same provider (the cross-currency leak the single-key
     * model made possible).
     */
    @Test
    void debitFromCommissionMovesThePaymentOwnCurrencyBalance() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        UUID paymentIntentId = UUID.randomUUID();
        UUID expectedSourceId = UUID.nameUUIDFromBytes(("commission-" + paymentIntentId.toString()).getBytes());
        when(entryRepository.findBySourceId(expectedSourceId)).thenReturn(Optional.empty());
        ProviderBalance usdBalance = ProviderBalance.empty(providerId, USD);
        usdBalance.credit(10_000L);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, USD)))
                .thenReturn(Optional.of(usdBalance));
        when(balanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProviderBalance result = service.debitFromCommission(providerId, paymentIntentId, 1000L, USD);

        assertThat(result.getCurrency()).isEqualTo(USD);
        assertThat(result.getAvailableCents()).isEqualTo(9000L);
        // the SAR row was never touched by the USD commission
        verify(balanceRepository, never()).findById(new ProviderBalance.ProviderBalanceId(providerId, SAR));
    }

    @Test
    void getBalancesForOwnerReturnsSameAsGetBalances() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        ProviderBalance existing = ProviderBalance.empty(providerId, SAR);
        existing.credit(4500L);
        when(balanceRepository.findByProviderIdOrderByCurrencyAsc(providerId)).thenReturn(List.of(existing));

        List<ProviderBalanceResponse> result = service.getBalancesForOwner(providerId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).availableCents()).isEqualTo(4500L);
    }

    @Test
    void getStatementForOwnerDelegatesToRepositoryPage() {
        LedgerEntryRepository entryRepository = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balanceRepository = mock(ProviderBalanceRepository.class);
        LedgerService service = new LedgerService(entryRepository, balanceRepository);

        UUID providerId = UUID.randomUUID();
        var pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        var expected = new org.springframework.data.domain.PageImpl<>(
                java.util.List.of(LedgerEntry.paymentCredit(providerId, UUID.randomUUID(), 5000L, SAR)));
        when(entryRepository.findByProviderIdOrderByCreatedAtDescIdDesc(providerId, pageable)).thenReturn(expected);

        var result = service.getStatementForOwner(providerId, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getEntryType()).isEqualTo(LedgerEntryType.PAYMENT_CREDIT);
        assertThat(result.getContent().get(0).getAmountCents()).isEqualTo(5000L);
        assertThat(result.getContent().get(0).getCurrency()).isEqualTo(SAR);
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
        ProviderBalance credited = ProviderBalance.empty(providerId, USD);
        credited.credit(5000L);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, USD)))
                .thenReturn(Optional.of(credited));
        when(balanceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ProviderBalance result = service.debitFromRefund(providerId, paymentIntentId, 5000L, USD);

        assertThat(result.getAvailableCents()).isZero();
        var captor = org.mockito.ArgumentCaptor.forClass(LedgerEntry.class);
        verify(entryRepository).save(captor.capture());
        assertThat(captor.getValue().getEntryType()).isEqualTo(LedgerEntryType.REFUND_DEBIT);
        assertThat(captor.getValue().getSourceId()).isEqualTo(expectedSourceId);
        assertThat(captor.getValue().getAmountCents()).isEqualTo(5000L);
        assertThat(captor.getValue().getCurrency()).isEqualTo(USD);
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
        ProviderBalance balance = ProviderBalance.empty(providerId, SAR);
        balance.credit(5000L);
        balance.debit(5000L);
        when(balanceRepository.findById(new ProviderBalance.ProviderBalanceId(providerId, SAR)))
                .thenReturn(Optional.of(balance));

        ProviderBalance result = service.debitFromRefund(providerId, paymentIntentId, 5000L, SAR);

        assertThat(result.getAvailableCents()).isZero();
        verify(entryRepository, never()).save(any());
        verify(balanceRepository, never()).save(any());
    }
}
