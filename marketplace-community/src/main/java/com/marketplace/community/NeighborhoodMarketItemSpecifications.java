package com.marketplace.community;

import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/**
 * The official Spring Data JPA Specifications over
 * {@link NeighborhoodMarketItem} — the NeighborhoodEventSpecifications
 * shape verbatim ("an extensible set of predicates … removing the need
 * to declare a query (method) for every needed combination" — the
 * documented foundation D-N5 builds the board read on).
 *
 * <p>Every predicate is REQUIRED for the board's contract:
 * {@code hasLocation} scopes the board to one neighborhood,
 * {@code textMatches} is the search box's server-side read (title +
 * pickup-spot label — the display haystack's own honest core; the
 * category has its own axis and the price label is not prose to
 * search), {@code hasCategory} is the chips' filter axis, and
 * {@code onlyMine} is the member's own-items view.
 *
 * <p><b>The absent-predicate law (measured the hard way in this
 * wave's first draft, fixed at the root):</b> an absent filter MUST
 * answer {@code cb.conjunction()} — NEVER a null Specification. The
 * service composes the predicates with {@code .and(...)}, and
 * {@code and(null)} throws an IllegalArgumentException at the first
 * board read with no category — the null-returning helper was a
 * landmine under the board's most common call. The events'
 * {@code hasCategory} already obeyed this law; the text read obeys it
 * too here (blank/absent query = no predicate, the official
 * Specifications model).
 *
 * <p>Soft-deleted rows are excluded automatically by the entity's
 * {@code @SoftDelete} filter — no predicate of its own, by design.
 * The sort lives in the caller's {@code Pageable}: the complete sort
 * key {@code created_at DESC, id DESC} (D-N5 — no shaky pages; the
 * board is newest-first, the feed's own key).
 */
public final class NeighborhoodMarketItemSpecifications {

    private NeighborhoodMarketItemSpecifications() {}

    /** The board's scope: exactly one neighborhood's items. */
    public static Specification<NeighborhoodMarketItem> hasLocation(UUID locationId) {
        return (root, query, cb) -> cb.equal(root.get("locationId"), locationId);
    }

    /**
     * The search box's read — a case-insensitive substring over the
     * title and the pickup-spot label. Blank/absent is NO predicate
     * ({@code cb.conjunction()}, never null — the absent-predicate
     * law this class's own javadoc pins).
     *
     * <p>The text is matched LITERALLY (the review round's root fix):
     * {@code %} and {@code _} in the caller's query are characters to
     * find, not wildcards to interpret — the pattern escapes the LIKE
     * metacharacters (backslash first, the escaping character's own
     * rule) and hands JPA the escape character, so {@code q="_"}
     * matches rows containing an underscore instead of every row
     * (Hibernate 6's CriteriaBuilder contract: the third
     * {@code like} argument IS the escape character).
     */
    public static Specification<NeighborhoodMarketItem> textMatches(String query) {
        return (root, query_, cb) -> {
            String q = query == null ? "" : query.trim();
            if (q.isEmpty()) {
                return cb.conjunction();
            }
            String like = "%" + escapeLike(q.toLowerCase()) + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("title")), like, LIKE_ESCAPE),
                    cb.like(cb.lower(root.get("locationLabel")), like, LIKE_ESCAPE));
        };
    }

    /** The LIKE pattern's escape character — a backslash. */
    private static final char LIKE_ESCAPE = '\\';

    /**
     * Escapes the LIKE metacharacters in the caller's literal text:
     * the escape character itself first (otherwise its own escapes
     * would be re-interpreted), then {@code %} and {@code _}.
     */
    private static String escapeLike(String literal) {
        return literal.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    /**
     * The optional category predicate — null is ABSENT (the official
     * Specifications model; {@code cb.equal} with a null argument is
     * not portable — the catalog's CodeRabbit round-1 lesson, adopted
     * from day one).
     */
    public static Specification<NeighborhoodMarketItem> hasCategory(MarketCategory category) {
        return (root, query, cb) -> category == null
                ? cb.conjunction()
                : cb.equal(root.get("category"), category);
    }

    /**
     * The mine axis — false is ABSENT (the whole board); true narrows
     * to the caller's own items (the author seam's own read shape).
     */
    public static Specification<NeighborhoodMarketItem> onlyMine(boolean mine, UUID authorId) {
        return (root, query, cb) -> !mine
                ? cb.conjunction()
                : cb.equal(root.get("authorId"), authorId);
    }
}
