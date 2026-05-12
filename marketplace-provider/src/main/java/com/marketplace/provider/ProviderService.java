package com.marketplace.provider;

import com.marketplace.shared.api.ProviderLookupPort;
import com.marketplace.shared.api.ProviderSummary;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class ProviderService implements ProviderLookupPort {

    private final ProviderRepository providerRepository;
    private final CurrentUserProvider currentUserProvider;

    public ProviderService(ProviderRepository providerRepository, CurrentUserProvider currentUserProvider) {
        this.providerRepository = providerRepository;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional(readOnly = true)
    public ProviderProfile getById(UUID id) {
        return providerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Provider", id));
    }

    @Transactional(readOnly = true)
    public Page<ProviderProfile> list(Pageable pageable) {
        return providerRepository.findAll(pageable);
    }

    @PreAuthorize("hasRole('PROVIDER')")
    public ProviderProfile create(UUID userId, String displayName, String bio, String city, String country, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!currentUserId.equals(userId)) {
            throw new org.springframework.security.access.AccessDeniedException("Providers can only create their own profile");
        }
        return providerRepository.save(ProviderProfile.create(userId, displayName, bio, city, country));
    }

    @PreAuthorize("hasAnyRole('PROVIDER','ADMIN')")
    public ProviderProfile update(UUID id, String displayName, String bio, String city, String country, Authentication authentication) {
        ProviderProfile profile = getById(id);
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        if (!currentUserProvider.isAdmin(authentication) && !profile.getUserId().equals(currentUserId)) {
            throw new org.springframework.security.access.AccessDeniedException("Providers can only update their own profile");
        }
        profile.update(displayName, bio, city, country);
        return profile;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile verify(UUID id) {
        ProviderProfile profile = getById(id);
        profile.verify();
        return profile;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public ProviderProfile suspend(UUID id) {
        ProviderProfile profile = getById(id);
        profile.suspend();
        return profile;
    }

    @Override
    @Transactional(readOnly = true)
    public ProviderSummary getProvider(UUID providerId) {
        ProviderProfile profile = getById(providerId);
        return new ProviderSummary(profile.getId(), profile.getUserId(), profile.getDisplayName(), profile.getStatus().name(),
                profile.getCity(), profile.getCountry());
    }
}
