package com.marketplace.community;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * The events' own repository (L49). The board read rides the official
 * Specifications (D-N5 — the NeighborhoodPostRepository shape
 * verbatim: an extensible predicate set, no query method per
 * combination), and the capacity-critical RSVP path reads the event
 * through the LOCKED find below.
 *
 * <ul>
 *   <li>{@link #findByIdForUpdate(UUID)} — the PESSIMISTIC_WRITE read
 *       the RSVP transaction opens: concurrent seats on one event
 *       QUEUE AT THE ROW, so the count-then-insert capacity check
 *       cannot race past capacity (the ListingViewsDailyRepository
 *       precedent verbatim — "concurrent +1s on the same row serialize
 *       here"). Hibernate's {@code @SoftDelete} filter applies to the
 *       JPQL itself, so a soft-deleted event locks nothing and answers
 *       empty — the honest 404 upstream.</li>
 *   <li>The RevisionRepository arm carries the Envers trail (V24
 *       convention): every event's lifecycle revision the export
 *       surface reads.</li>
 * </ul>
 */
public interface NeighborhoodEventRepository
        extends JpaRepository<NeighborhoodEvent, UUID>,
        JpaSpecificationExecutor<NeighborhoodEvent>,
        RevisionRepository<NeighborhoodEvent, UUID, Integer> {

    /**
     * The locked event read — the RSVP path's serialization point. The
     * row lock is held to the transaction's commit, so two members
     * racing for the last seat resolve in order: the second reads the
     * committed count after the first releases.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from NeighborhoodEvent e where e.id = :id")
    Optional<NeighborhoodEvent> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Stage 5 (community platform execution plan — the unified legal
     * multi-domain search): the events' unified-search read. JPQL (the
     * {@code @SoftDelete} filter applies to it — a withdrawn event never
     * answers), the text axis over title/description/location label, the
     * location axis optional (null = all neighborhoods), and the stable
     * (startsAt, id) order — soonest first, the event's own read surface
     * convention. The adapter adds no predicate and drops none.
     */
    @Query("""
            select e from NeighborhoodEvent e
            where (:locationId is null or e.locationId = :locationId)
              and (lower(e.title) like lower(concat('%', :query, '%'))
                   or lower(coalesce(e.description, '')) like lower(concat('%', :query, '%'))
                   or lower(coalesce(e.locationLabel, '')) like lower(concat('%', :query, '%')))
            order by e.startsAt asc, e.id asc
            """)
    Page<NeighborhoodEvent> searchLiveTextUnified(@Param("locationId") UUID locationId,
                                                  @Param("query") String query,
                                                  Pageable pageable);
}
