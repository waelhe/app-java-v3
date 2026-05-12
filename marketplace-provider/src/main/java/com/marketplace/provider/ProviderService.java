package com.marketplace.provider;

import com.marketplace.shared.api.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ProviderService {

    private final ProviderRepository providerRepository;

    public ProviderService(ProviderRepository providerRepository) {
        this.providerRepository = providerRepository;
    }

    public ProviderProfile create(String displayName, String bio) {
        return providerRepository.save(ProviderProfile.create(displayName, bio));
    }

    @Transactional(readOnly = true)
    public ProviderProfile getById(UUID id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + id));
    }

    public ProviderProfile update(UUID id, String displayName, String bio) {
        ProviderProfile provider = getById(id);
        provider.update(displayName, bio);
        return provider;
    }

    public ProviderProfile verify(UUID id) {
        ProviderProfile provider = getById(id);
        provider.verify();
        return provider;
    }

    public ProviderProfile suspend(UUID id) {
        ProviderProfile provider = getById(id);
        provider.suspend();
        return provider;
    }
}
