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

    /**
     * Whether the authenticated user owns the provider record identified by the
     * given provider id.
     *
     * <p>The {@code providerId} universe is the {@code users.id} space — every
     * cross-module {@code provider_id} column (catalog, booking, reviews, media,
     * availability, ledger) carries a user id, not a {@code provider_profiles.id}
     * (A1). A client-supplied provider id therefore must resolve through the
     * user-owned "me" seam {@code findByUserId}, never {@code findById} (which
     * queries the unrelated {@code provider_profiles.id} space) — otherwise the
     * legitimate owner is always denied. Database truth: V2/V3/V6 reference
     * {@code users(id)}; Spring Data derived query: {@code findByUserId}.
     */
    public boolean ownsProvider(UUID providerId, Authentication authentication) {
        UUID currentUserId = currentUserProvider.getCurrentUserId(authentication);
        return providerLookupPort.findByUserId(providerId)
                .map(provider -> provider.userId() != null && provider.userId().equals(currentUserId))
                .orElse(false);
    }
}
