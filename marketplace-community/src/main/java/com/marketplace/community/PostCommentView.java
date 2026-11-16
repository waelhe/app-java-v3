package com.marketplace.community;

import java.time.Instant;
import java.util.UUID;

/**
 * The comment read model (L42): the stored facts and nothing else — the
 * same projection discipline as the post view (the author stays an
 * opaque UUID; the comment is reached through the post gate, so it never
 * re-projects the post's own state).
 */
public record PostCommentView(
        UUID id,
        UUID postId,
        UUID authorId,
        String body,
        Instant createdAt,
        Instant updatedAt
) {
    static PostCommentView of(PostComment comment) {
        return new PostCommentView(
                comment.getId(),
                comment.getPostId(),
                comment.getAuthorId(),
                comment.getBody(),
                comment.getCreatedAt(),
                comment.getUpdatedAt());
    }
}
