package com.marketplace.community;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The RSVPs' own repository (L49). The PostReactionRepository shape
 * verbatim — three read forms serve the whole layer, all through
 * Hibernate's {@code @SoftDelete} filter (removed seats are absent from
 * every derived query and JPQL predicate without any predicate of our
 * own):
 *
 * <ul>
 *   <li>the one-seat lookup {@link #findByEventIdAndMemberId(UUID, UUID)}
 *       — the service's explicit 409 check and the un-RSVP's own row
 *       (the honest 404 when there is no live seat to remove);</li>
 *   <li>the member's own live seat across a page of events (the board's
 *       rsvpedByMe) and the single-event live count (the capacity gate's
 *       own number);</li>
 *   <li>the board's grouped count {@link #countByEventIdIn(Collection)}
 *       — one aggregate over the page's event ids (the attending column
 *       the board read carries), served by the V83 unique index's
 *       event_id-leading prefix scan.</li>
 * </ul>
 *
 * <p>The RevisionRepository arm carries the Envers trail (V24
 * convention): every seat taken and freed is a revision the export
 * surface reads.
 */
public interface EventRsvpRepository
        extends JpaRepository<EventRsvp, UUID>,
        RevisionRepository<EventRsvp, UUID, Integer> {

    /** The member's own LIVE seat on one event — the one-seat lookup. */
    Optional<EventRsvp> findByEventIdAndMemberId(UUID eventId, UUID memberId);

    /** The caller's own LIVE seats across a page of events (the board's rsvpedByMe). */
    List<EventRsvp> findByMemberIdAndEventIdIn(UUID memberId, Collection<UUID> eventIds);

    /** The live seat count on one event — the capacity gate's own number. */
    long countByEventId(UUID eventId);

    /**
     * The board's count projection: live seats grouped per event over
     * the page's ids. Hibernate's soft-delete filter appends the
     * is_deleted guard to the JPQL itself, so the projection counts
     * exactly what the reads see.
     */
    @Query("select r.eventId as eventId, count(r) as totalCount "
            + "from EventRsvp r where r.eventId in :eventIds group by r.eventId")
    List<EventRsvpCount> countByEventIdIn(Collection<UUID> eventIds);

    /** The grouped count's own projection (Spring Data's interface projection). */
    interface EventRsvpCount {
        UUID getEventId();

        long getTotalCount();
    }
}
