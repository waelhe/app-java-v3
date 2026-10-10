package com.marketplace.community;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * The official Spring Data JPA Specifications over
 * {@link NeighborhoodPost} — "an extensible set of predicates … removing
 * the need to declare a query (method) for every needed combination"
 * (the documented foundation D-N5 builds the feed on; the same entry
 * point the catalog's faceted path uses).
 *
 * <p>Every predicate is REQUIRED for the feed's contract (D-N5):
 * {@code hasLocation} scopes the feed to one neighborhood,
 * {@code isVisible} is the moderation floor (L45's hidden posts are
 * absent from the feed — the plan's criterion 5), and {@code hasCategory}
 * is the one OPTIONAL filter axis (null = absent — the official
 * Specifications model; {@code cb.equal} with a null argument is not
 * portable, the catalog's own CodeRabbit round-1 lesson).
 *
 * <p>Soft-deleted rows are excluded automatically by the entity's
 * {@code @SoftDelete} filter — no predicate of its own, by design.
 * The sort lives in the caller's {@code Pageable}: the complete sort key
 * {@code created_at DESC, id DESC} (D-N5 — no shaky pages).
 */
public final class NeighborhoodPostSpecifications {

    private NeighborhoodPostSpecifications() {}

    /** The feed's scope: exactly one neighborhood's posts. */
    public static Specification<NeighborhoodPost> hasLocation(UUID locationId) {
        return (root, query, cb) -> cb.equal(root.get("locationId"), locationId);
    }

    /** The moderation floor: only VISIBLE posts reach the feed. */
    public static Specification<NeighborhoodPost> isVisible() {
        return (root, query, cb) ->
                cb.equal(root.get("status"), PostStatus.VISIBLE);
    }

    /**
     * The optional category predicate — null is ABSENT (the official
     * Specifications model; {@code cb.equal} with a null argument is not
     * portable — the catalog's CodeRabbit round-1 lesson, adopted here
     * from day one).
     */
    public static Specification<NeighborhoodPost> hasCategory(PostCategory category) {
        return (root, query, cb) -> category == null
                ? cb.conjunction()
                : cb.equal(root.get("category"), category);
    }

    /**
     * JT-20: the lost-and-found lifecycle predicate — the discovery
     * rail's ACTIVE gate composes on top of {@link #hasCategory} (an
     * ACTIVE state without the LOST_FOUND category is a contradiction
     * the column's own null shape makes unrepresentable, and the
     * adapter composes both). Null is ABSENT (the same official model
     * every optional predicate here rides).
     */
    public static Specification<NeighborhoodPost> hasLostFoundState(LostFoundState state) {
        return (root, query, cb) -> state == null
                ? cb.conjunction()
                : cb.equal(root.get("lostFoundState"), state);
    }
}
