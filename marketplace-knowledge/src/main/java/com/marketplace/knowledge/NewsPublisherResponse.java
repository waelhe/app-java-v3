package com.marketplace.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * D-3 (JT-19/D-30): the publisher's read model — the {@code from}-factory
 * house shape (the entity never crosses the wire). The state rides the
 * response — the trust signal visible, never hidden (the institutions
 * registry's own discipline).
 */
public record NewsPublisherResponse(
        @Schema(description = "The publisher's id.") UUID id,
        @Schema(description = "The publisher's registry name.") String name,
        @Schema(description = "The optional public website of the outlet.") String websiteUrl,
        @Schema(description = "UNVERIFIED, PENDING, VERIFIED or REJECTED — the state rides, never hidden.") String verificationState,
        @Schema(description = "The registration time.") Instant createdAt,
        @Schema(description = "The last verification move's time.") Instant updatedAt
) {

    public static NewsPublisherResponse from(NewsPublisher publisher) {
        return new NewsPublisherResponse(
                publisher.getId(),
                publisher.getName(),
                publisher.getWebsiteUrl(),
                publisher.getVerificationState().name(),
                publisher.getCreatedAt(),
                publisher.getUpdatedAt());
    }
}
