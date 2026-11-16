package com.marketplace.ledger.spi;

import com.marketplace.ledger.LedgerEntry;
import com.marketplace.ledger.LedgerEntryRepository;
import com.marketplace.ledger.ProviderBalance;
import com.marketplace.ledger.ProviderBalanceRepository;
import com.marketplace.shared.api.CurrencyAmount;
import com.marketplace.shared.api.LedgerAllocationPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.instancio.Instancio.create;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The unified finance-operator read model's LEDGER side (payments plan
 * Phase 1, PR #620): the adapter's own unit pins — the deterministic
 * derivation keys (the writer paths' derivations, byte-identical), the
 * honest empty allocation for an uncollected intent, the balances of
 * exactly the providers the found entries name, and the net's sign
 * convention (credit positive, debits negative, per currency).
 */
class LedgerAllocationAdapterTest {

    private static final String SAR = "SAR";

    @Test
    void theDeterministicKeysMatchTheWriterPathsDerivations() {
        UUID intentId = create(UUID.class);
        // LedgerService.debitFromCommission's derivation, verbatim.
        assertThat(LedgerAllocationAdapter.commissionSourceKey(intentId))
                .isEqualTo(UUID.nameUUIDFromBytes(
                        ("commission-" + intentId.toString()).getBytes()));
        // LedgerService.debitFromRefund's derivation, verbatim.
        assertThat(LedgerAllocationAdapter.refundSourceKey(intentId))
                .isEqualTo(UUID.nameUUIDFromBytes(
                        ("refund-" + intentId.toString()).getBytes()));
    }

    @Test
    void anUncollectedIntentAnswersTheHonestEmptyAllocation() {
        LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balances = mock(ProviderBalanceRepository.class);
        when(entries.findBySourceId(any())).thenReturn(Optional.empty());
        LedgerAllocationAdapter adapter = new LedgerAllocationAdapter(entries, balances);

        LedgerAllocationPort.LedgerAllocation allocation =
                adapter.findByPaymentIntentId(create(UUID.class));

        assertThat(allocation.entries()).isEmpty();
        assertThat(allocation.providerBalances()).isEmpty();
        assertThat(allocation.netAllocation()).isEmpty();
    }

    @Test
    void aSettledOrderAllocationCarriesCreditCommissionBalanceAndNet() {
        LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balances = mock(ProviderBalanceRepository.class);
        UUID sellerId = create(UUID.class);
        UUID intentId = create(UUID.class);
        UUID commissionKey = UUID.nameUUIDFromBytes(
                ("commission-" + intentId.toString()).getBytes());

        LedgerEntry credit = LedgerEntry.paymentCredit(sellerId, intentId, 3000L, SAR);
        LedgerEntry commission = LedgerEntry.commissionDebit(sellerId, commissionKey, 300L, SAR);
        when(entries.findBySourceId(intentId)).thenReturn(Optional.of(credit));
        when(entries.findBySourceId(commissionKey)).thenReturn(Optional.of(commission));
        when(entries.findBySourceId(UUID.nameUUIDFromBytes(
                ("refund-" + intentId.toString()).getBytes()))).thenReturn(Optional.empty());
        ProviderBalance sellerBalance = ProviderBalance.empty(sellerId, SAR);
        sellerBalance.credit(2700L);
        when(balances.findByIdProviderIdOrderByIdCurrencyAsc(sellerId))
                .thenReturn(List.of(sellerBalance));

        LedgerAllocationAdapter adapter = new LedgerAllocationAdapter(entries, balances);
        LedgerAllocationPort.LedgerAllocation allocation = adapter.findByPaymentIntentId(intentId);

        assertThat(allocation.entries()).hasSize(2);
        assertThat(allocation.entries())
                .extracting(LedgerAllocationPort.LedgerAllocationEntry::entryType)
                .containsExactlyInAnyOrder("PAYMENT_CREDIT", "COMMISSION_DEBIT");
        assertThat(allocation.providerBalances()).hasSize(1);
        assertThat(allocation.providerBalances().get(0).providerId()).isEqualTo(sellerId);
        assertThat(allocation.netAllocation())
                .containsExactly(new CurrencyAmount(SAR, 2700L));
    }

    @Test
    void aRefundedAllocationSubtractsFromTheNet() {
        LedgerEntryRepository entries = mock(LedgerEntryRepository.class);
        ProviderBalanceRepository balances = mock(ProviderBalanceRepository.class);
        UUID sellerId = create(UUID.class);
        UUID intentId = create(UUID.class);
        UUID commissionKey = UUID.nameUUIDFromBytes(
                ("commission-" + intentId.toString()).getBytes());
        UUID refundKey = UUID.nameUUIDFromBytes(
                ("refund-" + intentId.toString()).getBytes());

        when(entries.findBySourceId(intentId))
                .thenReturn(Optional.of(LedgerEntry.paymentCredit(sellerId, intentId, 3000L, SAR)));
        when(entries.findBySourceId(commissionKey))
                .thenReturn(Optional.of(LedgerEntry.commissionDebit(sellerId, commissionKey, 300L, SAR)));
        when(entries.findBySourceId(refundKey))
                .thenReturn(Optional.of(LedgerEntry.refundDebit(sellerId, refundKey, 3000L, SAR)));
        when(balances.findByIdProviderIdOrderByIdCurrencyAsc(sellerId))
                .thenReturn(List.of());

        LedgerAllocationAdapter adapter = new LedgerAllocationAdapter(entries, balances);
        LedgerAllocationPort.LedgerAllocation allocation = adapter.findByPaymentIntentId(intentId);

        // credit 3000 - commission 300 - refund 3000 = -300: the sign
        // convention surfaces the reversed position honestly.
        assertThat(allocation.netAllocation())
                .containsExactly(new CurrencyAmount(SAR, -300L));
        assertThat(allocation.entries()).hasSize(3);
    }
}
