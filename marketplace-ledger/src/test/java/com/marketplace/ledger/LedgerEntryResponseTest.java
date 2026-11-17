package com.marketplace.ledger;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The statement presentation contract (L20 + A2, CodeRabbit round 3 — major):
 * every movement the owning provider reads carries a signed amount whose page
 * sum reproduces the balance — CREDITS positive (the payment credit AND the
 * full refund's commission REVERSAL credit), DEBITS negative (the commission,
 * the refund, the ad bill). The pre-fix mapping negated every type but
 * PAYMENT_CREDIT, so the A2 reversal credit — a row the ledger's own
 * {@link LedgerEntryType} classifies as a credit and {@code balance.credit}
 * books positively — displayed NEGATIVE: a client summing the statement page
 * landed a total short of the balance by twice the commission, breaking the
 * record's own documented contract («a client summing the page reproduces the
 * balance»). This test pins the sign of every closed-set member through
 * {@link LedgerEntryResponse#from} so the presentation can never drift from
 * the booking again.
 */
class LedgerEntryResponseTest {

    private static final UUID PROVIDER_ID = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final long AMOUNT_CENTS = 500L;

    @Test
    void everyCreditTypeRidesThePositivePresentationSide() {
        // The two credits of the closed set: the settlement's payment credit
        // and the full refund's commission reversal credit (A2) — both book
        // through balance.credit, both must read positive.
        assertThat(LedgerEntryResponse.from(
                LedgerEntry.paymentCredit(PROVIDER_ID, SOURCE_ID, AMOUNT_CENTS, "SAR")).amountCents())
                .as("PAYMENT_CREDIT is a credit — positive presentation")
                .isEqualTo(AMOUNT_CENTS);
        assertThat(LedgerEntryResponse.from(
                LedgerEntry.commissionReversalCredit(PROVIDER_ID, SOURCE_ID, AMOUNT_CENTS, "SAR")).amountCents())
                .as("COMMISSION_REVERSAL_CREDIT is a credit (CodeRabbit round 3, major) — "
                        + "positive presentation, the page sum must reproduce the balance")
                .isEqualTo(AMOUNT_CENTS);
    }

    @Test
    void everyDebitTypeRidesTheNegativePresentationSide() {
        // The three debits of the closed set: the settlement's commission
        // debit, the full refund's debit, and the ad bill's window charge —
        // all magnitudes in storage, all negative on the statement.
        assertThat(LedgerEntryResponse.from(
                LedgerEntry.commissionDebit(PROVIDER_ID, SOURCE_ID, AMOUNT_CENTS, "SAR")).amountCents())
                .as("COMMISSION_DEBIT is a debit — negative presentation")
                .isEqualTo(-AMOUNT_CENTS);
        assertThat(LedgerEntryResponse.from(
                LedgerEntry.refundDebit(PROVIDER_ID, SOURCE_ID, AMOUNT_CENTS, "SAR")).amountCents())
                .as("REFUND_DEBIT is a debit — negative presentation")
                .isEqualTo(-AMOUNT_CENTS);
        assertThat(LedgerEntryResponse.from(
                LedgerEntry.adDebit(PROVIDER_ID, SOURCE_ID, AMOUNT_CENTS, "SAR")).amountCents())
                .as("AD_DEBIT is a debit — negative presentation")
                .isEqualTo(-AMOUNT_CENTS);
    }

    @Test
    void theRefundedSettlementsStatementPageSumsBackToTheZeroBalance() {
        // The A2 journey as the provider READS it: a 5000.00 settlement with
        // a 10% commission, then the full refund that reverses both the
        // credit and its commission mirror. The statement page sums to the
        // booking's zero balance exactly — the pre-fix reversal sign left the
        // page summing to minus twice the commission instead.
        long priceCents = 5000L;
        long commissionCents = 500L;
        UUID paymentIntentId = UUID.randomUUID();
        var statement = java.util.List.of(
                LedgerEntry.paymentCredit(PROVIDER_ID, paymentIntentId, priceCents, "SAR"),
                LedgerEntry.commissionDebit(PROVIDER_ID,
                        UUID.nameUUIDFromBytes(("commission-" + paymentIntentId).getBytes()),
                        commissionCents, "SAR"),
                LedgerEntry.refundDebit(PROVIDER_ID,
                        UUID.nameUUIDFromBytes(("refund-" + paymentIntentId).getBytes()),
                        priceCents, "SAR"),
                LedgerEntry.commissionReversalCredit(PROVIDER_ID,
                        UUID.nameUUIDFromBytes(("commission-reversal-" + paymentIntentId).getBytes()),
                        commissionCents, "SAR"));

        long pageSum = statement.stream()
                .map(LedgerEntryResponse::from)
                .mapToLong(LedgerEntryResponse::amountCents)
                .sum();

        assertThat(pageSum)
                .as("the four movements of a fully refunded settlement sum to the "
                        + "pre-settlement ZERO balance — the L20 statement contract")
                .isZero();
    }
}
