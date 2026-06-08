package com.marketplace.booking;

import com.marketplace.shared.api.ConflictException;
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
        assertThrows(ConflictException.class,
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
        assertThrows(ConflictException.class,
                () -> BookingStatus.COMPLETED.validateTransitionTo(BookingStatus.PENDING));
        assertThrows(ConflictException.class,
                () -> BookingStatus.COMPLETED.validateTransitionTo(BookingStatus.CONFIRMED));
        assertThrows(ConflictException.class,
                () -> BookingStatus.COMPLETED.validateTransitionTo(BookingStatus.CANCELLED));
    }

    @Test
    void cancelledCannotTransitionToAny() {
        assertThrows(ConflictException.class,
                () -> BookingStatus.CANCELLED.validateTransitionTo(BookingStatus.PENDING));
        assertThrows(ConflictException.class,
                () -> BookingStatus.CANCELLED.validateTransitionTo(BookingStatus.CONFIRMED));
        assertThrows(ConflictException.class,
                () -> BookingStatus.CANCELLED.validateTransitionTo(BookingStatus.COMPLETED));
    }
}
