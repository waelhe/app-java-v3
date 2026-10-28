package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentIntentStatusTest {

    @Test
    void created_acceptsProcessing() {
        assertDoesNotThrow(() -> PaymentIntentStatus.CREATED.validateTransitionTo(PaymentIntentStatus.PROCESSING));
    }

    @Test
    void created_acceptsCancelled() {
        assertDoesNotThrow(() -> PaymentIntentStatus.CREATED.validateTransitionTo(PaymentIntentStatus.CANCELLED));
    }

    @Test
    void created_rejectsSucceeded() {
        assertThrows(ConflictException.class, () -> PaymentIntentStatus.CREATED.validateTransitionTo(PaymentIntentStatus.SUCCEEDED));
    }

    @Test
    void processing_acceptsSucceeded() {
        assertDoesNotThrow(() -> PaymentIntentStatus.PROCESSING.validateTransitionTo(PaymentIntentStatus.SUCCEEDED));
    }

    @Test
    void processing_acceptsFailed() {
        assertDoesNotThrow(() -> PaymentIntentStatus.PROCESSING.validateTransitionTo(PaymentIntentStatus.FAILED));
    }

    @Test
    void succeeded_rejectsAnyTransition() {
        assertThrows(ConflictException.class, () -> PaymentIntentStatus.SUCCEEDED.validateTransitionTo(PaymentIntentStatus.CANCELLED));
    }

    @Test
    void cancelled_rejectsAnyTransition() {
        assertThrows(ConflictException.class, () -> PaymentIntentStatus.CANCELLED.validateTransitionTo(PaymentIntentStatus.PROCESSING));
    }

    @Test
    void succeeded_acceptsRefunded() {
        assertDoesNotThrow(() -> PaymentIntentStatus.SUCCEEDED.validateTransitionTo(PaymentIntentStatus.REFUNDED));
    }

    @Test
    void succeeded_acceptsPartiallyRefunded() {
        assertDoesNotThrow(() -> PaymentIntentStatus.SUCCEEDED.validateTransitionTo(PaymentIntentStatus.PARTIALLY_REFUNDED));
    }

    @Test
    void refunded_rejectsAnyTransition() {
        assertThrows(ConflictException.class, () -> PaymentIntentStatus.REFUNDED.validateTransitionTo(PaymentIntentStatus.SUCCEEDED));
    }

    @Test
    void partiallyRefunded_acceptsRefunded() {
        assertDoesNotThrow(() -> PaymentIntentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentIntentStatus.REFUNDED));
    }

    @Test
    void partiallyRefunded_acceptsPartiallyRefunded() {
        assertDoesNotThrow(() -> PaymentIntentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentIntentStatus.PARTIALLY_REFUNDED));
    }

    @Test
    void partiallyRefunded_rejectsSucceeded() {
        assertThrows(ConflictException.class, () -> PaymentIntentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentIntentStatus.SUCCEEDED));
    }

    // ------------------------------------------------------------------
    // Row 15 (agent findings 2026-10-26T11:43 export) — the abandonment's
    // own state: CANCELLATION_PENDING is born from PROCESSING only, and the
    // PROVIDER's own truth resolves it (succeeded settles the books, its own
    // failure/cancellation resolves to FAILED). It is NOT collectible, NOT
    // retryable — the money may still land, so no new attempt may open.
    // ------------------------------------------------------------------

    @Test
    void processing_acceptsCancellationPending() {
        assertDoesNotThrow(() -> PaymentIntentStatus.PROCESSING.validateTransitionTo(PaymentIntentStatus.CANCELLATION_PENDING));
    }

    @Test
    void cancellationPending_acceptsSucceeded_theCaptureThatWonTheRace() {
        assertDoesNotThrow(() -> PaymentIntentStatus.CANCELLATION_PENDING.validateTransitionTo(PaymentIntentStatus.SUCCEEDED));
    }

    @Test
    void cancellationPending_acceptsFailed_theProviderOwnResolution() {
        assertDoesNotThrow(() -> PaymentIntentStatus.CANCELLATION_PENDING.validateTransitionTo(PaymentIntentStatus.FAILED));
    }

    @Test
    void cancellationPending_rejectsCancelled_theAbandonmentIsNotTheUnpaidCancelPath() {
        assertThrows(ConflictException.class, () -> PaymentIntentStatus.CANCELLATION_PENDING.validateTransitionTo(PaymentIntentStatus.CANCELLED));
    }

    @Test
    void cancellationPending_isNeitherCollectibleNorRetryable() {
        assertFalse(PaymentIntentStatus.COLLECTIBLE.contains(PaymentIntentStatus.CANCELLATION_PENDING));
        assertFalse(PaymentIntentStatus.RETRYABLE.contains(PaymentIntentStatus.CANCELLATION_PENDING));
        assertFalse(PaymentIntentStatus.COLLECTED.contains(PaymentIntentStatus.CANCELLATION_PENDING));
    }
}
