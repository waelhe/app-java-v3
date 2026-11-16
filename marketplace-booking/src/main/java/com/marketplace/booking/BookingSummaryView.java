package com.marketplace.booking;

import java.time.Instant;
import java.util.UUID;

/**
 * Closed projection for booking listing queries.
 * Loads only the fields needed for summary display.
 */
public interface BookingSummaryView {

    UUID getId();

    UUID getConsumerId();

    UUID getProviderId();

    UUID getListingId();

    BookingStatus getStatus();

    Long getPriceCents();

    String getCurrency();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
