package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The poll votes' own repository (L52). The EventRsvpRepository /
 * NeighborhoodGroupMembershipRepository shape verbatim — three read
 * forms serve the whole layer, all through Hibernate's
 * {@code @SoftDelete} filter (withdrawn votes are absent from every
 * derived query and JPQL predicate without any predicate of our own):
 *
 * <ul>
 *   <li>the one-vote lookup {@link #findByPollIdAndMemberId(UUID, UUID)}
 *       — the service's explicit 409 check and the withdraw's own row
 *       (the honest 404 when there is no live vote to remove);</li>
 *   <li>the caller's own live votes across a page of polls (the
 *       board's votedByMe — the caller's chosen option id per
 *       poll);</li>
 *   <li>the board's grouped count {@link #countByOptionIdIn(Collection)}
 *       — one aggregate over the page's OPTION ids (the per-option
 *       live vote count every option row carries — the percentage
 *       bars' own denominators), served by the V100 option_id-leading
 *       index scan.</li>
 * </ul>
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every vote cast and withdrawn is a revision the export
 * surface reads.
 */
public interface NeighborhoodPollVoteRepository
        extends JpaRepository<NeighborhoodPollVote, UUID>,
                RevisionRepository<NeighborhoodPollVote, UUID, Integer> {

    /** The member's own LIVE vote on one poll — the one-vote lookup. */
    Optional<NeighborhoodPollVote> findByPollIdAndMemberId(UUID pollId, UUID memberId);

    /** The caller's own LIVE votes across a page of polls (the board's votedByMe). */
    List<NeighborhoodPollVote> findByMemberIdAndPollIdIn(UUID memberId, Collection<UUID> pollIds);

    /**
     * The board's count projection: live votes grouped per option over
     * the page's option ids. Hibernate's soft-delete filter appends
     * the is_deleted guard to the JPQL itself, so the projection
     * counts exactly what the reads see — the option rows' own live
     * counts, earned by real rows, never a seeded display number.
     */
    @Query("select v.optionId as optionId, count(v) as totalCount "
            + "from NeighborhoodPollVote v where v.optionId in :optionIds group by v.optionId")
    List<PollVoteCount> countByOptionIdIn(Collection<UUID> optionIds);

    /** The grouped count's own projection (Spring Data's interface projection). */
    interface PollVoteCount {
        UUID getOptionId();

        long getTotalCount();
    }
}
