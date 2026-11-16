package com.marketplace.community;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.UUID;

/**
 * The groups' own repository (L51). The board read serves the whole
 * layer through ONE derived form — the location-scoped page — and
 * every query rides Hibernate's {@code @SoftDelete} filter (a retired
 * group is absent from the board and its memberships follow in the
 * read path, the events' own is_deleted semantics verbatim).
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every group's lifecycle is a revision the export
 * surface reads.
 */
public interface NeighborhoodGroupRepository
        extends JpaRepository<NeighborhoodGroup, UUID>,
                RevisionRepository<NeighborhoodGroup, UUID, Integer> {

    /**
     * The board read: one neighborhood's live groups. The service
     * forces the complete sort key onto the pageable (D-N5 —
     * {@code created_at ASC, id ASC}, the hood's historical order);
     * this form carries the location scoping alone.
     */
    Page<NeighborhoodGroup> findByLocationId(UUID locationId, Pageable pageable);
}
