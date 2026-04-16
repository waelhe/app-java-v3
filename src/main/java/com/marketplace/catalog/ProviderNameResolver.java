package com.marketplace.catalog;

import java.util.UUID;

public interface ProviderNameResolver {

    String resolveProviderName(UUID providerId);
}
