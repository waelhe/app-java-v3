package com.marketplace.provider;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ProviderService {

    private final ProviderRepository providerRepository;
    private final CurrentUserProvider currentUserProvider;

    public ProviderService(ProviderRepository providerRepository,
                           CurrentUserProvider currentUserProvider) {
        this.providerRepository = providerRepository;
        this.currentUserProvider = currentUserProvider;
    }

    public ProviderProfile create(String displayName, String bio, UUID userId) {
        return providerRepository.save(ProviderProfile.create(displayName, bio, userId));
    }

    @Transactional(readOnly = true)
    public ProviderProfile getById(UUID id) {
        return providerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Provider not found: " + id));
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderProfile update(UUID id, String displayName, String bio, Authentication authentication) {
        ProviderProfile provider = getById(id);
        verifyOwnership(provider, authentication);
        provider.update(displayName, bio);
        return provider;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile verify(UUID id) {
        ProviderProfile provider = getById(id);
        provider.verify();
        return provider;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile suspend(UUID id) {
        ProviderProfile provider = getById(id);
        provider.suspend();
        return provider;
    }

    private void verifyOwnership(ProviderProfile provider, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!currentUserProvider.isAdmin(authentication)
                && (provider.getUserId() == null || !provider.getUserId().equals(currentUserId))) {
            throw new AccessDeniedException("You do not own this provider");
        }
    }
}
