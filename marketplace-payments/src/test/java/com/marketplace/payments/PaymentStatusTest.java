package com.marketplace.payments;

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
        assertThrows(IllegalStateException.class, () -> PaymentStatus.PENDING.validateTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void completed_acceptsRefunded() {
        assertDoesNotThrow(() -> PaymentStatus.COMPLETED.validateTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void failed_rejectsAnyTransition() {
        assertThrows(IllegalStateException.class, () -> PaymentStatus.FAILED.validateTransitionTo(PaymentStatus.REFUNDED));
    }

    @Test
    void refunded_rejectsAnyTransition() {
        assertThrows(IllegalStateException.class, () -> PaymentStatus.REFUNDED.validateTransitionTo(PaymentStatus.PENDING));
    }
}
