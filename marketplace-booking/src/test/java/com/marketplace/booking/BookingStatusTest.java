package com.marketplace.booking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BookingStatusTest {

    @Test
    void pendingCanTransitionToConfirmed() {
        assertDoesNotThrow(() -> BookingStatus.PENDING.validateTransitionTo(BookingStatus.CONFIRMED));
    }

    @Test
    void pendingCanTransitionToCancelled() {
        assertDoesNotThrow(() -> BookingStatus.PENDING.validateTransitionTo(BookingStatus.CANCELLED));
    }

    @Test
    void pendingCannotTransitionToCompleted() {
        assertThrows(IllegalStateException.class,
                () -> BookingStatus.PENDING.validateTransitionTo(BookingStatus.COMPLETED));
    }

    @Test
    void confirmedCanTransitionToCompleted() {
        assertDoesNotThrow(() -> BookingStatus.CONFIRMED.validateTransitionTo(BookingStatus.COMPLETED));
    }

    @Test
    void confirmedCanTransitionToCancelled() {
        assertDoesNotThrow(() -> BookingStatus.CONFIRMED.validateTransitionTo(BookingStatus.CANCELLED));
    }

    @Test
    void completedCannotTransitionToAny() {
        assertThrows(IllegalStateException.class,
                () -> BookingStatus.COMPLETED.validateTransitionTo(BookingStatus.PENDING));
        assertThrows(IllegalStateException.class,
                () -> BookingStatus.COMPLETED.validateTransitionTo(BookingStatus.CONFIRMED));
        assertThrows(IllegalStateException.class,
                () -> BookingStatus.COMPLETED.validateTransitionTo(BookingStatus.CANCELLED));
    }

    @Test
    void cancelledCannotTransitionToAny() {
        assertThrows(IllegalStateException.class,
                () -> BookingStatus.CANCELLED.validateTransitionTo(BookingStatus.PENDING));
        assertThrows(IllegalStateException.class,
                () -> BookingStatus.CANCELLED.validateTransitionTo(BookingStatus.CONFIRMED));
        assertThrows(IllegalStateException.class,
                () -> BookingStatus.CANCELLED.validateTransitionTo(BookingStatus.COMPLETED));
    }
}
