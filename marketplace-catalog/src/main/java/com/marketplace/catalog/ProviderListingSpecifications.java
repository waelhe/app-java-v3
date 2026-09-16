package com.marketplace.catalog;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.query.QueryUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The official Spring Data JPA Specifications over {@code ProviderListing}
 * — "an extensible set of predicates … removing the need to declare a
 * query (method) for every needed combination" (the documented foundation
 * of the L32 faceted path; the house already uses this entry point).
 *
 * <p>Every predicate is OPTIONAL (null = absent). Soft-deleted rows are
 * excluded automatically by the entity's {@code @SoftDelete} filter.
 */
public final class ProviderListingSpecifications {

    private ProviderListingSpecifications() {}

    public static Specification<ProviderListing> hasStatus(ListingStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /**
     * The optional category predicate — null is ABSENT (the official
     * Specifications model, the same contract priceBetween and minGuests
     * implement; CodeRabbit PR #299 round 1: {@code cb.equal} with a null
     * argument is not portable — a provider may translate it into a null
     * comparison that matches nothing for a non-null column).
     */
    public static Specification<ProviderListing> hasCategory(String category) {
        return (root, query, cb) -> category == null
                ? cb.conjunction()
                : cb.equal(root.get("category"), category);
    }

    public static Specification<ProviderListing> priceBetween(Long min, Long max) {
        return (root, query, cb) -> {
            if (min == null && max == null) return cb.conjunction();
            if (min == null) return cb.lessThanOrEqualTo(root.get("priceCents"), max);
            if (max == null) return cb.greaterThanOrEqualTo(root.get("priceCents"), min);
            return cb.between(root.get("priceCents"), min, max);
        };
    }

    /**
     * I6 semantics as a predicate: a guests criterion matches only
     * listings with a DECLARED capacity that accommodates it (NULL
     * capacity never satisfies a requirement).
     */
    public static Specification<ProviderListing> minGuests(Integer guests) {
        return (root, query, cb) -> {
            if (guests == null) return cb.conjunction();
            return cb.and(
                    cb.isNotNull(root.get("maxGuests")),
                    cb.greaterThanOrEqualTo(root.get("maxGuests"), guests));
        };
    }

    /**
     * L32: the property-facet restriction — the realestate module's
     * matching-id set composes onto the catalog query as an optional
     * predicate (null = absent; an empty collection never arrives — the
     * caller short-circuits to an honest empty page first).
     */
    public static Specification<ProviderListing> hasListingIdIn(Collection<java.util.UUID> listingIds) {
        return (root, query, cb) -> {
            if (listingIds == null) return cb.conjunction();
            return root.get("id").in(listingIds);
        };
    }

    /**
     * L36: the public provider page's predicate — the provider whose
     * listings are browsed (null = absent, the house's optional-predicate
     * contract). The provider id lives in the users.id space (A1) exactly
     * as the derived query {@code findByProviderIdAndStatus} it replaces.
     */
    public static Specification<ProviderListing> hasProviderId(UUID providerId) {
        return (root, query, cb) -> providerId == null
                ? cb.conjunction()
                : cb.equal(root.get("providerId"), providerId);
    }

    /**
     * L37 (realestate systems plan §5 — the featured boost): the
     * boost-first ORDERING specification — "المعزّز أولًا داخل نفس الفرز
     * الأساسي"، one shape for every ordered public read:
     *
     * <pre>ORDER BY CASE WHEN promoted_until &gt; :now THEN 1 ELSE 0 END DESC,
     *         &lt;the effective whitelisted sort&gt;, id ASC</pre>
     *
     * <p><b>Why a CASE flag and not the naive {@code promoted_until
     * DESC}:</b> PostgreSQL ranks NULLS FIRST on DESC — every unboosted
     * row would outrank the boosted ones — and a past window would still
     * outrank NULL. The CASE is TOTAL (1 or 0, never NULL) and
     * expiry-aware at query time, so it is immune to both traps and an
     * expired boost is self-correcting on the next read (the plan's
     * criterion 3 — no cleanup job exists or is needed).
     *
     * <p><b>Why the ordering rides THIS specification and not the
     * Pageable (the measured framework chain):</b> Spring Data JPA's
     * {@code SimpleJpaRepository.getQuery(spec, sort)} runs
     * {@code toPredicate} (which may call {@code query.orderBy}) and only
     * THEN applies {@code if (sort.isSorted()) query.orderBy(toOrders(...))}
     * — and {@code CriteriaQuery.orderBy} REPLACES the whole list (the
     * JPA contract; the official reference states it: "page(Pageable)
     * using a sorted Pageable overrides any previous sort order"). So the
     * caller passes an UNSORTED pageable (page/size only) and this
     * specification owns the COMPLETE order. The {@code now} value is
     * captured as a Criteria literal — Hibernate renders it as a JDBC
     * bind parameter (measured), so the SQL string stays stable for the
     * plan cache and the value follows the injected Clock per call (the
     * expiry acceptance test's own seam).
     *
     * <p><b>Why the companion COUNT specification exists:</b> the same
     * {@code toPredicate} also runs for the count query, and PostgreSQL
     * rejects an aggregate with a non-grouped ORDER BY (measured:
     * "column must appear in the GROUP BY clause or be used in an
     * aggregate function"). The official escape is the two-specification
     * overload {@code findAll(spec, countSpec, pageable)} (since 3.5):
     * this ordering spec composes into the CONTENT spec only, while the
     * count spec keeps the predicates verbatim — which is precisely the
     * plan's criterion 2 made structural (the boost reorders, it never
     * filters: the count queries stay byte-identical to the pre-L37
     * shapes).
     *
     * <p><b>The total-order discipline (L32) moves here:</b> the
     * requested sort rides {@link QueryUtils#toOrders} — the framework's
     * own Sort-to-Criteria translation, the identical function
     * {@code SimpleJpaRepository} applies to a sorted Pageable — and the
     * id ASC tiebreak is appended for every request (unsorted defaults
     * to plain id ASC, "no deceptive pages"). This also closes the latent
     * L32 gap on the derived-query surfaces: {@code findByStatus(status,
     * unsorted-pageable)} paginated in ARBITRARY database order — the
     * conversion of those surfaces to this specification makes every
     * ordered public read total.
     *
     * <p><b>Composition order (measured):</b> {@code Specification.and}
     * evaluates the LEFT side before the RIGHT
     * ({@code SpecificationComposition.composed} calls {@code toPredicate(lhs)}
     * before {@code toPredicate(rhs)}), and {@code CriteriaQuery.orderBy}
     * replaces — so composing {@code predicates.and(boostFirst(...))}
     * guarantees this ordering wins even if a future predicate spec ever
     * sets an order of its own. Compose it LAST.
     */
    public static Specification<ProviderListing> boostFirst(Sort sort, Instant now) {
        return (root, query, cb) -> {
            List<jakarta.persistence.criteria.Order> orders = new ArrayList<>();
            // The total boost flag: promoted_until > now (NULL compares
            // UNKNOWN in SQL, which falls to the ELSE 0 branch — the NULL
            // rows are unboosted by definition, no isNotNull needed).
            orders.add(cb.desc(cb.<Integer>selectCase()
                    .when(cb.greaterThan(root.<Instant>get("promotedUntil"), now), 1)
                    .otherwise(0)));
            // The effective whitelisted sort — the framework's own
            // translation (what a sorted Pageable would have applied).
            orders.addAll(QueryUtils.toOrders(sort, root, cb));
            // The L32 total-order tiebreak — always.
            orders.add(cb.asc(root.get("id")));
            query.orderBy(orders);
            // Ordering-only: contributes no predicate (conjunction is
            // the Specification null-equivalent that composes cleanly).
            return cb.conjunction();
        };
    }
}
