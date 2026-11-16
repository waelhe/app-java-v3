package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The poll options' own repository (L52). TWO read forms serve the
 * whole layer, both through Hibernate's {@code @SoftDelete} filter:
 *
 * <ul>
 *  <li>the one-option lookup {@link #findById(UUID)} (inherited) —
 *      the vote write's own reference gate (the honest 404 for an
 *      unknown option, the poll-match check the service owns);</li>
 *  <li>the board's options batch {@link #findByPollIdIn(Collection)}
 *      — one IN read over the page's poll ids, the board read's own
 *      options projection (the service sorts by the author's own
 *      (position, id) pair — D-N5's complete key at the option
 *      level).</li>
 * </ul>
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every option authored with its poll is a revision the
 * export surface reads.
 */
public interface NeighborhoodPollOptionRepository
        extends JpaRepository<NeighborhoodPollOption, UUID>,
                RevisionRepository<NeighborhoodPollOption, UUID, Integer> {

    /** The board's options batch: the live options of a page of polls, one IN read. */
    List<NeighborhoodPollOption> findByPollIdIn(Collection<UUID> pollIds);
}
