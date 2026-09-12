package com.marketplace.catalog;

import com.marketplace.shared.api.PropertyDetailsPort;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The public listing detail response. L31 (realestate systems plan): gains
 * the optional {@code property} block — the real-estate field set when the
 * listing has one, embedded through the {@code PropertyDetailsPort} (the
 * {@code ProviderNameResolver} pattern: shared-api interface, realestate
 * implements, catalog consumes — an application association in the
 * aggregation, never JPA across module boundaries). The field is additive
 * and null for every listing without property details.
 */
public record ListingResponse(
        UUID id,
        String title,
        String description,
        String category,
        BigDecimal price,
        String currency,
        Integer maxGuests,
        Instant createdAt,
        Instant updatedAt,
        PropertyDetailsPort.PropertyView property
) {
    /** The pre-L31 nine-component form — every existing construction site. */
    public ListingResponse(UUID id, String title, String description, String category,
                           BigDecimal price, String currency, Integer maxGuests,
                           Instant createdAt, Instant updatedAt) {
        this(id, title, description, category, price, currency, maxGuests,
                createdAt, updatedAt, null);
    }

    /** The embed — the wither of the L31 block. */
    public ListingResponse withProperty(PropertyDetailsPort.PropertyView propertyView) {
        return new ListingResponse(id, title, description, category, price, currency,
                maxGuests, createdAt, updatedAt, propertyView);
    }
}
