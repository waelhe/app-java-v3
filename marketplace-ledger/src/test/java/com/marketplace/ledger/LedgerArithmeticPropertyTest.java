package com.marketplace.ledger;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.From;
import net.jqwik.api.Provide;
import net.jqwik.api.Property;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exception #17 proof test (community adoption wave, owner's word
 * 2026-10-07): property-based invariants over the ledger's money
 * arithmetic — {@link ProviderBalance#credit(long)} /
 * {@link ProviderBalance#debit(long)} — with generated inputs
 * (jqwik user guide: {@code @Property} + {@code @ForAll} +
 * {@code @Provide}), complementing the example-based tests the way the
 * adoption rationale stated: a human writes the invariant, the engine
 * hunts for a counterexample across thousands of generated cases.
 *
 * <p>Scope honesty: {@code ProviderBalance} is the exact arithmetic the
 * service layer moves money with ({@code LedgerService.creditFromPayment}
 * / {@code debitFromCommission} / {@code debitFromRefund} all funnel into
 * these two mutators and {@code getAvailableCents()}); the service-level
 * idempotency guarantees (unique source_id backstop) are covered by the
 * existing integration tests and stay out of scope here.
 *
 * <p>Bounds: amounts are generated within [0, 10^14] cents so that any
 * sum of two stays far below {@code Long.MAX_VALUE} — the properties
 * assert <em>exact</em> arithmetic (no drift), not overflow behavior.
 */
class LedgerArithmeticPropertyTest {

    /** 100 trillion cents — generous against real money paths, safe for long. */
    private static final long MAX_CENTS = 100_000_000_000_000L;

    @Provide
    Arbitrary<Long> cents() {
        return Arbitraries.longs().between(0, MAX_CENTS);
    }

    @Provide
    Arbitrary<List<Long>> creditDebitPairs() {
        return Arbitraries.longs().between(0, MAX_CENTS).flatMap(credited ->
                Arbitraries.longs().between(0, credited).map(debited ->
                        List.of(credited, debited)));
    }

    /** The refund mirror: credit X then debit X returns to exactly zero. */
    @Property(tries = 1000)
    void creditThenDebitOfTheSameAmountReturnsToExactlyZero(
            @ForAll @From("cents") long amount) {
        ProviderBalance balance = ProviderBalance.empty(UUID.randomUUID(), "SAR");

        balance.credit(amount);
        balance.debit(amount);

        assertThat(balance.getAvailableCents()).isZero();
    }

    /** Sequential credits equal the single credit of the sum — no drift. */
    @Property(tries = 1000)
    void sequentialCreditsEqualTheCreditOfTheirSum(
            @ForAll @From("cents") long first,
            @ForAll @From("cents") long second) {
        ProviderBalance bySteps = ProviderBalance.empty(UUID.randomUUID(), "USD");

        bySteps.credit(first);
        bySteps.credit(second);

        assertThat(bySteps.getAvailableCents()).isEqualTo(first + second);
    }

    /** Partial refund/debit semantics: the balance is the exact difference. */
    @Property(tries = 1000)
    void debitAfterCreditIsTheExactDifference(
            @ForAll @From("creditDebitPairs") List<Long> pair) {
        long credited = pair.get(0);
        long debited = pair.get(1);
        ProviderBalance balance = ProviderBalance.empty(UUID.randomUUID(), "SAR");

        balance.credit(credited);
        balance.debit(debited);

        assertThat(balance.getAvailableCents())
                .as("credit(%d) then debit(%d)", credited, debited)
                .isEqualTo(credited - debited);
    }

    /** Debits applied one-by-one equal the debit of their sum. */
    @Property(tries = 1000)
    void sequentialDebitsEqualTheDebitOfTheirSum(
            @ForAll @From("creditDebitPairs") List<Long> pair,
            @ForAll @From("cents") long extra) {
        long credited = pair.get(0);
        long debited = pair.get(1);
        ProviderBalance atOnce = ProviderBalance.empty(UUID.randomUUID(), "EUR");
        ProviderBalance bySteps = ProviderBalance.empty(UUID.randomUUID(), "EUR");
        for (ProviderBalance b : List.of(atOnce, bySteps)) {
            b.credit(credited + extra);
        }

        atOnce.debit(debited + extra);
        bySteps.debit(debited);
        bySteps.debit(extra);

        assertThat(bySteps.getAvailableCents())
                .isEqualTo(atOnce.getAvailableCents());
    }
}
