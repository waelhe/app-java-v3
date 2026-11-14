package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentStatusTest {

    @Test
    void pending_acceptsCompleted() {
        assertDoesNotThrow(() -> PaymentStatus.PENDING.validateTransitionTo(PaymentStatus.COMPLETED));
    }

    @Test
    void pending_acceptsFailed() {
        assertDoesNotThrow(() -> PaymentStatus.PENDING.validateTransitionTo(PaymentStatus.FAILED));
    }

    @Test
    void pending_rejectsRefunded() {
        assertThrows(ConflictException.class, () -> PaymentStatus.PENDING.validateTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void completed_acceptsRefunded() {
        assertDoesNotThrow(() -> PaymentStatus.COMPLETED.validateTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void completed_acceptsPartiallyRefunded() {
        assertDoesNotThrow(() -> PaymentStatus.COMPLETED.validateTransitionTo(PaymentStatus.PARTIALLY_REFUNDED));
    }

    @Test
    void failed_rejectsAnyTransition() {
        assertThrows(ConflictException.class, () -> PaymentStatus.FAILED.validateTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void refunded_rejectsAnyTransition() {
        assertThrows(ConflictException.class, () -> PaymentStatus.REFUNDED.validateTransitionTo(PaymentStatus.PENDING));
    }

    @Test
    void partiallyRefunded_acceptsRefunded() {
        assertDoesNotThrow(() -> PaymentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void partiallyRefunded_acceptsPartiallyRefunded() {
        assertDoesNotThrow(() -> PaymentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentStatus.PARTIALLY_REFUNDED));
    }

    @Test
    void partiallyRefunded_rejectsCompleted() {
        assertThrows(ConflictException.class, () -> PaymentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentStatus.COMPLETED));
    }

    // ------------------------------------------------------------------
    // E2 (adversarial review 2026-11-10) — the terminal-state law, pinned
    // exhaustively: the old single-edge pins (failed_rejectsAnyTransition
    // refusing only FAILED->REFUNDED, refunded_rejectsAnyTransition
    // refusing only REFUNDED->PENDING) could not fail against the
    // regression they name — a developer adding COMPLETED (or
    // PARTIALLY_REFUNDED) to FAILED's allowed set would keep every named
    // test green while a failed payment becomes markable as
    // completed/refunded (the late-PSP-success money double-open).
    // ------------------------------------------------------------------

    /**
     * E2's structural law itself: every terminal row's outgoing set is EMPTY
     * — the late-PSP-success money double-open has no legal front door.
     */
    @Test
    void theTerminalStatesAllowNoOutgoingTransitions_theLateSuccessLaw() {
        org.assertj.core.api.Assertions.assertThat(PaymentStatus.TRANSITIONS.get(PaymentStatus.FAILED))
                .as("FAILED is terminal — a late PSP success must never complete it")
                .isEmpty();
        org.assertj.core.api.Assertions.assertThat(PaymentStatus.TRANSITIONS.get(PaymentStatus.REFUNDED))
                .as("REFUNDED is terminal — no transition reopens refunded money")
                .isEmpty();
    }

    /**
     * FAILED refuses EVERY other status (the enum swept exhaustively —
     * including COMPLETED and PARTIALLY_REFUNDED, the exact double-open
     * the old single-edge pin could miss).
     */
    @Test
    void failed_rejectsEveryOtherStatus_exhaustively() {
        for (PaymentStatus target : PaymentStatus.values()) {
            if (target == PaymentStatus.FAILED) {
                continue;
            }
            assertThrows(ConflictException.class,
                    () -> PaymentStatus.FAILED.validateTransitionTo(target),
                    "FAILED -> " + target + " must be refused");
        }
    }

    /**
     * REFUNDED refuses every other status exhaustively — refunded money
     * never reopens through any transition.
     */
    @Test
    void refunded_rejectsEveryOtherStatus_exhaustively() {
        for (PaymentStatus target : PaymentStatus.values()) {
            if (target == PaymentStatus.REFUNDED) {
                continue;
            }
            assertThrows(ConflictException.class,
                    () -> PaymentStatus.REFUNDED.validateTransitionTo(target),
                    "REFUNDED -> " + target + " must be refused");
        }
    }
}
