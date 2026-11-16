package com.marketplace.shared.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unified finance-operator read model (payments plan Phase 1, PR #620):
 * the shared allocation contracts the workbench composes answer for their
 * value semantics here — the same pin family the delivery vertical's
 * {@code DeliveryContractsTest} carries. The pins that matter: the honest
 * EMPTY allocation for an uncollected intent (three empty lists, never
 * null — the operator surface reads them without guards), the entry
 * carrier's shape (the ledger's own posting vocabulary rides as a word —
 * the module's enum never crosses the seam), the balance fact's shape,
 * and the composed record's value equality.
 */
class LedgerAllocationContractsTest {

    @Test
    void ledgerAllocation_empty_answersThreeEmptyListsNeverNull() {
        LedgerAllocationPort.LedgerAllocation empty = LedgerAllocationPort.LedgerAllocation.empty();

        assertThat(empty.entries()).isNotNull().isEmpty();
        assertThat(empty.providerBalances()).isNotNull().isEmpty();
        assertThat(empty.netAllocation()).isNotNull().isEmpty();
    }

    @Test
    void ledgerAllocationEntry_carriesThePostingFacts() {
        UUID providerId = UUID.randomUUID();
        Instant postedAt = Instant.parse("2026-10-17T09:15:00Z");
        LedgerAllocationPort.LedgerAllocationEntry entry = new LedgerAllocationPort.LedgerAllocationEntry(
                "COMMISSION_DEBIT", 300L, "SAR", providerId, postedAt);

        assertThat(entry.entryType()).isEqualTo("COMMISSION_DEBIT");
        assertThat(entry.amountCents()).isEqualTo(300L);
        assertThat(entry.currency()).isEqualTo("SAR");
        assertThat(entry.providerId()).isEqualTo(providerId);
        assertThat(entry.postedAt()).isEqualTo(postedAt);
    }

    @Test
    void ledgerAllocationEntry_recordSemantics_hashEqualsAndToString() {
        LedgerAllocationPort.LedgerAllocationEntry a = new LedgerAllocationPort.LedgerAllocationEntry(
                "PAYMENT_CREDIT", 3000L, "SAR", UUID.randomUUID(), Instant.EPOCH);
        LedgerAllocationPort.LedgerAllocationEntry b = new LedgerAllocationPort.LedgerAllocationEntry(
                a.entryType(), a.amountCents(), a.currency(), a.providerId(), a.postedAt());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString())
                .contains("PAYMENT_CREDIT")
                .contains("3000")
                .contains("SAR");
    }

    @Test
    void providerBalanceFact_carriesTheCurrentPosition() {
        UUID providerId = UUID.randomUUID();
        LedgerAllocationPort.ProviderBalanceFact fact = new LedgerAllocationPort.ProviderBalanceFact(
                providerId, "SAR", 2700L);

        assertThat(fact.providerId()).isEqualTo(providerId);
        assertThat(fact.currency()).isEqualTo("SAR");
        assertThat(fact.balanceCents()).isEqualTo(2700L);
    }

    @Test
    void ledgerAllocation_carriesTheComposedAnswer() {
        UUID sellerId = UUID.randomUUID();
        Instant postedAt = Instant.EPOCH;
        LedgerAllocationPort.LedgerAllocationEntry credit =
                new LedgerAllocationPort.LedgerAllocationEntry(
                        "PAYMENT_CREDIT", 3000L, "SAR", sellerId, postedAt);
        LedgerAllocationPort.LedgerAllocationEntry commission =
                new LedgerAllocationPort.LedgerAllocationEntry(
                        "COMMISSION_DEBIT", 300L, "SAR", sellerId, postedAt);
        LedgerAllocationPort.ProviderBalanceFact balance =
                new LedgerAllocationPort.ProviderBalanceFact(sellerId, "SAR", 2700L);
        CurrencyAmount net = new CurrencyAmount("SAR", 2700L);

        LedgerAllocationPort.LedgerAllocation allocation = new LedgerAllocationPort.LedgerAllocation(
                List.of(credit, commission), List.of(balance), List.of(net));

        assertThat(allocation.entries()).containsExactly(credit, commission);
        assertThat(allocation.providerBalances()).containsExactly(balance);
        assertThat(allocation.netAllocation()).containsExactly(net);
    }

    @Test
    void ledgerAllocation_recordSemantics_hashEqualsAndToString() {
        LedgerAllocationPort.LedgerAllocation a = LedgerAllocationPort.LedgerAllocation.empty();
        LedgerAllocationPort.LedgerAllocation b = new LedgerAllocationPort.LedgerAllocation(
                a.entries(), a.providerBalances(), a.netAllocation());

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a.toString()).contains("entries").contains("providerBalances");
    }
}
