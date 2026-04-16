package com.marketplace.shared.api;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface ProviderNameResolver {

    Map<UUID, String> resolveNames(Set<UUID> providerIds);
}
