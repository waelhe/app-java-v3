package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * B-15 (compliance plan C.5): the flags' repository — the point reads
 * the service's request-time gates resolve through, riding the
 * BaseEntity {@code @SoftDelete} filter.
 */
public interface FeatureFlagRepository extends JpaRepository<FeatureFlag, UUID> {

    /** The one live row for a key — the request-time read. */
    Optional<FeatureFlag> findByKey(String key);
}
