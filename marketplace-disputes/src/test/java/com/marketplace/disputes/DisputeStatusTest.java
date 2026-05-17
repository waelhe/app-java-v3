package com.marketplace.disputes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DisputeStatusTest {

    @Test
    void openToResolved_valid() {
        assertDoesNotThrow(() -> DisputeStatus.OPEN.validateTransitionTo(DisputeStatus.RESOLVED));
    }

    @Test
    void resolvedToOpen_invalid() {
        assertThrows(IllegalStateException.class,
                () -> DisputeStatus.RESOLVED.validateTransitionTo(DisputeStatus.OPEN));
    }

    @Test
    void openToOpen_invalid() {
        assertThrows(IllegalStateException.class,
                () -> DisputeStatus.OPEN.validateTransitionTo(DisputeStatus.OPEN));
    }

    @Test
    void resolvedToResolved_invalid() {
        assertThrows(IllegalStateException.class,
                () -> DisputeStatus.RESOLVED.validateTransitionTo(DisputeStatus.RESOLVED));
    }
}
