package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The reactions' own repository (L47). Two read shapes serve the whole
 * layer, both through Hibernate's {@code @SoftDelete} filter (removed
 * reactions are absent from every derived query and JPQL predicate
 * without any predicate of our own):
 *
 * <ul>
 *   <li>the one-voice lookup {@link #findByPostIdAndMemberId(UUID, UUID)}
 *       — the service's explicit 409 check and the un-thank's own row
 *       (the honest 404 when there is no live voice to remove);</li>
 *   <li>the feed's grouped count {@link #countByPostIdIn(Collection)} —
 *       one aggregate over the page's post ids (the count column the
 *       feed read carries), served by the V73 unique index's
 *       post_id-leading prefix scan.</li>
 * </ul>
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every thank and every un-thank is a revision the export
 * surface reads.
 */
public interface PostReactionRepository
        extends JpaRepository<PostReaction, UUID>,
        RevisionRepository<PostReaction, UUID, Integer> {

    /** The member's own LIVE reaction on one post — the one-voice lookup. */
    Optional<PostReaction> findByPostIdAndMemberId(UUID postId, UUID memberId);

    /** The caller's own LIVE reactions across a page of posts (the feed's reactedByMe). */
    List<PostReaction> findByMemberIdAndPostIdIn(UUID memberId, Collection<UUID> postIds);

    /**
     * The feed's count projection: live reactions grouped per post over
     * the page's ids. Hibernate's soft-delete filter appends the
     * is_deleted guard to the JPQL itself, so the projection counts
     * exactly what the reads see.
     */
    @Query("select r.postId as postId, count(r) as totalCount "
            + "from PostReaction r where r.postId in :postIds group by r.postId")
    List<PostReactionCount> countByPostIdIn(Collection<UUID> postIds);

    /** The grouped count's own projection (Spring Data's interface projection). */
    interface PostReactionCount {
        UUID getPostId();

        long getTotalCount();
    }
}
