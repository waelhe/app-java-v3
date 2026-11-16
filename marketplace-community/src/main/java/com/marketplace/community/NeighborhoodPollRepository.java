package com.marketplace.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.UUID;

/**
 * The polls' own repository (L52). The board read serves the whole
 * layer through ONE derived form — the location-scoped page — and
 * every query rides Hibernate's {@code @SoftDelete} filter (a retired
 * poll is absent from the board and its options and votes follow in
 * the read path, the events'/groups' own is_deleted semantics
 * verbatim).
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every poll's lifecycle is a revision the export
 * surface reads.
 */
public interface NeighborhoodPollRepository
        extends JpaRepository<NeighborhoodPoll, UUID>,
                RevisionRepository<NeighborhoodPoll, UUID, Integer> {

    /**
     * The board read: one neighborhood's live polls. The service
     * forces the complete sort key onto the pageable (D-N5 —
     * {@code created_at DESC, id DESC}, the board's newest-first
     * order: the featured zone carries the LATEST poll, the market
     * board's own discipline); this form carries the location scoping
     * alone.
     */
    Page<NeighborhoodPoll> findByLocationId(UUID locationId, Pageable pageable);
}
