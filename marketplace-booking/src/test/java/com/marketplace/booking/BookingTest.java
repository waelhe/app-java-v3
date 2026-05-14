package com.marketplace.booking;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingTest {

    @Test
    void createSetsPendingStatus() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, "notes");
        assertNotNull(booking.getId());
        assertEquals(BookingStatus.PENDING, booking.getStatus());
    }

    @Test
    void confirmChangesFromPendingToConfirmed() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        booking.confirm();
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }

    @Test
    void confirmThrowsWhenNotPending() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        booking.confirm();
        assertThrows(IllegalStateException.class, booking::confirm);
    }

    @Test
    void completeChangesFromConfirmedToCompleted() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        booking.confirm();
        booking.complete();
        assertEquals(BookingStatus.COMPLETED, booking.getStatus());
    }

    @Test
    void completeThrowsWhenNotConfirmed() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        assertThrows(IllegalStateException.class, booking::complete);
    }

    @Test
    void cancelChangesToCancelled() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        booking.cancel();
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    }

    @Test
    void cancelThrowsWhenCompleted() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        booking.confirm();
        booking.complete();
        assertThrows(IllegalStateException.class, booking::cancel);
    }

    @Test
    void cancelFromPendingWorks() {
        var booking = Booking.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5000L, null);
        booking.cancel();
        assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    }

    @Test
    void constructorSetsFields() {
        var id = UUID.randomUUID();
        var consumerId = UUID.randomUUID();
        var providerId = UUID.randomUUID();
        var listingId = UUID.randomUUID();
        var booking = new Booking(id, consumerId, providerId, listingId, 3000L, "test notes");

        assertEquals(id, booking.getId());
        assertEquals(consumerId, booking.getConsumerId());
        assertEquals(providerId, booking.getProviderId());
        assertEquals(listingId, booking.getListingId());
        assertEquals(3000L, booking.getPriceCents());
        assertEquals("SAR", booking.getCurrency());
        assertEquals("test notes", booking.getNotes());
        assertEquals(BookingStatus.PENDING, booking.getStatus());
    }
}
