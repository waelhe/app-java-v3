package com.marketplace.provider;

public record ProviderFulfillmentLocationResponse(
        String address,
        double latitude,
        double longitude) {
    static ProviderFulfillmentLocationResponse from(ProviderProfile profile) {
        return new ProviderFulfillmentLocationResponse(profile.getFulfillmentAddress(),
                profile.getFulfillmentLatitude(), profile.getFulfillmentLongitude());
    }
}
