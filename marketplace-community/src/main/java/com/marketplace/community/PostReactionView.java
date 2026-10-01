package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The reaction read model (L47): the stored facts and nothing else —
 * the same projection discipline as the comment view (the member stays
 * an opaque UUID; the reaction is reached through the post gate, so it
 * never re-projects the post's own state).
 */
public record PostReactionView(
        UUID id,
        UUID postId,
        UUID memberId,
        Instant createdAt,
        Instant updatedAt
) {
    static PostReactionView of(PostReaction reaction) {
        return new PostReactionView(
                reaction.getId(),
                reaction.getPostId(),
                reaction.getMemberId(),
                reaction.getCreatedAt(),
                reaction.getUpdatedAt());
    }
}
