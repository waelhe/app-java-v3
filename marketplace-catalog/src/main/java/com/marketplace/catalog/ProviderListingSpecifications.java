package com.marketplace.catalog;

import org.springframework.data.jpa.domain.Specification;

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

}
