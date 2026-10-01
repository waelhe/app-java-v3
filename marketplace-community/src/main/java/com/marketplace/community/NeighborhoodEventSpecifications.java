package com.marketplace.community;

import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/**
 * The official Spring Data JPA Specifications over
 * {@link NeighborhoodEvent} — the NeighborhoodPostSpecifications shape
 * verbatim ("an extensible set of predicates … removing the need to
 * declare a query (method) for every needed combination" — the
 * documented foundation D-N5 builds the board read on).
 *
 * <p>Every predicate is REQUIRED for the board's contract:
 * {@code hasLocation} scopes the board to one neighborhood,
 * {@code upcoming} is the board's forward-looking floor (an event
 * whose start has passed leaves the board — its row, seats and audit
 * trail stay; the board itself is the product's upcoming-gatherings
 * surface), and {@code hasCategory} is the one OPTIONAL filter axis
 * (null = absent — the official Specifications model; {@code cb.equal}
 * with a null argument is not portable, the catalog's own CodeRabbit
 * round-1 lesson).
 *
 * <p>Soft-deleted rows are excluded automatically by the entity's
 * {@code @SoftDelete} filter — no predicate of its own, by design.
 * The sort lives in the caller's {@code Pageable}: the complete sort
 * key {@code starts_at ASC, id ASC} (D-N5 — no shaky pages; the board
 * is forward-looking, so the time key leads where the feed's
 * created_at led).
 *
 * <p>The board's THIS_WEEK and MINE view chips stay CLIENT-side by
 * contract (the product's own filterEvents semantics over the loaded
 * board — {@code rsvpedByMe} and {@code startsAt} ride every row), so
 * no predicate exists for them: the server's one axis is the category,
 * the posts feed's own discipline.
 */
public final class NeighborhoodEventSpecifications {

    private NeighborhoodEventSpecifications() {}

    /** The board's scope: exactly one neighborhood's events. */
    public static Specification<NeighborhoodEvent> hasLocation(UUID locationId) {
        return (root, query, cb) -> cb.equal(root.get("locationId"), locationId);
    }

    /** The board's floor: only events still to come (starts_at >= now). */
    public static Specification<NeighborhoodEvent> upcoming(Instant now) {
        return (root, query, cb) ->
                cb.greaterThanOrEqualTo(root.get("startsAt"), now);
    }

    /**
     * The optional category predicate — null is ABSENT (the official
     * Specifications model; {@code cb.equal} with a null argument is not
     * portable — the catalog's CodeRabbit round-1 lesson, adopted here
     * from day one).
     */
    public static Specification<NeighborhoodEvent> hasCategory(EventCategory category) {
        return (root, query, cb) -> category == null
                ? cb.conjunction()
                : cb.equal(root.get("category"), category);
    }
}
