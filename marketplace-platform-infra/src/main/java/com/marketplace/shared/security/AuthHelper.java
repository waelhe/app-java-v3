package com.marketplace.shared.security;

import com.marketplace.shared.api.ProviderLookupPort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("authHelper")
public class AuthHelper {

    private final CurrentUserProvider currentUserProvider;
    private final ProviderLookupPort providerLookupPort;

    public AuthHelper(CurrentUserProvider currentUserProvider,
                      ProviderLookupPort providerLookupPort) {
        this.currentUserProvider = currentUserProvider;
        this.providerLookupPort = providerLookupPort;
    }

    public boolean isCurrentUser(UUID userId, Authentication authentication) {
        return currentUserProvider.getCurrentUserId(authentication).equals(userId);
    }

    public boolean isAdmin(Authentication authentication) {
        return currentUserProvider.isAdmin(authentication);
    }

    public boolean ownsProvider(UUID providerId, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        return providerLookupPort.findById(providerId)
                .map(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .orElse(false);
    }
}
