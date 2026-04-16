package com.marketplace.shared.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * DIP interface for resolving provider display names.
 * Declared in shared.api so catalog can depend on the abstraction
 * without creating a circular dependency on identity.
 */
public interface ProviderNameResolver {

    /**
     * Batch-resolve display names for the given provider IDs.
     * Returns a map of providerId → displayName.
     * IDs not found are mapped to a fallback value.
     */
    Map<UUID, String> resolveNames(Set<UUID> providerIds);
}
