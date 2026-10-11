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
 * surface) — and, since JT-20, its status-honest twin: a CANCELLED or
 * POSTPONED event NEVER masquerades as upcoming (the gathering state
 * is deterministic eligibility, applied before any ordering), and
 * {@code hasCategory} is the one OPTIONAL filter axis (null = absent —
 * the official Specifications model; {@code cb.equal} with a null
 * argument is not portable, the catalog's own CodeRabbit round-1
 * lesson), joined by {@code hasStatus} (JT-20's optional status axis —
 * null = absent, the same model).
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

    /**
     * The board's floor: only events still to come (starts_at >= now)
     * AND not withdrawn from the calendar — a CANCELLED or POSTPONED
     * event never masquerades as upcoming (JT-20's status-honest
     * contract; the exclusion is expressed as the literal NOT-IN so the
     * predicate survives a future vocabulary widening without silently
     * re-admitting a withdrawn gathering). The row, its seats and its
     * audit trail stay — the state is a fact, not an erasure.
     */
    public static Specification<NeighborhoodEvent> upcoming(Instant now) {
        return (root, query, cb) -> cb.and(
                cb.greaterThanOrEqualTo(root.get("startsAt"), now),
                cb.not(root.get("status").in(
                        NeighborhoodEventStatus.CANCELLED,
                        NeighborhoodEventStatus.POSTPONED)));
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

    /**
     * JT-20: the optional status predicate — the explicit status axis on
     * the board read (null = ABSENT, the same official model). Composed
     * ON TOP of {@link #upcoming}: on the forward-looking board only an
     * ACTIVE event can match today (a cancelled or postponed one never
     * rides the upcoming floor) — the axis exists so the surface speaks
     * the state vocabulary honestly and the rails can pin it explicitly.
     */
    public static Specification<NeighborhoodEvent> hasStatus(NeighborhoodEventStatus status) {
        return (root, query, cb) -> status == null
                ? cb.conjunction()
                : cb.equal(root.get("status"), status);
    }
}
