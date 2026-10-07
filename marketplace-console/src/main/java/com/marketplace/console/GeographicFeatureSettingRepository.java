package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * B-18 (compliance plan C.10): the geographic feature settings'
 * repository — the two reads the resolution and the operator's surfaces
 * ride, both derived query methods (the Data JPA
 * {@code query-methods-details} reference's own channel) over the
 * BaseEntity {@code @SoftDelete} live-row filter.
 */
public interface GeographicFeatureSettingRepository extends JpaRepository<GeographicFeatureSetting, UUID> {

    /** The one live row for a (key, location) pair — the duplicate gate's read and the flip's lookup. */
    Optional<GeographicFeatureSetting> findByKeyAndLocationId(String key, UUID locationId);

    /**
     * Every live row for the key over the walked ancestor chain — the
     * resolution's ONE query ({@code In} the reference's own derived
     * form); the caller walks its own chain order to find the NEAREST
     * ancestor's row («الأخص يغلب الأعم»).
     */
    List<GeographicFeatureSetting> findByKeyAndLocationIdIn(String key, Collection<UUID> locationIds);

    /** The board's inventory — every live row in stable (key, location) order. */
    List<GeographicFeatureSetting> findAllByOrderByKeyAscLocationIdAsc();
}
