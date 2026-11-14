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

    // ------------------------------------------------------------------
    // E2 (adversarial review 2026-11-10) — the terminal-state law, pinned
    // exhaustively: the old single-edge pins (succeeded_rejectsAnyTransition
    // refusing only SUCCEEDED->CANCELLED, cancelled_rejectsAnyTransition
    // refusing only CANCELLED->PROCESSING, refunded_rejectsAnyTransition
    // refusing only REFUNDED->SUCCEEDED) could not fail against the
    // regression they name — widening any terminal row's allowed set keeps
    // every named test green while dead money reopens.
    // ------------------------------------------------------------------

    /**
     * E2's structural law itself: every terminal row's outgoing set is EMPTY
     * — a late PSP success (or any other actor) can never complete failed,
     * cancelled, or refunded money through the state machine's front door.
     */
    @Test
    void theTerminalStatesAllowNoOutgoingTransitions_theLateSuccessLaw() {
        org.assertj.core.api.Assertions.assertThat(PaymentIntentStatus.TRANSITIONS.get(PaymentIntentStatus.FAILED))
                .as("FAILED is terminal — a late PSP success must never complete it")
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(PaymentIntentStatus.TRANSITIONS.get(PaymentIntentStatus.CANCELLED))
                .as("CANCELLED is terminal — no transition reopens cancelled money")
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(PaymentIntentStatus.TRANSITIONS.get(PaymentIntentStatus.REFUNDED))
                .as("REFUNDED is terminal — no transition reopens refunded money")
                .isEmpty();
    }

    /**
     * FAILED refuses EVERY other status (the enum swept exhaustively — any
     * future status addition lands under this pin automatically).
     */
    @Test
    void failed_rejectsEveryOtherStatus_exhaustively() {
        for (PaymentIntentStatus target : PaymentIntentStatus.values()) {
            if (target == PaymentIntentStatus.FAILED) {
                continue;
            }
            assertThrows(ConflictException.class,
                    () -> PaymentIntentStatus.FAILED.validateTransitionTo(target),
                    "FAILED -> " + target + " must be refused");
        }
    }

    /**
     * CANCELLED refuses every other status exhaustively — cancelled money
     * never reopens through any transition.
     */
    @Test
    void cancelled_rejectsEveryOtherStatus_exhaustively() {
        for (PaymentIntentStatus target : PaymentIntentStatus.values()) {
            if (target == PaymentIntentStatus.CANCELLED) {
                continue;
            }
            assertThrows(ConflictException.class,
                    () -> PaymentIntentStatus.CANCELLED.validateTransitionTo(target),
                    "CANCELLED -> " + target + " must be refused");
        }
    }

    /**
     * REFUNDED refuses every other status exhaustively — refunded money
     * never reopens through any transition.
     */
    @Test
    void refunded_rejectsEveryOtherStatus_exhaustively() {
        for (PaymentIntentStatus target : PaymentIntentStatus.values()) {
            if (target == PaymentIntentStatus.REFUNDED) {
                continue;
            }
            assertThrows(ConflictException.class,
                    () -> PaymentIntentStatus.REFUNDED.validateTransitionTo(target),
                    "REFUNDED -> " + target + " must be refused");
        }
    }
}
