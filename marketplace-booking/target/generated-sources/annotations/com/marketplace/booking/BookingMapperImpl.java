package com.marketplace.booking;

import java.time.Instant;
import java.util.UUID;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-10-08T00:35:15+0000",
    comments = "version: 1.6.3, compiler: javac, environment: Java 25.0.4.1 (Eclipse Adoptium)"
)
@Component
public class BookingMapperImpl implements BookingMapper {

    @Override
    public BookingResponse toResponse(Booking booking) {
        if ( booking == null ) {
            return null;
        }

        UUID id = null;
        UUID listingId = null;
        String status = null;
        Instant startsAt = null;
        Instant endsAt = null;
        String notes = null;
        Instant createdAt = null;
        Instant updatedAt = null;

        id = booking.getId();
        listingId = booking.getListingId();
        if ( booking.getStatus() != null ) {
            status = booking.getStatus().name();
        }
        startsAt = booking.getStartsAt();
        endsAt = booking.getEndsAt();
        notes = booking.getNotes();
        createdAt = booking.getCreatedAt();
        updatedAt = booking.getUpdatedAt();

        BookingResponse bookingResponse = new BookingResponse( id, listingId, status, startsAt, endsAt, notes, createdAt, updatedAt );

        return bookingResponse;
    }
}
