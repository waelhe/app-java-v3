package com.marketplace.provider.spi;

import com.marketplace.provider.ProviderRepository;
import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
public class ProviderLookupAdapter implements ProviderLookupPort {

    private final ProviderRepository providerRepository;

    public ProviderLookupAdapter(ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    @Override
    public Optional<ProviderSummary> findById(UUID providerId) {
        return providerRepository.findById(providerId)
                .map(p -> new ProviderSummary(p.getId(), p.getDisplayName(), p.getStatus().name()));
    }
}
