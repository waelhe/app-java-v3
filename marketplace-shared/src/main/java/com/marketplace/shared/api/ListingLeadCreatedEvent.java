package com.marketplace.shared.api;

import java.util.UUID;

/**
 * L34 (realestate systems plan §5 — lead capture): published by the
 * messaging module's {@code LeadsService} after committing a new listing
 * lead, consumed by the notifications module to alert the listing's
 * provider ({@code NotificationType.LEAD_RECEIVED}).
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
