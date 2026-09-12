package com.marketplace.realestate;

import com.marketplace.shared.api.PropertyCriteria;
import com.marketplace.shared.api.RealestatePropertyFilterPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * L32: the realestate module's answer to the faceted-search integration —
 * the adopted CodeRabbit architecture (plan §5 L32, integration points):
 * the module queries ITS OWN table through the official Spring Data JPA
 * Specifications ("an extensible set of predicates … removing the need to
 * declare a query (method) for every needed combination" — the documented
 * foundation of the faceted path) and answers with the matching
 * LISTING-ID set; the search module composes that restriction onto the
 * catalog query in the existing restricted-branch shape. No cross-module
 * query ever runs — each module owns its schema.
 */
@Component
@Transactional(readOnly = true)
public class PropertyFilterAdapter implements RealestatePropertyFilterPort {

    /** The area sort marker as it arrives from the search surface. */
    static final String AREA_SORT_PROPERTY = "area";

    private final PropertyDetailsRepository repository;

    public PropertyFilterAdapter(PropertyDetailsRepository repository) {
        this.repository = repository;
    }

    @Override
    public Set<UUID> findListingIdsMatching(PropertyCriteria criteria) {
        return repository.findAll(toSpecification(criteria, null))
                .stream().map(PropertyDetails::getListingId).collect(Collectors.toSet());
    }

    @Override
    public Set<UUID> findListingIdsMatchingRestricted(PropertyCriteria criteria, Set<UUID> providerIds) {
        return repository.findAll(toSpecification(criteria, providerIds))
                .stream().map(PropertyDetails::getListingId).collect(Collectors.toSet());
    }

    @Override
    public Page<PropertyMatch> findMatchingPaged(PropertyCriteria criteria,
                                                 Set<UUID> activeListingIds, Pageable pageable) {
        return repository
                .findAll(toSpecification(criteria, null, activeListingIds, true), areaOrdered(pageable))
                .map(details -> new PropertyMatch(details.getListingId(), details.getAreaM2()));
    }

    @Override
    public Page<PropertyMatch> findMatchingPagedRestricted(PropertyCriteria criteria,
                                                           Set<UUID> activeListingIds,
                                                           Set<UUID> providerIds, Pageable pageable) {
        return repository
                .findAll(toSpecification(criteria, providerIds, activeListingIds, true), areaOrdered(pageable))
                .map(details -> new PropertyMatch(details.getListingId(), details.getAreaM2()));
    }

    // ------------------------------------------------------------------
    // P1 (postgis integration plan §D-P3): the radius operations. The set
    // forms delegate to the native ST_DWithin queries; the paged forms
    // page through the native distance ordering restricted to the
    // catalog-resolved ACTIVE set (the searchAreaSorted composition — each
    // module owns its schema, the search owns the composition). The
    // distance never leaves this module: the paged answers carry listing
    // ids only (D-P11 — the client computes the display distance from the
    // listing coordinates it already holds).
    // ------------------------------------------------------------------

    @Override
    public Set<UUID> findListingIdsWithinRadius(BigDecimal latitude, BigDecimal longitude,
                                                long radiusMeters) {
        return repository.findListingIdsWithinRadius(latitude, longitude, radiusMeters);
    }

    @Override
    public Set<UUID> findListingIdsWithinRadiusRestricted(BigDecimal latitude, BigDecimal longitude,
                                                          long radiusMeters, Set<UUID> providerIds) {
        return repository.findListingIdsWithinRadiusRestricted(latitude, longitude, radiusMeters, providerIds);
    }

    @Override
    public Page<UUID> findWithinRadiusPaged(BigDecimal latitude, BigDecimal longitude, long radiusMeters,
                                            Set<UUID> activeListingIds, Pageable pageable) {
        if (activeListingIds.isEmpty()) {
            // The Specification path's disjunction analog: an empty ACTIVE
            // set is an honest empty page — never a native IN () (invalid
            // SQL, not a semantics question).
            return Page.empty(pageable);
        }
        return repository
                .findWithinRadiusPaged(latitude, longitude, radiusMeters, activeListingIds, distancePaged(pageable))
                .map(PropertyDetails::getListingId);
    }

