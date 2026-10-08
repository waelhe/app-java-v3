package com.marketplace.knowledge;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * B-14 (compliance plan C.4): the guide's read model — the
 * {@code from}-factory house shape (the entry never crosses the wire).
 */
public record KnowledgeEntryResponse(
        @Schema(description = "The entry's id.") UUID id,
        @Schema(description = "The contributor's user id (users.id space).") UUID authorId,
        @Schema(description = "The neighborhood the entry documents (the geo level-3 node id).") UUID locationId,
        @Schema(description = "PLACES, SERVICES, HISTORY, PEOPLE or TIPS.") String category,
        @Schema(description = "The entry's title.") String title,
        @Schema(description = "The entry's body.") String body,
        @Schema(description = "The contribution time.") Instant createdAt,
        @Schema(description = "The last-revision time.") Instant updatedAt
) {

    public static KnowledgeEntryResponse from(KnowledgeEntry entry) {
        return new KnowledgeEntryResponse(
                entry.getId(),
                entry.getAuthorId(),
                entry.getLocationId(),
                entry.getCategory().name(),
                entry.getTitle(),
                entry.getBody(),
                entry.getCreatedAt(),
                entry.getUpdatedAt());
    }
}
