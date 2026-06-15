package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    void partiallyRefunded_rejectsAnyTransition() {
        assertThrows(ConflictException.class, () -> PaymentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentStatus.COMPLETED));
    }
}
