package com.marketplace.payments;

import com.marketplace.shared.api.ConflictException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    void partiallyRefunded_rejectsAnyTransition() {
        assertThrows(ConflictException.class, () -> PaymentIntentStatus.PARTIALLY_REFUNDED.validateTransitionTo(PaymentIntentStatus.SUCCEEDED));
    }
}
