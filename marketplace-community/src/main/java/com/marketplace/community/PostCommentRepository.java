package com.marketplace.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.UUID;

/**
 * The comments aggregate's own repository (L42). {@code findByPostId} is
 * the chronological read — the pageable carries the caller's page and the
 * service's complete sort key ({@code created_at ASC, id ASC}), so the
 * house's deterministic-pagination discipline applies on the way up the
 * thread exactly as the feed applies it on the way down.
 *
 * <p>Hibernate's {@code @SoftDelete} filter hides deleted comments from
 * the derived query — but the read is REACHED THROUGH the post gate in
 * the service first (unknown, hidden or deleted post ⇒ the honest 404),
 * so a comment of an invisible post is never served regardless of its
 * own state. The RevisionRepository arm carries the Envers trail (V24
 * convention).
 */
public interface PostCommentRepository
        extends JpaRepository<PostComment, UUID>,
        RevisionRepository<PostComment, UUID, Integer> {

    /** One post's comments, page-scoped — the caller supplies the sort. */
    Page<PostComment> findByPostId(UUID postId, Pageable pageable);
}
