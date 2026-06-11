package com.marketplace.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * Closed projection for payment intent listing queries.
 * Loads only the fields needed for summary display.
 */
public interface PaymentIntentSummaryView {

    UUID getId();

    UUID getBookingId();

    UUID getConsumerId();

    Long getAmountCents();

    String getCurrency();

    PaymentIntentStatus getStatus();

    Instant getCreatedAt();

    Instant getUpdatedAt();
}
