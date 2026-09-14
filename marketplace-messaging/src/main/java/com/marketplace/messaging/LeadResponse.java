package com.marketplace.messaging;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * L34 (realestate systems plan §5 — lead capture): the lead as the
 * provider sees it in their inbox — contact data included (that is the
 * point of a lead: the mediated-contact model, G-R4's default shape).
 */
public record LeadResponse(
        @Schema(description = "The lead's id.") UUID id,
        @Schema(description = "The listing the lead is about.") UUID listingId,
        @Schema(description = "The name the provider should reply to.") String contactName,
        @Schema(description = "The callback phone.") String contactPhone,
        @Schema(description = "The lead message.") String message,
        @Schema(description = "NEW, READ or ARCHIVED — the provider's inbox state.") String status,
        @Schema(description = "When the lead arrived.") Instant createdAt
) {
    static LeadResponse from(ListingLead lead) {
        return new LeadResponse(lead.getId(), lead.getListingId(), lead.getContactName(),
                lead.getContactPhone(), lead.getMessage(), lead.getStatus().name(), lead.getCreatedAt());
    }
}