    @Override
    public Page<UUID> findWithinRadiusPagedRestricted(BigDecimal latitude, BigDecimal longitude,
                                                      long radiusMeters, Set<UUID> activeListingIds,
                                                      Set<UUID> providerIds, Pageable pageable) {
        if (activeListingIds.isEmpty()) {
            return Page.empty(pageable);
        }
        return repository
                .findWithinRadiusPagedRestricted(latitude, longitude, radiusMeters,
                        activeListingIds, providerIds, distancePaged(pageable))
                .map(PropertyDetails::getListingId);
    }

    /**
     * The distance flow's page: the ORDER BY is baked in the native query
     * (ST_Distance ASC, listing_id ASC — nearest first with the
     * deterministic tiebreak), so the pageable arrives for LIMIT/OFFSET
     * only and the {@code distance} marker sort is consumed here — the
     * {@link #areaOrdered(Pageable)} analog: the entity-side remapping
     * that keeps the native query's baked order intact.
     */
    private static Pageable distancePaged(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    /** The optional-predicate Specification over property_details. */
    static Specification<PropertyDetails> toSpecification(PropertyCriteria criteria,
                                                          Set<UUID> providerIds) {
        return toSpecification(criteria, providerIds, null, false);
    }

    /**
     * Every criterion is an OPTIONAL predicate (null = absent — the
     * official Specifications model); soft-deleted rows are excluded by
     * the {@code @SoftDelete} filter automatically (BaseEntity).
     * {@code requireArea} (the paged/area-sorted forms) additionally keeps
     * only rows with a DECLARED area — an "ordered by area" page of
     * undeclared areas is meaningless.
     */
    static Specification<PropertyDetails> toSpecification(PropertyCriteria criteria,
                                                          Set<UUID> providerIds,
                                                          Set<UUID> activeListingIds,
                                                          boolean requireArea) {
        Specification<PropertyDetails> spec = (root, query, cb) -> cb.conjunction();
        if (requireArea) {
            spec = spec.and((root, q, cb) -> cb.isNotNull(root.get("areaM2")));
        }
        if (criteria.purpose() != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("purpose"), criteria.purpose()));
        }
        if (criteria.propertyType() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.equal(root.get("propertyType"), criteria.propertyType()));
        }
        if (criteria.minRooms() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("rooms"), criteria.minRooms()));
        }
        if (criteria.minBathrooms() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("bathrooms"), criteria.minBathrooms()));
        }
        if (criteria.minAreaM2() != null) {
            spec = spec.and((root, q, cb) ->
                    cb.greaterThanOrEqualTo(root.get("areaM2"), criteria.minAreaM2()));
        }
        if (criteria.locationIds() != null) {
            spec = spec.and((root, q, cb) -> root.get("locationId").in(criteria.locationIds()));
        }
        if (providerIds != null && !providerIds.isEmpty()) {
            spec = spec.and((root, q, cb) -> root.get("providerId").in(providerIds));
        }
        if (activeListingIds != null) {
            // The catalog-resolved ACTIVE set (area-sorted flow): the
            // realestate table does not know listing status by design.
            if (activeListingIds.isEmpty()) {
                spec = spec.and((root, q, cb) -> cb.disjunction());
            } else {
                spec = spec.and((root, q, cb) -> root.get("listingId").in(activeListingIds));
            }
        }
        return spec;
    }

    /**
     * Maps the {@code area} sort marker to the realestate-owned
     * {@code areaM2} field with the id tiebreak — a deterministic total
     * order (offset pagination never wobbles). Rows with a declared area
     * only (the requireArea predicate above).
     */
    private static Pageable areaOrdered(Pageable pageable) {
        Sort.Direction direction = Sort.Direction.ASC;
        for (Sort.Order order : pageable.getSort()) {
            if (AREA_SORT_PROPERTY.equals(order.getProperty())) {
                direction = order.getDirection();
            }
        }
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(direction, "areaM2").and(Sort.by(Sort.Direction.ASC, "id")));
    }

}
