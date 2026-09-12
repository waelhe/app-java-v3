package com.marketplace.catalog;

import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;

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

    public static Specification<ProviderListing> hasCategory(String category) {
        return (root, query, cb) -> cb.equal(root.get("category"), category);
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
}
