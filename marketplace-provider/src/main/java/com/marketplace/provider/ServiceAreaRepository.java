package com.marketplace.provider;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.history.RevisionRepository;

import java.util.List;
import java.util.UUID;

/**
 * W2 (yelp-level plan §5 — the business page): the declared service-areas
 * repository — the module's own house shape.
 */
public interface ServiceAreaRepository extends JpaRepository<ServiceArea, UUID>,
        JpaSpecificationExecutor<ServiceArea>,
        RevisionRepository<ServiceArea, UUID, Integer> {

    /**
     * The page's own read: one provider's declared areas. Natural id
     * order (the set is small and rendered as badges — the position key
     * the services list needs does not apply to a set of places).
     */
    List<ServiceArea> findByProviderIdOrderByIdAsc(UUID providerId);

    /**
     * The write surface's duplicate check — the unique key's read form
     * ({@code uq_service_areas_provider_location}).
     */
    boolean existsByProviderIdAndLocationId(UUID providerId, UUID locationId);
}
