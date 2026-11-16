package com.marketplace.console;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * B-15 (compliance plan C.5): the remote config's repository — the
 * point reads the service's request-time lookups resolve through.
 */
public interface RemoteConfigValueRepository extends JpaRepository<RemoteConfigValue, UUID> {

    /** The one live row for a key — the request-time read. */
    Optional<RemoteConfigValue> findByKey(String key);
}
