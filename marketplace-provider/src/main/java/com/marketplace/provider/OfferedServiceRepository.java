package com.marketplace.provider;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the declared services list
 * repository (the {@code provider_services} table's {@code OfferedService}
 * rows) — the module's own house shape.
 */
public interface OfferedServiceRepository extends JpaRepository<OfferedService, UUID>,
        JpaSpecificationExecutor<OfferedService>,
        RevisionRepository<OfferedService, UUID, Integer> {

    /**
     * The page's own read: one provider's declared menu in position order
     * — the deterministic total order (D-N5, the {@code position} key V88
     * keeps unique per provider over the live rows).
     */
    List<OfferedService> findByProviderIdOrderByPositionAsc(UUID providerId);

    /**
     * The allocation seam for the write surface: the current maximum
     * position — the W1 max-based allocation lesson (count-based
     * allocation re-issues held positions under soft deletion; the max
     * never goes backward).
     */
    @org.springframework.data.jpa.repository.Query(
            "select max(s.position) from OfferedService s where s.providerId = :providerId")
    Optional<Integer> findMaxPositionByProviderId(UUID providerId);

    /**
     * The swap seam for the reorder: the live row occupying one position
     * of this provider's menu — the unique key's read form
     * ({@code uq_provider_services_position}).
     */
    Optional<OfferedService> findByProviderIdAndPosition(UUID providerId, int position);
}
