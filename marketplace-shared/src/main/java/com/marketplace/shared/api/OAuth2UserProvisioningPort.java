package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Port for provisioning users from external OAuth2 providers (Google, GitHub, Apple).
 * <p>Implemented by the Identity module, consumed by the infrastructure layer's
 * OAuth2 login success handler.
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/oauth2/login/overview.html">Spring Security OAuth2 Login</a>
 */
public interface OAuth2UserProvisioningPort {

    /**
     * Provisions a user from an external OAuth2 provider.
     * Creates a new user if not exists, updates if exists.
     *
     * @param provider     the OAuth2 provider name (e.g., "google", "github")
     * @param providerId   the user's ID at the provider
     * @param email        the user's email from the provider
     * @param displayName  the user's display name from the provider
     * @return the provisioned user's ID
     */
    UUID provisionUser(String provider, String providerId, String email, String displayName);
}
