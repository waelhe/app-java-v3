package com.marketplace.shared.api;

import java.util.UUID;

/** Verified provider pickup point used only for platform fulfillment, not public profile output. */
public record ProviderFulfillmentLocation(
        UUID providerUserId,
        String address,
        double latitude,
        double longitude) { }
