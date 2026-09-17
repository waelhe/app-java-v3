package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The feed's read model (L42): the stored facts and nothing else — the
 * same projection discipline as the membership view. The author stays an
 * opaque UUID (the identity seams own any resolution the client does);
 * the geo tree's display names stay the public geo surface's concern.
 */
public record NeighborhoodPostView(
        UUID id,
        UUID authorId,
        UUID locationId,
        String category,
        String title,
        String body,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
    static NeighborhoodPostView of(NeighborhoodPost post) {
        return new NeighborhoodPostView(
                post.getId(),
                post.getAuthorId(),
                post.getLocationId(),
                post.getCategory().name(),
                post.getTitle(),
                post.getBody(),
                post.getStatus().name(),
                post.getCreatedAt(),
                post.getUpdatedAt());
    }
}
