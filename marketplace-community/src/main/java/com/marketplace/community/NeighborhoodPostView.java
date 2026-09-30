package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The feed's read model (L42): the stored facts and nothing else — the
 * same projection discipline as the membership view. The author stays an
 * opaque UUID (the identity seams own any resolution the client does);
 * the geo tree's display names stay the public geo surface's concern.
 *
 * <p><b>L47 (the reactions layer) widens the projection with the two
 * reaction facts the feed read now carries:</b> {@code reactionsCount}
 * (the live thanks on the post — the grouped count over the page's ids)
 * and {@code reactedByMe} (the caller's own live voice, so the client
 * renders the filled heart honestly). Both are the caller-scoped read
 * model's own facts — the count is the same for every reader, the
 * caller's own flag is the projection's one per-reader field (the same
 * shape the membership view carries).
 *
 * <p>The compatibility constructor (the nine-argument shape every
 * pre-L47 call site rode) keeps compiling: it delegates with the fresh
 * post's own zero-false facts — a post that just left the publish
 * factory has no reactions and no caller voice yet.
 */
public record NeighborhoodPostView(
        UUID id,
        UUID authorId,
        UUID locationId,
        String category,
        String title,
        String body,
        String status,
        long reactionsCount,
        boolean reactedByMe,
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
                0L,
                false,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    /**
     * The feed read's factory: the stored facts plus the two
     * caller-scoped reaction facts the grouped count and the caller's
     * own live voice produced.
     */
    static NeighborhoodPostView of(NeighborhoodPost post, long reactionsCount, boolean reactedByMe) {
        return new NeighborhoodPostView(
                post.getId(),
                post.getAuthorId(),
                post.getLocationId(),
                post.getCategory().name(),
                post.getTitle(),
                post.getBody(),
                post.getStatus().name(),
                reactionsCount,
                reactedByMe,
                post.getCreatedAt(),
                post.getUpdatedAt());
    }

    /**
     * The pre-L47 shape (the write paths' echo: a created post has no
     * reactions; a deleted one no longer renders): the compatibility
     * constructor every existing call site rode, delegating with the
     * zero-false facts of a post no one has thanked yet.
     */
    public NeighborhoodPostView(UUID id, UUID authorId, UUID locationId,
                                String category, String title, String body, String status,
                                Instant createdAt, Instant updatedAt) {
        this(id, authorId, locationId, category, title, body, status,
                0L, false, createdAt, updatedAt);
    }
}
