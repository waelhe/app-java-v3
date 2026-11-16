package com.marketplace.payments;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W7 (the origin-by-state matrix's own measured gap G7 — «نجاح PSP متأخر على
 * نية FAILED»): the LATE-SUCCESS law, measured on the state machine the
 * settlement's {@code confirm} rides ({@code markSucceeded} →
 * {@code validateTransitionTo} — the machine's own guarded edge, the
 * settlement shell delegates directly).
 *
 * <p>The measured behavior (the honest current state, not a wish): a FAILED
 * intent is TERMINAL — the late {@code payment_intent.succeeded} webhook's
 * settlement call answers the machine's own {@code ConflictException}, so
 * the money never double-books and the webhook is re-delivered by the
 * provider until the inbox's permanent-failure path records it as FAILED
 * with the reason (the dispatch's compensating-delete contract). The
 * BOOKING retry model's honest exit (a new intent is the recovery, never a
 * terminal-state resurrection) is pinned here exactly as ADR-0013 and the
 * matrix document it.
 */
class LateSuccessOnFailedIntentTest {

    @Test
    void aFailedIntentAnswersTheLateSuccessWithTheMachinesOwnConflict() {
        PaymentIntent intent = PaymentIntent.createForLoan(
                UUID.randomUUID(), UUID.randomUUID(), 2500L, "SAR", "late-success-key", null);
        intent.markProcessing();
        intent.markFailed();
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.FAILED);

        // The late PSP success lands AFTER the failure verdict — the
        // machine's terminal guard answers the honest 409 (never a
        // resurrection, never a double-book).
        assertThatThrownBy(intent::markSucceeded)
                .isInstanceOf(com.marketplace.shared.api.ConflictException.class)
                .hasMessageContaining("FAILED")
                .hasMessageContaining("SUCCEEDED");

        // The verdict is intact after the refused late success.
        assertThat(intent.getStatus()).isEqualTo(PaymentIntentStatus.FAILED);
    }

    @Test
    void theRecoveryIsANewIntentNotAResurrection_theBookingRetryModel() {
        // The matrix's G1/G7 pair: the one-COLLECTIBLE-at-a-time law (V81)
        // is what makes the retry model safe — the failed intent stays in
        // history as the attempt it was, and the recovery is a fresh
        // COLLECTIBLE intent.
        PaymentIntent first = PaymentIntent.create(
                UUID.randomUUID(), UUID.randomUUID(), 1200L, "SAR", "retry-key-1", null);
        first.markProcessing();
        first.markFailed();
        assertThat(first.getStatus()).isEqualTo(PaymentIntentStatus.FAILED);

        PaymentIntent retry = PaymentIntent.create(
                UUID.randomUUID(), UUID.randomUUID(), 1200L, "SAR", "retry-key-2", null);
        retry.markProcessing();
        retry.markSucceeded();
        assertThat(retry.getStatus()).isEqualTo(PaymentIntentStatus.SUCCEEDED);
        // The failed attempt is untouched by its successor's success.
        assertThat(first.getStatus()).isEqualTo(PaymentIntentStatus.FAILED);
    }
}
