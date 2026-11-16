package com.marketplace.provider.spi;

import com.marketplace.provider.ProviderRepository;
import com.marketplace.provider.ProviderStatus;
import com.marketplace.shared.api.ProviderFulfillmentLocation;
import com.marketplace.shared.api.ProviderFulfillmentPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns the delivery-specific projection of a provider's private pickup point.
 * Kept separate from ProviderLookupAdapter so contexts that replace the general
 * provider lookup port cannot accidentally shadow the fulfillment port bean.
 */
@Component
@Transactional(readOnly = true)
public class ProviderFulfillmentAdapter implements ProviderFulfillmentPort {

    private final ProviderRepository providerRepository;

    public ProviderFulfillmentAdapter(ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    @Override
    public Optional<ProviderFulfillmentLocation> findVerifiedLocation(UUID providerUserId) {
        return providerRepository.findByUserId(providerUserId)
                .filter(profile -> profile.getStatus() == ProviderStatus.VERIFIED)
                .filter(profile -> profile.getFulfillmentAddress() != null
                        && profile.getFulfillmentLatitude() != null
                        && profile.getFulfillmentLongitude() != null)
                .map(profile -> new ProviderFulfillmentLocation(profile.getUserId(),
                        profile.getFulfillmentAddress(), profile.getFulfillmentLatitude(),
                        profile.getFulfillmentLongitude()));
    }
}
