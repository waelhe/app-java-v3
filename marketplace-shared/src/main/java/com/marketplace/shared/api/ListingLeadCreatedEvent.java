package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L34 (realestate systems plan §5 — lead capture): published by the
 * messaging module's {@code LeadsService} after committing a new listing
 * lead, consumed by the notifications module to alert the listing's
 * provider ({@code NotificationType.LEAD_RECEIVED}).
 *
 * <p><b>The id-space fact (A1/V2, measured):</b> {@code providerId} is
 * the listing's provider — {@code provider_listings.provider_id} lives in
 * the <em>users.id</em> space (the same fact {@code CatalogService} pins
 * at its A1 comment and {@code onBookingCreated}'s BookingInfo provider
 * relies on), so consumers use it directly as a user-space reference —
 * no provider-profile resolution.
 *
 * <p>The Modulith house pattern ({@code BookingCreatedEvent} /
 * {@code MediaUploadedEvent} precedent): a shared-api record carrying
 * identifiers only, published inside the writer's transaction so the
 * Event Publication Registry writes its entries atomically with the
 * business write; {@code @ApplicationModuleListener} consumers run after
 * commit in their own transaction with the framework's retry.
 */
public record ListingLeadCreatedEvent(
        UUID leadId,
        UUID listingId,
        UUID providerId
) {
}
